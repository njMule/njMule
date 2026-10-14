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
package org.njmule.core.persist;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.core.config.Schedule;

class ScheduleCodecTest {

    @TempDir
    Path dir;

    @Test
    void aMalformedEscapeCostsOnlyItsLine() throws Exception {
        Path file = this.dir.resolve(ScheduleCodec.FILE_NAME);
        Files.writeString(file, "rule.1.name=Night\\u00\nrule.1.start=01:00\nrule.2.name=Day\nrule.2.start=09:00\n");

        assertThat(ScheduleCodec.load(file).rules())
                .extracting(Schedule.Rule::name)
                .containsExactly("", "Day");
    }
}
