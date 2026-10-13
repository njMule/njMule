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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.OptionalInt;
import java.util.Properties;
import java.util.Set;
import java.util.SortedSet;
import java.util.stream.Collectors;
import org.njmule.core.config.Schedule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads and writes the schedule as {@code scheduler.properties}: {@code rule.N.*} keys, rules in the order of N. A
 * broken rule is left out and logged.
 */
public final class ScheduleCodec {

    public static final String FILE_NAME = "scheduler.properties";

    private static final Logger logger = LoggerFactory.getLogger(ScheduleCodec.class);
    private static final String PREFIX = "rule.";

    private ScheduleCodec() {}

    public static Schedule load(Path file) throws IOException {
        if (!Files.exists(file) && !Files.exists(AtomicFiles.bakPath(file.toAbsolutePath()))) {
            return Schedule.EMPTY;
        }
        Properties p = PropertyLines.read(file);
        SortedSet<Integer> numbers = PropertyLines.numbers(p, PREFIX);
        List<Schedule.Rule> rules = new ArrayList<>();
        for (int n : numbers) {
            String k = PREFIX + n + ".";
            try {
                rules.add(new Schedule.Rule(
                        p.getProperty(k + "name", ""),
                        Boolean.parseBoolean(p.getProperty(k + "enabled", "true")),
                        days(p.getProperty(k + "days", "")),
                        LocalTime.parse(p.getProperty(k + "start", "00:00").strip()),
                        LocalTime.parse(p.getProperty(k + "end", "00:00").strip()),
                        number(p.getProperty(k + "uploadKiBps")),
                        number(p.getProperty(k + "downloadKiBps")),
                        number(p.getProperty(k + "maxConnections")),
                        number(p.getProperty(k + "maxSourcesPerFile"))));
            } catch (IllegalArgumentException | DateTimeException e) {
                logger.warn("Ignoring rule {} of {}: {}", n, file.getFileName(), e.getMessage());
            }
        }
        return new Schedule(rules);
    }

    public static void save(Path file, Schedule schedule) throws IOException {
        StringBuilder text = new StringBuilder("# njMule scheduler\n");
        int n = 1;
        for (Schedule.Rule r : schedule.rules()) {
            String k = PREFIX + n++ + ".";
            PropertyLines.append(text, k + "name", r.name());
            PropertyLines.append(text, k + "enabled", String.valueOf(r.enabled()));
            PropertyLines.append(
                    text,
                    k + "days",
                    r.days().stream().sorted().map(DayOfWeek::name).collect(Collectors.joining(",")));
            PropertyLines.append(text, k + "start", r.start().toString());
            PropertyLines.append(text, k + "end", r.end().toString());
            optional(text, k + "uploadKiBps", r.uploadKiBps());
            optional(text, k + "downloadKiBps", r.downloadKiBps());
            optional(text, k + "maxConnections", r.maxConnections());
            optional(text, k + "maxSourcesPerFile", r.maxSourcesPerFile());
        }
        AtomicFiles.writeBytes(file, text.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static Set<DayOfWeek> days(String text) {
        Set<DayOfWeek> days = EnumSet.noneOf(DayOfWeek.class);
        for (String day : text.split(",", -1)) {
            if (!day.isBlank()) {
                days.add(DayOfWeek.valueOf(day.strip().toUpperCase(Locale.ROOT)));
            }
        }
        return days;
    }

    private static OptionalInt number(String text) {
        return text == null || text.isBlank() ? OptionalInt.empty() : OptionalInt.of(Integer.parseInt(text.strip()));
    }

    private static void optional(StringBuilder text, String key, OptionalInt value) {
        if (value.isPresent()) {
            PropertyLines.append(text, key, String.valueOf(value.getAsInt()));
        }
    }
}
