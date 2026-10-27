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
package org.njmule.core.net;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.Authenticator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.njmule.core.config.BindInterface;
import org.njmule.core.config.ProxySettings;

class PublicIpv6Test {

    private boolean told;

    @AfterEach
    void goDirectAgain() {
        ProxyRoute.install(() -> ProxySettings.NONE);
        Authenticator.setDefault(null);
        TcpConnector.useBinding(BindInterface.Binding.Any::new);
    }

    @Test
    void shouldTellTheAddressOfADirectConnection() {
        whenAskedWhetherTheAddressMayBeTold();

        thenItMayBeTold(true);
    }

    @Test
    void shouldHideTheAddressWhileAProxyCarriesTheConnections() {
        givenTheProxy(new ProxySettings(ProxySettings.Type.SOCKS, "proxy.lan", 1080, "", ""));

        whenAskedWhetherTheAddressMayBeTold();

        thenItMayBeTold(false);
    }

    @Test
    void shouldHideTheAddressWhileNjmuleKeepsToAnInterfaceOfItsOwn() {
        givenTheBinding(new BindInterface.Binding.Missing("wg0"));

        whenAskedWhetherTheAddressMayBeTold();

        thenItMayBeTold(false);
    }

    private void givenTheProxy(ProxySettings settings) {
        ProxyRoute.install(() -> settings);
    }

    private void givenTheBinding(BindInterface.Binding binding) {
        TcpConnector.useBinding(() -> binding);
    }

    private void whenAskedWhetherTheAddressMayBeTold() {
        this.told = PublicIpv6.mayBeTold();
    }

    private void thenItMayBeTold(boolean expected) {
        assertThat(this.told).isEqualTo(expected);
    }
}
