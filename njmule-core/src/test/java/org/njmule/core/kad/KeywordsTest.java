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
package org.njmule.core.kad;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Stream;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class KeywordsTest {

    private List<String> keywords;
    private char[] lowered;

    @ParameterizedTest
    @ValueSource(strings = {"", Keywords.DELIMITERS})
    void shouldFindNoKeywordInTextWithoutWords(String text) {
        whenTheKeywordsAreTakenFrom(text);

        thenThereIsNoKeyword();
    }

    @Test
    void shouldFindNoKeywordInWordsUnderThreeBytes() {
        whenTheKeywordsAreTakenFrom("ab cd");

        thenThereIsNoKeyword("words under three bytes");
    }

    @ParameterizedTest
    @MethodSource("delimiters")
    void shouldSplitTheWordsAtEveryDelimiter(char delimiter) {
        whenTheKeywordsAreTakenFrom("alpha" + delimiter + "beta" + delimiter);

        thenTheKeywordsAre(List.of("alpha", "beta"), String.valueOf(delimiter));
    }

    @Test
    void shouldKeepOtherSignsInsideTheWords() {
        whenTheKeywordsAreTakenFrom("rock&roll mp3+ost it's #one.");

        thenTheKeywordsAre(List.of("rock&roll", "mp3+ost", "it's", "#one"));
    }

    @Test
    void shouldTakeOneCjkCharacterAsAWordOfThreeBytes() {
        whenTheKeywordsAreTakenFrom("日 x");

        thenTheKeywordsAre(List.of("日"), "one CJK character is three bytes");
    }

    @Test
    void shouldTakeTwoCharactersOfThreeBytesAsAWord() {
        whenTheKeywordsAreTakenFrom("éa");

        thenTheKeywordsAre(List.of("éa"), "two characters, three bytes");
    }

    @Test
    void shouldNotTakeALastWordOfTwoCharactersAndThreeBytesForAnExtension() {
        whenTheKeywordsAreTakenFrom("abc éa");

        thenTheKeywordsAre(List.of("abc", "éa"), "not taken for an extension");
    }

    @Test
    void shouldMoveARepeatedLastWordToTheEndThenDropItAsTheExtension() {
        whenTheKeywordsAreTakenFrom("abc def abc");

        thenTheKeywordsAre(List.of("def"), "the repeated last word moves to the end, then goes as the extension");
    }

    @ParameterizedTest
    @MethodSource("lastParts")
    void shouldApplyTheExtensionRuleToTheLastPartOnly(String text, List<String> expected) {
        whenTheKeywordsAreTakenFrom(text);

        thenTheKeywordsAre(expected);
    }

    @Test
    void shouldKeepALastPartOfFourLetters() {
        whenTheKeywordsAreTakenFrom("film.mpeg");

        thenTheKeywordsAre(List.of("film", "mpeg"), "four letters stay");
    }

    @ParameterizedTest
    @MethodSource("networkLowerCase")
    void shouldTurnTheWordsToTheNetworkLowerCase(String text, List<String> expected) {
        whenTheKeywordsAreTakenFrom(text);

        thenTheKeywordsAre(expected);
    }

    @Test
    void shouldLeaveTheDottedCapitalIUnchanged() {
        whenTheKeywordsAreTakenFrom("İSTANBUL");

        thenTheKeywordsAre(List.of("İstanbul"), "dotted capital I unchanged");
    }

    @Test
    void shouldNotComposeCombiningMarks() {
        whenTheKeywordsAreTakenFrom("école");

        thenTheKeywordsAre(List.of("école"), "combining marks are not composed");
    }

    @Test
    void shouldKeepTheLowerCaseTableWholeAndStable() {
        whenEveryCodeUnitIsLowered();

        thenLoweringAgainChangesNothing();
        thenTheUnitsTheTableChangesAre(873);
        thenTheAsciiLettersAreLoweredAsUsual();
    }

    @Property
    void shouldTakeKeywordsThatAreLowerCaseLongEnoughAndDistinct(@ForAll String text) {
        whenTheKeywordsAreTakenFrom(text);

        thenTheKeywordsAreDistinct();
        thenEveryKeywordIsLowerCaseLongEnoughAndFreeOfDelimiters();
        thenTheKeywordsOfTheKeywordsAreTheSame();
    }

    static Stream<Character> delimiters() {
        return Keywords.DELIMITERS.chars().mapToObj(c -> (char) c);
    }

    static Stream<Arguments> lastParts() {
        return Stream.of(
                Arguments.of("Movie MOVIE movie", List.of("movie")),
                Arguments.of("film.mkv", List.of("film")),
                Arguments.of("film.avi.", List.of("film", "avi")));
    }

    static Stream<Arguments> networkLowerCase() {
        return Stream.of(
                Arguments.of("STRASSE Straße", List.of("strasse", "straße")),
                Arguments.of("Ünïcode", List.of("ünïcode")),
                Arguments.of("🎵music", List.of("🎵music")));
    }

    private void whenTheKeywordsAreTakenFrom(String text) {
        this.keywords = Keywords.of(text);
    }

    private void whenEveryCodeUnitIsLowered() {
        this.lowered = new char[0x10000];
        for (int c = 0; c <= 0xFFFF; c++) {
            this.lowered[c] = KadLowerCase.toLowerCase((char) c);
        }
    }

    private void thenThereIsNoKeyword() {
        assertThat(this.keywords).isEmpty();
    }

    private void thenThereIsNoKeyword(String because) {
        assertThat(this.keywords).as(because).isEmpty();
    }

    private void thenTheKeywordsAre(List<String> expected) {
        assertThat(this.keywords).containsExactlyElementsOf(expected);
    }

    private void thenTheKeywordsAre(List<String> expected, String because) {
        assertThat(this.keywords).as(because).containsExactlyElementsOf(expected);
    }

    private void thenLoweringAgainChangesNothing() {
        for (int c = 0; c <= 0xFFFF; c++) {
            char lower = this.lowered[c];
            assertThat(KadLowerCase.toLowerCase(lower)).as("U+%04X", c).isEqualTo(lower);
        }
    }

    private void thenTheUnitsTheTableChangesAre(int count) {
        int mapped = 0;
        for (int c = 0; c <= 0xFFFF; c++) {
            if (this.lowered[c] != c) {
                mapped++;
            }
        }
        assertThat(mapped).as("every unit of the table is found").isEqualTo(count);
    }

    private void thenTheAsciiLettersAreLoweredAsUsual() {
        for (char c = 'A'; c <= 'Z'; c++) {
            assertThat(this.lowered[c]).isEqualTo(Character.toLowerCase(c));
        }
    }

    private void thenTheKeywordsAreDistinct() {
        assertThat(this.keywords).doesNotHaveDuplicates();
    }

    private void thenEveryKeywordIsLowerCaseLongEnoughAndFreeOfDelimiters() {
        for (String w : this.keywords) {
            assertThat(w.getBytes(StandardCharsets.UTF_8).length).isGreaterThanOrEqualTo(3);
            assertThat(w).isEqualTo(KadLowerCase.toLowerCase(w));
            assertThat(w.chars()).noneMatch(c -> Keywords.DELIMITERS.indexOf(c) >= 0);
        }
    }

    private void thenTheKeywordsOfTheKeywordsAreTheSame() {
        assertThat(Keywords.of(String.join(" ", this.keywords) + " ")).containsExactlyElementsOf(this.keywords);
    }
}
