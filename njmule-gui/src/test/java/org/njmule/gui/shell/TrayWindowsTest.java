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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
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
    private final List<String> commands = new CopyOnWriteArrayList<>();
    private final AtomicInteger writes = new AtomicInteger();
    private TrayMenu menu;
    private Slider slider;
    private MiniMule mini;

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
    void shouldSetTheDownloadLimitFromItsSlider() throws Exception {
        givenAStartedGui();
        givenATrayMenuThatRecordsItsActions();
        givenTheMenuShownAsPictured("tray-menu");

        whenTheDownloadSliderIsMovedTo(250);

        thenTheDownloadLimitBecomes(250);
    }

    @Test
    void shouldThrottleAndLiftBothLimitsFromItsItems() throws Exception {
        givenAStartedGui();
        givenATrayMenuThatRecordsItsActions();
        givenTheMenuShown();
        givenTheItemPressed("nj.tray.slowDown");
        givenBothLimitsBecame(1);

        whenTheItemIsPressed("nj.tray.fullSpeed");

        thenBothLimitsBecomeTheLineCapacities();
    }

    @Test
    void shouldCallTheActionsOfItsItemsAndCloseOnEach() throws Exception {
        givenAStartedGui();
        givenATrayMenuThatRecordsItsActions();

        whenTheItemsArePressedInTurn("IDS_EM_PREFS", "nj.tray.show", "nj.exit.quit");

        thenTheActionsCalledAre("options", "restore", "exit");
        thenTheMenuIsShowing(false, "an item closes the menu");
    }

    @Test
    void shouldNeverChangeTheLimitsWhenOpened() throws Exception {
        givenAGuiCountingThePreferenceWrites();
        givenTheLimits(1000, 800);
        givenATrayMenu();
        givenTheMenuShownAndHidden();
        // The line got slower meanwhile: the slider's top falls below its old position.
        givenTheLimits(500, 300);

        whenTheMenuIsShown();

        thenThePreferencesWereWrittenTimes(0, "opening the menu");
        thenTheDownloadLimitIs(300);
        thenTheDownloadSpeedReads(300, "the limit as set");
    }

    @Test
    void shouldShowTheStateAtAGlance() throws Exception {
        givenAStartedGui();
        givenAStatusWindowThatRecordsItsActions();

        whenTheStatusWindowIsShownAsPictured("minimule");

        thenTheStatusShowsValues(5);
        thenTheFirstValueReadsTheText("IDS_NOTCONNECTED", "not connected");
        thenTheValueReads(3, "0");
        thenTheLastValueIsKnown("the free space of the incoming folder");
    }

    @Test
    void shouldCallTheActionsOfTheStatusWindowButtons() throws Exception {
        givenAStartedGui();
        givenAStatusWindowThatRecordsItsActions();
        givenTheStatusWindowShown();

        whenAllItsButtonsArePressed();

        thenTheActionsCalledAre("incoming", "options", "restore");
    }

    @Test
    void shouldConnectAndDisconnectFromItsItems() throws Exception {
        givenAGuiRecordingItsCommandsAndPreferenceWrites();
        givenATrayMenu();
        givenTheMenuShownWithTheItemOff("IDS_MAIN_BTN_DISCONNECT", "not connected");
        givenTheItemPressed("IDS_MAIN_BTN_CONNECT");
        givenTheMenuShownWithTheItemOff("IDS_MAIN_BTN_CONNECT", "trying the servers");

        whenTheItemIsPressed("IDS_MAIN_BTN_DISCONNECT");

        thenTheCommandsSentAre("connect", "disconnect");
    }

    @Test
    void shouldShowOnlyTheConnectionItemThatApplies() throws Exception {
        givenAGuiRecordingItsCommandsAndPreferenceWrites();
        givenATrayMenu();

        whenTheMenuIsShown();

        thenTheItemShows("IDS_MAIN_BTN_CONNECT", true);
        thenTheItemShows("IDS_MAIN_BTN_DISCONNECT", false);
    }

    @Test
    void shouldSetTheUploadLimitOnceItsSliderIsLetGoAndOnlyWhenItMoved() throws Exception {
        givenAGuiRecordingItsCommandsAndPreferenceWrites();
        givenTheLimits(1000, 800);
        givenATrayMenu();
        givenTheMenuShown();
        givenTheUploadSliderDraggedTo(300);
        givenTheSliderLetGo();
        givenTheUploadLimitBecame(300);

        whenTheSliderIsDraggedAndLetGoWithoutMoving();

        thenThePreferencesWereWrittenTimes(1, "only for the slider that moved");
    }

    @Test
    void shouldCloseOnEscapeButNotOnOtherKeys() throws Exception {
        givenAGuiRecordingItsCommandsAndPreferenceWrites();
        givenATrayMenu();
        givenTheMenuShown();
        givenTheMenuStillShowingAfterTheKey(KeyCode.A);

        whenTheKeyIsPressed(KeyCode.ESCAPE);

        thenTheMenuIsShowing(false, "closed by Escape");
    }

    @Test
    void shouldCountKadAndAFolderNotMadeYet() throws Exception {
        givenAGuiOnKad();
        givenAStatusWindowForAFolderNotMadeYet();

        whenTheStatusWindowIsShown();

        thenTheStatusWindowIsShowing(true);
        thenTheFirstValueReadsTheText("IDS_CONNECTED", "connected to Kad");
        thenTheLastValueIsKnown("the free space of the disk it will be on");
    }

    @Test
    void shouldHideTheStatusWindow() throws Exception {
        givenAGuiOnKad();
        givenAStatusWindowForAFolderNotMadeYet();
        givenTheStatusWindowShown();

        whenTheStatusWindowIsHidden();

        thenTheStatusWindowIsShowing(false);
    }

    private void givenAStartedGui() throws Exception {
        start();
    }

    private void givenAGuiCountingThePreferenceWrites() throws Exception {
        start(real -> Engines.overriding(real, Map.of("updatePreferences", args -> {
            this.writes.incrementAndGet();
            return real.updatePreferences(change(args[0]));
        })));
    }

    private void givenAGuiRecordingItsCommandsAndPreferenceWrites() throws Exception {
        start(real -> Engines.overriding(
                real,
                Map.of(
                        "connect",
                        args -> {
                            this.commands.add("connect");
                            return CompletableFuture.completedFuture(null);
                        },
                        "disconnect",
                        args -> {
                            this.commands.add("disconnect");
                            return CompletableFuture.completedFuture(null);
                        },
                        "updatePreferences",
                        args -> {
                            this.writes.incrementAndGet();
                            return real.updatePreferences(change(args[0]));
                        })));
    }

    private void givenAGuiOnKad() throws Exception {
        EngineStatus onKad = Statuses.of(Optional.empty(), 0, false, Optional.of(Statuses.kad(true, false, "")), false);
        start(real -> Engines.overriding(real, Map.of("status", args -> CompletableFuture.completedFuture(onKad))));
        FxTesting.await(
                "on Kad", () -> Objects.equals(this.model.statusProperty().get(), onKad));
    }

    private void givenTheLimits(int capacity, int limit) throws Exception {
        limits(capacity, limit);
    }

    private void givenATrayMenuThatRecordsItsActions() throws Exception {
        this.menu = FxTesting.onFx(() -> new TrayMenu(this.gui, this.owner, new TrayMenu.Actions() {
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
    }

    private void givenATrayMenu() throws Exception {
        this.menu = FxTesting.onFx(() -> new TrayMenu(this.gui, this.owner, new TrayMenu.Actions() {
            @Override
            public void restore() {}

            @Override
            public void options() {}

            @Override
            public void exit() {}
        }));
    }

    private void givenTheMenuShown() throws Exception {
        FxTesting.onFx(() -> this.menu.show(600, 500));
    }

    private void givenTheMenuShownAsPictured(String picture) throws Exception {
        FxTesting.onFx(() -> this.menu.show(600, 500));
        assertThat(FxTesting.onFx(this.menu::isShowing)).isTrue();
        FxTesting.onFx(() -> {
            FxTesting.snapshot(this.menu.scene().getRoot(), picture);
            return null;
        });
    }

    private void givenTheMenuShownAndHidden() throws Exception {
        FxTesting.onFx(() -> this.menu.show(600, 500));
        FxTesting.onFx(this.menu::hide);
    }

    private void thenTheItemShows(String item, boolean shows) throws Exception {
        assertThat(FxTesting.onFx(
                        () -> item(this.menu.scene(), this.gui.t(item)).isVisible()))
                .as(item)
                .isEqualTo(shows);
    }

    private void givenTheMenuShownWithTheItemOff(String item, String why) throws Exception {
        FxTesting.onFx(() -> this.menu.show(600, 500));
        assertThat(FxTesting.onFx(
                        () -> item(this.menu.scene(), this.gui.t(item)).isDisabled()))
                .as(why)
                .isTrue();
    }

    private void givenTheMenuStillShowingAfterTheKey(KeyCode key) throws Exception {
        FxTesting.onFx(() -> this.menu.scene().getRoot().fireEvent(FxTesting.key(key, false)));
        assertThat(FxTesting.onFx(this.menu::isShowing)).isTrue();
    }

    private void givenTheItemPressed(String item) throws Exception {
        press(this.menu.scene(), this.gui.t(item));
    }

    private void givenBothLimitsBecame(int kib) throws Exception {
        FxTesting.await(
                "the throttled limits",
                () -> preferences().maxDownloadKiBps() == kib && preferences().maxUploadKiBps() == kib);
    }

    private void givenTheUploadSliderDraggedTo(int kib) throws Exception {
        this.slider = FxTesting.onFx(
                () -> (Slider) this.menu.scene().getRoot().lookupAll(".slider").toArray()[1]);
        int top = Math.max(preferences().uploadCapacityKiBps(), preferences().maxUploadKiBps());
        FxTesting.onFx(() -> {
            this.slider.setValueChanging(true);
            this.slider.setValue(SpeedLimits.position(kib, top));
        });
        assertThat(this.writes).as("while dragged").hasValue(0);
    }

    private void givenTheSliderLetGo() throws Exception {
        FxTesting.onFx(() -> this.slider.setValueChanging(false));
    }

    private void givenTheUploadLimitBecame(int kib) throws Exception {
        FxTesting.await("the upload limit", () -> preferences().maxUploadKiBps() == kib);
        FxTesting.await(
                "the new preferences",
                () -> this.model.preferencesProperty().get().maxUploadKiBps() == kib);
    }

    private void givenAStatusWindowThatRecordsItsActions() throws Exception {
        this.mini = FxTesting.onFx(() -> new MiniMule(this.gui, this.owner, () -> this.dir, new MiniMule.Actions() {
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
    }

    private void givenAStatusWindowForAFolderNotMadeYet() throws Exception {
        this.mini = FxTesting.onFx(() -> new MiniMule(
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
    }

    private void givenTheStatusWindowShown() throws Exception {
        FxTesting.onFx(this.mini::show);
    }

    private void whenTheDownloadSliderIsMovedTo(int kib) throws Exception {
        int top = Math.max(preferences().downloadCapacityKiBps(), preferences().maxDownloadKiBps());
        FxTesting.onFx(() -> ((Slider) this.menu
                        .scene()
                        .getRoot()
                        .lookupAll(".slider")
                        .iterator()
                        .next())
                .setValue(SpeedLimits.position(kib, top)));
    }

    private void whenTheItemIsPressed(String item) throws Exception {
        press(this.menu.scene(), this.gui.t(item));
    }

    private void whenTheItemsArePressedInTurn(String... items) throws Exception {
        for (String item : items) {
            FxTesting.onFx(() -> this.menu.show(600, 500));
            press(this.menu.scene(), this.gui.t(item));
        }
    }

    private void whenTheMenuIsShown() throws Exception {
        FxTesting.onFx(() -> this.menu.show(600, 500));
    }

    private void whenTheSliderIsDraggedAndLetGoWithoutMoving() throws Exception {
        FxTesting.onFx(() -> {
            this.slider.setValueChanging(true);
            this.slider.setValueChanging(false);
        });
    }

    private void whenTheKeyIsPressed(KeyCode key) throws Exception {
        FxTesting.onFx(() -> this.menu.scene().getRoot().fireEvent(FxTesting.key(key, false)));
    }

    private void whenTheStatusWindowIsShownAsPictured(String picture) throws Exception {
        FxTesting.onFx(this.mini::show);
        FxTesting.onFx(() -> {
            FxTesting.snapshot(this.mini.scene().getRoot(), picture);
            return null;
        });
    }

    private void whenTheStatusWindowIsShown() throws Exception {
        FxTesting.onFx(this.mini::show);
    }

    private void whenAllItsButtonsArePressed() throws Exception {
        FxTesting.onFx(() -> {
            for (Node node : this.mini.scene().getRoot().lookupAll(".button")) {
                ((Button) node).fire();
            }
        });
    }

    private void whenTheStatusWindowIsHidden() throws Exception {
        FxTesting.onFx(this.mini::hide);
    }

    private void thenTheDownloadLimitBecomes(int kib) throws Exception {
        FxTesting.assertEventually("the download limit", () -> preferences().maxDownloadKiBps() == kib);
    }

    private void thenBothLimitsBecomeTheLineCapacities() throws Exception {
        FxTesting.assertEventually(
                "the full limits",
                () -> preferences().maxDownloadKiBps() == preferences().downloadCapacityKiBps()
                        && preferences().maxUploadKiBps() == preferences().uploadCapacityKiBps());
    }

    private void thenTheActionsCalledAre(String... called) {
        assertThat(this.actions).containsExactly(called);
    }

    private void thenTheMenuIsShowing(boolean showing, String why) throws Exception {
        assertThat(FxTesting.onFx(this.menu::isShowing)).as(why).isEqualTo(showing);
    }

    private void thenThePreferencesWereWrittenTimes(int times, String why) {
        assertThat(this.writes).as(why).hasValue(times);
    }

    private void thenTheDownloadLimitIs(int kib) {
        assertThat(preferences().maxDownloadKiBps()).isEqualTo(kib);
    }

    private void thenTheDownloadSpeedReads(int kib, String why) throws Exception {
        assertThat(FxTesting.onFx(() -> ((Label) this.menu
                                .scene()
                                .getRoot()
                                .lookupAll(".speed-value")
                                .iterator()
                                .next())
                        .getText()))
                .as(why)
                .isEqualTo(this.gui.formats().rate(kib * 1024L));
    }

    private void thenTheCommandsSentAre(String... sent) {
        assertThat(this.commands).containsExactly(sent);
    }

    private void thenTheStatusWindowIsShowing(boolean showing) throws Exception {
        assertThat(FxTesting.onFx(this.mini::isShowing)).isEqualTo(showing);
    }

    private void thenTheStatusShowsValues(int count) throws Exception {
        assertThat(statusValues()).hasSize(count);
    }

    private void thenTheFirstValueReadsTheText(String text, String why) throws Exception {
        assertThat(statusValues().getFirst()).as(why).isEqualTo(this.gui.t(text));
    }

    private void thenTheValueReads(int index, String value) throws Exception {
        assertThat(statusValues().get(index)).isEqualTo(value);
    }

    private void thenTheLastValueIsKnown(String why) throws Exception {
        assertThat(statusValues().getLast()).as(why).isNotEqualTo("?");
    }

    private List<String> statusValues() throws Exception {
        return FxTesting.onFx(() -> this.mini.scene().getRoot().lookupAll(".minimule-value").stream()
                .map(n -> ((Label) n).getText())
                .toList());
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
        FxTesting.await(this.engine.updatePreferences(p -> p.toBuilder()
                .downloadCapacityKiBps(capacity)
                .uploadCapacityKiBps(capacity)
                .maxDownloadKiBps(limit)
                .maxUploadKiBps(limit)
                .build()));
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
        start(UnaryOperator.identity());
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
                    Formats.of(i18n),
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
