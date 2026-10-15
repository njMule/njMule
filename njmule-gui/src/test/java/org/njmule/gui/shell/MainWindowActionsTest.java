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
    private MainWindow shown;
    private final List<String> calls = new CopyOnWriteArrayList<>();
    private final AtomicReference<EngineStatus> status =
            new AtomicReference<>(Statuses.of(Optional.empty(), 0, false, Optional.empty(), false));

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
    void connectActsOnBothNetworksAsTheyAre() throws Exception {
        MainWindow window = this.window();
        FxTesting.onFx(() -> this.test.model.handle(new EngineEvent.PreferencesChanged(
                this.test.engine.preferences().toBuilder().kadEnabled(true).build())));

        FxTesting.onFx(() -> window.connectButton().fire());
        assertThat(this.calls).containsExactly("connect", "connectKad");
        assertThat(FxTesting.onFx(() -> window.connectButton().getText())).isEqualTo("Cancel");
        FxTesting.onFx(() -> window.connectButton().fire());
        assertThat(this.calls).endsWith("disconnect");

        // On a server with Kad running, Disconnect leaves both.
        this.status(Statuses.of(Optional.of(BIG), 5, false, Optional.of(Statuses.kad(true, false, "")), false));
        FxTesting.onFx(() -> this.test.model.handle(new EngineEvent.ServerConnected(BIG, 5, false, false)));
        assertThat(FxTesting.onFx(() -> window.connectButton().getText())).isEqualTo("Disconnect");
        FxTesting.onFx(() -> window.connectButton().fire());
        assertThat(this.calls).endsWith("disconnect", "disconnectKad");

        // Kad running already: Connect only tries the servers.
        FxTesting.onFx(() -> this.test.model.handle(new EngineEvent.ServerDisconnected(Optional.of(BIG), "test")));
        this.calls.clear();
        FxTesting.onFx(() -> window.connectButton().fire());
        assertThat(this.calls).containsExactly("connect");
    }

    @Test
    void theChipsTellTheStateOfEachNetwork() throws Exception {
        MainWindow window = this.window();
        assertThat(FxTesting.onFx(() -> window.kadChip().getText())).isEqualTo("Kad: Off");

        FxTesting.onFx(() -> this.test.model.handle(new EngineEvent.ServerConnected(BIG, 5, true, false)));
        this.status(Statuses.of(Optional.of(BIG), 5, true, Optional.of(Statuses.kad(false, false, "")), false));
        assertThat(FxTesting.onFx(() -> window.ed2kChip().getText())).isEqualTo("eD2K: Big (LowID)");
        assertThat(FxTesting.onFx(() -> window.kadChip().getText())).isEqualTo("Kad: Not Connected");
        assertThat(FxTesting.onFx(() -> window.kadChip().getStyleClass())).contains("connecting");

        this.status(Statuses.of(
                Optional.of(BIG), 5_000_000, false, Optional.of(Statuses.kad(true, true, "1.2.3.4")), false));
        assertThat(FxTesting.onFx(() -> window.ed2kChip().getText())).isEqualTo("eD2K: Big (HighID)");
        assertThat(FxTesting.onFx(() -> window.kadChip().getText())).isEqualTo("Kad: Firewalled");

        this.status(Statuses.of(Optional.of(BIG), 0, false, Optional.of(Statuses.kad(true, false, "1.2.3.4")), false));
        assertThat(FxTesting.onFx(() -> window.ed2kChip().getText())).isEqualTo("eD2K: Big");
        assertThat(FxTesting.onFx(() -> window.kadChip().getText())).isEqualTo("Kad: Open");
        assertThat(FxTesting.onFx(() -> window.kadChip().getStyleClass())).contains("connected");
        assertThat(FxTesting.onFx(() -> window.root().lookupAll(".label").stream()
                        .map(n -> ((Label) n).getText())
                        .toList()))
                .contains("Users 1,200 | Files 34,000");
    }

    @Test
    void keysTheRailAndTheToolsOpenWhatTheyName() throws Exception {
        this.test = TestGui.start(this.dir.resolve("njmule"), real -> Engines.overriding(real, this.commands()));
        this.test.gui.settings().setScreen("no such screen");
        MainWindow window = this.show();
        assertThat(FxTesting.onFx(window::current)).as("an unknown screen").isEqualTo("transfers");

        FxTesting.onFx(() -> {
            window.root().fireEvent(FxTesting.key(KeyCode.DIGIT2, true));
            assertThat(window.current()).isEqualTo("search");
            window.root().fireEvent(FxTesting.key(KeyCode.DIGIT9, true));
            window.root().fireEvent(FxTesting.key(KeyCode.DIGIT1, false));
            assertThat(window.current()).isEqualTo("search");
            window.show("no such screen");
            assertThat(window.current()).isEqualTo("search");
            this.rail(window, "Statistics").fire();
            assertThat(window.current()).isEqualTo("statistics");
        });

        boolean dark =
                FxTesting.onFx(() -> this.test.gui.themes().darkProperty().get());
        FxTesting.onFx(() -> ((Button) window.root().lookup("#theme")).fire());
        assertThat(FxTesting.onFx(() -> this.test.gui.themes().darkProperty().get()))
                .isNotEqualTo(dark);
        FxTesting.onFx(() -> ((Button) window.root().lookup("#theme")).fire());
        assertThat(FxTesting.onFx(() -> this.test.gui.themes().darkProperty().get()))
                .isEqualTo(dark);

        FxTesting.onFx(() -> {
            MenuButton tools = (MenuButton) window.root().lookup(".menu-button");
            // Open Incoming Folder would start the system's file manager: not here.
            for (MenuItem item : tools.getItems()) {
                if ("import".equals(item.getId()) || "about".equals(item.getId())) {
                    item.fire();
                }
            }
        });
        FxTesting.await(
                "the import and about windows",
                () -> this.titles()
                        .containsAll(List.of(this.test.gui.t("nj.import.title"), this.test.gui.t("nj.about.title"))));
    }

    @Test
    void messagesTellOfFinishedDownloadsAndChatsElsewhere() throws Exception {
        MainWindow window = this.window();
        List<String> elsewhere = new CopyOnWriteArrayList<>();
        FxTesting.onFx(() -> {
            window.whileHidden(elsewhere::add);
            this.test.model.handle(new EngineEvent.DownloadCompleted(
                    new DownloadInfo(
                            PEER,
                            "film.mkv",
                            1000,
                            1000,
                            DownloadInfo.Status.COMPLETE,
                            DownloadPriority.AUTO,
                            0,
                            0,
                            0,
                            0,
                            PartMap.EMPTY),
                    this.dir.resolve("film.mkv")));
            this.test.model.handle(new EngineEvent.MessageReceived("10.0.0.1:4662", PEER, Optional.of("Bob"), "hi"));
            this.test.model.handle(new EngineEvent.MessageReceived("10.0.0.2:4662", PEER, Optional.empty(), "hello"));
            window.show("messages");
            this.test.model.handle(new EngineEvent.MessageReceived("10.0.0.1:4662", PEER, Optional.of("Bob"), "again"));
        });
        assertThat(elsewhere)
                .containsExactly(
                        "Download finished: film.mkv", "New message from Bob", "New message from 10.0.0.2:4662");

        FxTesting.onFx(() -> {
            Notification first = (Notification) window.messages().getFirst();
            first.getOnClose().handle(null);
        });
        assertThat(FxTesting.onFx(() -> window.messages().size())).isEqualTo(2);
    }

    private MainWindow window() throws Exception {
        this.test = TestGui.start(this.dir.resolve("njmule"), real -> Engines.overriding(real, this.commands()));
        return this.show();
    }

    private MainWindow show() throws Exception {
        MainWindow window = FxTesting.onFx(() -> new MainWindow(this.test.gui));
        this.test.show(window.root());
        return window;
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

    @Test
    void theKadChipTellsTheTestedUdpStateBehindAFirewall() throws Exception {
        this.givenTheMainWindow();

        this.whenKadIs(new KadInfo(true, 10, 5, true, "", "1.2.3.4", 4672, false, true, KadInfo.Buddy.NONE));

        this.thenTheKadChipSays("Kad: Firewalled, UDP open");

        this.whenKadIs(new KadInfo(true, 10, 5, true, "", "1.2.3.4", 4672, true, true, KadInfo.Buddy.CONNECTED));

        this.thenTheKadChipSays("Kad: Firewalled, UDP firewalled");
    }

    private void givenTheMainWindow() throws Exception {
        this.shown = this.window();
    }

    private void whenKadIs(KadInfo kad) throws Exception {
        this.status(Statuses.of(Optional.empty(), 0, false, Optional.of(kad), false));
    }

    private void thenTheKadChipSays(String text) throws Exception {
        assertThat(FxTesting.onFx(() -> this.shown.kadChip().getText())).isEqualTo(text);
    }

    private void status(EngineStatus next) throws Exception {
        this.status.set(next);
        FxTesting.onFx(this.test.model::tick);
        FxTesting.await(
                "the state",
                () -> Objects.equals(this.test.model.statusProperty().get(), next));
    }

    private ToggleButton rail(MainWindow window, String title) {
        return window.root().lookupAll(".toggle-button").stream()
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
