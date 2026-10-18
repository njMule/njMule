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
package org.njmule.core.download;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.common.net.InetAddresses;
import java.net.Inet6Address;
import java.net.InetSocketAddress;
import java.util.Optional;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class Ipv6RouteTest {

    private static final Inet6Address THEIRS = (Inet6Address) InetAddresses.forString("2001:db8::7");
    private static final int PORT = 4662;

    private boolean weHaveIpv6;
    private Optional<Inet6Address> theirs = Optional.empty();
    private Optional<InetSocketAddress> route;

    @ParameterizedTest
    @CsvSource({
        "true, true, true, true",
        "false, true, true, false",
        "true, false, true, false",
        "true, true, false, false"
    })
    void shouldGoOverIpv6WhenBothHaveItAndIpv4DoesNotDo(
            boolean ours, boolean theyGaveIt, boolean ipv4DoesNotDo, boolean overIpv6) {
        givenWeHaveIpv6(ours);
        givenTheClientGaveItsIpv6Address(theyGaveIt);

        whenTheRouteIsChosen(ipv4DoesNotDo);

        thenItGoesOverIpv6(overIpv6);
    }

    private void givenWeHaveIpv6(boolean ours) {
        this.weHaveIpv6 = ours;
    }

    private void givenTheClientGaveItsIpv6Address(boolean given) {
        this.theirs = given ? Optional.of(THEIRS) : Optional.empty();
    }

    private void whenTheRouteIsChosen(boolean wanted) {
        this.route = DownloadManager.ipv6Route(this.weHaveIpv6, this.theirs, PORT, wanted);
    }

    private void thenItGoesOverIpv6(boolean expected) {
        if (expected) {
            assertThat(this.route).contains(new InetSocketAddress(THEIRS, PORT));
        } else {
            assertThat(this.route).isEmpty();
        }
    }
}
