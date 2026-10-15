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
package org.njmule.gui.ui;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class RateGraphScaleTest {

    private long top;

    @ParameterizedTest
    @CsvSource({"0, 1", "1024, 1", "1025, 2", "3000, 5", "9216, 10", "512000, 500"})
    void theScaleRoundsTheHighestRateUpToOneTwoOrFiveKilobytes(long highest, long expectedKib) {
        this.whenTheScaleIsTakenFor(highest);
        this.thenItsTopIsKib(expectedKib);
    }

    // From a thousand KB/s on, the rates read in MB/s (and GB/s): the scale rounds whole units of those.
    @ParameterizedTest
    @CsvSource({"614400, 1", "1355000, 2", "51200000, 50", "1073741824, 1024"})
    void aboveAThousandKilobytesTheScaleRoundsMegabytes(long highest, long expectedMib) {
        this.whenTheScaleIsTakenFor(highest);
        this.thenItsTopIsKib(expectedMib * 1024);
    }

    private void whenTheScaleIsTakenFor(long highest) {
        this.top = RateGraph.scale(highest);
    }

    private void thenItsTopIsKib(long expected) {
        assertThat(this.top).isEqualTo(expected * 1024);
    }
}
