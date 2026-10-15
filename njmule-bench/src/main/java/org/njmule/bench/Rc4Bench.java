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

import java.util.SplittableRandom;
import java.util.concurrent.TimeUnit;
import org.bouncycastle.crypto.engines.RC4Engine;
import org.bouncycastle.crypto.params.KeyParameter;
import org.njmule.bench.alternatives.HandWrittenRc4;
import org.njmule.core.crypto.Rc4;
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

/** RC4 key stream over a long obfuscated connection; one operation is 1 MiB, so ops/s = MiB/s. */
@State(Scope.Thread)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(1)
public class Rc4Bench {

    private static final int SIZE = 1 << 20;

    private final byte[] data = new byte[SIZE];
    private Rc4 jdk;
    private RC4Engine bouncyCastle;
    private HandWrittenRc4 handWritten;

    @Setup
    public void setUp() {
        new SplittableRandom(42).nextBytes(this.data);
        byte[] key = new byte[16];
        this.jdk = new Rc4(key);
        this.bouncyCastle = new RC4Engine();
        this.bouncyCastle.init(true, new KeyParameter(key));
        this.handWritten = new HandWrittenRc4(key);
    }

    @Benchmark
    public byte[] jdkArcfour() {
        this.jdk.apply(this.data, 0, SIZE);
        return this.data;
    }

    @Benchmark
    public byte[] bouncyCastle() {
        this.bouncyCastle.processBytes(this.data, 0, SIZE, this.data, 0);
        return this.data;
    }

    @Benchmark
    public byte[] handWritten() {
        this.handWritten.apply(this.data, 0, SIZE);
        return this.data;
    }
}
