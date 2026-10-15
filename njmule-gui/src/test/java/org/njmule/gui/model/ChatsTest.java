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
package org.njmule.gui.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.njmule.core.api.Hash16;

class ChatsTest {

    private final Chats chats = new Chats();
    private Chats.Chat mine;

    @Test
    void shouldTrimNoticesLikeTheOtherLines() {
        whenNoticesArrive("10.0.0.1:4662", Chats.MAX_LINES + 5);

        thenTheConversationHasLines("10.0.0.1:4662", Chats.MAX_LINES);
    }

    @Test
    void shouldCapTheConversationsStrangersStartLeastRecentFirst() {
        givenAConversationTheUserOpened("10.0.0.1:4662");
        givenAMessageFrom("10.0.0.2:4662", "first stranger");

        whenStrangersWriteWhileAnotherKeepsWriting(Chats.MAX_STRANGERS + 5, "10.0.0.2:4662");

        thenTheConversationsAre(Chats.MAX_STRANGERS + 1);
        thenTheConversationTheUserOpenedStays();
        thenTheConversationsInclude("10.0.0.2:4662", "10.1.0.6:4662");
        thenTheLeastRecentConversationsAreClosed("10.1.0.0:4662", "10.1.0.5:4662");
    }

    @Test
    void shouldNotTreatAConversationTheUserAnsweredAsAStrangers() {
        givenAMessageFrom("10.0.0.2:4662", "hello");
        givenTheUserAnswered("10.0.0.2:4662", "hi");

        whenStrangersWrite(Chats.MAX_STRANGERS + 5);

        thenTheConversationsInclude("10.0.0.2:4662");
    }

    private void givenAConversationTheUserOpened(String address) {
        this.mine = this.chats.with(address);
    }

    private void givenAMessageFrom(String address, String text) {
        this.chats.received(address, Hash16.ZERO, Optional.empty(), text);
    }

    private void givenTheUserAnswered(String address, String text) {
        this.chats.sent(address, text);
    }

    private void whenNoticesArrive(String address, int count) {
        for (int i = 0; i < count; i++) {
            this.chats.notice(address, "nj.chat.failed", "timeout");
        }
    }

    private void whenStrangersWrite(int count) {
        for (int i = 0; i < count; i++) {
            strangerWrites(i);
        }
    }

    private void whenStrangersWriteWhileAnotherKeepsWriting(int count, String another) {
        for (int i = 0; i < count; i++) {
            strangerWrites(i);
            // The first stranger keeps writing: it stays.
            this.chats.received(another, Hash16.ZERO, Optional.empty(), "again");
        }
    }

    private void strangerWrites(int i) {
        this.chats.received("10.1.0." + i + ":4662", Hash16.ZERO, Optional.empty(), "spam " + i);
    }

    private void thenTheConversationHasLines(String address, int lines) {
        assertThat(this.chats.with(address).lines()).hasSize(lines);
    }

    private void thenTheConversationsAre(int count) {
        assertThat(this.chats.all()).hasSize(count);
    }

    private void thenTheConversationTheUserOpenedStays() {
        assertThat(this.chats.all()).contains(this.mine);
    }

    private void thenTheConversationsInclude(String... addresses) {
        assertThat(this.chats.all()).extracting(Chats.Chat::address).contains(addresses);
    }

    private void thenTheLeastRecentConversationsAreClosed(String... addresses) {
        assertThat(this.chats.all())
                .extracting(Chats.Chat::address)
                .as("the least recent are closed")
                .doesNotContain(addresses);
    }
}
