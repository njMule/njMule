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
package org.njmule.core.download;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.njmule.core.download.KnownSources.Candidate;
import org.njmule.core.download.KnownSources.Standing;
import org.njmule.core.files.PartFile;

/** Which sources of a download are kept for the next run. */
class KnownSourcesTest {

    private static final long NOW = 1_760_000_000L;
    private static final long DAY = 86_400L;

    private final List<Candidate> now = new ArrayList<>();
    private final List<PartFile.KnownSource> before = new ArrayList<>();
    private List<PartFile.KnownSource> kept;

    @Test
    void shouldKeepFirstTheSourcesThatServedBest() {
        givenTheSourceNow(1, Standing.ANSWERED, 0, 3);
        givenTheSourceNow(2, Standing.QUEUED, 5, 0);
        givenTheSourceNow(3, Standing.DOWNLOADING, 0, 1);
        givenTheSourceNow(4, Standing.ANSWERED, 0, 7);
        givenTheSourceNow(5, Standing.QUEUED, 2, 0);

        whenTheSourcesAreChosen();

        thenThePortsKeptAre(3, 5, 2, 4, 1);
    }

    @Test
    void shouldKeepAfterThemTheSourcesKeptBeforeUntilAWeekOld() {
        givenTheSourceNow(1, Standing.ANSWERED, 0, 1);
        givenTheSourceKeptBefore(2, NOW - 6 * DAY);
        givenTheSourceKeptBefore(3, NOW - 8 * DAY);
        givenTheSourceKeptBefore(1, NOW - 2 * DAY);

        whenTheSourcesAreChosen();

        thenThePortsKeptAre(1, 2);
    }

    @Test
    void shouldKeepTheDateOfASourceKeptLessThanADayAgo() {
        givenTheSourceKeptBefore(1, NOW - 3_600);
        givenTheSourceKeptBefore(2, NOW - 2 * DAY);
        givenTheSourceNow(1, Standing.ANSWERED, 0, 1);
        givenTheSourceNow(2, Standing.ANSWERED, 0, 1);

        whenTheSourcesAreChosen();

        thenTheDatesKeptAre(NOW - 3_600, NOW);
    }

    @Test
    void shouldKeepNoMoreSourcesThanTheMost() {
        givenSourcesNow(PartFile.MAX_KNOWN_SOURCES + 20);
        givenTheSourceKeptBefore(60_000, NOW);

        whenTheSourcesAreChosen();

        thenTheSourcesKeptAre(PartFile.MAX_KNOWN_SOURCES);
    }

    private void givenTheSourceNow(int port, Standing standing, int rank, int parts) {
        this.now.add(new Candidate(source(port, NOW), standing, rank, parts));
    }

    private void givenSourcesNow(int count) {
        for (int i = 1; i <= count; i++) {
            givenTheSourceNow(i, Standing.ANSWERED, 0, 1);
        }
    }

    private void givenTheSourceKeptBefore(int port, long seen) {
        this.before.add(source(port, seen));
    }

    private void whenTheSourcesAreChosen() {
        this.kept = KnownSources.choose(this.now, this.before, NOW);
    }

    private void thenThePortsKeptAre(Integer... ports) {
        assertThat(this.kept).extracting(PartFile.KnownSource::port).containsExactly(ports);
    }

    private void thenTheDatesKeptAre(Long... dates) {
        assertThat(this.kept).extracting(PartFile.KnownSource::seen).containsExactly(dates);
    }

    private void thenTheSourcesKeptAre(int count) {
        assertThat(this.kept).hasSize(count);
    }

    private static PartFile.KnownSource source(int port, long seen) {
        return new PartFile.KnownSource(0x5511_7400, port, seen, Optional.empty(), 0);
    }
}
