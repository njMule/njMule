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

import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import javafx.collections.ListChangeListener;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import org.kordamp.ikonli.Ikon;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.material2.Material2AL;
import org.kordamp.ikonli.material2.Material2MZ;
import org.njmule.core.api.LogLine;
import org.njmule.core.api.ServerAddress;
import org.njmule.core.api.ServerInfo;
import org.njmule.gui.infra.Formats;
import org.njmule.gui.model.EngineModel;
import org.njmule.gui.model.LiveList;
import org.njmule.gui.ui.Fx;
import org.njmule.gui.ui.Gui;
import org.njmule.gui.ui.Input;
import org.njmule.gui.ui.Inputs;
import org.njmule.gui.ui.Screen;
import org.njmule.gui.ui.UrlBox;

/** The known servers, connecting to one, and the log below. */
public final class ServersScreen implements Screen {

    private final Gui gui;
    private Node view;
    private TableView<LiveList.Row<ServerInfo>> table;
    private final TextField address = new TextField();
    private Input<ServerAddress> addressInput;
    private UrlBox list;
    private TabPane logTabs;
    private final Button connectButton = new Button();

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
        connect.setOnAction(e -> this.connectOrDisconnect());
        MenuItem remove = new MenuItem(this.gui.t("IDS_REMOVE"));
        remove.setOnAction(e -> this.removeSelected());
        this.table.setContextMenu(new ContextMenu(connect, remove));
        this.table.getContextMenu().setOnShowing(e -> connect.setText(this.connectText()));

        this.address.setPromptText(this.gui.t("nj.servers.addressPrompt"));
        this.address.setPrefColumnCount(18);
        this.address.setOnAction(e -> this.connectToAddress());
        this.addressInput = new Inputs(this.gui)
                .address(this.address, this.gui.t("nj.servers.addConnect"))
                .quietWhenEmpty();
        Button connectTo =
                Fx.primary(Fx.button(this.gui.t("nj.servers.addConnect"), Material2AL.ADD, this::connectToAddress));
        connectTo.disableProperty().bind(this.addressInput.validProperty().not());
        this.connectButton.setOnAction(e -> this.connectOrDisconnect());
        this.table.getSelectionModel().selectedItemProperty().addListener((o, was, now) -> this.showConnect());
        this.gui.model().connectionProperty().addListener((o, was, now) -> this.showConnect());
        this.gui.model().serverAddressProperty().addListener((o, was, now) -> this.showConnect());
        this.showConnect();
        var tools = Fx.toolRow(
                this.connectButton,
                Fx.button(this.gui.t("IDS_REMOVE"), Material2AL.DELETE, this::removeSelected),
                Fx.spacer(),
                this.address,
                connectTo,
                Fx.button(this.gui.t("nj.servers.addMet"), Material2AL.FOLDER_OPEN, this::chooseServerMet));

        SplitPane split = new SplitPane(this.table, this.logs());
        split.setOrientation(Orientation.VERTICAL);
        split.setDividerPositions(0.62);
        VBox content = new VBox(8, this.listRow(), split);
        VBox.setVgrow(split, Priority.ALWAYS);
        return Fx.screen(this.title(), tools, content);
    }

    // What the servers said and what happened, in two tabs as in eMule; Reset empties the one shown.
    private Node logs() {
        ListView<EngineModel.ServerNote> info = new ListView<>(this.gui.model().serverInfo());
        info.getStyleClass().add("log-list");
        info.setCellFactory(l -> new ServerNoteCell(this.gui));
        info.getItems().addListener((ListChangeListener<EngineModel.ServerNote>)
                c -> info.scrollTo(info.getItems().size() - 1));
        ListView<LogLine> log = new ListView<>(this.gui.model().log());
        log.getStyleClass().add("log-list");
        log.setCellFactory(l -> new LogCell(this.gui.formats()));
        log.getItems().addListener((ListChangeListener<LogLine>)
                c -> log.scrollTo(log.getItems().size() - 1));
        Tab infoTab = new Tab(this.gui.t("IDS_SV_SERVERINFO"), info);
        Tab logTab = new Tab(this.gui.t("IDS_SV_LOG"), log);
        TabPane tabs = new TabPane(infoTab, logTab);
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        this.logTabs = tabs;
        Button clear = Fx.button(this.gui.t("IDS_PW_RESET"), Material2AL.CLEAR_ALL, () -> {
            if (infoTab.isSelected()) {
                this.gui.model().clearServerInfo();
            } else {
                this.gui.model().clearLog();
            }
        });
        clear.getStyleClass().add("small");
        StackPane pane = new StackPane(tabs, clear);
        StackPane.setAlignment(clear, Pos.TOP_RIGHT);
        return pane;
    }

    // Connect becomes Disconnect on the server we are connected to or trying.
    private boolean isCurrent(ServerInfo server) {
        return server != null
                && this.gui.model().connectionProperty().get() != EngineModel.Connection.DISCONNECTED
                && server.address()
                        .equals(this.gui.model().serverAddressProperty().get());
    }

    private String connectText() {
        LiveList.Row<ServerInfo> row = this.table.getSelectionModel().getSelectedItem();
        return this.isCurrent(row == null ? null : row.get())
                ? this.gui.t("IDS_MAIN_BTN_DISCONNECT")
                : this.gui.t("IDS_MAIN_BTN_CONNECT");
    }

    private void showConnect() {
        String text = this.connectText();
        this.connectButton.setText(text);
        this.connectButton.setGraphic(new FontIcon(
                text.equals(this.gui.t("IDS_MAIN_BTN_CONNECT")) ? Material2AL.LINK : Material2AL.LINK_OFF));
    }

    /** Disconnects from the selected server when it is ours, else connects to it, leaving the current one. */
    void connectOrDisconnect() {
        LiveList.Row<ServerInfo> row = this.table.getSelectionModel().getSelectedItem();
        if (this.isCurrent(row == null ? null : row.get())) {
            this.gui.model().disconnect();
        } else {
            this.connectSelected();
        }
    }

    TabPane logTabs() {
        return this.logTabs;
    }

    Button connectButton() {
        return this.connectButton;
    }

    // A server list from the web, from an address typed or used before.
    private Node listRow() {
        this.list = new UrlBox(this.gui, "serverMet", this.gui.t("IDS_SV_MET"));
        this.list.node().setPromptText("https://\u2026/server.met");
        this.list.node().getEditor().setOnAction(e -> this.updateFromUrl());
        Button update = Fx.button(this.gui.t("IDS_SV_UPDATE"), Material2MZ.REFRESH, this::updateFromUrl);
        update.disableProperty().bind(this.list.input().validProperty().not());
        HBox row = new HBox(8, new Label(this.gui.t("IDS_SV_MET")), this.list.node(), update);
        row.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(this.list.node(), Priority.ALWAYS);
        return row;
    }

    void updateFromUrl() {
        if (!this.list.input().isValid()) {
            return;
        }
        URI url = this.list.input().value();
        this.gui.inform().accept(this.gui.f("IDS_DOWNLOADING_SERVERMET_FROM", url));
        this.gui.model().addServers(url, added -> {
            this.list.worked(url);
            this.gui.inform().accept(this.gui.f("nj.servers.fromUrl", added, url.getHost()));
        });
    }

    UrlBox listBox() {
        return this.list;
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
        if (!this.addressInput.isValid()) {
            this.gui
                    .inform()
                    .accept(this.gui.f(
                            "nj.servers.badAddress", this.address.getText().strip()));
            return;
        }
        this.gui.model().connect(Optional.of(this.addressInput.value()));
        this.address.clear();
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

    /** An event after its date and time as the system shows them, with its level as style. */
    static final class LogCell extends ListCell<LogLine> {
        private final Formats formats;

        LogCell(Formats formats) {
            this.formats = formats;
        }

        @Override
        protected void updateItem(LogLine line, boolean empty) {
            super.updateItem(line, empty);
            this.getStyleClass().removeAll("log-line", "debug", "info", "success", "warning", "error");
            if (empty || line == null) {
                this.setText(null);
                return;
            }
            this.setText(this.formats.dateTime(line.time()) + ": " + line.message());
            this.getStyleClass().addAll("log-line", line.level().name().toLowerCase(Locale.ROOT));
        }
    }

    /** A session's start with its date and time, or a line the server sent as it is. */
    static final class ServerNoteCell extends ListCell<EngineModel.ServerNote> {
        private final Gui gui;

        ServerNoteCell(Gui gui) {
            this.gui = gui;
        }

        @Override
        protected void updateItem(EngineModel.ServerNote note, boolean empty) {
            super.updateItem(note, empty);
            this.getStyleClass().removeAll("log-line", "success");
            if (empty || note == null) {
                this.setText(null);
                return;
            }
            this.getStyleClass().add("log-line");
            switch (note.kind()) {
                case CONNECTED, CONNECTED_OBFUSCATED -> {
                    String key = note.kind() == EngineModel.ServerNote.Kind.CONNECTED
                            ? "IDS_CONNECTEDTO"
                            : "IDS_CONNECTEDTOOBFUSCATED";
                    this.setText(this.gui.formats().dateTime(note.time()) + ": " + this.gui.f(key, note.text()));
                    this.getStyleClass().add("success");
                }
                default -> this.setText(note.text());
            }
        }
    }
}
