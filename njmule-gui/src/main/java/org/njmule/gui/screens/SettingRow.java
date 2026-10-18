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

import atlantafx.base.controls.ToggleSwitch;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Control;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import org.njmule.gui.ui.Fx;
import org.njmule.gui.ui.Gui;
import org.njmule.gui.ui.Icons;
import org.njmule.gui.ui.Input;

/**
 * One option of the settings: its name, what it does, its control, whether it differs from its default with a way back
 * to it, and what is wrong with the value typed.
 */
final class SettingRow {

    private final Gui gui;
    private final String title;
    private final Node control;
    private String description = "";
    private boolean wide;
    private List<Input<?>> inputs = List.of();
    private BooleanSupplier changed = () -> false;
    private Runnable reset = () -> {};
    private Supplier<String> defaultText = () -> "";
    private final Label changedMark = new Label();
    private final Hyperlink resetLink = new Hyperlink();
    private final Label error = new Label();
    private Region node;

    SettingRow(Gui gui, String title, Node control) {
        this.gui = gui;
        this.title = title.strip().replaceAll(":$", "");
        this.control = control;
    }

    /** A line under the name on what the option does. */
    SettingRow describedAs(String text) {
        this.description = text;
        return this;
    }

    /** The control under the name, the width of the page: for paths, lists and command lines. */
    SettingRow wide() {
        this.wide = true;
        return this;
    }

    /** The inputs whose wrong value the row names under the option, the first wrong one first. */
    SettingRow checkedBy(Input<?>... checked) {
        this.inputs = List.of(checked);
        return this;
    }

    /** Whether the option differs from its default, how to go back to it, and the default in words ("" for none). */
    SettingRow defaultIs(BooleanSupplier differs, Runnable back, Supplier<String> words) {
        this.changed = differs;
        this.reset = back;
        this.defaultText = words;
        return this;
    }

    String title() {
        return this.title;
    }

    Node control() {
        return this.control;
    }

    boolean isChanged() {
        return this.changed.getAsBoolean();
    }

    /** Whether a value typed is wrong. */
    boolean isWrong() {
        return this.inputs.stream().anyMatch(i -> !i.isValid());
    }

    /** The first input with a wrong value, else the first input. */
    Optional<Input<?>> input() {
        return this.inputs.stream()
                .filter(i -> !i.isValid())
                .findFirst()
                .or(() -> this.inputs.stream().findFirst());
    }

    List<Input<?>> inputs() {
        return this.inputs;
    }

    Hyperlink resetLink() {
        return this.resetLink;
    }

    Label errorLabel() {
        return this.error;
    }

    /** Whether the row holds every word, in its name, its line, its section or its page. */
    boolean matches(List<String> words, String section, String page) {
        String all =
                String.join(" ", this.title, this.description, section, page).toLowerCase(Locale.ROOT);
        return words.stream().allMatch(all::contains);
    }

    /** The row's node, made once. */
    Region node() {
        if (this.node == null) {
            this.node = build();
        }
        return this.node;
    }

    // The name with its marks, the line and the error on the start side, the control on the end side or below.
    private Region build() {
        Label name = new Label(this.title);
        name.getStyleClass().add("setting-title");
        Fx.wrap(name);
        name.setMinHeight(Region.USE_PREF_SIZE);
        if (this.control instanceof Control target) {
            name.setLabelFor(target);
        }
        // The name of a switch turns it too, as a check box's text does.
        if (this.control instanceof ToggleSwitch toggle) {
            name.setOnMouseClicked(e -> {
                if (!toggle.isDisabled()) {
                    toggle.setSelected(!toggle.isSelected());
                }
            });
        }
        this.changedMark.setText(this.gui.t("nj.settings.changed"));
        this.changedMark.getStyleClass().add("changed-mark");
        this.changedMark.setMinWidth(Region.USE_PREF_SIZE);
        this.resetLink.getStyleClass().add("reset-link");
        this.resetLink.setOnAction(e -> {
            this.reset.run();
            refresh();
        });
        FlowPane head = new FlowPane(8, 2, name, this.changedMark, this.resetLink);
        head.setAlignment(Pos.CENTER_LEFT);
        head.setMinWidth(0);
        VBox words = new VBox(2, head);
        words.setMinWidth(0);
        if (this.title.isEmpty()) {
            words.getChildren().clear();
        }
        if (!this.description.isEmpty()) {
            Label line = new Label(this.description);
            line.getStyleClass().add("setting-description");
            Fx.wrap(line);
            line.setMinHeight(Region.USE_PREF_SIZE);
            words.getChildren().add(line);
        }
        this.error.setGraphic(Fx.icon(Icons.ERROR));
        this.error.getStyleClass().add("setting-error");
        Fx.wrap(this.error);
        this.error.setMinHeight(Region.USE_PREF_SIZE);
        this.error.managedProperty().bind(this.error.visibleProperty());
        words.getChildren().add(this.error);
        this.inputs.forEach(i -> i.validProperty().addListener((o, was, now) -> showError()));
        showError();
        Pane row;
        if (this.wide) {
            row = new VBox(8, words, this.control);
        } else {
            HBox.setHgrow(words, Priority.ALWAYS);
            // The control keeps its size; the name wraps instead.
            if (this.control instanceof Region box) {
                box.setMinWidth(Region.USE_PREF_SIZE);
            }
            HBox line = new HBox(16, words, this.control);
            line.setAlignment(Pos.CENTER_LEFT);
            row = line;
        }
        if (words.getChildren().isEmpty()) {
            row.getChildren().remove(words);
        }
        row.getStyleClass().add("setting-row");
        refresh();
        return row;
    }

    private void showError() {
        Optional<Input<?>> wrong =
                this.inputs.stream().filter(i -> !i.isValid()).findFirst();
        wrong.ifPresent(i -> this.error.setText(i.hint()));
        this.error.setVisible(wrong.isPresent());
    }

    /** Shows whether the option differs from its default, and the way back to it. */
    void refresh() {
        boolean differs = this.changed.getAsBoolean();
        this.changedMark.setVisible(differs);
        this.changedMark.setManaged(differs);
        String words = this.defaultText.get();
        this.resetLink.setText(words.isEmpty() ? this.gui.t("IDS_PW_RESET") : this.gui.f("nj.settings.resetTo", words));
        this.resetLink.setVisible(differs);
        this.resetLink.setManaged(differs);
    }
}
