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

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
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
import org.njmule.core.api.EngineStatus;
import org.njmule.core.api.KadInfo;
import org.njmule.core.api.NjMuleEngine;
import org.njmule.core.api.ServerInfo;
import org.njmule.core.config.AppDirectories;
import org.njmule.gui.FxTesting;
import org.njmule.gui.Statuses;
import org.njmule.gui.i18n.I18n;
import org.njmule.gui.infra.Formats;
import org.njmule.gui.infra.GuiSettings;
import org.njmule.gui.infra.Themes;
import org.njmule.gui.model.EngineModel;
import org.njmule.gui.ui.Gui;

@Timeout(120)
class MainWindowTest {

    private static final List<String> SCREENS =
            List.of("servers", "kad", "log", "transfers", "search", "shared", "messages", "statistics", "settings");

    @TempDir
    Path dir;

    private NjMuleEngine engine;
    private EngineModel model;
    private Stage stage;
    private MainWindow window;
    private String counted;
    private final List<Shown> shown = new ArrayList<>();
    private final Map<String, List<String>> cut = new LinkedHashMap<>();

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
    void shouldShowEveryScreenInEveryLanguageAndMode() throws Exception {
        givenTheEngine();

        whenEveryScreenIsShownInEveryLanguageAndMode();

        thenEachScreenShowedAsAsked();
    }

    @Test
    void shouldShowEveryButtonWholeAtTheMinimumSize() throws Exception {
        givenTheEngine();

        whenEveryScreenIsLaidOutAtTheMinimumSizeInEveryLanguage();

        thenNoButtonIsCut();
    }

    @Test
    void shouldLetMessagesComeAndGo() throws Exception {
        givenTheEngine();
        givenTheWindowIn(Locale.ENGLISH, GuiSettings.Mode.LIGHT);

        whenAMessageIsShown("hello");

        thenTheMessagesShownAre(1);
        thenConnectReads("Connect");
        thenTheEd2kChipTells("Not Connected");
    }

    @Test
    void shouldCountTheUsersOfTheAnsweringServersBesideTheKadEstimate() throws Exception {
        givenTheEngine();
        givenTheWindowIn(Locale.ENGLISH, GuiSettings.Mode.LIGHT);

        whenTheNetworksAreCounted(true, true);

        thenTheCountsRead("Users 2,400 (5,000) | Files 68,000 (600,000)");
    }

    @Test
    void shouldCountTheServersAloneWhileKadIsDown() throws Exception {
        givenTheEngine();
        givenTheWindowIn(Locale.ENGLISH, GuiSettings.Mode.LIGHT);

        whenTheNetworksAreCounted(true, false);

        thenTheCountsRead("Users 2,400 | Files 68,000");
    }

    @Test
    void shouldCountTheKadEstimateAloneWithoutAServer() throws Exception {
        givenTheEngine();
        givenTheWindowIn(Locale.ENGLISH, GuiSettings.Mode.LIGHT);

        whenTheNetworksAreCounted(false, true);

        thenTheCountsRead("Users 5,000 | Files 600,000");
    }

    private void givenTheEngine() throws IOException {
        this.engine = FxTesting.engine(this.dir.resolve("njmule"));
        this.model = new EngineModel(this.engine, Platform::runLater);
    }

    private void givenTheWindowIn(Locale language, GuiSettings.Mode mode) throws Exception {
        this.window = window(language, mode);
    }

    private void whenEveryScreenIsShownInEveryLanguageAndMode() throws Exception {
        for (Locale language : I18n.LANGUAGES) {
            for (GuiSettings.Mode mode : List.of(GuiSettings.Mode.LIGHT, GuiSettings.Mode.DARK)) {
                MainWindow window = window(language, mode);
                for (String screen : SCREENS) {
                    String current = FxTesting.onFx(() -> {
                        window.show(screen);
                        String now = window.current();
                        window.root().applyCss();
                        window.root().layout();
                        return now;
                    });
                    this.shown.add(new Shown(language + " " + mode, screen, current));
                    FxTesting.onFx(() -> {
                        FxTesting.snapshot(
                                window.root(),
                                screen
                                        + "-"
                                        + language.toLanguageTag()
                                        + "-"
                                        + mode.name().toLowerCase(Locale.ROOT));
                        return null;
                    });
                }
            }
        }
    }

    private void whenEveryScreenIsLaidOutAtTheMinimumSizeInEveryLanguage() throws Exception {
        for (Locale language : I18n.LANGUAGES) {
            MainWindow window = window(language, GuiSettings.Mode.LIGHT);
            Dimension2D minimum = FxTesting.onFx(window::minimumSize);
            for (String screen : SCREENS) {
                List<String> cutButtons = FxTesting.onFx(() -> {
                    window.show(screen);
                    Parent root = window.root();
                    root.resize(minimum.getWidth(), minimum.getHeight());
                    root.applyCss();
                    root.layout();
                    List<String> wrong = new ArrayList<>();
                    // Laid out right to left, the window is mirrored about the middle of the scene.
                    Bounds area = root.localToScene(root.getLayoutBounds());
                    for (Node node : root.lookupAll(".button")) {
                        if (!(node instanceof ButtonBase button) || !treeVisible(button) || button.getScene() == null) {
                            continue;
                        }
                        Bounds bounds = button.localToScene(button.getBoundsInLocal());
                        // Buttons in a scrolled pane are reached by scrolling down.
                        boolean inside = bounds.getMinX() >= area.getMinX() - 0.5
                                && bounds.getMaxX() <= area.getMaxX() + 0.5
                                && (scrolled(button) || bounds.getMaxY() <= area.getMaxY() + 0.5);
                        if (button.getWidth() + 0.5 < button.prefWidth(-1) || !inside) {
                            wrong.add(screen + ": " + button.getText() + " " + bounds);
                        }
                    }
                    return wrong;
                });
                this.cut.put(language + " " + screen, cutButtons);
            }
        }
    }

    // Visible along with every pane it is in: a button of a hidden pane is not shown.
    private static boolean treeVisible(Node node) {
        for (Node at = node; at != null; at = at.getParent()) {
            if (!at.isVisible()) {
                return false;
            }
        }
        return true;
    }

    private void whenAMessageIsShown(String text) throws Exception {
        FxTesting.onFx(() -> this.window.message(text));
    }

    private void thenEachScreenShowedAsAsked() {
        assertThat(this.shown).hasSize(I18n.LANGUAGES.size() * 2 * SCREENS.size());
        for (Shown screen : this.shown) {
            assertThat(screen.current()).as(screen.where()).isEqualTo(screen.asked());
        }
    }

    private void thenNoButtonIsCut() {
        assertThat(this.cut).hasSize(I18n.LANGUAGES.size() * SCREENS.size());
        this.cut.forEach((where, wrong) -> assertThat(wrong).as(where).isEmpty());
    }

    private void thenTheMessagesShownAre(int count) throws Exception {
        assertThat(FxTesting.onFx(() -> this.window.messages().size())).isEqualTo(count);
    }

    private void thenConnectReads(String text) throws Exception {
        assertThat(FxTesting.onFx(() -> this.window.connectButton().getText())).isEqualTo(text);
    }

    // Two servers answer and one failed; Kad estimates 5,000 users sharing 600,000 files.
    private void whenTheNetworksAreCounted(boolean onAServer, boolean kadUp) throws Exception {
        ServerInfo first = Statuses.server("10.0.0.1:4661", "First", false);
        ServerInfo failed = new ServerInfo(
                "10.0.0.3:4661",
                "Gone",
                "",
                9_000,
                900_000,
                0,
                2,
                ServerInfo.Priority.NORMAL,
                0,
                0,
                0,
                0,
                "",
                false,
                false);
        Optional<KadInfo> kad = Optional.of(new KadInfo(
                kadUp,
                10,
                5,
                false,
                "00112233445566778899AABBCCDDEEFF",
                "",
                4672,
                false,
                false,
                KadInfo.Buddy.NONE,
                5_000,
                600_000));
        EngineStatus status = Statuses.of(onAServer ? Optional.of(first) : Optional.empty(), 0, false, kad, false);
        List<ServerInfo> servers = List.of(first, Statuses.server("10.0.0.2:4661", "Second", false), failed);
        this.counted = FxTesting.onFx(() -> this.window.networkCounts(status, servers));
    }

    private void thenTheCountsRead(String text) {
        assertThat(this.counted).isEqualTo(text);
    }

    private void thenTheEd2kChipTells(String text) throws Exception {
        assertThat(FxTesting.onFx(() -> this.window.ed2kChip().getText())).contains(text);
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
                    Formats.of(i18n),
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

    // A screen asked for in one language and mode, and the one the window showed.
    private record Shown(String where, String asked, String current) {}
}
