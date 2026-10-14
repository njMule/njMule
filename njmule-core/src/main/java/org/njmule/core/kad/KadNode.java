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
package org.njmule.core.kad;

import com.google.common.base.Ticker;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.IntPredicate;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;
import org.njmule.core.api.FileComment;
import org.njmule.core.api.Hash16;
import org.njmule.core.api.KadContactInfo;
import org.njmule.core.api.KadSearchInfo;
import org.njmule.core.api.SearchResult;
import org.njmule.core.persist.AtomicFiles;
import org.njmule.core.persist.format.KadIndexFiles;
import org.njmule.core.persist.format.NodesDat;
import org.njmule.core.protocol.io.DecodeException;
import org.njmule.core.protocol.io.PacketReader;
import org.njmule.core.protocol.io.PacketWriter;
import org.njmule.core.protocol.kad.KadMessages;
import org.njmule.core.protocol.packet.Opcodes;
import org.njmule.core.protocol.packet.Packet;
import org.njmule.core.protocol.tag.FileTag;
import org.njmule.core.protocol.tag.MediaTags;
import org.njmule.core.protocol.tag.Tag;
import org.njmule.core.protocol.tag.TagName;
import org.njmule.core.protocol.tag.Tags;
import org.njmule.core.search.SearchExpr;
import org.njmule.core.search.SearchTreeCodec;
import org.njmule.core.util.Ipv4;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Our Kad node: answers other nodes, keeps the routing table fresh, runs lookups for searches and publishes, indexes
 * what others publish to us and checks whether we can be reached. Engine thread only.
 */
public final class KadNode implements KadPublisher.Node, KadNotes.Finder, AutoCloseable {

    /** Sends Kad packets, obfuscated with the receiver's id or key when given. */
    public interface Transport {
        void send(int ip, int port, Packet packet, Optional<UInt128> receiverId, int receiverKey, int senderKey);
    }

    /** What the node needs from the client around it. */
    public interface Host {
        /** Connects to {@code ip:port} over TCP to prove to that node it is reachable. */
        void checkReachable(int ip, int port, Hash16 userHash, int connectOptions);

        /**
         * Connects to the helper at {@code ip:tcpPort} and asks it to send a datagram to our UDP ports, obfuscated with
         * {@code senderKey}; then tells {@link KadNode#udpCheckEnded} whether the request went out.
         */
        default void checkUdp(int ip, int tcpPort, int internalPort, int externalPort, int senderKey) {}

        /** Whether the client at {@code ip} deals with us already (a download, an upload): it cannot be a helper. */
        default boolean isKnownClient(int ip) {
            return false;
        }

        /** Whether other clients reach us anyway, through a HighID on the server. */
        default boolean hasHighId() {
            return false;
        }
    }

    /**
     * The TCP side of buddies: the link to our buddy while we are firewalled, or to the firewalled node we serve. A
     * buddy relays the callback requests of other clients to the node it serves.
     */
    public interface Buddies {
        /** No buddy, ours being connected to, ours connected, or a node we serve. */
        enum State {
            NONE,
            CONNECTING,
            CONNECTED,
            SERVING
        }

        Buddies NONE = new Buddies() {};

        default State state() {
            return State.NONE;
        }

        /** The address ({@code Ipv4} form) of our buddy, 0 without one. */
        default int buddyIp() {
            return 0;
        }

        /** The UDP port of our buddy, 0 without one. */
        default int buddyUdpPort() {
            return 0;
        }

        /** The node at {@code ip} answered our search and offers to be our buddy. */
        default void offered(int ip, int tcpPort, int udpPort, Hash16 userHash, int connectOptions) {}

        /** The firewalled node at {@code ip} asks us to be its buddy; {@code false} when we cannot now. */
        default boolean asked(int ip, int tcpPort, int udpPort, Hash16 userHash, UInt128 buddyId) {
            return false;
        }

        /**
         * Relays the callback request of the client at {@code requesterIp} to the node we serve, when {@code buddyId}
         * is its; {@code false} otherwise.
         */
        default boolean relay(UInt128 buddyId, UInt128 file, int requesterIp, int requesterPort) {
            return false;
        }

        /** The link is no longer needed. */
        default void release() {}
    }

    /** The buddy of a firewalled source, which relays callback requests to it. */
    public record Buddy(int ip, int udpPort, UInt128 id) {
        public Buddy {
            Objects.requireNonNull(id, "id");
        }
    }

    /**
     * A source a Kad search found. A firewalled one connects to us when asked: through its {@code buddy}, or directly
     * at its Kad {@code udpPort} when it takes {@code directCallback}s.
     */
    public record Source(
            Hash16 userHash,
            int ip,
            int tcpPort,
            int connectOptions,
            int udpPort,
            Optional<Buddy> buddy,
            boolean directCallback) {
        public Source {
            Objects.requireNonNull(userHash, "userHash");
            Objects.requireNonNull(buddy, "buddy");
        }

        /** A source reachable directly. */
        public Source(Hash16 userHash, int ip, int tcpPort, int connectOptions) {
            this(userHash, ip, tcpPort, connectOptions, 0, Optional.empty(), false);
        }

        /** Whether the source is firewalled and reached only by a callback. */
        public boolean isFirewalled() {
            return this.buddy.isPresent() || this.directCallback;
        }
    }

    /** A file a keyword search found. */
    public record FileResult(
            Hash16 hash, String name, long size, int sources, Optional<String> type, SearchResult.Media media) {}

    /** A note a search found: what the node {@code publisher} said of a file, under the name it gave the file. */
    public record Note(UInt128 publisher, String fileName, long size, FileComment comment) {
        public Note {
            Objects.requireNonNull(publisher, "publisher");
            Objects.requireNonNull(fileName, "fileName");
            Objects.requireNonNull(comment, "comment");
        }
    }

    /** Our note being published; the comment is read again for each node, and an empty one ends the publish. */
    private record OwnNote(String fileName, long size, Supplier<Optional<FileComment>> comment) {}

    /**
     * Settings that are not the protocol's.
     *
     * @param trusted addresses taken although they are not public (tests, LANs)
     * @param blocked addresses the IP filter refuses, never taken as contacts
     * @param udpPort the UDP port we listen on, 0 when unknown: the UDP firewall test needs it
     */
    public record Settings(
            int tcpPort, Hash16 userHash, int connectOptions, IntPredicate trusted, IntPredicate blocked, int udpPort) {
        public Settings {
            Objects.requireNonNull(userHash, "userHash");
            Objects.requireNonNull(trusted, "trusted");
            Objects.requireNonNull(blocked, "blocked");
        }

        public Settings(int tcpPort, Hash16 userHash, int connectOptions, IntPredicate trusted, IntPredicate blocked) {
            this(tcpPort, userHash, connectOptions, trusted, blocked, 0);
        }
    }

    public static final String NODES_FILE = "nodes.dat";

    /** The files of what other nodes published to us, and of our keywords whose nodes are loaded. */
    public static final String KEYWORD_INDEX_FILE = "key_index.dat";

    public static final String SOURCE_INDEX_FILE = "src_index.dat";
    public static final String NOTE_INDEX_FILE = "notes_index.dat";
    public static final String LOAD_INDEX_FILE = "load_index.dat";

    /** A node counts as connected while packets keep arriving. */
    static final Duration CONNECTED_FOR = Duration.ofMinutes(20);

    private static final Duration QUIET_FOR = Duration.ofMinutes(15);

    // Fewer contacts than this do not replace a nodes.dat: a short outage must not lose a good one.
    static final int MIN_SAVED_CONTACTS = 25;

    // An index file larger than this is not read: the index holds far less within its own limits.
    private static final int MAX_INDEX_BYTES = 64 * 1024 * 1024;

    /** Contacts of a bootstrap-only nodes.dat kept to start from: those closest to our id. */
    static final int BOOTSTRAP_CONTACTS = 50;

    private static final Logger logger = LoggerFactory.getLogger(KadNode.class);
    private static final long SECOND = 1_000_000_000L;
    private static final long MINUTE = 60 * SECOND;
    private static final long HOUR = 60 * MINUTE;
    private static final Duration SELF_LOOKUP_FIRST = Duration.ofMinutes(3);
    private static final Duration SELF_LOOKUP_EVERY = Duration.ofHours(4);
    // Above this load the nodes of a keyword are spared: for up to a week, in proportion to the load.
    private static final int HIGH_LOAD = 20;
    private static final Duration LOADED_FOR = Duration.ofDays(7);
    private static final Duration FIREWALL_CHECK_EVERY = Duration.ofHours(1);
    private static final int FIREWALL_CHECKS = 4;
    private static final int FIREWALL_ACKS = 2;
    private static final int HELLO_ACK_VERSION = 8;
    // Nodes from this version on obfuscate with keys, which a ping proves; older ones only answer lookups.
    private static final int KEYS_VERSION = 7;
    // The contacts a lookup that challenges an old node asks for.
    private static final int CHALLENGE_CONTACTS = 2;
    private static final int OBFUSCATION_VERSION = 6;
    private static final int FIREWALL2_VERSION = 7;
    private static final int MISC_UDP_FIREWALLED = 0x01;
    private static final int MISC_TCP_FIREWALLED = 0x02;
    private static final int MISC_ACK_REQUESTED = 0x04;
    private static final int SOURCE_DIRECT = 1;
    private static final int SOURCE_BUDDY = 3;
    private static final int SOURCE_DIRECT_LARGE = 4;
    private static final int SOURCE_BUDDY_LARGE = 5;
    private static final int SOURCE_DIRECT_CALLBACK = 6;
    // More sources than any file has: a result saying so tells nothing.
    private static final long MAX_AVAILABILITY = 65_500;
    private static final int DNS_PORT = 53;
    private static final long LARGE_SOURCE = 4_290_048_000L;
    // Connect option bits.
    private static final int OBFUSCATION_REQUIRED = 0x04;
    private static final int DIRECT_CALLBACK = 0x08;
    private static final UInt128 ALL_ONES = new UInt128(-1L, -1L);
    // A firewalled node looks for a buddy a while after the start, when its state is known, then now and then.
    private static final Duration FIND_BUDDY_FIRST = Duration.ofMinutes(5);
    private static final Duration FIND_BUDDY_EVERY = Duration.ofMinutes(20);
    private static final Duration EXTERNAL_PORT_ASK_EVERY = Duration.ofSeconds(15);
    // A bootstrap contact is asked every so often while we are not connected, more often while we know no one.
    private static final Duration BOOTSTRAP_EVERY = Duration.ofSeconds(15);
    private static final Duration BOOTSTRAP_EVERY_ALONE = Duration.ofSeconds(2);
    // Lookups for UDP test helpers in one test at most, and how far apart.
    private static final int UDP_HELPER_LOOKUPS = 3;
    private static final Duration UDP_HELPER_LOOKUP_GAP = Duration.ofMinutes(1);
    // A node that proves another id too soon after the last is kept out this long.
    private static final Duration ID_CHANGE_BAN = Duration.ofHours(4);
    // A node is helped with its UDP test once in this time at most, and so many are remembered.
    private static final Duration UDP_HELP_GAP = Duration.ofMinutes(5);
    private static final int MAX_UDP_HELPED = 256;

    private final KadIdentity identity;
    private final RoutingTable table;
    private final PacketTracking tracking = new PacketTracking();
    private final ProvenIds provenIds = new ProvenIds();
    private final ResponseTimes responseTimes = new ResponseTimes();
    private final KadIndex index = new KadIndex();
    private final Transport transport;
    private final Host host;
    private final Settings settings;
    private final Path configDir;
    private final SecureRandom random;
    private final Ticker ticker;
    private final Map<UInt128, Lookup> lookups = new LinkedHashMap<>();
    // Where the results of a lookup go; each returns how many of them were usable.
    private final Map<Lookup, ToIntFunction<List<KadMessages.Entry>>> resultSinks = new HashMap<>();
    private final Map<Lookup, Object> lookupData = new HashMap<>();
    private final Map<Lookup, Runnable> endActions = new HashMap<>();
    // Nodes asked to connect to our TCP port, and when; read by the listener's threads too.
    private final Map<Integer, Long> firewallChecks = new ConcurrentHashMap<>();
    private final Set<Integer> firewallAcks = new HashSet<>();
    private final UdpFirewallTest udpTest = new UdpFirewallTest();
    // Nodes we sent datagrams for their UDP test, and when.
    private final Map<Integer, Long> udpHelped = new LinkedHashMap<>();
    // The contacts of a bootstrap-only nodes.dat, closest to our id first: asked, never added.
    private final List<KadMessages.Contact> bootstrapList = new ArrayList<>();
    private long lastBootstrap;
    private Buddies buddies = Buddies.NONE;
    private Buddies.State buddyState = Buddies.State.NONE;
    private boolean findBuddy;
    private long nextFindBuddy;
    private long nextExternalPortAsk;
    private long nextUdpHelperLookup;
    private int udpHelperLookups;
    private int publicIp;
    private int pendingIp;
    private long lastPacket;
    private long lastQuietRefresh;
    private long nextSmallTimer;
    private long nextBigTimer;
    private long nextConsolidate;
    private long nextSelfLookup;
    private long nextFirewallCheck;
    private long nextClean;
    private int firewallReports;
    private boolean selfLookupDone;
    private boolean wasOpen;

    public KadNode(
            KadIdentity identity,
            Transport transport,
            Host host,
            Settings settings,
            Path configDir,
            SecureRandom random,
            Ticker ticker) {
        this.identity = Objects.requireNonNull(identity, "identity");
        this.transport = Objects.requireNonNull(transport, "transport");
        this.host = Objects.requireNonNull(host, "host");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.configDir = Objects.requireNonNull(configDir, "configDir");
        this.random = Objects.requireNonNull(random, "random");
        this.ticker = Objects.requireNonNull(ticker, "ticker");
        this.table = new RoutingTable(identity.id(), settings.trusted());
        long now = ticker.read();
        this.nextSmallTimer = now + MINUTE;
        this.nextBigTimer = now + 10 * SECOND;
        this.nextConsolidate = now + 45 * MINUTE;
        this.nextSelfLookup = now + SELF_LOOKUP_FIRST.toNanos();
        this.nextFirewallCheck = now;
        this.nextClean = now + 12 * MINUTE;
        this.nextFindBuddy = now + FIND_BUDDY_FIRST.toNanos();
        this.lastBootstrap = now - BOOTSTRAP_EVERY.toNanos();
    }

    /** Where the buddy links are kept; none until set. */
    public void useBuddies(Buddies links) {
        this.buddies = Objects.requireNonNull(links, "links");
    }

    public UInt128 id() {
        return this.identity.id();
    }

    /** Contacts in the routing table. */
    public int contacts() {
        return this.table.size();
    }

    /** Contacts that proved who they are. */
    public int verifiedContacts() {
        int verified = 0;
        for (KadContact c : this.table.all()) {
            if (c.isVerified()) {
                verified++;
            }
        }
        return verified;
    }

    @Override
    public boolean isConnected() {
        return this.lastPacket != 0
                && this.ticker.read() - this.lastPacket < CONNECTED_FOR.toNanos()
                && this.table.size() > 0;
    }

    /**
     * Checks again whether other nodes can reach our TCP port, the next contacts heard from being asked at once, and
     * our UDP port.
     */
    public void recheckFirewall() {
        this.nextFirewallCheck = this.ticker.read();
    }

    /**
     * Whether nodes we never wrote to cannot send to our UDP port, as far as we know; while a test runs, the last
     * outcome stands. Unknown counts as open.
     */
    public boolean isFirewalledUdp() {
        return this.udpTest.isFirewalled(true);
    }

    /** Whether a UDP firewall test came to an outcome. */
    public boolean isUdpVerified() {
        return this.udpTest.isVerified();
    }

    /** Whether we take direct UDP callbacks: TCP-firewalled, with a UDP port proven open. */
    public boolean supportsDirectCallback() {
        return this.isFirewalled() && !this.isFirewalledUdp() && this.udpTest.isVerified();
    }

    /** The Kad port other clients should use: the one a NAT shows them once a test proved it, else ours. */
    public int announcedUdpPort() {
        int external = this.udpTest.externalPort();
        return external != 0 && this.udpTest.useExternalPort() && this.udpTest.isVerified()
                ? external
                : this.settings.udpPort();
    }

    /** What our buddy link is doing. */
    public Buddies.State buddyState() {
        return this.buddies.state();
    }

    /** Whether {@code buddyId} is the one our buddy was found by: the complement of our id. */
    public boolean isOurBuddyId(UInt128 buddyId) {
        return buddyId.xor(ALL_ONES).equals(this.identity.id());
    }

    // Firewalled as other clients see us: neither Kad nor the server gives us a HighID.
    private boolean isFirewalledForClients() {
        return this.isFirewalled() && !this.host.hasHighId();
    }

    /** Whether other clients can reach us, directly, through our buddy or by a direct callback. */
    @Override
    public boolean isReachable() {
        return !this.isFirewalledForClients()
                || this.buddies.state() == Buddies.State.CONNECTED
                || (!this.isFirewalledUdp() && this.udpTest.isVerified());
    }

    @Override
    public int buddyIp() {
        return this.buddies.state() == Buddies.State.CONNECTED ? this.buddies.buddyIp() : 0;
    }

    /** Whether other nodes cannot connect to our TCP port, as far as we know. */
    @Override
    public boolean isFirewalled() {
        boolean open = this.firewallAcks.size() >= FIREWALL_ACKS;
        // While a recheck runs, the last outcome stands.
        if (!open && this.firewallReports < FIREWALL_CHECKS && this.wasOpen) {
            return false;
        }
        return !open;
    }

    /** Our address as other nodes see it, 0 until one told us. */
    public int publicIp() {
        return this.publicIp;
    }

    /** Whether the first lookup of our own id is done, which publishing waits for. */
    @Override
    public boolean isReady() {
        return this.selfLookupDone;
    }

    /** Loads the contacts of {@code nodes.dat}, if any. */
    public void loadNodes() {
        Path file = this.configDir.resolve(NODES_FILE);
        if (!Files.exists(file)) {
            return;
        }
        try {
            NodesDat.Contents contents = NodesDat.read(Files.readAllBytes(file));
            int added = this.addContacts(contents);
            logger.info(
                    "{} Kad {} from {}",
                    added,
                    contents.bootstrap() ? "contacts to bootstrap from" : "contacts",
                    file.getFileName());
        } catch (IOException e) {
            logger.warn("Cannot read {}", file, e);
        }
    }

    /**
     * Adds the contacts of a {@code nodes.dat}, or keeps those of a bootstrap-only one to start from; returns how many
     * were new.
     */
    public int addContacts(NodesDat.Contents contents) {
        if (contents.bootstrap()) {
            return this.keepForBootstrap(contents.contacts());
        }
        boolean anyVerified = false;
        for (NodesDat.Contact c : contents.contacts()) {
            anyVerified |= c.verified();
        }
        long now = this.ticker.read();
        int added = 0;
        for (NodesDat.Contact c : contents.contacts()) {
            if (!this.isGoodAddress(c.ip())) {
                continue;
            }
            KadContact contact = new KadContact(c.id(), c.ip(), c.udpPort(), c.tcpPort(), c.version(), now);
            contact.setVerified(c.verified() || !anyVerified);
            if (this.table.add(contact, false, c.udpKey(), this.publicIp, now) == RoutingTable.Outcome.ADDED) {
                added++;
            }
        }
        return added;
    }

    // A list shipped with a client would flood the nodes on it if every user took them as contacts: they are only asked
    // for contacts, one at a time, and the closest to us give a start near our own zone.
    private int keepForBootstrap(List<NodesDat.Contact> contacts) {
        UInt128 own = this.identity.id();
        Set<UInt128> known = new HashSet<>();
        this.bootstrapList.forEach(c -> known.add(c.id()));
        int before = this.bootstrapList.size();
        for (NodesDat.Contact c : contacts) {
            if (this.isGoodAddress(c.ip())
                    && c.udpPort() != 0
                    && c.version() > 1
                    && !(c.udpPort() == DNS_PORT && c.version() <= 5)
                    && known.add(c.id())) {
                this.bootstrapList.add(new KadMessages.Contact(c.id(), c.ip(), c.udpPort(), c.tcpPort(), c.version()));
            }
        }
        this.bootstrapList.sort(Comparator.comparing(c -> c.id().xor(own)));
        while (this.bootstrapList.size() > BOOTSTRAP_CONTACTS) {
            this.bootstrapList.removeLast();
        }
        return Math.max(0, this.bootstrapList.size() - before);
    }

    // The next bootstrap contact is asked while nothing tells us we are connected.
    private void bootstrapFromList(long now) {
        long gap = (this.table.size() == 0 ? BOOTSTRAP_EVERY_ALONE : BOOTSTRAP_EVERY).toNanos();
        if (this.bootstrapList.isEmpty() || this.isConnected() || now - this.lastBootstrap < gap) {
            return;
        }
        this.lastBootstrap = now;
        KadMessages.Contact c = this.bootstrapList.removeFirst();
        Optional<UInt128> key = c.version() >= OBFUSCATION_VERSION ? Optional.of(c.id()) : Optional.empty();
        this.send(c.ip(), c.udpPort(), KadMessages.bootstrapRequest(), key, 0);
        this.tracking.sent(c.ip(), Opcodes.Kad.BOOTSTRAP_REQ, this.nowMs());
    }

    /** Looks for contacts at once rather than at the next rounds: when asked to connect, or after new ones came. */
    public void lookForContacts() {
        long now = this.ticker.read();
        this.nextSmallTimer = now;
        this.nextBigTimer = now;
        this.nextSelfLookup = now;
        for (RoutingTable.Zone zone : this.table.leaves()) {
            zone.setNextBigTimer(now);
        }
    }

    /** Writes the best contacts to {@code nodes.dat}. */
    public void saveNodes() {
        List<NodesDat.Contact> records = new ArrayList<>();
        for (KadContact c : this.table.bootstrapContacts(NodesDat.MAX_WRITTEN, this.random)) {
            records.add(c.record());
        }
        Path file = this.configDir.resolve(NODES_FILE);
        if (records.isEmpty() || (records.size() < MIN_SAVED_CONTACTS && Files.exists(file))) {
            logger.debug("Keeping {}: only {} contacts", file.getFileName(), records.size());
            return;
        }
        try {
            AtomicFiles.writeBytes(file, NodesDat.write(records));
        } catch (IOException e) {
            logger.warn("Cannot save the Kad contacts", e);
        }
    }

    /** Writes what other nodes published to us. */
    public void saveIndex() {
        this.saveIndex(System.currentTimeMillis() / 1000);
    }

    void saveIndex(long wallSeconds) {
        long now = this.ticker.read();
        this.writeIndex(
                KEYWORD_INDEX_FILE,
                KadIndexFiles.writeKeywords(this.index.keywordFile(this.identity.id(), wallSeconds, now)));
        this.writeIndex(SOURCE_INDEX_FILE, KadIndexFiles.writeSources(this.index.sourceFile(wallSeconds, now)));
        this.writeIndex(NOTE_INDEX_FILE, KadIndexFiles.writeSources(this.index.noteFile(wallSeconds, now)));
        this.writeIndex(LOAD_INDEX_FILE, KadIndexFiles.writeLoad(this.index.loadFile(wallSeconds, now)));
    }

    private void writeIndex(String name, byte[] data) {
        try {
            AtomicFiles.writeBytes(this.configDir.resolve(name), data);
        } catch (IOException e) {
            logger.warn("Cannot save {}", name, e);
        }
    }

    /** Loads what other nodes published to us before, as far as it is still alive. */
    public void loadIndex() {
        this.loadIndex(System.currentTimeMillis() / 1000);
    }

    // Read with the limits of live publishes: our tolerance, the IP filter and the index's own caps.
    void loadIndex(long wallSeconds) {
        long now = this.ticker.read();
        IntPredicate refused = this.settings.blocked();
        this.readIndex(KEYWORD_INDEX_FILE, KadIndexFiles::readKeywords)
                .ifPresent(f -> this.index.restoreKeywords(f, this.identity.id(), refused, wallSeconds, now));
        this.readIndex(SOURCE_INDEX_FILE, KadIndexFiles::readSources)
                .ifPresent(f -> this.index.restoreSources(f, this::inTolerance, refused, wallSeconds, now));
        this.readIndex(NOTE_INDEX_FILE, KadIndexFiles::readSources)
                .ifPresent(f -> this.index.restoreNotes(f, this::inTolerance, refused, wallSeconds, now));
        this.readIndex(LOAD_INDEX_FILE, KadIndexFiles::readLoad)
                .ifPresent(f -> this.index.restoreLoads(f, LOADED_FOR, wallSeconds, now));
        int[] held = this.indexed();
        logger.info("Kad index: {} keyword entries, {} sources, {} notes", held[0], held[1], this.indexedNotes());
    }

    /** Decodes the bytes of an index file. */
    private interface IndexReader<T> {
        T read(byte[] data) throws DecodeException;
    }

    // A damaged or oversized file is left out: the index fills again as nodes publish.
    private <T> Optional<T> readIndex(String name, IndexReader<T> reader) {
        Path file = this.configDir.resolve(name);
        if (!Files.exists(file) && !Files.exists(AtomicFiles.bakPath(file))) {
            return Optional.empty();
        }
        try {
            return Optional.of(AtomicFiles.read(file, in -> {
                byte[] data = in.readNBytes(MAX_INDEX_BYTES + 1);
                if (data.length > MAX_INDEX_BYTES) {
                    throw new DecodeException("larger than " + MAX_INDEX_BYTES + " bytes");
                }
                return reader.read(data);
            }));
        } catch (IOException e) {
            logger.warn("Cannot read {}: {}", name, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public void close() {
        this.saveNodes();
        this.saveIndex();
    }

    /** Asks the node at {@code ip:port} for contacts to start from. */
    public void bootstrap(int ip, int port) {
        this.send(ip, port, KadMessages.bootstrapRequest(), Optional.empty(), 0);
        this.tracking.sent(ip, Opcodes.Kad.BOOTSTRAP_REQ, this.nowMs());
    }

    /**
     * Looks for files under the first keyword of {@code words}, filtered by {@code expression} there and here; the
     * handle ends this search and no later one for the same keyword. Empty when the search cannot start now.
     */
    public Optional<Runnable> searchKeyword(
            String words, Optional<SearchExpr> expression, Consumer<List<FileResult>> results) {
        List<String> keywords = Keywords.of(words);
        if (keywords.isEmpty()) {
            return Optional.empty();
        }
        UInt128 target = Keywords.id(keywords.getFirst());
        if (this.lookups.containsKey(target)) {
            return Optional.empty();
        }
        byte[] tree = new byte[0];
        if (expression.isPresent()) {
            PacketWriter out = new PacketWriter();
            SearchTreeCodec.write(out, expression.get(), true);
            tree = out.toByteArray();
        }
        Lookup lookup = this.startLookup(target, Lookup.Kind.KEYWORD);
        if (lookup == null) {
            return Optional.empty();
        }
        this.lookupData.put(lookup, tree);
        Optional<KadIndex.Matcher> matcher = expression.map(KadIndex.Matcher::new);
        this.resultSinks.put(lookup, entries -> {
            List<FileResult> found = fileResults(entries, keywords, matcher);
            results.accept(found);
            return found.size();
        });
        // Ending a lookup that already ended leaves the one now running for the keyword alone.
        return Optional.of(() -> this.lookupEnded(lookup));
    }

    /** Looks for sources of the file {@code hash} of {@code size} bytes. */
    public boolean findSources(Hash16 hash, long size, Consumer<List<Source>> sources) {
        UInt128 target = UInt128.of(hash);
        if (this.lookups.containsKey(target)) {
            return false;
        }
        Lookup lookup = this.startLookup(target, Lookup.Kind.SOURCES);
        if (lookup == null) {
            return false;
        }
        this.lookupData.put(lookup, size);
        this.resultSinks.put(lookup, entries -> {
            List<Source> found = sourceResults(entries);
            sources.accept(found);
            return found.size();
        });
        return true;
    }

    /**
     * Looks for the notes on the file {@code hash} of {@code size} bytes: each publisher's note comes once, then
     * {@code ended} runs. False when the search cannot start now.
     */
    @Override
    public boolean findNotes(Hash16 hash, long size, Consumer<List<Note>> notes, Runnable ended) {
        UInt128 target = UInt128.of(hash);
        if (this.lookups.containsKey(target)) {
            return false;
        }
        Lookup lookup = this.startLookup(target, Lookup.Kind.NOTES);
        if (lookup == null) {
            return false;
        }
        this.lookupData.put(lookup, size);
        Set<UInt128> publishers = new HashSet<>();
        this.resultSinks.put(lookup, entries -> {
            List<Note> found = noteResults(entries, size, publishers);
            if (!found.isEmpty()) {
                notes.accept(found);
            }
            return found.size();
        });
        this.endActions.put(lookup, ended);
        return true;
    }

    /** Publishes {@code files} under the keyword {@code word}; not while its nodes are loaded. */
    @Override
    public boolean publishKeyword(String word, List<KadMessages.Entry> files) {
        UInt128 target = Keywords.id(word);
        if (files.isEmpty() || this.lookups.containsKey(target) || !this.index.mayStore(target, this.ticker.read())) {
            return false;
        }
        Lookup lookup = this.startLookup(target, Lookup.Kind.STORE_KEYWORD);
        if (lookup != null) {
            this.lookupData.put(lookup, List.copyOf(files));
        }
        return lookup != null;
    }

    /** Publishes us as a source of the file {@code hash} of {@code size} bytes. */
    @Override
    public boolean publishSource(Hash16 hash, long size) {
        UInt128 target = UInt128.of(hash);
        if (this.lookups.containsKey(target)) {
            return false;
        }
        Lookup lookup = this.startLookup(target, Lookup.Kind.STORE_SOURCE);
        if (lookup != null) {
            this.lookupData.put(lookup, size);
        }
        return lookup != null;
    }

    /** Publishes our comment on the file {@code hash}, under our id, to the nodes closest to it. */
    @Override
    public boolean publishNote(Hash16 hash, String name, long size, Supplier<Optional<FileComment>> comment) {
        UInt128 target = UInt128.of(hash);
        if (this.lookups.containsKey(target)) {
            return false;
        }
        Lookup lookup = this.startLookup(target, Lookup.Kind.STORE_NOTES);
        if (lookup != null) {
            this.lookupData.put(lookup, new OwnNote(name, size, comment));
        }
        return lookup != null;
    }

    /** The target of the lookup for UDP test helpers running now, for tests. */
    Optional<UInt128> udpHelpersTarget() {
        for (Lookup lookup : this.lookups.values()) {
            if (lookup.kind() == Lookup.Kind.UDP_HELPERS) {
                return Optional.of(lookup.target());
            }
        }
        return Optional.empty();
    }

    /** Files indexed under keywords and sources indexed, for tests. */
    int[] indexed() {
        return new int[] {this.index.keywordEntries(), this.index.sourceEntries()};
    }

    /** Notes indexed, for tests. */
    int indexedNotes() {
        return this.index.noteEntries();
    }

    /** Keyword publishes running now. */
    @Override
    public int runningKeywordPublishes() {
        return this.running(Lookup.Kind.STORE_KEYWORD);
    }

    /** Source publishes running now. */
    @Override
    public int runningSourcePublishes() {
        return this.running(Lookup.Kind.STORE_SOURCE);
    }

    /** Note publishes running now. */
    @Override
    public int runningNotePublishes() {
        return this.running(Lookup.Kind.STORE_NOTES);
    }

    private int running(Lookup.Kind kind) {
        int count = 0;
        for (Lookup lookup : this.lookups.values()) {
            if (lookup.kind() == kind) {
                count++;
            }
        }
        return count;
    }

    /** Lookups running now. */
    public int runningLookups() {
        return this.lookups.size();
    }

    /** The contacts of the routing table, closest first. */
    public List<KadContactInfo> contactInfos() {
        List<KadContact> all = new ArrayList<>(this.table.all());
        UInt128 us = this.identity.id();
        all.sort(Comparator.comparing(c -> c.id().xor(us)));
        List<KadContactInfo> infos = new ArrayList<>(all.size());
        for (KadContact c : all) {
            infos.add(new KadContactInfo(
                    c.id().toHex(),
                    Ipv4.format(c.ip()) + ":" + c.udpPort(),
                    c.type(),
                    c.id().xor(us).toHex(),
                    c.version(),
                    c.isVerified()));
        }
        return infos;
    }

    /** The lookups running now. */
    public List<KadSearchInfo> lookupInfos() {
        List<KadSearchInfo> infos = new ArrayList<>();
        for (Lookup lookup : this.lookups.values()) {
            if (!lookup.isStopped()) {
                infos.add(new KadSearchInfo(lookup.target().toHex(), kind(lookup.kind()), lookup.answers()));
            }
        }
        return infos;
    }

    private static KadSearchInfo.Kind kind(Lookup.Kind kind) {
        return switch (kind) {
            case NODE, NODE_COMPLETE, UDP_HELPERS, FIND_BUDDY -> KadSearchInfo.Kind.NODE;
            case KEYWORD -> KadSearchInfo.Kind.KEYWORD;
            case SOURCES -> KadSearchInfo.Kind.SOURCES;
            case NOTES -> KadSearchInfo.Kind.NOTES;
            case STORE_KEYWORD -> KadSearchInfo.Kind.STORE_KEYWORD;
            case STORE_SOURCE -> KadSearchInfo.Kind.STORE_SOURCE;
            case STORE_NOTES -> KadSearchInfo.Kind.STORE_NOTES;
        };
    }

    /** The IP or LAN filter changed: contacts it now refuses are dropped; returns how many. */
    public int filtersChanged() {
        int dropped = 0;
        for (KadContact c : this.table.all()) {
            if (!this.isGoodAddress(c.ip())) {
                this.table.remove(c);
                dropped++;
            }
        }
        return dropped;
    }

    /** Whether the node at {@code ip} was asked to connect to our TCP port and has yet to say it did; any thread. */
    public boolean awaitsConnectionFrom(int ip) {
        return this.firewallChecks.containsKey(ip);
    }

    /** A node we asked to check our TCP port connected and said so. */
    public void reachableConfirmed(int ip) {
        if (this.firewallChecks.containsKey(ip)) {
            this.firewallChecks.remove(ip);
            this.firewallAcks.add(ip);
        }
    }

    /**
     * The TCP connection to a UDP test helper ended; {@code asked} whether the request went out. A helper asked whose
     * datagram did not come by then failed, one never asked does not count.
     */
    public void udpCheckEnded(int ip, boolean asked) {
        if (this.udpTest.outcome(ip, false, !asked, 0, this.settings.udpPort(), this.ticker.read())) {
            this.askUdpHelpers();
        }
    }

    /**
     * A client asked us over TCP to send a datagram to its UDP ports for its UDP firewall test. It gets one per port,
     * marked when it is known to us already ({@code knownClient}, a contact, a node we wrote to): that would bias its
     * outcome.
     */
    public void udpCheckRequested(int ip, int internalPort, int externalPort, int senderKey, boolean knownClient) {
        long now = this.ticker.read();
        if (internalPort == 0 || !this.isGoodAddress(ip)) {
            return;
        }
        this.udpHelped.values().removeIf(at -> now - at >= UDP_HELP_GAP.toNanos());
        if (this.udpHelped.containsKey(ip) || this.udpHelped.size() >= MAX_UDP_HELPED) {
            return;
        }
        this.udpHelped.put(ip, now);
        boolean known = knownClient || this.table.byAddress(ip).isPresent() || this.tracking.wroteTo(ip, this.nowMs());
        int error = known ? 1 : 0;
        this.send(
                ip,
                internalPort,
                new KadMessages.FirewallUdp(error, internalPort).encode(),
                Optional.empty(),
                senderKey);
        if (externalPort != 0 && externalPort != internalPort) {
            this.send(
                    ip,
                    externalPort,
                    new KadMessages.FirewallUdp(error, externalPort).encode(),
                    Optional.empty(),
                    senderKey);
        }
    }

    /**
     * Asks the buddy of a firewalled source, plain as the buddy may not know us, to have the source connect to our TCP
     * port for {@code file}; {@code false} when the buddy's address is not usable.
     */
    public boolean requestCallback(Buddy buddy, Hash16 file) {
        if (!this.isGoodAddress(buddy.ip()) || buddy.udpPort() == 0) {
            return false;
        }
        Packet request =
                new KadMessages.CallbackRequest(buddy.id(), UInt128.of(file), this.settings.tcpPort()).encode();
        this.transport.send(buddy.ip(), buddy.udpPort(), request, Optional.empty(), 0, 0);
        return true;
    }

    /** Runs every second. */
    public void tick() {
        long now = this.ticker.read();
        for (Lookup lookup : List.copyOf(this.lookups.values())) {
            lookup.tick(now);
            if (lookup.isStopped()) {
                this.lookupEnded(lookup);
            }
        }
        if (now - this.nextSmallTimer >= 0) {
            this.nextSmallTimer = now + MINUTE;
            this.checkContacts(now);
        }
        if (now - this.nextBigTimer >= 0) {
            this.nextBigTimer = now + 10 * SECOND;
            // After a quarter of an hour without a packet every zone looks for contacts again, and
            // again every quarter of an hour while it stays quiet.
            if (this.lastPacket != 0
                    && now - this.lastPacket > QUIET_FOR.toNanos()
                    && (this.lastQuietRefresh == 0 || now - this.lastQuietRefresh > QUIET_FOR.toNanos())) {
                for (RoutingTable.Zone zone : this.table.leaves()) {
                    zone.setNextBigTimer(now);
                }
                this.lastQuietRefresh = now;
            }
            this.refreshOneZone(now);
        }
        if (now - this.nextConsolidate >= 0) {
            this.nextConsolidate = now + 45 * MINUTE;
            this.table.consolidate();
        }
        if (now - this.nextSelfLookup >= 0 && this.table.size() > 0) {
            this.nextSelfLookup = now + SELF_LOOKUP_EVERY.toNanos();
            this.startLookup(this.identity.id(), Lookup.Kind.NODE_COMPLETE);
        }
        if (now - this.nextFirewallCheck >= 0) {
            this.nextFirewallCheck = now + FIREWALL_CHECK_EVERY.toNanos();
            // Only a check that ended gives the outcome that stands while the next one runs.
            boolean open = this.firewallAcks.size() >= FIREWALL_ACKS;
            if (open || this.firewallReports >= FIREWALL_CHECKS) {
                this.wasOpen = open;
            }
            this.firewallReports = 0;
            this.firewallAcks.clear();
            this.udpTest.recheck(this.isConnected(), now);
            this.udpHelperLookups = 0;
            this.nextUdpHelperLookup = now;
            this.stopLookups(Lookup.Kind.UDP_HELPERS);
            // A buddy is looked for once the outcome is likely known.
            this.findBuddy = false;
            if (this.nextFindBuddy - (now + FIND_BUDDY_FIRST.toNanos()) < 0) {
                this.nextFindBuddy = now + FIND_BUDDY_FIRST.toNanos();
            }
        }
        this.bootstrapFromList(now);
        this.tickUdpTest(now);
        this.tickBuddies(now);
        if (now - this.nextClean >= 0) {
            this.nextClean = now + 12 * MINUTE;
            this.tracking.clean(this.nowMs());
            this.provenIds.clean(now);
            this.index.clean(now);
            this.firewallChecks.values().removeIf(at -> now - at > 3 * MINUTE);
            this.udpHelped.values().removeIf(at -> now - at >= UDP_HELP_GAP.toNanos());
        }
    }

    // While the UDP test runs: nodes are asked the port they see us on, and lookups find helpers.
    private void tickUdpTest(long now) {
        boolean connected = this.isConnected();
        this.udpTest.tick(connected, this.isFirewalled(), now);
        if (!this.udpTest.isRunning()) {
            this.stopLookups(Lookup.Kind.UDP_HELPERS);
            return;
        }
        if (!connected) {
            return;
        }
        if (this.udpTest.findsExternalPort() && now - this.nextExternalPortAsk >= 0) {
            this.nextExternalPortAsk = now + EXTERNAL_PORT_ASK_EVERY.toNanos();
            this.askExternalPort();
        }
        // Helpers are looked for once they can be asked: when the port a NAT shows is known.
        if (!this.udpTest.findsExternalPort()
                && this.udpTest.wantsCandidates()
                && this.udpHelperLookups < UDP_HELPER_LOOKUPS
                && now - this.nextUdpHelperLookup >= 0
                && this.running(Lookup.Kind.UDP_HELPERS) == 0
                && this.startLookup(UInt128.random(this.random), Lookup.Kind.UDP_HELPERS) != null) {
            this.udpHelperLookups++;
            this.nextUdpHelperLookup = now + UDP_HELPER_LOOKUP_GAP.toNanos();
        }
    }

    // A random contact recent enough says which UDP port it sees us on.
    private void askExternalPort() {
        List<KadContact> usable = new ArrayList<>();
        for (KadContact c : this.table.all()) {
            if (c.type() < KadContact.DEAD && c.version() >= OBFUSCATION_VERSION) {
                usable.add(c);
            }
        }
        if (!usable.isEmpty()) {
            this.request(usable.get(this.random.nextInt(usable.size())), KadMessages.ping(), Opcodes.Kad.PING);
        }
    }

    // Helpers are asked one by one until enough are; each is told our ports and the key to obfuscate with.
    private void askUdpHelpers() {
        while (true) {
            Optional<KadMessages.Contact> helper =
                    this.udpTest.nextHelper(this::mayHelpWithUdp, this.publicIp, this.identity.id());
            if (helper.isEmpty()) {
                return;
            }
            KadMessages.Contact c = helper.get();
            logger.debug("Asking {} to test our UDP port", Ipv4.format(c.ip()));
            this.host.checkUdp(
                    c.ip(),
                    c.tcpPort(),
                    this.settings.udpPort(),
                    this.udpTest.externalPort(),
                    this.identity.verifyKey(c.ip()));
        }
    }

    // A helper must know nothing of us that would let its datagram through: no contact of ours, no node we wrote to,
    // no client we deal with.
    private boolean mayHelpWithUdp(KadMessages.Contact c) {
        return this.settings.udpPort() != 0
                && c.tcpPort() != 0
                && this.isGoodAddress(c.ip())
                && this.table.byAddress(c.ip()).isEmpty()
                && !this.tracking.wroteTo(c.ip(), this.nowMs())
                && !this.host.isKnownClient(c.ip());
    }

    // A buddy is looked for while we are firewalled on both ports, and let go once we are not.
    private void tickBuddies(long now) {
        if (now - this.nextFindBuddy >= 0) {
            this.findBuddy = true;
            this.nextFindBuddy = now + FIND_BUDDY_EVERY.toNanos();
        }
        Buddies.State state = this.buddies.state();
        boolean lost = state == Buddies.State.NONE
                && (this.buddyState == Buddies.State.CONNECTED || this.buddyState == Buddies.State.CONNECTING);
        this.buddyState = state;
        if (!this.isConnected()) {
            if (state != Buddies.State.NONE) {
                this.buddies.release();
            }
            return;
        }
        if (!this.needsBuddy()) {
            if (state == Buddies.State.CONNECTED || state == Buddies.State.CONNECTING) {
                logger.debug("Kad: our buddy is no longer needed");
                this.buddies.release();
            }
            return;
        }
        // A buddy lost is replaced at once.
        if (lost) {
            this.findBuddy = true;
        }
        // Callback requests to us cannot be obfuscated, so a node that requires it cannot use a buddy.
        if (state == Buddies.State.NONE
                && this.findBuddy
                && (this.settings.connectOptions() & OBFUSCATION_REQUIRED) == 0) {
            this.findBuddy = false;
            if (this.startLookup(this.identity.id().xor(ALL_ONES), Lookup.Kind.FIND_BUDDY) == null) {
                this.findBuddy = true;
            }
        }
    }

    // Firewalled on both ports, a node needs a buddy to be reached.
    private boolean needsBuddy() {
        return this.isConnected() && this.isFirewalled() && this.isFirewalledUdp();
    }

    private void stopLookups(Lookup.Kind kind) {
        for (Lookup lookup : List.copyOf(this.lookups.values())) {
            if (lookup.kind() == kind) {
                this.lookupEnded(lookup);
            }
        }
    }

    /** A Kad packet from {@code ip:port}; the keys come from its obfuscation (0 when plain). */
    public void received(int ip, int port, Packet packet, boolean obfuscated, int receiverKey, int senderKey) {
        // A datagram from the DNS port without a sender key may be a DNS answer made to look like Kad.
        if (port == DNS_PORT && senderKey == 0) {
            return;
        }
        long nowMs = this.nowMs();
        if (this.tracking.isBanned(ip, nowMs)) {
            return;
        }
        Optional<Opcodes.Kad> opcode = Opcodes.Kad.of(packet.opcode());
        if (opcode.isEmpty()) {
            return;
        }
        // Only the packet is dropped: the sender's address may be forged, so its contact is kept.
        if (!this.tracking.allow(ip, opcode.get(), nowMs)) {
            return;
        }
        this.lastPacket = this.ticker.read();
        Peer peer = new Peer(ip, port, receiverKey != 0 && receiverKey == this.identity.verifyKey(ip), senderKey);
        try {
            switch (opcode.get()) {
                case BOOTSTRAP_REQ -> this.bootstrapRequested(peer);
                case BOOTSTRAP_RES -> this.bootstrapAnswered(peer, packet);
                case HELLO_REQ -> this.helloReceived(peer, packet, false);
                case HELLO_RES -> this.helloReceived(peer, packet, true);
                case HELLO_RES_ACK -> this.helloAcknowledged(peer, packet);
                case REQ -> this.lookupRequested(peer, packet);
                case RES -> this.lookupAnswered(peer, packet);
                case SEARCH_KEY_REQ -> this.keywordSearchRequested(peer, packet);
                case SEARCH_SOURCE_REQ -> this.sourceSearchRequested(peer, packet);
                case SEARCH_NOTES_REQ -> this.notesSearchRequested(peer, packet);
                case SEARCH_RES -> this.searchAnswered(peer, packet);
                case PUBLISH_KEY_REQ -> this.keywordPublished(peer, packet);
                case PUBLISH_SOURCE_REQ -> this.sourcePublished(peer, packet);
                case PUBLISH_NOTES_REQ -> this.notePublished(peer, packet);
                case PUBLISH_RES -> this.publishAnswered(peer, packet);
                case FIREWALLED2_REQ -> this.firewallCheckRequested(peer, packet);
                case FIREWALLED_RES -> this.firewallAnswered(peer, packet);
                case FIREWALLUDP -> this.udpTestAnswered(peer, packet);
                case FINDBUDDY_REQ -> this.buddyRequested(peer, packet);
                case FINDBUDDY_RES -> this.buddyOffered(peer, packet);
                case CALLBACK_REQ -> this.callbackRequested(peer, packet);
                case PING -> this.answer(peer, KadMessages.pong(port));
                case PONG -> this.pongReceived(peer, packet);
                default -> {
                    // Kad 1 and acknowledgements we never ask for.
                }
            }
        } catch (DecodeException e) {
            logger.debug("Bad Kad packet from {}:{}: {}", Ipv4.format(ip), port, e.getMessage());
        }
    }

    /** Where a packet came from and the keys it carried. */
    private record Peer(int ip, int port, boolean validKey, int senderKey) {
        NodesDat.UdpKey key(int ourIp) {
            return this.senderKey != 0 && ourIp != 0
                    ? new NodesDat.UdpKey(this.senderKey, ourIp)
                    : NodesDat.UdpKey.NONE;
        }
    }

    private void bootstrapRequested(Peer peer) {
        List<KadMessages.Contact> contacts = new ArrayList<>();
        for (KadContact c : this.table.bootstrapContacts(KadMessages.MAX_BOOTSTRAP_CONTACTS, this.random)) {
            contacts.add(c.message());
        }
        this.answer(peer, new KadMessages.BootstrapAnswer(this.self(), contacts).encode());
    }

    private void bootstrapAnswered(Peer peer, Packet packet) throws DecodeException {
        if (!this.tracking.expected(peer.ip(), Opcodes.Kad.BOOTSTRAP_RES, this.nowMs())) {
            return;
        }
        KadMessages.BootstrapAnswer answer = KadMessages.BootstrapAnswer.decode(packet);
        long now = this.ticker.read();
        boolean empty = this.table.size() == 0;
        // A node of a bootstrap list gives contacts but is not taken: everyone starting from that list would.
        if (this.bootstrapList.isEmpty() && this.isGoodAddress(peer.ip())) {
            KadContact sender = new KadContact(
                    answer.sender().id(),
                    peer.ip(),
                    peer.port(),
                    answer.sender().tcpPort(),
                    answer.sender().version(),
                    now);
            sender.setVerified(true);
            this.table.add(sender, true, peer.key(this.publicIp), this.publicIp, now);
        }
        for (KadMessages.Contact c : answer.contacts()) {
            this.addUnverified(c, empty, now);
        }
    }

    private void helloReceived(Peer peer, Packet packet, boolean answer) throws DecodeException {
        if (answer && !this.tracking.expected(peer.ip(), Opcodes.Kad.HELLO_RES, this.nowMs())) {
            return;
        }
        KadMessages.Hello hello = KadMessages.Hello.decode(packet);
        // Our key for its address proves where the node is: the id it gives there counts as proven.
        if (peer.validKey() && !this.proved(peer, hello.sender().id())) {
            return;
        }
        boolean claimedOnly = !peer.validKey()
                && this.provenIds.contradicts(
                        peer.ip(), peer.port(), hello.sender().id());
        int udpPort = Tags.portValue(hello.tags(), FileTag.SOURCEUPORT).orElse(peer.port());
        int misc = (int) Tags.intValue(hello.tags(), FileTag.KADMISCOPTIONS).orElse(0);
        int version = hello.sender().version();
        long now = this.ticker.read();
        RoutingTable.Outcome outcome = RoutingTable.Outcome.REFUSED;
        if (!claimedOnly && this.isGoodAddress(peer.ip()) && (misc & MISC_UDP_FIREWALLED) == 0) {
            KadContact contact = new KadContact(
                    hello.sender().id(), peer.ip(), udpPort, hello.sender().tcpPort(), version, now);
            contact.setVerified(peer.validKey());
            outcome = this.table.add(contact, true, peer.key(this.publicIp), this.publicIp, now);
            this.table.get(contact.id()).ifPresent(KadContact::setHelloReceived);
        }
        boolean verified =
                this.table.get(hello.sender().id()).map(KadContact::isVerified).orElse(peer.validKey());
        if (outcome != RoutingTable.Outcome.REFUSED && !verified && !peer.validKey()) {
            this.challenge(peer, hello.sender().id(), version, answer);
        }
        if (!answer) {
            boolean askAck = outcome != RoutingTable.Outcome.REFUSED && !verified && version >= HELLO_ACK_VERSION;
            Packet reply = this.hello(version, askAck).encode(true);
            Optional<UInt128> key =
                    version >= OBFUSCATION_VERSION && !hello.sender().id().isZero()
                            ? Optional.of(hello.sender().id())
                            : Optional.empty();
            this.send(peer.ip(), peer.port(), reply, key, peer.senderKey());
            if (askAck) {
                this.tracking.sent(peer.ip(), Opcodes.Kad.HELLO_RES, this.nowMs());
            }
        } else if ((misc & MISC_ACK_REQUESTED) != 0 && version >= HELLO_ACK_VERSION && peer.senderKey() != 0) {
            this.answer(peer, KadMessages.helloAck(this.identity.id()));
        }
        this.maybeCheckFirewall(peer, version);
    }

    // Nodes older than the hello acknowledgement prove their address by answering a request only they were sent: a
    // ping for a node with keys, else a lookup for a random target. One at a time per address.
    private void challenge(Peer peer, UInt128 id, int version, boolean answer) {
        long nowMs = this.nowMs();
        if (version >= HELLO_ACK_VERSION
                || (answer && version >= KEYS_VERSION)
                || this.tracking.isChallenged(peer.ip(), nowMs)) {
            return;
        }
        if (version >= KEYS_VERSION) {
            this.answer(peer, KadMessages.ping());
            this.tracking.sent(peer.ip(), Opcodes.Kad.PING, nowMs);
            this.tracking.challenged(peer.ip(), Opcodes.Kad.PING, UInt128.ZERO, id, nowMs);
            return;
        }
        UInt128 target = UInt128.random(this.random);
        // Such nodes cannot open obfuscated packets.
        this.transport.send(
                peer.ip(),
                peer.port(),
                new KadMessages.Request(CHALLENGE_CONTACTS, target, id).encode(),
                Optional.empty(),
                0,
                0);
        this.tracking.sent(peer.ip(), Opcodes.Kad.REQ, nowMs);
        this.tracking.challenged(peer.ip(), Opcodes.Kad.REQ, target, id, nowMs);
    }

    private void verified(Peer peer, UInt128 id) {
        if (this.proved(peer, id)) {
            this.table.get(id).filter(c -> c.ip() == peer.ip()).ifPresent(c -> c.setVerified(true));
        }
    }

    // False when the node changed its id too soon, which bans it.
    private boolean proved(Peer peer, UInt128 id) {
        if (!this.provenIds.proved(peer.ip(), peer.port(), id, this.ticker.read())) {
            return true;
        }
        logger.debug("Kad: {} changed its id too soon, ignored for a while", Ipv4.format(peer.ip()));
        this.tracking.ban(peer.ip(), ID_CHANGE_BAN, this.nowMs());
        this.table.byAddress(peer.ip()).ifPresent(this.table::remove);
        return false;
    }

    private void helloAcknowledged(Peer peer, Packet packet) throws DecodeException {
        if (!peer.validKey() || !this.tracking.expected(peer.ip(), Opcodes.Kad.HELLO_RES_ACK, this.nowMs())) {
            return;
        }
        this.verified(peer, KadMessages.decodeHelloAck(packet));
    }

    private void lookupRequested(Peer peer, Packet packet) throws DecodeException {
        KadMessages.Request request = KadMessages.Request.decode(packet);
        if (!request.receiver().equals(this.identity.id())) {
            return;
        }
        List<KadMessages.Contact> closest = new ArrayList<>();
        int count = Math.min(request.count(), KadMessages.MAX_LOOKUP_CONTACTS);
        for (KadContact c : this.table.closest(request.target(), 2, count)) {
            closest.add(c.message());
        }
        this.answer(peer, new KadMessages.Answer(request.target(), closest).encode());
    }

    private void lookupAnswered(Peer peer, Packet packet) throws DecodeException {
        if (!this.tracking.expected(peer.ip(), Opcodes.Kad.RES, this.nowMs())) {
            return;
        }
        KadMessages.Answer answer = KadMessages.Answer.decode(packet);
        long now = this.ticker.read();
        this.table.byAddress(peer.ip()).ifPresent(c -> this.table.alive(c, now));
        // The answer to a challenge proves the contact; the contacts it carries are not wanted.
        Optional<UInt128> proven =
                this.tracking.challengeAnswered(peer.ip(), Opcodes.Kad.REQ, answer.target(), this.nowMs());
        if (proven.isPresent()) {
            this.verified(peer, proven.get());
            return;
        }
        // Contacts are learnt only from the answer to a lookup that asked this node for that many, under the id
        // the node proved there if it did.
        Lookup lookup = this.lookups.get(answer.target());
        if (lookup == null
                || !lookup.asked(peer.ip(), peer.port())
                || answer.contacts().size() > lookup.expectedContacts(peer.ip(), peer.port())
                || lookup.askedId(peer.ip(), peer.port())
                        .filter(id -> this.provenIds.contradicts(peer.ip(), peer.port(), id))
                        .isPresent()) {
            logger.debug("Ignoring {} contacts from {}", answer.contacts().size(), Ipv4.format(peer.ip()));
            return;
        }
        boolean helpers = lookup.kind() == Lookup.Kind.UDP_HELPERS;
        List<KadMessages.Contact> usable = new ArrayList<>();
        for (KadMessages.Contact c : answer.contacts()) {
            if (c.version() < 2
                    || c.udpPort() == 0
                    || !this.isGoodAddress(c.ip())
                    || (c.udpPort() == DNS_PORT && c.version() <= 5)) {
                continue;
            }
            // Helpers must never hear from us over UDP: they are neither added nor asked further.
            if (helpers) {
                this.udpTest.candidate(c);
                usable.add(c);
            } else if (this.addUnverified(c, false, now) == RoutingTable.Outcome.ADDED
                    || this.table.isAcceptable(c.id(), c.ip(), c.udpPort())) {
                usable.add(c);
            }
        }
        lookup.answered(peer.ip(), peer.port(), helpers ? List.of() : usable, now);
        if (helpers) {
            this.askUdpHelpers();
        }
    }

    private void keywordSearchRequested(Peer peer, Packet packet) throws DecodeException {
        KadMessages.KeywordSearch search = KadMessages.KeywordSearch.decode(packet);
        Optional<SearchExpr> expression = Optional.empty();
        if (search.expression().length > 0) {
            expression = Optional.of(SearchTreeCodec.read(PacketReader.of(search.expression())));
        }
        List<KadMessages.Entry> results =
                this.index.searchKeyword(search.target(), expression, search.start(), this.ticker.read());
        this.answerResults(peer, search.target(), results);
    }

    private void sourceSearchRequested(Peer peer, Packet packet) throws DecodeException {
        KadMessages.SourceSearch search = KadMessages.SourceSearch.decode(packet);
        List<KadMessages.Entry> results =
                this.index.searchSources(search.target(), search.size(), search.start(), this.ticker.read());
        this.answerResults(peer, search.target(), results);
    }

    private void notesSearchRequested(Peer peer, Packet packet) throws DecodeException {
        KadMessages.NotesSearch search = KadMessages.NotesSearch.decode(packet);
        this.answerResults(
                peer, search.target(), this.index.searchNotes(search.target(), search.size(), this.ticker.read()));
    }

    private void answerResults(Peer peer, UInt128 target, List<KadMessages.Entry> results) {
        if (results.isEmpty()) {
            return;
        }
        for (Packet p : KadMessages.SearchAnswer.split(this.identity.id(), target, results, KadIndex.ANSWER_BYTES)) {
            this.answer(peer, p);
        }
    }

    private void searchAnswered(Peer peer, Packet packet) throws DecodeException {
        if (!this.tracking.awaits(peer.ip(), Opcodes.Kad.SEARCH_RES, this.nowMs())) {
            return;
        }
        KadMessages.SearchAnswer answer = KadMessages.SearchAnswer.decode(packet);
        if (this.provenIds.contradicts(peer.ip(), peer.port(), answer.sender())) {
            return;
        }
        Lookup lookup = this.lookups.get(answer.target());
        ToIntFunction<List<KadMessages.Entry>> sink = lookup == null ? null : this.resultSinks.get(lookup);
        // Only a node this very search was sent to answers it.
        if (sink == null || lookup.isStopped() || !lookup.requestedFrom(peer.ip())) {
            return;
        }
        // Only usable results bring the lookup closer to its end: junk from one node must not stop it.
        lookup.valueReceived(sink.applyAsInt(answer.results()), this.ticker.read());
    }

    // A node others cannot send to would store what nobody can ask for: it takes no publishes.
    private void keywordPublished(Peer peer, Packet packet) throws DecodeException {
        KadMessages.KeywordPublish publish = KadMessages.KeywordPublish.decode(packet);
        if (this.isFirewalledUdp() || !this.inTolerance(publish.keyword())) {
            return;
        }
        long now = this.ticker.read();
        int load = 0;
        for (KadMessages.Entry file : publish.files()) {
            load = this.index.publishKeyword(publish.keyword(), file, peer.ip(), now);
        }
        this.answer(peer, new KadMessages.PublishAnswer(publish.keyword(), load).encode());
    }

    private void sourcePublished(Peer peer, Packet packet) throws DecodeException {
        KadMessages.SourcePublish publish = KadMessages.SourcePublish.decode(packet);
        if (this.isFirewalledUdp() || !this.inTolerance(publish.file())) {
            return;
        }
        OptionalLong load = this.index.publishSource(
                publish.file(), publish.source(), publish.tags(), peer.ip(), peer.port(), this.ticker.read());
        if (load.isPresent()) {
            this.answer(peer, new KadMessages.PublishAnswer(publish.file(), (int) load.getAsLong()).encode());
        }
    }

    private void notePublished(Peer peer, Packet packet) throws DecodeException {
        KadMessages.NotesPublish publish = KadMessages.NotesPublish.decode(packet);
        // Like keywords and sources: a node others cannot ask over UDP keeps nothing for them.
        if (this.isFirewalledUdp() || !this.inTolerance(publish.file())) {
            return;
        }
        OptionalLong load = this.index.publishNote(
                publish.file(), publish.publisher(), publish.tags(), peer.ip(), this.ticker.read());
        if (load.isPresent()) {
            this.answer(peer, new KadMessages.PublishAnswer(publish.file(), (int) load.getAsLong()).encode());
        }
    }

    private void publishAnswered(Peer peer, Packet packet) throws DecodeException {
        if (!this.tracking.expected(peer.ip(), Opcodes.Kad.PUBLISH_RES, this.nowMs())) {
            return;
        }
        KadMessages.PublishAnswer answer = KadMessages.PublishAnswer.decode(packet);
        Lookup lookup = this.lookups.get(answer.target());
        if (lookup != null && lookup.requestedFrom(peer.ip())) {
            if (lookup.kind() == Lookup.Kind.STORE_KEYWORD) {
                lookup.loadReported(answer.load());
            }
            lookup.valueReceived(1, this.ticker.read());
        }
    }

    // We help only nodes on addresses that could be real: a loopback answer would mislead them.
    private void firewallCheckRequested(Peer peer, Packet packet) throws DecodeException {
        KadMessages.FirewallCheck check = KadMessages.FirewallCheck.decode(packet);
        if (!this.isGoodAddress(peer.ip()) || check.tcpPort() == 0) {
            return;
        }
        this.answer(peer, KadMessages.firewallAnswer(peer.ip()));
        this.host.checkReachable(peer.ip(), check.tcpPort(), check.userHash().toHash16(), check.connectOptions());
    }

    private void firewallAnswered(Peer peer, Packet packet) throws DecodeException {
        if (!this.firewallChecks.containsKey(peer.ip())
                || !this.tracking.expected(peer.ip(), Opcodes.Kad.FIREWALLED_RES, this.nowMs())) {
            return;
        }
        int ip = KadMessages.decodeFirewallAnswer(packet);
        this.firewallReports++;
        // A first report is taken; afterwards a new address needs two reports in a row.
        if (this.publicIp == 0 || ip == this.publicIp) {
            this.publicIp = ip;
            this.pendingIp = 0;
        } else if (ip == this.pendingIp) {
            this.publicIp = ip;
            this.pendingIp = 0;
        } else {
            this.pendingIp = ip;
        }
    }

    private void pongReceived(Peer peer, Packet packet) throws DecodeException {
        if (!this.tracking.expected(peer.ip(), Opcodes.Kad.PONG, this.nowMs())) {
            return;
        }
        this.tracking
                .challengeAnswered(peer.ip(), Opcodes.Kad.PING, UInt128.ZERO, this.nowMs())
                .ifPresent(id -> this.verified(peer, id));
        int port = KadMessages.decodePong(packet);
        if (port != 0 && this.udpTest.isRunning() && this.udpTest.findsExternalPort()) {
            this.udpTest.portSeen(port, peer.ip());
            this.askUdpHelpers();
        }
    }

    // Only a helper we asked counts, and only with the key we gave it: a datagram from anyone else proves nothing.
    private void udpTestAnswered(Peer peer, Packet packet) throws DecodeException {
        if (!peer.validKey() || !this.udpTest.asked(peer.ip())) {
            return;
        }
        KadMessages.FirewallUdp answer = KadMessages.FirewallUdp.decode(packet);
        int port = answer.port();
        int internal = this.settings.udpPort();
        boolean ours = port != 0 && (port == internal || port == this.udpTest.externalPort());
        boolean succeeded = ours && answer.error() == 0;
        long now = this.ticker.read();
        boolean more = this.udpTest.outcome(peer.ip(), succeeded, !succeeded, succeeded ? port : 0, internal, now);
        if (!this.udpTest.isRunning()) {
            this.stopLookups(Lookup.Kind.UDP_HELPERS);
            logger.info("Kad: UDP {}", this.udpTest.isFirewalled(false) ? "firewalled" : "open");
        } else if (more) {
            this.askUdpHelpers();
        }
    }

    // We serve one firewalled node, and only when both our ports are open: it must be able to reach us.
    private void buddyRequested(Peer peer, Packet packet) throws DecodeException {
        KadMessages.FindBuddy request = KadMessages.FindBuddy.decode(packet);
        if (this.isFirewalled()
                || this.isFirewalledUdp()
                || !this.udpTest.isVerified()
                || this.buddies.state() != Buddies.State.NONE
                || request.tcpPort() == 0
                || !this.isGoodAddress(peer.ip())
                || this.firewallChecks.containsKey(peer.ip())) {
            return;
        }
        if (!this.buddies.asked(
                peer.ip(), request.tcpPort(), peer.port(), request.userHash().toHash16(), request.buddyId())) {
            return;
        }
        // Only nodes recent enough to obfuscate read the connect options.
        OptionalInt options =
                peer.senderKey() != 0 ? OptionalInt.of(this.settings.connectOptions()) : OptionalInt.empty();
        this.answer(
                peer,
                new KadMessages.BuddyOffer(
                                request.buddyId(),
                                UInt128.of(this.settings.userHash()),
                                this.settings.tcpPort(),
                                options)
                        .encode());
    }

    private void buddyOffered(Peer peer, Packet packet) throws DecodeException {
        if (!this.tracking.expected(peer.ip(), Opcodes.Kad.FINDBUDDY_RES, this.nowMs())) {
            return;
        }
        KadMessages.BuddyOffer offer = KadMessages.BuddyOffer.decode(packet);
        Buddies.State state = this.buddies.state();
        if (!this.isOurBuddyId(offer.buddyId())
                || offer.tcpPort() == 0
                || !this.isGoodAddress(peer.ip())
                || !this.needsBuddy()
                || (state != Buddies.State.NONE && state != Buddies.State.CONNECTING)) {
            return;
        }
        this.buddies.offered(
                peer.ip(),
                offer.tcpPort(),
                peer.port(),
                offer.userHash().toHash16(),
                offer.connectOptions().orElse(0));
    }

    // The node we serve is asked to connect to whoever sent this; the buddy id must be the one it registered.
    private void callbackRequested(Peer peer, Packet packet) throws DecodeException {
        KadMessages.CallbackRequest request = KadMessages.CallbackRequest.decode(packet);
        if (request.tcpPort() == 0 || !this.isGoodAddress(peer.ip()) || this.buddies.state() != Buddies.State.SERVING) {
            return;
        }
        if (this.buddies.relay(request.buddyId(), request.file(), peer.ip(), request.tcpPort())) {
            logger.debug("Kad: callback request from {} relayed", Ipv4.format(peer.ip()));
        }
    }

    private void maybeCheckFirewall(Peer peer, int version) {
        if (this.firewallReports >= FIREWALL_CHECKS
                || version < FIREWALL2_VERSION
                || this.firewallChecks.containsKey(peer.ip())
                || !this.isGoodAddress(peer.ip())) {
            return;
        }
        this.firewallChecks.put(peer.ip(), this.ticker.read());
        Packet request = new KadMessages.FirewallCheck(
                        this.settings.tcpPort(), UInt128.of(this.settings.userHash()), this.settings.connectOptions())
                .encode();
        this.table
                .byAddress(peer.ip())
                .ifPresentOrElse(c -> this.request(c, request, Opcodes.Kad.FIREWALLED2_REQ), () -> {
                    this.send(peer.ip(), peer.port(), request, Optional.empty(), peer.senderKey());
                    this.tracking.sent(peer.ip(), Opcodes.Kad.FIREWALLED2_REQ, this.nowMs());
                });
    }

    // Each minute a bin checks its oldest contact; contacts that keep failing are dropped.
    private void checkContacts(long now) {
        for (RoutingTable.Zone zone : this.table.leaves()) {
            List<KadContact> bin = zone.contacts();
            for (KadContact c : List.copyOf(bin)) {
                if (c.type() == KadContact.DEAD && c.isExpired(now) && !c.inUse()) {
                    this.table.remove(c);
                } else {
                    c.touchExpires(now);
                }
            }
            if (bin.isEmpty()) {
                continue;
            }
            KadContact oldest = bin.getFirst();
            if (!oldest.isExpired(now) || oldest.type() == KadContact.DEAD) {
                bin.remove(oldest);
                bin.add(oldest);
                continue;
            }
            oldest.checking(now);
            this.request(oldest, this.hello(oldest.version(), false).encode(false), Opcodes.Kad.HELLO_REQ);
        }
    }

    // One zone at a time, at most every ten seconds, looks for more contacts once an hour.
    private void refreshOneZone(long now) {
        for (RoutingTable.Zone zone : this.table.leaves()) {
            if (now - zone.nextBigTimer() < 0) {
                continue;
            }
            zone.setNextBigTimer(now + HOUR);
            if (this.table.wantsContacts(zone)) {
                this.startLookup(this.table.randomIdIn(zone, this.random), Lookup.Kind.NODE);
                return;
            }
        }
    }

    private Lookup startLookup(UInt128 target, Lookup.Kind kind) {
        if (this.lookups.containsKey(target)) {
            return null;
        }
        Lookup lookup = new Lookup(
                target, kind, new Requests(), this.settings.trusted(), this.responseTimes, this.ticker.read());
        this.lookups.put(target, lookup);
        lookup.start(this.table);
        if (lookup.isStopped()) {
            this.lookupEnded(lookup);
            return null;
        }
        return lookup;
    }

    private void lookupEnded(Lookup lookup) {
        this.lookups.remove(lookup.target(), lookup);
        this.resultSinks.remove(lookup);
        this.lookupData.remove(lookup);
        if (lookup.kind() == Lookup.Kind.NODE_COMPLETE) {
            this.selfLookupDone = true;
        }
        int load = lookup.load();
        if (lookup.kind() == Lookup.Kind.STORE_KEYWORD && load > HIGH_LOAD) {
            this.index.loaded(lookup.target(), this.ticker.read() + LOADED_FOR.toNanos() / 100 * load);
        }
        Runnable ended = this.endActions.remove(lookup);
        if (ended != null) {
            ended.run();
        }
    }

    /** The packets lookups send. */
    private final class Requests implements Lookup.Requests {
        @Override
        public void ask(Lookup lookup, KadMessages.Contact contact, int count) {
            Packet request = new KadMessages.Request(count, lookup.target(), contact.id()).encode();
            KadNode.this.requestTo(contact, request, Opcodes.Kad.REQ);
        }

        @Override
        public void request(Lookup lookup, KadMessages.Contact contact) {
            KadNode node = KadNode.this;
            Object data = node.lookupData.get(lookup);
            switch (lookup.kind()) {
                case KEYWORD ->
                    node.requestTo(
                            contact,
                            new KadMessages.KeywordSearch(lookup.target(), 0, (byte[]) data).encode(),
                            Opcodes.Kad.SEARCH_KEY_REQ);
                case SOURCES ->
                    node.requestTo(
                            contact,
                            new KadMessages.SourceSearch(lookup.target(), 0, (Long) data).encode(),
                            Opcodes.Kad.SEARCH_SOURCE_REQ);
                case NOTES ->
                    node.requestTo(
                            contact,
                            new KadMessages.NotesSearch(lookup.target(), (Long) data).encode(),
                            Opcodes.Kad.SEARCH_NOTES_REQ);
                case STORE_KEYWORD -> {
                    @SuppressWarnings("unchecked")
                    List<KadMessages.Entry> files = (List<KadMessages.Entry>) data;
                    for (int at = 0; at < files.size(); at += KadMessages.MAX_PUBLISHED_FILES) {
                        List<KadMessages.Entry> batch =
                                files.subList(at, Math.min(files.size(), at + KadMessages.MAX_PUBLISHED_FILES));
                        node.requestTo(
                                contact,
                                new KadMessages.KeywordPublish(lookup.target(), batch).encode(),
                                Opcodes.Kad.PUBLISH_KEY_REQ);
                    }
                }
                case STORE_SOURCE ->
                    node.sourceTags((Long) data)
                            .ifPresent(tags -> node.requestTo(
                                    contact,
                                    new KadMessages.SourcePublish(
                                                    lookup.target(), UInt128.of(node.settings.userHash()), tags)
                                            .encode(),
                                    Opcodes.Kad.PUBLISH_SOURCE_REQ));
                case FIND_BUDDY -> {
                    node.requestTo(
                            contact,
                            new KadMessages.FindBuddy(
                                            lookup.target(),
                                            UInt128.of(node.settings.userHash()),
                                            node.settings.tcpPort())
                                    .encode(),
                            Opcodes.Kad.FINDBUDDY_REQ);
                    lookup.valueReceived(1, node.ticker.read());
                }
                case STORE_NOTES -> {
                    OwnNote note = (OwnNote) data;
                    Optional<FileComment> comment = note.comment().get().filter(c -> !c.isEmpty());
                    if (comment.isEmpty()) {
                        // The comment was taken away meanwhile: the nodes left hear nothing of it.
                        lookup.stop();
                        return;
                    }
                    node.requestTo(
                            contact,
                            new KadMessages.NotesPublish(
                                            lookup.target(), node.identity.id(), noteTags(note, comment.get()))
                                    .encode(),
                            Opcodes.Kad.PUBLISH_NOTES_REQ);
                }
                default -> {
                    // Node lookups only collect contacts.
                }
            }
        }
    }

    // How others reach us as a source: directly, through our buddy or by a direct callback; empty when they cannot.
    private Optional<List<Tag>> sourceTags(long size) {
        boolean large = size > LARGE_SOURCE;
        boolean direct = false;
        List<Tag> tags = new ArrayList<>();
        if (!this.isFirewalledForClients()) {
            tags.add(new Tag.IntTag(TagName.id(FileTag.SOURCETYPE), large ? SOURCE_DIRECT_LARGE : SOURCE_DIRECT));
        } else if (!this.isFirewalledUdp() && this.udpTest.isVerified()) {
            direct = true;
            tags.add(new Tag.IntTag(TagName.id(FileTag.SOURCETYPE), SOURCE_DIRECT_CALLBACK));
        } else if (this.buddies.state() == Buddies.State.CONNECTED && this.buddies.buddyIp() != 0) {
            tags.add(new Tag.IntTag(TagName.id(FileTag.SOURCETYPE), large ? SOURCE_BUDDY_LARGE : SOURCE_BUDDY));
            tags.add(new Tag.IntTag(TagName.id(FileTag.SERVERIP), Ipv4.toEd2k(this.buddies.buddyIp())));
            tags.add(new Tag.IntTag(TagName.id(FileTag.SERVERPORT), this.buddies.buddyUdpPort()));
            tags.add(new Tag.StringTag(TagName.id(FileTag.BUDDYHASH), buddyHash(this.identity.id())));
        } else {
            return Optional.empty();
        }
        tags.add(new Tag.IntTag(TagName.id(FileTag.SOURCEPORT), this.settings.tcpPort()));
        if (!this.udpTest.useExternalPort() && this.settings.udpPort() != 0) {
            tags.add(new Tag.IntTag(TagName.id(FileTag.SOURCEUPORT), this.settings.udpPort()));
        }
        tags.add(new Tag.IntTag(TagName.id(FileTag.FILESIZE), size));
        int options = this.settings.connectOptions() | (direct ? DIRECT_CALLBACK : 0);
        tags.add(new Tag.IntTag(TagName.id(FileTag.ENCRYPTION), options));
        return Optional.of(tags);
    }

    /** The buddy hash of a source publish: the complement of the Kad id, as hex of its id layout. */
    static String buddyHash(UInt128 kadId) {
        return Hash16.of(kadId.xor(ALL_ONES).toWireBytes()).toHex();
    }

    static Optional<UInt128> parseBuddyHash(String hex) {
        try {
            return Optional.of(UInt128.fromWireBytes(Hash16.parseHex(hex).toByteArray()));
        } catch (IllegalArgumentException | DecodeException e) {
            return Optional.empty();
        }
    }

    private static List<Tag> noteTags(OwnNote note, FileComment comment) {
        List<Tag> tags = new ArrayList<>();
        tags.add(new Tag.StringTag(TagName.id(FileTag.FILENAME), note.fileName()));
        if (comment.rating() > 0) {
            tags.add(new Tag.IntTag(TagName.id(FileTag.FILERATING), comment.rating()));
        }
        if (!comment.text().isEmpty()) {
            tags.add(new Tag.StringTag(TagName.id(FileTag.DESCRIPTION), comment.text()));
        }
        tags.add(new Tag.IntTag(TagName.id(FileTag.FILESIZE), note.size()));
        return tags;
    }

    private void requestTo(KadMessages.Contact contact, Packet request, Opcodes.Kad kind) {
        Optional<KadContact> known = this.table.get(contact.id());
        if (known.isPresent()) {
            this.request(known.get(), request, kind);
            return;
        }
        Optional<UInt128> key = contact.version() >= OBFUSCATION_VERSION ? Optional.of(contact.id()) : Optional.empty();
        this.send(contact.ip(), contact.udpPort(), request, key, 0);
        this.tracking.sent(contact.ip(), kind, this.nowMs());
    }

    private void request(KadContact contact, Packet request, Opcodes.Kad kind) {
        Optional<UInt128> key = contact.version() >= OBFUSCATION_VERSION ? Optional.of(contact.id()) : Optional.empty();
        NodesDat.UdpKey stored = contact.udpKey();
        int receiverKey = stored.ip() == this.publicIp ? stored.key() : 0;
        this.send(contact.ip(), contact.udpPort(), request, key, receiverKey);
        this.tracking.sent(contact.ip(), kind, this.nowMs());
    }

    private void answer(Peer peer, Packet answer) {
        this.send(peer.ip(), peer.port(), answer, Optional.empty(), peer.senderKey());
    }

    private void send(int ip, int port, Packet packet, Optional<UInt128> receiverId, int receiverKey) {
        this.transport.send(ip, port, packet, receiverId, receiverKey, this.identity.verifyKey(ip));
    }

    private KadMessages.Hello hello(int peerVersion, boolean askAck) {
        List<Tag> tags = new ArrayList<>();
        // Our own port, once the UDP test proved it better than the one a NAT shows.
        if (!this.udpTest.useExternalPort() && this.settings.udpPort() != 0) {
            tags.add(new Tag.IntTag(TagName.id(FileTag.SOURCEUPORT), this.settings.udpPort()));
        }
        boolean tcp = this.isFirewalled();
        boolean udp = this.isFirewalledUdp();
        // A UDP-firewalled node says so: others do not take it as a contact.
        if (peerVersion >= HELLO_ACK_VERSION && (askAck || tcp || udp)) {
            int misc = (udp ? MISC_UDP_FIREWALLED : 0)
                    | (tcp ? MISC_TCP_FIREWALLED : 0)
                    | (askAck ? MISC_ACK_REQUESTED : 0);
            tags.add(new Tag.IntTag(TagName.id(FileTag.KADMISCOPTIONS), misc));
        }
        return new KadMessages.Hello(this.self(), tags);
    }

    private KadMessages.Self self() {
        return new KadMessages.Self(this.identity.id(), this.settings.tcpPort(), KadMessages.VERSION);
    }

    private RoutingTable.Outcome addUnverified(KadMessages.Contact c, boolean verified, long now) {
        if (!this.isGoodAddress(c.ip()) || c.version() < 2) {
            return RoutingTable.Outcome.REFUSED;
        }
        KadContact contact = KadContact.of(c, now);
        contact.setVerified(verified);
        return this.table.add(contact, false, NodesDat.UdpKey.NONE, this.publicIp, now);
    }

    private boolean inTolerance(UInt128 target) {
        return target.xor(this.identity.id()).top32() <= Lookup.TOLERANCE;
    }

    // Public unicast addresses the IP filter allows, or what the settings trust (tests, LANs).
    private boolean isGoodAddress(int ip) {
        if (this.settings.blocked().test(ip)) {
            return false;
        }
        if (this.settings.trusted().test(ip)) {
            return true;
        }
        return Ipv4.isUnicastPublicCandidate(ip) && !Ipv4.isLan(ip);
    }

    private long nowMs() {
        return this.ticker.read() / 1_000_000L;
    }

    // A node may answer what it holds under the keyword without the filter: the whole expression is checked again.
    private static List<FileResult> fileResults(
            List<KadMessages.Entry> entries, List<String> keywords, Optional<KadIndex.Matcher> matcher) {
        List<FileResult> results = new ArrayList<>();
        for (KadMessages.Entry e : entries) {
            Optional<String> name = Tags.stringValue(e.tags(), FileTag.FILENAME);
            long size = Tags.intValue(e.tags(), FileTag.FILESIZE).orElse(0);
            if (name.isEmpty()
                    || size <= 0
                    || !Keywords.of(name.get()).containsAll(keywords)
                    || (matcher.isPresent()
                            && !matcher.get()
                                    .matches(name.get(), KadLowerCase.toLowerCase(name.get()), size, e.tags()))) {
                continue;
            }
            long sources = Tags.intValue(e.tags(), FileTag.SOURCES).orElse(0);
            results.add(new FileResult(
                    e.id().toHash16(),
                    name.get(),
                    size,
                    sources > MAX_AVAILABILITY ? 0 : (int) sources,
                    Tags.stringValue(e.tags(), FileTag.FILETYPE),
                    MediaTags.read(e.tags())));
        }
        return results;
    }

    // A note on a file of another size, or with neither comment nor rating, says nothing of this file.
    private static List<Note> noteResults(List<KadMessages.Entry> entries, long size, Set<UInt128> publishers) {
        List<Note> notes = new ArrayList<>();
        for (KadMessages.Entry e : entries) {
            FileComment comment = KadIndex.noteComment(e.tags());
            long noteSize =
                    Math.max(0, Tags.intValue(e.tags(), FileTag.FILESIZE).orElse(0));
            if (comment.isEmpty() || (size != 0 && noteSize != 0 && noteSize != size) || !publishers.add(e.id())) {
                continue;
            }
            String name =
                    KadIndex.cut(Tags.stringValue(e.tags(), FileTag.FILENAME).orElse(""), KadIndex.MAX_NOTE_NAME);
            notes.add(new Note(e.id(), name, noteSize, comment));
        }
        return notes;
    }

    // Sources reachable directly, through their buddy, or by a direct callback when they say they take it.
    static List<Source> sourceResults(List<KadMessages.Entry> entries) {
        List<Source> sources = new ArrayList<>();
        for (KadMessages.Entry e : entries) {
            List<Tag> tags = e.tags();
            int type = (int) Tags.intValue(tags, FileTag.SOURCETYPE).orElse(0);
            int ip = (int) Tags.intValue(tags, FileTag.SOURCEIP).orElse(0);
            int port = Tags.portValue(tags, FileTag.SOURCEPORT).orElse(0);
            int udp = Tags.portValue(tags, FileTag.SOURCEUPORT).orElse(0);
            int options = (int) Tags.intValue(tags, FileTag.ENCRYPTION).orElse(0) & 0xFF;
            if (ip == 0 || port == 0) {
                continue;
            }
            Hash16 user = e.id().toHash16();
            switch (type) {
                case SOURCE_DIRECT, SOURCE_DIRECT_LARGE -> sources.add(new Source(user, ip, port, options));
                case SOURCE_BUDDY, SOURCE_BUDDY_LARGE -> {
                    int buddyIp =
                            Ipv4.fromEd2k(Tags.intValue(tags, FileTag.SERVERIP).orElse(0));
                    int buddyPort = Tags.portValue(tags, FileTag.SERVERPORT).orElse(0);
                    Optional<UInt128> buddyId =
                            Tags.stringValue(tags, FileTag.BUDDYHASH).flatMap(KadNode::parseBuddyHash);
                    if (buddyIp != 0 && buddyPort != 0 && buddyId.isPresent()) {
                        Buddy buddy = new Buddy(buddyIp, buddyPort, buddyId.get());
                        sources.add(new Source(user, ip, port, options, udp, Optional.of(buddy), false));
                    }
                }
                case SOURCE_DIRECT_CALLBACK -> {
                    if ((options & DIRECT_CALLBACK) != 0 && udp != 0) {
                        sources.add(new Source(user, ip, port, options, udp, Optional.empty(), true));
                    }
                }
                default -> {
                    // Type 2 is read wrongly by old clients; other types are unknown.
                }
            }
        }
        return sources;
    }
}
