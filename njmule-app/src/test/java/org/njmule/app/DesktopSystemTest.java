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
package org.njmule.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.njmule.core.remote.DesktopIntegration;

class DesktopSystemTest {

    private final Switch links = new Switch();
    private final Switch start = new Switch();
    private DesktopSystem system;
    private DesktopIntegration.Settings settings;
    private Exception failure;

    @Test
    void shouldTellBothSettingsWhereTheSystemOffersThem() throws Exception {
        givenTheSystemOffersBoth();
        givenTheLinksAreOurs();

        whenTheSettingsAreRead();

        thenTheSettingsAre(Optional.of(true), Optional.of(false));
    }

    @Test
    void shouldTellNothingOfTheLinksWhereTheSystemHandsThemOverItself() throws Exception {
        givenTheSystemOffersOnlyTheStartAtLogin();

        whenTheSettingsAreRead();

        thenTheSettingsAre(Optional.empty(), Optional.of(false));
    }

    @Test
    void shouldTurnTheStartAtLoginOn() {
        givenTheSystemOffersBoth();

        whenChanged(Optional.empty(), Optional.of(true));

        thenTheSettingsAre(Optional.of(false), Optional.of(true));
    }

    @Test
    void shouldGiveTheLinksBack() {
        givenTheSystemOffersBoth();
        givenTheLinksAreOurs();

        whenChanged(Optional.of(false), Optional.empty());

        thenTheSettingsAre(Optional.of(false), Optional.of(false));
    }

    @Test
    void shouldChangeNothingWhenASettingIsNotOffered() {
        givenTheSystemOffersOnlyTheStartAtLogin();

        whenChanged(Optional.of(true), Optional.of(true));

        thenAWrongChangeWasRefused();
        thenTheStartAtLoginIsStill(false);
    }

    // Given

    private void givenTheSystemOffersBoth() {
        this.system = new DesktopSystem(Optional.of(this.links), Optional.of(this.start));
    }

    private void givenTheSystemOffersOnlyTheStartAtLogin() {
        this.system = new DesktopSystem(Optional.empty(), Optional.of(this.start));
    }

    private void givenTheLinksAreOurs() {
        this.links.on = true;
    }

    // When

    private void whenTheSettingsAreRead() throws Exception {
        this.settings = this.system.settings();
    }

    private void whenChanged(Optional<Boolean> links, Optional<Boolean> autostart) {
        try {
            this.settings = this.system.change(new DesktopIntegration.Change(links, autostart));
        } catch (Exception e) {
            this.failure = e;
        }
    }

    // Then

    private void thenTheSettingsAre(Optional<Boolean> links, Optional<Boolean> autostart) {
        assertThat(this.settings).isEqualTo(new DesktopIntegration.Settings(links, autostart));
    }

    private void thenAWrongChangeWasRefused() {
        assertThat(this.failure).isInstanceOf(IllegalArgumentException.class);
    }

    private void thenTheStartAtLoginIsStill(boolean on) {
        assertThat(this.start.on).isEqualTo(on);
    }

    private static final class Switch implements DesktopSystem.Toggle {
        private boolean on;

        @Override
        public boolean isOn() {
            return this.on;
        }

        @Override
        public void set(boolean value) {
            this.on = value;
        }
    }
}
