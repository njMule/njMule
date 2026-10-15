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
import javafx.scene.control.ListView;
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

    @AfterEach
    void close() {
        if (this.test != null) {
            this.test.close();
        }
    }

    @Test
    void theServerMenuFollowsTheSelectionAndActs() throws Exception {
        ServersScreen screen = this.screen();
        ContextMenu menu = FxTesting.onFx(() -> screen.table().getContextMenu());

        FxTesting.onFx(() -> {
            ClipboardContent content = new ClipboardContent();
            content.putString("nothing to paste");
            Clipboard.getSystemClipboard().setContent(content);
            screen.table().getSelectionModel().clearSelection();
            menu.getOnShowing().handle(null);
            assertThat(this.item(menu, "IDS_CONNECTTHIS").isDisable()).isTrue();
            assertThat(this.item(menu, "IDS_SW_DIRECTDOWNLOAD").isDisable()).isTrue();
            assertThat(this.item(menu, "IDS_REMOVEALL").isDisable()).isFalse();
            screen.copyLinks();
            screen.connectOrDisconnect();
        });
        assertThat(this.calls).containsExactly("connect any");

        // Both servers: one static, the priorities differ, so none is ticked.
        FxTesting.onFx(() -> {
            screen.table().getSelectionModel().selectAll();
            menu.getOnShowing().handle(null);
            assertThat(this.item(menu, "IDS_ADDTOSTATIC").isDisable()).isFalse();
            assertThat(this.item(menu, "IDS_REMOVEFROMSTATIC").isDisable()).isFalse();
            assertThat(this.priorities(menu)).noneMatch(RadioMenuItem::isSelected);
            this.item(menu, "IDS_REMOVEFROMSTATIC").fire();
        });
        assertThat(this.calls).contains("static 81.2.3.5:4242 false");

        // The server we are on: Disconnect, and its priority ticked.
        FxTesting.onFx(() -> this.test.model.handle(new EngineEvent.ServerConnected(BIG, 99, false, true)));
        FxTesting.onFx(() -> {
            this.select(screen, BIG);
            menu.getOnShowing().handle(null);
            assertThat(this.item(menu, "IDS_CONNECTTHIS").getText()).isEqualTo("Disconnect");
            assertThat(this.priorities(menu))
                    .filteredOn(RadioMenuItem::isSelected)
                    .extracting(MenuItem::getText)
                    .containsExactly("Normal");
            this.priorities(menu).getLast().fire();
            this.item(menu, "IDS_ADDTOSTATIC").fire();
            this.item(menu, "IDS_CONNECTTHIS").fire();
        });
        assertThat(this.calls).contains("priority 81.2.3.4:4661 HIGH", "static 81.2.3.4:4661 true", "disconnect");

        // A server link on the clipboard can be pasted.
        FxTesting.onFx(() -> {
            ClipboardContent content = new ClipboardContent();
            content.putString("ed2k://|server|81.2.3.9|4661|/ ed2k://|serverlist|https://example.org/list.met|/");
            Clipboard.getSystemClipboard().setContent(content);
            menu.getOnShowing().handle(null);
            assertThat(this.item(menu, "IDS_SW_DIRECTDOWNLOAD").isDisable()).isFalse();
            this.item(menu, "IDS_SW_DIRECTDOWNLOAD").fire();
        });
        FxTesting.await("the list fetched", () -> this.test.told.contains("3 new servers from example.org"));
        assertThat(this.calls).contains("add 81.2.3.9:4661", "list https://example.org/list.met");
        assertThat(this.test.gui.settings().history("serverMet")).containsExactly("https://example.org/list.met");

        // Remove All asks first.
        FxTesting.onFx(() -> {
            FxTesting.answer(ButtonBar.ButtonData.CANCEL_CLOSE);
            this.item(menu, "IDS_REMOVEALL").fire();
        });
        assertThat(this.calls).noneMatch(c -> c.startsWith("remove"));
        FxTesting.onFx(() -> {
            this.test.model.connect(Optional.empty());
            FxTesting.answer(ButtonBar.ButtonData.OK_DONE);
            this.item(menu, "IDS_REMOVEALL").fire();
        });
        assertThat(this.calls).contains("remove [81.2.3.4:4661, 81.2.3.5:4242]");
        assertThat(this.calls.getLast())
                .as("trying servers stops first")
                .isEqualTo("remove [81.2.3.4:4661, 81.2.3.5:4242]");
        assertThat(this.calls.get(this.calls.size() - 2)).isEqualTo("disconnect");
    }

    @Test
    void keysAndDoubleClicksActOnTheList() throws Exception {
        ServersScreen screen = this.screen();
        FxTesting.onFx(() -> {
            this.select(screen, SMALL);
            screen.table().fireEvent(FxTesting.key(KeyCode.C, true));
            assertThat(Clipboard.getSystemClipboard().getString()).isEqualTo("ed2k://|server|81.2.3.5|4242|/");
            ClipboardContent content = new ClipboardContent();
            content.putString("ed2k://|server|81.2.3.8|4661|/");
            Clipboard.getSystemClipboard().setContent(content);
            screen.table().fireEvent(FxTesting.key(KeyCode.V, true));
            screen.table().fireEvent(FxTesting.key(KeyCode.ENTER, false));
            screen.table().fireEvent(FxTesting.key(KeyCode.DELETE, false));
            screen.table().fireEvent(FxTesting.key(KeyCode.A, false));
        });
        assertThat(this.calls).containsExactly("add 81.2.3.8:4661", "connect 81.2.3.5:4242", "remove [81.2.3.5:4242]");

        FxTesting.onFx(() -> FxTesting.doubleClick(
                screen.table(), r -> ((LiveList.Row<?>) r).get().equals(BIG)));
        assertThat(this.calls.getLast()).isEqualTo("connect 81.2.3.4:4661");

        FxTesting.onFx(() -> {
            screen.addressField().setText("81.2.3.7:4661");
            screen.connectToAddress();
            screen.filterField().setText("big");
            screen.filterField().fireEvent(FxTesting.key(KeyCode.ESCAPE, false));
        });
        assertThat(this.calls.getLast()).isEqualTo("connect 81.2.3.7:4661");
        assertThat(FxTesting.onFx(() -> screen.addressField().getText())).isEmpty();
        assertThat(FxTesting.onFx(() -> screen.filterField().getText())).isEmpty();

        // An empty list address is not fetched.
        FxTesting.onFx(() -> {
            screen.listBox().node().getEditor().clear();
            screen.updateFromUrl();
        });
        assertThat(this.calls).noneMatch(c -> c.startsWith("list"));
    }

    @Test
    void theLogsShowTheSessionsAndAreReset() throws Exception {
        ServersScreen screen = this.screen();
        FxTesting.onFx(() -> {
            this.test.model.handle(new EngineEvent.ServerConnected(BIG, 99, false, true));
            this.test.model.handle(new EngineEvent.ServerConnected(SMALL, 99, true, false));
            this.test.model.handle(new EngineEvent.ServerMessage(BIG, "Welcome to Big"));
            this.test.model.handle(new EngineEvent.Log(new LogLine(Instant.now(), LogLine.Level.WARNING, "careful")));
        });
        ListView<?> info = FxTesting.onFx(
                () -> (ListView<?>) screen.logTabs().getTabs().getFirst().getContent());
        FxTesting.await(
                "the sessions",
                () -> FxTesting.cellTexts(info).stream().anyMatch(t -> t.contains("Obfuscated connection established"))
                        && FxTesting.cellTexts(info).stream().anyMatch(t -> t.contains("Connection established"))
                        && FxTesting.cellTexts(info).contains("Welcome to Big"));
        assertThat(FxTesting.onFx(() -> info.lookupAll(".success").size())).isEqualTo(2);

        FxTesting.onFx(() -> this.reset(screen).fire());
        FxTesting.await("no server info", () -> this.test.model.serverInfo().isEmpty());
        assertThat(this.test.model.log()).isNotEmpty();

        FxTesting.onFx(() -> screen.logTabs().getSelectionModel().select(1));
        ListView<?> log = FxTesting.onFx(
                () -> (ListView<?>) screen.logTabs().getTabs().get(1).getContent());
        FxTesting.await("the warning", () -> log.lookupAll(".warning").size() == 1);
        FxTesting.onFx(() -> this.reset(screen).fire());
        assertThat(FxTesting.onFx(() -> this.test.model.log().isEmpty())).isTrue();
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
        return ((Menu) this.item(menu, "IDS_PRIORITY"))
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
}
