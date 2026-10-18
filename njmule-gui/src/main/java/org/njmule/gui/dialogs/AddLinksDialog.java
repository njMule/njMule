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
package org.njmule.gui.dialogs;

import atlantafx.base.theme.Styles;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.input.Clipboard;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import org.njmule.core.api.Category;
import org.njmule.core.api.Ed2kLink;
import org.njmule.core.api.FileHistory;
import org.njmule.core.api.FileKind;
import org.njmule.core.config.Preferences;
import org.njmule.gui.ui.ClipboardWatch;
import org.njmule.gui.ui.Fx;
import org.njmule.gui.ui.Gui;
import org.njmule.gui.ui.Icons;

/**
 * eD2K file links to download, typed or pasted in a box: the files found in it, each with what is known of it already,
 * all or some of them, in a category, paused or not.
 */
public final class AddLinksDialog {

    // The category the rules of the categories pick by the file's name.
    private static final int BY_RULES = -1;

    private final Gui gui;
    private final TextArea text = Fx.leftToRight(new TextArea());
    private final Map<Ed2kLink.FileLink, CheckBox> links = new LinkedHashMap<>();
    private final Map<Ed2kLink.FileLink, Node> rows = new LinkedHashMap<>();
    private final VBox list = new VBox(4);
    private final Label found = new Label();
    private final Label total = new Label();
    private final ComboBox<Integer> category = new ComboBox<>();
    private final CheckBox paused;
    private final BooleanProperty noneChosen = new SimpleBooleanProperty(true);
    private final Alert alert;
    private final ButtonType download;

    /**
     * For the links in {@code text}, in {@code shown} by default when a category is shown, else in that of their names.
     */
    public AddLinksDialog(Gui gui, Window owner, String text, OptionalInt shown) {
        this.gui = gui;
        Label prompt = new Label(gui.t("nj.addLinks.prompt"));
        prompt.setLabelFor(this.text);
        prompt.getStyleClass().add("field-label");
        Button paste = Fx.button(gui.t("IDS_PASTE"), Icons.PASTE, this::paste);
        paste.getStyleClass().add(Styles.FLAT);
        HBox promptRow = new HBox(8, prompt, Fx.spacer(), paste);
        promptRow.setAlignment(Pos.CENTER_LEFT);
        this.text.setPromptText("ed2k://|file|…|/");
        this.text.setPrefRowCount(4);
        this.text.setWrapText(false);
        this.text.setId("links-text");
        Label hint = new Label(gui.t("nj.addLinks.hint"));
        Fx.wrap(hint);
        hint.getStyleClass().add("text-muted");
        this.found.getStyleClass().add("field-label");
        ScrollPane scroll = new ScrollPane(this.list);
        scroll.setFitToWidth(true);
        scroll.setPrefViewportHeight(160);
        scroll.getStyleClass().add("links-found");
        List<Integer> choices = new ArrayList<>(List.of(BY_RULES, Category.NONE));
        gui.model().categories().forEach(c -> choices.add(c.id()));
        this.category.getItems().setAll(choices);
        this.category.setCellFactory(l -> new CategoryCell());
        this.category.setButtonCell(new CategoryCell());
        this.category.setValue(shown.isPresent() && choices.contains(shown.getAsInt()) ? shown.getAsInt() : BY_RULES);
        Label categoryLabel = Fx.whole(new Label(gui.t("IDS_CAT")));
        categoryLabel.setLabelFor(this.category);
        this.paused = new CheckBox(gui.t("IDS_ADDNEWFILESPAUSED"));
        Preferences preferences = gui.model().preferencesProperty().get();
        this.paused.setSelected(preferences != null && preferences.addDownloadsPaused());
        HBox choiceRow = new HBox(8, categoryLabel, this.category);
        choiceRow.setAlignment(Pos.CENTER_LEFT);
        this.total.getStyleClass().add("text-muted");
        VBox content = new VBox(10, promptRow, this.text, hint, this.found, scroll, choiceRow, this.paused, this.total);
        content.getStyleClass().add("add-links");
        content.setPrefWidth(560);
        this.alert = gui.dialog(new Alert(Alert.AlertType.CONFIRMATION, null));
        this.alert.setTitle(gui.t("nj.addLinks.title"));
        this.alert.setHeaderText(null);
        this.alert.setGraphic(null);
        this.alert.getDialogPane().setContent(content);
        this.download = Fx.answers(this.alert, gui.t("IDS_DOWNLOAD"), gui.t("IDS_CANCEL"), false);
        Button go = (Button) this.alert.getDialogPane().lookupButton(this.download);
        Fx.primary(go);
        go.disableProperty().bind(this.noneChosen);
        if (owner != null) {
            this.alert.initOwner(owner);
        }
        this.text.textProperty().addListener((o, was, now) -> findLinks(now));
        this.text.setText(text);
        findLinks(text);
    }

    /** For {@code links}, already found. */
    public AddLinksDialog(Gui gui, Window owner, List<Ed2kLink.FileLink> links, OptionalInt shown) {
        this(
                gui,
                owner,
                String.join("\n", links.stream().map(Ed2kLink.FileLink::toUri).toList()),
                shown);
    }

    /**
     * What is known of the file of {@code link} already, for a word beside it: being downloaded, shared, downloaded or
     * cancelled before; empty for a file never seen.
     */
    public static Optional<String> note(Gui gui, Ed2kLink.FileLink link) {
        if (gui.model().downloads().contains(link.hash())) {
            return Optional.of(gui.t("IDS_IMP_ALRDWL"));
        }
        if (gui.model().isShared(link.hash())) {
            return Optional.of(gui.t("IDS_SHARED"));
        }
        FileHistory history = gui.model().historyProperty().get();
        if (history != null && history.downloaded(link.hash())) {
            return Optional.of(gui.t("IDS_DL_TRANSFCOMPL"));
        }
        if (history != null && history.cancelled(link.hash())) {
            return Optional.of(gui.t("IDS_CANCELLED"));
        }
        return Optional.empty();
    }

    /** Shows the dialog and waits; whether the user chose to download. */
    public boolean ask() {
        this.text.requestFocus();
        return this.alert.showAndWait().filter(this.download::equals).isPresent();
    }

    /** Waits for the answer, then adds the links ticked. */
    public void showAndAdd() {
        if (ask()) {
            addChosen();
        }
    }

    /** Adds the links ticked, in the category and the state chosen. */
    public void addChosen() {
        int chosen = this.category.getValue();
        OptionalInt in = chosen == BY_RULES ? OptionalInt.empty() : OptionalInt.of(chosen);
        boolean asPaused = this.paused.isSelected();
        this.links.forEach((link, box) -> {
            if (box.isSelected()) {
                this.gui.model().addDownload(link, in, asPaused);
            }
        });
    }

    // The clipboard's text goes after what is typed, on a line of its own.
    private void paste() {
        String pasted = Clipboard.getSystemClipboard().getString();
        if (pasted == null || pasted.isBlank()) {
            return;
        }
        String now = this.text.getText();
        this.text.setText(now.isBlank() ? pasted : now.stripTrailing() + "\n" + pasted);
    }

    // The files of the links in the box, each once; a file still there keeps its tick.
    private void findLinks(String typed) {
        Map<Ed2kLink.FileLink, CheckBox> before = new LinkedHashMap<>(this.links);
        this.links.clear();
        this.rows.clear();
        for (Ed2kLink.FileLink link : ClipboardWatch.fileLinks(typed == null ? "" : typed)) {
            if (this.links.keySet().stream().anyMatch(l -> l.hash().equals(link.hash()))) {
                continue;
            }
            CheckBox box = before.get(link);
            if (box == null) {
                box = new CheckBox(this.gui.i18n().embed(link.name()));
                box.setGraphic(Fx.icon(Fx.fileIcon(FileKind.of(link.name()))));
                // A file being downloaded or shared already is there: it is left out unless ticked.
                box.setSelected(!this.gui.model().downloads().contains(link.hash())
                        && !this.gui.model().isShared(link.hash()));
                box.selectedProperty().addListener((o, was, now) -> showChosen());
                box.setMinWidth(0);
                box.setMaxWidth(Double.MAX_VALUE);
            }
            this.links.put(link, box);
            this.rows.put(link, row(link, box));
        }
        this.list.getChildren().setAll(this.rows.values());
        this.found.setText(this.gui.f("nj.addLinks.found", this.links.size()));
        showChosen();
    }

    // A file found: its tick and name, what is known of it, its size.
    private Node row(Ed2kLink.FileLink link, CheckBox box) {
        Optional<String> known = note(this.gui, link);
        String size = this.gui.formats().size(link.size());
        box.setAccessibleText(
                link.name() + ", " + size + known.map(n -> ", " + n).orElse(""));
        HBox row = new HBox(8, box);
        HBox.setHgrow(box, Priority.ALWAYS);
        known.ifPresent(n -> {
            Label chip = Fx.chip(n, Icons.INFO, "neutral");
            chip.getStyleClass().add("link-note");
            row.getChildren().add(chip);
        });
        Label shownSize = Fx.leftToRight(new Label(size));
        shownSize.getStyleClass().add("text-muted");
        shownSize.setMinWidth(Region.USE_PREF_SIZE);
        row.getChildren().add(shownSize);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("link-row");
        return row;
    }

    // How many files and how much will be added; nothing to add, nothing to press.
    private void showChosen() {
        List<Ed2kLink.FileLink> chosen = this.links.entrySet().stream()
                .filter(e -> e.getValue().isSelected())
                .map(Map.Entry::getKey)
                .toList();
        long bytes = chosen.stream().mapToLong(Ed2kLink.FileLink::size).sum();
        this.total.setText(
                chosen.isEmpty()
                        ? ""
                        : chosen.size() + " · " + this.gui.formats().size(bytes));
        this.noneChosen.set(chosen.isEmpty());
    }

    TextArea text() {
        return this.text;
    }

    Map<Ed2kLink.FileLink, CheckBox> links() {
        return this.links;
    }

    /** The word on what is known of {@code link}'s file, empty for none. */
    String noteOf(Ed2kLink.FileLink link) {
        Node row = this.rows.get(link);
        return row == null
                ? ""
                : row.lookupAll(".link-note").stream()
                        .map(n -> ((Label) n).getText())
                        .findFirst()
                        .orElse("");
    }

    ComboBox<Integer> category() {
        return this.category;
    }

    CheckBox paused() {
        return this.paused;
    }

    Alert alert() {
        return this.alert;
    }

    /** Whether the download button is off, nothing being ticked. */
    boolean nothingToAdd() {
        return this.noneChosen.get();
    }

    private String categoryName(int id) {
        if (id == BY_RULES) {
            return this.gui.t("nj.transfers.categoryByRules");
        }
        if (id == Category.NONE) {
            return this.gui.t("IDS_CAT_UNASSIGN");
        }
        return this.gui.model().categories().stream()
                .filter(c -> c.id() == id)
                .map(Category::name)
                .findFirst()
                .orElse(String.valueOf(id));
    }

    private final class CategoryCell extends ListCell<Integer> {
        @Override
        protected void updateItem(Integer item, boolean empty) {
            super.updateItem(item, empty);
            setText(empty || item == null ? null : categoryName(item));
        }
    }
}
