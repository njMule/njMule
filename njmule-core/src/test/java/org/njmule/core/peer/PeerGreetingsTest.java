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
import java.io.BufferedInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.njmule.core.api.Hash16;
import org.njmule.core.net.StreamTransport;
import org.njmule.core.net.TcpListener;
import org.njmule.core.protocol.ed2k.Hello;
import org.njmule.core.protocol.packet.FrameLimits;
import org.njmule.core.protocol.packet.Opcodes;
import org.njmule.core.protocol.packet.Packet;
import org.njmule.core.protocol.packet.TcpFraming;
import org.njmule.core.security.ConnectionGuard;
import org.njmule.core.security.IpFilter;

/** The hello of clients that connect to us. */
@Timeout(60)
class PeerGreetingsTest {

    private static final Hash16 OURS = Hash16.parseHex("ab".repeat(16));
    private static final Hash16 PEER = Hash16.parseHex("59".repeat(16));
    private static final Duration WAIT = Duration.ofSeconds(10);

    private final List<AutoCloseable> resources = new ArrayList<>();
    private final List<Hash16> routed = new CopyOnWriteArrayList<>();
    private int port;
    private Socket client;
    private InputStream in;
    private Packet answer;
    private Packet first;
    private OutputStream clientOut;

    @AfterEach
    void stop() throws Exception {
        Collections.reverse(this.resources);
        for (AutoCloseable resource : this.resources) {
            resource.close();
        }
    }

    @Test
    void shouldAnswerAClientThatGreetsUsWithOurOwnUserHashButTakeNothingFromIt() throws Exception {
        givenWeListen();

        whenAClientGreetsUsWith(OURS);

        thenItGetsOurAnswer();
        thenTheConnectionCloses();
        thenNothingTakesTheConnection();
    }

    @Test
    void shouldHandOverAClientThatGreetsUsWithItsOwnUserHash() throws Exception {
        givenWeListen();

        whenAClientGreetsUsWith(PEER);

        thenItGetsOurAnswer();
        thenTheConnectionIsHandedOverFor(PEER);
    }

    @Test
    void shouldSayHelloFirstOverAConnectionThroughRoutersAndHandItOverOnTheClientsHello() throws Exception {
        givenAConnectionThroughRoutersWeTook();

        whenTheClientGreetsUsBackWith(PEER);

        thenOurHelloCameFirstThenOurAnswer();
        thenTheConnectionIsHandedOverFor(PEER);
    }

    private void givenAConnectionThroughRoutersWeTook() throws Exception {
        SecureRandom random = new SecureRandom();
        Hello ours = Hello.of(OURS, 0, 4662, "test", 0, 0, 0, 0, 0, 0);
        PeerGreetings greetings = new PeerGreetings(OURS, () -> ours, () -> true, ip -> false, random);
        greetings.routeTo((connection, peer) -> {
            this.routed.add(peer.userHash());
            return Optional.empty();
        });
        this.resources.add(greetings);
        PipedInputStream toUs = new PipedInputStream(1 << 16);
        PipedInputStream toClient = new PipedInputStream(1 << 16);
        this.clientOut = new PipedOutputStream(toUs);
        this.in = new BufferedInputStream(toClient);
        StreamTransport ourSide = new PipeTransport(toUs, new PipedOutputStream(toClient));
        ConnectionGuard guard = new ConnectionGuard(
                IpFilter::empty,
                new ConnectionGuard.Limits(500, 100, 1000, Duration.ofSeconds(5)),
                Ticker.systemTicker());
        greetings.acceptedOver(ourSide, guard.outgoing(), true);
    }

    private void whenTheClientGreetsUsBackWith(Hash16 userHash) throws Exception {
        this.first = TcpFraming.read(this.in, FrameLimits.CLIENT);
        Hello hello = Hello.of(userHash, 1, 4663, "client", 0, 0, 0, 0, 0, 0);
        this.clientOut.write(TcpFraming.encode(hello.encode(false)));
        this.clientOut.flush();
        this.answer = TcpFraming.read(this.in, FrameLimits.CLIENT);
    }

    private void thenOurHelloCameFirstThenOurAnswer() throws Exception {
        assertThat(this.first.opcode()).isEqualTo(Opcodes.Client.HELLO.code());
        assertThat(Hello.decode(this.first).userHash()).isEqualTo(OURS);
        thenItGetsOurAnswer();
    }

    private void givenWeListen() throws Exception {
        SecureRandom random = new SecureRandom();
        Hello ours = Hello.of(OURS, 0, 4662, "test", 0, 0, 0, 0, 0, 0);
        PeerGreetings greetings = new PeerGreetings(OURS, () -> ours, () -> true, ip -> false, random);
        greetings.routeTo((connection, peer) -> {
            this.routed.add(peer.userHash());
            return Optional.empty();
        });
        TcpListener listener = TcpListener.open(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0),
                new ConnectionGuard(
                        IpFilter::empty,
                        new ConnectionGuard.Limits(500, 100, 1000, Duration.ofSeconds(5)),
                        Ticker.systemTicker()),
                greetings::accepted);
        this.resources.add(greetings);
        this.resources.add(listener);
        this.port = listener.port();
    }

    private void whenAClientGreetsUsWith(Hash16 userHash) throws Exception {
        this.client = new Socket(InetAddress.getLoopbackAddress(), this.port);
        this.resources.add(this.client);
        this.client.setSoTimeout(Math.toIntExact(WAIT.toMillis()));
        this.in = new BufferedInputStream(this.client.getInputStream());
        Hello hello = Hello.of(userHash, 0, 4663, "client", 0, 0, 0, 0, 0, 0);
        this.client.getOutputStream().write(TcpFraming.encode(hello.encode(false)));
        this.answer = TcpFraming.read(this.in, FrameLimits.CLIENT);
    }

    private void thenItGetsOurAnswer() throws Exception {
        assertThat(this.answer.opcode()).isEqualTo(Opcodes.Client.HELLOANSWER.code());
        assertThat(Hello.decode(this.answer).userHash()).isEqualTo(OURS);
    }

    private void thenTheConnectionCloses() throws Exception {
        boolean closed;
        try {
            closed = this.in.read() < 0;
        } catch (EOFException | SocketException e) {
            closed = true;
        }
        assertThat(closed).isTrue();
    }

    private void thenNothingTakesTheConnection() {
        assertThat(this.routed).isEmpty();
    }

    private void thenTheConnectionIsHandedOverFor(Hash16 userHash) throws Exception {
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (this.routed.isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertThat(this.routed).containsExactly(userHash);
    }

    // One end of a pipe, as a stream through the routers would be.
    private record PipeTransport(InputStream in, OutputStream out) implements StreamTransport {
        @Override
        public InputStream input() {
            return this.in;
        }

        @Override
        public OutputStream output() {
            return this.out;
        }

        @Override
        public InetSocketAddress remoteAddress() {
            return new InetSocketAddress(InetAddress.getLoopbackAddress(), 4663);
        }

        @Override
        public Optional<InetAddress> localAddress() {
            return Optional.empty();
        }

        @Override
        public void setReadTimeout(Duration timeout) {
            // Pipes wait forever.
        }

        @Override
        public void close() {
            try {
                this.in.close();
                this.out.close();
            } catch (IOException e) {
                // Closing anyway.
            }
        }
    }
}
