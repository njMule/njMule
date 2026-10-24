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
package org.njmule.core.remote;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.njmule.core.api.EngineEvent;
import org.njmule.core.api.Hash16;

class ConversationsTest {

    private static final String ALICE = "203.0.113.5:4662";
    private static final Hash16 ALICE_HASH = Hash16.parseHex("A1".repeat(16));

    private final Conversations conversations =
            new Conversations(Clock.fixed(Instant.parse("2026-10-24T12:00:00Z"), ZoneOffset.UTC));
    private List<Conversations.Chat> chats;

    @Test
    void shouldKeepAMessageWithTheNickAndUserHashItCameWith() {
        givenAMessageFrom(ALICE, "hello");

        whenTheChatsAreRead();

        thenTheChatWithIs(ALICE, "alice", Conversations.Author.THEM, "hello");
    }

    @Test
    void shouldKeepOurAnswerAndForgetTheCaptchaItSolves() {
        givenACaptchaAskedBy(ALICE);

        whenWeWrite(ALICE, "XK4P");

        thenTheChatWithHasNoCaptcha(ALICE);
        thenTheLastLineIs(Conversations.Author.US, "XK4P");
    }

    @Test
    void shouldTellThatAMessageWasNotDelivered() {
        givenAMessageFrom(ALICE, "hello");
        givenAMessageNotDeliveredTo(ALICE, "offline");

        whenTheChatsAreRead();

        thenTheLastLineIsTheNotice(Conversations.Notice.NOT_DELIVERED, "offline");
    }

    @Test
    void shouldForgetTheOldestStrangersBeyondTheirLimit() {
        givenStrangersWrote(Conversations.MAX_STRANGERS + 1);

        whenTheChatsAreRead();

        thenThereAreChats(Conversations.MAX_STRANGERS);
        thenThereIsNoChatWith(stranger(0));
    }

    @Test
    void shouldKeepTheChatsWeWroteWhateverStrangersWrite() {
        givenWeWrote(ALICE);
        givenStrangersWrote(Conversations.MAX_STRANGERS + 1);

        whenTheChatsAreRead();

        thenThereAreChats(Conversations.MAX_STRANGERS + 1);
        thenTheOldestChatIsWith(ALICE);
    }

    private void givenAMessageFrom(String address, String text) {
        this.conversations.received(new EngineEvent.MessageReceived(address, ALICE_HASH, Optional.of("alice"), text));
    }

    private void givenACaptchaAskedBy(String address) {
        this.conversations.captcha(new EngineEvent.CaptchaRequested(address, ALICE_HASH, new byte[] {'B', 'M'}));
    }

    private void givenAMessageNotDeliveredTo(String address, String reason) {
        this.conversations.failed(new EngineEvent.MessageFailed(address, reason));
    }

    private void givenWeWrote(String address) {
        this.conversations.sent(address, "hi");
    }

    private void givenStrangersWrote(int count) {
        for (int i = 0; i < count; i++) {
            this.conversations.received(
                    new EngineEvent.MessageReceived(stranger(i), ALICE_HASH, Optional.empty(), "buy now"));
        }
    }

    private void whenTheChatsAreRead() {
        this.chats = this.conversations.all();
    }

    private void whenWeWrite(String address, String text) {
        this.conversations.sent(address, text);
        this.chats = this.conversations.all();
    }

    private void thenTheChatWithIs(String address, String nick, Conversations.Author author, String text) {
        Conversations.Chat chat = chatWith(address);
        assertThat(chat.nick()).contains(nick);
        assertThat(chat.userHash()).contains(ALICE_HASH);
        assertThat(chat.lines())
                .extracting(Conversations.Line::author, Conversations.Line::text)
                .containsExactly(tuple(author, text));
    }

    private void thenTheChatWithHasNoCaptcha(String address) {
        assertThat(chatWith(address).captcha()).isEmpty();
    }

    private void thenTheLastLineIs(Conversations.Author author, String text) {
        Conversations.Line last = this.chats.getFirst().lines().getLast();
        assertThat(last.author()).isEqualTo(author);
        assertThat(last.text()).isEqualTo(text);
    }

    private void thenTheLastLineIsTheNotice(Conversations.Notice notice, String text) {
        Conversations.Line last = this.chats.getFirst().lines().getLast();
        assertThat(last.author()).isEqualTo(Conversations.Author.NOTICE);
        assertThat(last.notice()).contains(notice);
        assertThat(last.text()).isEqualTo(text);
    }

    private void thenThereAreChats(int count) {
        assertThat(this.chats).hasSize(count);
    }

    private void thenThereIsNoChatWith(String address) {
        assertThat(this.chats).extracting(Conversations.Chat::address).doesNotContain(address);
    }

    private void thenTheOldestChatIsWith(String address) {
        assertThat(this.chats.getLast().address()).isEqualTo(address);
    }

    private Conversations.Chat chatWith(String address) {
        return this.chats.stream()
                .filter(c -> c.address().equals(address))
                .findFirst()
                .orElseThrow();
    }

    private static String stranger(int i) {
        return "198.51.100." + (i + 1) + ":4662";
    }
}
