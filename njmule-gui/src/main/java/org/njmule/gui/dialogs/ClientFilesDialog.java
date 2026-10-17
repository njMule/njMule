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

import java.util.List;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableView;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.njmule.core.api.Hash16;
import org.njmule.core.api.SearchResult;
import org.njmule.core.api.ServerAddress;
import org.njmule.gui.model.LiveList;
import org.njmule.gui.ui.Fx;
import org.njmule.gui.ui.Gui;
import org.njmule.gui.ui.Icons;

/** The files another client shares, as it lists them when asked; the chosen ones can be downloaded. */
public final class ClientFilesDialog {

    private final Gui gui;
    private final Stage stage = new Stage();
    private final LiveList<Hash16, SearchResult> files = new LiveList<>(SearchResult::hash);
    private final TableView<LiveList.Row<SearchResult>> table;
    private final Label state = new Label();

    public ClientFilesDialog(Gui gui, Window owner, String client) {
        this.gui = gui;
        this.stage.initOwner(owner);
        this.stage.initModality(Modality.NONE);
        this.stage.setTitle(gui.t("IDS_VIEWFILES") + ": " + client);
        var formats = gui.formats();
        this.table = Fx.table(this.files, gui.t("nj.browse.asking"));
        this.table
                .getColumns()
                .addAll(List.of(
                        Fx.ownDirection(Fx.column(gui.t("IDS_DL_FILENAME"), 380, SearchResult::name)),
                        Fx.leftToRight(Fx.column(gui.t("IDS_DL_SIZE"), 100, SearchResult::size, formats::size)),
                        Fx.leftToRight(Fx.column(
                                gui.t("IDS_TYPE"),
                                90,
                                (SearchResult r) -> r.type().orElse("")))));
        Button download = Fx.primary(Fx.button(gui.t("IDS_DOWNLOAD"), Icons.DOWNLOAD, this::download));
        download.disableProperty()
                .bind(this.table.getSelectionModel().selectedItemProperty().isNull());
        VBox root = new VBox(
                10,
                this.state,
                this.table,
                Fx.toolRow(download, Fx.spacer(), Fx.button(gui.t("IDS_FD_CLOSE"), Icons.CLOSE, this.stage::close)));
        VBox.setVgrow(this.table, Priority.ALWAYS);
        root.setPadding(new Insets(14));
        Scene scene = new Scene(root, 680, 420);
        gui.attach(scene);
        this.stage.setScene(scene);
    }

    /** Opens the window and asks the client listening at {@code address} for its files. */
    public void show(ServerAddress address) {
        this.state.setText(this.gui.t("nj.browse.asking"));
        this.stage.show();
        this.gui
                .model()
                .browse(
                        address,
                        found -> {
                            this.files.update(found);
                            this.state.setText(
                                    found.isEmpty()
                                            ? this.gui.t("nj.browse.none")
                                            : this.gui.f("nj.browse.count", found.size()));
                        },
                        why -> this.state.setText(this.gui.f("nj.browse.failed", why)));
    }

    void download() {
        Fx.selected(this.table).forEach(r -> this.gui.model().addDownload(r.link()));
    }

    Stage stage() {
        return this.stage;
    }
}
