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
package org.njmule.gui.screens;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Flow;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.core.api.EngineEvent;
import org.njmule.core.api.Hash16;
import org.njmule.core.api.NjMuleEngine;
import org.njmule.core.api.ServerAddress;
import org.njmule.gui.FxTesting;
import org.njmule.gui.TestGui;
import org.njmule.gui.model.Chats;

@Timeout(90)
class MessagesScreenTest {

    @TempDir
    Path dir;

    private TestGui test;
    private NjMuleEngine bob;

    @AfterEach
    void close() throws Exception {
        if (this.test != null) {
            this.test.close();
        }
        if (this.bob != null) {
            this.bob.close();
        }
    }

    @Test
    void chatsWithAnotherClientAndKeepsItAsAFriend() throws Exception {
        this.test = TestGui.start(this.dir.resolve("njmule"));
        this.bob = FxTesting.engine(this.dir.resolve("bob"));
        BlockingQueue<EngineEvent> bobSees = new LinkedBlockingQueue<>();
        this.bob.events().subscribe(new Collector(bobSees));
        MessagesScreen screen = new MessagesScreen(this.test.gui);
        this.test.show(FxTesting.onFx(screen::view));
        String bobAddress = "127.0.0.1:" + this.bob.preferences().tcpPort();

        FxTesting.onFx(() -> {
            screen.addressField().setText(bobAddress);
            screen.startChat();
        });
        MessagesScreen.ChatPane pane = FxTesting.onFx(() ->
                (MessagesScreen.ChatPane) screen.tabs().getTabs().getFirst().getContent());
        FxTesting.onFx(() -> {
            pane.inputField().setText("hello bob");
            pane.send();
        });
        EngineEvent.MessageReceived got = null;
        while (got == null) {
            EngineEvent e = bobSees.poll(20, TimeUnit.SECONDS);
            assertThat(e).isNotNull();
            if (e instanceof EngineEvent.MessageReceived m) {
                got = m;
            }
        }
        assertThat(got.text()).isEqualTo("hello bob");

        this.bob.sendMessage(ServerAddress.parse(got.address()), "hi there").get(5, TimeUnit.SECONDS);
        Chats.Chat chat = FxTesting.onFx(() -> this.test.model.chats().all().getFirst());
        FxTesting.await("the answer", () -> chat.lines().stream().anyMatch(l -> l.author() == Chats.Author.THEM));
        assertThat(FxTesting.onFx(() -> screen.tabs().getTabs().size())).isEqualTo(1);
        assertThat(FxTesting.onFx(() -> screen.tabs().getTabs().getFirst().getText()))
                .isEqualTo(this.bob.preferences().nick());
        FxTesting.onFx(() -> {
            FxTesting.snapshot(screen.view(), "messages-chat");
            pane.befriend();
            return null;
        });
        FxTesting.await("a friend", () -> screen.friendsList().getItems().size() == 1);
        FxTesting.onFx(() -> {
            screen.friendsList().getSelectionModel().select(0);
            screen.chatWithFriend();
            screen.removeFriend();
        });
        FxTesting.await("no friend", () -> screen.friendsList().getItems().isEmpty());

        FxTesting.onFx(() -> {
            screen.addressField().setText("nowhere");
            screen.startChat();
            screen.chatWithFriend();
        });
        assertThat(this.test.told).anyMatch(m -> m.contains("host:port: nowhere"));
    }

    @Test
    void aCaptchaIsShownAndItsSolutionSent() throws Exception {
        this.test = TestGui.start(this.dir.resolve("njmule"));
        MessagesScreen screen = new MessagesScreen(this.test.gui);
        this.test.show(FxTesting.onFx(screen::view));
        BufferedImage picture = new BufferedImage(80, 24, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream bmp = new ByteArrayOutputStream();
        ImageIO.write(picture, "bmp", bmp);

        Chats.Chat chat = FxTesting.onFx(() -> this.test.model.chats().with("127.0.0.1:1"));
        MessagesScreen.ChatPane pane = FxTesting.onFx(() ->
                (MessagesScreen.ChatPane) screen.tabs().getTabs().getFirst().getContent());
        assertThat(FxTesting.onFx(pane::showsCaptcha)).isFalse();
        // Who wrote is not known before the first packet from them.
        FxTesting.onFx(pane::befriend);
        FxTesting.onFx(() -> this.test.model.handle(
                new EngineEvent.CaptchaRequested("127.0.0.1:1", Hash16.ZERO, bmp.toByteArray())));
        assertThat(FxTesting.onFx(pane::showsCaptcha)).isTrue();
        assertThat(MessagesScreen.picture(new byte[] {1, 2, 3})).isEmpty();
        FxTesting.onFx(() -> {
            pane.solutionField().setText("ABCD");
            pane.solve();
        });
        assertThat(FxTesting.onFx(pane::showsCaptcha)).isFalse();
        assertThat(FxTesting.onFx(() -> chat.lines().getFirst().text())).isEqualTo("ABCD");
        assertThat(this.test.told).anyMatch(m -> m.startsWith("Wait for an answer"));
        FxTesting.await("the failure", () -> chat.lines().stream().anyMatch(l -> l.author() == Chats.Author.NOTICE));
        FxTesting.onFx(() -> screen.tabs().getTabs().getFirst().getOnClosed().handle(null));
        assertThat(FxTesting.onFx(() -> screen.tabs().getTabs().isEmpty())).isTrue();
    }

    private record Collector(BlockingQueue<EngineEvent> queue) implements Flow.Subscriber<EngineEvent> {
        @Override
        public void onSubscribe(Flow.Subscription s) {
            s.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(EngineEvent item) {
            this.queue.add(item);
        }

        @Override
        public void onError(Throwable t) {}

        @Override
        public void onComplete() {}
    }
}
