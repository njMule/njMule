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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import javafx.scene.Node;
import javafx.scene.control.Label;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.core.api.DownloadInfo;
import org.njmule.core.api.DownloadPriority;
import org.njmule.core.api.Hash16;
import org.njmule.core.api.PartMap;
import org.njmule.core.api.SourceInfo;
import org.njmule.gui.Engines;
import org.njmule.gui.FxTesting;
import org.njmule.gui.TestGui;

@Timeout(60)
class DownloadSourcesTest {

    private static final DownloadInfo FILM = new DownloadInfo(
            Hash16.parseHex("0123456789ABCDEF0123456789ABCDEF"),
            "film.mkv",
            2 * PartMap.PART_SIZE,
            PartMap.PART_SIZE,
            DownloadInfo.Status.DOWNLOADING,
            DownloadPriority.AUTO,
            0,
            1,
            1,
            2048,
            PartMap.EMPTY);

    @TempDir
    Path dir;

    private TestGui test;
    private TransfersScreen screen;

    @AfterEach
    void close() {
        if (this.test != null) {
            this.test.close();
        }
    }

    @Test
    void shouldShowTheSourcesOfADownloadUnderItWhenItIsOpened() throws Exception {
        givenTheFilmWithASourceAndASourceOfAnotherFile();

        whenTheFilmIsOpened();

        thenTheSourcesShownAre("10.0.0.1:4662");
    }

    @Test
    void shouldHideTheSourcesOfADownloadOpenedAgain() throws Exception {
        givenTheFilmWithASourceAndASourceOfAnotherFile();
        givenTheFilmOpened();

        whenTheFilmIsOpened();

        thenNoSourceIsShown();
    }

    private void givenTheFilmWithASourceAndASourceOfAnotherFile() throws Exception {
        List<SourceInfo> sources = List.of(
                source(FILM.hash(), "10.0.0.1:4662"), source(Hash16.parseHex("FF".repeat(16)), "10.0.0.2:4662"));
        Map<String, Engines.Command> commands = Map.of(
                "downloads", a -> CompletableFuture.completedFuture(List.of(FILM)),
                "sources", a -> CompletableFuture.completedFuture(sources));
        this.test = TestGui.start(this.dir.resolve("njmule"), real -> Engines.overriding(real, commands));
        this.screen = new TransfersScreen(this.test.gui);
        this.test.show(FxTesting.onFx(this.screen::view));
        FxTesting.onFx(this.test.model::tick);
        FxTesting.onFx(this.test.model::tick);
        FxTesting.await("the download", () -> this.screen.downloads().getItems().size() == 1);
        FxTesting.await("the sources", () -> this.test.model.sources().size() == sources.size());
    }

    private void givenTheFilmOpened() throws Exception {
        whenTheFilmIsOpened();
        FxTesting.await("the sources shown", () -> !shownSources().isEmpty());
    }

    private void whenTheFilmIsOpened() throws Exception {
        FxTesting.onFx(() -> this.screen.activate(FILM));
    }

    private void thenTheSourcesShownAre(String... names) throws Exception {
        FxTesting.assertEventually(
                "the sources under the download", () -> shownSources().equals(List.of(names)));
    }

    private void thenNoSourceIsShown() throws Exception {
        FxTesting.assertEventually(
                "no source under the download", () -> shownSources().isEmpty());
        assertThat(FxTesting.onFx(() -> this.screen.isOpened(FILM.hash()))).isFalse();
    }

    // The names of the source lines in the scene, those of rows taken off it left out; on the FX thread.
    private List<String> shownSources() {
        return this.screen.downloads().lookupAll(".source-name").stream()
                .filter(DownloadSourcesTest::inTheScene)
                .map(n -> ((Label) n).getText())
                .toList();
    }

    private static boolean inTheScene(Node node) {
        return node.getScene() != null && node.isVisible();
    }

    private static SourceInfo source(Hash16 file, String address) {
        return new SourceInfo(
                file,
                "film.mkv",
                address,
                Optional.empty(),
                "eMule",
                SourceInfo.State.DOWNLOADING,
                0,
                1024,
                1,
                2,
                Optional.empty(),
                "",
                Optional.empty());
    }
}
