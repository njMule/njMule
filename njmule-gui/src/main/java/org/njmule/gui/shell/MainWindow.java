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

import atlantafx.base.controls.Popover;
import atlantafx.base.theme.Styles;
import atlantafx.base.theme.Tweaks;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.css.PseudoClass;
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
import javafx.stage.Stage;
import javafx.stage.Window;
import org.njmule.core.api.DownloadInfo;
import org.njmule.core.api.Ed2kLink;
import org.njmule.core.api.EngineStatus;
import org.njmule.core.api.KadInfo;
import org.njmule.core.api.NewVersion;
import org.njmule.core.api.ServerInfo;
import org.njmule.core.api.UpdateStatus;
import org.njmule.core.config.Preferences;
import org.njmule.gui.dialogs.AboutDialog;
import org.njmule.gui.dialogs.FirstRunWizard;
import org.njmule.gui.dialogs.ImportDialog;
import org.njmule.gui.dialogs.RemoteDialog;
import org.njmule.gui.dialogs.UpdateDialog;
import org.njmule.gui.infra.Folders;
import org.njmule.gui.infra.Formats;
import org.njmule.gui.infra.GuiSettings;
import org.njmule.gui.model.EngineModel;
import org.njmule.gui.model.LiveList;
import org.njmule.gui.screens.KadScreen;
import org.njmule.gui.screens.LogScreen;
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
import org.njmule.gui.ui.Icon;
import org.njmule.gui.ui.Icons;
import org.njmule.gui.ui.Navigate;
import org.njmule.gui.ui.Screen;

/**
 * The window's content: the sidebar of places, the top bar with the screen's title, the field to search or paste links,
 * the rates and the networks, the current screen, the status strip, and messages floating over it all.
 */
public final class MainWindow {

    private static final List<KeyCode> DIGITS = List.of(
            KeyCode.DIGIT1,
            KeyCode.DIGIT2,
            KeyCode.DIGIT3,
            KeyCode.DIGIT4,
            KeyCode.DIGIT5,
            KeyCode.DIGIT6,
            KeyCode.DIGIT7);
    private static final String NETWORK_PLACE = "network";
    // The places of the sidebar in groups: what one does first comes first, the network and the statistics after, the
    // options at the bottom.
    private static final List<List<String>> GROUPS = List.of(
            List.of("transfers", "search", "shared", "messages"),
            List.of(NETWORK_PLACE, "statistics"),
            List.of("settings"));
    private static final List<String> PLACES =
            GROUPS.stream().flatMap(List::stream).toList();
    // The free space of the incoming folder is looked at again after this long.
    private static final long SPACE_EVERY = TimeUnit.SECONDS.toNanos(10);
    private static final double FIELD_WIDTH = 200;
    private static final double FIELD_MAX_WIDTH = 460;
    private static final PseudoClass NARROW = PseudoClass.getPseudoClass("narrow");

    private final Gui gui;
    private final Map<String, Screen> screens = new LinkedHashMap<>();
    private final NetworkPane networkPane;
    private final NetworkCards networkCards;
    private final LogScreen log;
    private final Banner banner;
    private final CommandPalette palette;
    private final Sidebar sidebar;
    private final NetworkControl network;
    private final Toasts toasts = new Toasts();
    private SpeedLimits limits;
    private Popover limitsPopover;
    private final StackPane content = new StackPane();
    private final StackPane root;
    private final Label title = new Label();
    private final Label summary = new Label();
    private final StringProperty networkSummary = new SimpleStringProperty("");
    private final Label down = rate(Icons.ARROW_DOWN, "rate-down");
    private final Label up = rate(Icons.ARROW_UP, "rate-up");
    private final Button rates = new Button();
    private final Label lastLog = new Label();
    private final Label server = new Label();
    private final Button limitsLink = new Button();
    private final Label freeSpace = new Label();
    // A new version on its way: its download, then that it is ready; opens the updates.
    private final Button updateLink = new Button();
    private long spaceCheckedAt = System.nanoTime() - SPACE_EVERY;
    private String current = "";
    private String lastNetwork = "servers";
    private int unread;
    private Consumer<String> elsewhere = text -> {};

    public MainWindow(Gui gui) {
        this.gui = gui;
        ServersScreen servers = new ServersScreen(gui);
        KadScreen kadScreen = new KadScreen(gui);
        this.log = new LogScreen(gui);
        for (Screen screen : List.of(
                new TransfersScreen(gui),
                new SearchScreen(gui),
                new SharedScreen(gui),
                new MessagesScreen(gui),
                servers,
                kadScreen,
                this.log,
                new StatisticsScreen(gui),
                new SettingsScreen(gui))) {
            this.screens.put(screen.id(), screen);
        }
        this.networkCards = new NetworkCards(gui, this::show);
        this.networkPane = new NetworkPane(this.networkCards.view(), List.of(servers, kadScreen, this.log), this::show);
        this.banner = new Banner(gui, this::connectIfOff, () -> show("settings"));
        this.network = new NetworkControl(
                gui,
                this::toggleConnection,
                this::show,
                () -> open(NETWORK_PLACE),
                shortcut(PLACES.indexOf(NETWORK_PLACE)));
        this.sidebar = new Sidebar(
                gui,
                GROUPS.stream()
                        .map(group -> group.stream().map(this::place).toList())
                        .toList(),
                List.of("transfers", "messages"),
                NETWORK_PLACE,
                this::open);
        VBox center = new VBox(topBar(), this.banner.view(), this.content);
        VBox.setVgrow(this.content, Priority.ALWAYS);
        // The brand's orange along the top of the window, the sidebar beside the screen, the status strip under both.
        Region brand = new Region();
        brand.getStyleClass().add("brand-line");
        BorderPane frame = new BorderPane(center, brand, null, statusStrip(), this.sidebar.view());
        this.palette = new CommandPalette(this::commands, this::typedCommands, gui.t("nj.palette.prompt"));
        this.root = new StackPane(frame, this.toasts.view(), this.palette.view());
        this.root.getStyleClass().add("njmule");
        this.sidebar.follow(this.root.widthProperty(), gui.settings());
        this.network.compactWhile(this.sidebar.narrowProperty());
        // Narrow, the tools keep to their icons and the top bar to its essentials.
        this.sidebar.narrowProperty().addListener((o, was, now) -> {
            this.root.pseudoClassStateChanged(NARROW, now);
            showSummary();
        });
        this.root.pseudoClassStateChanged(NARROW, this.sidebar.narrowProperty().get());
        // A screen's button can lead to another screen, such as Search from an empty list of downloads.
        this.root.addEventHandler(Navigate.TO_SCREEN, e -> show(e.screen()));
        // eD2K links dragged from a page or a text onto the window become downloads.
        this.root.setOnDragOver(e -> {
            if (!ClipboardWatch.fileLinks(dragged(e.getDragboard())).isEmpty()) {
                e.acceptTransferModes(TransferMode.COPY_OR_MOVE);
            }
            e.consume();
        });
        this.root.setOnDragDropped(e -> {
            e.setDropCompleted(dropped(dragged(e.getDragboard())) > 0);
            e.consume();
        });
        this.root.setNodeOrientation(gui.orientation());
        // Ctrl+1 ... Ctrl+7 (Cmd on macOS) open the places in sidebar order, Ctrl+B folds the sidebar, Ctrl+K opens the
        // palette; F1 lists the shortcuts.
        this.root.setOnKeyPressed(e -> {
            int index = DIGITS.indexOf(e.getCode());
            if (e.isShortcutDown() && index >= 0 && index < PLACES.size()) {
                open(PLACES.get(index));
                e.consume();
            } else if (e.getCode() == KeyCode.F1) {
                showShortcuts();
                e.consume();
            } else if (e.isShortcutDown() && e.getCode() == KeyCode.K) {
                this.palette.show("");
                e.consume();
            } else if (e.isShortcutDown() && e.getCode() == KeyCode.B) {
                this.sidebar.toggle();
                e.consume();
            }
        });
        EngineModel model = gui.model();
        gui.subscriptions().onChange(model.connectionProperty(), (was, now) -> showConnection());
        gui.subscriptions().onChange(model.serverNameProperty(), (was, now) -> showConnection());
        gui.subscriptions().onChange(model.statusProperty(), (was, now) -> showStatus(now));
        gui.subscriptions().onChange(model.preferencesProperty(), (was, now) -> showLimitsText(now));
        this.lastLog.textProperty().bind(model.lastLogProperty());
        model.onCompleted(d -> {
            success(gui.f("nj.transfers.completed", d.name()));
            chime();
        });
        model.onNewVersion(v -> offer(gui.f("nj.update.available", v.version()), gui.t("nj.update.details"), () -> {
            new UpdateDialog(gui, this.root.getScene().getWindow()).show();
        }));
        model.onUpdateReady(this::updateReady);
        gui.subscriptions().onChange(model.updateProperty(), (was, now) -> showUpdate(now));
        gui.subscriptions().onListChange(model.downloads().rows(), c -> showActive());
        model.chats().onMessage(chat -> {
            if (!this.current.equals("messages")) {
                this.unread++;
                showUnread();
                String who = chat.nickProperty().get().isEmpty()
                        ? chat.address()
                        : chat.nickProperty().get();
                message(gui.f("nj.chat.from", who));
                chime();
            }
        });
        showActive();
        showUnread();
        showConnection();
        showStatus(model.statusProperty().get());
        showLimitsText(model.preferencesProperty().get());
        showUpdate(model.updateProperty().get());
        show(this.screens.containsKey(gui.settings().screen()) ? gui.settings().screen() : "transfers");
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
        this.toasts.show(text, Icons.INFO, null, true);
    }

    /** Shows for a few seconds that something went well, such as a finished download. */
    public void success(String text) {
        this.elsewhere.accept(text);
        this.toasts.show(text, Icons.CHECK_CIRCLE, Styles.SUCCESS, true);
    }

    /**
     * Shows a message with a button labelled {@code action} that runs {@code run}, for longer than a short message: an
     * undo has to be found in time.
     */
    public void offer(String text, String action, Runnable run) {
        this.elsewhere.accept(text);
        this.toasts.offer(text, action, run);
    }

    /** Shows a problem, which stays until the user closes it. */
    public void problem(String text) {
        this.elsewhere.accept(text);
        this.toasts.show(text, Icons.ERROR, Styles.DANGER, false);
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
        this.content.getChildren().setAll(viewOf(id));
        this.sidebar.select(placeOf(id));
        boolean inNetwork = this.networkPane.holds(id);
        if (inNetwork) {
            this.lastNetwork = id;
        }
        showHeading(id);
        if (id.equals("messages")) {
            this.unread = 0;
            showUnread();
        }
        screen.shown();
        this.gui.settings().setScreen(id);
    }

    // The title and summary of the screen {@code id}; the network's two parts share one and say both networks' state.
    private void showHeading(String id) {
        boolean inNetwork = this.networkPane.holds(id);
        this.title.setText(
                inNetwork ? this.gui.t("IDS_NETWORK") : this.screens.get(id).title());
        this.summary
                .textProperty()
                .bind(inNetwork ? this.networkSummary : this.screens.get(id).summary());
        showSummary();
    }

    // A place of the sidebar: a screen, or the network at the part last shown.
    private void open(String place) {
        show(place.equals(NETWORK_PLACE) ? this.lastNetwork : place);
    }

    private String placeOf(String id) {
        return this.networkPane.holds(id) ? NETWORK_PLACE : id;
    }

    private Node viewOf(String id) {
        return this.networkPane.holds(id)
                ? this.networkPane.view(id)
                : this.screens.get(id).view();
    }

    private Sidebar.Place place(String id) {
        int index = PLACES.indexOf(id);
        return id.equals(NETWORK_PLACE)
                ? new Sidebar.Place(id, this.gui.t("IDS_NETWORK"), Icons.NETWORK, shortcut(index))
                : new Sidebar.Place(
                        id, this.screens.get(id).title(), this.screens.get(id).icon(), shortcut(index));
    }

    public String current() {
        return this.current;
    }

    /**
     * The smallest size of the content at which every screen still shows all its buttons, laid out once for each screen
     * as in a narrow window: the sidebar folded, the tools as icons; FX thread, with the window shown. The window turns
     * narrow below the width the wide layout needs, so that no button is ever cut.
     */
    public Dimension2D minimumSize() {
        this.sidebar.measureAs(false);
        double wide = widest();
        this.sidebar.measureAs(true);
        double width = widest();
        double height = 0;
        for (String id : this.screens.keySet()) {
            this.content.getChildren().setAll(viewOf(id));
            showHeading(id);
            this.root.applyCss();
            this.root.layout();
            // At the width it has, a row that wraps takes fewer lines than at its narrowest.
            height = Math.max(height, this.root.minHeight(width));
        }
        // The banner comes and goes with the network: there is room for it too.
        if (!this.banner.view().isManaged()) {
            height += this.banner.view().prefHeight(-1);
        }
        this.sidebar.measureAs(null);
        this.sidebar.foldBelow(Math.ceil(wide));
        if (this.screens.containsKey(this.current)) {
            this.content.getChildren().setAll(viewOf(this.current));
            showHeading(this.current);
        }
        return new Dimension2D(Math.ceil(width), Math.ceil(height));
    }

    // The widest of the screens' smallest widths, as the window is laid out now.
    private double widest() {
        double width = 0;
        for (String id : this.screens.keySet()) {
            this.content.getChildren().setAll(viewOf(id));
            showHeading(id);
            this.root.applyCss();
            this.root.layout();
            width = Math.max(width, this.root.minWidth(-1));
        }
        return width;
    }

    /** The ids of the screens, in sidebar order. */
    public List<String> screenIds() {
        return List.copyOf(this.screens.keySet());
    }

    /** The screen {@code id}, for tests. */
    public Optional<Screen> screen(String id) {
        return Optional.ofNullable(this.screens.get(id));
    }

    List<Node> messages() {
        return this.toasts.shown();
    }

    /** What connects or disconnects both networks, in the network's menu. */
    Button connectButton() {
        return this.network.bothButton();
    }

    NetworkControl network() {
        return this.network;
    }

    Sidebar sidebar() {
        return this.sidebar;
    }

    private Node topBar() {
        this.title.getStyleClass().addAll("screen-title", "display");
        this.title.setMinWidth(Region.USE_PREF_SIZE);
        this.summary.getStyleClass().add("screen-summary");
        // The title is never cut; the summary gives way first.
        this.summary.setMinWidth(0);
        this.summary.textProperty().addListener((o, was, now) -> showSummary());
        VBox heading = new VBox(this.title, this.summary);
        heading.setAlignment(Pos.CENTER_LEFT);
        MenuItem importer = Fx.menuItem(
                this.gui.t("nj.import.title"),
                Icons.IMPORT,
                () -> new ImportDialog(this.gui, this.root.getScene().getWindow()).show());
        importer.setId("import");
        MenuItem remote = Fx.menuItem(
                this.gui.t("nj.remote.title"),
                Icons.LINK,
                () -> new RemoteDialog(this.gui, this.root.getScene().getWindow()).show());
        remote.setId("remote");
        MenuItem about = Fx.menuItem(
                this.gui.t("nj.about.title"),
                Icons.INFO,
                () -> new AboutDialog(this.gui, this.root.getScene().getWindow()).show());
        about.setId("about");
        MenuItem incoming = Fx.menuItem(this.gui.t("IDS_OPENINC"), Icons.FOLDER_OPEN, this::openIncoming);
        incoming.setId("incoming");
        MenuItem options = Fx.menuItem(this.gui.t("IDS_EM_PREFS"), Icons.OPTIONS, () -> show("settings"));
        options.setId("options");
        MenuItem theme = Fx.menuItem(this.gui.t("nj.top.theme"), Icons.THEME, this::toggleTheme);
        theme.setId("theme");
        MenuItem shortcuts = Fx.menuItem(this.gui.t("nj.shortcuts.title"), Icons.KEYBOARD, this::showShortcuts);
        shortcuts.setAccelerator(new KeyCodeCombination(KeyCode.F1));
        shortcuts.setId("shortcuts");
        MenuItem wizard = Fx.menuItem(this.gui.t("nj.wizard.title"), Icons.WIZARD, this::showWizard);
        wizard.setId("wizard");
        MenuItem updates = Fx.menuItem(
                this.gui.t("IDS_CHECK4UPDATE"),
                Icons.REFRESH,
                () -> UpdateDialog.checkNow(this.gui, this.root.getScene().getWindow()));
        updates.setId("updates");
        // What the window does besides its places, folded behind three dots.
        MenuButton more = new MenuButton(
                null,
                new Icon(Icons.MORE),
                incoming,
                options,
                wizard,
                importer,
                remote,
                new SeparatorMenuItem(),
                theme,
                shortcuts,
                updates,
                about);
        more.setId("more");
        more.getStyleClass().addAll(Styles.FLAT, Styles.BUTTON_ICON, Tweaks.NO_ARROW);
        more.setTooltip(new Tooltip(this.gui.t("IDS_MORE")));
        more.setAccessibleText(this.gui.t("IDS_MORE"));
        HBox bar =
                new HBox(heading, Fx.spacer(), universalField(), Fx.spacer(), ratesButton(), this.network.view(), more);
        bar.getStyleClass().add("top-bar");
        Fx.whole(more);
        return bar;
    }

    // Looks like a field and opens the palette in its place: a search of the network, eD2K links, or any command.
    private Node universalField() {
        String keys = new KeyCodeCombination(KeyCode.K, KeyCombination.SHORTCUT_DOWN).getDisplayText();
        String prompt = this.gui.t("nj.palette.prompt");
        Button field = new Button(prompt, new Icon(Icons.SEARCH));
        field.setId("find");
        field.getStyleClass().add("universal-field");
        field.setAlignment(Pos.CENTER_LEFT);
        field.setAccessibleText(prompt);
        field.setTooltip(new Tooltip(prompt + "  " + keys));
        field.setOnAction(e -> this.palette.show(""));
        // A letter typed on it starts the text of the palette.
        field.setOnKeyTyped(e -> {
            String typed = e.getCharacter();
            if (!typed.isBlank() && typed.codePointAt(0) >= ' ' && !e.isShortcutDown() && !e.isAltDown()) {
                this.palette.show(typed);
                e.consume();
            }
        });
        Label hint = Fx.leftToRight(new Label(keys));
        hint.getStyleClass().add("shortcut-key");
        hint.setMouseTransparent(true);
        StackPane box = new StackPane(field, hint);
        box.getStyleClass().add("universal-box");
        StackPane.setAlignment(hint, Pos.CENTER_RIGHT);
        StackPane.setMargin(hint, new Insets(0, 8, 0, 8));
        field.setMaxWidth(Double.MAX_VALUE);
        // In a narrow window it keeps to its icon and keys.
        Runnable fit = () -> {
            boolean narrow = this.sidebar.narrowProperty().get();
            field.setText(narrow ? keys : prompt);
            hint.setVisible(!narrow);
            field.pseudoClassStateChanged(NARROW, narrow);
            // Narrow, it is whole; wide, its prompt gives way first.
            field.setMinWidth(narrow ? Region.USE_PREF_SIZE : Region.USE_COMPUTED_SIZE);
            box.setPrefWidth(narrow ? Region.USE_COMPUTED_SIZE : FIELD_WIDTH);
            box.setMaxWidth(narrow ? Region.USE_PREF_SIZE : FIELD_MAX_WIDTH);
            HBox.setHgrow(box, narrow ? Priority.NEVER : Priority.ALWAYS);
        };
        this.sidebar.narrowProperty().addListener((o, was, now) -> fit.run());
        fit.run();
        return box;
    }

    // The rates with their last half minute drawn beside; they open the limits, what one wants to change on seeing
    // them.
    private Button ratesButton() {
        Sparkline spark = new Sparkline(this.gui.model().rates());
        VBox numbers = new VBox(this.down, this.up);
        numbers.setAlignment(Pos.CENTER_RIGHT);
        HBox graphic = new HBox(10, spark, numbers);
        graphic.setAlignment(Pos.CENTER);
        for (Node hidden : List.of(spark, this.up)) {
            hidden.visibleProperty().bind(this.sidebar.narrowProperty().not());
            hidden.managedProperty().bind(this.sidebar.narrowProperty().not());
        }
        this.rates.setGraphic(graphic);
        this.rates.setId("rates");
        this.rates.getStyleClass().addAll(Styles.FLAT, "rates");
        this.rates.setTooltip(new Tooltip(this.gui.t("nj.tray.speed")));
        this.rates.setOnAction(e -> showLimits(this.rates, Popover.ArrowLocation.TOP_CENTER));
        Fx.whole(this.rates);
        return this.rates;
    }

    // In a narrow window the title keeps to itself.
    private void showSummary() {
        boolean shown = !this.sidebar.narrowProperty().get()
                && this.summary.getText() != null
                && !this.summary.getText().isEmpty();
        this.summary.setVisible(shown);
        this.summary.setManaged(shown);
    }

    // The downloads that run now, on the transfers' place.
    private void showActive() {
        long active = this.gui.model().downloads().rows().stream()
                .filter(r -> r.get().status() == DownloadInfo.Status.DOWNLOADING)
                .count();
        this.sidebar.count("transfers", active, this.gui.f("nj.rail.active", active));
    }

    // The messages that came while the messages did not show, on their place.
    private void showUnread() {
        this.sidebar.count("messages", this.unread, this.gui.f("nj.rail.unread", this.unread));
    }

    private Node statusStrip() {
        HBox.setHgrow(this.lastLog, Priority.ALWAYS);
        this.lastLog.setMaxWidth(Double.MAX_VALUE);
        this.lastLog.setMinWidth(0);
        Fx.leftToRight(this.lastLog);
        // The last line of the log opens the whole log; the version is in About and in the title.
        this.lastLog.setOnMouseClicked(e -> {
            show("log");
            this.log.showLog();
        });
        this.lastLog.setCursor(Cursor.HAND);
        this.lastLog.setTooltip(new Tooltip(this.gui.t("IDS_SV_LOG")));
        this.limitsLink.setId("limits");
        this.limitsLink.getStyleClass().addAll(Styles.FLAT, "status-link");
        this.limitsLink.setOnAction(e -> showLimits(this.limitsLink, Popover.ArrowLocation.BOTTOM_CENTER));
        Fx.whole(this.limitsLink);
        Fx.whole(this.server);
        Fx.whole(this.freeSpace);
        this.freeSpace.setVisible(false);
        this.freeSpace.managedProperty().bind(this.freeSpace.visibleProperty());
        this.updateLink.setId("update-link");
        this.updateLink.getStyleClass().addAll(Styles.FLAT, "status-link");
        this.updateLink.setGraphic(Fx.icon(Icons.DOWNLOAD));
        this.updateLink.setOnAction(
                e -> new UpdateDialog(this.gui, this.root.getScene().getWindow()).show());
        this.updateLink.setVisible(false);
        this.updateLink.managedProperty().bind(this.updateLink.visibleProperty());
        Fx.whole(this.updateLink);
        HBox strip = new HBox(this.lastLog, this.updateLink, this.server, this.limitsLink, this.freeSpace);
        strip.getStyleClass().add("status-strip");
        return strip;
    }

    // A new version downloaded: installed when njMule closes, or now if the user wants.
    private void updateReady(NewVersion version) {
        UpdateStatus status = this.gui.model().updateProperty().get();
        if (status.install() == UpdateStatus.Install.AT_EXIT) {
            message(this.gui.f("nj.update.readyAtExit", version.version()));
        } else if (status.canInstall()) {
            offer(
                    this.gui.f("nj.update.ready", version.version()),
                    this.gui.t("nj.update.restartNow"),
                    () -> this.gui.model().installUpdate(true));
        } else {
            offer(
                    this.gui.f("nj.update.ready", version.version()),
                    this.gui.t("nj.update.details"),
                    () -> new UpdateDialog(this.gui, this.root.getScene().getWindow()).show());
        }
    }

    // While a new version comes and once it is ready, the strip says so.
    private void showUpdate(UpdateStatus status) {
        if (status == null) {
            return;
        }
        String version = status.available().map(NewVersion::version).orElse("");
        String text = null;
        if (status.install() == UpdateStatus.Install.RESTART) {
            text = this.gui.t("nj.update.installing");
        } else if (status.download().isPresent()) {
            long percent = Math.round(status.download().get().fraction() * 100);
            text = this.gui.f("nj.update.strip.downloading", version, percent);
        } else if (status.ready().isPresent() && status.isWorthTelling()) {
            text = this.gui.f(
                    status.install() == UpdateStatus.Install.AT_EXIT
                            ? "nj.update.readyAtExit"
                            : "nj.update.strip.ready",
                    version);
        }
        this.updateLink.setText(text == null ? "" : text);
        this.updateLink.setAccessibleText(text);
        this.updateLink.setVisible(text != null);
    }

    // The limits as the preferences set them, which open the sliders.
    private void showLimitsText(Preferences preferences) {
        if (preferences == null) {
            return;
        }
        String text = this.gui.t("IDS_PW_CON_LIMITFRM") + ": ↓ " + limitText(preferences.maxDownloadKiBps()) + " · ↑ "
                + limitText(preferences.maxUploadKiBps());
        this.limitsLink.setText(text);
        this.limitsLink.setTooltip(new Tooltip(this.gui.t("IDS_SPEED_LIMITS")));
    }

    private String limitText(int kib) {
        return kib == 0 ? this.gui.t("nj.rate.noLimit") : this.gui.formats().rate(kib * 1024L);
    }

    // The room left where downloads end up, looked at every few seconds.
    private void showFreeSpace() {
        long now = System.nanoTime();
        if (now - this.spaceCheckedAt < SPACE_EVERY) {
            return;
        }
        this.spaceCheckedAt = now;
        Preferences preferences = this.gui.model().preferencesProperty().get();
        if (preferences == null) {
            return;
        }
        Path incoming = preferences.effectiveIncomingDir(this.gui.directories());
        try {
            long free = Files.getFileStore(incoming).getUsableSpace();
            this.freeSpace.setText(this.gui.t("IDS_STATS_FREESPACE") + ": "
                    + this.gui.formats().size(free));
            this.freeSpace.setTooltip(new Tooltip(incoming.toString()));
            this.freeSpace.setVisible(true);
        } catch (IOException e) {
            // A folder not made yet, or a drive gone: nothing to tell.
            this.freeSpace.setVisible(false);
        }
    }

    /** Adds the eD2K file links in {@code text} as downloads, telling each one added; returns how many it found. */
    int dropped(String text) {
        List<Ed2kLink.FileLink> links = ClipboardWatch.fileLinks(text);
        for (Ed2kLink.FileLink link : links) {
            this.gui.model().addDownload(link, added -> message(this.gui.f("nj.transfers.added", added.name())));
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
            toggleConnection();
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
        showNetwork(this.gui.model().statusProperty().get());
    }

    private void showStatus(EngineStatus status) {
        Formats formats = this.gui.formats();
        this.down.setText(formats.rate(status.downloadBytesPerSecond()));
        this.up.setText(formats.rate(status.uploadBytesPerSecond()));
        this.rates.setAccessibleText(
                this.gui.t("nj.tray.speed") + ": ↓ " + this.down.getText() + ", ↑ " + this.up.getText());
        this.server.setText(networkCounts(
                status,
                this.gui.model().servers().rows().stream()
                        .map(LiveList.Row::get)
                        .toList()));
        showNetwork(status);
        showActive();
        showFreeSpace();
    }

    /**
     * The users and files of the networks we are on: on eD2K, all the {@code servers} of the list that answer; on Kad,
     * the estimate of the network, in brackets when both are up.
     */
    String networkCounts(EngineStatus status, List<ServerInfo> servers) {
        Formats formats = this.gui.formats();
        Optional<KadInfo> kad = status.kad().filter(KadInfo::connected);
        long users = 0;
        long files = 0;
        if (status.server().isPresent()) {
            for (ServerInfo s : servers) {
                if (s.failed() == 0) {
                    users += s.users();
                    files += s.files();
                }
            }
            if (kad.isPresent()) {
                return this.gui.f(
                        "nj.status.networks",
                        formats.count(users),
                        formats.count(kad.get().users()),
                        formats.count(files),
                        formats.count(kad.get().files()));
            }
        } else if (kad.isPresent()) {
            users = kad.get().users();
            files = kad.get().files();
        }
        return this.gui.f("nj.status.server", formats.count(users), formats.count(files));
    }

    // The networks' state on their control, on their place and as the network's summary; the banner tells what to do.
    private void showNetwork(EngineStatus status) {
        EngineModel model = this.gui.model();
        EngineModel.Connection connection = model.connectionProperty().get();
        this.network.update(connection, status, model.serverNameProperty().get());
        this.networkCards.update(
                connection,
                status,
                model.serverNameProperty().get(),
                model.preferencesProperty().get());
        this.networkPane.count(
                "servers",
                this.screens.get("servers").title(),
                model.servers().rows().size());
        this.networkPane.count(
                "kad",
                this.screens.get("kad").title(),
                status.kad().map(k -> (long) k.contacts()).orElse(-1L));
        Optional<KadInfo> kadInfo = status.kad();
        boolean kadUp = kadInfo.isPresent() && kadInfo.get().connected();
        boolean up = connection == EngineModel.Connection.CONNECTED || kadUp;
        boolean trying = connection == EngineModel.Connection.CONNECTING || kadInfo.isPresent();
        String state = this.network.ed2kChip().getText() + "\n"
                + this.network.kadChip().getText();
        this.sidebar.network(NETWORK_PLACE, up, trying, state, shortcut(PLACES.indexOf(NETWORK_PLACE)));
        this.networkSummary.set(this.network.summary());
        this.banner.update(connection, status);
    }

    Label ed2kChip() {
        return this.network.ed2kChip();
    }

    Label kadChip() {
        return this.network.kadChip();
    }

    // The limits over the rates or the status strip, as in the notification area's menu, with the same two presets.
    private void showLimits(Node anchor, Popover.ArrowLocation arrow) {
        if (this.limitsPopover == null) {
            this.limits = new SpeedLimits(this.gui);
            Button full = Fx.button(
                    this.gui.t("nj.tray.fullSpeed"),
                    Icons.SPEED,
                    () -> this.limits.set(p -> p.downloadCapacityKiBps(), p -> p.uploadCapacityKiBps()));
            Button slow = Fx.button(this.gui.t("nj.tray.slowDown"), Icons.SLOW, () -> this.limits.set(p -> 1, p -> 1));
            VBox box = new VBox(10, this.limits.view(), new HBox(8, full, slow));
            box.getStyleClass().add("limits-popover");
            this.limitsPopover = new Popover(box);
            this.limitsPopover.setTitle(this.gui.t("nj.tray.limits"));
            this.limitsPopover.setHeaderAlwaysVisible(true);
        }
        this.limitsPopover.setArrowLocation(arrow);
        this.limits.load();
        this.limitsPopover.show(anchor);
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
            problem(e.getMessage());
        }
    }

    // The questions of the first start, asked again from the settings as they are now.
    private void showWizard() {
        Preferences preferences = this.gui.model().preferencesProperty().get();
        if (preferences == null) {
            return;
        }
        Window owner = this.root.getScene().getWindow();
        new FirstRunWizard(this.gui, owner, preferences, ImportDialog.guessEmuleDirectory(), outcome -> {
                    if (outcome.importEmule()) {
                        new ImportDialog(this.gui, owner).show();
                    }
                    if (outcome.languageChanged()) {
                        this.gui.relaunch().run();
                    }
                })
                .show();
    }

    // What the palette offers: the places, then the commands of the bar and the menus.
    private List<CommandPalette.Command> commands() {
        List<CommandPalette.Command> all = new ArrayList<>();
        for (int i = 0; i < PLACES.size(); i++) {
            String place = PLACES.get(i);
            all.add(new CommandPalette.Command(placeTitle(place), shortcut(i), () -> open(place)));
        }
        all.add(new CommandPalette.Command(connectButton().getText(), "", this::toggleConnection));
        all.add(new CommandPalette.Command(this.gui.t("IDS_OPENINC"), "", this::openIncoming));
        all.add(new CommandPalette.Command(this.gui.t("nj.wizard.title"), "", this::showWizard));
        all.add(new CommandPalette.Command(
                this.gui.t("nj.import.title"),
                "",
                () -> new ImportDialog(this.gui, this.root.getScene().getWindow()).show()));
        all.add(new CommandPalette.Command(this.gui.t("nj.top.theme"), "", this::toggleTheme));
        all.add(new CommandPalette.Command(
                this.gui.t(this.sidebar.collapsedProperty().get() ? "nj.sidebar.expand" : "nj.sidebar.collapse"),
                new KeyCodeCombination(KeyCode.B, KeyCombination.SHORTCUT_DOWN).getDisplayText(),
                this.sidebar::toggle));
        all.add(new CommandPalette.Command(
                this.gui.t("nj.shortcuts.title"),
                new KeyCodeCombination(KeyCode.F1).getDisplayText(),
                this::showShortcuts));
        all.add(new CommandPalette.Command(
                this.gui.t("IDS_CHECK4UPDATE"),
                "",
                () -> UpdateDialog.checkNow(this.gui, this.root.getScene().getWindow())));
        all.add(new CommandPalette.Command(
                this.gui.t("nj.about.title"),
                "",
                () -> new AboutDialog(this.gui, this.root.getScene().getWindow()).show()));
        return all;
    }

    private String placeTitle(String place) {
        return place.equals(NETWORK_PLACE)
                ? this.gui.t("IDS_NETWORK")
                : this.screens.get(place).title();
    }

    // What the text typed is itself: eD2K links to download, or else words to search the network for.
    private List<CommandPalette.Command> typedCommands(String text) {
        List<Ed2kLink.FileLink> links = ClipboardWatch.fileLinks(text);
        if (!links.isEmpty()) {
            String names = String.join(
                    ", ", links.stream().map(Ed2kLink.FileLink::name).toList());
            return List.of(
                    new CommandPalette.Command(this.gui.t("IDS_DOWNLOAD") + ": " + names, "", () -> dropped(text)));
        }
        if (text.regionMatches(true, 0, "ed2k://", 0, 7)) {
            return List.of();
        }
        return List.of(new CommandPalette.Command(this.gui.f("nj.palette.search", text), "", () -> {
            show("search");
            ((SearchScreen) this.screens.get("search")).searchFor(text);
        }));
    }

    /** The palette, for tests. */
    CommandPalette palette() {
        return this.palette;
    }

    /** The shortcut of the place at {@code index} of the sidebar, as the platform writes it: Ctrl+1, or ⌘1. */
    static String shortcut(int index) {
        return new KeyCodeCombination(DIGITS.get(index), KeyCombination.SHORTCUT_DOWN).getDisplayText();
    }

    // The keys that do something, with what they do, in a window of their own.
    void showShortcuts() {
        GridPane grid = new GridPane();
        grid.getStyleClass().add("shortcuts");
        int row = 0;
        for (int i = 0; i < PLACES.size(); i++) {
            row = shortcutRow(grid, row, shortcut(i), placeTitle(PLACES.get(i)));
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
        row = shortcutRow(
                grid,
                row,
                new KeyCodeCombination(KeyCode.K, KeyCombination.SHORTCUT_DOWN).getDisplayText(),
                this.gui.t("nj.palette.prompt"));
        row = shortcutRow(
                grid,
                row,
                new KeyCodeCombination(KeyCode.B, KeyCombination.SHORTCUT_DOWN).getDisplayText(),
                this.gui.t("nj.sidebar.collapse") + " / " + this.gui.t("nj.sidebar.expand"));
        shortcutRow(grid, row, new KeyCodeCombination(KeyCode.F1).getDisplayText(), this.gui.t("nj.shortcuts.title"));
        Stage stage = new Stage();
        stage.initOwner(this.root.getScene().getWindow());
        stage.setTitle(this.gui.t("nj.shortcuts.title"));
        Button close = Fx.button(this.gui.t("IDS_FD_CLOSE"), Icons.CLOSE, stage::close);
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

    private static Label rate(Icons arrow, String style) {
        Label label = new Label("", new Icon(arrow));
        label.getStyleClass().addAll("rate-label", style);
        return label;
    }
}
