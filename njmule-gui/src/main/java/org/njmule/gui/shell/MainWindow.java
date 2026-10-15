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
package org.njmule.gui.shell;

import atlantafx.base.controls.Notification;
import atlantafx.base.controls.Popover;
import atlantafx.base.theme.Styles;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import javafx.animation.PauseTransition;
import javafx.geometry.Dimension2D;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.input.Dragboard;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;
import javafx.stage.Stage;
import javafx.util.Duration;
import org.kordamp.ikonli.Ikon;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.material2.Material2AL;
import org.kordamp.ikonli.material2.Material2MZ;
import org.njmule.core.api.DownloadInfo;
import org.njmule.core.api.Ed2kLink;
import org.njmule.core.api.EngineStatus;
import org.njmule.core.api.KadInfo;
import org.njmule.core.config.Preferences;
import org.njmule.gui.dialogs.AboutDialog;
import org.njmule.gui.dialogs.ImportDialog;
import org.njmule.gui.infra.Folders;
import org.njmule.gui.infra.GuiSettings;
import org.njmule.gui.model.EngineModel;
import org.njmule.gui.screens.KadScreen;
import org.njmule.gui.screens.MessagesScreen;
import org.njmule.gui.screens.SearchScreen;
import org.njmule.gui.screens.ServersScreen;
import org.njmule.gui.screens.SettingsScreen;
import org.njmule.gui.screens.SharedScreen;
import org.njmule.gui.screens.StatisticsScreen;
import org.njmule.gui.screens.TransfersScreen;
import org.njmule.gui.ui.ClipboardWatch;
import org.njmule.gui.ui.Fx;
import org.njmule.gui.ui.Gui;
import org.njmule.gui.ui.Navigate;
import org.njmule.gui.ui.Screen;

/**
 * The window's content: the top bar with the connection and the rates, the navigation rail, the current screen, the
 * status strip, and messages floating over it all.
 */
public final class MainWindow {

    private static final Duration MESSAGE_TIME = Duration.seconds(6);
    // More messages at once push the oldest out: the stack never covers the screen.
    private static final int MAX_MESSAGES = 4;
    private static final List<KeyCode> DIGITS = List.of(
            KeyCode.DIGIT1,
            KeyCode.DIGIT2,
            KeyCode.DIGIT3,
            KeyCode.DIGIT4,
            KeyCode.DIGIT5,
            KeyCode.DIGIT6,
            KeyCode.DIGIT7);
    private static final String NETWORK = "network";
    // The places of the rail, in its order: what one does first comes first, the network and the
    // statistics after, the options at the bottom.
    private static final List<String> PLACES =
            List.of("transfers", "search", "shared", "messages", NETWORK, "statistics", "settings");
    private static final int MAX_BADGE = 99;

    private final Gui gui;
    private final Map<String, Screen> screens = new LinkedHashMap<>();
    private final Map<String, ToggleButton> rail = new LinkedHashMap<>();
    private final NetworkPane network;
    private final ServersScreen servers;
    private final Banner banner;
    private SpeedLimits limits;
    private Popover limitsPopover;
    private final Label activeBadge = badge();
    private final Label unreadBadge = badge();
    private final Circle networkDot = new Circle(4);
    private final StackPane content = new StackPane();
    private final VBox messages = new VBox(8);
    private final StackPane root;
    private final Button connect = new Button();
    private final Label ed2k = chip();
    private final Label kad = chip();
    private final Label down = rate(Material2AL.ARROW_DOWNWARD);
    private final Label up = rate(Material2AL.ARROW_UPWARD);
    private final Label lastLog = new Label();
    private final Label server = new Label();
    private final Label counts = new Label();
    private String current = "";
    private String lastNetwork = "servers";
    private int unread;
    private Consumer<String> elsewhere = text -> {};

    public MainWindow(Gui gui) {
        this.gui = gui;
        ServersScreen servers = new ServersScreen(gui);
        this.servers = servers;
        KadScreen kadScreen = new KadScreen(gui);
        for (Screen screen : List.of(
                new TransfersScreen(gui),
                new SearchScreen(gui),
                new SharedScreen(gui),
                new MessagesScreen(gui),
                servers,
                kadScreen,
                new StatisticsScreen(gui),
                new SettingsScreen(gui))) {
            this.screens.put(screen.id(), screen);
        }
        this.network = new NetworkPane(gui.t("IDS_NETWORK"), List.of(servers, kadScreen), this::show);
        this.banner = new Banner(gui, this::connectIfOff, () -> this.show("settings"));
        VBox center = new VBox(this.banner.view(), this.content);
        VBox.setVgrow(this.content, Priority.ALWAYS);
        BorderPane frame = new BorderPane(center, this.topBar(), null, this.statusStrip(), this.navRail());
        // Messages stack in the bottom corner, above the status strip: never over the tool rows.
        this.messages.setAlignment(Pos.BOTTOM_RIGHT);
        this.messages.setPickOnBounds(false);
        this.messages.setPadding(new Insets(16, 16, 40, 16));
        this.messages.setMaxWidth(420);
        this.messages.setMaxHeight(Region.USE_PREF_SIZE);
        StackPane.setAlignment(this.messages, Pos.BOTTOM_RIGHT);
        this.root = new StackPane(frame, this.messages);
        this.root.getStyleClass().add("njmule");
        // A screen's button can lead to another screen, such as Search from an empty list of downloads.
        this.root.addEventHandler(Navigate.TO_SCREEN, e -> this.show(e.screen()));
        // eD2K links dragged from a page or a text onto the window become downloads.
        this.root.setOnDragOver(e -> {
            if (!ClipboardWatch.fileLinks(dragged(e.getDragboard())).isEmpty()) {
                e.acceptTransferModes(TransferMode.COPY_OR_MOVE);
            }
            e.consume();
        });
        this.root.setOnDragDropped(e -> {
            e.setDropCompleted(this.dropped(dragged(e.getDragboard())) > 0);
            e.consume();
        });
        this.root.setNodeOrientation(gui.orientation());
        // Ctrl+1 ... Ctrl+7 (Cmd on macOS) open the places in rail order; F1 lists the shortcuts.
        this.root.setOnKeyPressed(e -> {
            int index = DIGITS.indexOf(e.getCode());
            if (e.isShortcutDown() && index >= 0 && index < PLACES.size()) {
                this.open(PLACES.get(index));
                e.consume();
            } else if (e.getCode() == KeyCode.F1) {
                this.showShortcuts();
                e.consume();
            }
        });
        EngineModel model = gui.model();
        gui.subscriptions().onChange(model.connectionProperty(), (was, now) -> this.showConnection());
        gui.subscriptions().onChange(model.serverNameProperty(), (was, now) -> this.showConnection());
        gui.subscriptions().onChange(model.statusProperty(), (was, now) -> this.showStatus(now));
        this.lastLog.textProperty().bind(model.lastLogProperty());
        model.onCompleted(d -> {
            this.success(gui.f("nj.transfers.completed", d.name()));
            this.chime();
        });
        gui.subscriptions().onListChange(model.downloads().rows(), c -> this.showActive());
        model.chats().onMessage(chat -> {
            if (!this.current.equals("messages")) {
                this.unread++;
                this.showUnread();
                String who = chat.nickProperty().get().isEmpty()
                        ? chat.address()
                        : chat.nickProperty().get();
                this.message(gui.f("nj.chat.from", who));
                this.chime();
            }
        });
        this.showConnection();
        this.showStatus(model.statusProperty().get());
        this.show(
                this.screens.containsKey(gui.settings().screen())
                        ? gui.settings().screen()
                        : "transfers");
    }

    public Parent root() {
        return this.root;
    }

    /** Where messages also go while the window is hidden, such as the notification area. */
    public void whileHidden(Consumer<String> elsewhere) {
        this.elsewhere = Objects.requireNonNull(elsewhere, "elsewhere");
    }

    /** Shows a short message for a few seconds. */
    public void message(String text) {
        this.show(text, new FontIcon(Material2AL.INFO), null, true);
    }

    /** Shows for a few seconds that something went well, such as a finished download. */
    public void success(String text) {
        this.show(text, new FontIcon(Material2AL.CHECK_CIRCLE_OUTLINE), Styles.SUCCESS, true);
    }

    /** Shows a problem, which stays until the user closes it. */
    public void problem(String text) {
        this.show(text, new FontIcon(Material2AL.ERROR_OUTLINE), Styles.DANGER, false);
    }

    private void show(String text, FontIcon icon, String style, boolean fades) {
        this.elsewhere.accept(text);
        Notification note = new Notification(text, icon);
        note.getStyleClass().add("elevated-1");
        if (style != null) {
            note.getStyleClass().add(style);
        }
        note.setPrefWidth(380);
        note.setMaxWidth(380);
        note.setOnClose(e -> this.messages.getChildren().remove(note));
        this.messages.getChildren().add(note);
        if (this.messages.getChildren().size() > MAX_MESSAGES) {
            this.messages.getChildren().removeFirst();
        }
        if (fades) {
            PauseTransition later = new PauseTransition(MESSAGE_TIME);
            later.setOnFinished(e -> this.messages.getChildren().remove(note));
            later.play();
        }
    }

    // The sound of a notification of an event, when the preferences ask for one; not for the replies to the user.
    private void chime() {
        Preferences preferences = this.gui.model().preferencesProperty().get();
        if (preferences != null && preferences.notificationSound()) {
            preferences.notificationSoundFile().ifPresent(this.gui.sound()::play);
        }
    }

    /** The window closes for good: each screen keeps what it keeps between runs. */
    public void closing() {
        this.screens.values().forEach(Screen::closing);
    }

    /** Switches to the screen {@code id}. */
    public void show(String id) {
        Screen screen = this.screens.get(id);
        if (screen == null) {
            return;
        }
        this.current = id;
        this.content.getChildren().setAll(this.viewOf(id));
        this.rail.get(this.placeOf(id)).setSelected(true);
        if (this.network.holds(id)) {
            this.lastNetwork = id;
        }
        if (id.equals("messages")) {
            this.unread = 0;
            this.showUnread();
        }
        screen.shown();
        this.gui.settings().setScreen(id);
    }

    // A place of the rail: a screen, or the network at the part last shown.
    private void open(String place) {
        this.show(place.equals(NETWORK) ? this.lastNetwork : place);
    }

    private String placeOf(String id) {
        return this.network.holds(id) ? NETWORK : id;
    }

    private Node viewOf(String id) {
        return this.network.holds(id)
                ? this.network.view(id)
                : this.screens.get(id).view();
    }

    public String current() {
        return this.current;
    }

    /**
     * The smallest size of the content at which every screen still shows all its buttons, laid out once for each
     * screen; FX thread, with the window shown.
     */
    public Dimension2D minimumSize() {
        double width = 0;
        double height = 0;
        for (String id : this.screens.keySet()) {
            this.content.getChildren().setAll(this.viewOf(id));
            this.root.applyCss();
            this.root.layout();
            width = Math.max(width, this.root.minWidth(-1));
            height = Math.max(height, this.root.minHeight(-1));
        }
        if (this.screens.containsKey(this.current)) {
            this.content.getChildren().setAll(this.viewOf(this.current));
        }
        return new Dimension2D(Math.ceil(width), Math.ceil(height));
    }

    /** The ids of the screens, in rail order. */
    public List<String> screenIds() {
        return List.copyOf(this.screens.keySet());
    }

    /** The screen {@code id}, for tests. */
    public Optional<Screen> screen(String id) {
        return Optional.ofNullable(this.screens.get(id));
    }

    List<Node> messages() {
        return this.messages.getChildren();
    }

    Button connectButton() {
        return this.connect;
    }

    private Node topBar() {
        this.connect.setId("connect");
        this.connect.setOnAction(e -> this.toggleConnection());
        Button theme = Fx.iconButton(this.gui.t("nj.top.theme"), Material2AL.BRIGHTNESS_MEDIUM, this::toggleTheme);
        MenuItem importer = new MenuItem(this.gui.t("nj.import.title"), Fx.icon(Material2AL.INPUT));
        importer.setOnAction(
                e -> new ImportDialog(this.gui, this.root.getScene().getWindow()).show());
        MenuItem about = new MenuItem(this.gui.t("nj.about.title"), new FontIcon(Material2AL.INFO));
        about.setOnAction(e -> new AboutDialog(this.gui, this.root.getScene().getWindow()).show());
        theme.setId("theme");
        importer.setId("import");
        about.setId("about");
        MenuItem incoming = new MenuItem(this.gui.t("IDS_OPENINC"), Fx.icon(Material2AL.FOLDER_OPEN));
        incoming.setOnAction(e -> this.openIncoming());
        incoming.setId("incoming");
        MenuItem options = new MenuItem(this.gui.t("IDS_EM_PREFS"), Fx.icon(Material2MZ.SETTINGS));
        options.setOnAction(e -> this.show("settings"));
        options.setId("options");
        MenuItem shortcuts = new MenuItem(this.gui.t("nj.shortcuts.title"), Fx.icon(Material2AL.KEYBOARD));
        shortcuts.setAccelerator(new KeyCodeCombination(KeyCode.F1));
        shortcuts.setOnAction(e -> this.showShortcuts());
        shortcuts.setId("shortcuts");
        MenuButton tools = new MenuButton(
                this.gui.t("nj.top.tools"),
                new FontIcon(Material2AL.BUILD),
                incoming,
                options,
                new SeparatorMenuItem(),
                importer,
                shortcuts,
                about);
        // The rates open the limits: what one wants to change on seeing them.
        Button rates = new Button(null, new HBox(10, this.down, this.up));
        rates.setId("rates");
        rates.getStyleClass().addAll(Styles.FLAT, "rates");
        rates.setTooltip(new Tooltip(this.gui.t("nj.tray.speed")));
        rates.setAccessibleText(this.gui.t("nj.tray.speed"));
        rates.setOnAction(e -> this.showLimits(rates));
        for (Region whole : List.of(this.connect, this.ed2k, this.kad, this.down, this.up, rates, tools)) {
            Fx.whole(whole);
        }
        // The chips open the network, at the part they tell of.
        this.opens(this.ed2k, "servers");
        this.opens(this.kad, "kad");
        HBox bar = new HBox(this.connect, this.ed2k, this.kad, Fx.spacer(), rates, tools, theme);
        bar.getStyleClass().add("top-bar");
        return bar;
    }

    private Node navRail() {
        ToggleGroup group = new ToggleGroup();
        VBox box = new VBox();
        box.getStyleClass().add("nav-rail");
        for (String place : PLACES) {
            if (place.equals(NETWORK)) {
                Region line = new Region();
                line.getStyleClass().add("rail-separator");
                VBox.setMargin(line, new Insets(6, 10, 6, 10));
                box.getChildren().add(line);
            }
            if (place.equals("settings")) {
                // The options keep to the bottom, apart from the places of every day.
                Region grow = new Region();
                VBox.setVgrow(grow, Priority.ALWAYS);
                box.getChildren().add(grow);
            }
            boolean isNetwork = place.equals(NETWORK);
            String title = isNetwork
                    ? this.gui.t("IDS_NETWORK")
                    : this.screens.get(place).title();
            Node icon = isNetwork
                    ? NetworkPane.icon()
                    : new FontIcon(this.screens.get(place).icon());
            Node mark = switch (place) {
                case "transfers" -> this.activeBadge;
                case "messages" -> this.unreadBadge;
                case NETWORK -> this.networkDot;
                default -> null;
            };
            StackPane graphic = mark == null ? new StackPane(icon) : new StackPane(icon, mark);
            graphic.getStyleClass().add("rail-graphic");
            if (mark != null) {
                StackPane.setAlignment(mark, Pos.TOP_RIGHT);
            }
            ToggleButton button = new ToggleButton(title, graphic);
            // The tooltip tells the shortcut of the place, as menus show theirs.
            button.setTooltip(new Tooltip(title + "  " + shortcut(PLACES.indexOf(place))));
            button.setToggleGroup(group);
            button.setId("rail-" + place);
            // A rail button stays selected while its place shows.
            button.setOnAction(e -> this.open(place));
            button.setMaxWidth(Double.MAX_VALUE);
            this.rail.put(place, button);
            box.getChildren().add(button);
        }
        this.networkDot.getStyleClass().add("network-dot");
        this.showActive();
        this.showUnread();
        return box;
    }

    // The downloads that run now, on the transfers' place.
    private void showActive() {
        long active = this.gui.model().downloads().rows().stream()
                .filter(r -> r.get().status() == DownloadInfo.Status.DOWNLOADING)
                .count();
        this.count(this.activeBadge, this.rail.get("transfers"), active, "nj.rail.active");
    }

    // The messages that came while the messages did not show, on their place.
    private void showUnread() {
        this.count(this.unreadBadge, this.rail.get("messages"), this.unread, "nj.rail.unread");
    }

    private void count(Label badge, ToggleButton button, long value, String key) {
        badge.setText(value > MAX_BADGE ? MAX_BADGE + "+" : Long.toString(value));
        badge.setVisible(value > 0);
        if (button != null) {
            // The number is said too, not only shown.
            button.setAccessibleHelp(value > 0 ? this.gui.f(key, value) : null);
        }
    }

    private Node statusStrip() {
        HBox.setHgrow(this.lastLog, Priority.ALWAYS);
        this.lastLog.setMaxWidth(Double.MAX_VALUE);
        Fx.leftToRight(this.lastLog);
        // The last line of the log opens the whole log; the version is in About and in the title.
        this.lastLog.setOnMouseClicked(e -> {
            this.show("servers");
            this.servers.showLog();
        });
        this.lastLog.setCursor(Cursor.HAND);
        this.lastLog.setTooltip(new Tooltip(this.gui.t("IDS_SV_LOG")));
        HBox strip = new HBox(this.lastLog, this.server, this.counts);
        strip.getStyleClass().add("status-strip");
        return strip;
    }

    /** Adds the eD2K file links in {@code text} as downloads, telling each one added; returns how many it found. */
    int dropped(String text) {
        List<Ed2kLink.FileLink> links = ClipboardWatch.fileLinks(text);
        for (Ed2kLink.FileLink link : links) {
            this.gui.model().addDownload(link, added -> this.message(this.gui.f("nj.transfers.added", added.name())));
        }
        return links.size();
    }

    private static String dragged(Dragboard board) {
        if (board.hasString()) {
            return board.getString();
        }
        return board.hasUrl() ? board.getUrl() : "";
    }

    // The banner's way out of being offline: what Connect does then.
    private void connectIfOff() {
        if (this.gui.model().connectionProperty().get() == EngineModel.Connection.DISCONNECTED) {
            this.toggleConnection();
        }
    }

    /** The banner, for tests. */
    Banner banner() {
        return this.banner;
    }

    // Connect and Disconnect act on both networks; Cancel only stops trying servers.
    private void toggleConnection() {
        EngineModel model = this.gui.model();
        boolean kadRuns = model.statusProperty().get().kad().isPresent();
        switch (model.connectionProperty().get()) {
            case DISCONNECTED -> {
                model.connect(Optional.empty());
                Preferences preferences = model.preferencesProperty().get();
                if (!kadRuns && preferences != null && preferences.kadEnabled()) {
                    model.connectKad();
                }
            }
            case CONNECTING -> model.disconnect();
            case CONNECTED -> {
                model.disconnect();
                if (kadRuns) {
                    model.disconnectKad();
                }
            }
        }
    }

    private void toggleTheme() {
        boolean dark = this.gui.themes().darkProperty().get();
        GuiSettings.Mode mode = dark ? GuiSettings.Mode.LIGHT : GuiSettings.Mode.DARK;
        this.gui.themes().set(this.gui.themes().family(), mode);
        this.gui.settings().setMode(mode);
    }

    private void showConnection() {
        EngineModel.Connection state = this.gui.model().connectionProperty().get();
        String name =
                this.gui.i18n().embed(this.gui.model().serverNameProperty().get());
        this.connect.setText(
                switch (state) {
                    case DISCONNECTED -> this.gui.t("IDS_MAIN_BTN_CONNECT");
                    case CONNECTING -> this.gui.t("IDS_MAIN_BTN_CANCEL");
                    case CONNECTED -> this.gui.t("IDS_MAIN_BTN_DISCONNECT");
                });
        this.connect.setGraphic(new FontIcon(
                state == EngineModel.Connection.DISCONNECTED ? Material2AL.LINK : Material2MZ.POWER_SETTINGS_NEW));
        // Connecting is what to do while disconnected: only then does the button take the accent.
        this.connect.getStyleClass().remove("accent");
        if (state == EngineModel.Connection.DISCONNECTED) {
            this.connect.getStyleClass().add("accent");
        }
        String text = switch (state) {
            case DISCONNECTED -> this.gui.t("IDS_NOTCONNECTED");
            case CONNECTING -> this.gui.t("IDS_CONNECTING") + " " + name;
            case CONNECTED -> name;
        };
        this.state(
                this.ed2k,
                "eD2K: " + text,
                state == EngineModel.Connection.CONNECTED,
                state == EngineModel.Connection.CONNECTING);
        this.showNetwork();
        this.banner.update(state, this.gui.model().statusProperty().get());
    }

    private void showStatus(EngineStatus status) {
        var formats = this.gui.formats();
        this.down.setText(formats.rate(status.downloadBytesPerSecond()));
        this.up.setText(formats.rate(status.uploadBytesPerSecond()));
        if (this.gui.model().connectionProperty().get() == EngineModel.Connection.CONNECTED) {
            this.state(
                    this.ed2k,
                    "eD2K: "
                            + this.gui
                                    .i18n()
                                    .embed(this.gui.model().serverNameProperty().get()
                                            + (status.lowId()
                                                    ? " (LowID)"
                                                    : status.clientId() != 0 ? " (HighID)" : "")),
                    true,
                    false);
        }
        Optional<KadInfo> kadInfo = status.kad();
        if (kadInfo.isEmpty()) {
            this.state(this.kad, "Kad: " + this.gui.t("nj.kad.off"), false, false);
        } else if (!kadInfo.get().connected()) {
            this.state(this.kad, "Kad: " + this.gui.t("IDS_NOTCONNECTED"), false, true);
        } else {
            KadInfo k = kadInfo.get();
            boolean firewalled = k.firewalled();
            // Behind a TCP firewall, whether UDP gets through tells if others still reach us, once tested.
            String udp = firewalled && (k.udpVerified() || k.udpFirewalled())
                    ? ", " + this.gui.t(k.udpFirewalled() ? "nj.kad.udpFirewalled" : "nj.kad.udpOpen")
                    : "";
            this.state(
                    this.kad,
                    "Kad: " + (firewalled ? this.gui.t("IDS_FIREWALLED") : this.gui.t("IDS_KADOPEN")) + udp,
                    !firewalled,
                    firewalled);
        }
        this.server.setText(status.server()
                .map(s -> this.gui.f("nj.status.server", formats.count(s.users()), formats.count(s.files())))
                .orElse(""));
        this.counts.setText(this.gui.f(
                "nj.status.counts", status.downloads(), status.uploads(), status.queued(), status.sharedFiles()));
        this.showNetwork();
        this.showActive();
        this.banner.update(this.gui.model().connectionProperty().get(), status);
    }

    // The network's place tells at a glance whether either network is up; its tooltip says which.
    private void showNetwork() {
        EngineModel.Connection connection =
                this.gui.model().connectionProperty().get();
        Optional<KadInfo> kadInfo = this.gui.model().statusProperty().get().kad();
        boolean kadUp = kadInfo.isPresent() && kadInfo.get().connected();
        this.networkDot.getStyleClass().removeAll("connected", "connecting");
        if (connection == EngineModel.Connection.CONNECTED || kadUp) {
            this.networkDot.getStyleClass().add("connected");
        } else if (connection == EngineModel.Connection.CONNECTING || kadInfo.isPresent()) {
            this.networkDot.getStyleClass().add("connecting");
        }
        ToggleButton button = this.rail.get(NETWORK);
        if (button != null) {
            String state = this.ed2k.getText() + "\n" + this.kad.getText();
            button.setTooltip(new Tooltip(state + "\n" + shortcut(PLACES.indexOf(NETWORK))));
            button.setAccessibleHelp(state);
        }
    }

    private void state(Label chip, String text, boolean on, boolean partly) {
        chip.setText(text);
        chip.getStyleClass().removeAll("connected", "connecting");
        if (on) {
            chip.getStyleClass().add("connected");
        } else if (partly) {
            chip.getStyleClass().add("connecting");
        }
    }

    Label ed2kChip() {
        return this.ed2k;
    }

    Label kadChip() {
        return this.kad;
    }

    private static Label chip() {
        Label label = new Label("", new FontIcon(Material2AL.FIBER_MANUAL_RECORD));
        label.getStyleClass().add("connection-chip");
        return label;
    }

    // The limits under the rates, as in the notification area's menu, with the same two presets.
    private void showLimits(Node anchor) {
        if (this.limitsPopover == null) {
            this.limits = new SpeedLimits(this.gui);
            Button full = Fx.button(
                    this.gui.t("IDS_PW_UA"),
                    Material2MZ.SPEED,
                    () -> this.limits.set(p -> p.downloadCapacityKiBps(), p -> p.uploadCapacityKiBps()));
            Button slow = Fx.button(
                    this.gui.t("IDS_PW_PA"), Material2MZ.SLOW_MOTION_VIDEO, () -> this.limits.set(p -> 1, p -> 1));
            VBox box = new VBox(10, this.limits.view(), new HBox(8, full, slow));
            box.getStyleClass().add("limits-popover");
            this.limitsPopover = new Popover(box);
            this.limitsPopover.setTitle(this.gui.t("nj.tray.speed"));
            this.limitsPopover.setHeaderAlwaysVisible(true);
            this.limitsPopover.setArrowLocation(Popover.ArrowLocation.TOP_CENTER);
        }
        this.limits.load();
        this.limitsPopover.show(anchor);
    }

    private void opens(Label chip, String screen) {
        chip.setOnMouseClicked(e -> this.show(screen));
        chip.setCursor(Cursor.HAND);
        chip.setTooltip(new Tooltip(this.gui.t("IDS_NETWORK")));
    }

    // Opens the folder of finished downloads in the system's file manager.
    private void openIncoming() {
        Preferences preferences = this.gui.model().preferencesProperty().get();
        if (preferences == null) {
            return;
        }
        try {
            Folders.open(preferences.effectiveIncomingDir(this.gui.directories()));
        } catch (IOException e) {
            this.problem(e.getMessage());
        }
    }

    /** The shortcut of the place at {@code index} of the rail, as the platform writes it: Ctrl+1, or ⌘1. */
    static String shortcut(int index) {
        return new KeyCodeCombination(DIGITS.get(index), KeyCombination.SHORTCUT_DOWN).getDisplayText();
    }

    // The keys that do something, with what they do, in a window of their own.
    void showShortcuts() {
        GridPane grid = new GridPane();
        grid.getStyleClass().add("shortcuts");
        int row = 0;
        for (int i = 0; i < PLACES.size(); i++) {
            String place = PLACES.get(i);
            String title = place.equals(NETWORK)
                    ? this.gui.t("IDS_NETWORK")
                    : this.screens.get(place).title();
            row = shortcutRow(grid, row, shortcut(i), title);
        }
        String transfers = this.screens.get("transfers").title() + ": ";
        row = shortcutRow(
                grid,
                row,
                new KeyCodeCombination(KeyCode.F, KeyCombination.SHORTCUT_DOWN).getDisplayText(),
                transfers + this.gui.t("nj.transfers.filter"));
        row = shortcutRow(
                grid,
                row,
                new KeyCodeCombination(KeyCode.ENTER, KeyCombination.ALT_DOWN).getDisplayText(),
                transfers + this.gui.t("IDS_DETAILS"));
        shortcutRow(grid, row, new KeyCodeCombination(KeyCode.F1).getDisplayText(), this.gui.t("nj.shortcuts.title"));
        Stage stage = new Stage();
        stage.initOwner(this.root.getScene().getWindow());
        stage.setTitle(this.gui.t("nj.shortcuts.title"));
        Button close = Fx.button(this.gui.t("IDS_FD_CLOSE"), Material2AL.CLOSE, stage::close);
        close.setCancelButton(true);
        close.setDefaultButton(true);
        HBox buttons = new HBox(close);
        buttons.setAlignment(Pos.CENTER_RIGHT);
        VBox box = new VBox(14, grid, buttons);
        box.setPadding(new Insets(16));
        stage.setScene(this.gui.attach(new Scene(box)));
        stage.show();
    }

    private static int shortcutRow(GridPane grid, int row, String keys, String what) {
        Label key = Fx.leftToRight(new Label(keys));
        key.getStyleClass().add("shortcut-key");
        grid.addRow(row, key, new Label(what));
        return row + 1;
    }

    private static Label badge() {
        Label badge = new Label();
        badge.getStyleClass().add("rail-badge");
        badge.setVisible(false);
        return badge;
    }

    private static Label rate(Ikon arrow) {
        Label label = new Label("", new FontIcon(arrow));
        label.getStyleClass().add("rate-label");
        return label;
    }
}
