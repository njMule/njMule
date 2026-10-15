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
import java.util.List;
import javafx.scene.Node;
import javafx.scene.control.Button;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.gui.FxTesting;
import org.njmule.gui.TestGui;

@Timeout(60)
class EmptyStatesTest {

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
    void anEmptyListOfDownloadsSaysHowToFillItAndLeadsThere() throws Exception {
        givenTheWindowWithoutDownloads();
        thenTheEmptyListOffers("Search", "Paste eD2K Links");
        whenTheEmptyListsButtonIsPressed("Search");
        thenTheScreenIs("search");
    }

    private void givenTheWindowWithoutDownloads() throws Exception {
        this.test = TestGui.start(this.dir.resolve("njmule"));
        this.window = FxTesting.onFx(() -> new MainWindow(this.test.gui));
        this.test.show(this.window.root());
        FxTesting.onFx(() -> this.window.show("transfers"));
    }

    private void whenTheEmptyListsButtonIsPressed(String text) throws Exception {
        FxTesting.onFx(() -> buttons().stream()
                .filter(b -> text.equals(b.getText()))
                .findFirst()
                .orElseThrow()
                .fire());
    }

    private void thenTheEmptyListOffers(String... texts) throws Exception {
        assertThat(FxTesting.onFx(() -> buttons().stream().map(Button::getText).toList()))
                .containsExactly(texts);
    }

    private void thenTheScreenIs(String id) throws Exception {
        assertThat(FxTesting.onFx(this.window::current)).isEqualTo(id);
    }

    private List<Button> buttons() {
        Node empty = this.window.root().lookup(".empty-state");
        return empty.lookupAll(".button").stream().map(Button.class::cast).toList();
    }
}
