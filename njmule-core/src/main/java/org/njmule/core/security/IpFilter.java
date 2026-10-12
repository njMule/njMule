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

import com.google.common.collect.ImmutableRangeMap;
import com.google.common.collect.Range;
import com.google.common.collect.RangeMap;
import com.google.common.collect.TreeRangeMap;
import java.util.Collection;
import java.util.Optional;
import org.njmule.core.persist.format.IpFilterDat;

/**
 * Immutable set of blocked IPv4 ranges: a range blocks when its level is below the filter level.
 * Where ranges overlap, the one listed later wins. Safe to use from any thread.
 */
public final class IpFilter {

    /** Filter level used when the user did not choose one. */
    public static final int DEFAULT_LEVEL = 127;

    private static final IpFilter EMPTY = new IpFilter(ImmutableRangeMap.of());

    // Keyed by the unsigned address widened to long.
    private final ImmutableRangeMap<Long, String> blocked;

    private IpFilter(ImmutableRangeMap<Long, String> blocked) {
        this.blocked = blocked;
    }

    public static IpFilter empty() {
        return EMPTY;
    }

    /** Keeps the ranges that block at {@code level}. */
    public static IpFilter of(Collection<IpFilterDat.Range> ranges, int level) {
        RangeMap<Long, String> blocked = TreeRangeMap.create();
        for (IpFilterDat.Range r : ranges) {
            if (r.level() < level) {
                blocked.put(
                        Range.closed(
                                Integer.toUnsignedLong(r.start()), Integer.toUnsignedLong(r.end())),
                        r.description());
            }
        }
        return new IpFilter(ImmutableRangeMap.copyOf(blocked));
    }

    /** Whether {@code ip} ({@link org.njmule.core.util.Ipv4} form) is blocked; 0 never is. */
    public boolean blocks(int ip) {
        return this.match(ip).isPresent();
    }

    /** The description of the range blocking {@code ip}, if any. */
    public Optional<String> match(int ip) {
        return ip == 0
                ? Optional.empty()
                : Optional.ofNullable(this.blocked.get(Integer.toUnsignedLong(ip)));
    }

    /** Number of disjoint blocked ranges. */
    public int rangeCount() {
        return this.blocked.asMapOfRanges().size();
    }
}
