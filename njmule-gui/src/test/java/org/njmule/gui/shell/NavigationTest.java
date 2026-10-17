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
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.input.KeyCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.core.api.DownloadInfo;
import org.njmule.core.api.DownloadPriority;
import org.njmule.core.api.EngineEvent;
import org.njmule.core.api.Hash16;
import org.njmule.core.api.PartMap;
import org.njmule.gui.Engines;
import org.njmule.gui.FxTesting;
import org.njmule.gui.TestGui;

@Timeout(60)
class NavigationTest {

    private static final Hash16 PEER = Hash16.parseHex("00112233445566778899AABBCCDDEEFF");

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
    void shouldPutTheEverydayPlacesFirstInTheRailAndTheOptionsLast() throws Exception {
        givenTheEngine();

        whenTheWindowShows();

        thenTheRailReads("Transfers", "Search", "Shared Files", "Messages", "Network", "Statistics", "Options");
    }

    @Test
    void shouldHoldTheServersAndKadInTheNetworkAndRememberTheLastOne() throws Exception {
        givenTheWindow();
        givenTheRailOpened("Network");
        givenTheScreenIs("servers");
        givenTheNetworkSwitchedTo("kad");
        givenTheScreenIs("kad");
        givenTheRailShows("Network");
        givenTheRailOpened("Transfers");

        whenTheShortcutIsPressed(KeyCode.DIGIT5);

        thenTheScreenIs("kad");
    }

    @Test
    void shouldCountTheMessagesThatCameWhileTheirPlaceDidNotShow() throws Exception {
        givenTheWindow();
        givenMessagesArrived(2);
        givenTheBadgeOf("Messages", "2");

        whenTheRailOpens("Messages");

        thenTheBadgeOf("Messages", null);
    }

    @Test
    void shouldCountTheRunningDownloadsOnTheTransfersPlace() throws Exception {
        givenTheWindowWithDownloads(
                DownloadInfo.Status.DOWNLOADING, DownloadInfo.Status.DOWNLOADING, DownloadInfo.Status.PAUSED);

        whenTheDownloadsAreRead(3);

        thenTheBadgeOf("Transfers", "2");
    }

    private void givenTheEngine() throws Exception {
        this.test = TestGui.start(this.dir.resolve("njmule"));
    }

    private void givenTheWindow() throws Exception {
        this.test = TestGui.start(this.dir.resolve("njmule"));
        show();
    }

    private void givenTheRailOpened(String title) throws Exception {
        whenTheRailOpens(title);
    }

    private void givenTheScreenIs(String id) throws Exception {
        thenTheScreenIs(id);
    }

    private void givenTheNetworkSwitchedTo(String part) throws Exception {
        FxTesting.onFx(() -> ((ToggleButton) this.window.root().lookup("#network-" + part)).fire());
    }

    private void givenTheRailShows(String title) throws Exception {
        assertThat(FxTesting.onFx(() -> rail(title).isSelected())).isTrue();
    }

    private void givenMessagesArrived(int count) throws Exception {
        FxTesting.onFx(() -> {
            for (int i = 0; i < count; i++) {
                this.test.model.handle(
                        new EngineEvent.MessageReceived("10.0.0.1:4662", PEER, Optional.of("Bob"), "hi " + i));
            }
        });
    }

    private void givenTheBadgeOf(String title, String count) throws Exception {
        thenTheBadgeOf(title, count);
    }

    private void givenTheWindowWithDownloads(DownloadInfo.Status... statuses) throws Exception {
        List<DownloadInfo> downloads = new java.util.ArrayList<>();
        for (int i = 0; i < statuses.length; i++) {
            downloads.add(new DownloadInfo(
                    Hash16.parseHex(String.format("%032X", i + 1)),
                    "file" + i + ".bin",
                    1000,
                    100,
                    statuses[i],
                    DownloadPriority.AUTO,
                    0,
                    1,
                    0,
                    0,
                    PartMap.EMPTY,
                    false,
                    false));
        }
        Map<String, Engines.Command> commands =
                Map.of("downloads", a -> CompletableFuture.completedFuture(List.copyOf(downloads)));
        this.test = TestGui.start(this.dir.resolve("njmule"), real -> Engines.overriding(real, commands));
        show();
    }

    private void whenTheWindowShows() throws Exception {
        show();
    }

    private void whenTheRailOpens(String title) throws Exception {
        FxTesting.onFx(() -> rail(title).fire());
    }

    private void whenTheShortcutIsPressed(KeyCode digit) throws Exception {
        FxTesting.onFx(() -> this.window.root().fireEvent(FxTesting.key(digit, true)));
    }

    private void whenTheDownloadsAreRead(int count) throws Exception {
        FxTesting.onFx(this.test.model::tick);
        FxTesting.await(
                "the downloads", () -> this.test.model.downloads().rows().size() == count);
    }

    private void thenTheRailReads(String... titles) throws Exception {
        assertThat(FxTesting.onFx(() -> this.window.root().lookupAll(".nav-rail .toggle-button").stream()
                        .map(n -> ((ToggleButton) n).getText())
                        .toList()))
                .containsExactly(titles);
    }

    private void thenTheScreenIs(String id) throws Exception {
        assertThat(FxTesting.onFx(this.window::current)).isEqualTo(id);
    }

    private void thenTheBadgeOf(String title, String count) throws Exception {
        Optional<String> shown = FxTesting.onFx(() -> {
            Node badge = rail(title).getParent().lookup(".rail-badge");
            return badge != null && badge.isVisible() ? Optional.of(((Label) badge).getText()) : Optional.empty();
        });
        assertThat(shown).isEqualTo(Optional.ofNullable(count));
        if (count != null) {
            assertThat(FxTesting.onFx(() -> rail(title).getAccessibleHelp())).endsWith(count);
        }
    }

    private void show() throws Exception {
        this.window = FxTesting.onFx(() -> new MainWindow(this.test.gui));
        this.test.show(this.window.root());
    }

    private ToggleButton rail(String title) {
        return this.window.root().lookupAll(".nav-rail .toggle-button").stream()
                .map(ToggleButton.class::cast)
                .filter(b -> title.equals(b.getText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no place " + title));
    }
}
