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
import java.util.Arrays;
import java.util.List;
import org.njmule.core.kad.KadTextBench;

/**
 * Runs the JMH benchmark bodies in a plain timed loop, so they can also be measured inside a native image, where JMH
 * cannot run: {@code native-image -cp benchmarks.jar -O2 org.njmule.bench.NativeSpot}, then {@code nativespot [seconds]
 * [name filter]}.
 */
public class NativeSpot {

    interface Op {
        Object run() throws Exception;
    }

    record Kernel(String name, double unitsPerCall, String unit, Op op) {}

    static Object sink;

    public static void main(String[] args) throws Exception {
        double seconds = args.length > 0 ? Double.parseDouble(args[0]) : 1.0;
        List<Kernel> k = new ArrayList<>();

        HashingBench h = new HashingBench();
        h.setUp();
        k.add(new Kernel("HashingBench.md4BouncyCastle", 1, "MiB/s", h::md4BouncyCastle));
        k.add(new Kernel("HashingBench.md4HandWritten", 1, "MiB/s", h::md4HandWritten));
        k.add(new Kernel("HashingBench.sha1Jdk", 1, "MiB/s", h::sha1Jdk));
        k.add(new Kernel("HashingBench.sha1BouncyCastle", 1, "MiB/s", h::sha1BouncyCastle));

        Rc4Bench r = new Rc4Bench();
        r.setUp();
        k.add(new Kernel("Rc4Bench.jdkArcfour", 1, "MiB/s", r::jdkArcfour));
        k.add(new Kernel("Rc4Bench.bouncyCastle", 1, "MiB/s", r::bouncyCastle));
        k.add(new Kernel("Rc4Bench.handWritten", 1, "MiB/s", r::handWritten));

        DatagramKeyBench d = new DatagramKeyBench();
        d.setUp();
        k.add(new Kernel("DatagramKeyBench.jdkNewInstances", 1, "ns/op", d::jdkNewInstances));
        k.add(new Kernel("DatagramKeyBench.jdkReusedInstances", 1, "ns/op", d::jdkReusedInstances));
        k.add(new Kernel("DatagramKeyBench.bouncyCastleNewInstances", 1, "ns/op", d::bouncyCastleNewInstances));
        k.add(new Kernel("DatagramKeyBench.bouncyCastleReusedInstances", 1, "ns/op", d::bouncyCastleReusedInstances));
        k.add(new Kernel("DatagramKeyBench.handWrittenRc4", 1, "ns/op", d::handWrittenRc4));

        ObfuscatedStreamBench o = new ObfuscatedStreamBench();
        o.setUp();
        k.add(new Kernel("ObfuscatedStreamBench.readJdkCipherInputStream", 1, "ops/s", o::readJdkCipherInputStream));
        k.add(new Kernel("ObfuscatedStreamBench.readJdkCipherInPlace", 1, "ops/s", o::readJdkCipherInPlace));
        k.add(new Kernel("ObfuscatedStreamBench.readBouncyCastleInPlace", 1, "ops/s", o::readBouncyCastleInPlace));
        k.add(new Kernel(
                "ObfuscatedStreamBench.writeJdkCipherOutputStream", 1, "ops/s", o::writeJdkCipherOutputStream));
        k.add(new Kernel("ObfuscatedStreamBench.writeJdkCipherInPlace", 1, "ops/s", o::writeJdkCipherInPlace));
        k.add(new Kernel("ObfuscatedStreamBench.writeBouncyCastleInPlace", 1, "ops/s", o::writeBouncyCastleInPlace));

        for (String kind : new String[] {"ascii", "utf8", "windows1252"}) {
            StringDecodeBench s = new StringDecodeBench();
            s.kind = kind;
            s.setUp();
            k.add(new Kernel("StringDecodeBench.charsetDecoder:" + kind, 1, "ns/op", s::charsetDecoder));
            k.add(new Kernel("StringDecodeBench.guavaUtf8:" + kind, 1, "ns/op", s::guavaUtf8));
            k.add(new Kernel("StringDecodeBench.jdkCoderResult:" + kind, 1, "ns/op", s::jdkCoderResult));
        }

        for (String content : new String[] {"text", "random"}) {
            BlockDeflateBench b = new BlockDeflateBench();
            b.content = content;
            b.setUp();
            k.add(new Kernel("BlockDeflateBench.newDeflaterPerBlock:" + content, 1, "us/op", b::newDeflaterPerBlock));
            k.add(new Kernel("BlockDeflateBench.reusedDeflater:" + content, 1, "us/op", b::reusedDeflater));
        }

        IpFilterBench f = new IpFilterBench();
        f.setUp();
        k.add(new Kernel("IpFilterBench.lookupGuavaRangeMap", 4096, "ns/op", f::lookupGuavaRangeMap));
        k.add(new Kernel("IpFilterBench.lookupPrimitiveArrays", 4096, "ns/op", f::lookupPrimitiveArrays));
        k.add(new Kernel("IpFilterBench.buildGuavaRangeMap", 1, "ms/op", f::buildGuavaRangeMap));
        k.add(new Kernel("IpFilterBench.buildPrimitiveArrays", 1, "ms/op", f::buildPrimitiveArrays));

        KadTextBench t = new KadTextBench();
        k.add(new Kernel("KadTextBench.searchWordsRegex", 1, "ns/op", t::searchWordsRegex));
        k.add(new Kernel("KadTextBench.searchWordsGuavaSplitter", 1, "ns/op", t::searchWordsGuavaSplitter));
        k.add(new Kernel("KadTextBench.keywordsCurrent", 1, "ns/op", t::keywordsCurrent));
        k.add(new Kernel("KadTextBench.keywordsGuava", 1, "ns/op", t::keywordsGuava));
        k.add(new Kernel("KadTextBench.idHexFormatString", 1, "ns/op", t::idHexFormatString));
        k.add(new Kernel("KadTextBench.idHexFormatJdk", 1, "ns/op", t::idHexFormatJdk));
        k.add(new Kernel("KadTextBench.socketAddressViaString", 1, "ns/op", t::socketAddressViaString));
        k.add(new Kernel("KadTextBench.socketAddressGuava", 1, "ns/op", t::socketAddressGuava));

        LittleEndianBench e = new LittleEndianBench();
        e.setUp();
        int values = 64 * 1024 / 4 - 1;
        k.add(new Kernel("LittleEndianBench.readShifts", values, "ns/op", e::readShifts));
        k.add(new Kernel("LittleEndianBench.readVarHandle", values, "ns/op", e::readVarHandle));
        k.add(new Kernel("LittleEndianBench.readByteBuffer", values, "ns/op", e::readByteBuffer));
        k.add(new Kernel("LittleEndianBench.writeShifts", values, "ns/op", e::writeShifts));
        k.add(new Kernel("LittleEndianBench.writeVarHandle", values, "ns/op", e::writeVarHandle));

        PartPickBench p = new PartPickBench();
        p.setUp();
        k.add(new Kernel("PartPickBench.copyAndRemoveAll", 1, "us/op", p::copyAndRemoveAll));
        k.add(new Kernel("PartPickBench.enclosesAll", 1, "us/op", p::enclosesAll));

        for (String kind : new String[] {"plain", "special"}) {
            HtmlEscapeBench x = new HtmlEscapeBench();
            x.kind = kind;
            k.add(new Kernel("HtmlEscapeBench.handWritten:" + kind, 1, "ns/op", x::handWritten));
            k.add(new Kernel("HtmlEscapeBench.guavaHtmlEscaper:" + kind, 1, "ns/op", x::guavaHtmlEscaper));
        }

        String filter = args.length > 1 ? args[1] : "";
        for (Kernel kernel : k) {
            if (kernel.name().contains(filter)) {
                System.out.printf("%-58s %12.3f %s%n", kernel.name(), measure(kernel, seconds), kernel.unit());
            }
        }
    }

    // Median of five rounds after one round of warm-up; calls are batched so the clock costs little.
    static double measure(Kernel kernel, double seconds) throws Exception {
        long batch = 1;
        while (true) {
            long t0 = System.nanoTime();
            for (long i = 0; i < batch; i++) {
                sink = kernel.op().run();
            }
            if (System.nanoTime() - t0 > 10_000_000L) {
                break;
            }
            batch *= 2;
        }
        double[] rates = new double[6];
        for (int round = 0; round < rates.length; round++) {
            long calls = 0;
            long start = System.nanoTime();
            long end = start + (long) (seconds * 1e9);
            long now;
            do {
                for (long i = 0; i < batch; i++) {
                    sink = kernel.op().run();
                }
                calls += batch;
                now = System.nanoTime();
            } while (now < end);
            rates[round] = calls / ((now - start) / 1e9);
        }
        double[] measured = Arrays.copyOfRange(rates, 1, rates.length);
        Arrays.sort(measured);
        double callsPerSecond = measured[measured.length / 2];
        return switch (kernel.unit()) {
            case "MiB/s", "ops/s" -> callsPerSecond * kernel.unitsPerCall();
            case "ns/op" -> 1e9 / callsPerSecond / kernel.unitsPerCall();
            case "us/op" -> 1e6 / callsPerSecond / kernel.unitsPerCall();
            default -> 1e3 / callsPerSecond / kernel.unitsPerCall();
        };
    }
}
