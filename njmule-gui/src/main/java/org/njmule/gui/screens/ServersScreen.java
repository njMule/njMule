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
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.collections.ListChangeListener;
import javafx.css.PseudoClass;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.RadioMenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.control.Toggle;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.input.Clipboard;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import org.kordamp.ikonli.Ikon;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.material2.Material2AL;
import org.kordamp.ikonli.material2.Material2MZ;
import org.njmule.core.api.Ed2kLink;
import org.njmule.core.api.LogLine;
import org.njmule.core.api.ServerAddress;
import org.njmule.core.api.ServerInfo;
import org.njmule.gui.infra.Formats;
import org.njmule.gui.model.EngineModel;
import org.njmule.gui.model.LiveList;
import org.njmule.gui.ui.ClipboardWatch;
import org.njmule.gui.ui.Fx;
import org.njmule.gui.ui.Gui;
import org.njmule.gui.ui.Input;
import org.njmule.gui.ui.Inputs;
import org.njmule.gui.ui.Screen;
import org.njmule.gui.ui.TableLayout;
import org.njmule.gui.ui.UrlBox;

/** The known servers, connecting to one, and the log below. */
public final class ServersScreen implements Screen {

    private static final PseudoClass CURRENT = PseudoClass.getPseudoClass("current-server");
    private static final PseudoClass TRYING = PseudoClass.getPseudoClass("trying-server");

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final KeyCombination COPY = new KeyCodeCombination(KeyCode.C, KeyCombination.SHORTCUT_DOWN);
    private static final KeyCombination PASTE = new KeyCodeCombination(KeyCode.V, KeyCombination.SHORTCUT_DOWN);

    private final Gui gui;
    private final MyInfoPane myInfo;
    private final TitledPane add = new TitledPane();
    private Tab logTab;
    private boolean wasEmpty;
    private Node view;
    private TableView<LiveList.Row<ServerInfo>> table;
    private final TextField address = new TextField();
    private Input<ServerAddress> addressInput;
    private UrlBox list;
    private TabPane logTabs;
    private final Button connectButton = new Button();
    private final TextField filter = new TextField();
    private final Label count = new Label();
    private final ObjectProperty<Predicate<ServerInfo>> shown = new SimpleObjectProperty<>(server -> true);

    public ServersScreen(Gui gui) {
        this.gui = gui;
        this.myInfo = new MyInfoPane(gui);
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
    public void shown() {
        this.myInfo.readWebUi();
    }

    MyInfoPane myInfo() {
        return this.myInfo;
    }

    @Override
    public Node view() {
        if (this.view == null) {
            this.view = build();
        }
        return this.view;
    }

    private Node build() {
        this.table = Fx.table(this.gui.model().servers(), this.gui.t("nj.servers.none"), this.shown);
        this.table.setPlaceholder(Fx.emptyState(
                Material2AL.DNS,
                this.gui.t("nj.servers.none"),
                Fx.button(this.gui.t("IDS_SV_UPDATE"), Material2MZ.REFRESH, this::updateFromUrl)));
        // The usual server columns; the hard file limit and the version start hidden.
        this.table
                .getColumns()
                .addAll(List.of(
                        Fx.ownDirection(Fx.column(this.gui.t("IDS_SL_SERVERNAME"), 170, ServerInfo::name)),
                        Fx.leftToRight(Fx.column(this.gui.t("IDS_IP"), 150, ServerInfo::address)),
                        Fx.ownDirection(Fx.column(this.gui.t("IDS_DESCRIPTION"), 170, ServerInfo::description)),
                        Fx.numeric(Fx.column(this.gui.t("IDS_PING"), 75, ServerInfo::ping, ms -> known(ms))),
                        Fx.numeric(Fx.column(this.gui.t("IDS_UUSERS"), 90, ServerInfo::users, this::known)),
                        Fx.numeric(Fx.column(this.gui.t("IDS_MAXCLIENT"), 120, ServerInfo::maxUsers, this::known)),
                        Fx.numeric(Fx.column(this.gui.t("IDS_PW_FILES"), 95, ServerInfo::files, this::known)),
                        Fx.column(this.gui.t("IDS_PRIORITY"), 80, ServerInfo::priority, this::priority),
                        Fx.numeric(Fx.column(this.gui.t("IDS_UFAILED"), 90, ServerInfo::failed)),
                        Fx.column(this.gui.t("IDS_STATICSERVER"), 70, ServerInfo::isStatic, this::yesNo),
                        Fx.numeric(Fx.column(this.gui.t("IDS_SOFTFILES"), 175, ServerInfo::softFiles, this::known)),
                        hidden(Fx.numeric(
                                Fx.column(this.gui.t("IDS_HARDFILES"), 210, ServerInfo::hardFiles, this::known))),
                        hidden(Fx.leftToRight(Fx.column(this.gui.t("IDS_VERSION"), 90, ServerInfo::version))),
                        Fx.numeric(Fx.column(this.gui.t("IDS_IDLOW"), 85, ServerInfo::lowIdUsers, this::known)),
                        Fx.column(this.gui.t("IDS_OBFUSCATION"), 110, ServerInfo::supportsObfuscation, this::yesNo)));
        TableLayout.remember(this.table, "servers", this.gui.settings());
        // Columns keep their widths and the list scrolls sideways.
        this.table.setColumnResizePolicy(TableView.UNCONSTRAINED_RESIZE_POLICY);
        this.table.setOnKeyPressed(this::key);
        this.table.setRowFactory(t -> {
            TableRow<LiveList.Row<ServerInfo>> row = new TableRow<>() {
                @Override
                protected void updateItem(LiveList.Row<ServerInfo> item, boolean empty) {
                    super.updateItem(item, empty);
                    ServersScreen.this.markCurrent(this);
                }
            };
            row.setOnMouseClicked(e -> {
                if (e.getClickCount() == 2 && !row.isEmpty()) {
                    connectTo(row.getItem().get());
                }
            });
            return row;
        });
        this.table.setContextMenu(menu());

        this.address.setPromptText(this.gui.i18n().embed(this.gui.t("nj.servers.addressPrompt")));
        this.address.setPrefColumnCount(18);
        this.address.setOnAction(e -> connectToAddress());
        this.addressInput = new Inputs(this.gui)
                .address(this.address, this.gui.t("nj.servers.addConnect"))
                .quietWhenEmpty();
        Button connectTo = Fx.button(this.gui.t("nj.servers.addConnect"), Material2AL.ADD, this::connectToAddress);
        connectTo.disableProperty().bind(this.addressInput.validProperty().not());
        this.connectButton.setOnAction(e -> connectOrDisconnect());
        this.table.getSelectionModel().selectedItemProperty().addListener((o, was, now) -> showConnect());
        this.gui.subscriptions().onChange(this.gui.model().connectionProperty(), (was, now) -> showCurrent());
        this.gui.subscriptions().onChange(this.gui.model().serverAddressProperty(), (was, now) -> showCurrent());
        showConnect();
        var tools = Fx.toolRow(
                this.connectButton, Fx.button(this.gui.t("IDS_REMOVE"), Material2AL.DELETE, this::removeSelected));
        this.add.setText(this.gui.t("nj.servers.add"));
        this.add.getStyleClass().add(Styles.DENSE);
        this.add.setContent(ways(connectTo));
        // Open while there is no server to connect to, closed once there are some; in between, as the user leaves it.
        this.add.setExpanded(this.table.getItems().isEmpty());
        this.gui.subscriptions().onListChange(this.gui.model().servers().rows(), c -> {
            boolean none = this.gui.model().servers().rows().isEmpty();
            if (none != this.add.isExpanded() && none != this.wasEmpty) {
                this.add.setExpanded(none);
            }
            this.wasEmpty = none;
        });
        this.wasEmpty = this.gui.model().servers().rows().isEmpty();

        // The list, with the server's messages, the log and My Info below it.
        SplitPane split = new SplitPane(listPane(), logs());
        split.setOrientation(Orientation.VERTICAL);
        split.setDividerPositions(0.62);
        VBox content = new VBox(8, this.add, split);
        VBox.setVgrow(split, Priority.ALWAYS);
        // Shown under the network, which gives the title.
        return Fx.part(tools, content);
    }

    // What the servers said and what happened, in two tabs; Reset empties the one shown.
    private Node logs() {
        // Both logs read left to right, the lines starting on the left, in every language.
        ListView<EngineModel.ServerNote> info =
                Fx.leftToRight(new ListView<>(this.gui.model().serverInfo()));
        info.getStyleClass().add("log-list");
        info.setCellFactory(l -> new ServerNoteCell(this.gui));
        this.gui
                .subscriptions()
                .onListChange(
                        this.gui.model().serverInfo(),
                        c -> info.scrollTo(info.getItems().size() - 1));
        ListView<LogLine> log = Fx.leftToRight(new ListView<>(this.gui.model().log()));
        log.getStyleClass().add("log-list");
        log.setCellFactory(l -> new LogCell(this.gui.formats()));
        this.gui
                .subscriptions()
                .onListChange(
                        this.gui.model().log(), c -> log.scrollTo(log.getItems().size() - 1));
        Tab infoTab = new Tab(this.gui.t("IDS_SV_SERVERINFO"), info);
        Tab logTab = new Tab(this.gui.t("IDS_SV_LOG"), log);
        this.logTab = logTab;
        Tab mine = new Tab(this.gui.t("IDS_MYINFO"), this.myInfo.node());
        TabPane tabs = new TabPane(infoTab, logTab, mine);
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
        // My Info has nothing to reset.
        clear.visibleProperty().bind(mine.selectedProperty().not());
        StackPane pane = new StackPane(tabs, clear);
        StackPane.setAlignment(clear, Pos.TOP_RIGHT);
        return pane;
    }

    private void showCurrent() {
        showConnect();
        for (Node node : this.table.lookupAll(".table-row-cell")) {
            if (node instanceof TableRow<?> row) {
                markCurrent(row);
            }
        }
    }

    // The server we are connected to is bold, the one we are trying italic; the tooltip says which, so colour is never
    // the only cue.
    private void markCurrent(TableRow<?> row) {
        boolean ours = row.getItem() instanceof LiveList.Row<?> item
                && item.get() instanceof ServerInfo server
                && isCurrent(server);
        EngineModel.Connection state = this.gui.model().connectionProperty().get();
        boolean connected = ours && state == EngineModel.Connection.CONNECTED;
        boolean connecting = ours && state == EngineModel.Connection.CONNECTING;
        row.pseudoClassStateChanged(CURRENT, connected);
        row.pseudoClassStateChanged(TRYING, connecting);
        row.setTooltip(
                connected
                        ? new Tooltip(this.gui.t("nj.servers.current"))
                        : connecting ? new Tooltip(this.gui.t("nj.servers.trying")) : null);
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
        return isCurrent(row == null ? null : row.get())
                ? this.gui.t("IDS_MAIN_BTN_DISCONNECT")
                : this.gui.t("IDS_MAIN_BTN_CONNECT");
    }

    private void showConnect() {
        String text = connectText();
        this.connectButton.setText(text);
        this.connectButton.setGraphic(new FontIcon(
                text.equals(this.gui.t("IDS_MAIN_BTN_CONNECT")) ? Material2AL.LINK : Material2AL.LINK_OFF));
    }

    /** Disconnects from the selected server when it is ours, else connects to it, leaving the current one. */
    void connectOrDisconnect() {
        LiveList.Row<ServerInfo> row = this.table.getSelectionModel().getSelectedItem();
        if (isCurrent(row == null ? null : row.get())) {
            this.gui.model().disconnect();
        } else {
            connectSelected();
        }
    }

    TabPane logTabs() {
        return this.logTabs;
    }

    /** Brings the log to the front, below the server list. */
    public void showLog() {
        view();
        this.logTabs.getSelectionModel().select(this.logTab);
    }

    Button connectButton() {
        return this.connectButton;
    }

    // The ways to add servers, together: a server list from the web (an address typed or used before), one server by
    // its address, or a server.met file.
    private Node ways(Button connectTo) {
        this.list = new UrlBox(this.gui, "serverMet", this.gui.t("IDS_SV_MET"));
        this.list.node().setPromptText("https://\u2026/server.met");
        this.list.node().getEditor().setOnAction(e -> updateFromUrl());
        this.list.node().setMaxWidth(Double.MAX_VALUE);
        Button update = Fx.button(this.gui.t("IDS_SV_UPDATE"), Material2MZ.REFRESH, this::updateFromUrl);
        update.disableProperty().bind(this.list.input().validProperty().not());
        this.address.setMaxWidth(Double.MAX_VALUE);
        GridPane ways = new GridPane(8, 8);
        ways.addRow(0, Fx.whole(new Label(this.gui.t("IDS_SV_MET"))), this.list.node(), Fx.whole(update));
        ways.addRow(1, Fx.whole(new Label(this.gui.t("IDS_SV_ADDRESS"))), this.address, Fx.whole(connectTo));
        ways.add(
                Fx.whole(Fx.button(this.gui.t("nj.servers.addMet"), Material2AL.FOLDER_OPEN, this::chooseServerMet)),
                1,
                2);
        ColumnConstraints grows = new ColumnConstraints();
        grows.setHgrow(Priority.ALWAYS);
        ways.getColumnConstraints().addAll(new ColumnConstraints(), grows);
        return ways;
    }

    TitledPane addPane() {
        return this.add;
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

    // The count of servers, and the box that narrows the list as one types.
    private Node listPane() {
        this.filter.setPromptText(this.gui.t("nj.servers.filter"));
        this.filter.setPrefColumnCount(18);
        this.filter.textProperty().addListener((o, was, now) -> this.shown.set(matching(now)));
        this.filter.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ESCAPE) {
                this.filter.clear();
            }
        });
        this.count.getStyleClass().add(Styles.TEXT_BOLD);
        this.table.getItems().addListener((ListChangeListener<LiveList.Row<ServerInfo>>) c -> showCount());
        this.gui.subscriptions().onListChange(this.gui.model().servers().rows(), c -> showCount());
        showCount();
        HBox head = new HBox(8, this.count, Fx.spacer(), Fx.icon(Material2MZ.SEARCH), this.filter);
        head.setAlignment(Pos.CENTER_LEFT);
        VBox pane = new VBox(6, head, this.table);
        VBox.setVgrow(this.table, Priority.ALWAYS);
        return pane;
    }

    private void showCount() {
        int all = this.gui.model().servers().rows().size();
        int visible = this.table.getItems().size();
        this.count.setText(this.gui.t("IDS_SV_SERVERLIST")
                + " ("
                + (visible == all ? String.valueOf(all) : visible + "/" + all)
                + ")");
    }

    /** Servers whose name, address, description or version hold every word of {@code text}, in any case. */
    static Predicate<ServerInfo> matching(String text) {
        List<String> words = WHITESPACE
                .splitAsStream(text.strip().toLowerCase(Locale.ROOT))
                .filter(w -> !w.isEmpty())
                .toList();
        if (words.isEmpty()) {
            return server -> true;
        }
        return server -> {
            String all = String.join(" ", server.name(), server.address(), server.description(), server.version())
                    .toLowerCase(Locale.ROOT);
            return words.stream().allMatch(all::contains);
        };
    }

    // The server menu; no Find, which the box above the list does as one types.
    private ContextMenu menu() {
        MenuItem connect = Fx.menuItem(this.gui.t("IDS_CONNECTTHIS"), Material2AL.LINK, this::connectOrDisconnect);
        connect.getStyleClass().add("default-item");
        Menu priority = new Menu(this.gui.t("IDS_PRIORITY"), Fx.icon(Material2AL.LOW_PRIORITY));
        ToggleGroup priorities = new ToggleGroup();
        for (ServerInfo.Priority p :
                List.of(ServerInfo.Priority.LOW, ServerInfo.Priority.NORMAL, ServerInfo.Priority.HIGH)) {
            RadioMenuItem item = new RadioMenuItem(priority(p));
            item.setUserData(p);
            item.setToggleGroup(priorities);
            item.setOnAction(e -> setPriority(p));
            priority.getItems().add(item);
        }
        MenuItem addStatic =
                Fx.menuItem(this.gui.t("IDS_ADDTOSTATIC"), Material2MZ.PLAYLIST_ADD, () -> setStatic(true));
        MenuItem removeStatic = Fx.menuItem(
                this.gui.t("IDS_REMOVEFROMSTATIC"), Material2MZ.REMOVE_CIRCLE_OUTLINE, () -> setStatic(false));
        MenuItem copy = Fx.menuItem(this.gui.t("IDS_DL_LINK1"), Material2AL.CONTENT_COPY, this::copyLinks);
        MenuItem paste = Fx.menuItem(this.gui.t("IDS_SW_DIRECTDOWNLOAD"), Material2AL.CONTENT_PASTE, this::pasteLinks);
        MenuItem remove = Fx.menuItem(this.gui.t("IDS_REMOVETHIS"), Material2AL.DELETE, this::removeSelected);
        MenuItem removeAll = Fx.menuItem(this.gui.t("IDS_REMOVEALL"), Material2AL.DELETE_SWEEP, this::askRemoveAll);
        ContextMenu menu = new ContextMenu(
                connect,
                priority,
                new SeparatorMenuItem(),
                addStatic,
                removeStatic,
                new SeparatorMenuItem(),
                copy,
                paste,
                remove,
                removeAll);
        menu.setOnShowing(e -> {
            List<ServerInfo> selected = Fx.selected(this.table);
            boolean none = selected.isEmpty();
            connect.setText(
                    isCurrent(none ? null : selected.getFirst())
                            ? this.gui.t("IDS_MAIN_BTN_DISCONNECT")
                            : this.gui.t("IDS_CONNECTTHIS"));
            connect.setDisable(none);
            priority.setDisable(none);
            Set<ServerInfo.Priority> current =
                    selected.stream().map(ServerInfo::priority).collect(Collectors.toSet());
            for (Toggle t : priorities.getToggles()) {
                t.setSelected(current.size() == 1 && current.contains((ServerInfo.Priority) t.getUserData()));
            }
            addStatic.setDisable(selected.stream().allMatch(ServerInfo::isStatic));
            removeStatic.setDisable(selected.stream().noneMatch(ServerInfo::isStatic));
            copy.setDisable(none);
            paste.setDisable(
                    serverLinks(Clipboard.getSystemClipboard().getString()).isEmpty());
            remove.setDisable(none);
            removeAll.setDisable(this.gui.model().servers().rows().isEmpty());
        });
        return menu;
    }

    private void key(KeyEvent e) {
        if (e.getCode() == KeyCode.DELETE) {
            removeSelected();
        } else if (e.getCode() == KeyCode.ENTER) {
            connectOrDisconnect();
        } else if (COPY.match(e)) {
            copyLinks();
        } else if (PASTE.match(e)) {
            pasteLinks();
        } else {
            return;
        }
        e.consume();
    }

    void setPriority(ServerInfo.Priority priority) {
        for (ServerInfo server : Fx.selected(this.table)) {
            this.gui.model().setServerPriority(ServerAddress.parse(server.address()), priority);
        }
    }

    void setStatic(boolean isStatic) {
        for (ServerInfo server : Fx.selected(this.table)) {
            if (server.isStatic() != isStatic) {
                this.gui.model().setStaticServer(ServerAddress.parse(server.address()), isStatic);
            }
        }
    }

    /** The ed2k links of the selected servers, one per line. */
    void copyLinks() {
        List<ServerInfo> selected = Fx.selected(this.table);
        if (selected.isEmpty()) {
            return;
        }
        List<String> links = new ArrayList<>();
        for (ServerInfo server : selected) {
            ServerAddress address = ServerAddress.parse(server.address());
            links.add(new Ed2kLink.ServerLink(address.host(), address.port()).toUri());
        }
        ClipboardWatch.copy(String.join("\n", links));
    }

    /** Adds the servers of the ed2k server links on the clipboard, and fetches the server lists linked there. */
    void pasteLinks() {
        for (Ed2kLink link : serverLinks(Clipboard.getSystemClipboard().getString())) {
            switch (link) {
                case Ed2kLink.ServerLink server ->
                    this.gui.model().addServer(new ServerAddress(server.host(), server.port()));
                case Ed2kLink.ServerListLink linked -> {
                    this.list.node().getEditor().setText(linked.url());
                    updateFromUrl();
                }
                default -> {
                    // Only server links are taken here.
                }
            }
        }
    }

    /** The server and server list links in {@code text}, which may be null. */
    static List<Ed2kLink> serverLinks(String text) {
        if (text == null) {
            return List.of();
        }
        List<Ed2kLink> links = new ArrayList<>();
        for (String word : WHITESPACE.splitAsStream(text.strip()).toList()) {
            if (word.regionMatches(true, 0, "ed2k://", 0, 7)) {
                Ed2kLink.tryParse(word)
                        .filter(l -> l instanceof Ed2kLink.ServerLink || l instanceof Ed2kLink.ServerListLink)
                        .ifPresent(links::add);
            }
        }
        return links;
    }

    private void askRemoveAll() {
        Alert confirm = this.gui.dialog(new Alert(Alert.AlertType.CONFIRMATION, this.gui.t("IDS_REMOVEALLSERVERS")));
        ButtonType go = Fx.answers(confirm, this.gui.t("IDS_REMOVEALL"), this.gui.t("IDS_CANCEL"), true);
        confirm.initOwner(this.view.getScene().getWindow());
        confirm.showAndWait().filter(go::equals).ifPresent(b -> removeAll());
    }

    void removeAll() {
        if (this.gui.model().connectionProperty().get() == EngineModel.Connection.CONNECTING) {
            this.gui.model().disconnect();
        }
        this.gui
                .model()
                .removeServers(this.gui.model().servers().rows().stream()
                        .map(row -> ServerAddress.parse(row.get().address()))
                        .toList());
    }

    private String known(long value) {
        return value == 0 ? "" : this.gui.formats().count(value);
    }

    private String yesNo(boolean value) {
        return this.gui.t(value ? "IDS_YES" : "IDS_NO");
    }

    private static <S, T> TableColumn<S, T> hidden(TableColumn<S, T> column) {
        column.setVisible(false);
        return column;
    }

    TextField filterField() {
        return this.filter;
    }

    Label countLabel() {
        return this.count;
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
            connectTo(selected.getFirst());
        }
    }

    void removeSelected() {
        List<ServerAddress> selected = Fx.selected(this.table).stream()
                .map(server -> ServerAddress.parse(server.address()))
                .toList();
        if (!selected.isEmpty()) {
            this.gui.model().removeServers(selected);
        }
    }

    void connectToAddress() {
        if (!this.addressInput.isValid()) {
            this.gui
                    .warn()
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
            addServerMet(file.toPath());
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
            getStyleClass().removeAll("log-line", "debug", "info", "success", "warning", "error");
            if (empty || line == null) {
                setText(null);
                setGraphic(null);
                return;
            }
            setText(this.formats.dateTime(line.time()) + ": " + line.message());
            setGraphic(icon(line.level()));
            getStyleClass().addAll("log-line", line.level().name().toLowerCase(Locale.ROOT));
        }

        // Problems and successes carry an icon besides their colour; the other lines keep the same indent.
        private static FontIcon icon(LogLine.Level level) {
            FontIcon icon = new FontIcon(
                    switch (level) {
                        case ERROR -> Material2AL.ERROR_OUTLINE;
                        case WARNING -> Material2MZ.WARNING;
                        case SUCCESS -> Material2AL.CHECK_CIRCLE_OUTLINE;
                        case INFO, DEBUG -> Material2AL.INFO;
                    });
            icon.getStyleClass().add("log-icon");
            if (level == LogLine.Level.INFO || level == LogLine.Level.DEBUG) {
                icon.getStyleClass().add("blank");
            }
            return icon;
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
            getStyleClass().removeAll("log-line", "success");
            if (empty || note == null) {
                setText(null);
                return;
            }
            getStyleClass().add("log-line");
            switch (note.kind()) {
                case CONNECTED, CONNECTED_OBFUSCATED -> {
                    String key = note.kind() == EngineModel.ServerNote.Kind.CONNECTED
                            ? "IDS_CONNECTEDTO"
                            : "IDS_CONNECTEDTOOBFUSCATED";
                    setText(this.gui.formats().dateTime(note.time()) + ": " + this.gui.f(key, note.text()));
                    getStyleClass().add("success");
                }
                default -> setText(note.text());
            }
        }
    }
}
