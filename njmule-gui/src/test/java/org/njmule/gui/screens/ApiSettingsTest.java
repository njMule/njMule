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
import java.util.concurrent.TimeUnit;
import javafx.scene.input.Clipboard;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.core.remote.ApiToken;
import org.njmule.gui.FxTesting;
import org.njmule.gui.TestGui;
import org.njmule.webui.WebUiSettings;

/** The remote-control API in the web interface settings. */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class ApiSettingsTest {

    @TempDir
    Path dir;

    private TestGui test;
    private SettingsScreen screen;
    private String firstToken;

    @AfterEach
    void close() throws Exception {
        if (this.test != null) {
            this.test.close();
        }
    }

    @Test
    void shouldKeepTheApiChoiceWithTheWebInterface() throws Exception {
        givenTheSettingsShown();

        whenTheWebInterfaceIsTurnedOnWithTheApi();

        thenTheSavedSettingsLetTheApiAnswer();
    }

    @Test
    void shouldCopyTheTokenAndReplaceItOnRequest() throws Exception {
        givenTheSettingsShown();
        givenTheTokenCopied();

        whenANewTokenIsAsked();

        thenTheClipboardHoldsTheKeptToken();
        thenTheTokenIsNotTheFirstOne();
    }

    private void givenTheSettingsShown() throws Exception {
        this.test = TestGui.start(this.dir.resolve("njmule"));
        this.screen = new SettingsScreen(this.test.gui);
        this.test.show(FxTesting.onFx(this.screen::view));
        FxTesting.onFx(this.screen::shown);
    }

    private void givenTheTokenCopied() throws Exception {
        FxTesting.onFx(this.screen::copyApiToken);
        this.firstToken = FxTesting.onFx(() -> Clipboard.getSystemClipboard().getString());
        assertThat(ApiToken.load(config())).contains(this.firstToken);
        assertThat(this.test.told).contains("The API token is in the clipboard.");
    }

    private void whenTheWebInterfaceIsTurnedOnWithTheApi() throws Exception {
        FxTesting.onFx(() -> {
            this.screen.webUiBox().setSelected(true);
            this.screen.webUiApiBox().setSelected(true);
            this.screen.webUiPasswordField().setText("long enough");
            this.screen.webUiAgainField().setText("long enough");
            this.screen.apply();
        });
    }

    private void whenANewTokenIsAsked() throws Exception {
        FxTesting.onFx(this.screen::newApiToken);
    }

    private void thenTheSavedSettingsLetTheApiAnswer() throws Exception {
        WebUiSettings saved = WebUiSettings.load(config());
        assertThat(saved.usable()).isTrue();
        assertThat(saved.api()).isTrue();
        FxTesting.onFx(this.screen::shown);
        assertThat(FxTesting.onFx(() -> this.screen.webUiApiBox().isSelected())).isTrue();
    }

    private void thenTheClipboardHoldsTheKeptToken() throws Exception {
        String copied = FxTesting.onFx(() -> Clipboard.getSystemClipboard().getString());
        assertThat(ApiToken.load(config())).contains(copied);
        assertThat(this.test.told).anyMatch(m -> m.contains("the old one no longer works"));
    }

    private void thenTheTokenIsNotTheFirstOne() throws Exception {
        assertThat(ApiToken.load(config())).isPresent().get().isNotEqualTo(this.firstToken);
    }

    private Path config() {
        return this.test.engine.directories().config();
    }
}
