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
package org.njmule.core.download;

import com.google.common.collect.Range;
import com.google.common.collect.RangeMap;
import com.google.common.collect.TreeRangeMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Which source sent each range of a download's data still unproven, to find the sources of corrupt data. Parts that
 * pass their check and AICH recovery data prove data good; recovery data proves blocks corrupt, and a part that fails
 * its check after a single source sent all of it counts against that source. A source is guilty once more than
 * {@link #GUILTY_PERCENT} of what it sent and was proven is corrupt, and, on failed parts alone, once it sent at least
 * {@link #FAILED_PARTS} of them: a stray bad block among much good data, or one bad part, is forgiven. Engine thread
 * only.
 */
final class CorruptionBlackBox {

    static final int GUILTY_PERCENT = 32;
    static final int FAILED_PARTS = 3;

    // IPv4 addresses in host order, by the range of the data each sent.
    private final RangeMap<Long, Integer> senders = TreeRangeMap.create();
    // By source: the bytes proven good, those recovery data proved corrupt, those of the parts it alone sent that
    // failed, and how many such parts.
    private final Map<Integer, long[]> proven = new HashMap<>();

    /** {@code [start, end)} from {@code ip} reached the disk, replacing what was there. */
    void received(long start, long end, int ip) {
        this.senders.putCoalescing(Range.closedOpen(start, end), ip);
    }

    /** {@code [start, end)} passed its check. */
    void passed(long start, long end) {
        Range<Long> range = Range.closedOpen(start, end);
        for (Map.Entry<Range<Long>, Integer> sent :
                this.senders.subRangeMap(range).asMapOfRanges().entrySet()) {
            proven(sent.getValue())[0] += length(sent.getKey());
        }
        this.senders.remove(range);
    }

    /**
     * The part {@code [start, end)} failed its check: it counts against the source that sent all of it, if one did. The
     * ranges are kept, for recovery data to tell which blocks were bad. Returns the sources now guilty.
     */
    Set<Integer> failed(long start, long end) {
        Map<Range<Long>, Integer> sent =
                this.senders.subRangeMap(Range.closedOpen(start, end)).asMapOfRanges();
        Set<Integer> ips = new HashSet<>(sent.values());
        long covered = 0;
        for (Range<Long> part : sent.keySet()) {
            covered += length(part);
        }
        if (ips.size() != 1 || covered != end - start) {
            return Set.of();
        }
        long[] bytes = proven(ips.iterator().next());
        bytes[2] += end - start;
        bytes[3]++;
        return guilty(ips);
    }

    /**
     * Recovery data proved the blocks {@code good} and {@code bad}: each source of some of a bad block counts the whole
     * block as corrupt. Returns the sources now guilty.
     */
    Set<Integer> recovered(List<Range<Long>> good, List<Range<Long>> bad) {
        for (Range<Long> block : good) {
            passed(block.lowerEndpoint(), block.upperEndpoint());
        }
        Set<Integer> suspects = new HashSet<>();
        for (Range<Long> block : bad) {
            for (int ip : new HashSet<>(
                    this.senders.subRangeMap(block).asMapOfRanges().values())) {
                proven(ip)[1] += length(block);
                suspects.add(ip);
            }
            this.senders.remove(block);
        }
        return guilty(suspects);
    }

    private Set<Integer> guilty(Set<Integer> suspects) {
        Set<Integer> guilty = new HashSet<>();
        for (int ip : suspects) {
            long[] bytes = proven(ip);
            if (tooMuch(bytes[1], bytes[0]) || (bytes[3] >= FAILED_PARTS && tooMuch(bytes[2], bytes[0]))) {
                guilty.add(ip);
            }
        }
        return guilty;
    }

    private static boolean tooMuch(long corrupt, long good) {
        return corrupt * 100 > GUILTY_PERCENT * (good + corrupt);
    }

    private long[] proven(int ip) {
        return this.proven.computeIfAbsent(ip, k -> new long[4]);
    }

    private static long length(Range<Long> range) {
        return range.upperEndpoint() - range.lowerEndpoint();
    }
}
