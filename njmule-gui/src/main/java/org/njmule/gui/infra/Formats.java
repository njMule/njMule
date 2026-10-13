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
package org.njmule.gui.infra;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.text.NumberFormat;
import java.time.Instant;
import java.time.ZoneId;
import java.time.chrono.IsoChronology;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.FormatStyle;
import java.util.Locale;

/** Sizes, rates, durations and counts as the interface shows them, in one locale. */
public final class Formats {

    private static final String[] UNITS = {"B", "KB", "MB", "GB", "TB", "PB"};
    private static final long MINUTE = 60;
    private static final long HOUR = 60 * MINUTE;
    private static final long DAY = 24 * HOUR;

    private final DecimalFormat oneDecimal;
    private final DecimalFormat twoDecimals;
    private final NumberFormat integer;
    private final DateTimeFormatter dateTime;

    public Formats(Locale locale) {
        this.dateTime = dateTime(Locale.getDefault(Locale.Category.FORMAT), ZoneId.systemDefault());
        DecimalFormatSymbols symbols = DecimalFormatSymbols.getInstance(locale);
        this.oneDecimal = new DecimalFormat("0.0", symbols);
        this.twoDecimals = new DecimalFormat("0.00", symbols);
        this.integer = NumberFormat.getIntegerInstance(locale);
    }

    /** {@code time} as the system shows a date and a time, whatever the language of the interface. */
    public String dateTime(Instant time) {
        return this.dateTime.format(time);
    }

    // The short date and the time with seconds of {@code locale}; short dates often have a two-digit year.
    static DateTimeFormatter dateTime(Locale locale, ZoneId zone) {
        String pattern = DateTimeFormatterBuilder.getLocalizedDateTimePattern(
                FormatStyle.SHORT, FormatStyle.MEDIUM, IsoChronology.INSTANCE, locale);
        return DateTimeFormatter.ofPattern(pattern.replaceAll("(?<!y)yy(?!y)", "yyyy"), locale)
                .withZone(zone);
    }

    /** {@code bytes} with a binary unit: 999 B, 1.00 KB, 12.3 MB, 1.21 GB. */
    public String size(long bytes) {
        if (bytes < 1024) {
            return Math.max(0, bytes) + " B";
        }
        double value = bytes;
        int unit = 0;
        while (value >= 1024 && unit < UNITS.length - 1) {
            value /= 1024;
            unit++;
        }
        DecimalFormat format = value < 100 ? this.twoDecimals : this.oneDecimal;
        if (value >= 1000) {
            return this.integer.format(Math.round(value)) + " " + UNITS[unit];
        }
        return format.format(value) + " " + UNITS[unit];
    }

    /** A rate in KB/s with one decimal, as eMule shows it. */
    public String rate(long bytesPerSecond) {
        return this.oneDecimal.format(Math.max(0, bytesPerSecond) / 1024.0) + " KB/s";
    }

    public String count(long value) {
        return this.integer.format(value);
    }

    /** {@code part} of {@code whole} as a percentage with one decimal. */
    public String percent(long part, long whole) {
        double ratio = whole <= 0 ? 0 : Math.min(1.0, (double) part / whole);
        return this.oneDecimal.format(ratio * 100) + " %";
    }

    /** A duration as {@code 2d 3h}, {@code 1h 05m}, {@code 4m 07s} or {@code 9s}. */
    public String duration(long seconds) {
        long s = Math.max(0, seconds);
        if (s >= DAY) {
            return s / DAY + "d " + s % DAY / HOUR + "h";
        }
        if (s >= HOUR) {
            return s / HOUR + "h " + twoDigits(s % HOUR / MINUTE) + "m";
        }
        if (s >= MINUTE) {
            return s / MINUTE + "m " + twoDigits(s % MINUTE) + "s";
        }
        return s + "s";
    }

    private static String twoDigits(long value) {
        return value < 10 ? "0" + value : String.valueOf(value);
    }
}
