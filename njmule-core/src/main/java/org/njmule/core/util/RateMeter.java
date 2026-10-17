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

/** Bytes per second averaged over the last whole seconds, and the total. Not thread-safe. */
public final class RateMeter {

    private static final long NANOS_PER_SECOND = 1_000_000_000L;

    // The seconds of the average, then the one under way: counted once it is over, else the average would dip each
    // time a second starts.
    private final long[] perSecond;
    private final int seconds;
    private long second = Long.MIN_VALUE;
    private long total;

    /** @param seconds how many seconds the average spans */
    public RateMeter(int seconds) {
        if (seconds < 1) {
            throw new IllegalArgumentException("seconds must be positive");
        }
        this.seconds = seconds;
        this.perSecond = new long[seconds + 1];
    }

    /** Counts {@code bytes} at {@code now} (monotonic nanoseconds). */
    public void add(long bytes, long now) {
        advance(now);
        this.perSecond[Math.floorMod(this.second, this.perSecond.length)] += bytes;
        this.total += bytes;
    }

    public long rate(long now) {
        advance(now);
        long sum = 0;
        int current = Math.floorMod(this.second, this.perSecond.length);
        for (int i = 0; i < this.perSecond.length; i++) {
            if (i != current) {
                sum += this.perSecond[i];
            }
        }
        return sum / this.seconds;
    }

    public long total() {
        return this.total;
    }

    private void advance(long now) {
        long current = Math.floorDiv(now, NANOS_PER_SECOND);
        if (this.second == Long.MIN_VALUE || current - this.second >= this.perSecond.length) {
            Arrays.fill(this.perSecond, 0);
        } else {
            for (long s = this.second + 1; s <= current; s++) {
                this.perSecond[Math.floorMod(s, this.perSecond.length)] = 0;
            }
        }
        if (this.second == Long.MIN_VALUE || current > this.second) {
            this.second = current;
        }
    }
}
