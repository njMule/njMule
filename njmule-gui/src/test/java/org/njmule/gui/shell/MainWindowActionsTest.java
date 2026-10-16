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

import atlantafx.base.controls.Notification;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ToggleButton;
import javafx.scene.input.KeyCode;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.core.api.DownloadInfo;
import org.njmule.core.api.DownloadPriority;
import org.njmule.core.api.EngineEvent;
import org.njmule.core.api.EngineStatus;
import org.njmule.core.api.Hash16;
import org.njmule.core.api.KadInfo;
import org.njmule.core.api.PartMap;
import org.njmule.core.api.ServerInfo;
import org.njmule.gui.Engines;
import org.njmule.gui.FxTesting;
import org.njmule.gui.Statuses;
import org.njmule.gui.TestGui;

@Timeout(120)
class MainWindowActionsTest {

    private static final ServerInfo BIG = Statuses.server("81.2.3.4:4661", "Big", false);
    private static final Hash16 PEER = Hash16.parseHex("00112233445566778899AABBCCDDEEFF");

    @TempDir
    Path dir;

    private TestGui test;
    private MainWindow window;
    private final List<String> calls = new CopyOnWriteArrayList<>();
    private final AtomicReference<EngineStatus> status =
            new AtomicReference<>(Statuses.of(Optional.empty(), 0, false, Optional.empty(), false));
    private final List<String> elsewhere = new CopyOnWriteArrayList<>();
    private boolean dark;

    @AfterEach
    void close() throws Exception {
        if (this.test != null) {
            FxTesting.onFx(() -> List.copyOf(Window.getWindows()).stream()
                    .filter(w -> w instanceof Stage s && s.getOwner() != null)
                    .forEach(Window::hide));
            this.test.close();
        }
    }

    @Test
    void shouldActOnBothNetworksAsTheyAreWithConnect() throws Exception {
        givenTheWindow();
        givenKadEnabled();
        givenConnectPressed();
        givenTheCallsAre("connect", "connectKad");
        givenConnectReads("Cancel");
        givenConnectPressed();
        givenTheLastCallsAre("disconnect");
        givenConnectedToBigWithKadRunning();
        givenConnectReads("Disconnect");
        givenConnectPressed();
        givenTheLastCallsAre("disconnect", "disconnectKad");
        givenDisconnectedFromBigWithKadRunning();
        givenTheCallsCleared();

        whenConnectIsPressed();

        thenTheCallsAre("connect");
    }

    @Test
    void shouldTellTheStateOfEachNetworkOnTheChips() throws Exception {
        givenTheWindow();
        givenTheKadChipSays("Kad: Off");
        givenConnectedToBig(5, true);
        givenTheStatusOnBig(5, true, Statuses.kad(false, false, ""));
        givenTheEd2kChipSays("eD2K: Big (LowID)");
        givenTheKadChipSays("Kad: Not Connected");
        givenTheKadChipIs("connecting");
        givenTheStatusOnBig(5_000_000, false, Statuses.kad(true, true, "1.2.3.4"));
        givenTheEd2kChipSays("eD2K: Big (HighID)");
        givenTheKadChipSays("Kad: Firewalled");

        whenTheStatusOnBigIs(0, false, Statuses.kad(true, false, "1.2.3.4"));

        thenTheEd2kChipSays("eD2K: Big");
        thenTheKadChipSays("Kad: Open");
        thenTheKadChipIs("connected");
        // Both networks counted: no server in the list, no Kad estimate yet.
        thenTheWindowShows("Users 0 (0) | Files 0 (0)");
    }

    @Test
    void shouldOpenWhatTheKeysAndTheRailName() throws Exception {
        givenTheWindowWithTheScreenSaved("no such screen");
        givenTheFirstScreenIs("transfers");
        givenTheShortcutPressed(KeyCode.DIGIT2);
        givenTheScreenIs("search");
        givenTheShortcutPressed(KeyCode.DIGIT9);
        givenTheKeyPressed(KeyCode.DIGIT1);
        givenTheScreenIs("search");
        givenTheScreenShown("no such screen");
        givenTheScreenIs("search");

        whenTheRailOpens("Statistics");

        thenTheScreenIs("statistics");
    }

    @Test
    void shouldSwitchTheThemeBackAndForth() throws Exception {
        givenTheWindow();
        givenTheThemeSwitched();

        whenTheThemeIsSwitched();

        thenTheThemeIsTheFirstOneAgain();
    }

    @Test
    void shouldOpenTheImportAndAboutWindowsFromTheTools() throws Exception {
        givenTheWindow();

        whenTheToolsAreChosen("import", "about");

        thenTheImportAndAboutWindowsShow();
    }

    @Test
    void shouldTellOfFinishedDownloadsAndChatsElsewhere() throws Exception {
        givenTheWindow();
        givenTheMessagesAlsoToldElsewhere();
        givenADownloadFinished("film.mkv");
        givenAChatMessage("10.0.0.1:4662", Optional.of("Bob"), "hi");
        givenAChatMessage("10.0.0.2:4662", Optional.empty(), "hello");
        givenTheScreenShown("messages");
        givenAChatMessage("10.0.0.1:4662", Optional.of("Bob"), "again");
        givenToldElsewhere("Download finished: film.mkv", "New message from Bob", "New message from 10.0.0.2:4662");

        whenTheFirstMessageIsClosed();

        thenTheMessagesShownAre(2);
    }

    @Test
    void shouldTellTheTestedUdpStateBehindAFirewallOnTheKadChip() throws Exception {
        givenTheWindow();
        givenKadIs(new KadInfo(true, 10, 5, true, "", "1.2.3.4", 4672, false, true, KadInfo.Buddy.NONE));
        givenTheKadChipSays("Kad: Firewalled, UDP open");

        whenKadIs(new KadInfo(true, 10, 5, true, "", "1.2.3.4", 4672, true, true, KadInfo.Buddy.CONNECTED));

        thenTheKadChipSays("Kad: Firewalled, UDP firewalled");
    }

    private void givenTheWindow() throws Exception {
        this.test = TestGui.start(this.dir.resolve("njmule"), real -> Engines.overriding(real, commands()));
        show();
    }

    private void givenTheWindowWithTheScreenSaved(String screen) throws Exception {
        this.test = TestGui.start(this.dir.resolve("njmule"), real -> Engines.overriding(real, commands()));
        this.test.gui.settings().setScreen(screen);
        show();
    }

    private void givenKadEnabled() throws Exception {
        FxTesting.onFx(() -> this.test.model.handle(new EngineEvent.PreferencesChanged(
                this.test.engine.preferences().toBuilder().kadEnabled(true).build())));
    }

    private void givenConnectPressed() throws Exception {
        whenConnectIsPressed();
    }

    private void givenTheCallsAre(String... calls) {
        thenTheCallsAre(calls);
    }

    private void givenConnectReads(String text) throws Exception {
        assertThat(FxTesting.onFx(() -> this.window.connectButton().getText())).isEqualTo(text);
    }

    private void givenTheLastCallsAre(String... calls) {
        assertThat(this.calls).endsWith(calls);
    }

    // On a server with Kad running, Disconnect leaves both.
    private void givenConnectedToBigWithKadRunning() throws Exception {
        status(Statuses.of(Optional.of(BIG), 5, false, Optional.of(Statuses.kad(true, false, "")), false));
        FxTesting.onFx(() -> this.test.model.handle(new EngineEvent.ServerConnected(BIG, 5, false, false)));
    }

    // Kad running already: Connect only tries the servers.
    private void givenDisconnectedFromBigWithKadRunning() throws Exception {
        FxTesting.onFx(() -> this.test.model.handle(new EngineEvent.ServerDisconnected(Optional.of(BIG), "test")));
    }

    private void givenTheCallsCleared() {
        this.calls.clear();
    }

    private void givenTheKadChipSays(String text) throws Exception {
        thenTheKadChipSays(text);
    }

    private void givenConnectedToBig(long clientId, boolean lowId) throws Exception {
        FxTesting.onFx(() -> this.test.model.handle(new EngineEvent.ServerConnected(BIG, clientId, lowId, false)));
    }

    private void givenTheStatusOnBig(long clientId, boolean lowId, KadInfo kad) throws Exception {
        whenTheStatusOnBigIs(clientId, lowId, kad);
    }

    private void givenTheEd2kChipSays(String text) throws Exception {
        thenTheEd2kChipSays(text);
    }

    private void givenTheKadChipIs(String style) throws Exception {
        thenTheKadChipIs(style);
    }

    private void givenTheFirstScreenIs(String id) throws Exception {
        assertThat(FxTesting.onFx(this.window::current)).as("an unknown screen").isEqualTo(id);
    }

    private void givenTheShortcutPressed(KeyCode key) throws Exception {
        FxTesting.onFx(() -> this.window.root().fireEvent(FxTesting.key(key, true)));
    }

    private void givenTheKeyPressed(KeyCode key) throws Exception {
        FxTesting.onFx(() -> this.window.root().fireEvent(FxTesting.key(key, false)));
    }

    private void givenTheScreenIs(String id) throws Exception {
        thenTheScreenIs(id);
    }

    private void givenTheScreenShown(String id) throws Exception {
        FxTesting.onFx(() -> this.window.show(id));
    }

    private void givenTheThemeSwitched() throws Exception {
        this.dark = FxTesting.onFx(() -> this.test.gui.themes().darkProperty().get());
        whenTheThemeIsSwitched();
        assertThat(FxTesting.onFx(() -> this.test.gui.themes().darkProperty().get()))
                .isNotEqualTo(this.dark);
    }

    private void givenTheMessagesAlsoToldElsewhere() throws Exception {
        FxTesting.onFx(() -> this.window.whileHidden(this.elsewhere::add));
    }

    private void givenADownloadFinished(String name) throws Exception {
        FxTesting.onFx(() -> this.test.model.handle(new EngineEvent.DownloadCompleted(
                new DownloadInfo(
                        PEER,
                        name,
                        1000,
                        1000,
                        DownloadInfo.Status.COMPLETE,
                        DownloadPriority.AUTO,
                        0,
                        0,
                        0,
                        0,
                        PartMap.EMPTY),
                this.dir.resolve(name))));
    }

    private void givenAChatMessage(String address, Optional<String> name, String text) throws Exception {
        FxTesting.onFx(() -> this.test.model.handle(new EngineEvent.MessageReceived(address, PEER, name, text)));
    }

    private void givenToldElsewhere(String... texts) {
        assertThat(this.elsewhere).containsExactly(texts);
    }

    private void givenKadIs(KadInfo kad) throws Exception {
        whenKadIs(kad);
    }

    private void whenConnectIsPressed() throws Exception {
        FxTesting.onFx(() -> this.window.connectButton().fire());
    }

    private void whenTheStatusOnBigIs(long clientId, boolean lowId, KadInfo kad) throws Exception {
        status(Statuses.of(Optional.of(BIG), clientId, lowId, Optional.of(kad), false));
    }

    private void whenTheRailOpens(String title) throws Exception {
        FxTesting.onFx(() -> rail(title).fire());
    }

    private void whenTheThemeIsSwitched() throws Exception {
        FxTesting.onFx(() -> ((Button) this.window.root().lookup("#theme")).fire());
    }

    private void whenTheToolsAreChosen(String... ids) throws Exception {
        List<String> chosen = List.of(ids);
        FxTesting.onFx(() -> {
            MenuButton tools = (MenuButton) this.window.root().lookup(".top-bar .menu-button");
            // Open Incoming Folder would start the system's file manager: not here.
            for (MenuItem item : tools.getItems()) {
                if (item.getId() != null && chosen.contains(item.getId())) {
                    item.fire();
                }
            }
        });
    }

    private void whenTheFirstMessageIsClosed() throws Exception {
        FxTesting.onFx(() -> {
            Notification first = (Notification) this.window.messages().getFirst();
            first.getOnClose().handle(null);
        });
    }

    private void whenKadIs(KadInfo kad) throws Exception {
        status(Statuses.of(Optional.empty(), 0, false, Optional.of(kad), false));
    }

    private void thenTheCallsAre(String... calls) {
        assertThat(this.calls).containsExactly(calls);
    }

    private void thenTheEd2kChipSays(String text) throws Exception {
        assertThat(FxTesting.onFx(() -> this.window.ed2kChip().getText())).isEqualTo(text);
    }

    private void thenTheKadChipSays(String text) throws Exception {
        assertThat(FxTesting.onFx(() -> this.window.kadChip().getText())).isEqualTo(text);
    }

    private void thenTheKadChipIs(String style) throws Exception {
        assertThat(FxTesting.onFx(() -> this.window.kadChip().getStyleClass())).contains(style);
    }

    private void thenTheWindowShows(String text) throws Exception {
        assertThat(FxTesting.onFx(() -> this.window.root().lookupAll(".label").stream()
                        .map(n -> ((Label) n).getText())
                        .toList()))
                .contains(text);
    }

    private void thenTheScreenIs(String id) throws Exception {
        assertThat(FxTesting.onFx(this.window::current)).isEqualTo(id);
    }

    private void thenTheThemeIsTheFirstOneAgain() throws Exception {
        assertThat(FxTesting.onFx(() -> this.test.gui.themes().darkProperty().get()))
                .isEqualTo(this.dark);
    }

    private void thenTheImportAndAboutWindowsShow() throws Exception {
        FxTesting.assertEventually(
                "the import and about windows",
                () -> titles().containsAll(
                                List.of(this.test.gui.t("nj.import.title"), this.test.gui.t("nj.about.title"))));
    }

    private void thenTheMessagesShownAre(int count) throws Exception {
        assertThat(FxTesting.onFx(() -> this.window.messages().size())).isEqualTo(count);
    }

    private void show() throws Exception {
        this.window = FxTesting.onFx(() -> new MainWindow(this.test.gui));
        this.test.show(this.window.root());
    }

    private Map<String, Engines.Command> commands() {
        Map<String, Engines.Command> commands = new HashMap<>();
        for (String command : List.of("connect", "disconnect", "connectKad", "disconnectKad")) {
            commands.put(command, args -> {
                this.calls.add(command);
                return CompletableFuture.completedFuture(null);
            });
        }
        commands.put("status", args -> CompletableFuture.completedFuture(this.status.get()));
        return commands;
    }

    private void status(EngineStatus next) throws Exception {
        this.status.set(next);
        FxTesting.onFx(this.test.model::tick);
        FxTesting.await(
                "the state",
                () -> Objects.equals(this.test.model.statusProperty().get(), next));
    }

    private ToggleButton rail(String title) {
        return this.window.root().lookupAll(".toggle-button").stream()
                .filter(n -> n instanceof ToggleButton b && title.equals(b.getText()))
                .map(ToggleButton.class::cast)
                .findFirst()
                .orElseThrow();
    }

    private List<String> titles() {
        return Window.getWindows().stream()
                .filter(Window::isShowing)
                .filter(w -> w instanceof Stage)
                .map(w -> ((Stage) w).getTitle())
                .filter(Objects::nonNull)
                .toList();
    }
}
