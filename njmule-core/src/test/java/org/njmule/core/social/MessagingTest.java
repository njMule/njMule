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
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.SecureRandom;
import java.time.Duration;
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

    private final List<Packet> sent = new CopyOnWriteArrayList<>();
    private final AtomicBoolean open = new AtomicBoolean(true);
    private InetSocketAddress to;
    private ServerSocket peer;
    private Socket ours;

    @AfterEach
    void stop() throws IOException {
        this.messaging.close();
        this.loop.close();
        if (this.ours != null) {
            this.ours.close();
        }
        if (this.peer != null) {
            this.peer.close();
        }
    }

    @Test
    void shouldStillFilterASpammerComingBackFromAnotherPort() throws Exception {
        givenTheChatFilter(new ChatFilter(false, List.of(), true));
        givenAMessageFrom(SPAMMER, 4001, "cheap stuff at http://spam.example");

        whenAMessageComesFrom(SPAMMER, 4002, "hello there");

        thenTheMessagesReceivedAre();
    }

    @Test
    void shouldLetALinkFromAFriendThroughWhileTheSpammerIsFiltered() throws Exception {
        givenTheChatFilter(new ChatFilter(false, List.of(), true));
        givenAMessageFrom(SPAMMER, 4001, "cheap stuff at http://spam.example");
        givenAMessageFrom(SPAMMER, 4002, "hello there");

        whenAMessageComesFrom(PAL, 4003, "a link from a friend: http://pal.example");

        thenTheMessagesReceivedAre("a link from a friend: http://pal.example");
    }

    @Test
    void shouldStartNoConversationForABlankText() throws Exception {
        givenACorrespondentAt(FROM);

        whenWeSend(" \t ");

        thenThereAreConversations(0);
    }

    @Test
    void shouldReportAndForgetATextToAClientThatCannotBeReached() throws Exception {
        givenACorrespondentNobodyListensFor();

        whenWeSend("anyone?");

        thenTheFailureToReachItIsReported();
        thenThereAreConversations(0);
    }

    @Test
    void shouldEndAConversationOnceItsConnectionClosesWithNothingPending() throws Exception {
        givenAMessageFrom(PAL, 4001, "hi");
        givenThereAreConversations(1);

        whenTheConnectionCloses();

        thenThereAreConversations(0);
        thenTheMessagesReceivedAre("hi");
    }

    @Test
    void shouldKeepAConversationThatAnIncomingConnectionServesWhenOursCloses() throws Exception {
        givenACorrespondentListening();
        givenWeSent("first");
        givenItAcceptedOurConnectionAndReadOurHello();
        givenItConnectedToUsWith("hey");
        givenOurConnectionClosed();
        givenTheConversationKeptFor(Duration.ofSeconds(2));

        whenWeSend("second");

        thenThePacketsSentOverItsConnectionAre(2);
        thenTheSecondOfThemCarries("second");
        thenNoFailureIsReported();
    }

    private void givenTheChatFilter(ChatFilter chatFilter) {
        this.filter = chatFilter;
    }

    private void givenAMessageFrom(Hash16 hash, int port, String text) throws Exception {
        incoming(hello(hash, port), text, this.open::get, p -> {});
    }

    private void givenACorrespondentAt(InetSocketAddress address) {
        this.to = address;
    }

    private void givenACorrespondentNobodyListensFor() throws IOException {
        try (ServerSocket probe = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            this.to = new InetSocketAddress(InetAddress.getLoopbackAddress(), probe.getLocalPort());
        }
    }

    private void givenThereAreConversations(int count) throws Exception {
        assertThat(onLoop(this.messaging::conversations)).isEqualTo(count);
    }

    private void givenACorrespondentListening() throws IOException {
        this.peer = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        this.to = new InetSocketAddress(InetAddress.getLoopbackAddress(), this.peer.getLocalPort());
    }

    private void givenWeSent(String text) throws Exception {
        whenWeSend(text);
    }

    private void givenItAcceptedOurConnectionAndReadOurHello() throws Exception {
        this.ours = this.peer.accept();
        this.ours.setSoTimeout(5000);
        assertThat(TcpFraming.read(new BufferedInputStream(this.ours.getInputStream()), FrameLimit.fixed(1 << 16))
                        .opcode())
                .isEqualTo(Opcodes.Client.HELLO.code());
    }

    // The client connects to us before answering our hello: the conversation goes over its connection.
    private void givenItConnectedToUsWith(String text) throws Exception {
        onLoop(() -> this.messaging.incoming(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 50_001),
                hello(PAL, this.peer.getLocalPort()),
                true,
                this.sent::add,
                () -> true,
                ChatMessages.message(text, true)));
    }

    private void givenOurConnectionClosed() throws IOException {
        this.ours.close();
    }

    private void givenTheConversationKeptFor(Duration duration) throws Exception {
        long deadline = System.nanoTime() + duration.toNanos();
        while (System.nanoTime() < deadline) {
            assertThat(onLoop(this.messaging::conversations)).isEqualTo(1);
            Thread.sleep(50);
        }
    }

    private void whenAMessageComesFrom(Hash16 hash, int port, String text) throws Exception {
        incoming(hello(hash, port), text, this.open::get, p -> {});
    }

    private void whenWeSend(String text) throws Exception {
        onLoop(() -> {
            this.messaging.send(this.to, text);
            return null;
        });
    }

    private void whenTheConnectionCloses() {
        this.open.set(false);
    }

    private void thenTheMessagesReceivedAre(String... texts) {
        assertThat(this.received).containsExactly(texts);
    }

    private void thenThereAreConversations(int count) throws Exception {
        assertThat(onLoop(this.messaging::conversations)).isEqualTo(count);
    }

    private void thenTheFailureToReachItIsReported() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (this.failed.isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertThat(this.failed).containsExactly(this.to.toString());
    }

    private void thenThePacketsSentOverItsConnectionAre(int count) {
        assertThat(this.sent).hasSize(count);
    }

    private void thenTheSecondOfThemCarries(String text) throws Exception {
        assertThat(ChatMessages.text(this.sent.get(1))).isEqualTo(text);
    }

    private void thenNoFailureIsReported() {
        assertThat(this.failed).isEmpty();
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
