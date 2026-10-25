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
package org.njmule.core.remote;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class RateHistoryTest {

    private final RateHistory history = new RateHistory();
    private List<RateHistory.Rate> rates;

    @Test
    void shouldKeepTheSecondsOfTheLastHourOnly() {
        givenSecondsWithRisingRates(RateHistory.SECONDS_KEPT + 1);

        whenTheSecondsAreRead(RateHistory.SECONDS_KEPT);

        thenThereAre(RateHistory.SECONDS_KEPT);
        thenTheFirstIs(new RateHistory.Rate(1, 2));
    }

    @Test
    void shouldGiveTheMeanOfEachMinute() {
        givenSecondsWithRisingRates(60);

        whenTheMinutesAreRead(1);

        thenTheRatesAre(new RateHistory.Rate(29, 59));
    }

    @Test
    void shouldGiveNoMinuteBeforeOneEnds() {
        givenSecondsWithRisingRates(59);

        whenTheMinutesAreRead(1);

        thenThereAre(0);
    }

    // Second i downloads i bytes a second and uploads twice as many.
    private void givenSecondsWithRisingRates(int count) {
        for (int i = 0; i < count; i++) {
            this.history.add(i, 2L * i);
        }
    }

    private void whenTheSecondsAreRead(int count) {
        this.rates = this.history.seconds(count);
    }

    private void whenTheMinutesAreRead(int count) {
        this.rates = this.history.minutes(count);
    }

    private void thenThereAre(int count) {
        assertThat(this.rates).hasSize(count);
    }

    private void thenTheFirstIs(RateHistory.Rate rate) {
        assertThat(this.rates.getFirst()).isEqualTo(rate);
    }

    private void thenTheRatesAre(RateHistory.Rate... expected) {
        assertThat(this.rates).containsExactly(expected);
    }
}
