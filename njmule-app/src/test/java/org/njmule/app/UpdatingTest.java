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
package org.njmule.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(30)
class UpdatingTest {

    private List<String> args;
    private List<String> again;
    private Map<String, String> environment = Map.of();
    private boolean supervised;
    private Duration waited;

    @Test
    void shouldStartAgainWithoutWaitingForAnEarlierProcess() {
        givenTheArguments("--wait", "4242", "--config-dir", "/data", "--wait=77", "--headless");

        whenTheArgumentsToStartAgainAreTaken();

        thenTheyAre("--config-dir", "/data", "--headless");
    }

    @Test
    void shouldTellAServiceOfSystemd() {
        givenTheEnvironment(Map.of("INVOCATION_ID", "8f3c", "JOURNAL_STREAM", "8:1234"));

        whenAskedWhetherSystemdRestartsIt();

        thenTheAnswerIs(true);
    }

    @Test
    void shouldNotTakeATerminalForAService() {
        givenTheEnvironment(Map.of("TERM", "xterm"));

        whenAskedWhetherSystemdRestartsIt();

        thenTheAnswerIs(false);
    }

    @Test
    void shouldNotWaitForAProcessThatHasEnded() {
        whenWaitingFor(Long.MAX_VALUE);

        thenItWaitedLessThan(Duration.ofSeconds(5));
    }

    private void givenTheArguments(String... given) {
        this.args = List.of(given);
    }

    private void givenTheEnvironment(Map<String, String> chosen) {
        this.environment = chosen;
    }

    private void whenTheArgumentsToStartAgainAreTaken() {
        this.again = Updating.again(this.args);
    }

    private void whenAskedWhetherSystemdRestartsIt() {
        this.supervised = Updating.underSystemd(this.environment);
    }

    private void whenWaitingFor(long pid) {
        long start = System.nanoTime();
        Updating.awaitEnd(List.of(pid));
        this.waited = Duration.ofNanos(System.nanoTime() - start);
    }

    private void thenTheyAre(String... expected) {
        assertThat(this.again).containsExactly(expected);
    }

    private void thenTheAnswerIs(boolean expected) {
        assertThat(this.supervised).isEqualTo(expected);
    }

    private void thenItWaitedLessThan(Duration limit) {
        assertThat(this.waited).isLessThan(limit);
    }
}
