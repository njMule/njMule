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
package org.njmule.core.kad;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * How long nodes take to answer a lookup, and so how long a lookup waits before it moves on: the time within which
 * about 95% of the last answers came (mean plus twice the deviation, plus a margin), never more than three seconds.
 * Each of the last nodes counts once, and those not yet timed count as three seconds, so the wait shortens only as
 * answers prove it may. Engine thread only.
 */
final class ResponseTimes {

    /** The longest a lookup waits for an answer before it asks the next node. */
    static final Duration MAX_WAIT = Duration.ofSeconds(3);

    private static final int NODES = 100;
    private static final long MARGIN = Duration.ofMillis(100).toNanos();

    // By address, the oldest first.
    private final Map<Integer, Long> times = new LinkedHashMap<>();
    private long wait = MAX_WAIT.toNanos();

    /** A node at {@code ip} answered after {@code nanos}. */
    void answered(int ip, long nanos) {
        this.times.remove(ip);
        this.times.put(ip, Math.clamp(nanos, 0, MAX_WAIT.toNanos()));
        if (this.times.size() > NODES) {
            this.times.remove(this.times.keySet().iterator().next());
        }
        this.wait = estimate();
    }

    /** Answers timed, for tests. */
    int samples() {
        return this.times.size();
    }

    /** How long a lookup waits for an answer before it asks the next node. */
    long waitNanos() {
        return this.wait;
    }

    private long estimate() {
        double max = MAX_WAIT.toNanos();
        int untimed = NODES - this.times.size();
        double sum = untimed * max;
        for (long t : this.times.values()) {
            sum += t;
        }
        double mean = sum / NODES;
        double squares = untimed * (max - mean) * (max - mean);
        for (long t : this.times.values()) {
            squares += (t - mean) * (t - mean);
        }
        double deviation = Math.sqrt(squares / (NODES - 1));
        return Math.min(MAX_WAIT.toNanos(), (long) (mean + 2 * deviation) + MARGIN);
    }
}
