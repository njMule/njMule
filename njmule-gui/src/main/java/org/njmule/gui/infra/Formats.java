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
import java.util.function.UnaryOperator;
import org.njmule.gui.i18n.I18n;

/** Sizes, rates, durations and counts as the interface shows them, in one locale. */
public final class Formats {

    private static final String[] UNITS = {"B", "KB", "MB", "GB", "TB", "PB"};
    // The texts that name the units in each language: none names petabytes.
    private static final String[] UNIT_KEYS = {"IDS_BYTESPERSEC", "IDS_KBYTES", "IDS_MBYTES", "IDS_GBYTES", "IDS_TBYTES"
    };
    // Days, hours, minutes and seconds as eMule's texts name them, English when a language does not.
    private static final String[] TIME_UNITS = {"d", "h", "mins", "secs"};
    private static final String[] TIME_KEYS = {"IDS_DAYS", "IDS_HOURS", "IDS_MINS", "IDS_SECS"};
    private static final String RATE = "KB/s";
    private static final String RATE_KEY = "IDS_KBYTESPERSEC";
    private static final long MINUTE = 60;
    private static final long HOUR = 60 * MINUTE;
    private static final long DAY = 24 * HOUR;

    private final DecimalFormat oneDecimal;
    private final DecimalFormat twoDecimals;
    private final NumberFormat integer;
    private final DateTimeFormatter dateTime;
    private final DateTimeFormatter time;
    private final String[] units;
    private final String[] timeUnits;
    private final String rateUnit;

    /** Formats with the English units. */
    public Formats(Locale locale) {
        this(locale, key -> null);
    }

    /** Formats with the units as the translations of {@code i18n}'s language write them, such as Ko and Ko/s. */
    public static Formats of(I18n i18n) {
        return new Formats(i18n.numberLocale(), i18n::t);
    }

    // A unit the language does not name stays English; "B/s" names bytes too.
    Formats(Locale locale, UnaryOperator<String> text) {
        this.units = UNITS.clone();
        for (int i = 0; i < UNIT_KEYS.length; i++) {
            String named = unit(text.apply(UNIT_KEYS[i]));
            if (named != null) {
                this.units[i] = i == 0 && named.indexOf('/') > 0
                        ? named.substring(0, named.indexOf('/')).strip()
                        : named;
            }
        }
        if (this.units[0].isEmpty()) {
            this.units[0] = UNITS[0];
        }
        this.timeUnits = TIME_UNITS.clone();
        for (int i = 0; i < TIME_KEYS.length; i++) {
            String named = unit(text.apply(TIME_KEYS[i]));
            if (named != null) {
                this.timeUnits[i] = named;
            }
        }
        String rate = unit(text.apply(RATE_KEY));
        this.rateUnit = rate == null ? RATE : rate;
        this.dateTime = dateTime(Locale.getDefault(Locale.Category.FORMAT), ZoneId.systemDefault());
        this.time = time(Locale.getDefault(Locale.Category.FORMAT), ZoneId.systemDefault());
        DecimalFormatSymbols symbols = DecimalFormatSymbols.getInstance(locale);
        this.oneDecimal = new DecimalFormat("0.0", symbols);
        this.twoDecimals = new DecimalFormat("0.00", symbols);
        this.integer = NumberFormat.getIntegerInstance(locale);
    }

    // A translated unit, or null for a missing one (shown as its key in brackets) or one too odd to use.
    private static String unit(String text) {
        if (text == null) {
            return null;
        }
        String unit = text.strip();
        return unit.isEmpty() || unit.length() > 12 || unit.startsWith("[") ? null : unit;
    }

    /** {@code time} as the system shows a date and a time, whatever the language of the interface. */
    public String dateTime(Instant time) {
        return this.dateTime.format(time);
    }

    /** The time of the day of {@code time}, without seconds, as the system shows it. */
    public String time(Instant time) {
        return this.time.format(time);
    }

    static DateTimeFormatter time(Locale locale, ZoneId zone) {
        return DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
                .withLocale(locale)
                .withZone(zone);
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
            return Math.max(0, bytes) + " " + this.units[0];
        }
        double value = bytes;
        int unit = 0;
        while (value >= 1024 && unit < UNITS.length - 1) {
            value /= 1024;
            unit++;
        }
        // The form follows the rounded value: 99.995 shows as 100.0, 999.95 as 1,000.
        if (value >= 999.95) {
            return this.integer.format(Math.round(value)) + " " + this.units[unit];
        }
        DecimalFormat format = value < 99.995 ? this.twoDecimals : this.oneDecimal;
        return format.format(value) + " " + this.units[unit];
    }

    /** A rate in kilobytes per second with one decimal. */
    public String rate(long bytesPerSecond) {
        return this.oneDecimal.format(Math.max(0, bytesPerSecond) / 1024.0) + " " + this.rateUnit;
    }

    /** A ratio with up to two decimals: 2.5, 0.33. */
    public String ratio(double value) {
        return this.twoDecimals.format(value);
    }

    public String count(long value) {
        return this.integer.format(value);
    }

    /** {@code part} of {@code whole} as a percentage with one decimal. */
    public String percent(long part, long whole) {
        double ratio = whole <= 0 ? 0 : Math.min(1.0, (double) part / whole);
        return this.oneDecimal.format(ratio * 100) + " %";
    }

    /**
     * A duration in the units of the language, the largest first: {@code 2 d 3 h}, {@code 1:05 h}, {@code 4:07 mins} or
     * {@code 9 secs} in English, {@code 2 g 3 h} or {@code 4:07 min} in Italian.
     */
    public String duration(long seconds) {
        long s = Math.max(0, seconds);
        if (s >= DAY) {
            return s / DAY + " " + this.timeUnits[0] + " " + s % DAY / HOUR + " " + this.timeUnits[1];
        }
        if (s >= HOUR) {
            return s / HOUR + ":" + twoDigits(s % HOUR / MINUTE) + " " + this.timeUnits[1];
        }
        if (s >= MINUTE) {
            return s / MINUTE + ":" + twoDigits(s % MINUTE) + " " + this.timeUnits[2];
        }
        return s + " " + this.timeUnits[3];
    }

    /** A playing time as {@code 1:02:03} or {@code 3:25}. */
    public String playingTime(long seconds) {
        long s = Math.max(0, seconds);
        String minutesAndSeconds = twoDigits(s % HOUR / MINUTE) + ":" + twoDigits(s % MINUTE);
        return s >= HOUR ? s / HOUR + ":" + minutesAndSeconds : s / MINUTE + ":" + twoDigits(s % MINUTE);
    }

    private static String twoDigits(long value) {
        return value < 10 ? "0" + value : String.valueOf(value);
    }
}
