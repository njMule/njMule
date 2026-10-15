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
import javafx.scene.control.Button;
import javafx.scene.control.TextField;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.gui.FxTesting;
import org.njmule.gui.TestGui;

@Timeout(60)
class SettingsApplyTest {

    @TempDir
    Path dir;

    private TestGui test;
    private SettingsScreen screen;
    private int portBefore;

    @AfterEach
    void close() {
        if (this.test != null) {
            this.test.close();
        }
    }

    @Test
    void applyWithAWrongValueShowsTheFieldAndSaysWhatItTakes() throws Exception {
        givenTheOptionsScrolledToTheBottom();
        givenAPortOutOfRange();
        whenApplyIsPressed();
        thenTheUserIsToldWhichFieldAndWhatItTakes("Not saved: TCP: ");
        thenTheFieldHasTheCursorAndIsInSight(this.screen.tcpPortField());
        thenNothingWasSaved();
    }

    @Test
    void applyIsNeverGreyedOutByAWrongValue() throws Exception {
        givenTheOptionsScrolledToTheBottom();
        givenAPortOutOfRange();
        thenApplyCanBePressed();
    }

    private void givenTheOptionsScrolledToTheBottom() throws Exception {
        this.test = TestGui.start(this.dir.resolve("njmule"));
        this.screen = new SettingsScreen(this.test.gui);
        this.test.show(FxTesting.onFx(this.screen::view));
        FxTesting.onFx(this.screen::shown);
        this.portBefore = this.test.engine.preferences().tcpPort();
        FxTesting.onFx(() -> this.screen.scrollPane().setVvalue(1));
    }

    private void givenAPortOutOfRange() throws Exception {
        FxTesting.onFx(() -> this.screen.tcpPortField().setText("99999"));
    }

    private void whenApplyIsPressed() throws Exception {
        FxTesting.onFx(this.screen::apply);
    }

    private void thenTheUserIsToldWhichFieldAndWhatItTakes(String start) {
        assertThat(this.test.told).anyMatch(m -> m.startsWith(start) && m.contains("65535"));
    }

    private void thenTheFieldHasTheCursorAndIsInSight(TextField field) throws Exception {
        FxTesting.await(
                "the cursor in the field", () -> field.equals(field.getScene().getFocusOwner()));
        assertThat(FxTesting.onFx(() -> this.screen.scrollPane().getVvalue())).isLessThan(0.5);
    }

    private void thenNothingWasSaved() {
        assertThat(this.test.engine.preferences().tcpPort()).isEqualTo(this.portBefore);
        assertThat(this.test.told).doesNotContain("Settings saved.");
    }

    private void thenApplyCanBePressed() throws Exception {
        Button apply = FxTesting.onFx(() -> (Button) this.screen.view().lookup(".button.accent"));
        assertThat(FxTesting.onFx(apply::isDisabled)).isFalse();
    }
}
