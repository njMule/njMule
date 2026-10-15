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

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.SplittableRandom;
import java.util.concurrent.TimeUnit;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import org.bouncycastle.crypto.digests.MD5Digest;
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

/**
 * One key attempt of an obfuscated UDP datagram: MD5 of the key material, an RC4 key schedule and the decryption of a
 * 150-byte datagram. A received datagram may take up to three attempts.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class DatagramKeyBench {

    private final byte[] material = new byte[23];
    private final byte[] datagram = new byte[150];
    private final byte[] work = new byte[150];
    private final byte[] md5Out = new byte[16];
    private final MD5Digest bcMd5 = new MD5Digest();
    private final RC4Engine bcRc4 = new RC4Engine();
    private MessageDigest jdkMd5;
    private Cipher jdkCipher;

    @Setup
    public void setUp() throws GeneralSecurityException {
        SplittableRandom random = new SplittableRandom(42);
        random.nextBytes(this.material);
        random.nextBytes(this.datagram);
        this.jdkMd5 = MessageDigest.getInstance("MD5");
        this.jdkCipher = Cipher.getInstance("ARCFOUR");
    }

    /** Today's code: a new MessageDigest and a new Cipher for every attempt. */
    @Benchmark
    public byte[] jdkNewInstances() throws GeneralSecurityException {
        Rc4 rc4 = new Rc4(MessageDigest.getInstance("MD5").digest(this.material));
        rc4.apply(this.datagram, 0, this.work, 0, this.datagram.length);
        return this.work;
    }

    @Benchmark
    public byte[] jdkReusedInstances() throws GeneralSecurityException {
        byte[] key = this.jdkMd5.digest(this.material);
        this.jdkCipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "ARCFOUR"));
        this.jdkCipher.update(this.datagram, 0, this.datagram.length, this.work, 0);
        return this.work;
    }

    @Benchmark
    public byte[] bouncyCastleNewInstances() {
        MD5Digest md5 = new MD5Digest();
        md5.update(this.material, 0, this.material.length);
        byte[] key = new byte[16];
        md5.doFinal(key, 0);
        RC4Engine rc4 = new RC4Engine();
        rc4.init(true, new KeyParameter(key));
        rc4.processBytes(this.datagram, 0, this.datagram.length, this.work, 0);
        return this.work;
    }

    @Benchmark
    public byte[] bouncyCastleReusedInstances() {
        this.bcMd5.update(this.material, 0, this.material.length);
        this.bcMd5.doFinal(this.md5Out, 0);
        this.bcRc4.init(true, new KeyParameter(this.md5Out));
        this.bcRc4.processBytes(this.datagram, 0, this.datagram.length, this.work, 0);
        return this.work;
    }

    /** The hand-written RC4 the JDK cipher replaced, with today's MD5. */
    @Benchmark
    public byte[] handWrittenRc4() throws GeneralSecurityException {
        HandWrittenRc4 rc4 = new HandWrittenRc4(MessageDigest.getInstance("MD5").digest(this.material));
        rc4.apply(this.datagram, 0, this.work, 0, this.datagram.length);
        return this.work;
    }
}
