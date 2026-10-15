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

import java.time.Instant;
import java.util.Optional;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.njmule.gui.FxTesting;
import org.njmule.gui.model.EngineModel;

/** The rates graph over the time chosen, and the rates under the pointer. */
@Timeout(60)
class RateGraphTest {

    private static final int WIDTH = 600;

    private final ObservableList<EngineModel.RateSample> seconds = FXCollections.observableArrayList();
    private final ObservableList<EngineModel.RateSample> minutes = FXCollections.observableArrayList();
    private RateGraph graph;
    private Stage stage;
    private Optional<String> pointed;

    @AfterEach
    void close() throws Exception {
        if (this.stage != null) {
            FxTesting.onFx(() -> {
                this.stage.close();
                return null;
            });
        }
    }

    @Test
    void shouldTellTheTimeAndRatesOfTheSecondUnderThePointer() throws Exception {
        givenAGraphOfSecondsAndMinutes();

        whenThePointerIsAtTheRightEdge();

        thenThePointedRatesAre("T1599", 1599, 15);
    }

    @Test
    void shouldTellTheMeanRatesOfTheMinuteUnderThePointerOverTheLastDay() throws Exception {
        givenAGraphOfSecondsAndMinutes();
        givenTheRange(RateGraph.Range.DAY);

        whenThePointerIsAtTheRightEdge();

        thenThePointedRatesAre("T60000", 7000, 70);
    }

    @Test
    void shouldTellNothingOnceThePointerLeaves() throws Exception {
        givenAGraphOfSecondsAndMinutes();
        givenThePointerAtTheRightEdge();

        whenThePointerLeaves();

        thenNothingIsPointed();
    }

    // A second's rates go up with the second; a minute's are 7000 and 70 for the last one.
    private void givenAGraphOfSecondsAndMinutes() throws Exception {
        for (int s = 1000; s < 1600; s++) {
            this.seconds.add(new EngineModel.RateSample(Instant.ofEpochSecond(s), s, s / 100));
        }
        for (int m = 1; m <= 1000; m++) {
            this.minutes.add(new EngineModel.RateSample(Instant.ofEpochSecond(m * 60L), m * 7L, m * 7L / 100));
        }
        FxTesting.start();
        FxTesting.onFx(() -> {
            this.graph = new RateGraph(
                    this.seconds,
                    this.minutes,
                    RateGraph.swatch("legend-down"),
                    RateGraph.swatch("legend-up"),
                    new Subscriptions(),
                    r -> String.valueOf(r),
                    t -> "T" + t.getEpochSecond());
            this.stage = new Stage();
            this.stage.setScene(new Scene(new StackPane(this.graph), WIDTH, 200));
            this.stage.show();
            return null;
        });
    }

    private void givenTheRange(RateGraph.Range range) throws Exception {
        FxTesting.onFx(() -> {
            this.graph.setRange(range);
            return null;
        });
    }

    private void givenThePointerAtTheRightEdge() throws Exception {
        whenThePointerIsAtTheRightEdge();
        assertThat(this.pointed).isPresent();
    }

    private void whenThePointerIsAtTheRightEdge() throws Exception {
        this.pointed = FxTesting.onFx(() -> {
            this.graph.point(this.graph.getWidth());
            return this.graph.pointed();
        });
    }

    private void whenThePointerLeaves() throws Exception {
        this.pointed = FxTesting.onFx(() -> {
            this.graph.point(-1);
            return this.graph.pointed();
        });
    }

    private void thenThePointedRatesAre(String time, long down, long up) {
        assertThat(this.pointed).contains(time + "  ↓ " + down + "  ↑ " + up);
    }

    private void thenNothingIsPointed() {
        assertThat(this.pointed).isEmpty();
    }
}
