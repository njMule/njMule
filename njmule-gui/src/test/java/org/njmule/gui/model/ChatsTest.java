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

    @Test
    void noticesAreTrimmedLikeTheOtherLines() {
        Chats chats = new Chats();
        for (int i = 0; i < Chats.MAX_LINES + 5; i++) {
            chats.notice("10.0.0.1:4662", "nj.chat.failed", "timeout");
        }

        assertThat(chats.with("10.0.0.1:4662").lines()).hasSize(Chats.MAX_LINES);
    }

    @Test
    void conversationsStrangersStartAreCappedLeastRecentFirst() {
        Chats chats = new Chats();
        Chats.Chat mine = chats.with("10.0.0.1:4662");
        chats.received("10.0.0.2:4662", Hash16.ZERO, Optional.empty(), "first stranger");
        for (int i = 0; i < Chats.MAX_STRANGERS + 5; i++) {
            chats.received("10.1.0." + i + ":4662", Hash16.ZERO, Optional.empty(), "spam " + i);
            // The first stranger keeps writing: it stays.
            chats.received("10.0.0.2:4662", Hash16.ZERO, Optional.empty(), "again");
        }

        assertThat(chats.all()).hasSize(Chats.MAX_STRANGERS + 1);
        assertThat(chats.all()).contains(mine);
        assertThat(chats.all()).extracting(Chats.Chat::address).contains("10.0.0.2:4662", "10.1.0.6:4662");
        assertThat(chats.all())
                .extracting(Chats.Chat::address)
                .as("the least recent are closed")
                .doesNotContain("10.1.0.0:4662", "10.1.0.5:4662");
    }

    @Test
    void aConversationTheUserAnsweredIsNotAStrangersAnyMore() {
        Chats chats = new Chats();
        chats.received("10.0.0.2:4662", Hash16.ZERO, Optional.empty(), "hello");
        chats.sent("10.0.0.2:4662", "hi");
        for (int i = 0; i < Chats.MAX_STRANGERS + 5; i++) {
            chats.received("10.1.0." + i + ":4662", Hash16.ZERO, Optional.empty(), "spam " + i);
        }

        assertThat(chats.all()).extracting(Chats.Chat::address).contains("10.0.0.2:4662");
    }
}
