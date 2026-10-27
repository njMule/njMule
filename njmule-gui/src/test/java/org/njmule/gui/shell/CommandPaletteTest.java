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
import org.njmule.gui.Engines;
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
    void shouldOpenThePaletteOnCtrlKAndRunTheCommandFoundOnEnter() throws Exception {
        givenTheWindow();
        givenTheShortcutPressed(KeyCode.K);
        givenThePaletteShows(true);
        givenTyped("stat");
        givenTheFirstCommandIs("Statistics");

        whenEnterIsPressed();

        thenThePaletteShows(false);
        thenTheScreenIs("statistics");
    }

    @Test
    void shouldOfferASearchOfTheNetworkForTheWordsTyped() throws Exception {
        givenTheWindow();
        givenTheShortcutPressed(KeyCode.K);

        whenTyped("big buck bunny");

        thenTheFirstCommandIs("Search the network for \u201cbig buck bunny\u201d");
    }

    @Test
    void shouldOfferTheDownloadOfALinkTyped() throws Exception {
        givenTheWindow();
        givenTheShortcutPressed(KeyCode.K);

        whenTyped(LINK);

        thenTheFirstCommandIs("Download: film.mkv");
    }

    @Test
    void shouldTellThatTheIncomingFolderOfAnotherComputerIsThere() throws Exception {
        givenTheWindowOnAnotherComputer();
        givenTheShortcutPressed(KeyCode.K);
        givenTyped("Open Incoming Folder");
        givenTheFirstCommandIs("Open Incoming Folder");

        whenEnterIsPressed();

        thenTheUserIsTold("on its own computer");
    }

    private void givenTheWindow() throws Exception {
        this.test = TestGui.start(this.dir.resolve("njmule"));
        this.window = FxTesting.onFx(() -> new MainWindow(this.test.gui));
        this.test.show(this.window.root());
    }

    private void givenTheWindowOnAnotherComputer() throws Exception {
        this.test = TestGui.start(this.dir.resolve("njmule"), Engines::elsewhere);
        this.window = FxTesting.onFx(() -> new MainWindow(this.test.gui));
        this.test.show(this.window.root());
    }

    private void givenTheShortcutPressed(KeyCode key) throws Exception {
        FxTesting.onFx(() -> this.window.root().fireEvent(FxTesting.key(key, true)));
    }

    private void givenThePaletteShows(boolean shown) throws Exception {
        thenThePaletteShows(shown);
    }

    private void givenTyped(String text) throws Exception {
        whenTyped(text);
    }

    private void givenTheFirstCommandIs(String text) throws Exception {
        thenTheFirstCommandIs(text);
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

    private void thenTheUserIsTold(String part) {
        assertThat(this.test.told).anyMatch(text -> text.contains(part));
    }

    private void thenTheScreenIs(String id) throws Exception {
        assertThat(FxTesting.onFx(this.window::current)).isEqualTo(id);
    }
}
