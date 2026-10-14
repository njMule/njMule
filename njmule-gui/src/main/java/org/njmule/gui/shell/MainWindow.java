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
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import org.kordamp.ikonli.Ikon;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.material2.Material2AL;
import org.kordamp.ikonli.material2.Material2MZ;
import org.njmule.core.api.EngineStatus;
import org.njmule.core.api.KadInfo;
import org.njmule.core.api.NjMuleInfo;
import org.njmule.core.config.Preferences;
import org.njmule.gui.dialogs.AboutDialog;
import org.njmule.gui.dialogs.ImportDialog;
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
import org.njmule.gui.ui.Fx;
import org.njmule.gui.ui.Gui;
import org.njmule.gui.ui.Screen;

/**
 * The window's content: the top bar with the connection and the rates, the navigation rail, the current screen, the
 * status strip, and messages floating over it all.
 */
public final class MainWindow {

    private static final Duration MESSAGE_TIME = Duration.seconds(6);
    private static final List<KeyCode> DIGITS = List.of(
            KeyCode.DIGIT1,
            KeyCode.DIGIT2,
            KeyCode.DIGIT3,
            KeyCode.DIGIT4,
            KeyCode.DIGIT5,
            KeyCode.DIGIT6,
            KeyCode.DIGIT7,
            KeyCode.DIGIT8);

    private final Gui gui;
    private final Map<String, Screen> screens = new LinkedHashMap<>();
    private final Map<String, ToggleButton> rail = new LinkedHashMap<>();
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
    private Consumer<String> elsewhere = text -> {};

    public MainWindow(Gui gui) {
        this.gui = gui;
        for (Screen screen : List.of(
                new ServersScreen(gui),
                new KadScreen(gui),
                new TransfersScreen(gui),
                new SearchScreen(gui),
                new SharedScreen(gui),
                new MessagesScreen(gui),
                new StatisticsScreen(gui),
                new SettingsScreen(gui))) {
            this.screens.put(screen.id(), screen);
        }
        BorderPane frame = new BorderPane(this.content, this.topBar(), null, this.statusStrip(), this.navRail());
        this.messages.setAlignment(Pos.TOP_RIGHT);
        this.messages.setPickOnBounds(false);
        this.messages.setPadding(new Insets(56, 16, 16, 16));
        this.messages.setMaxWidth(420);
        StackPane.setAlignment(this.messages, Pos.TOP_RIGHT);
        this.root = new StackPane(frame, this.messages);
        this.root.getStyleClass().add("njmule");
        // Ctrl+1 ... Ctrl+8 (Cmd on macOS) open the screens in rail order.
        this.root.setOnKeyPressed(e -> {
            int index = DIGITS.indexOf(e.getCode());
            List<String> ids = List.copyOf(this.screens.keySet());
            if (e.isShortcutDown() && index >= 0 && index < ids.size()) {
                this.show(ids.get(index));
                e.consume();
            }
        });
        EngineModel model = gui.model();
        gui.subscriptions().onChange(model.connectionProperty(), (was, now) -> this.showConnection());
        gui.subscriptions().onChange(model.serverNameProperty(), (was, now) -> this.showConnection());
        gui.subscriptions().onChange(model.statusProperty(), (was, now) -> this.showStatus(now));
        this.lastLog.textProperty().bind(model.lastLogProperty());
        model.onCompleted(d -> {
            this.message(gui.f("nj.transfers.completed", d.name()));
            this.chime();
        });
        model.chats().onMessage(chat -> {
            if (!this.current.equals("messages")) {
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
        this.elsewhere.accept(text);
        Notification note = new Notification(text, new FontIcon(Material2AL.INFO));
        note.getStyleClass().add("elevated-1");
        note.setPrefWidth(380);
        note.setMaxWidth(380);
        note.setOnClose(e -> this.messages.getChildren().remove(note));
        this.messages.getChildren().add(note);
        PauseTransition later = new PauseTransition(MESSAGE_TIME);
        later.setOnFinished(e -> this.messages.getChildren().remove(note));
        later.play();
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
        this.content.getChildren().setAll(screen.view());
        this.rail.get(id).setSelected(true);
        screen.shown();
        this.gui.settings().setScreen(id);
    }

    public String current() {
        return this.current;
    }

    /**
     * The smallest size of the content at which every screen still shows all its buttons, laid out once for each
     * screen; FX thread, with the window shown.
     */
    public Dimension2D minimumSize() {
        Screen shown = this.screens.get(this.current);
        double width = 0;
        double height = 0;
        for (Screen screen : this.screens.values()) {
            this.content.getChildren().setAll(screen.view());
            this.root.applyCss();
            this.root.layout();
            width = Math.max(width, this.root.minWidth(-1));
            height = Math.max(height, this.root.minHeight(-1));
        }
        if (shown != null) {
            this.content.getChildren().setAll(shown.view());
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
        this.connect.getStyleClass().add("accent");
        Button theme = Fx.iconButton(this.gui.t("nj.top.theme"), Material2AL.BRIGHTNESS_MEDIUM, this::toggleTheme);
        MenuItem importer = new MenuItem(this.gui.t("nj.import.title"), new FontIcon(Material2AL.INPUT));
        importer.setOnAction(
                e -> new ImportDialog(this.gui, this.root.getScene().getWindow()).show());
        MenuItem about = new MenuItem(this.gui.t("nj.about.title"), new FontIcon(Material2AL.INFO));
        about.setOnAction(e -> new AboutDialog(this.gui, this.root.getScene().getWindow()).show());
        theme.setId("theme");
        importer.setId("import");
        about.setId("about");
        MenuButton tools = new MenuButton(this.gui.t("nj.top.tools"), new FontIcon(Material2AL.BUILD), importer, about);
        for (Region whole : List.of(this.connect, this.ed2k, this.kad, this.down, this.up, tools)) {
            Fx.whole(whole);
        }
        HBox bar = new HBox(this.connect, this.ed2k, this.kad, Fx.spacer(), this.down, this.up, tools, theme);
        bar.getStyleClass().add("top-bar");
        return bar;
    }

    private Node navRail() {
        ToggleGroup group = new ToggleGroup();
        VBox box = new VBox();
        box.getStyleClass().add("nav-rail");
        for (Screen screen : this.screens.values()) {
            ToggleButton button = new ToggleButton(screen.title(), new FontIcon(screen.icon()));
            button.setToggleGroup(group);
            button.setOnAction(e -> {
                // A rail button stays selected while its screen shows.
                this.show(screen.id());
            });
            button.setMaxWidth(Double.MAX_VALUE);
            this.rail.put(screen.id(), button);
            box.getChildren().add(button);
        }
        return box;
    }

    private Node statusStrip() {
        HBox.setHgrow(this.lastLog, Priority.ALWAYS);
        this.lastLog.setMaxWidth(Double.MAX_VALUE);
        Label version = new Label(NjMuleInfo.NAME + " " + NjMuleInfo.version());
        HBox strip = new HBox(this.lastLog, this.server, this.counts, version);
        strip.getStyleClass().add("status-strip");
        return strip;
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
        String name = this.gui.model().serverNameProperty().get();
        this.connect.setText(
                switch (state) {
                    case DISCONNECTED -> this.gui.t("IDS_MAIN_BTN_CONNECT");
                    case CONNECTING -> this.gui.t("IDS_MAIN_BTN_CANCEL");
                    case CONNECTED -> this.gui.t("IDS_MAIN_BTN_DISCONNECT");
                });
        this.connect.setGraphic(new FontIcon(
                state == EngineModel.Connection.DISCONNECTED ? Material2AL.LINK : Material2MZ.POWER_SETTINGS_NEW));
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
    }

    private void showStatus(EngineStatus status) {
        var formats = this.gui.formats();
        this.down.setText(formats.rate(status.downloadBytesPerSecond()));
        this.up.setText(formats.rate(status.uploadBytesPerSecond()));
        if (this.gui.model().connectionProperty().get() == EngineModel.Connection.CONNECTED) {
            this.state(
                    this.ed2k,
                    "eD2K: "
                            + this.gui.model().serverNameProperty().get()
                            + (status.lowId() ? " (LowID)" : status.clientId() != 0 ? " (HighID)" : ""),
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

    private static Label rate(Ikon arrow) {
        Label label = new Label("", new FontIcon(arrow));
        label.getStyleClass().add("rate-label");
        return label;
    }
}
