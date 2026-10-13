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
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.Background;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.paint.Paint;
import org.njmule.core.api.PartMap;

/**
 * The bar of a download, with eMule's meaning in njMule's colours: what we have, what is missing
 * (bluer the more sources have it, red when none has), the blocks being requested, and a thin strip
 * on top for the completed share.
 */
public final class PartBar extends Region {

    /** Sources at which the blue reaches its strongest tone. */
    static final int FULL_SOURCES = 10;

    private static final double STRIP = 3;

    private final Canvas canvas = new Canvas();
    private final Region track = swatch("bar-bg");
    private final Region have = swatch("bar-have");
    private final Region missing = swatch("bar-missing");
    private final Region few = swatch("bar-sources-min");
    private final Region many = swatch("bar-sources-max");
    private final Region requested = swatch("bar-requested");
    private final Region complete = swatch("bar-complete");
    private PartMap map = PartMap.EMPTY;
    private long done;

    public PartBar() {
        this.getStyleClass().add("part-bar");
        this.getChildren().add(this.canvas);
        for (Region r : this.swatches()) {
            this.getChildren().add(r);
            r.backgroundProperty().addListener((o, was, now) -> this.draw());
        }
        this.setMinHeight(12);
        this.setPrefHeight(16);
    }

    /** Shows {@code map}, of which {@code completed} bytes are done. */
    public void set(PartMap map, long completed) {
        this.map = map;
        this.done = completed;
        this.draw();
    }

    @Override
    protected void layoutChildren() {
        this.canvas.setWidth(this.getWidth());
        this.canvas.setHeight(this.getHeight());
        this.draw();
    }

    private List<Region> swatches() {
        return List.of(
                this.track,
                this.have,
                this.missing,
                this.few,
                this.many,
                this.requested,
                this.complete);
    }

    private void draw() {
        double w = this.canvas.getWidth();
        double h = this.canvas.getHeight();
        GraphicsContext g = this.canvas.getGraphicsContext2D();
        g.clearRect(0, 0, w, h);
        g.setFill(color(this.track, Color.LIGHTGRAY));
        g.fillRect(0, 0, w, h);
        long size = this.map.size();
        if (size <= 0 || w < 1) {
            return;
        }
        double scale = w / size;
        g.setFill(color(this.have, Color.DIMGRAY));
        g.fillRect(0, STRIP, w, h - STRIP);
        Color none = color(this.missing, Color.FIREBRICK);
        Color low = color(this.few, Color.LIGHTSTEELBLUE);
        Color high = color(this.many, Color.STEELBLUE);
        for (PartMap.Span gap : this.map.missing()) {
            // A gap can cross parts, each with its own sources.
            long start = gap.start();
            while (start < gap.end()) {
                int part = (int) (start / PartMap.PART_SIZE);
                long end = Math.min(gap.end(), (part + 1) * PartMap.PART_SIZE);
                int sources = part < this.map.sources().size() ? this.map.sources().get(part) : 0;
                g.setFill(
                        sources == 0
                                ? none
                                : low.interpolate(
                                        high, Math.min(1.0, (double) sources / FULL_SOURCES)));
                fill(g, start, end, scale, STRIP, h - STRIP);
                start = end;
            }
        }
        g.setFill(color(this.requested, Color.GOLD));
        for (PartMap.Span block : this.map.requested()) {
            fill(g, block.start(), block.end(), scale, STRIP, h - STRIP);
        }
        g.setFill(color(this.complete, Color.SEAGREEN));
        g.fillRect(0, 0, Math.min(w, this.done * scale), STRIP);
    }

    // At least one pixel wide, so a small gap still shows.
    private static void fill(
            GraphicsContext g, long start, long end, double scale, double y, double height) {
        double x = start * scale;
        double width = Math.max(1, (end - start) * scale);
        g.fillRect(x, y, width, height);
    }

    private static Region swatch(String styleClass) {
        Region region = new Region();
        region.getStyleClass().add(styleClass);
        region.setManaged(false);
        region.setVisible(false);
        return region;
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
