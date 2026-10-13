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
package org.njmule.core.util;

import java.util.Arrays;

/** Bytes per second averaged over the last seconds, and the total. Not thread-safe. */
public final class RateMeter {

    private static final long SECOND = 1_000_000_000L;

    private final long[] perSecond;
    private long second = Long.MIN_VALUE;
    private long total;

    /**
     * @param seconds how many seconds the average spans
     */
    public RateMeter(int seconds) {
        if (seconds < 1) {
            throw new IllegalArgumentException("seconds must be positive");
        }
        this.perSecond = new long[seconds];
    }

    /** Counts {@code bytes} at {@code now} (monotonic nanoseconds). */
    public void add(long bytes, long now) {
        this.advance(now);
        this.perSecond[(int) Math.floorMod(this.second, this.perSecond.length)] += bytes;
        this.total += bytes;
    }

    public long rate(long now) {
        this.advance(now);
        long sum = 0;
        for (long bytes : this.perSecond) {
            sum += bytes;
        }
        return sum / this.perSecond.length;
    }

    public long total() {
        return this.total;
    }

    private void advance(long now) {
        long current = Math.floorDiv(now, SECOND);
        if (this.second == Long.MIN_VALUE || current - this.second >= this.perSecond.length) {
            Arrays.fill(this.perSecond, 0);
        } else {
            for (long s = this.second + 1; s <= current; s++) {
                this.perSecond[(int) Math.floorMod(s, this.perSecond.length)] = 0;
            }
        }
        if (this.second == Long.MIN_VALUE || current > this.second) {
            this.second = current;
        }
    }
}
