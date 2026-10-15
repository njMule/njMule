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
package org.njmule.core.kad;

import com.google.common.base.CharMatcher;
import com.google.common.base.Splitter;
import com.google.common.base.Utf8;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.njmule.core.util.Ipv4;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Small per-message Kad work: the words of a search term, the keywords of a result name, the hex form of an id and the
 * socket address of every datagram sent. In the core's package, as the lower-casing is package-private.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class KadTextBench {

    private static final String NAME = "Ubuntu 24.04.1 LTS Desktop-amd64 [Official Release] (64-bit) ubuntu.iso";
    private static final Splitter WORDS =
            Splitter.on(CharMatcher.anyOf(Keywords.DELIMITERS).precomputed()).omitEmptyStrings();
    private static final CharMatcher DELIMITER =
            CharMatcher.anyOf(Keywords.DELIMITERS).precomputed();

    private final UInt128 id = UInt128.parseHex("0123456789ABCDEFFEDCBA9876543210");
    private final int ip = Ipv4.of(81, 2, 133, 47);
    private final int port = 4672;

    /** Today's code: {@code String.split} with a regular expression. */
    @Benchmark
    public List<String> searchWordsRegex() {
        List<String> parts = new ArrayList<>();
        for (String part : KadLowerCase.toLowerCase(NAME).split("[\" ()\\[\\]{}<>,._\\-!?:;\\\\/]+", -1)) {
            if (!part.isEmpty()) {
                parts.add(part);
            }
        }
        return parts;
    }

    @Benchmark
    public List<String> searchWordsGuavaSplitter() {
        return WORDS.splitToList(KadLowerCase.toLowerCase(NAME));
    }

    /** Today's code. */
    @Benchmark
    public List<String> keywordsCurrent() {
        return Keywords.of(NAME);
    }

    /** The same rules with Guava's CharMatcher and Utf8 and an ordered set. */
    @Benchmark
    public List<String> keywordsGuava() {
        LinkedHashSet<String> words = new LinkedHashSet<>();
        String last = "";
        int start = 0;
        for (int i = 0; i <= NAME.length(); i++) {
            if (i == NAME.length() || DELIMITER.matches(NAME.charAt(i))) {
                last = NAME.substring(start, i);
                start = i + 1;
                if (Utf8.encodedLength(last) >= 3) {
                    String word = KadLowerCase.toLowerCase(last);
                    words.remove(word);
                    words.add(word);
                }
            }
        }
        List<String> out = new ArrayList<>(words);
        if (out.size() > 1 && last.length() == 3 && last.getBytes(StandardCharsets.UTF_8).length == 3) {
            out.removeLast();
        }
        return out;
    }

    /** The replaced code: {@code String.format}. */
    @Benchmark
    public String idHexFormatString() {
        return String.format("%016X%016X", this.id.high(), this.id.low());
    }

    /** Today's code, on the JDK's HexFormat. */
    @Benchmark
    public String idHexFormatJdk() {
        return this.id.toHex();
    }

    /** The replaced code: the address formatted to a string, then parsed back. */
    @Benchmark
    public InetSocketAddress socketAddressViaString() {
        return new InetSocketAddress(Ipv4.format(this.ip), this.port);
    }

    /** Today's code, on Guava's InetAddresses. */
    @Benchmark
    public InetSocketAddress socketAddressGuava() {
        return Ipv4.socketAddress(this.ip, this.port);
    }
}
