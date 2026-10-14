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

    @AfterEach
    void stop() {
        this.loop.close();
    }

    @Test
    void anObfuscatedPortBeyondTheRangeIsIgnoredAndThePlainOneUsed() throws Exception {
        this.preferences = this.preferences.toBuilder()
                .obfuscation(ObfuscationMode.REQUESTED)
                .build();
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            ServerMet.Server target = new ServerMet.Server(LOOPBACK, server.getLocalPort(), List.of())
                    .with(intTag(ServerTag.UDPFLAGS, ServerDatagrams.UDP_TCP_OBFUSCATION))
                    .with(intTag(ServerTag.TCPPORTOBFUSCATION, 70_000));

            this.onLoop(() -> {
                this.session.connect(Optional.of(target));
                return null;
            });

            try (Socket socket = server.accept()) {
                socket.setSoTimeout(10_000);
                Packet login = TcpFraming.read(new BufferedInputStream(socket.getInputStream()), FrameLimits.SERVER);
                assertThat(login.opcode()).isEqualTo(Opcodes.Server.LOGINREQUEST.code());
            }
        }
    }

    @Test
    void controlCharactersFromTheServerAreDropped() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            ServerMet.Server target = new ServerMet.Server(LOOPBACK, server.getLocalPort(), List.of())
                    // Pinged before and known to be plain: no obfuscation is tried first.
                    .with(intTag(ServerTag.UDPFLAGS, 0));
            this.onLoop(() -> {
                this.session.connect(Optional.of(target));
                return null;
            });
            try (Socket socket = server.accept()) {
                socket.setSoTimeout(10_000);
                InputStream in = new BufferedInputStream(socket.getInputStream());
                OutputStream out = socket.getOutputStream();
                TcpFraming.read(in, FrameLimits.SERVER);
                out.write(TcpFraming.encode(new ServerMessages.IdChange(HIGH_ID, 0, 0).encode()));
                out.write(TcpFraming.encode(ServerMessages.message("\u001b[31mWelcome\u0007\r\nsecond\tline")));
                out.write(TcpFraming.encode(new ServerMessages.ServerIdent(
                                Hash16.parseHex("02".repeat(16)),
                                LOOPBACK,
                                server.getLocalPort(),
                                Optional.of("Evil\r\n6.6.6.6:4661,0,Injected"),
                                Optional.of("bad\u0000description"))
                        .encode()));
                out.flush();

                assertThat(this.next("message")).isEqualTo("message [31mWelcome");
                assertThat(this.next("message")).isEqualTo("message secondline");
                assertThat(this.next("updated")).isEqualTo("updated Evil6.6.6.6:4661,0,Injected / baddescription");
            }
        }
    }

    @Test
    void anIdOfZeroIsNoLoginAndALaterIdIs() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            ServerMet.Server target = new ServerMet.Server(LOOPBACK, server.getLocalPort(), List.of())
                    // Pinged before and known to be plain: no obfuscation is tried first.
                    .with(intTag(ServerTag.UDPFLAGS, 0));
            this.onLoop(() -> {
                this.session.connect(Optional.of(target));
                return null;
            });
            try (Socket socket = server.accept()) {
                socket.setSoTimeout(10_000);
                InputStream in = new BufferedInputStream(socket.getInputStream());
                OutputStream out = socket.getOutputStream();
                TcpFraming.read(in, FrameLimits.SERVER);
                out.write(TcpFraming.encode(new ServerMessages.IdChange(0, 0, 0).encode()));
                out.write(TcpFraming.encode(new ServerMessages.IdChange(HIGH_ID, 0, 0).encode()));
                out.flush();

                assertThat(this.next("connected")).isEqualTo("connected " + HIGH_ID);
                assertThat(this.onLoop(() -> this.session.id().map(ServerMessages.IdChange::clientId)))
                        .contains(HIGH_ID);
                assertThat(this.seen).filteredOn(e -> e.startsWith("connected")).hasSize(1);
            }
        }
    }

    @Test
    void aServerHangingUpBeforeTheIdEndsTheAttempt() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            ServerMet.Server target = new ServerMet.Server(LOOPBACK, server.getLocalPort(), List.of())
                    // Pinged before and known to be plain: no obfuscation is tried first.
                    .with(intTag(ServerTag.UDPFLAGS, 0));
            this.onLoop(() -> {
                this.session.connect(Optional.of(target));
                return null;
            });
            try (Socket socket = server.accept()) {
                TcpFraming.read(new BufferedInputStream(socket.getInputStream()), FrameLimits.SERVER);
            }

            assertThat(this.next("disconnected")).startsWith("disconnected");
            assertThat(this.onLoop(this.session::isConnected)).isFalse();
            assertThat(this.onLoop(this.session::server)).isEmpty();
        }
    }

    @Test
    void aServerKnownByAHostNameIsNeverDialedAtNoAddress() throws Exception {
        try (ServerSocket trap = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            // localhost resolves without any network, to an address no server may have.
            ServerMet.Server named = new ServerMet.Server(
                    0, trap.getLocalPort(), List.of(new Tag.StringTag(TagName.id(ServerTag.DYNIP), "localhost")));

            this.onLoop(() -> {
                this.session.connect(Optional.of(named));
                return null;
            });

            assertThat(this.next("disconnected")).startsWith("disconnected");
            assertThat(this.seen).noneMatch(e -> e.startsWith("connecting 0.0.0.0"));
            trap.setSoTimeout(500);
            assertThat(accepts(trap)).isFalse();
        }
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
            if (server.name().isPresent()) {
                this.add("updated " + server.name().get() + " / "
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
