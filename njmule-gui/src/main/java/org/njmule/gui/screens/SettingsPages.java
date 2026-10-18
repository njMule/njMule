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

import atlantafx.base.controls.CustomTextField;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import javafx.geometry.Pos;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ToggleButton;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import org.njmule.gui.ui.Fx;
import org.njmule.gui.ui.Gui;
import org.njmule.gui.ui.Icon;
import org.njmule.gui.ui.Icons;
import org.njmule.gui.ui.VerticalScroll;

/**
 * The options one page at a time, chosen from a list beside them, with a box that finds options by their words on every
 * page and a chip that keeps only those changed from their defaults.
 */
final class SettingsPages {

    /** A group of options under a title ("" for none). */
    record Section(String title, List<SettingRow> rows) {}

    /** A page of the list: its name, what it is about and its sections. */
    record Page(String id, String title, String description, Icons icon, List<Section> sections) {
        Stream<SettingRow> rows() {
            return this.sections.stream().flatMap(s -> s.rows().stream());
        }
    }

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private final Gui gui;
    private final List<Page> pages;
    private final CustomTextField find = new CustomTextField();
    private final ToggleButton changedOnly = new ToggleButton();
    private final Label changedCount = new Label();
    private final ListView<Page> list = new ListView<>();
    private final VerticalScroll scroll = new VerticalScroll();
    private final Map<Page, VBox> views = new HashMap<>();
    private final VBox none = new VBox();
    private final VBox side;
    private boolean choosing;

    SettingsPages(Gui gui, List<Page> pages) {
        this.gui = gui;
        this.pages = List.copyOf(pages);
        this.find.setPromptText(gui.t("nj.settings.find"));
        this.find.setLeft(Fx.icon(Icons.SEARCH));
        this.find.setId("settings-find");
        this.find.textProperty().addListener((o, was, now) -> filter());
        this.find.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ESCAPE) {
                this.find.clear();
            } else if (e.getCode() == KeyCode.DOWN && !this.list.getItems().isEmpty()) {
                this.list.requestFocus();
            } else if (e.getCode() == KeyCode.ENTER) {
                focusFirstShown();
            }
        });
        this.changedOnly.setText(gui.t("nj.settings.changedOnly"));
        this.changedOnly.setId("settings-changed-only");
        this.changedCount.getStyleClass().add("chip-count");
        this.changedOnly.setGraphic(this.changedCount);
        this.changedOnly.setContentDisplay(ContentDisplay.RIGHT);
        this.changedOnly.getStyleClass().add("filter-chip");
        this.changedOnly.selectedProperty().addListener((o, was, now) -> filter());
        Fx.whole(this.changedOnly);
        this.list.setCellFactory(l -> new PageCell());
        this.list.getStyleClass().add("settings-pages");
        this.list.setId("settings-pages");
        this.list.getSelectionModel().selectedItemProperty().addListener((o, was, now) -> {
            if (now != null && !this.choosing) {
                showPage(now);
            }
        });
        Label nothing = new Label(gui.t("nj.settings.noMatch"));
        nothing.getStyleClass().add("text-muted");
        this.none.getChildren().add(nothing);
        this.none.getStyleClass().add("settings-page");
        this.scroll.setFitToWidth(true);
        this.scroll.getStyleClass().add("settings-scroll");
        this.side = new VBox(8, this.find, this.changedOnly, this.list);
        VBox.setVgrow(this.list, Priority.ALWAYS);
        this.side.getStyleClass().add("settings-side");
        // The list keeps its width whatever the page beside it asks for.
        this.side.setMinWidth(210);
        this.side.setPrefWidth(210);
        this.side.setMaxWidth(210);
        // Every page is made at once: an option's marks and errors follow it even while its page is not shown.
        this.pages.forEach(p -> this.views.put(p, build(p)));
        filter();
    }

    /** The list of pages with the find box and the chip. */
    VBox side() {
        return this.side;
    }

    /** Where the chosen page shows. */
    VerticalScroll page() {
        return this.scroll;
    }

    /** Shows the page {@code id}, clearing the filters that would hide it. */
    void show(String id) {
        Page page =
                this.pages.stream().filter(p -> p.id().equals(id)).findFirst().orElseThrow();
        if (!this.list.getItems().contains(page)) {
            this.find.clear();
            this.changedOnly.setSelected(false);
        }
        this.list.getSelectionModel().select(page);
    }

    /** Shows the page that holds {@code row}. */
    void reveal(SettingRow row) {
        this.pages.stream()
                .filter(p -> p.rows().anyMatch(row::equals))
                .findFirst()
                .ifPresent(p -> show(p.id()));
    }

    /** Shows again which options differ from their defaults, and keeps the filters right. */
    void refresh() {
        this.pages.forEach(p -> p.rows().forEach(SettingRow::refresh));
        this.changedCount.setText(Long.toString(this.pages.stream()
                .flatMap(Page::rows)
                .filter(SettingRow::isChanged)
                .count()));
        if (this.changedOnly.isSelected()) {
            filter();
        }
        this.list.refresh();
    }

    // Only the pages with an option that holds every word typed, changed if so asked; on the page shown, only those.
    private void filter() {
        List<String> words = words();
        boolean onlyChanged = this.changedOnly.isSelected();
        List<Page> shown = this.pages.stream()
                .filter(p -> p.sections().stream()
                        .anyMatch(s -> s.rows().stream().anyMatch(r -> keeps(r, s, p, words, onlyChanged))))
                .toList();
        Page chosen = this.list.getSelectionModel().getSelectedItem();
        this.choosing = true;
        try {
            this.list.getItems().setAll(shown);
            if (chosen != null && shown.contains(chosen)) {
                this.list.getSelectionModel().select(chosen);
            } else if (!shown.isEmpty()) {
                this.list.getSelectionModel().selectFirst();
            }
        } finally {
            this.choosing = false;
        }
        this.changedCount.setText(Long.toString(this.pages.stream()
                .flatMap(Page::rows)
                .filter(SettingRow::isChanged)
                .count()));
        Page now = this.list.getSelectionModel().getSelectedItem();
        if (now == null) {
            this.scroll.setContent(this.none);
        } else {
            showPage(now);
        }
    }

    private List<String> words() {
        return Arrays.stream(WHITESPACE.split(this.find.getText().strip().toLowerCase(Locale.ROOT)))
                .filter(w -> !w.isEmpty())
                .toList();
    }

    private static boolean keeps(SettingRow row, Section section, Page page, List<String> words, boolean onlyChanged) {
        return row.matches(words, section.title(), page.title()) && (!onlyChanged || row.isChanged());
    }

    private void showPage(Page page) {
        VBox view = this.views.computeIfAbsent(page, this::build);
        List<String> words = words();
        boolean onlyChanged = this.changedOnly.isSelected();
        // The sections and options the filters keep; a section left empty hides its title too.
        int s = 0;
        for (Section section : page.sections()) {
            VBox card = (VBox) view.getChildren().get(2 + s++);
            boolean any = false;
            for (SettingRow row : section.rows()) {
                boolean kept = keeps(row, section, page, words, onlyChanged);
                row.node().setVisible(kept);
                row.node().setManaged(kept);
                // The first row of a card has no line above it.
                row.node().getStyleClass().remove("first");
                if (kept && !any) {
                    row.node().getStyleClass().add("first");
                }
                any |= kept;
            }
            card.setVisible(any);
            card.setManaged(any);
        }
        if (!view.equals(this.scroll.getContent())) {
            this.scroll.setContent(view);
            this.scroll.setVvalue(0);
        }
    }

    // The title, what the page is about, then a card per section.
    private VBox build(Page page) {
        Label title = new Label(page.title());
        title.getStyleClass().addAll("page-title", "display");
        Label about = new Label(page.description());
        about.getStyleClass().add("page-description");
        about.setWrapText(true);
        about.setMinHeight(Region.USE_PREF_SIZE);
        VBox view = new VBox(6, title, about);
        for (Section section : page.sections()) {
            VBox card = new VBox();
            card.getStyleClass().add("settings-card");
            if (!section.title().isEmpty()) {
                Label head = new Label(section.title());
                head.getStyleClass().add("settings-card-title");
                card.getChildren().add(head);
            }
            section.rows().forEach(r -> card.getChildren().add(r.node()));
            view.getChildren().add(card);
        }
        view.getStyleClass().add("settings-page");
        view.setId("settings-page-" + page.id());
        return view;
    }

    // Enter in the find box: the cursor goes to the first option shown.
    private void focusFirstShown() {
        Page page = this.list.getSelectionModel().getSelectedItem();
        if (page == null) {
            return;
        }
        page.rows()
                .filter(r -> r.node().isVisible())
                .findFirst()
                .ifPresent(r -> r.control().requestFocus());
    }

    CustomTextField findField() {
        return this.find;
    }

    ToggleButton changedOnlyChip() {
        return this.changedOnly;
    }

    /** The titles of the pages listed. */
    List<String> listed() {
        return this.list.getItems().stream().map(Page::title).toList();
    }

    /** The page shown, if any. */
    String shownPage() {
        Page page = this.list.getSelectionModel().getSelectedItem();
        return page == null ? "" : page.id();
    }

    /** The titles of the options the page shown keeps. */
    List<String> shownRows() {
        Page page = this.list.getSelectionModel().getSelectedItem();
        if (page == null) {
            return List.of();
        }
        return page.rows()
                .filter(r -> r.node().isVisible())
                .map(SettingRow::title)
                .toList();
    }

    /** The option named {@code title}, on any page. */
    SettingRow row(String title) {
        return this.pages.stream()
                .flatMap(Page::rows)
                .filter(r -> r.title().equals(title))
                .findFirst()
                .orElseThrow();
    }

    /** The count on the chip. */
    String changedCount() {
        return this.changedCount.getText();
    }

    /** A page in the list: its icon and name, and a dot when it holds a changed option. */
    private final class PageCell extends ListCell<Page> {
        private final Icon icon = new Icon(Icons.OPTIONS);
        private final Label name = new Label();
        private final Icon dot = new Icon(Icons.DOT);
        private final HBox box;

        PageCell() {
            this.dot.getStyleClass().add("page-dot");
            Region gap = new Region();
            HBox.setHgrow(gap, Priority.ALWAYS);
            this.name.setMinWidth(0);
            this.box = new HBox(10, this.icon, this.name, gap, this.dot);
            this.box.setAlignment(Pos.CENTER_LEFT);
            // As wide as the list, never wider: a long name ends in an ellipsis instead of a scroll bar.
            setPrefWidth(0);
        }

        @Override
        protected void updateItem(Page page, boolean empty) {
            super.updateItem(page, empty);
            setText(null);
            if (empty || page == null) {
                setGraphic(null);
                setAccessibleText(null);
                return;
            }
            this.icon.setGlyph(page.icon());
            this.name.setText(page.title());
            boolean changed = page.rows().anyMatch(SettingRow::isChanged);
            this.dot.setVisible(changed);
            setGraphic(this.box);
            setAccessibleText(
                    changed ? page.title() + ", " + SettingsPages.this.gui.t("nj.settings.changed") : page.title());
        }
    }
}
