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
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.njmule.core.api.ImportOptions;
import org.njmule.core.api.ImportReport;
import org.njmule.gui.ui.DialogFrame;
import org.njmule.gui.ui.Fx;
import org.njmule.gui.ui.Gui;
import org.njmule.gui.ui.Icons;
import org.njmule.gui.ui.Input;
import org.njmule.gui.ui.Inputs;

/**
 * Taking an eMule profile over: a check first shows what would be taken, then the import itself. The eMule directory is
 * only read.
 */
public final class ImportDialog {

    private final Gui gui;
    private final Stage stage = new Stage();
    private final TextField directory = new TextField();
    private final Input<Path> directoryInput;
    private final BooleanProperty working = new SimpleBooleanProperty();
    private final CheckBox identity = new CheckBox();
    private final CheckBox downloads = new CheckBox();
    private final TableView<ImportReport.Item> report = new TableView<>();
    private final Label summary = new Label();
    private final Button check;
    private final Button take;

    public ImportDialog(Gui gui, Window owner) {
        this.gui = gui;
        this.stage.initOwner(owner);
        this.stage.initModality(Modality.WINDOW_MODAL);
        this.stage.setTitle(gui.t("nj.import.title"));
        this.directory.setPrefColumnCount(36);
        guessEmuleDirectory().ifPresent(p -> this.directory.setText(p.toString()));
        this.identity.setText(gui.t("nj.import.identity"));
        this.downloads.setText(gui.t("nj.import.downloads"));
        this.directoryInput = new Inputs(gui)
                .existingFolder(this.directory, gui.t("nj.import.directory"))
                .quietWhenEmpty();
        this.check = Fx.button(gui.t("nj.import.check"), Icons.SEARCH, () -> run(true));
        this.check
                .disableProperty()
                .bind(this.directoryInput.validProperty().not().or(this.working));
        this.take = Fx.button(gui.t("nj.import.run"), Icons.IMPORT, () -> run(false));
        this.take.setDisable(true);

        TableColumn<ImportReport.Item, String> file = new TableColumn<>(gui.t("IDS_DL_FILENAME"));
        file.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().file()));
        file.setPrefWidth(150);
        TableColumn<ImportReport.Item, String> detail = new TableColumn<>(gui.t("nj.import.detail"));
        detail.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().detail()));
        detail.setPrefWidth(400);
        TableColumn<ImportReport.Item, ImportReport.Outcome> outcome = new TableColumn<>(gui.t("nj.import.outcome"));
        outcome.setCellValueFactory(
                c -> new ReadOnlyObjectWrapper<>(c.getValue().outcome()));
        outcome.setCellFactory(c -> new OutcomeCell());
        outcome.setPrefWidth(170);
        this.report.getColumns().addAll(List.of(Fx.leftToRight(file), Fx.leftToRight(detail), outcome));
        this.report.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        this.report.setPlaceholder(new Label(gui.t("nj.import.empty")));
        this.report.getStyleClass().add("import-report");

        Label intro = new Label(gui.t("nj.import.intro"));
        // Read whole: the report below gives way.
        intro.setMinHeight(Region.USE_PREF_SIZE);
        intro.setWrapText(true);
        intro.getStyleClass().add(Styles.TEXT_MUTED);
        Label dirName = new Label(gui.t("nj.import.directory"));
        dirName.getStyleClass().add("dialog-label");
        dirName.setLabelFor(this.directory);
        Fx.leftToRight(this.directory);
        HBox dirRow = new HBox(8, this.directory, Fx.button(gui.t("IDS_PW_BROWSE"), Icons.FOLDER_OPEN, this::browse));
        HBox.setHgrow(this.directory, Priority.ALWAYS);
        // One option a card, each wrapping: their texts are long in every language.
        GridPane options = new GridPane(8, 8);
        for (var option : List.of(this.identity, this.downloads)) {
            option.setWrapText(true);
            option.setMinHeight(Region.USE_PREF_SIZE);
            option.setMaxWidth(Double.MAX_VALUE);
            option.setMaxHeight(Double.MAX_VALUE);
            option.getStyleClass().add("import-option");
            ColumnConstraints half = new ColumnConstraints();
            half.setPercentWidth(50);
            options.getColumnConstraints().add(half);
            options.add(option, options.getChildren().size(), 0);
        }
        Label happens = new Label(gui.t("nj.import.whatHappens"));
        happens.getStyleClass().add("card-heading");
        this.summary.getStyleClass().add(Styles.TEXT_MUTED);
        this.summary.setWrapText(true);
        this.summary.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(this.summary, Priority.ALWAYS);
        Button close = Fx.button(gui.t("IDS_FD_CLOSE"), Icons.CLOSE, this.stage::close);
        close.setCancelButton(true);
        // Its words change once checked: a width shared with the others would be measured on the first ones.
        ButtonBar.setButtonUniformSize(this.check, false);
        VBox body = new VBox(12, intro, new VBox(6, dirName, dirRow), options, happens, this.report);
        VBox.setVgrow(this.report, Priority.ALWAYS);
        VBox root = DialogFrame.frame(
                DialogFrame.header(gui, gui.t("nj.import.heading"), this.stage::close),
                body,
                DialogFrame.footer(
                        this.summary,
                        DialogFrame.role(close, ButtonBar.ButtonData.CANCEL_CLOSE),
                        DialogFrame.role(this.check, ButtonBar.ButtonData.OTHER),
                        DialogFrame.role(this.take, ButtonBar.ButtonData.OK_DONE)));
        Scene scene = new Scene(root, 800, 580);
        gui.attach(scene);
        this.stage.setScene(scene);
        showChecked(false);
    }

    // Before a check, Check is the main action; once it has shown what would happen, Import is.
    private void showChecked(boolean checked) {
        this.check.setText(this.gui.t(checked ? "nj.import.checkAgain" : "nj.import.check"));
        this.check.setGraphic(Fx.icon(checked ? Icons.REFRESH : Icons.SEARCH));
        this.check.getStyleClass().remove("accent");
        this.take.getStyleClass().remove("accent");
        (checked ? this.take : this.check).getStyleClass().add("accent");
        (checked ? this.take : this.check).setDefaultButton(true);
        (checked ? this.check : this.take).setDefaultButton(false);
    }

    public void show() {
        this.stage.show();
    }

    /** eMule's usual config directory on this computer, if there is one. */
    public static Optional<Path> guessEmuleDirectory() {
        List<String> bases = new ArrayList<>();
        for (String variable : List.of("LOCALAPPDATA", "APPDATA")) {
            String value = System.getenv(variable);
            if (value != null) {
                bases.add(value);
            }
        }
        for (String base : bases) {
            try {
                Path config = Path.of(base, "eMule", "config");
                if (Files.isDirectory(config)) {
                    return Optional.of(config);
                }
            } catch (InvalidPathException e) {
                // Not a usable path.
            }
        }
        return Optional.empty();
    }

    private void browse() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle(this.gui.t("nj.import.directory"));
        var dir = chooser.showDialog(this.stage);
        if (dir != null) {
            this.directory.setText(dir.getAbsolutePath());
        }
    }

    /** Checks ({@code dryRun}) or imports the profile in the directory field. */
    void run(boolean dryRun) {
        Path dir;
        try {
            dir = Path.of(this.directory.getText().strip());
        } catch (InvalidPathException e) {
            this.gui.warn().accept(e.getMessage());
            return;
        }
        if (!Files.isDirectory(dir)) {
            this.gui.warn().accept(this.gui.f("nj.import.notADirectory", dir));
            return;
        }
        this.working.set(true);
        this.take.setDisable(true);
        this.summary.setText(this.gui.t("nj.import.working"));
        this.gui
                .model()
                .importFromEmule(
                        dir,
                        new ImportOptions(dryRun, this.identity.isSelected(), this.downloads.isSelected()),
                        result -> {
                            this.report.getItems().setAll(result.items());
                            long failed = result.items().stream()
                                    .filter(i -> i.outcome() == ImportReport.Outcome.FAILED)
                                    .count();
                            this.summary.setText(this.gui.f(
                                    dryRun ? "nj.import.checked" : "nj.import.done",
                                    result.items().size(),
                                    failed));
                            this.working.set(false);
                            this.take.setDisable(!dryRun);
                            showChecked(true);
                            if (!dryRun) {
                                // Taken: nothing is left to do here but to close.
                                this.take.getStyleClass().remove("accent");
                                this.take.setDefaultButton(false);
                            }
                            if (!dryRun && result.startAtLogin()) {
                                startAtLogin();
                            }
                        },
                        error -> {
                            // A check may be tried again; an import too, once it was checked.
                            this.summary.setText(this.gui.f("nj.import.failed", error));
                            this.working.set(false);
                            this.take.setDisable(dryRun);
                        });
    }

    // eMule started at login: njMule does now, where it can; asking the system takes a moment.
    private void startAtLogin() {
        this.gui
                .autostart()
                .ifPresent(system -> Thread.ofVirtual().name("njmule-autostart").start(() -> {
                    try {
                        if (!system.isOn()) {
                            system.turnOn();
                        }
                        Platform.runLater(() -> this.gui.inform().accept(this.gui.t("nj.import.autostart")));
                    } catch (IOException e) {
                        Platform.runLater(() ->
                                this.gui.warn().accept(this.gui.f("nj.settings.autostartFailed", e.getMessage())));
                    }
                }));
    }

    private String outcome(ImportReport.Outcome outcome) {
        return this.gui.t("nj.import.outcome." + outcome.name().toLowerCase(Locale.ROOT));
    }

    /** What happens to a file, as a chip: taken in the success tone, left out neutral, unreadable in danger. */
    private final class OutcomeCell extends TableCell<ImportReport.Item, ImportReport.Outcome> {

        private final Label chip = Fx.chip("", Icons.CHECK_CIRCLE, "neutral");

        @Override
        protected void updateItem(ImportReport.Outcome item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setGraphic(null);
                return;
            }
            switch (item) {
                case IMPORTED, WOULD_IMPORT -> Fx.chip(this.chip, outcome(item), Icons.CHECK_CIRCLE, "success");
                case SKIPPED, MISSING -> Fx.chip(this.chip, outcome(item), Icons.REMOVE_CIRCLE, "neutral");
                case FAILED -> Fx.chip(this.chip, outcome(item), Icons.ERROR, "danger");
            }
            setGraphic(this.chip);
        }
    }

    TextField directoryField() {
        return this.directory;
    }

    TableView<ImportReport.Item> reportTable() {
        return this.report;
    }

    Button checkButton() {
        return this.check;
    }

    Button importButton() {
        return this.take;
    }

    Label summaryLabel() {
        return this.summary;
    }

    Stage stage() {
        return this.stage;
    }
}
