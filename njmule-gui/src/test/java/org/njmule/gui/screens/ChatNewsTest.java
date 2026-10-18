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

import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.core.api.EngineEvent;
import org.njmule.core.api.Hash16;
import org.njmule.gui.FxTesting;
import org.njmule.gui.TestGui;
import org.njmule.gui.model.Chats;

@Timeout(60)
class ChatNewsTest {

    private static final Hash16 PEER = Hash16.parseHex("00112233445566778899AABBCCDDEEFF");

    @TempDir
    Path dir;

    private TestGui test;
    private MessagesScreen screen;

    @AfterEach
    void close() {
        if (this.test != null) {
            this.test.close();
        }
    }

    @Test
    void shouldCountTheNewsOfAConversationNotShown() throws Exception {
        givenTheMessages();
        givenAMessageFrom("Ann", "10.0.0.1:4662");
        givenAMessageFrom("Bob", "10.0.0.2:4662");

        whenAMessageFrom("Bob", "10.0.0.2:4662");

        thenTheConversationHasNews("Ann", 0);
        thenTheConversationHasNews("Bob", 2);
    }

    @Test
    void shouldMarkAConversationWithNewsUntilLookedAt() throws Exception {
        givenTheMessages();
        givenAMessageFrom("Ann", "10.0.0.1:4662");
        givenAMessageFrom("Bob", "10.0.0.2:4662");
        givenTheConversationHasNews("Bob", 1);

        whenTheConversationIsChosen("Bob");

        thenTheConversationHasNews("Bob", 0);
    }

    @Test
    void shouldKeepTheNewsWrittenBeforeTheMessagesFirstShowedButTheNewest() throws Exception {
        givenTheApp();
        givenAMessageSentFrom("Ann", "10.0.0.1:4662");
        givenAMessageSentFrom("Bob", "10.0.0.2:4662");

        whenTheMessagesShow();

        thenTheConversationHasNews("Ann", 1);
        thenTheConversationHasNews("Bob", 0);
    }

    private void givenTheApp() throws Exception {
        this.test = TestGui.start(this.dir.resolve("njmule"));
    }

    private void givenTheMessages() throws Exception {
        givenTheApp();
        whenTheMessagesShow();
    }

    // The screen is not built yet: the model alone holds the conversation.
    private void givenAMessageSentFrom(String nick, String address) throws Exception {
        whenAMessageFrom(nick, address);
        FxTesting.assertEventually("the conversation with " + nick, () -> chat(nick) != null);
    }

    private void whenTheMessagesShow() throws Exception {
        this.screen = new MessagesScreen(this.test.gui);
        this.test.show(FxTesting.onFx(this.screen::view));
    }

    // The first conversation shows at once: the next ones wait in the list.
    private void givenAMessageFrom(String nick, String address) throws Exception {
        whenAMessageFrom(nick, address);
        FxTesting.assertEventually("the conversation with " + nick, () -> chat(nick) != null);
    }

    private void givenTheConversationHasNews(String nick, int news) throws Exception {
        thenTheConversationHasNews(nick, news);
    }

    private void whenAMessageFrom(String nick, String address) throws Exception {
        FxTesting.onFx(() -> this.test.model.handle(
                new EngineEvent.MessageReceived(address, PEER, Optional.of(nick), "hello from " + nick)));
    }

    private void whenTheConversationIsChosen(String nick) throws Exception {
        FxTesting.onFx(() -> this.screen.choose(nick));
    }

    private void thenTheConversationHasNews(String nick, int news) throws Exception {
        FxTesting.assertEventually(nick + " has " + news + " news", () -> this.screen.unread(chat(nick)) == news);
        assertThat(FxTesting.onFx(() -> this.screen.unread(chat(nick)))).isEqualTo(news);
    }

    private Chats.Chat chat(String nick) {
        return this.test.model.chats().all().stream()
                .filter(c -> nick.equals(c.nickProperty().get()))
                .findFirst()
                .orElse(null);
    }
}
