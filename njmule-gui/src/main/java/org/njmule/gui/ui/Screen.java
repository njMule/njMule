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

import javafx.beans.property.SimpleStringProperty;
import javafx.beans.value.ObservableValue;
import javafx.scene.Node;

/** One screen of the sidebar, under the top bar that shows its title and summary. */
public interface Screen {

    /** A stable name, remembered between runs. */
    String id();

    /** The label of the sidebar button and the title in the top bar. */
    String title();

    Icons icon();

    /** A line under the title on what the screen holds now, such as the downloads running; empty for none. */
    default ObservableValue<String> summary() {
        return new SimpleStringProperty("");
    }

    /** The screen's content, built once. */
    Node view();

    /** The screen became visible. */
    default void shown() {}

    /** The window closes for good: the screen keeps what it keeps between runs. */
    default void closing() {}
}
