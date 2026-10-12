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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class TimersTest {

    private static final long MS = 1_000_000L;

    private final Timers timers = new Timers();
    private final List<String> runs = new ArrayList<>();

    @Test
    void runsDueJobsInDeadlineOrderThenInsertionOrder() {
        this.timers.schedule("c", Duration.ofMillis(30), 0, () -> this.runs.add("c"));
        this.timers.schedule("a", Duration.ofMillis(10), 0, () -> this.runs.add("a"));
        this.timers.schedule("b1", Duration.ofMillis(20), 0, () -> this.runs.add("b1"));
        this.timers.schedule("b2", Duration.ofMillis(20), 0, () -> this.runs.add("b2"));

        assertThat(this.timers.runDue(20 * MS)).isEqualTo(30 * MS);
        assertThat(this.runs).containsExactly("a", "b1", "b2");
        assertThat(this.timers.runDue(30 * MS)).isEqualTo(Timers.NO_DEADLINE);
        assertThat(this.runs).containsExactly("a", "b1", "b2", "c");
    }

    @Test
    void periodicJobSkipsMissedRunsInsteadOfBursting() {
        this.timers.scheduleAtFixedRate(
                "tick",
                Duration.ofMillis(100),
                Duration.ofMillis(100),
                0,
                () -> this.runs.add("tick"));

        this.timers.runDue(100 * MS);
        assertThat(this.runs).hasSize(1);

        // The engine stalled for 350 ms: one catch-up run, next deadline aligned to the period.
        long next = this.timers.runDue(550 * MS);
        assertThat(this.runs).hasSize(2);
        assertThat(next).isEqualTo(600 * MS);
    }

    @Test
    void cancelledTimersDoNotRunAndAreCollected() {
        Timers.Timer t = this.timers.schedule("x", Duration.ZERO, 0, () -> this.runs.add("x"));
        t.cancel();

        assertThat(this.timers.nextDeadline()).isEqualTo(Timers.NO_DEADLINE);
        this.timers.runDue(MS);
        assertThat(this.runs).isEmpty();
        assertThat(t.isCancelled()).isTrue();
    }

    @Test
    void periodicJobCanCancelItself() {
        Timers.Timer[] self = new Timers.Timer[1];
        self[0] =
                this.timers.scheduleAtFixedRate(
                        "once",
                        Duration.ZERO,
                        Duration.ofMillis(1),
                        0,
                        () -> {
                            this.runs.add("once");
                            self[0].cancel();
                        });

        this.timers.runDue(0);
        this.timers.runDue(10 * MS);
        assertThat(this.runs).containsExactly("once");
    }

    @Test
    void failingJobDoesNotStopOthersAndPeriodicOneIsRescheduled() {
        this.timers.scheduleAtFixedRate(
                "boom",
                Duration.ZERO,
                Duration.ofMillis(5),
                0,
                () -> {
                    throw new IllegalStateException("boom");
                });
        this.timers.schedule("ok", Duration.ZERO, 0, () -> this.runs.add("ok"));

        assertThat(this.timers.runDue(0)).isEqualTo(5 * MS);
        assertThat(this.runs).containsExactly("ok");
    }

    @Test
    void rejectsNonPositivePeriod() {
        assertThatThrownBy(
                        () ->
                                this.timers.scheduleAtFixedRate(
                                        "p", Duration.ZERO, Duration.ZERO, 0, () -> {}))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void clearCancelsEverything() {
        Timers.Timer t =
                this.timers.schedule("x", Duration.ofSeconds(1), 0, () -> this.runs.add("x"));
        this.timers.clear();

        assertThat(this.timers.size()).isZero();
        assertThat(t.isCancelled()).isTrue();
        assertThat(t.name()).isEqualTo("x");
    }
}
