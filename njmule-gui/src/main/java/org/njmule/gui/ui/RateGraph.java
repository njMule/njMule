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
import java.util.function.LongFunction;
import java.util.function.ToLongFunction;
import javafx.collections.ObservableList;
import javafx.geometry.NodeOrientation;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Label;
import javafx.scene.layout.Background;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.paint.Paint;
import org.njmule.gui.model.EngineModel;

/**
 * The download and upload rates of the last minutes as two filled lines, on a scale rounded up from the highest rate
 * shown and labelled at its top and middle. Its colours come from the theme tokens of the legend swatches, so it
 * follows the theme.
 */
public final class RateGraph extends Region {

    /** Seconds shown. */
    public static final int SPAN = 600;

    private static final double FILL_OPACITY = 0.25;
    private static final long GIB = 1L << 30;

    private final Canvas canvas = new Canvas();
    private final ObservableList<EngineModel.RateSample> samples;
    private final Region down;
    private final Region up;
    private final Region grid = swatch("graph-grid");
    private final LongFunction<String> rate;
    private final Label top = scaleLabel();
    private final Label middle = scaleLabel();
    private long highest;

    /**
     * @param down the legend swatch of the download line
     * @param up the legend swatch of the upload line
     * @param subscriptions where the graph's following of {@code samples} is kept
     * @param rate writes a rate in bytes per second for the scale
     */
    public RateGraph(
            ObservableList<EngineModel.RateSample> samples,
            Region down,
            Region up,
            Subscriptions subscriptions,
            LongFunction<String> rate) {
        this.samples = samples;
        this.rate = rate;
        this.down = down;
        this.up = up;
        getStyleClass().add("rate-graph");
        // Time runs to the right in every language, as in JavaFX's charts.
        setNodeOrientation(NodeOrientation.LEFT_TO_RIGHT);
        this.grid.setManaged(false);
        this.grid.setVisible(false);
        getChildren().addAll(this.canvas, this.grid, this.top, this.middle);
        setMinHeight(160);
        setPrefHeight(260);
        subscriptions.onListChange(samples, c -> draw());
        for (Region r : List.of(down, up, this.grid)) {
            r.backgroundProperty().addListener((o, was, now) -> draw());
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
        this.canvas.setWidth(getWidth());
        this.canvas.setHeight(getHeight());
        draw();
        // The scale's words sit just under the lines they name, at the left edge.
        double h = getHeight();
        for (Label label : List.of(this.top, this.middle)) {
            label.autosize();
        }
        this.top.relocate(6, 2);
        this.middle.relocate(6, Math.rint(h / 2.0) + 2);
    }

    /**
     * The top of the scale: the highest rate shown, rounded up to 1, 2 or 5 times a power of ten KB/s, MB/s or GB/s.
     */
    static long scale(long highest) {
        // From a thousand KB/s on the rates read in MB/s, and from a thousand MB/s in GB/s: the scale rounds whole
        // units of what it shows.
        long unit = 1024;
        while (true) {
            long top = roundUp(Math.max(1, (highest + unit - 1) / unit));
            if (top < 1000 || unit == GIB) {
                return top * unit;
            }
            unit *= 1024;
        }
    }

    // Up to 1, 2 or 5 times a power of ten.
    private static long roundUp(long value) {
        long step = 1;
        while (true) {
            for (long m : new long[] {1, 2, 5}) {
                if (m * step >= value) {
                    return m * step;
                }
            }
            step *= 10;
        }
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
        long seen = 1024;
        for (EngineModel.RateSample s : shown) {
            seen = Math.max(seen, Math.max(s.down(), s.up()));
        }
        this.highest = seen;
        long max = scale(seen);
        this.top.setText(this.rate.apply(max));
        this.middle.setText(this.rate.apply(max / 2));
        this.top.autosize();
        this.middle.autosize();
        g.setStroke(color(this.grid, Color.gray(0.5, 0.4)));
        g.setLineWidth(1);
        for (int i = 1; i < 4; i++) {
            double y = Math.round(h * i / 4.0) + 0.5;
            g.strokeLine(0, y, w, y);
        }
        line(g, shown, EngineModel.RateSample::down, max, color(this.down, Color.STEELBLUE));
        line(g, shown, EngineModel.RateSample::up, max, color(this.up, Color.SEAGREEN));
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

    private static Label scaleLabel() {
        Label label = new Label();
        label.getStyleClass().add("graph-scale");
        label.setManaged(false);
        return label;
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
