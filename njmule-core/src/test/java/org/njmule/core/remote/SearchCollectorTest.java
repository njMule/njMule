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
package org.njmule.core.remote;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class SearchCollectorTest {

    private static final long SEARCH = 7;

    private final AtomicLong now = new AtomicLong();
    private final SearchCollector collector = new SearchCollector(this.now::get);
    private boolean running;

    @Test
    void shouldStopCountingASearchAsRunningAfterTheLongestSearch() {
        givenASearchStarted();
        givenTimePassed(SearchCollector.LONGEST_SEARCH.plusSeconds(1));

        whenItIsAskedWhetherTheSearchRuns();

        thenTheSearchRuns(false);
    }

    @Test
    void shouldCountASearchAsRunningAgainOnceAskedForMoreResults() {
        givenASearchStarted();
        givenTimePassed(SearchCollector.LONGEST_SEARCH.plusSeconds(1));
        givenMoreResultsWereAskedFor();

        whenItIsAskedWhetherTheSearchRuns();

        thenTheSearchRuns(true);
    }

    private void givenASearchStarted() {
        this.collector.started(SEARCH);
    }

    private void givenTimePassed(Duration time) {
        this.now.addAndGet(time.toNanos());
    }

    private void givenMoreResultsWereAskedFor() {
        this.collector.resumed(SEARCH);
    }

    private void whenItIsAskedWhetherTheSearchRuns() {
        this.running = this.collector.running(SEARCH);
    }

    private void thenTheSearchRuns(boolean expected) {
        assertThat(this.running).isEqualTo(expected);
    }
}
