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
package org.njmule.gui.ui;

import static org.assertj.core.api.Assertions.assertThat;

import atlantafx.base.theme.Styles;
import java.util.List;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.njmule.gui.FxTesting;

class AnswersTest {

    private Alert alert;
    private ButtonType go;

    @BeforeAll
    static void toolkit() throws Exception {
        FxTesting.start();
    }

    @Test
    void aRiskyAnswerIsMarkedAndKeepingThingsIsTheDefault() throws Exception {
        givenAQuestion();
        whenItIsAnswered("Cancel and delete", "Keep downloading", true);
        thenTheButtonsRead("Cancel and delete", "Keep downloading");
        thenTheDefaultButtonIs("Keep downloading");
        thenTheButtonMarkedAsDangerousIs("Cancel and delete");
    }

    @Test
    void aSafeAnswerIsTheDefaultAndNothingIsMarked() throws Exception {
        givenAQuestion();
        whenItIsAnswered("Share", "Cancel", false);
        thenTheButtonsRead("Share", "Cancel");
        thenTheDefaultButtonIs("Share");
        thenNoButtonIsMarkedAsDangerous();
    }

    private void givenAQuestion() throws Exception {
        this.alert = FxTesting.onFx(() -> new Alert(Alert.AlertType.CONFIRMATION, "Sure?"));
    }

    private void whenItIsAnswered(String go, String stay, boolean risky) throws Exception {
        this.go = FxTesting.onFx(() -> Fx.answers(this.alert, go, stay, risky));
    }

    private void thenTheButtonsRead(String... texts) throws Exception {
        assertThat(FxTesting.onFx(() -> buttons().stream().map(Button::getText).toList()))
                .containsExactly(texts);
        assertThat(this.go.getText()).isEqualTo(texts[0]);
    }

    private void thenTheDefaultButtonIs(String text) throws Exception {
        assertThat(FxTesting.onFx(() -> buttons().stream()
                        .filter(Button::isDefaultButton)
                        .map(Button::getText)
                        .toList()))
                .containsExactly(text);
    }

    private void thenTheButtonMarkedAsDangerousIs(String text) throws Exception {
        assertThat(FxTesting.onFx(() -> buttons().stream()
                        .filter(b -> b.getStyleClass().contains(Styles.DANGER))
                        .map(Button::getText)
                        .toList()))
                .containsExactly(text);
    }

    private void thenNoButtonIsMarkedAsDangerous() throws Exception {
        assertThat(FxTesting.onFx(() ->
                        buttons().stream().noneMatch(b -> b.getStyleClass().contains(Styles.DANGER))))
                .isTrue();
    }

    private List<Button> buttons() {
        return this.alert.getButtonTypes().stream()
                .map(t -> (Button) this.alert.getDialogPane().lookupButton(t))
                .toList();
    }
}
