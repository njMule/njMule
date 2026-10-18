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
import com.google.common.net.InetAddresses;
import java.net.InetAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.njmule.core.persist.format.IpFilterDat;
import org.njmule.core.security.ConnectionGuard.Admission;
import org.njmule.core.security.ConnectionGuard.Limits;
import org.njmule.core.security.ConnectionGuard.Refusal;
import org.njmule.core.util.Ipv4;

class ConnectionGuardTest {

    private static final int PEER = Ipv4.parse("1.2.3.4");
    private static final int OTHER = Ipv4.parse("5.6.7.8");
    private static final int SERVER = Ipv4.parse("7.7.7.7");
    private static final InetAddress HOME = InetAddresses.forString("2001:db8:1:2::1");
    private static final InetAddress SAME_HOME = InetAddresses.forString("2001:db8:1:2:ffff:eeee:dddd:7");
    private static final InetAddress NEIGHBOUR = InetAddresses.forString("2001:db8:1:3::1");

    private final FakeTicker ticker = new FakeTicker();
    private final List<ConnectionGuard.Permit> ours = new ArrayList<>();
    private final List<ConnectionGuard.Permit> permits = new ArrayList<>();
    private final Set<InetAddress> banned = new HashSet<>();
    private final AtomicReference<Limits> settings = new AtomicReference<>();
    private ConnectionGuard guard;
    private Admission admission;
    private boolean room;
    private int admitted;
    private Duration window;
    private Limits madeLimits;
    private RuntimeException exception;

    @Test
    void shouldRefuseFilteredAddressesFirst() {
        givenAGuardFiltering(PEER);

        whenAConnectionComesFrom(PEER);

        thenItIsRefused(Refusal.FILTERED);
    }

    @Test
    void shouldAdmitAddressesTheFilterLetsThrough() {
        givenAGuardFiltering(PEER);

        whenAConnectionComesFrom(OTHER);

        thenItIsAdmitted();
    }

    @Test
    void shouldCapConnectionsPerAddressAndInTotalUntilPermitsAreReleased() {
        givenAGuard(new Limits(3, 2, 100, Duration.ofSeconds(5)));
        givenAConnectionFrom(PEER);
        givenAConnectionFrom(PEER);
        givenTheGuardRefuses(PEER, Refusal.TOO_MANY_FROM_ADDRESS);
        givenAConnectionFrom(OTHER);
        givenTheGuardRefuses(Ipv4.parse("9.9.9.9"), Refusal.TOO_MANY_CONNECTIONS);
        givenTheFirstConnectionIsClosedTwice();
        givenTheOpenConnectionsAre(2);

        whenAConnectionComesFrom(PEER);

        thenItIsAdmitted();
    }

    @Test
    void shouldReadTheLimitsAtEachAdmission() {
        givenAGuardReadingTheLimitsFromTheSettings(new Limits(1, 5, 100, Duration.ofSeconds(5)));
        givenAConnectionFrom(PEER);
        givenTheGuardRefuses(OTHER, Refusal.TOO_MANY_CONNECTIONS);
        givenTheSettingsChangeTo(new Limits(2, 5, 100, Duration.ofSeconds(5)));

        whenAConnectionComesFrom(OTHER);

        thenItIsAdmitted("the settings changed");
    }

    @Test
    void shouldLetTheServerTestingUsPassTheRateAndTotalLimitsButNotTheFilter() {
        givenAGuardFilteringButExempting(PEER, Set.of(SERVER, PEER), new Limits(1, 5, 1, Duration.ofSeconds(5)));
        givenAConnectionFrom(OTHER);
        givenTheGuardRefuses(Ipv4.parse("9.9.9.9"), Refusal.ACCEPT_RATE);
        givenAConnectionFrom(SERVER);

        whenAConnectionComesFrom(PEER);

        thenItIsRefused(Refusal.FILTERED);
    }

    @Test
    void shouldRefuseBannedAddressesAsLongAsTheBanLasts() {
        givenAGuardBanning(PEER);
        givenTheGuardRefuses(PEER, Refusal.BANNED);
        givenAConnectionFrom(OTHER);
        givenTheBanEnds();

        whenAConnectionComesFrom(PEER);

        thenItIsAdmitted();
    }

    @Test
    void shouldCountTheAddressesOfAnIpv6Slash64AsOne() {
        givenAGuard(new Limits(10, 1, 100, Duration.ofSeconds(5)));
        givenAConnectionFrom(HOME);

        whenAConnectionComesFrom(SAME_HOME);

        thenItIsRefused(Refusal.TOO_MANY_FROM_ADDRESS);
    }

    @Test
    void shouldCountTheNextIpv6Slash64Apart() {
        givenAGuard(new Limits(10, 1, 100, Duration.ofSeconds(5)));
        givenAConnectionFrom(HOME);

        whenAConnectionComesFrom(NEIGHBOUR);

        thenItIsAdmitted();
    }

    @Test
    void shouldGiveTheSlotOfAnIpv6Slash64BackWhenItsConnectionCloses() {
        givenAGuard(new Limits(10, 1, 100, Duration.ofSeconds(5)));
        givenAClosedConnectionFrom(HOME);

        whenAConnectionComesFrom(SAME_HOME);

        thenItIsAdmitted();
    }

    @Test
    void shouldRefuseABannedIpv6Address() {
        givenAGuardBanning(HOME);

        whenAConnectionComesFrom(HOME);

        thenItIsRefused(Refusal.BANNED);
    }

    @Test
    void shouldLimitTheAcceptRateOverASlidingWindow() {
        givenAGuard(new Limits(100, 100, 2, Duration.ofSeconds(5)));
        givenAClosedConnectionFrom(PEER);
        givenTimePasses(Duration.ofSeconds(3));
        givenAClosedConnectionFrom(PEER);
        givenTheGuardRefuses(PEER, Refusal.ACCEPT_RATE);
        givenTimePasses(Duration.ofSeconds(2));

        whenAConnectionComesFrom(PEER);

        thenItIsAdmitted();
    }

    @Test
    void shouldNotCountRefusalsInTheRateAndClosePermitsOnce() {
        givenAGuard(new Limits(100, 1, 2, Duration.ofSeconds(5)));
        givenAConnectionFrom(PEER);
        givenTheGuardRefusesEveryTime(PEER, 5, Refusal.TOO_MANY_FROM_ADDRESS);
        givenAConnectionFrom(OTHER);
        givenTheFirstConnectionIsClosedTwice();
        givenTheOpenConnectionsAre(1);
        givenTheGuardRefuses(PEER, Refusal.ACCEPT_RATE, "closing a permit gives no accept back");

        whenTheLastConnectionIsClosed();

        thenNoConnectionIsOpen();
    }

    @Test
    void shouldLetAnAcceptLeaveTheWindowExactlyWhenItEnds() {
        givenAGuard(new Limits(100, 100, 1, Duration.ofSeconds(5)));
        givenAClosedConnectionFrom(PEER);
        givenTimePasses(Duration.ofSeconds(5).minusNanos(1));
        givenTheGuardRefuses(PEER, Refusal.ACCEPT_RATE);
        givenTimePasses(Duration.ofNanos(1));

        whenAConnectionComesFrom(PEER);

        thenItIsAdmitted();
    }

    @Test
    void shouldStillCapTheConnectionsOfAnExemptAddress() {
        givenAGuardExempting(PEER, new Limits(1, 2, 1, Duration.ofSeconds(5)));
        givenAConnectionFrom(OTHER);
        givenAConnectionFrom(PEER);
        givenAConnectionFrom(PEER);

        whenAConnectionComesFrom(PEER);

        thenItIsRefused(Refusal.TOO_MANY_FROM_ADDRESS);
    }

    @ParameterizedTest
    @MethodSource("windowsThatAreNotAPositiveSpanOfTime")
    void shouldRefuseARateWindowThatIsNotAPositiveSpanOfTime(Duration rateWindow) {
        whenLimitsAreMadeWithTheRateWindow(rateWindow);

        thenExceptionOccurred(IllegalArgumentException.class);
    }

    @Test
    void shouldAcceptTheLongestRateWindow() {
        whenLimitsAreMadeWithTheRateWindow(Limits.MAX_RATE_WINDOW);

        thenTheRateWindowIs(Limits.MAX_RATE_WINDOW);
    }

    @Test
    void shouldAcceptARateWindowOfOneNanosecond() {
        whenLimitsAreMadeWithTheRateWindow(Duration.ofNanos(1));

        thenTheRateWindowIs(Duration.ofNanos(1));
    }

    @Test
    void shouldLeaveTheAcceptRateToOthersWhenAnAddressOpensConnectionsInARow() {
        givenAGuard(new Limits(100, 2, 8, Duration.ofSeconds(5)));
        givenAnAddressThatOpenedAndClosedConnectionsInARow(PEER, 8);

        whenAnotherAddressConnects(OTHER);

        thenTheFirstAddressGotOnlyItsShare(2);
        thenTheOtherAddressIsAdmitted();
    }

    @Test
    void shouldCountOurConnectionsInTheTotalTheIncomingOnesShare() {
        givenAGuard(new Limits(2, 5, 100, Duration.ofSeconds(5)));
        givenConnectionsOfOurs(2);

        whenAnotherAddressConnects(OTHER);

        thenTheOtherAddressIsRefused(Refusal.TOO_MANY_CONNECTIONS);
        thenThereIsNoRoomForOneMoreOfOurs();
        thenTheOtherAddressIsAdmittedOnceOneOfOursCloses();
    }

    @Test
    void shouldLeaveNoRoomForOurConnectionsOnceTheRateOfNewOnesIsReached() {
        givenAGuard(new Limits(100, 5, 2, Duration.ofSeconds(5)));
        givenConnectionsOfOurs(2);

        whenRoomForOneMoreOfOursIsAsked();

        thenThereIsNoRoom();
    }

    @Test
    void shouldCountOurNewConnectionsInTheRateTheIncomingOnesShare() {
        givenAGuard(new Limits(100, 5, 2, Duration.ofSeconds(5)));
        givenConnectionsOfOurs(2);

        whenAnotherAddressConnects(OTHER);

        thenItIsRefused(Refusal.ACCEPT_RATE);
    }

    @Test
    void shouldHaveRoomForOurConnectionsAgainOnceTheWindowPassed() {
        givenAGuard(new Limits(100, 5, 2, Duration.ofSeconds(5)));
        givenConnectionsOfOurs(2);
        givenTimePasses(Duration.ofSeconds(5));

        whenRoomForOneMoreOfOursIsAsked();

        thenThereIsRoom();
    }

    private void givenAGuard(Limits limits) {
        this.guard = new ConnectionGuard(IpFilter::empty, limits, this.ticker);
    }

    private void givenAGuardFiltering(int ip) {
        IpFilter filter = IpFilter.of(List.of(new IpFilterDat.Range(ip, ip, 0, "test")), IpFilter.DEFAULT_LEVEL);
        this.guard = new ConnectionGuard(() -> filter, Limits.DEFAULT, this.ticker);
    }

    private void givenAGuardFilteringButExempting(int filtered, Set<Integer> exempt, Limits limits) {
        IpFilter filter =
                IpFilter.of(List.of(new IpFilterDat.Range(filtered, filtered, 0, "test")), IpFilter.DEFAULT_LEVEL);
        this.guard = new ConnectionGuard(() -> filter, exempt::contains, limits, this.ticker);
    }

    private void givenAGuardExempting(int ip, Limits limits) {
        this.guard =
                new ConnectionGuard(IpFilter::empty, address -> address == ip, address -> false, limits, this.ticker);
    }

    private void givenAGuardBanning(int ip) {
        givenAGuardBanning(InetAddresses.fromInteger(ip));
    }

    private void givenAGuardBanning(InetAddress address) {
        this.banned.add(address);
        this.guard =
                new ConnectionGuard(IpFilter::empty, ip -> false, this.banned::contains, Limits.DEFAULT, this.ticker);
    }

    private void givenAGuardReadingTheLimitsFromTheSettings(Limits limits) {
        this.settings.set(limits);
        this.guard = new ConnectionGuard(
                IpFilter::empty, address -> false, address -> false, this.settings::get, this.ticker);
    }

    private void givenTheSettingsChangeTo(Limits limits) {
        this.settings.set(limits);
    }

    private void givenTheBanEnds() {
        this.banned.clear();
    }

    private void givenTimePasses(Duration duration) {
        this.ticker.advance(duration);
    }

    private void givenAConnectionFrom(int ip) {
        this.permits.add(permit(this.guard.admit(ip)));
    }

    private void givenAConnectionFrom(InetAddress address) {
        this.permits.add(permit(this.guard.admit(address)));
    }

    private void givenAClosedConnectionFrom(int ip) {
        permit(this.guard.admit(ip)).close();
    }

    private void givenAClosedConnectionFrom(InetAddress address) {
        permit(this.guard.admit(address)).close();
    }

    private void givenTheFirstConnectionIsClosedTwice() {
        this.permits.getFirst().close();
        this.permits.getFirst().close();
    }

    private void givenTheGuardRefuses(int ip, Refusal reason) {
        assertThat(this.guard.admit(ip)).isEqualTo(new Admission.Refused(reason));
    }

    private void givenTheGuardRefuses(int ip, Refusal reason, String why) {
        assertThat(this.guard.admit(ip)).as(why).isEqualTo(new Admission.Refused(reason));
    }

    private void givenTheGuardRefusesEveryTime(int ip, int times, Refusal reason) {
        for (int i = 0; i < times; i++) {
            assertThat(this.guard.admit(ip)).isEqualTo(new Admission.Refused(reason));
        }
    }

    private void givenTheOpenConnectionsAre(int count) {
        assertThat(this.guard.openConnections()).isEqualTo(count);
    }

    private void givenAnAddressThatOpenedAndClosedConnectionsInARow(int ip, int times) {
        for (int i = 0; i < times; i++) {
            if (this.guard.admit(ip) instanceof Admission.Admitted(ConnectionGuard.Permit permit)) {
                this.admitted++;
                permit.close();
            }
        }
    }

    private void givenConnectionsOfOurs(int count) {
        for (int i = 0; i < count; i++) {
            this.ours.add(this.guard.outgoing());
        }
    }

    private void whenAnotherAddressConnects(int ip) {
        this.admission = this.guard.admit(ip);
    }

    private void whenRoomForOneMoreOfOursIsAsked() {
        this.room = this.guard.hasRoom();
    }

    private void whenAConnectionComesFrom(int ip) {
        this.admission = this.guard.admit(ip);
    }

    private void whenAConnectionComesFrom(InetAddress address) {
        this.admission = this.guard.admit(address);
    }

    private void whenTheLastConnectionIsClosed() {
        this.permits.getLast().close();
    }

    private void whenLimitsAreMadeWithTheRateWindow(Duration rateWindow) {
        this.window = rateWindow;
        try {
            this.madeLimits = new Limits(100, 100, 2, rateWindow);
        } catch (RuntimeException e) {
            this.exception = e;
        }
    }

    private void thenTheFirstAddressGotOnlyItsShare(int share) {
        assertThat(this.admitted).isEqualTo(share);
    }

    private void thenThereIsNoRoom() {
        assertThat(this.room).isFalse();
    }

    private void thenThereIsRoom() {
        assertThat(this.room).isTrue();
    }

    private void thenItIsAdmitted() {
        assertThat(this.admission).isInstanceOf(Admission.Admitted.class);
    }

    private void thenItIsAdmitted(String why) {
        assertThat(this.admission).as(why).isInstanceOf(Admission.Admitted.class);
    }

    private void thenItIsRefused(Refusal reason) {
        assertThat(this.admission).isEqualTo(new Admission.Refused(reason));
    }

    private void thenNoConnectionIsOpen() {
        assertThat(this.guard.openConnections()).isZero();
    }

    private void thenExceptionOccurred(Class<? extends RuntimeException> type) {
        assertThat(this.exception).as("%s", this.window).isInstanceOf(type);
    }

    private void thenTheRateWindowIs(Duration expected) {
        assertThat(this.exception).isNull();
        assertThat(this.madeLimits.rateWindow()).isEqualTo(expected);
    }

    private void thenTheOtherAddressIsAdmitted() {
        assertThat(this.admission).isInstanceOf(Admission.Admitted.class);
    }

    private void thenTheOtherAddressIsRefused(Refusal reason) {
        assertThat(this.admission).isEqualTo(new Admission.Refused(reason));
    }

    private void thenThereIsNoRoomForOneMoreOfOurs() {
        assertThat(this.guard.hasRoom()).isFalse();
        assertThat(this.guard.openConnections()).isEqualTo(2);
    }

    private void thenTheOtherAddressIsAdmittedOnceOneOfOursCloses() {
        this.ours.getFirst().close();
        this.ours.getFirst().close();
        assertThat(this.guard.hasRoom()).isTrue();
        assertThat(this.guard.admit(OTHER)).isInstanceOf(Admission.Admitted.class);
        assertThat(this.guard.hasRoom()).isFalse();
    }

    static Stream<Duration> windowsThatAreNotAPositiveSpanOfTime() {
        return Stream.of(Duration.ZERO, Duration.ofSeconds(-5), Duration.ofDays(365 * 300));
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
