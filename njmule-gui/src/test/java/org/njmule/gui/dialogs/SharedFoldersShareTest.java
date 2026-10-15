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
package org.njmule.gui.dialogs;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import javafx.scene.control.Button;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.gui.FxTesting;
import org.njmule.gui.TestGui;

@Timeout(60)
class SharedFoldersShareTest {

    @TempDir
    Path dir;

    private TestGui test;
    private SharedFoldersDialog dialog;
    private final AtomicInteger asked = new AtomicInteger();

    @AfterEach
    void close() throws Exception {
        if (this.dialog != null) {
            FxTesting.onFx(() -> this.dialog.stage().close());
        }
        if (this.test != null) {
            this.test.close();
        }
    }

    @Test
    void theFoldersCanGrowFromTheirOwnWindow() throws Exception {
        this.givenTheSharedFoldersWithAWayToShare();
        this.whenTheButtonIsPressed("Share Directory");
        this.thenAFolderWasAskedFor();
    }

    private void givenTheSharedFoldersWithAWayToShare() throws Exception {
        this.test = TestGui.start(this.dir.resolve("njmule"));
        this.dialog = FxTesting.onFx(() -> new SharedFoldersDialog(this.test.gui, null, this.asked::incrementAndGet));
        FxTesting.onFx(this.dialog::show);
    }

    private void whenTheButtonIsPressed(String text) throws Exception {
        FxTesting.onFx(() -> this.dialog.stage().getScene().getRoot().lookupAll(".button").stream()
                .map(Button.class::cast)
                .filter(b -> text.equals(b.getText()))
                .findFirst()
                .orElseThrow()
                .fire());
    }

    private void thenAFolderWasAskedFor() {
        assertThat(this.asked).hasValue(1);
    }
}
