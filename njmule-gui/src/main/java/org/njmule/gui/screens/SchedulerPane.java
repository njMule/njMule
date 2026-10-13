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

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import javafx.beans.binding.BooleanBinding;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import org.kordamp.ikonli.material2.Material2AL;
import org.kordamp.ikonli.material2.Material2MZ;
import org.njmule.core.api.Category;
import org.njmule.core.config.Preferences;
import org.njmule.core.config.Schedule;
import org.njmule.gui.ui.Fx;
import org.njmule.gui.ui.Gui;
import org.njmule.gui.ui.Input;
import org.njmule.gui.ui.Inputs;

/**
 * The scheduler's rules: a list, a form for one rule, and saving them to the engine. Empty limit fields leave the
 * preferences' value.
 */
final class SchedulerPane extends VBox {

    private static final int MAX_NAME = 100;

    private final Gui gui;
    private final TableView<Schedule.Rule> rules = new TableView<>();
    private final TextField name = new TextField();
    private final Map<DayOfWeek, CheckBox> days = new EnumMap<>(DayOfWeek.class);
    private final TextField start = new TextField("00:00");
    private final TextField end = new TextField("00:00");
    private final TextField upload = new TextField();
    private final TextField download = new TextField();
    private final TextField connections = new TextField();
    private final TextField sources = new TextField();
    private final CheckBox enabled = new CheckBox();
    // A category id, 0 for the downloads in none; empty for no action.
    private final ComboBox<OptionalInt> stopCategory = new ComboBox<>();
    private final ComboBox<OptionalInt> resumeCategory = new ComboBox<>();
    private final Label active = new Label();
    private final Input<String> nameInput;
    private final Input<LocalTime> startInput;
    private final Input<LocalTime> endInput;
    private final Input<OptionalInt> uploadInput;
    private final Input<OptionalInt> downloadInput;
    private final Input<OptionalInt> connectionsInput;
    private final Input<OptionalInt> sourcesInput;

    SchedulerPane(Gui gui) {
        super(8);
        this.gui = gui;
        this.rules
                .getColumns()
                .addAll(List.of(
                        column(gui.t("nj.scheduler.name"), 150, Schedule.Rule::name),
                        column(gui.t("nj.scheduler.days"), 170, this::dayNames),
                        column(gui.t("nj.scheduler.from"), 70, r -> r.start().toString()),
                        column(gui.t("nj.scheduler.to"), 70, r -> r.end().toString()),
                        column(gui.t("nj.settings.upload"), 90, r -> text(r.uploadKiBps())),
                        column(gui.t("nj.settings.download"), 90, r -> text(r.downloadKiBps())),
                        column(gui.t("nj.scheduler.enabled"), 70, r -> r.enabled() ? "✓" : "")));
        this.rules.setPrefHeight(160);
        this.rules.setPlaceholder(new Label(gui.t("nj.scheduler.none")));
        this.rules.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        this.rules.getSelectionModel().selectedItemProperty().addListener((o, was, now) -> this.edit(now));
        FlowPane dayBoxes = new FlowPane(8, 4);
        for (DayOfWeek day : DayOfWeek.values()) {
            CheckBox box =
                    new CheckBox(day.getDisplayName(TextStyle.SHORT, gui.i18n().locale()));
            this.days.put(day, box);
            dayBoxes.getChildren().add(box);
        }
        this.enabled.setText(gui.t("nj.scheduler.enabled"));
        this.enabled.setSelected(true);
        for (TextField f : List.of(this.start, this.end, this.upload, this.download, this.connections, this.sources)) {
            f.setPrefColumnCount(6);
        }
        Inputs inputs = new Inputs(gui);
        String window = gui.t("nj.scheduler.from") + " / " + gui.t("nj.scheduler.to");
        this.nameInput = inputs.text(this.name, gui.t("nj.scheduler.name"), MAX_NAME, false);
        this.startInput = inputs.time(this.start, window);
        this.endInput = inputs.time(this.end, window);
        this.uploadInput =
                inputs.optionalNumber(this.upload, gui.t("nj.settings.upload"), 0, Preferences.MAX_RATE_KIBPS);
        this.downloadInput =
                inputs.optionalNumber(this.download, gui.t("nj.settings.download"), 0, Preferences.MAX_RATE_KIBPS);
        this.connectionsInput =
                inputs.optionalNumber(this.connections, gui.t("IDS_PW_MAXC"), 1, Preferences.MAX_CONNECTIONS);
        this.sourcesInput =
                inputs.optionalNumber(this.sources, gui.t("IDS_PW_MAXSOURCES"), 1, Preferences.MAX_SOURCES_PER_FILE);
        BooleanBinding invalid = Input.allValid(List.of(
                        this.nameInput,
                        this.startInput,
                        this.endInput,
                        this.uploadInput,
                        this.downloadInput,
                        this.connectionsInput,
                        this.sourcesInput))
                .not();
        GridPane form = new GridPane();
        form.setHgap(10);
        form.setVgap(6);
        form.addRow(0, new Label(gui.t("nj.scheduler.name")), this.name, this.enabled);
        form.addRow(1, new Label(gui.t("nj.scheduler.days")), dayBoxes);
        GridPane.setColumnSpan(dayBoxes, 2);
        form.addRow(2, new Label(window), new HBox(8, this.start, this.end));
        form.addRow(3, new Label(gui.t("nj.settings.upload")), this.upload);
        form.addRow(4, new Label(gui.t("nj.settings.download")), this.download);
        form.addRow(5, new Label(gui.t("IDS_PW_MAXC")), this.connections);
        form.addRow(6, new Label(gui.t("IDS_PW_MAXSOURCES")), this.sources);
        for (ComboBox<OptionalInt> box : List.of(this.stopCategory, this.resumeCategory)) {
            box.setCellFactory(l -> new CategoryCell());
            box.setButtonCell(new CategoryCell());
            box.setOnShowing(e -> this.fillCategories(box));
            this.fillCategories(box);
            box.setValue(OptionalInt.empty());
        }
        form.addRow(7, new Label(gui.t("IDS_SCHED_CATSTOP")), this.stopCategory);
        form.addRow(8, new Label(gui.t("IDS_SCHED_CATRESUME")), this.resumeCategory);
        Button add = Fx.button(gui.t("IDS_ADD"), Material2AL.ADD, this::add);
        Button replace = Fx.button(gui.t("nj.scheduler.replace"), Material2MZ.REFRESH, this::replace);
        add.disableProperty().bind(invalid);
        replace.disableProperty().bind(invalid);
        var tools = Fx.toolRow(
                add,
                replace,
                Fx.button(gui.t("IDS_REMOVE"), Material2AL.DELETE, this::remove),
                Fx.button(gui.t("nj.scheduler.save"), Material2MZ.SAVE, this::save),
                Fx.spacer(),
                this.active);
        this.getChildren().addAll(this.rules, form, tools);
        this.load();
    }

    /** Shows the engine's rules and the one that applies now. */
    void load() {
        this.rules.getItems().setAll(this.gui.model().engine().schedule().rules());
        this.showActive();
    }

    private void showActive() {
        this.active.setText(this.gui
                .model()
                .engine()
                .activeRule()
                .map(r -> this.gui.f("nj.scheduler.active", r.name()))
                .orElse(this.gui.t("nj.scheduler.inactive")));
    }

    void add() {
        this.read().ifPresent(rule -> this.rules.getItems().add(rule));
    }

    void replace() {
        int index = this.rules.getSelectionModel().getSelectedIndex();
        if (index >= 0) {
            this.read().ifPresent(rule -> this.rules.getItems().set(index, rule));
        }
    }

    void remove() {
        int index = this.rules.getSelectionModel().getSelectedIndex();
        if (index >= 0) {
            this.rules.getItems().remove(index);
        }
    }

    void save() {
        this.gui.model().updateSchedule(new Schedule(List.copyOf(this.rules.getItems())), s -> {
            this.showActive();
            this.gui.inform().accept(this.gui.t("nj.settings.saved"));
        });
    }

    private void edit(Schedule.Rule rule) {
        if (rule == null) {
            return;
        }
        this.name.setText(rule.name());
        this.enabled.setSelected(rule.enabled());
        this.days.forEach((day, box) -> box.setSelected(rule.days().contains(day)));
        this.start.setText(rule.start().toString());
        this.end.setText(rule.end().toString());
        this.upload.setText(text(rule.uploadKiBps()));
        this.download.setText(text(rule.downloadKiBps()));
        this.connections.setText(text(rule.maxConnections()));
        this.sources.setText(text(rule.maxSourcesPerFile()));
        this.stopCategory.setValue(rule.stopCategory());
        this.resumeCategory.setValue(rule.resumeCategory());
    }

    // No action, the downloads in no category, then the categories.
    private void fillCategories(ComboBox<OptionalInt> box) {
        OptionalInt chosen = box.getValue();
        List<OptionalInt> items = new ArrayList<>();
        items.add(OptionalInt.empty());
        items.add(OptionalInt.of(Category.NONE));
        this.gui.model().categories().forEach(c -> items.add(OptionalInt.of(c.id())));
        box.getItems().setAll(items);
        box.setValue(chosen == null ? OptionalInt.empty() : chosen);
    }

    private String categoryName(OptionalInt id) {
        if (id == null || id.isEmpty()) {
            return "-";
        }
        if (id.getAsInt() == Category.NONE) {
            return this.gui.t("IDS_ALLOTHERS");
        }
        return this.gui.model().categories().stream()
                .filter(c -> c.id() == id.getAsInt())
                .map(Category::name)
                .findFirst()
                .orElse("#" + id.getAsInt());
    }

    private final class CategoryCell extends ListCell<OptionalInt> {
        @Override
        protected void updateItem(OptionalInt item, boolean empty) {
            super.updateItem(item, empty);
            this.setText(empty ? null : SchedulerPane.this.categoryName(item));
        }
    }

    /** The rule of the form; a message and nothing when it is not valid. */
    Optional<Schedule.Rule> read() {
        Set<DayOfWeek> chosen = EnumSet.noneOf(DayOfWeek.class);
        this.days.forEach((day, box) -> {
            if (box.isSelected()) {
                chosen.add(day);
            }
        });
        try {
            return Optional.of(new Schedule.Rule(
                    this.nameInput.value(),
                    this.enabled.isSelected(),
                    chosen,
                    this.startInput.value(),
                    this.endInput.value(),
                    this.uploadInput.value(),
                    this.downloadInput.value(),
                    this.connectionsInput.value(),
                    this.sourcesInput.value(),
                    orEmpty(this.stopCategory.getValue()),
                    orEmpty(this.resumeCategory.getValue())));
        } catch (IllegalArgumentException e) {
            this.gui.inform().accept(this.gui.f("nj.settings.invalid", e.getMessage()));
            return Optional.empty();
        }
    }

    private String dayNames(Schedule.Rule rule) {
        return rule.days().stream()
                .sorted()
                .map(d -> d.getDisplayName(TextStyle.SHORT, this.gui.i18n().locale()))
                .collect(Collectors.joining(" "));
    }

    private static OptionalInt orEmpty(OptionalInt value) {
        return value == null ? OptionalInt.empty() : value;
    }

    private static String text(OptionalInt value) {
        return value.isPresent() ? String.valueOf(value.getAsInt()) : "";
    }

    private static TableColumn<Schedule.Rule, String> column(
            String title, double width, Function<Schedule.Rule, String> value) {
        TableColumn<Schedule.Rule, String> column = new TableColumn<>(title);
        column.setPrefWidth(width);
        column.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(value.apply(c.getValue())));
        return column;
    }

    TableView<Schedule.Rule> rulesTable() {
        return this.rules;
    }

    TextField nameField() {
        return this.name;
    }

    Map<DayOfWeek, CheckBox> dayBoxes() {
        return this.days;
    }

    TextField uploadField() {
        return this.upload;
    }

    TextField startField() {
        return this.start;
    }

    Label activeLabel() {
        return this.active;
    }

    ComboBox<OptionalInt> stopCategoryBox() {
        return this.stopCategory;
    }
}
