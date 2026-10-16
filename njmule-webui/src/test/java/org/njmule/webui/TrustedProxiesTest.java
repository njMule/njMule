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
package org.njmule.webui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.google.common.net.InetAddresses;
import java.net.InetAddress;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Which client a request comes from, as the trusted proxies tell. */
class TrustedProxiesTest {

    private TrustedProxies proxies = TrustedProxies.NONE;
    private InetAddress client;
    private Throwable exception;

    @Test
    void shouldTakeTheClientTheTrustedProxyNames() {
        givenTheTrustedProxies("172.17.0.0/16", "::1");

        whenARequestComesFrom("172.17.0.1", "198.51.100.7");

        thenTheClientIs("198.51.100.7");
    }

    @Test
    void shouldReadTheHeaderFromTheRightPastTheTrustedProxies() {
        givenTheTrustedProxies("10.0.0.1-10.0.0.9");

        whenARequestComesFrom("10.0.0.2", "203.0.113.66, 198.51.100.7, 10.0.0.5");

        thenTheClientIs("198.51.100.7");
    }

    @Test
    void shouldBelieveNoHeaderFromAnAddressNotTrusted() {
        givenTheTrustedProxies("172.17.0.1");

        whenARequestComesFrom("192.0.2.10", "198.51.100.7");

        thenTheClientIs("192.0.2.10");
    }

    @Test
    void shouldBelieveNoHeaderWithoutTrustedProxies() {
        whenARequestComesFrom("127.0.0.1", "198.51.100.7");

        thenTheClientIs("127.0.0.1");
    }

    @Test
    void shouldStopAtWhatIsNoAddress() {
        givenTheTrustedProxies("172.17.0.1", "172.17.0.2");

        whenARequestComesFrom("172.17.0.1", "198.51.100.7, evil.example, 172.17.0.2");

        thenTheClientIs("172.17.0.2");
    }

    @ParameterizedTest
    @ValueSource(strings = {"localhost", "proxy.example", "10.0.0.0/", "10.0.0.0/99", "10.0.0.1-", "", "1.2.3.4 5"})
    void shouldRefuseWhatIsNoAddressBlockOrRange(String entry) {
        whenTheTrustedProxiesAre(entry);

        thenTheyAreRefused();
    }

    private void givenTheTrustedProxies(String... entries) {
        this.proxies = new TrustedProxies(List.of(entries));
    }

    private void whenARequestComesFrom(String remote, String forwardedFor) {
        this.client = this.proxies.client(InetAddresses.forString(remote), List.of(forwardedFor));
    }

    private void whenTheTrustedProxiesAre(String entry) {
        this.exception = catchThrowable(() -> new TrustedProxies(List.of(entry)));
    }

    private void thenTheClientIs(String address) {
        assertThat(this.client).isEqualTo(InetAddresses.forString(address));
    }

    private void thenTheyAreRefused() {
        assertThat(this.exception).isInstanceOf(IllegalArgumentException.class);
    }
}
