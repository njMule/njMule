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

    private final ChatFilter.State state = new ChatFilter.State();
    private ChatFilter filter;
    private List<String> words;
    private boolean shown;

    @Test
    void shouldSplitTheFilteredWordsAtTheBars() {
        whenTheWordsAreRead("a|b c|");

        thenTheWordsAre("a", "b c", "");
    }

    @Test
    void shouldKeepTheFilteredWordsTrimmedInLowerCaseAndNoneEmpty() {
        whenAFilterIsMadeAgainstTheWords("Fastest Download Speed| |viagra");

        thenTheFilterLeavesOut("fastest download speed", "viagra");
    }

    @Test
    void shouldLeaveOutAMessageWithAFilteredWord() {
        givenTheUsualFilter();

        whenAFriendWrites("get the FASTEST download speed now");

        thenTheMessageIsHidden();
    }

    @Test
    void shouldShowAMessageWithoutFilteredWords() {
        givenTheUsualFilter();

        whenAFriendWrites("hello");

        thenTheMessageIsShown();
    }

    @Test
    void shouldHideStrangersWhenOnlyFriendsAreShown() {
        givenAFilterShowingOnlyFriends();

        whenAStrangerWrites("hello");

        thenTheMessageIsHidden();
    }

    @Test
    void shouldShowFriendsWhenOnlyFriendsAreShown() {
        givenAFilterShowingOnlyFriends();

        whenAFriendWrites("hello");

        thenTheMessageIsShown();
    }

    @Test
    void shouldTakeALinkBeforeWeWroteForSpam() {
        givenTheUsualFilter();

        whenAStrangerWrites("visit www.example.org");

        thenTheMessageIsHidden();
    }

    @Test
    void shouldKeepTakingASpammerForOne() {
        givenTheUsualFilter();
        givenAStrangerWasHiddenForWriting("visit www.example.org");

        whenAStrangerWrites("hello");

        thenTheMessageIsHidden("a spammer stays one");
    }

    @Test
    void shouldNotTakeALinkAfterWeWroteForSpam() {
        givenTheUsualFilter();
        givenWeWrote();

        whenAStrangerWrites("see https://example.org");

        thenTheMessageIsShown();
    }

    @Test
    void shouldNotTakeALinkFromAFriendForSpam() {
        givenTheUsualFilter();

        whenAFriendWrites("www.example.org");

        thenTheMessageIsShown("friends");
    }

    @Test
    void shouldShowThreeUnansweredMessagesAtMost() {
        givenTheUsualFilter();
        givenTheUnansweredMessagesShown(ChatFilter.MAX_UNANSWERED);

        whenAStrangerWrites("hi again");

        thenTheMessageIsHidden();
    }

    @Test
    void shouldShowEverythingWithoutAFilter() {
        givenNoFilter();

        whenAStrangerWrites("www.example.org");

        thenTheMessageIsShown();
    }

    private void givenTheUsualFilter() {
        this.filter = USUAL;
    }

    private void givenAFilterShowingOnlyFriends() {
        this.filter = new ChatFilter(true, List.of(), false);
    }

    private void givenNoFilter() {
        this.filter = ChatFilter.NONE;
    }

    private void givenWeWrote() {
        this.state.wrote();
    }

    private void givenAStrangerWasHiddenForWriting(String message) {
        assertThat(this.filter.shows(this.state, false, message)).isFalse();
    }

    private void givenTheUnansweredMessagesShown(int count) {
        for (int i = 0; i < count; i++) {
            assertThat(this.filter.shows(this.state, false, "hi " + i)).isTrue();
        }
    }

    private void whenTheWordsAreRead(String text) {
        this.words = ChatFilter.words(text);
    }

    private void whenAFilterIsMadeAgainstTheWords(String text) {
        this.filter = new ChatFilter(false, ChatFilter.words(text), true);
    }

    private void whenAFriendWrites(String message) {
        this.shown = this.filter.shows(this.state, true, message);
    }

    private void whenAStrangerWrites(String message) {
        this.shown = this.filter.shows(this.state, false, message);
    }

    private void thenTheWordsAre(String... expected) {
        assertThat(this.words).containsExactly(expected);
    }

    private void thenTheFilterLeavesOut(String... expected) {
        assertThat(this.filter.words()).as("trimmed, in lower case, none empty").containsExactly(expected);
    }

    private void thenTheMessageIsShown() {
        assertThat(this.shown).isTrue();
    }

    private void thenTheMessageIsShown(String why) {
        assertThat(this.shown).as(why).isTrue();
    }

    private void thenTheMessageIsHidden() {
        assertThat(this.shown).isFalse();
    }

    private void thenTheMessageIsHidden(String why) {
        assertThat(this.shown).as(why).isFalse();
    }
}
