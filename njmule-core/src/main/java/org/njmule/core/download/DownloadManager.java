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

import com.google.common.base.Splitter;
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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
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
 * upload queue and asked again while it keeps us there, and data flows from those that give us a slot. Engine thread
 * only.
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

    /** Kad, when it runs: finds sources of a file. */
    public interface KadSources {
        /** Starts a search; {@code false} when Kad cannot search for it now. */
        boolean find(Hash16 file, long size, Consumer<List<InetSocketAddress>> sources);
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

    /** The server is asked for the sources of each download this often. */
    public static final Duration SERVER_SOURCES_INTERVAL = Duration.ofMinutes(20);

    private static final Duration TICK = Duration.ofSeconds(1);
    // The download limit is shared out in tenths of a second.
    private static final Duration BUDGET_TICK = Duration.ofMillis(100);
    private static final int MAX_COMMENTS = 100;
    private static final int MAX_STARTING = 8;
    private static final int MAX_FAILURES = 3;
    private static final int RATE_WINDOW = 5;
    private static final int CRYPT_SUPPORTED = 0x01;
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

    /** How long a corrupt part waits for its AICH recovery data before it is downloaded again whole. */
    static final Duration RECOVERY_WAIT = Duration.ofSeconds(30);

    /** Firewalled sources asked for at once, and how long a callback is awaited. */
    static final int MAX_CALLBACKS = 32;

    static final Duration CALLBACK_WAIT = Duration.ofSeconds(60);

    private static final class Callback {
        private final Hash16 file;
        private final Optional<Hash16> userHash;
        private final int cryptOptions;
        private long since;
        private boolean requested;

        Callback(Hash16 file, Optional<Hash16> userHash, int cryptOptions, long since) {
            this.file = file;
            this.userHash = userHash;
            this.cryptOptions = cryptOptions;
            this.since = since;
        }
    }

    private static final class Source {
        private final InetSocketAddress address;
        private Optional<Hash16> userHash = Optional.empty();
        private long nextAsk;
        private long lastAsk;
        private boolean asked;
        private int failures;
        private BitSet parts;
        private DownloadSession session;
        private long sourcesAsked;
        private boolean sourcesEverAsked;
        private int cryptOptions;
        // The id of a firewalled source on our server, reached only by callbacks; 0 otherwise.
        private long lowId;
        private long received;

        Source(InetSocketAddress address, long now) {
            this.address = address;
            this.nextAsk = now;
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
    // Firewalled sources asked, through the server, to connect to us: by their LowID.
    private final Map<Long, Callback> callbacks = new LinkedHashMap<>();
    private final SessionEvents sessionEvents = new SessionEvents();
    private DownloadQueue queue;
    private ScheduledFuture<?> ticking;
    private ScheduledFuture<?> budgeting;
    // Bytes that may still be asked for under the download limit; below zero once more was asked.
    private long budget;
    // Sessions waiting for budget, first come first served.
    private final Set<DownloadSession> throttled = new LinkedHashSet<>();

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
            this.addLinkSources(entry, link.sources());
        } else {
            logger.warn(
                    "A link of {} gives another size or AICH hash than the download: its sources are left out",
                    download.file().name());
        }
        return this.info(entry);
    }

    /** Takes over another client's download copied into {@link #tempDir}. */
    public DownloadInfo adopt(Path met, Path data) throws IOException {
        DownloadQueue.Download download = this.queue.adopt(met, data);
        return this.info(this.entries.get(download.hash()));
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
            all.add(this.info(entry));
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
            if (source.userHash.isPresent()
                    && source.parts != null
                    && source.address.getAddress() instanceof Inet4Address ip) {
                sources.add(new SourceExchange.Source(
                        Ipv4.fromBytes(ip.getAddress()),
                        0,
                        source.address.getPort(),
                        0,
                        0,
                        source.userHash,
                        source.cryptOptions));
            }
        }
        return sources;
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
                text.append(' ').append(source.session == null ? source.address : source.session.describe());
            }
            text.append('\n');
        }
        return text.toString();
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
        this.closeSessions(entry, "the download is paused");
        this.listener.progress(this.info(entry));
        return true;
    }

    /** Pauses the download and forgets its sources: resumed, it looks for them again. */
    public boolean stop(Hash16 hash) {
        Entry entry = this.entries.get(hash);
        if (entry == null) {
            return false;
        }
        this.queue.stop(entry.download);
        this.closeSessions(entry, "the download is stopped");
        entry.sources.clear();
        this.callbacks.values().removeIf(callback -> callback.file.equals(hash));
        this.listener.progress(this.info(entry));
        return true;
    }

    /** Stops the download as a scheduler rule asks, also one stopped already: no other leaving starts it again. */
    public boolean stopBySchedule(Hash16 hash) {
        Entry entry = this.entries.get(hash);
        if (entry == null) {
            return false;
        }
        entry.stoppedBySchedule = true;
        return entry.download.file().status() == PartFile.Status.STOPPED || this.stop(hash);
    }

    /** Puts the download in a category, with its priority; {@link Category#NONE} takes it out of any. */
    public boolean setCategory(Hash16 hash, int id) {
        Entry entry = this.entries.get(hash);
        if (entry == null || (id != Category.NONE && this.category(id).isEmpty())) {
            return false;
        }
        Optional<Category> category = this.category(id);
        if (category.isPresent()) {
            this.putIn(entry.download, category.get());
        } else {
            this.queue.setCategory(entry.download, Category.NONE);
        }
        this.listener.progress(this.info(entry));
        return true;
    }

    /** The categories changed: downloads in one that is gone have none. */
    public void categoriesChanged() {
        for (Entry entry : this.entries.values()) {
            int id = entry.download.file().category();
            if (id != Category.NONE && this.category(id).isEmpty()) {
                this.queue.setCategory(entry.download, Category.NONE);
                this.listener.progress(this.info(entry));
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
        this.listener.progress(this.info(entry));
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
            source.nextAsk = source.asked ? Math.max(now, source.lastAsk + MIN_REASK.toNanos()) : now;
        }
        entry.nextServerAsk = now;
        this.listener.progress(this.info(entry));
        return true;
    }

    /**
     * Resumes the paused or stopped download of highest priority, the first added among equals: one of {@code category}
     * first, unless {@code mode} takes any; returns it, if there was one. Downloads a scheduler rule stopped wait.
     */
    public Optional<DownloadInfo> resumeNext(int category, NextDownload mode) {
        Optional<Entry> next = Optional.empty();
        if (mode == NextDownload.SAME_CATEGORY_FIRST || mode == NextDownload.SAME_CATEGORY) {
            next = this.firstPaused(e -> e.download.file().category() == category);
        }
        if (next.isEmpty() && (mode == NextDownload.ANY || mode == NextDownload.SAME_CATEGORY_FIRST)) {
            next = this.firstPaused(e -> true);
        }
        next.ifPresent(e -> this.resume(e.download.hash()));
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
        this.closeSessions(entry, "the download is cancelled");
        this.queue.cancel(entry.download);
        this.listener.removed(hash);
        return true;
    }

    /** A server connection was made: it is asked for the sources of every download. */
    public void serverConnected() {
        long now = this.ticker.read();
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
            if (source.isLowId()) {
                if (ourId >= ServerMessages.LOW_ID_LIMIT && source.clientId() != 0) {
                    this.askCallback(entry, source.clientId(), source.userHash(), source.cryptOptions());
                }
                continue;
            }
            int ip = Ipv4.fromEd2k(source.clientId());
            if (!this.usable(ip, source.port())) {
                continue;
            }
            Source fresh = this.addSource(entry, new InetSocketAddress(Ipv4.format(ip), source.port()));
            if (fresh != null) {
                fresh.userHash = source.userHash();
                fresh.cryptOptions = source.cryptOptions();
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
            this.closeSessions(entry, "shutting down");
        }
        this.queue.close();
    }

    // Queues a callback request; one goes out each tick, and those not answered soon are dropped.
    private void askCallback(Entry entry, long lowId, Optional<Hash16> userHash, int cryptOptions) {
        if (this.callbacks.size() >= MAX_CALLBACKS
                || this.callbacks.containsKey(lowId)
                || entry.sources.size() >= this.settings.get().maxSourcesPerFile()) {
            return;
        }
        this.callbacks.put(lowId, new Callback(entry.download.hash(), userHash, cryptOptions, this.ticker.read()));
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
    }

    // A firewalled source we asked to call us, recognised by the LowID our server gave it.
    private boolean adoptCallback(PeerLink link, Hello peer) {
        Callback callback = this.callbacks.get(peer.clientId());
        if (callback == null
                || !callback.requested
                || peer.serverIp() == 0
                || peer.serverIp() != this.server.serverIp()
                || !callback.userHash.map(peer.userHash()::equals).orElse(true)) {
            return false;
        }
        this.callbacks.remove(peer.clientId());
        Entry entry = this.entries.get(callback.file);
        if (entry == null || !entry.download.isActive()) {
            return false;
        }
        InetSocketAddress address = new InetSocketAddress(link.remoteAddress().getAddress(), peer.tcpPort());
        Source source = entry.sources.get(address);
        if (source == null) {
            source = this.addSource(entry, address);
            if (source == null) {
                return false;
            }
        }
        if (source.session != null) {
            return false;
        }
        long now = this.ticker.read();
        source.lowId = peer.clientId();
        source.userHash = Optional.of(peer.userHash());
        source.cryptOptions = callback.cryptOptions;
        source.asked = true;
        source.lastAsk = now;
        source.nextAsk = now + REASK_INTERVAL.toNanos();
        DownloadSession session = new DownloadSession(
                this.loop, this.queue, entry.download, address, this.ourHello, this.links, this.sessionEvents);
        source.session = session;
        logger.debug("{} called us back for {}", address, entry.download.file().name());
        session.adopt(link, Optional.empty(), false);
        return true;
    }

    /**
     * Takes the download side of a link a client opened when it is one of our sources, as a source does when it has a
     * slot for us or when we asked it through the server to connect; {@code false} if it is not.
     */
    public boolean adopt(PeerLink link) {
        Hello peer = link.peer().orElseThrow();
        if (this.adoptCallback(link, peer)) {
            return true;
        }
        InetAddress from = link.remoteAddress().getAddress();
        for (Entry entry : this.entries.values()) {
            if (!entry.download.isActive()) {
                continue;
            }
            for (Source source : entry.sources.values()) {
                boolean same = source.userHash.map(peer.userHash()::equals).orElse(false)
                        || (source.address.getAddress().equals(from) && source.address.getPort() == peer.tcpPort());
                if (!same || !source.asked) {
                    continue;
                }
                DownloadSession old = source.session;
                if (old != null && old.state() != DownloadSession.State.QUEUED) {
                    return false;
                }
                if (old != null) {
                    source.session = null;
                    old.close("the source called back");
                }
                DownloadSession session = new DownloadSession(
                        this.loop,
                        this.queue,
                        entry.download,
                        source.address,
                        this.ourHello,
                        this.links,
                        this.sessionEvents);
                source.session = session;
                source.userHash = Optional.of(peer.userHash());
                logger.debug(
                        "{} called back for {}",
                        source.address,
                        entry.download.file().name());
                session.adopt(link, Optional.ofNullable(source.parts), true);
                return true;
            }
        }
        return false;
    }

    private void tick() {
        long now = this.ticker.read();
        int starting = 0;
        boolean preview = this.settings.get().previewFirst();
        for (Entry entry : this.entries.values()) {
            entry.download.file().previewAlways(preview);
            for (Source source : entry.sources.values()) {
                if (source.session != null && isStarting(source.session)) {
                    starting++;
                }
            }
        }
        this.sendCallbacks(now);
        // One source request per tick, so the server is never flooded.
        boolean askedServer = false;
        for (Entry entry : this.byPriority()) {
            entry.window[entry.slot] = entry.received;
            entry.slot = (entry.slot + 1) % RATE_WINDOW;
            if (entry.received > 0) {
                entry.changed = true;
            }
            entry.received = 0;
            if (entry.download.isActive()) {
                if (!askedServer
                        && now - entry.nextServerAsk >= 0
                        && this.server.askSources(
                                entry.download.hash(), entry.download.file().size())) {
                    askedServer = true;
                    entry.nextServerAsk = now + SERVER_SOURCES_INTERVAL.toNanos();
                }
                starting += this.startSessions(entry, now, MAX_STARTING - starting);
                this.searchKad(entry, now);
            }
            if (entry.changed) {
                entry.changed = false;
                this.listener.progress(this.info(entry));
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
            for (InetSocketAddress address : found) {
                if (this.usable(Ipv4.fromBytes(address.getAddress().getAddress()), address.getPort())) {
                    this.addSource(current, address);
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

    private int startSessions(Entry entry, long now, int budget) {
        int started = 0;
        boolean required = this.settings.get().obfuscation() == ObfuscationMode.REQUIRED;
        boolean disabled = this.settings.get().obfuscation() == ObfuscationMode.DISABLED;
        for (Source source : entry.sources.values()) {
            if (started >= budget) {
                break;
            }
            if (source.session != null || now - source.nextAsk < 0) {
                continue;
            }
            if (required && source.userHash.isEmpty()) {
                continue;
            }
            if (source.lowId != 0) {
                // Firewalled: asked again through the server, while it keeps the same LowID.
                source.nextAsk = now + REASK_INTERVAL.toNanos();
                if (this.server.clientId() >= ServerMessages.LOW_ID_LIMIT) {
                    this.askCallback(entry, source.lowId, source.userHash, source.cryptOptions);
                }
                continue;
            }
            source.asked = true;
            source.lastAsk = now;
            source.nextAsk = now + REASK_INTERVAL.toNanos();
            source.session = new DownloadSession(
                    this.loop,
                    this.queue,
                    entry.download,
                    source.address,
                    this.ourHello,
                    this.links,
                    this.sessionEvents);
            // A client connected to download from us is asked over the same connection.
            Optional<PeerLink> open = this.links.find(source.address).filter(link -> !link.has(PeerLink.Side.DOWNLOAD));
            if (open.isPresent()) {
                source.session.adopt(open.get(), Optional.empty(), false);
                continue;
            }
            // Obfuscated only towards sources known to take it, which needs their user hash.
            boolean crypt = !disabled && (source.cryptOptions & CRYPT_SUPPORTED) != 0;
            source.session.connect(crypt ? source.userHash : Optional.empty(), this.random);
            started++;
        }
        return started;
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
                this.addLinkSource(entry, new InetSocketAddress(address, source.port()));
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
                                    .ifPresent(e -> this.addLinkSource(e, resolved)));
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
            this.addSource(entry, address);
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
        this.addLinkSource(entry, address);
        if (entry.sources.size() > before) {
            this.listener.progress(this.info(entry));
            return true;
        }
        return false;
    }

    /**
     * Drops the sources the IP filter now refuses, ending their sessions; returns how many. LAN sources stay: some were
     * given by the user.
     */
    public int dropBlockedSources() {
        int dropped = 0;
        for (Entry entry : this.entries.values()) {
            for (Source source : List.copyOf(entry.sources.values())) {
                if (!this.blocked.test(
                        Ipv4.fromBytes(source.address.getAddress().getAddress()))) {
                    continue;
                }
                entry.sources.remove(source.address);
                entry.changed = true;
                dropped++;
                DownloadSession session = source.session;
                if (session != null) {
                    source.session = null;
                    session.close("the IP filter blocks it");
                }
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
                && !this.isFilteredLan(ip)
                && !(Ipv4.toEd2k(ip) == this.server.clientId()
                        && port == this.settings.get().listenPort());
    }

    // Returns the new source, or null if it was known or the list is full.
    private Source addSource(Entry entry, InetSocketAddress address) {
        if (entry.sources.containsKey(address)
                || entry.sources.size() >= this.settings.get().maxSourcesPerFile()) {
            return null;
        }
        Source source = new Source(address, this.ticker.read());
        entry.sources.put(address, source);
        entry.changed = true;
        return source;
    }

    private void closeSessions(Entry entry, String reason) {
        for (Source source : List.copyOf(entry.sources.values())) {
            DownloadSession session = source.session;
            if (session != null) {
                source.session = null;
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
                DownloadSession session = source.session;
                Optional<Hello> hello = session == null ? Optional.empty() : session.peer();
                BitSet parts = session != null && session.sourceParts().isPresent()
                        ? session.sourceParts().get()
                        : source.parts;
                all.add(new SourceInfo(
                        file.hash(),
                        file.name(),
                        source.address.getAddress().getHostAddress() + ":" + source.address.getPort(),
                        hello.flatMap(Hello::nick),
                        hello.map(PeerSoftware::describe).orElse(""),
                        session == null ? SourceInfo.State.WAITING : state(session.state()),
                        session == null ? 0 : session.queueRank(),
                        source.received,
                        parts == null ? 0 : parts.cardinality(),
                        file.partCount()));
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
        this.loop.schedule("AICH recovery wait", RECOVERY_WAIT, () -> this.unhold(download, part));
        for (Source source : entry.sources.values()) {
            if (source.session != null && source.session.mayRecover(part)) {
                logger.info("Asking {} for AICH recovery data of part {} of {}", source.address, part, file.name());
                source.session.requestAich(part, file.aichHash().get());
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
            this.unhold(download, part);
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
            this.wakeSessions(entry);
        }
    }

    // Blocks were let go or a part may be downloaded again: a session with nothing to ask for asks now.
    private void wakeSessions(Entry entry) {
        for (Source source : List.copyOf(entry.sources.values())) {
            if (source.session != null) {
                source.session.wake();
            }
        }
    }

    private DownloadInfo info(Entry entry) {
        PartFile file = entry.download.file();
        int transferring = 0;
        for (Source source : entry.sources.values()) {
            if (source.session != null && source.session.state() == DownloadSession.State.DOWNLOADING) {
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
                file.previewFirst());
    }

    /** Fetches the first and last parts of the download first, or stops doing so; {@code false} if unknown. */
    public boolean setPreviewFirst(Hash16 hash, boolean value) {
        Entry entry = this.entries.get(hash);
        if (entry == null) {
            return false;
        }
        this.queue.setPreviewFirst(entry.download, value);
        this.listener.progress(this.info(entry));
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
        String text = comment.text().toLowerCase(Locale.ROOT);
        for (String word : Splitter.on('|').split(this.settings.get().commentFilter())) {
            String w = word.strip().toLowerCase(Locale.ROOT);
            if (!w.isEmpty() && text.contains(w)) {
                return;
            }
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
            if (s.isLowId() || !this.usable(s.ip(), s.port())) {
                continue;
            }
            Source fresh = this.addSource(entry, new InetSocketAddress(Ipv4.format(s.ip()), s.port()));
            if (fresh != null) {
                fresh.userHash = s.userHash().filter(h -> !h.equals(Hash16.ZERO));
                fresh.cryptOptions = s.cryptOptions();
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
            Entry entry = DownloadManager.this.entries.get(session.download().hash());
            Source source = entry == null ? null : entry.sources.get(session.address());
            if (source == null || source.session != session) {
                return;
            }
            source.session = null;
            session.sourceParts().ifPresent(parts -> source.parts = parts);
            session.peerHash().ifPresent(hash -> source.userHash = Optional.of(hash));
            if (session.peerHash().isPresent()) {
                source.cryptOptions = session.peerCryptOptions();
            }
            logger.debug("{} ({}): {}", source.address, outcome, reason);
            long now = DownloadManager.this.ticker.read();
            switch (outcome) {
                case QUEUED, LATER -> {
                    source.failures = 0;
                    source.nextAsk = source.lastAsk + REASK_INTERVAL.toNanos();
                }
                case UNREACHABLE -> {
                    source.failures++;
                    source.nextAsk = now + MIN_REASK.toNanos();
                    if (source.failures >= MAX_FAILURES) {
                        entry.sources.remove(source.address);
                    }
                }
                case GONE -> entry.sources.remove(source.address);
            }
            entry.changed = true;
            // The blocks it held may come from another source with a slot.
            DownloadManager.this.wakeSessions(entry);
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
            DownloadManager.this.listener.completed(DownloadManager.this.info(entry), file, download.file());
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
                for (Source source : entry.sources.values()) {
                    source.nextAsk = source.asked ? Math.max(now, source.lastAsk + MIN_REASK.toNanos()) : now;
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
            DownloadManager.this.listener.failed(DownloadManager.this.info(entry), reason);
        }
    }
}
