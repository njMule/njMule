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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Control;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import org.njmule.gui.ui.Gui;

/**
 * The index beside the options: their sections, to jump to one and to see where one is while scrolling, and a box that
 * finds the options whose words hold what is typed, each a step from its field.
 */
final class SettingsIndex {

    /** A place of the page: a section's title, or one option within a section. */
    record Entry(String text, String section, boolean isSection, Node target) {}

    private final ScrollPane scroll;
    private final List<Entry> all;
    private final TextField find = new TextField();
    private final ListView<Entry> list = new ListView<>();
    private final VBox view;
    // Set while scrolling picks the section: the list then follows the page and does not move it.
    private boolean following;

    SettingsIndex(Gui gui, GridPane grid, ScrollPane scroll) {
        this.scroll = scroll;
        this.all = entries(grid);
        this.find.setPromptText(gui.t("nj.settings.find"));
        this.find.textProperty().addListener((o, was, now) -> this.filter(now));
        this.find.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ESCAPE) {
                this.find.clear();
            } else if (e.getCode() == KeyCode.DOWN && !this.list.getItems().isEmpty()) {
                this.list.requestFocus();
                this.list.getSelectionModel().selectFirst();
            } else if (e.getCode() == KeyCode.ENTER && !this.list.getItems().isEmpty()) {
                this.go(this.list.getItems().getFirst(), true);
            }
        });
        // Moving in the list scrolls the page; a click or Enter also takes the cursor to the option.
        this.list.setOnMouseClicked(e -> this.goToChosen());
        this.list.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER) {
                this.goToChosen();
            }
        });
        this.list.setCellFactory(l -> new EntryCell());
        this.list.getStyleClass().add("settings-index");
        this.list.getSelectionModel().selectedItemProperty().addListener((o, was, now) -> {
            if (now != null && !this.following && !this.list.getItems().isEmpty()) {
                this.go(now, false);
            }
        });
        this.scroll.vvalueProperty().addListener((o, was, now) -> this.follow());
        this.view = new VBox(8, this.find, this.list);
        VBox.setVgrow(this.list, Priority.ALWAYS);
        this.view.setMinWidth(150);
        this.view.setPrefWidth(210);
        this.view.setMaxWidth(260);
        this.filter("");
        // The page opens at its top: the first section is the one chosen.
        this.following = true;
        try {
            this.list.getSelectionModel().selectFirst();
        } finally {
            this.following = false;
        }
    }

    VBox view() {
        return this.view;
    }

    TextField findField() {
        return this.find;
    }

    List<Entry> shown() {
        return List.copyOf(this.list.getItems());
    }

    /** The sections, or the options and sections that hold every word of {@code text}. */
    private void filter(String text) {
        List<String> words = Arrays.stream(text.strip().toLowerCase(Locale.ROOT).split("\\s+"))
                .filter(w -> !w.isEmpty())
                .toList();
        this.following = true;
        try {
            this.list
                    .getItems()
                    .setAll(
                            words.isEmpty()
                                    ? this.all.stream().filter(Entry::isSection).toList()
                                    : this.all.stream()
                                            .filter(
                                                    e -> words.stream()
                                                            .allMatch((e.text() + " " + e.section())
                                                                    .toLowerCase(Locale.ROOT)::contains))
                                            .toList());
        } finally {
            this.following = false;
        }
    }

    private void goToChosen() {
        Entry chosen = this.list.getSelectionModel().getSelectedItem();
        if (chosen != null) {
            this.go(chosen, true);
        }
    }

    // Scrolls the place to the top of the page; with focus, an option also gets the cursor.
    void go(Entry entry, boolean focus) {
        Node content = this.scroll.getContent();
        Bounds at =
                content.sceneToLocal(entry.target().localToScene(entry.target().getBoundsInLocal()));
        double view = this.scroll.getViewportBounds().getHeight();
        double free = content.getLayoutBounds().getHeight() - view;
        if (free > 0) {
            this.scroll.setVvalue(Math.clamp((at.getMinY() - 8) / free, 0.0, 1.0));
        }
        if (focus && !entry.isSection() && entry.target() instanceof Control control) {
            control.requestFocus();
        }
    }

    // While the find box is empty, the section at the top of the page is the one chosen in the list.
    private void follow() {
        if (!this.find.getText().isBlank() || this.scroll.getContent() == null) {
            return;
        }
        Node content = this.scroll.getContent();
        double view = this.scroll.getViewportBounds().getHeight();
        double free = content.getLayoutBounds().getHeight() - view;
        double top = Math.max(0, free) * this.scroll.getVvalue();
        Entry current = null;
        for (Entry e : this.list.getItems()) {
            Bounds at = content.sceneToLocal(e.target().localToScene(e.target().getBoundsInLocal()));
            // The section whose title is in the upper third of the page; at the end of the page, the last.
            if (at.getMinY() <= top + view / 3 || this.scroll.getVvalue() >= 0.999) {
                current = e;
            }
        }
        if (current != null && current != this.list.getSelectionModel().getSelectedItem()) {
            this.following = true;
            try {
                this.list.getSelectionModel().select(current);
                this.list.scrollTo(current);
            } finally {
                this.following = false;
            }
        }
    }

    // The section titles and the options of the grid, in the order of the page.
    static List<Entry> entries(GridPane grid) {
        List<Node> nodes = new ArrayList<>(grid.getChildren());
        nodes.sort(Comparator.comparingInt(SettingsIndex::row).thenComparingInt(SettingsIndex::column));
        List<Entry> entries = new ArrayList<>();
        String section = "";
        for (Node n : nodes) {
            if (n instanceof Label l && l.getStyleClass().contains("settings-section")) {
                section = l.getText();
                entries.add(new Entry(section, section, true, l));
            } else if (n instanceof Label l && column(n) == 0 && !l.getText().isBlank()) {
                Node control = nodes.stream()
                        .filter(m -> row(m) == row(n) && column(m) == 1)
                        .findFirst()
                        .orElse(l);
                entries.add(new Entry(l.getText(), section, false, focusable(control)));
            } else if (n instanceof CheckBox c && column(n) == 1 && !c.getText().isBlank()) {
                entries.add(new Entry(c.getText(), section, false, c));
            }
        }
        return entries;
    }

    // The first control within a row's node, such as the field of a field-and-button box.
    private static Node focusable(Node node) {
        if (node instanceof Control) {
            return node;
        }
        return node.lookupAll(".text-field, .combo-box, .check-box, .button").stream()
                .findFirst()
                .orElse(node);
    }

    private static int row(Node n) {
        Integer row = GridPane.getRowIndex(n);
        return row == null ? 0 : row;
    }

    private static int column(Node n) {
        Integer column = GridPane.getColumnIndex(n);
        return column == null ? 0 : column;
    }

    private static final class EntryCell extends ListCell<Entry> {
        @Override
        protected void updateItem(Entry entry, boolean empty) {
            super.updateItem(entry, empty);
            this.getStyleClass().removeAll("index-section", "index-option");
            if (empty || entry == null) {
                this.setText(null);
                return;
            }
            this.setText(entry.text().strip().replaceAll(":$", ""));
            this.getStyleClass().add(entry.isSection() ? "index-section" : "index-option");
        }
    }
}
