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
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.core.api.Hash16;
import org.njmule.core.persist.format.NodesDat;
import org.njmule.core.protocol.kad.KadMessages;
import org.njmule.core.protocol.packet.Opcodes;
import org.njmule.core.protocol.packet.Packet;
import org.njmule.core.util.Ipv4;

/**
 * A bootstrap-only nodes.dat: its contacts are asked one at a time for contacts to start from while the node is not
 * connected, and never taken as contacts themselves. Shipped contacts are asked the same way from the start, next to
 * the saved ones, and again after a pause when they ran out without a connection. Clients met over eD2K that run Kad
 * are asked too while the node is not connected.
 */
class KadNodeBootstrapTest {

    private static final int PORT = 4672;
    private static final long SECOND = 1_000_000_000L;
    private static final int BLOCKED = Ipv4.of(99, 1, 1, 1);
    private static final int REFILL_SECONDS = (int) KadNode.BOOTSTRAP_REFILL_AFTER.toSeconds();
    private static final int CLIENT_GAP_SECONDS = (int) KadNode.CLIENT_BOOTSTRAP_EVERY.toSeconds();
    private static final int CLIENT = Ipv4.of(60, 1, 1, 1);
    private static final int OTHER_CLIENT = Ipv4.of(61, 1, 1, 1);

    /** A packet the node sent. */
    private record Sent(int ip, int port, Packet packet, Optional<UInt128> receiverId) {}

    @TempDir
    Path dir;

    private final SecureRandom random = new SecureRandom();
    private final AtomicLong time = new AtomicLong(1_000 * SECOND);
    private final Ticker ticker = new Ticker() {
        @Override
        public long read() {
            return KadNodeBootstrapTest.this.time.get();
        }
    };
    private final List<Sent> sent = new ArrayList<>();
    private final List<NodesDat.Contact> good = new ArrayList<>();
    private final List<NodesDat.Contact> bad = new ArrayList<>();
    private final List<NodesDat.Contact> saved = new ArrayList<>();
    private final List<NodesDat.Contact> shipped = new ArrayList<>();
    private int shippedReads;
    private KadNode node;
    private boolean loaded;

    @BeforeEach
    void create() throws IOException {
        this.node = new KadNode(
                KadIdentity.loadOrCreate(this.dir, this.random),
                (ip, port, packet, receiverId, receiverKey, senderKey) ->
                        this.sent.add(new Sent(ip, port, packet, receiverId)),
                (ip, port, userHash, options) -> {},
                new KadNode.Settings(4662, Hash16.ZERO, 0, ip -> false, ip -> ip == BLOCKED, PORT),
                this.dir,
                this.random,
                this.ticker);
    }

    @Test
    void shouldAskTheBootstrapContactsOneByOne() {
        givenABootstrapListOfGoodAndBadContacts(60);

        whenSecondsPass(10);

        thenBootstrapRequestsWent(5);
    }

    @Test
    void shouldAskOnlyTheFiftyClosestUsableContacts() {
        givenABootstrapListOfGoodAndBadContacts(60);
        givenSecondsPass(10);

        whenSecondsPass(200);

        thenBootstrapRequestsWentToTheClosestGoodContacts(KadNode.BOOTSTRAP_CONTACTS);
        thenNoBadContactWasAsked();
        thenTheNodeHasContacts(0);
    }

    @Test
    void shouldObfuscateTheRequestForANodeThatTakesIt() {
        givenABootstrapListOfGoodAndBadContacts(1);

        whenSecondsPass(1);

        thenTheRequestIsObfuscatedFor(this.good.getFirst().id());
    }

    @Test
    void shouldStopAskingOnceConnectedAndNeverTakeTheNodeThatAnswered() {
        givenABootstrapListOfGoodAndBadContacts(10);
        givenSecondsPass(1);
        givenTheAskedNodeAnswersWithContacts(3);

        whenSecondsPass(60);

        thenBootstrapRequestsWent(1);
        thenTheNodeHasContacts(3);
        thenTheAnsweringNodeIsNoContact();
    }

    @Test
    void shouldTellThatThereWasNoNodesDatToLoad() {
        whenTheNodesAreLoaded();

        thenThereWasNoNodesDat();
    }

    @Test
    void shouldTellThatThereWasANodesDatToLoad() throws IOException {
        givenANodesDat();

        whenTheNodesAreLoaded();

        thenThereWasANodesDat();
    }

    @Test
    void shouldAskTheShippedContactsAtOnceNextToTheSavedOnes() {
        givenSavedContacts(5);
        givenShippedContacts(10);

        whenSecondsPass(1);

        thenBootstrapRequestsWentToShippedContacts(1);
    }

    @Test
    void shouldStopAskingTheShippedContactsOnceASavedContactAnswers() {
        givenSavedContacts(5);
        givenShippedContacts(10);
        givenSecondsPass(1);
        givenASavedContactPings();

        whenSecondsPass(120);

        thenBootstrapRequestsWentToShippedContacts(1);
        thenTheShippedContactsWereRead(1);
    }

    @Test
    void shouldNotAskTheShippedContactsAgainBeforeThePause() {
        givenShippedContacts(10);

        whenSecondsPass(REFILL_SECONDS);

        thenBootstrapRequestsWentToShippedContacts(10);
        thenTheShippedContactsWereRead(1);
    }

    @Test
    void shouldAskTheShippedContactsAgainWhenTheyRanOutWithoutAConnection() {
        givenShippedContacts(10);

        whenSecondsPass(REFILL_SECONDS + 1);

        thenBootstrapRequestsWentToShippedContacts(11);
        thenTheShippedContactsWereRead(2);
    }

    @Test
    void shouldAskAClientRunningKadForContactsWhileNotConnected() {
        whenAClientSaysHello(CLIENT, PORT, 9);

        thenBootstrapRequestsWentTo(CLIENT);
    }

    @Test
    void shouldTakeAnAnsweringClientAsAContactWhileShippedContactsWait() {
        givenShippedContacts(10);
        givenAClientSaidHello(CLIENT, PORT, 9);

        whenTheClientAnswersWithContacts(CLIENT, 3);

        thenTheNodeHasContacts(4);
        thenTheContactsInclude(CLIENT);
    }

    @Test
    void shouldNotAskAnotherClientBeforeTheGap() {
        givenAClientSaidHello(CLIENT, PORT, 9);
        givenSecondsPass(CLIENT_GAP_SECONDS - 1);

        whenAClientSaysHello(OTHER_CLIENT, PORT, 9);

        thenBootstrapRequestsWentTo(CLIENT);
    }

    @Test
    void shouldAskAnotherClientOnceTheGapPassed() {
        givenAClientSaidHello(CLIENT, PORT, 9);
        givenSecondsPass(CLIENT_GAP_SECONDS);

        whenAClientSaysHello(OTHER_CLIENT, PORT, 9);

        thenBootstrapRequestsWentTo(CLIENT, OTHER_CLIENT);
    }

    @Test
    void shouldNotAskAClientOnceConnected() {
        givenSavedContacts(5);
        givenASavedContactPings();

        whenAClientSaysHello(CLIENT, PORT, 9);

        thenBootstrapRequestsWent(0);
    }

    @Test
    void shouldNotAskAClientWithoutAKadPort() {
        whenAClientSaysHello(CLIENT, 0, 9);

        thenBootstrapRequestsWent(0);
    }

    @Test
    void shouldNotAskAClientRunningTheFirstKad() {
        whenAClientSaysHello(CLIENT, PORT, 1);

        thenBootstrapRequestsWent(0);
    }

    private void givenAClientSaidHello(int ip, int kadPort, int kadVersion) {
        whenAClientSaysHello(ip, kadPort, kadVersion);
    }

    private void whenAClientSaysHello(int ip, int kadPort, int kadVersion) {
        this.node.bootstrapVia(ip, kadPort, kadVersion);
    }

    private void whenTheClientAnswersWithContacts(int ip, int count) {
        List<KadMessages.Contact> contacts = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            contacts.add(new KadMessages.Contact(UInt128.random(this.random), Ipv4.of(70 + i, 1, 1, 1), PORT, 4662, 8));
        }
        KadMessages.Self sender = new KadMessages.Self(UInt128.random(this.random), 4662, 9);
        this.node.received(ip, PORT, new KadMessages.BootstrapAnswer(sender, contacts).encode(), false, 0, 0);
    }

    private void thenTheContactsInclude(int ip) {
        assertThat(this.node.contactInfos()).extracting(c -> c.address()).contains(Ipv4.format(ip) + ":" + PORT);
    }

    private void thenBootstrapRequestsWentTo(Integer... ips) {
        assertThat(bootstrapRequests()).extracting(Sent::ip).containsExactly(ips);
    }

    private void givenANodesDat() throws IOException {
        this.good.add(record(UInt128.random(this.random), Ipv4.of(20, 1, 1, 1), PORT, 8));
        Files.write(this.dir.resolve(KadNode.NODES_FILE), NodesDat.write(this.good));
    }

    private void whenTheNodesAreLoaded() {
        this.loaded = this.node.loadNodes();
    }

    private void thenThereWasNoNodesDat() {
        assertThat(this.loaded).isFalse();
    }

    private void thenThereWasANodesDat() {
        assertThat(this.loaded).isTrue();
    }

    private void givenABootstrapListOfGoodAndBadContacts(int goodOnes) {
        for (int i = 0; i < goodOnes; i++) {
            this.good.add(record(UInt128.random(this.random), Ipv4.of(20 + i, 1, 1, 1), PORT, 8));
        }
        this.bad.add(record(UInt128.random(this.random), Ipv4.of(90, 1, 1, 1), 53, 5));
        this.bad.add(record(UInt128.random(this.random), Ipv4.of(90, 1, 1, 2), 0, 8));
        this.bad.add(record(UInt128.random(this.random), Ipv4.of(90, 1, 1, 3), PORT, 1));
        this.bad.add(record(UInt128.random(this.random), Ipv4.of(192, 168, 1, 3), PORT, 8));
        this.bad.add(record(UInt128.random(this.random), BLOCKED, PORT, 8));
        List<NodesDat.Contact> all = new ArrayList<>(this.bad);
        all.addAll(this.good);
        this.node.addContacts(new NodesDat.Contents(3, true, all));
    }

    private void givenSavedContacts(int count) {
        for (int i = 0; i < count; i++) {
            this.saved.add(record(UInt128.random(this.random), Ipv4.of(30 + i, 1, 1, 1), PORT, 8));
        }
        this.node.addContacts(new NodesDat.Contents(2, false, this.saved));
    }

    private void givenShippedContacts(int count) {
        for (int i = 0; i < count; i++) {
            this.shipped.add(record(UInt128.random(this.random), Ipv4.of(40 + i, 1, 1, 1), PORT, 8));
        }
        this.node.bootstrapFrom(() -> {
            this.shippedReads++;
            return new NodesDat.Contents(3, true, this.shipped);
        });
    }

    private void givenASavedContactPings() {
        this.node.received(this.saved.getFirst().ip(), PORT, KadMessages.ping(), false, 0, 0);
    }

    private void givenSecondsPass(int seconds) {
        whenSecondsPass(seconds);
    }

    private void whenSecondsPass(int seconds) {
        for (int s = 0; s < seconds; s++) {
            this.node.tick();
            this.time.addAndGet(SECOND);
        }
    }

    private void givenTheAskedNodeAnswersWithContacts(int count) {
        Sent request = bootstrapRequests().getFirst();
        List<KadMessages.Contact> contacts = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            contacts.add(new KadMessages.Contact(UInt128.random(this.random), Ipv4.of(70 + i, 1, 1, 1), PORT, 4662, 8));
        }
        UInt128 id = this.good.stream()
                .filter(c -> c.ip() == request.ip())
                .findFirst()
                .orElseThrow()
                .id();
        KadMessages.Self sender = new KadMessages.Self(id, 4662, 8);
        this.node.received(
                request.ip(), request.port(), new KadMessages.BootstrapAnswer(sender, contacts).encode(), true, 0, 0);
    }

    private void thenBootstrapRequestsWent(int count) {
        assertThat(bootstrapRequests()).hasSize(count);
    }

    private void thenBootstrapRequestsWentToShippedContacts(int count) {
        List<Integer> shippedIps =
                this.shipped.stream().map(NodesDat.Contact::ip).toList();
        assertThat(bootstrapRequests()).extracting(Sent::ip).hasSize(count).isSubsetOf(shippedIps);
    }

    private void thenTheShippedContactsWereRead(int times) {
        assertThat(this.shippedReads).isEqualTo(times);
    }

    private void thenBootstrapRequestsWentToTheClosestGoodContacts(int count) {
        UInt128 own = this.node.id();
        List<Integer> closest = this.good.stream()
                .sorted(Comparator.comparing(c -> c.id().xor(own)))
                .limit(count)
                .map(NodesDat.Contact::ip)
                .toList();
        assertThat(bootstrapRequests()).extracting(Sent::ip).containsExactlyElementsOf(closest);
    }

    private void thenNoBadContactWasAsked() {
        List<Integer> badIps = this.bad.stream().map(NodesDat.Contact::ip).toList();
        assertThat(this.sent).extracting(Sent::ip).doesNotContainAnyElementsOf(badIps);
    }

    private void thenTheRequestIsObfuscatedFor(UInt128 id) {
        assertThat(bootstrapRequests())
                .singleElement()
                .satisfies(s -> assertThat(s.receiverId()).contains(id));
    }

    private void thenTheNodeHasContacts(int count) {
        assertThat(this.node.contacts()).isEqualTo(count);
    }

    private void thenTheAnsweringNodeIsNoContact() {
        int answering = bootstrapRequests().getFirst().ip();
        assertThat(this.node.contactInfos())
                .extracting(c -> c.address())
                .doesNotContain(Ipv4.format(answering) + ":" + PORT);
    }

    private List<Sent> bootstrapRequests() {
        return this.sent.stream()
                .filter(s -> s.packet().opcode() == Opcodes.Kad.BOOTSTRAP_REQ.code())
                .toList();
    }

    private static NodesDat.Contact record(UInt128 id, int ip, int udpPort, int version) {
        return new NodesDat.Contact(id, ip, udpPort, 4662, version, 0, NodesDat.UdpKey.NONE, false);
    }
}
