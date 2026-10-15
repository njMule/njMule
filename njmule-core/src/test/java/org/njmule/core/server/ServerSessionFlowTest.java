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
import static org.junit.jupiter.params.provider.Arguments.arguments;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.njmule.core.api.Hash16;
import org.njmule.core.config.ObfuscationMode;
import org.njmule.core.config.Preferences;
import org.njmule.core.engine.EngineLoop;
import org.njmule.core.persist.format.ServerMet;
import org.njmule.core.protocol.ed2k.ServerDatagrams;
import org.njmule.core.protocol.ed2k.ServerMessages;
import org.njmule.core.protocol.packet.Opcodes;
import org.njmule.core.protocol.packet.Packet;
import org.njmule.core.protocol.packet.Protocol;
import org.njmule.core.protocol.tag.ServerTag;
import org.njmule.core.protocol.tag.Tag;
import org.njmule.core.protocol.tag.TagName;
import org.njmule.core.util.Ipv4;

/** A session through its connection attempts and with a server a test scripts; loopback only. */
@Timeout(30)
class ServerSessionFlowTest {

    private static final long HIGH_ID = Ipv4.toEd2k(Ipv4.parse("1.2.3.4"));
    private static final long OTHER_ID = Ipv4.toEd2k(Ipv4.parse("5.6.7.8"));

    private final EngineLoop loop = EngineLoop.start(1024, "test-engine");
    private final ServerList servers = new ServerList();
    private final BlockingQueue<String> events = new LinkedBlockingQueue<>();
    private final List<String> seen = new CopyOnWriteArrayList<>();
    private volatile Preferences preferences = Preferences.defaults();
    private final ServerSession session = new ServerSession(
            this.loop,
            this.servers,
            () -> this.preferences,
            Hash16.parseHex("01".repeat(16)),
            () -> 4662,
            new Recorder(),
            () -> false,
            new SecureRandom());
    private ScriptedServer server;
    private ServerSocket listener;
    private ServerMet.Server target;
    private int capabilities;

    @AfterEach
    void stop() throws Exception {
        onLoop(() -> {
            this.session.disconnect();
            return null;
        });
        this.loop.close();
        if (this.server != null) {
            this.server.close();
        }
        if (this.listener != null) {
            this.listener.close();
        }
    }

    @Test
    void shouldTryServersThatCannotBeReachedOneAfterTheOther() throws Exception {
        // Neither is looked up: a malformed address and one with no IPv4 form.
        givenTheServers(named("[bad", 4661), named("::1", 4662));

        whenTheSessionLooksForAnyServer();

        thenTheSessionGivesUpBecause("no server accepted the connection");
        thenEveryFailureIsCounted();
        thenNoServerIsDialed();
    }

    @Test
    void shouldSkipServersThatCannotBeUsedAndTryOnlyStaticOnesWhenAsked() throws Exception {
        givenObfuscation(ObfuscationMode.REQUIRED);
        givenStaticServersOnly();
        givenTheServers(named("[bad", 4661), named("[bad2", 4661));
        givenTheServerIsStatic(named("[bad", 4661));

        whenTheSessionLooksForAnyServer();

        thenTheSessionGivesUpBecause("no server accepted the connection");
        thenNoFailureIsCounted();
    }

    @Test
    void shouldNotDialAServerWithoutAPort() throws Exception {
        givenObfuscation(ObfuscationMode.REQUIRED);
        givenStaticServersOnly();
        givenTheServers(named("[bad", 4661), named("[bad2", 4661));
        givenTheServerIsStatic(named("[bad", 4661));
        givenTheSessionGaveUpLookingForAnyServer();

        whenTheSessionConnectsTo(new ServerMet.Server(Ipv4.parse("127.0.0.1"), 0, List.of()));

        thenTheSessionGivesUpBecause("no server accepted the connection", "no port to connect to");
        thenNoServerIsDialed();
    }

    @Test
    void shouldFailToReachAServerWithoutAnAddress() throws Exception {
        whenTheSessionConnectsTo(new ServerMet.Server(Ipv4.parse("0.0.0.5"), 4661, List.of()));

        thenTheSessionGivesUpBecause("no address to connect to");
    }

    @Test
    void shouldFailToReachAServerThatRefuses() throws Exception {
        givenAServerThatRefusesConnections();

        whenTheSessionConnectsToTheTarget();

        thenTheSessionDials("127.0.0.1");
        thenTheSessionIsLost();
        thenNoSessionIsOpen();
    }

    @Test
    void shouldDialAServerThatMustObfuscateOnItsObfuscatedPort() throws Exception {
        givenObfuscation(ObfuscationMode.REQUIRED);
        givenAServerListeningOnlyOnItsObfuscatedPort();

        whenTheSessionConnectsToTheTarget();

        thenTheObfuscatedPortGetsNoPlainFrame();
        thenTheSessionIsLost();
    }

    @Test
    void shouldTakeWhatTheServerSaysAndDropWhatItCannotSay() throws Exception {
        givenAScriptedServer();
        givenTheSessionLoggedIn();
        givenTheSessionIsNotObfuscated();
        givenTheServerListWasAskedFor();

        whenTheServerSends(
                Packet.of(Protocol.EDONKEY, 0xEE, new byte[1]),
                Packet.empty(Protocol.EDONKEY, Opcodes.Server.REJECT.code()),
                Packet.of(Protocol.EDONKEY, Opcodes.Server.LOGINREQUEST.code(), new byte[1]),
                Packet.of(Protocol.EDONKEY, Opcodes.Server.CALLBACKREQUESTED.code(), new byte[2]),
                ServerMessages.message("first\n \nsecond"),
                ServerMessages.serverList(List.of(new ServerMessages.Endpoint(Ipv4.parse("198.51.100.1"), 4661))),
                new ServerMessages.ServerStatus(7, 8).encode());

        thenTheNextMessageIs("first");
        thenTheNextMessageIs("second", "the blank line is left out");
        thenTheServerListHolds(1);
        thenTheServerStatusIs("7/8");
    }

    @Test
    void shouldEndTheSessionWhenTheServerHangsUp() throws Exception {
        givenAScriptedServer();
        givenTheSessionLoggedIn();
        givenTheSessionIsNotObfuscated();
        givenTheServerListWasAskedFor();

        whenTheServerHangsUp();

        thenTheSessionIsLost();
        thenTheSessionHasNoServer();
        thenNothingCanBeSent("no session");
    }

    @Test
    void shouldDialAServerThatCanObfuscatePlainlyWhenObfuscationIsOnlySupported() throws Exception {
        givenObfuscation(ObfuscationMode.SUPPORTED);
        givenAScriptedServerAnnouncingObfuscationOnPort(9);

        whenTheSessionConnectsToTheTarget();

        thenTheSessionIsLoggedIn();
    }

    @Test
    void shouldTakeANewIdWhileLoggedIn() throws Exception {
        givenObfuscation(ObfuscationMode.SUPPORTED);
        givenAScriptedServerAnnouncingObfuscationOnPort(9);
        givenTheSessionLoggedIn();

        whenTheServerSendsANewIdThenItsStatus(OTHER_ID, 3, 4);

        thenTheServerStatusIs("3/4");
        thenTheClientIdIs(OTHER_ID, "a new id while logged in");
        thenTheSessionLoggedInOnce();
    }

    @ParameterizedTest
    @MethodSource("capabilitiesByObfuscation")
    void shouldTellInTheCapabilitiesHowMuchObfuscationWeWant(ObfuscationMode mode, int expected) {
        whenTheCapabilitiesAreTakenFor(mode);

        thenTheCapabilitiesAre(expected);
    }

    static Stream<Arguments> capabilitiesByObfuscation() {
        int base = ServerMessages.CAP_ZLIB
                | ServerMessages.CAP_NEWTAGS
                | ServerMessages.CAP_UNICODE
                | ServerMessages.CAP_LARGEFILES;
        return Stream.of(
                arguments(ObfuscationMode.DISABLED, base),
                arguments(ObfuscationMode.SUPPORTED, base | ServerMessages.CAP_SUPPORTS_CRYPT),
                arguments(
                        ObfuscationMode.REQUESTED,
                        base | ServerMessages.CAP_SUPPORTS_CRYPT | ServerMessages.CAP_REQUESTS_CRYPT),
                arguments(
                        ObfuscationMode.REQUIRED,
                        base
                                | ServerMessages.CAP_SUPPORTS_CRYPT
                                | ServerMessages.CAP_REQUESTS_CRYPT
                                | ServerMessages.CAP_REQUIRES_CRYPT));
    }

    private void givenObfuscation(ObfuscationMode mode) {
        this.preferences = this.preferences.toBuilder().obfuscation(mode).build();
    }

    private void givenStaticServersOnly() {
        this.preferences = this.preferences.toBuilder().staticServersOnly(true).build();
    }

    private void givenTheServers(ServerMet.Server... list) {
        for (ServerMet.Server known : list) {
            this.servers.add(known);
        }
    }

    private void givenTheServerIsStatic(ServerMet.Server known) {
        this.servers.setStatic(ServerList.addressOf(known), true);
    }

    private void givenTheSessionGaveUpLookingForAnyServer() throws Exception {
        whenTheSessionLooksForAnyServer();
        thenTheSessionGivesUpBecause("no server accepted the connection");
    }

    private void givenAServerThatRefusesConnections() throws IOException {
        int closed;
        try (ServerSocket probe = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            closed = probe.getLocalPort();
        }
        this.target = new ServerMet.Server(Ipv4.parse("127.0.0.1"), closed, List.of());
    }

    private void givenAServerListeningOnlyOnItsObfuscatedPort() throws IOException {
        this.listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        this.target = new ServerMet.Server(Ipv4.parse("127.0.0.1"), 9, List.of())
                .with(new Tag.IntTag(TagName.id(ServerTag.UDPFLAGS), ServerDatagrams.UDP_TCP_OBFUSCATION))
                .with(new Tag.IntTag(TagName.id(ServerTag.TCPPORTOBFUSCATION), this.listener.getLocalPort()));
    }

    private void givenAScriptedServer() throws IOException {
        this.server = ScriptedServer.start(HIGH_ID, 0);
        this.target = new ServerMet.Server(this.server.ip(), this.server.port(), List.of());
    }

    private void givenAScriptedServerAnnouncingObfuscationOnPort(int obfuscatedPort) throws IOException {
        this.server = ScriptedServer.start(HIGH_ID, 0);
        this.target = new ServerMet.Server(this.server.ip(), this.server.port(), List.of())
                .with(new Tag.IntTag(TagName.id(ServerTag.UDPFLAGS), ServerDatagrams.UDP_TCP_OBFUSCATION))
                .with(new Tag.IntTag(TagName.id(ServerTag.TCPPORTOBFUSCATION), obfuscatedPort));
    }

    private void givenTheSessionLoggedIn() throws Exception {
        whenTheSessionConnectsToTheTarget();
        thenTheSessionIsLoggedIn();
    }

    private void givenTheSessionIsNotObfuscated() throws Exception {
        assertThat(onLoop(this.session::isObfuscated)).isFalse();
    }

    private void givenTheServerListWasAskedFor() throws Exception {
        assertThat(onLoop(() -> this.session.send(ServerMessages.getServerList())))
                .isTrue();
        assertThat(this.server.next(Opcodes.Server.GETSERVERLIST.code(), Duration.ofSeconds(10)))
                .isPresent();
    }

    private void whenTheSessionLooksForAnyServer() throws Exception {
        onLoop(() -> {
            this.session.connect(Optional.empty());
            return null;
        });
    }

    private void whenTheSessionConnectsTo(ServerMet.Server known) throws Exception {
        onLoop(() -> {
            this.session.connect(Optional.of(known));
            return null;
        });
    }

    private void whenTheSessionConnectsToTheTarget() throws Exception {
        whenTheSessionConnectsTo(this.target);
    }

    private void whenTheServerSends(Packet... packets) throws Exception {
        this.server.send(packets);
    }

    private void whenTheServerSendsANewIdThenItsStatus(long id, long users, long files) throws Exception {
        this.server.send(
                new ServerMessages.IdChange(id, 0, 0).encode(),
                new ServerMessages.ServerIdent(
                                Hash16.parseHex("02".repeat(16)),
                                this.server.ip(),
                                this.server.port(),
                                Optional.empty(),
                                Optional.empty())
                        .encode(),
                new ServerMessages.ServerStatus(users, files).encode());
    }

    private void whenTheServerHangsUp() throws IOException {
        this.server.hangUp();
    }

    private void whenTheCapabilitiesAreTakenFor(ObfuscationMode mode) {
        this.capabilities = ServerSession.capabilities(mode);
    }

    private void thenTheSessionGivesUpBecause(String reason) throws InterruptedException {
        assertThat(next("disconnected")).isEqualTo("disconnected " + reason);
    }

    private void thenTheSessionGivesUpBecause(String reason, String why) throws InterruptedException {
        assertThat(next("disconnected")).as(why).isEqualTo("disconnected " + reason);
    }

    private void thenEveryFailureIsCounted() {
        assertThat(this.servers.all())
                .as("each failure counted")
                .allSatisfy(s -> assertThat(s.failedCount()).hasValue(1));
    }

    private void thenNoFailureIsCounted() {
        assertThat(this.servers.all())
                .allSatisfy(s -> assertThat(s.failedCount()).isEmpty());
    }

    private void thenNoServerIsDialed() {
        assertThat(this.seen).noneMatch(e -> e.startsWith("connecting"));
    }

    private void thenTheSessionDials(String ip) throws InterruptedException {
        assertThat(next("connecting")).isEqualTo("connecting " + ip);
    }

    private void thenTheSessionIsLost() throws InterruptedException {
        assertThat(next("disconnected")).startsWith("disconnected");
    }

    private void thenNoSessionIsOpen() throws Exception {
        assertThat(onLoop(this.session::isConnected)).isFalse();
    }

    private void thenTheObfuscatedPortGetsNoPlainFrame() throws IOException {
        try (Socket socket = this.listener.accept()) {
            socket.setSoTimeout(10_000);
            InputStream in = socket.getInputStream();
            int first = in.read();
            assertThat(first).isNotNegative();
            assertThat(Protocol.isTcp(first)).as("not a plain frame").isFalse();
        }
    }

    private void thenTheSessionIsLoggedIn() throws InterruptedException {
        assertThat(next("connected")).isEqualTo("connected " + HIGH_ID);
    }

    private void thenTheNextMessageIs(String line) throws InterruptedException {
        assertThat(next("message")).isEqualTo("message " + line);
    }

    private void thenTheNextMessageIs(String line, String why) throws InterruptedException {
        assertThat(next("message")).as(why).isEqualTo("message " + line);
    }

    private void thenTheServerListHolds(int count) throws InterruptedException {
        assertThat(next("servers")).isEqualTo("servers " + count);
    }

    private void thenTheServerStatusIs(String usersAndFiles) throws InterruptedException {
        assertThat(next("status")).isEqualTo("status " + usersAndFiles);
    }

    private void thenTheSessionHasNoServer() throws Exception {
        assertThat(onLoop(this.session::server)).isEmpty();
    }

    private void thenNothingCanBeSent(String why) throws Exception {
        assertThat(onLoop(() -> this.session.send(ServerMessages.getServerList())))
                .as(why)
                .isFalse();
    }

    private void thenTheClientIdIs(long id, String why) throws Exception {
        assertThat(onLoop(() -> this.session.id().orElseThrow().clientId()))
                .as(why)
                .isEqualTo(id);
    }

    private void thenTheSessionLoggedInOnce() {
        assertThat(this.seen).filteredOn(e -> e.startsWith("connected")).hasSize(1);
    }

    private void thenTheCapabilitiesAre(int expected) {
        assertThat(this.capabilities).isEqualTo(expected);
    }

    private static ServerMet.Server named(String host, int port) {
        return new ServerMet.Server(0, port, List.of(new Tag.StringTag(TagName.id(ServerTag.DYNIP), host)));
    }

    private String next(String kind) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            String event = this.events.poll(100, TimeUnit.MILLISECONDS);
            if (event != null && event.startsWith(kind)) {
                return event;
            }
        }
        throw new AssertionError("no " + kind + " in " + this.seen);
    }

    private <T> T onLoop(Callable<T> task) throws Exception {
        return this.loop.submit(task).get(5, TimeUnit.SECONDS);
    }

    private final class Recorder implements ServerSession.Listener {
        private void add(String event) {
            ServerSessionFlowTest.this.seen.add(event);
            ServerSessionFlowTest.this.events.add(event);
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
            if (server.users().isPresent()) {
                add("status " + server.users().getAsLong() + "/"
                        + server.files().orElseThrow());
            }
        }

        @Override
        public void searchResults(ServerMet.Server server, ServerMessages.SearchResults results) {
            add("results " + results.files().size());
        }

        @Override
        public void callbackRequested(ServerMessages.CallbackRequested request) {
            add("callback");
        }

        @Override
        public void foundSources(ServerMessages.FoundSources sources) {
            add("sources");
        }

        @Override
        public void serverList(ServerMet.Server server, List<ServerMessages.Endpoint> servers) {
            add("servers " + servers.size());
        }
    }
}
