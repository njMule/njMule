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
package org.njmule.core.util;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.common.net.InetAddresses;
import java.net.InetAddress;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class Ipv6Test {

    private InetAddress address;
    private InetAddress network;
    private boolean reachable;

    @ParameterizedTest
    @CsvSource({
        "2001:db8:1:2:ffff:eeee:dddd:cccc, 2001:db8:1:2::",
        "2001:db8:1:2::, 2001:db8:1:2::",
        "fe80::1, fe80::",
        "93.184.216.34, 93.184.216.34"
    })
    void shouldTakeTheSlash64OfAnIpv6AddressAndAnIpv4AddressAsItIs(String given, String expected) {
        givenTheAddress(given);

        whenItsNetworkIsTaken();

        thenTheNetworkIs(expected);
    }

    @ParameterizedTest
    @CsvSource({
        "2a01:4f8:1:2::3, true",
        "2001:db8::1, true",
        "3fff:ffff::1, true",
        "fe80::1, false",
        "fd12:3456::1, false",
        "::1, false",
        "ff02::1, false",
        "2002:102:304::1, false",
        "2001:0:4136:e378::1, false",
        "93.184.216.34, false"
    })
    void shouldTellWhetherPeersOnTheInternetMayReachAnAddress(String given, boolean expected) {
        givenTheAddress(given);

        whenItIsCheckedForTheInternet();

        thenItIsReachable(expected);
    }

    private void givenTheAddress(String text) {
        this.address = InetAddresses.forString(text);
    }

    private void whenItsNetworkIsTaken() {
        this.network = Ipv6.network(this.address);
    }

    private void whenItIsCheckedForTheInternet() {
        this.reachable = Ipv6.isPublic(this.address);
    }

    private void thenItIsReachable(boolean expected) {
        assertThat(this.reachable).isEqualTo(expected);
    }

    private void thenTheNetworkIs(String expected) {
        assertThat(this.network).isEqualTo(InetAddresses.forString(expected));
    }
}
