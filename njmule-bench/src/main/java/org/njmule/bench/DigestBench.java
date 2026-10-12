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

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.SplittableRandom;
import java.util.concurrent.TimeUnit;
import org.bouncycastle.crypto.digests.MD4Digest;
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

/**
 * Single-thread throughput of the per-byte primitives; one operation is 1 MiB, so ops/s = MiB/s.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(1)
public class DigestBench {

    private static final int SIZE = 1 << 20;

    private final byte[] data = new byte[SIZE];
    private final MD4Digest md4 = new MD4Digest();
    private final byte[] md4Out = new byte[16];
    private MessageDigest sha1;
    private MessageDigest md5;
    private Rc4 rc4;

    @Setup
    public void setUp() throws NoSuchAlgorithmException {
        new SplittableRandom(42).nextBytes(this.data);
        this.sha1 = MessageDigest.getInstance("SHA-1");
        this.md5 = MessageDigest.getInstance("MD5");
        this.rc4 = new Rc4(new byte[16]);
    }

    @Benchmark
    public byte[] md4() {
        this.md4.update(this.data, 0, SIZE);
        this.md4.doFinal(this.md4Out, 0);
        return this.md4Out;
    }

    @Benchmark
    public byte[] sha1() {
        return this.sha1.digest(this.data);
    }

    @Benchmark
    public byte[] md5() {
        return this.md5.digest(this.data);
    }

    @Benchmark
    public byte[] rc4() {
        this.rc4.apply(this.data, 0, SIZE);
        return this.data;
    }
}
