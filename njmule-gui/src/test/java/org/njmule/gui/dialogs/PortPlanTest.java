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
package org.njmule.gui.dialogs;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import java.util.OptionalInt;
import java.util.SplittableRandom;
import java.util.function.IntPredicate;
import org.junit.jupiter.api.Test;
import org.njmule.core.api.EmulePorts;

class PortPlanTest {

    private Optional<EmulePorts> emule = Optional.empty();
    private IntPredicate free = p -> true;
    private PortPlan plan;

    @Test
    void withoutEmuleTheOwnPortsStay() {
        this.whenThePortsArePlannedFrom(4662, 4672);
        this.thenThePortsAre(4662, 4672);
    }

    @Test
    void portsEmuleDoesNotUseStay() {
        this.givenEmuleUses(4663, OptionalInt.of(4673));
        this.whenThePortsArePlannedFrom(4662, 4672);
        this.thenThePortsAre(4662, 4672);
    }

    @Test
    void portsEmuleUsesMoveToFreeOnes() {
        this.givenEmuleUses(4662, OptionalInt.of(4672));
        this.whenThePortsArePlannedFrom(4662, 4672);
        this.thenNoPortIsEmules();
        this.thenThePortsAreInTheRegisteredRange();
    }

    @Test
    void aBusyPortIsNotProposed() {
        this.givenEmuleUses(4662, OptionalInt.empty());
        this.givenOnlyPortsAboveAreFree(40_000);
        this.whenThePortsArePlannedFrom(4662, 4672);
        this.thenTheTcpPortIsAtLeast(40_000);
        this.thenThePortsAre(this.plan.tcp(), 4672);
    }

    private void givenEmuleUses(int tcp, OptionalInt udp) {
        this.emule = Optional.of(new EmulePorts(tcp, udp));
    }

    private void givenOnlyPortsAboveAreFree(int lowest) {
        this.free = p -> p >= lowest;
    }

    private void whenThePortsArePlannedFrom(int tcp, int udp) {
        this.plan = PortPlan.of(tcp, udp, this.emule, this.free, this.free, new SplittableRandom(7));
    }

    private void thenThePortsAre(int tcp, int udp) {
        assertThat(this.plan).isEqualTo(new PortPlan(tcp, udp));
    }

    private void thenNoPortIsEmules() {
        EmulePorts ports = this.emule.orElseThrow();
        assertThat(ports.uses(this.plan.tcp())).isFalse();
        assertThat(ports.uses(this.plan.udp())).isFalse();
    }

    private void thenThePortsAreInTheRegisteredRange() {
        assertThat(this.plan.tcp()).isBetween(PortPlan.LOW, PortPlan.HIGH);
        assertThat(this.plan.udp()).isBetween(PortPlan.LOW, PortPlan.HIGH);
    }

    private void thenTheTcpPortIsAtLeast(int lowest) {
        assertThat(this.plan.tcp()).isGreaterThanOrEqualTo(lowest);
    }
}
