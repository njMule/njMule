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

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.util.SplittableRandom;
import java.util.concurrent.TimeUnit;
import org.njmule.core.files.hash.FileHasher;
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

/**
 * Throughput of the one-pass eD2K hash set and AICH tree over data already in memory, so disk speed
 * does not count; ops/s = MiB/s.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 3)
@Measurement(iterations = 5, time = 3)
@Fork(1)
public class FileHashBench {

    // Three full parts and a partial one.
    private static final int MIB = 32;

    private final byte[] data = new byte[MIB << 20];

    @Setup
    public void setUp() {
        new SplittableRandom(42).nextBytes(this.data);
    }

    @Benchmark
    @OperationsPerInvocation(MIB)
    public FileHasher.Result ed2kAndAich() throws IOException {
        return FileHasher.hash(new MemoryChannel(this.data), this.data.length, done -> {});
    }

    private static final class MemoryChannel implements ReadableByteChannel {

        private final byte[] data;
        private int position;

        MemoryChannel(byte[] data) {
            this.data = data;
        }

        @Override
        public int read(ByteBuffer dst) {
            if (this.position == this.data.length) {
                return -1;
            }
            int n = Math.min(dst.remaining(), this.data.length - this.position);
            dst.put(this.data, this.position, n);
            this.position += n;
            return n;
        }

        @Override
        public boolean isOpen() {
            return true;
        }

        @Override
        public void close() {}
    }
}
