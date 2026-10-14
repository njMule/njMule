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

    @Test
    void savedTotalsStopAtTheLargestNumberInsteadOfStartingAgain() throws Exception {
        Path file = this.dir.resolve(Totals.FILE_NAME);
        Files.writeString(file, HUGE);

        Totals.load(file).save(10, 10, 10);

        Totals saved = Totals.load(file);
        assertThat(saved.downloaded()).isEqualTo(Long.MAX_VALUE);
        assertThat(saved.uploaded()).isEqualTo(Long.MAX_VALUE);
        assertThat(saved.sessions()).isEqualTo(Long.MAX_VALUE);
        assertThat(saved.seconds()).isEqualTo(Long.MAX_VALUE);
    }

    @Test
    void theStatusAddsTheSessionWithoutOverflowing() throws Exception {
        AppDirectories dirs = AppDirectories.underRoot(this.dir, false);
        Files.createDirectories(dirs.config());
        Files.writeString(dirs.config().resolve(Totals.FILE_NAME), HUGE);
        Files.writeString(dirs.config().resolve("njmule.properties"), "net.kad.enabled=false\n");

        try (NjMuleEngine engine = NjMuleEngine.start(new EngineConfig(dirs))) {
            EngineStatus status = engine.status().get(5, TimeUnit.SECONDS);
            assertThat(status.sessions()).isEqualTo(Long.MAX_VALUE);
            assertThat(status.totalDownloaded()).isEqualTo(Long.MAX_VALUE);
            assertThat(status.totalSeconds()).isEqualTo(Long.MAX_VALUE);
        }
    }
}
