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
package org.njmule.core.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Flow;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.core.api.EngineConfig;
import org.njmule.core.api.EngineEvent;
import org.njmule.core.api.Friend;
import org.njmule.core.api.Hash16;
import org.njmule.core.api.NjMuleEngine;
import org.njmule.core.api.ServerAddress;
import org.njmule.core.config.AppDirectories;
import org.njmule.core.protocol.ed2k.ChatMessages;
import org.njmule.core.protocol.ed2k.Hello;
import org.njmule.core.protocol.packet.FrameLimit;
import org.njmule.core.protocol.packet.Opcodes;
import org.njmule.core.protocol.packet.Packet;
import org.njmule.core.protocol.packet.Protocol;
import org.njmule.core.protocol.packet.TcpFraming;

@Timeout(60)
class ChatTest {

    private static final Duration WAIT = Duration.ofSeconds(20);
    private static final Hash16 PAL = Hash16.parseHex("0123456789ABCDEF0123456789ABCDEF");
    private static final Hello ASKER =
            Hello.of(Hash16.parseHex("11223344556677889900AABBCCDDEEFF"), 0, 4999, "asker", 0, 0, 0, 0, 0, 0);

    @TempDir
    Path dir;

    private final List<AutoCloseable> resources = new ArrayList<>();
    private NjMuleEngine engine;
    private Events seen;
    private NjMuleEngine alice;
    private NjMuleEngine bob;
    private Events aliceSees;
    private Events bobSees;
    private EngineEvent.MessageReceived toBob;
    private Socket client;
    private ServerSocket peer;
    private int closedPort;
    private Hello claimed;
    private boolean removed;
    private Throwable exception;

    @AfterEach
    void closeEverything() throws Exception {
        Collections.reverse(this.resources);
        for (AutoCloseable resource : this.resources) {
            resource.close();
        }
    }

    @Test
    void shouldLetTwoClientsChatOverOneConnection() throws Exception {
        givenTheEnginesOfAliceAndBob();
        givenAliceWroteToBob("hello bob, ça va?");
        givenBobGotFromAlice("hello bob, ça va?");
        givenBobAnsweredAlice("hi alice");
        givenAliceGotFromBob("hi alice");
        givenAliceWroteToBob("again");
        givenBobGot("again");

        whenAliceWritesToBob("  ");

        thenNoExceptionOccurred();
    }

    @Test
    void shouldIgnoreACaptchaNobodyAskedFor() throws Exception {
        givenAnEngine("me");
        givenAClientThatCalledUs();

        whenTheClientSends(ChatMessages.captchaRequest(bmp(40, 24)), ChatMessages.message("unasked", true));

        thenTheNextMessageShownIs("unasked");
    }

    @Test
    void shouldShowTheCaptchaOfAClientWeWroteToAndTellItsResult() throws Exception {
        givenAnEngine("me");
        givenWeWroteToAClient("may I write?");

        // A picture declaring a huge size is refused; a plausible one is shown.
        whenTheClientSends(
                ChatMessages.captchaRequest(bmp(30_000, 24)),
                ChatMessages.captchaRequest(bmp(40, 24)),
                ChatMessages.captchaResult(ChatMessages.SOLVED),
                ChatMessages.message("x".repeat(500), true));

        thenTheCaptchaShownIs(bmp(40, 24));
        thenTheCaptchaIsSolved();
        thenTheNextMessageShownHasTheLength(ChatMessages.MAX_LENGTH);
    }

    @Test
    void shouldReportAnUnreachableClient() throws Exception {
        givenAnEngine("me");
        givenAClosedPort();

        whenWeWriteToTheClosedPort("anyone?");

        thenTheMessageToTheClosedPortFails();
    }

    @Test
    void shouldLetOnlyAFriendThatProvesItsKeyGetPastFriendsOnly() throws Exception {
        givenAnEngine("me", "chat.friendsOnly=true\n");
        givenAFriend(PAL, "pal");
        givenAnImpostorOfTheFriendWrote("from an impostor");

        whenTheFriendWritesAndProvesItsKey("from the friend");

        thenTheNextMessageShownIs("from the friend");
    }

    @Test
    void shouldKeepTheFriendsAcrossARestart() throws Exception {
        givenAnEngine("me");
        givenAFriendAdded(PAL, "pal", new ServerAddress("10.0.0.7", 4662));
        givenAFriendAdded(PAL, "pal again", new ServerAddress("pal.example", 1));

        whenTheEngineRestarts();

        thenTheFriendsAre(new Friend(PAL, "pal again", Optional.empty()));
    }

    @Test
    void shouldRemoveAKeptFriendOnlyOnce() throws Exception {
        givenAnEngine("me");
        givenAFriendAdded(PAL, "pal", new ServerAddress("10.0.0.7", 4662));
        givenAFriendAdded(PAL, "pal again", new ServerAddress("pal.example", 1));
        givenTheEngineRestarted();
        givenTheFriendRemoved(PAL);

        whenTheFriendIsRemoved(PAL);

        thenNoFriendWasRemoved();
        thenThereAreNoFriends();
    }

    private void givenTheEnginesOfAliceAndBob() throws IOException {
        this.alice = engine("alice");
        this.bob = engine("bob");
        this.aliceSees = new Events(this.alice);
        this.bobSees = new Events(this.bob);
    }

    private void givenAliceWroteToBob(String text) throws Exception {
        this.alice.sendMessage(local(this.bob), text).get(5, TimeUnit.SECONDS);
    }

    private void givenBobGotFromAlice(String text) throws InterruptedException {
        this.toBob = this.bobSees.next(EngineEvent.MessageReceived.class);
        assertThat(this.toBob.text()).isEqualTo(text);
        assertThat(this.toBob.nick()).contains("alice");
        assertThat(this.toBob.address()).endsWith(":" + this.alice.preferences().tcpPort());
    }

    private void givenBobAnsweredAlice(String text) throws Exception {
        this.bob.sendMessage(ServerAddress.parse(this.toBob.address()), text).get(5, TimeUnit.SECONDS);
    }

    private void givenAliceGotFromBob(String text) throws InterruptedException {
        EngineEvent.MessageReceived toAlice = this.aliceSees.next(EngineEvent.MessageReceived.class);
        assertThat(toAlice.text()).isEqualTo(text);
        assertThat(toAlice.nick()).contains("bob");
    }

    private void givenBobGot(String text) throws InterruptedException {
        assertThat(this.bobSees.next(EngineEvent.MessageReceived.class).text()).isEqualTo(text);
    }

    private void givenAnEngine(String nick) throws IOException {
        givenAnEngine(nick, "");
    }

    private void givenAnEngine(String nick, String extra) throws IOException {
        this.engine = engine(nick, extra);
        this.seen = new Events(this.engine);
    }

    private void givenAClientThatCalledUs() throws IOException {
        this.client = new Socket(
                InetAddress.getLoopbackAddress(), this.engine.preferences().tcpPort());
        this.resources.add(this.client);
        this.client.setSoTimeout((int) WAIT.toMillis());
        InputStream in = new BufferedInputStream(this.client.getInputStream());
        this.client.getOutputStream().write(TcpFraming.encode(ASKER.encode(false)));
        assertThat(TcpFraming.read(in, FrameLimit.fixed(1 << 20)).opcode())
                .isEqualTo(Opcodes.Client.HELLOANSWER.code());
    }

    private void givenWeWroteToAClient(String text) throws Exception {
        this.peer = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        this.resources.add(this.peer);
        this.peer.setSoTimeout((int) WAIT.toMillis());
        this.engine
                .sendMessage(new ServerAddress("127.0.0.1", this.peer.getLocalPort()), text)
                .get(5, TimeUnit.SECONDS);
        this.client = this.peer.accept();
        this.resources.add(this.client);
        this.client.setSoTimeout((int) WAIT.toMillis());
        InputStream in = new BufferedInputStream(this.client.getInputStream());
        assertThat(TcpFraming.read(in, FrameLimit.fixed(1 << 20)).opcode()).isEqualTo(Opcodes.Client.HELLO.code());
        this.client.getOutputStream().write(TcpFraming.encode(ASKER.encode(true)));
        assertThat(TcpFraming.read(in, FrameLimit.fixed(1 << 20)).opcode()).isEqualTo(Opcodes.Client.MESSAGE.code());
    }

    private void givenAClosedPort() throws IOException {
        try (ServerSocket probe = new ServerSocket(0)) {
            this.closedPort = probe.getLocalPort();
        }
    }

    private void givenAFriend(Hash16 hash, String nick) throws Exception {
        this.engine.addFriend(new Friend(hash, nick, Optional.empty())).get(5, TimeUnit.SECONDS);
        this.claimed = Hello.of(hash, 0, 4999, nick, 0, 0, IdentityProof.MISC1, 0, 0, 0);
    }

    // Anyone can send the friend's user hash: a failed proof shows nothing.
    private void givenAnImpostorOfTheFriendWrote(String text) throws IOException {
        try (Socket impostor = connect(this.engine, this.claimed)) {
            InputStream in = new BufferedInputStream(impostor.getInputStream());
            assertThat(nextOf(in).opcode()).isEqualTo(Opcodes.Client.HELLOANSWER.code());
            OutputStream out = impostor.getOutputStream();
            out.write(TcpFraming.encode(ChatMessages.message(text, true)));
            IdentityProof.fake(out);
            out.write(TcpFraming.encode(Packet.empty(Protocol.EMULE, Opcodes.Extended.PUBLICIP_REQ.code())));
            assertThat(nextOf(in).opcode()).isEqualTo(Opcodes.Extended.PUBLICIP_ANSWER.code());
        }
    }

    private void givenAFriendAdded(Hash16 hash, String nick, ServerAddress address) throws Exception {
        this.engine.addFriend(new Friend(hash, nick, Optional.of(address))).get(5, TimeUnit.SECONDS);
    }

    private void givenTheEngineRestarted() throws Exception {
        restartTheEngine();
    }

    private void givenTheFriendRemoved(Hash16 hash) throws Exception {
        assertThat(this.engine.removeFriend(hash).get(5, TimeUnit.SECONDS)).isTrue();
    }

    private void whenAliceWritesToBob(String text) {
        this.exception = catchThrowable(
                () -> this.alice.sendMessage(local(this.bob), text).get(5, TimeUnit.SECONDS));
    }

    private void whenTheClientSends(Packet... packets) throws IOException {
        for (Packet packet : packets) {
            this.client.getOutputStream().write(TcpFraming.encode(packet));
        }
    }

    private void whenWeWriteToTheClosedPort(String text) throws Exception {
        this.engine
                .sendMessage(new ServerAddress("127.0.0.1", this.closedPort), text)
                .get(5, TimeUnit.SECONDS);
    }

    private void whenTheFriendWritesAndProvesItsKey(String text) throws IOException {
        Socket friend = connect(this.engine, this.claimed);
        this.resources.add(friend);
        InputStream in = new BufferedInputStream(friend.getInputStream());
        Hello us = Hello.decode(nextOf(in));
        friend.getOutputStream().write(TcpFraming.encode(ChatMessages.message(text, true)));
        IdentityProof.prove(us, in, friend.getOutputStream());
    }

    private void whenTheEngineRestarts() throws Exception {
        restartTheEngine();
    }

    private void whenTheFriendIsRemoved(Hash16 hash) throws Exception {
        this.removed = this.engine.removeFriend(hash).get(5, TimeUnit.SECONDS);
    }

    private void thenNoExceptionOccurred() {
        assertThat(this.exception).isNull();
    }

    private void thenTheNextMessageShownIs(String text) throws InterruptedException {
        assertThat(this.seen.next(EngineEvent.MessageReceived.class).text()).isEqualTo(text);
    }

    private void thenTheCaptchaShownIs(byte[] image) throws InterruptedException {
        EngineEvent.CaptchaRequested asked = this.seen.next(EngineEvent.CaptchaRequested.class);
        assertThat(asked.image()).isEqualTo(image);
        assertThat(asked.address()).endsWith(":" + this.peer.getLocalPort());
        assertThat(asked).isEqualTo(new EngineEvent.CaptchaRequested(asked.address(), asked.userHash(), image));
        assertThat(asked.hashCode())
                .isEqualTo(new EngineEvent.CaptchaRequested(asked.address(), asked.userHash(), image).hashCode());
        assertThat(asked.toString()).contains("300 bytes");
    }

    private void thenTheCaptchaIsSolved() throws InterruptedException {
        assertThat(this.seen.next(EngineEvent.CaptchaAnswered.class).solved()).isTrue();
    }

    private void thenTheNextMessageShownHasTheLength(int length) throws InterruptedException {
        assertThat(this.seen.next(EngineEvent.MessageReceived.class).text()).hasSize(length);
    }

    private void thenTheMessageToTheClosedPortFails() throws InterruptedException {
        assertThat(this.seen.next(EngineEvent.MessageFailed.class).address()).isEqualTo("127.0.0.1:" + this.closedPort);
    }

    private void thenTheFriendsAre(Friend... expected) throws Exception {
        assertThat(this.engine.friends().get(5, TimeUnit.SECONDS)).containsExactly(expected);
    }

    private void thenNoFriendWasRemoved() {
        assertThat(this.removed).isFalse();
    }

    private void thenThereAreNoFriends() throws Exception {
        assertThat(this.engine.friends().get(5, TimeUnit.SECONDS)).isEmpty();
    }

    private void restartTheEngine() throws Exception {
        this.engine.close();
        this.engine = engine("me");
    }

    // The start of a BMP file of the given size; the pixels do not matter here.
    private static byte[] bmp(int width, int height) {
        ByteBuffer file = ByteBuffer.allocate(300).order(ByteOrder.LITTLE_ENDIAN);
        file.put((byte) 'B').put((byte) 'M');
        file.putInt(18, width).putInt(22, height);
        return file.array();
    }

    // A connection to the engine, greeted with {@code hello}; the hello answer is left to read.
    private Socket connect(NjMuleEngine engine, Hello hello) throws IOException {
        Socket socket = new Socket(
                InetAddress.getLoopbackAddress(), engine.preferences().tcpPort());
        socket.setSoTimeout((int) WAIT.toMillis());
        socket.getOutputStream().write(TcpFraming.encode(hello.encode(false)));
        return socket;
    }

    // The next packet that is not part of the identification.
    private static Packet nextOf(InputStream in) throws IOException {
        while (true) {
            Packet packet = TcpFraming.read(in, FrameLimit.fixed(1 << 20));
            if (!IdentityProof.isIdentification(packet)) {
                return packet;
            }
        }
    }

    private static ServerAddress local(NjMuleEngine engine) {
        return new ServerAddress("127.0.0.1", engine.preferences().tcpPort());
    }

    private NjMuleEngine engine(String nick) throws IOException {
        return engine(nick, "");
    }

    private NjMuleEngine engine(String nick, String extra) throws IOException {
        Path root = this.dir.resolve(nick);
        AppDirectories dirs = AppDirectories.underRoot(root, true);
        Files.createDirectories(dirs.config());
        Path props = dirs.config().resolve("njmule.properties");
        if (!Files.exists(props)) {
            int port;
            try (ServerSocket probe = new ServerSocket(0)) {
                port = probe.getLocalPort();
            }
            Files.writeString(
                    props, "net.tcpPort=" + port + "\nnet.kad.enabled=false\nuser.nick=" + nick + "\n" + extra);
        }
        NjMuleEngine engine = NjMuleEngine.start(new EngineConfig(dirs));
        this.resources.add(engine);
        return engine;
    }

    /** The events of an engine, taken one kind at a time. */
    private static final class Events implements Flow.Subscriber<EngineEvent> {
        private final BlockingQueue<EngineEvent> queue = new LinkedBlockingQueue<>();

        Events(NjMuleEngine engine) {
            engine.events().subscribe(this);
        }

        <T extends EngineEvent> T next(Class<T> kind) throws InterruptedException {
            long deadline = System.nanoTime() + WAIT.toNanos();
            while (System.nanoTime() < deadline) {
                EngineEvent e = this.queue.poll(100, TimeUnit.MILLISECONDS);
                if (kind.isInstance(e)) {
                    return kind.cast(e);
                }
            }
            throw new AssertionError("no " + kind.getSimpleName());
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(EngineEvent item) {
            this.queue.add(item);
        }

        @Override
        public void onError(Throwable throwable) {}

        @Override
        public void onComplete() {}
    }
}
