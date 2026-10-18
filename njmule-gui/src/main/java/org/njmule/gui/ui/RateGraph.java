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

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
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
 * The download and upload rates of the last ten minutes, hour or day as two filled lines, on a scale rounded up from
 * the highest rate shown and labelled at its top and middle, over the times of its width; under the pointer, the rates
 * of that moment; a word while nothing moved. Its colours come from the theme tokens of the legend swatches, so it
 * follows the theme.
 */
public final class RateGraph extends Region {

    /** The time shown: the last ten minutes or hour second by second, or the last day minute by minute. */
    public enum Range {
        TEN_MINUTES(600, 1),
        HOUR(3_600, 1),
        DAY(86_400, 60);

        private final int seconds;
        private final int step;

        Range(int seconds, int step) {
            this.seconds = seconds;
            this.step = step;
        }

        public int seconds() {
            return this.seconds;
        }

        int slots() {
            return this.seconds / this.step;
        }
    }

    private static final double FILL_OPACITY = 0.25;
    // The band under the lines that holds the times, and how many times it shows.
    private static final double AXIS = 18;
    private static final int TICKS = 5;
    private static final long GIB = 1L << 30;

    private final Canvas canvas = new Canvas();
    private final ObservableList<EngineModel.RateSample> seconds;
    private final ObservableList<EngineModel.RateSample> minutes;
    private final Region down;
    private final Region up;
    private final Region grid = swatch("graph-grid");
    private final LongFunction<String> rate;
    private final Function<Instant, String> time;
    private final Label top = scaleLabel();
    private final Label middle = scaleLabel();
    private final Label pointed = new Label();
    private final List<Label> times = List.of(timeLabel(), timeLabel(), timeLabel(), timeLabel(), timeLabel());
    private final Label empty = new Label();
    private Range range = Range.TEN_MINUTES;
    private long highest;
    // Where the pointer is over the graph, or below zero.
    private double pointer = -1;

    /**
     * @param seconds the rates of each second, the oldest first
     * @param minutes the mean rates of each minute, the oldest first
     * @param down the legend swatch of the download line
     * @param up the legend swatch of the upload line
     * @param subscriptions where the graph's following of the rates is kept
     * @param rate writes a rate in bytes per second for the scale
     * @param time writes the time of a rate under the pointer
     */
    public RateGraph(
            ObservableList<EngineModel.RateSample> seconds,
            ObservableList<EngineModel.RateSample> minutes,
            Region down,
            Region up,
            Subscriptions subscriptions,
            LongFunction<String> rate,
            Function<Instant, String> time) {
        this.seconds = seconds;
        this.minutes = minutes;
        this.rate = rate;
        this.time = time;
        this.down = down;
        this.up = up;
        getStyleClass().add("rate-graph");
        // Time runs to the right in every language, as in JavaFX's charts.
        setNodeOrientation(NodeOrientation.LEFT_TO_RIGHT);
        this.grid.setManaged(false);
        this.grid.setVisible(false);
        this.pointed.getStyleClass().addAll("graph-pointed", "elevated-1");
        this.pointed.setManaged(false);
        this.pointed.setVisible(false);
        this.empty.getStyleClass().add("graph-empty");
        this.empty.setManaged(false);
        this.empty.setVisible(false);
        getChildren().addAll(this.canvas, this.grid, this.top, this.middle, this.empty, this.pointed);
        getChildren().addAll(this.times);
        setMinHeight(160);
        setPrefHeight(260);
        subscriptions.onListChange(seconds, c -> draw());
        subscriptions.onListChange(minutes, c -> draw());
        for (Region r : List.of(down, up, this.grid)) {
            r.backgroundProperty().addListener((o, was, now) -> draw());
        }
        setOnMouseMoved(e -> point(e.getX()));
        setOnMouseExited(e -> point(-1));
    }

    /** Shows {@code range} from now on. */
    public void setRange(Range range) {
        this.range = Objects.requireNonNull(range, "range");
        draw();
    }

    public Range range() {
        return this.range;
    }

    /** The word shown while nothing moved in the time shown. */
    public void setEmptyText(String text) {
        this.empty.setText(text);
    }

    /** Whether the word that nothing moved shows. */
    public boolean showsEmpty() {
        return this.empty.isVisible();
    }

    /** The times under the lines, from the oldest to now. */
    public List<String> times() {
        return this.times.stream().map(Label::getText).toList();
    }

    /** What the rates under the pointer read, or empty while the pointer is elsewhere. */
    public Optional<String> pointed() {
        return this.pointed.isVisible() ? Optional.of(this.pointed.getText()) : Optional.empty();
    }

    void point(double x) {
        this.pointer = x;
        draw();
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
        this.middle.relocate(6, Math.rint((h - AXIS) / 2.0) + 2);
        placeTimes();
    }

    // The first time at the start of the band, the last at its end, the others centred on their place.
    private void placeTimes() {
        double w = getWidth();
        double y = getHeight() - AXIS + 2;
        for (int i = 0; i < TICKS; i++) {
            Label label = this.times.get(i);
            label.autosize();
            double x = w * i / (TICKS - 1);
            double lw = label.getWidth();
            label.relocate(Math.clamp(x - lw / 2, 0, Math.max(0, w - lw)), y);
        }
        this.empty.autosize();
        this.empty.relocate(
                Math.max(0, (w - this.empty.getWidth()) / 2),
                Math.max(0, (getHeight() - AXIS - this.empty.getHeight()) / 2));
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
        double h = this.canvas.getHeight() - AXIS;
        GraphicsContext g = this.canvas.getGraphicsContext2D();
        g.clearRect(0, 0, w, this.canvas.getHeight());
        if (w < 2 || h < 2) {
            this.pointed.setVisible(false);
            return;
        }
        List<EngineModel.RateSample> all = this.range.step == 1 ? this.seconds : this.minutes;
        int from = Math.max(0, all.size() - this.range.slots());
        List<EngineModel.RateSample> shown = all.subList(from, all.size());
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
        for (int i = 1; i <= 4; i++) {
            double y = Math.round(h * i / 4.0) + 0.5;
            g.strokeLine(0, y, w, y);
        }
        showTimes(shown);
        this.empty.setVisible(shown.stream().allMatch(s -> s.down() == 0 && s.up() == 0));
        line(g, shown, EngineModel.RateSample::down, max, color(this.down, Color.STEELBLUE));
        line(g, shown, EngineModel.RateSample::up, max, color(this.up, Color.SEAGREEN));
        showPointed(g, shown);
    }

    // The times of the band: now at its end, counted back from the newest sample.
    private void showTimes(List<EngineModel.RateSample> shown) {
        Instant now = shown.isEmpty() ? Instant.now() : shown.getLast().time();
        for (int i = 0; i < TICKS; i++) {
            long back = (long) this.range.seconds * (TICKS - 1 - i) / (TICKS - 1);
            this.times.get(i).setText(this.time.apply(now.minusSeconds(back)));
        }
    }

    // The sample under the pointer, its time and rates beside a mark; nothing where no sample is.
    private void showPointed(GraphicsContext g, List<EngineModel.RateSample> shown) {
        double w = this.canvas.getWidth();
        double h = this.canvas.getHeight() - AXIS;
        int index = shown.size() - 1 - (int) Math.round((w - this.pointer) / step(w));
        if (this.pointer < 0 || index < 0 || index >= shown.size()) {
            this.pointed.setVisible(false);
            return;
        }
        EngineModel.RateSample sample = shown.get(index);
        double x = Math.round(w - (shown.size() - 1 - index) * step(w)) + 0.5;
        g.setStroke(color(this.grid, Color.gray(0.5, 0.4)));
        g.setLineWidth(1);
        g.strokeLine(x, 0, x, h);
        this.pointed.setText(this.time.apply(sample.time()) + "  \u2193 " + this.rate.apply(sample.down()) + "  \u2191 "
                + this.rate.apply(sample.up()));
        this.pointed.autosize();
        double lw = this.pointed.getWidth();
        // Beside the mark, on the side with room.
        double lx = x + 8 + lw <= w ? x + 8 : Math.max(0, x - 8 - lw);
        this.pointed.relocate(lx, 4);
        this.pointed.setVisible(true);
    }

    private double step(double width) {
        return width / Math.max(1, this.range.slots() - 1);
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
        double h = this.canvas.getHeight() - AXIS;
        double step = step(w);
        double x0 = w - (shown.size() - 1) * step;
        // Samples closer than a pixel are drawn as their mean, one point per pixel.
        int perPoint = Math.max(1, (int) Math.ceil(1 / step));
        int n = (shown.size() + perPoint - 1) / perPoint;
        double[] xs = new double[n + 2];
        double[] ys = new double[n + 2];
        for (int p = 0; p < n; p++) {
            int first = p * perPoint;
            int last = Math.min(shown.size(), first + perPoint);
            long sum = 0;
            for (int i = first; i < last; i++) {
                sum += rate.applyAsLong(shown.get(i));
            }
            xs[p] = x0 + (last - 1) * step;
            ys[p] = h - (double) sum / (last - first) / max * (h - 4);
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

    private static Label timeLabel() {
        Label label = new Label();
        label.getStyleClass().add("graph-time");
        label.setManaged(false);
        return label;
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
