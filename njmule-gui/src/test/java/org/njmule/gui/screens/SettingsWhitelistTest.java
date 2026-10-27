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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.gui.FxTesting;
import org.njmule.gui.TestGui;

@Timeout(120)
class SettingsWhitelistTest {

    @TempDir
    Path dir;

    private TestGui test;
    private SettingsScreen screen;

    @AfterEach
    void close() {
        if (this.test != null) {
            this.test.close();
        }
    }

    @Test
    void shouldSaveTheAddressesTypedInTheWhitelist() throws Exception {
        givenTheSettingsScreen();
        givenTheWhitelistTyped("1.2.3.4\n10.0.0.0/8\n5.6.7.0 - 5.6.7.255");

        whenTheSettingsAreApplied();

        thenTheEngineAlwaysAllows("1.2.3.4\n10.0.0.0/8\n5.6.7.0 - 5.6.7.255");
    }

    @Test
    void shouldShowTheWhitelistOfTheEngine() throws Exception {
        givenTheEngineAlwaysAllows("9.9.9.9");
        givenTheSettingsScreen();

        whenTheSettingsAreShown();

        thenTheWhitelistShows("9.9.9.9");
    }

    @Test
    void shouldMarkAWrongLineAndNameItInTheTooltip() throws Exception {
        givenTheSettingsScreen();

        whenTheWhitelistIsTyped("1.2.3.4\nmy friend\n5.6.7.8");

        thenTheWhitelistIsMarkedInvalid();
        thenTheTooltipNames("Not an address, a block or a range: my friend");
    }

    @Test
    void shouldDropControlCharactersButKeepTheLines() throws Exception {
        givenTheSettingsScreen();

        whenTheWhitelistIsTyped("1.2.3.4\u0007\n5.6.7.8\r\n");

        thenTheWhitelistShows("1.2.3.4\n5.6.7.8\n");
        thenTheTooltipNamesNoLine();
    }

    private void givenTheSettingsScreen() throws Exception {
        if (this.test == null) {
            this.test = TestGui.start(this.dir.resolve("njmule"));
        }
        this.screen = new SettingsScreen(this.test.gui);
        this.test.show(FxTesting.onFx(this.screen::view));
    }

    private void givenTheEngineAlwaysAllows(String whitelist) throws Exception {
        this.test = TestGui.start(this.dir.resolve("njmule"));
        FxTesting.await(this.test.engine.updatePreferences(
                p -> p.toBuilder().ipFilterWhitelist(whitelist).build()));
        FxTesting.await(
                "the new preferences",
                () -> this.test
                        .model
                        .preferencesProperty()
                        .get()
                        .ipFilterWhitelist()
                        .equals(whitelist));
    }

    private void givenTheWhitelistTyped(String text) throws Exception {
        whenTheWhitelistIsTyped(text);
    }

    private void whenTheWhitelistIsTyped(String text) throws Exception {
        FxTesting.onFx(() -> this.screen.whitelistArea().setText(text));
    }

    private void whenTheSettingsAreApplied() throws Exception {
        FxTesting.onFx(this.screen::apply);
    }

    private void whenTheSettingsAreShown() throws Exception {
        FxTesting.onFx(this.screen::shown);
    }

    private void thenTheEngineAlwaysAllows(String expected) throws Exception {
        FxTesting.assertEventually(
                "the whitelist saved",
                () -> this.test.engine.preferences().ipFilterWhitelist().equals(expected));
    }

    private void thenTheWhitelistShows(String expected) throws Exception {
        assertThat(FxTesting.onFx(() -> this.screen.whitelistArea().getText())).isEqualTo(expected);
    }

    private void thenTheWhitelistIsMarkedInvalid() throws Exception {
        assertThat(FxTesting.onFx(() -> this.screen.whitelistInput().isValid())).isFalse();
    }

    private void thenTheTooltipNames(String line) throws Exception {
        assertThat(FxTesting.onFx(() -> this.screen.whitelistArea().getTooltip().getText()))
                .endsWith("\n" + line);
    }

    private void thenTheTooltipNamesNoLine() throws Exception {
        assertThat(FxTesting.onFx(() -> this.screen.whitelistArea().getTooltip().getText()))
                .isEqualTo(this.test.gui.t("nj.settings.whitelistHint"));
    }
}
