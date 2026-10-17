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
package org.njmule.gui.infra;

import static org.assertj.core.api.Assertions.assertThat;

import javafx.collections.FXCollections;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.njmule.gui.FxTesting;

/** Titles and the wordmark in Manrope, bundled with the app; text in the system's face. */
@Timeout(60)
class DisplayFaceTest {

    private Label title;
    private Label wordmark;
    private Label text;

    @BeforeAll
    static void startFx() throws InterruptedException {
        FxTesting.start();
    }

    @Test
    void shouldSetTitlesInManropeBold() throws Exception {
        whenAWindowIsStyled();

        thenTheFaceIs(this.title, "Manrope Bold");
    }

    @Test
    void shouldSetTheWordmarkInManropeExtraBold() throws Exception {
        whenAWindowIsStyled();

        thenTheFaceIs(this.wordmark, "Manrope ExtraBold");
    }

    @Test
    void shouldLeaveTextInTheSystemFace() throws Exception {
        whenAWindowIsStyled();

        thenTheFaceIsNotManrope(this.text);
    }

    private void whenAWindowIsStyled() throws Exception {
        FxTesting.onFx(() -> {
            this.title = new Label("Transfers");
            this.title.getStyleClass().addAll("screen-title", "display");
            this.wordmark = new Label("njMule");
            this.wordmark.getStyleClass().addAll("display", "wordmark");
            this.text = new Label("ubuntu-24.04.1-desktop-amd64.iso");
            VBox root = new VBox(this.title, this.wordmark, this.text);
            Scene scene = new Scene(root);
            new Themes(Themes.Family.NORD, GuiSettings.Mode.LIGHT, FXCollections.observableHashMap()).attach(scene);
            root.applyCss();
        });
    }

    private void thenTheFaceIs(Label label, String face) throws Exception {
        assertThat(FxTesting.onFx(() -> label.getFont().getName())).isEqualTo(face);
    }

    private void thenTheFaceIsNotManrope(Label label) throws Exception {
        assertThat(FxTesting.onFx(() -> label.getFont().getName())).doesNotStartWith("Manrope");
    }
}
