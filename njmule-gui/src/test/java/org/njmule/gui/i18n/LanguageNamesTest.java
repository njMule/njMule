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
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class LanguageNamesTest {

    private String name;

    static Stream<Locale> languages() {
        return Stream.concat(
                I18n.LANGUAGES.stream(), Stream.of(Locale.of("nn"), Locale.of("pt"), Locale.TAIWAN, Locale.of("xx")));
    }

    @ParameterizedTest
    @MethodSource("languages")
    void theNameOfALanguageIsTheOneItsTextsGive(Locale language) {
        this.whenTheLanguageIsNamed(language);
        this.thenTheNameIs(I18n.of(language).name());
    }

    private void whenTheLanguageIsNamed(Locale language) {
        this.name = I18n.nameOf(language);
    }

    private void thenTheNameIs(String name) {
        assertThat(this.name).isEqualTo(name);
    }
}
