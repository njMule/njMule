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
package org.njmule.core.search;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** The edit distance of two names. */
class LevenshteinTest {

    private int distance;

    @ParameterizedTest(name = "{0} to {1}: {2}")
    @CsvSource({
        "kitten,sitting,3",
        "flaw,lawn,2",
        "'',abc,3",
        "abc,'',3",
        "same,same,0",
        "great movie 2026,great movie 2025,1"
    })
    void shouldCountTheFewestEditsFromOneNameToTheOther(String a, String b, int expected) {
        whenTheDistanceIsTaken(a, b);

        thenItIs(expected);
    }

    private void whenTheDistanceIsTaken(String a, String b) {
        this.distance = Levenshtein.distance(a, b);
    }

    private void thenItIs(int expected) {
        assertThat(this.distance).isEqualTo(expected);
    }
}
