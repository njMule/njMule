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

/** The IP filter lists, and how often they are fetched again, in the options. */
@Timeout(120)
class SettingsIpFilterListsTest {

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
    void shouldSaveTheListsAndHowOftenTheyAreFetched() throws Exception {
        givenTheSettingsScreen();
        givenTheListsTyped("https://one.example/ipfilter.zip\nhttps://two.example/level1.p2p.gz");
        givenTheUpdateEveryTyped("3");

        whenTheSettingsAreApplied();

        thenTheEngineFetches("https://one.example/ipfilter.zip\nhttps://two.example/level1.p2p.gz", 3);
    }

    @Test
    void shouldMarkALineThatIsNoWebAddressAndNameItInTheTooltip() throws Exception {
        givenTheSettingsScreen();

        whenTheListsAreTyped("https://one.example/ipfilter.zip\nlevel1.p2p");

        thenTheListsAreMarkedInvalid();
        thenTheTooltipNames("Not an http or https address: level1.p2p");
    }

    private void givenTheSettingsScreen() throws Exception {
        this.test = TestGui.start(this.dir.resolve("njmule"));
        this.screen = new SettingsScreen(this.test.gui);
        this.test.show(FxTesting.onFx(this.screen::view));
    }

    private void givenTheListsTyped(String text) throws Exception {
        whenTheListsAreTyped(text);
    }

    private void givenTheUpdateEveryTyped(String days) throws Exception {
        FxTesting.onFx(() -> this.screen.ipFilterDaysField().setText(days));
    }

    private void whenTheListsAreTyped(String text) throws Exception {
        FxTesting.onFx(() -> this.screen.ipFilterListsArea().setText(text));
    }

    private void whenTheSettingsAreApplied() throws Exception {
        FxTesting.onFx(this.screen::apply);
    }

    private void thenTheEngineFetches(String lists, int days) throws Exception {
        FxTesting.assertEventually(
                "the lists saved",
                () -> this.test.engine.preferences().ipFilterLists().equals(lists)
                        && this.test.engine.preferences().ipFilterUpdateDays() == days);
    }

    private void thenTheListsAreMarkedInvalid() throws Exception {
        assertThat(FxTesting.onFx(() -> this.screen.ipFilterListsInput().isValid()))
                .isFalse();
    }

    private void thenTheTooltipNames(String line) throws Exception {
        assertThat(FxTesting.onFx(
                        () -> this.screen.ipFilterListsArea().getTooltip().getText()))
                .endsWith("\n" + line);
    }
}
