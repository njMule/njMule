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

import java.util.List;
import java.util.function.ToLongFunction;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.geometry.NodeOrientation;
import javafx.scene.layout.Region;
import javafx.scene.shape.Polyline;
import org.njmule.gui.model.EngineModel;

/**
 * The rates of the last half minute as two small lines, down and up, on one scale: a glance at how they go, beside the
 * numbers. Time runs left to right in every language, as on the statistics' graph.
 */
final class Sparkline extends Region {

    /** The seconds drawn. */
    static final int SAMPLES = 30;

    private static final double WIDTH = 56;
    private static final double HEIGHT = 22;
    // Half the stroke: the lines are not cut at the edges.
    private static final double INSET = 1;

    private final ObservableList<EngineModel.RateSample> rates;
    private final Polyline down = line("sparkline-down");
    private final Polyline up = line("sparkline-up");

    Sparkline(ObservableList<EngineModel.RateSample> rates) {
        this.rates = rates;
        getStyleClass().add("sparkline");
        setNodeOrientation(NodeOrientation.LEFT_TO_RIGHT);
        setMinSize(WIDTH, HEIGHT);
        setPrefSize(WIDTH, HEIGHT);
        setMaxSize(WIDTH, HEIGHT);
        // Up first: the download line, the one more often watched, is drawn over it.
        getChildren().addAll(this.up, this.down);
        rates.addListener((ListChangeListener<EngineModel.RateSample>) c -> requestLayout());
    }

    @Override
    protected void layoutChildren() {
        List<EngineModel.RateSample> last =
                this.rates.subList(Math.max(0, this.rates.size() - SAMPLES), this.rates.size());
        long top = 1;
        for (EngineModel.RateSample s : last) {
            top = Math.max(top, Math.max(s.down(), s.up()));
        }
        draw(this.down, last, EngineModel.RateSample::down, top);
        draw(this.up, last, EngineModel.RateSample::up, top);
    }

    /** The points of the download line, x then y, for tests. */
    List<Double> downPoints() {
        layoutChildren();
        return List.copyOf(this.down.getPoints());
    }

    // The newest sample at the right edge, one step a second, the bottom for nothing.
    private void draw(
            Polyline line,
            List<EngineModel.RateSample> samples,
            ToLongFunction<EngineModel.RateSample> rate,
            long top) {
        // Before its first layout it has the size it always takes.
        double w = (getWidth() > 0 ? getWidth() : WIDTH) - 2 * INSET;
        double h = (getHeight() > 0 ? getHeight() : HEIGHT) - 2 * INSET;
        double step = w / (SAMPLES - 1);
        Double[] points = new Double[samples.size() * 2];
        for (int i = 0; i < samples.size(); i++) {
            double x = INSET + w - (samples.size() - 1 - i) * step;
            double y = INSET + h - h * rate.applyAsLong(samples.get(i)) / top;
            points[2 * i] = x;
            points[2 * i + 1] = y;
        }
        line.getPoints().setAll(points);
    }

    private static Polyline line(String style) {
        Polyline line = new Polyline();
        line.getStyleClass().add(style);
        line.setManaged(false);
        return line;
    }
}
