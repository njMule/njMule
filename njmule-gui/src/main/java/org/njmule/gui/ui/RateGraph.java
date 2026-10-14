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

import java.util.List;
import java.util.function.ToLongFunction;
import javafx.collections.ObservableList;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.Background;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.paint.Paint;
import org.njmule.gui.model.EngineModel;

/**
 * The download and upload rates of the last minutes as two filled lines, scaled to the highest rate shown. Its colours
 * come from the theme tokens of the legend swatches, so it follows the theme.
 */
public final class RateGraph extends Region {

    /** Seconds shown. */
    public static final int SPAN = 600;

    private static final double FILL_OPACITY = 0.25;

    private final Canvas canvas = new Canvas();
    private final ObservableList<EngineModel.RateSample> samples;
    private final Region down;
    private final Region up;
    private final Region grid = swatch("graph-grid");
    private long highest;

    /**
     * @param down the legend swatch of the download line
     * @param up the legend swatch of the upload line
     * @param subscriptions where the graph's following of {@code samples} is kept
     */
    public RateGraph(
            ObservableList<EngineModel.RateSample> samples, Region down, Region up, Subscriptions subscriptions) {
        this.samples = samples;
        this.down = down;
        this.up = up;
        this.getStyleClass().add("rate-graph");
        this.grid.setManaged(false);
        this.grid.setVisible(false);
        this.getChildren().addAll(this.canvas, this.grid);
        this.setMinHeight(160);
        this.setPrefHeight(260);
        subscriptions.onListChange(samples, c -> this.draw());
        for (Region r : List.of(down, up, this.grid)) {
            r.backgroundProperty().addListener((o, was, now) -> this.draw());
        }
    }

    /** A small coloured box for a legend, styled by {@code styleClass}. */
    public static Region swatch(String styleClass) {
        Region region = new Region();
        region.getStyleClass().add(styleClass);
        return region;
    }

    /** The highest rate in the last drawing, bytes per second. */
    public long highest() {
        return this.highest;
    }

    @Override
    protected void layoutChildren() {
        this.canvas.setWidth(this.getWidth());
        this.canvas.setHeight(this.getHeight());
        this.draw();
    }

    private void draw() {
        double w = this.canvas.getWidth();
        double h = this.canvas.getHeight();
        GraphicsContext g = this.canvas.getGraphicsContext2D();
        g.clearRect(0, 0, w, h);
        if (w < 2 || h < 2) {
            return;
        }
        int from = Math.max(0, this.samples.size() - SPAN);
        List<EngineModel.RateSample> shown = this.samples.subList(from, this.samples.size());
        long max = 1024;
        for (EngineModel.RateSample s : shown) {
            max = Math.max(max, Math.max(s.down(), s.up()));
        }
        this.highest = max;
        g.setStroke(color(this.grid, Color.gray(0.5, 0.4)));
        g.setLineWidth(1);
        for (int i = 1; i < 4; i++) {
            double y = Math.round(h * i / 4.0) + 0.5;
            g.strokeLine(0, y, w, y);
        }
        this.line(g, shown, EngineModel.RateSample::down, max, color(this.down, Color.STEELBLUE));
        this.line(g, shown, EngineModel.RateSample::up, max, color(this.up, Color.SEAGREEN));
    }

    private void line(
            GraphicsContext g,
            List<EngineModel.RateSample> shown,
            ToLongFunction<EngineModel.RateSample> rate,
            long max,
            Color color) {
        if (shown.isEmpty()) {
            return;
        }
        double w = this.canvas.getWidth();
        double h = this.canvas.getHeight();
        double step = w / (SPAN - 1);
        double x0 = w - (shown.size() - 1) * step;
        int n = shown.size();
        double[] xs = new double[n + 2];
        double[] ys = new double[n + 2];
        for (int i = 0; i < n; i++) {
            xs[i] = x0 + i * step;
            ys[i] = h - (double) rate.applyAsLong(shown.get(i)) / max * (h - 4);
        }
        xs[n] = xs[n - 1];
        ys[n] = h;
        xs[n + 1] = xs[0];
        ys[n + 1] = h;
        g.setFill(color.deriveColor(0, 1, 1, FILL_OPACITY));
        g.fillPolygon(xs, ys, n + 2);
        g.setStroke(color);
        g.setLineWidth(1.5);
        g.strokePolyline(xs, ys, n);
    }

    private static Color color(Region swatch, Color fallback) {
        Background background = swatch.getBackground();
        if (background != null && !background.getFills().isEmpty()) {
            Paint fill = background.getFills().getFirst().getFill();
            if (fill instanceof Color c) {
                return c;
            }
        }
        return fallback;
    }
}
