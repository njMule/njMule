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
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.core.api.EngineStatus;
import org.njmule.core.api.NjMuleEngine;
import org.njmule.core.config.Preferences;
import org.njmule.gui.Engines;
import org.njmule.gui.FxTesting;
import org.njmule.gui.Statuses;
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
    void openingTheMenuNeverChangesTheLimits() throws Exception {
        AtomicInteger writes = new AtomicInteger();
        this.start(real -> Engines.overriding(real, Map.of("updatePreferences", args -> {
            writes.incrementAndGet();
            return real.updatePreferences(change(args[0]));
        })));
        this.limits(1000, 800);
        TrayMenu menu = FxTesting.onFx(() -> new TrayMenu(this.gui, this.owner, new TrayMenu.Actions() {
            @Override
            public void restore() {}

            @Override
            public void options() {}

            @Override
            public void exit() {}
        }));
        FxTesting.onFx(() -> menu.show(600, 500));
        FxTesting.onFx(menu::hide);
        // The line got slower meanwhile: the slider's top falls below its old position.
        this.limits(500, 300);

        FxTesting.onFx(() -> menu.show(600, 500));
        assertThat(writes).as("opening the menu").hasValue(0);
        assertThat(this.preferences().maxDownloadKiBps()).isEqualTo(300);

        TextField down = FxTesting.onFx(() -> (TextField)
                menu.scene().getRoot().lookupAll(".text-field").iterator().next());
        FxTesting.onFx(() -> {
            down.setText("900");
            down.fireEvent(new javafx.event.ActionEvent());
        });
        assertThat(writes).as("a limit above the slider's top").hasValue(0);
        assertThat(FxTesting.onFx(() -> down.getText())).isEqualTo("300");
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

    @Test
    void theMenuConnectsTakesTheLimitsLetGoAndCloses() throws Exception {
        List<String> commands = new CopyOnWriteArrayList<>();
        AtomicInteger writes = new AtomicInteger();
        this.start(real -> Engines.overriding(
                real,
                Map.of(
                        "connect",
                        args -> {
                            commands.add("connect");
                            return CompletableFuture.completedFuture(null);
                        },
                        "disconnect",
                        args -> {
                            commands.add("disconnect");
                            return CompletableFuture.completedFuture(null);
                        },
                        "updatePreferences",
                        args -> {
                            writes.incrementAndGet();
                            return real.updatePreferences(change(args[0]));
                        })));
        this.limits(1000, 800);
        TrayMenu menu = FxTesting.onFx(() -> new TrayMenu(this.gui, this.owner, new TrayMenu.Actions() {
            @Override
            public void restore() {}

            @Override
            public void options() {}

            @Override
            public void exit() {}
        }));
        String connect = this.gui.t("IDS_MAIN_BTN_CONNECT");
        String disconnect = this.gui.t("IDS_MAIN_BTN_DISCONNECT");

        FxTesting.onFx(() -> menu.show(600, 500));
        assertThat(FxTesting.onFx(() -> this.item(menu.scene(), disconnect).isDisabled()))
                .isTrue();
        this.press(menu.scene(), connect);
        FxTesting.onFx(() -> menu.show(600, 500));
        assertThat(FxTesting.onFx(() -> this.item(menu.scene(), connect).isDisabled()))
                .as("trying the servers")
                .isTrue();
        this.press(menu.scene(), disconnect);
        assertThat(commands).containsExactly("connect", "disconnect");

        // A slider dragged sets its limit once it is let go, and only when it moved.
        FxTesting.onFx(() -> menu.show(600, 500));
        Slider up = FxTesting.onFx(
                () -> (Slider) menu.scene().getRoot().lookupAll(".slider").toArray()[1]);
        FxTesting.onFx(() -> {
            up.setValueChanging(true);
            up.setValue(300);
        });
        assertThat(writes).as("while dragged").hasValue(0);
        FxTesting.onFx(() -> up.setValueChanging(false));
        FxTesting.await("the upload limit", () -> this.preferences().maxUploadKiBps() == 300);
        FxTesting.await(
                "the new preferences",
                () -> this.model.preferencesProperty().get().maxUploadKiBps() == 300);
        FxTesting.onFx(() -> {
            up.setValueChanging(true);
            up.setValueChanging(false);
        });
        assertThat(writes).hasValue(1);

        // Escape closes the menu; other keys do not.
        FxTesting.onFx(() -> menu.scene().getRoot().fireEvent(FxTesting.key(KeyCode.A, false)));
        assertThat(FxTesting.onFx(menu::isShowing)).isTrue();
        FxTesting.onFx(() -> menu.scene().getRoot().fireEvent(FxTesting.key(KeyCode.ESCAPE, false)));
        assertThat(FxTesting.onFx(menu::isShowing)).isFalse();
    }

    @Test
    void theStatusWindowCountsKadAndAFolderNotMadeYet() throws Exception {
        EngineStatus onKad = Statuses.of(Optional.empty(), 0, false, Optional.of(Statuses.kad(true, false, "")), false);
        this.start(
                real -> Engines.overriding(real, Map.of("status", args -> CompletableFuture.completedFuture(onKad))));
        FxTesting.await(
                "on Kad", () -> Objects.equals(this.model.statusProperty().get(), onKad));
        MiniMule mini = FxTesting.onFx(() -> new MiniMule(
                this.gui,
                this.owner,
                () -> this.dir.resolve("incoming").resolve("not made yet"),
                new MiniMule.Actions() {
                    @Override
                    public void openIncoming() {}

                    @Override
                    public void options() {}

                    @Override
                    public void restore() {}
                }));

        FxTesting.onFx(mini::show);
        assertThat(FxTesting.onFx(mini::isShowing)).isTrue();
        List<String> values = FxTesting.onFx(() -> mini.scene().getRoot().lookupAll(".minimule-value").stream()
                .map(n -> ((Label) n).getText())
                .toList());
        assertThat(values.getFirst()).as("connected to Kad").isEqualTo(this.gui.t("IDS_YES"));
        assertThat(values.getLast())
                .as("the free space of the disk it will be on")
                .isNotEqualTo("?");
        FxTesting.onFx(mini::hide);
        assertThat(FxTesting.onFx(mini::isShowing)).isFalse();
    }

    private Button item(Scene scene, String text) {
        return scene.getRoot().lookupAll(".tray-item").stream()
                .filter(n -> n instanceof Button b && text.equals(b.getText()))
                .map(Button.class::cast)
                .findFirst()
                .orElseThrow();
    }

    private Preferences preferences() {
        return this.engine.preferences();
    }

    @SuppressWarnings("unchecked")
    private static UnaryOperator<Preferences> change(Object change) {
        return (UnaryOperator<Preferences>) change;
    }

    // Changed past the menu, as the settings screen or the scheduler do.
    private void limits(int capacity, int limit) throws Exception {
        this.engine
                .updatePreferences(p -> p.toBuilder()
                        .downloadCapacityKiBps(capacity)
                        .uploadCapacityKiBps(capacity)
                        .maxDownloadKiBps(limit)
                        .maxUploadKiBps(limit)
                        .build())
                .get(10, TimeUnit.SECONDS);
        FxTesting.await(
                "the new limits", () -> this.model.preferencesProperty().get().maxDownloadKiBps() == limit);
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
        this.start(UnaryOperator.identity());
    }

    // The model sees the engine through wrap; the test sees the real one.
    private void start(UnaryOperator<NjMuleEngine> wrap) throws Exception {
        this.engine = FxTesting.engine(this.dir.resolve("njmule"));
        this.model = new EngineModel(wrap.apply(this.engine), Platform::runLater);
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
