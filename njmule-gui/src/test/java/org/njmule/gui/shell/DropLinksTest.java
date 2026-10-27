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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.core.api.DownloadInfo;
import org.njmule.gui.FxTesting;
import org.njmule.gui.TestGui;

@Timeout(60)
class DropLinksTest {

    // As a page gives them: the links one after another, as copied or dragged.
    private static final String TWO_LINKS = "ed2k://|file|first%20file.bin|10000|0123456789ABCDEF0123456789ABCDEF|/"
            + "\ned2k://|file|second.bin|20000|FEDCBA9876543210FEDCBA9876543210|/";

    @TempDir
    Path dir;

    private TestGui test;
    private MainWindow window;
    private int found;

    @AfterEach
    void close() {
        if (this.test != null) {
            this.test.close();
        }
    }

    @Test
    void shouldTurnTheFileLinksDroppedOnTheWindowIntoDownloads() throws Exception {
        givenTheWindow();
        whenDropped(TWO_LINKS);
        thenTheLinksFoundAre(2);
        thenTheDownloadsAre("first file.bin", "second.bin");
    }

    @Test
    void shouldNotTakeATextWithoutLinks() throws Exception {
        givenTheWindow();
        whenDropped("just some words");
        thenTheLinksFoundAre(0);
    }

    private void givenTheWindow() throws Exception {
        this.test = TestGui.start(this.dir.resolve("njmule"));
        this.window = FxTesting.onFx(() -> new MainWindow(this.test.gui));
        this.test.show(this.window.root());
    }

    private void whenDropped(String text) throws Exception {
        this.found = FxTesting.onFx(() -> this.window.dropped(text));
    }

    private void thenTheLinksFoundAre(int count) {
        assertThat(this.found).isEqualTo(count);
    }

    private void thenTheDownloadsAre(String... names) throws Exception {
        FxTesting.assertEventually(
                "the downloads", () -> this.test.engine.downloads().join().size() == names.length);
        assertThat(FxTesting.await(this.test.engine.downloads()))
                .extracting(DownloadInfo::name)
                .containsExactlyInAnyOrder(names);
    }
}
