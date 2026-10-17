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
package org.njmule.gui.screens;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.njmule.core.api.KadContactInfo;

class DistanceChartTest {

    private final List<KadContactInfo> contacts = new ArrayList<>();
    private int bar;
    private int[] counts;

    @ParameterizedTest
    @CsvSource({
        "00000000000000000000000000000000, 0",
        "00000000010000000000000000000000, 0",
        "00000400000000000000000000000000, 2",
        "0FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF, 19",
        "8000000000000000000000000000000A, 23",
        "FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF, 23"
    })
    void shouldPutTheContactsThatShareMoreLeadingBitsWithUsInEarlierBars(String distance, int expected) {
        whenTheBarOfIsTaken(distance);

        thenTheBarIs(expected);
    }

    @Test
    void shouldCountTheContactsOfEachDistance() {
        givenContactsAt(
                "FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF",
                "8000000000000000000000000000000A",
                "0FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF",
                "00000000000000000000000000000001");

        whenTheContactsAreCounted();

        thenTheCountsAre(0, 1, 19, 1, 23, 2);
    }

    @Test
    void shouldLeaveOutADistanceThatIsNotAnId() {
        givenContactsAt("not an id", "FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF");

        whenTheContactsAreCounted();

        thenTheCountsAre(23, 1);
    }

    private void givenContactsAt(String... distances) {
        for (String distance : distances) {
            this.contacts.add(new KadContactInfo(distance, "198.51.100.1:4672", 0, distance, 8, true));
        }
    }

    private void whenTheBarOfIsTaken(String distance) {
        this.bar = DistanceChart.bar(distance);
    }

    private void whenTheContactsAreCounted() {
        this.counts = DistanceChart.counts(this.contacts);
    }

    private void thenTheBarIs(int expected) {
        assertThat(this.bar).isEqualTo(expected);
    }

    // Pairs of a bar and its count; every other bar is empty.
    private void thenTheCountsAre(int... barsAndCounts) {
        int[] expected = new int[DistanceChart.BARS];
        for (int i = 0; i < barsAndCounts.length; i += 2) {
            expected[barsAndCounts[i]] = barsAndCounts[i + 1];
        }
        assertThat(this.counts).containsExactly(expected);
    }
}
