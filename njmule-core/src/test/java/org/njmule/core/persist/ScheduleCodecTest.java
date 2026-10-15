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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;
import java.util.OptionalInt;
import java.util.Set;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.core.config.Schedule;

class ScheduleCodecTest {

    @TempDir
    Path dir;

    private Path file;
    private Schedule schedule;
    private Schedule loaded;

    @Test
    void shouldLoseOnlyTheLineOfAMalformedEscape() throws Exception {
        givenTheFile("rule.1.name=Night\\u00\nrule.1.start=01:00\nrule.2.name=Day\nrule.2.start=09:00\n");

        whenTheFileIsLoaded();

        thenTheRulesAreNamed("", "Day");
    }

    @Property(tries = 200)
    void shouldKeepTheRuleNamesThroughTheFile(@ForAll("names") List<String> names) throws IOException {
        givenAScheduleWithRulesNamed(names);

        whenTheScheduleIsSavedAndLoadedBack();

        thenTheLoadedScheduleIsTheSame();
    }

    private void givenTheFile(String content) throws IOException {
        this.file = this.dir.resolve(ScheduleCodec.FILE_NAME);
        Files.writeString(this.file, content);
    }

    private void givenAScheduleWithRulesNamed(List<String> names) {
        this.schedule = new Schedule(names.stream()
                .map(name -> new Schedule.Rule(
                        name,
                        true,
                        Set.of(DayOfWeek.MONDAY),
                        LocalTime.of(1, 0),
                        LocalTime.of(2, 0),
                        OptionalInt.of(10),
                        OptionalInt.empty(),
                        OptionalInt.empty(),
                        OptionalInt.empty(),
                        OptionalInt.empty(),
                        OptionalInt.empty()))
                .toList());
    }

    private void whenTheFileIsLoaded() throws IOException {
        this.loaded = ScheduleCodec.load(this.file);
    }

    private void whenTheScheduleIsSavedAndLoadedBack() throws IOException {
        Path folder = Files.createTempDirectory("schedule");
        try {
            Path saved = folder.resolve(ScheduleCodec.FILE_NAME);
            ScheduleCodec.save(saved, this.schedule);
            this.loaded = ScheduleCodec.load(saved);
        } finally {
            for (String name : List.of(ScheduleCodec.FILE_NAME, ScheduleCodec.FILE_NAME + AtomicFiles.BAK_SUFFIX)) {
                Files.deleteIfExists(folder.resolve(name));
            }
            Files.delete(folder);
        }
    }

    private void thenTheRulesAreNamed(String... names) {
        assertThat(this.loaded.rules()).extracting(Schedule.Rule::name).containsExactly(names);
    }

    private void thenTheLoadedScheduleIsTheSame() {
        assertThat(this.loaded).isEqualTo(this.schedule);
    }

    // No lone surrogate: UTF-8 cannot keep one.
    @Provide
    Arbitrary<List<String>> names() {
        return Arbitraries.strings()
                .withCharRange('\u0000', '퟿')
                .withCharRange('', '�')
                .ofMaxLength(30)
                .map(String::strip)
                .list()
                .ofMaxSize(4);
    }
}
