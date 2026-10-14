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

    @Test
    void exemptConnectionsLeaveTheAcceptRateToTheOthers() {
        int checker = Ipv4.parse("7.7.7.7");
        ConnectionGuard guard = new ConnectionGuard(
                IpFilter::empty,
                ip -> ip == checker,
                new Limits(100, 5, 1, Duration.ofSeconds(5)),
                Ticker.systemTicker());

        assertThat(guard.admit(checker)).isInstanceOf(Admission.Admitted.class);
        assertThat(guard.admit(checker)).isInstanceOf(Admission.Admitted.class);

        assertThat(guard.admit(Ipv4.parse("1.2.3.4")))
                .as("the one connection a window allows is still free")
                .isInstanceOf(Admission.Admitted.class);
    }
}
