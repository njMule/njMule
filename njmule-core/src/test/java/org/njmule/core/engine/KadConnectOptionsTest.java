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
package org.njmule.core.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.njmule.core.api.Hash16;
import org.njmule.core.config.ObfuscationMode;
import org.njmule.core.config.Preferences;
import org.njmule.core.config.ProxySettings;
import org.njmule.core.kad.KadNode;
import org.njmule.core.protocol.ed2k.NatTraversalMessages;

class KadConnectOptionsTest {

    private static final int TRAVERSAL = NatTraversalMessages.OPTION_UTP | NatTraversalMessages.OPTION_QUIC;

    private final AtomicReference<Preferences> preferences = new AtomicReference<>(Preferences.defaults());
    private KadNode.Settings settings;
    private int told;

    @Test
    void shouldTellThatWeAreReachedThroughTheRouters() {
        whenKadTellsHowToReachUs();

        thenTraversalIsTold(true);
    }

    @Test
    void shouldNotTellTraversalWhereObfuscationIsRequired() {
        givenTheObfuscation(ObfuscationMode.REQUIRED);

        whenKadTellsHowToReachUs();

        thenTraversalIsTold(false);
    }

    @Test
    void shouldNotTellTraversalBehindAProxy() {
        givenTheProxy(new ProxySettings(ProxySettings.Type.SOCKS, "proxy.lan", 1080, "", ""));

        whenKadTellsHowToReachUs();

        thenTraversalIsTold(false);
    }

    // Kad started before the change still tells the options of now.
    @Test
    void shouldTellTheOptionsOfThePreferencesAsTheyAreNow() {
        givenKadStarted();
        givenTheObfuscation(ObfuscationMode.REQUIRED);

        whenKadTellsHowToReachUs();

        thenTraversalIsTold(false);
    }

    private void givenKadStarted() {
        this.settings = kadSettings();
    }

    private void givenTheObfuscation(ObfuscationMode mode) {
        this.preferences.updateAndGet(p -> p.toBuilder().obfuscation(mode).build());
    }

    private void givenTheProxy(ProxySettings proxy) {
        this.preferences.updateAndGet(p -> p.toBuilder().proxy(proxy).build());
    }

    private void whenKadTellsHowToReachUs() {
        this.told = (this.settings != null ? this.settings : kadSettings()).connectOptions();
    }

    private void thenTraversalIsTold(boolean expected) {
        assertThat((this.told & TRAVERSAL) == TRAVERSAL).isEqualTo(expected);
        assertThat(this.told & TRAVERSAL).isIn(0, TRAVERSAL);
    }

    private KadNode.Settings kadSettings() {
        return new KadNode.Settings(
                4662,
                Hash16.parseHex("0123456789abcdef0123456789abcdef"),
                () -> Network.kadOptions(this.preferences.get()),
                ip -> false,
                ip -> false,
                4672,
                Optional::empty);
    }
}
