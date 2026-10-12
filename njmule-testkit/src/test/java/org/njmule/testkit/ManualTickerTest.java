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
package org.njmule.testkit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class ManualTickerTest {

    @Test
    void movesOnlyWhenAdvanced() {
        ManualTicker ticker = new ManualTicker(5);
        assertThat(ticker.nanoTime()).isEqualTo(5);

        ticker.advance(Duration.ofMillis(3));
        assertThat(ticker.millis()).isEqualTo(3);
        assertThat(new ManualTicker().nanoTime()).isZero();
    }

    @Test
    void cannotGoBack() {
        assertThatThrownBy(() -> new ManualTicker().advance(Duration.ofNanos(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
