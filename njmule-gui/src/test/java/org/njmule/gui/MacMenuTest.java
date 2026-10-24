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
package org.njmule.gui;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class MacMenuTest {

    private final List<String> done = new ArrayList<>();
    private final MacMenu.Actions actions = new MacMenu.Actions(
            () -> this.done.add("about"), () -> this.done.add("settings"), () -> this.done.add("quit"));
    private boolean supported = true;
    private boolean failing;
    private MacMenu.Actions taken;
    private boolean installed;

    @Test
    void shouldLeadTheMenuOfMacOsIntoTheApp() {
        whenInstalledOn("Mac OS X");

        thenItIsInstalled(true);
        thenTheMenuLeadsTo("about", "settings", "quit");
    }

    @ParameterizedTest
    @ValueSource(strings = {"Windows 11", "Linux"})
    void shouldLeaveTheMenuAloneElsewhere(String osName) {
        whenInstalledOn(osName);

        thenItIsInstalled(false);
        thenTheMenuWasNotTouched();
    }

    @Test
    void shouldLeaveAMenuTheDesktopDoesNotOffer() {
        givenTheDesktopHasNoApplicationMenu();

        whenInstalledOn("Mac OS X");

        thenItIsInstalled(false);
        thenTheMenuWasNotTouched();
    }

    @Test
    void shouldGoOnWhenTheDesktopFails() {
        givenTheDesktopFails();

        whenInstalledOn("Mac OS X");

        thenItIsInstalled(false);
    }

    private void givenTheDesktopHasNoApplicationMenu() {
        this.supported = false;
    }

    private void givenTheDesktopFails() {
        this.failing = true;
    }

    private void whenInstalledOn(String osName) {
        this.installed = MacMenu.install(
                osName,
                new MacMenu.AppMenu() {
                    @Override
                    public boolean supported() {
                        return MacMenuTest.this.supported;
                    }

                    @Override
                    public void handle(MacMenu.Actions actions) {
                        if (MacMenuTest.this.failing) {
                            throw new UnsupportedOperationException("no application menu here");
                        }
                        MacMenuTest.this.taken = actions;
                    }
                },
                this.actions);
    }

    private void thenItIsInstalled(boolean expected) {
        assertThat(this.installed).isEqualTo(expected);
    }

    private void thenTheMenuLeadsTo(String... expected) {
        this.taken.about().run();
        this.taken.settings().run();
        this.taken.quit().run();
        assertThat(this.done).containsExactly(expected);
    }

    private void thenTheMenuWasNotTouched() {
        assertThat(this.taken).isNull();
    }
}
