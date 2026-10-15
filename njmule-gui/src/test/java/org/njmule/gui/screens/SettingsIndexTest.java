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
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.gui.FxTesting;
import org.njmule.gui.TestGui;

@Timeout(60)
class SettingsIndexTest {

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
    void shouldListTheSectionsInTheOrderOfThePage() throws Exception {
        givenTheOptionsScreen();

        whenTheOptionsAreShown();

        thenTheIndexStartsWith("General", "Notifications", "Connection");
        thenTheIndexHoldsOnlySections();
    }

    @Test
    void shouldLeadAFoundOptionToItsField() throws Exception {
        givenTheOptions();
        givenFound("client port");
        givenTheIndexHolds("Client Port (TCP)");

        whenTheFirstFoundIsChosen();

        thenTheCursorIsIn(this.screen.tcpPortField());
    }

    @Test
    void shouldChooseTheLastSectionWhenScrolledToTheEnd() throws Exception {
        givenTheOptions();

        whenThePageIsScrolledTo(1.0);

        thenTheChosenSectionIsTheLast();
    }

    private void givenTheOptions() throws Exception {
        givenTheOptionsScreen();
        whenTheOptionsAreShown();
    }

    private void givenTheOptionsScreen() throws Exception {
        this.test = TestGui.start(this.dir.resolve("njmule"));
        this.screen = new SettingsScreen(this.test.gui);
        this.test.show(FxTesting.onFx(this.screen::view));
    }

    private void givenFound(String text) throws Exception {
        FxTesting.onFx(() -> this.screen.index().findField().setText(text));
    }

    private void givenTheIndexHolds(String text) throws Exception {
        thenTheIndexHolds(text);
    }

    private void whenTheOptionsAreShown() throws Exception {
        FxTesting.onFx(this.screen::shown);
        FxTesting.onFx(() -> {
            this.screen.view().applyCss();
            ((javafx.scene.Parent) this.screen.view()).layout();
        });
    }

    private void whenTheFirstFoundIsChosen() throws Exception {
        FxTesting.onFx(() -> this.screen.index().go(this.screen.index().shown().getFirst(), true));
    }

    private void whenThePageIsScrolledTo(double where) throws Exception {
        FxTesting.onFx(() -> this.screen.scrollPane().setVvalue(where));
    }

    private void thenTheIndexStartsWith(String... sections) throws Exception {
        List<String> shown = FxTesting.onFx(() -> this.screen.index().shown().stream()
                .map(SettingsIndex.Entry::text)
                .toList());
        assertThat(shown).startsWith(sections);
    }

    private void thenTheIndexHoldsOnlySections() throws Exception {
        assertThat(FxTesting.onFx(() -> this.screen.index().shown())).allMatch(SettingsIndex.Entry::isSection);
    }

    private void thenTheIndexHolds(String text) throws Exception {
        assertThat(FxTesting.onFx(() -> this.screen.index().shown()))
                .anyMatch(e -> !e.isSection() && e.text().startsWith(text));
    }

    private void thenTheCursorIsIn(javafx.scene.Node field) throws Exception {
        FxTesting.await(
                "the cursor in the field", () -> field.equals(field.getScene().getFocusOwner()));
    }

    private void thenTheChosenSectionIsTheLast() throws Exception {
        FxTesting.await("the last section chosen", () -> {
            List<SettingsIndex.Entry> sections = this.screen.index().shown();
            var chosen = ((javafx.scene.control.ListView<?>)
                            this.screen.index().view().getChildren().getLast())
                    .getSelectionModel()
                    .getSelectedItem();
            return chosen != null && chosen.equals(sections.getLast());
        });
    }
}
