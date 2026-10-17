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
package org.njmule.gui.screens;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import javafx.css.PseudoClass;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import org.njmule.core.api.KadContactInfo;
import org.njmule.gui.ui.Fx;
import org.njmule.gui.ui.Gui;

/**
 * Our Kad contacts by their distance from us, as bars from the closest to the farthest: a routing table that holds
 * contacts at every distance, and many close ones, finds what it looks for in few steps.
 */
final class DistanceChart {

    /** The bars: one per leading bit our ids share, the last one for all that share more. */
    static final int BARS = 24;

    private static final PseudoClass EMPTY = PseudoClass.getPseudoClass("empty");
    private static final int ID_BITS = 128;
    private static final double HEIGHT = 72;

    private final Gui gui;
    private final List<Region> bars = new ArrayList<>();
    private final Label total = new Label();
    private final HBox plot = new HBox(3);
    private final VBox view;
    private int[] counts = new int[BARS];

    DistanceChart(Gui gui) {
        this.gui = gui;
        Label title = new Label(gui.t("nj.kad.byDistance"));
        title.getStyleClass().add("card-title");
        this.total.getStyleClass().add("text-muted");
        HBox head = new HBox(8, title, Fx.spacer(), this.total);
        head.setAlignment(Pos.CENTER_LEFT);
        // The bars stand on the base line, as tall as their share of the fullest.
        this.plot.setAlignment(Pos.BOTTOM_LEFT);
        this.plot.setFillHeight(false);
        this.plot.setMinHeight(HEIGHT);
        this.plot.setPrefHeight(HEIGHT);
        this.plot.setMaxHeight(HEIGHT);
        this.plot.getStyleClass().add("distance-plot");
        for (int i = 0; i < BARS; i++) {
            Region bar = new Region();
            bar.getStyleClass().add("distance-bar");
            bar.setMinWidth(2);
            bar.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(bar, Priority.ALWAYS);
            this.bars.add(bar);
            this.plot.getChildren().add(bar);
        }
        Label close = new Label(gui.t("nj.kad.close"));
        Label far = new Label(gui.t("nj.kad.far"));
        close.getStyleClass().add("chart-axis");
        far.getStyleClass().add("chart-axis");
        HBox axis = new HBox(close, Fx.spacer(), far);
        this.view = new VBox(8, head, this.plot, axis);
        this.view.getStyleClass().addAll("network-card", "distance-chart");
        this.view.setMinWidth(0);
        show(List.of());
    }

    VBox view() {
        return this.view;
    }

    /** Shows {@code contacts} by their distance; a distance that is not a hexadecimal id is left out. */
    void show(Collection<KadContactInfo> contacts) {
        this.counts = counts(contacts);
        int fullest = 1;
        int all = 0;
        for (int count : this.counts) {
            fullest = Math.max(fullest, count);
            all += count;
        }
        String contactsName = this.gui.t("IDS_KADCONTACTLAB");
        for (int i = 0; i < BARS; i++) {
            Region bar = this.bars.get(i);
            int count = this.counts[i];
            // An empty distance still shows a hairline, so the axis reads as a whole.
            double height = count == 0 ? 1 : Math.max(3, HEIGHT * count / fullest);
            bar.setMinHeight(height);
            bar.setPrefHeight(height);
            bar.setMaxHeight(height);
            bar.pseudoClassStateChanged(EMPTY, count == 0);
            Tooltip.install(
                    bar, new Tooltip(contactsName + ": " + this.gui.formats().count(count)));
        }
        this.total.setText(contactsName + ": " + this.gui.formats().count(all));
        this.view.setAccessibleText(this.gui.t("nj.kad.byDistance") + ", " + this.total.getText());
    }

    /** How many contacts each bar has. */
    int[] counts() {
        return this.counts.clone();
    }

    /**
     * The bar of {@code distance}, the XOR of the contact's id and ours in hexadecimal: the more leading bits they
     * share, the closer the contact and the nearer the first bar.
     */
    static int bar(String distance) {
        int shared = ID_BITS - new BigInteger(distance, 16).bitLength();
        return BARS - 1 - Math.min(shared, BARS - 1);
    }

    static int[] counts(Collection<KadContactInfo> contacts) {
        int[] counts = new int[BARS];
        for (KadContactInfo contact : contacts) {
            try {
                counts[bar(contact.distance())]++;
            } catch (NumberFormatException e) {
                // Not an id: nothing to place.
            }
        }
        return counts;
    }
}
