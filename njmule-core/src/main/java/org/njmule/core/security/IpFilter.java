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
package org.njmule.core.security;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.njmule.core.persist.format.IpFilterDat;

/**
 * Immutable set of blocked IPv4 ranges: a range blocks when its level is below the filter level.
 * Lookups are a binary search over disjoint ranges, safe from any thread.
 */
public final class IpFilter {

    /** Filter level used when the user did not choose one. */
    public static final int DEFAULT_LEVEL = 127;

    private static final IpFilter EMPTY = new IpFilter(new long[0], new long[0], new String[0]);

    // Unsigned addresses widened to long; sorted, disjoint and non-adjacent.
    private final long[] starts;
    private final long[] ends;
    private final String[] descriptions;

    private IpFilter(long[] starts, long[] ends, String[] descriptions) {
        this.starts = starts;
        this.ends = ends;
        this.descriptions = descriptions;
    }

    public static IpFilter empty() {
        return EMPTY;
    }

    /** Keeps the ranges that block at {@code level} and merges those that touch or overlap. */
    public static IpFilter of(Collection<IpFilterDat.Range> ranges, int level) {
        List<IpFilterDat.Range> blocking = new ArrayList<>();
        for (IpFilterDat.Range r : ranges) {
            if (r.level() < level) {
                blocking.add(r);
            }
        }
        if (blocking.isEmpty()) {
            return EMPTY;
        }
        blocking.sort(Comparator.comparingLong(r -> Integer.toUnsignedLong(r.start())));
        long[] starts = new long[blocking.size()];
        long[] ends = new long[blocking.size()];
        String[] descriptions = new String[blocking.size()];
        int n = 0;
        for (IpFilterDat.Range r : blocking) {
            long start = Integer.toUnsignedLong(r.start());
            long end = Integer.toUnsignedLong(r.end());
            if (n > 0 && start <= ends[n - 1] + 1) {
                ends[n - 1] = Math.max(ends[n - 1], end);
            } else {
                starts[n] = start;
                ends[n] = end;
                descriptions[n] = r.description();
                n++;
            }
        }
        return new IpFilter(
                Arrays.copyOf(starts, n), Arrays.copyOf(ends, n), Arrays.copyOf(descriptions, n));
    }

    /** Whether {@code ip} ({@link org.njmule.core.util.Ipv4} form) is blocked; 0 never is. */
    public boolean blocks(int ip) {
        return this.indexOf(ip) >= 0;
    }

    /** The description of the range blocking {@code ip}, if any. */
    public Optional<String> match(int ip) {
        int i = this.indexOf(ip);
        return i < 0 ? Optional.empty() : Optional.of(this.descriptions[i]);
    }

    /** Number of disjoint blocked ranges. */
    public int rangeCount() {
        return this.starts.length;
    }

    private int indexOf(int ip) {
        if (ip == 0) {
            return -1;
        }
        long key = Integer.toUnsignedLong(ip);
        int low = 0;
        int high = this.starts.length - 1;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            if (key < this.starts[mid]) {
                high = mid - 1;
            } else if (key > this.ends[mid]) {
                low = mid + 1;
            } else {
                return mid;
            }
        }
        return -1;
    }
}
