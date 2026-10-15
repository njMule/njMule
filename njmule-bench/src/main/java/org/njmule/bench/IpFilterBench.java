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

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.concurrent.TimeUnit;
import org.njmule.bench.alternatives.ArrayIpFilter;
import org.njmule.core.persist.format.IpFilterDat;
import org.njmule.core.security.IpFilter;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OperationsPerInvocation;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/** An IP filter of 200,000 ranges, the size of the public lists: lookups (per connection) and building it. */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class IpFilterBench {

    private static final int RANGES = 200_000;
    private static final int LOOKUPS = 4096;

    private final List<IpFilterDat.Range> ranges = new ArrayList<>(RANGES);
    private final int[] addresses = new int[LOOKUPS];
    private IpFilter guava;
    private ArrayIpFilter arrays;

    @Setup
    public void setUp() {
        SplittableRandom random = new SplittableRandom(42);
        long at = 0x0100_0000L;
        for (int i = 0; i < RANGES; i++) {
            at += 1 + random.nextInt(20_000);
            long end = at + random.nextInt(2_000);
            this.ranges.add(new IpFilterDat.Range((int) at, (int) end, 100, "Range " + (i % 500)));
            at = end;
        }
        for (int i = 0; i < LOOKUPS; i++) {
            this.addresses[i] = random.nextInt();
        }
        this.guava = IpFilter.of(this.ranges, IpFilter.DEFAULT_LEVEL);
        this.arrays = ArrayIpFilter.of(this.ranges, ArrayIpFilter.DEFAULT_LEVEL);
    }

    /** Today's code: Guava's ImmutableRangeMap keyed by boxed longs. */
    @Benchmark
    @OperationsPerInvocation(LOOKUPS)
    public int lookupGuavaRangeMap() {
        int blocked = 0;
        for (int ip : this.addresses) {
            blocked += this.guava.blocks(ip) ? 1 : 0;
        }
        return blocked;
    }

    /** The replaced binary search over primitive arrays. */
    @Benchmark
    @OperationsPerInvocation(LOOKUPS)
    public int lookupPrimitiveArrays() {
        int blocked = 0;
        for (int ip : this.addresses) {
            blocked += this.arrays.blocks(ip) ? 1 : 0;
        }
        return blocked;
    }

    @Benchmark
    @OutputTimeUnit(TimeUnit.MILLISECONDS)
    public IpFilter buildGuavaRangeMap() {
        return IpFilter.of(this.ranges, IpFilter.DEFAULT_LEVEL);
    }

    @Benchmark
    @OutputTimeUnit(TimeUnit.MILLISECONDS)
    public ArrayIpFilter buildPrimitiveArrays() {
        return ArrayIpFilter.of(this.ranges, ArrayIpFilter.DEFAULT_LEVEL);
    }
}
