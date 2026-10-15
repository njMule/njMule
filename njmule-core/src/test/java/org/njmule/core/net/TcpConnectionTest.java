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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import javax.crypto.CipherInputStream;
import javax.crypto.CipherOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
    private static final int DATA = 0x46;
    private static final int CONTROL = 0x58;
    // About 10 MB: more than a loopback socket buffers for a peer that does not read.
    private static final int DATA_FRAMES = 1_000;

    private final List<AutoCloseable> resources = new ArrayList<>();

    private ServerSocket server;
    private Echo echo;
    private TcpConnection connection;
    private Socket peer;
    private Recorder recorder;
    private BlockingQueue<Optional<IOException>> closes;
    private CompletableFuture<Packet> received;
    private CompletableFuture<Void> serving;
    private Packet packet;
    private boolean sent;
    private int framesQueued;

    @AfterEach
    void closeEverything() throws Exception {
        for (AutoCloseable resource : this.resources.reversed()) {
            resource.close();
        }
    }

    @Test
    void shouldTalkToAPlainServer() throws Exception {
        givenAServerAnsweringOnce(false);
        givenAConnectionToTheServer(false);

        whenThePacketIsSent(Packet.of(Protocol.EDONKEY, 0x01, new byte[] {7, 7, 7}));

        thenItIsSent();
        thenTheServerReceivedThePacket();
        thenThePacketReceivedIs(Packet.of(Protocol.EDONKEY, 0x38, new byte[2]));
        thenTheConnectionIsObfuscated(false);
        thenTheServerIsDoneOnceTheConnectionCloses();
    }

    @Test
    void shouldTalkToAnObfuscatedServer() throws Exception {
        givenAServerAnsweringOnce(true);
        givenAConnectionToTheServer(true);

        whenThePacketIsSent(Packet.of(Protocol.EDONKEY, 0x01, new byte[] {7, 7, 7}));

        thenItIsSent();
        thenTheServerReceivedThePacket();
        thenThePacketReceivedIs(Packet.of(Protocol.EDONKEY, 0x38, new byte[2]));
        thenTheConnectionIsObfuscated(true);
        thenTheServerIsDoneOnceTheConnectionCloses();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void shouldConnectPlainOrObfuscatedToTheListener(boolean obfuscated) throws IOException {
        givenAListenerEchoingPackets(true);

        whenAClientConnectsToTheListener(obfuscated);

        thenTheConnectionIsObfuscated(obfuscated);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void shouldGetItsPacketsBackFromAnEchoingListener(boolean obfuscated) throws Exception {
        givenAListenerEchoingPackets(true);
        givenAClientConnectedToTheListener(obfuscated);

        whenThePacketIsSent(Packet.of(Protocol.EDONKEY, 0x01, new byte[] {1, 2, 3}));

        thenItIsSent();
        thenThePacketComesBack();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void shouldCloseAConnectionToTheListenerWithoutError(boolean obfuscated) throws Exception {
        givenAListenerEchoingPackets(true);
        givenAClientConnectedToTheListener(obfuscated);
        givenThePacketEchoed(Packet.of(Protocol.EDONKEY, 0x01, new byte[] {1, 2, 3}));

        whenTheConnectionIsClosed();

        thenItClosedWithoutError();
    }

    @Test
    void shouldDropPlainClientsOfAListenerRequiringObfuscation() throws Exception {
        givenAListenerEchoingPackets(false);
        givenAClientConnectedToTheListener(false);

        whenThePacketIsSent(Packet.of(Protocol.EDONKEY, 0x01, new byte[1]));

        thenTheConnectionIsDropped();
    }

    @Test
    void shouldCloseTheConnectionOnAFrameBeyondTheHandshakeLimit() throws Exception {
        givenAPeerSendingAFrameThatIsNotAGreeting();

        whenAClientConnectsToThePeer();

        thenItClosedWith(DecodeException.class);
        thenThePeerIsDone();
    }

    @Test
    void shouldDropAPeerNotReadingOnceAFewMegabytesWait() throws Exception {
        givenAConnectionToAPeerThatReadsNothingYet();

        whenMegabyteFramesAreSentUpTo(64);

        thenFewerFramesAreQueuedThan(64);
        thenItClosedWithACause();
        thenThePeerSocketIsStillOpen();
    }

    @Test
    void shouldStillSendFramesQueuedBeforeAClose() throws Exception {
        givenAConnectionToAPeerThatReadsNothingYet();
        givenSmallFramesQueued(200);

        whenTheConnectionIsClosed();

        thenItClosedWithoutError();
        thenThePeerReadsTheFramesThenTheEnd(200);
    }

    @Test
    void shouldRefuseToSendAfterAClose() throws IOException {
        givenAConnectionToAPeerThatReadsNothingYet();
        givenSmallFramesQueued(200);
        givenTheConnectionClosed();

        whenThePacketIsSent(Packet.empty(Protocol.EDONKEY, 0x02));

        thenItIsRefused();
    }

    @Test
    void shouldCloseTheConnectionWhenAHandlerFails() throws Exception {
        givenAConnectionWhoseHandlerFails();

        whenThePeerSendsAPacket();

        thenTheCloseIsToldBecause("handler failed");
    }

    @Test
    void shouldTellTheCloseOfAFailedHandlerOnlyOnce() throws Exception {
        givenAConnectionWhoseHandlerFails();
        givenThePeerSentAPacket();
        givenTheCloseToldBecause("handler failed");

        whenTheConnectionIsClosed();

        thenNoOtherCloseIsTold();
        thenTheConnectionIsNotOpen();
    }

    @Test
    void shouldSayAConnectionClosedBeforeItStartsIsClosedWhenStarted() throws Exception {
        givenAConnectionClosedBeforeItStarts();

        whenItIsStarted();

        thenItClosedWithoutError();
    }

    @Test
    void shouldRefuseToSendOnAConnectionClosedBeforeItStarted() throws Exception {
        givenAConnectionClosedBeforeItStarts();
        givenItStartedAndSaidItIsClosed();

        whenThePacketIsSent(Packet.empty(Protocol.EDONKEY, 0x01));

        thenItIsRefused();
    }

    @Test
    void shouldSendAPacketWhileFileDataWaitsBeforeIt() throws IOException, DecodeException {
        givenAConnectionToAPeerThatReadsNothingYet();
        givenMuchFileDataQueued();

        whenThePacketIsSent(Packet.of(Protocol.EDONKEY, CONTROL, new byte[1]));

        thenItIsSent();
        thenThePeerReadsItBeforeTheDataStillQueued();
    }

    private void givenAServerAnsweringOnce(boolean obfuscated) throws IOException {
        ServerSocket listening = listen();
        CompletableFuture<Packet> receiving = new CompletableFuture<>();
        this.received = receiving;
        this.serving = CompletableFuture.runAsync(() -> serveOnce(listening, obfuscated, receiving));
    }

    private void givenAConnectionToTheServer(boolean obfuscated) throws IOException {
        this.recorder = new Recorder();
        this.connection = TcpConnector.connectToServer(
                new InetSocketAddress(LOOPBACK, this.server.getLocalPort()), obfuscated, TIMEOUT, RANDOM);
        this.connection.start(this.recorder, frame -> frame);
    }

    private void givenAListenerEchoingPackets(boolean allowPlain) throws IOException {
        this.echo = new Echo(allowPlain);
        this.resources.add(this.echo);
    }

    private void givenAClientConnectedToTheListener(boolean obfuscated) throws IOException {
        connectToTheListener(obfuscated);
    }

    private void givenThePacketEchoed(Packet echoed) throws InterruptedException {
        assertThat(this.connection.send(echoed)).isTrue();
        assertThat(this.recorder.next()).isEqualTo(echoed);
    }

    private void givenAPeerSendingAFrameThatIsNotAGreeting() throws IOException {
        ServerSocket listening = listen();
        this.serving = CompletableFuture.runAsync(() -> {
            try (Socket socket = listening.accept()) {
                // Not a greeting: refused before the payload is allocated.
                socket.getOutputStream().write(TcpFraming.encode(Packet.of(Protocol.EDONKEY, 0x46, new byte[16])));
                socket.getInputStream().read();
            } catch (IOException e) {
                // The client hung up, as expected.
            }
        });
    }

    private void givenAConnectionToAPeerThatReadsNothingYet() throws IOException {
        listen();
        this.connection = TcpConnector.connectToClient(
                new InetSocketAddress(LOOPBACK, this.server.getLocalPort()), Optional.empty(), TIMEOUT, RANDOM);
        acceptThePeer();
        this.peer.setSoTimeout(5000);
        this.recorder = new Recorder();
        this.connection.start(this.recorder, frame -> frame);
    }

    private void givenSmallFramesQueued(int count) {
        for (int i = 0; i < count; i++) {
            assertThat(this.connection.send(Packet.of(Protocol.EDONKEY, 0x01, new byte[] {(byte) i})))
                    .isTrue();
        }
    }

    private void givenMuchFileDataQueued() {
        for (int i = 0; i < DATA_FRAMES; i++) {
            assertThat(this.connection.sendData(Packet.of(Protocol.EDONKEY, DATA, new byte[10_240])))
                    .isTrue();
        }
        assertThat(this.connection.queuedData()).isPositive();
    }

    private void givenTheConnectionClosed() {
        this.connection.close();
    }

    private void givenAConnectionWhoseHandlerFails() throws IOException {
        listen();
        this.connection = TcpConnector.connectToClient(
                new InetSocketAddress(LOOPBACK, this.server.getLocalPort()), Optional.empty(), TIMEOUT, RANDOM);
        BlockingQueue<Optional<IOException>> told = new LinkedBlockingQueue<>();
        this.closes = told;
        this.connection.start(
                new TcpConnection.Handler() {
                    @Override
                    public void onPacket(TcpConnection c, Packet incoming) {
                        throw new IllegalStateException("bug");
                    }

                    @Override
                    public void onClosed(TcpConnection c, Optional<IOException> cause) {
                        told.add(cause);
                    }
                },
                frame -> frame);
        acceptThePeer();
    }

    private void givenThePeerSentAPacket() throws IOException {
        sendFromThePeer();
    }

    private void givenTheCloseToldBecause(String reason) throws InterruptedException {
        assertTheCloseIsToldBecause(reason);
    }

    private void givenAConnectionClosedBeforeItStarts() throws IOException {
        listen();
        this.connection = TcpConnector.connectToClient(
                new InetSocketAddress(LOOPBACK, this.server.getLocalPort()), Optional.empty(), TIMEOUT, RANDOM);
        this.connection.close();
        this.recorder = new Recorder();
    }

    private void givenItStartedAndSaidItIsClosed() throws Exception {
        this.connection.start(this.recorder, frame -> frame);
        assertThat(this.recorder.closed.get(5, TimeUnit.SECONDS)).isEmpty();
    }

    private void whenThePacketIsSent(Packet sending) {
        this.packet = sending;
        this.sent = this.connection.send(sending);
    }

    private void whenAClientConnectsToTheListener(boolean obfuscated) throws IOException {
        connectToTheListener(obfuscated);
    }

    private void whenAClientConnectsToThePeer() throws IOException {
        this.recorder = new Recorder();
        this.connection = TcpConnector.connectToClient(
                new InetSocketAddress(LOOPBACK, this.server.getLocalPort()), Optional.empty(), TIMEOUT, RANDOM);
        this.connection.start(this.recorder, frame -> frame);
    }

    private void whenMegabyteFramesAreSentUpTo(int most) {
        Packet big = Packet.of(Protocol.EDONKEY, 0x4B, new byte[1 << 20]);
        while (this.framesQueued < most && this.connection.send(big)) {
            this.framesQueued++;
        }
    }

    private void whenTheConnectionIsClosed() {
        this.connection.close();
    }

    private void whenThePeerSendsAPacket() throws IOException {
        sendFromThePeer();
    }

    private void whenItIsStarted() {
        this.connection.start(this.recorder, frame -> frame);
    }

    private void thenItIsSent() {
        assertThat(this.sent).isTrue();
    }

    private void thenItIsRefused() {
        assertThat(this.sent).isFalse();
    }

    private void thenTheServerReceivedThePacket() throws Exception {
        assertThat(this.received.get(5, TimeUnit.SECONDS)).isEqualTo(this.packet);
    }

    private void thenThePacketReceivedIs(Packet expected) throws InterruptedException {
        assertThat(this.recorder.next()).isEqualTo(expected);
    }

    private void thenThePacketComesBack() throws InterruptedException {
        assertThat(this.recorder.next()).isEqualTo(this.packet);
    }

    private void thenTheConnectionIsObfuscated(boolean expected) {
        assertThat(this.connection.isObfuscated()).isEqualTo(expected);
    }

    private void thenTheServerIsDoneOnceTheConnectionCloses() throws Exception {
        this.connection.close();
        this.serving.get(5, TimeUnit.SECONDS);
    }

    private void thenThePeerIsDone() throws Exception {
        this.serving.get(5, TimeUnit.SECONDS);
    }

    private void thenItClosedWithoutError() throws Exception {
        assertThat(this.recorder.closed.get(5, TimeUnit.SECONDS)).isEmpty();
    }

    private void thenItClosedWithACause() throws Exception {
        assertThat(this.recorder.closed.get(5, TimeUnit.SECONDS)).isPresent();
    }

    private void thenItClosedWith(Class<? extends IOException> cause) throws Exception {
        assertThat(this.recorder.closed.get(5, TimeUnit.SECONDS)).containsInstanceOf(cause);
    }

    private void thenTheConnectionIsDropped() throws Exception {
        this.recorder.closed.get(5, TimeUnit.SECONDS);
        assertThat(this.connection.isOpen()).isFalse();
    }

    private void thenTheConnectionIsNotOpen() {
        assertThat(this.connection.isOpen()).isFalse();
    }

    private void thenFewerFramesAreQueuedThan(int most) {
        assertThat(this.framesQueued).as("frames queued").isLessThan(most);
    }

    private void thenThePeerSocketIsStillOpen() {
        assertThat(this.peer.isClosed()).isFalse();
    }

    private void thenThePeerReadsTheFramesThenTheEnd(int count) throws IOException, DecodeException {
        InputStream in = new BufferedInputStream(this.peer.getInputStream());
        for (int i = 0; i < count; i++) {
            assertThat(TcpFraming.read(in, FrameLimits.CLIENT).payload()).containsExactly((byte) i);
        }
        assertThat(in.read()).as("closed after the last frame").isEqualTo(-1);
    }

    private void thenTheCloseIsToldBecause(String reason) throws InterruptedException {
        assertTheCloseIsToldBecause(reason);
    }

    private void thenNoOtherCloseIsTold() throws InterruptedException {
        assertThat(this.closes.poll(300, TimeUnit.MILLISECONDS)).as("told once").isNull();
    }

    private void thenThePeerReadsItBeforeTheDataStillQueued() throws IOException, DecodeException {
        InputStream in = new BufferedInputStream(this.peer.getInputStream());
        int dataBefore = 0;
        while (TcpFraming.read(in, FrameLimits.CLIENT).opcode() == DATA) {
            dataBefore++;
        }
        assertThat(dataBefore).isLessThan(DATA_FRAMES);
        for (int i = dataBefore; i < DATA_FRAMES; i++) {
            assertThat(TcpFraming.read(in, FrameLimits.CLIENT).opcode()).isEqualTo(DATA);
        }
        assertThat(this.connection.queuedData()).isZero();
        this.connection.close();
        this.peer.close();
    }

    private void assertTheCloseIsToldBecause(String reason) throws InterruptedException {
        assertThat(this.closes.poll(5, TimeUnit.SECONDS))
                .hasValueSatisfying(e -> assertThat(e).hasMessageContaining(reason));
    }

    private ServerSocket listen() throws IOException {
        this.server = new ServerSocket(0, 1, LOOPBACK);
        this.resources.add(this.server);
        return this.server;
    }

    private void acceptThePeer() throws IOException {
        this.peer = this.server.accept();
        this.resources.add(this.peer);
    }

    private void connectToTheListener(boolean obfuscated) throws IOException {
        Optional<Hash16> obfuscation = obfuscated ? Optional.of(OWN_HASH) : Optional.empty();
        this.recorder = new Recorder();
        this.connection = TcpConnector.connectToClient(this.echo.address(), obfuscation, TIMEOUT, RANDOM);
        this.connection.start(this.recorder, frame -> frame);
    }

    private void sendFromThePeer() throws IOException {
        this.peer.getOutputStream().write(TcpFraming.encode(Packet.of(Protocol.EDONKEY, 0x01, new byte[1])));
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
