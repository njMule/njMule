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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.core.api.EngineConfig;
import org.njmule.core.api.EngineStatus;
import org.njmule.core.api.NjMuleEngine;
import org.njmule.core.config.AppDirectories;

@Timeout(30)
class TotalsTest {

    private static final String HUGE = "downloaded=" + Long.MAX_VALUE + "\nuploaded=" + (Long.MAX_VALUE - 1)
            + "\nsessions=" + Long.MAX_VALUE + "\nseconds=" + Long.MAX_VALUE + "\n";

    @TempDir
    Path dir;

    private Path file;
    private Totals totals;
    private AppDirectories dirs;
    private EngineStatus status;

    @Test
    void shouldStopSavedTotalsAtTheLargestNumberInsteadOfStartingAgain() throws Exception {
        givenTotalsSavedAtTheLargestNumbers();
        givenTheTotalsAreLoaded();

        whenASessionIsSaved(10, 10, 10);

        thenTheSavedTotalsAre(Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE);
    }

    @Test
    void shouldAddTheSessionToTheStatusWithoutOverflowing() throws Exception {
        givenAProfileWithTotalsAtTheLargestNumbers();

        whenTheEngineStartsAndTellsItsStatus();

        thenTheStatusCountsSessions(Long.MAX_VALUE);
        thenTheStatusCountsDownloadedBytes(Long.MAX_VALUE);
        thenTheStatusCountsSeconds(Long.MAX_VALUE);
    }

    private void givenTotalsSavedAtTheLargestNumbers() throws Exception {
        this.file = this.dir.resolve(Totals.FILE_NAME);
        Files.writeString(this.file, HUGE);
    }

    private void givenTheTotalsAreLoaded() {
        this.totals = Totals.load(this.file);
    }

    private void givenAProfileWithTotalsAtTheLargestNumbers() throws Exception {
        this.dirs = AppDirectories.underRoot(this.dir, false);
        Files.createDirectories(this.dirs.config());
        Files.writeString(this.dirs.config().resolve(Totals.FILE_NAME), HUGE);
        Files.writeString(this.dirs.config().resolve("njmule.properties"), "net.kad.enabled=false\n");
    }

    private void whenASessionIsSaved(long downloaded, long uploaded, long seconds) {
        this.totals.save(downloaded, uploaded, seconds);
    }

    private void whenTheEngineStartsAndTellsItsStatus() throws Exception {
        try (NjMuleEngine engine = NjMuleEngine.start(new EngineConfig(this.dirs))) {
            this.status = engine.status().get(5, TimeUnit.SECONDS);
        }
    }

    private void thenTheSavedTotalsAre(long downloaded, long uploaded, long sessions, long seconds) {
        Totals saved = Totals.load(this.file);
        assertThat(saved.downloaded()).isEqualTo(downloaded);
        assertThat(saved.uploaded()).isEqualTo(uploaded);
        assertThat(saved.sessions()).isEqualTo(sessions);
        assertThat(saved.seconds()).isEqualTo(seconds);
    }

    private void thenTheStatusCountsSessions(long expected) {
        assertThat(this.status.sessions()).isEqualTo(expected);
    }

    private void thenTheStatusCountsDownloadedBytes(long expected) {
        assertThat(this.status.totalDownloaded()).isEqualTo(expected);
    }

    private void thenTheStatusCountsSeconds(long expected) {
        assertThat(this.status.totalSeconds()).isEqualTo(expected);
    }
}
