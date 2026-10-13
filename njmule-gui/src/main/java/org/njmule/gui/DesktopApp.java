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
package org.njmule.gui;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.geometry.Dimension2D;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.image.Image;
import javafx.stage.PopupWindow;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;
import javafx.stage.WindowEvent;
import javafx.util.Duration;
import org.njmule.app.LaunchOptions;
import org.njmule.app.LinkHandoff;
import org.njmule.app.logging.LoggingSetup;
import org.njmule.core.api.Ed2kLink;
import org.njmule.core.api.EngineConfig;
import org.njmule.core.api.EngineStatus;
import org.njmule.core.api.NjMuleEngine;
import org.njmule.core.api.NjMuleInfo;
import org.njmule.core.api.ServerAddress;
import org.njmule.core.config.AppDirectories;
import org.njmule.gui.dialogs.ImportDialog;
import org.njmule.gui.i18n.I18n;
import org.njmule.gui.infra.Folders;
import org.njmule.gui.infra.Formats;
import org.njmule.gui.infra.GuiSettings;
import org.njmule.gui.infra.Themes;
import org.njmule.gui.model.EngineModel;
import org.njmule.gui.shell.MainWindow;
import org.njmule.gui.shell.MiniMule;
import org.njmule.gui.shell.NotificationArea;
import org.njmule.gui.shell.TrayMenu;
import org.njmule.gui.ui.ClipboardWatch;
import org.njmule.gui.ui.Gui;
import org.njmule.webui.WebUi;
import org.njmule.webui.WebUiSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The desktop application around one engine: opening it, showing the main window, keeping the window's settings and
 * closing everything together.
 */
public final class DesktopApp implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(DesktopApp.class);
    private static final int[] ICON_SIZES = {16, 24, 32, 48, 64, 128, 256, 512};

    private final NjMuleEngine engine;
    private final AppDirectories directories;
    private final GuiSettings settings;
    private final Optional<WebUi> webUi;
    // Links from the command line or a later start, until the window is there; FX thread.
    private final List<Ed2kLink.FileLink> pending = new ArrayList<>();
    // The links of the command line, followed once the window shows.
    private final List<String> startLinks = new ArrayList<>();
    private LinkHandoff handoff;
    private Stage stage;
    private NotificationArea tray;
    private Stage trayOwner;
    private TrayMenu trayMenu;
    private MiniMule miniMule;
    private EngineModel model;
    private Themes themes;
    private MainWindow window;
    private Gui gui;
    private Scene scene;
    private Timeline ticker;
    private ClipboardWatch clipboard;

    private DesktopApp(NjMuleEngine engine, AppDirectories directories, GuiSettings settings, Optional<WebUi> webUi) {
        this.engine = engine;
        this.directories = directories;
        this.settings = settings;
        this.webUi = webUi;
    }

    /** Starts the engine as the command line says; any thread. */
    public static DesktopApp open(LaunchOptions cli) throws IOException {
        AppDirectories dirs = AppDirectories.resolve(cli.configDir(), Optional.empty());
        dirs.createAll();
        LoggingSetup.enableFileLog(dirs.logs());
        NjMuleEngine engine = NjMuleEngine.start(new EngineConfig(dirs));
        LoggingSetup.setLevel(cli.logLevel().orElse(engine.preferences().logLevel()));
        DesktopApp app = new DesktopApp(engine, dirs, GuiSettings.load(dirs.gui()), startWebUi(engine, dirs));
        cli.links().forEach(link -> app.startLinks.add(link.toUri()));
        try {
            app.handoff = LinkHandoff.listen(dirs.config(), links -> Platform.runLater(() -> app.handedOver(links)));
        } catch (IOException e) {
            logger.warn("Later starts cannot hand their links over: {}", e.toString());
        }
        return app;
    }

    /**
     * The configuration directory a start with {@code cli} uses, where a running instance waits for links; any thread.
     */
    public static Path configDir(LaunchOptions cli) {
        return AppDirectories.resolve(cli.configDir(), Optional.empty()).config();
    }

    /** Whether to exit: asked first, unless the question was turned off. */
    boolean mayExit(Window owner) {
        if (!this.settings.confirmExit()) {
            return true;
        }
        CheckBox again = new CheckBox(this.gui.t("IDS_DONOTASKAGAIN"));
        Alert ask = new Alert(Alert.AlertType.CONFIRMATION, null, ButtonType.YES, ButtonType.NO);
        ask.setHeaderText(this.gui.t("nj.exit.confirm"));
        ask.getDialogPane().setContent(again);
        ask.initOwner(owner);
        boolean yes = ask.showAndWait().filter(b -> b == ButtonType.YES).isPresent();
        if (yes && again.isSelected()) {
            this.settings.setConfirmExit(false);
            try {
                this.settings.save();
            } catch (IOException e) {
                logger.warn("Cannot save the interface settings: {}", e.toString());
            }
        }
        return yes;
    }

    // A later start handed over its links, or none when it only wants the window: FX thread.
    private void handedOver(List<String> links) {
        if (links.contains(LinkHandoff.EXIT)) {
            // Asked by njmule --exit: no question, the user asked already.
            logger.info("Exiting as a later start asked");
            Platform.exit();
            return;
        }
        for (String text : links) {
            switch (Ed2kLink.tryParse(text).orElse(null)) {
                case Ed2kLink.FileLink link -> this.pending.add(link);
                case Ed2kLink.ServerListLink list -> this.fromUrl(list.url(), true);
                case Ed2kLink.NodesListLink list -> this.fromUrl(list.url(), false);
                case Ed2kLink.ServerLink server -> this.addServer(server);
                case null, default -> {
                    // A search link is not acted upon yet.
                }
            }
        }
        this.addPending();
        if (this.tray != null && this.tray.isHidden()) {
            this.tray.restore();
        } else if (this.stage != null) {
            this.stage.setIconified(false);
            this.stage.show();
            this.stage.toFront();
        }
    }

    // A server list or a nodes.dat a link points to; links come before the window only from the command line.
    private void fromUrl(String url, boolean servers) {
        if (this.model == null) {
            return;
        }
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            this.window.message(e.getMessage());
            return;
        }
        if (servers) {
            this.model.addServers(
                    uri, added -> this.window.message(this.gui.f("nj.servers.fromUrl", added, uri.getHost())));
        } else {
            this.model.addKadContacts(
                    uri, added -> this.window.message(this.gui.f("nj.kad.fromUrl", added, uri.getHost())));
        }
    }

    private void addServer(Ed2kLink.ServerLink server) {
        if (this.model != null) {
            this.model.addServer(new ServerAddress(server.host(), server.port()));
            this.window.message(this.gui.f("nj.servers.added", server.host() + ":" + server.port()));
        }
    }

    private void addPending() {
        if (this.model == null) {
            return;
        }
        for (Ed2kLink.FileLink link : this.pending) {
            this.model.addDownload(link);
            this.window.message(this.gui.f("nj.transfers.added", link.name()));
        }
        this.pending.clear();
    }

    // The web interface runs beside the window when its settings ask for it.
    private static Optional<WebUi> startWebUi(NjMuleEngine engine, AppDirectories dirs) {
        WebUiSettings web = WebUiSettings.load(dirs.config());
        if (!web.usable()) {
            return Optional.empty();
        }
        try {
            return Optional.of(WebUi.start(engine, web));
        } catch (IOException e) {
            logger.warn("Cannot start the web interface on port {}: {}", web.port(), e.toString());
            return Optional.empty();
        }
    }

    public Optional<WebUi> webUi() {
        return this.webUi;
    }

    /** Shows the main window in {@code stage}; FX thread. */
    public void show(Stage stage) {
        this.stage = stage;
        this.themes = new Themes(this.settings.theme(), this.settings.mode());
        this.model = new EngineModel(this.engine, Platform::runLater);
        this.scene = new Scene(this.build(), 1180, 760);
        this.themes.attach(this.scene);
        for (int size : ICON_SIZES) {
            var icon = DesktopApp.class.getResource("icons/njmule-" + size + ".png");
            if (icon != null) {
                stage.getIcons().add(new Image(icon.toExternalForm()));
            }
        }
        iconEveryWindow(List.copyOf(stage.getIcons()));
        this.settings.window().ifPresent(b -> {
            stage.setX(b[0]);
            stage.setY(b[1]);
            stage.setWidth(b[2]);
            stage.setHeight(b[3]);
        });
        stage.setMaximized(this.settings.maximized());
        stage.setTitle(NjMuleInfo.NAME + " " + NjMuleInfo.version());
        stage.setScene(this.scene);
        stage.setOnHiding(e -> this.remember(stage));
        stage.setOnCloseRequest(e -> {
            if (!this.mayExit(stage)) {
                e.consume();
            }
        });
        stage.show();
        this.model.start();
        this.ticker = new Timeline(new KeyFrame(Duration.seconds(1), e -> {
            this.model.tick();
            this.tickTray();
        }));
        this.ticker.setCycleCount(Animation.INDEFINITE);
        this.ticker.play();
        // eD2K file links copied anywhere become downloads when the settings ask for it.
        this.clipboard = ClipboardWatch.start(this.settings::watchClipboard, links -> {
            this.pending.addAll(links);
            this.addPending();
        });
        logger.info("Main window shown");
        this.keepButtonsVisible(stage);
        if (NotificationArea.isSupported()) {
            this.attachTray(stage);
        }
        if (this.settings.startMinimized() && !GuiTour.requested()) {
            if (this.tray != null && this.settings.minimizeToTray()) {
                this.tray.hideNow();
            } else {
                stage.setIconified(true);
            }
        }
        stage.addEventHandler(WindowEvent.WINDOW_HIDDEN, e -> {
            if (this.tray == null || !this.tray.isHidden()) {
                this.closeTheOthers(stage);
            }
        });
        this.handedOver(List.copyOf(this.startLinks));
        this.startLinks.clear();
        if (GuiTour.requested()) {
            GuiTour.start(this.gui, this.window, stage, this.trayTour(stage), Platform::exit);
        } else if (!this.settings.existed()) {
            // At the first start an eMule on this computer is offered for import.
            ImportDialog.guessEmuleDirectory().ifPresent(dir -> new ImportDialog(this.gui, stage).show());
        }
    }

    // Dialogs and alerts do not take the icon of their owner: every window of ours that opens without one gets it.
    private static void iconEveryWindow(List<Image> icons) {
        Window.getWindows().addListener((ListChangeListener<Window>) change -> {
            while (change.next()) {
                for (Window window : change.getAddedSubList()) {
                    if (window instanceof Stage s && s.getIcons().isEmpty()) {
                        s.getIcons().setAll(icons);
                    }
                }
            }
        });
    }

    // The icon, its menu and the small status window. They belong to a window of their own that never
    // shows, so they get no button on the taskbar.
    private void attachTray(Stage stage) {
        Stage owner = new Stage(StageStyle.UTILITY);
        this.trayOwner = owner;
        owner.setOpacity(0);
        owner.setWidth(1);
        owner.setHeight(1);
        owner.setX(-10_000);
        owner.setY(-10_000);
        owner.show();
        this.trayWindows();
        this.tray = NotificationArea.attach(stage, this.settings::minimizeToTray, new NotificationArea.Handlers() {
            @Override
            public void click() {
                DesktopApp.this.miniMule.show();
            }

            @Override
            public void menu(double x, double y) {
                DesktopApp.this.trayMenu.show(x, y);
            }

            @Override
            public void restored() {
                DesktopApp.this.miniMule.hide();
                DesktopApp.this.trayMenu.hide();
            }
        });
        this.window.whileHidden(this.tray::notify);
    }

    // The menu and the small status window, in the language of the interface.
    private void trayWindows() {
        Runnable restore = () -> this.tray.restore();
        Runnable options = () -> {
            this.tray.restore();
            this.window.show("settings");
        };
        this.trayMenu = new TrayMenu(this.gui, this.trayOwner, new TrayMenu.Actions() {
            @Override
            public void restore() {
                restore.run();
            }

            @Override
            public void options() {
                options.run();
            }

            @Override
            public void exit() {
                if (DesktopApp.this.mayExit(DesktopApp.this.trayOwner)) {
                    Platform.exit();
                }
            }
        });
        this.miniMule = new MiniMule(
                this.gui,
                this.trayOwner,
                () -> this.engine.preferences().effectiveIncomingDir(this.directories),
                new MiniMule.Actions() {
                    @Override
                    public void openIncoming() {
                        try {
                            Folders.open(DesktopApp.this
                                    .engine
                                    .preferences()
                                    .effectiveIncomingDir(DesktopApp.this.directories));
                        } catch (IOException e) {
                            DesktopApp.this.window.message(e.getMessage());
                        }
                    }

                    @Override
                    public void options() {
                        options.run();
                    }

                    @Override
                    public void restore() {
                        restore.run();
                    }
                });
    }

    // Closing the main window ends njMule: its dialogs and the tray's windows go with it, or any of them left
    // open (the tray's owner is never seen) would keep the toolkit running.
    private void closeTheOthers(Stage stage) {
        for (Window window : List.copyOf(Window.getWindows())) {
            if (!window.equals(stage) && this.belongs(window, stage)) {
                window.hide();
            }
        }
    }

    private boolean belongs(Window window, Stage stage) {
        for (Window w = window; w != null; w = ownerOf(w)) {
            if (w.equals(stage) || w.equals(this.trayOwner)) {
                return true;
            }
        }
        return false;
    }

    private static Window ownerOf(Window window) {
        return switch (window) {
            case Stage s -> s.getOwner();
            case PopupWindow p -> p.getOwnerWindow();
            default -> null;
        };
    }

    // The tray's own windows in the tour, for the native metadata: the icon cannot be clicked there.
    private List<Runnable> trayTour(Stage stage) {
        if (this.tray == null) {
            return List.of();
        }
        return List.of(
                () -> stage.setIconified(true),
                () -> this.tray.notify("Tour"),
                () -> this.miniMule.show(),
                () -> this.miniMule.refresh(),
                () -> this.miniMule.hide(),
                () -> this.trayMenu.show(400, 400),
                () -> this.trayMenu.hide(),
                () -> this.tray.restore());
    }

    // The window never gets so small that buttons are cut or hidden; the frame adds its own borders.
    private void keepButtonsVisible(Stage stage) {
        Dimension2D content = this.window.minimumSize();
        stage.setMinWidth(content.getWidth() + Math.max(0, stage.getWidth() - this.scene.getWidth()));
        stage.setMinHeight(content.getHeight() + Math.max(0, stage.getHeight() - this.scene.getHeight()));
    }

    // While hidden in the notification area: the rates on the icon and in the small window.
    private void tickTray() {
        if (this.tray == null || !this.tray.isHidden()) {
            return;
        }
        EngineStatus status = this.model.statusProperty().get();
        if (status != null) {
            this.tray.tooltip(NjMuleInfo.NAME + " - " + this.gui.t("IDS_PW_CON_DOWNLBL") + ": "
                    + this.gui.formats().rate(status.downloadBytesPerSecond()) + " | "
                    + this.gui.t("IDS_PW_CON_UPLBL") + ": "
                    + this.gui.formats().rate(status.uploadBytesPerSecond()));
        }
        if (this.miniMule.isShowing()) {
            this.miniMule.refresh();
        }
    }

    public MainWindow window() {
        return this.window;
    }

    public NjMuleEngine engine() {
        return this.engine;
    }

    /** Stops the window's timer, then the engine. */
    @Override
    public void close() {
        if (this.ticker != null) {
            this.ticker.stop();
        }
        if (this.clipboard != null) {
            this.clipboard.close();
        }
        if (this.handoff != null) {
            this.handoff.close();
        }
        if (this.tray != null) {
            this.tray.close();
        }
        if (this.window != null) {
            this.window.closing();
        }
        if (this.model != null) {
            this.model.close();
        }
        this.webUi.ifPresent(WebUi::close);
        this.engine.close();
        LoggingSetup.disableFileLog();
    }

    private Parent build() {
        Locale locale = this.settings.language().orElse(Locale.getDefault());
        I18n i18n = I18n.of(locale);
        this.gui = new Gui(
                i18n,
                new Formats(i18n.locale()),
                this.model,
                this.themes,
                this.settings,
                this.directories,
                text -> this.window.message(text),
                this::relaunch);
        this.window = new MainWindow(this.gui);
        this.model.onError(this.window::message);
        return this.window.root();
    }

    // After a language change the window is built again around the same engine model.
    void relaunch() {
        String screen = this.window.current();
        this.scene.setRoot(this.build());
        this.window.show(screen);
        if (this.tray != null) {
            this.trayWindows();
            this.window.whileHidden(this.tray::notify);
        }
    }

    TrayMenu trayMenu() {
        return this.trayMenu;
    }

    private void remember(Stage stage) {
        if (!stage.isMaximized()) {
            this.settings.setWindow(stage.getX(), stage.getY(), stage.getWidth(), stage.getHeight());
        }
        this.settings.setMaximized(stage.isMaximized());
        try {
            this.settings.save();
        } catch (IOException e) {
            logger.warn("Cannot save the window settings", e);
        }
    }
}
