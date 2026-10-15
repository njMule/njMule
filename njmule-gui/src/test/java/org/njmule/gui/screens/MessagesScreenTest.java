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
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Flow;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import javafx.scene.image.Image;
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
    private MessagesScreen screen;
    private final BlockingQueue<EngineEvent> bobSees = new LinkedBlockingQueue<>();
    private EngineEvent.MessageReceived got;
    private MessagesScreen.ChatPane pane;
    private Chats.Chat chat;
    private byte[] bmp;
    private Optional<Image> picture;

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
    void shouldChatWithAnotherClientKeepItAsAFriendAndRefuseABadAddress() throws Exception {
        givenTheMessages();
        givenBob();
        givenAChatStartedWithBob();
        givenTheUserWrites("hello bob");
        givenBobReceives("hello bob");
        givenBobAnswers("hi there");
        givenTheAnswerShows();
        givenOneConversationNamedAfterBob();
        givenBobBefriended();
        givenBobChattedWithAndRemovedAsAFriend();

        whenChatsAreStartedWithTheAddressAndNoFriendChosen("nowhere");

        thenTheUserWasToldSomethingContaining("host:port: nowhere");
    }

    @Test
    void shouldShowACaptchaSendItsSolutionAndCloseTheConversation() throws Exception {
        givenTheMessages();
        givenABmpPicture(80, 24);
        givenAConversationWith("127.0.0.1:1");
        givenTheCaptchaShows(false);
        givenTheConversationBefriended();
        givenACaptchaRequestedBy("127.0.0.1:1");
        givenTheCaptchaShows(true);
        givenTheSolutionSent("ABCD");
        givenTheCaptchaShows(false);
        givenTheFirstLineIs("ABCD");
        givenTheUserWasToldSomethingStartingWith("Wait for an answer");
        givenTheFailureIsNoted();

        whenTheConversationIsClosed();

        thenThereIsNoConversation();
    }

    @Test
    void shouldShowNoPictureForBytesThatAreNoImage() {
        whenThePictureIsRead(new byte[] {1, 2, 3});

        thenThereIsNoPicture();
    }

    @Test
    void shouldRefuseAChatToAnIpv6AddressAndCountNothingAsSent() throws Exception {
        givenTheMessages();
        givenAChatStartedWith("[2001:db8::1]:4661");
        givenTheUserWasToldSomethingContaining("2001:db8::1");
        givenThereIsNoConversation();

        whenAMessageIsSentTo("2001:db8::1:4661", "hello");

        thenNothingShowsAsSent();
        thenTheUserWasToldSomethingContaining("2001:db8::1:4661");
    }

    private void givenTheMessages() throws Exception {
        this.test = TestGui.start(this.dir.resolve("njmule"));
        this.screen = new MessagesScreen(this.test.gui);
        this.test.show(FxTesting.onFx(this.screen::view));
    }

    private void givenBob() throws Exception {
        this.bob = FxTesting.engine(this.dir.resolve("bob"));
        this.bob.events().subscribe(new Collector(this.bobSees));
    }

    private void givenAChatStartedWithBob() throws Exception {
        startChat("127.0.0.1:" + this.bob.preferences().tcpPort());
        this.pane = FxTesting.onFx(() -> (MessagesScreen.ChatPane)
                this.screen.tabs().getTabs().getFirst().getContent());
    }

    private void givenAChatStartedWith(String address) throws Exception {
        startChat(address);
    }

    private void givenTheUserWrites(String text) throws Exception {
        FxTesting.onFx(() -> {
            this.pane.inputField().setText(text);
            this.pane.send();
        });
    }

    private void givenBobReceives(String text) throws Exception {
        while (this.got == null) {
            EngineEvent e = this.bobSees.poll(20, TimeUnit.SECONDS);
            assertThat(e).isNotNull();
            if (e instanceof EngineEvent.MessageReceived m) {
                this.got = m;
            }
        }
        assertThat(this.got.text()).isEqualTo(text);
    }

    private void givenBobAnswers(String text) throws Exception {
        this.bob.sendMessage(ServerAddress.parse(this.got.address()), text).get(5, TimeUnit.SECONDS);
    }

    private void givenTheAnswerShows() throws Exception {
        this.chat = FxTesting.onFx(() -> this.test.model.chats().all().getFirst());
        FxTesting.await("the answer", () -> this.chat.lines().stream().anyMatch(l -> l.author() == Chats.Author.THEM));
    }

    private void givenOneConversationNamedAfterBob() throws Exception {
        assertThat(FxTesting.onFx(() -> this.screen.tabs().getTabs().size())).isEqualTo(1);
        assertThat(FxTesting.onFx(() -> this.screen.tabs().getTabs().getFirst().getText()))
                .isEqualTo(this.bob.preferences().nick());
    }

    private void givenBobBefriended() throws Exception {
        FxTesting.onFx(() -> {
            FxTesting.snapshot(this.screen.view(), "messages-chat");
            this.pane.befriend();
            return null;
        });
        FxTesting.await("a friend", () -> this.screen.friendsList().getItems().size() == 1);
    }

    private void givenBobChattedWithAndRemovedAsAFriend() throws Exception {
        FxTesting.onFx(() -> {
            this.screen.friendsList().getSelectionModel().select(0);
            this.screen.chatWithFriend();
            this.screen.removeFriend();
        });
        FxTesting.await("no friend", () -> this.screen.friendsList().getItems().isEmpty());
    }

    private void givenABmpPicture(int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "bmp", out);
        this.bmp = out.toByteArray();
    }

    private void givenAConversationWith(String address) throws Exception {
        this.chat = FxTesting.onFx(() -> this.test.model.chats().with(address));
        this.pane = FxTesting.onFx(() -> (MessagesScreen.ChatPane)
                this.screen.tabs().getTabs().getFirst().getContent());
    }

    private void givenTheCaptchaShows(boolean shows) throws Exception {
        assertThat(FxTesting.onFx(this.pane::showsCaptcha)).isEqualTo(shows);
    }

    // Who wrote is not known before the first packet from them.
    private void givenTheConversationBefriended() throws Exception {
        FxTesting.onFx(this.pane::befriend);
    }

    private void givenACaptchaRequestedBy(String address) throws Exception {
        FxTesting.onFx(() -> this.test.model.handle(new EngineEvent.CaptchaRequested(address, Hash16.ZERO, this.bmp)));
    }

    private void givenTheSolutionSent(String solution) throws Exception {
        FxTesting.onFx(() -> {
            this.pane.solutionField().setText(solution);
            this.pane.solve();
        });
    }

    private void givenTheFirstLineIs(String text) throws Exception {
        assertThat(FxTesting.onFx(() -> this.chat.lines().getFirst().text())).isEqualTo(text);
    }

    private void givenTheUserWasToldSomethingStartingWith(String start) {
        assertThat(this.test.told).anyMatch(m -> m.startsWith(start));
    }

    private void givenTheUserWasToldSomethingContaining(String part) {
        assertThat(this.test.told).anyMatch(m -> m.contains(part));
    }

    private void givenTheFailureIsNoted() throws Exception {
        FxTesting.await(
                "the failure", () -> this.chat.lines().stream().anyMatch(l -> l.author() == Chats.Author.NOTICE));
    }

    private void givenThereIsNoConversation() throws Exception {
        assertThat(FxTesting.onFx(() -> this.screen.tabs().getTabs().isEmpty())).isTrue();
    }

    private void whenChatsAreStartedWithTheAddressAndNoFriendChosen(String address) throws Exception {
        FxTesting.onFx(() -> {
            this.screen.addressField().setText(address);
            this.screen.startChat();
            this.screen.chatWithFriend();
        });
    }

    private void whenTheConversationIsClosed() throws Exception {
        FxTesting.onFx(
                () -> this.screen.tabs().getTabs().getFirst().getOnClosed().handle(null));
    }

    private void whenThePictureIsRead(byte[] bytes) {
        this.picture = MessagesScreen.picture(bytes);
    }

    // An address as an IPv6 one reads without its brackets.
    private void whenAMessageIsSentTo(String address, String text) throws Exception {
        FxTesting.onFx(() -> this.test.model.sendMessage(address, text));
    }

    private void thenTheUserWasToldSomethingContaining(String part) {
        assertThat(this.test.told).anyMatch(m -> m.contains(part));
    }

    private void thenThereIsNoConversation() throws Exception {
        assertThat(FxTesting.onFx(() -> this.screen.tabs().getTabs().isEmpty())).isTrue();
    }

    private void thenThereIsNoPicture() {
        assertThat(this.picture).isEmpty();
    }

    private void thenNothingShowsAsSent() throws Exception {
        assertThat(FxTesting.onFx(() -> this.test.model.chats().all().stream()
                        .allMatch(c -> c.lines().isEmpty())))
                .as("nothing shows as sent")
                .isTrue();
    }

    private void startChat(String address) throws Exception {
        FxTesting.onFx(() -> {
            this.screen.addressField().setText(address);
            this.screen.startChat();
        });
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
