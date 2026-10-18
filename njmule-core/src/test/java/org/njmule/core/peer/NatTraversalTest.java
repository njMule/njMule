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
package org.njmule.core.peer;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.common.base.Ticker;
import com.google.common.net.InetAddresses;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.njmule.core.api.Hash16;
import org.njmule.core.net.StreamTransport;
import org.njmule.core.protocol.ed2k.NatTraversalMessages;
import org.njmule.core.protocol.io.DecodeException;
import org.njmule.core.protocol.io.PacketReader;
import org.njmule.core.protocol.io.PacketWriter;
import org.njmule.core.protocol.packet.Opcodes;
import org.njmule.core.protocol.packet.Packet;
import org.njmule.core.protocol.packet.Protocol;
import org.njmule.core.quic.QuicTransport;
import org.njmule.core.util.Ipv4;
import org.njmule.core.utp.UtpConnection;

@Timeout(60)
class NatTraversalTest {

    // Both behind routers that let in only what comes from where they sent to.
    private static final InetSocketAddress REQUESTER = endpoint("198.51.100.10", 4672);
    private static final InetSocketAddress TARGET = endpoint("203.0.113.20", 4682);
    private static final InetSocketAddress BUDDY = endpoint("192.0.2.30", 4692);
    private static final Hash16 REQUESTER_HASH = Hash16.parseHex("aa".repeat(16));
    private static final Hash16 TARGET_HASH = Hash16.parseHex("bb".repeat(16));
    private static final Hash16 BUDDY_ID = Hash16.parseHex("cc".repeat(16));
    private static final Hash16 FILE = Hash16.parseHex("dd".repeat(16));

    private final ScheduledExecutorService wire = Executors.newSingleThreadScheduledExecutor();
    // What each side's router has sent to, by the side's address.
    private final Map<InetSocketAddress, Set<InetSocketAddress>> sentTo = new ConcurrentHashMap<>();
    private final BlockingQueue<String> opened = new LinkedBlockingQueue<>();
    private final Map<InetSocketAddress, NatTraversal> sides = new ConcurrentHashMap<>();
    private volatile boolean targetTakesPart = true;
    private NatTraversal requester;
    private NatTraversal target;
    private volatile NatTraversalMessages.Rendezvous relayed;
    private CompletableFuture<StreamTransport> asked;
    private StreamTransport requesterSide;
    private StreamTransport targetSide;

    @AfterEach
    void stop() {
        for (NatTraversal side : this.sides.values()) {
            side.close();
        }
        this.wire.shutdownNow();
    }

    @Test
    void shouldOpenAUtpConnectionThroughBothRoutersByARendezvousThroughTheTargetsBuddy() throws Exception {
        givenTwoFirewalledClients();

        whenTheRequesterAsksForARendezvous(false);

        thenTheRequesterGetsAConnection(UtpConnection.class);
        thenTheRendezvousAskedFor(NatTraversalMessages.TRANSPORT_UTP);
        thenTheTargetSaysHelloFirstAndTheRequesterWaits();
        thenBytesCrossTheConnection();
    }

    @Test
    void shouldOpenAQuicConnectionThroughBothRoutersWhenTheTargetHasQuic() throws Exception {
        givenTwoFirewalledClients();

        whenTheRequesterAsksForARendezvous(true);

        thenTheRequesterGetsAConnection(QuicTransport.class);
        thenTheRendezvousAskedFor(NatTraversalMessages.TRANSPORT_QUIC);
        thenTheTargetSaysHelloFirstAndTheRequesterWaits();
        thenBytesCrossTheConnection();
    }

    @Test
    void shouldOpenNoConnectionWhenTheTargetDoesNotPunchItsHoles() throws Exception {
        givenTwoFirewalledClients();
        givenTheTargetIgnoresRendezvous();

        whenTheRequesterAsksForARendezvous(false);

        thenNothingOpensWithinSeconds(3);
    }

    private void givenTwoFirewalledClients() {
        this.requester = side(REQUESTER, REQUESTER_HASH);
        this.target = side(TARGET, TARGET_HASH);
    }

    private void givenTheTargetIgnoresRendezvous() {
        this.targetTakesPart = false;
    }

    private void whenTheRequesterAsksForARendezvous(boolean targetHasQuic) {
        this.asked = this.requester.rendezvous(
                TARGET,
                TARGET_HASH,
                BUDDY,
                BUDDY_ID,
                FILE,
                Ipv4.fromBytes(REQUESTER.getAddress().getAddress()),
                REQUESTER.getPort(),
                targetHasQuic);
    }

    private void thenTheRequesterGetsAConnection(Class<? extends StreamTransport> kind) throws Exception {
        this.requesterSide = this.asked.get(30, TimeUnit.SECONDS);
        assertThat(this.requesterSide).isInstanceOf(kind);
        assertThat(this.requesterSide.remoteAddress().getAddress()).isEqualTo(TARGET.getAddress());
    }

    private void thenTheRendezvousAskedFor(int transport) {
        assertThat(this.relayed.transport()).isEqualTo(transport);
        assertThat(this.relayed.options() & NatTraversalMessages.OPTION_UTP).isNotZero();
        assertThat(this.relayed.options() & NatTraversalMessages.OPTION_QUIC).isNotZero();
    }

    private void thenTheTargetSaysHelloFirstAndTheRequesterWaits() throws Exception {
        String first = this.opened.poll(10, TimeUnit.SECONDS);
        String second = this.opened.poll(10, TimeUnit.SECONDS);
        assertThat(Set.of(first, second)).containsExactlyInAnyOrder(REQUESTER + " waits", TARGET + " speaks first");
        this.targetSide = this.target.connections().getFirst();
    }

    private void thenBytesCrossTheConnection() throws IOException {
        this.requesterSide.output().write("file request".getBytes(StandardCharsets.US_ASCII));
        this.requesterSide.output().flush();
        assertThat(new String(this.targetSide.input().readNBytes(12), StandardCharsets.US_ASCII))
                .isEqualTo("file request");
    }

    private void thenNothingOpensWithinSeconds(int seconds) throws InterruptedException {
        assertThat(this.opened.poll(seconds, TimeUnit.SECONDS)).isNull();
        assertThat(this.asked).isNotDone();
    }

    private NatTraversal side(InetSocketAddress at, Hash16 hash) {
        this.sentTo.put(at, ConcurrentHashMap.newKeySet());
        NatTraversal side = new NatTraversal(
                (to, packet) -> send(at, to, packet),
                (connection, helloFirst) -> this.opened.add(at + (helloFirst ? " speaks first" : " waits")),
                Ticker.systemTicker(),
                new SecureRandom(),
                () -> hash,
                () -> 0,
                () -> false);
        this.sides.put(at, side);
        return side;
    }

    // The datagram leaves the sender's router, which lets answers from there in from now on; it reaches the other side
    // if that side's router let it in. The buddy is reachable and passes a rendezvous on.
    @SuppressWarnings("FutureReturnValueIgnored")
    private void send(InetSocketAddress from, InetSocketAddress to, Packet packet) {
        this.sentTo.get(from).add(to);
        this.wire.schedule(() -> deliver(from, to, packet), 3, TimeUnit.MILLISECONDS);
    }

    private void deliver(InetSocketAddress from, InetSocketAddress to, Packet packet) {
        if (to.equals(BUDDY)) {
            relay(from, packet);
            return;
        }
        NatTraversal side = this.sides.get(to);
        if (side == null || !this.sentTo.get(to).contains(from)) {
            return;
        }
        if (packet.protocol() == Protocol.UDP_RESERVED_2) {
            side.frame(from, packet);
        } else if (packet.protocol() == Protocol.EMULE && packet.opcode() == Opcodes.ClientUdp.HOLEPUNCH.code()) {
            side.holePunched(from);
        }
    }

    // As a buddy does: the re-ask through it goes on over TCP with the address it came from, the buddy id left out.
    private void relay(InetSocketAddress from, Packet packet) {
        if (packet.opcode() != Opcodes.ClientUdp.REASKCALLBACKUDP.code() || !this.targetTakesPart) {
            return;
        }
        try {
            PacketReader in = packet.reader();
            assertThat(in.hash16()).isEqualTo(BUDDY_ID);
            Packet forward = Packet.of(
                    Protocol.EMULE,
                    Opcodes.Extended.REASKCALLBACKTCP.code(),
                    new PacketWriter()
                            .u32(Ipv4.toEd2k(Ipv4.fromBytes(from.getAddress().getAddress())))
                            .u16(from.getPort())
                            .bytes(in.rest()));
            Optional<NatTraversalMessages.Forward> passed = NatTraversalMessages.Forward.decode(forward);
            assertThat(passed)
                    .hasValueSatisfying(f -> assertThat(f.opcode()).isEqualTo(Opcodes.ClientUdp.RENDEZVOUS.code()));
            this.relayed = NatTraversalMessages.Rendezvous.decode(passed.get().payload());
            this.target.rendezvousForwarded(passed.get().requester(), this.relayed);
        } catch (DecodeException e) {
            throw new AssertionError(e);
        }
    }

    private static InetSocketAddress endpoint(String ip, int port) {
        return new InetSocketAddress(InetAddresses.forString(ip), port);
    }
}
