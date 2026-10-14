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
            new SecureRandom());

    @AfterEach
    void stop() throws Exception {
        this.onLoop(() -> {
            this.session.disconnect();
            return null;
        });
        this.loop.close();
    }

    @Test
    void serversThatCannotBeReachedAreTriedOneAfterTheOther() throws Exception {
        // Neither is looked up: a malformed address and one with no IPv4 form.
        ServerMet.Server malformed = named("[bad", 4661);
        ServerMet.Server ipv6 = named("::1", 4662);
        this.servers.add(malformed);
        this.servers.add(ipv6);

        this.onLoop(() -> {
            this.session.connect(Optional.empty());
            return null;
        });

        assertThat(this.next("disconnected")).contains("no IPv4 address");
        assertThat(this.servers.all())
                .as("each failure counted")
                .allSatisfy(s -> assertThat(s.failedCount()).hasValue(1));
        assertThat(this.seen).noneMatch(e -> e.startsWith("connecting"));
    }

    @Test
    void serversThatCannotBeUsedAreSkippedAndOnlyStaticOnesTriedWhenAsked() throws Exception {
        this.preferences = this.preferences.toBuilder()
                .obfuscation(ObfuscationMode.REQUIRED)
                .staticServersOnly(true)
                .build();
        ServerMet.Server plain = named("[bad", 4661);
        ServerMet.Server notStatic = named("[bad2", 4661);
        this.servers.add(plain);
        this.servers.add(notStatic);
        this.servers.setStatic(ServerList.addressOf(plain), true);

        this.onLoop(() -> {
            this.session.connect(Optional.empty());
            return null;
        });

        assertThat(this.next("disconnected")).isEqualTo("disconnected no server accepted the connection");
        assertThat(this.servers.all())
                .allSatisfy(s -> assertThat(s.failedCount()).isEmpty());

        this.onLoop(() -> {
            this.session.connect(Optional.of(new ServerMet.Server(Ipv4.parse("127.0.0.1"), 0, List.of())));
            return null;
        });
        assertThat(this.next("disconnected"))
                .as("no port to connect to")
                .isEqualTo("disconnected no server accepted the connection");
        assertThat(this.seen).noneMatch(e -> e.startsWith("connecting"));
    }

    @Test
    void aServerWithoutAnAddressOrThatRefusesFails() throws Exception {
        ServerMet.Server nowhere = new ServerMet.Server(Ipv4.parse("0.0.0.5"), 4661, List.of());
        this.onLoop(() -> {
            this.session.connect(Optional.of(nowhere));
            return null;
        });
        assertThat(this.next("disconnected")).isEqualTo("disconnected no address to connect to");

        int closed;
        try (ServerSocket probe = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            closed = probe.getLocalPort();
        }
        this.onLoop(() -> {
            this.session.connect(Optional.of(new ServerMet.Server(Ipv4.parse("127.0.0.1"), closed, List.of())));
            return null;
        });
        assertThat(this.next("connecting")).isEqualTo("connecting 127.0.0.1");
        assertThat(this.next("disconnected")).startsWith("disconnected");
        assertThat(this.onLoop(this.session::isConnected)).isFalse();
    }

    @Test
    void aServerThatMustObfuscateIsDialedOnItsObfuscatedPort() throws Exception {
        this.preferences = this.preferences.toBuilder()
                .obfuscation(ObfuscationMode.REQUIRED)
                .build();
        try (ServerSocket obfuscated = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            ServerMet.Server target = new ServerMet.Server(Ipv4.parse("127.0.0.1"), 9, List.of())
                    .with(new Tag.IntTag(TagName.id(ServerTag.UDPFLAGS), ServerDatagrams.UDP_TCP_OBFUSCATION))
                    .with(new Tag.IntTag(TagName.id(ServerTag.TCPPORTOBFUSCATION), obfuscated.getLocalPort()));

            this.onLoop(() -> {
                this.session.connect(Optional.of(target));
                return null;
            });

            try (Socket socket = obfuscated.accept()) {
                socket.setSoTimeout(10_000);
                InputStream in = socket.getInputStream();
                int first = in.read();
                assertThat(first).isNotNegative();
                assertThat(Protocol.isTcp(first)).as("not a plain frame").isFalse();
            }
            assertThat(this.next("disconnected")).startsWith("disconnected");
        }
    }

    @Test
    void whatTheServerSaysIsTakenAndWhatItCannotSayIsDropped() throws Exception {
        try (ScriptedServer server = ScriptedServer.start(HIGH_ID, 0)) {
            this.onLoop(() -> {
                this.session.connect(Optional.of(new ServerMet.Server(server.ip(), server.port(), List.of())));
                return null;
            });
            assertThat(this.next("connected")).isEqualTo("connected " + HIGH_ID);
            assertThat(this.onLoop(this.session::isObfuscated)).isFalse();
            assertThat(this.onLoop(() -> this.session.send(ServerMessages.getServerList())))
                    .isTrue();
            assertThat(server.next(Opcodes.Server.GETSERVERLIST.code(), Duration.ofSeconds(10)))
                    .isPresent();

            server.send(
                    Packet.of(Protocol.EDONKEY, 0xEE, new byte[1]),
                    Packet.empty(Protocol.EDONKEY, Opcodes.Server.REJECT.code()),
                    Packet.of(Protocol.EDONKEY, Opcodes.Server.LOGINREQUEST.code(), new byte[1]),
                    Packet.of(Protocol.EDONKEY, Opcodes.Server.CALLBACKREQUESTED.code(), new byte[2]),
                    ServerMessages.message("first\n \nsecond"),
                    ServerMessages.serverList(List.of(new ServerMessages.Endpoint(Ipv4.parse("198.51.100.1"), 4661))),
                    new ServerMessages.ServerStatus(7, 8).encode());

            assertThat(this.next("message")).isEqualTo("message first");
            assertThat(this.next("message")).as("the blank line is left out").isEqualTo("message second");
            assertThat(this.next("servers")).isEqualTo("servers 1");
            assertThat(this.next("status")).isEqualTo("status 7/8");

            server.hangUp();

            assertThat(this.next("disconnected")).startsWith("disconnected");
            assertThat(this.onLoop(this.session::server)).isEmpty();
            assertThat(this.onLoop(() -> this.session.send(ServerMessages.getServerList())))
                    .as("no session")
                    .isFalse();
        }
    }

    @Test
    void theCapabilitiesSayHowMuchObfuscationWeWant() {
        int base = ServerMessages.CAP_ZLIB
                | ServerMessages.CAP_NEWTAGS
                | ServerMessages.CAP_UNICODE
                | ServerMessages.CAP_LARGEFILES;

        assertThat(ServerSession.capabilities(ObfuscationMode.DISABLED)).isEqualTo(base);
        assertThat(ServerSession.capabilities(ObfuscationMode.SUPPORTED))
                .isEqualTo(base | ServerMessages.CAP_SUPPORTS_CRYPT);
        assertThat(ServerSession.capabilities(ObfuscationMode.REQUESTED))
                .isEqualTo(base | ServerMessages.CAP_SUPPORTS_CRYPT | ServerMessages.CAP_REQUESTS_CRYPT);
        assertThat(ServerSession.capabilities(ObfuscationMode.REQUIRED))
                .isEqualTo(base
                        | ServerMessages.CAP_SUPPORTS_CRYPT
                        | ServerMessages.CAP_REQUESTS_CRYPT
                        | ServerMessages.CAP_REQUIRES_CRYPT);
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
            this.add("connecting " + Ipv4.format(server.ip()));
        }

        @Override
        public void connected(ServerMet.Server server, ServerMessages.IdChange id) {
            this.add("connected " + id.clientId());
        }

        @Override
        public void disconnected(Optional<ServerMet.Server> server, String reason) {
            this.add("disconnected " + reason);
        }

        @Override
        public void message(ServerMet.Server server, String line) {
            this.add("message " + line);
        }

        @Override
        public void updated(ServerMet.Server server) {
            if (server.users().isPresent()) {
                this.add("status " + server.users().getAsLong() + "/"
                        + server.files().orElseThrow());
            }
        }

        @Override
        public void searchResults(ServerMet.Server server, ServerMessages.SearchResults results) {
            this.add("results " + results.files().size());
        }

        @Override
        public void callbackRequested(ServerMessages.CallbackRequested request) {
            this.add("callback");
        }

        @Override
        public void foundSources(ServerMessages.FoundSources sources) {
            this.add("sources");
        }

        @Override
        public void serverList(ServerMet.Server server, List<ServerMessages.Endpoint> servers) {
            this.add("servers " + servers.size());
        }
    }
}
