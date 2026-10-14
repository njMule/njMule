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
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.core.api.Hash16;
import org.njmule.core.api.KadContactInfo;
import org.njmule.core.persist.format.NodesDat;
import org.njmule.core.protocol.io.DecodeException;
import org.njmule.core.protocol.kad.KadMessages;
import org.njmule.core.protocol.packet.Opcodes;
import org.njmule.core.protocol.packet.Packet;
import org.njmule.core.protocol.packet.Protocol;
import org.njmule.core.util.Ipv4;

/**
 * Nodes older than the hello acknowledgement prove their address another way: a node with keys answers a ping, an older
 * one a lookup for a random target only it was sent.
 */
class KadNodeLegacyChallengeTest {

    private static final int PORT = 4672;
    private static final int PEER = Ipv4.of(20, 1, 1, 1);
    private static final long SECOND = 1_000_000_000L;

    /** A packet the node sent. */
    private record Sent(int ip, int port, Packet packet, Optional<UInt128> receiverId) {}

    @TempDir
    Path dir;

    private final SecureRandom random = new SecureRandom();
    private final AtomicLong time = new AtomicLong(1_000 * SECOND);
    private final Ticker ticker = new Ticker() {
        @Override
        public long read() {
            return KadNodeLegacyChallengeTest.this.time.get();
        }
    };
    private final List<Sent> sent = new ArrayList<>();
    private final UInt128 peerId = new UInt128(0x1234, 0x5678);
    private KadNode node;

    @BeforeEach
    void create() throws IOException {
        this.node = new KadNode(
                KadIdentity.loadOrCreate(this.dir, this.random),
                (ip, port, packet, receiverId, receiverKey, senderKey) ->
                        this.sent.add(new Sent(ip, port, packet, receiverId)),
                (ip, port, userHash, options) -> {},
                new KadNode.Settings(4662, Hash16.ZERO, 0, ip -> false, ip -> false, PORT),
                this.dir,
                this.random,
                this.ticker);
    }

    @Test
    void shouldVerifyANodeWithKeysThatAnswersOurPing() {
        this.givenAHelloFrom(7);

        this.thenThePeerIsVerified(false);

        this.whenThePeerAnswersOurPing();

        this.thenThePeerIsVerified(true);
    }

    @Test
    void shouldVerifyAnOlderNodeThatAnswersALookupOnlyItWasSent() throws DecodeException {
        this.givenAHelloFrom(5);

        this.thenTheChallengeIsAPlainLookupAddressedToThePeer();

        this.whenThePeerAnswersTheLookupFor(this.challenge());

        this.thenThePeerIsVerified(true);
    }

    @Test
    void shouldNotVerifyAnOlderNodeThatAnswersAnotherLookup() throws DecodeException {
        this.givenAHelloFrom(5);

        this.whenThePeerAnswersTheLookupFor(this.challenge().xor(new UInt128(0, 1)));

        this.thenThePeerIsVerified(false);
    }

    @Test
    void shouldChallengeANodeOnceAtATime() {
        this.givenAHelloFrom(5);

        this.whenAHelloArrivesFrom(5);

        this.thenChallengesSent(1);
    }

    @Test
    void shouldChallengeAnOlderNodeAnsweringOurHello() throws DecodeException {
        this.givenAContactWeSayHelloTo(5);

        this.whenThePeerAnswersOurHello(5);
        this.whenThePeerAnswersTheLookupFor(this.challenge());

        this.thenThePeerIsVerified(true);
    }

    private void givenAHelloFrom(int version) {
        this.whenAHelloArrivesFrom(version);
    }

    private void givenAContactWeSayHelloTo(int version) {
        this.node.addContacts(new NodesDat.Contents(
                2,
                false,
                List.of(
                        new NodesDat.Contact(this.peerId, PEER, PORT, 4662, version, 0, NodesDat.UdpKey.NONE, false),
                        new NodesDat.Contact(
                                UInt128.random(this.random),
                                Ipv4.of(30, 1, 1, 1),
                                PORT,
                                4662,
                                8,
                                0,
                                NodesDat.UdpKey.NONE,
                                true))));
        this.time.addAndGet(61 * SECOND);
        this.node.tick();
        assertThat(this.sent)
                .as("our hello")
                .anySatisfy(s -> assertThat(s.packet().opcode()).isEqualTo(Opcodes.Kad.HELLO_REQ.code()));
    }

    private void whenAHelloArrivesFrom(int version) {
        KadMessages.Self sender = new KadMessages.Self(this.peerId, 4662, version);
        this.node.received(PEER, PORT, new KadMessages.Hello(sender, List.of()).encode(false), false, 0, 0);
    }

    private void whenThePeerAnswersOurHello(int version) {
        KadMessages.Self sender = new KadMessages.Self(this.peerId, 4662, version);
        this.node.received(PEER, PORT, new KadMessages.Hello(sender, List.of()).encode(true), false, 0, 0);
    }

    private void whenThePeerAnswersOurPing() {
        assertThat(this.sentOf(Opcodes.Kad.PING)).as("our ping").hasSize(1);
        this.node.received(PEER, PORT, KadMessages.pong(5_000), false, 0, 0);
    }

    private void whenThePeerAnswersTheLookupFor(UInt128 target) {
        this.node.received(PEER, PORT, new KadMessages.Answer(target, List.of()).encode(), false, 0, 0);
    }

    private void thenThePeerIsVerified(boolean verified) {
        assertThat(this.node.contactInfos())
                .filteredOn(c -> c.address().equals(Ipv4.format(PEER) + ":" + PORT))
                .singleElement()
                .extracting(KadContactInfo::verified)
                .isEqualTo(verified);
    }

    private void thenTheChallengeIsAPlainLookupAddressedToThePeer() throws DecodeException {
        Sent challenge = this.sentOf(Opcodes.Kad.REQ).getFirst();
        assertThat(challenge.receiverId()).isEmpty();
        assertThat(KadMessages.Request.decode(challenge.packet()).receiver()).isEqualTo(this.peerId);
    }

    private void thenChallengesSent(int count) {
        assertThat(this.sentOf(Opcodes.Kad.REQ)).hasSize(count);
    }

    private UInt128 challenge() throws DecodeException {
        List<Sent> requests = this.sentOf(Opcodes.Kad.REQ);
        assertThat(requests).as("our challenge").isNotEmpty();
        return KadMessages.Request.decode(requests.getLast().packet()).target();
    }

    private List<Sent> sentOf(Opcodes.Kad opcode) {
        return this.sent.stream()
                .filter(s -> s.ip() == PEER && s.packet().protocol() == Protocol.KAD)
                .filter(s -> s.packet().opcode() == opcode.code())
                .toList();
    }
}
