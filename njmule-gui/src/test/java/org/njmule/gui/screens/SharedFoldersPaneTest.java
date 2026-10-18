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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import javafx.scene.control.Labeled;
import javafx.scene.control.TreeItem;
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

@Timeout(60)
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
    void shouldListAllFilesTheIncomingFolderAndEachSharedFolderWithItsSubfolders() throws Exception {
        givenFilmsSharedWithSubfoldersAndMusicAlone();

        whenTheSharedScreenShows();

        thenTheTreeLists(
                "ALL ",
                "INCOMING " + incoming(),
                "FOLDER " + this.films,
                "SUBFOLDER " + this.films.resolve("2026"),
                "FOLDER " + this.music);
    }

    @Test
    void shouldShowOnlyTheFilesOfThePlaceChosen() throws Exception {
        givenFilmsSharedWithSubfoldersAndMusicAlone();
        givenTheSharedScreenShown();

        whenThePlaceIsChosen(this.films.resolve("2026"));

        thenTheFilesShownAre("clip.mkv");
    }

    @Test
    void shouldShareTheSubfoldersOfAFolderOnceItsSwitchIsTurnedOn() throws Exception {
        givenFilmsSharedWithSubfoldersAndMusicAlone();
        givenTheSharedScreenShown();
        givenThePlaceChosen(this.music);

        whenTheSubfoldersSwitchIsTurned(true);

        thenTheEngineWasAsked("share " + this.music + " true");
    }

    @Test
    void shouldStopSharingTheChosenFolder() throws Exception {
        givenFilmsSharedWithSubfoldersAndMusicAlone();
        givenTheSharedScreenShown();
        givenThePlaceChosen(this.films);

        whenTheFolderIsUnshared();

        thenTheEngineWasAsked("unshare " + this.films);
    }

    @Test
    void shouldCountWhatWasAskedOfAFileInThisRun() throws Exception {
        givenFilmsSharedWithSubfoldersAndMusicAlone();

        whenTheSharedScreenShows();

        thenTheTableShows("2");
    }

    @Test
    void shouldCountWhatWasAskedOfAFileInAllRunsWhenAsked() throws Exception {
        givenFilmsSharedWithSubfoldersAndMusicAlone();
        givenTheSharedScreenShown();

        whenTheCountersCount(SharedScreen.Counter.TOTAL);

        thenTheTableShows("4");
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
        SharedFileInfo clip = new SharedFileInfo(
                Hash16.parseHex("FFEEDDCCBBAA99887766554433221100"),
                "clip.mkv",
                70_000_000,
                this.films.resolve("2026").resolve("clip.mkv"),
                UploadPriority.AUTO,
                new SharedFileInfo.Statistics(0, 0, 0, 0, 0, 0));
        Map<String, Engines.Command> commands = new HashMap<>();
        commands.put("sharedFiles", args -> CompletableFuture.completedFuture(List.of(film, clip)));
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
        FxTesting.await("the folders", () -> places().size() == 5);
    }

    private void givenThePlaceChosen(Path path) throws Exception {
        whenThePlaceIsChosen(path);
    }

    private void whenTheSharedScreenShows() throws Exception {
        this.screen = FxTesting.onFx(() -> new SharedScreen(this.test.gui));
        this.test.show(FxTesting.onFx(this.screen::view));
    }

    private void whenThePlaceIsChosen(Path path) throws Exception {
        FxTesting.onFx(() -> this.screen.choosePlace(path));
    }

    private void whenTheSubfoldersSwitchIsTurned(boolean on) throws Exception {
        FxTesting.onFx(() -> this.screen.subfoldersSwitch().setSelected(on));
    }

    private void whenTheFolderIsUnshared() throws Exception {
        FxTesting.onFx(this.screen::unshareFolder);
    }

    private void whenTheCountersCount(SharedScreen.Counter counter) throws Exception {
        FxTesting.onFx(() -> this.screen.counters().setValue(counter));
    }

    private void thenTheTreeLists(String... places) throws Exception {
        FxTesting.assertEventually("the places " + List.of(places), () -> places().equals(List.of(places)));
    }

    private void thenTheFilesShownAre(String... names) throws Exception {
        FxTesting.assertEventually(
                "the files " + List.of(names),
                () -> this.screen.table().getItems().stream()
                        .map(r -> r.get().name())
                        .toList()
                        .equals(List.of(names)));
    }

    private void thenTheEngineWasAsked(String call) throws Exception {
        FxTesting.assertEventually("the engine asked " + call, () -> this.calls.contains(call));
    }

    private void thenTheTableShows(String... texts) throws Exception {
        FxTesting.assertEventually(
                "the counts " + List.of(texts), () -> cellTexts().containsAll(List.of(texts)));
        assertThat(FxTesting.onFx(this::cellTexts)).contains(texts);
    }

    private Path incoming() {
        return this.test.model.preferencesProperty().get().effectiveIncomingDir(this.test.gui.directories());
    }

    // Each place of the tree as its kind and path, in the order of the tree.
    private List<String> places() {
        List<String> all = new ArrayList<>();
        walk(this.screen.places().getRoot(), all);
        return all;
    }

    private static void walk(TreeItem<SharedScreen.Place> parent, List<String> into) {
        for (TreeItem<SharedScreen.Place> item : parent.getChildren()) {
            into.add(item.getValue().kind() + " " + item.getValue().path());
            walk(item, into);
        }
    }

    private List<String> cellTexts() {
        return this.screen.table().lookupAll(".table-cell").stream()
                .map(n -> ((Labeled) n).getText())
                .filter(t -> t != null)
                .toList();
    }
}
