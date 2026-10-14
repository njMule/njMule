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
package org.njmule.gui.i18n;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.njmule.gui.infra.Formats;

class RightToLeftTextsTest {

    private static final Set<String> WRITTEN_RIGHT_TO_LEFT = Set.of("ar", "fa", "he");
    // Left-to-right and right-to-left embedding, and its end.
    private static final String LRE = String.valueOf((char) 0x202A);
    private static final String RLE = String.valueOf((char) 0x202B);
    private static final String PDF = String.valueOf((char) 0x202C);

    private I18n texts;
    private String shown;

    static Stream<Locale> languages() {
        return I18n.LANGUAGES.stream();
    }

    @ParameterizedTest
    @MethodSource("languages")
    void onlyArabicPersianAndHebrewAreWrittenRightToLeft(Locale language) {
        this.whenTheTextsOfALanguageAreLoaded(language);

        this.thenTheyAreWrittenRightToLeft(WRITTEN_RIGHT_TO_LEFT.contains(language.getLanguage()));
    }

    @Test
    void aFileNameInAnArabicSentenceKeepsItsOwnDirection() {
        this.givenTheTextsOf(Locale.of("ar"));

        this.whenTheTextIsFilledIn("nj.transfers.completed", "01 - Track (live).mp3");

        this.thenTheTextShows(LRE + "01 - Track (live).mp3" + PDF);
    }

    @Test
    void eachLineKeepsTheDirectionOfItsOwnFirstLetters() {
        this.givenTheTextsOf(Locale.of("ar"));

        this.whenTheTextIsEmbedded("movie.mkv\nفيلم 2\n");

        this.thenTheTextIs(LRE + "movie.mkv" + PDF + "\n" + RLE + "فيلم 2" + PDF + "\n");
    }

    @Test
    void aNumberIsFilledInAsItIs() {
        this.givenTheTextsOf(Locale.of("ar"));

        this.whenTheTextIsFilledIn("nj.a4af.swapped", 12);

        this.thenTheTextEndsWith(": 12");
        this.thenTheTextHasNoDirectionalEmbedding();
    }

    @Test
    void anEnglishSentenceTakesTheFileNameUnchanged() {
        this.givenTheTextsOf(Locale.ENGLISH);

        this.whenTheTextIsFilledIn("nj.transfers.completed", "01 - Track.mp3");

        this.thenTheTextIs("Download finished: 01 - Track.mp3");
    }

    @Test
    void persianWritesNumbersWithLatinDigits() {
        this.givenTheTextsOf(Locale.of("fa"));

        this.whenTheTextIsFilledIn("nj.input.number", 1, 65535);

        this.thenTheTextShows("65535");
    }

    @Test
    void persianSizesHaveLatinDigits() {
        this.givenTheTextsOf(Locale.of("fa"));

        this.whenASizeIsShown(1_234_567);

        this.thenTheTextIs("1.18 MB");
    }

    private void givenTheTextsOf(Locale language) {
        this.texts = I18n.of(language);
    }

    private void whenTheTextsOfALanguageAreLoaded(Locale language) {
        this.texts = I18n.of(language);
    }

    private void whenTheTextIsFilledIn(String key, Object... args) {
        this.shown = this.texts.f(key, args);
    }

    private void whenTheTextIsEmbedded(String text) {
        this.shown = this.texts.embed(text);
    }

    private void whenASizeIsShown(long bytes) {
        this.shown = Formats.of(this.texts).size(bytes);
    }

    private void thenTheyAreWrittenRightToLeft(boolean rightToLeft) {
        assertThat(this.texts.isRightToLeft()).isEqualTo(rightToLeft);
    }

    private void thenTheTextShows(String part) {
        assertThat(this.shown).contains(part);
    }

    private void thenTheTextEndsWith(String end) {
        assertThat(this.shown).endsWith(end);
    }

    private void thenTheTextIs(String text) {
        assertThat(this.shown).isEqualTo(text);
    }

    private void thenTheTextHasNoDirectionalEmbedding() {
        assertThat(this.shown).doesNotContain(LRE, RLE, PDF);
    }
}
