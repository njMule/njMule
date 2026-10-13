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
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import javafx.collections.ListChangeListener;
import javafx.geometry.Orientation;
import javafx.scene.Node;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.stage.FileChooser;
import org.kordamp.ikonli.Ikon;
import org.kordamp.ikonli.material2.Material2AL;
import org.njmule.core.api.LogLine;
import org.njmule.core.api.ServerAddress;
import org.njmule.core.api.ServerInfo;
import org.njmule.gui.model.LiveList;
import org.njmule.gui.ui.Fx;
import org.njmule.gui.ui.Gui;
import org.njmule.gui.ui.Screen;

/** The known servers, connecting to one, and the log below. */
public final class ServersScreen implements Screen {

    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT).withZone(ZoneId.systemDefault());

    private final Gui gui;
    private Node view;
    private TableView<LiveList.Row<ServerInfo>> table;
    private final TextField address = new TextField();

    public ServersScreen(Gui gui) {
        this.gui = gui;
    }

    @Override
    public String id() {
        return "servers";
    }

    @Override
    public String title() {
        return this.gui.t("IDS_EM_SERVER");
    }

    @Override
    public Ikon icon() {
        return Material2AL.DNS;
    }

    @Override
    public Node view() {
        if (this.view == null) {
            this.view = this.build();
        }
        return this.view;
    }

    private Node build() {
        this.table = Fx.table(this.gui.model().servers(), this.gui.t("nj.servers.none"));
        this.table
                .getColumns()
                .addAll(List.of(
                        Fx.column(this.gui.t("IDS_SL_SERVERNAME"), 220, ServerInfo::name),
                        Fx.column(this.gui.t("IDS_IP"), 150, ServerInfo::address),
                        Fx.column(this.gui.t("IDS_DESCRIPTION"), 220, ServerInfo::description),
                        Fx.column(this.gui.t("nj.servers.users"), 90, ServerInfo::users, this.gui.formats()::count),
                        Fx.column(
                                this.gui.t("nj.servers.maxUsers"), 90, ServerInfo::maxUsers, this.gui.formats()::count),
                        Fx.column(this.gui.t("IDS_FILES"), 100, ServerInfo::files, this.gui.formats()::count),
                        Fx.column(this.gui.t("IDS_PRIORITY"), 80, ServerInfo::priority, this::priority),
                        Fx.column(this.gui.t("nj.servers.failed"), 70, ServerInfo::failed)));
        this.table.setRowFactory(t -> {
            TableRow<LiveList.Row<ServerInfo>> row = new TableRow<>();
            row.setOnMouseClicked(e -> {
                if (e.getClickCount() == 2 && !row.isEmpty()) {
                    this.connectTo(row.getItem().get());
                }
            });
            return row;
        });
        MenuItem connect = new MenuItem(this.gui.t("IDS_MAIN_BTN_CONNECT"));
        connect.setOnAction(e -> this.connectSelected());
        MenuItem remove = new MenuItem(this.gui.t("IDS_REMOVE"));
        remove.setOnAction(e -> this.removeSelected());
        this.table.setContextMenu(new ContextMenu(connect, remove));

        this.address.setPromptText(this.gui.t("nj.servers.addressPrompt"));
        this.address.setPrefColumnCount(18);
        this.address.setOnAction(e -> this.connectToAddress());
        var tools = Fx.toolRow(
                Fx.button(this.gui.t("IDS_MAIN_BTN_CONNECT"), Material2AL.LINK, this::connectSelected),
                Fx.button(this.gui.t("IDS_REMOVE"), Material2AL.DELETE, this::removeSelected),
                Fx.spacer(),
                this.address,
                Fx.button(this.gui.t("nj.servers.addConnect"), Material2AL.ADD, this::connectToAddress),
                Fx.button(this.gui.t("nj.servers.addMet"), Material2AL.FOLDER_OPEN, this::chooseServerMet));

        ListView<LogLine> log = new ListView<>(this.gui.model().log());
        log.setCellFactory(l -> new LogCell());
        log.getItems().addListener((ListChangeListener<LogLine>)
                c -> log.scrollTo(log.getItems().size() - 1));
        SplitPane split = new SplitPane(this.table, log);
        split.setOrientation(Orientation.VERTICAL);
        split.setDividerPositions(0.62);
        return Fx.screen(this.title(), tools, split);
    }

    private String priority(ServerInfo.Priority priority) {
        return switch (priority) {
            case HIGH -> this.gui.t("IDS_PRIOHIGH");
            case NORMAL -> this.gui.t("IDS_PRIONORMAL");
            case LOW -> this.gui.t("IDS_PRIOLOW");
        };
    }

    /** Connects to the selected server, or to any known one when none is selected. */
    void connectSelected() {
        List<ServerInfo> selected = Fx.selected(this.table);
        if (selected.isEmpty()) {
            this.gui.model().connect(Optional.empty());
        } else {
            this.connectTo(selected.getFirst());
        }
    }

    void removeSelected() {
        for (ServerInfo server : Fx.selected(this.table)) {
            this.gui.model().removeServer(ServerAddress.parse(server.address()));
        }
    }

    void connectToAddress() {
        String text = this.address.getText().strip();
        try {
            this.gui.model().connect(Optional.of(ServerAddress.parse(text)));
            this.address.clear();
        } catch (IllegalArgumentException e) {
            this.gui.inform().accept(this.gui.f("nj.servers.badAddress", text));
        }
    }

    private void connectTo(ServerInfo server) {
        this.gui.model().connect(Optional.of(ServerAddress.parse(server.address())));
    }

    private void chooseServerMet() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(this.gui.t("nj.servers.addMet"));
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("server.met", "*.met"));
        var file = chooser.showOpenDialog(this.view.getScene().getWindow());
        if (file != null) {
            this.addServerMet(file.toPath());
        }
    }

    void addServerMet(Path file) {
        this.gui.model().addServers(file);
    }

    TableView<LiveList.Row<ServerInfo>> table() {
        return this.table;
    }

    TextField addressField() {
        return this.address;
    }

    /** One log line: time, text, and the level as style. */
    static final class LogCell extends ListCell<LogLine> {
        @Override
        protected void updateItem(LogLine line, boolean empty) {
            super.updateItem(line, empty);
            this.getStyleClass().removeAll("log-line", "debug", "info", "success", "warning", "error");
            if (empty || line == null) {
                this.setText(null);
                return;
            }
            this.setText(TIME.format(line.time()) + "  " + line.message());
            this.getStyleClass().addAll("log-line", line.level().name().toLowerCase(Locale.ROOT));
        }
    }
}
