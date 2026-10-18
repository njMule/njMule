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

import atlantafx.base.theme.Styles;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/**
 * The parts of a dialog that has a window of its own, in the style of the other dialogs: a header with its title and a
 * close button, the body, and a band with the buttons in the platform's order.
 */
public final class DialogFrame {

    private static final double BUTTON_MIN_WIDTH = 64;

    private DialogFrame() {}

    public static HBox header(Gui gui, String title, Runnable close) {
        Label words = new Label(title);
        words.getStyleClass().add("dialog-title");
        Fx.wrap(words);
        words.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(words, Priority.ALWAYS);
        Button closing = Fx.iconButton(gui.t("IDS_FD_CLOSE"), Icons.CLOSE, close);
        closing.getStyleClass().add(Styles.FLAT);
        HBox header = new HBox(10, words, closing);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("dialog-header");
        return header;
    }

    /** {@code button} with its {@code role}, which places it in the band. */
    public static <B extends Button> B role(B button, ButtonBar.ButtonData role) {
        ButtonBar.setButtonData(button, role);
        return button;
    }

    /** The band: {@code start} on the leading side (words, or a button apart), then the buttons, given their roles. */
    public static HBox footer(Node start, Button... buttons) {
        ButtonBar bar = new ButtonBar();
        bar.setButtonMinWidth(BUTTON_MIN_WIDTH);
        bar.getButtons().addAll(buttons);
        // The words beside give way, never the buttons.
        bar.setMinWidth(Region.USE_PREF_SIZE);
        HBox.setHgrow(start, Priority.ALWAYS);
        HBox footer = new HBox(8, start, bar);
        footer.getStyleClass().add("dialog-footer");
        return footer;
    }

    /** A field with its name above it, which a screen reader reads with it. */
    public static VBox named(String name, Node field) {
        Label label = new Label(name);
        label.getStyleClass().add("dialog-label");
        label.setLabelFor(field);
        Fx.wrap(label);
        return new VBox(6, label, field);
    }

    /** The header, the body that takes the room left, and the band. */
    public static VBox frame(Node header, Node body, Node footer) {
        body.getStyleClass().add("dialog-body-own");
        VBox.setVgrow(body, Priority.ALWAYS);
        return new VBox(header, body, footer);
    }
}
