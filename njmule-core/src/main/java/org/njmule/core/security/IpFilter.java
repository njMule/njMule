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

import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.PriorityQueue;
import org.njmule.core.persist.format.IpFilterDat;

/**
 * Immutable set of blocked IPv4 ranges: a range blocks when its level is below the filter level. Where ranges overlap,
 * the one listed later wins. Addresses the user always allows are never blocked. Safe to use from any thread.
 *
 * <p>Held in sorted primitive arrays: Guava's range map took 5 times the memory and searched 8 times slower in the
 * native image (njmule-bench IpFilterBench).
 */
public final class IpFilter {

    /** Filter level used when the user did not choose one. */
    public static final int DEFAULT_LEVEL = 127;

    private static final Spans NONE = new Spans(new long[0], new long[0], new String[0]);
    private static final IpFilter EMPTY = new IpFilter(NONE, NONE);

    private final Spans blocked;
    private final Spans allowed;

    private IpFilter(Spans blocked, Spans allowed) {
        this.blocked = blocked;
        this.allowed = allowed;
    }

    public static IpFilter empty() {
        return EMPTY;
    }

    /** Keeps the ranges that block at {@code level}. */
    public static IpFilter of(Collection<IpFilterDat.Range> ranges, int level) {
        return new IpFilter(
                Spans.laterWins(ranges.stream().filter(r -> r.level() < level).toList()), NONE);
    }

    /** The same blocked ranges, with {@code ranges} (whatever their levels) as the only addresses never blocked. */
    public IpFilter allowing(Collection<IpFilterDat.Range> ranges) {
        return new IpFilter(this.blocked, Spans.laterWins(List.copyOf(ranges)));
    }

    /** Whether {@code ip} ({@link org.njmule.core.util.Ipv4} form) is blocked; 0 never is. */
    public boolean blocks(int ip) {
        return blockedSpan(ip) >= 0;
    }

    /** The description of the range blocking {@code ip}, if any. */
    public Optional<String> match(int ip) {
        int span = blockedSpan(ip);
        return span < 0 ? Optional.empty() : Optional.of(this.blocked.descriptions[span]);
    }

    /** Number of disjoint blocked ranges. */
    public int rangeCount() {
        return this.blocked.starts.length;
    }

    private int blockedSpan(int ip) {
        long address = Integer.toUnsignedLong(ip);
        return ip == 0 || this.allowed.indexOf(address) >= 0 ? -1 : this.blocked.indexOf(address);
    }

    /** Sorted, disjoint inclusive spans of unsigned addresses, each with the description of its range. */
    private static final class Spans {

        private final long[] starts;
        private final long[] ends;
        private final String[] descriptions;

        Spans(long[] starts, long[] ends, String[] descriptions) {
            this.starts = starts;
            this.ends = ends;
            this.descriptions = descriptions;
        }

        int indexOf(long address) {
            int i = Arrays.binarySearch(this.starts, address);
            if (i < 0) {
                i = -i - 2;
            }
            return i >= 0 && address <= this.ends[i] ? i : -1;
        }

        /**
         * Sweeps the ranges by address, keeping at each point the last range listed that covers it; adjacent spans of
         * different ranges stay apart.
         */
        static Spans laterWins(List<IpFilterDat.Range> ranges) {
            int n = ranges.size();
            if (n == 0) {
                return NONE;
            }
            // The start (32 bits) above the range's position in the list (31 bits), so one primitive sort orders both.
            long[] order = new long[n];
            for (int i = 0; i < n; i++) {
                order[i] = Integer.toUnsignedLong(ranges.get(i).start()) << 31 | i;
            }
            Arrays.sort(order);
            long[] starts = new long[2 * n];
            long[] ends = new long[2 * n];
            String[] descriptions = new String[2 * n];
            int count = 0;
            int previous = -1;
            PriorityQueue<Integer> covering = new PriorityQueue<>(Comparator.reverseOrder());
            int next = 0;
            long at = 0;
            while (next < n || !covering.isEmpty()) {
                if (covering.isEmpty()) {
                    at = order[next] >>> 31;
                }
                for (; next < n && order[next] >>> 31 <= at; next++) {
                    covering.add((int) (order[next] & Integer.MAX_VALUE));
                }
                while (!covering.isEmpty() && end(ranges, covering.element()) < at) {
                    covering.remove();
                }
                if (covering.isEmpty()) {
                    continue;
                }
                int winner = covering.element();
                long end = end(ranges, winner);
                if (next < n) {
                    end = Math.min(end, (order[next] >>> 31) - 1);
                }
                if (winner == previous) {
                    ends[count - 1] = end;
                } else {
                    starts[count] = at;
                    ends[count] = end;
                    descriptions[count] = ranges.get(winner).description();
                    count++;
                    previous = winner;
                }
                at = end + 1;
            }
            return new Spans(
                    Arrays.copyOf(starts, count), Arrays.copyOf(ends, count), Arrays.copyOf(descriptions, count));
        }

        private static long end(List<IpFilterDat.Range> ranges, int index) {
            return Integer.toUnsignedLong(ranges.get(index).end());
        }
    }
}
