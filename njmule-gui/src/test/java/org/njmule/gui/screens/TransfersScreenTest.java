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
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.core.api.DownloadInfo;
import org.njmule.core.api.DownloadPriority;
import org.njmule.core.api.EngineEvent;
import org.njmule.core.api.Hash16;
import org.njmule.core.api.PartMap;
import org.njmule.gui.FxTesting;
import org.njmule.gui.TestGui;

@Timeout(120)
class TransfersScreenTest {

    private static final Hash16 FILE = Hash16.parseHex("0123456789ABCDEF0123456789ABCDEF");

    @TempDir
    Path dir;

    private TestGui test;

    @AfterEach
    void close() {
        if (this.test != null) {
            this.test.close();
        }
    }

    @Test
    void aCompletedDownloadOffersNoPreviewNorSource() throws Exception {
        this.test = TestGui.start(this.dir.resolve("njmule"));
        TransfersScreen screen = new TransfersScreen(this.test.gui);
        this.test.show(FxTesting.onFx(screen::view));
        DownloadInfo done = new DownloadInfo(
                FILE,
                "film.mkv",
                1000,
                1000,
                DownloadInfo.Status.COMPLETE,
                DownloadPriority.AUTO,
                0,
                0,
                0,
                0,
                PartMap.EMPTY);
        FxTesting.onFx(() -> this.test.model.handle(new EngineEvent.DownloadCompleted(done, this.dir.resolve("f"))));
        FxTesting.await("the download row", () -> screen.downloads().getItems().size() == 1);

        FxTesting.onFx(() -> {
            screen.downloads().getSelectionModel().select(0);
            ContextMenu menu = screen.downloads().getContextMenu();
            menu.getOnShowing().handle(null);
            assertThat(this.item(menu, "IDS_DL_PREVIEW").isDisable())
                    .as("preview")
                    .isTrue();
            assertThat(this.item(menu, "IDS_ADDSRCMANUALLY").isDisable())
                    .as("add a source")
                    .isTrue();
            assertThat(this.item(menu, "IDS_CMT_ADD").isDisable())
                    .as("comments")
                    .isFalse();
        });
    }

    private MenuItem item(ContextMenu menu, String key) {
        String text = this.test.gui.t(key);
        return menu.getItems().stream()
                .filter(i -> text.equals(i.getText()))
                .findFirst()
                .orElseThrow();
    }
}
