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
package org.njmule.gui.screens;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.RadioMenuItem;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.njmule.core.api.EngineEvent;
import org.njmule.core.api.LogLine;
import org.njmule.core.api.ServerInfo;
import org.njmule.gui.Engines;
import org.njmule.gui.FxTesting;
import org.njmule.gui.Statuses;
import org.njmule.gui.TestGui;
import org.njmule.gui.model.LiveList;

@Timeout(120)
class ServersMenusTest {

    private static final ServerInfo BIG = Statuses.server("81.2.3.4:4661", "Big", false);
    private static final ServerInfo SMALL = new ServerInfo(
            "81.2.3.5:4242", "Small", "", 0, 0, 0, 2, ServerInfo.Priority.HIGH, 0, 0, 0, 0, "", false, true);

    @TempDir
    Path dir;

    private TestGui test;
    private final List<String> calls = new CopyOnWriteArrayList<>();
    private ServersScreen screen;

    @AfterEach
    void close() {
        if (this.test != null) {
            this.test.close();
        }
    }

    @Test
    void shouldConnectToAnyServerWithoutASelection() throws Exception {
        givenTheServersScreen();
        givenTheClipboardHolds("nothing to paste");
        givenNoServerChosen();
        givenTheMenuShown();
        givenTheMenuItemsAreOff(true, "IDS_CONNECTTHIS", "IDS_SW_DIRECTDOWNLOAD");
        givenTheMenuItemsAreOff(false, "IDS_REMOVEALL");
        givenTheLinksCopied();

        whenConnectOrDisconnectIsPressed();

        thenTheEngineWasAskedFor("connect any");
    }

    @Test
    void shouldTakeTheStaticServerOutOfTheStaticListWhenBothServersAreChosen() throws Exception {
        givenTheServersScreen();
        givenAllServersChosen();
        givenTheMenuShown();
        givenTheMenuItemsAreOff(false, "IDS_ADDTOSTATIC", "IDS_REMOVEFROMSTATIC");
        givenNoPriorityTicked();

        whenTheMenuItemIsFired("IDS_REMOVEFROMSTATIC");

        thenTheEngineWasAlsoAskedFor("static 81.2.3.5:4242 false");
    }

    @Test
    void shouldOfferToDisconnectFromTheServerWeAreOnWithItsPriorityTicked() throws Exception {
        givenTheServersScreen();
        givenConnectedTo(BIG);
        givenTheServerChosen(BIG);
        givenTheMenuShown();
        givenTheConnectItemReads("Disconnect");
        givenThePriorityTicked("Normal");
        givenTheLastPriorityChosen();
        givenTheMenuItemFired("IDS_ADDTOSTATIC");

        whenTheMenuItemIsFired("IDS_CONNECTTHIS");

        thenTheEngineWasAlsoAskedFor("priority 81.2.3.4:4661 HIGH", "static 81.2.3.4:4661 true", "disconnect");
    }

    @Test
    void shouldOfferToConnectToAServerOtherThanTheOneWeAreOn() throws Exception {
        givenTheServersScreen();
        givenConnectedTo(BIG);
        givenTheServerChosen(SMALL);

        whenTheMenuShows();

        thenTheConnectItemReads("Connect To");
    }

    @Test
    void shouldPasteTheServerLinksOfTheClipboard() throws Exception {
        givenTheServersScreen();
        givenTheClipboardHolds("ed2k://|server|81.2.3.9|4661|/ ed2k://|serverlist|https://example.org/list.met|/");
        givenTheMenuShown();
        givenTheMenuItemsAreOff(false, "IDS_SW_DIRECTDOWNLOAD");

        whenTheMenuItemIsFired("IDS_SW_DIRECTDOWNLOAD");

        thenTheUserIsTold("3 new servers from example.org");
        thenTheEngineWasAlsoAskedFor("add 81.2.3.9:4661", "list https://example.org/list.met");
        thenTheServerListAddressesRememberedAre("https://example.org/list.met");
    }

    @Test
    void shouldAskBeforeRemovingAllServersAndStopTryingThemFirst() throws Exception {
        givenTheServersScreen();
        givenTheMenuShown();
        givenRemoveAllCancelled();
        givenNoServerRemoved();
        givenNjMuleTryingTheServers();

        whenRemoveAllIsConfirmed();

        thenTheEngineWasAlsoAskedFor("remove [81.2.3.4:4661, 81.2.3.5:4242]");
        thenTryingTheServersStoppedBeforeTheRemoval("remove [81.2.3.4:4661, 81.2.3.5:4242]");
    }

    @Test
    void shouldCopyTheLinkOfTheChosenServerWithTheShortcut() throws Exception {
        givenTheServersScreen();
        givenTheServerChosen(SMALL);

        whenTheKeyIsPressedOnTheList(KeyCode.C, true);

        thenTheClipboardHolds("ed2k://|server|81.2.3.5|4242|/");
        thenTheEngineWasAskedNothing();
    }

    @ParameterizedTest
    @CsvSource({
        "V, true, add 81.2.3.8:4661",
        "ENTER, false, connect 81.2.3.5:4242",
        "DELETE, false, remove [81.2.3.5:4242]"
    })
    void shouldActOnTheChosenServerWithAKey(KeyCode key, boolean shortcut, String call) throws Exception {
        givenTheServersScreen();
        givenTheServerChosen(SMALL);
        givenTheClipboardHolds("ed2k://|server|81.2.3.8|4661|/");

        whenTheKeyIsPressedOnTheList(key, shortcut);

        thenTheEngineWasAskedFor(call);
    }

    @Test
    void shouldIgnoreAPlainLetterOnTheList() throws Exception {
        givenTheServersScreen();
        givenTheServerChosen(SMALL);

        whenTheKeyIsPressedOnTheList(KeyCode.A, false);

        thenTheEngineWasAskedNothing();
    }

    @Test
    void shouldConnectToADoubleClickedServer() throws Exception {
        givenTheServersScreen();

        whenTheServerIsDoubleClicked(BIG);

        thenTheLastCallIs("connect 81.2.3.4:4661");
    }

    @Test
    void shouldConnectToATypedAddressAndEmptyTheBox() throws Exception {
        givenTheServersScreen();
        givenTheAddressTyped("81.2.3.7:4661");

        whenConnectToTheAddressIsPressed();

        thenTheLastCallIs("connect 81.2.3.7:4661");
        thenTheAddressBoxIsEmpty();
    }

    @Test
    void shouldEmptyTheFilterOnEscape() throws Exception {
        givenTheServersScreen();
        givenTheFilterTyped("big");

        whenTheKeyIsPressedInTheFilter(KeyCode.ESCAPE);

        thenTheFilterIsEmpty();
    }

    @Test
    void shouldNotFetchAnEmptyListAddress() throws Exception {
        givenTheServersScreen();
        givenTheListAddressCleared();

        whenTheListIsUpdatedFromTheAddress();

        thenNoListWasFetched();
    }

    @Test
    void shouldShowTheSessionsInTheServerInfo() throws Exception {
        givenTheServersScreen();

        whenTwoSessionsAMessageAndAWarningCome();

        thenTheServerInfoShowsTheSessions();
        thenTheServerInfoShowsSuccesses(2);
    }

    @Test
    void shouldResetTheServerInfoAndKeepTheLog() throws Exception {
        givenTheServersScreen();
        givenTwoSessionsAMessageAndAWarning();
        givenTheServerInfoShowsTheSessions();

        whenResetIsPressed();

        thenTheServerInfoIsEmpty();
        thenTheLogIsKept();
    }

    @Test
    void shouldResetTheLog() throws Exception {
        givenTheServersScreen();
        givenTwoSessionsAMessageAndAWarning();
        givenTheLogTabChosen();
        givenTheLogShowsTheWarning();

        whenResetIsPressed();

        thenTheLogIsEmpty();
    }

    private void givenTheServersScreen() throws Exception {
        this.screen = screen();
    }

    private void givenTheClipboardHolds(String text) throws Exception {
        FxTesting.onFx(() -> {
            ClipboardContent content = new ClipboardContent();
            content.putString(text);
            Clipboard.getSystemClipboard().setContent(content);
        });
    }

    private void givenNoServerChosen() throws Exception {
        FxTesting.onFx(() -> this.screen.table().getSelectionModel().clearSelection());
    }

    private void givenAllServersChosen() throws Exception {
        FxTesting.onFx(() -> this.screen.table().getSelectionModel().selectAll());
    }

    private void givenTheServerChosen(ServerInfo server) throws Exception {
        FxTesting.onFx(() -> select(this.screen, server));
    }

    private void givenConnectedTo(ServerInfo server) throws Exception {
        FxTesting.onFx(() -> this.test.model.handle(new EngineEvent.ServerConnected(server, 99, false, true)));
    }

    private void givenTheMenuShown() throws Exception {
        FxTesting.onFx(() -> menu().getOnShowing().handle(null));
    }

    private void givenTheMenuItemsAreOff(boolean off, String... keys) throws Exception {
        for (String key : keys) {
            assertThat(FxTesting.onFx(() -> item(menu(), key).isDisable()))
                    .as(key)
                    .isEqualTo(off);
        }
    }

    // One server is static and the priorities differ, so none is ticked.
    private void givenNoPriorityTicked() throws Exception {
        assertThat(FxTesting.onFx(() -> priorities(menu()))).noneMatch(RadioMenuItem::isSelected);
    }

    private void givenTheConnectItemReads(String text) throws Exception {
        thenTheConnectItemReads(text);
    }

    private void givenThePriorityTicked(String priority) throws Exception {
        assertThat(FxTesting.onFx(() -> priorities(menu())))
                .filteredOn(RadioMenuItem::isSelected)
                .extracting(MenuItem::getText)
                .containsExactly(priority);
    }

    private void givenTheLastPriorityChosen() throws Exception {
        FxTesting.onFx(() -> priorities(menu()).getLast().fire());
    }

    private void givenTheMenuItemFired(String key) throws Exception {
        whenTheMenuItemIsFired(key);
    }

    private void givenTheLinksCopied() throws Exception {
        FxTesting.onFx(this.screen::copyLinks);
    }

    private void givenRemoveAllCancelled() throws Exception {
        answerRemoveAll(ButtonBar.ButtonData.CANCEL_CLOSE);
    }

    private void givenNoServerRemoved() {
        assertThat(this.calls).noneMatch(c -> c.startsWith("remove"));
    }

    private void givenNjMuleTryingTheServers() throws Exception {
        FxTesting.onFx(() -> this.test.model.connect(Optional.empty()));
    }

    private void givenTheAddressTyped(String address) throws Exception {
        FxTesting.onFx(() -> this.screen.addressField().setText(address));
    }

    private void givenTheFilterTyped(String text) throws Exception {
        FxTesting.onFx(() -> this.screen.filterField().setText(text));
    }

    private void givenTheListAddressCleared() throws Exception {
        FxTesting.onFx(() -> this.screen.listBox().node().getEditor().clear());
    }

    private void givenTwoSessionsAMessageAndAWarning() throws Exception {
        whenTwoSessionsAMessageAndAWarningCome();
    }

    private void givenTheServerInfoShowsTheSessions() throws Exception {
        thenTheServerInfoShowsTheSessions();
    }

    private void givenTheLogTabChosen() throws Exception {
        FxTesting.onFx(() -> this.screen.logTabs().getSelectionModel().select(1));
    }

    private void givenTheLogShowsTheWarning() throws Exception {
        FxTesting.await("the warning", () -> this.screen.logView().count("warning") == 1);
    }

    private void whenConnectOrDisconnectIsPressed() throws Exception {
        FxTesting.onFx(this.screen::connectOrDisconnect);
    }

    private void whenTheMenuShows() throws Exception {
        givenTheMenuShown();
    }

    private void whenTheMenuItemIsFired(String key) throws Exception {
        FxTesting.onFx(() -> item(menu(), key).fire());
    }

    private void whenRemoveAllIsConfirmed() throws Exception {
        answerRemoveAll(ButtonBar.ButtonData.OK_DONE);
    }

    private void whenTheKeyIsPressedOnTheList(KeyCode key, boolean shortcut) throws Exception {
        FxTesting.onFx(() -> this.screen.table().fireEvent(FxTesting.key(key, shortcut)));
    }

    private void whenTheServerIsDoubleClicked(ServerInfo server) throws Exception {
        FxTesting.onFx(() -> FxTesting.doubleClick(
                this.screen.table(), r -> ((LiveList.Row<?>) r).get().equals(server)));
    }

    private void whenConnectToTheAddressIsPressed() throws Exception {
        FxTesting.onFx(this.screen::connectToAddress);
    }

    private void whenTheKeyIsPressedInTheFilter(KeyCode key) throws Exception {
        FxTesting.onFx(() -> this.screen.filterField().fireEvent(FxTesting.key(key, false)));
    }

    private void whenTheListIsUpdatedFromTheAddress() throws Exception {
        FxTesting.onFx(this.screen::updateFromUrl);
    }

    private void whenTwoSessionsAMessageAndAWarningCome() throws Exception {
        FxTesting.onFx(() -> {
            this.test.model.handle(new EngineEvent.ServerConnected(BIG, 99, false, true));
            this.test.model.handle(new EngineEvent.ServerConnected(SMALL, 99, true, false));
            this.test.model.handle(new EngineEvent.ServerMessage(BIG, "Welcome to Big"));
            this.test.model.handle(new EngineEvent.Log(new LogLine(Instant.now(), LogLine.Level.WARNING, "careful")));
        });
    }

    private void whenResetIsPressed() throws Exception {
        FxTesting.onFx(() -> reset(this.screen).fire());
    }

    private void thenTheEngineWasAskedFor(String... expected) {
        assertThat(this.calls).containsExactly(expected);
    }

    private void thenTheEngineWasAlsoAskedFor(String... expected) {
        assertThat(this.calls).contains(expected);
    }

    private void thenTheEngineWasAskedNothing() {
        assertThat(this.calls).isEmpty();
    }

    private void thenTheLastCallIs(String call) {
        assertThat(this.calls.getLast()).isEqualTo(call);
    }

    private void thenTryingTheServersStoppedBeforeTheRemoval(String removal) {
        assertThat(this.calls.getLast()).as("trying servers stops first").isEqualTo(removal);
        assertThat(this.calls.get(this.calls.size() - 2)).isEqualTo("disconnect");
    }

    private void thenTheUserIsTold(String message) throws Exception {
        FxTesting.assertEventually("the list fetched", () -> this.test.told.contains(message));
    }

    private void thenTheServerListAddressesRememberedAre(String... addresses) {
        assertThat(this.test.gui.settings().history("serverMet")).containsExactly(addresses);
    }

    private void thenTheClipboardHolds(String text) throws Exception {
        assertThat(FxTesting.onFx(() -> Clipboard.getSystemClipboard().getString()))
                .isEqualTo(text);
    }

    private void thenTheAddressBoxIsEmpty() throws Exception {
        assertThat(FxTesting.onFx(() -> this.screen.addressField().getText())).isEmpty();
    }

    private void thenTheFilterIsEmpty() throws Exception {
        assertThat(FxTesting.onFx(() -> this.screen.filterField().getText())).isEmpty();
    }

    // An empty list address is not fetched.
    private void thenNoListWasFetched() {
        assertThat(this.calls).noneMatch(c -> c.startsWith("list"));
    }

    private void thenTheServerInfoShowsTheSessions() throws Exception {
        FxTesting.assertEventually("the sessions", () -> {
            List<String> lines = List.of(this.screen.serverInfoView().text().split("\n"));
            return lines.stream().anyMatch(t -> t.contains("Obfuscated connection established"))
                    && lines.stream().anyMatch(t -> t.contains("Connection established"))
                    && lines.contains("Welcome to Big");
        });
    }

    private void thenTheServerInfoShowsSuccesses(int count) throws Exception {
        assertThat(FxTesting.onFx(() -> this.screen.serverInfoView().count("success")))
                .isEqualTo(count);
    }

    private void thenTheServerInfoIsEmpty() throws Exception {
        FxTesting.assertEventually(
                "no server info", () -> this.test.model.serverInfo().isEmpty());
    }

    private void thenTheLogIsKept() {
        assertThat(this.test.model.log()).isNotEmpty();
    }

    private void thenTheLogIsEmpty() throws Exception {
        assertThat(FxTesting.onFx(() -> this.test.model.log().isEmpty())).isTrue();
    }

    private void answerRemoveAll(ButtonBar.ButtonData answer) throws Exception {
        FxTesting.onFx(() -> {
            FxTesting.answer(answer);
            item(menu(), "IDS_REMOVEALL").fire();
        });
    }

    private ContextMenu menu() {
        return this.screen.table().getContextMenu();
    }

    private ServersScreen screen() throws Exception {
        Map<String, Engines.Command> commands = new HashMap<>();
        commands.put("servers", args -> CompletableFuture.completedFuture(List.of(BIG, SMALL)));
        commands.put("connect", args -> {
            Optional<?> server = (Optional<?>) args[0];
            this.calls.add("connect " + server.map(String::valueOf).orElse("any"));
            return CompletableFuture.completedFuture(null);
        });
        commands.put("disconnect", args -> {
            this.calls.add("disconnect");
            return CompletableFuture.completedFuture(null);
        });
        commands.put("setServerPriority", args -> {
            this.calls.add("priority " + args[0] + " " + args[1]);
            return CompletableFuture.completedFuture(true);
        });
        commands.put("setStaticServer", args -> {
            this.calls.add("static " + args[0] + " " + args[1]);
            return CompletableFuture.completedFuture(true);
        });
        commands.put("addServer", args -> {
            this.calls.add("add " + args[0]);
            return CompletableFuture.completedFuture(true);
        });
        commands.put("addServers", args -> {
            this.calls.add("list " + args[0]);
            return CompletableFuture.completedFuture(3);
        });
        commands.put("removeServers", args -> {
            this.calls.add("remove " + args[0]);
            return CompletableFuture.completedFuture(1);
        });
        this.test = TestGui.start(this.dir.resolve("njmule"), real -> Engines.overriding(real, commands));
        ServersScreen screen = new ServersScreen(this.test.gui);
        this.test.show(FxTesting.onFx(screen::view));
        FxTesting.await("the servers", () -> screen.table().getItems().size() == 2);
        return screen;
    }

    private Button reset(ServersScreen screen) {
        return screen.logTabs().getParent().lookupAll(".button").stream()
                .map(Button.class::cast)
                .filter(b -> "Reset".equals(b.getText()))
                .findFirst()
                .orElseThrow();
    }

    private void select(ServersScreen screen, ServerInfo server) {
        var table = screen.table();
        table.getSelectionModel().clearSelection();
        for (int i = 0; i < table.getItems().size(); i++) {
            if (table.getItems().get(i).get().address().equals(server.address())) {
                table.getSelectionModel().select(i);
            }
        }
    }

    private List<RadioMenuItem> priorities(ContextMenu menu) {
        return ((Menu) item(menu, "IDS_PRIORITY"))
                .getItems().stream().map(RadioMenuItem.class::cast).toList();
    }

    private MenuItem item(ContextMenu menu, String key) {
        String text = this.test.gui.t(key);
        return menu.getItems().stream()
                .filter(i -> text.equals(i.getText())
                        || (key.equals("IDS_CONNECTTHIS") && i.getStyleClass().contains("default-item")))
                .findFirst()
                .orElseThrow();
    }

    private void thenTheConnectItemReads(String text) throws Exception {
        assertThat(FxTesting.onFx(() -> item(menu(), "IDS_CONNECTTHIS").getText()))
                .isEqualTo(text);
    }
}
