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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.njmule.gui.FxTesting;
import org.njmule.gui.TestGui;

@Timeout(120)
class SearchScreenTest {

    @TempDir
    Path dir;

    private TestGui test;
    private SearchScreen screen;

    @AfterEach
    void close() {
        if (this.test != null) {
            this.test.close();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {".mkv", "mkv", ""})
    void shouldTakeAnExtensionOfOneWordWithAtMostALeadingDot(String extension) throws Exception {
        givenTheSearchScreen();

        whenTheExtensionIsTyped(extension);

        thenTheExtensionIsValid(true);
    }

    @ParameterizedTest
    @ValueSource(strings = {"tar.gz", "..mkv", "."})
    void shouldFlagAnExtensionOfSeveralWordsOrDots(String extension) throws Exception {
        givenTheSearchScreen();

        whenTheExtensionIsTyped(extension);

        thenTheExtensionIsValid(false);
    }

    @Test
    void shouldKeepNoSpaceInTheExtension() throws Exception {
        givenTheSearchScreen();

        whenTheExtensionIsTyped("tar gz");

        thenTheExtensionShows("targz", "no space, which would make a keyword of gz");
    }

    @Test
    void shouldKeepNoAttributeSignInTheExtension() throws Exception {
        givenTheSearchScreen();

        whenTheExtensionIsTyped("@size");

        thenTheExtensionShows("size");
    }

    @Test
    void shouldFlagAMinimumSizeAboveTheMaximumUntilItIsLowered() throws Exception {
        givenTheSearchScreen();
        givenTheSizesTyped("100", "10");
        givenTheMaximumSizeIsFlagged();
        givenASearchStartedFor("ubuntu");
        givenTheUserWasToldOnceOf("100");

        whenTheMinimumSizeIsTyped("5");

        thenTheMaximumSizeIsValid();
    }

    @Test
    void shouldTellWhatTheBoxTakesOnEnterWithAWrongExpression() throws Exception {
        givenTheSearchScreen();

        whenTheExpressionIsEntered("(film");

        thenTheUserIsToldOnlyWhatTheBoxTakes();
        thenTheTooltipTellsWhatTheBoxTakes();
    }

    private void givenTheSearchScreen() throws Exception {
        this.test = TestGui.start(this.dir.resolve("njmule"));
        this.screen = new SearchScreen(this.test.gui);
        this.test.show(FxTesting.onFx(this.screen::view));
    }

    private void givenTheSizesTyped(String minimum, String maximum) throws Exception {
        FxTesting.onFx(() -> {
            this.screen.minSizeField().setText(minimum);
            this.screen.maxSizeField().setText(maximum);
        });
    }

    private void givenTheMaximumSizeIsFlagged() throws Exception {
        assertThat(FxTesting.onFx(() -> this.screen.maxSizeInput().isValid())).isFalse();
    }

    private void givenASearchStartedFor(String query) throws Exception {
        FxTesting.onFx(() -> {
            this.screen.queryField().setText(query);
            this.screen.start();
        });
    }

    private void givenTheUserWasToldOnceOf(String text) {
        assertThat(this.test.told).singleElement().asString().contains(text);
    }

    private void whenTheExtensionIsTyped(String typed) throws Exception {
        FxTesting.onFx(() -> this.screen.extensionField().setText(typed));
    }

    private void whenTheMinimumSizeIsTyped(String text) throws Exception {
        FxTesting.onFx(() -> this.screen.minSizeField().setText(text));
    }

    private void whenTheExpressionIsEntered(String expression) throws Exception {
        FxTesting.onFx(() -> {
            this.screen.queryField().setText(expression);
            this.screen.queryField().fireEvent(new ActionEvent());
        });
    }

    private void thenTheExtensionIsValid(boolean valid) throws Exception {
        assertThat(FxTesting.onFx(() -> this.screen.extensionInput().isValid())).isEqualTo(valid);
    }

    private void thenTheExtensionShows(String text) throws Exception {
        assertThat(FxTesting.onFx(() -> this.screen.extensionField().getText())).isEqualTo(text);
    }

    private void thenTheExtensionShows(String text, String why) throws Exception {
        assertThat(FxTesting.onFx(() -> this.screen.extensionField().getText()))
                .as(why)
                .isEqualTo(text);
    }

    private void thenTheMaximumSizeIsValid() throws Exception {
        assertThat(FxTesting.onFx(() -> this.screen.maxSizeInput().isValid()))
                .as("checked again when the minimum changes")
                .isTrue();
    }

    private void thenTheUserIsToldOnlyWhatTheBoxTakes() {
        assertThat(this.test.told).containsExactly(this.test.gui.t("nj.input.search"));
    }

    private void thenTheTooltipTellsWhatTheBoxTakes() throws Exception {
        assertThat(FxTesting.onFx(() -> this.screen.queryField().getTooltip().getText()))
                .as("the hint of the box")
                .isEqualTo(this.test.gui.t("nj.input.search"));
    }
}
