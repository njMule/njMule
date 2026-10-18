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
package org.njmule.core.upload;

import com.google.common.base.Ticker;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntConsumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.njmule.core.api.BanInfo;
import org.njmule.core.api.FileComment;
import org.njmule.core.api.Hash16;
import org.njmule.core.api.Hash20;
import org.njmule.core.api.UploadInfo;
import org.njmule.core.api.UploadPriority;
import org.njmule.core.config.ObfuscationMode;
import org.njmule.core.download.DownloadManager;
import org.njmule.core.download.DownloadQueue;
import org.njmule.core.engine.EngineLoop;
import org.njmule.core.files.SharedFiles;
import org.njmule.core.files.hash.AichRecovery;
import org.njmule.core.files.hash.AichTree;
import org.njmule.core.files.hash.Ed2k;
import org.njmule.core.net.TcpConnection;
import org.njmule.core.net.TcpConnector;
import org.njmule.core.peer.Identities;
import org.njmule.core.peer.PeerLink;
import org.njmule.core.peer.PeerLinks;
import org.njmule.core.peer.PeerSoftware;
import org.njmule.core.protocol.ed2k.ClientMessages;
import org.njmule.core.protocol.ed2k.ClientReasks;
import org.njmule.core.protocol.ed2k.Hello;
import org.njmule.core.protocol.ed2k.LowIdMessages;
import org.njmule.core.protocol.ed2k.SourceExchange;
import org.njmule.core.protocol.io.DecodeException;
import org.njmule.core.protocol.packet.Packet;
import org.njmule.core.protocol.packet.Packing;
import org.njmule.core.protocol.packet.TcpFraming;
import org.njmule.core.util.Ipv4;
import org.njmule.core.util.Ipv6;
import org.njmule.core.util.RateMeter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Our upload queue: clients that ask for a file wait in it, the best scored (waiting time times credits times file
 * priority) get the slots the upload limit allows, and each slot gets about one part before the client goes back to
 * waiting. Engine thread only.
 */
public final class UploadManager implements AutoCloseable {

    /**
     * What the uploads need from the preferences; {@code queueSize} is how many clients may wait, {@code friendSlot}
     * the friend served before anyone else.
     */
    public record Settings(
            int maxUploadKiBps, ObfuscationMode obfuscation, int queueSize, Optional<Hash16> friendSlot) {
        public Settings {
            Objects.requireNonNull(obfuscation, "obfuscation");
            Objects.requireNonNull(friendSlot, "friendSlot");
        }

        public Settings(int maxUploadKiBps, ObfuscationMode obfuscation, int queueSize) {
            this(maxUploadKiBps, obfuscation, queueSize, Optional.empty());
        }
    }

    /** Payload a slot gets before the client waits again, while others wait. */
    public static final long SESSION_LIMIT = Ed2k.PARTSIZE + 20_480;

    /** Clients asking again for a file sooner than this are counted; at {@code BAD_LIMIT} they are banned. */
    public static final Duration MIN_REQUEST_TIME = Duration.ofMinutes(10);

    public static final Duration BAN_TIME = Duration.ofHours(2);

    /** Slots a client leaves without asking for data within {@code IDLE_SLOTS_WINDOW}, at which it is banned. */
    static final int IDLE_SLOTS_LIMIT = 8;

    static final Duration IDLE_SLOTS_WINDOW = Duration.ofHours(4);

    // Connections from an address dropped for asking for files we do not have, at which it is banned.
    private static final int UNKNOWN_FILES_DROPS = 2;

    /** A waiting client that does not ask again for this long is forgotten. */
    public static final Duration PURGE_AFTER = Duration.ofHours(1);

    static final int BAD_LIMIT = 4;
    static final int MAX_WAITING_PER_IP = 3;
    static final int MIN_SLOTS = 2;
    static final int MAX_SLOTS = 100;
    static final int UNLIMITED_SLOTS = 10;

    private static final Duration TICK = Duration.ofMillis(100);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(45);
    private static final Duration WAITING_TIMEOUT = Duration.ofSeconds(40);
    // Throttled slots may send nothing for a while; the client keeps its connection.
    private static final Duration SLOT_TIMEOUT = Duration.ofMinutes(5);

    /** While others wait, a slot goes back to the queue when its client asks for nothing this long. */
    static final Duration FIRST_REQUEST_WAIT = Duration.ofSeconds(15);

    static final Duration IDLE_SLOT = Duration.ofSeconds(30);

    /**
     * File data a slot may have waiting in its connection before it gets more: a second's worth at its rate, at least
     * this. A client that reads slowly holds back only its own data, and the budget goes to the others.
     */
    static final long MIN_QUEUED = Ed2k.EMBLOCKSIZE;

    // Well below what a connection holds before it drops a peer that does not read.
    private static final long MAX_QUEUED = 4L << 20;

    private static final Duration CREDITS_SAVE = Duration.ofMinutes(13);
    private static final Duration SX_RARE_INTERVAL = Duration.ofMinutes(40);
    private static final Duration SX_INTERVAL = Duration.ofMinutes(160);
    private static final Duration SX_PURGE = Duration.ofMinutes(1);
    private static final int SX_RARE_SOURCES = 50;
    private static final int SX_PACK_ABOVE = 354;
    private static final int HEADER = TcpFraming.HEADER_SIZE;
    private static final int MIN_COMPRESSIBLE = 32;
    private static final Logger logger = LoggerFactory.getLogger(UploadManager.class);
    private static final int RATE_SECONDS = 5;
    private static final FileCounts NO_COUNTS = new FileCounts() {
        @Override
        public void asked(Hash16 file) {
            // Not counted.
        }

        @Override
        public void accepted(Hash16 file) {
            // Not counted.
        }

        @Override
        public void sent(Hash16 file, long bytes) {
            // Not counted.
        }
    };
    // A client not in the queue that asks over UDP is told the queue is full this close to its size.
    private static final int QUEUE_FULL_MARGIN = 50;

    private static final class Client {
        private final Hash16 userHash;
        private InetAddress ip;
        private final int port;
        private final boolean lowId;
        private final boolean crypt;
        private final Hello hello;
        private Uploadable file;
        private UploadSession session;
        private long waitStart;
        // When it was last heard of, to forget it when it stops asking.
        private long lastAsk;
        private boolean connecting;
        // A LowID we cannot call whose turn came while it was away: it gets a slot when it asks again.
        private boolean nextConnect;
        // Whether credits applied when it was last connected, from its address above.
        private boolean trusted;

        Client(UploadSession session, Uploadable file, long now) {
            this(session.peer(), session.address().getAddress(), file, now);
            this.session = session;
        }

        Client(Hello hello, InetAddress ip, Uploadable file, long now) {
            this.userHash = hello.userHash();
            this.ip = ip;
            this.port = hello.tcpPort();
            this.lowId = hello.clientId() != 0 && hello.clientId() < 0x0100_0000L;
            this.crypt = (hello.miscOptions2() & Hello.MISC2_SUPPORTS_CRYPT) != 0;
            this.hello = hello;
            this.file = file;
            this.waitStart = now;
            this.lastAsk = now;
        }
    }

    /** A packet to send and the file bytes it completes. */
    private record Item(Packet packet, long payload) {}

    private static final class Slot {
        private final Client client;
        private UploadSession session;
        private final Deque<Item> out = new ArrayDeque<>();
        private long sent;
        private final RateMeter rate = new RateMeter(RATE_SECONDS);
        private boolean reading;
        // Whether the client asked for a block we have, and since when it has nothing to send.
        private boolean requested;
        private long idleSince;

        Slot(Client client, UploadSession session, long now) {
            this.client = client;
            this.session = session;
            this.idleSince = now;
        }

        boolean isBusy() {
            return this.reading
                    || !this.out.isEmpty()
                    || this.session.hasPendingBlocks()
                    || this.session.queuedData() > 0;
        }
    }

    private final EngineLoop loop;
    private final Function<Hash16, Optional<SharedFiles.SharedFile>> shared;
    private final Function<Hash16, Optional<AichTree>> aichTrees;
    private final BiFunction<Hash16, Boolean, Optional<List<SharedFiles.SharedFile>>> visibleTo;
    private final Predicate<Hash16> friends;
    private final Function<Hash16, Optional<FileComment>> comments;
    private final Function<Hash16, Optional<DownloadQueue.Download>> downloads;
    private final Supplier<Hello> ourHello;
    private final Supplier<Settings> settings;
    private final PeerLinks links;
    private final Credits credits;
    private final SecureRandom random;
    private final Ticker ticker;
    private final Map<Hash16, Client> waiting = new LinkedHashMap<>();
    private final List<Slot> slots = new ArrayList<>();
    // Banned addresses until when; read by the listener thread too.
    private final Map<InetAddress, Long> bans = new ConcurrentHashMap<>();
    // When each client, by address and user hash, was last told sources.
    private final Map<RequestTracker.Key, Long> sourcesAnswered = new HashMap<>();
    private final RequestTracker requests = new RequestTracker(MIN_REQUEST_TIME, IDLE_SLOTS_WINDOW);
    private final Function<Hash16, List<SourceExchange.Source>> downloadSources;
    private final IntConsumer reachable;
    private final SessionEvents events = new SessionEvents();
    private final RateMeter rate = new RateMeter(RATE_SECONDS);
    private ChatSink chat = (address, peer, proven, reply, open, packet) -> false;
    private UdpCheckSink udpChecks = (ip, request, known) -> {};

    /** Where the UDP firewall test requests of clients go, on the engine thread. */
    @FunctionalInterface
    public interface UdpCheckSink {
        /** The client at {@code ip} asks for its test; {@code known} when it waits for or has an upload slot. */
        void requested(int ip, LowIdMessages.UdpCheckRequest request, boolean known);
    }

    /** The UDP firewall test requests arriving on upload connections go to {@code sink}. */
    public void onUdpCheck(UdpCheckSink sink) {
        this.udpChecks = Objects.requireNonNull(sink, "sink");
    }

    /** Whether a client at {@code ip} waits in our queue or has a slot. */
    public boolean knows(InetAddress ip) {
        for (Slot slot : this.slots) {
            if (slot.client.ip.equals(ip)) {
                return true;
            }
        }
        for (Client client : this.waiting.values()) {
            if (client.ip.equals(ip)) {
                return true;
            }
        }
        return false;
    }

    /** Where chat packets from clients go, {@code proven} when the client's identity is; engine thread. */
    @FunctionalInterface
    public interface ChatSink {
        boolean incoming(
                InetSocketAddress connection,
                Hello peer,
                boolean proven,
                Consumer<Packet> reply,
                BooleanSupplier open,
                Packet packet);
    }

    /** Chat packets arriving on upload connections go to {@code sink}. */
    public void onChat(ChatSink sink) {
        this.chat = Objects.requireNonNull(sink, "sink");
    }

    private ScheduledFuture<?> ticking;
    private long budget;
    private Function<Hash16, UploadPriority> priorities = hash -> UploadPriority.AUTO;
    private FileCounts counts = NO_COUNTS;
    private long nextCreditsSave;
    private long nextSourcesPurge;
    private int turn;

    /**
     * @param shared the shared file with a hash, if any
     * @param downloads the download of a hash, whose checked parts are shared too
     * @param downloadSources the sources an active download of a hash knows
     * @param reachable told the address (host order) of a Kad node that could reach us
     * @param visibleTo the shared files a client may browse, by its user hash and whether its identity is proven; empty
     *     when it may not
     * @param friends whether a user hash is one of a friend's
     */
    public UploadManager(
            EngineLoop loop,
            Function<Hash16, Optional<SharedFiles.SharedFile>> shared,
            Function<Hash16, Optional<AichTree>> aichTrees,
            BiFunction<Hash16, Boolean, Optional<List<SharedFiles.SharedFile>>> visibleTo,
            Predicate<Hash16> friends,
            Function<Hash16, Optional<FileComment>> comments,
            Function<Hash16, Optional<DownloadQueue.Download>> downloads,
            Function<Hash16, List<SourceExchange.Source>> downloadSources,
            Supplier<Hello> ourHello,
            Supplier<Settings> settings,
            PeerLinks links,
            Credits credits,
            IntConsumer reachable,
            SecureRandom random,
            Ticker ticker) {
        this.loop = Objects.requireNonNull(loop, "loop");
        this.shared = Objects.requireNonNull(shared, "shared");
        this.aichTrees = Objects.requireNonNull(aichTrees, "aichTrees");
        this.visibleTo = Objects.requireNonNull(visibleTo, "visibleTo");
        this.friends = Objects.requireNonNull(friends, "friends");
        this.comments = Objects.requireNonNull(comments, "comments");
        this.downloads = Objects.requireNonNull(downloads, "downloads");
        this.downloadSources = Objects.requireNonNull(downloadSources, "downloadSources");
        this.ourHello = Objects.requireNonNull(ourHello, "ourHello");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.links = Objects.requireNonNull(links, "links");
        this.credits = Objects.requireNonNull(credits, "credits");
        this.reachable = Objects.requireNonNull(reachable, "reachable");
        this.random = Objects.requireNonNull(random, "random");
        this.ticker = Objects.requireNonNull(ticker, "ticker");
        this.nextCreditsSave = ticker.read() + CREDITS_SAVE.toNanos();
        this.nextSourcesPurge = ticker.read() + SX_PURGE.toNanos();
    }

    /** Starts serving the queue. */
    public void start() {
        this.ticking = this.loop.scheduleRepeating("uploads", TICK, TICK, this::tick);
    }

    /** Slots and waiting clients, for diagnostics. */
    public String describe() {
        long called = this.waiting.values().stream().filter(c -> c.nextConnect).count();
        StringBuilder text = new StringBuilder("waiting " + this.waiting.size() + ", " + called
                + " called when they ask, " + this.sourcesAnswered.size() + " source answers");
        for (Slot slot : this.slots) {
            text.append("\nslot ")
                    .append(slot.session.describe())
                    .append(" out ")
                    .append(slot.out.size())
                    .append(slot.reading ? " reading" : "")
                    .append(" payload ")
                    .append(slot.sent);
        }
        return text.toString();
    }

    /** The clients with a slot, then the waiting ones from the best score down. */
    public List<UploadInfo> uploads() {
        long now = this.ticker.read();
        List<UploadInfo> all = new ArrayList<>();
        for (Slot slot : this.slots) {
            all.add(info(slot.client, UploadInfo.State.UPLOADING, slot.sent, slot.rate.rate(now), now));
        }
        Scoring scoring = new Scoring(now);
        List<Scored> queue = new ArrayList<>(this.waiting.size());
        for (Client client : this.waiting.values()) {
            queue.add(new Scored(client, scoring.of(client)));
        }
        queue.sort(Comparator.comparingDouble(Scored::score).reversed());
        for (Scored scored : queue) {
            all.add(info(scored.client(), UploadInfo.State.QUEUED, 0, 0, now));
        }
        return all;
    }

    private record Scored(Client client, double score) {}

    /** Payload bytes per second sent to all slots. */
    public long rate() {
        return this.rate.rate(this.ticker.read());
    }

    /** Payload bytes sent in this session. */
    public long uploaded() {
        return this.rate.total();
    }

    private static UploadInfo info(Client client, UploadInfo.State state, long sent, long rate, long now) {
        return new UploadInfo(
                client.ip.getHostAddress() + ":" + client.port,
                client.userHash,
                client.hello.nick(),
                PeerSoftware.describe(client.hello),
                client.file.name(),
                state,
                sent,
                rate,
                Math.max(0, now - client.waitStart) / 1_000_000_000L);
    }

    /** Where the requests, slots and bytes sent of each file are counted. */
    public interface FileCounts {
        void asked(Hash16 file);

        void accepted(Hash16 file);

        void sent(Hash16 file, long bytes);
    }

    /** Counts the requests, slots and bytes sent of each file in {@code counts}. */
    public void countIn(FileCounts counts) {
        this.counts = Objects.requireNonNull(counts, "counts");
    }

    /** What a UDP re-ask is answered with, and the user hash that obfuscates the answer, when it is known. */
    public record ReaskAnswer(Packet packet, Optional<Hash16> obfuscateFor) {}

    /**
     * A client at {@code ip} asked again over UDP from {@code port}. One waiting for the file is told its place and the
     * parts we have; anyone is told when we do not share the file, or when the queue is about full. A client we cannot
     * tell apart from others at its address, waiting for another file or due for a slot it can only get over TCP is
     * answered nothing, and so asks over TCP.
     */
    public Optional<ReaskAnswer> reasked(InetAddress ip, int port, Packet ping) {
        if (isBanned(ip)) {
            return Optional.empty();
        }
        Hash16 hash;
        try {
            hash = ClientReasks.Ping.file(ping);
        } catch (DecodeException e) {
            return Optional.empty();
        }
        long now = this.ticker.read();
        Client client = waitingAt(ip, port);
        Optional<Hash16> key = client != null && client.crypt ? Optional.of(client.userHash) : Optional.empty();
        Optional<Uploadable> file = find(hash);
        if (file.isEmpty()) {
            return Optional.of(new ReaskAnswer(ClientReasks.fileNotFound(), key));
        }
        if (client == null) {
            // Its user hash unknown, the answer goes plain.
            return this.waiting.size() + QUEUE_FULL_MARGIN > this.settings.get().queueSize()
                    ? Optional.of(new ReaskAnswer(ClientReasks.queueFull(), Optional.empty()))
                    : Optional.empty();
        }
        if (!client.file.hash().equals(hash)
                || client.nextConnect
                || askedTooOften(ip, client.port, client.userHash, client.trusted, file.get(), now)) {
            return Optional.empty();
        }
        client.lastAsk = now;
        int version = client.hello.udpVersion();
        Optional<ClientMessages.PartStatus> parts =
                version > 3 ? Optional.of(UploadSession.status(file.get()).status()) : Optional.empty();
        return Optional.of(new ReaskAnswer(new ClientReasks.Ack(parts, rank(client, now)).encode(version), key));
    }

    // The client waiting at this address and UDP port; none when it is not the only one there.
    private Client waitingAt(InetAddress ip, int port) {
        Client found = null;
        for (Client client : this.waiting.values()) {
            if (client.ip.equals(ip) && client.hello.udpPort() == port) {
                if (found != null) {
                    return null;
                }
                found = client;
            }
        }
        return found;
    }

    /** Clients waiting for a slot. */
    public int waitingCount() {
        return this.waiting.size();
    }

    /** Clients with a slot. */
    public int slotCount() {
        return this.slots.size();
    }

    /** Takes the upload side of a link whose client greeted us; empty for a banned client. */
    public Optional<PeerLink.Role> attach(PeerLink link) {
        if (isBanned(link.remoteAddress().getAddress())) {
            return Optional.empty();
        }
        return Optional.of(UploadSession.on(link, this.ourHello, this.events));
    }

    @Override
    public void close() {
        if (this.ticking != null) {
            this.ticking.cancel(false);
        }
        for (Slot slot : List.copyOf(this.slots)) {
            slot.session.close();
        }
        this.slots.clear();
        this.waiting.clear();
        this.credits.save();
    }

    // As find, without building what a slot needs.
    private boolean isAvailable(Hash16 hash) {
        return this.shared.apply(hash).isPresent()
                || this.downloads
                        .apply(hash)
                        .filter(d -> !d.verifiedParts().isEmpty())
                        .isPresent();
    }

    private Optional<Uploadable> find(Hash16 hash) {
        Optional<Uploadable> complete = this.shared.apply(hash).map(Uploadable::of);
        if (complete.isPresent()) {
            return complete;
        }
        return this.downloads
                .apply(hash)
                .filter(d -> !d.verifiedParts().isEmpty())
                .map(Uploadable::of);
    }

    /** Puts a client that asked for {@code file} and is not connected now in the queue, as the benchmark needs. */
    void enqueue(Hello hello, InetAddress ip, Uploadable file, long waitStart) {
        Client client = new Client(hello, ip, file, waitStart);
        client.lastAsk = this.ticker.read();
        this.waiting.put(client.userHash, client);
    }

    /** The place in the queue of the client waiting with {@code userHash}. */
    int rankOf(Hash16 userHash) {
        return rank(this.waiting.get(userHash), this.ticker.read());
    }

    // One step of the queue: slots fed, ended and filled.
    void tick() {
        long now = this.ticker.read();
        int limit = this.settings.get().maxUploadKiBps();
        // Nothing carries over from unlimited: the sum would overflow and stop the uploads.
        long carried = this.budget == Long.MAX_VALUE ? 0 : this.budget;
        this.budget = limit == 0 ? Long.MAX_VALUE : Math.min(carried + limit * 1024L / 10, limit * 1024L);
        this.bans.values().removeIf(until -> now - until >= 0);
        // Many clients wait for the same few files: each is looked up once.
        Map<Hash16, Boolean> available = new HashMap<>();
        this.waiting
                .values()
                .removeIf(c -> now - c.lastAsk > PURGE_AFTER.toNanos()
                        || !available.computeIfAbsent(c.file.hash(), this::isAvailable));
        for (Slot slot : List.copyOf(this.slots)) {
            read(slot);
            if (slot.sent >= SESSION_LIMIT
                    && !this.waiting.isEmpty()
                    && slot.out.isEmpty()
                    && !slot.reading
                    && !hasFriendSlot(slot.client.userHash, slot.session.trust().credits())) {
                requeue(slot, now);
            } else if (slot.isBusy()) {
                slot.idleSince = now;
            } else if (!this.waiting.isEmpty()
                    && now - slot.idleSince > (slot.requested ? IDLE_SLOT : FIRST_REQUEST_WAIT).toNanos()) {
                // A client that holds a slot without asking for data we have keeps others waiting.
                logger.debug("{} left its upload slot idle", slot.session.address());
                requeue(slot, now);
                if (!slot.requested) {
                    idleSlot(slot, now);
                }
            }
        }
        send();
        fillSlots(now);
        if (now - this.nextCreditsSave >= 0) {
            this.nextCreditsSave = now + CREDITS_SAVE.toNanos();
            this.credits.save();
        }
        // An answer older than the longest wait no longer holds anyone back.
        if (now - this.nextSourcesPurge >= 0) {
            this.nextSourcesPurge = now + SX_PURGE.toNanos();
            this.sourcesAnswered.values().removeIf(at -> now - at >= SX_INTERVAL.toNanos());
            this.requests.purge(now);
        }
    }

    // Taking slots and asking for nothing, over and over, keeps others from them: the client is banned.
    private void idleSlot(Slot slot, long now) {
        InetAddress ip = slot.session.address().getAddress();
        if (this.requests.idleSlot(new RequestTracker.Key(ip, slot.client.port), now) >= IDLE_SLOTS_LIMIT
                && !hasFriendSlot(slot.client.userHash, slot.session.trust().credits())) {
            ban(ip, now, "took slots and asked for nothing");
        }
    }

    // A slot that sent data forgives the idle slots of its client.
    private void slotEnded(Slot slot) {
        if (slot.sent > 0) {
            this.requests.productive(
                    new RequestTracker.Key(slot.session.address().getAddress(), slot.client.port));
        }
    }

    // One data packet per slot in turn, while the budget lasts: every slot whose client reads what it gets has an equal
    // share. The budget is spent on what goes out, as a slot holds little more than it sends.
    private void send() {
        boolean progress = true;
        long start = this.ticker.read();
        while (this.budget > 0 && progress && !this.slots.isEmpty()) {
            progress = false;
            for (int i = 0; i < this.slots.size() && this.budget > 0; i++) {
                Slot slot = this.slots.get((this.turn + i) % this.slots.size());
                if (slot.session.queuedData() >= Math.clamp(slot.rate.rate(start), MIN_QUEUED, MAX_QUEUED)) {
                    continue;
                }
                Item item = slot.out.poll();
                if (item == null) {
                    continue;
                }
                progress = true;
                int wire = item.packet().length() + HEADER;
                if (this.budget != Long.MAX_VALUE) {
                    this.budget -= wire;
                }
                if (slot.session.sendData(item.packet())) {
                    slot.sent += item.payload();
                    this.counts.sent(slot.client.file.hash(), item.payload());
                    long now = this.ticker.read();
                    slot.rate.add(item.payload(), now);
                    this.rate.add(item.payload(), now);
                    if (slot.session.trust().credits()) {
                        this.credits.uploaded(slot.client.userHash, wire);
                    }
                }
                if (slot.out.isEmpty()) {
                    read(slot);
                }
            }
            this.turn = this.slots.isEmpty() ? 0 : (this.turn + 1) % this.slots.size();
        }
    }

    private void read(Slot slot) {
        if (slot.reading || !slot.out.isEmpty() || !slot.session.isOpen()) {
            return;
        }
        Optional<UploadSession.Block> next = slot.session.nextBlock();
        if (next.isEmpty()) {
            return;
        }
        read(slot, next.get().file(), next.get());
    }

    // The block is read, and compressed, off the engine thread.
    private void read(Slot slot, Uploadable file, UploadSession.Block block) {
        slot.client.file = file;
        slot.reading = true;
        int length = Math.toIntExact(block.end() - block.start());
        boolean compress = file.compressible()
                && length >= MIN_COMPRESSIBLE
                && (slot.session.peer().miscOptions1() >>> 20 & 0x0F) != 0;
        this.loop.whenDone(
                Blocks.readPackets(file.data(), file.hash(), block.start(), length, compress),
                (packets, failure) -> blockRead(slot, file, block, packets, failure));
    }

    private void blockRead(
            Slot slot, Uploadable file, UploadSession.Block block, List<Packet> packets, Throwable failure) {
        slot.reading = false;
        if (!this.slots.contains(slot)) {
            return;
        }
        // A file renamed, or a download completed, since the block was asked for is read where it is now.
        Optional<Uploadable> moved = failure == null
                ? Optional.empty()
                : find(file.hash()).filter(f -> !f.data().equals(file.data()) && f.has(block.start(), block.end()));
        if (moved.isPresent()) {
            read(slot, moved.get(), block);
            return;
        }
        if (failure != null) {
            logger.warn("Cannot read {} for an upload: {}", file.name(), failure.getMessage());
            this.slots.remove(slot);
            slot.session.close();
            return;
        }
        long length = block.end() - block.start();
        for (int i = 0; i < packets.size(); i++) {
            slot.out.add(new Item(packets.get(i), i == packets.size() - 1 ? length : 0));
        }
        // At once, within what the budget of this tick has left: waiting for the next tick would cap each slot at a
        // block every tick, about 1.8 MB/s, whatever the line.
        send();
    }

    // About one part sent while others wait: the client waits again, from now.
    private void requeue(Slot slot, long now) {
        this.slots.remove(slot);
        slotEnded(slot);
        Client client = slot.client;
        client.session = slot.session;
        client.waitStart = now;
        client.lastAsk = now;
        this.waiting.put(client.userHash, client);
        slot.session.endSlot(rank(client, now));
        slot.session.setReadTimeout(WAITING_TIMEOUT);
    }

    /** The banned addresses with the time left. */
    public List<BanInfo> bans() {
        long now = this.ticker.read();
        List<BanInfo> all = new ArrayList<>();
        for (Map.Entry<InetAddress, Long> ban : this.bans.entrySet()) {
            long left = ban.getValue() - now;
            if (left > 0) {
                all.add(new BanInfo(ban.getKey().getHostAddress(), TimeUnit.NANOSECONDS.toSeconds(left)));
            }
        }
        return all;
    }

    /** Lifts the ban on {@code ip}; false when it was not banned. */
    public boolean unban(InetAddress ip) {
        return this.bans.remove(Ipv6.network(ip)) != null;
    }

    /** Whether {@code ip} is banned, with the rest of its /64 for IPv6; from any thread. */
    public boolean isBanned(InetAddress ip) {
        Long until = this.bans.get(Ipv6.network(ip));
        return until != null && this.ticker.read() - until < 0;
    }

    /** Ends the uploads to and drops the waiting clients at the addresses {@code refused} takes; returns how many. */
    public int dropClients(Predicate<InetAddress> refused) {
        int dropped = 0;
        for (Slot slot : List.copyOf(this.slots)) {
            if (refused.test(slot.client.ip)) {
                this.slots.remove(slot);
                slot.session.close();
                dropped++;
            }
        }
        for (var it = this.waiting.values().iterator(); it.hasNext(); ) {
            Client c = it.next();
            if (refused.test(c.ip)) {
                it.remove();
                if (c.session != null) {
                    c.session.disconnect();
                }
                dropped++;
            }
        }
        return dropped;
    }

    /** Bans {@code ip} for {@link #BAN_TIME}, as when it sent corrupt data. */
    public void ban(InetAddress ip, String reason) {
        ban(ip, this.ticker.read(), reason);
    }

    // Clients waiting from a banned address leave the queue; a slot it holds runs to its end, as
    // other clients may share the address.
    private void ban(InetAddress ip, long now, String reason) {
        logger.debug("Banning {}: {}", ip, reason);
        InetAddress banned = Ipv6.network(ip);
        this.bans.put(banned, now + BAN_TIME.toNanos());
        this.waiting.values().removeIf(c -> {
            if (!Ipv6.network(c.ip).equals(banned)) {
                return false;
            }
            if (c.session != null && slotOf(c.session).isEmpty()) {
                c.session.disconnect();
            }
            return true;
        });
    }

    // The best client we can reach gets the slot; a LowID that is not connected cannot be called, so when it would have
    // been the best it gets a slot of its own on its next request.
    private void fillSlots(long now) {
        // Moving a client from the queue to a slot changes no score.
        Scoring scoring = new Scoring(now);
        while (!this.waiting.isEmpty() && canOpenSlot(false)) {
            Client best = null;
            double bestScore = -1;
            Client away = null;
            double awayScore = -1;
            for (Client c : this.waiting.values()) {
                if (c.connecting) {
                    continue;
                }
                double score = scoring.of(c);
                boolean reachable = !c.lowId || (c.session != null && c.session.isOpen());
                if (reachable && score > bestScore) {
                    best = c;
                    bestScore = score;
                } else if (!reachable && !c.nextConnect && score > awayScore) {
                    away = c;
                    awayScore = score;
                }
            }
            if (away != null && awayScore > bestScore) {
                away.nextConnect = true;
            }
            if (best == null) {
                return;
            }
            if (best.session != null && best.session.isOpen()) {
                this.waiting.remove(best.userHash);
                open(best, best.session);
            } else {
                // Called once our connections leave room for one more.
                if (this.links.mayConnect()) {
                    connect(best);
                }
                return;
            }
        }
    }

    private void open(Client client, UploadSession session) {
        this.counts.accepted(client.file.hash());
        Slot slot = new Slot(client, session, this.ticker.read());
        this.slots.add(slot);
        client.session = session;
        client.nextConnect = false;
        session.setReadTimeout(SLOT_TIMEOUT);
        session.accept();
        logger.debug("Upload slot for {} ({})", session.address(), client.file.name());
    }

    private boolean canOpenSlot(boolean extra) {
        int open = this.slots.size();
        for (Client c : this.waiting.values()) {
            if (c.connecting) {
                open++;
            }
        }
        if (open >= MAX_SLOTS) {
            return false;
        }
        if (open < MIN_SLOTS) {
            return true;
        }
        int limit = this.settings.get().maxUploadKiBps();
        if (limit == 0) {
            return open < UNLIMITED_SLOTS + (extra ? 1 : 0);
        }
        long perSlot = open <= 3 ? 3_072 : Math.min(25_600, open * 1_024L);
        return open < Math.max(MIN_SLOTS, limit * 1_024L / perSlot) + (extra ? 1 : 0);
    }

    // A client asking for the same file again too soon BAD_LIMIT times, with no slower ask between, is banned unless it
    // holds the friend slot, also when it left the queue meanwhile. Asks for its other files and the end of its slot,
    // which is ours, do not count. A slot it holds runs on.
    private boolean askedTooOften(UploadSession session, Uploadable file, long now) {
        if (!askedTooOften(
                session.address().getAddress(),
                session.peer().tcpPort(),
                session.userHash(),
                session.trust().credits(),
                file,
                now)) {
            return false;
        }
        if (slotOf(session).isEmpty()) {
            session.disconnect();
        }
        return true;
    }

    private boolean askedTooOften(
            InetAddress ip, int port, Hash16 userHash, boolean trusted, Uploadable file, long now) {
        int bad = this.requests.asked(new RequestTracker.Key(ip, port), file.hash(), now);
        if (bad < BAD_LIMIT || hasFriendSlot(userHash, trusted)) {
            return false;
        }
        ban(ip, now, "asked again too often");
        return true;
    }

    // The friend with the friend slot, unless it has a LowID, comes before anyone and keeps its slot; only with its
    // identity trusted, as anyone can send a user hash.
    private boolean hasFriendSlot(Hash16 userHash, boolean trusted) {
        return trusted
                && this.settings.get().friendSlot().filter(userHash::equals).isPresent();
    }

    /** Where the upload priority of a shared file comes from; without it every file is AUTO. */
    public void priorities(Function<Hash16, UploadPriority> priorities) {
        this.priorities = Objects.requireNonNull(priorities, "priorities");
    }

    private int rank(Client client, long now) {
        Scoring scoring = new Scoring(now);
        double score = scoring.of(client);
        int rank = 1;
        for (Client c : this.waiting.values()) {
            if (c != client && scoring.of(c) > score) {
                rank++;
            }
        }
        return rank;
    }

    /**
     * Scores at one moment: waiting time times credits times the factor of the file's priority. The factors, which
     * count the clients of each file, are worked out once, so a pass over the queue costs a score per client.
     */
    private final class Scoring {
        private final long now;
        private final Optional<Hash16> friend;
        private final Map<Hash16, Integer> factors = new HashMap<>();
        private Map<Hash16, Integer> requesters;

        Scoring(long now) {
            this.now = now;
            this.friend = UploadManager.this.settings.get().friendSlot();
        }

        double of(Client client) {
            boolean trusted = client.session != null ? client.session.trust().credits() : client.trusted;
            // The friend with the friend slot, unless it has a LowID, comes before anyone; only with its identity
            // trusted.
            if (!client.lowId
                    && trusted
                    && this.friend.filter(client.userHash::equals).isPresent()) {
                return Double.MAX_VALUE;
            }
            double waited = Math.max(0, this.now - client.waitStart) / 1e9;
            double ratio = UploadManager.this.credits.ratio(client.userHash, trusted);
            return waited * ratio * factor(client.file.hash()) / 10;
        }

        // The factor of a file's level, in tenths.
        private int factor(Hash16 file) {
            return this.factors.computeIfAbsent(file, f -> switch (UploadManager.this.priorities.apply(f)) {
                case VERY_LOW -> 2;
                case LOW -> 6;
                case NORMAL -> 7;
                case HIGH -> 9;
                case RELEASE -> 18;
                case AUTO -> automatic(f);
            });
        }

        // Files many clients want get a lower priority, so rare files spread first.
        private int automatic(Hash16 file) {
            if (this.requesters == null) {
                this.requesters = new HashMap<>();
                for (Client c : UploadManager.this.waiting.values()) {
                    this.requesters.merge(c.file.hash(), 1, Integer::sum);
                }
                for (Slot s : UploadManager.this.slots) {
                    this.requesters.merge(s.client.file.hash(), 1, Integer::sum);
                }
            }
            int count = this.requesters.getOrDefault(file, 0);
            return count > 20 ? 6 : count > 1 ? 7 : 9;
        }
    }

    // A client with a slot but no connection: we connect to it and give the slot after the hello.
    private void connect(Client client) {
        client.connecting = true;
        // Obfuscated only towards clients that said they take it.
        Optional<Hash16> obfuscateFor = this.settings.get().obfuscation() == ObfuscationMode.DISABLED || !client.crypt
                ? Optional.empty()
                : Optional.of(client.userHash);
        InetSocketAddress address = new InetSocketAddress(client.ip, client.port);
        Thread.ofVirtual().name("njmule-upload-" + address).start(() -> {
            try {
                TcpConnection opened =
                        TcpConnector.connectToClient(address, obfuscateFor, CONNECT_TIMEOUT, this.random);
                if (!this.loop.postOutcome(() -> connected(client, opened))) {
                    opened.close();
                }
            } catch (IOException e) {
                this.loop.postOutcome(() -> unreachable(client, e.getMessage()));
            }
        });
    }

    private void unreachable(Client client, String reason) {
        logger.debug("Cannot reach {} for its slot: {}", client.ip, reason);
        client.connecting = false;
        this.waiting.remove(client.userHash, client);
    }

    private void connected(Client client, TcpConnection connection) {
        if (this.waiting.get(client.userHash) != client) {
            connection.close();
            return;
        }
        PeerLink link = this.links.outgoing(connection, new PeerLinks.Opener() {
            @Override
            public void greeted(PeerLink link) {
                UploadManager.this.greeted(client, link);
            }

            @Override
            public void failed(String reason) {
                UploadManager.this.unreachable(client, reason);
            }
        });
        connection.start(link, UploadManager::unpack);
        connection.send(this.ourHello.get().encode(false));
    }

    // The client we called back to give it its slot answered our hello.
    private void greeted(Client client, PeerLink link) {
        client.connecting = false;
        Hello peer = link.peer().orElseThrow();
        if (!peer.userHash().equals(client.userHash) || this.waiting.get(client.userHash) != client) {
            link.disconnect();
            return;
        }
        UploadSession session = UploadSession.on(link, this.ourHello, this.events);
        this.waiting.remove(client.userHash);
        open(client, session);
    }

    // A client may ask once, then again after 40 minutes for rare downloads or 160 for the rest; only for a file it
    // asked
    // for on the connection, and never while its address is banned. The wait goes by address and user hash, so another
    // address claiming the hash cannot keep the client from its answer.
    private void answerSources(UploadSession session, SourceExchange.Request request) {
        long now = this.ticker.read();
        InetAddress ip = session.address().getAddress();
        if (!session.asked(request.file()) || isBanned(ip)) {
            return;
        }
        Optional<Uploadable> file = find(request.file());
        if (file.isEmpty() || request.version() < 1) {
            return;
        }
        List<SourceExchange.Source> sources = new ArrayList<>();
        Optional<DownloadQueue.Download> download = this.downloads.apply(request.file());
        if (download.isPresent() && download.get().isActive()) {
            sources.addAll(this.downloadSources.apply(request.file()));
        }
        boolean rare = download.isPresent() && sources.size() <= SX_RARE_SOURCES;
        RequestTracker.Key asker = new RequestTracker.Key(ip, session.peer().tcpPort());
        Long last = this.sourcesAnswered.get(asker);
        long wait = (rare ? SX_RARE_INTERVAL : SX_INTERVAL).toNanos();
        if (last != null && now - last < wait) {
            return;
        }
        if (sources.isEmpty()) {
            for (Client c : uploaders(request.file())) {
                if (!c.lowId && c.ip instanceof Inet4Address ip4) {
                    sources.add(new SourceExchange.Source(
                            Ipv4.fromBytes(ip4.getAddress()), 0, c.port, 0, 0, Optional.of(c.userHash), 0));
                } else if (!c.lowId && c.ip instanceof Inet6Address ip6 && Ipv6.isPublic(ip6)) {
                    sources.add(SourceExchange.Source.ipv6Only(ip6, c.port, Optional.of(c.userHash)));
                }
            }
        }
        // Both announce the extended form, which carries IPv6 addresses; the others get none at an IPv6 address alone.
        boolean extended = session.peer().supportsExtendedSourceExchange()
                && this.ourHello.get().supportsExtendedSourceExchange();
        if (!extended) {
            sources.removeIf(SourceExchange.Source::isIpv6Only);
        }
        sources.removeIf(s -> s.userHash().map(session.userHash()::equals).orElse(false));
        // Addresses outside the public network stay within their own: LAN and loopback ones go to no one outside.
        sources.removeIf(s -> !s.isIpv6Only()
                && !DownloadManager.passesOn(s.ip(), session.address().getAddress()));
        if (sources.isEmpty()) {
            return;
        }
        this.sourcesAnswered.put(asker, now);
        List<SourceExchange.Source> sent = sources.subList(0, Math.min(sources.size(), SourceExchange.MAX_SOURCES));
        int version = Math.min(request.version(), SourceExchange.VERSION);
        Packet answer = extended
                ? new SourceExchange.Answer(SourceExchange.EXTENDED_VERSION, request.file(), sent).encodeExtended()
                : new SourceExchange.Answer(version, request.file(), sent).encode();
        session.send(answer.length() > SX_PACK_ABOVE ? Packing.pack(answer) : answer);
    }

    // Clients waiting for or downloading the file from us.
    private List<Client> uploaders(Hash16 file) {
        List<Client> clients = new ArrayList<>();
        for (Client c : this.waiting.values()) {
            if (c.file.hash().equals(file)) {
                clients.add(c);
            }
        }
        for (Slot s : this.slots) {
            if (s.client.file.hash().equals(file)) {
                clients.add(s.client);
            }
        }
        return clients;
    }

    private static Packet unpack(Packet frame) throws DecodeException {
        return Packing.unpack(frame, Packing.MAX_CLIENT_UNPACKED);
    }

    private Optional<Slot> slotOf(UploadSession session) {
        for (Slot slot : this.slots) {
            if (slot.session == session) {
                return Optional.of(slot);
            }
        }
        return Optional.empty();
    }

    private final class SessionEvents implements UploadSession.Queue {
        @Override
        public Optional<Uploadable> find(Hash16 file) {
            return UploadManager.this.find(file);
        }

        @Override
        public Optional<FileComment> comment(Hash16 file) {
            return UploadManager.this.comments.apply(file);
        }

        @Override
        public Optional<List<SharedFiles.SharedFile>> visibleTo(Hash16 userHash, boolean proven) {
            return UploadManager.this.visibleTo.apply(userHash, proven);
        }

        @Override
        public boolean isFriend(Hash16 userHash) {
            return UploadManager.this.friends.test(userHash);
        }

        @Override
        public Optional<Hash20> aichMaster(Hash16 file) {
            UploadManager manager = UploadManager.this;
            return manager.aichTrees
                    .apply(file)
                    .map(AichTree::masterHash)
                    .or(() ->
                            manager.downloads.apply(file).flatMap(d -> d.file().aichHash()));
        }

        @Override
        public Optional<byte[]> aichRecovery(Hash16 file, int part, Hash20 master) {
            return UploadManager.this
                    .aichTrees
                    .apply(file)
                    .filter(tree -> tree.masterHash().equals(master) && AichRecovery.hasRecovery(tree.size(), part))
                    .map(tree -> AichRecovery.create(tree, part));
        }

        @Override
        public void named(Uploadable file) {
            UploadManager.this.counts.asked(file.hash());
        }

        @Override
        public void requested(UploadSession session, Uploadable file) {
            UploadManager manager = UploadManager.this;
            long now = manager.ticker.read();
            InetAddress ip = session.address().getAddress();
            if (manager.isBanned(ip)) {
                return;
            }
            for (Slot slot : manager.slots) {
                if (slot.client.userHash.equals(session.userHash())) {
                    if (!sameClient(slot.client, session) || manager.askedTooOften(session, file, now)) {
                        return;
                    }
                    // Already uploading to it, maybe over a new connection or for another file.
                    slot.session = session;
                    slot.client.file = file;
                    session.setReadTimeout(SLOT_TIMEOUT);
                    session.accept();
                    return;
                }
            }
            Client client = manager.waiting.get(session.userHash());
            if (client != null) {
                if (!sameClient(client, session) || manager.askedTooOften(session, file, now)) {
                    return;
                }
                client.lastAsk = now;
                client.session = session;
                client.file = file;
                if (client.nextConnect && manager.canOpenSlot(true)) {
                    manager.waiting.remove(client.userHash);
                    manager.open(client, session);
                } else {
                    session.sendRank(manager.rank(client, now));
                }
                return;
            }
            if (manager.askedTooOften(session, file, now)) {
                return;
            }
            int sameIp = 0;
            for (Client c : manager.waiting.values()) {
                if (c.ip.equals(ip)) {
                    sameIp++;
                }
            }
            if ((manager.waiting.size() >= manager.settings.get().queueSize()
                            && !manager.hasFriendSlot(
                                    session.userHash(), session.trust().credits()))
                    || sameIp >= MAX_WAITING_PER_IP) {
                return;
            }
            client = new Client(session, file, now);
            if (manager.waiting.isEmpty() && manager.canOpenSlot(false)) {
                manager.open(client, session);
                return;
            }
            manager.waiting.put(client.userHash, client);
            session.sendRank(manager.rank(client, now));
        }

        @Override
        public void sourcesRequested(UploadSession session, SourceExchange.Request request) {
            UploadManager.this.answerSources(session, request);
        }

        @Override
        public void reachabilityConfirmed(UploadSession session) {
            if (session.address().getAddress() instanceof Inet4Address ip) {
                UploadManager.this.reachable.accept(Ipv4.fromBytes(ip.getAddress()));
            }
        }

        @Override
        public void udpCheckRequested(UploadSession session, LowIdMessages.UdpCheckRequest request) {
            InetAddress from = session.address().getAddress();
            if (from instanceof Inet4Address ip) {
                UploadManager.this.udpChecks.requested(
                        Ipv4.fromBytes(ip.getAddress()), request, UploadManager.this.knows(from));
            }
        }

        @Override
        public void chat(UploadSession session, Packet packet) {
            UploadManager.this.chat.incoming(
                    session.address(),
                    session.peer(),
                    session.trust().credits(),
                    session::send,
                    session::isOpen,
                    packet);
        }

        @Override
        public void blocksRequested(UploadSession session) {
            UploadManager.this.slotOf(session).ifPresent(slot -> {
                slot.requested = true;
                slot.idleSince = UploadManager.this.ticker.read();
                UploadManager.this.read(slot);
            });
        }

        @Override
        public void stopped(UploadSession session, String reason) {
            slotGone(session);
        }

        private void slotGone(UploadSession session) {
            UploadManager manager = UploadManager.this;
            manager.slotOf(session).ifPresent(slot -> {
                manager.slots.remove(slot);
                manager.slotEnded(slot);
            });
        }

        @Override
        public void closed(UploadSession session) {
            UploadManager manager = UploadManager.this;
            slotGone(session);
            for (Client c : manager.waiting.values()) {
                if (c.session == session) {
                    c.trusted = session.trust().credits();
                    c.session = null;
                }
            }
        }

        @Override
        public void misbehaved(UploadSession session, String reason) {
            UploadManager.this.ban(session.address().getAddress(), UploadManager.this.ticker.read(), reason);
        }

        @Override
        public boolean isOurs(Hash16 file) {
            UploadManager manager = UploadManager.this;
            return manager.shared.apply(file).isPresent()
                    || manager.downloads.apply(file).isPresent();
        }

        // Dropped once for it, an address is banned the second time within a while.
        @Override
        public void askedForUnknownFiles(UploadSession session) {
            UploadManager manager = UploadManager.this;
            long now = manager.ticker.read();
            InetAddress ip = session.address().getAddress();
            if (manager.requests.unknownFiles(ip, now) >= UNKNOWN_FILES_DROPS) {
                manager.ban(ip, now, "asked for files we do not have again and again");
            }
        }

        // A request with the user hash of a known client comes from that client when it comes
        // from the same address, or from a new one with the client's key proven; anyone else
        // claiming the hash would take its slot, place and credits.
        private static boolean sameClient(Client client, UploadSession session) {
            InetAddress ip = session.address().getAddress();
            if (client.ip.equals(ip)) {
                return true;
            }
            if (session.trust() == Identities.Trust.IDENTIFIED) {
                client.ip = ip;
                return true;
            }
            logger.debug("{} claims the user hash of the client at {}", ip, client.ip);
            return false;
        }
    }
}
