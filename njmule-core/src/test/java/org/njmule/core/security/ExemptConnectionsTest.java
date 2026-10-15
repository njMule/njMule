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
package org.njmule.core.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.common.base.Ticker;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.njmule.core.security.ConnectionGuard.Admission;
import org.njmule.core.security.ConnectionGuard.Limits;
import org.njmule.core.util.Ipv4;

class ExemptConnectionsTest {

    private static final int CHECKER = Ipv4.parse("7.7.7.7");

    private ConnectionGuard guard;
    private Admission admission;

    @Test
    void shouldLetExemptConnectionsLeaveTheAcceptRateToTheOthers() {
        givenAGuardExempting(CHECKER, new Limits(100, 5, 1, Duration.ofSeconds(5)));
        givenAConnectionFrom(CHECKER);
        givenAConnectionFrom(CHECKER);

        whenAConnectionComesFrom(Ipv4.parse("1.2.3.4"));

        thenItIsAdmitted("the one connection a window allows is still free");
    }

    private void givenAGuardExempting(int ip, Limits limits) {
        this.guard = new ConnectionGuard(IpFilter::empty, address -> address == ip, limits, Ticker.systemTicker());
    }

    private void givenAConnectionFrom(int ip) {
        assertThat(this.guard.admit(ip)).isInstanceOf(Admission.Admitted.class);
    }

    private void whenAConnectionComesFrom(int ip) {
        this.admission = this.guard.admit(ip);
    }

    private void thenItIsAdmitted(String why) {
        assertThat(this.admission).as(why).isInstanceOf(Admission.Admitted.class);
    }
}
