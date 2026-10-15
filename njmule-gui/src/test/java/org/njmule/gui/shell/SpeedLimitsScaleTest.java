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
package org.njmule.gui.shell;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class SpeedLimitsScaleTest {

    private static final int FIBRE = 122_070;

    private double position;
    private int limit;

    @ParameterizedTest
    @CsvSource({"0", "5", "50", "300", "1200", "12000", "120000"})
    void aLimitSetIsTheLimitItsPositionGivesBack(int kib) {
        this.whenTheLimitIsPlaced(kib, FIBRE);
        this.whenTheLimitIsRead(FIBRE);
        this.thenTheLimitIs(kib);
    }

    @ParameterizedTest
    @CsvSource({"0, 0", "1000, " + FIBRE})
    void theEndsAreNoLimitAndTheTop(double position, int kib) {
        this.givenThePosition(position);
        this.whenTheLimitIsRead(FIBRE);
        this.thenTheLimitIs(kib);
    }

    @ParameterizedTest
    @CsvSource({"1234, 1200", "98765, 99000"})
    void aDraggedLimitKeepsTwoDigits(int kib, int expected) {
        this.whenTheLimitIsPlaced(kib, FIBRE);
        this.whenTheLimitIsRead(FIBRE);
        this.thenTheLimitIs(expected);
    }

    @ParameterizedTest
    @CsvSource({"50", "500", "5000", "50000"})
    void aLowerLimitSitsLowerOnTheSlider(int kib) {
        this.whenTheLimitIsPlaced(kib, FIBRE);
        this.thenThePositionIsBelow(SpeedLimits.position(kib * 2, FIBRE));
    }

    private void givenThePosition(double position) {
        this.position = position;
    }

    private void whenTheLimitIsPlaced(int kib, int top) {
        this.position = SpeedLimits.position(kib, top);
    }

    private void whenTheLimitIsRead(int top) {
        this.limit = SpeedLimits.kib(this.position, top);
    }

    private void thenTheLimitIs(int kib) {
        assertThat(this.limit).isEqualTo(kib);
    }

    private void thenThePositionIsBelow(double higher) {
        assertThat(this.position).isLessThan(higher);
    }
}
