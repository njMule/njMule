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

import atlantafx.base.theme.NordDark;
import atlantafx.base.theme.NordLight;
import atlantafx.base.theme.Styles;
import java.util.Optional;
import javafx.application.Application;
import javafx.application.ColorScheme;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.material2.Material2AL;
import org.njmule.app.LaunchOptions;
import org.njmule.app.logging.LoggingSetup;
import org.njmule.core.api.EngineConfig;
import org.njmule.core.api.NjMuleEngine;
import org.njmule.core.api.NjMuleInfo;
import org.njmule.core.config.AppDirectories;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Placeholder main window (milestone G0 replaces it with the full shell). */
public final class NjMuleFxApp extends Application {

    private static final Logger LOG = LoggerFactory.getLogger(NjMuleFxApp.class);

    private NjMuleEngine engine;
    private boolean dark;

    @Override
    public void init() throws Exception {
        LaunchOptions cli =
                LaunchOptions.parse(this.getParameters().getRaw().toArray(String[]::new));
        AppDirectories dirs = AppDirectories.resolve(cli.configDir(), Optional.empty());
        dirs.createAll();
        LoggingSetup.enableFileLog(dirs.logs());
        this.engine = NjMuleEngine.start(new EngineConfig(dirs));
        LoggingSetup.setLevel(cli.logLevel().orElse(this.engine.preferences().logLevel()));
    }

    @Override
    public void start(Stage stage) {
        this.dark = Platform.getPreferences().getColorScheme() == ColorScheme.DARK;
        this.applyTheme();

        for (int size : new int[] {16, 24, 32, 48, 64, 128, 256, 512}) {
            var icon = NjMuleFxApp.class.getResource("icons/njmule-" + size + ".png");
            if (icon != null) {
                stage.getIcons().add(new Image(icon.toExternalForm()));
            }
        }

        ImageView logo = new ImageView();
        stage.getIcons().stream()
                .filter(i -> i.getWidth() == 64)
                .findFirst()
                .ifPresent(logo::setImage);
        logo.setFitWidth(48);
        logo.setFitHeight(48);
        logo.setSmooth(true);
        Label title = new Label(NjMuleInfo.NAME);
        title.getStyleClass().add(Styles.TITLE_1);
        Label tagline = new Label(NjMuleInfo.TAGLINE + " - " + NjMuleInfo.version());
        tagline.getStyleClass().add(Styles.TEXT_MUTED);
        HBox header = new HBox(16, logo, new VBox(title, tagline));
        header.setAlignment(Pos.CENTER_LEFT);

        Label status =
                new Label("Engine running. Configuration: " + this.engine.directories().config());
        status.setWrapText(true);
        Button theme = new Button("Light / dark", new FontIcon(Material2AL.BRIGHTNESS_MEDIUM));
        theme.setOnAction(
                e -> {
                    this.dark = !this.dark;
                    this.applyTheme();
                });
        VBox center = new VBox(12, status, theme);
        center.setPadding(new Insets(24, 0, 24, 0));

        Label notice =
                new Label(
                        NjMuleInfo.COPYRIGHT
                                + ". This program comes with ABSOLUTELY NO WARRANTY. It is free"
                                + " software under the "
                                + NjMuleInfo.LICENSE_ID
                                + " license.");
        notice.setWrapText(true);
        notice.getStyleClass().addAll(Styles.TEXT_SMALL, Styles.TEXT_MUTED);

        BorderPane root = new BorderPane(center, header, null, notice, null);
        root.setPadding(new Insets(24));
        stage.setTitle(NjMuleInfo.NAME + " " + NjMuleInfo.version());
        stage.setScene(new Scene(root, 720, 420));
        stage.show();
        LOG.info("Main window shown");
    }

    @Override
    public void stop() {
        if (this.engine != null) {
            this.engine.close();
        }
    }

    private void applyTheme() {
        Application.setUserAgentStylesheet(
                this.dark
                        ? new NordDark().getUserAgentStylesheet()
                        : new NordLight().getUserAgentStylesheet());
    }
}
