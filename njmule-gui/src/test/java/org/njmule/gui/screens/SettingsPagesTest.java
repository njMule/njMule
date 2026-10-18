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
import javafx.event.ActionEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.gui.FxTesting;
import org.njmule.gui.TestGui;

@Timeout(60)
class SettingsPagesTest {

    private static final List<String> PAGES = List.of(
            "General",
            "Connection",
            "Bandwidth",
            "Server",
            "Proxy",
            "Directories",
            "IP Filter",
            "Messages",
            "Scheduler",
            "Programs run on events",
            "Web Interface",
            "Extended");

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
    void shouldListThePagesAndOpenOnTheFirst() throws Exception {
        givenTheGui();

        whenTheOptionsShow();

        thenThePagesListedAre(PAGES);
        thenThePageShownIs("general");
    }

    @Test
    void shouldKeepOnlyThePagesAndOptionsThatHoldTheWordsTyped() throws Exception {
        givenTheOptionsShown();

        whenTheFindBoxReads("upnp");

        thenThePagesListedAre(List.of("Connection"));
        thenTheOptionsShownAre(List.of("Forward the ports with UPnP"));
    }

    @Test
    void shouldSayWhenNoOptionMatches() throws Exception {
        givenTheOptionsShown();

        whenTheFindBoxReads("nothing matches this");

        thenThePagesListedAre(List.of());
        thenThePageShownIs("");
    }

    @Test
    void shouldMarkAnOptionSetApartFromItsDefault() throws Exception {
        givenTheOptionsShown();
        givenTheNickTyped("Tester");

        whenEnterIsPressedInTheNick();

        thenTheNickIsSaved("Tester");
        thenTheResetOfReads("Nick", "Reset to njMule");
    }

    @Test
    void shouldKeepOnlyTheChangedOptionsWhenAsked() throws Exception {
        givenTheOptionsShown();
        givenTheNickSaved("Tester");

        whenOnlyTheChangedAreAskedFor();

        thenThePagesListedStartWith("General");
        thenTheOptionsShownInclude("Nick");
        thenTheOptionsShownLeaveOut("Language");
    }

    @Test
    void shouldGoBackToTheDefaultWhenResetIsPressed() throws Exception {
        givenTheOptionsShown();
        givenTheNickSaved("Tester");

        whenTheResetOfIsPressed("Nick");

        thenTheNickIsSaved("njMule");
        thenTheResetOfIsHidden("Nick");
    }

    private void givenTheGui() throws Exception {
        this.test = TestGui.start(this.dir.resolve("njmule"));
    }

    private void givenTheOptionsShown() throws Exception {
        givenTheGui();
        whenTheOptionsShow();
    }

    private void givenTheNickTyped(String nick) throws Exception {
        FxTesting.onFx(() -> this.screen.nickField().setText(nick));
    }

    private void givenTheNickSaved(String nick) throws Exception {
        givenTheNickTyped(nick);
        whenEnterIsPressedInTheNick();
        thenTheNickIsSaved(nick);
    }

    private void whenTheOptionsShow() throws Exception {
        this.screen = FxTesting.onFx(() -> new SettingsScreen(this.test.gui));
        this.test.show(FxTesting.onFx(this.screen::view));
        FxTesting.onFx(this.screen::shown);
    }

    private void whenTheFindBoxReads(String words) throws Exception {
        FxTesting.onFx(() -> this.screen.pagesView().findField().setText(words));
    }

    private void whenEnterIsPressedInTheNick() throws Exception {
        FxTesting.onFx(() -> this.screen.nickField().fireEvent(new ActionEvent()));
    }

    private void whenOnlyTheChangedAreAskedFor() throws Exception {
        FxTesting.onFx(() -> this.screen.pagesView().changedOnlyChip().fire());
    }

    private void whenTheResetOfIsPressed(String option) throws Exception {
        FxTesting.onFx(() -> this.screen.pagesView().row(option).resetLink().fire());
    }

    private void thenThePagesListedAre(List<String> titles) throws Exception {
        assertThat(FxTesting.onFx(() -> this.screen.pagesView().listed())).containsExactlyElementsOf(titles);
    }

    // The test's own profile may set other options apart too, such as its ports.
    private void thenThePagesListedStartWith(String title) throws Exception {
        assertThat(FxTesting.onFx(() -> this.screen.pagesView().listed())).startsWith(title);
    }

    private void thenThePageShownIs(String id) throws Exception {
        assertThat(FxTesting.onFx(() -> this.screen.pagesView().shownPage())).isEqualTo(id);
    }

    private void thenTheOptionsShownAre(List<String> titles) throws Exception {
        assertThat(FxTesting.onFx(() -> this.screen.pagesView().shownRows())).containsExactlyElementsOf(titles);
    }

    // The test's own look may differ from the default too.
    private void thenTheOptionsShownInclude(String title) throws Exception {
        assertThat(FxTesting.onFx(() -> this.screen.pagesView().shownRows())).contains(title);
    }

    private void thenTheOptionsShownLeaveOut(String title) throws Exception {
        assertThat(FxTesting.onFx(() -> this.screen.pagesView().shownRows())).doesNotContain(title);
    }

    private void thenTheNickIsSaved(String nick) throws Exception {
        FxTesting.assertEventually(
                "the nick saved",
                () -> nick.equals(this.test.engine.preferences().nick()));
    }

    private void thenTheResetOfReads(String option, String text) throws Exception {
        FxTesting.assertEventually(
                "the reset of " + option,
                () -> this.screen.pagesView().row(option).resetLink().isVisible()
                        && text.equals(
                                this.screen.pagesView().row(option).resetLink().getText()));
    }

    private void thenTheResetOfIsHidden(String option) throws Exception {
        FxTesting.assertEventually(
                "no reset of " + option,
                () -> !this.screen.pagesView().row(option).resetLink().isVisible());
    }
}
