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

import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.kordamp.ikonli.material2.Material2AL;
import org.kordamp.ikonli.material2.Material2MZ;
import org.njmule.core.api.SharedDirectory;
import org.njmule.gui.ui.Fx;
import org.njmule.gui.ui.Gui;

/** The folders shared besides the incoming one, to share another or stop sharing some of them. */
public final class SharedFoldersDialog {

    private final Gui gui;
    private final Stage stage = new Stage();
    private final ListView<SharedDirectory> list = new ListView<>();

    public SharedFoldersDialog(Gui gui, Window owner) {
        this(gui, owner, null);
    }

    /** With {@code share}, which asks for a folder and shares it, behind a button of its own. */
    public SharedFoldersDialog(Gui gui, Window owner, Runnable share) {
        this.gui = gui;
        this.stage.initOwner(owner);
        this.stage.initModality(Modality.WINDOW_MODAL);
        this.stage.setTitle(gui.t("IDS_SHAREDFOLDERS"));
        this.list.setPlaceholder(new Label(gui.t("nj.shared.noFolders")));
        this.list.setCellFactory(l -> new ListCell<>() {
            @Override
            protected void updateItem(SharedDirectory item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                } else {
                    String path = item.path().toString();
                    setText(
                            item.subdirectories()
                                    ? gui.f("nj.shared.withSubfolders", path)
                                    : gui.i18n().embed(path));
                }
            }
        });
        Button unshare = Fx.button(gui.t("IDS_UNSHAREDIR"), Material2MZ.REMOVE_CIRCLE_OUTLINE, this::unshareSelected);
        unshare.disableProperty()
                .bind(this.list.getSelectionModel().selectedItemProperty().isNull());
        var tools = Fx.toolRow(
                unshare, Fx.spacer(), Fx.button(gui.t("IDS_FD_CLOSE"), Material2AL.CLOSE, this.stage::close));
        if (share != null) {
            tools.getChildren().addFirst(Fx.button(gui.t("IDS_SHAREDIR"), Material2AL.FOLDER_OPEN, share));
            // The folder chooser and the question take the focus; back here, the list may have grown.
            this.stage.focusedProperty().addListener((o, was, now) -> {
                if (now) {
                    load();
                }
            });
        }
        VBox root = new VBox(10, this.list, tools);
        VBox.setVgrow(this.list, Priority.ALWAYS);
        root.setPadding(new Insets(14));
        Scene scene = new Scene(root, 620, 360);
        gui.attach(scene);
        this.stage.setScene(scene);
        load();
    }

    public void show() {
        this.stage.show();
    }

    void load() {
        this.gui.model().sharedDirectories(dirs -> this.list.getItems().setAll(dirs));
    }

    void unshareSelected() {
        SharedDirectory selected = this.list.getSelectionModel().getSelectedItem();
        if (selected != null) {
            this.gui.model().unshareDirectory(selected.path(), this::load);
        }
    }

    ListView<SharedDirectory> list() {
        return this.list;
    }

    Stage stage() {
        return this.stage;
    }
}
