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

import java.util.List;
import org.junit.jupiter.api.Test;

class ChatFilterTest {

    private static final ChatFilter USUAL =
            new ChatFilter(false, ChatFilter.words("Fastest Download Speed| |viagra"), true);

    @Test
    void leavesOutTheFilteredWords() {
        assertThat(ChatFilter.words("a|b c|")).containsExactly("a", "b c", "");
        assertThat(USUAL.words())
                .as("trimmed, in lower case, none empty")
                .containsExactly("fastest download speed", "viagra");
        assertThat(USUAL.shows(new ChatFilter.State(), true, "get the FASTEST download speed now"))
                .isFalse();
        assertThat(USUAL.shows(new ChatFilter.State(), true, "hello")).isTrue();
    }

    @Test
    void showsOnlyFriendsWhenAsked() {
        ChatFilter friends = new ChatFilter(true, List.of(), false);
        assertThat(friends.shows(new ChatFilter.State(), false, "hello")).isFalse();
        assertThat(friends.shows(new ChatFilter.State(), true, "hello")).isTrue();
    }

    @Test
    void takesALinkBeforeWeWroteForSpam() {
        ChatFilter.State state = new ChatFilter.State();
        assertThat(USUAL.shows(state, false, "visit www.example.org")).isFalse();
        assertThat(USUAL.shows(state, false, "hello")).as("a spammer stays one").isFalse();

        ChatFilter.State answered = new ChatFilter.State();
        answered.wrote();
        assertThat(USUAL.shows(answered, false, "see https://example.org")).isTrue();
        assertThat(USUAL.shows(new ChatFilter.State(), true, "www.example.org"))
                .as("friends")
                .isTrue();
    }

    @Test
    void showsThreeUnansweredMessagesAtMost() {
        ChatFilter.State state = new ChatFilter.State();
        for (int i = 0; i < ChatFilter.MAX_UNANSWERED; i++) {
            assertThat(USUAL.shows(state, false, "hi " + i)).isTrue();
        }
        assertThat(USUAL.shows(state, false, "hi again")).isFalse();
        assertThat(ChatFilter.NONE.shows(new ChatFilter.State(), false, "www.example.org"))
                .isTrue();
    }
}
