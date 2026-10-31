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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.layout.StackPane;
import javafx.stage.Window;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.core.api.DownloadInfo;
import org.njmule.core.api.DownloadPriority;
import org.njmule.core.api.Hash16;
import org.njmule.core.api.PartMap;
import org.njmule.gui.Engines;
import org.njmule.gui.FxTesting;
import org.njmule.gui.TestGui;
import org.njmule.gui.dialogs.CategoriesDialog;

/** A window on a njMule of another computer opens no folder of this one: it tells where they are instead. */
@Timeout(60)
class RemoteFolderActionsTest {

    private static final String ELSEWHERE = "on its own computer";

    @TempDir
    Path dir;

    private TestGui test;
    private Node view;
    private SharedScreen shared;
    private TransfersScreen transfers;

    @AfterEach
    void close() {
        if (this.test != null) {
            this.test.close();
        }
    }

    @Test
    void shouldTellThatTheFolderToBrowseForIsOnTheOtherComputer() throws Exception {
        givenAWindowOnAnotherComputer();
        givenTheOptionsPageShown("folders");

        whenTheButtonIsPressed("Browse...");

        thenTheUserIsTold(ELSEWHERE);
    }

    @Test
    void shouldTellThatTheFolderToShareIsOnTheOtherComputer() throws Exception {
        givenAWindowOnAnotherComputer();
        givenTheSharedFilesShown();

        whenTheButtonIsPressed("Share a folder");

        thenTheUserIsTold(ELSEWHERE);
    }

    @Test
    void shouldTellThatTheFolderOfASharedFileIsOnTheOtherComputer() throws Exception {
        givenAWindowOnAnotherComputer();
        givenTheSharedFilesShown();
        givenASharedFileChosen();

        whenItsFolderIsOpened();

        thenTheUserIsTold(ELSEWHERE);
    }

    @Test
    void shouldTellThatTheFolderOfACompletedDownloadIsOnTheOtherComputer() throws Exception {
        givenAWindowOnAnotherComputerThatDownloaded(new DownloadInfo(
                Hash16.parseHex("AB".repeat(16)),
                "talk.bin",
                1000,
                1000,
                DownloadInfo.Status.COMPLETE,
                DownloadPriority.AUTO,
                0,
                0,
                0,
                0,
                PartMap.EMPTY));
        givenTheTransfersShown();
        givenTheDownloadChosen();

        whenItsFileIsShownInItsFolder();

        thenTheUserIsTold(ELSEWHERE);
    }

    @Test
    void shouldTellThatTheFolderOfACategoryIsOnTheOtherComputer() throws Exception {
        givenAWindowOnAnotherComputer();
        givenTheCategoriesOpen();

        whenTheButtonIsPressed("Browse...");

        thenTheUserIsTold(ELSEWHERE);
    }

    private void givenAWindowOnAnotherComputerThatDownloaded(DownloadInfo download) throws Exception {
        Map<String, Engines.Command> commands =
                Map.of("downloads", args -> CompletableFuture.completedFuture(List.of(download)));
        this.test = TestGui.start(
                this.dir.resolve("njmule"), real -> Engines.elsewhere(Engines.overriding(real, commands)));
    }

    private void givenTheTransfersShown() throws Exception {
        this.transfers = FxTesting.onFx(() -> new TransfersScreen(this.test.gui));
        this.view = FxTesting.onFx(this.transfers::view);
        this.test.show(this.view);
    }

    private void givenTheDownloadChosen() throws Exception {
        for (int i = 0;
                i < 40
                        && FxTesting.onFx(
                                () -> this.transfers.downloads().getItems().isEmpty());
                i++) {
            Thread.sleep(250);
            FxTesting.onFx(this.test.model::tick);
        }
        FxTesting.onFx(() -> this.transfers.downloads().getSelectionModel().select(0));
    }

    // The dialog's buttons are those looked in.
    private void givenTheCategoriesOpen() throws Exception {
        StackPane owner = new StackPane();
        this.test.show(owner);
        FxTesting.onFx(
                () -> new CategoriesDialog(this.test.gui, owner.getScene().getWindow()).show());
        Window first = FxTesting.onFx(() -> owner.getScene().getWindow());
        this.view = FxTesting.onFx(() -> Window.getWindows().stream()
                .filter(w -> w.isShowing() && !w.equals(first))
                .findFirst()
                .orElseThrow()
                .getScene()
                .getRoot());
    }

    private void givenAWindowOnAnotherComputer() throws Exception {
        this.test = TestGui.start(this.dir.resolve("njmule"), Engines::elsewhere);
    }

    private void givenTheOptionsPageShown(String page) throws Exception {
        SettingsScreen screen = FxTesting.onFx(() -> new SettingsScreen(this.test.gui));
        this.view = FxTesting.onFx(screen::view);
        this.test.show(this.view);
        FxTesting.onFx(() -> screen.pagesView().show(page));
    }

    private void givenTheSharedFilesShown() throws Exception {
        this.shared = FxTesting.onFx(() -> new SharedScreen(this.test.gui));
        this.view = FxTesting.onFx(this.shared::view);
        this.test.show(this.view);
    }

    private void givenASharedFileChosen() throws Exception {
        Path folder = Files.createDirectories(this.dir.resolve("films"));
        Files.write(folder.resolve("talk.bin"), new byte[100]);
        FxTesting.onFx(() -> this.shared.share(folder, true));
        // The list follows the model, which reads the engine at each tick.
        for (int i = 0;
                i < 40 && FxTesting.onFx(() -> this.shared.table().getItems().isEmpty());
                i++) {
            Thread.sleep(250);
            FxTesting.onFx(this.test.model::tick);
        }
        FxTesting.onFx(() -> this.shared.table().getSelectionModel().selectFirst());
    }

    private void whenTheButtonIsPressed(String text) throws Exception {
        FxTesting.onFx(() -> this.view.lookupAll(".button").stream()
                .filter(n -> n instanceof Button b && text.equals(b.getText()))
                .map(Button.class::cast)
                .findFirst()
                .orElseThrow()
                .fire());
    }

    private void whenItsFileIsShownInItsFolder() throws Exception {
        FxTesting.onFx(this.transfers::revealSelected);
    }

    private void whenItsFolderIsOpened() throws Exception {
        FxTesting.onFx(this.shared::openFolder);
    }

    private void thenTheUserIsTold(String part) {
        assertThat(this.test.told).anyMatch(text -> text.contains(part));
    }
}
