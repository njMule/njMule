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

import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.njmule.gui.FxTesting;

@Timeout(30)
class SegmentsTest {

    private Segments<String> segments;

    @BeforeAll
    static void startFx() throws InterruptedException {
        FxTesting.start();
    }

    @Test
    void theFirstChoiceIsChosenAtFirst() throws Exception {
        givenTheChoices("auto", "server", "kad");
        thenTheChoiceIs("auto");
        thenTheChosenButtonsAre("auto");
    }

    @Test
    void aClickChoosesItsChoiceAndLeavesTheOthers() throws Exception {
        givenTheChoices("auto", "server", "kad");
        whenClicked("kad");
        thenTheChoiceIs("kad");
        thenTheChosenButtonsAre("kad");
    }

    @Test
    void aClickOnTheChosenOneKeepsIt() throws Exception {
        givenTheChoices("auto", "server", "kad");
        whenClicked("server");
        whenClicked("server");
        thenTheChoiceIs("server");
        thenTheChosenButtonsAre("server");
    }

    @Test
    void aChoiceSetFromCodeShowsOnItsButton() throws Exception {
        givenTheChoices("auto", "server", "kad");
        whenTheChoiceIsSetTo("server");
        thenTheChosenButtonsAre("server");
    }

    private void givenTheChoices(String... choices) throws Exception {
        this.segments = FxTesting.onFx(() -> new Segments<>(List.of(choices), c -> c.toUpperCase(Locale.ROOT)));
    }

    private void whenClicked(String choice) throws Exception {
        FxTesting.onFx(() -> this.segments.button(choice).fire());
    }

    private void whenTheChoiceIsSetTo(String choice) throws Exception {
        FxTesting.onFx(() -> this.segments.setValue(choice));
    }

    private void thenTheChoiceIs(String choice) throws Exception {
        assertThat(FxTesting.onFx(this.segments::getValue)).isEqualTo(choice);
    }

    private void thenTheChosenButtonsAre(String... choices) throws Exception {
        assertThat(FxTesting.onFx(() -> List.of("auto", "server", "kad").stream()
                        .filter(c -> this.segments.button(c).isSelected())
                        .toList()))
                .containsExactly(choices);
    }
}
