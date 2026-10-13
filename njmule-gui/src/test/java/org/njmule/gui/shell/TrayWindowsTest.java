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
import java.util.Optional;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.core.api.NjMuleEngine;
import org.njmule.core.config.Preferences;
import org.njmule.gui.FxTesting;
import org.njmule.gui.i18n.I18n;
import org.njmule.gui.infra.Formats;
import org.njmule.gui.infra.GuiSettings;
import org.njmule.gui.infra.Themes;
import org.njmule.gui.model.EngineModel;
import org.njmule.gui.ui.Gui;

@Timeout(60)
class TrayWindowsTest {

    @TempDir
    Path dir;

    private NjMuleEngine engine;
    private EngineModel model;
    private Gui gui;
    private Stage owner;
    private final List<String> actions = new ArrayList<>();

    @BeforeAll
    static void toolkit() throws Exception {
        FxTesting.start();
    }

    @AfterEach
    void close() throws Exception {
        if (this.owner != null) {
            FxTesting.onFx(this.owner::close);
        }
        if (this.model != null) {
            this.model.close();
        }
        if (this.engine != null) {
            this.engine.close();
        }
    }

    @Test
    void theMenuSetsTheLimitsAndCallsItsActions() throws Exception {
        this.start();
        TrayMenu menu = FxTesting.onFx(() -> new TrayMenu(this.gui, this.owner, new TrayMenu.Actions() {
            @Override
            public void restore() {
                TrayWindowsTest.this.actions.add("restore");
            }

            @Override
            public void options() {
                TrayWindowsTest.this.actions.add("options");
            }

            @Override
            public void exit() {
                TrayWindowsTest.this.actions.add("exit");
            }
        }));
        FxTesting.onFx(() -> menu.show(600, 500));
        assertThat(FxTesting.onFx(menu::isShowing)).isTrue();
        FxTesting.onFx(() -> {
            FxTesting.snapshot(menu.scene().getRoot(), "tray-menu");
            return null;
        });

        FxTesting.onFx(() -> {
            TextField down = (TextField)
                    menu.scene().getRoot().lookupAll(".text-field").iterator().next();
            down.setText("250");
            down.fireEvent(new javafx.event.ActionEvent());
        });
        FxTesting.await("the download limit", () -> this.preferences().maxDownloadKiBps() == 250);

        this.press(menu.scene(), this.gui.t("IDS_PW_PA"));
        FxTesting.await(
                "the throttled limits",
                () -> this.preferences().maxDownloadKiBps() == 1
                        && this.preferences().maxUploadKiBps() == 1);
        this.press(menu.scene(), this.gui.t("IDS_PW_UA"));
        FxTesting.await(
                "the full limits",
                () -> this.preferences().maxDownloadKiBps()
                                == this.preferences().downloadCapacityKiBps()
                        && this.preferences().maxUploadKiBps()
                                == this.preferences().uploadCapacityKiBps());

        for (String item : List.of("IDS_EM_PREFS", "IDS_MAIN_POPUP_RESTORE", "IDS_EXIT")) {
            FxTesting.onFx(() -> menu.show(600, 500));
            this.press(menu.scene(), this.gui.t(item));
        }
        assertThat(this.actions).containsExactly("options", "restore", "exit");
        assertThat(FxTesting.onFx(menu::isShowing))
                .as("an item closes the menu")
                .isFalse();
    }

    @Test
    void theStatusWindowShowsTheStateAtAGlance() throws Exception {
        this.start();
        MiniMule mini = FxTesting.onFx(() -> new MiniMule(this.gui, this.owner, () -> this.dir, new MiniMule.Actions() {
            @Override
            public void openIncoming() {
                TrayWindowsTest.this.actions.add("incoming");
            }

            @Override
            public void options() {
                TrayWindowsTest.this.actions.add("options");
            }

            @Override
            public void restore() {
                TrayWindowsTest.this.actions.add("restore");
            }
        }));
        FxTesting.onFx(mini::show);
        FxTesting.onFx(() -> {
            FxTesting.snapshot(mini.scene().getRoot(), "minimule");
            return null;
        });

        List<String> values = FxTesting.onFx(() -> mini.scene().getRoot().lookupAll(".minimule-value").stream()
                .map(n -> ((Label) n).getText())
                .toList());
        assertThat(values).hasSize(5);
        assertThat(values.get(0)).isEqualTo(this.gui.t("IDS_NO"));
        assertThat(values.get(3)).isEqualTo("0");
        assertThat(values.get(4)).isNotEqualTo("?");
        FxTesting.onFx(() -> {
            for (Node node : mini.scene().getRoot().lookupAll(".button")) {
                ((Button) node).fire();
            }
        });
        assertThat(this.actions).containsExactly("incoming", "options", "restore");
    }

    private Preferences preferences() {
        return this.engine.preferences();
    }

    private void press(Scene scene, String text) throws Exception {
        FxTesting.onFx(() -> {
            for (Node node : scene.getRoot().lookupAll(".tray-item")) {
                if (node instanceof Button button && text.equals(button.getText())) {
                    button.fire();
                    return;
                }
            }
            throw new AssertionError("no item " + text);
        });
    }

    private void start() throws Exception {
        this.engine = FxTesting.engine(this.dir.resolve("njmule"));
        this.model = new EngineModel(this.engine, Platform::runLater);
        FxTesting.onFx(() -> {
            GuiSettings settings = GuiSettings.load(this.engine.directories().gui());
            I18n i18n = I18n.of(Locale.ITALIAN);
            this.gui = new Gui(
                    i18n,
                    new Formats(i18n.locale()),
                    this.model,
                    new Themes(Themes.Family.NORD, GuiSettings.Mode.LIGHT),
                    settings,
                    this.engine.directories(),
                    text -> {},
                    () -> {});
            this.owner = new Stage();
            this.owner.show();
        });
        this.model.start();
        FxTesting.await(
                "the preferences", () -> this.model.preferencesProperty().get() != null);
        assertThat(Optional.ofNullable(this.model.preferencesProperty().get())).isPresent();
    }
}
