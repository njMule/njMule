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
package org.njmule.core.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.njmule.core.net.FakeSocks5Proxy;
import org.njmule.core.net.Socks5Udp;
import org.njmule.core.net.UdpRoute;
import org.njmule.core.protocol.packet.Packet;
import org.njmule.core.protocol.packet.Protocol;
import org.njmule.core.protocol.packet.UdpFraming;

@Timeout(30)
class ServerUdpTest {

    // Enough rounds to meet the close race, which most rounds lose on Linux when the port is not waited for.
    private static final int ROUNDS = 20;
    private static final int OPCODE = 0x96;

    private final BlockingQueue<InetSocketAddress> senders = new LinkedBlockingQueue<>();
    private int busy;
    private ServerUdp udp;
    private DatagramSocket server;
    private FakeSocks5Proxy proxy;
    private Socks5Udp association;
    private InetSocketAddress askedBy;

    @AfterEach
    void close() throws IOException {
        if (this.udp != null) {
            this.udp.close();
        }
        if (this.server != null) {
            this.server.close();
        }
        if (this.association != null) {
            this.association.close();
        }
        if (this.proxy != null) {
            this.proxy.close();
        }
    }

    @Test
    void shouldLeaveThePortOfAClosedSocketFreeAtOnce() throws Exception {
        whenSocketsAreClosedAndTheirPortsBoundAgain(ROUNDS);
        thenThePortWasBusyTimes(0);
    }

    @Test
    void shouldAskAServerThroughTheUdpRelayOfASocksProxy() throws Exception {
        givenAServerSocketThatAnswers();
        givenTheUdpSocketGoesThroughTheRelayOfAProxy();

        whenTheServerIsAsked();

        thenTheServerWasAskedByTheRelay();
        thenTheAnswerCameFromTheServer();
    }

    @Test
    void shouldSendNothingWhileTheRouteIsHeld() throws Exception {
        givenAServerSocketThatAnswers();
        givenTheUdpSocketIsHeld();

        whenTheServerIsAsked();

        thenTheServerHeardNothing();
    }

    private void givenAServerSocketThatAnswers() throws SocketException {
        this.server = new DatagramSocket(0, InetAddress.getLoopbackAddress());
        this.server.setSoTimeout(2000);
        this.udp = ServerUdp.open(0, (from, packet) -> this.senders.add(from));
    }

    private void givenTheUdpSocketGoesThroughTheRelayOfAProxy() throws IOException {
        this.proxy = FakeSocks5Proxy.start();
        this.association = Socks5Udp.associate(this.proxy.settings(), null, Duration.ofSeconds(5));
        this.udp.route(UdpRoute.through(this.association));
    }

    private void givenTheUdpSocketIsHeld() {
        this.udp.route(UdpRoute.HELD);
    }

    // The server echoes what it gets to whoever sent it.
    private void whenTheServerIsAsked() throws IOException {
        this.udp.send(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), this.server.getLocalPort()),
                Packet.of(Protocol.EDONKEY, OPCODE, new byte[4]));
        byte[] buffer = new byte[1024];
        DatagramPacket asked = new DatagramPacket(buffer, buffer.length);
        try {
            this.server.receive(asked);
        } catch (SocketTimeoutException e) {
            return;
        }
        this.askedBy = (InetSocketAddress) asked.getSocketAddress();
        byte[] answer = UdpFraming.encode(Packet.of(Protocol.EDONKEY, OPCODE + 1, new byte[4]));
        this.server.send(new DatagramPacket(answer, answer.length, this.askedBy));
    }

    // Each socket receives (its reader waits on it) when it closes, as when Kad takes the port of the server socket.
    private void whenSocketsAreClosedAndTheirPortsBoundAgain(int rounds) throws Exception {
        for (int i = 0; i < rounds; i++) {
            ServerUdp udp = ServerUdp.open(0, (from, packet) -> {});
            int port = udp.port();
            Thread.sleep(5);
            udp.close();
            try (DatagramSocket again = new DatagramSocket(port)) {
                assertThat(again.getLocalPort()).isEqualTo(port);
            } catch (SocketException e) {
                this.busy++;
            }
        }
    }

    private void thenThePortWasBusyTimes(int times) {
        assertThat(this.busy).isEqualTo(times);
    }

    private void thenTheServerWasAskedByTheRelay() {
        assertThat(this.askedBy).isEqualTo(this.association.relay());
    }

    private void thenTheAnswerCameFromTheServer() throws InterruptedException {
        assertThat(this.senders.poll(5, TimeUnit.SECONDS))
                .isEqualTo(new InetSocketAddress(InetAddress.getLoopbackAddress(), this.server.getLocalPort()));
    }

    private void thenTheServerHeardNothing() {
        assertThat(this.askedBy).isNull();
    }
}
