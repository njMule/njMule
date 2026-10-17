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

import java.io.File;
import java.nio.file.Path;
import javafx.scene.Node;
import javafx.scene.layout.HBox;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.gui.FxTesting;
import org.njmule.gui.TestGui;

/** The video player of the previews: its program typed by hand or chosen with Browse. */
@Timeout(120)
class SettingsVideoPlayerTest {

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
    void shouldOfferBrowseRightOfThePlayersField() throws Exception {
        whenTheSettingsScreenShows();

        thenBrowseFollowsThePlayersField();
    }

    @Test
    void shouldSaveAPlayerChosenWithBrowse() throws Exception {
        givenTheSettingsScreen();
        givenThePlayerChosen(this.dir.resolve("vlc.exe").toFile());

        whenTheSettingsAreApplied();

        thenThePlayerIs(this.dir.resolve("vlc.exe").toFile().getAbsolutePath());
    }

    @Test
    void shouldSaveAPlayerTypedByHand() throws Exception {
        givenTheSettingsScreen();
        givenThePlayerTyped("/usr/bin/mpv");

        whenTheSettingsAreApplied();

        thenThePlayerIs("/usr/bin/mpv");
    }

    private void givenTheSettingsScreen() throws Exception {
        whenTheSettingsScreenShows();
    }

    private void givenThePlayerChosen(File player) throws Exception {
        FxTesting.onFx(() -> this.screen.videoPlayerChosen(player));
    }

    private void givenThePlayerTyped(String player) throws Exception {
        FxTesting.onFx(() -> this.screen.videoPlayerField().setText(player));
    }

    private void whenTheSettingsScreenShows() throws Exception {
        this.test = TestGui.start(this.dir.resolve("njmule"));
        this.screen = new SettingsScreen(this.test.gui);
        this.test.show(FxTesting.onFx(this.screen::view));
    }

    private void whenTheSettingsAreApplied() throws Exception {
        FxTesting.onFx(this.screen::apply);
        FxTesting.await("saved", () -> this.test.told.contains("Settings saved."));
    }

    private void thenBrowseFollowsThePlayersField() throws Exception {
        assertThat(FxTesting.onFx(() -> {
                    Node field = this.screen.videoPlayerField();
                    HBox row = (HBox) field.getParent();
                    return row.getChildren().indexOf(this.screen.videoPlayerBrowseButton())
                            - row.getChildren().indexOf(field);
                }))
                .isEqualTo(1);
    }

    private void thenThePlayerIs(String player) {
        assertThat(this.test.engine.preferences().videoPlayer()).isEqualTo(player);
    }
}
