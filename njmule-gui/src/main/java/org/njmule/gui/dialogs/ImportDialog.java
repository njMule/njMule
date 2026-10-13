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

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.kordamp.ikonli.material2.Material2AL;
import org.kordamp.ikonli.material2.Material2MZ;
import org.njmule.core.api.ImportOptions;
import org.njmule.core.api.ImportReport;
import org.njmule.gui.ui.Fx;
import org.njmule.gui.ui.Gui;

/**
 * Taking an eMule profile over: a check first shows what would be taken, then the import itself.
 * The eMule directory is only read.
 */
public final class ImportDialog {

    private final Gui gui;
    private final Stage stage = new Stage();
    private final TextField directory = new TextField();
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
        this.check = Fx.button(gui.t("nj.import.check"), Material2MZ.SEARCH, () -> this.run(true));
        this.take = Fx.button(gui.t("nj.import.run"), Material2AL.INPUT, () -> this.run(false));
        this.take.setDisable(true);

        TableColumn<ImportReport.Item, String> file = new TableColumn<>(gui.t("IDS_DL_FILENAME"));
        file.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().file()));
        file.setPrefWidth(170);
        TableColumn<ImportReport.Item, String> outcome =
                new TableColumn<>(gui.t("nj.import.outcome"));
        outcome.setCellValueFactory(
                c -> new ReadOnlyStringWrapper(this.outcome(c.getValue().outcome())));
        outcome.setPrefWidth(130);
        TableColumn<ImportReport.Item, String> detail =
                new TableColumn<>(gui.t("nj.import.detail"));
        detail.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().detail()));
        detail.setPrefWidth(420);
        this.report.getColumns().addAll(List.of(file, outcome, detail));
        this.report.setPlaceholder(new Label(gui.t("nj.import.empty")));
        this.report.getStyleClass().add("import-report");

        Label intro = new Label(gui.t("nj.import.intro"));
        intro.setWrapText(true);
        HBox dir =
                new HBox(
                        8,
                        new Label(gui.t("nj.import.directory")),
                        this.directory,
                        Fx.button(gui.t("IDS_PW_BROWSE"), Material2AL.FOLDER_OPEN, this::browse));
        HBox.setHgrow(this.directory, Priority.ALWAYS);
        HBox buttons =
                Fx.toolRow(
                        this.check,
                        this.take,
                        Fx.spacer(),
                        this.summary,
                        Fx.button(gui.t("IDS_FD_CLOSE"), Material2AL.CLOSE, this.stage::close));
        VBox root =
                new VBox(
                        10,
                        intro,
                        dir,
                        new HBox(16, this.identity, this.downloads),
                        this.report,
                        buttons);
        VBox.setVgrow(this.report, Priority.ALWAYS);
        root.setPadding(new Insets(14));
        Scene scene = new Scene(root, 820, 520);
        gui.themes().attach(scene);
        this.stage.setScene(scene);
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
            this.gui.inform().accept(e.getMessage());
            return;
        }
        if (!Files.isDirectory(dir)) {
            this.gui.inform().accept(this.gui.f("nj.import.notADirectory", dir));
            return;
        }
        this.check.setDisable(true);
        this.take.setDisable(true);
        this.summary.setText(this.gui.t("nj.import.working"));
        this.gui
                .model()
                .importFromEmule(
                        dir,
                        new ImportOptions(
                                dryRun, this.identity.isSelected(), this.downloads.isSelected()),
                        result -> {
                            this.report.getItems().setAll(result.items());
                            long failed =
                                    result.items().stream()
                                            .filter(i -> i.outcome() == ImportReport.Outcome.FAILED)
                                            .count();
                            this.summary.setText(
                                    this.gui.f(
                                            dryRun ? "nj.import.checked" : "nj.import.done",
                                            result.items().size(),
                                            failed));
                            this.check.setDisable(false);
                            this.take.setDisable(!dryRun);
                        });
    }

    private String outcome(ImportReport.Outcome outcome) {
        return this.gui.t("nj.import.outcome." + outcome.name().toLowerCase(Locale.ROOT));
    }

    TextField directoryField() {
        return this.directory;
    }

    TableView<ImportReport.Item> reportTable() {
        return this.report;
    }

    Stage stage() {
        return this.stage;
    }
}
