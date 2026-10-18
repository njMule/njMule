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
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import org.njmule.core.api.NjMuleInfo;

/**
 * A question in the one style of the dialogs: what is decided as its title, the words and fields below, then answers
 * named by their outcome in the platform's order, the safe one the default.
 */
public final class Ask {

    private static final double WIDTH = 460;

    private final Dialog<ButtonType> dialog = new Dialog<>();
    private final Label title;
    private final HBox header;
    private final VBox body = new VBox(12);
    private ButtonType go;

    private Ask(Gui gui, String title) {
        gui.dialog(this.dialog);
        this.dialog.setTitle(NjMuleInfo.NAME);
        this.title = new Label(title);
        this.title.getStyleClass().add("dialog-title");
        this.title.setWrapText(true);
        HBox.setHgrow(this.title, Priority.ALWAYS);
        this.header = new HBox(10, this.title);
        this.header.getStyleClass().add("dialog-header");
        this.header.setAlignment(Pos.CENTER_LEFT);
        this.body.getStyleClass().add("dialog-body");
        this.body.setFillWidth(true);
        this.dialog.getDialogPane().setHeader(this.header);
        this.dialog.getDialogPane().setContent(this.body);
        this.dialog.getDialogPane().setPrefWidth(WIDTH);
        this.dialog.getDialogPane().setMinHeight(Region.USE_PREF_SIZE);
    }

    /** A question whose {@code title} says what is decided, such as "Remove all 8 servers?". */
    public static Ask about(Gui gui, String title) {
        return new Ask(gui, title);
    }

    /** Marks the question as a warning, with its sign before the title. */
    public Ask warning() {
        Node sign = Fx.icon(Icons.WARNING);
        sign.getStyleClass().add("dialog-warning");
        this.header.getChildren().addFirst(sign);
        return this;
    }

    public Ask says(String text) {
        Label words = new Label(text);
        words.setWrapText(true);
        words.getStyleClass().add("dialog-text");
        words.setMinHeight(Region.USE_PREF_SIZE);
        this.body.getChildren().add(words);
        return this;
    }

    /** The names the question is about, one a line, each in its own direction. */
    public Ask names(List<String> names) {
        VBox list = new VBox(2);
        list.getStyleClass().add("dialog-names");
        for (String name : names) {
            Label label = Fx.ownDirection(new Label(name));
            label.setMinWidth(0);
            list.getChildren().add(label);
        }
        this.body.getChildren().add(list);
        return this;
    }

    /** A field named above it, with an optional {@code hint} under it. */
    public Ask field(String name, TextField field, String hint) {
        Label label = new Label(name);
        label.getStyleClass().add("dialog-label");
        label.setLabelFor(field);
        VBox box = new VBox(6, label, field);
        if (!hint.isEmpty()) {
            Label under = new Label(hint);
            under.setWrapText(true);
            under.getStyleClass().add("dialog-hint");
            box.getChildren().add(under);
        }
        this.body.getChildren().add(box);
        return this;
    }

    public Ask with(Node... nodes) {
        this.body.getChildren().addAll(nodes);
        return this;
    }

    /** A muted line with {@code glyph} before it, for what the answer implies. */
    public Ask note(Icons glyph, String text) {
        Label note = new Label(text, Fx.icon(glyph));
        note.setWrapText(true);
        note.getStyleClass().add("dialog-hint");
        note.setMinHeight(Region.USE_PREF_SIZE);
        this.body.getChildren().add(note);
        return this;
    }

    /** Answers that change nothing that cannot be undone: {@code go} is the default. */
    public Ask answers(String goText, String stayText) {
        this.go = Fx.answers(this.dialog, goText, stayText, false);
        return this;
    }

    /** Answers to a risky question: {@code go} is marked as dangerous, and Enter keeps things as they are. */
    public Ask risky(String goText, String stayText) {
        this.go = Fx.answers(this.dialog, goText, stayText, true);
        return this;
    }

    public Ask owner(Window owner) {
        if (owner != null) {
            this.dialog.initOwner(owner);
        }
        return this;
    }

    public Dialog<ButtonType> dialog() {
        return this.dialog;
    }

    /** The answer that goes ahead. */
    public ButtonType go() {
        return this.go;
    }

    /** Shows the question and waits: whether the answer was to go ahead. */
    public boolean ask() {
        return this.dialog.showAndWait().filter(this.go::equals).isPresent();
    }

    Label titleLabel() {
        return this.title;
    }
}
