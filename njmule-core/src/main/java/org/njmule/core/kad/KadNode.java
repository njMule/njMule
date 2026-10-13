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
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.IntPredicate;
import org.njmule.core.api.Hash16;
import org.njmule.core.persist.AtomicFiles;
import org.njmule.core.persist.format.NodesDat;
import org.njmule.core.protocol.io.DecodeException;
import org.njmule.core.protocol.io.PacketReader;
import org.njmule.core.protocol.io.PacketWriter;
import org.njmule.core.protocol.kad.KadMessages;
import org.njmule.core.protocol.packet.Opcodes;
import org.njmule.core.protocol.packet.Packet;
import org.njmule.core.protocol.tag.FileTag;
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
public final class KadNode implements KadPublisher.Node, AutoCloseable {

    /** Sends Kad packets, obfuscated with the receiver's id or key when given. */
    public interface Transport {
        void send(int ip, int port, Packet packet, Optional<UInt128> receiverId, int receiverKey, int senderKey);
    }

    /** What the node needs from the client around it. */
    public interface Host {
        /** Connects to {@code ip:port} over TCP to prove to that node it is reachable. */
        void checkReachable(int ip, int port, Hash16 userHash, int connectOptions);
    }

    /** A source a Kad search found: a client reachable directly. */
    public record Source(Hash16 userHash, int ip, int tcpPort, int connectOptions) {}

    /** A file a keyword search found. */
    public record FileResult(Hash16 hash, String name, long size, int sources, Optional<String> type) {}

    /**
     * Settings that are not the protocol's.
     *
     * @param trusted addresses taken although they are not public (tests, LANs)
     * @param blocked addresses the IP filter refuses, never taken as contacts
     */
    public record Settings(
            int tcpPort, Hash16 userHash, int connectOptions, IntPredicate trusted, IntPredicate blocked) {
        public Settings {
            Objects.requireNonNull(userHash, "userHash");
            Objects.requireNonNull(trusted, "trusted");
            Objects.requireNonNull(blocked, "blocked");
        }
    }

    public static final String NODES_FILE = "nodes.dat";

    /** A node counts as connected while packets keep arriving. */
    static final Duration CONNECTED_FOR = Duration.ofMinutes(20);

    private static final Duration QUIET_FOR = Duration.ofMinutes(15);

    private static final Logger logger = LoggerFactory.getLogger(KadNode.class);
    private static final long SECOND = 1_000_000_000L;
    private static final long MINUTE = 60 * SECOND;
    private static final long HOUR = 60 * MINUTE;
    private static final Duration SELF_LOOKUP_FIRST = Duration.ofMinutes(3);
    private static final Duration SELF_LOOKUP_EVERY = Duration.ofHours(4);
    private static final Duration FIREWALL_CHECK_EVERY = Duration.ofHours(1);
    private static final int FIREWALL_CHECKS = 4;
    private static final int FIREWALL_ACKS = 2;
    private static final int UDP_FRAGMENT = 1_420;
    private static final int HELLO_ACK_VERSION = 8;
    private static final int OBFUSCATION_VERSION = 6;
    private static final int FIREWALL2_VERSION = 7;
    private static final int MISC_UDP_FIREWALLED = 0x01;
    private static final int MISC_TCP_FIREWALLED = 0x02;
    private static final int MISC_ACK_REQUESTED = 0x04;
    private static final int SOURCE_DIRECT = 1;
    private static final int SOURCE_DIRECT_LARGE = 4;

    private final KadIdentity identity;
    private final RoutingTable table;
    private final PacketTracking tracking = new PacketTracking();
    private final KadIndex index = new KadIndex();
    private final Transport transport;
    private final Host host;
    private final Settings settings;
    private final Path configDir;
    private final SecureRandom random;
    private final Ticker ticker;
    private final Map<UInt128, Lookup> lookups = new LinkedHashMap<>();
    private final Map<Lookup, Consumer<List<KadMessages.Entry>>> resultSinks = new HashMap<>();
    private final Map<Lookup, Object> lookupData = new HashMap<>();
    private final Map<Integer, Long> firewallChecks = new HashMap<>();
    private final Set<Integer> firewallAcks = new HashSet<>();
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
            this.addContacts(NodesDat.read(Files.readAllBytes(file)));
            logger.info("{} Kad contacts from {}", this.table.size(), file.getFileName());
        } catch (IOException e) {
            logger.warn("Cannot read {}", file, e);
        }
    }

    /** Adds the contacts of a {@code nodes.dat}, or bootstraps from them; returns how many were new. */
    public int addContacts(NodesDat.Contents contents) {
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
            if (contents.bootstrap()) {
                this.bootstrap(c.ip(), c.udpPort());
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

    /** Writes the best contacts to {@code nodes.dat}. */
    public void saveNodes() {
        List<NodesDat.Contact> records = new ArrayList<>();
        for (KadContact c : this.table.bootstrapContacts(NodesDat.MAX_WRITTEN, this.random)) {
            records.add(c.record());
        }
        if (records.isEmpty()) {
            return;
        }
        try {
            AtomicFiles.writeBytes(this.configDir.resolve(NODES_FILE), NodesDat.write(records));
        } catch (IOException e) {
            logger.warn("Cannot save the Kad contacts", e);
        }
    }

    @Override
    public void close() {
        this.saveNodes();
    }

    /** Asks the node at {@code ip:port} for contacts to start from. */
    public void bootstrap(int ip, int port) {
        this.send(ip, port, KadMessages.bootstrapRequest(), Optional.empty(), 0);
        this.tracking.sent(ip, Opcodes.Kad.BOOTSTRAP_REQ, this.nowMs());
    }

    /** Looks for files under the first keyword of {@code words}, filtered by {@code expression}. */
    public boolean searchKeyword(String words, Optional<SearchExpr> expression, Consumer<List<FileResult>> results) {
        List<String> keywords = Keywords.of(words);
        if (keywords.isEmpty()) {
            return false;
        }
        UInt128 target = Keywords.id(keywords.getFirst());
        if (this.lookups.containsKey(target)) {
            return false;
        }
        byte[] tree = new byte[0];
        if (expression.isPresent()) {
            PacketWriter out = new PacketWriter();
            SearchTreeCodec.write(out, expression.get(), true);
            tree = out.toByteArray();
        }
        Lookup lookup = this.startLookup(target, Lookup.Kind.KEYWORD);
        if (lookup == null) {
            return false;
        }
        this.lookupData.put(lookup, tree);
        this.resultSinks.put(lookup, entries -> results.accept(fileResults(entries, keywords)));
        return true;
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
        this.resultSinks.put(lookup, entries -> sources.accept(sourceResults(entries)));
        return true;
    }

    /** Publishes {@code files} under the keyword {@code word}. */
    @Override
    public boolean publishKeyword(String word, List<KadMessages.Entry> files) {
        UInt128 target = Keywords.id(word);
        if (files.isEmpty() || this.lookups.containsKey(target)) {
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

    /** Files indexed under keywords and sources indexed, for tests. */
    int[] indexed() {
        return new int[] {this.index.keywordEntries(), this.index.sourceEntries()};
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

    /** A node we asked to check our TCP port connected and said so. */
    public void reachableConfirmed(int ip) {
        if (this.firewallChecks.containsKey(ip)) {
            this.firewallChecks.remove(ip);
            this.firewallAcks.add(ip);
        }
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
            this.wasOpen = this.firewallAcks.size() >= FIREWALL_ACKS;
            this.firewallReports = 0;
            this.firewallAcks.clear();
        }
        if (now - this.nextClean >= 0) {
            this.nextClean = now + 12 * MINUTE;
            this.tracking.clean(this.nowMs());
            this.index.clean(now);
            this.firewallChecks.values().removeIf(at -> now - at > 3 * MINUTE);
        }
    }

    /** A Kad packet from {@code ip:port}; the keys come from its obfuscation (0 when plain). */
    public void received(int ip, int port, Packet packet, boolean obfuscated, int receiverKey, int senderKey) {
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
                case SEARCH_RES -> this.searchAnswered(peer, packet);
                case PUBLISH_KEY_REQ -> this.keywordPublished(peer, packet);
                case PUBLISH_SOURCE_REQ -> this.sourcePublished(peer, packet);
                case PUBLISH_RES -> this.publishAnswered(peer, packet);
                case FIREWALLED2_REQ -> this.firewallCheckRequested(peer, packet);
                case FIREWALLED_RES -> this.firewallAnswered(peer, packet);
                case PING -> this.answer(peer, KadMessages.pong(port));
                case PONG -> this.tracking.expected(ip, Opcodes.Kad.PONG, nowMs);
                default -> {
                    // Buddies, callbacks and the UDP firewall test are not taken part in.
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
        if (this.isGoodAddress(peer.ip())) {
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
        int udpPort = Tags.portValue(hello.tags(), FileTag.SOURCEUPORT).orElse(peer.port());
        int misc = (int) Tags.intValue(hello.tags(), FileTag.KADMISCOPTIONS).orElse(0);
        int version = hello.sender().version();
        long now = this.ticker.read();
        RoutingTable.Outcome outcome = RoutingTable.Outcome.REFUSED;
        if (this.isGoodAddress(peer.ip()) && (misc & MISC_UDP_FIREWALLED) == 0) {
            KadContact contact = new KadContact(
                    hello.sender().id(), peer.ip(), udpPort, hello.sender().tcpPort(), version, now);
            contact.setVerified(peer.validKey());
            outcome = this.table.add(contact, true, peer.key(this.publicIp), this.publicIp, now);
            this.table.get(contact.id()).ifPresent(KadContact::setHelloReceived);
        }
        boolean verified =
                this.table.get(hello.sender().id()).map(KadContact::isVerified).orElse(peer.validKey());
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

    private void helloAcknowledged(Peer peer, Packet packet) throws DecodeException {
        if (!peer.validKey() || !this.tracking.expected(peer.ip(), Opcodes.Kad.HELLO_RES_ACK, this.nowMs())) {
            return;
        }
        UInt128 id = KadMessages.decodeHelloAck(packet);
        this.table.get(id).filter(c -> c.ip() == peer.ip()).ifPresent(c -> c.setVerified(true));
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
        List<KadMessages.Contact> usable = new ArrayList<>();
        for (KadMessages.Contact c : answer.contacts()) {
            if (c.version() >= 2 && this.isGoodAddress(c.ip()) && !(c.udpPort() == 53 && c.version() <= 5)) {
                this.addUnverified(c, false, now);
                usable.add(c);
            }
        }
        this.table.byAddress(peer.ip()).ifPresent(c -> this.table.alive(c, now));
        Lookup lookup = this.lookups.get(answer.target());
        if (lookup != null) {
            lookup.answered(peer.ip(), peer.port(), usable, now);
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

    private void answerResults(Peer peer, UInt128 target, List<KadMessages.Entry> results) {
        if (results.isEmpty()) {
            return;
        }
        for (Packet p : KadMessages.SearchAnswer.split(this.identity.id(), target, results, UDP_FRAGMENT)) {
            this.answer(peer, p);
        }
    }

    private void searchAnswered(Peer peer, Packet packet) throws DecodeException {
        if (!this.tracking.awaits(peer.ip(), Opcodes.Kad.SEARCH_RES, this.nowMs())) {
            return;
        }
        KadMessages.SearchAnswer answer = KadMessages.SearchAnswer.decode(packet);
        Lookup lookup = this.lookups.get(answer.target());
        Consumer<List<KadMessages.Entry>> sink = lookup == null ? null : this.resultSinks.get(lookup);
        if (sink == null || lookup.isStopped()) {
            return;
        }
        lookup.valueReceived(answer.results().size());
        sink.accept(answer.results());
    }

    private void keywordPublished(Peer peer, Packet packet) throws DecodeException {
        KadMessages.KeywordPublish publish = KadMessages.KeywordPublish.decode(packet);
        if (!this.inTolerance(publish.keyword())) {
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
        if (!this.inTolerance(publish.file())) {
            return;
        }
        OptionalLong load = this.index.publishSource(
                publish.file(), publish.source(), publish.tags(), peer.ip(), peer.port(), this.ticker.read());
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
        if (lookup != null) {
            lookup.valueReceived(1);
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
        Lookup lookup = new Lookup(target, kind, new Requests(), this.ticker.read());
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
                    node.requestTo(
                            contact,
                            new KadMessages.SourcePublish(
                                            lookup.target(),
                                            UInt128.of(node.settings.userHash()),
                                            node.sourceTags((Long) data))
                                    .encode(),
                            Opcodes.Kad.PUBLISH_SOURCE_REQ);
                default -> {
                    // Node lookups only collect contacts.
                }
            }
        }
    }

    private List<Tag> sourceTags(long size) {
        List<Tag> tags = new ArrayList<>();
        int type = size > 4_290_048_000L ? SOURCE_DIRECT_LARGE : SOURCE_DIRECT;
        tags.add(new Tag.IntTag(TagName.id(FileTag.SOURCETYPE), type));
        tags.add(new Tag.IntTag(TagName.id(FileTag.SOURCEPORT), this.settings.tcpPort()));
        tags.add(new Tag.IntTag(TagName.id(FileTag.FILESIZE), size));
        tags.add(new Tag.IntTag(TagName.id(FileTag.ENCRYPTION), this.settings.connectOptions()));
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
        if (peerVersion >= HELLO_ACK_VERSION) {
            // Without a UDP test of our own, a reachable TCP port stands for a reachable node.
            int misc = this.isFirewalled() ? MISC_UDP_FIREWALLED | MISC_TCP_FIREWALLED : 0;
            if (askAck) {
                misc |= MISC_ACK_REQUESTED;
            }
            tags.add(new Tag.IntTag(TagName.id(FileTag.KADMISCOPTIONS), misc));
        }
        return new KadMessages.Hello(this.self(), tags);
    }

    private KadMessages.Self self() {
        return new KadMessages.Self(this.identity.id(), this.settings.tcpPort(), KadMessages.VERSION);
    }

    private void addUnverified(KadMessages.Contact c, boolean verified, long now) {
        if (!this.isGoodAddress(c.ip()) || c.version() < 2) {
            return;
        }
        KadContact contact = KadContact.of(c, now);
        contact.setVerified(verified);
        this.table.add(contact, false, NodesDat.UdpKey.NONE, this.publicIp, now);
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
        int a = ip >>> 24;
        int b = (ip >>> 16) & 0xFF;
        boolean lan = a == 10 || (a == 172 && b >= 16 && b < 32) || (a == 192 && b == 168);
        return Ipv4.isUnicastPublicCandidate(ip) && !lan;
    }

    private long nowMs() {
        return this.ticker.read() / 1_000_000L;
    }

    private static List<FileResult> fileResults(List<KadMessages.Entry> entries, List<String> keywords) {
        List<FileResult> results = new ArrayList<>();
        for (KadMessages.Entry e : entries) {
            Optional<String> name = Tags.stringValue(e.tags(), FileTag.FILENAME);
            long size = Tags.intValue(e.tags(), FileTag.FILESIZE).orElse(0);
            if (name.isEmpty() || size <= 0 || !Keywords.of(name.get()).containsAll(keywords)) {
                continue;
            }
            results.add(new FileResult(
                    e.id().toHash16(),
                    name.get(),
                    size,
                    (int) Tags.intValue(e.tags(), FileTag.SOURCES).orElse(0),
                    Tags.stringValue(e.tags(), FileTag.FILETYPE)));
        }
        return results;
    }

    private static List<Source> sourceResults(List<KadMessages.Entry> entries) {
        List<Source> sources = new ArrayList<>();
        for (KadMessages.Entry e : entries) {
            int type = (int) Tags.intValue(e.tags(), FileTag.SOURCETYPE).orElse(0);
            if (type != SOURCE_DIRECT && type != SOURCE_DIRECT_LARGE) {
                continue;
            }
            int ip = (int) Tags.intValue(e.tags(), FileTag.SOURCEIP).orElse(0);
            int port = Tags.portValue(e.tags(), FileTag.SOURCEPORT).orElse(0);
            if (ip == 0 || port == 0) {
                continue;
            }
            sources.add(new Source(e.id().toHash16(), ip, port, (int)
                    Tags.intValue(e.tags(), FileTag.ENCRYPTION).orElse(0)));
        }
        return sources;
    }
}
