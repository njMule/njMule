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
import javafx.scene.input.KeyCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.gui.FxTesting;
import org.njmule.gui.TestGui;

@Timeout(60)
class CommandPaletteTest {

    private static final String LINK = "ed2k://|file|film.mkv|10000|0123456789ABCDEF0123456789ABCDEF|/";

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
    void ctrlKOpensThePaletteAndEnterRunsTheCommandFound() throws Exception {
        this.givenTheWindow();
        this.whenTheShortcutIsPressed(KeyCode.K);
        this.thenThePaletteShows(true);
        this.whenTyped("stat");
        this.thenTheFirstCommandIs("Statistics");
        this.whenEnterIsPressed();
        this.thenThePaletteShows(false);
        this.thenTheScreenIs("statistics");
    }

    @Test
    void wordsTypedOfferASearchOfTheNetwork() throws Exception {
        this.givenTheWindow();
        this.whenTheShortcutIsPressed(KeyCode.K);
        this.whenTyped("big buck bunny");
        this.thenTheFirstCommandIs("Search the network for \u201cbig buck bunny\u201d");
    }

    @Test
    void aLinkTypedOffersItsDownload() throws Exception {
        this.givenTheWindow();
        this.whenTheShortcutIsPressed(KeyCode.K);
        this.whenTyped(LINK);
        this.thenTheFirstCommandIs("Download: film.mkv");
    }

    private void givenTheWindow() throws Exception {
        this.test = TestGui.start(this.dir.resolve("njmule"));
        this.window = FxTesting.onFx(() -> new MainWindow(this.test.gui));
        this.test.show(this.window.root());
    }

    private void whenTheShortcutIsPressed(KeyCode key) throws Exception {
        FxTesting.onFx(() -> this.window.root().fireEvent(FxTesting.key(key, true)));
    }

    private void whenTyped(String text) throws Exception {
        FxTesting.onFx(() -> this.window.palette().field().setText(text));
    }

    private void whenEnterIsPressed() throws Exception {
        FxTesting.onFx(() -> this.window.palette().field().fireEvent(FxTesting.key(KeyCode.ENTER, false)));
    }

    private void thenThePaletteShows(boolean shown) throws Exception {
        assertThat(FxTesting.onFx(() -> this.window.palette().isShowing())).isEqualTo(shown);
    }

    private void thenTheFirstCommandIs(String text) throws Exception {
        assertThat(FxTesting.onFx(() -> this.window.palette().shown().getFirst().text()))
                .isEqualTo(text);
    }

    private void thenTheScreenIs(String id) throws Exception {
        assertThat(FxTesting.onFx(this.window::current)).isEqualTo(id);
    }
}
