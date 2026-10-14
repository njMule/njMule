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

import java.net.InetAddress;
import java.security.SecureRandom;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** Login attempts are counted per client: one IPv4 address, or one IPv6 network of a /64 whose host part it picks. */
class LoginThrottleTest {

    private final AtomicLong now = new AtomicLong();
    private final Sessions sessions = new Sessions(new SecureRandom(), this.now::get);
    private boolean allowed;

    @Test
    void shouldCountTheAddressesOfOneIpv6NetworkTogether() {
        this.givenFailedAttemptsFromAddressesOf("2001:db8:1:2::", Sessions.MAX_FAILURES);

        this.whenAnAttemptComesFrom("2001:db8:1:2:abcd::99");

        this.thenTheAttemptIsAllowed(false);

        this.whenAnAttemptComesFrom("2001:db8:1:3::1");

        this.thenTheAttemptIsAllowed(true);
    }

    @Test
    void shouldCountEachIpv4AddressOnItsOwn() {
        this.givenFailedAttemptsFromAddressesOf("192.0.2.", Sessions.MAX_FAILURES);

        this.whenAnAttemptComesFrom("192.0.2.200");

        this.thenTheAttemptIsAllowed(true);
    }

    private void givenFailedAttemptsFromAddressesOf(String prefix, int count) {
        for (int i = 1; i <= count; i++) {
            assertThat(this.sessions.mayTry(Sessions.clientKey(InetAddress.ofLiteral(prefix + i))))
                    .isTrue();
        }
    }

    private void whenAnAttemptComesFrom(String address) {
        this.allowed = this.sessions.mayTry(Sessions.clientKey(InetAddress.ofLiteral(address)));
    }

    private void thenTheAttemptIsAllowed(boolean allowed) {
        assertThat(this.allowed).isEqualTo(allowed);
    }
}
