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

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.SplittableRandom;
import java.util.concurrent.TimeUnit;
import java.util.zip.Deflater;
import org.njmule.core.protocol.packet.Packing;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

/** zlib (level 1) of one 180 KiB upload block, as the uploader does for files that may compress. */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class BlockDeflateBench {

    private static final int BLOCK = 184_320;
    private static final int LEVEL = 1;
    private static final String[] WORDS = {
        "the", "file", "server", "kad", "node", "source", "<tag>", "</tag>", "value=", "0x1F", "\n", "download"
    };

    @Param({"text", "random"})
    public String content;

    private final byte[] data = new byte[BLOCK];
    private final byte[] out = new byte[BLOCK];
    private final Deflater deflater = new Deflater(LEVEL);

    @Setup
    public void setUp() {
        SplittableRandom random = new SplittableRandom(42);
        if (this.content.equals("random")) {
            random.nextBytes(this.data);
            return;
        }
        int at = 0;
        while (at < BLOCK) {
            byte[] word = (WORDS[random.nextInt(WORDS.length)] + ' ').getBytes(StandardCharsets.US_ASCII);
            int n = Math.min(word.length, BLOCK - at);
            System.arraycopy(word, 0, this.data, at, n);
            at += n;
        }
    }

    @TearDown
    public void tearDown() {
        this.deflater.end();
    }

    /** Today's code: a new Deflater and a growing output stream for every block. */
    @Benchmark
    public byte[] newDeflaterPerBlock() {
        return Packing.deflate(this.data, LEVEL);
    }

    /** One Deflater per thread, reset for each block, into a block-sized buffer; stops once it cannot win. */
    @Benchmark
    public byte[] reusedDeflater() {
        this.deflater.reset();
        this.deflater.setInput(this.data);
        this.deflater.finish();
        int n = 0;
        while (!this.deflater.finished() && n < this.out.length) {
            n += this.deflater.deflate(this.out, n, this.out.length - n);
        }
        return this.deflater.finished() ? Arrays.copyOf(this.out, n) : this.data;
    }
}
