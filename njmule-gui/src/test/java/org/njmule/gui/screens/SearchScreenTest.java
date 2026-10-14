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
import javafx.event.ActionEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.gui.FxTesting;
import org.njmule.gui.TestGui;

@Timeout(120)
class SearchScreenTest {

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
    void theExtensionIsOneWordWithAtMostALeadingDot() throws Exception {
        SearchScreen screen = this.screen();

        assertThat(this.extension(screen, "tar gz"))
                .as("no space, which would make a keyword of gz")
                .isEqualTo("targz");
        assertThat(this.extensionValid(screen, ".mkv")).isTrue();
        assertThat(this.extensionValid(screen, "mkv")).isTrue();
        assertThat(this.extensionValid(screen, "")).isTrue();
        assertThat(this.extensionValid(screen, "tar.gz")).isFalse();
        assertThat(this.extensionValid(screen, "..mkv")).isFalse();
        assertThat(this.extensionValid(screen, ".")).isFalse();
        assertThat(this.extension(screen, "@size")).isEqualTo("size");
    }

    @Test
    void aMinimumSizeAboveTheMaximumIsFlagged() throws Exception {
        SearchScreen screen = this.screen();

        FxTesting.onFx(() -> {
            screen.minSizeField().setText("100");
            screen.maxSizeField().setText("10");
        });
        assertThat(FxTesting.onFx(() -> screen.maxSizeInput().isValid())).isFalse();
        FxTesting.onFx(() -> {
            screen.queryField().setText("ubuntu");
            screen.start();
        });
        assertThat(this.test.told).singleElement().asString().contains("100");

        FxTesting.onFx(() -> screen.minSizeField().setText("5"));
        assertThat(FxTesting.onFx(() -> screen.maxSizeInput().isValid()))
                .as("checked again when the minimum changes")
                .isTrue();
    }

    @Test
    void enterOnAWrongExpressionTellsWhatTheBoxTakes() throws Exception {
        SearchScreen screen = this.screen();

        FxTesting.onFx(() -> {
            screen.queryField().setText("(film");
            screen.queryField().fireEvent(new ActionEvent());
        });

        assertThat(this.test.told).containsExactly(this.test.gui.t("nj.input.search"));
        assertThat(FxTesting.onFx(() -> screen.queryField().getTooltip().getText()))
                .as("the hint of the box")
                .isEqualTo(this.test.gui.t("nj.input.search"));
    }

    private SearchScreen screen() throws Exception {
        this.test = TestGui.start(this.dir.resolve("njmule"));
        SearchScreen screen = new SearchScreen(this.test.gui);
        this.test.show(FxTesting.onFx(screen::view));
        return screen;
    }

    private String extension(SearchScreen screen, String typed) throws Exception {
        return FxTesting.onFx(() -> {
            screen.extensionField().setText(typed);
            return screen.extensionField().getText();
        });
    }

    private boolean extensionValid(SearchScreen screen, String typed) throws Exception {
        return FxTesting.onFx(() -> {
            screen.extensionField().setText(typed);
            return screen.extensionInput().isValid();
        });
    }
}
