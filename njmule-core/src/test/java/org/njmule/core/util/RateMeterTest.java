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
package org.njmule.core.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class RateMeterTest {

    private static final long SECOND = 1_000_000_000L;
    private static final long START = 100 * SECOND;

    private RateMeter meter;
    private long rate;
    private Throwable exception;

    // A second counts once it is over, then for four seconds: the second under way would make the average dip.
    @ParameterizedTest
    @CsvSource({"1, 1000", "2, 2000", "4, 2000", "5, 1000", "6, 0"})
    void shouldAverageTheLastSeconds(long secondsLater, long expected) {
        givenAMeterOver(4);
        givenBytesAddedAt(4_000, START);
        givenBytesAddedAt(4_000, START + SECOND);

        whenTheRateIsReadAt(START + secondsLater * SECOND);

        thenTheRateIs(expected);
    }

    @Test
    void shouldKeepTheRateWhileASecondHasJustBegun() {
        givenAMeterOver(2);
        givenBytesAddedAt(1_000, 10 * SECOND);
        givenBytesAddedAt(1_000, 11 * SECOND);
        givenBytesAddedAt(50, 12 * SECOND);

        whenTheRateIsReadAt(12 * SECOND + SECOND / 10);

        thenTheRateIs(1_000);
    }

    @Test
    void shouldCountEveryByteInTheTotal() {
        givenAMeterOver(4);
        givenBytesAddedAt(4_000, START);

        whenBytesAreAddedAt(4_000, START + SECOND);

        thenTheTotalIs(8_000);
    }

    @Test
    void shouldClearTheWindowAfterALongPause() {
        givenAMeterOver(3);
        givenBytesAddedAt(900, 0);

        whenBytesAreAddedAt(300, 60 * SECOND);

        thenTheRateAtIs(61 * SECOND, 100);
        thenTheTotalIs(1_200);
    }

    @Test
    void shouldCountBytesOfAClockGoingBackwardsInTheLatestSecond() {
        givenAMeterOver(2);
        givenBytesAddedAt(1_000, 10 * SECOND);

        whenBytesAreAddedAt(1_000, 5 * SECOND);

        thenTheRateAtIs(11 * SECOND, 1_000);
        thenTheRateAtIs(13 * SECOND, 0);
        thenTheTotalIs(2_000);
    }

    @Test
    void shouldAverageAcrossTheCrossingOfZero() {
        givenAMeterOver(2);
        givenBytesAddedAt(400, -SECOND / 2);

        whenBytesAreAddedAt(600, SECOND / 2);

        thenTheRateAtIs(SECOND + SECOND / 2, 500);
        thenTheRateAtIs(Long.MAX_VALUE, 0);
    }

    @Test
    void shouldCountBytesAddedAtTheMostNegativeTimes() {
        givenAMeterOver(2);
        givenBytesAddedAt(400, -SECOND / 2);
        givenBytesAddedAt(600, SECOND / 2);
        givenTheRateWasReadAt(Long.MAX_VALUE);

        whenBytesAreAddedAt(10, Long.MIN_VALUE + 1);

        thenTheTotalIs(1_010);
    }

    @Test
    void shouldNeedAWindow() {
        whenAMeterIsMadeOver(0);

        thenExceptionOccurred(IllegalArgumentException.class);
    }

    private void givenAMeterOver(int seconds) {
        this.meter = new RateMeter(seconds);
    }

    private void givenBytesAddedAt(long bytes, long now) {
        this.meter.add(bytes, now);
    }

    private void givenTheRateWasReadAt(long now) {
        this.meter.rate(now);
    }

    private void whenBytesAreAddedAt(long bytes, long now) {
        this.meter.add(bytes, now);
    }

    private void whenTheRateIsReadAt(long now) {
        this.rate = this.meter.rate(now);
    }

    private void whenAMeterIsMadeOver(int seconds) {
        this.exception = catchThrowable(() -> new RateMeter(seconds));
    }

    private void thenTheRateIs(long expected) {
        assertThat(this.rate).isEqualTo(expected);
    }

    private void thenTheRateAtIs(long now, long expected) {
        assertThat(this.meter.rate(now)).isEqualTo(expected);
    }

    private void thenTheTotalIs(long expected) {
        assertThat(this.meter.total()).isEqualTo(expected);
    }

    private void thenExceptionOccurred(Class<? extends Throwable> type) {
        assertThat(this.exception).isInstanceOf(type);
    }
}
