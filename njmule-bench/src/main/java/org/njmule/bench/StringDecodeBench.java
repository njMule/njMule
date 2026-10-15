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

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import org.njmule.core.protocol.io.Ed2kStrings;
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
import org.openjdk.jmh.annotations.Warmup;

/** Decoding one string of a packet or tag (a file name): UTF-8 when valid, else Windows-1252. */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class StringDecodeBench {

    private static final Charset WINDOWS_1252 = Charset.forName("windows-1252");
    private static final String ASCII = "Ubuntu 24.04.1 LTS Desktop amd64 (official release) [x64].iso";
    private static final String NON_ASCII = "Расписание поездов – 東京駅 – Übersicht der Fahrpläne 2026.pdf";
    private static final String ANSI = "Café Müller – Ölgemälde und Brötchen (Sammlung) 1999.avi";

    @Param({"ascii", "utf8", "windows1252"})
    public String kind;

    private byte[] raw;

    @Setup
    public void setUp() {
        this.raw = switch (this.kind) {
            case "ascii" -> ASCII.getBytes(StandardCharsets.US_ASCII);
            case "utf8" -> NON_ASCII.getBytes(StandardCharsets.UTF_8);
            default -> ANSI.getBytes(WINDOWS_1252);
        };
    }

    /** The replaced code: a strict CharsetDecoder for each string, and an exception for each one not UTF-8. */
    @Benchmark
    public String charsetDecoder() {
        int[] span = withoutBom(this.raw);
        try {
            return StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(this.raw, span[0], span[1]))
                    .toString();
        } catch (CharacterCodingException e) {
            return new String(this.raw, span[0], span[1], WINDOWS_1252);
        }
    }

    /** Today's code: Guava's well-formedness check, then the JDK's String decoding. */
    @Benchmark
    public String guavaUtf8() {
        return Ed2kStrings.decode(this.raw);
    }

    /** The same strict JDK decoder, reading its result instead of catching an exception. */
    @Benchmark
    public String jdkCoderResult() {
        int[] span = withoutBom(this.raw);
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder();
        // UTF-8 never yields more chars than bytes, so the buffer cannot overflow.
        CharBuffer out = CharBuffer.allocate(span[1]);
        if (decoder.decode(ByteBuffer.wrap(this.raw, span[0], span[1]), out, true)
                        .isError()
                || decoder.flush(out).isError()) {
            return new String(this.raw, span[0], span[1], WINDOWS_1252);
        }
        return out.flip().toString();
    }

    private static int[] withoutBom(byte[] raw) {
        boolean bom = raw.length >= 3 && raw[0] == (byte) 0xEF && raw[1] == (byte) 0xBB && raw[2] == (byte) 0xBF;
        return bom ? new int[] {3, raw.length - 3} : new int[] {0, raw.length};
    }
}
