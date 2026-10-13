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

import java.nio.file.Path;
import java.util.List;
import javafx.beans.binding.Bindings;
import javafx.scene.Node;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TableView;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.stage.DirectoryChooser;
import org.kordamp.ikonli.Ikon;
import org.kordamp.ikonli.material2.Material2AL;
import org.njmule.core.api.SharedFileInfo;
import org.njmule.gui.model.LiveList;
import org.njmule.gui.ui.Fx;
import org.njmule.gui.ui.Gui;
import org.njmule.gui.ui.Screen;

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
                .addAll(
                        List.of(
                                Fx.column(this.gui.t("IDS_DL_FILENAME"), 340, SharedFileInfo::name),
                                Fx.column(
                                        this.gui.t("IDS_DL_SIZE"),
                                        90,
                                        SharedFileInfo::size,
                                        this.gui.formats()::size),
                                Fx.column(
                                        this.gui.t("nj.shared.folder"),
                                        360,
                                        f -> String.valueOf(f.path().getParent()))));
        MenuItem copy = new MenuItem(this.gui.t("nj.copyLink"));
        copy.setOnAction(e -> this.copyLinks());
        this.table.setContextMenu(new ContextMenu(copy));
        Label count = new Label();
        count.textProperty()
                .bind(
                        Bindings.createStringBinding(
                                () ->
                                        this.gui.f(
                                                "IDS_SHAREDFILESCOUNT",
                                                this.gui.model().sharedFiles().size()),
                                this.gui.model().sharedFiles().rows()));
        var tools =
                Fx.toolRow(
                        Fx.button(
                                this.gui.t("IDS_SHAREDIR"),
                                Material2AL.FOLDER_OPEN,
                                this::chooseDirectory),
                        Fx.button(
                                this.gui.t("nj.copyLink"),
                                Material2AL.CONTENT_COPY,
                                this::copyLinks),
                        Fx.spacer(),
                        count);
        return Fx.screen(this.title(), tools, this.table);
    }

    private void chooseDirectory() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle(this.gui.t("IDS_SHAREDIR"));
        var dir = chooser.showDialog(this.view.getScene().getWindow());
        if (dir != null) {
            this.share(dir.toPath());
        }
    }

    void share(Path directory) {
        this.gui.model().shareDirectory(directory);
    }

    void copyLinks() {
        List<SharedFileInfo> selected = Fx.selected(this.table);
        if (!selected.isEmpty()) {
            ClipboardContent content = new ClipboardContent();
            content.putString(
                    String.join("\n", selected.stream().map(f -> f.link().toUri()).toList()));
            Clipboard.getSystemClipboard().setContent(content);
        }
    }

    TableView<LiveList.Row<SharedFileInfo>> table() {
        return this.table;
    }
}
