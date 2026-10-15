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

import atlantafx.base.theme.Styles;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import javafx.beans.binding.Bindings;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TableView;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import org.kordamp.ikonli.Ikon;
import org.kordamp.ikonli.material2.Material2AL;
import org.kordamp.ikonli.material2.Material2MZ;
import org.njmule.core.api.Ed2kLink;
import org.njmule.core.api.SharedDirectory;
import org.njmule.core.api.SharedFileInfo;
import org.njmule.core.api.UploadPriority;
import org.njmule.gui.dialogs.CommentDialog;
import org.njmule.gui.dialogs.SharedFoldersDialog;
import org.njmule.gui.infra.Folders;
import org.njmule.gui.model.LiveList;
import org.njmule.gui.ui.ClipboardWatch;
import org.njmule.gui.ui.Fx;
import org.njmule.gui.ui.Gui;
import org.njmule.gui.ui.Screen;
import org.njmule.gui.ui.TableLayout;

/** The files we share, and sharing one more directory. */
public final class SharedScreen implements Screen {

    private final Gui gui;
    private Node view;
    private TableView<LiveList.Row<SharedFileInfo>> table;

    public SharedScreen(Gui gui) {
        this.gui = gui;
    }

    @Override
    public String id() {
        return "shared";
    }

    @Override
    public String title() {
        return this.gui.t("IDS_EM_FILES");
    }

    @Override
    public Ikon icon() {
        return Material2AL.FOLDER_SHARED;
    }

    @Override
    public Node view() {
        if (this.view == null) {
            this.view = this.build();
        }
        return this.view;
    }

    private Node build() {
        this.table = Fx.table(this.gui.model().sharedFiles(), this.gui.t("nj.shared.none"));
        this.table
                .getColumns()
                .addAll(List.of(
                        Fx.ownDirection(Fx.column(this.gui.t("IDS_DL_FILENAME"), 340, SharedFileInfo::name)),
                        Fx.leftToRight(Fx.column(
                                this.gui.t("IDS_DL_SIZE"), 90, SharedFileInfo::size, this.gui.formats()::size)),
                        Fx.column(this.gui.t("IDS_PRIORITY"), 100, SharedFileInfo::priority, this::priorityName),
                        Fx.leftToRight(Fx.column(
                                this.gui.t("nj.shared.folder"),
                                360,
                                (SharedFileInfo f) -> String.valueOf(f.path().getParent())))));
        TableLayout.remember(this.table, "shared", this.gui.settings());
        MenuItem copy = new MenuItem(this.gui.t("nj.copyLink"));
        copy.setOnAction(e -> this.copyLinks());
        MenuItem html = new MenuItem(this.gui.t("IDS_DL_LINK2"));
        html.setOnAction(e -> this.copyHtml());
        Menu priority = new Menu(this.gui.t("IDS_PRIORITY"));
        priority.getItems().setAll(this.priorityItems());
        MenuItem rename = Fx.menuItem(this.gui.t("IDS_RENAME"), Material2AL.EDIT, this::renameSelected);
        MenuItem comment = Fx.menuItem(this.gui.t("IDS_CMT_ADD"), Material2MZ.RATE_REVIEW, this::commentSelected);
        MenuItem folder = Fx.menuItem(this.gui.t("IDS_OPENFOLDER"), Material2AL.FOLDER_OPEN, this::openFolder);
        ContextMenu menu = new ContextMenu(folder, rename, comment, new SeparatorMenuItem(), priority, copy, html);
        menu.setOnShowing(e -> {
            int selected = Fx.selected(this.table).size();
            rename.setDisable(selected != 1);
            comment.setDisable(selected != 1);
            folder.setDisable(selected != 1);
        });
        this.table.setContextMenu(menu);
        MenuButton priorities = new MenuButton(null, Fx.icon(Material2AL.LOW_PRIORITY));
        priorities.getItems().setAll(this.priorityItems());
        priorities.setTooltip(new Tooltip(this.gui.t("IDS_PRIORITY")));
        priorities.setAccessibleText(this.gui.t("IDS_PRIORITY"));
        Label count = new Label();
        count.textProperty()
                .bind(Bindings.createStringBinding(
                        () -> this.gui.f(
                                "IDS_SHAREDFILESCOUNT",
                                this.gui.model().sharedFiles().size()),
                        this.gui.model().sharedFiles().rows()));
        var tools = Fx.toolRow(
                Fx.primary(Fx.button(this.gui.t("IDS_SHAREDIR"), Material2AL.FOLDER_OPEN, this::chooseDirectory)),
                Fx.button(this.gui.t("IDS_SHAREDFOLDERS"), Material2AL.FOLDER_SHARED, this::showFolders),
                Fx.button(this.gui.t("nj.copyLink"), Material2AL.CONTENT_COPY, this::copyLinks),
                priorities,
                Fx.spacer(),
                count);
        return Fx.screen(this.title(), tools, this.table);
    }

    private void chooseDirectory() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle(this.gui.t("IDS_SHAREDIR"));
        var dir = chooser.showDialog(this.view.getScene().getWindow());
        if (dir != null) {
            this.confirmShare(dir.toPath());
        }
    }

    // With its subfolders unless unticked; a folder where personal or system files may be gets a warning first.
    void confirmShare(Path directory) {
        CheckBox subfolders = new CheckBox(this.gui.t("IDS_SHAREDIRSUB"));
        subfolders.setSelected(true);
        VBox content = new VBox(8, Fx.leftToRight(new Label(directory.toString())), subfolders);
        boolean sensitive = SharedDirectory.isSensitive(directory);
        if (sensitive) {
            Label warning = new Label(this.gui.f("nj.shared.sensitive", directory));
            warning.setWrapText(true);
            warning.getStyleClass().add(Styles.DANGER);
            content.getChildren().add(warning);
        }
        Alert ask =
                this.gui.orient(new Alert(sensitive ? Alert.AlertType.WARNING : Alert.AlertType.CONFIRMATION, null));
        ButtonType go = Fx.answers(
                ask,
                this.gui.t(sensitive ? "nj.shared.shareAnyway" : "nj.shared.share"),
                this.gui.t("IDS_CANCEL"),
                sensitive);
        ask.setHeaderText(this.gui.t("IDS_SHAREDIR"));
        ask.getDialogPane().setContent(content);
        ask.initOwner(this.view.getScene().getWindow());
        ask.showAndWait().filter(go::equals).ifPresent(b -> this.share(directory, subfolders.isSelected()));
    }

    void share(Path directory, boolean subdirectories) {
        this.gui.model().shareDirectory(directory, subdirectories);
    }

    private void showFolders() {
        new SharedFoldersDialog(this.gui, this.view.getScene().getWindow()).show();
    }

    // Each level, for every selected file.
    private List<MenuItem> priorityItems() {
        List<MenuItem> items = new ArrayList<>();
        for (UploadPriority priority : UploadPriority.values()) {
            MenuItem item = new MenuItem(this.priorityName(priority));
            item.setOnAction(e -> this.prioritize(priority));
            items.add(item);
        }
        return items;
    }

    void prioritize(UploadPriority priority) {
        Fx.selected(this.table).forEach(f -> this.gui.model().setUploadPriority(f.hash(), priority));
    }

    private String priorityName(UploadPriority priority) {
        return switch (priority) {
            case VERY_LOW -> this.gui.t("IDS_PRIOVERYLOW");
            case LOW -> this.gui.t("IDS_PRIOLOW");
            case NORMAL -> this.gui.t("IDS_PRIONORMAL");
            case HIGH -> this.gui.t("IDS_PRIOHIGH");
            case RELEASE -> this.gui.t("IDS_PRIORELEASE");
            case AUTO -> this.gui.t("IDS_PRIOAUTO");
        };
    }

    // One file at a time, in its own directory.
    void renameSelected() {
        List<SharedFileInfo> selected = Fx.selected(this.table);
        if (selected.size() != 1) {
            return;
        }
        SharedFileInfo file = selected.getFirst();
        TextInputDialog ask = this.gui.orient(new TextInputDialog(file.name()));
        ask.setTitle(this.gui.t("IDS_RENAME"));
        ask.setHeaderText(this.gui.i18n().embed(file.name()));
        Fx.leftToRight(ask.getEditor());
        ask.initOwner(this.table.getScene().getWindow());
        ask.showAndWait()
                .map(String::strip)
                .filter(name -> !name.isEmpty() && !name.equals(file.name()))
                .ifPresent(name -> this.gui.model().renameShared(file.hash(), name));
    }

    void commentSelected() {
        List<SharedFileInfo> selected = Fx.selected(this.table);
        if (selected.size() == 1) {
            SharedFileInfo f = selected.getFirst();
            new CommentDialog(this.gui, this.table.getScene().getWindow(), f.hash(), f.name(), false).show();
        }
    }

    void openFolder() {
        List<SharedFileInfo> selected = Fx.selected(this.table);
        if (selected.size() == 1) {
            try {
                Folders.reveal(selected.getFirst().path());
            } catch (IOException e) {
                this.gui.inform().accept(e.getMessage());
            }
        }
    }

    void copyLinks() {
        this.copy(Ed2kLink.FileLink::toUri);
    }

    void copyHtml() {
        this.copy(Ed2kLink.FileLink::toHtml);
    }

    private void copy(Function<Ed2kLink.FileLink, String> form) {
        List<SharedFileInfo> selected = Fx.selected(this.table);
        if (!selected.isEmpty()) {
            ClipboardWatch.copy(String.join(
                    "\n", selected.stream().map(f -> form.apply(f.link())).toList()));
        }
    }

    TableView<LiveList.Row<SharedFileInfo>> table() {
        return this.table;
    }
}
