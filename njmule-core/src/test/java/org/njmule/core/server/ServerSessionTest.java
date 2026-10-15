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

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.SecureRandom;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.njmule.core.api.Hash16;
import org.njmule.core.config.ObfuscationMode;
import org.njmule.core.config.Preferences;
import org.njmule.core.engine.EngineLoop;
import org.njmule.core.persist.format.ServerMet;
import org.njmule.core.protocol.ed2k.ServerDatagrams;
import org.njmule.core.protocol.ed2k.ServerMessages;
import org.njmule.core.protocol.packet.FrameLimits;
import org.njmule.core.protocol.packet.Opcodes;
import org.njmule.core.protocol.packet.Packet;
import org.njmule.core.protocol.packet.TcpFraming;
import org.njmule.core.protocol.tag.ServerTag;
import org.njmule.core.protocol.tag.Tag;
import org.njmule.core.protocol.tag.TagName;
import org.njmule.core.util.Ipv4;

@Timeout(30)
class ServerSessionTest {

    private static final int LOOPBACK = Ipv4.parse("127.0.0.1");
    private static final long HIGH_ID = Ipv4.toEd2k(Ipv4.parse("1.2.3.4"));

    private final EngineLoop loop = EngineLoop.start(1024, "test-engine");
    private final ServerList servers = new ServerList();
    private final BlockingQueue<String> events = new LinkedBlockingQueue<>();
    private final List<String> seen = new CopyOnWriteArrayList<>();
    private Preferences preferences = Preferences.defaults();
    private final ServerSession session = new ServerSession(
            this.loop,
            this.servers,
            () -> this.preferences,
            Hash16.parseHex("01".repeat(16)),
            () -> 4662,
            new Recorder(),
            () -> false,
            new SecureRandom());
    private ServerSocket listener;
    private Socket socket;
    private OutputStream out;
    private ServerMet.Server target;

    @AfterEach
    void stop() throws IOException {
        if (this.socket != null) {
            this.socket.close();
        }
        if (this.listener != null) {
            this.listener.close();
        }
        this.loop.close();
    }

    @Test
    void shouldIgnoreAnObfuscatedPortBeyondTheRangeAndUseThePlainOne() throws Exception {
        givenObfuscation(ObfuscationMode.REQUESTED);
        givenAServerAnnouncingTheObfuscatedPort(70_000);

        whenTheSessionConnects();

        thenTheServerGetsAPlainLoginRequest();
    }

    @Test
    void shouldDropTheControlCharactersFromTheServer() throws Exception {
        givenAPlainServer();
        givenTheSessionConnected();

        whenTheServerLogsUsInWithTheMessageAndIdentity(
                "\u001b[31mWelcome\u0007\r\nsecond\tline", "Evil\r\n6.6.6.6:4661,0,Injected", "bad\u0000description");

        thenTheNextMessageIs("[31mWelcome");
        thenTheNextMessageIs("secondline");
        thenTheServerIsUpdatedTo("Evil6.6.6.6:4661,0,Injected / baddescription");
    }

    @Test
    void shouldTakeAnIdOfZeroAsNoLoginAndALaterIdAsOne() throws Exception {
        givenAPlainServer();
        givenTheSessionConnected();

        whenTheServerSends(
                new ServerMessages.IdChange(0, 0, 0).encode(), new ServerMessages.IdChange(HIGH_ID, 0, 0).encode());

        thenTheSessionIsLoggedIn();
        thenTheClientIdIs(HIGH_ID);
        thenTheSessionLoggedInOnce();
    }

    @Test
    void shouldEndTheAttemptWhenTheServerHangsUpBeforeTheId() throws Exception {
        givenAPlainServer();
        givenTheSessionConnected();

        whenTheServerHangsUp();

        thenTheSessionIsLost();
        thenNoSessionIsOpen();
        thenTheSessionHasNoServer();
    }

    @Test
    void shouldNeverDialAServerKnownByAHostNameAtNoAddress() throws Exception {
        givenAServerKnownOnlyByTheHostName("localhost");

        whenTheSessionConnects();

        thenTheSessionIsLost();
        thenNoServerIsDialedAt("0.0.0.0");
        thenNoConnectionArrivesWithin(500);
    }

    private void givenObfuscation(ObfuscationMode mode) {
        this.preferences = this.preferences.toBuilder().obfuscation(mode).build();
    }

    private void givenAServerAnnouncingTheObfuscatedPort(int obfuscatedPort) throws IOException {
        this.listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        this.target = new ServerMet.Server(LOOPBACK, this.listener.getLocalPort(), List.of())
                .with(intTag(ServerTag.UDPFLAGS, ServerDatagrams.UDP_TCP_OBFUSCATION))
                .with(intTag(ServerTag.TCPPORTOBFUSCATION, obfuscatedPort));
    }

    private void givenAPlainServer() throws IOException {
        this.listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        this.target = new ServerMet.Server(LOOPBACK, this.listener.getLocalPort(), List.of())
                // Pinged before and known to be plain: no obfuscation is tried first.
                .with(intTag(ServerTag.UDPFLAGS, 0));
    }

    private void givenAServerKnownOnlyByTheHostName(String host) throws IOException {
        this.listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        // localhost resolves without any network, to an address no server may have.
        this.target = new ServerMet.Server(
                0, this.listener.getLocalPort(), List.of(new Tag.StringTag(TagName.id(ServerTag.DYNIP), host)));
    }

    private void givenTheSessionConnected() throws Exception {
        whenTheSessionConnects();
        this.socket = this.listener.accept();
        this.socket.setSoTimeout(10_000);
        InputStream in = new BufferedInputStream(this.socket.getInputStream());
        this.out = this.socket.getOutputStream();
        TcpFraming.read(in, FrameLimits.SERVER);
    }

    private void whenTheSessionConnects() throws Exception {
        onLoop(() -> {
            this.session.connect(Optional.of(this.target));
            return null;
        });
    }

    private void whenTheServerSends(Packet... packets) throws IOException {
        for (Packet packet : packets) {
            this.out.write(TcpFraming.encode(packet));
        }
        this.out.flush();
    }

    private void whenTheServerLogsUsInWithTheMessageAndIdentity(String message, String name, String description)
            throws IOException {
        whenTheServerSends(
                new ServerMessages.IdChange(HIGH_ID, 0, 0).encode(),
                ServerMessages.message(message),
                new ServerMessages.ServerIdent(
                                Hash16.parseHex("02".repeat(16)),
                                LOOPBACK,
                                this.listener.getLocalPort(),
                                Optional.of(name),
                                Optional.of(description))
                        .encode());
    }

    private void whenTheServerHangsUp() throws IOException {
        this.socket.close();
    }

    private void thenTheServerGetsAPlainLoginRequest() throws IOException {
        this.socket = this.listener.accept();
        this.socket.setSoTimeout(10_000);
        Packet login = TcpFraming.read(new BufferedInputStream(this.socket.getInputStream()), FrameLimits.SERVER);
        assertThat(login.opcode()).isEqualTo(Opcodes.Server.LOGINREQUEST.code());
    }

    private void thenTheNextMessageIs(String line) throws InterruptedException {
        assertThat(next("message")).isEqualTo("message " + line);
    }

    private void thenTheServerIsUpdatedTo(String nameAndDescription) throws InterruptedException {
        assertThat(next("updated")).isEqualTo("updated " + nameAndDescription);
    }

    private void thenTheSessionIsLoggedIn() throws InterruptedException {
        assertThat(next("connected")).isEqualTo("connected " + HIGH_ID);
    }

    private void thenTheClientIdIs(long id) throws Exception {
        assertThat(onLoop(() -> this.session.id().map(ServerMessages.IdChange::clientId)))
                .contains(id);
    }

    private void thenTheSessionLoggedInOnce() {
        assertThat(this.seen).filteredOn(e -> e.startsWith("connected")).hasSize(1);
    }

    private void thenTheSessionIsLost() throws InterruptedException {
        assertThat(next("disconnected")).startsWith("disconnected");
    }

    private void thenNoSessionIsOpen() throws Exception {
        assertThat(onLoop(this.session::isConnected)).isFalse();
    }

    private void thenTheSessionHasNoServer() throws Exception {
        assertThat(onLoop(this.session::server)).isEmpty();
    }

    private void thenNoServerIsDialedAt(String ip) {
        assertThat(this.seen).noneMatch(e -> e.startsWith("connecting " + ip));
    }

    private void thenNoConnectionArrivesWithin(int millis) throws IOException {
        this.listener.setSoTimeout(millis);
        assertThat(accepts(this.listener)).isFalse();
    }

    private static boolean accepts(ServerSocket server) {
        try {
            server.accept().close();
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private String next(String kind) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            String event = this.events.poll(100, TimeUnit.MILLISECONDS);
            if (event != null && event.startsWith(kind)) {
                return event;
            }
        }
        throw new AssertionError("no " + kind);
    }

    private <T> T onLoop(Callable<T> task) throws Exception {
        return this.loop.submit(task).get(5, TimeUnit.SECONDS);
    }

    private static Tag intTag(int id, long value) {
        return new Tag.IntTag(TagName.id(id), value);
    }

    private final class Recorder implements ServerSession.Listener {
        private void add(String event) {
            ServerSessionTest.this.seen.add(event);
            ServerSessionTest.this.events.add(event);
        }

        @Override
        public void connecting(ServerMet.Server server) {
            add("connecting " + Ipv4.format(server.ip()));
        }

        @Override
        public void connected(ServerMet.Server server, ServerMessages.IdChange id) {
            add("connected " + id.clientId());
        }

        @Override
        public void disconnected(Optional<ServerMet.Server> server, String reason) {
            add("disconnected " + reason);
        }

        @Override
        public void message(ServerMet.Server server, String line) {
            add("message " + line);
        }

        @Override
        public void updated(ServerMet.Server server) {
            if (server.name().isPresent()) {
                add("updated " + server.name().get() + " / "
                        + server.description().orElse(""));
            }
        }

        @Override
        public void searchResults(ServerMet.Server server, ServerMessages.SearchResults results) {}

        @Override
        public void callbackRequested(ServerMessages.CallbackRequested request) {}

        @Override
        public void foundSources(ServerMessages.FoundSources sources) {}

        @Override
        public void serverList(ServerMet.Server server, List<ServerMessages.Endpoint> servers) {}
    }
}
