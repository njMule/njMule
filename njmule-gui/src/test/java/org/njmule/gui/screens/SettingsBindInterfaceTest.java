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

/** The network interface every connection goes through, chosen in the options. */
@Timeout(60)
class SettingsBindInterfaceTest {

    // No computer has an interface of that name.
    private static final String GONE = "njmule-test-vpn";

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
    void shouldShowAChosenInterfaceThatIsGoneAsNotAvailable() throws Exception {
        givenNjMuleBoundTo(GONE);
        whenTheSettingsShow();
        thenTheInterfaceChosenReads(GONE + " (not available)");
    }

    @Test
    void shouldSaveTheChoiceOfAnyInterface() throws Exception {
        givenNjMuleBoundTo(GONE);
        givenTheSettingsShow();
        givenTheUserChoseAnyInterface();
        whenTheUserApplies();
        thenTheEngineBindsToAnyInterface();
    }

    private void givenNjMuleBoundTo(String choice) throws Exception {
        this.test = TestGui.start(this.dir.resolve("njmule"));
        FxTesting.await(this.test.engine.updatePreferences(
                p -> p.toBuilder().bindInterface(choice).build()));
    }

    private void givenTheSettingsShow() throws Exception {
        showTheSettings();
    }

    private void givenTheUserChoseAnyInterface() throws Exception {
        FxTesting.onFx(() -> this.screen.bindInterfaceBox().setValue(""));
    }

    // The interface is on the page of the connection.
    private void whenTheSettingsShow() throws Exception {
        showTheSettings();
        FxTesting.onFx(() -> this.screen.pagesView().show("connection"));
    }

    private void whenTheUserApplies() throws Exception {
        FxTesting.onFx(this.screen::apply);
    }

    private void thenTheInterfaceChosenReads(String text) throws Exception {
        assertThat(FxTesting.onFx(() -> this.screen.bindInterfaceBox().getValue()))
                .isEqualTo(GONE);
        // The list shows its choice once its page is laid out.
        FxTesting.assertEventually(
                "the choice shown",
                () -> text.equals(this.screen.bindInterfaceBox().getButtonCell().getText()));
    }

    private void thenTheEngineBindsToAnyInterface() throws Exception {
        FxTesting.assertEventually(
                "any interface saved",
                () -> this.test.engine.preferences().bindInterface().isEmpty());
    }

    private void showTheSettings() throws Exception {
        this.screen = new SettingsScreen(this.test.gui);
        this.test.show(FxTesting.onFx(this.screen::view));
        FxTesting.onFx(this.screen::shown);
    }
}
