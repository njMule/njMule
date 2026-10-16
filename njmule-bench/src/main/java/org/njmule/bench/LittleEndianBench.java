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

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.SplittableRandom;
import java.util.concurrent.TimeUnit;
import org.njmule.core.util.LittleEndian;
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
 * Little-endian 32-bit reads and writes in a byte array, as the framing, obfuscation and file codecs do; one operation
 * is one value.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class LittleEndianBench {

    private static final int SIZE = 64 * 1024;
    private static final int VALUES = SIZE / 4 - 1;
    private static final VarHandle INT = MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.LITTLE_ENDIAN);

    private final byte[] data = new byte[SIZE];
    private final ByteBuffer buffer = ByteBuffer.wrap(this.data).order(ByteOrder.LITTLE_ENDIAN);

    @Setup
    public void setUp() {
        new SplittableRandom(42).nextBytes(this.data);
    }

    /** The replaced code: shifts and masks written in place, at unaligned offsets as in a packet. */
    @Benchmark
    @OperationsPerInvocation(VALUES)
    public int readShifts() {
        int sum = 0;
        for (int at = 1; at + 4 <= SIZE; at += 4) {
            sum += (this.data[at] & 0xFF)
                    | (this.data[at + 1] & 0xFF) << 8
                    | (this.data[at + 2] & 0xFF) << 16
                    | (this.data[at + 3] & 0xFF) << 24;
        }
        return sum;
    }

    /** Today's code: the {@link LittleEndian} helper, on a byte-array view. */
    @Benchmark
    @OperationsPerInvocation(VALUES)
    public int readHelper() {
        int sum = 0;
        for (int at = 1; at + 4 <= SIZE; at += 4) {
            sum += LittleEndian.getInt(this.data, at);
        }
        return sum;
    }

    /** A byte-array view used in place, as MD4 does. */
    @Benchmark
    @OperationsPerInvocation(VALUES)
    public int readVarHandle() {
        int sum = 0;
        for (int at = 1; at + 4 <= SIZE; at += 4) {
            sum += (int) INT.get(this.data, at);
        }
        return sum;
    }

    @Benchmark
    @OperationsPerInvocation(VALUES)
    public int readByteBuffer() {
        int sum = 0;
        for (int at = 1; at + 4 <= SIZE; at += 4) {
            sum += this.buffer.getInt(at);
        }
        return sum;
    }

    @Benchmark
    @OperationsPerInvocation(VALUES)
    public byte[] writeShifts() {
        for (int at = 1, v = 0; at + 4 <= SIZE; at += 4, v += 0x01010101) {
            this.data[at] = (byte) v;
            this.data[at + 1] = (byte) (v >>> 8);
            this.data[at + 2] = (byte) (v >>> 16);
            this.data[at + 3] = (byte) (v >>> 24);
        }
        return this.data;
    }

    @Benchmark
    @OperationsPerInvocation(VALUES)
    public byte[] writeHelper() {
        for (int at = 1, v = 0; at + 4 <= SIZE; at += 4, v += 0x01010101) {
            LittleEndian.putInt(this.data, at, v);
        }
        return this.data;
    }

    @Benchmark
    @OperationsPerInvocation(VALUES)
    public byte[] writeVarHandle() {
        for (int at = 1, v = 0; at + 4 <= SIZE; at += 4, v += 0x01010101) {
            INT.set(this.data, at, v);
        }
        return this.data;
    }
}
