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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.njmule.core.persist.format.IpFilterDat;
import org.njmule.core.security.ConnectionGuard.Admission;
import org.njmule.core.security.ConnectionGuard.Limits;
import org.njmule.core.security.ConnectionGuard.Refusal;
import org.njmule.core.util.Ipv4;

class ConnectionGuardTest {

    private static final int PEER = Ipv4.parse("1.2.3.4");
    private static final int OTHER = Ipv4.parse("5.6.7.8");

    private final FakeTicker ticker = new FakeTicker();

    @Test
    void filteredAddressesAreRefusedFirst() {
        IpFilter filter = IpFilter.of(List.of(new IpFilterDat.Range(PEER, PEER, 0, "test")), IpFilter.DEFAULT_LEVEL);
        ConnectionGuard guard = new ConnectionGuard(() -> filter, Limits.DEFAULT, this.ticker);

        assertThat(guard.admit(PEER)).isEqualTo(new Admission.Refused(Refusal.FILTERED));
        assertThat(guard.admit(OTHER)).isInstanceOf(Admission.Admitted.class);
    }

    @Test
    void capsConnectionsPerAddressAndInTotalUntilPermitsAreReleased() {
        ConnectionGuard guard =
                new ConnectionGuard(IpFilter::empty, new Limits(3, 2, 100, Duration.ofSeconds(5)), this.ticker);

        ConnectionGuard.Permit first = permit(guard.admit(PEER));
        permit(guard.admit(PEER));
        assertThat(guard.admit(PEER)).isEqualTo(new Admission.Refused(Refusal.TOO_MANY_FROM_ADDRESS));
        permit(guard.admit(OTHER));
        assertThat(guard.admit(Ipv4.parse("9.9.9.9"))).isEqualTo(new Admission.Refused(Refusal.TOO_MANY_CONNECTIONS));

        first.close();
        first.close();
        assertThat(guard.openConnections()).isEqualTo(2);
        assertThat(guard.admit(PEER)).isInstanceOf(Admission.Admitted.class);
    }

    @Test
    void theServerTestingUsPassesTheRateAndTotalLimitsButNotTheFilter() {
        int server = Ipv4.parse("7.7.7.7");
        IpFilter filter = IpFilter.of(List.of(new IpFilterDat.Range(PEER, PEER, 0, "test")), IpFilter.DEFAULT_LEVEL);
        ConnectionGuard guard = new ConnectionGuard(
                () -> filter,
                ip -> ip == server || ip == PEER,
                new Limits(1, 5, 1, Duration.ofSeconds(5)),
                this.ticker);

        permit(guard.admit(OTHER));
        assertThat(guard.admit(Ipv4.parse("9.9.9.9"))).isEqualTo(new Admission.Refused(Refusal.ACCEPT_RATE));
        assertThat(guard.admit(server)).isInstanceOf(Admission.Admitted.class);
        assertThat(guard.admit(PEER)).isEqualTo(new Admission.Refused(Refusal.FILTERED));
    }

    @Test
    void refusesBannedAddressesAsLongAsTheBanLasts() {
        Set<Integer> banned = new HashSet<>(Set.of(PEER));
        ConnectionGuard guard =
                new ConnectionGuard(IpFilter::empty, ip -> false, banned::contains, Limits.DEFAULT, this.ticker);

        assertThat(guard.admit(PEER)).isEqualTo(new Admission.Refused(Refusal.BANNED));
        permit(guard.admit(OTHER));
        banned.clear();
        assertThat(guard.admit(PEER)).isInstanceOf(Admission.Admitted.class);
    }

    @Test
    void limitsTheAcceptRateOverASlidingWindow() {
        ConnectionGuard guard =
                new ConnectionGuard(IpFilter::empty, new Limits(100, 100, 2, Duration.ofSeconds(5)), this.ticker);

        permit(guard.admit(PEER)).close();
        this.ticker.advance(Duration.ofSeconds(3));
        permit(guard.admit(PEER)).close();
        assertThat(guard.admit(PEER)).isEqualTo(new Admission.Refused(Refusal.ACCEPT_RATE));

        this.ticker.advance(Duration.ofSeconds(2));
        assertThat(guard.admit(PEER)).isInstanceOf(Admission.Admitted.class);
    }

    private static ConnectionGuard.Permit permit(Admission admission) {
        assertThat(admission).isInstanceOf(Admission.Admitted.class);
        return ((Admission.Admitted) admission).permit();
    }

    private static final class FakeTicker extends Ticker {
        private long nanos;

        void advance(Duration duration) {
            this.nanos += duration.toNanos();
        }

        @Override
        public long read() {
            return this.nanos;
        }
    }
}
