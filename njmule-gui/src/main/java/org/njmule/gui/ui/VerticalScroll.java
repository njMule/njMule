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

import javafx.geometry.Orientation;
import javafx.scene.Node;
import javafx.scene.control.ScrollBar;
import javafx.scene.control.ScrollPane;

/**
 * A pane that scrolls only up and down, its content as wide as the pane: it asks for the width its content needs, with
 * room for the bar, so that the window never gets narrow enough to cut a button inside it.
 */
public final class VerticalScroll extends ScrollPane {

    public VerticalScroll() {
        setFitToWidth(true);
        setHbarPolicy(ScrollBarPolicy.NEVER);
    }

    public VerticalScroll(Node content) {
        this();
        setContent(content);
    }

    @Override
    protected double computeMinWidth(double height) {
        double bar = 0;
        for (Node child : getChildrenUnmodifiable()) {
            if (child instanceof ScrollBar b && b.getOrientation() == Orientation.VERTICAL) {
                bar = b.prefWidth(-1);
            }
        }
        Node content = getContent();
        double inside = content == null ? 0 : content.minWidth(-1);
        return snappedLeftInset() + inside + bar + snappedRightInset();
    }
}
