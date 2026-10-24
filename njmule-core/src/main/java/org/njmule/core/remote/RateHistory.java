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
package org.njmule.core.remote;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * The transfer rates of the last hour, one sample a second, and of the last day, the mean of each minute: what the
 * front ends draw however long they were away.
 */
public final class RateHistory {

    static final int SECONDS = 3600;
    static final int MINUTES = 1440;

    /** The bytes a second downloaded and uploaded. */
    public record Rate(long down, long up) {}

    private final Deque<Rate> seconds = new ArrayDeque<>();
    private final Deque<Rate> minutes = new ArrayDeque<>();
    private long minuteDown;
    private long minuteUp;
    private int minuteSamples;

    /** Adds the rates of a second; every sixtieth closes a minute. */
    public synchronized void add(long down, long up) {
        keep(this.seconds, new Rate(down, up), SECONDS);
        this.minuteDown += down;
        this.minuteUp += up;
        if (++this.minuteSamples == 60) {
            keep(this.minutes, new Rate(this.minuteDown / 60, this.minuteUp / 60), MINUTES);
            this.minuteDown = 0;
            this.minuteUp = 0;
            this.minuteSamples = 0;
        }
    }

    /** The last {@code count} seconds, oldest first. */
    public synchronized List<Rate> seconds(int count) {
        return last(this.seconds, count);
    }

    /** The last {@code count} minutes, oldest first. */
    public synchronized List<Rate> minutes(int count) {
        return last(this.minutes, count);
    }

    private static void keep(Deque<Rate> samples, Rate rate, int most) {
        samples.addLast(rate);
        if (samples.size() > most) {
            samples.removeFirst();
        }
    }

    private static List<Rate> last(Deque<Rate> samples, int count) {
        List<Rate> all = new ArrayList<>(samples);
        return List.copyOf(all.subList(Math.max(0, all.size() - count), all.size()));
    }
}
