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

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.geometry.Dimension2D;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.ScrollPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.core.api.NjMuleEngine;
import org.njmule.core.config.AppDirectories;
import org.njmule.gui.FxTesting;
import org.njmule.gui.i18n.I18n;
import org.njmule.gui.infra.Formats;
import org.njmule.gui.infra.GuiSettings;
import org.njmule.gui.infra.Themes;
import org.njmule.gui.model.EngineModel;
import org.njmule.gui.ui.Gui;

@Timeout(120)
class MainWindowTest {

    private static final List<String> SCREENS =
            List.of("servers", "kad", "transfers", "search", "shared", "messages", "statistics", "settings");

    @TempDir
    Path dir;

    private NjMuleEngine engine;
    private EngineModel model;
    private Stage stage;

    @BeforeAll
    static void toolkit() throws Exception {
        FxTesting.start();
    }

    @AfterEach
    void close() throws Exception {
        if (this.stage != null) {
            FxTesting.onFx(this.stage::close);
        }
        if (this.model != null) {
            this.model.close();
        }
        if (this.engine != null) {
            this.engine.close();
        }
    }

    @Test
    void everyScreenShowsInEveryLanguageAndMode() throws Exception {
        this.engine = FxTesting.engine(this.dir.resolve("njmule"));
        this.model = new EngineModel(this.engine, Platform::runLater);
        for (Locale language : I18n.LANGUAGES) {
            for (GuiSettings.Mode mode : List.of(GuiSettings.Mode.LIGHT, GuiSettings.Mode.DARK)) {
                MainWindow window = this.window(language, mode);
                for (String screen : SCREENS) {
                    FxTesting.onFx(() -> {
                        window.show(screen);
                        assertThat(window.current()).isEqualTo(screen);
                        window.root().applyCss();
                        window.root().layout();
                    });
                    FxTesting.onFx(() -> {
                        FxTesting.snapshot(
                                window.root(),
                                screen
                                        + "-"
                                        + language.getLanguage()
                                        + "-"
                                        + mode.name().toLowerCase(Locale.ROOT));
                        return null;
                    });
                }
            }
        }
    }

    @Test
    void atItsMinimumSizeEveryButtonShowsWhole() throws Exception {
        this.engine = FxTesting.engine(this.dir.resolve("njmule"));
        this.model = new EngineModel(this.engine, Platform::runLater);
        for (Locale language : I18n.LANGUAGES) {
            MainWindow window = this.window(language, GuiSettings.Mode.LIGHT);
            Dimension2D minimum = FxTesting.onFx(window::minimumSize);
            for (String screen : SCREENS) {
                List<String> cut = FxTesting.onFx(() -> {
                    window.show(screen);
                    Parent root = window.root();
                    root.resize(minimum.getWidth(), minimum.getHeight());
                    root.applyCss();
                    root.layout();
                    List<String> wrong = new ArrayList<>();
                    for (Node node : root.lookupAll(".button")) {
                        if (!(node instanceof ButtonBase button) || !button.isVisible() || button.getScene() == null) {
                            continue;
                        }
                        Bounds bounds = button.localToScene(button.getBoundsInLocal());
                        // Buttons in a scrolled pane are reached by scrolling down.
                        boolean inside = bounds.getMaxX() <= minimum.getWidth() + 0.5
                                && (scrolled(button) || bounds.getMaxY() <= minimum.getHeight() + 0.5);
                        if (button.getWidth() + 0.5 < button.prefWidth(-1) || !inside) {
                            wrong.add(screen + ": " + button.getText() + " " + bounds);
                        }
                    }
                    return wrong;
                });
                assertThat(cut).as(language + " " + screen).isEmpty();
            }
        }
    }

    @Test
    void messagesComeAndGo() throws Exception {
        this.engine = FxTesting.engine(this.dir.resolve("njmule"));
        this.model = new EngineModel(this.engine, Platform::runLater);
        MainWindow window = this.window(Locale.ENGLISH, GuiSettings.Mode.LIGHT);

        FxTesting.onFx(() -> window.message("hello"));

        assertThat(FxTesting.onFx(() -> window.messages().size())).isEqualTo(1);
        assertThat(FxTesting.onFx(() -> window.connectButton().getText())).isEqualTo("Connect");
        assertThat(FxTesting.onFx(() -> window.ed2kChip().getText())).contains("Not Connected");
    }

    private static boolean scrolled(Node node) {
        for (Node up = node.getParent(); up != null; up = up.getParent()) {
            if (up instanceof ScrollPane) {
                return true;
            }
        }
        return false;
    }

    private MainWindow window(Locale language, GuiSettings.Mode mode) throws Exception {
        AppDirectories dirs = this.engine.directories();
        AtomicReference<MainWindow> created = new AtomicReference<>();
        FxTesting.onFx(() -> {
            GuiSettings settings = GuiSettings.load(dirs.gui());
            Themes themes = new Themes(Themes.Family.NORD, mode);
            I18n i18n = I18n.of(language);
            Gui gui = new Gui(
                    i18n,
                    new Formats(i18n.locale()),
                    this.model,
                    themes,
                    settings,
                    dirs,
                    text -> created.get().message(text),
                    () -> {});
            MainWindow window = new MainWindow(gui);
            created.set(window);
            if (this.stage == null) {
                this.stage = new Stage();
            }
            Scene scene = new Scene(window.root(), 1200, 760);
            themes.attach(scene);
            this.stage.setScene(scene);
            this.stage.show();
        });
        this.model.start();
        return created.get();
    }
}
