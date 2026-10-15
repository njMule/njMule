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
import javafx.scene.Parent;
import javafx.scene.control.ToggleButton;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.gui.FxTesting;
import org.njmule.gui.TestGui;

@Timeout(60)
class SearchParametersFoldTest {

    @TempDir
    Path dir;

    private TestGui test;
    private SearchScreen screen;
    private Parent view;

    @AfterEach
    void close() {
        if (this.test != null) {
            this.test.close();
        }
    }

    @Test
    void theParametersFoldAwayUntilWantedAndTheButtonCountsThoseInUse() throws Exception {
        givenTheSearchScreen();
        thenTheParametersShow(false);
        thenTheButtonReads("Search Parameters");
        whenTheButtonIsPressed();
        thenTheParametersShow(true);
        whenTheMinimumSizeIs("100");
        whenTheExtensionIs("mkv");
        thenTheButtonReads("Search Parameters (2)");
        whenTheButtonIsPressed();
        thenTheParametersShow(false);
    }

    private void givenTheSearchScreen() throws Exception {
        this.test = TestGui.start(this.dir.resolve("njmule"));
        this.screen = FxTesting.onFx(() -> new SearchScreen(this.test.gui));
        this.view = FxTesting.onFx(() -> (Parent) this.screen.view());
        this.test.show(this.view);
    }

    private void whenTheButtonIsPressed() throws Exception {
        FxTesting.onFx(() -> button().fire());
    }

    private void whenTheMinimumSizeIs(String text) throws Exception {
        FxTesting.onFx(() -> this.screen.minSizeField().setText(text));
    }

    private void whenTheExtensionIs(String text) throws Exception {
        FxTesting.onFx(() -> this.screen.extensionField().setText(text));
    }

    private void thenTheParametersShow(boolean shown) throws Exception {
        assertThat(FxTesting.onFx(() -> this.view.lookup(".search-parameters") != null))
                .isEqualTo(shown);
        assertThat(FxTesting.onFx(() -> button().isSelected())).isEqualTo(shown);
    }

    private void thenTheButtonReads(String text) throws Exception {
        assertThat(FxTesting.onFx(() -> button().getText())).isEqualTo(text);
    }

    private ToggleButton button() {
        return (ToggleButton) this.view.lookup("#search-parameters");
    }
}
