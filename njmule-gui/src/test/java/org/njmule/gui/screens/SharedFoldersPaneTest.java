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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import javafx.scene.Node;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Labeled;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.core.api.Hash16;
import org.njmule.core.api.SharedDirectory;
import org.njmule.core.api.SharedFileInfo;
import org.njmule.core.api.UploadPriority;
import org.njmule.gui.Engines;
import org.njmule.gui.FxTesting;
import org.njmule.gui.TestGui;

/** The folders pane of the shared files, and what was asked of each file. */
@Timeout(120)
class SharedFoldersPaneTest {

    @TempDir
    Path dir;

    private TestGui test;
    private SharedScreen screen;
    private Path films;
    private Path music;
    private final List<String> calls = new CopyOnWriteArrayList<>();

    @AfterEach
    void close() {
        if (this.test != null) {
            this.test.close();
        }
    }

    @Test
    void shouldListTheSharedFoldersEachWithItsSubfoldersSwitch() throws Exception {
        givenFilmsSharedWithSubfoldersAndMusicAlone();

        whenTheSharedScreenShows();

        thenThePaneLists(new SharedDirectory(this.films, true), new SharedDirectory(this.music, false));
        thenTheSubfoldersSwitchesAre(true, false);
    }

    @Test
    void shouldShareTheSubfoldersOfAFolderOnceItsSwitchIsTicked() throws Exception {
        givenFilmsSharedWithSubfoldersAndMusicAlone();
        givenTheSharedScreenShown();

        whenTheSubfoldersSwitchIsTicked(1);

        thenTheEngineWasAsked("share " + this.music + " true");
    }

    @Test
    void shouldStopSharingTheChosenFolder() throws Exception {
        givenFilmsSharedWithSubfoldersAndMusicAlone();
        givenTheSharedScreenShown();

        whenTheFolderIsUnshared(0);

        thenTheEngineWasAsked("unshare " + this.films);
    }

    @Test
    void shouldShowWhatWasAskedOfAFileInThisRunAndInAll() throws Exception {
        givenFilmsSharedWithSubfoldersAndMusicAlone();

        whenTheSharedScreenShows();

        thenTheTableShows("2 (4)", "1 (2)");
    }

    private void givenFilmsSharedWithSubfoldersAndMusicAlone() throws Exception {
        this.films = this.dir.resolve("films");
        this.music = this.dir.resolve("music");
        SharedFileInfo film = new SharedFileInfo(
                Hash16.parseHex("00112233445566778899AABBCCDDEEFF"),
                "film.mkv",
                700_000_000,
                this.films.resolve("film.mkv"),
                UploadPriority.AUTO,
                new SharedFileInfo.Statistics(2, 1, 9_000, 4, 2, 10_000));
        Map<String, Engines.Command> commands = new HashMap<>();
        commands.put("sharedFiles", args -> CompletableFuture.completedFuture(List.of(film)));
        commands.put(
                "sharedDirectories",
                args -> CompletableFuture.completedFuture(
                        List.of(new SharedDirectory(this.films, true), new SharedDirectory(this.music, false))));
        commands.put("shareDirectory", args -> {
            this.calls.add("share " + args[0] + " " + (args.length > 1 ? args[1] : ""));
            return CompletableFuture.completedFuture(null);
        });
        commands.put("unshareDirectory", args -> {
            this.calls.add("unshare " + args[0]);
            return CompletableFuture.completedFuture(null);
        });
        this.test = TestGui.start(this.dir.resolve("njmule"), real -> Engines.overriding(real, commands));
    }

    private void givenTheSharedScreenShown() throws Exception {
        whenTheSharedScreenShows();
        FxTesting.await("the folders", () -> this.screen.folders().getItems().size() == 2);
    }

    private void whenTheSharedScreenShows() throws Exception {
        this.screen = FxTesting.onFx(() -> new SharedScreen(this.test.gui));
        this.test.show(FxTesting.onFx(this.screen::view));
    }

    private void whenTheSubfoldersSwitchIsTicked(int folder) throws Exception {
        FxTesting.onFx(() -> switches().get(folder).fire());
    }

    private void whenTheFolderIsUnshared(int folder) throws Exception {
        FxTesting.onFx(() -> {
            this.screen.folders().getSelectionModel().select(folder);
            this.screen.unshareFolder();
        });
    }

    private void thenThePaneLists(SharedDirectory... folders) throws Exception {
        FxTesting.assertEventually(
                "the folders", () -> this.screen.folders().getItems().equals(List.of(folders)));
    }

    private void thenTheSubfoldersSwitchesAre(Boolean... ticked) throws Exception {
        FxTesting.assertEventually(
                "the switches",
                () -> switches().stream().map(CheckBox::isSelected).toList().equals(List.of(ticked)));
    }

    private void thenTheEngineWasAsked(String call) throws Exception {
        FxTesting.assertEventually("the engine asked " + call, () -> this.calls.contains(call));
    }

    private void thenTheTableShows(String... texts) throws Exception {
        FxTesting.assertEventually(
                "the counts " + List.of(texts), () -> cellTexts().containsAll(List.of(texts)));
        assertThat(FxTesting.onFx(this::cellTexts)).contains(texts);
    }

    private List<String> cellTexts() {
        return this.screen.table().lookupAll(".table-cell").stream()
                .map(n -> ((Labeled) n).getText())
                .filter(t -> t != null)
                .toList();
    }

    // The switches of the folders shown, in their order.
    private List<CheckBox> switches() {
        return this.screen.folders().lookupAll(".check-box").stream()
                .filter(Node::isVisible)
                .map(CheckBox.class::cast)
                .filter(c -> c.getParent() != null && c.getParent().isVisible())
                .toList();
    }
}
