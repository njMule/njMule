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
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.stage.Stage;
import javafx.util.Duration;
import org.njmule.app.LaunchOptions;
import org.njmule.app.LinkHandoff;
import org.njmule.app.logging.LoggingSetup;
import org.njmule.core.api.Ed2kLink;
import org.njmule.core.api.EngineConfig;
import org.njmule.core.api.NjMuleEngine;
import org.njmule.core.api.NjMuleInfo;
import org.njmule.core.config.AppDirectories;
import org.njmule.gui.dialogs.ImportDialog;
import org.njmule.gui.i18n.I18n;
import org.njmule.gui.infra.Formats;
import org.njmule.gui.infra.GuiSettings;
import org.njmule.gui.infra.Themes;
import org.njmule.gui.model.EngineModel;
import org.njmule.gui.shell.MainWindow;
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
    private LinkHandoff handoff;
    private Stage stage;
    private EngineModel model;
    private Themes themes;
    private MainWindow window;
    private Gui gui;
    private Scene scene;
    private Timeline ticker;

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
        app.pending.addAll(cli.links());
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

    // A later start handed over its links, or none when it only wants the window: FX thread.
    private void handedOver(List<String> links) {
        for (String text : links) {
            if (Ed2kLink.tryParse(text).orElse(null) instanceof Ed2kLink.FileLink link) {
                this.pending.add(link);
            }
        }
        this.addPending();
        if (this.stage != null) {
            this.stage.setIconified(false);
            this.stage.show();
            this.stage.toFront();
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
        stage.show();
        this.model.start();
        this.ticker = new Timeline(new KeyFrame(Duration.seconds(1), e -> this.model.tick()));
        this.ticker.setCycleCount(Animation.INDEFINITE);
        this.ticker.play();
        logger.info("Main window shown");
        this.addPending();
        if (GuiTour.requested()) {
            GuiTour.start(this.gui, this.window, stage, Platform::exit);
        } else if (!this.settings.existed()) {
            // At the first start an eMule on this computer is offered for import.
            ImportDialog.guessEmuleDirectory().ifPresent(dir -> new ImportDialog(this.gui, stage).show());
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
        if (this.handoff != null) {
            this.handoff.close();
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
