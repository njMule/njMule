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
package org.njmule.core.social;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedInputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.SecureRandom;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.njmule.core.api.Hash16;
import org.njmule.core.engine.EngineLoop;
import org.njmule.core.protocol.ed2k.ChatMessages;
import org.njmule.core.protocol.ed2k.Hello;
import org.njmule.core.protocol.packet.FrameLimit;
import org.njmule.core.protocol.packet.Opcodes;
import org.njmule.core.protocol.packet.Packet;
import org.njmule.core.protocol.packet.TcpFraming;

@Timeout(30)
class MessagingTest {

    private static final Hash16 SPAMMER = Hash16.parseHex("5A".repeat(16));
    private static final Hash16 PAL = Hash16.parseHex("0123456789ABCDEF0123456789ABCDEF");
    private static final InetSocketAddress FROM = new InetSocketAddress("10.0.0.9", 50_000);

    private final EngineLoop loop = EngineLoop.start(1024, "test-engine");
    private final List<String> received = new CopyOnWriteArrayList<>();
    private final List<String> failed = new CopyOnWriteArrayList<>();
    private ChatFilter filter = ChatFilter.NONE;
    private final Messaging messaging = new Messaging(
            this.loop,
            () -> Hello.of(Hash16.parseHex("01".repeat(16)), 0, 4662, "us", 0, 0, 0, 0, 0, 0),
            new SecureRandom(),
            new Messaging.Listener() {
                @Override
                public void received(Messaging.Correspondent from, String text) {
                    MessagingTest.this.received.add(text);
                }

                @Override
                public void captcha(Messaging.Correspondent from, byte[] image) {}

                @Override
                public void captchaResult(Messaging.Correspondent from, boolean solved) {}

                @Override
                public void failed(InetSocketAddress to, String reason) {
                    MessagingTest.this.failed.add(to.toString());
                }
            },
            () -> this.filter,
            PAL::equals);

    @AfterEach
    void stop() {
        this.messaging.close();
        this.loop.close();
    }

    @Test
    void aSpammerComingBackFromAnotherPortIsStillFiltered() throws Exception {
        this.filter = new ChatFilter(false, List.of(), true);

        incoming(hello(SPAMMER, 4001), "cheap stuff at http://spam.example", () -> true, p -> {});
        incoming(hello(SPAMMER, 4002), "hello there", () -> true, p -> {});
        incoming(hello(PAL, 4003), "a link from a friend: http://pal.example", () -> true, p -> {});

        assertThat(this.received).containsExactly("a link from a friend: http://pal.example");
    }

    @Test
    void blankTextStartsNoConversation() throws Exception {
        onLoop(() -> {
            this.messaging.send(FROM, " \t ");
            return null;
        });

        assertThat(onLoop(this.messaging::conversations)).isZero();
    }

    @Test
    void textToAClientThatCannotBeReachedIsReportedAndForgotten() throws Exception {
        InetSocketAddress closed;
        try (ServerSocket probe = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            closed = new InetSocketAddress(InetAddress.getLoopbackAddress(), probe.getLocalPort());
        }
        onLoop(() -> {
            this.messaging.send(closed, "anyone?");
            return null;
        });

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (this.failed.isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertThat(this.failed).containsExactly(closed.toString());
        assertThat(onLoop(this.messaging::conversations)).isZero();
    }

    @Test
    void aConversationGoesOnceItsConnectionClosesWithNothingPending() throws Exception {
        AtomicBoolean open = new AtomicBoolean(true);
        incoming(hello(PAL, 4001), "hi", open::get, p -> {});
        assertThat(onLoop(this.messaging::conversations)).isEqualTo(1);

        open.set(false);

        assertThat(onLoop(this.messaging::conversations)).isZero();
        assertThat(this.received).containsExactly("hi");
    }

    @Test
    void ourConnectionClosingKeepsAConversationThatAnIncomingOneServes() throws Exception {
        try (ServerSocket peer = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            InetSocketAddress to = new InetSocketAddress(InetAddress.getLoopbackAddress(), peer.getLocalPort());
            onLoop(() -> {
                this.messaging.send(to, "first");
                return null;
            });
            List<Packet> sent = new CopyOnWriteArrayList<>();
            try (Socket ours = peer.accept()) {
                ours.setSoTimeout(5000);
                assertThat(TcpFraming.read(new BufferedInputStream(ours.getInputStream()), FrameLimit.fixed(1 << 16))
                                .opcode())
                        .isEqualTo(Opcodes.Client.HELLO.code());
                // The client connects to us before answering our hello: the conversation goes over its connection.
                onLoop(() -> this.messaging.incoming(
                        new InetSocketAddress(InetAddress.getLoopbackAddress(), 50_001),
                        hello(PAL, peer.getLocalPort()),
                        true,
                        sent::add,
                        () -> true,
                        ChatMessages.message("hey", true)));
            }
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (System.nanoTime() < deadline) {
                assertThat(onLoop(this.messaging::conversations)).isEqualTo(1);
                Thread.sleep(50);
            }

            onLoop(() -> {
                this.messaging.send(to, "second");
                return null;
            });

            assertThat(sent).hasSize(2);
            assertThat(ChatMessages.text(sent.get(1))).isEqualTo("second");
            assertThat(this.failed).isEmpty();
        }
    }

    private void incoming(Hello peer, String text, BooleanSupplier open, Consumer<Packet> reply) throws Exception {
        onLoop(() -> this.messaging.incoming(FROM, peer, true, reply, open, ChatMessages.message(text, true)));
    }

    private <T> T onLoop(Callable<T> task) throws Exception {
        return this.loop.submit(task).get(5, TimeUnit.SECONDS);
    }

    private static Hello hello(Hash16 hash, int port) {
        return Hello.of(hash, 0, port, "peer", 0, 0, 0, 0, 0, 0);
    }
}
