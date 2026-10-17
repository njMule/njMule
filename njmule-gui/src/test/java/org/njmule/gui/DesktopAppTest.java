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

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import javafx.geometry.Rectangle2D;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.app.LaunchOptions;
import org.njmule.core.api.EngineEvent;
import org.njmule.core.api.ServerInfo;
import org.njmule.gui.infra.GuiSettings;
import org.njmule.gui.shell.TrayMenu;
import org.njmule.testkit.FreePorts;

@Timeout(60)
class DesktopAppTest {

    @TempDir
    Path dir;

    private DesktopApp app;
    private boolean closed;
    private Stage stage;
    private List<Window> before;
    private TrayMenu menu;
    private Button oldNetwork;
    private String oldNetworkText;
    // The JVM's languages before the test, which the app changes.
    private final Locale language = Locale.getDefault();
    private final Locale display = Locale.getDefault(Locale.Category.DISPLAY);
    private final Locale format = Locale.getDefault(Locale.Category.FORMAT);

    @BeforeAll
    static void toolkit() throws Exception {
        FxTesting.start();
    }

    @AfterEach
    void close() throws Exception {
        if (this.stage != null) {
            FxTesting.onFx(this.stage::close);
        }
        if (this.app != null && !this.closed) {
            this.app.close();
        }
        Locale.setDefault(this.language);
        Locale.setDefault(Locale.Category.DISPLAY, this.display);
        Locale.setDefault(Locale.Category.FORMAT, this.format);
    }

    @Test
    void shouldOpenShowRememberTheWindowAndClose() throws Exception {
        givenAnAppOnAFreeTcpPort();
        givenTheWindowsShowingSoFar();
        givenTheAppShown();
        givenTheTitleStartsWith("njMule");
        givenADialogOfTheWindowShowsTheAppsIcon();
        givenTheCurrentScreenIs("transfers");
        givenTheTrayMenuNoted();
        givenTheAppRelaunchedOnTheScreen("search");
        givenTheTrayMenuFollowsTheLanguage();
        givenTheCurrentScreenIs("search");
        givenTheEngineRuns();
        givenTheWindowClosed();
        givenNoWindowOfOursKeepsNjMuleRunning();

        whenTheAppCloses();

        thenTheEngineIsStopped();
        thenTheSettingsWereSaved();
        thenTheSavedScreenIs("search");
        thenTheWindowPlaceWasSaved();
    }

    @Test
    void shouldLeaveAWindowPlaceOffEveryScreenAndNeverSaveTheOneOfAnIcon() throws Exception {
        // As left by a monitor since unplugged.
        givenTheWindowPlace("window.x=-50000\nwindow.y=-50000\nwindow.width=900\nwindow.height=700\n");
        givenAnApp();
        givenTheAppShownWithoutTheTray();
        givenTheWindowShowsOnAScreen();
        givenTheWindowMovedTo(10, 20);
        givenTheWindowMinimisedAndClosed();

        whenTheAppCloses();

        thenTheSavedPlaceIsNotAtX(10);
    }

    @Test
    void shouldStopUpdatingTheWindowLeftByALanguageChange() throws Exception {
        givenAnApp();
        givenTheAppShown();
        givenTheNetworkStateNoted();
        givenTheAppRelaunched();

        whenTheEngineConnectsTo("81.2.3.4:4661", "Big");

        thenTheNetworkStateTells("Big");
        thenTheOldNetworkStateIsNotUpdated();
    }

    @Test
    void shouldSpeakTheSystemLanguageInJavaFxTextsOnceChosenAgain() throws Exception {
        givenTheSystemLanguage(Locale.ITALIAN);
        givenTheLanguageChosen("en");
        givenAnApp();
        givenTheAppShownWithoutTheTray();
        givenTheCancelButtonReads("Cancel");
        givenTheSystemLanguageChosen();

        whenTheAppRelaunches();

        thenTheCancelButtonReads("Annulla");
    }

    @Test
    void shouldGiveTheJvmTheSystemLanguageBackOnClosing() throws Exception {
        givenTheSystemLanguage(Locale.ITALIAN);
        givenTheLanguageChosen("en");
        givenAnApp();
        givenTheAppShownWithoutTheTray();
        givenTheCancelButtonReads("Cancel");

        whenTheAppCloses();

        thenTheLanguageOfTheJvmIs(Locale.ITALIAN);
    }

    private void givenTheSystemLanguage(Locale system) {
        Locale.setDefault(system);
    }

    private void givenTheLanguageChosen(String tag) throws Exception {
        Path gui = Files.createDirectories(this.dir.resolve("gui"));
        Files.writeString(gui.resolve(GuiSettings.FILE_NAME), "language=" + tag + "\n");
    }

    private void givenTheCancelButtonReads(String text) {
        assertThat(ButtonType.CANCEL.getText()).isEqualTo(text);
    }

    private void givenTheSystemLanguageChosen() throws Exception {
        FxTesting.onFx(() -> this.app.gui().settings().setLanguage(Optional.empty()));
    }

    private void givenAnAppOnAFreeTcpPort() throws Exception {
        Path config = Files.createDirectories(this.dir.resolve("config"));
        int port = FreePorts.tcp();
        Files.writeString(config.resolve("njmule.properties"), "net.tcpPort=" + port + "\nnet.kad.enabled=false\n");
        this.app = DesktopApp.open(LaunchOptions.parse("--config-dir", this.dir.toString()));
    }

    private void givenAnApp() throws Exception {
        this.app = open();
    }

    private void givenTheWindowPlace(String settings) throws Exception {
        Path gui = Files.createDirectories(this.dir.resolve("gui"));
        Files.writeString(gui.resolve(GuiSettings.FILE_NAME), settings);
    }

    private void givenTheWindowsShowingSoFar() throws Exception {
        this.before = FxTesting.onFx(DesktopAppTest::showing);
    }

    private void givenTheAppShown() throws Exception {
        FxTesting.onFx(() -> {
            this.stage = new Stage();
            this.app.show(this.stage);
        });
    }

    // Without the tray: minimising must never put a real icon in the system tray.
    private void givenTheAppShownWithoutTheTray() throws Exception {
        FxTesting.onFx(() -> {
            this.stage = new Stage();
            this.app.show(this.stage, false);
        });
    }

    private void givenTheTitleStartsWith(String title) throws Exception {
        assertThat(FxTesting.onFx(() -> this.stage.getTitle())).startsWith(title);
    }

    private void givenADialogOfTheWindowShowsTheAppsIcon() throws Exception {
        assertThat(FxTesting.onFx(() -> {
                    Stage dialog = new Stage();
                    dialog.initOwner(this.stage);
                    dialog.show();
                    int icons = dialog.getIcons().size();
                    dialog.close();
                    return icons;
                }))
                .as("a dialog shows the app's icon too")
                .isPositive();
    }

    private void givenTheCurrentScreenIs(String screen) throws Exception {
        assertThat(FxTesting.onFx(() -> this.app.window().current())).isEqualTo(screen);
    }

    private void givenTheTrayMenuNoted() throws Exception {
        this.menu = FxTesting.onFx(this.app::trayMenu);
    }

    private void givenTheAppRelaunchedOnTheScreen(String screen) throws Exception {
        FxTesting.onFx(() -> {
            this.app.window().show(screen);
            this.app.relaunch();
        });
    }

    private void givenTheTrayMenuFollowsTheLanguage() throws Exception {
        if (this.menu != null) {
            assertThat(FxTesting.onFx(this.app::trayMenu))
                    .as("the tray menu follows the language")
                    .isNotSameAs(this.menu);
        }
    }

    private void givenTheEngineRuns() {
        assertThat(this.app.engine().isRunning()).isTrue();
    }

    private void givenTheWindowClosed() throws Exception {
        FxTesting.onFx(() -> this.stage.close());
    }

    private void givenNoWindowOfOursKeepsNjMuleRunning() throws Exception {
        assertThat(FxTesting.onFx(DesktopAppTest::showing))
                .as("no window of ours keeps njMule running")
                .containsExactlyInAnyOrderElementsOf(this.before);
    }

    private void givenTheWindowShowsOnAScreen() throws Exception {
        Rectangle2D shown = FxTesting.onFx(() ->
                new Rectangle2D(this.stage.getX(), this.stage.getY(), this.stage.getWidth(), this.stage.getHeight()));
        assertThat(FxTesting.onFx(() -> Screen.getScreensForRectangle(shown)))
                .as("the window shows on a screen")
                .isNotEmpty();
    }

    private void givenTheWindowMovedTo(double x, double y) throws Exception {
        FxTesting.onFx(() -> {
            this.stage.setX(x);
            this.stage.setY(y);
        });
        assertThat(FxTesting.onFx(() -> this.stage.getX())).isEqualTo(x);
    }

    // Minimised, the place a window reports is not where it shows: on Windows -32000.
    private void givenTheWindowMinimisedAndClosed() throws Exception {
        FxTesting.onFx(() -> {
            this.stage.setIconified(true);
            this.stage.close();
        });
    }

    private void givenTheNetworkStateNoted() throws Exception {
        this.oldNetwork = FxTesting.onFx(() -> (Button) this.app.window().root().lookup("#network"));
        this.oldNetworkText = FxTesting.onFx(() -> this.oldNetwork.getTooltip().getText());
    }

    private void givenTheAppRelaunched() throws Exception {
        FxTesting.onFx(this.app::relaunch);
    }

    private void whenTheAppRelaunches() throws Exception {
        FxTesting.onFx(this.app::relaunch);
    }

    private void whenTheAppCloses() {
        this.closed = true;
        this.app.close();
    }

    private void whenTheEngineConnectsTo(String address, String name) throws Exception {
        ServerInfo server = new ServerInfo(
                address, name, "", 10, 20, 30, 0, ServerInfo.Priority.NORMAL, 0, 0, 0, 0, "", false, false);
        FxTesting.onFx(() -> this.app.model().handle(new EngineEvent.ServerConnecting(server)));
    }

    private void thenTheCancelButtonReads(String text) {
        assertThat(ButtonType.CANCEL.getText()).isEqualTo(text);
    }

    private void thenTheLanguageOfTheJvmIs(Locale language) {
        assertThat(Locale.getDefault()).isEqualTo(language);
    }

    private void thenTheEngineIsStopped() {
        assertThat(this.app.engine().isRunning()).isFalse();
    }

    private void thenTheSettingsWereSaved() {
        assertThat(GuiSettings.load(this.dir.resolve("gui")).existed()).isTrue();
    }

    private void thenTheSavedScreenIs(String screen) {
        assertThat(GuiSettings.load(this.dir.resolve("gui")).screen()).isEqualTo(screen);
    }

    private void thenTheWindowPlaceWasSaved() {
        assertThat(GuiSettings.load(this.dir.resolve("gui")).window()).isPresent();
    }

    private void thenTheSavedPlaceIsNotAtX(double x) {
        assertThat(GuiSettings.load(this.dir.resolve("gui")).window())
                .as("the place before the window was minimised")
                .hasValueSatisfying(b -> assertThat(b[0]).isNotEqualTo(x));
    }

    private void thenTheNetworkStateTells(String text) throws Exception {
        // The network control's tooltip tells each network's state.
        Button network = FxTesting.onFx(() -> (Button) this.app.window().root().lookup("#network"));
        assertThat(FxTesting.onFx(() -> network.getTooltip().getText())).contains(text);
    }

    private void thenTheOldNetworkStateIsNotUpdated() throws Exception {
        assertThat(FxTesting.onFx(() -> this.oldNetwork.getTooltip().getText()))
                .as("the old window is not updated any more")
                .isEqualTo(this.oldNetworkText);
    }

    // An app in the test's folder, on free ports and without Kad: never on the ports of a running eMule.
    private DesktopApp open() throws Exception {
        Path config = Files.createDirectories(this.dir.resolve("config"));
        int port = FreePorts.tcp();
        int udp = FreePorts.udp();
        Files.writeString(
                config.resolve("njmule.properties"),
                "net.tcpPort=" + port + "\nnet.udpPort=" + udp + "\nnet.kad.enabled=false\n");
        return DesktopApp.open(LaunchOptions.parse("--config-dir", this.dir.toString()));
    }

    private static List<Window> showing() {
        return Window.getWindows().stream().filter(Window::isShowing).toList();
    }
}
