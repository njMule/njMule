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

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyBooleanWrapper;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.css.PseudoClass;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;

/**
 * A list with the details of what is chosen in it at its end: beside the list while the list keeps a width it can be
 * read at, over its end otherwise, so that the details never squeeze the list. The details never make the window's
 * smallest size larger.
 */
public final class InspectorPane extends Pane {

    private static final PseudoClass FLOATING_STATE = PseudoClass.getPseudoClass("floating");

    private final Region main;
    private final Region side;
    private final double mainWidth;
    private final double sideWidth;
    private final double gap;
    private final BooleanProperty open = new SimpleBooleanProperty(true);
    private final ReadOnlyBooleanWrapper floating = new ReadOnlyBooleanWrapper();

    /**
     * {@code main} keeps at least {@code mainWidth} beside {@code side}, which is {@code sideWidth} wide and
     * {@code gap} apart.
     */
    public InspectorPane(Region main, Region side, double mainWidth, double sideWidth, double gap) {
        this.main = main;
        this.side = side;
        this.mainWidth = mainWidth;
        this.sideWidth = sideWidth;
        this.gap = gap;
        getStyleClass().add("inspector-pane");
        side.getStyleClass().add("inspector");
        getChildren().addAll(main, side);
        this.open.addListener((o, was, now) -> requestLayout());
        this.floating.addListener((o, was, now) -> side.pseudoClassStateChanged(FLOATING_STATE, now));
    }

    /** Whether the details show. */
    public BooleanProperty openProperty() {
        return this.open;
    }

    /** Whether the details lie over the end of the list, the window being too narrow for both side by side. */
    public ReadOnlyBooleanProperty floatingProperty() {
        return this.floating.getReadOnlyProperty();
    }

    @Override
    protected void layoutChildren() {
        double x = snappedLeftInset();
        double y = snappedTopInset();
        double w = getWidth() - x - snappedRightInset();
        double h = getHeight() - y - snappedBottomInset();
        boolean shown = this.open.get();
        this.side.setVisible(shown);
        boolean beside = w >= this.mainWidth + this.gap + this.sideWidth;
        this.floating.set(shown && !beside);
        if (!shown) {
            this.main.resizeRelocate(x, y, w, h);
        } else if (beside) {
            double list = w - this.gap - this.sideWidth;
            this.main.resizeRelocate(x, y, list, h);
            this.side.resizeRelocate(x + list + this.gap, y, this.sideWidth, h);
        } else {
            double width = Math.min(this.sideWidth, w);
            this.main.resizeRelocate(x, y, w, h);
            this.side.resizeRelocate(x + w - width, y, width, h);
        }
    }

    @Override
    protected double computeMinWidth(double height) {
        return snappedLeftInset() + this.main.minWidth(height) + snappedRightInset();
    }

    @Override
    protected double computeMinHeight(double width) {
        return snappedTopInset() + this.main.minHeight(width) + snappedBottomInset();
    }

    @Override
    protected double computePrefWidth(double height) {
        double side = this.open.get() ? this.gap + this.sideWidth : 0;
        return snappedLeftInset() + this.main.prefWidth(height) + side + snappedRightInset();
    }

    @Override
    protected double computePrefHeight(double width) {
        return snappedTopInset()
                + Math.max(this.main.prefHeight(-1), this.open.get() ? this.side.prefHeight(-1) : 0)
                + snappedBottomInset();
    }
}
