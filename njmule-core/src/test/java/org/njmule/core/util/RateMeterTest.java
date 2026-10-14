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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class RateMeterTest {

    private static final long SECOND = 1_000_000_000L;

    @Test
    void averagesTheLastSeconds() {
        RateMeter meter = new RateMeter(4);
        long start = 100 * SECOND;

        meter.add(4_000, start);
        meter.add(4_000, start + SECOND);
        assertThat(meter.rate(start + SECOND)).isEqualTo(2_000);
        assertThat(meter.rate(start + 3 * SECOND)).isEqualTo(2_000);
        // The first second leaves the window, then the second one.
        assertThat(meter.rate(start + 4 * SECOND)).isEqualTo(1_000);
        assertThat(meter.rate(start + 5 * SECOND)).isZero();
        assertThat(meter.total()).isEqualTo(8_000);
    }

    @Test
    void aLongPauseClearsTheWindow() {
        RateMeter meter = new RateMeter(3);
        meter.add(900, 0);

        meter.add(300, 60 * SECOND);

        assertThat(meter.rate(60 * SECOND)).isEqualTo(100);
        assertThat(meter.total()).isEqualTo(1_200);
    }

    @Test
    void aClockGoingBackwardsCountsInTheLatestSecond() {
        RateMeter meter = new RateMeter(2);
        meter.add(1_000, 10 * SECOND);

        meter.add(1_000, 5 * SECOND);

        assertThat(meter.rate(10 * SECOND)).isEqualTo(1_000);
        assertThat(meter.rate(12 * SECOND)).isZero();
        assertThat(meter.total()).isEqualTo(2_000);
    }

    @Test
    void negativeTimesAndTheirCrossingOfZeroWork() {
        RateMeter meter = new RateMeter(2);
        meter.add(400, -SECOND / 2);
        meter.add(600, SECOND / 2);

        assertThat(meter.rate(SECOND / 2)).isEqualTo(500);
        assertThat(meter.rate(Long.MAX_VALUE)).isZero();
        meter.add(10, Long.MIN_VALUE + 1);
        assertThat(meter.total()).isEqualTo(1_010);
    }

    @Test
    void needsAWindow() {
        assertThatThrownBy(() -> new RateMeter(0)).isInstanceOf(IllegalArgumentException.class);
    }
}
