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

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

class SmileysTest {

    private String shown;

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "hi :) see you ;-) <3|hi 🙂 see you 😉 ❤",
                ":D|😃",
                "http://example.org/:) [::1]:4662 f(x:D)|http://example.org/:) [::1]:4662 f(x:D)"
            })
    void shouldShowTheSmileysStandingAloneAsEmoji(String text, String expected) {
        whenShown(text);

        thenItIs(expected);
    }

    @ParameterizedTest
    @MethodSource("everySmileyFound")
    void shouldShowEverySmileyItFindsAsAnEmoji(String smiley) {
        whenShown(smiley);

        thenItIsNoLonger(smiley);
    }

    // Each smiley the pattern finds: a colon with or without a nose before each mouth, and the others.
    static Stream<String> everySmileyFound() {
        Stream<String> faces =
                Stream.of(":", ":-").flatMap(eyes -> ")(DPpOo|*".chars().mapToObj(mouth -> eyes + (char) mouth));
        return Stream.concat(faces, Stream.of(";)", ";-)", ":'(", "8)", "<3"));
    }

    private void whenShown(String text) {
        this.shown = Smileys.show(text);
    }

    private void thenItIsNoLonger(String smiley) {
        assertThat(this.shown).isNotEqualTo(smiley);
    }

    private void thenItIs(String expected) {
        assertThat(this.shown).isEqualTo(expected);
    }
}
