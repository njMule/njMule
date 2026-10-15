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
import java.util.concurrent.CompletableFuture;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
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

@Timeout(60)
class DownloadDetailsTest {

    private static final long SIZE = 4 * PartMap.PART_SIZE;
    private static final DownloadInfo FILM = new DownloadInfo(
            Hash16.parseHex("0123456789ABCDEF0123456789ABCDEF"),
            "film.mkv",
            SIZE,
            2 * PartMap.PART_SIZE,
            DownloadInfo.Status.DOWNLOADING,
            DownloadPriority.HIGH,
            0,
            7,
            2,
            2048,
            new PartMap(
                    SIZE,
                    List.of(
                            new PartMap.Span(PartMap.PART_SIZE, PartMap.PART_SIZE + 10),
                            new PartMap.Span(3 * PartMap.PART_SIZE, SIZE)),
                    List.of(),
                    List.of(1, 2, 3, 0)),
            false,
            false);

    @TempDir
    Path dir;

    private TestGui test;
    private TransfersScreen screen;
    private int complete;

    @AfterEach
    void close() {
        if (this.test != null) {
            this.test.close();
        }
    }

    @Test
    void aPartWithAnyByteMissingIsNotComplete() {
        whenTheCompletePartsAreCounted(FILM.parts(), 4);
        thenTheyAre(2);
    }

    @Test
    void theDetailsOpenFromTheirButtonAndShowTheDownloadChosen() throws Exception {
        givenTheTransfersWith(FILM);
        thenTheDetailsAre(false);
        whenTheDetailsButtonIsPressed();
        thenTheDetailsAre(true);
        thenTheDetailsTell("Choose one download");
        whenTheDownloadIsChosen();
        thenTheDetailsTell("film.mkv", "2 / 4", "7", "High");
        thenTheSettingsRemember(true);
    }

    @Test
    void altEnterOpensTheDetails() throws Exception {
        givenTheTransfersWith(FILM);
        whenAltEnterIsPressed();
        thenTheDetailsAre(true);
    }

    private void givenTheTransfersWith(DownloadInfo download) throws Exception {
        Map<String, Engines.Command> commands =
                Map.of("downloads", a -> CompletableFuture.completedFuture(List.of(download)));
        this.test = TestGui.start(this.dir.resolve("njmule"), real -> Engines.overriding(real, commands));
        this.screen = new TransfersScreen(this.test.gui);
        this.test.show(FxTesting.onFx(this.screen::view));
        FxTesting.onFx(this.test.model::tick);
        FxTesting.await("the download", () -> this.screen.downloads().getItems().size() == 1);
    }

    private void whenTheCompletePartsAreCounted(PartMap map, int total) {
        this.complete = DownloadDetails.complete(map, total);
    }

    private void whenTheDetailsButtonIsPressed() throws Exception {
        FxTesting.onFx(() -> ((ToggleButton) this.screen.view().lookup("#details")).fire());
    }

    private void whenAltEnterIsPressed() throws Exception {
        FxTesting.onFx(() -> this.screen
                .downloads()
                .fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER, false, false, true, false)));
    }

    private void whenTheDownloadIsChosen() throws Exception {
        FxTesting.onFx(() -> this.screen.downloads().getSelectionModel().select(0));
    }

    private void thenTheyAre(int expected) {
        assertThat(this.complete).isEqualTo(expected);
    }

    private void thenTheDetailsAre(boolean shown) throws Exception {
        assertThat(FxTesting.onFx(() -> this.screen.details().view().getScene() != null))
                .isEqualTo(shown);
    }

    private void thenTheDetailsTell(String... texts) throws Exception {
        List<String> shown = FxTesting.onFx(() -> {
            Node view = this.screen.details().view();
            view.applyCss();
            return view.lookupAll(".label").stream()
                    .filter(Node::isVisible)
                    .filter(n -> n.getParent() == null || n.getParent().isVisible())
                    .map(n -> ((Label) n).getText())
                    .toList();
        });
        for (String text : texts) {
            assertThat(shown).anyMatch(t -> t != null && t.contains(text));
        }
    }

    private void thenTheSettingsRemember(boolean shown) throws Exception {
        assertThat(FxTesting.onFx(() -> this.test.gui.settings().transfersDetails()))
                .isEqualTo(shown);
    }
}
