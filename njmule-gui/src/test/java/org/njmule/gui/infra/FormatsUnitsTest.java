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
package org.njmule.gui.infra;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.njmule.gui.i18n.I18n;

/** Sizes and rates with the units of the interface's language. */
class FormatsUnitsTest {

    private Formats formats;
    private String size;
    private String rate;

    @Test
    void shouldWriteFrenchSizesAndRatesInOctets() {
        givenTheInterfaceIn(Locale.FRENCH);

        whenASizeAndARateAreShown(1536, 12_595);

        thenTheyRead("1,50 Ko", "12,3 Ko/s");
    }

    @Test
    void shouldKeepTheEnglishUnitsInEnglish() {
        givenTheInterfaceIn(Locale.ENGLISH);

        whenASizeAndARateAreShown(5L << 30, 2048);

        thenTheyRead("5.00 GB", "2.0 KB/s");
    }

    @Test
    void shouldNameSmallSizesWithTheShortUnitOfTheLanguage() {
        givenTheInterfaceIn(Locale.FRENCH);

        whenASizeAndARateAreShown(900, 0);

        thenTheyRead("900 o", "0,0 Ko/s");
    }

    @Test
    void shouldKeepTheEnglishUnitsALanguageDoesNotName() {
        givenUnitsNamedOnlyFor(Map.of("IDS_MBYTES", "Mo"));

        whenASizeAndARateAreShown(3L << 20, 1024);

        thenTheyRead("3.00 Mo", "1.0 KB/s");
    }

    @ParameterizedTest
    @MethodSource("fastRates")
    void shouldWriteFastRatesInMegabytesAndGigabytes(long bytesPerSecond, String shown) {
        givenTheInterfaceIn(Locale.ENGLISH);

        whenARateIsShown(bytesPerSecond);

        thenItReads(shown);
    }

    @ParameterizedTest
    @MethodSource("ratesAroundAThousandKilobytes")
    void shouldKeepKilobytesJustBelowAThousand(long bytesPerSecond, String shown) {
        givenTheInterfaceIn(Locale.ENGLISH);

        whenARateIsShown(bytesPerSecond);

        thenItReads(shown);
    }

    @ParameterizedTest
    @MethodSource("fastFrenchRates")
    void shouldWriteFastRatesWithTheUnitsOfTheLanguage(long bytesPerSecond, String shown) {
        givenTheInterfaceIn(Locale.FRENCH);

        whenARateIsShown(bytesPerSecond);

        thenItReads(shown);
    }

    static Stream<Arguments> fastRates() {
        return Stream.of(
                Arguments.of(1_355_000L, "1.29 MB/s"),
                Arguments.of(100L << 20, "100.0 MB/s"),
                Arguments.of(1_181_116_006L, "1.10 GB/s"));
    }

    static Stream<Arguments> ratesAroundAThousandKilobytes() {
        return Stream.of(Arguments.of(999L * 1024, "999.0 KB/s"), Arguments.of(1000L * 1024, "0.98 MB/s"));
    }

    static Stream<Arguments> fastFrenchRates() {
        return Stream.of(Arguments.of(1_355_000L, "1,29 Mo/s"), Arguments.of(1_181_116_006L, "1,10 Go/s"));
    }

    private void givenTheInterfaceIn(Locale language) {
        this.formats = Formats.of(I18n.of(language));
    }

    private void givenUnitsNamedOnlyFor(Map<String, String> texts) {
        this.formats = new Formats(Locale.ENGLISH, key -> texts.getOrDefault(key, "[" + key + "]"));
    }

    private void whenASizeAndARateAreShown(long bytes, long bytesPerSecond) {
        this.size = this.formats.size(bytes);
        this.rate = this.formats.rate(bytesPerSecond);
    }

    private void whenARateIsShown(long bytesPerSecond) {
        this.rate = this.formats.rate(bytesPerSecond);
    }

    private void thenItReads(String rate) {
        assertThat(this.rate).isEqualTo(rate);
    }

    private void thenTheyRead(String size, String rate) {
        assertThat(this.size).isEqualTo(size);
        assertThat(this.rate).isEqualTo(rate);
    }
}
