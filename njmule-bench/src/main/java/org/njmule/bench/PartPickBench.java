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
package org.njmule.bench;

import com.google.common.collect.Range;
import com.google.common.collect.RangeSet;
import com.google.common.collect.TreeRangeSet;
import java.util.SplittableRandom;
import java.util.concurrent.TimeUnit;
import org.njmule.core.api.PartMap;
import org.njmule.core.files.PartFile;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/**
 * The scan behind each block request of a 4 GiB download (442 parts): which parts still have bytes that are missing and
 * not yet requested from another source.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class PartPickBench {

    private static final long SIZE = 4L << 30;
    private static final int BLOCK = 184_320;

    private final RangeSet<Long> missing = TreeRangeSet.create();
    private final RangeSet<Long> requested = TreeRangeSet.create();
    private int parts;

    @Setup
    public void setUp() {
        SplittableRandom random = new SplittableRandom(42);
        this.missing.add(Range.closedOpen(0L, SIZE));
        this.parts = (int) ((SIZE + PartMap.PART_SIZE - 1) / PartMap.PART_SIZE);
        // A third of the parts done, a few begun, and blocks asked from 40 sources.
        for (int part = 0; part < this.parts; part++) {
            Range<Long> range = PartFile.partRange(SIZE, part);
            if (part % 3 == 0) {
                this.missing.remove(range);
            } else if (part % 7 == 0) {
                long start = range.lowerEndpoint();
                this.missing.remove(Range.closedOpen(start, start + 20L * BLOCK));
            }
        }
        for (int i = 0; i < 40; i++) {
            Range<Long> range = PartFile.partRange(SIZE, random.nextInt(this.parts));
            long start = range.lowerEndpoint() + (long) random.nextInt(50) * BLOCK;
            this.requested.add(Range.closedOpen(start, Math.min(start + 3L * BLOCK, range.upperEndpoint())));
        }
    }

    /** Today's code: a copy of the part's missing ranges, minus the requested ones, then an emptiness test. */
    @Benchmark
    public int copyAndRemoveAll() {
        int wanted = 0;
        for (int part = 0; part < this.parts; part++) {
            RangeSet<Long> missingHere = this.missing.subRangeSet(PartFile.partRange(SIZE, part));
            RangeSet<Long> left = TreeRangeSet.create(missingHere);
            left.removeAll(this.requested);
            wanted += left.isEmpty() ? 0 : 1;
        }
        return wanted;
    }

    @Benchmark
    public int enclosesAll() {
        int wanted = 0;
        for (int part = 0; part < this.parts; part++) {
            RangeSet<Long> missingHere = this.missing.subRangeSet(PartFile.partRange(SIZE, part));
            wanted += this.requested.enclosesAll(missingHere) ? 0 : 1;
        }
        return wanted;
    }
}
