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
import java.util.List;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TabPane;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.core.api.EngineEvent;
import org.njmule.core.api.ServerInfo;
import org.njmule.gui.FxTesting;
import org.njmule.gui.TestGui;

@Timeout(60)
class TopBarTest {

    private static final ServerInfo SERVER = new ServerInfo(
            "10.0.0.1:4661", "Big", "", 1200, 34000, 5000, 0, ServerInfo.Priority.NORMAL, 0, 0, 0, 0, "", false, false);

    @TempDir
    Path dir;

    private TestGui test;
    private MainWindow window;

    @AfterEach
    void close() {
        if (this.test != null) {
            this.test.close();
        }
    }

    @Test
    void shouldGiveConnectTheAccentOnlyWhileThereIsNoConnection() throws Exception {
        givenTheWindow();
        givenConnectIsTheAccent(true);

        whenTheServerIsTried();

        thenConnectIsTheAccent(false);
    }

    @Test
    void shouldOpenTheNetworkWhereTheChipsTell() throws Exception {
        givenTheWindow();
        givenTheChipClicked("kad");
        givenTheScreenIs("kad");

        whenTheChipIsClicked("ed2k");

        thenTheScreenIs("servers");
    }

    @Test
    void shouldOpenTheLogFromItsLastLine() throws Exception {
        givenTheWindow();

        whenTheLastLineOfTheLogIsClicked();

        thenTheScreenIs("servers");
        thenTheLogIsInFront();
    }

    @Test
    void shouldOpenTheIncomingFolderAndTheOptionsFromTheTools() throws Exception {
        givenTheWindow();
        givenTheToolsAre("incoming", "options", "wizard", null, "import", "shortcuts", "about");

        whenTheToolIsChosen("options");

        thenTheScreenIs("settings");
    }

    @Test
    void shouldOpenTheLimitsAsTheyAreFromTheRates() throws Exception {
        givenTheWindow();

        whenTheRatesArePressed();

        thenTheLimitsOfThePreferencesShow();
    }

    private void givenTheWindow() throws Exception {
        this.test = TestGui.start(this.dir.resolve("njmule"));
        this.window = FxTesting.onFx(() -> new MainWindow(this.test.gui));
        this.test.show(this.window.root());
    }

    private void givenConnectIsTheAccent(boolean accent) throws Exception {
        thenConnectIsTheAccent(accent);
    }

    private void givenTheChipClicked(String network) throws Exception {
        click(chip(network));
    }

    private void givenTheScreenIs(String id) throws Exception {
        thenTheScreenIs(id);
    }

    private void givenTheToolsAre(String... ids) throws Exception {
        assertThat(FxTesting.onFx(() -> tools().stream().map(MenuItem::getId).toList()))
                .containsExactly(ids);
    }

    private void whenTheRatesArePressed() throws Exception {
        FxTesting.onFx(() -> ((javafx.scene.control.Button) this.window.root().lookup("#rates")).fire());
    }

    private void whenTheServerIsTried() throws Exception {
        FxTesting.onFx(() -> this.test.model.handle(new EngineEvent.ServerConnecting(SERVER)));
    }

    private void whenTheChipIsClicked(String network) throws Exception {
        click(chip(network));
    }

    private void whenTheLastLineOfTheLogIsClicked() throws Exception {
        click(lastLog());
    }

    private void whenTheToolIsChosen(String id) throws Exception {
        FxTesting.onFx(() -> tools().stream()
                .filter(i -> id.equals(i.getId()))
                .findFirst()
                .orElseThrow()
                .fire());
    }

    private void thenConnectIsTheAccent(boolean accent) throws Exception {
        assertThat(FxTesting.onFx(
                        () -> this.window.connectButton().getStyleClass().contains("accent")))
                .isEqualTo(accent);
    }

    private void thenTheScreenIs(String id) throws Exception {
        assertThat(FxTesting.onFx(this.window::current)).isEqualTo(id);
    }

    private void thenTheLogIsInFront() throws Exception {
        String log = this.test.gui.t("IDS_SV_LOG");
        FxTesting.assertEventually(
                "the log in front",
                () -> this.window.root().lookupAll(".tab-pane").stream()
                        .map(TabPane.class::cast)
                        .map(t -> t.getSelectionModel().getSelectedItem())
                        .anyMatch(tab -> tab != null && log.equals(tab.getText())));
    }

    private void thenTheLimitsOfThePreferencesShow() throws Exception {
        thenTheLimitsShow(
                this.test.engine.preferences().maxDownloadKiBps(),
                this.test.engine.preferences().maxUploadKiBps());
    }

    private void thenTheLimitsShow(int down, int up) throws Exception {
        FxTesting.assertEventually(
                "the limits",
                () -> javafx.stage.Window.getWindows().stream()
                        .filter(w -> w instanceof javafx.stage.PopupWindow && w.isShowing() && w.getScene() != null)
                        .anyMatch(w -> w.getScene().getRoot().lookup(".tray-speeds") != null));
        List<String> values = FxTesting.onFx(() -> javafx.stage.Window.getWindows().stream()
                .filter(w -> w instanceof javafx.stage.PopupWindow && w.isShowing() && w.getScene() != null)
                .flatMap(w -> w.getScene().getRoot().lookupAll(".tray-speeds .speed-value").stream())
                .map(n -> ((javafx.scene.control.Label) n).getText())
                .toList());
        assertThat(values).containsExactly(limit(down), limit(up));
    }

    private String limit(int kib) {
        return kib == 0
                ? this.test.gui.t("nj.rate.noLimit")
                : this.test.gui.formats().rate(kib * 1024L);
    }

    private void click(Label label) throws Exception {
        FxTesting.onFx(() -> label.fireEvent(new MouseEvent(
                MouseEvent.MOUSE_CLICKED,
                1,
                1,
                1,
                1,
                MouseButton.PRIMARY,
                1,
                false,
                false,
                false,
                false,
                true,
                false,
                false,
                true,
                false,
                false,
                null)));
    }

    private List<MenuItem> tools() {
        return ((MenuButton) this.window.root().lookup(".top-bar .menu-button")).getItems();
    }

    private Label chip(String network) {
        return "kad".equals(network) ? this.window.kadChip() : this.window.ed2kChip();
    }

    private Label lastLog() throws Exception {
        return FxTesting.onFx(() -> (Label) this.window.root().lookup(".status-strip .label"));
    }
}
