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

import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.njmule.core.api.PortTest;
import org.njmule.core.api.PortTest.Forwarding;
import org.njmule.core.api.PortTest.Reach;
import org.njmule.core.upnp.PortForwarding;

class PortCheckTest {

    private static final long START = 1_000;
    private static final Duration WAIT = Duration.ofMinutes(3);

    private PortCheck check;
    private PortTest state;
    private boolean readyToReach;

    @Test
    void shouldAskTheRouterBeforeTheServerAndKad() {
        givenATestWithUpnp();

        whenItIsAskedWhetherToReachAfter(Duration.ofSeconds(1));

        thenTheOtherChecksWait();
        thenTheStateIs(Forwarding.ASKING, Reach.TESTING, Reach.TESTING, false);
    }

    @Test
    void shouldStartTheOtherChecksOnceTheRouterForwardedThePorts() {
        givenATestWithUpnp();
        givenTheRouterAnswered(PortForwarding.Outcome.FORWARDED, "192.168.1.1");

        whenItIsAskedWhetherToReachAfter(Duration.ofSeconds(1));

        thenTheOtherChecksStart();
        thenTheRouterIs("192.168.1.1");
    }

    @Test
    void shouldStartTheOtherChecksWhenTheRouterTakesTooLong() {
        givenATestWithUpnp();

        whenItIsAskedWhetherToReachAfter(PortCheck.UPNP_WAIT);

        thenTheOtherChecksStart();
    }

    @Test
    void shouldFindBothPortsOpenWhenKadReachedThemAndTheServerGaveALowId() {
        givenATestWithoutUpnp();
        givenTheChecksStarted(true);
        givenTheServerTookUs(false);

        whenKadTells(Optional.of(true), Optional.of(true), Duration.ofSeconds(20));

        thenTheStateIs(Forwarding.OFF, Reach.OPEN, Reach.OPEN, true);
    }

    @Test
    void shouldWaitForKadBeforeCallingTheTcpPortClosedOnALowId() {
        givenATestWithoutUpnp();
        givenTheChecksStarted(true);
        givenTheServerTookUs(false);

        whenKadTells(Optional.empty(), Optional.empty(), Duration.ofSeconds(20));

        thenTheStateIs(Forwarding.OFF, Reach.TESTING, Reach.TESTING, false);
    }

    @Test
    void shouldFindTheTcpPortClosedWhenNeitherTheServerNorKadReachedIt() {
        givenATestWithoutUpnp();
        givenTheChecksStarted(true);
        givenTheServerTookUs(false);

        whenKadTells(Optional.of(false), Optional.of(false), Duration.ofSeconds(20));

        thenTheStateIs(Forwarding.OFF, Reach.CLOSED, Reach.CLOSED, true);
    }

    @Test
    void shouldCountOnlyTheLoginTheTestAskedFor() {
        givenATestWithoutUpnp();
        givenTheServerTookUs(true);
        givenTheChecksStarted(true);

        whenNothingRuns(Duration.ofSeconds(20));

        thenTheStateIs(Forwarding.OFF, Reach.TESTING, Reach.UNKNOWN, false);
    }

    @Test
    void shouldTellWhatNothingTestedInTimeAsUnknown() {
        givenATestWithUpnp();
        givenTheChecksStarted(false);

        whenKadTells(Optional.empty(), Optional.empty(), WAIT);

        thenTheStateIs(Forwarding.FAILED, Reach.UNKNOWN, Reach.UNKNOWN, true);
    }

    @Test
    void shouldEndAtOnceWhenTheEngineStops() {
        givenATestWithoutUpnp();
        givenTheChecksStarted(true);

        whenTheEngineStops();

        thenTheStateIs(Forwarding.OFF, Reach.UNKNOWN, Reach.UNKNOWN, true);
        thenTheFutureCompletedWithTheState();
    }

    private void givenATestWithUpnp() {
        this.check = new PortCheck(4662, 4672, true, START, WAIT);
    }

    private void givenATestWithoutUpnp() {
        this.check = new PortCheck(4662, 4672, false, START, WAIT);
    }

    private void givenTheRouterAnswered(PortForwarding.Outcome outcome, String router) {
        this.check.forwarded(outcome, router);
    }

    private void givenTheChecksStarted(boolean login) {
        this.check.reachStarted(login);
    }

    private void givenTheServerTookUs(boolean highId) {
        this.check.loggedIn(highId);
    }

    private void whenItIsAskedWhetherToReachAfter(Duration time) {
        this.readyToReach = this.check.readyToReach(START + time.toNanos());
        this.state = this.check.update(false, Optional.empty(), Optional.empty(), START + time.toNanos());
    }

    private void whenKadTells(Optional<Boolean> tcp, Optional<Boolean> udp, Duration time) {
        this.state = this.check.update(true, tcp, udp, START + time.toNanos());
    }

    private void whenNothingRuns(Duration time) {
        this.state = this.check.update(false, Optional.empty(), Optional.empty(), START + time.toNanos());
    }

    private void whenTheEngineStops() {
        this.check.stop();
        this.state = this.check.state();
    }

    private void thenTheOtherChecksWait() {
        assertThat(this.readyToReach).isFalse();
    }

    private void thenTheOtherChecksStart() {
        assertThat(this.readyToReach).isTrue();
    }

    private void thenTheRouterIs(String router) {
        assertThat(this.state.forwarding()).isEqualTo(Forwarding.FORWARDED);
        assertThat(this.state.router()).isEqualTo(router);
    }

    private void thenTheStateIs(Forwarding forwarding, Reach tcp, Reach udp, boolean done) {
        assertThat(this.state).isEqualTo(new PortTest(4662, 4672, forwarding, this.state.router(), tcp, udp, done));
        assertThat(this.check.done().isDone()).as("the future").isEqualTo(done);
    }

    private void thenTheFutureCompletedWithTheState() {
        assertThat(this.check.done()).isCompletedWithValue(this.state);
    }
}
