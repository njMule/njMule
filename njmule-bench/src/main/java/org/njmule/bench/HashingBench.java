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
import org.bouncycastle.crypto.digests.SHA1Digest;
import org.njmule.core.api.Hash16;
import org.njmule.core.crypto.Md4;
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

/** The file hashes, ours against the libraries; one operation is 1 MiB, so ops/s = MiB/s. */
@State(Scope.Thread)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(1)
public class HashingBench {

    private static final int SIZE = 1 << 20;

    private final byte[] data = new byte[SIZE];
    private final byte[] out = new byte[20];
    private final MD4Digest bcMd4 = new MD4Digest();
    private final SHA1Digest bcSha1 = new SHA1Digest();
    private final Md4 md4 = new Md4();
    private MessageDigest jdkSha1;

    @Setup
    public void setUp() throws NoSuchAlgorithmException {
        new SplittableRandom(42).nextBytes(this.data);
        this.jdkSha1 = MessageDigest.getInstance("SHA-1");
    }

    @Benchmark
    public byte[] md4BouncyCastle() {
        this.bcMd4.update(this.data, 0, SIZE);
        this.bcMd4.doFinal(this.out, 0);
        return this.out;
    }

    @Benchmark
    public Hash16 md4HandWritten() {
        this.md4.update(this.data, 0, SIZE);
        return this.md4.digest();
    }

    @Benchmark
    public byte[] sha1Jdk() {
        return this.jdkSha1.digest(this.data);
    }

    @Benchmark
    public byte[] sha1BouncyCastle() {
        this.bcSha1.update(this.data, 0, SIZE);
        this.bcSha1.doFinal(this.out, 0);
        return this.out;
    }
}
