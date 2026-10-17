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

import atlantafx.base.controls.CustomTextField;
import atlantafx.base.controls.Popover;
import atlantafx.base.theme.Styles;
import atlantafx.base.theme.Tweaks;
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
import javafx.beans.value.ObservableBooleanValue;
import javafx.collections.ListChangeListener;
import javafx.css.PseudoClass;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.RadioMenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.Toggle;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.input.Clipboard;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import org.njmule.core.api.Ed2kLink;
import org.njmule.core.api.ServerAddress;
import org.njmule.core.api.ServerInfo;
import org.njmule.gui.model.EngineModel;
import org.njmule.gui.model.LiveList;
import org.njmule.gui.ui.ClipboardWatch;
import org.njmule.gui.ui.Fx;
import org.njmule.gui.ui.Gui;
import org.njmule.gui.ui.Icons;
import org.njmule.gui.ui.Input;
import org.njmule.gui.ui.Inputs;
import org.njmule.gui.ui.InspectorPane;
import org.njmule.gui.ui.Screen;
import org.njmule.gui.ui.TableLayout;
import org.njmule.gui.ui.UrlBox;

/** The known servers, with the details of the one chosen and what to do with it, and the ways to add more. */
public final class ServersScreen implements Screen {

    private static final PseudoClass CURRENT = PseudoClass.getPseudoClass("current-server");
    private static final PseudoClass TRYING = PseudoClass.getPseudoClass("trying-server");

    // The list keeps this much beside the panel, else the panel lies over its end while a server is chosen.
    private static final double LIST_WIDTH = 520;
    private static final double PANEL_WIDTH = 300;
    private static final double PANEL_GAP = 12;

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final KeyCombination COPY = new KeyCodeCombination(KeyCode.C, KeyCombination.SHORTCUT_DOWN);
    private static final KeyCombination PASTE = new KeyCodeCombination(KeyCode.V, KeyCombination.SHORTCUT_DOWN);

    private final Gui gui;
    private final Button add = new Button();
    private Popover ways;
    private ServerPanel panel;
    private InspectorPane inspector;
    private Node view;
    private TableView<LiveList.Row<ServerInfo>> table;
    private final TextField address = new TextField();
    private Input<ServerAddress> addressInput;
    private UrlBox list;
    private final CustomTextField filter = new CustomTextField();
    private final Label count = new Label();
    private final ObjectProperty<Predicate<ServerInfo>> shown = new SimpleObjectProperty<>(server -> true);

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
    public Icons icon() {
        return Icons.SERVER;
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
        Button connectTo = Fx.button(this.gui.t("nj.servers.addConnect"), Icons.ADD, this::connectToAddress);
        connectTo.disableProperty().bind(this.addressInput.validProperty().not());
        this.gui.subscriptions().onChange(this.gui.model().connectionProperty(), (was, now) -> showCurrent());
        this.gui.subscriptions().onChange(this.gui.model().serverAddressProperty(), (was, now) -> showCurrent());
        // The ways to add servers in a popup under their button, also offered by an empty list.
        Node wayList = ways(connectTo);
        wayList.getStyleClass().add("server-ways");
        this.ways = new Popover(wayList);
        this.ways.setHeaderAlwaysVisible(true);
        this.ways.setTitle(this.gui.t("nj.servers.add"));
        this.ways.setDetachable(false);
        this.ways.setArrowLocation(Popover.ArrowLocation.TOP_RIGHT);
        this.add.setText(this.gui.t("nj.servers.add"));
        this.add.setGraphic(Fx.icon(Icons.ADD));
        this.add.setId("add-servers");
        this.add.setOnAction(e -> showWays());
        this.table.setPlaceholder(Fx.emptyState(
                Icons.SERVER,
                this.gui.t("nj.servers.none"),
                Fx.button(this.gui.t("IDS_SV_UPDATE"), Icons.REFRESH, this::updateFromUrl),
                Fx.button(this.gui.t("nj.servers.add"), Icons.ADD, this::showWays)));
        this.filter.setPromptText(this.gui.t("nj.servers.filter"));
        this.filter.setPrefColumnCount(14);
        this.filter.setLeft(Fx.icon(Icons.FILTER));
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
        // Connecting is the network menu's in the top bar; the chosen server's is in its details, its menu, Enter and a
        // double click.
        var tools = Fx.toolRow(this.count, Fx.spacer(), this.filter, this.add);
        // The list, with the details of the server chosen beside it or over its end.
        this.panel = new ServerPanel();
        VBox list = new VBox(this.table);
        VBox.setVgrow(this.table, Priority.ALWAYS);
        this.inspector = new InspectorPane(list, this.panel.view(), LIST_WIDTH, PANEL_WIDTH, PANEL_GAP);
        this.panel.closeShown(this.inspector.floatingProperty());
        this.table.getSelectionModel().getSelectedItems().addListener((ListChangeListener<LiveList.Row<ServerInfo>>)
                c -> this.panel.show());
        this.gui.subscriptions().onListChange(this.gui.model().servers().rows(), c -> this.panel.show());
        this.gui.subscriptions().onListChange(this.gui.model().serverInfo(), c -> this.panel.show());
        this.inspector.widthProperty().addListener((o, was, now) -> this.panel.show());
        // Shown under the network, which gives the title.
        return Fx.part(tools, this.inspector);
    }

    // The ways to add servers, under their button.
    void showWays() {
        if (this.add.getScene() != null && this.add.getScene().getWindow() != null) {
            this.ways.show(this.add);
        }
    }

    /** Whether the ways to add servers show, for tests. */
    boolean waysShown() {
        return this.ways != null && this.ways.isShowing();
    }

    private void showCurrent() {
        if (this.panel != null) {
            this.panel.show();
        }
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

    // Connect to this server becomes Disconnect on the server we are connected to or trying.
    private boolean isCurrent(ServerInfo server) {
        return server != null
                && this.gui.model().connectionProperty().get() != EngineModel.Connection.DISCONNECTED
                && server.address()
                        .equals(this.gui.model().serverAddressProperty().get());
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

    // The ways to add servers, together: a server list from the web (an address typed or used before), one server by
    // its address, or a server.met file.
    private Node ways(Button connectTo) {
        this.list = new UrlBox(this.gui, "serverMet", this.gui.t("IDS_SV_MET"));
        this.list.node().setPromptText("https://\u2026/server.met");
        Fx.onEnter(this.list.node(), this::updateFromUrl);
        this.list.node().setMaxWidth(Double.MAX_VALUE);
        Button update = Fx.button(this.gui.t("IDS_SV_UPDATE"), Icons.REFRESH, this::updateFromUrl);
        update.disableProperty().bind(this.list.input().validProperty().not());
        this.address.setMaxWidth(Double.MAX_VALUE);
        GridPane ways = new GridPane(8, 8);
        ways.addRow(0, Fx.whole(new Label(this.gui.t("IDS_SV_MET"))), this.list.node(), Fx.whole(update));
        ways.addRow(1, Fx.whole(new Label(this.gui.t("IDS_SV_ADDRESS"))), this.address, Fx.whole(connectTo));
        ways.add(Fx.whole(Fx.button(this.gui.t("nj.servers.addMet"), Icons.FOLDER_OPEN, this::chooseServerMet)), 1, 2);
        ColumnConstraints grows = new ColumnConstraints();
        grows.setHgrow(Priority.ALWAYS);
        ways.getColumnConstraints().addAll(new ColumnConstraints(), grows);
        return ways;
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
        MenuItem connect = Fx.menuItem(this.gui.t("IDS_CONNECTTHIS"), Icons.LINK, this::connectOrDisconnect);
        connect.getStyleClass().add("default-item");
        Menu priority = new Menu(this.gui.t("IDS_PRIORITY"), Fx.icon(Icons.PRIORITY));
        ToggleGroup priorities = new ToggleGroup();
        for (ServerInfo.Priority p :
                List.of(ServerInfo.Priority.LOW, ServerInfo.Priority.NORMAL, ServerInfo.Priority.HIGH)) {
            RadioMenuItem item = new RadioMenuItem(priority(p));
            item.setUserData(p);
            item.setToggleGroup(priorities);
            item.setOnAction(e -> setPriority(p));
            priority.getItems().add(item);
        }
        MenuItem addStatic = Fx.menuItem(this.gui.t("IDS_ADDTOSTATIC"), Icons.LIST_ADD, () -> setStatic(true));
        MenuItem removeStatic =
                Fx.menuItem(this.gui.t("IDS_REMOVEFROMSTATIC"), Icons.REMOVE_CIRCLE, () -> setStatic(false));
        MenuItem copy = Fx.menuItem(this.gui.t("IDS_DL_LINK1"), Icons.COPY, this::copyLinks);
        MenuItem paste = Fx.menuItem(this.gui.t("IDS_SW_DIRECTDOWNLOAD"), Icons.PASTE, this::pasteLinks);
        MenuItem remove = Fx.menuItem(this.gui.t("IDS_REMOVETHIS"), Icons.DELETE, this::removeSelected);
        MenuItem removeAll = Fx.menuItem(this.gui.t("IDS_REMOVEALL"), Icons.DELETE_ALL, this::askRemoveAll);
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

    ServerPanel panel() {
        return this.panel;
    }

    /**
     * The details of the server chosen: what it is, how many use it, its limits and what it said, and what to do with
     * it; a word on choosing one when none is.
     */
    final class ServerPanel {
        private final Label name = new Label();
        private final Label where = Fx.leftToRight(new Label());
        private final Label description = new Label();
        private final GridPane facts = new GridPane();
        private final Label message = new Label();
        private final VBox said;
        private final Button action = new Button();
        private final Button keep = new Button();
        private final VBox one;
        private final ScrollPane scroll;
        private final Label none = new Label();
        private final Button close;
        private final StackPane box;

        ServerPanel() {
            StackPane kind = new StackPane(Fx.icon(Icons.SERVER));
            kind.getStyleClass().add("details-kind");
            this.name.getStyleClass().add("details-name");
            this.name.setWrapText(true);
            Fx.ownDirection(this.name);
            this.where.getStyleClass().add("details-meta");
            VBox title = new VBox(2, this.name, this.where);
            title.setMinWidth(0);
            this.close = Fx.iconButton(
                    ServersScreen.this.gui.t("IDS_FD_CLOSE"),
                    Icons.CLOSE,
                    () -> ServersScreen.this.table.getSelectionModel().clearSelection());
            this.close.getStyleClass().add(Styles.FLAT);
            HBox.setHgrow(title, Priority.ALWAYS);
            HBox top = new HBox(12, kind, title, this.close);
            this.description.setWrapText(true);
            this.description.getStyleClass().add("details-meta");
            Fx.ownDirection(this.description);
            this.facts.getStyleClass().add("details-facts");
            Label saidTitle = new Label(ServersScreen.this.gui.t("IDS_SV_SERVERINFO"));
            saidTitle.getStyleClass().add("field-label");
            this.message.setWrapText(true);
            this.message.getStyleClass().add("server-message");
            Fx.leftToRight(this.message);
            this.said = new VBox(4, saidTitle, this.message);
            this.action.setId("server-action");
            this.action.setOnAction(e -> connectOrDisconnect());
            this.keep.setId("server-static");
            this.keep.getStyleClass().add(Styles.FLAT);
            this.keep.setOnAction(e -> {
                LiveList.Row<ServerInfo> row =
                        ServersScreen.this.table.getSelectionModel().getSelectedItem();
                if (row != null) {
                    setStatic(!row.get().isStatic());
                }
            });
            Fx.whole(this.action);
            Fx.whole(this.keep);
            MenuItem copy =
                    Fx.menuItem(ServersScreen.this.gui.t("IDS_DL_LINK1"), Icons.COPY, ServersScreen.this::copyLinks);
            MenuItem remove = Fx.menuItem(
                    ServersScreen.this.gui.t("IDS_REMOVETHIS"), Icons.DELETE, ServersScreen.this::removeSelected);
            Menu priorities = new Menu(ServersScreen.this.gui.t("IDS_PRIORITY"), Fx.icon(Icons.PRIORITY));
            for (ServerInfo.Priority p :
                    List.of(ServerInfo.Priority.LOW, ServerInfo.Priority.NORMAL, ServerInfo.Priority.HIGH)) {
                priorities.getItems().add(Fx.menuItem(priority(p), Icons.PRIORITY, () -> setPriority(p)));
            }
            MenuButton more =
                    new MenuButton(null, Fx.icon(Icons.MORE), priorities, copy, new SeparatorMenuItem(), remove);
            more.getStyleClass().addAll(Styles.BUTTON_ICON, Tweaks.NO_ARROW);
            more.setTooltip(new Tooltip(ServersScreen.this.gui.t("IDS_MORE")));
            more.setAccessibleText(ServersScreen.this.gui.t("IDS_MORE"));
            Fx.whole(more);
            FlowPane buttons = new FlowPane(8, 8, this.action, this.keep);
            buttons.setMinWidth(0);
            HBox.setHgrow(buttons, Priority.ALWAYS);
            HBox actions = new HBox(8, buttons, more);
            // What to do comes first, the figures below it: the panel may be short.
            this.one = new VBox(14, top, this.description, actions, this.facts, this.said);
            this.one.getStyleClass().add("server-panel-body");
            this.none.setText(ServersScreen.this.gui.t("nj.servers.choose"));
            this.none.setWrapText(true);
            this.none.getStyleClass().add("details-none");
            this.scroll = Fx.scrollWhenShort(this.one);
            this.box = new StackPane(this.scroll, this.none);
            StackPane.setAlignment(this.none, Pos.TOP_LEFT);
            this.box.getStyleClass().addAll("download-details", "server-panel");
            show();
        }

        Region view() {
            return this.box;
        }

        // Over the list, the panel has a close, which leaves no server chosen.
        void closeShown(ObservableBooleanValue floating) {
            this.close.visibleProperty().bind(floating);
            this.close.managedProperty().bind(floating);
        }

        Button actionButton() {
            return this.action;
        }

        Button staticButton() {
            return this.keep;
        }

        /** Whether one server shows, rather than the word on choosing one. */
        boolean showsOne() {
            return this.scroll.isVisible();
        }

        Label message() {
            return this.message;
        }

        // The server chosen as it is now: one shows in full, none or several leave a word.
        void show() {
            List<ServerInfo> chosen = Fx.selected(ServersScreen.this.table);
            boolean shown = chosen.size() == 1;
            this.scroll.setVisible(shown);
            this.none.setVisible(!shown);
            // Too narrow for both side by side, the panel covers the list only while a server is chosen.
            InspectorPane pane = ServersScreen.this.inspector;
            if (pane != null) {
                pane.openProperty().set(shown || pane.getWidth() >= LIST_WIDTH + PANEL_GAP + PANEL_WIDTH);
            }
            if (!shown) {
                return;
            }
            ServerInfo server = chosen.getFirst();
            this.name.setText(server.name().isEmpty() ? server.address() : server.name());
            this.where.setText(server.address());
            this.description.setText(server.description());
            this.description.setVisible(!server.description().isEmpty());
            this.description.setManaged(!server.description().isEmpty());
            this.facts.getChildren().clear();
            int row = 0;
            row = fact(row, "IDS_UUSERS", known(server.users()));
            row = fact(row, "IDS_PW_FILES", known(server.files()));
            row = fact(row, "IDS_SOFTFILES", known(server.softFiles()));
            row = fact(row, "IDS_HARDFILES", known(server.hardFiles()));
            row = fact(row, "IDS_IDLOW", known(server.lowIdUsers()));
            row = fact(row, "IDS_PING", server.ping() == 0 ? "" : server.ping() + " ms");
            row = fact(row, "IDS_VERSION", server.version());
            row = fact(row, "IDS_UFAILED", server.failed() == 0 ? "" : String.valueOf(server.failed()));
            fact(row, "IDS_PRIORITY", priority(server.priority()));
            // What the server said in this session goes with the server we are on.
            boolean ours = isCurrent(server);
            List<String> lines = new ArrayList<>();
            if (ours) {
                for (EngineModel.ServerNote note :
                        ServersScreen.this.gui.model().serverInfo()) {
                    switch (note.kind()) {
                        case CONNECTED, CONNECTED_OBFUSCATED -> lines.clear();
                        case MESSAGE -> lines.add(note.text());
                        default -> {
                            // The title and the gaps say nothing of the server.
                        }
                    }
                }
            }
            String last = String.join("\n", lines.subList(Math.max(0, lines.size() - 4), lines.size()));
            this.message.setText(last);
            this.said.setVisible(!last.isEmpty());
            this.said.setManaged(!last.isEmpty());
            this.action.setText(ServersScreen.this.gui.t(ours ? "IDS_MAIN_BTN_DISCONNECT" : "IDS_MAIN_BTN_CONNECT"));
            this.action.setGraphic(Fx.icon(ours ? Icons.LINK_OFF : Icons.LINK));
            this.keep.setText(ServersScreen.this.gui.t(server.isStatic() ? "IDS_REMOVEFROMSTATIC" : "IDS_ADDTOSTATIC"));
            this.keep.setGraphic(Fx.icon(server.isStatic() ? Icons.REMOVE_CIRCLE : Icons.LIST_ADD));
        }

        private int fact(int row, String key, String value) {
            if (value.isEmpty()) {
                return row;
            }
            String text = ServersScreen.this.gui.t(key).strip();
            Label name = new Label(text.endsWith(":") ? text.substring(0, text.length() - 1) : text);
            name.getStyleClass().add("text-muted");
            name.setMinWidth(Region.USE_PREF_SIZE);
            Label shown = new Label(value);
            shown.setWrapText(true);
            this.facts.addRow(row, name, shown);
            return row + 1;
        }
    }
}
