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
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.core.api.Hash16;
import org.njmule.core.persist.format.NodesDat;
import org.njmule.core.protocol.kad.KadMessages;
import org.njmule.core.protocol.packet.Opcodes;
import org.njmule.core.protocol.packet.Packet;
import org.njmule.core.protocol.tag.FileTag;
import org.njmule.core.protocol.tag.Tag;
import org.njmule.core.protocol.tag.TagName;
import org.njmule.core.protocol.tag.Tags;
import org.njmule.core.util.Ipv4;

/**
 * What keeps a firewalled Kad node reachable: the UDP firewall test, buddies and callbacks, with a manual clock, a
 * transport that only records and a host and buddy links that only record.
 */
class KadNodeLowIdTest {

    private static final int PORT = 4672;
    private static final int TCP = 4662;
    private static final long SECOND = 1_000_000_000L;
    private static final Hash16 USER = Hash16.parseHex("0102030405060708090a0b0c0d0e0f10");
    private static final List<Integer> CONTACTS =
            List.of(Ipv4.of(20, 1, 1, 1), Ipv4.of(21, 1, 1, 1), Ipv4.of(22, 1, 1, 1));
    private static final List<Integer> HELPERS =
            List.of(Ipv4.of(60, 1, 1, 1), Ipv4.of(61, 1, 1, 1), Ipv4.of(62, 1, 1, 1));

    /** A packet the node sent. */
    private record Sent(int ip, int port, Packet packet, Optional<UInt128> receiverId, int receiverKey) {}

    /** A helper the host was asked to connect to for our UDP test. */
    private record UdpCheck(int ip, int tcpPort, int internalPort, int externalPort, int senderKey) {}

    /** A node that offered to be our buddy, or asked us to be its own. */
    private record BuddyCall(int ip, int tcpPort, int udpPort, Hash16 userHash, Optional<UInt128> buddyId) {}

    /** A callback request relayed to the node we serve. */
    private record Relay(UInt128 buddyId, UInt128 file, int requesterIp, int requesterPort) {}

    @TempDir
    Path dir;

    private final SecureRandom random = new SecureRandom();
    private final AtomicLong time = new AtomicLong(1_000 * SECOND);
    private final Ticker ticker = new Ticker() {
        @Override
        public long read() {
            return KadNodeLowIdTest.this.time.get();
        }
    };
    private final List<Sent> sent = new ArrayList<>();
    private final List<UdpCheck> udpChecks = new ArrayList<>();
    private final List<BuddyCall> offers = new ArrayList<>();
    private final List<BuddyCall> asks = new ArrayList<>();
    private final List<Relay> relays = new ArrayList<>();
    private final List<KadNode.Source> found = new ArrayList<>();
    private KadNode.Buddies.State buddyState = KadNode.Buddies.State.NONE;
    private boolean acceptsBuddies = true;
    private KadIdentity identity;
    private KadNode node;
    private boolean published;

    @BeforeEach
    void create() throws IOException {
        this.identity = KadIdentity.loadOrCreate(this.dir, this.random);
        this.node = new KadNode(
                this.identity,
                (ip, port, packet, receiverId, receiverKey, senderKey) ->
                        this.sent.add(new Sent(ip, port, packet, receiverId, receiverKey)),
                new KadNode.Host() {
                    @Override
                    public void checkReachable(int ip, int port, Hash16 userHash, int connectOptions) {}

                    @Override
                    public void checkUdp(int ip, int tcpPort, int internalPort, int externalPort, int senderKey) {
                        KadNodeLowIdTest.this.udpChecks.add(
                                new UdpCheck(ip, tcpPort, internalPort, externalPort, senderKey));
                    }
                },
                new KadNode.Settings(TCP, USER, 3, ip -> false, ip -> false, PORT),
                this.dir,
                this.random,
                this.ticker);
        this.node.useBuddies(new Buddies());
    }

    @Test
    void helpersFoundByALookupAreAskedOnceOurPortIsKnownAndOneDatagramProvesUdpOpen() throws Exception {
        this.givenAConnectedNode();
        this.givenTheNodesSeeOurUdpPortAs(PORT);

        this.whenALookupFindsHelpers();

        this.thenHelpersAreAsked(2);
        this.thenTheHelpersAreAskedForOurPortsWithOurKey();

        this.whenAHelperSendsItsDatagram(this.udpChecks.getFirst().ip(), PORT, true);

        this.thenUdpIs(false, true);
    }

    @Test
    void twoHelpersWhoseDatagramsNeverCameMakeUdpFirewalled() throws Exception {
        this.givenAConnectedNode();
        this.givenTheNodesSeeOurUdpPortAs(PORT);
        this.givenALookupFoundHelpers();

        this.whenTheHelpersLinksEndWithoutADatagram();

        this.thenUdpIs(true, true);
        this.thenOurHelloSaysUdpFirewalled();
    }

    @Test
    void aDatagramFromANodeNotAskedOrWithoutOurKeyProvesNothing() throws Exception {
        this.givenAConnectedNode();
        this.givenTheNodesSeeOurUdpPortAs(PORT);
        this.givenALookupFoundHelpers();

        this.whenAHelperSendsItsDatagram(Ipv4.of(70, 1, 1, 1), PORT, true);
        this.whenAHelperSendsItsDatagram(this.udpChecks.getFirst().ip(), PORT, false);

        this.thenUdpIs(false, false);
    }

    @Test
    void aHelperThatCouldNotBeAskedIsReplacedByAnother() throws Exception {
        this.givenAConnectedNode();
        this.givenTheNodesSeeOurUdpPortAs(PORT);
        this.givenALookupFoundHelpers();

        this.whenAHelperCouldNotBeAsked(this.udpChecks.getFirst().ip());

        this.thenHelpersAreAsked(3);
        this.thenUdpIs(false, false);
    }

    @Test
    void aTcpFirewalledNodeWithoutAnOutcomeCountsAsUdpFirewalledAfterSixMinutes() throws Exception {
        this.givenAConnectedNode();

        this.whenMinutesPass(7);

        this.thenUdpIs(true, false);
    }

    @Test
    void aClientsUdpTestGetsOneDatagramPerPortAndOnlyOnceInAWhile() throws Exception {
        int client = Ipv4.of(80, 1, 1, 1);

        this.whenAClientAsksForItsUdpTest(client, 4673, 5000, 77);
        this.whenAClientAsksForItsUdpTest(client, 4673, 5000, 77);

        this.thenDatagramsForTheUdpTestWentTo(client, List.of(4673, 5000), 0, 77);
    }

    @Test
    void aClientOfOursIsToldItsUdpTestIsWorthless() throws Exception {
        this.givenAConnectedNode();
        int contact = CONTACTS.getFirst();

        this.whenAClientAsksForItsUdpTest(contact, 4673, 0, 77);

        this.thenDatagramsForTheUdpTestWentTo(contact, List.of(4673), 1, 77);
    }

    @Test
    void aNodeFirewalledOnBothPortsAsksTheNodesNearItsComplementToBeItsBuddy() throws Exception {
        this.givenAConnectedNode();
        int near = Ipv4.of(30, 1, 1, 1);
        this.givenAContactAt(near, this.complement());
        this.givenUdpIsFirewalled();

        this.whenTheBuddySearchAsks(near);

        this.thenABuddyRequestWentTo(near);

        this.whenTheNodeOffersToBeOurBuddy(near, this.complement());

        this.thenTheOfferIsTakenFrom(near);
    }

    @Test
    void anOfferForAnotherIdOrNotAskedForIsLeftOut() throws Exception {
        this.givenAConnectedNode();
        int near = Ipv4.of(30, 1, 1, 1);
        this.givenAContactAt(near, this.complement());
        this.givenUdpIsFirewalled();
        this.givenTheBuddySearchAsked(near);

        this.whenTheNodeOffersToBeOurBuddy(near, UInt128.random(this.random));
        this.whenTheNodeOffersToBeOurBuddy(Ipv4.of(31, 1, 1, 1), this.complement());

        this.thenNoOfferIsTaken();
    }

    @Test
    void anOpenNodeOffersToBeTheBuddyOfAFirewalledNode() throws Exception {
        this.givenAnOpenNodeOnBothPorts();
        int firewalled = Ipv4.of(40, 1, 1, 1);
        UInt128 buddyId = UInt128.random(this.random);

        this.whenAFirewalledNodeAsksUsToBeItsBuddy(firewalled, buddyId, 55);

        this.thenWeAreAsked(firewalled, buddyId);
        this.thenOurOfferWentTo(firewalled, buddyId, OptionalInt.of(3));
    }

    @Test
    void aFirewalledNodeOrOneServingAlreadyIsNoBuddy() throws Exception {
        this.givenAConnectedNode();
        int firewalled = Ipv4.of(40, 1, 1, 1);

        this.whenAFirewalledNodeAsksUsToBeItsBuddy(firewalled, UInt128.random(this.random), 55);

        this.thenNoOfferWentTo(firewalled);
    }

    @Test
    void aCallbackRequestIsRelayedToTheNodeWeServe() throws Exception {
        this.givenWeServeABuddy();
        int requester = Ipv4.of(50, 1, 1, 1);
        UInt128 buddyId = UInt128.random(this.random);
        UInt128 file = UInt128.random(this.random);

        this.whenACallbackRequestComes(requester, buddyId, file, 4711);

        this.thenTheCallbackIsRelayed(new Relay(buddyId, file, requester, 4711));
    }

    @Test
    void theBuddyOfAFirewalledSourceIsAskedPlainForACallback() throws Exception {
        int buddyIp = Ipv4.of(45, 1, 1, 1);
        UInt128 buddyId = UInt128.random(this.random);
        Hash16 file = Hash16.parseHex("aa".repeat(16));

        this.whenTheBuddyOfASourceIsAskedForACallback(new KadNode.Buddy(buddyIp, 4673, buddyId), file);

        this.thenAPlainCallbackRequestWentTo(buddyIp, 4673, buddyId, file);
    }

    @Test
    void firewalledSourcesAreFoundWithTheirBuddyOrTheirDirectCallback() throws Exception {
        UInt128 buddyId = UInt128.random(this.random);
        UInt128 viaBuddy = UInt128.random(this.random);
        UInt128 direct = UInt128.random(this.random);

        this.whenASearchFinds(List.of(
                new KadMessages.Entry(viaBuddy, buddySource(5, Ipv4.of(91, 1, 1, 1), Ipv4.of(92, 1, 1, 1), buddyId)),
                new KadMessages.Entry(direct, directCallbackSource(0x08 | 0x01)),
                new KadMessages.Entry(UInt128.random(this.random), directCallbackSource(0x01)),
                new KadMessages.Entry(
                        UInt128.random(this.random), List.of(new Tag.IntTag(TagName.id(FileTag.SOURCETYPE), 3)))));

        this.thenTheSourcesFoundAre(
                new KadNode.Source(
                        viaBuddy.toHash16(),
                        Ipv4.of(91, 1, 1, 1),
                        TCP,
                        0,
                        PORT,
                        Optional.of(new KadNode.Buddy(Ipv4.of(92, 1, 1, 1), 4673, buddyId)),
                        false),
                new KadNode.Source(direct.toHash16(), Ipv4.of(93, 1, 1, 1), TCP, 0x09, PORT, Optional.empty(), true));
    }

    @Test
    void aFirewalledNodeWithABuddyIsPublishedAsASourceReachedThroughIt() throws Exception {
        this.givenAConnectedNode();
        this.givenUdpIsFirewalled();
        this.givenOurBuddyIsAt(Ipv4.of(44, 1, 1, 1), 4673);

        this.whenWeArePublishedAsASource(1000);

        this.thenTheSourceTypeIs(3);
        this.thenTheSourceNamesOurBuddy(Ipv4.of(44, 1, 1, 1), 4673);
    }

    @Test
    void aFirewalledNodeWithAnOpenUdpPortIsPublishedForDirectCallbacks() throws Exception {
        this.givenAConnectedNode();
        this.givenTheNodesSeeOurUdpPortAs(PORT);
        this.givenALookupFoundHelpers();
        this.whenAHelperSendsItsDatagram(this.udpChecks.getFirst().ip(), PORT, true);

        this.whenWeArePublishedAsASource(1000);

        this.thenTheSourceTypeIs(6);
        this.thenTheConnectOptionsAre(0x08 | 3);
        this.thenTheNodeTakesDirectCallbacks();
    }

    @Test
    void aFirewalledNodeNobodyCanReachIsNotPublishedAsASource() throws Exception {
        this.givenAConnectedNode();
        this.givenUdpIsFirewalled();

        this.whenWeArePublishedAsASource(1000);

        this.thenNoSourcePublishWent();
        this.thenTheNodeIsNotReachable();
    }

    // Steps.

    private void givenAConnectedNode() {
        for (int ip : CONTACTS) {
            this.givenAContactAt(ip, UInt128.random(this.random));
        }
    }

    private void givenAContactAt(int ip, UInt128 id) {
        this.node.addContacts(new NodesDat.Contents(
                2, false, List.of(new NodesDat.Contact(id, ip, PORT, TCP, 8, 0, NodesDat.UdpKey.NONE, true))));
        this.node.received(
                ip,
                PORT,
                new KadMessages.Hello(new KadMessages.Self(id, TCP, 8), List.of()).encode(false),
                false,
                0,
                0);
    }

    // Each node asked says which port it sees; two that agree decide it.
    private void givenTheNodesSeeOurUdpPortAs(int port) throws Exception {
        List<Integer> answered = new ArrayList<>();
        for (int round = 0; round < 40 && answered.size() < 2; round++) {
            this.tick(15);
            Sent ping = this.lastSent(Opcodes.Kad.PING);
            if (ping != null && !answered.contains(ping.ip())) {
                answered.add(ping.ip());
                this.node.received(ping.ip(), PORT, KadMessages.pong(port), true, 0, 0);
            }
        }
        assertThat(answered).as("nodes asked for our port").hasSize(2);
    }

    private void givenALookupFoundHelpers() throws Exception {
        this.whenALookupFindsHelpers();
        this.thenHelpersAreAsked(2);
    }

    // A TCP-firewalled node whose test brings no outcome: right when it times out.
    private void givenUdpIsFirewalled() {
        for (int s = 0; s < 8 * 60 && !this.node.isFirewalledUdp(); s++) {
            this.tick(1);
        }
        this.thenUdpIs(true, false);
    }

    private void givenAnOpenNodeOnBothPorts() throws Exception {
        this.givenAConnectedNode();
        for (int ip : new int[] {Ipv4.of(26, 1, 1, 1), Ipv4.of(27, 1, 1, 1)}) {
            this.givenAContactAt(ip, UInt128.random(this.random));
            this.node.reachableConfirmed(ip);
        }
        this.givenTheNodesSeeOurUdpPortAs(PORT);
        this.givenALookupFoundHelpers();
        this.whenAHelperSendsItsDatagram(this.udpChecks.getFirst().ip(), PORT, true);
        assertThat(this.node.isFirewalled()).isFalse();
        this.thenUdpIs(false, true);
    }

    private void givenTheBuddySearchAsked(int near) throws Exception {
        this.whenTheBuddySearchAsks(near);
        this.thenABuddyRequestWentTo(near);
    }

    private void givenWeServeABuddy() {
        this.givenAConnectedNode();
        this.buddyState = KadNode.Buddies.State.SERVING;
    }

    private void givenOurBuddyIsAt(int ip, int port) {
        this.buddyState = KadNode.Buddies.State.CONNECTED;
        this.offers.add(new BuddyCall(ip, TCP, port, USER, Optional.empty()));
    }

    private void whenALookupFindsHelpers() throws Exception {
        this.tick(1);
        Optional<UInt128> running = this.node.udpHelpersTarget();
        assertThat(running).as("a lookup for helpers").isPresent();
        UInt128 target = running.get();
        Sent request = null;
        for (Sent s : this.sent) {
            if (s.packet().opcode() == Opcodes.Kad.REQ.code()
                    && KadMessages.Request.decode(s.packet()).target().equals(target)) {
                request = s;
            }
        }
        assertThat(request).as("a node asked for helpers").isNotNull();
        List<KadMessages.Contact> helpers = new ArrayList<>();
        for (int ip : HELPERS) {
            helpers.add(new KadMessages.Contact(UInt128.random(this.random), ip, PORT, TCP, 8));
        }
        this.node.received(request.ip(), PORT, new KadMessages.Answer(target, helpers).encode(), false, 0, 0);
    }

    private void whenAHelperSendsItsDatagram(int ip, int port, boolean withOurKey) {
        int key = withOurKey ? this.identity.verifyKey(ip) : 0;
        this.node.received(ip, PORT, new KadMessages.FirewallUdp(0, port).encode(), true, key, 0);
    }

    private void whenTheHelpersLinksEndWithoutADatagram() {
        for (UdpCheck check : List.copyOf(this.udpChecks)) {
            this.node.udpCheckEnded(check.ip(), true);
        }
    }

    private void whenAHelperCouldNotBeAsked(int ip) {
        this.node.udpCheckEnded(ip, false);
    }

    private void whenMinutesPass(int minutes) {
        for (int s = 0; s < minutes * 60; s += 10) {
            this.tick(10);
        }
    }

    private void whenAClientAsksForItsUdpTest(int ip, int internal, int external, int key) {
        this.node.udpCheckRequested(ip, internal, external, key, false);
    }

    private void whenTheBuddySearchAsks(int near) throws Exception {
        this.tick(1);
        Sent request = null;
        for (Sent s : this.sent) {
            if (s.ip() == near
                    && s.packet().opcode() == Opcodes.Kad.REQ.code()
                    && KadMessages.Request.decode(s.packet()).target().equals(this.complement())) {
                request = s;
            }
        }
        assertThat(request)
                .as("the buddy search asks the node near our complement")
                .isNotNull();
        this.node.received(near, PORT, new KadMessages.Answer(this.complement(), List.of()).encode(), false, 0, 0);
        this.tick(4);
    }

    private void whenTheNodeOffersToBeOurBuddy(int ip, UInt128 buddyId) {
        this.node.received(
                ip,
                PORT,
                new KadMessages.BuddyOffer(
                                buddyId, UInt128.of(Hash16.parseHex("bb".repeat(16))), 4663, OptionalInt.of(1))
                        .encode(),
                false,
                0,
                0);
    }

    private void whenAFirewalledNodeAsksUsToBeItsBuddy(int ip, UInt128 buddyId, int senderKey) {
        this.node.received(
                ip,
                PORT,
                new KadMessages.FindBuddy(buddyId, UInt128.of(Hash16.parseHex("cc".repeat(16))), 4664).encode(),
                false,
                0,
                senderKey);
    }

    private void whenACallbackRequestComes(int ip, UInt128 buddyId, UInt128 file, int port) {
        this.node.received(ip, PORT, new KadMessages.CallbackRequest(buddyId, file, port).encode(), false, 0, 0);
    }

    private void whenTheBuddyOfASourceIsAskedForACallback(KadNode.Buddy buddy, Hash16 file) {
        assertThat(this.node.requestCallback(buddy, file)).isTrue();
    }

    private void whenASearchFinds(List<KadMessages.Entry> entries) {
        this.found.addAll(KadNode.sourceResults(entries));
    }

    private void whenWeArePublishedAsASource(long size) throws Exception {
        Hash16 hash = Hash16.parseHex("dd".repeat(16));
        UInt128 target = UInt128.of(hash);
        int holder = Ipv4.of(35, 1, 1, 1);
        this.givenAContactAt(holder, target);
        this.published = this.node.publishSource(hash, size);
        this.node.received(holder, PORT, new KadMessages.Answer(target, List.of()).encode(), false, 0, 0);
        this.tick(4);
    }

    private void thenHelpersAreAsked(int count) {
        assertThat(this.udpChecks).hasSize(count);
        assertThat(this.udpChecks).extracting(UdpCheck::ip).allMatch(HELPERS::contains);
        assertThat(this.udpChecks).extracting(UdpCheck::ip).doesNotHaveDuplicates();
    }

    private void thenTheHelpersAreAskedForOurPortsWithOurKey() {
        for (UdpCheck check : this.udpChecks) {
            assertThat(check.tcpPort()).isEqualTo(TCP);
            assertThat(check.internalPort()).isEqualTo(PORT);
            assertThat(check.externalPort()).isEqualTo(PORT);
            assertThat(check.senderKey()).isEqualTo(this.identity.verifyKey(check.ip()));
        }
        assertThat(this.sent).as("helpers never hear from us over UDP").noneMatch(s -> HELPERS.contains(s.ip()));
    }

    private void thenUdpIs(boolean firewalled, boolean verified) {
        assertThat(this.node.isFirewalledUdp()).as("UDP firewalled").isEqualTo(firewalled);
        assertThat(this.node.isUdpVerified()).as("UDP tested").isEqualTo(verified);
    }

    private void thenOurHelloSaysUdpFirewalled() throws Exception {
        int ip = Ipv4.of(28, 1, 1, 1);
        this.givenAContactAt(ip, UInt128.random(this.random));
        KadMessages.Hello hello =
                KadMessages.Hello.decode(this.lastSent(Opcodes.Kad.HELLO_RES).packet());
        assertThat(Tags.intValue(hello.tags(), FileTag.KADMISCOPTIONS).orElse(0) & 0x03)
                .as("UDP and TCP firewalled")
                .isEqualTo(0x03);
    }

    private void thenDatagramsForTheUdpTestWentTo(int ip, List<Integer> ports, int error, int key) throws Exception {
        List<Sent> datagrams = this.sent.stream()
                .filter(s -> s.ip() == ip && s.packet().opcode() == Opcodes.Kad.FIREWALLUDP.code())
                .toList();
        assertThat(datagrams).extracting(Sent::port).containsExactlyElementsOf(ports);
        for (Sent s : datagrams) {
            KadMessages.FirewallUdp datagram = KadMessages.FirewallUdp.decode(s.packet());
            assertThat(datagram.port()).isEqualTo(s.port());
            assertThat(datagram.error()).isEqualTo(error);
            assertThat(s.receiverKey()).as("obfuscated with the key it gave").isEqualTo(key);
        }
    }

    private void thenABuddyRequestWentTo(int ip) throws Exception {
        Sent request = this.lastSent(Opcodes.Kad.FINDBUDDY_REQ);
        assertThat(request).isNotNull();
        assertThat(request.ip()).isEqualTo(ip);
        KadMessages.FindBuddy findBuddy = KadMessages.FindBuddy.decode(request.packet());
        assertThat(findBuddy.buddyId()).isEqualTo(this.complement());
        assertThat(findBuddy.userHash()).isEqualTo(UInt128.of(USER));
        assertThat(findBuddy.tcpPort()).isEqualTo(TCP);
    }

    private void thenTheOfferIsTakenFrom(int ip) {
        assertThat(this.offers)
                .containsExactly(new BuddyCall(ip, 4663, PORT, Hash16.parseHex("bb".repeat(16)), Optional.empty()));
    }

    private void thenNoOfferIsTaken() {
        assertThat(this.offers).isEmpty();
    }

    private void thenWeAreAsked(int ip, UInt128 buddyId) {
        assertThat(this.asks)
                .containsExactly(new BuddyCall(ip, 4664, PORT, Hash16.parseHex("cc".repeat(16)), Optional.of(buddyId)));
    }

    private void thenOurOfferWentTo(int ip, UInt128 buddyId, OptionalInt options) throws Exception {
        Sent answer = this.lastSent(Opcodes.Kad.FINDBUDDY_RES);
        assertThat(answer).isNotNull();
        assertThat(answer.ip()).isEqualTo(ip);
        KadMessages.BuddyOffer offer = KadMessages.BuddyOffer.decode(answer.packet());
        assertThat(offer).isEqualTo(new KadMessages.BuddyOffer(buddyId, UInt128.of(USER), TCP, options));
    }

    private void thenNoOfferWentTo(int ip) {
        assertThat(this.asks).isEmpty();
        assertThat(this.sent).noneMatch(s -> s.ip() == ip && s.packet().opcode() == Opcodes.Kad.FINDBUDDY_RES.code());
    }

    private void thenTheCallbackIsRelayed(Relay relay) {
        assertThat(this.relays).containsExactly(relay);
    }

    private void thenAPlainCallbackRequestWentTo(int ip, int port, UInt128 buddyId, Hash16 file) throws Exception {
        Sent request = this.lastSent(Opcodes.Kad.CALLBACK_REQ);
        assertThat(request).isNotNull();
        assertThat(request.ip()).isEqualTo(ip);
        assertThat(request.port()).isEqualTo(port);
        assertThat(request.receiverId()).isEmpty();
        assertThat(request.receiverKey()).isZero();
        assertThat(KadMessages.CallbackRequest.decode(request.packet()))
                .isEqualTo(new KadMessages.CallbackRequest(buddyId, UInt128.of(file), TCP));
    }

    private void thenTheSourcesFoundAre(KadNode.Source... sources) {
        assertThat(this.found).containsExactly(sources);
    }

    private void thenTheSourceTypeIs(int type) throws Exception {
        assertThat(this.published).as("the publish started").isTrue();
        assertThat(Tags.intValue(this.sourcePublish().tags(), FileTag.SOURCETYPE))
                .hasValue(type);
    }

    private void thenTheSourceNamesOurBuddy(int ip, int port) throws Exception {
        List<Tag> tags = this.sourcePublish().tags();
        assertThat(Tags.intValue(tags, FileTag.SERVERIP)).hasValue(Ipv4.toEd2k(ip));
        assertThat(Tags.intValue(tags, FileTag.SERVERPORT)).hasValue(port);
        assertThat(Tags.stringValue(tags, FileTag.BUDDYHASH).flatMap(KadNode::parseBuddyHash))
                .as("the hash names our complement")
                .contains(this.complement());
        assertThat(this.node.isReachable()).isTrue();
    }

    private void thenTheConnectOptionsAre(int options) throws Exception {
        assertThat(Tags.intValue(this.sourcePublish().tags(), FileTag.ENCRYPTION))
                .hasValue(options);
    }

    private void thenTheNodeTakesDirectCallbacks() {
        assertThat(this.node.supportsDirectCallback()).isTrue();
        assertThat(this.node.isReachable()).isTrue();
    }

    private void thenNoSourcePublishWent() {
        assertThat(this.lastSent(Opcodes.Kad.PUBLISH_SOURCE_REQ)).isNull();
    }

    private void thenTheNodeIsNotReachable() {
        assertThat(this.node.isReachable()).isFalse();
    }

    // Helpers.

    private UInt128 complement() {
        return this.identity.id().xor(new UInt128(-1L, -1L));
    }

    private KadMessages.SourcePublish sourcePublish() throws Exception {
        Sent publish = this.lastSent(Opcodes.Kad.PUBLISH_SOURCE_REQ);
        assertThat(publish).as("a source publish").isNotNull();
        return KadMessages.SourcePublish.decode(publish.packet());
    }

    private void tick(int seconds) {
        for (int s = 0; s < seconds; s++) {
            this.time.addAndGet(SECOND);
            this.node.tick();
        }
    }

    private Sent lastSent(Opcodes.Kad opcode) {
        for (int i = this.sent.size() - 1; i >= 0; i--) {
            if (this.sent.get(i).packet().opcode() == opcode.code()) {
                return this.sent.get(i);
            }
        }
        return null;
    }

    private static List<Tag> buddySource(int type, int ip, int buddyIp, UInt128 buddyId) {
        return List.of(
                new Tag.IntTag(TagName.id(FileTag.SOURCETYPE), type),
                new Tag.IntTag(TagName.id(FileTag.SOURCEIP), Integer.toUnsignedLong(ip)),
                new Tag.IntTag(TagName.id(FileTag.SOURCEPORT), TCP),
                new Tag.IntTag(TagName.id(FileTag.SOURCEUPORT), PORT),
                new Tag.IntTag(TagName.id(FileTag.SERVERIP), Ipv4.toEd2k(buddyIp)),
                new Tag.IntTag(TagName.id(FileTag.SERVERPORT), 4673),
                new Tag.StringTag(
                        TagName.id(FileTag.BUDDYHASH), KadNode.buddyHash(buddyId.xor(new UInt128(-1L, -1L)))));
    }

    private static List<Tag> directCallbackSource(int options) {
        return List.of(
                new Tag.IntTag(TagName.id(FileTag.SOURCETYPE), 6),
                new Tag.IntTag(TagName.id(FileTag.SOURCEIP), Integer.toUnsignedLong(Ipv4.of(93, 1, 1, 1))),
                new Tag.IntTag(TagName.id(FileTag.SOURCEPORT), TCP),
                new Tag.IntTag(TagName.id(FileTag.SOURCEUPORT), PORT),
                new Tag.IntTag(TagName.id(FileTag.ENCRYPTION), options));
    }

    /** Buddy links that only record what the node asks of them. */
    private final class Buddies implements KadNode.Buddies {
        @Override
        public State state() {
            return KadNodeLowIdTest.this.buddyState;
        }

        @Override
        public int buddyIp() {
            return KadNodeLowIdTest.this.offers.isEmpty()
                    ? 0
                    : KadNodeLowIdTest.this.offers.getFirst().ip();
        }

        @Override
        public int buddyUdpPort() {
            return KadNodeLowIdTest.this.offers.isEmpty()
                    ? 0
                    : KadNodeLowIdTest.this.offers.getFirst().udpPort();
        }

        @Override
        public void offered(int ip, int tcpPort, int udpPort, Hash16 userHash, int connectOptions) {
            KadNodeLowIdTest.this.offers.add(new BuddyCall(ip, tcpPort, udpPort, userHash, Optional.empty()));
        }

        @Override
        public boolean asked(int ip, int tcpPort, int udpPort, Hash16 userHash, UInt128 buddyId) {
            KadNodeLowIdTest.this.asks.add(new BuddyCall(ip, tcpPort, udpPort, userHash, Optional.of(buddyId)));
            return KadNodeLowIdTest.this.acceptsBuddies;
        }

        @Override
        public boolean relay(UInt128 buddyId, UInt128 file, int requesterIp, int requesterPort) {
            KadNodeLowIdTest.this.relays.add(new Relay(buddyId, file, requesterIp, requesterPort));
            return true;
        }
    }
}
