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

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import javafx.beans.property.ReadOnlyIntegerWrapper;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.kordamp.ikonli.material2.Material2AL;
import org.kordamp.ikonli.material2.Material2MZ;
import org.njmule.core.api.DownloadInfo;
import org.njmule.core.api.FileNames;
import org.njmule.core.api.Hash16;
import org.njmule.core.api.PartMap;
import org.njmule.core.api.SourceInfo;
import org.njmule.core.config.Preferences;
import org.njmule.gui.model.LiveList;
import org.njmule.gui.ui.Fx;
import org.njmule.gui.ui.Gui;
import org.njmule.gui.ui.PartBar;

/**
 * The details of the download chosen, beside the list: its parts drawn large with what each colour means, the facts
 * that the narrow columns cut short, and the names its sources give it, to rename it by.
 */
final class DownloadDetails {

    /** A name the sources give the download, and how many give it. */
    record SourceName(String name, int sources) {}

    /** How the screen words what the details show. */
    record Words(
            Function<DownloadInfo, String> state,
            Function<DownloadInfo, String> priority,
            Function<DownloadInfo, String> category,
            Function<DownloadInfo, String> remaining) {}

    private final Gui gui;
    private final Words words;
    private final Label name = new Label();
    private final PartBar bar = new PartBar();
    private final GridPane facts = new GridPane();
    private final TextField hash = Fx.leftToRight(new TextField());
    private final TableView<SourceName> names = new TableView<>();
    private final TextField newName = new TextField();
    private final Button takeOver;
    private final Button cleanUp;
    private final Button rename;
    // The download the new name is for: it is reset only when another one is shown, not as the figures move.
    private Hash16 renaming;
    private final VBox shown;
    private final Label none;
    private final StackPane view;

    DownloadDetails(Gui gui, Words words, Runnable copyLink, Runnable comments) {
        this.gui = gui;
        this.words = words;
        this.name.getStyleClass().add("details-name");
        this.name.setWrapText(true);
        this.name.setMinHeight(Region.USE_PREF_SIZE);
        Fx.ownDirection(this.name);
        this.bar.setPrefHeight(26);
        this.bar.setMinHeight(26);
        this.hash.setEditable(false);
        this.hash.getStyleClass().add("details-hash");
        this.facts.getStyleClass().add("details-facts");
        HBox actions = new HBox(
                8,
                Fx.button(gui.t("nj.copyLink"), Material2AL.CONTENT_COPY, copyLink),
                Fx.button(gui.t("IDS_CMT_ADD"), Material2MZ.RATE_REVIEW, comments));
        actions.setAlignment(Pos.CENTER_LEFT);
        Label parts = new Label(gui.t("IDS_FD_PARTS"));
        parts.getStyleClass().add("details-heading");
        Label sourceNames = new Label(gui.t("IDS_SOURCENAMES"));
        sourceNames.getStyleClass().add("details-heading");
        this.takeOver = Fx.button(gui.t("IDS_TAKEOVER"), Material2MZ.VERTICAL_ALIGN_BOTTOM, this::takeOver);
        this.takeOver.setId("take-over");
        this.cleanUp = Fx.button(gui.t("IDS_CLEANUP"), Material2AL.CLEANING_SERVICES, this::cleanUp);
        this.cleanUp.setId("clean-up");
        this.rename = Fx.button(gui.t("IDS_RENAME"), Material2AL.EDIT, this::rename);
        this.rename.setId("rename");
        this.newName.setId("new-name");
        this.newName.setAccessibleText(gui.t("IDS_RENAME"));
        this.newName.setMaxWidth(Double.MAX_VALUE);
        // The name the whole width, the buttons below it: the pane is narrow.
        FlowPane renameActions = new FlowPane(8, 8, this.takeOver, this.cleanUp, this.rename);
        this.shown = new VBox(
                10,
                this.name,
                parts,
                this.bar,
                legend(),
                this.facts,
                actions,
                sourceNames,
                namesTable(gui),
                this.newName,
                renameActions);
        this.shown.getStyleClass().add("details-content");
        this.none = new Label(gui.t("nj.details.none"));
        this.none.setWrapText(true);
        this.none.getStyleClass().add("text-muted");
        ScrollPane scroll = new ScrollPane(this.shown);
        scroll.setFitToWidth(true);
        this.view = new StackPane(scroll, this.none);
        this.view.getStyleClass().add("download-details");
        this.view.setMinWidth(240);
        show(Optional.empty());
    }

    Node view() {
        return this.view;
    }

    TableView<SourceName> names() {
        return this.names;
    }

    TextField newName() {
        return this.newName;
    }

    /** The button of {@code action}: take-over, clean-up or rename. */
    Button button(String action) {
        return switch (action) {
            case "take-over" -> this.takeOver;
            case "clean-up" -> this.cleanUp;
            case "rename" -> this.rename;
            default -> throw new IllegalArgumentException(action);
        };
    }

    /** Shows {@code download}, or a word on how to choose one. */
    void show(Optional<DownloadInfo> download) {
        this.shown.setVisible(download.isPresent());
        this.none.setVisible(download.isEmpty());
        download.ifPresent(this::fill);
    }

    private void fill(DownloadInfo d) {
        var f = this.gui.formats();
        this.name.setText(d.name());
        fillNames(d);
        this.bar.set(d.parts(), d.completed());
        int total = (int) ((d.size() + PartMap.PART_SIZE - 1) / PartMap.PART_SIZE);
        int have = d.status() == DownloadInfo.Status.COMPLETE
                ? total
                : d.parts().size() == 0 ? (int) (d.completed() / PartMap.PART_SIZE) : complete(d.parts(), total);
        String percent = f.percent(d.completed(), d.size());
        this.bar.setAccessibleText(percent + ", " + have + "/" + total);
        this.facts.getChildren().clear();
        int row = 0;
        row = fact(row, "IDS_FD_SIZE", f.size(d.size()));
        row = fact(row, "IDS_FD_COMPSIZE", f.size(d.completed()) + " (" + percent + ")");
        row = fact(row, "IDS_FD_PARTS", have + " / " + total);
        row = fact(row, "IDS_FD_DATARATE", f.rate(d.bytesPerSecond()));
        row = fact(row, "IDS_DL_SOURCES", String.valueOf(d.sources()));
        row = fact(row, "IDS_TRANSFERRING", String.valueOf(d.transferring()));
        row = fact(row, "nj.transfers.status", this.words.state().apply(d));
        row = fact(row, "IDS_PRIORITY", this.words.priority().apply(d));
        row = fact(row, "IDS_CAT", this.words.category().apply(d));
        row = fact(row, "IDS_DL_REMAINS", this.words.remaining().apply(d));
        this.hash.setText(d.hash().toString());
        Label hashName = label("IDS_FD_HASH");
        this.facts.add(hashName, 0, row);
        this.facts.add(this.hash, 1, row);
    }

    // The names of the sources of the download, the most given first; a choice made stays while the list moves.
    private void fillNames(DownloadInfo d) {
        Map<String, Integer> counts = new HashMap<>();
        for (LiveList.Row<SourceInfo> row : this.gui.model().sources().rows()) {
            SourceInfo source = row.get();
            if (source.file().equals(d.hash())) {
                source.reportedName().ifPresent(n -> counts.merge(n, 1, Integer::sum));
            }
        }
        List<SourceName> shownNames = counts.entrySet().stream()
                .map(e -> new SourceName(e.getKey(), e.getValue()))
                .sorted(Comparator.comparingInt(SourceName::sources).reversed().thenComparing(SourceName::name))
                .toList();
        if (!this.names.getItems().equals(shownNames)) {
            SourceName chosen = this.names.getSelectionModel().getSelectedItem();
            this.names.getItems().setAll(shownNames);
            shownNames.stream()
                    .filter(n -> chosen != null && n.name().equals(chosen.name()))
                    .findFirst()
                    .ifPresent(n -> this.names.getSelectionModel().select(n));
        }
        if (!d.hash().equals(this.renaming)) {
            this.renaming = d.hash();
            this.newName.setText(d.name());
        }
        // A finished download has its file already.
        boolean renamable = d.status() != DownloadInfo.Status.COMPLETE;
        this.newName.setDisable(!renamable);
        this.takeOver.setDisable(!renamable);
        this.cleanUp.setDisable(!renamable);
        this.rename.setDisable(!renamable);
    }

    private TableView<SourceName> namesTable(Gui gui) {
        TableColumn<SourceName, String> nameColumn = new TableColumn<>(gui.t("IDS_DL_FILENAME"));
        nameColumn.setCellValueFactory(
                c -> new ReadOnlyStringWrapper(c.getValue().name()));
        Fx.ownDirection(nameColumn);
        TableColumn<SourceName, Number> countColumn = new TableColumn<>(gui.t("IDS_DL_SOURCES"));
        countColumn.setCellValueFactory(
                c -> new ReadOnlyIntegerWrapper(c.getValue().sources()));
        countColumn.setMaxWidth(90);
        for (TableColumn<SourceName, ?> column : List.of(nameColumn, countColumn)) {
            column.setSortable(false);
        }
        this.names.getColumns().setAll(List.of(nameColumn, countColumn));
        this.names.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        this.names.setPlaceholder(new Label());
        this.names.setPrefHeight(150);
        this.names.setMinHeight(90);
        this.names.setId("source-names");
        this.names.setRowFactory(t -> {
            TableRow<SourceName> row = new TableRow<>();
            row.setOnMouseClicked(e -> {
                if (e.getClickCount() == 2 && !row.isEmpty()) {
                    takeOver();
                }
            });
            return row;
        });
        return this.names;
    }

    private void takeOver() {
        SourceName chosen = this.names.getSelectionModel().getSelectedItem();
        if (chosen != null && !this.newName.isDisabled()) {
            this.newName.setText(chosen.name());
        }
    }

    private void cleanUp() {
        Preferences preferences = this.gui.model().preferencesProperty().get();
        String words = preferences == null ? Preferences.DEFAULT_NAME_CLEANUPS : preferences.nameCleanups();
        this.newName.setText(FileNames.cleanUp(this.newName.getText(), words));
    }

    private void rename() {
        String chosen = this.newName.getText().strip();
        if (this.renaming != null && !chosen.isEmpty()) {
            this.gui.model().renameDownload(this.renaming, chosen);
        }
    }

    private int fact(int row, String key, String value) {
        Label shownValue = new Label(value);
        shownValue.setWrapText(true);
        shownValue.getStyleClass().add("details-value");
        this.facts.add(label(key), 0, row);
        this.facts.add(shownValue, 1, row);
        return row + 1;
    }

    private Label label(String key) {
        String text = this.gui.t(key).strip();
        Label label = new Label(text.endsWith(":") ? text.substring(0, text.length() - 1) : text);
        label.getStyleClass().add("text-muted");
        label.setMinWidth(Region.USE_PREF_SIZE);
        return label;
    }

    // What each colour of the bar means; the words are there, not only the colours.
    private Node legend() {
        FlowPane legend = new FlowPane(12, 4);
        legend.getStyleClass().add("details-legend");
        for (List<String> entry : List.of(
                List.of("bar-have", "nj.legend.have"),
                List.of("bar-missing", "nj.legend.noSource"),
                List.of("bar-sources-max", "nj.legend.sources"),
                List.of("bar-requested", "nj.legend.requested"),
                List.of("bar-complete", "nj.legend.done"))) {
            Region swatch = new Region();
            swatch.getStyleClass().addAll("legend-swatch", entry.get(0));
            Label label = new Label(this.gui.t(entry.get(1)), swatch);
            label.setWrapText(true);
            legend.getChildren().add(label);
        }
        return legend;
    }

    // The parts with no byte missing.
    static int complete(PartMap map, int total) {
        boolean[] missing = new boolean[total];
        for (PartMap.Span gap : map.missing()) {
            int first = (int) (gap.start() / PartMap.PART_SIZE);
            int last = (int) (Math.max(gap.start(), gap.end() - 1) / PartMap.PART_SIZE);
            for (int p = first; p <= last && p < total; p++) {
                missing[p] = true;
            }
        }
        int have = 0;
        for (boolean m : missing) {
            if (!m) {
                have++;
            }
        }
        return have;
    }
}
