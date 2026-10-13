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
package org.njmule.core.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.EnumSet;
import java.util.List;
import java.util.OptionalInt;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.core.persist.ScheduleCodec;

class ScheduleTest {

    // A Monday.
    private static final LocalDateTime MONDAY_NOON = LocalDateTime.of(2026, 10, 12, 12, 0);

    @TempDir
    Path dir;

    @Test
    void windowsHoldOnTheirDaysAndHours() {
        Schedule.Rule office = rule("office", EnumSet.range(DayOfWeek.MONDAY, DayOfWeek.FRIDAY), "09:00", "18:00");
        Schedule.Rule night = rule("night", EnumSet.of(DayOfWeek.SUNDAY), "23:00", "07:00");
        Schedule.Rule saturday = rule("saturday", EnumSet.of(DayOfWeek.SATURDAY), "00:00", "00:00");

        assertThat(office.holds(MONDAY_NOON)).isTrue();
        assertThat(office.holds(MONDAY_NOON.withHour(18))).isFalse();
        assertThat(office.holds(MONDAY_NOON.withHour(9))).isTrue();
        assertThat(office.holds(MONDAY_NOON.minusDays(1))).isFalse();
        // Sunday 23:30 and the Monday morning after.
        assertThat(night.holds(MONDAY_NOON.minusDays(1).withHour(23).withMinute(30)))
                .isTrue();
        assertThat(night.holds(MONDAY_NOON.withHour(6))).isTrue();
        assertThat(night.holds(MONDAY_NOON.withHour(7))).isFalse();
        assertThat(night.holds(MONDAY_NOON.minusDays(1).withHour(6))).isFalse();
        assertThat(saturday.holds(MONDAY_NOON.minusDays(2).withHour(3))).isTrue();
        assertThat(saturday.holds(MONDAY_NOON)).isFalse();
    }

    @Test
    void theFirstEnabledRuleThatHoldsWins() {
        Schedule.Rule off = new Schedule.Rule(
                "off",
                false,
                EnumSet.allOf(DayOfWeek.class),
                LocalTime.MIDNIGHT,
                LocalTime.MIDNIGHT,
                OptionalInt.of(1),
                OptionalInt.empty(),
                OptionalInt.empty(),
                OptionalInt.empty());
        Schedule.Rule office = rule("office", EnumSet.allOf(DayOfWeek.class), "09:00", "18:00");
        Schedule.Rule always = rule("always", EnumSet.allOf(DayOfWeek.class), "00:00", "00:00");
        Schedule schedule = new Schedule(List.of(off, office, always));

        assertThat(schedule.active(MONDAY_NOON)).contains(office);
        assertThat(schedule.active(MONDAY_NOON.withHour(20))).contains(always);
        assertThat(Schedule.EMPTY.active(MONDAY_NOON)).isEmpty();
    }

    @Test
    void aRuleChangesOnlyItsOwnValues() {
        Schedule.Rule rule = new Schedule.Rule(
                "slow",
                true,
                Set.of(),
                LocalTime.NOON,
                LocalTime.MIDNIGHT,
                OptionalInt.of(5),
                OptionalInt.of(50),
                OptionalInt.of(100),
                OptionalInt.of(20));
        Preferences base = Preferences.defaults().toBuilder().nick("me").build();

        Preferences applied = rule.applyTo(base);

        assertThat(applied.maxUploadKiBps()).isEqualTo(5);
        assertThat(applied.maxDownloadKiBps()).isEqualTo(50);
        assertThat(applied.maxConnections()).isEqualTo(100);
        assertThat(applied.maxSourcesPerFile()).isEqualTo(20);
        assertThat(applied.nick()).isEqualTo("me");
        assertThat(rule.holds(MONDAY_NOON)).as("no days").isFalse();
        assertThatThrownBy(() -> new Schedule.Rule(
                        "x",
                        true,
                        Set.of(),
                        LocalTime.NOON,
                        LocalTime.NOON,
                        OptionalInt.empty(),
                        OptionalInt.empty(),
                        OptionalInt.of(0),
                        OptionalInt.empty()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theScheduleSurvivesTheFileAndBrokenRulesAreLeftOut() throws Exception {
        Path file = this.dir.resolve(ScheduleCodec.FILE_NAME);
        Schedule schedule = new Schedule(List.of(
                rule("Night = slow", EnumSet.of(DayOfWeek.FRIDAY, DayOfWeek.SATURDAY), "23:30", "06:15"),
                new Schedule.Rule(
                        "off",
                        false,
                        Set.of(),
                        LocalTime.MIDNIGHT,
                        LocalTime.NOON,
                        OptionalInt.empty(),
                        OptionalInt.of(0),
                        OptionalInt.empty(),
                        OptionalInt.of(10),
                        OptionalInt.of(3),
                        OptionalInt.of(0))));

        ScheduleCodec.save(file, schedule);

        assertThat(ScheduleCodec.load(file)).isEqualTo(schedule);
        assertThat(ScheduleCodec.load(this.dir.resolve("none"))).isEqualTo(Schedule.EMPTY);
        Files.writeString(file, """
                rule.1.name=good
                rule.1.days=MONDAY
                rule.1.start=01:00
                rule.1.end=02:00
                rule.2.name=bad
                rule.2.start=25:00
                rule.3.name=worse
                rule.3.days=FUNDAY
                rule.x.name=odd
                """);
        assertThat(ScheduleCodec.load(file).rules())
                .extracting(Schedule.Rule::name)
                .containsExactly("good");
    }

    private static Schedule.Rule rule(String name, Set<DayOfWeek> days, String start, String end) {
        return new Schedule.Rule(
                name,
                true,
                days,
                LocalTime.parse(start),
                LocalTime.parse(end),
                OptionalInt.of(10),
                OptionalInt.empty(),
                OptionalInt.empty(),
                OptionalInt.empty());
    }
}
