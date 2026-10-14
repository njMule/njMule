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

import static org.assertj.core.api.Assertions.assertThat;

import com.google.common.base.Ticker;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.core.api.Hash16;
import org.njmule.core.api.KadContactInfo;
import org.njmule.core.api.KadSearchInfo;
import org.njmule.core.api.SearchResult;
import org.njmule.core.persist.format.NodesDat;
import org.njmule.core.protocol.io.PacketWriter;
import org.njmule.core.protocol.kad.KadMessages;
import org.njmule.core.protocol.packet.Opcodes;
import org.njmule.core.protocol.packet.Packet;
import org.njmule.core.protocol.packet.Protocol;
import org.njmule.core.protocol.tag.FileTag;
import org.njmule.core.protocol.tag.Tag;
import org.njmule.core.protocol.tag.TagName;
import org.njmule.core.protocol.tag.Tags;
import org.njmule.core.search.SearchExpr;
import org.njmule.core.search.SearchTreeCodec;
import org.njmule.core.util.Ipv4;

/** The Kad messages a node answers and takes, with a manual clock and a transport that only records. */
class KadNodeMessagesTest {

    private static final int PORT = 4672;
    private static final int PEER = Ipv4.of(20, 1, 1, 1);
    private static final long SECOND = 1_000_000_000L;
    private static final long MINUTE = 60 * SECOND;
    private static final Hash16 USER = Hash16.parseHex("0102030405060708090a0b0c0d0e0f10");

    /** A packet the node sent. */
    private record Sent(int ip, int port, Packet packet, Optional<UInt128> receiverId, int receiverKey) {}

    /** A node the host was asked to connect to. */
    private record Check(int ip, int port, Hash16 userHash, int options) {}

    @TempDir
    Path dir;

    private final SecureRandom random = new SecureRandom();
    private final AtomicLong time = new AtomicLong(1_000 * SECOND);
    private final Ticker ticker = new Ticker() {
        @Override
        public long read() {
            return KadNodeMessagesTest.this.time.get();
        }
    };
    private final List<Sent> sent = new CopyOnWriteArrayList<>();
    private final List<Check> checks = new ArrayList<>();
    private final Set<Integer> blocked = new HashSet<>();
    private KadIdentity identity;
    private KadNode node;

    @BeforeEach
    void create() throws IOException {
        this.identity = KadIdentity.loadOrCreate(this.dir, this.random);
        this.node = new KadNode(
                this.identity,
                (ip, port, packet, receiverId, receiverKey, senderKey) ->
                        this.sent.add(new Sent(ip, port, packet, receiverId, receiverKey)),
                (ip, port, userHash, options) -> this.checks.add(new Check(ip, port, userHash, options)),
                new KadNode.Settings(4662, USER, 3, ip -> false, this.blocked::contains),
                this.dir,
                this.random,
                this.ticker);
    }

    @Test
    void answersABootstrapAndTakesOnlyTheAnswerToItsOwnRequest() throws Exception {
        this.node.addContacts(contents(this.record(Ipv4.of(70, 1, 1, 1)), this.record(Ipv4.of(71, 1, 1, 1))));

        this.receive(PEER, KadMessages.bootstrapRequest());

        KadMessages.BootstrapAnswer ours =
                KadMessages.BootstrapAnswer.decode(this.last(PEER, Opcodes.Kad.BOOTSTRAP_RES));
        assertThat(ours.sender().id()).isEqualTo(this.node.id());
        assertThat(ours.sender().tcpPort()).isEqualTo(4662);
        assertThat(ours.contacts()).hasSize(2);

        int helper = Ipv4.of(25, 1, 1, 1);
        UInt128 helperId = UInt128.random(this.random);
        Packet answer = new KadMessages.BootstrapAnswer(
                        new KadMessages.Self(helperId, 4662, 8),
                        List.of(
                                this.contact(Ipv4.of(30, 1, 1, 1)),
                                this.contact(Ipv4.of(192, 168, 1, 9)),
                                new KadMessages.Contact(UInt128.random(this.random), Ipv4.of(31, 1, 1, 1), PORT, 0, 1)))
                .encode();
        this.receive(helper, answer);
        assertThat(this.node.contacts()).as("an answer nobody asked for").isEqualTo(2);

        this.node.bootstrap(helper, PORT);
        assertThat(this.last(helper, Opcodes.Kad.BOOTSTRAP_REQ)).isNotNull();
        this.receive(helper, answer);

        assertThat(this.node.contacts())
                .as("the helper and its one usable contact, not a LAN address or an old version")
                .isEqualTo(4);
        assertThat(this.node.verifiedContacts())
                .as("the helper proved itself by answering")
                .isEqualTo(3);
    }

    @Test
    void aBootstrapListIsAskedRatherThanTaken() {
        int lan = Ipv4.of(192, 168, 1, 9);
        int filtered = Ipv4.of(50, 1, 1, 1);
        this.blocked.add(filtered);

        int added = this.node.addContacts(
                new NodesDat.Contents(2, true, List.of(this.record(PEER), this.record(lan), this.record(filtered))));

        assertThat(added).isZero();
        assertThat(this.node.contacts()).isZero();
        assertThat(this.sent)
                .extracting(Sent::ip)
                .as("only the public address allowed by the filter")
                .containsExactly(PEER);
        assertThat(this.sent.getFirst().packet().opcode()).isEqualTo(Opcodes.Kad.BOOTSTRAP_REQ.code());
    }

    @Test
    void aHelloIsAnsweredAndANodeThatAcknowledgesBecomesVerified() throws Exception {
        UInt128 peerId = UInt128.random(this.random);

        this.receive(
                PEER, new KadMessages.Hello(new KadMessages.Self(peerId, 4662, 8), List.of()).encode(false), 0, 77);

        assertThat(this.node.contacts()).isEqualTo(1);
        assertThat(this.node.verifiedContacts()).isZero();
        Sent reply = this.sentWith(PEER, Opcodes.Kad.HELLO_RES);
        assertThat(reply.receiverId()).as("obfuscated with its id").contains(peerId);
        assertThat(reply.receiverKey()).as("and the key it sent").isEqualTo(77);
        KadMessages.Hello hello = KadMessages.Hello.decode(reply.packet());
        assertThat(hello.sender().id()).isEqualTo(this.node.id());
        assertThat(Tags.intValue(hello.tags(), FileTag.KADMISCOPTIONS))
                .as("firewalled, and asking for an acknowledgement")
                .hasValue(0x01 | 0x02 | 0x04);
        assertThat(this.last(PEER, Opcodes.Kad.FIREWALLED2_REQ))
                .as("a recent node is asked to check our port")
                .isNotNull();

        // An acknowledgement without our key proves nothing.
        this.receive(PEER, KadMessages.helloAck(peerId), 0, 0);
        assertThat(this.node.verifiedContacts()).isZero();
        this.receive(PEER, KadMessages.helloAck(peerId), this.identity.verifyKey(PEER), 0);

        assertThat(this.node.verifiedContacts()).isEqualTo(1);
    }

    @Test
    void anOldNodeOrOneBehindAFirewallIsAnsweredButNotAlwaysTaken() throws Exception {
        int old = Ipv4.of(21, 1, 1, 1);
        this.receive(
                old,
                new KadMessages.Hello(new KadMessages.Self(UInt128.random(this.random), 4662, 5), List.of())
                        .encode(false));

        Sent plain = this.sentWith(old, Opcodes.Kad.HELLO_RES);
        assertThat(plain.receiverId()).as("too old for obfuscation").isEmpty();
        assertThat(KadMessages.Hello.decode(plain.packet()).tags()).isEmpty();
        assertThat(this.node.contacts()).isEqualTo(1);
        assertThat(this.last(old, Opcodes.Kad.FIREWALLED2_REQ))
                .as("too old to check our port")
                .isNull();

        int firewalled = Ipv4.of(22, 1, 1, 1);
        Tag udpFirewalled = new Tag.IntTag(TagName.id(FileTag.KADMISCOPTIONS), 0x01);
        this.receive(
                firewalled,
                new KadMessages.Hello(
                                new KadMessages.Self(UInt128.random(this.random), 4662, 8), List.of(udpFirewalled))
                        .encode(false));

        assertThat(this.sentWith(firewalled, Opcodes.Kad.HELLO_RES)).isNotNull();
        assertThat(this.node.contacts())
                .as("a node others cannot reach is no contact")
                .isEqualTo(1);
    }

    @Test
    void anOldestContactIsCheckedEachMinuteAndDroppedWhenSilent() throws Exception {
        int quiet = Ipv4.of(70, 1, 1, 1);
        this.node.addContacts(contents(this.record(quiet)));

        this.time.addAndGet(MINUTE);
        this.node.tick();
        assertThat(this.last(quiet, Opcodes.Kad.HELLO_REQ))
                .as("asked whether it is still there")
                .isNotNull();

        this.time.addAndGet(MINUTE);
        this.node.tick();
        assertThat(this.node.contacts()).as("still waited for").isEqualTo(1);
        this.time.addAndGet(MINUTE);
        this.node.tick();

        assertThat(this.node.contacts()).as("gone once its check expired").isZero();
    }

    @Test
    void aContactThatAnswersItsCheckStaysAndIsAcknowledgedWhenItAsks() throws Exception {
        int alive = Ipv4.of(70, 1, 1, 1);
        UInt128 id = UInt128.random(this.random);
        this.node.addContacts(contents(new NodesDat.Contact(id, alive, PORT, 4662, 8, 0, NodesDat.UdpKey.NONE, true)));
        Packet answer = new KadMessages.Hello(
                        new KadMessages.Self(id, 4662, 8),
                        List.of(new Tag.IntTag(TagName.id(FileTag.KADMISCOPTIONS), 0x04)))
                .encode(true);

        this.receive(alive, answer, 0, 99);
        assertThat(this.last(alive, Opcodes.Kad.HELLO_RES_ACK))
                .as("not asked for")
                .isNull();

        this.time.addAndGet(MINUTE);
        this.node.tick();
        assertThat(this.last(alive, Opcodes.Kad.HELLO_REQ)).isNotNull();
        this.receive(alive, answer, 0, 99);

        Sent ack = this.sentWith(alive, Opcodes.Kad.HELLO_RES_ACK);
        assertThat(KadMessages.decodeHelloAck(ack.packet())).isEqualTo(this.node.id());
        assertThat(ack.receiverKey()).isEqualTo(99);
        for (int i = 0; i < 3; i++) {
            this.time.addAndGet(MINUTE);
            this.node.tick();
        }
        assertThat(this.node.contacts())
                .as("it answered: no longer checked to death")
                .isEqualTo(1);
    }

    @Test
    void aLookupForUsIsAnsweredAndOneForAnotherNodeIsNot() throws Exception {
        this.node.addContacts(contents(this.record(Ipv4.of(70, 1, 1, 1))));
        UInt128 target = UInt128.random(this.random);

        this.receive(PEER, new KadMessages.Request(2, target, UInt128.random(this.random)).encode());
        assertThat(this.last(PEER, Opcodes.Kad.RES)).isNull();

        this.receive(PEER, new KadMessages.Request(2, target, this.node.id()).encode());

        assertThat(KadMessages.Answer.decode(this.last(PEER, Opcodes.Kad.RES)).target())
                .isEqualTo(target);
    }

    @Test
    void keywordsPublishedToUsAreFoundWithAndWithoutAnExpression() throws Exception {
        UInt128 keyword = this.node.id();
        KadMessages.Entry file = entry(UInt128.random(this.random), "ubuntu desktop.iso", 4000);

        this.receive(PEER, new KadMessages.KeywordPublish(keyword, List.of(file)).encode());

        KadMessages.PublishAnswer load = KadMessages.PublishAnswer.decode(this.last(PEER, Opcodes.Kad.PUBLISH_RES));
        assertThat(load.target()).isEqualTo(keyword);
        assertThat(this.node.indexed()[0]).isEqualTo(1);

        int searcher = Ipv4.of(23, 1, 1, 1);
        this.receive(searcher, new KadMessages.KeywordSearch(keyword, 0, new byte[0]).encode());
        assertThat(KadMessages.SearchAnswer.decode(this.last(searcher, Opcodes.Kad.SEARCH_RES))
                        .results())
                .extracting(KadMessages.Entry::id)
                .containsExactly(file.id());

        int other = Ipv4.of(24, 1, 1, 1);
        this.receive(other, new KadMessages.KeywordSearch(keyword, 0, tree(new SearchExpr.Keyword("ubuntu"))).encode());
        assertThat(this.last(other, Opcodes.Kad.SEARCH_RES))
                .as("the expression matches")
                .isNotNull();
        int nobody = Ipv4.of(26, 1, 1, 1);
        this.receive(
                nobody, new KadMessages.KeywordSearch(keyword, 0, tree(new SearchExpr.Keyword("windows"))).encode());
        assertThat(this.last(nobody, Opcodes.Kad.SEARCH_RES))
                .as("nothing found, nothing sent")
                .isNull();
    }

    @Test
    void publishesFarFromUsAreNotStored() throws Exception {
        UInt128 far = farFrom(this.node.id());
        int other = Ipv4.of(27, 1, 1, 1);

        this.receive(PEER, new KadMessages.KeywordPublish(far, List.of(entry(far, "far away.iso", 10))).encode());
        this.receive(other, new KadMessages.SourcePublish(far, UInt128.random(this.random), sourceTags(1)).encode());

        assertThat(this.last(PEER, Opcodes.Kad.PUBLISH_RES)).isNull();
        assertThat(this.last(other, Opcodes.Kad.PUBLISH_RES)).isNull();
        assertThat(this.node.indexed()).containsExactly(0, 0);
    }

    @Test
    void sourcesPublishedToUsAreFoundAtTheAddressTheyCameFrom() throws Exception {
        UInt128 file = this.node.id();
        UInt128 source = UInt128.random(this.random);

        this.receive(PEER, new KadMessages.SourcePublish(file, source, sourceTags(1)).encode());
        assertThat(KadMessages.PublishAnswer.decode(this.last(PEER, Opcodes.Kad.PUBLISH_RES))
                        .target())
                .isEqualTo(file);
        int untyped = Ipv4.of(28, 1, 1, 1);
        this.receive(untyped, new KadMessages.SourcePublish(file, UInt128.random(this.random), List.of()).encode());
        assertThat(this.last(untyped, Opcodes.Kad.PUBLISH_RES))
                .as("a source of no type")
                .isNull();

        int searcher = Ipv4.of(23, 1, 1, 1);
        this.receive(searcher, new KadMessages.SourceSearch(file, 0, 0).encode());

        List<KadMessages.Entry> found = KadMessages.SearchAnswer.decode(this.last(searcher, Opcodes.Kad.SEARCH_RES))
                .results();
        assertThat(found).extracting(KadMessages.Entry::id).containsExactly(source);
        assertThat(Tags.intValue(found.getFirst().tags(), FileTag.SOURCEIP)).hasValue(Integer.toUnsignedLong(PEER));
    }

    @Test
    void aFirewallCheckIsAnsweredOnlyForAddressesThatCouldBeReal() throws Exception {
        UInt128 asker = UInt128.random(this.random);

        this.receive(PEER, new KadMessages.FirewallCheck(5000, asker, 1).encode());

        assertThat(KadMessages.decodeFirewallAnswer(this.last(PEER, Opcodes.Kad.FIREWALLED_RES)))
                .isEqualTo(PEER);
        assertThat(this.checks).containsExactly(new Check(PEER, 5000, asker.toHash16(), 1));

        int lan = Ipv4.of(192, 168, 1, 9);
        this.receive(lan, new KadMessages.FirewallCheck(5000, asker, 1).encode());
        int noPort = Ipv4.of(29, 1, 1, 1);
        this.receive(noPort, new KadMessages.FirewallCheck(0, asker, 1).encode());

        assertThat(this.last(lan, Opcodes.Kad.FIREWALLED_RES)).isNull();
        assertThat(this.last(noPort, Opcodes.Kad.FIREWALLED_RES)).isNull();
        assertThat(this.checks).hasSize(1);
    }

    @Test
    void ourAddressChangesOnlyWhenTwoNodesAgreeOnTheNewOne() {
        int first = Ipv4.of(80, 1, 1, 1);
        int second = Ipv4.of(81, 1, 1, 1);
        int[] helpers = {Ipv4.of(21, 1, 1, 1), Ipv4.of(22, 1, 1, 1), Ipv4.of(23, 1, 1, 1), Ipv4.of(24, 1, 1, 1)};
        for (int helper : helpers) {
            this.hello(helper, 8);
        }
        // From a node never asked.
        this.receive(Ipv4.of(25, 1, 1, 1), KadMessages.firewallAnswer(first));
        assertThat(this.node.publicIp()).isZero();

        this.receive(helpers[0], KadMessages.firewallAnswer(first));
        assertThat(this.node.publicIp()).isEqualTo(first);
        this.receive(helpers[1], KadMessages.firewallAnswer(second));
        assertThat(this.node.publicIp()).as("one report is not enough").isEqualTo(first);
        this.receive(helpers[2], KadMessages.firewallAnswer(second));
        assertThat(this.node.publicIp()).isEqualTo(second);
        this.receive(helpers[3], KadMessages.firewallAnswer(second));
        assertThat(this.node.publicIp()).isEqualTo(second);

        // Four reports: no further node is asked until the next check.
        int late = Ipv4.of(26, 1, 1, 1);
        this.hello(late, 8);
        assertThat(this.last(late, Opcodes.Kad.FIREWALLED2_REQ)).isNull();
        assertThat(this.node.isFirewalled()).as("no node connected to us").isTrue();
    }

    @Test
    void aCheckThatEndsWithoutTwoConnectionsLeavesUsFirewalled() {
        int[] helpers = {Ipv4.of(21, 1, 1, 1), Ipv4.of(22, 1, 1, 1), Ipv4.of(23, 1, 1, 1), Ipv4.of(24, 1, 1, 1)};
        for (int helper : helpers) {
            this.hello(helper, 8);
            this.receive(helper, KadMessages.firewallAnswer(Ipv4.of(80, 1, 1, 1)));
        }
        this.node.reachableConfirmed(helpers[0]);
        this.node.reachableConfirmed(Ipv4.of(99, 1, 1, 1));

        this.node.recheckFirewall();
        this.node.tick();

        assertThat(this.node.isFirewalled()).isTrue();
        assertThat(this.node.awaitsConnectionFrom(helpers[1]))
                .as("still awaited")
                .isTrue();
        this.time.addAndGet(13 * MINUTE);
        this.node.tick();
        assertThat(this.node.awaitsConnectionFrom(helpers[1]))
                .as("forgotten after three minutes")
                .isFalse();
    }

    @Test
    void aPingIsAnsweredWithThePortItCameFromAndAFloodIsBanned() throws Exception {
        Packet ping = KadMessages.ping();
        this.node.received(PEER, 5555, ping, false, 0, 0);
        assertThat(KadMessages.decodePong(this.last(PEER, Opcodes.Kad.PONG))).isEqualTo(5555);
        // A pong nobody waits for changes nothing.
        this.receive(PEER, KadMessages.pong(PORT));

        for (int i = 0; i < 20; i++) {
            this.receive(PEER, ping);
        }
        int pongs = this.count(PEER, Opcodes.Kad.PONG);
        assertThat(pongs).as("only a few are answered").isLessThan(5);

        this.receive(PEER, KadMessages.bootstrapRequest());
        assertThat(this.last(PEER, Opcodes.Kad.BOOTSTRAP_RES))
                .as("banned for everything")
                .isNull();

        this.time.addAndGet(121 * MINUTE);
        this.receive(PEER, ping);
        assertThat(this.count(PEER, Opcodes.Kad.PONG)).as("the ban ends").isEqualTo(pongs + 1);
    }

    @Test
    void unknownBuddyAndMalformedPacketsAreDropped() {
        this.receive(PEER, Packet.of(Protocol.KAD, 0xEE, new byte[0]));
        this.receive(PEER, Packet.of(Protocol.KAD, Opcodes.Kad.FINDBUDDY_REQ.code(), new byte[20]));
        this.receive(PEER, Packet.of(Protocol.KAD, Opcodes.Kad.HELLO_REQ.code(), new byte[3]));
        this.receive(PEER, Packet.of(Protocol.KAD, Opcodes.Kad.FIREWALLED_RES.code(), new byte[3]));

        assertThat(this.sent).isEmpty();
        assertThat(this.node.contacts()).isZero();
    }

    @Test
    void aSourceSearchKeepsOnlyClientsThatCanBeReachedDirectly() throws Exception {
        Hash16 hash = this.hash();
        UInt128 target = UInt128.of(hash);
        this.contactAt(target);
        List<KadNode.Source> found = new ArrayList<>();

        assertThat(this.node.findSources(hash, 1000, found::addAll)).isTrue();
        assertThat(this.node.findSources(hash, 1000, found::addAll))
                .as("already running")
                .isFalse();
        this.answerAndWait(target);
        KadMessages.SourceSearch request =
                KadMessages.SourceSearch.decode(this.last(PEER, Opcodes.Kad.SEARCH_SOURCE_REQ));
        assertThat(request.size()).isEqualTo(1000);

        UInt128 direct = UInt128.random(this.random);
        UInt128 large = UInt128.random(this.random);
        List<KadMessages.Entry> entries = List.of(
                new KadMessages.Entry(direct, source(1, Ipv4.of(90, 1, 1, 1), 4662, 0)),
                new KadMessages.Entry(large, source(4, Ipv4.of(91, 1, 1, 1), 4663, 3)),
                new KadMessages.Entry(UInt128.random(this.random), source(3, Ipv4.of(92, 1, 1, 1), 4662, 0)),
                new KadMessages.Entry(UInt128.random(this.random), source(1, 0, 4662, 0)),
                new KadMessages.Entry(UInt128.random(this.random), source(1, Ipv4.of(93, 1, 1, 1), 0, 0)));
        this.receive(PEER, new KadMessages.SearchAnswer(target, target, entries).encode());

        assertThat(found)
                .containsExactly(
                        new KadNode.Source(direct.toHash16(), Ipv4.of(90, 1, 1, 1), 4662, 0),
                        new KadNode.Source(large.toHash16(), Ipv4.of(91, 1, 1, 1), 4663, 3));
    }

    @Test
    void aKeywordSearchKeepsOnlyFilesThatMatchAllItsWords() throws Exception {
        UInt128 target = Keywords.id("ubuntu");
        this.contactAt(target);
        List<KadNode.FileResult> found = new ArrayList<>();
        SearchExpr expression = new SearchExpr.Keyword("ubuntu desktop");

        assertThat(this.node.searchKeyword("ubuntu desktop", Optional.of(expression), found::addAll))
                .isPresent();
        this.answerAndWait(target);
        KadMessages.KeywordSearch request =
                KadMessages.KeywordSearch.decode(this.last(PEER, Opcodes.Kad.SEARCH_KEY_REQ));
        assertThat(request.expression()).as("the expression goes along").isEqualTo(tree(expression));

        UInt128 good = UInt128.random(this.random);
        List<KadMessages.Entry> entries = List.of(
                new KadMessages.Entry(
                        good,
                        List.of(
                                new Tag.StringTag(TagName.id(FileTag.FILENAME), "Ubuntu Desktop 24.iso"),
                                new Tag.IntTag(TagName.id(FileTag.FILESIZE), 5000),
                                new Tag.IntTag(TagName.id(FileTag.SOURCES), 7),
                                new Tag.StringTag(TagName.id(FileTag.FILETYPE), "Iso"),
                                new Tag.StringTag(TagName.id(FileTag.MEDIA_TITLE), "Noble Numbat"),
                                new Tag.IntTag(TagName.id(FileTag.MEDIA_LENGTH), 95))),
                entry(UInt128.random(this.random), "ubuntu server.iso", 5000),
                entry(UInt128.random(this.random), "ubuntu desktop.iso", 0),
                new KadMessages.Entry(
                        UInt128.random(this.random), List.of(new Tag.IntTag(TagName.id(FileTag.FILESIZE), 5))));
        this.receive(PEER, new KadMessages.SearchAnswer(target, target, entries).encode());

        assertThat(found)
                .containsExactly(new KadNode.FileResult(
                        good.toHash16(),
                        "Ubuntu Desktop 24.iso",
                        5000,
                        7,
                        Optional.of("Iso"),
                        new SearchResult.Media("", "", "Noble Numbat", 95, 0, "")));
        // Results for a search nobody runs.
        UInt128 other = Keywords.id("debian");
        this.receive(PEER, new KadMessages.SearchAnswer(other, other, entries).encode());
        assertThat(found).hasSize(1);
    }

    @Test
    void searchesNeedAWordAndAContact() {
        assertThat(this.node.searchKeyword("a b", Optional.empty(), r -> {}))
                .as("no word long enough")
                .isEmpty();
        assertThat(this.node.searchKeyword("ubuntu", Optional.empty(), r -> {}))
                .as("nobody to ask")
                .isEmpty();
        assertThat(this.node.findSources(this.hash(), 10, s -> {})).isFalse();
        assertThat(this.node.publishSource(this.hash(), 10)).isFalse();
        assertThat(this.node.publishKeyword("ubuntu", List.of(entry(UInt128.random(this.random), "ubuntu.iso", 1))))
                .isFalse();
        assertThat(this.node.runningLookups()).isZero();
    }

    @Test
    void keywordsArePublishedInBatchesAndTheirAnswersCounted() throws Exception {
        UInt128 target = Keywords.id("ubuntu");
        this.contactAt(target);
        List<KadMessages.Entry> files = new ArrayList<>();
        for (int i = 0; i < KadMessages.MAX_PUBLISHED_FILES + 10; i++) {
            files.add(entry(UInt128.random(this.random), "ubuntu " + i + ".iso", 1000 + i));
        }

        assertThat(this.node.publishKeyword("ubuntu", List.of()))
                .as("nothing to publish")
                .isFalse();
        assertThat(this.node.publishKeyword("ubuntu", files)).isTrue();
        assertThat(this.node.publishKeyword("ubuntu", files))
                .as("already running")
                .isFalse();
        assertThat(this.node.runningKeywordPublishes()).isEqualTo(1);
        assertThat(this.node.runningSourcePublishes()).isZero();
        this.answerAndWait(target);

        assertThat(this.count(PEER, Opcodes.Kad.PUBLISH_KEY_REQ)).isEqualTo(2);
        assertThat(KadMessages.KeywordPublish.decode(this.last(PEER, Opcodes.Kad.PUBLISH_KEY_REQ))
                        .files())
                .hasSize(10);

        this.receive(PEER, new KadMessages.PublishAnswer(target, 3).encode());
        assertThat(this.node.lookupInfos()).singleElement().satisfies(l -> {
            assertThat(l.kind()).isEqualTo(KadSearchInfo.Kind.STORE_KEYWORD);
            assertThat(l.answers()).isEqualTo(1);
        });
        // An answer to no request.
        this.receive(Ipv4.of(70, 1, 1, 1), new KadMessages.PublishAnswer(target, 3).encode());
        assertThat(this.node.lookupInfos().getFirst().answers()).isEqualTo(1);
    }

    @Test
    void weArePublishedAsASourceWithOurPortAndTheKindOfSize() throws Exception {
        Hash16 hash = this.hash();
        UInt128 target = UInt128.of(hash);
        this.contactAt(target);
        long size = 5_000_000_000L;

        assertThat(this.node.publishSource(hash, size)).isTrue();
        assertThat(this.node.publishSource(hash, size)).as("already running").isFalse();
        assertThat(this.node.runningSourcePublishes()).isEqualTo(1);
        assertThat(this.node.lookupInfos())
                .singleElement()
                .satisfies(l -> assertThat(l.kind()).isEqualTo(KadSearchInfo.Kind.STORE_SOURCE));
        this.answerAndWait(target);

        KadMessages.SourcePublish publish =
                KadMessages.SourcePublish.decode(this.last(PEER, Opcodes.Kad.PUBLISH_SOURCE_REQ));
        assertThat(publish.file()).isEqualTo(target);
        assertThat(publish.source()).isEqualTo(UInt128.of(USER));
        assertThat(Tags.intValue(publish.tags(), FileTag.SOURCETYPE))
                .as("a large file")
                .hasValue(4);
        assertThat(Tags.intValue(publish.tags(), FileTag.SOURCEPORT)).hasValue(4662);
        assertThat(Tags.intValue(publish.tags(), FileTag.FILESIZE)).hasValue(size);
        assertThat(Tags.intValue(publish.tags(), FileTag.ENCRYPTION)).hasValue(3);
    }

    @Test
    void theLookupsShowWhatTheyLookFor() {
        Hash16 hash = this.hash();
        this.aliveContact(PEER);

        assertThat(this.node.findSources(hash, 10, s -> {})).isTrue();
        assertThat(this.node.searchKeyword("ubuntu", Optional.empty(), r -> {})).isPresent();
        this.node.lookForContacts();
        this.node.tick();

        assertThat(this.node.lookupInfos())
                .extracting(KadSearchInfo::kind)
                .contains(KadSearchInfo.Kind.SOURCES, KadSearchInfo.Kind.KEYWORD, KadSearchInfo.Kind.NODE);
        assertThat(this.node.runningLookups()).isEqualTo(this.node.lookupInfos().size());
    }

    @Test
    void theFirstLookupOfOurOwnIdMakesTheNodeReady() throws Exception {
        this.aliveContact(PEER);
        assertThat(this.node.isReady()).isFalse();

        this.time.addAndGet(3 * MINUTE);
        this.node.tick();
        assertThat(this.requestedTargets()).as("our own id is looked up").contains(this.node.id());
        assertThat(this.node.isReady()).isFalse();
        this.time.addAndGet(MINUTE);
        this.node.tick();

        assertThat(this.node.isReady()).isTrue();
    }

    @Test
    void aQuietNodeLooksForContactsAgain() throws Exception {
        this.aliveContact(PEER);
        this.time.addAndGet(11 * SECOND);
        this.node.tick();
        this.time.addAndGet(10 * SECOND);
        this.node.tick();
        this.sent.clear();

        this.time.addAndGet(16 * MINUTE);
        this.node.tick();

        assertThat(this.requestedTargets())
                .as("a zone is looked up again, besides our own id")
                .anyMatch(target -> !target.equals(this.node.id()));
        assertThat(this.node.isConnected())
                .as("heard from within twenty minutes")
                .isTrue();
        this.time.addAndGet(5 * MINUTE);
        assertThat(this.node.isConnected()).isFalse();
    }

    @Test
    void anUnreadableNodesDatLeavesNoContactsAndNoneAreSaved() throws Exception {
        Path file = this.dir.resolve(KadNode.NODES_FILE);
        Files.write(file, new byte[] {1, 2, 3});

        this.node.loadNodes();
        assertThat(this.node.contacts()).isZero();

        Files.delete(file);
        this.node.close();
        assertThat(file).as("no contacts, no file").doesNotExist();
    }

    @Test
    void contactsThatCannotBeSavedStayInTheTable() throws Exception {
        this.node.addContacts(contents(this.record(PEER)));
        Path tmp = this.dir.resolve(KadNode.NODES_FILE + ".tmp");
        Files.createDirectories(tmp);
        Files.writeString(tmp.resolve("keep"), "x");

        this.node.saveNodes();

        assertThat(this.dir.resolve(KadNode.NODES_FILE)).doesNotExist();
        assertThat(this.node.contacts()).isEqualTo(1);
    }

    @Test
    void answersNobodyAskedForAreDropped() throws Exception {
        int stranger = Ipv4.of(40, 1, 1, 1);
        UInt128 target = Keywords.id("ubuntu");

        this.receive(stranger, new KadMessages.Answer(target, List.of(this.contact(Ipv4.of(41, 1, 1, 1)))).encode());
        this.receive(
                stranger,
                new KadMessages.SearchAnswer(target, target, List.of(entry(target, "ubuntu.iso", 10))).encode());

        assertThat(this.node.contacts()).isZero();
        assertThat(this.sent).isEmpty();
    }

    @Test
    void contactsAnAnswerCannotVouchForAreLeftOut() {
        UInt128 target = Keywords.id("ubuntu");
        this.contactAt(target);
        assertThat(this.node.publishKeyword("ubuntu", List.of(entry(UInt128.random(this.random), "ubuntu.iso", 10))))
                .isTrue();

        this.receive(
                PEER,
                new KadMessages.Answer(
                                target,
                                List.of(
                                        new KadMessages.Contact(
                                                UInt128.random(this.random), Ipv4.of(41, 1, 1, 1), PORT, 0, 1),
                                        this.contact(Ipv4.of(192, 168, 7, 7)),
                                        new KadMessages.Contact(
                                                UInt128.random(this.random), Ipv4.of(42, 1, 1, 1), 53, 0, 5),
                                        this.contact(Ipv4.of(43, 1, 1, 1))))
                        .encode());

        assertThat(this.node.contactInfos())
                .extracting(KadContactInfo::address)
                .containsExactlyInAnyOrder(Ipv4.format(PEER) + ":" + PORT, "43.1.1.1:" + PORT);
    }

    @Test
    void aNodeOnThisMachineIsAnsweredButNotTaken() throws Exception {
        this.hello(Ipv4.of(127, 0, 0, 1), 8);

        assertThat(this.sentWith(Ipv4.of(127, 0, 0, 1), Opcodes.Kad.HELLO_RES)).isNotNull();
        assertThat(this.node.contacts()).isZero();
        assertThat(this.last(Ipv4.of(127, 0, 0, 1), Opcodes.Kad.FIREWALLED2_REQ))
                .as("it could not check our port")
                .isNull();
    }

    @Test
    void ourOwnIdIsNotLookedUpTwiceAtOnce() throws Exception {
        this.aliveContact(PEER);
        assertThat(this.node.findSources(this.node.id().toHash16(), 10, s -> {}))
                .isTrue();

        this.node.lookForContacts();
        this.node.tick();

        assertThat(this.requestedTargets()).filteredOn(this.node.id()::equals).hasSize(1);
        assertThat(this.node.isReady()).isFalse();
    }

    // Lets the lookup towards target ask the contact at that very id, which then gets the request.
    private void answerAndWait(UInt128 target) {
        this.receive(PEER, new KadMessages.Answer(target, List.of()).encode());
        this.time.addAndGet(4 * SECOND);
        this.node.tick();
    }

    // A contact that greeted us: alive for an hour, so lookups take it.
    private void aliveContact(int ip) {
        UInt128 id = UInt128.random(this.random);
        this.node.addContacts(contents(new NodesDat.Contact(id, ip, PORT, 4662, 8, 0, NodesDat.UdpKey.NONE, true)));
        this.receive(ip, new KadMessages.Hello(new KadMessages.Self(id, 4662, 8), List.of()).encode(false));
    }

    private List<UInt128> requestedTargets() throws Exception {
        List<UInt128> targets = new ArrayList<>();
        for (Sent s : this.sent) {
            if (s.packet().opcode() == Opcodes.Kad.REQ.code()) {
                targets.add(KadMessages.Request.decode(s.packet()).target());
            }
        }
        return targets;
    }

    private void contactAt(UInt128 id) {
        this.node.addContacts(contents(new NodesDat.Contact(id, PEER, PORT, 4662, 8, 0, NodesDat.UdpKey.NONE, true)));
    }

    private void hello(int ip, int version) {
        this.receive(
                ip,
                new KadMessages.Hello(new KadMessages.Self(UInt128.random(this.random), 4662, version), List.of())
                        .encode(false));
    }

    private void receive(int ip, Packet packet) {
        this.receive(ip, packet, 0, 0);
    }

    private void receive(int ip, Packet packet, int receiverKey, int senderKey) {
        this.node.received(ip, PORT, packet, receiverKey != 0, receiverKey, senderKey);
    }

    private Packet last(int ip, Opcodes.Kad opcode) {
        Sent s = this.lastSent(ip, opcode);
        return s == null ? null : s.packet();
    }

    private Sent sentWith(int ip, Opcodes.Kad opcode) {
        Sent s = this.lastSent(ip, opcode);
        assertThat(s).as("%s sent to %s", opcode, Ipv4.format(ip)).isNotNull();
        return s;
    }

    private Sent lastSent(int ip, Opcodes.Kad opcode) {
        for (int i = this.sent.size() - 1; i >= 0; i--) {
            Sent s = this.sent.get(i);
            if (s.ip() == ip && s.packet().opcode() == opcode.code()) {
                return s;
            }
        }
        return null;
    }

    private int count(int ip, Opcodes.Kad opcode) {
        return (int) this.sent.stream()
                .filter(s -> s.ip() == ip && s.packet().opcode() == opcode.code())
                .count();
    }

    private Hash16 hash() {
        byte[] bytes = new byte[16];
        this.random.nextBytes(bytes);
        return Hash16.of(bytes);
    }

    private KadMessages.Contact contact(int ip) {
        return new KadMessages.Contact(UInt128.random(this.random), ip, PORT, 4662, 8);
    }

    private NodesDat.Contact record(int ip) {
        return new NodesDat.Contact(UInt128.random(this.random), ip, PORT, 4662, 8, 0, NodesDat.UdpKey.NONE, true);
    }

    private static NodesDat.Contents contents(NodesDat.Contact... contacts) {
        return new NodesDat.Contents(2, false, List.of(contacts));
    }

    private static KadMessages.Entry entry(UInt128 id, String name, long size) {
        return new KadMessages.Entry(
                id,
                List.of(
                        new Tag.StringTag(TagName.id(FileTag.FILENAME), name),
                        new Tag.IntTag(TagName.id(FileTag.FILESIZE), size)));
    }

    private static List<Tag> sourceTags(int type) {
        return List.of(
                new Tag.IntTag(TagName.id(FileTag.SOURCETYPE), type),
                new Tag.IntTag(TagName.id(FileTag.SOURCEPORT), 4662));
    }

    private static List<Tag> source(int type, int ip, int port, int encryption) {
        return List.of(
                new Tag.IntTag(TagName.id(FileTag.SOURCETYPE), type),
                new Tag.IntTag(TagName.id(FileTag.SOURCEIP), Integer.toUnsignedLong(ip)),
                new Tag.IntTag(TagName.id(FileTag.SOURCEPORT), port),
                new Tag.IntTag(TagName.id(FileTag.ENCRYPTION), encryption));
    }

    private static byte[] tree(SearchExpr expression) {
        PacketWriter out = new PacketWriter();
        SearchTreeCodec.write(out, expression, true);
        return out.toByteArray();
    }

    // An id whose top bits differ from ours: far beyond the tolerance of a publish.
    private static UInt128 farFrom(UInt128 id) {
        return new UInt128(~id.high(), id.low());
    }
}
