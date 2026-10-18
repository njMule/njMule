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
import java.util.Locale;
import java.util.Optional;
import javafx.event.ActionEvent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
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
    void shouldApplyASwitchAtOnce() throws Exception {
        givenTheOptionsShown();

        whenTheSpamFilterOfSearchResultsIsSwitchedOff();

        thenTheSpamFilterOfSearchResultsIsOff();
        thenNothingWaits();
    }

    @Test
    void shouldApplyATypedValueWhenEnterIsPressed() throws Exception {
        givenTheOptionsShown();
        givenTheCleanUpOfNewNamesSwitchedOn();
        givenTyped(this.screen.nameCleanupsField(), "dvdrip|sponsored");

        whenEnterIsPressedIn(this.screen.nameCleanupsField());

        thenTheNamesOfNewDownloadsAreCleanedUpOf("dvdrip|sponsored");
    }

    @Test
    void shouldHoldANewPortForApplyAndReconnect() throws Exception {
        givenTheOptionsShown();
        givenTyped(this.screen.tcpPortField(), "4777");

        whenEnterIsPressedIn(this.screen.tcpPortField());

        thenTheBarOffers("Apply and reconnect");
        thenThePortIsStill(this.portBefore);
    }

    @Test
    void shouldHoldAnotherLanguageForApplyAndRestart() throws Exception {
        givenTheOptionsShown();

        whenTheLanguageIsChosen(Locale.ITALIAN);

        thenTheBarOffers("Apply and restart");
        thenNoRelaunchHappened();
    }

    @Test
    void shouldSaveAHeldPortWhenApplied() throws Exception {
        givenTheOptionsShown();
        givenAPortHeld("4777");

        whenApplyAndReconnectIsPressed();

        thenThePortBecomes(4777);
        thenNothingWaits();
    }

    @Test
    void shouldShowTheSavedPortAgainWhenDiscarded() throws Exception {
        givenTheOptionsShown();
        givenAPortHeld("4777");

        whenDiscardIsPressed();

        thenTheFieldShows(this.screen.tcpPortField(), String.valueOf(this.portBefore));
        thenNothingWaits();
    }

    @Test
    void shouldNameWhatAWrongValueTakesUnderItsOption() throws Exception {
        givenTheOptionsShown();
        givenTyped(this.screen.tcpPortField(), "99999");

        whenEnterIsPressedIn(this.screen.tcpPortField());

        thenTheErrorUnderReads("Client Port (TCP)", "A whole number from 1 to 65535");
        thenThePortIsStill(this.portBefore);
        thenNothingWaits();
    }

    @Test
    void shouldKeepApplyOffAndNameTheOptionToFixFirst() throws Exception {
        givenTheOptionsShown();
        givenAPortHeld("4777");
        givenTyped(this.screen.nickField(), "");

        whenEnterIsPressedIn(this.screen.nickField());

        thenTheBarSays("Fix “Nick” first.");
        thenApplyIsOff(true);
    }

    private void givenTheOptionsShown() throws Exception {
        this.test = TestGui.start(this.dir.resolve("njmule"));
        this.screen = FxTesting.onFx(() -> new SettingsScreen(this.test.gui));
        this.test.show(FxTesting.onFx(this.screen::view));
        FxTesting.onFx(this.screen::shown);
        this.portBefore = this.test.engine.preferences().tcpPort();
    }

    private void givenTheCleanUpOfNewNamesSwitchedOn() throws Exception {
        FxTesting.onFx(() -> this.screen.cleanUpNewNamesBox().setSelected(true));
        FxTesting.assertEventually(
                "the clean-up saved", () -> this.test.engine.preferences().cleanUpNewNames());
    }

    private void givenTyped(TextField field, String text) throws Exception {
        FxTesting.onFx(() -> field.setText(text));
    }

    private void givenAPortHeld(String port) throws Exception {
        givenTyped(this.screen.tcpPortField(), port);
        whenEnterIsPressedIn(this.screen.tcpPortField());
        thenTheBarOffers("Apply and reconnect");
    }

    private void whenTheSpamFilterOfSearchResultsIsSwitchedOff() throws Exception {
        FxTesting.onFx(() -> this.screen.searchSpamFilterBox().setSelected(false));
    }

    private void whenTheLanguageIsChosen(Locale language) throws Exception {
        FxTesting.onFx(() -> this.screen.languageBox().setValue(Optional.of(language)));
    }

    private void whenEnterIsPressedIn(TextField field) throws Exception {
        FxTesting.onFx(() -> field.fireEvent(new ActionEvent()));
    }

    private void whenApplyAndReconnectIsPressed() throws Exception {
        FxTesting.onFx(() -> ((Button) this.screen.pendingBar().lookup("#settings-apply")).fire());
    }

    private void whenDiscardIsPressed() throws Exception {
        FxTesting.onFx(() -> ((Button) this.screen.pendingBar().lookup("#settings-discard")).fire());
    }

    private void thenTheSpamFilterOfSearchResultsIsOff() throws Exception {
        FxTesting.assertEventually(
                "the preferences saved", () -> !this.test.engine.preferences().searchSpamFilter());
    }

    private void thenTheNamesOfNewDownloadsAreCleanedUpOf(String words) throws Exception {
        FxTesting.assertEventually(
                "the preferences saved",
                () -> words.equals(this.test.engine.preferences().nameCleanups()));
    }

    private void thenTheBarOffers(String button) throws Exception {
        assertThat(FxTesting.onFx(() -> this.screen.pendingBar().isVisible())).isTrue();
        assertThat(FxTesting.onFx(() -> ((Button) this.screen.pendingBar().lookup("#settings-apply")).getText()))
                .isEqualTo(button);
    }

    private void thenTheBarSays(String words) throws Exception {
        assertThat(FxTesting.onFx(
                        () -> ((Label) this.screen.pendingBar().getChildren().getFirst()).getText()))
                .contains(words);
    }

    private void thenApplyIsOff(boolean off) throws Exception {
        assertThat(FxTesting.onFx(
                        () -> this.screen.pendingBar().lookup("#settings-apply").isDisabled()))
                .isEqualTo(off);
    }

    private void thenNoRelaunchHappened() {
        assertThat(this.test.relaunches()).isZero();
    }

    private void thenNothingWaits() throws Exception {
        assertThat(FxTesting.onFx(() -> this.screen.pendingBar().isVisible())).isFalse();
    }

    private void thenThePortIsStill(int port) throws Exception {
        assertThat(FxTesting.onFx(() -> this.test.engine.preferences().tcpPort()))
                .isEqualTo(port);
    }

    private void thenThePortBecomes(int port) throws Exception {
        FxTesting.assertEventually(
                "the port saved", () -> this.test.engine.preferences().tcpPort() == port);
    }

    private void thenTheFieldShows(TextField field, String text) throws Exception {
        assertThat(FxTesting.onFx(() -> field.getText())).isEqualTo(text);
    }

    private void thenTheErrorUnderReads(String option, String words) throws Exception {
        Label error = FxTesting.onFx(() -> this.screen.pagesView().row(option).errorLabel());
        assertThat(FxTesting.onFx(error::isVisible)).isTrue();
        assertThat(FxTesting.onFx(error::getText)).isEqualTo(words);
    }
}
