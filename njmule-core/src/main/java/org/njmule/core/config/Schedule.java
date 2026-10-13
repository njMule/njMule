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

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

/**
 * Times of the week when other limits apply: during a rule's window its values replace those of the preferences, which
 * stay as they are; the first enabled rule whose window holds wins.
 */
public record Schedule(List<Rule> rules) {

    /** No rules. */
    public static final Schedule EMPTY = new Schedule(List.of());

    /**
     * One window of the week and the limits it sets; a window that ends before it starts runs past midnight, one that
     * ends when it starts lasts the whole day.
     */
    public record Rule(
            String name,
            boolean enabled,
            Set<DayOfWeek> days,
            LocalTime start,
            LocalTime end,
            OptionalInt uploadKiBps,
            OptionalInt downloadKiBps,
            OptionalInt maxConnections,
            OptionalInt maxSourcesPerFile) {

        public Rule {
            name = Objects.requireNonNull(name, "name").strip();
            days = days.isEmpty() ? Set.of() : Set.copyOf(EnumSet.copyOf(days));
            Objects.requireNonNull(start, "start");
            Objects.requireNonNull(end, "end");
            check("uploadKiBps", uploadKiBps, 0, Preferences.MAX_RATE_KIBPS);
            check("downloadKiBps", downloadKiBps, 0, Preferences.MAX_RATE_KIBPS);
            check("maxConnections", maxConnections, 1, Preferences.MAX_CONNECTIONS);
            check("maxSourcesPerFile", maxSourcesPerFile, 1, Preferences.MAX_SOURCES_PER_FILE);
        }

        /** Whether {@code now} falls in this rule's window. */
        public boolean holds(LocalDateTime now) {
            if (!this.enabled) {
                return false;
            }
            DayOfWeek today = now.getDayOfWeek();
            LocalTime time = now.toLocalTime();
            if (this.start.equals(this.end)) {
                return this.days.contains(today);
            }
            if (this.start.isBefore(this.end)) {
                return this.days.contains(today) && !time.isBefore(this.start) && time.isBefore(this.end);
            }
            return (this.days.contains(today) && !time.isBefore(this.start))
                    || (this.days.contains(today.minus(1)) && time.isBefore(this.end));
        }

        /** {@code preferences} with this rule's values. */
        public Preferences applyTo(Preferences preferences) {
            Preferences.Builder b = preferences.toBuilder();
            this.uploadKiBps.ifPresent(b::maxUploadKiBps);
            this.downloadKiBps.ifPresent(b::maxDownloadKiBps);
            this.maxConnections.ifPresent(b::maxConnections);
            this.maxSourcesPerFile.ifPresent(b::maxSourcesPerFile);
            return b.build();
        }

        private static void check(String name, OptionalInt value, int min, int max) {
            Objects.requireNonNull(value, name);
            if (value.isPresent() && (value.getAsInt() < min || value.getAsInt() > max)) {
                throw new IllegalArgumentException(
                        name + " must be in [" + min + ", " + max + "]: " + value.getAsInt());
            }
        }
    }

    public Schedule {
        rules = List.copyOf(rules);
    }

    /** The rule that holds at {@code now}, if any. */
    public Optional<Rule> active(LocalDateTime now) {
        return this.rules.stream().filter(r -> r.holds(now)).findFirst();
    }
}
