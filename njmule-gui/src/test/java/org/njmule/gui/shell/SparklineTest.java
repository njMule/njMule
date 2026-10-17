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

import java.time.Instant;
import java.util.List;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import org.assertj.core.data.Offset;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.njmule.gui.FxTesting;
import org.njmule.gui.model.EngineModel;

@Timeout(30)
class SparklineTest {

    private final ObservableList<EngineModel.RateSample> rates = FXCollections.observableArrayList();
    private Sparkline line;
    private List<Double> points;

    @BeforeAll
    static void startFx() throws InterruptedException {
        FxTesting.start();
    }

    @Test
    void shouldDrawTheNewestRateAtTheRightEdgeAndTheFastestAtTheTop() throws Exception {
        givenTheLine();

        whenTheRatesAre(0, 512, 1024);

        // One step a second of the 54 pixels inside the edges, the oldest of the three two steps left of the newest.
        thenThePointsAre(55 - 2 * 54.0 / 29, 21, 55 - 54.0 / 29, 11, 55, 1);
    }

    @Test
    void shouldDrawOnlyTheLastHalfMinute() throws Exception {
        givenTheLine();
        givenRatesFor(Sparkline.SAMPLES + 10);

        whenTheRatesAre(2048);

        thenTheLineHasPoints(Sparkline.SAMPLES);
    }

    private void givenTheLine() throws Exception {
        this.line = FxTesting.onFx(() -> new Sparkline(this.rates));
    }

    private void givenRatesFor(int seconds) throws Exception {
        FxTesting.onFx(() -> {
            for (int i = 0; i < seconds; i++) {
                this.rates.add(new EngineModel.RateSample(Instant.EPOCH.plusSeconds(i), 1024, 0));
            }
        });
    }

    private void whenTheRatesAre(long... down) throws Exception {
        FxTesting.onFx(() -> {
            for (long rate : down) {
                this.rates.add(new EngineModel.RateSample(Instant.EPOCH, rate, 0));
            }
        });
        this.points = FxTesting.onFx(this.line::downPoints);
    }

    private void thenThePointsAre(double... xy) {
        assertThat(this.points).hasSize(xy.length);
        for (int i = 0; i < xy.length; i++) {
            assertThat(this.points.get(i)).as("coordinate " + i).isCloseTo(xy[i], Offset.offset(0.01));
        }
    }

    private void thenTheLineHasPoints(int count) {
        assertThat(this.points).hasSize(2 * count);
    }
}
