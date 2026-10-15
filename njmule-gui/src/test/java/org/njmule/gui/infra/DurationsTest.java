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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.njmule.gui.i18n.I18n;

class DurationsTest {

    private Formats formats;
    private String shown;

    @ParameterizedTest
    @CsvSource({
        "it, 9, 9 sec",
        "it, 3562, 59:22 min",
        "it, 3900, 1:05 h",
        "it, 176400, 2 g 1 h",
        "de, 176400, 2 T 1 H",
        "en, 247, 4:07 mins"
    })
    void aDurationIsWrittenInTheUnitsOfTheLanguage(String language, long seconds, String expected) {
        this.givenTheFormatsOf(language);
        this.whenTheDurationIsWritten(seconds);
        this.thenItReads(expected);
    }

    private void givenTheFormatsOf(String language) {
        this.formats = Formats.of(I18n.of(Locale.forLanguageTag(language)));
    }

    private void whenTheDurationIsWritten(long seconds) {
        this.shown = this.formats.duration(seconds);
    }

    private void thenItReads(String expected) {
        assertThat(this.shown).isEqualTo(expected);
    }
}
