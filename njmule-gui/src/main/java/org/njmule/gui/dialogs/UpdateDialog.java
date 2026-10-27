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
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import javafx.beans.value.ChangeListener;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TextArea;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.njmule.core.api.InstallKind;
import org.njmule.core.api.NewVersion;
import org.njmule.core.api.UpdateStatus;
import org.njmule.core.api.UpdateStatus.Install;
import org.njmule.core.api.UpdateStatus.Installer;
import org.njmule.gui.infra.Folders;
import org.njmule.gui.infra.WebPages;
import org.njmule.gui.ui.DialogFrame;
import org.njmule.gui.ui.Fx;
import org.njmule.gui.ui.Gui;
import org.njmule.gui.ui.Icons;

/**
 * What is known of new versions of njMule: the version found with what changed, its download and its installation, a
 * check running, this version being the newest, or why something failed. It follows the status while it shows; while a
 * download runs only its progress changes.
 */
public final class UpdateDialog {

    private static final double WIDTH = 560;
    private static final String DOCKER_IMAGE = "ghcr.io/njmule/njmule";

    private final Gui gui;
    private final Stage stage = new Stage();
    private final VBox body = new VBox(10);
    private final VBox root;
    private final ProgressBar progress = new ProgressBar(0);
    private final Label received = new Label();
    private final ChangeListener<UpdateStatus> follow = (o, was, now) -> show(now);
    // What the body shows now: its parts are built again only when this changes.
    private String shown = "";
    private TextArea notes;
    private String notesOf = "";

    public UpdateDialog(Gui gui, Window owner) {
        this.gui = gui;
        this.stage.initOwner(owner);
        this.stage.initModality(Modality.NONE);
        this.stage.setTitle(gui.t("nj.update.title"));
        this.body.setFillWidth(true);
        this.progress.setMaxWidth(Double.MAX_VALUE);
        this.progress.setId("update-progress");
        this.progress.setAccessibleText(gui.t("nj.update.downloadInstall"));
        this.received.getStyleClass().add(Styles.TEXT_MUTED);
        this.root = DialogFrame.frame(
                DialogFrame.header(gui, gui.t("nj.update.title"), this.stage::close), this.body, new Region());
        this.body.setPrefWidth(WIDTH);
        Scene scene = new Scene(this.root);
        gui.attach(scene);
        this.stage.setScene(scene);
        gui.model().updateProperty().addListener(this.follow);
        this.stage.setOnHidden(e -> gui.model().updateProperty().removeListener(this.follow));
        show(gui.model().updateProperty().get());
    }

    /** Opens the dialog and looks for a new version at once. */
    public static UpdateDialog checkNow(Gui gui, Window owner) {
        UpdateDialog dialog = new UpdateDialog(gui, owner);
        dialog.show();
        gui.model().checkForUpdate();
        return dialog;
    }

    public void show() {
        this.stage.show();
        this.stage.toFront();
    }

    Stage stage() {
        return this.stage;
    }

    private void show(UpdateStatus status) {
        status.download().ifPresent(this::showProgress);
        String key = key(status);
        if (key.equals(this.shown)) {
            return;
        }
        this.shown = key;
        List<Node> parts = new ArrayList<>();
        List<Button> buttons = new ArrayList<>();
        Optional<NewVersion> available = status.available();
        if (status.install() == Install.RESTART) {
            parts.add(title(this.gui.t("nj.update.installing")));
        } else if (available.isPresent()) {
            describe(status, available.get(), parts, buttons);
        }
        if (status.checking()) {
            ProgressIndicator spinning = new ProgressIndicator();
            spinning.setPrefSize(18, 18);
            HBox checking = new HBox(8, spinning, new Label(this.gui.t("nj.update.checking")));
            parts.add(checking);
        } else if (status.failure().isPresent()) {
            parts.add(wrapped(this.gui.f("nj.update.failed", status.failure().get()), Styles.DANGER));
        } else if (available.isEmpty() && status.lastCheck().isPresent()) {
            Label newest = new Label(this.gui.f("nj.update.newest", status.current()));
            newest.getStyleClass().add(Styles.TITLE_4);
            parts.add(newest);
        }
        if (available.isEmpty() || status.failure().isPresent()) {
            status.lastCheck()
                    .ifPresent(t -> parts.add(wrapped(
                            this.gui.f("nj.update.lastCheck", this.gui.formats().dateTime(t)), Styles.TEXT_MUTED)));
        }
        if (!status.checking() && available.isEmpty()) {
            Button check = Fx.button(
                    this.gui.t("IDS_CHECK4UPDATE"),
                    Icons.REFRESH,
                    () -> this.gui.model().checkForUpdate());
            check.setId("update-check");
            buttons.add(DialogFrame.role(check, ButtonBar.ButtonData.OK_DONE));
        }
        Label note = wrapped(this.gui.t("nj.update.note"), "dialog-hint");
        this.body.getChildren().setAll(parts);
        Button close = Fx.button(this.gui.t("IDS_FD_CLOSE"), Icons.CLOSE, this.stage::close);
        close.setCancelButton(true);
        buttons.add(DialogFrame.role(close, ButtonBar.ButtonData.CANCEL_CLOSE));
        // The band says what the check sends, above the buttons that the state of the update offers: beside them
        // the words would be left a column wide. Without a width of its own the note leaves it to the body and
        // the buttons, so the window is as wide as they need.
        note.setPrefWidth(0);
        this.root.getChildren().set(2, DialogFrame.footerBelow(note, buttons.toArray(Button[]::new)));
        this.stage.sizeToScene();
    }

    // The version found: what changed, then where its download and installation stand, with what can be done now.
    private void describe(UpdateStatus status, NewVersion v, List<Node> parts, List<Button> buttons) {
        boolean ready = status.ready().isPresent();
        parts.add(title(this.gui.f(ready ? "nj.update.ready" : "nj.update.available", v.version())));
        if (v.critical()) {
            Label critical = wrapped(this.gui.t("nj.update.critical"), Styles.WARNING);
            critical.setGraphic(Fx.icon(Icons.WARNING));
            parts.add(critical);
        }
        parts.add(wrapped(
                this.gui.f("nj.update.published", this.gui.formats().dateTime(v.published())), Styles.TEXT_MUTED));
        if (!v.notes().isBlank()) {
            parts.add(notes(v));
        }
        if (status.download().isPresent()) {
            parts.add(new VBox(4, this.progress, this.received));
        } else if (ready && status.canInstall()) {
            parts.add(wrapped(
                    this.gui.t(status.install() == Install.AT_EXIT ? "nj.update.willInstall" : "nj.update.how.self"),
                    null));
            Button restart = Fx.primary(Fx.button(
                    this.gui.t("nj.update.restart"),
                    Icons.REFRESH,
                    () -> this.gui.model().installUpdate(true)));
            restart.setId("update-restart");
            buttons.add(DialogFrame.role(restart, ButtonBar.ButtonData.OK_DONE));
            if (status.install() != Install.AT_EXIT) {
                Button atExit = Fx.button(
                        this.gui.t("nj.update.atExit"),
                        Icons.CLOCK,
                        () -> this.gui.model().installUpdate(false));
                atExit.setId("update-at-exit");
                buttons.add(DialogFrame.role(atExit, ButtonBar.ButtonData.OTHER));
            }
        } else if (ready) {
            Path file = status.ready().get();
            parts.add(wrapped(this.gui.f("nj.update.how.file", file), null));
            parts.add(wrapped(howToInstall(status.installation(), v), null));
            Button folder = Fx.button(this.gui.t("IDS_OPENFOLDER"), Icons.FOLDER_OPEN, () -> reveal(file));
            folder.setId("update-folder");
            buttons.add(DialogFrame.role(folder, ButtonBar.ButtonData.OK_DONE));
        } else {
            parts.add(wrapped(
                    status.installer() == Installer.NONE
                            ? howToInstall(status.installation(), v)
                            : this.gui.t(
                                    status.installer() == Installer.ELEVATED
                                            ? "nj.update.how.elevated"
                                            : "nj.update.how.self"),
                    null));
            // The Package Center installs a package from the browser's computer, not from the NAS.
            if (v.download().isPresent() && status.installation() != InstallKind.SYNOLOGY) {
                Button download = Fx.primary(Fx.button(
                        this.gui.t(
                                status.installer() == Installer.NONE
                                        ? "nj.update.download"
                                        : "nj.update.downloadInstall"),
                        Icons.DOWNLOAD,
                        () -> this.gui.model().downloadUpdate()));
                download.setId("update-download");
                buttons.add(DialogFrame.role(download, ButtonBar.ButtonData.OK_DONE));
            }
        }
        buttons.add(DialogFrame.role(
                Fx.button(this.gui.t("nj.update.changes"), Icons.OPEN_EXTERNAL, () -> open(v.page())),
                ButtonBar.ButtonData.OTHER));
        if (!v.critical() && !status.skipped().equals(Optional.of(v.version()))) {
            Button skip = Fx.button(this.gui.t("nj.update.skip"), Icons.BLOCK, () -> {
                this.gui.model().skipUpdate(v.version());
                this.stage.close();
            });
            skip.setId("update-skip");
            buttons.add(DialogFrame.role(skip, ButtonBar.ButtonData.LEFT));
        }
    }

    // The parts the body is built of; the progress of a download is not one of them.
    private static String key(UpdateStatus status) {
        return String.join(
                "|",
                status.available().map(NewVersion::version).orElse(""),
                status.phase().name(),
                Boolean.toString(status.checking()),
                status.failure().orElse(""),
                status.install().name(),
                status.skipped().orElse(""),
                status.lastCheck().map(Object::toString).orElse(""));
    }

    private void showProgress(UpdateStatus.Progress p) {
        this.progress.setProgress(p.fraction());
        this.received.setText(this.gui.f(
                "nj.update.downloading",
                this.gui.formats().size(p.received()),
                this.gui.formats().size(p.size())));
    }

    // Kept while the version stays: its scroll position with it.
    private TextArea notes(NewVersion v) {
        if (this.notes == null || !Objects.equals(this.notesOf, v.version())) {
            this.notes = new TextArea(plain(v.notes()));
            this.notes.setEditable(false);
            this.notes.setWrapText(true);
            this.notes.setPrefRowCount(10);
            this.notes.setId("update-notes");
            Fx.leftToRight(this.notes);
            this.notesOf = v.version();
        }
        return this.notes;
    }

    // The words fit how this copy was installed: a package manager's command stays as typed, in every language.
    private String howToInstall(InstallKind installation, NewVersion v) {
        String file = v.download().map(d -> fileName(d.uri())).orElse("");
        return switch (installation) {
            case MSI -> this.gui.t("nj.update.how.installer");
            case DMG -> this.gui.t("nj.update.how.dmg");
            case WINDOWS_ZIP, TAR, STATIC_TAR -> this.gui.t("nj.update.how.archive");
            case DEB -> this.gui.f("nj.update.how.package", "sudo apt install ./" + file);
            case RPM -> this.gui.f("nj.update.how.package", "sudo dnf install ./" + file);
            case DOCKER -> this.gui.f("nj.update.how.docker", "docker pull " + DOCKER_IMAGE);
            case SYNOLOGY -> this.gui.t("nj.update.how.synology");
            default -> this.gui.t("nj.update.how.page");
        };
    }

    private void open(URI page) {
        try {
            WebPages.open(page);
        } catch (IOException | IllegalArgumentException e) {
            this.gui.warn().accept(String.valueOf(e.getMessage()));
        }
    }

    private void reveal(Path file) {
        if (!this.gui.opensFoldersHere()) {
            return;
        }
        try {
            Folders.reveal(file);
        } catch (IOException e) {
            this.gui.warn().accept(String.valueOf(e.getMessage()));
        }
    }

    // The notes are the Markdown of the changelog: its headings and list marks read better as plain text.
    static String plain(String markdown) {
        return markdown.lines()
                .map(line -> line.startsWith("#")
                        ? line.replaceFirst("^#+\\s*", "")
                        : line.replaceFirst("^(\\s*)[-*] ", "$1• "))
                .collect(Collectors.joining("\n"));
    }

    private static Label title(String text) {
        Label title = wrapped(text, Styles.TITLE_3);
        title.setId("update-title");
        return title;
    }

    private static Label wrapped(String text, String style) {
        Label label = new Label(text);
        Fx.wrap(label);
        label.setMaxWidth(Double.MAX_VALUE);
        if (style != null) {
            label.getStyleClass().add(style);
        }
        return label;
    }

    private static String fileName(URI uri) {
        String path = uri.getPath() == null ? "" : uri.getPath();
        return path.substring(path.lastIndexOf('/') + 1);
    }
}
