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

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Objects;

/**
 * Hours of the day when njMule may restart to install a new version, written {@code 03:00-05:00}; a window that ends
 * before it starts runs past midnight.
 *
 * @param start the first minute of the window
 * @param end the minute after the last
 */
public record NightHours(LocalTime start, LocalTime end) {

    /** From three to five in the morning. */
    public static final NightHours DEFAULT = new NightHours(LocalTime.of(3, 0), LocalTime.of(5, 0));

    private static final DateTimeFormatter HOURS = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT);

    public NightHours {
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(end, "end");
        if (start.equals(end)) {
            throw new IllegalArgumentException("the night hours must not start when they end: " + start);
        }
    }

    /** The hours written {@code HH:mm-HH:mm}. */
    public static NightHours parse(String text) {
        String[] parts = text.strip().split("-", -1);
        if (parts.length != 2) {
            throw new IllegalArgumentException("not hours from-to, as 03:00-05:00: " + text);
        }
        try {
            return new NightHours(LocalTime.parse(parts[0].strip(), HOURS), LocalTime.parse(parts[1].strip(), HOURS));
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("not hours from-to, as 03:00-05:00: " + text, e);
        }
    }

    /** Whether {@code time} falls in the window. */
    public boolean holds(LocalTime time) {
        if (this.start.isBefore(this.end)) {
            return !time.isBefore(this.start) && time.isBefore(this.end);
        }
        return !time.isBefore(this.start) || time.isBefore(this.end);
    }

    @Override
    public String toString() {
        return HOURS.format(this.start) + "-" + HOURS.format(this.end);
    }
}
