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
import org.junit.jupiter.api.Test;
import org.njmule.gui.i18n.I18n;

/** Sizes and rates with the units of the interface's language. */
class FormatsUnitsTest {

    private Formats formats;
    private String size;
    private String rate;

    @Test
    void shouldWriteFrenchSizesAndRatesInOctets() {
        this.givenTheInterfaceIn(Locale.FRENCH);

        this.whenASizeAndARateAreShown(1536, 12_595);

        this.thenTheyRead("1,50 Ko", "12,3 Ko/s");
    }

    @Test
    void shouldKeepTheEnglishUnitsInEnglish() {
        this.givenTheInterfaceIn(Locale.ENGLISH);

        this.whenASizeAndARateAreShown(5L << 30, 2048);

        this.thenTheyRead("5.00 GB", "2.0 KB/s");
    }

    @Test
    void shouldNameSmallSizesWithTheShortUnitOfTheLanguage() {
        this.givenTheInterfaceIn(Locale.FRENCH);

        this.whenASizeAndARateAreShown(900, 0);

        this.thenTheyRead("900 o", "0,0 Ko/s");
    }

    @Test
    void shouldKeepTheEnglishUnitsALanguageDoesNotName() {
        this.givenUnitsNamedOnlyFor(Map.of("IDS_MBYTES", "Mo"));

        this.whenASizeAndARateAreShown(3L << 20, 1024);

        this.thenTheyRead("3.00 Mo", "1.0 KB/s");
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

    private void thenTheyRead(String size, String rate) {
        assertThat(this.size).isEqualTo(size);
        assertThat(this.rate).isEqualTo(rate);
    }
}
