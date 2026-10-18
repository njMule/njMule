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
import javafx.scene.AccessibleAttribute;
import javafx.scene.AccessibleRole;
import javafx.scene.layout.Region;

/**
 * A thin bar of how much is done, its fill in the tone of a state; the figure goes beside it, never only the bar. It
 * fills from the start of the layout, so from the right in a right-to-left language.
 */
public final class ThinBar extends Region {

    /** The tones of the fill, as style classes. */
    public static final List<String> TONES = List.of("accent", "success", "warning", "danger", "neutral");

    private final Region fill = new Region();
    private double fraction;

    public ThinBar() {
        getStyleClass().add("thin-bar");
        setAccessibleRole(AccessibleRole.PROGRESS_INDICATOR);
        this.fill.getStyleClass().add("thin-bar-fill");
        getChildren().add(this.fill);
        // As thin in a tall cell as anywhere.
        setMaxHeight(Region.USE_PREF_SIZE);
    }

    /** Shows {@code fraction} of the bar filled, clamped to 0..1, in {@code tone}, one of {@link #TONES}. */
    public void set(double fraction, String tone) {
        this.fraction = Double.isFinite(fraction) ? Math.clamp(fraction, 0, 1) : 0;
        this.fill.getStyleClass().removeAll(TONES);
        this.fill.getStyleClass().add(tone);
        requestLayout();
        notifyAccessibleAttributeChanged(AccessibleAttribute.VALUE);
    }

    @Override
    public Object queryAccessibleAttribute(AccessibleAttribute attribute, Object... parameters) {
        return switch (attribute) {
            case VALUE -> this.fraction;
            case MAX_VALUE -> 1.0;
            case MIN_VALUE -> 0.0;
            case INDETERMINATE -> false;
            default -> super.queryAccessibleAttribute(attribute, parameters);
        };
    }

    public double fraction() {
        return this.fraction;
    }

    @Override
    protected void layoutChildren() {
        double x = snappedLeftInset();
        double y = snappedTopInset();
        double w = getWidth() - x - snappedRightInset();
        double h = getHeight() - y - snappedBottomInset();
        this.fill.resizeRelocate(x, y, snapSizeX(w * this.fraction), h);
    }

    @Override
    protected double computePrefHeight(double width) {
        return 6;
    }

    @Override
    protected double computeMinHeight(double width) {
        return 6;
    }

    @Override
    protected double computePrefWidth(double height) {
        return 60;
    }
}
