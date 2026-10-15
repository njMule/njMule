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
import org.njmule.core.config.Preferences;
import org.njmule.core.engine.EngineLoop;
import org.njmule.core.persist.format.ServerMet;
import org.njmule.core.protocol.ed2k.ServerMessages;
import org.njmule.core.protocol.io.PacketWriter;
import org.njmule.core.protocol.packet.Opcodes;
import org.njmule.core.protocol.packet.Packet;
import org.njmule.core.protocol.packet.Protocol;
import org.njmule.core.protocol.tag.ServerTag;
import org.njmule.core.protocol.tag.Tag;
import org.njmule.core.protocol.tag.TagName;
import org.njmule.core.util.Ipv4;

/** What a logged-in session makes of the server's answers; a scripted server on loopback. */
@Timeout(30)
class ServerAnswersTest {

    private static final long HIGH_ID = Ipv4.toEd2k(Ipv4.parse("1.2.3.4"));

    private final EngineLoop loop = EngineLoop.start(1024, "test-engine");
    private final BlockingQueue<String> events = new LinkedBlockingQueue<>();
    private final List<String> seen = new CopyOnWriteArrayList<>();
    private final ServerSession session = new ServerSession(
            this.loop,
            new ServerList(),
            Preferences::defaults,
            Hash16.parseHex("01".repeat(16)),
            () -> 4662,
            new Recorder(),
            () -> false,
            new SecureRandom());
    private ScriptedServer server;

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
    }

    @Test
    void aSearchAnswerThatCannotBeReadStillAnswersTheSearch() throws Exception {
        givenALoggedInSession();

        // One result announcing five tags, and one byte for them.
        whenTheServerSends(Packet.of(
                Protocol.EDONKEY,
                Opcodes.Server.SEARCHRESULT.code(),
                new PacketWriter().u32(1).bytes(new byte[16 + 4 + 2]).u32(5).u8(0x02)));

        thenTheSearchIsAnsweredWith(0);
    }

    @Test
    void aPacketThatDoesNotInflateIsDroppedAndTheSessionGoesOn() throws Exception {
        givenALoggedInSession();

        whenTheServerSends(
                Packet.of(Protocol.PACKED, Opcodes.Server.SERVERMESSAGE.code(), new byte[] {1, 2, 3, 4}),
                ServerMessages.message("still here"));

        thenTheServerSays("still here");
        thenTheSessionIsOpen();
    }

    private void givenALoggedInSession() throws Exception {
        this.server = ScriptedServer.start(HIGH_ID, 0);
        // A server known to be plain: no obfuscation is tried first.
        ServerMet.Server target = new ServerMet.Server(this.server.ip(), this.server.port(), List.of())
                .with(new Tag.IntTag(TagName.id(ServerTag.UDPFLAGS), 0));
        onLoop(() -> {
            this.session.connect(Optional.of(target));
            return null;
        });
        assertThat(next("connected")).isEqualTo("connected " + HIGH_ID);
    }

    private void whenTheServerSends(Packet... packets) throws Exception {
        this.server.send(packets);
    }

    private void thenTheSearchIsAnsweredWith(int results) throws InterruptedException {
        assertThat(next("results")).isEqualTo("results " + results);
    }

    private void thenTheServerSays(String line) throws InterruptedException {
        assertThat(next("message")).isEqualTo("message " + line);
    }

    private void thenTheSessionIsOpen() throws Exception {
        assertThat(onLoop(this.session::isConnected)).isTrue();
        assertThat(this.seen).noneMatch(e -> e.startsWith("disconnected"));
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
            ServerAnswersTest.this.seen.add(event);
            ServerAnswersTest.this.events.add(event);
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
        public void updated(ServerMet.Server server) {}

        @Override
        public void searchResults(ServerMet.Server server, ServerMessages.SearchResults results) {
            add("results " + results.files().size());
        }

        @Override
        public void callbackRequested(ServerMessages.CallbackRequested request) {}

        @Override
        public void foundSources(ServerMessages.FoundSources sources) {}

        @Override
        public void serverList(ServerMet.Server server, List<ServerMessages.Endpoint> servers) {}
    }
}
