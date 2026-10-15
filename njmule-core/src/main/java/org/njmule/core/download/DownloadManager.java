/*
 * njMule - Next generation Java Mule
 * SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See
 * the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with this program. If
 * not, see <https://www.gnu.org/licenses/>.
 */
package org.njmule.core.download;

import com.google.common.base.Ticker;
import com.google.common.net.InetAddresses;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.IntPredicate;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.njmule.core.api.Category;
import org.njmule.core.api.DownloadInfo;
import org.njmule.core.api.DownloadPriority;
import org.njmule.core.api.Ed2kLink;
import org.njmule.core.api.FileComment;
import org.njmule.core.api.Hash16;
import org.njmule.core.api.Hash20;
import org.njmule.core.api.PeerComment;
import org.njmule.core.api.SourceInfo;
import org.njmule.core.config.NextDownload;
import org.njmule.core.config.ObfuscationMode;
import org.njmule.core.engine.EngineLoop;
import org.njmule.core.files.CommentFilter;
import org.njmule.core.files.PartFile;
import org.njmule.core.files.hash.AichRecovery;
import org.njmule.core.files.hash.AichVotes;
import org.njmule.core.files.hash.Ed2k;
import org.njmule.core.peer.PeerLink;
import org.njmule.core.peer.PeerLinks;
import org.njmule.core.peer.PeerSoftware;
import org.njmule.core.protocol.ed2k.Hello;
import org.njmule.core.protocol.ed2k.ServerMessages;
import org.njmule.core.protocol.ed2k.SourceExchange;
import org.njmule.core.security.PathSanitizer;
import org.njmule.core.util.Ipv4;
import org.njmule.core.util.RateMeter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The downloads and their sources: sources come from links and from the server, each one is asked for a place in its
 * upload queue and asked again while it keeps us there, and data flows from those that give us a slot. A client that is
 * a source of several downloads is asked for one at a time, on its own clock; the others count it as asked for another
 * file (A4AF) until it is swapped to one of them. Engine thread only.
 */
public final class DownloadManager implements AutoCloseable {

    /** What the downloads report, on the engine thread. */
    public interface Listener {
        void added(DownloadInfo download);

        /** At most once a second per download. */
        void progress(DownloadInfo download);

        /** {@code part} has the hashes of the finished file, now at {@code file}. */
        void completed(DownloadInfo download, Path file, PartFile part);

        void failed(DownloadInfo download, String reason);

        void removed(Hash16 hash);

        /** The temp disk has dropped below, or come back above, the free space downloads need. */
        default void diskSpace(boolean enough) {}

        /**
         * The source at {@code ip} sent corrupt data: its sources were dropped, and it is not taken again for a while.
         */
        default void corruptSender(InetAddress ip) {}
    }

    /** The server connection, as far as the downloads use it. */
    public interface ServerLink {
        /** Asks the connected server for the sources of a file; {@code false} without one. */
        boolean askSources(Hash16 file, long size);

        /** Our client id on the server, zero when not connected. */
        long clientId();

        /** The server's address ({@code Ipv4} form), zero when not connected. */
        int serverIp();

        /** Asks the server to have the LowID client {@code lowId} connect to us. */
        boolean requestCallback(long lowId);
    }

    /** Kad, when it runs: finds sources of a file, and asks the firewalled ones to connect to us. */
    public interface KadSources {
        /** Starts a search; {@code false} when Kad cannot search for it now. */
        boolean find(Hash16 file, long size, Consumer<List<KadSource>> sources);

        /** Asks a firewalled source to connect to us for {@code file}; {@code false} when it cannot be asked now. */
        default boolean requestCallback(KadSource source, Hash16 file) {
            return false;
        }

        /** Our address ({@code Ipv4} form) where Kad found us reachable; 0 when unknown or firewalled. */
        default int publicIp() {
            return 0;
        }
    }

    /**
     * A source Kad found. A firewalled one cannot be connected to: it is asked to connect to us, through its buddy or
     * directly over UDP, as {@code callback} says.
     *
     * @param address where the source listens, as Kad saw it
     * @param cryptOptions the connect options it publishes
     */
    public record KadSource(
            InetSocketAddress address, Optional<Hash16> userHash, int cryptOptions, Optional<KadCallback> callback) {
        public KadSource {
            Objects.requireNonNull(address, "address");
            Objects.requireNonNull(userHash, "userHash");
            Objects.requireNonNull(callback, "callback");
        }

        /** A source reachable directly. */
        public static KadSource direct(InetSocketAddress address) {
            return new KadSource(address, Optional.empty(), 0, Optional.empty());
        }
    }

    /**
     * How a firewalled Kad source is asked to connect to us: through the buddy at {@code buddyIp:buddyPort} that knows
     * it by {@code buddyId}, or, with no buddy, by a datagram to its own Kad {@code udpPort}.
     */
    public record KadCallback(int buddyIp, int buddyPort, Optional<Hash16> buddyId, int udpPort) {
        public KadCallback {
            Objects.requireNonNull(buddyId, "buddyId");
        }

        public boolean viaBuddy() {
            return this.buddyId.isPresent();
        }
    }

    /**
     * What the downloads need from the preferences.
     *
     * @param minFreeSpace in bytes, 0 for none
     * @param filterLan whether sources at LAN addresses are left out
     * @param previewFirst whether every playable download fetches its first and last parts first
     * @param commentFilter words, apart by {@code |}, that leave the sources' comments holding them out
     * @param maxDownloadKiBps the download limit, 0 for none
     */
    public record Settings(
            ObfuscationMode obfuscation,
            int maxSourcesPerFile,
            int listenPort,
            long minFreeSpace,
            boolean filterLan,
            boolean previewFirst,
            String commentFilter,
            int maxDownloadKiBps) {

        public Settings(
                ObfuscationMode obfuscation,
                int maxSourcesPerFile,
                int listenPort,
                long minFreeSpace,
                boolean filterLan) {
            this(obfuscation, maxSourcesPerFile, listenPort, minFreeSpace, filterLan, false, "", 0);
        }
    }

    /** A source that keeps us in its queue is asked again this often. */
    public static final Duration REASK_INTERVAL = Duration.ofMinutes(29);

    /** Sources ban clients that ask more often than this. */
    public static final Duration MIN_REASK = Duration.ofMinutes(10);

    /**
     * A client asked for another download, or again before its time, waits at least this long since its last ask: a
     * margin over {@link #MIN_REASK}, which the source measures from when our request reached it.
     */
    public static final Duration EARLIEST_REASK = MIN_REASK.plusMinutes(1);

    /** A place in a source's queue up to this rank is kept: the client is not swapped to another download by itself. */
    public static final int GOOD_RANK = 50;

    /** The server is asked for the sources of each download this often. */
    public static final Duration SERVER_SOURCES_INTERVAL = Duration.ofMinutes(20);

    /** Source requests a server takes in a row; then it takes one more each {@link #SERVER_REQUEST_CREDIT}. */
    public static final int SERVER_REQUEST_BURST = 15;

    public static final Duration SERVER_REQUEST_CREDIT = Duration.ofSeconds(20);

    private static final Duration TICK = Duration.ofSeconds(1);
    // The download limit is shared out in tenths of a second.
    private static final Duration BUDGET_TICK = Duration.ofMillis(100);
    private static final int MAX_COMMENTS = 100;
    private static final int MAX_STARTING = 8;
    private static final int MAX_FAILURES = 3;
    private static final int RATE_WINDOW = 5;
    private static final int CRYPT_SUPPORTED = 0x01;
    // Supports, requests and requires: each flag counts only with the ones before it.
    private static final int CRYPT_REQUIRED = 0x07;
    // A source that told it has no part we need is asked for something else first this long.
    private static final Duration NO_PARTS_WAIT = Duration.ofMinutes(58);
    // A source swapped away from by hand waits this long before it is asked for that download again by itself.
    private static final Duration AWAY = Duration.ofHours(1);
    private static final Duration KAD_SEARCH_STEP = Duration.ofHours(1);
    private static final int KAD_SEARCH_MAX_STEPS = 7;
    private static final int KAD_SEARCHES = 5;
    private static final Duration SX_RARE_INTERVAL = Duration.ofMinutes(40);
    private static final Duration SX_INTERVAL = Duration.ofMinutes(160);
    private static final Duration SX_RARE_ANSWER_GAP = Duration.ofMinutes(5);
    private static final Duration SX_ANSWER_GAP = Duration.ofMinutes(20);
    private static final int SX_VERY_RARE = 10;
    private static final int SX_RARE = 50;
    private static final int SX_MAX_SOURCES = 750;
    // Host names in one link that we look up; a link may name thousands.
    private static final int MAX_LINK_HOSTS = 16;

    private static final Logger logger = LoggerFactory.getLogger(DownloadManager.class);

    // A player needs the header at the start of the file.
    private static final int PREVIEW_START = 16 * 1024;

    /** A source found sending corrupt data is not taken again for so long. */
    static final Duration CORRUPT_BAN = Duration.ofHours(2);

    /** How long a corrupt part waits for its AICH recovery data before it is downloaded again whole. */
    static final Duration RECOVERY_WAIT = Duration.ofSeconds(30);

    /** Firewalled sources asked for at once, and how long a callback is awaited. */
    static final int MAX_CALLBACKS = 32;

    static final Duration CALLBACK_WAIT = Duration.ofSeconds(60);

    /** A buddy or a firewalled source is asked for a callback this rarely at most: receivers drop more. */
    static final Duration KAD_CALLBACK_GAP = Duration.ofMinutes(3);

    private static final class Callback {
        private final Hash16 file;
        private final Optional<Hash16> userHash;
        private final int cryptOptions;
        // How a firewalled Kad source is asked; null for one asked through the server.
        private final KadSource kad;
        private long since;
        private boolean requested;

        Callback(Hash16 file, Optional<Hash16> userHash, int cryptOptions, KadSource kad, long since) {
            this.file = file;
            this.userHash = userHash;
            this.cryptOptions = cryptOptions;
            this.kad = kad;
            this.since = since;
        }

        // Where the request goes: the buddy, or the source itself.
        int target() {
            KadCallback route = this.kad.callback().orElseThrow();
            return route.viaBuddy()
                    ? route.buddyIp()
                    : Ipv4.fromBytes(this.kad.address().getAddress().getAddress());
        }
    }

    // A client known as a source of one or more downloads: one session at a time, for the source it is asked for, and
    // one clock for its asks whatever the number of downloads.
    private static final class Client {
        private final InetSocketAddress address;
        // Its sources, one per download, in the order they were found.
        private final List<Source> sources = new ArrayList<>();
        // The source it is asked for; the others are A4AF.
        private Source current;
        // The source the last file request went to: a slot the client offers later is for that file.
        private Source requested;
        private DownloadSession session;
        // The sources tried on the open session, each with nothing for us.
        private final Set<Source> tried = new HashSet<>();
        // Swapped by hand: asked next for the current source, whatever the others.
        private boolean pinned;
        private Optional<Hash16> userHash = Optional.empty();
        private int cryptOptions;
        // The id of a firewalled source on our server, reached only by callbacks; 0 otherwise.
        private long lowId;
        private long nextAsk;
        private long lastAsk;
        private boolean asked;
        private int failures;
        // How a source firewalled on Kad is asked to call us; null for any other.
        private KadSource kad;

        Client(InetSocketAddress address, long now) {
            this.address = address;
            this.nextAsk = now;
        }

        // The session, when it is for this source.
        DownloadSession session(Source source) {
            return this.current == source ? this.session : null;
        }
    }

    // A client as a source of one download.
    private static final class Source {
        private final Entry entry;
        private final Client client;
        private BitSet parts;
        // When the client last told the parts it has of the file.
        private long partsAt;
        // Our place in the client's queue for the file, as it last told; 0 for none.
        private int rank;
        private long sourcesAsked;
        private boolean sourcesEverAsked;
        private long received;
        // Swapped away from by hand: other downloads go first until then.
        private boolean away;
        private long awayUntil;

        Source(Entry entry, Client client) {
            this.entry = entry;
            this.client = client;
        }

        InetSocketAddress address() {
            return this.client.address;
        }
    }

    private static final class Entry {
        private final DownloadQueue.Download download;
        private final Map<InetSocketAddress, Source> sources = new LinkedHashMap<>();
        private final long[] window = new long[RATE_WINDOW];
        private int slot;
        private long received;
        private boolean changed;
        private long nextServerAsk;
        private long sourcesAnswered;
        private boolean sourcesEverAnswered;
        private long nextKadSearch;
        private int kadSearches;
        // Stopped by a scheduler rule: starting the next download leaves it alone until it is resumed.
        private boolean stoppedBySchedule;
        // What the sources said of the file, the latest of each.
        private final Map<InetSocketAddress, PeerComment> comments = new LinkedHashMap<>();
        // Corrupt parts waiting for a source able to send their AICH recovery data.
        private final BitSet recoveryWanted = new BitSet();
        // The parts whose recovery was tried, once each.
        private final BitSet recoveryTried = new BitSet();
        // The AICH hashes announced while the download has no trusted one.
        private AichVotes aichVotes = new AichVotes();

        Entry(DownloadQueue.Download download, long now) {
            this.download = download;
            this.nextServerAsk = now;
        }

        long rate() {
            long sum = 0;
            for (long bytes : this.window) {
                sum += bytes;
            }
            return sum / RATE_WINDOW;
        }
    }

    private final EngineLoop loop;
    private final Supplier<Hello> ourHello;
    private final Supplier<Settings> settings;
    private final ServerLink server;
    private final IntPredicate blocked;
    private final SecureRandom random;
    private final Ticker ticker;
    private final PeerLinks links;
    private final BiConsumer<Hash16, Long> credit;
    private final Supplier<Optional<KadSources>> kad;
    private final Listener listener;
    private final Supplier<List<Category>> categories;
    private int kadSearching;
    private final RateMeter rate = new RateMeter(5);
    private long nextKadStart;
    private final Map<Hash16, Entry> entries = new LinkedHashMap<>();
    // The clients that are sources of any download, by the address they are reached at.
    private final Map<InetSocketAddress, Client> clients = new LinkedHashMap<>();
    // Firewalled sources asked, through the server, to connect to us: by their LowID.
    private final Map<Long, Callback> callbacks = new LinkedHashMap<>();
    // Sources firewalled on Kad asked to connect to us, by their user hash, and when each buddy or source was asked.
    private final Map<Hash16, Callback> kadCallbacks = new LinkedHashMap<>();
    private final Map<Integer, Long> kadCallbackTargets = new HashMap<>();
    private final SessionEvents sessionEvents = new SessionEvents();
    private DownloadQueue queue;
    private ScheduledFuture<?> ticking;
    private ScheduledFuture<?> budgeting;
    // Bytes that may still be asked for under the download limit; below zero once more was asked.
    private long budget;
    // When the server will have credited back every source request sent.
    private long serverRequestsDue;
    // Sessions waiting for budget, first come first served.
    private final Set<DownloadSession> throttled = new LinkedHashSet<>();
    // Sources found sending corrupt data (IPv4, host order), until when they are not taken.
    private final Map<Integer, Long> corruptSenders = new HashMap<>();

    private DownloadManager(
            EngineLoop loop,
            Supplier<Hello> ourHello,
            Supplier<Settings> settings,
            ServerLink server,
            IntPredicate blocked,
            SecureRandom random,
            Ticker ticker,
            PeerLinks links,
            BiConsumer<Hash16, Long> credit,
            Supplier<Optional<KadSources>> kad,
            Supplier<List<Category>> categories,
            Listener listener) {
        this.loop = Objects.requireNonNull(loop, "loop");
        this.categories = Objects.requireNonNull(categories, "categories");
        this.ourHello = Objects.requireNonNull(ourHello, "ourHello");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.server = Objects.requireNonNull(server, "server");
        this.blocked = Objects.requireNonNull(blocked, "blocked");
        this.random = Objects.requireNonNull(random, "random");
        this.ticker = Objects.requireNonNull(ticker, "ticker");
        this.serverRequestsDue = ticker.read();
        this.links = Objects.requireNonNull(links, "links");
        this.credit = Objects.requireNonNull(credit, "credit");
        this.kad = Objects.requireNonNull(kad, "kad");
        this.listener = Objects.requireNonNull(listener, "listener");
    }

    /**
     * Loads the downloads of {@code tempDir} and starts asking for sources.
     *
     * @param blocked IP addresses (host order) that the IP filter refuses
     * @param credit credits a trusted source's user hash with the bytes it sent us
     * @param kad Kad, when it runs
     * @param categories the download categories, with their incoming folders and the names they take
     */
    public static DownloadManager open(
            EngineLoop loop,
            Path tempDir,
            Supplier<Path> incomingDir,
            Supplier<Hello> ourHello,
            Supplier<Settings> settings,
            ServerLink server,
            IntPredicate blocked,
            SecureRandom random,
            Ticker ticker,
            PeerLinks links,
            BiConsumer<Hash16, Long> credit,
            Supplier<Optional<KadSources>> kad,
            Supplier<List<Category>> categories,
            Listener listener)
            throws IOException {
        DownloadManager manager = new DownloadManager(
                loop, ourHello, settings, server, blocked, random, ticker, links, credit, kad, categories, listener);
        manager.queue = DownloadQueue.load(
                loop,
                tempDir,
                file -> manager.category(file.category())
                        .flatMap(Category::incoming)
                        .orElseGet(incomingDir),
                () -> settings.get().minFreeSpace(),
                manager.new QueueEvents());
        long now = ticker.read();
        for (DownloadQueue.Download download : manager.queue.all()) {
            manager.entries.put(download.hash(), new Entry(download, now));
        }
        manager.ticking = loop.scheduleRepeating("downloads", TICK, TICK, manager::tick);
        manager.budgeting = loop.scheduleRepeating("download budget", BUDGET_TICK, BUDGET_TICK, manager::refill);
        return manager;
    }

    /**
     * Adds a download, or the link's sources to the existing one of the same file. A new download goes in the first
     * category whose names it matches, with that category's priority.
     */
    public DownloadInfo add(Ed2kLink.FileLink link) throws IOException {
        Optional<DownloadQueue.Download> known = this.queue.get(link.hash());
        // A link of another file under the same hash would give sources that cannot serve this one.
        boolean same = known.map(d -> DownloadQueue.sameFile(d.file(), link)).orElse(true);
        int category = Category.NONE;
        DownloadPriority priority = DownloadPriority.AUTO;
        if (known.isEmpty()) {
            // Set before the download is announced, so the news of it tells them.
            String name = PathSanitizer.fileName(link.name());
            for (Category c : this.categories.get()) {
                if (c.matches(name)) {
                    category = c.id();
                    priority = c.priority();
                    break;
                }
            }
        }
        DownloadQueue.Download download = this.queue.add(link, category, priority);
        Entry entry = this.entries.computeIfAbsent(download.hash(), hash -> new Entry(download, this.ticker.read()));
        if (same) {
            addLinkSources(entry, link.sources());
        } else {
            logger.warn(
                    "A link of {} gives another size or AICH hash than the download: its sources are left out",
                    download.file().name());
        }
        return info(entry);
    }

    /** Takes over another client's download copied into {@link #tempDir}. */
    public DownloadInfo adopt(Path met, Path data) throws IOException {
        DownloadQueue.Download download = this.queue.adopt(met, data);
        return info(this.entries.get(download.hash()));
    }

    public Path tempDir() {
        return this.queue.tempDir();
    }

    /** Payload bytes per second received for all downloads. */
    public long rate() {
        return this.rate.rate(this.ticker.read());
    }

    /** Payload bytes received in this session. */
    public long downloaded() {
        return this.rate.total();
    }

    public List<DownloadInfo> downloads() {
        List<DownloadInfo> all = new ArrayList<>(this.entries.size());
        for (Entry entry : this.entries.values()) {
            all.add(info(entry));
        }
        return all;
    }

    /**
     * Sources of an active download that we spoke with, for other clients asking for sources: HighIDs with a known user
     * hash.
     */
    public List<SourceExchange.Source> exchangeable(Hash16 hash) {
        Entry entry = this.entries.get(hash);
        if (entry == null || !entry.download.isActive()) {
            return List.of();
        }
        List<SourceExchange.Source> sources = new ArrayList<>();
        for (Source source : entry.sources.values()) {
            Client client = source.client;
            // A firewalled source passed on by its address would be tried in vain.
            if (client.userHash.isPresent()
                    && source.parts != null
                    && client.lowId == 0
                    && client.kad == null
                    && client.address.getAddress() instanceof Inet4Address ip) {
                sources.add(new SourceExchange.Source(
                        Ipv4.fromBytes(ip.getAddress()),
                        0,
                        client.address.getPort(),
                        0,
                        0,
                        client.userHash,
                        client.cryptOptions));
            }
        }
        return sources;
    }

    /**
     * Whether a source at {@code ip} ({@code Ipv4} form) may be passed on to the client at {@code asker}: a public
     * address to anyone, a LAN address within the LAN or this machine, a loopback address within this machine.
     */
    public static boolean passesOn(int ip, InetAddress asker) {
        int first = ip >>> 24;
        if (first == 0 || first >= 224) {
            return false;
        }
        boolean loopback = first == 127;
        if (!loopback && !Ipv4.isLan(ip)) {
            return true;
        }
        if (!(asker instanceof Inet4Address four)) {
            return false;
        }
        int at = Ipv4.fromBytes(four.getAddress());
        boolean askerLoopback = at >>> 24 == 127;
        return loopback ? askerLoopback : askerLoopback || Ipv4.isLan(at);
    }

    /** Downloads, sources and sessions, for diagnostics. */
    public String describe() {
        StringBuilder text = new StringBuilder();
        for (Entry entry : this.entries.values()) {
            text.append(entry.download.file().name())
                    .append(" missing ")
                    .append(entry.download.file().missingBytes())
                    .append(':');
            for (Source source : entry.sources.values()) {
                DownloadSession session = source.client.session(source);
                text.append(' ')
                        .append(
                                session != null
                                        ? session.describe()
                                        : source.client.current == source
                                                ? source.address()
                                                : source.address() + " A4AF");
                if (source.client.failures > 0) {
                    text.append(" (failed ").append(source.client.failures).append(')');
                }
            }
            text.append('\n');
        }
        return text.toString();
    }

    /** Whether a client at {@code ip} is a source of one of our downloads. */
    public boolean hasSourceAt(InetAddress ip) {
        for (Entry entry : this.entries.values()) {
            for (InetSocketAddress address : entry.sources.keySet()) {
                if (address.getAddress().equals(ip)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** The download of {@code hash}, whose checked parts other clients may download. */
    public Optional<DownloadQueue.Download> find(Hash16 hash) {
        return Optional.ofNullable(this.entries.get(hash)).map(e -> e.download);
    }

    public Optional<DownloadInfo> download(Hash16 hash) {
        return Optional.ofNullable(this.entries.get(hash)).map(this::info);
    }

    public boolean pause(Hash16 hash) {
        Entry entry = this.entries.get(hash);
        if (entry == null) {
            return false;
        }
        this.queue.pause(entry.download);
        closeSessions(entry, "the download is paused");
        release(entry);
        this.listener.progress(info(entry));
        return true;
    }

    /** Pauses the download and forgets its sources: resumed, it looks for them again. */
    public boolean stop(Hash16 hash) {
        Entry entry = this.entries.get(hash);
        if (entry == null) {
            return false;
        }
        this.queue.stop(entry.download);
        closeSessions(entry, "the download is stopped");
        forget(entry);
        this.callbacks.values().removeIf(callback -> callback.file.equals(hash));
        this.kadCallbacks.values().removeIf(callback -> callback.file.equals(hash));
        this.listener.progress(info(entry));
        return true;
    }

    /** Stops the download as a scheduler rule asks, also one stopped already: no other leaving starts it again. */
    public boolean stopBySchedule(Hash16 hash) {
        Entry entry = this.entries.get(hash);
        if (entry == null) {
            return false;
        }
        entry.stoppedBySchedule = true;
        return entry.download.file().status() == PartFile.Status.STOPPED || stop(hash);
    }

    /** Puts the download in a category, with its priority; {@link Category#NONE} takes it out of any. */
    public boolean setCategory(Hash16 hash, int id) {
        Entry entry = this.entries.get(hash);
        if (entry == null || (id != Category.NONE && category(id).isEmpty())) {
            return false;
        }
        Optional<Category> category = category(id);
        if (category.isPresent()) {
            putIn(entry.download, category.get());
        } else {
            this.queue.setCategory(entry.download, Category.NONE);
        }
        this.listener.progress(info(entry));
        return true;
    }

    /** The categories changed: downloads in one that is gone have none. */
    public void categoriesChanged() {
        for (Entry entry : this.entries.values()) {
            int id = entry.download.file().category();
            if (id != Category.NONE && category(id).isEmpty()) {
                this.queue.setCategory(entry.download, Category.NONE);
                this.listener.progress(info(entry));
            }
        }
    }

    private void putIn(DownloadQueue.Download download, Category category) {
        this.queue.setCategory(download, category.id());
        if (category.priority() != DownloadPriority.AUTO) {
            this.queue.setPriority(download, category.priority());
        }
    }

    private Optional<Category> category(int id) {
        return this.categories.get().stream().filter(c -> c.id() == id).findFirst();
    }

    public boolean setPriority(Hash16 hash, DownloadPriority priority) {
        Entry entry = this.entries.get(hash);
        if (entry == null) {
            return false;
        }
        this.queue.setPriority(entry.download, priority);
        this.listener.progress(info(entry));
        return true;
    }

    /** Resumes a paused, stopped or failed download; {@code false} if unknown or running already. */
    public boolean resume(Hash16 hash) {
        Entry entry = this.entries.get(hash);
        // A running download keeps its timers: its sources and the server are not asked before their time.
        if (entry == null || !this.queue.resume(entry.download)) {
            return false;
        }
        entry.stoppedBySchedule = false;
        long now = this.ticker.read();
        entry.nextKadSearch = now;
        entry.kadSearches = 0;
        for (Source source : entry.sources.values()) {
            Client client = source.client;
            if (client.current == source) {
                client.nextAsk = earliestAsk(client, now);
            } else if (client.session == null && !client.current.entry.download.isActive()) {
                // Idle while the download it was asked for waits: asked for this one now.
                swap(client, source, now);
            }
        }
        entry.nextServerAsk = now;
        this.listener.progress(info(entry));
        return true;
    }

    /**
     * Resumes the paused or stopped download of highest priority, the first added among equals: one of {@code category}
     * first, unless {@code mode} takes any; returns it, if there was one. Downloads a scheduler rule stopped wait.
     */
    public Optional<DownloadInfo> resumeNext(int category, NextDownload mode) {
        Optional<Entry> next = Optional.empty();
        if (mode == NextDownload.SAME_CATEGORY_FIRST || mode == NextDownload.SAME_CATEGORY) {
            next = firstPaused(e -> e.download.file().category() == category);
        }
        if (next.isEmpty() && (mode == NextDownload.ANY || mode == NextDownload.SAME_CATEGORY_FIRST)) {
            next = firstPaused(e -> true);
        }
        next.ifPresent(e -> resume(e.download.hash()));
        return next.map(this::info);
    }

    private Optional<Entry> firstPaused(Predicate<Entry> wanted) {
        Entry best = null;
        for (Entry entry : this.entries.values()) {
            PartFile.Status status = entry.download.file().status();
            if ((status == PartFile.Status.PAUSED || status == PartFile.Status.STOPPED)
                    && !entry.stoppedBySchedule
                    && wanted.test(entry)
                    && (best == null || priority(entry).compareTo(priority(best)) > 0)) {
                best = entry;
            }
        }
        return Optional.ofNullable(best);
    }

    /** Stops the download and deletes its data; {@code false} if unknown or while its finished file is moved. */
    public boolean cancel(Hash16 hash) {
        Entry entry = this.entries.get(hash);
        if (entry == null || entry.download.isFinishing()) {
            return false;
        }
        this.entries.remove(hash);
        closeSessions(entry, "the download is cancelled");
        forget(entry);
        this.queue.cancel(entry.download);
        this.listener.removed(hash);
        return true;
    }

    /** A server connection was made: it is asked for the sources of every download. */
    public void serverConnected() {
        long now = this.ticker.read();
        this.serverRequestsDue = now;
        for (Entry entry : this.entries.values()) {
            entry.nextServerAsk = now;
        }
    }

    /**
     * Sources the server found. A LowID source is asked through the server to connect to us, which needs a HighID of
     * ours.
     */
    public void foundSources(ServerMessages.FoundSources found) {
        Entry entry = this.entries.get(found.file());
        if (entry == null || !entry.download.isActive()) {
            return;
        }
        long ourId = this.server.clientId();
        int added = 0;
        for (ServerMessages.Source source : found.sources()) {
            if (source.userHash().filter(this::isOurs).isPresent()) {
                continue;
            }
            if (source.isLowId()) {
                if (ourId >= ServerMessages.LOW_ID_LIMIT && source.clientId() != 0) {
                    askCallback(entry, source.clientId(), source.userHash(), source.cryptOptions());
                }
                continue;
            }
            int ip = Ipv4.fromEd2k(source.clientId());
            if (!usable(ip, source.port())) {
                continue;
            }
            Source fresh = addSource(entry, new InetSocketAddress(Ipv4.format(ip), source.port()));
            if (fresh != null) {
                learn(fresh.client, source.userHash(), source.cryptOptions());
                added++;
            }
        }
        logger.debug(
                "{} of {} sources from the server are new for {}",
                added,
                found.sources().size(),
                entry.download.file().name());
    }

    /** Ends every session and saves the downloads. */
    @Override
    public void close() {
        if (this.ticking != null) {
            this.ticking.cancel(false);
        }
        if (this.budgeting != null) {
            this.budgeting.cancel(false);
        }
        for (Entry entry : this.entries.values()) {
            closeSessions(entry, "shutting down");
        }
        this.queue.close();
    }

    // Queues a callback request; one goes out each tick, and those not answered soon are dropped.
    private void askCallback(Entry entry, long lowId, Optional<Hash16> userHash, int cryptOptions) {
        if (!suitsObfuscation(cryptOptions, userHash)
                || this.callbacks.size() >= MAX_CALLBACKS
                || this.callbacks.containsKey(lowId)
                || entry.sources.size() >= this.settings.get().maxSourcesPerFile()) {
            return;
        }
        this.callbacks.put(
                lowId, new Callback(entry.download.hash(), userHash, cryptOptions, null, this.ticker.read()));
    }

    // Queues a callback request for a source firewalled on Kad; ourselves, seen by Kad, are left out.
    private void askKadCallback(Entry entry, KadSource source) {
        Optional<Hash16> user = source.userHash();
        if (user.isEmpty()
                || user.get().equals(this.ourHello.get().userHash())
                || !suitsObfuscation(source.cryptOptions(), user)
                || this.callbacks.size() + this.kadCallbacks.size() >= MAX_CALLBACKS
                || this.kadCallbacks.containsKey(user.get())
                || entry.sources.size() >= this.settings.get().maxSourcesPerFile()) {
            return;
        }
        for (Source known : entry.sources.values()) {
            if (known.client.session != null && known.client.userHash.equals(user)) {
                return;
            }
        }
        this.kadCallbacks.put(
                user.get(),
                new Callback(entry.download.hash(), user, source.cryptOptions(), source, this.ticker.read()));
    }

    // A connection with the source, ours or its callback, needs obfuscation when either side requires it: with
    // obfuscation required the source must support it and its user hash, the key, be known.
    private boolean suitsObfuscation(int cryptOptions, Optional<Hash16> userHash) {
        return switch (this.settings.get().obfuscation()) {
            case REQUIRED -> userHash.isPresent() && (cryptOptions & CRYPT_SUPPORTED) != 0;
            case DISABLED -> (cryptOptions & CRYPT_REQUIRED) != CRYPT_REQUIRED;
            case SUPPORTED, REQUESTED -> true;
        };
    }

    private void sendCallbacks(long now) {
        boolean sent = false;
        for (var it = this.callbacks.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Long, Callback> e = it.next();
            Callback callback = e.getValue();
            if (now - callback.since >= CALLBACK_WAIT.toNanos()) {
                it.remove();
            } else if (!sent && !callback.requested) {
                sent = true;
                if (this.server.requestCallback(e.getKey())) {
                    callback.requested = true;
                    callback.since = now;
                } else {
                    it.remove();
                }
            }
        }
        sendKadCallbacks(now);
    }

    // One request a tick, each buddy or source asked once in a while: they drop requests that come too often.
    private void sendKadCallbacks(long now) {
        this.kadCallbackTargets.values().removeIf(at -> now - at >= KAD_CALLBACK_GAP.toNanos());
        Optional<KadSources> finder = this.kad.get();
        boolean sent = false;
        for (var it = this.kadCallbacks.values().iterator(); it.hasNext(); ) {
            Callback callback = it.next();
            if (now - callback.since >= CALLBACK_WAIT.toNanos()) {
                it.remove();
            } else if (!sent && !callback.requested && !this.kadCallbackTargets.containsKey(callback.target())) {
                sent = true;
                if (finder.isPresent() && finder.get().requestCallback(callback.kad, callback.file)) {
                    callback.requested = true;
                    callback.since = now;
                    this.kadCallbackTargets.put(callback.target(), now);
                } else {
                    it.remove();
                }
            }
        }
    }

    // A firewalled source we asked to call us, recognised by the LowID our server gave it or, from Kad, by its hash.
    private boolean adoptCallback(PeerLink link, Hello peer) {
        Callback callback = this.callbacks.get(peer.clientId());
        if (callback != null
                && callback.requested
                && peer.serverIp() != 0
                && peer.serverIp() == this.server.serverIp()
                && callback.userHash.map(peer.userHash()::equals).orElse(true)) {
            this.callbacks.remove(peer.clientId());
            return calledBack(link, peer, callback);
        }
        Callback kad = this.kadCallbacks.get(peer.userHash());
        if (kad != null && kad.requested) {
            this.kadCallbacks.remove(peer.userHash());
            return calledBack(link, peer, kad);
        }
        return false;
    }

    private boolean calledBack(PeerLink link, Hello peer, Callback callback) {
        Entry entry = this.entries.get(callback.file);
        if (entry == null || !entry.download.isActive()) {
            return false;
        }
        InetSocketAddress address = new InetSocketAddress(link.remoteAddress().getAddress(), peer.tcpPort());
        Source source = entry.sources.get(address);
        if (source == null) {
            source = addSource(entry, address);
            if (source == null) {
                return false;
            }
        }
        Client client = source.client;
        if (client.session != null) {
            return false;
        }
        long now = this.ticker.read();
        if (callback.kad != null) {
            client.lowId = 0;
            client.kad = followBuddy(callback.kad, peer);
        } else {
            client.lowId = peer.clientId();
        }
        client.userHash = Optional.of(peer.userHash());
        client.cryptOptions = callback.cryptOptions;
        client.asked = true;
        client.lastAsk = now;
        client.nextAsk = now + REASK_INTERVAL.toNanos();
        startAsking(client, source);
        DownloadSession session = new DownloadSession(
                this.loop,
                this.ticker,
                this.queue,
                entry.download,
                address,
                this.ourHello,
                this.links,
                this.sessionEvents);
        client.session = session;
        logger.debug("{} called us back for {}", address, entry.download.file().name());
        session.adopt(link, Optional.empty(), false);
        return true;
    }

    // A firewalled source names its current buddy in its hello: the next callback goes there.
    private static KadSource followBuddy(KadSource source, Hello peer) {
        Optional<KadCallback> route = source.callback();
        if (route.isEmpty() || !route.get().viaBuddy() || peer.buddyIp() == 0 || peer.buddyUdpPort() == 0) {
            return source;
        }
        KadCallback moved = new KadCallback(
                peer.buddyIp(),
                peer.buddyUdpPort(),
                route.get().buddyId(),
                route.get().udpPort());
        return new KadSource(source.address(), source.userHash(), source.cryptOptions(), Optional.of(moved));
    }

    /**
     * Takes the download side of a link a client opened when it is one of our sources, as a source does when it has a
     * slot for us or when we asked it through the server to connect; {@code false} if it is not.
     */
    public boolean adopt(PeerLink link) {
        Hello peer = link.peer().orElseThrow();
        if (isOurs(peer.userHash())) {
            return false;
        }
        if (adoptCallback(link, peer)) {
            return true;
        }
        InetAddress from = link.remoteAddress().getAddress();
        for (Client client : this.clients.values()) {
            boolean same = client.userHash.map(peer.userHash()::equals).orElse(false)
                    || (client.address.getAddress().equals(from) && client.address.getPort() == peer.tcpPort());
            if (!same || !client.asked) {
                continue;
            }
            // A slot is for the client, whatever download it was asked for: one that runs takes it.
            Source source = client.current.entry.download.isActive()
                    ? client.current
                    : best(client, this.ticker.read(), Set.of());
            if (source == null) {
                continue;
            }
            DownloadSession old = client.session;
            if (old != null && old.state() != DownloadSession.State.QUEUED) {
                return false;
            }
            if (old != null) {
                client.session = null;
                old.close("the source called back");
            }
            // The file it was asked for last needs no request again, another does.
            Optional<BitSet> known = client.requested == source ? Optional.ofNullable(source.parts) : Optional.empty();
            startAsking(client, source);
            DownloadSession session = new DownloadSession(
                    this.loop,
                    this.ticker,
                    this.queue,
                    source.entry.download,
                    client.address,
                    this.ourHello,
                    this.links,
                    this.sessionEvents);
            client.session = session;
            client.userHash = Optional.of(peer.userHash());
            logger.debug(
                    "{} called back for {}",
                    client.address,
                    source.entry.download.file().name());
            session.adopt(link, known, true);
            return true;
        }
        return false;
    }

    private void tick() {
        long now = this.ticker.read();
        int starting = 0;
        boolean preview = this.settings.get().previewFirst();
        for (Client client : List.copyOf(this.clients.values())) {
            if (client.session != null) {
                client.session.checkProgress(now);
            }
        }
        for (Entry entry : this.entries.values()) {
            entry.download.file().previewAlways(preview);
        }
        for (Client client : this.clients.values()) {
            if (client.session != null && isStarting(client.session)) {
                starting++;
            }
        }
        sendCallbacks(now);
        for (Entry entry : byPriority()) {
            entry.window[entry.slot] = entry.received;
            entry.slot = (entry.slot + 1) % RATE_WINDOW;
            if (entry.received > 0) {
                entry.changed = true;
            }
            entry.received = 0;
            if (entry.download.isActive()) {
                if (now - entry.nextServerAsk >= 0
                        && mayAskServer(now)
                        && this.server.askSources(
                                entry.download.hash(), entry.download.file().size())) {
                    this.serverRequestsDue =
                            now + Math.max(this.serverRequestsDue - now, 0) + SERVER_REQUEST_CREDIT.toNanos();
                    entry.nextServerAsk = now + SERVER_SOURCES_INTERVAL.toNanos();
                }
                starting += startSessions(entry, now, MAX_STARTING - starting);
                searchKad(entry, now);
            }
            if (entry.changed) {
                entry.changed = false;
                this.listener.progress(info(entry));
            }
        }
    }

    // Each tick adds a tenth of the limit, up to a second's worth; without a limit nothing is counted, so a limit set
    // later starts from nothing.
    private void refill() {
        int limit = this.settings.get().maxDownloadKiBps();
        if (limit <= 0) {
            this.budget = 0;
        } else {
            long second = limit * 1024L;
            this.budget = Math.min(this.budget + second / 10, second);
        }
        if (this.throttled.isEmpty() || (limit > 0 && this.budget <= 0)) {
            return;
        }
        List<DownloadSession> waiting = List.copyOf(this.throttled);
        this.throttled.clear();
        for (DownloadSession session : waiting) {
            if (limit > 0 && this.budget <= 0) {
                this.throttled.add(session);
            } else {
                session.wake();
            }
        }
    }

    // Under a limit, the blocks the budget covers, at least one while any budget is left.
    private int allowance(DownloadSession session, int wanted) {
        if (this.settings.get().maxDownloadKiBps() <= 0) {
            return wanted;
        }
        if (this.budget <= 0) {
            this.throttled.add(session);
            return 0;
        }
        return (int) Math.min(wanted, (this.budget + Ed2k.EMBLOCKSIZE - 1) / Ed2k.EMBLOCKSIZE);
    }

    // Servers credit each client with a source request every 20 seconds, up to 15 saved; requests beyond the credit are
    // ignored or get the client dropped.
    private boolean mayAskServer(long now) {
        return this.serverRequestsDue - now <= (SERVER_REQUEST_BURST - 1) * SERVER_REQUEST_CREDIT.toNanos();
    }

    // Higher priorities first: they ask the server, start sessions and search Kad before the others.
    private List<Entry> byPriority() {
        List<Entry> sorted = new ArrayList<>(this.entries.values());
        sorted.sort(Comparator.comparing(DownloadManager::priority).reversed());
        return sorted;
    }

    private static DownloadPriority priority(Entry entry) {
        return entry.download.file().priority().effective(entry.sources.size());
    }

    // A few Kad searches at a time, one new a second; a file waits longer after each search.
    private void searchKad(Entry entry, long now) {
        Optional<KadSources> finder = this.kad.get();
        if (finder.isEmpty()
                || this.kadSearching >= KAD_SEARCHES
                || now - this.nextKadStart < 0
                || now - entry.nextKadSearch < 0
                || entry.sources.size() >= this.settings.get().maxSourcesPerFile()) {
            return;
        }
        Hash16 hash = entry.download.hash();
        boolean started = finder.get().find(hash, entry.download.file().size(), found -> {
            Entry current = this.entries.get(hash);
            // A stopped download has forgotten its sources and takes none until it is resumed.
            if (current == null || current.download.file().status() == PartFile.Status.STOPPED) {
                return;
            }
            for (KadSource source : found) {
                if (source.callback().isPresent()) {
                    askKadCallback(current, source);
                    continue;
                }
                InetSocketAddress address = source.address();
                if (source.userHash().filter(this::isOurs).isEmpty()
                        && usable(Ipv4.fromBytes(address.getAddress().getAddress()), address.getPort())) {
                    Source fresh = addSource(current, address);
                    // A client known already keeps the user hash it told us itself.
                    if (fresh != null && source.userHash().isPresent() && fresh.client.userHash.isEmpty()) {
                        fresh.client.userHash = source.userHash();
                        fresh.client.cryptOptions = source.cryptOptions();
                    }
                }
            }
        });
        if (started) {
            this.kadSearching++;
            this.nextKadStart = now + 1_000_000_000L;
            entry.kadSearches = Math.min(entry.kadSearches + 1, KAD_SEARCH_MAX_STEPS);
            entry.nextKadSearch = now + KAD_SEARCH_STEP.toNanos() * entry.kadSearches;
            this.loop.schedule("Kad source search", Duration.ofSeconds(45), () -> this.kadSearching--);
        }
    }

    // The clients asked for the entry's download whose time has come are asked again, each for the download it is best
    // asked for now.
    private int startSessions(Entry entry, long now, int budget) {
        int started = 0;
        boolean disabled = this.settings.get().obfuscation() == ObfuscationMode.DISABLED;
        for (Source due : List.copyOf(entry.sources.values())) {
            if (started >= budget) {
                break;
            }
            Client client = due.client;
            if (client.current != due || client.session != null || now - client.nextAsk < 0) {
                continue;
            }
            if (!suitsObfuscation(client.cryptOptions, client.userHash)) {
                continue;
            }
            Source source = chooseToAsk(client, now);
            if (client.lowId != 0) {
                // Firewalled: asked again through the server, while it keeps the same LowID.
                makeCurrent(client, source);
                client.pinned = false;
                client.nextAsk = now + REASK_INTERVAL.toNanos();
                if (this.server.clientId() >= ServerMessages.LOW_ID_LIMIT) {
                    askCallback(source.entry, client.lowId, client.userHash, client.cryptOptions);
                }
                continue;
            }
            if (client.kad != null) {
                // Firewalled on Kad: asked again through its buddy, or directly.
                makeCurrent(client, source);
                client.pinned = false;
                client.nextAsk = now + REASK_INTERVAL.toNanos();
                askKadCallback(source.entry, client.kad);
                continue;
            }
            // Our connections share the limit with those of others: the sources wait while it is reached.
            if (!this.links.mayConnect() && this.links.find(client.address).isEmpty()) {
                break;
            }
            client.asked = true;
            client.lastAsk = now;
            client.nextAsk = now + REASK_INTERVAL.toNanos();
            startAsking(client, source);
            DownloadSession session = new DownloadSession(
                    this.loop,
                    this.ticker,
                    this.queue,
                    source.entry.download,
                    client.address,
                    this.ourHello,
                    this.links,
                    this.sessionEvents);
            client.session = session;
            // A client connected to download from us is asked over the same connection.
            Optional<PeerLink> open = this.links.find(client.address).filter(link -> !link.has(PeerLink.Side.DOWNLOAD));
            if (open.isPresent()) {
                session.adopt(open.get(), Optional.empty(), false);
                continue;
            }
            // Obfuscated only towards sources known to take it, which needs their user hash.
            boolean crypt = !disabled && (client.cryptOptions & CRYPT_SUPPORTED) != 0;
            session.connect(crypt ? client.userHash : Optional.empty(), this.random);
            started++;
        }
        return started;
    }

    // At its time the client is asked for the download it is best asked for: the one swapped to by hand, or the one
    // asked for while we keep a good place in its queue for it, otherwise the best of its sources.
    private Source chooseToAsk(Client client, long now) {
        Source current = client.current;
        if (client.pinned || holdsPlace(current)) {
            return current;
        }
        Source best = best(client, now, Set.of());
        if (best == null || best == current) {
            return current;
        }
        logger.debug(
                "{} is asked for {} rather than {}",
                client.address,
                best.entry.download.file().name(),
                current.entry.download.file().name());
        return best;
    }

    // The client is asked for the source's download now.
    private void startAsking(Client client, Source source) {
        makeCurrent(client, source);
        client.requested = source;
        client.tried.clear();
        client.tried.add(source);
        client.pinned = false;
    }

    private void makeCurrent(Client client, Source source) {
        if (client.current != source) {
            client.current.entry.changed = true;
            source.entry.changed = true;
            client.current = source;
        }
    }

    // A place in the client's queue that a swap could lose: a slot, or a good rank.
    private static boolean holdsPlace(Source source) {
        DownloadSession session = source.client.session(source);
        if (session != null && session.state() == DownloadSession.State.DOWNLOADING) {
            return true;
        }
        int rank = session != null && session.queueRank() > 0 ? session.queueRank() : source.rank;
        return rank > 0 && rank <= GOOD_RANK;
    }

    // The source of a running download the client is best asked for, of those not in skipped: one not swapped away
    // from by hand, one that may have parts we need, of a download that takes A4AF sources by itself, of a higher
    // priority, whose parts we know, the current one, the first found; null when no download of it runs.
    private Source best(Client client, long now, Set<Source> skipped) {
        Comparator<Source> order = Comparator.<Source, Boolean>comparing(s -> !isAway(s, now))
                .thenComparing(s -> !hasNothing(s, now))
                .thenComparing(s -> s.entry.download.file().autoSwap())
                .thenComparing(s -> priority(s.entry))
                .thenComparing(s -> s.parts != null)
                .thenComparing(s -> s == client.current);
        Source best = null;
        for (Source source : client.sources) {
            if (!skipped.contains(source)
                    && source.entry.download.isActive()
                    && (best == null || order.compare(source, best) > 0)) {
                best = source;
            }
        }
        return best;
    }

    private static boolean isAway(Source source, long now) {
        if (source.away && now - source.awayUntil >= 0) {
            source.away = false;
        }
        return source.away;
    }

    // The client told lately that it has no part of the file we need.
    private static boolean hasNothing(Source source, long now) {
        return source.parts != null
                && now - source.partsAt < NO_PARTS_WAIT.toNanos()
                && !needs(source.entry.download.file(), source.parts);
    }

    private static boolean needs(PartFile file, BitSet parts) {
        for (int part = parts.nextSetBit(0); part >= 0 && part < file.partCount(); part = parts.nextSetBit(part + 1)) {
            if (!file.isComplete(part)) {
                return true;
            }
        }
        return false;
    }

    // Asked for another download, at the client's time but no later than the shortest wait sources allow.
    private void swap(Client client, Source to, long now) {
        makeCurrent(client, to);
        client.pinned = false;
        long allowed = earliestAsk(client, now);
        if (client.nextAsk - allowed > 0) {
            client.nextAsk = allowed;
        }
    }

    // The soonest a client may be asked again, without being taken for one that asks too often.
    private static long earliestAsk(Client client, long now) {
        return client.asked ? Math.max(now, client.lastAsk + EARLIEST_REASK.toNanos()) : now;
    }

    // The download does not run any more: the clients asked for it are asked for another they are sources of.
    private void release(Entry entry) {
        long now = this.ticker.read();
        for (Source source : entry.sources.values()) {
            Client client = source.client;
            if (client.current != source || client.session != null) {
                continue;
            }
            Source next = best(client, now, Set.of());
            if (next != null) {
                swap(client, next, now);
            }
        }
    }

    // The download leaves for good, with its sources.
    private void forget(Entry entry) {
        long now = this.ticker.read();
        for (Source source : List.copyOf(entry.sources.values())) {
            detach(source, now);
        }
    }

    // Takes the source off its download and its client; a client asked for that download is asked for another.
    private void detach(Source source, long now) {
        Client client = source.client;
        source.entry.sources.remove(client.address);
        source.entry.changed = true;
        DownloadSession session = client.session(source);
        if (session != null) {
            client.session = null;
            session.close("the source is dropped");
        }
        client.sources.remove(source);
        client.tried.remove(source);
        if (client.requested == source) {
            client.requested = null;
        }
        if (client.sources.isEmpty()) {
            this.clients.remove(client.address);
            return;
        }
        if (client.current == source) {
            Source next = best(client, now, Set.of());
            client.current = client.sources.getFirst();
            swap(client, next != null ? next : client.current, now);
        }
    }

    // A client of no use any more, dropped from every download.
    private void drop(Client client) {
        long now = this.ticker.read();
        for (Source source : List.copyOf(client.sources)) {
            detach(source, now);
        }
    }

    // What the server or another client tells of a source; what the source showed us itself counts more.
    private static void learn(Client client, Optional<Hash16> userHash, int cryptOptions) {
        if (client.userHash.isEmpty() && userHash.isPresent()) {
            client.userHash = userHash;
            client.cryptOptions = cryptOptions;
        }
    }

    private static boolean isStarting(DownloadSession session) {
        return switch (session.state()) {
            case CONNECTING, GREETING, REQUESTING -> true;
            default -> false;
        };
    }

    // The user chose these sources, so a LAN or loopback address is fine; the IP filter still applies.
    private void addLinkSources(Entry entry, List<Ed2kLink.Source> sources) {
        List<Ed2kLink.Source> named = new ArrayList<>();
        for (Ed2kLink.Source source : sources) {
            if (!InetAddresses.isInetAddress(source.host())) {
                if (named.size() < MAX_LINK_HOSTS) {
                    named.add(source);
                }
            } else if (InetAddresses.forString(source.host()) instanceof Inet4Address address) {
                addLinkSource(entry, new InetSocketAddress(address, source.port()));
            }
        }
        if (named.isEmpty()) {
            return;
        }
        Hash16 hash = entry.download.hash();
        Thread.ofVirtual().name("njmule-resolve-sources").start(() -> {
            for (Ed2kLink.Source source : named) {
                try {
                    for (InetAddress address : InetAddress.getAllByName(source.host())) {
                        if (address instanceof Inet4Address) {
                            InetSocketAddress resolved = new InetSocketAddress(address, source.port());
                            this.loop.postOutcome(() -> Optional.ofNullable(this.entries.get(hash))
                                    .ifPresent(e -> addLinkSource(e, resolved)));
                            break;
                        }
                    }
                } catch (UnknownHostException e) {
                    logger.debug("Cannot resolve the source {}", source.host());
                }
            }
        });
    }

    // No client is at 0.x.x.x, at a multicast address or above.
    private void addLinkSource(Entry entry, InetSocketAddress address) {
        int ip = Ipv4.fromBytes(address.getAddress().getAddress());
        int first = ip >>> 24;
        if (address.getPort() != 0 && first != 0 && first < 224 && !this.blocked.test(ip)) {
            addSource(entry, address);
        }
    }

    /**
     * Adds a source the user typed, LAN addresses too, as long as the IP filter allows it; {@code false} for an unknown
     * download or a refused source.
     */
    public boolean addSourceByHand(Hash16 hash, InetSocketAddress address) {
        Entry entry = this.entries.get(hash);
        if (entry == null || address.getPort() == 0 || !(address.getAddress() instanceof Inet4Address)) {
            return false;
        }
        int before = entry.sources.size();
        addLinkSource(entry, address);
        if (entry.sources.size() > before) {
            this.listener.progress(info(entry));
            return true;
        }
        return false;
    }

    /**
     * Drops the sources the IP filter now refuses, ending their sessions; returns how many. LAN sources stay: some were
     * given by the user.
     */
    public int dropBlockedSources() {
        return dropSources(this.blocked, "the IP filter blocks it");
    }

    /**
     * Drops the sources at {@code ip}, which sent corrupt data, and keeps them out while {@link #CORRUPT_BAN} lasts.
     */
    void banCorruptSender(int ip) {
        logger.info("{} sent corrupt data: its sources are dropped", Ipv4.format(ip));
        this.corruptSenders.put(ip, this.ticker.read() + CORRUPT_BAN.toNanos());
        dropSources(i -> i == ip, "it sent corrupt data");
        this.listener.corruptSender(InetAddresses.fromInteger(ip));
    }

    private boolean isCorruptSender(int ip) {
        Long until = this.corruptSenders.get(ip);
        if (until != null && this.ticker.read() - until >= 0) {
            this.corruptSenders.remove(ip);
            return false;
        }
        return until != null;
    }

    private int dropSources(IntPredicate refused, String reason) {
        int dropped = 0;
        for (Client client : List.copyOf(this.clients.values())) {
            if (!refused.test(Ipv4.fromBytes(client.address.getAddress().getAddress()))) {
                continue;
            }
            dropped += client.sources.size();
            DownloadSession session = client.session;
            Entry released = session == null ? null : client.current.entry;
            if (session != null) {
                client.session = null;
                session.close(reason);
            }
            drop(client);
            // The blocks the dropped session held may come from the sources left.
            if (released != null) {
                wakeSessions(released);
            }
        }
        return dropped;
    }

    private boolean isFilteredLan(int ip) {
        return this.settings.get().filterLan() && Ipv4.isLan(ip);
    }

    // A source the network told us about: a public-looking address the IP filter allows, not ourselves.
    private boolean usable(int ip, int port) {
        return port != 0
                && Ipv4.isUnicastPublicCandidate(ip)
                && !this.blocked.test(ip)
                && !isCorruptSender(ip)
                && !isFilteredLan(ip)
                && !isOurAddress(ip, port);
    }

    // Our port at our address as the server, with our HighID, or Kad, finding us reachable, sees it.
    private boolean isOurAddress(int ip, int port) {
        return port == this.settings.get().listenPort()
                && (Ipv4.toEd2k(ip) == this.server.clientId()
                        || ip == this.kad.get().map(KadSources::publicIp).orElse(0));
    }

    private boolean isOurs(Hash16 userHash) {
        return userHash.equals(this.ourHello.get().userHash());
    }

    // Returns the new source, or null if it was known or the list is full. A client known already as a source of
    // another download keeps its clock: it counts as asked for another file, unless that download waits.
    private Source addSource(Entry entry, InetSocketAddress address) {
        if (entry.sources.containsKey(address)
                || entry.sources.size() >= this.settings.get().maxSourcesPerFile()) {
            return null;
        }
        long now = this.ticker.read();
        Client client = this.clients.computeIfAbsent(address, a -> new Client(a, now));
        Source source = new Source(entry, client);
        entry.sources.put(address, source);
        client.sources.add(source);
        if (client.current == null) {
            client.current = source;
        } else if (client.session == null && !client.current.entry.download.isActive() && entry.download.isActive()) {
            swap(client, source, now);
        }
        entry.changed = true;
        return source;
    }

    // Ends the sessions of the entry's download; the clients stay its sources.
    private void closeSessions(Entry entry, String reason) {
        for (Source source : List.copyOf(entry.sources.values())) {
            DownloadSession session = source.client.session(source);
            if (session != null) {
                session.close(reason);
            }
        }
    }

    /** The sources of every download, as the list of downloading clients shows them. */
    public List<SourceInfo> sources() {
        List<SourceInfo> all = new ArrayList<>();
        for (Entry entry : this.entries.values()) {
            PartFile file = entry.download.file();
            for (Source source : entry.sources.values()) {
                Client client = source.client;
                DownloadSession session = client.session(source);
                // The client is the same whatever download it is asked for.
                Optional<Hello> hello = client.session == null ? Optional.empty() : client.session.peer();
                BitSet parts = session != null && session.sourceParts().isPresent()
                        ? session.sourceParts().get()
                        : source.parts;
                boolean a4af = client.current != source;
                SourceInfo.State state;
                if (a4af) {
                    state = SourceInfo.State.A4AF;
                } else {
                    state = session == null ? SourceInfo.State.WAITING : state(session.state());
                }
                PartFile askedFor = client.current.entry.download.file();
                all.add(new SourceInfo(
                        file.hash(),
                        file.name(),
                        client.address.getAddress().getHostAddress() + ":" + client.address.getPort(),
                        hello.flatMap(Hello::nick),
                        hello.map(PeerSoftware::describe).orElse(""),
                        state,
                        a4af ? 0 : session != null ? session.queueRank() : source.rank,
                        source.received,
                        parts == null ? 0 : parts.cardinality(),
                        file.partCount(),
                        a4af ? Optional.of(askedFor.hash()) : Optional.empty(),
                        a4af ? askedFor.name() : ""));
            }
        }
        return all;
    }

    private static SourceInfo.State state(DownloadSession.State state) {
        return switch (state) {
            case CONNECTING -> SourceInfo.State.CONNECTING;
            case GREETING, REQUESTING -> SourceInfo.State.ASKING;
            case QUEUED -> SourceInfo.State.QUEUED;
            case DOWNLOADING -> SourceInfo.State.DOWNLOADING;
            case ENDED -> SourceInfo.State.WAITING;
        };
    }

    // A corrupt part with a trusted AICH master hash: one source able to is asked for the recovery data.
    private void recover(DownloadQueue.Download download, int part) {
        PartFile file = download.file();
        Entry entry = this.entries.get(download.hash());
        // Once only: a part that fails again after its recovery is downloaded again whole.
        if (entry == null
                || entry.download != download
                || file.aichHash().isEmpty()
                || !AichRecovery.hasRecovery(file.size(), part)
                || entry.recoveryTried.get(part)) {
            return;
        }
        entry.recoveryTried.set(part);
        // The part waits a little for the recovery data rather than come again whole.
        file.hold(part);
        this.loop.schedule("AICH recovery wait", RECOVERY_WAIT, () -> unhold(download, part));
        for (Source source : entry.sources.values()) {
            DownloadSession session = source.client.session(source);
            if (session != null && session.mayRecover(part)) {
                logger.info("Asking {} for AICH recovery data of part {} of {}", source.address(), part, file.name());
                session.requestAich(part, file.aichHash().get());
                return;
            }
        }
        // The next source able to is asked when it says which parts it has.
        entry.recoveryWanted.set(part);
    }

    private void aichAnnounced(DownloadSession session, Hash20 master) {
        DownloadQueue.Download download = session.download();
        PartFile file = download.file();
        Entry entry = this.entries.get(download.hash());
        if (entry == null || file.aichHash().isPresent()) {
            return;
        }
        int ip = Ipv4.fromBytes(session.address().getAddress().getAddress());
        Optional<Hash20> trusted = entry.aichVotes.announced(ip, master);
        if (trusted.isPresent()) {
            entry.aichVotes = new AichVotes();
            file.trustAich(trusted.get());
            logger.info("The sources agree on the AICH hash of {}", file.name());
        }
    }

    private void mayRecoverFrom(DownloadSession session) {
        DownloadQueue.Download download = session.download();
        Entry entry = this.entries.get(download.hash());
        Optional<Hash20> master = download.file().aichHash();
        if (entry == null || master.isEmpty()) {
            return;
        }
        BitSet wanted = entry.recoveryWanted;
        wanted.andNot(download.verifiedParts());
        for (int part = wanted.nextSetBit(0); part >= 0; part = wanted.nextSetBit(part + 1)) {
            if (download.file().isComplete(part)) {
                wanted.clear(part);
            } else if (session.mayRecover(part)) {
                wanted.clear(part);
                logger.info(
                        "Asking {} for AICH recovery data of part {} of {}",
                        session.address(),
                        part,
                        download.file().name());
                session.requestAich(part, master.get());
                break;
            }
        }
    }

    private void aichAnswered(DownloadSession session, int part, Hash20 master, byte[] data) {
        DownloadQueue.Download download = session.download();
        PartFile file = download.file();
        if (file.aichHash().filter(master::equals).isEmpty()) {
            return;
        }
        Optional<List<Hash20>> blocks = AichRecovery.verify(file.size(), part, master, data);
        if (blocks.isEmpty()) {
            unhold(download, part);
            logger.info(
                    "AICH recovery data of part {} of {} from {} proves nothing", part, file.name(), session.address());
            return;
        }
        this.queue.recovered(download, part, blocks.get());
    }

    // No recovery data will come: the part is downloaded again whole, by the sessions idle now too.
    private void unhold(DownloadQueue.Download download, int part) {
        download.file().unhold(part);
        Entry entry = this.entries.get(download.hash());
        if (entry != null && entry.download == download) {
            wakeSessions(entry);
        }
    }

    // Blocks were let go or a part may be downloaded again: a session with nothing to ask for asks now.
    private void wakeSessions(Entry entry) {
        for (Source source : List.copyOf(entry.sources.values())) {
            DownloadSession session = source.client.session(source);
            if (session != null) {
                session.wake();
            }
        }
    }

    private DownloadInfo info(Entry entry) {
        PartFile file = entry.download.file();
        int transferring = 0;
        for (Source source : entry.sources.values()) {
            DownloadSession session = source.client.session(source);
            if (session != null && session.state() == DownloadSession.State.DOWNLOADING) {
                transferring++;
            }
        }
        return new DownloadInfo(
                file.hash(),
                file.name(),
                file.size(),
                file.completedBytes(),
                file.status() == PartFile.Status.DOWNLOADING && this.queue.isSpaceShort()
                        ? DownloadInfo.Status.NO_SPACE
                        : status(file.status()),
                file.priority(),
                file.category(),
                entry.sources.size(),
                transferring,
                entry.rate(),
                file.map(),
                file.previewFirst(),
                file.autoSwap());
    }

    /** Fetches the first and last parts of the download first, or stops doing so; {@code false} if unknown. */
    public boolean setPreviewFirst(Hash16 hash, boolean value) {
        Entry entry = this.entries.get(hash);
        if (entry == null) {
            return false;
        }
        this.queue.setPreviewFirst(entry.download, value);
        this.listener.progress(info(entry));
        return true;
    }

    /** What the sources of the download said of the file. */
    public List<PeerComment> comments(Hash16 hash) {
        Entry entry = this.entries.get(hash);
        return entry == null ? List.of() : List.copyOf(entry.comments.values());
    }

    // A comment holding a word of the filter is left out, as spam; a source keeps its latest one.
    private void commented(DownloadSession session, FileComment comment) {
        Entry entry = this.entries.get(session.download().hash());
        if (entry == null || comment.isEmpty()) {
            return;
        }
        if (CommentFilter.of(this.settings.get().commentFilter()).leavesOut(comment.text())) {
            return;
        }
        String user = session.peer()
                .flatMap(Hello::nick)
                .orElse(session.address().getAddress().getHostAddress());
        entry.comments.remove(session.address());
        entry.comments.put(
                session.address(), new PeerComment(user, entry.download.file().name(), comment));
        while (entry.comments.size() > MAX_COMMENTS) {
            entry.comments.remove(entry.comments.keySet().iterator().next());
        }
    }

    /** The data file of the download, which a player can show while it grows once the start of it has come. */
    public Optional<Path> dataFile(Hash16 hash) {
        return Optional.ofNullable(this.entries.get(hash))
                .map(e -> e.download.file())
                .filter(file -> file.isComplete(0, Math.min(file.size(), PREVIEW_START)))
                .map(PartFile::dataFile);
    }

    private static DownloadInfo.Status status(PartFile.Status status) {
        return switch (status) {
            case DOWNLOADING -> DownloadInfo.Status.DOWNLOADING;
            case PAUSED -> DownloadInfo.Status.PAUSED;
            case STOPPED -> DownloadInfo.Status.STOPPED;
            case ERROR -> DownloadInfo.Status.ERROR;
            case COMPLETE -> DownloadInfo.Status.COMPLETE;
        };
    }

    // Rare files may ask a source every 40 minutes, others every 160; a file waits for answers.
    private boolean maySourcesBeAsked(DownloadSession session) {
        Entry entry = this.entries.get(session.download().hash());
        Source source = entry == null ? null : entry.sources.get(session.address());
        if (source == null) {
            return false;
        }
        int known = entry.sources.size();
        int max = Math.min(this.settings.get().maxSourcesPerFile() * 9 / 10, SX_MAX_SOURCES);
        if (known >= max) {
            return false;
        }
        long now = this.ticker.read();
        boolean rareDue = !source.sourcesEverAsked || now - source.sourcesAsked >= SX_RARE_INTERVAL.toNanos();
        boolean commonDue = !source.sourcesEverAsked || now - source.sourcesAsked >= SX_INTERVAL.toNanos();
        long sinceAnswer = entry.sourcesEverAnswered ? now - entry.sourcesAnswered : Long.MAX_VALUE;
        boolean allowed = (known <= SX_VERY_RARE && rareDue)
                || (known <= SX_RARE && rareDue && sinceAnswer >= SX_RARE_ANSWER_GAP.toNanos())
                || (commonDue && sinceAnswer >= SX_ANSWER_GAP.toNanos());
        if (allowed) {
            source.sourcesEverAsked = true;
            source.sourcesAsked = now;
        }
        return allowed;
    }

    private void sourcesFound(DownloadSession session, SourceExchange.Answer answer) {
        Entry entry = this.entries.get(answer.file());
        if (entry == null || !entry.download.isActive()) {
            return;
        }
        entry.sourcesEverAnswered = true;
        entry.sourcesAnswered = this.ticker.read();
        int added = 0;
        for (SourceExchange.Source s : answer.sources()) {
            // A LowID would need a callback through its server; ours may differ.
            if (s.isLowId()
                    || !usable(s.ip(), s.port())
                    || s.userHash().filter(this::isOurs).isPresent()) {
                continue;
            }
            Source fresh = addSource(entry, new InetSocketAddress(Ipv4.format(s.ip()), s.port()));
            if (fresh != null) {
                learn(fresh.client, s.userHash().filter(h -> !h.equals(Hash16.ZERO)), s.cryptOptions());
                added++;
            }
        }
        logger.debug(
                "{} of {} sources from {} are new for {}",
                added,
                answer.sources().size(),
                session.address(),
                entry.download.file().name());
    }

    // The client has nothing for the download the session asks for: the next of its downloads that may have something,
    // asked for on the same connection; a download it lacks loses it as a source.
    private Optional<DownloadQueue.Download> another(DownloadSession session, boolean hasFile) {
        Client client = this.clients.get(session.address());
        if (client == null || client.session != session) {
            return Optional.empty();
        }
        long now = this.ticker.read();
        Source from = client.current;
        session.sourceParts().ifPresent(parts -> {
            from.parts = parts;
            from.partsAt = now;
        });
        client.tried.add(from);
        Source next = best(client, now, client.tried);
        if (next == null || hasNothing(next, now) || isAway(next, now)) {
            return Optional.empty();
        }
        makeCurrent(client, next);
        client.requested = next;
        client.tried.add(next);
        if (!hasFile) {
            detach(from, now);
        }
        return Optional.of(next.entry.download);
    }

    /**
     * Swaps the A4AF sources of the download to it: each client is asked for it next, unless it sends us data or is
     * being asked now; returns how many were swapped.
     */
    public int swapSourcesHere(Hash16 hash) {
        Entry entry = this.entries.get(hash);
        if (entry == null || !entry.download.isActive()) {
            return 0;
        }
        long now = this.ticker.read();
        int swapped = 0;
        for (Source source : List.copyOf(entry.sources.values())) {
            if (source.client.current != source && swapByHand(source, now)) {
                swapped++;
            }
        }
        return swapped;
    }

    /**
     * Swaps the clients asked for the download to another download they are sources of, which goes first for a while;
     * returns how many were swapped.
     */
    public int swapSourcesAway(Hash16 hash) {
        Entry entry = this.entries.get(hash);
        if (entry == null) {
            return 0;
        }
        long now = this.ticker.read();
        int swapped = 0;
        for (Source source : List.copyOf(entry.sources.values())) {
            if (source.client.current != source) {
                continue;
            }
            Source next = best(source.client, now, Set.of(source));
            if (next != null && swapByHand(next, now)) {
                source.away = true;
                source.awayUntil = now + AWAY.toNanos();
                swapped++;
            }
        }
        return swapped;
    }

    /**
     * Swaps the client at {@code address}, an A4AF source of the download, to it; {@code false} if it is none, the
     * download does not run or the client cannot move now.
     */
    public boolean swapSource(Hash16 hash, InetSocketAddress address) {
        Entry entry = this.entries.get(hash);
        Source source = entry == null ? null : entry.sources.get(address);
        return source != null
                && entry.download.isActive()
                && source.client.current != source
                && swapByHand(source, this.ticker.read());
    }

    /**
     * Has the A4AF sources of the download swapped to it whenever they are free to move, or stops; kept with the
     * download. {@code false} if unknown.
     */
    public boolean setAutoSwap(Hash16 hash, boolean value) {
        Entry entry = this.entries.get(hash);
        if (entry == null) {
            return false;
        }
        this.queue.setAutoSwap(entry.download, value);
        if (value && entry.download.isActive()) {
            // Those free now move at once, keeping their clock.
            long now = this.ticker.read();
            for (Source source : entry.sources.values()) {
                Client client = source.client;
                if (client.current != source
                        && client.session == null
                        && !client.pinned
                        && !holdsPlace(client.current)
                        && best(client, now, Set.of()) == source) {
                    makeCurrent(client, source);
                }
            }
        }
        this.listener.progress(info(entry));
        return true;
    }

    // By hand a client waiting in a queue moves too; one being asked or sending data does not.
    private boolean swapByHand(Source to, long now) {
        Client client = to.client;
        DownloadSession session = client.session;
        if (session != null) {
            if (session.state() != DownloadSession.State.QUEUED) {
                return false;
            }
            session.close("swapped to another download");
        }
        to.away = false;
        swap(client, to, now);
        client.pinned = true;
        logger.debug(
                "{} swapped by hand to {}",
                client.address,
                to.entry.download.file().name());
        return true;
    }

    private final class SessionEvents implements DownloadSession.Listener {
        @Override
        public boolean maySourcesBeAsked(DownloadSession session) {
            return DownloadManager.this.maySourcesBeAsked(session);
        }

        @Override
        public void sourcesFound(DownloadSession session, SourceExchange.Answer answer) {
            DownloadManager.this.sourcesFound(session, answer);
        }

        @Override
        public void aichAnswered(DownloadSession session, int part, Hash20 master, byte[] data) {
            DownloadManager.this.aichAnswered(session, part, master, data);
        }

        @Override
        public int allowance(DownloadSession session, int blocks) {
            return DownloadManager.this.allowance(session, blocks);
        }

        @Override
        public void requested(DownloadSession session, long bytes) {
            if (DownloadManager.this.settings.get().maxDownloadKiBps() > 0) {
                DownloadManager.this.budget -= bytes;
            }
        }

        @Override
        public void aichUnavailable(DownloadSession session, int part) {
            DownloadManager.this.unhold(session.download(), part);
        }

        @Override
        public void released(DownloadSession session) {
            Entry entry = DownloadManager.this.entries.get(session.download().hash());
            if (entry != null) {
                DownloadManager.this.wakeSessions(entry);
            }
        }

        @Override
        public void partsKnown(DownloadSession session) {
            DownloadManager.this.mayRecoverFrom(session);
        }

        @Override
        public void aichAnnounced(DownloadSession session, Hash20 master) {
            DownloadManager.this.aichAnnounced(session, master);
        }

        @Override
        public void commented(DownloadSession session, FileComment comment) {
            DownloadManager.this.commented(session, comment);
        }

        @Override
        public Optional<DownloadQueue.Download> another(DownloadSession session, boolean hasFile) {
            return DownloadManager.this.another(session, hasFile);
        }

        @Override
        public void received(DownloadSession session, int bytes, int transferred) {
            Entry entry = DownloadManager.this.entries.get(session.download().hash());
            if (entry != null) {
                entry.received += bytes;
                Source source = entry.sources.get(session.address());
                if (source != null) {
                    source.received += bytes;
                }
            }
            DownloadManager.this.rate.add(bytes, DownloadManager.this.ticker.read());
            if (session.trusted() && session.peerHash().isPresent()) {
                DownloadManager.this.credit.accept(session.peerHash().get(), (long) transferred);
            }
        }

        @Override
        public void ended(DownloadSession session, DownloadSession.Outcome outcome, String reason) {
            // The download may have left already, its client not.
            Client client = DownloadManager.this.clients.get(session.address());
            if (client == null || client.session != session) {
                return;
            }
            client.session = null;
            Source source = client.current;
            Entry entry = source.entry;
            long now = DownloadManager.this.ticker.read();
            if (session.peerHash().filter(DownloadManager.this::isOurs).isPresent()) {
                // We reached ourselves: the address is no source of any download.
                logger.debug("{} is ourselves", client.address);
                DownloadManager.this.drop(client);
                return;
            }
            session.sourceParts().ifPresent(parts -> {
                source.parts = parts;
                source.partsAt = now;
            });
            if (session.uploadAsked()) {
                source.rank = session.queueRank();
            }
            session.peerHash().ifPresent(hash -> client.userHash = Optional.of(hash));
            if (session.peerHash().isPresent()) {
                client.cryptOptions = session.peerCryptOptions();
            }
            logger.debug("{} ({}): {}", client.address, outcome, reason);
            switch (outcome) {
                case QUEUED, LATER -> {
                    client.failures = 0;
                    client.nextAsk = client.lastAsk + REASK_INTERVAL.toNanos();
                }
                case UNREACHABLE -> {
                    client.failures++;
                    client.nextAsk = now + MIN_REASK.toNanos();
                    if (client.failures >= MAX_FAILURES) {
                        DownloadManager.this.drop(client);
                    }
                }
                case GONE -> {
                    client.nextAsk = client.lastAsk + REASK_INTERVAL.toNanos();
                    DownloadManager.this.detach(source, now);
                }
            }
            entry.changed = true;
            // The blocks it held may come from another source with a slot.
            if (entry.download.isActive() && DownloadManager.this.entries.get(entry.download.hash()) == entry) {
                DownloadManager.this.wakeSessions(entry);
            }
        }
    }

    private final class QueueEvents implements DownloadQueue.Listener {
        @Override
        public void added(DownloadQueue.Download download) {
            Entry entry = new Entry(download, DownloadManager.this.ticker.read());
            DownloadManager.this.entries.put(download.hash(), entry);
            DownloadManager.this.listener.added(DownloadManager.this.info(entry));
        }

        @Override
        public void corrupt(DownloadQueue.Download download, int part) {
            DownloadManager.this.recover(download, part);
        }

        @Override
        public void corruptSenders(DownloadQueue.Download download, Set<Integer> ips) {
            for (int ip : ips) {
                DownloadManager.this.banCorruptSender(ip);
            }
        }

        @Override
        public void progress(DownloadQueue.Download download) {
            Entry entry = DownloadManager.this.entries.get(download.hash());
            if (entry == null) {
                return;
            }
            entry.changed = true;
            DownloadManager.this.wakeSessions(entry);
        }

        @Override
        public void completed(DownloadQueue.Download download, Path file) {
            Entry entry = DownloadManager.this.entries.remove(download.hash());
            if (entry == null) {
                return;
            }
            DownloadManager.this.closeSessions(entry, "the download is complete");
            DownloadInfo done = DownloadManager.this.info(entry);
            DownloadManager.this.forget(entry);
            DownloadManager.this.listener.completed(done, file, download.file());
        }

        @Override
        public void diskSpace(boolean enough) {
            long now = DownloadManager.this.ticker.read();
            for (Entry entry : DownloadManager.this.entries.values()) {
                entry.changed = true;
                if (!enough) {
                    DownloadManager.this.closeSessions(entry, "not enough free disk space");
                    continue;
                }
                entry.nextServerAsk = now;
            }
            if (enough) {
                for (Client client : DownloadManager.this.clients.values()) {
                    client.nextAsk = earliestAsk(client, now);
                }
            }
            DownloadManager.this.listener.diskSpace(enough);
        }

        @Override
        public void failed(DownloadQueue.Download download, String reason) {
            Entry entry = DownloadManager.this.entries.get(download.hash());
            if (entry == null) {
                return;
            }
            DownloadManager.this.closeSessions(entry, reason);
            DownloadManager.this.release(entry);
            DownloadManager.this.listener.failed(DownloadManager.this.info(entry), reason);
        }
    }
}
