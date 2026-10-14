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
package org.njmule.core.net;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.common.base.Ticker;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import javax.crypto.CipherInputStream;
import javax.crypto.CipherOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.njmule.core.api.Hash16;
import org.njmule.core.protocol.crypt.StreamObfuscation;
import org.njmule.core.protocol.io.DecodeException;
import org.njmule.core.protocol.packet.FrameLimits;
import org.njmule.core.protocol.packet.Packet;
import org.njmule.core.protocol.packet.Protocol;
import org.njmule.core.protocol.packet.TcpFraming;
import org.njmule.core.security.ConnectionGuard;
import org.njmule.core.security.IpFilter;

@Timeout(20)
class TcpConnectionTest {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final Hash16 OWN_HASH = Hash16.parseHex("0123456789ABCDEF0123456789ABCDEF");
    private static final InetAddress LOOPBACK = InetAddress.getLoopbackAddress();

    @Test
    void talksToAPlainServer() throws Exception {
        this.exchangeWithServer(false);
    }

    @Test
    void talksToAnObfuscatedServer() throws Exception {
        this.exchangeWithServer(true);
    }

    @Test
    void clientsConnectPlainOrObfuscatedToTheListener() throws Exception {
        try (Echo echo = new Echo(true)) {
            for (Optional<Hash16> obfuscation : List.of(Optional.<Hash16>empty(), Optional.of(OWN_HASH))) {
                Recorder recorder = new Recorder();
                TcpConnection client = TcpConnector.connectToClient(echo.address(), obfuscation, TIMEOUT, RANDOM);
                client.start(recorder, frame -> frame);
                assertThat(client.isObfuscated()).isEqualTo(obfuscation.isPresent());

                Packet hello = Packet.of(Protocol.EDONKEY, 0x01, new byte[] {1, 2, 3});
                assertThat(client.send(hello)).isTrue();
                assertThat(recorder.next()).isEqualTo(hello);
                client.close();
                assertThat(recorder.closed.get(5, TimeUnit.SECONDS)).isEmpty();
            }
        }
    }

    @Test
    void aListenerRequiringObfuscationDropsPlainClients() throws Exception {
        try (Echo echo = new Echo(false)) {
            Recorder recorder = new Recorder();
            TcpConnection client = TcpConnector.connectToClient(echo.address(), Optional.empty(), TIMEOUT, RANDOM);
            client.start(recorder, frame -> frame);
            client.send(Packet.of(Protocol.EDONKEY, 0x01, new byte[1]));

            recorder.closed.get(5, TimeUnit.SECONDS);
            assertThat(client.isOpen()).isFalse();
        }
    }

    @Test
    void framesBeyondTheHandshakeLimitCloseTheConnection() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, LOOPBACK)) {
            CompletableFuture<Void> sent = CompletableFuture.runAsync(() -> {
                try (Socket peer = server.accept()) {
                    // Not a greeting: refused before the payload is allocated.
                    peer.getOutputStream().write(TcpFraming.encode(Packet.of(Protocol.EDONKEY, 0x46, new byte[16])));
                    peer.getInputStream().read();
                } catch (IOException e) {
                    // The client hung up, as expected.
                }
            });
            Recorder recorder = new Recorder();
            TcpConnection client = TcpConnector.connectToClient(
                    new InetSocketAddress(LOOPBACK, server.getLocalPort()), Optional.empty(), TIMEOUT, RANDOM);
            client.start(recorder, frame -> frame);

            assertThat(recorder.closed.get(5, TimeUnit.SECONDS)).containsInstanceOf(DecodeException.class);
            sent.get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void aPeerNotReadingIsDroppedOnceAFewMegabytesWait() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, LOOPBACK)) {
            TcpConnection connection = TcpConnector.connectToClient(
                    new InetSocketAddress(LOOPBACK, server.getLocalPort()), Optional.empty(), TIMEOUT, RANDOM);
            try (Socket idle = server.accept()) {
                Recorder recorder = new Recorder();
                connection.start(recorder, frame -> frame);
                Packet big = Packet.of(Protocol.EDONKEY, 0x4B, new byte[1 << 20]);
                int sent = 0;
                while (sent < 64 && connection.send(big)) {
                    sent++;
                }

                assertThat(sent).as("frames queued").isLessThan(64);
                assertThat(recorder.closed.get(5, TimeUnit.SECONDS)).isPresent();
                assertThat(idle.isClosed()).isFalse();
            }
        }
    }

    @Test
    void framesQueuedBeforeACloseAreStillSent() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, LOOPBACK)) {
            TcpConnection connection = TcpConnector.connectToClient(
                    new InetSocketAddress(LOOPBACK, server.getLocalPort()), Optional.empty(), TIMEOUT, RANDOM);
            try (Socket peer = server.accept()) {
                peer.setSoTimeout(5000);
                Recorder recorder = new Recorder();
                connection.start(recorder, frame -> frame);
                for (int i = 0; i < 200; i++) {
                    assertThat(connection.send(Packet.of(Protocol.EDONKEY, 0x01, new byte[] {(byte) i})))
                            .isTrue();
                }
                connection.close();
                assertThat(connection.send(Packet.empty(Protocol.EDONKEY, 0x02)))
                        .isFalse();
                assertThat(recorder.closed.get(5, TimeUnit.SECONDS)).isEmpty();

                InputStream in = new BufferedInputStream(peer.getInputStream());
                for (int i = 0; i < 200; i++) {
                    assertThat(TcpFraming.read(in, FrameLimits.CLIENT).payload())
                            .containsExactly((byte) i);
                }
                assertThat(in.read()).as("closed after the last frame").isEqualTo(-1);
            }
        }
    }

    @Test
    void aHandlerThatFailsClosesTheConnectionOnce() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, LOOPBACK)) {
            TcpConnection connection = TcpConnector.connectToClient(
                    new InetSocketAddress(LOOPBACK, server.getLocalPort()), Optional.empty(), TIMEOUT, RANDOM);
            BlockingQueue<Optional<IOException>> closes = new LinkedBlockingQueue<>();
            connection.start(
                    new TcpConnection.Handler() {
                        @Override
                        public void onPacket(TcpConnection c, Packet packet) {
                            throw new IllegalStateException("bug");
                        }

                        @Override
                        public void onClosed(TcpConnection c, Optional<IOException> cause) {
                            closes.add(cause);
                        }
                    },
                    frame -> frame);
            try (Socket peer = server.accept()) {
                peer.getOutputStream().write(TcpFraming.encode(Packet.of(Protocol.EDONKEY, 0x01, new byte[1])));

                assertThat(closes.poll(5, TimeUnit.SECONDS))
                        .hasValueSatisfying(e -> assertThat(e).hasMessageContaining("handler failed"));
                connection.close();
                assertThat(closes.poll(300, TimeUnit.MILLISECONDS))
                        .as("told once")
                        .isNull();
                assertThat(connection.isOpen()).isFalse();
            }
        }
    }

    @Test
    void aConnectionClosedBeforeItStartsSaysSoWhenStarted() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, LOOPBACK)) {
            TcpConnection connection = TcpConnector.connectToClient(
                    new InetSocketAddress(LOOPBACK, server.getLocalPort()), Optional.empty(), TIMEOUT, RANDOM);
            connection.close();
            Recorder recorder = new Recorder();

            connection.start(recorder, frame -> frame);

            assertThat(recorder.closed.get(5, TimeUnit.SECONDS)).isEmpty();
            assertThat(connection.send(Packet.empty(Protocol.EDONKEY, 0x01))).isFalse();
        }
    }

    private void exchangeWithServer(boolean obfuscated) throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, LOOPBACK)) {
            CompletableFuture<Packet> received = new CompletableFuture<>();
            CompletableFuture<Void> serving = CompletableFuture.runAsync(() -> serveOnce(server, obfuscated, received));
            Recorder recorder = new Recorder();
            TcpConnection connection = TcpConnector.connectToServer(
                    new InetSocketAddress(LOOPBACK, server.getLocalPort()), obfuscated, TIMEOUT, RANDOM);
            connection.start(recorder, frame -> frame);
            Packet login = Packet.of(Protocol.EDONKEY, 0x01, new byte[] {7, 7, 7});

            assertThat(connection.send(login)).isTrue();
            assertThat(received.get(5, TimeUnit.SECONDS)).isEqualTo(login);
            assertThat(recorder.next()).isEqualTo(Packet.of(Protocol.EDONKEY, 0x38, new byte[2]));
            assertThat(connection.isObfuscated()).isEqualTo(obfuscated);
            connection.close();
            serving.get(5, TimeUnit.SECONDS);
        }
    }

    // Reads one frame and answers with an empty server message, like a minimal eD2K server.
    private static void serveOnce(ServerSocket server, boolean obfuscated, CompletableFuture<Packet> received) {
        try (Socket socket = server.accept()) {
            InputStream in = new BufferedInputStream(socket.getInputStream());
            OutputStream out = new BufferedOutputStream(socket.getOutputStream());
            if (obfuscated) {
                in.read();
                StreamObfuscation.Ciphers ciphers = StreamObfuscation.acceptAsServer(in, out, RANDOM);
                in = new CipherInputStream(in, ciphers.receive().cipher());
                out = new CipherOutputStream(out, ciphers.send().cipher());
            }
            received.complete(TcpFraming.read(in, FrameLimits.SERVER));
            out.write(TcpFraming.encode(Packet.of(Protocol.EDONKEY, 0x38, new byte[2])));
            out.flush();
            in.read();
        } catch (IOException e) {
            received.completeExceptionally(e);
        }
    }

    /** A listener whose connections echo every packet back. */
    private static final class Echo implements AutoCloseable {
        private final TcpListener listener;

        Echo(boolean allowPlain) throws IOException {
            ConnectionGuard guard =
                    new ConnectionGuard(IpFilter::empty, ConnectionGuard.Limits.DEFAULT, Ticker.systemTicker());
            this.listener = TcpListener.open(new InetSocketAddress(LOOPBACK, 0), guard, (socket, permit) -> {
                try {
                    TcpConnection connection = TcpConnector.accept(socket, OWN_HASH, allowPlain, TIMEOUT, RANDOM);
                    connection.start(
                            new TcpConnection.Handler() {
                                @Override
                                public void onPacket(TcpConnection c, Packet packet) {
                                    c.send(packet);
                                }

                                @Override
                                public void onClosed(TcpConnection c, Optional<IOException> cause) {
                                    permit.close();
                                }
                            },
                            frame -> frame);
                } catch (IOException e) {
                    permit.close();
                }
            });
        }

        InetSocketAddress address() {
            return new InetSocketAddress(LOOPBACK, this.listener.port());
        }

        @Override
        public void close() throws IOException {
            this.listener.close();
        }
    }

    private static final class Recorder implements TcpConnection.Handler {
        final BlockingQueue<Packet> packets = new LinkedBlockingQueue<>();
        final CompletableFuture<Optional<IOException>> closed = new CompletableFuture<>();

        @Override
        public void onPacket(TcpConnection connection, Packet packet) {
            this.packets.add(packet);
        }

        @Override
        public void onClosed(TcpConnection connection, Optional<IOException> cause) {
            this.closed.complete(cause);
        }

        Packet next() throws InterruptedException {
            Packet packet = this.packets.poll(5, TimeUnit.SECONDS);
            assertThat(packet).isNotNull();
            return packet;
        }
    }
}
