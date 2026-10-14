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
import org.njmule.core.protocol.ed2k.Hello;
import org.njmule.core.protocol.ed2k.SourceExchange;
import org.njmule.core.protocol.io.DecodeException;
import org.njmule.core.protocol.packet.Packet;
import org.njmule.core.protocol.packet.Packing;
import org.njmule.core.protocol.packet.TcpFraming;
import org.njmule.core.util.Ipv4;
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

    /** Clients asking again sooner than this are counted; at {@code BAD_LIMIT} they are banned. */
    public static final Duration MIN_REQUEST_TIME = Duration.ofMinutes(10);

    public static final Duration BAN_TIME = Duration.ofHours(2);

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
    private static final Duration CREDITS_SAVE = Duration.ofMinutes(13);
    private static final Duration SX_RARE_INTERVAL = Duration.ofMinutes(40);
    private static final Duration SX_INTERVAL = Duration.ofMinutes(160);
    private static final int SX_RARE_SOURCES = 50;
    private static final int SX_PACK_ABOVE = 354;
    private static final int HEADER = TcpFraming.HEADER_SIZE;
    private static final int MIN_COMPRESSIBLE = 32;
    private static final Logger logger = LoggerFactory.getLogger(UploadManager.class);
    private static final int RATE_SECONDS = 5;

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
        private long lastAsk;
        private int bad;
        private boolean connecting;
        private boolean nextConnect;
        // Whether credits applied when it was last connected, from its address above.
        private boolean trusted;

        Client(UploadSession session, Uploadable file, long now) {
            this.userHash = session.userHash();
            this.ip = session.address().getAddress();
            this.port = session.peer().tcpPort();
            this.lowId = session.peer().clientId() != 0 && session.peer().clientId() < 0x0100_0000L;
            this.crypt = (session.peer().miscOptions2() & Hello.MISC2_SUPPORTS_CRYPT) != 0;
            this.hello = session.peer();
            this.file = file;
            this.session = session;
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
            return this.reading || !this.out.isEmpty() || this.session.hasPendingBlocks();
        }
    }

    private final EngineLoop loop;
    private final Function<Hash16, Optional<SharedFiles.SharedFile>> shared;
    private final Function<Hash16, Optional<AichTree>> aichTrees;
    private final BiFunction<Hash16, Boolean, List<SharedFiles.SharedFile>> visibleTo;
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
    private final Map<Hash16, Long> sourcesAnswered = new HashMap<>();
    private final Function<Hash16, List<SourceExchange.Source>> downloadSources;
    private final IntConsumer reachable;
    private final SessionEvents events = new SessionEvents();
    private final RateMeter rate = new RateMeter(RATE_SECONDS);
    private ChatSink chat = (address, peer, proven, reply, open, packet) -> false;

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
    private long nextCreditsSave;
    private int turn;

    /**
     * @param shared the shared file with a hash, if any
     * @param downloads the download of a hash, whose checked parts are shared too
     * @param downloadSources the sources an active download of a hash knows
     * @param reachable told the address (host order) of a Kad node that could reach us
     * @param visibleTo the shared files a client may browse, by its user hash and whether its identity is proven
     * @param friends whether a user hash is one of a friend's
     */
    public UploadManager(
            EngineLoop loop,
            Function<Hash16, Optional<SharedFiles.SharedFile>> shared,
            Function<Hash16, Optional<AichTree>> aichTrees,
            BiFunction<Hash16, Boolean, List<SharedFiles.SharedFile>> visibleTo,
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
    }

    /** Starts serving the queue. */
    public void start() {
        this.ticking = this.loop.scheduleRepeating("uploads", TICK, TICK, this::tick);
    }

    /** Slots and waiting clients, for diagnostics. */
    public String describe() {
        StringBuilder text = new StringBuilder("waiting " + this.waiting.size());
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
        List<Client> queue = new ArrayList<>(this.waiting.values());
        queue.sort(Comparator.comparingDouble((Client c) -> this.score(c, now)).reversed());
        for (Client client : queue) {
            all.add(info(client, UploadInfo.State.QUEUED, 0, 0, now));
        }
        return all;
    }

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
        if (this.isBanned(link.remoteAddress().getAddress())) {
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

    private void tick() {
        long now = this.ticker.read();
        int limit = this.settings.get().maxUploadKiBps();
        // Nothing carries over from unlimited: the sum would overflow and stop the uploads.
        long carried = this.budget == Long.MAX_VALUE ? 0 : this.budget;
        this.budget = limit == 0 ? Long.MAX_VALUE : Math.min(carried + limit * 1024L / 10, limit * 1024L);
        this.bans.values().removeIf(until -> now - until >= 0);
        this.waiting
                .values()
                .removeIf(c -> now - c.lastAsk > PURGE_AFTER.toNanos()
                        || this.find(c.file.hash()).isEmpty());
        for (Slot slot : List.copyOf(this.slots)) {
            this.read(slot);
            if (slot.sent >= SESSION_LIMIT
                    && !this.waiting.isEmpty()
                    && slot.out.isEmpty()
                    && !slot.reading
                    && !this.hasFriendSlot(
                            slot.client.userHash, slot.session.trust().credits())) {
                this.requeue(slot, now);
            } else if (slot.isBusy()) {
                slot.idleSince = now;
            } else if (!this.waiting.isEmpty()
                    && now - slot.idleSince > (slot.requested ? IDLE_SLOT : FIRST_REQUEST_WAIT).toNanos()) {
                // A client that holds a slot without asking for data we have keeps others waiting.
                logger.debug("{} left its upload slot idle", slot.session.address());
                this.requeue(slot, now);
            }
        }
        this.send();
        this.fillSlots(now);
        if (now - this.nextCreditsSave >= 0) {
            this.nextCreditsSave = now + CREDITS_SAVE.toNanos();
            this.credits.save();
        }
    }

    // One data packet per slot in turn, while the budget lasts: every slot gets an equal share.
    private void send() {
        boolean progress = true;
        while (this.budget > 0 && progress && !this.slots.isEmpty()) {
            progress = false;
            for (int i = 0; i < this.slots.size() && this.budget > 0; i++) {
                Slot slot = this.slots.get((this.turn + i) % this.slots.size());
                Item item = slot.out.poll();
                if (item == null) {
                    continue;
                }
                progress = true;
                int wire = item.packet().length() + HEADER;
                if (this.budget != Long.MAX_VALUE) {
                    this.budget -= wire;
                }
                if (slot.session.send(item.packet())) {
                    slot.sent += item.payload();
                    long now = this.ticker.read();
                    slot.rate.add(item.payload(), now);
                    this.rate.add(item.payload(), now);
                    if (slot.session.trust().credits()) {
                        this.credits.uploaded(slot.client.userHash, wire);
                    }
                }
                if (slot.out.isEmpty()) {
                    this.read(slot);
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
        UploadSession.Block block = next.get();
        Uploadable file = block.file();
        slot.client.file = file;
        slot.reading = true;
        this.loop.whenDone(
                Blocks.read(file.data(), block.start(), Math.toIntExact(block.end() - block.start())),
                (data, failure) -> this.blockRead(slot, file, block, data, failure));
    }

    private void blockRead(Slot slot, Uploadable file, UploadSession.Block block, byte[] data, Throwable failure) {
        slot.reading = false;
        if (!this.slots.contains(slot)) {
            return;
        }
        if (failure != null) {
            logger.warn("Cannot read {} for an upload: {}", file.name(), failure.getMessage());
            this.slots.remove(slot);
            slot.session.close();
            return;
        }
        boolean compress = file.compressible()
                && data.length >= MIN_COMPRESSIBLE
                && (slot.session.peer().miscOptions1() >>> 20 & 0x0F) != 0;
        List<Packet> packets = Blocks.packets(file.hash(), block.start(), data, compress);
        for (int i = 0; i < packets.size(); i++) {
            slot.out.add(new Item(packets.get(i), i == packets.size() - 1 ? data.length : 0));
        }
    }

    // About one part sent while others wait: the client waits again, from now.
    private void requeue(Slot slot, long now) {
        this.slots.remove(slot);
        slot.session.endSlot();
        slot.session.setReadTimeout(WAITING_TIMEOUT);
        Client client = slot.client;
        client.session = slot.session;
        client.waitStart = now;
        client.lastAsk = now;
        this.waiting.put(client.userHash, client);
        slot.session.sendRank(this.rank(client, now));
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
        return this.bans.remove(ip) != null;
    }

    /** Whether {@code ip} is banned; from any thread. */
    public boolean isBanned(InetAddress ip) {
        Long until = this.bans.get(ip);
        return until != null && this.ticker.read() - until < 0;
    }

    // Clients waiting from a banned address leave the queue; a slot it holds runs to its end, as
    // other clients may share the address.
    private void ban(InetAddress ip, long now, String reason) {
        logger.debug("Banning {}: {}", ip, reason);
        this.bans.put(ip, now + BAN_TIME.toNanos());
        this.waiting.values().removeIf(c -> {
            if (!c.ip.equals(ip)) {
                return false;
            }
            if (c.session != null && this.slotOf(c.session).isEmpty()) {
                c.session.disconnect();
            }
            return true;
        });
    }

    private void fillSlots(long now) {
        while (!this.waiting.isEmpty() && this.canOpenSlot(false)) {
            Client best = null;
            double bestScore = -1;
            for (Client c : this.waiting.values()) {
                boolean reachable = c.session != null && c.session.isOpen();
                if (c.connecting || (!reachable && c.lowId)) {
                    continue;
                }
                double score = this.score(c, now);
                if (score > bestScore) {
                    best = c;
                    bestScore = score;
                }
            }
            if (best == null) {
                return;
            }
            if (best.session != null && best.session.isOpen()) {
                this.waiting.remove(best.userHash);
                this.open(best, best.session);
            } else {
                this.connect(best);
                return;
            }
        }
    }

    private void open(Client client, UploadSession session) {
        Slot slot = new Slot(client, session, this.ticker.read());
        this.slots.add(slot);
        client.session = session;
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

    // The friend with the friend slot, unless it has a LowID, comes before anyone and keeps its slot; only with its
    // identity trusted, as anyone can send a user hash.
    private boolean hasFriendSlot(Hash16 userHash, boolean trusted) {
        return trusted
                && this.settings.get().friendSlot().filter(userHash::equals).isPresent();
    }

    private double score(Client client, long now) {
        boolean trusted = client.session != null ? client.session.trust().credits() : client.trusted;
        if (!client.lowId && this.hasFriendSlot(client.userHash, trusted)) {
            return Double.MAX_VALUE;
        }
        double waited = Math.max(0, now - client.waitStart) / 1e9;
        double ratio = this.credits.ratio(client.userHash, trusted);
        return waited * ratio * this.priority(client.file.hash()) / 10;
    }

    /** Where the upload priority of a shared file comes from; without it every file is AUTO. */
    public void priorities(Function<Hash16, UploadPriority> priorities) {
        this.priorities = Objects.requireNonNull(priorities, "priorities");
    }

    // The factor of a file's level, in tenths.
    private int priority(Hash16 file) {
        return switch (this.priorities.apply(file)) {
            case VERY_LOW -> 2;
            case LOW -> 6;
            case NORMAL -> 7;
            case HIGH -> 9;
            case RELEASE -> 18;
            case AUTO -> this.automatic(file);
        };
    }

    // Files many clients want get a lower priority, so rare files spread first.
    private int automatic(Hash16 file) {
        int requesters = 0;
        for (Client c : this.waiting.values()) {
            if (c.file.hash().equals(file)) {
                requesters++;
            }
        }
        for (Slot s : this.slots) {
            if (s.client.file.hash().equals(file)) {
                requesters++;
            }
        }
        return requesters > 20 ? 6 : requesters > 1 ? 7 : 9;
    }

    private int rank(Client client, long now) {
        double score = this.score(client, now);
        int rank = 1;
        for (Client c : this.waiting.values()) {
            if (c != client && this.score(c, now) > score) {
                rank++;
            }
        }
        return rank;
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
                if (!this.loop.postOutcome(() -> this.connected(client, opened))) {
                    opened.close();
                }
            } catch (IOException e) {
                this.loop.postOutcome(() -> this.unreachable(client, e.getMessage()));
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
        this.open(client, session);
    }

    // A client may ask once, then again after 40 minutes for rare downloads or 160 for the rest.
    private void answerSources(UploadSession session, SourceExchange.Request request) {
        long now = this.ticker.read();
        Optional<Uploadable> file = this.find(request.file());
        if (file.isEmpty() || request.version() < 1) {
            return;
        }
        List<SourceExchange.Source> sources = new ArrayList<>();
        Optional<DownloadQueue.Download> download = this.downloads.apply(request.file());
        if (download.isPresent() && download.get().isActive()) {
            sources.addAll(this.downloadSources.apply(request.file()));
        }
        boolean rare = download.isPresent() && sources.size() <= SX_RARE_SOURCES;
        Long last = this.sourcesAnswered.get(session.userHash());
        long wait = (rare ? SX_RARE_INTERVAL : SX_INTERVAL).toNanos();
        if (last != null && now - last < wait) {
            return;
        }
        if (sources.isEmpty()) {
            for (Client c : this.uploaders(request.file())) {
                if (!c.lowId && c.ip instanceof Inet4Address ip4) {
                    sources.add(new SourceExchange.Source(
                            Ipv4.fromBytes(ip4.getAddress()), 0, c.port, 0, 0, Optional.of(c.userHash), 0));
                }
            }
        }
        sources.removeIf(s -> s.userHash().map(session.userHash()::equals).orElse(false));
        if (sources.isEmpty()) {
            return;
        }
        this.sourcesAnswered.put(session.userHash(), now);
        List<SourceExchange.Source> sent = sources.subList(0, Math.min(sources.size(), SourceExchange.MAX_SOURCES));
        int version = Math.min(request.version(), SourceExchange.VERSION);
        Packet answer = new SourceExchange.Answer(version, request.file(), sent).encode();
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
        public List<SharedFiles.SharedFile> visibleTo(Hash16 userHash, boolean proven) {
            return UploadManager.this.visibleTo.apply(userHash, proven);
        }

        @Override
        public boolean isFriend(Hash16 userHash) {
            return UploadManager.this.friends.test(userHash);
        }

        @Override
        public Optional<Hash20> aichMaster(Hash16 file) {
            return UploadManager.this.aichTrees.apply(file).map(AichTree::masterHash);
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
        public void requested(UploadSession session, Uploadable file) {
            UploadManager manager = UploadManager.this;
            long now = manager.ticker.read();
            InetAddress ip = session.address().getAddress();
            if (manager.isBanned(ip)) {
                return;
            }
            for (Slot slot : manager.slots) {
                if (slot.client.userHash.equals(session.userHash())) {
                    if (!sameClient(slot.client, session)) {
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
                if (!sameClient(client, session)) {
                    return;
                }
                client.bad = now - client.lastAsk < MIN_REQUEST_TIME.toNanos()
                        ? client.bad + 1
                        : Math.max(0, client.bad - 1);
                client.lastAsk = now;
                if (client.bad >= BAD_LIMIT) {
                    manager.ban(ip, now, "asked again too often");
                    return;
                }
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
            UploadManager.this.slotOf(session).ifPresent(UploadManager.this.slots::remove);
        }

        @Override
        public void closed(UploadSession session) {
            UploadManager manager = UploadManager.this;
            manager.slotOf(session).ifPresent(manager.slots::remove);
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
