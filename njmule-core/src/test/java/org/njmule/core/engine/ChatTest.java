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

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
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
import org.njmule.core.protocol.packet.TcpFraming;

@Timeout(60)
class ChatTest {

    private static final Duration WAIT = Duration.ofSeconds(20);

    @TempDir
    Path dir;

    private final List<AutoCloseable> resources = new ArrayList<>();

    @AfterEach
    void closeEverything() throws Exception {
        Collections.reverse(this.resources);
        for (AutoCloseable resource : this.resources) {
            resource.close();
        }
    }

    @Test
    void twoClientsChatOverOneConnection() throws Exception {
        NjMuleEngine alice = this.engine("alice");
        NjMuleEngine bob = this.engine("bob");
        Events aliceSees = new Events(alice);
        Events bobSees = new Events(bob);

        alice.sendMessage(local(bob), "hello bob, ça va?").get(5, TimeUnit.SECONDS);
        EngineEvent.MessageReceived toBob = bobSees.next(EngineEvent.MessageReceived.class);
        assertThat(toBob.text()).isEqualTo("hello bob, ça va?");
        assertThat(toBob.nick()).contains("alice");
        assertThat(toBob.address()).endsWith(":" + alice.preferences().tcpPort());

        bob.sendMessage(ServerAddress.parse(toBob.address()), "hi alice").get(5, TimeUnit.SECONDS);
        EngineEvent.MessageReceived toAlice = aliceSees.next(EngineEvent.MessageReceived.class);
        assertThat(toAlice.text()).isEqualTo("hi alice");
        assertThat(toAlice.nick()).contains("bob");

        alice.sendMessage(local(bob), "again").get(5, TimeUnit.SECONDS);
        assertThat(bobSees.next(EngineEvent.MessageReceived.class).text()).isEqualTo("again");
        alice.sendMessage(local(bob), "  ").get(5, TimeUnit.SECONDS);
    }

    @Test
    void aClientWeWroteToAsksForACaptchaAndTellsTheResult() throws Exception {
        NjMuleEngine engine = this.engine("me");
        Events seen = new Events(engine);
        Hello asker = Hello.of(Hash16.parseHex("11223344556677889900AABBCCDDEEFF"), 0, 4999, "asker", 0, 0, 0, 0, 0, 0);

        // A CAPTCHA nobody asked for, on a connection the other side opened, is ignored.
        try (Socket socket = new Socket(
                InetAddress.getLoopbackAddress(), engine.preferences().tcpPort())) {
            socket.setSoTimeout((int) WAIT.toMillis());
            InputStream in = new BufferedInputStream(socket.getInputStream());
            socket.getOutputStream().write(TcpFraming.encode(asker.encode(false)));
            assertThat(TcpFraming.read(in, FrameLimit.fixed(1 << 20)).opcode())
                    .isEqualTo(Opcodes.Client.HELLOANSWER.code());
            socket.getOutputStream().write(TcpFraming.encode(ChatMessages.captchaRequest(bmp(40, 24))));
            socket.getOutputStream().write(TcpFraming.encode(ChatMessages.message("unasked", true)));
            assertThat(seen.next(EngineEvent.MessageReceived.class).text()).isEqualTo("unasked");
        }

        try (ServerSocket peer = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            peer.setSoTimeout((int) WAIT.toMillis());
            engine.sendMessage(new ServerAddress("127.0.0.1", peer.getLocalPort()), "may I write?")
                    .get(5, TimeUnit.SECONDS);
            try (Socket socket = peer.accept()) {
                socket.setSoTimeout((int) WAIT.toMillis());
                InputStream in = new BufferedInputStream(socket.getInputStream());
                assertThat(TcpFraming.read(in, FrameLimit.fixed(1 << 20)).opcode())
                        .isEqualTo(Opcodes.Client.HELLO.code());
                socket.getOutputStream().write(TcpFraming.encode(asker.encode(true)));
                assertThat(TcpFraming.read(in, FrameLimit.fixed(1 << 20)).opcode())
                        .isEqualTo(Opcodes.Client.MESSAGE.code());

                // A picture declaring a huge size is refused; a plausible one is shown.
                socket.getOutputStream().write(TcpFraming.encode(ChatMessages.captchaRequest(bmp(30_000, 24))));
                byte[] image = bmp(40, 24);
                socket.getOutputStream().write(TcpFraming.encode(ChatMessages.captchaRequest(image)));
                socket.getOutputStream().write(TcpFraming.encode(ChatMessages.captchaResult(ChatMessages.SOLVED)));
                socket.getOutputStream().write(TcpFraming.encode(ChatMessages.message("x".repeat(500), true)));

                EngineEvent.CaptchaRequested asked = seen.next(EngineEvent.CaptchaRequested.class);
                assertThat(asked.image()).isEqualTo(image);
                assertThat(asked.address()).endsWith(":" + peer.getLocalPort());
                assertThat(asked).isEqualTo(new EngineEvent.CaptchaRequested(asked.address(), asked.userHash(), image));
                assertThat(asked.hashCode())
                        .isEqualTo(
                                new EngineEvent.CaptchaRequested(asked.address(), asked.userHash(), image).hashCode());
                assertThat(asked.toString()).contains("300 bytes");
                assertThat(seen.next(EngineEvent.CaptchaAnswered.class).solved())
                        .isTrue();
                assertThat(seen.next(EngineEvent.MessageReceived.class).text()).hasSize(ChatMessages.MAX_LENGTH);
            }
        }
    }

    // The start of a BMP file of the given size; the pixels do not matter here.
    private static byte[] bmp(int width, int height) {
        ByteBuffer file = ByteBuffer.allocate(300).order(ByteOrder.LITTLE_ENDIAN);
        file.put((byte) 'B').put((byte) 'M');
        file.putInt(18, width).putInt(22, height);
        return file.array();
    }

    @Test
    void anUnreachableClientIsReported() throws Exception {
        NjMuleEngine engine = this.engine("me");
        Events seen = new Events(engine);
        int closed;
        try (ServerSocket probe = new ServerSocket(0)) {
            closed = probe.getLocalPort();
        }
        engine.sendMessage(new ServerAddress("127.0.0.1", closed), "anyone?").get(5, TimeUnit.SECONDS);

        assertThat(seen.next(EngineEvent.MessageFailed.class).address()).isEqualTo("127.0.0.1:" + closed);
    }

    @Test
    void friendsAreKept() throws Exception {
        Hash16 hash = Hash16.parseHex("0123456789ABCDEF0123456789ABCDEF");
        NjMuleEngine engine = this.engine("me");
        engine.addFriend(new Friend(hash, "pal", Optional.of(new ServerAddress("10.0.0.7", 4662))))
                .get(5, TimeUnit.SECONDS);
        engine.addFriend(new Friend(hash, "pal again", Optional.of(new ServerAddress("pal.example", 1))))
                .get(5, TimeUnit.SECONDS);
        engine.close();

        NjMuleEngine again = this.engine("me");
        List<Friend> friends = again.friends().get(5, TimeUnit.SECONDS);
        assertThat(friends).containsExactly(new Friend(hash, "pal again", Optional.empty()));
        assertThat(again.removeFriend(hash).get(5, TimeUnit.SECONDS)).isTrue();
        assertThat(again.removeFriend(hash).get(5, TimeUnit.SECONDS)).isFalse();
        assertThat(again.friends().get(5, TimeUnit.SECONDS)).isEmpty();
    }

    private static ServerAddress local(NjMuleEngine engine) {
        return new ServerAddress("127.0.0.1", engine.preferences().tcpPort());
    }

    private NjMuleEngine engine(String nick) throws IOException {
        Path root = this.dir.resolve(nick);
        AppDirectories dirs = AppDirectories.underRoot(root, true);
        Files.createDirectories(dirs.config());
        Path props = dirs.config().resolve("njmule.properties");
        if (!Files.exists(props)) {
            int port;
            try (ServerSocket probe = new ServerSocket(0)) {
                port = probe.getLocalPort();
            }
            Files.writeString(props, "net.tcpPort=" + port + "\nnet.kad.enabled=false\nuser.nick=" + nick + "\n");
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
