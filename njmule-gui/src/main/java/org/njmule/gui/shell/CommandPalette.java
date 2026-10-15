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
package org.njmule.gui.shell;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.function.Supplier;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.njmule.gui.ui.Fx;

/**
 * A box over the window to do anything by typing: the places, the commands of the menus, a search of the network or an
 * eD2K link to download. Opened by Ctrl+K; Enter runs the command chosen, Esc closes.
 */
final class CommandPalette {

    /** Something to do, with the keys that also do it, if any. */
    record Command(String text, String keys, Runnable action) {}

    private static final int ROWS = 9;
    private static final double ROW_HEIGHT = 30;

    private final Supplier<List<Command>> commands;
    private final Function<String, List<Command>> typed;
    private final TextField field = new TextField();
    private final ListView<Command> list = new ListView<>();
    private final StackPane view;

    /**
     * @param commands the commands as they are when the box opens
     * @param typed the commands the text typed is itself, such as a search for it or its links
     * @param prompt the hint of the empty box
     */
    CommandPalette(Supplier<List<Command>> commands, Function<String, List<Command>> typed, String prompt) {
        this.commands = commands;
        this.typed = typed;
        this.field.setPromptText(prompt);
        this.field.textProperty().addListener((o, was, now) -> this.filter(now));
        this.field.setOnKeyPressed(e -> {
            switch (e.getCode()) {
                case ESCAPE -> this.hide();
                case ENTER -> this.run();
                case DOWN -> this.move(1);
                case UP -> this.move(-1);
                default -> {
                    return;
                }
            }
            e.consume();
        });
        this.list.setCellFactory(l -> new CommandCell());
        this.list.setFocusTraversable(false);
        this.list.setOnMouseClicked(e -> this.run());
        VBox box = new VBox(6, this.field, this.list);
        box.getStyleClass().addAll("command-palette", "elevated-2");
        box.setMaxWidth(560);
        box.setMaxHeight(Region.USE_PREF_SIZE);
        Region scrim = new Region();
        scrim.getStyleClass().add("command-scrim");
        scrim.setOnMouseClicked(e -> this.hide());
        this.view = new StackPane(scrim, box);
        StackPane.setAlignment(box, Pos.TOP_CENTER);
        StackPane.setMargin(box, new Insets(64, 16, 16, 16));
        this.view.setVisible(false);
    }

    Node view() {
        return this.view;
    }

    boolean isShowing() {
        return this.view.isVisible();
    }

    void show() {
        this.field.clear();
        this.filter("");
        this.view.setVisible(true);
        this.field.requestFocus();
    }

    void hide() {
        this.view.setVisible(false);
    }

    /** The commands shown now, for tests. */
    List<Command> shown() {
        return List.copyOf(this.list.getItems());
    }

    TextField field() {
        return this.field;
    }

    // The commands whose words hold every word typed, then what the text itself is: a search, links.
    private void filter(String text) {
        List<String> words = Arrays.stream(text.strip().toLowerCase(Locale.ROOT).split("\\s+"))
                .filter(w -> !w.isEmpty())
                .toList();
        List<Command> found = new ArrayList<>();
        for (Command c : this.commands.get()) {
            String all = c.text().toLowerCase(Locale.ROOT);
            if (words.stream().allMatch(all::contains)) {
                found.add(c);
            }
        }
        if (!text.isBlank()) {
            found.addAll(this.typed.apply(text.strip()));
        }
        this.list.getItems().setAll(found);
        this.list.getSelectionModel().selectFirst();
        // As tall as what it lists, up to a few rows.
        this.list.setPrefHeight(Math.clamp(found.size(), 1, ROWS) * ROW_HEIGHT + 4);
    }

    private void move(int by) {
        int size = this.list.getItems().size();
        if (size > 0) {
            int at = Math.floorMod(this.list.getSelectionModel().getSelectedIndex() + by, size);
            this.list.getSelectionModel().select(at);
            this.list.scrollTo(at);
        }
    }

    private void run() {
        Command chosen = this.list.getSelectionModel().getSelectedItem();
        if (chosen != null) {
            this.hide();
            chosen.action().run();
        }
    }

    private static final class CommandCell extends ListCell<Command> {
        @Override
        protected void updateItem(Command command, boolean empty) {
            super.updateItem(command, empty);
            if (empty || command == null) {
                this.setText(null);
                this.setGraphic(null);
                return;
            }
            Label text = new Label(command.text());
            HBox.setHgrow(text, Priority.ALWAYS);
            text.setMaxWidth(Double.MAX_VALUE);
            HBox row = new HBox(8, text);
            if (!command.keys().isEmpty()) {
                Label keys = Fx.leftToRight(new Label(command.keys()));
                keys.getStyleClass().add("shortcut-key");
                row.getChildren().add(keys);
            }
            row.setAlignment(Pos.CENTER_LEFT);
            this.setText(null);
            this.setGraphic(row);
        }
    }
}
