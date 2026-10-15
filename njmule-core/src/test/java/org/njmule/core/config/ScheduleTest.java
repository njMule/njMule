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
import static org.assertj.core.api.Assertions.catchThrowable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.njmule.core.persist.ScheduleCodec;

class ScheduleTest {

    // A Monday.
    private static final LocalDateTime MONDAY_NOON = LocalDateTime.of(2026, 10, 12, 12, 0);

    @TempDir
    Path dir;

    private Schedule.Rule rule;
    private boolean holds;
    private Schedule schedule;
    private Optional<Schedule.Rule> active;
    private Preferences preferences;
    private Preferences applied;
    private Schedule loaded;
    private Throwable exception;

    // 2026-10-12 is a Monday.
    @ParameterizedTest
    @CsvSource(textBlock = """
            2026-10-12T12:00, true
            2026-10-12T18:00, false
            2026-10-12T09:00, true
            2026-10-11T12:00, false
            """)
    void shouldHoldAnOfficeWindowOnItsDaysAndHours(LocalDateTime time, boolean expected) {
        givenTheRule("office", EnumSet.range(DayOfWeek.MONDAY, DayOfWeek.FRIDAY), "09:00", "18:00");

        whenTheRuleIsCheckedAt(time);

        thenTheRuleHolds(expected);
    }

    // Sunday 23:30 and the Monday morning after.
    @ParameterizedTest
    @CsvSource(textBlock = """
            2026-10-11T23:30, true
            2026-10-12T06:00, true
            2026-10-12T07:00, false
            2026-10-11T06:00, false
            """)
    void shouldHoldANightWindowAcrossMidnight(LocalDateTime time, boolean expected) {
        givenTheRule("night", EnumSet.of(DayOfWeek.SUNDAY), "23:00", "07:00");

        whenTheRuleIsCheckedAt(time);

        thenTheRuleHolds(expected);
    }

    @ParameterizedTest
    @CsvSource({"2026-10-10T03:00, true", "2026-10-12T12:00, false"})
    void shouldHoldAWindowFromMidnightToMidnightAllItsDay(LocalDateTime time, boolean expected) {
        givenTheRule("saturday", EnumSet.of(DayOfWeek.SATURDAY), "00:00", "00:00");

        whenTheRuleIsCheckedAt(time);

        thenTheRuleHolds(expected);
    }

    @ParameterizedTest
    @CsvSource({"12, office", "20, always"})
    void shouldLetTheFirstEnabledRuleThatHoldsWin(int hour, String expected) {
        givenAScheduleOfADisabledRuleThenOfficeHoursThenAlways();

        whenTheActiveRuleIsLookedUpAt(MONDAY_NOON.withHour(hour));

        thenTheActiveRuleIs(expected);
    }

    @Test
    void shouldFindNoActiveRuleInAnEmptySchedule() {
        givenTheSchedule(Schedule.EMPTY);

        whenTheActiveRuleIsLookedUpAt(MONDAY_NOON);

        thenNoRuleIsActive();
    }

    @Test
    void shouldChangeOnlyItsOwnValues() {
        givenARuleWithoutDaysAndTheLimits(5, 50, 100, 20);
        givenPreferencesWithTheNick("me");

        whenTheRuleIsAppliedToThem();

        thenTheLimitsAre(5, 50, 100, 20);
        thenTheNickIs("me");
    }

    @Test
    void shouldNotHoldARuleWithoutDays() {
        givenARuleWithoutDaysAndTheLimits(5, 50, 100, 20);

        whenTheRuleIsCheckedAt(MONDAY_NOON);

        thenTheRuleDoesNotHoldWithoutDays();
    }

    @Test
    void shouldRejectARuleWithoutConnections() {
        whenARuleIsMadeWithTheMaxConnections(0);

        thenExceptionOccurred(IllegalArgumentException.class);
    }

    @Test
    void shouldSurviveTheFile() throws IOException {
        givenAScheduleOfANightRuleAndADisabledRule();

        whenTheScheduleIsSavedAndLoadedBack();

        thenTheLoadedScheduleIsTheSavedOne();
    }

    @Test
    void shouldLoadAnEmptyScheduleWithoutAFile() throws IOException {
        whenTheScheduleIsLoadedFrom("none");

        thenTheLoadedScheduleIs(Schedule.EMPTY);
    }

    @Test
    void shouldLeaveBrokenRulesOut() throws IOException {
        givenTheScheduleFile("""
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

        whenTheScheduleIsLoadedFrom(ScheduleCodec.FILE_NAME);

        thenTheLoadedRulesAre("good");
    }

    private void givenTheRule(String name, Set<DayOfWeek> days, String start, String end) {
        this.rule = rule(name, days, start, end);
    }

    private void givenAScheduleOfADisabledRuleThenOfficeHoursThenAlways() {
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
        this.schedule = new Schedule(List.of(off, office, always));
    }

    private void givenTheSchedule(Schedule given) {
        this.schedule = given;
    }

    private void givenARuleWithoutDaysAndTheLimits(
            int uploadKiBps, int downloadKiBps, int maxConnections, int maxSourcesPerFile) {
        this.rule = new Schedule.Rule(
                "slow",
                true,
                Set.of(),
                LocalTime.NOON,
                LocalTime.MIDNIGHT,
                OptionalInt.of(uploadKiBps),
                OptionalInt.of(downloadKiBps),
                OptionalInt.of(maxConnections),
                OptionalInt.of(maxSourcesPerFile));
    }

    private void givenPreferencesWithTheNick(String nick) {
        this.preferences = Preferences.defaults().toBuilder().nick(nick).build();
    }

    private void givenAScheduleOfANightRuleAndADisabledRule() {
        this.schedule = new Schedule(List.of(
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
    }

    private void givenTheScheduleFile(String content) throws IOException {
        Files.writeString(this.dir.resolve(ScheduleCodec.FILE_NAME), content);
    }

    private void whenTheRuleIsCheckedAt(LocalDateTime time) {
        this.holds = this.rule.holds(time);
    }

    private void whenTheActiveRuleIsLookedUpAt(LocalDateTime time) {
        this.active = this.schedule.active(time);
    }

    private void whenTheRuleIsAppliedToThem() {
        this.applied = this.rule.applyTo(this.preferences);
    }

    private void whenARuleIsMadeWithTheMaxConnections(int maxConnections) {
        this.exception = catchThrowable(() -> new Schedule.Rule(
                "x",
                true,
                Set.of(),
                LocalTime.NOON,
                LocalTime.NOON,
                OptionalInt.empty(),
                OptionalInt.empty(),
                OptionalInt.of(maxConnections),
                OptionalInt.empty()));
    }

    private void whenTheScheduleIsSavedAndLoadedBack() throws IOException {
        Path file = this.dir.resolve(ScheduleCodec.FILE_NAME);
        ScheduleCodec.save(file, this.schedule);
        this.loaded = ScheduleCodec.load(file);
    }

    private void whenTheScheduleIsLoadedFrom(String fileName) throws IOException {
        this.loaded = ScheduleCodec.load(this.dir.resolve(fileName));
    }

    private void thenTheRuleHolds(boolean expected) {
        assertThat(this.holds).isEqualTo(expected);
    }

    private void thenTheRuleDoesNotHoldWithoutDays() {
        assertThat(this.holds).as("no days").isFalse();
    }

    private void thenTheActiveRuleIs(String name) {
        Schedule.Rule expected = this.schedule.rules().stream()
                .filter(r -> r.name().equals(name))
                .findFirst()
                .orElseThrow();
        assertThat(this.active).contains(expected);
    }

    private void thenNoRuleIsActive() {
        assertThat(this.active).isEmpty();
    }

    private void thenTheLimitsAre(int uploadKiBps, int downloadKiBps, int maxConnections, int maxSourcesPerFile) {
        assertThat(this.applied.maxUploadKiBps()).isEqualTo(uploadKiBps);
        assertThat(this.applied.maxDownloadKiBps()).isEqualTo(downloadKiBps);
        assertThat(this.applied.maxConnections()).isEqualTo(maxConnections);
        assertThat(this.applied.maxSourcesPerFile()).isEqualTo(maxSourcesPerFile);
    }

    private void thenTheNickIs(String expected) {
        assertThat(this.applied.nick()).isEqualTo(expected);
    }

    private void thenExceptionOccurred(Class<? extends Throwable> type) {
        assertThat(this.exception).isInstanceOf(type);
    }

    private void thenTheLoadedScheduleIsTheSavedOne() {
        assertThat(this.loaded).isEqualTo(this.schedule);
    }

    private void thenTheLoadedScheduleIs(Schedule expected) {
        assertThat(this.loaded).isEqualTo(expected);
    }

    private void thenTheLoadedRulesAre(String... names) {
        assertThat(this.loaded.rules()).extracting(Schedule.Rule::name).containsExactly(names);
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
