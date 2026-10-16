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

import java.security.SecureRandom;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.njmule.core.api.Hash16;
import org.njmule.core.protocol.crypt.DatagramObfuscation;
import org.njmule.core.protocol.io.DecodeException;
import org.njmule.core.protocol.packet.Packet;
import org.njmule.core.protocol.packet.Protocol;
import org.njmule.core.protocol.packet.UdpFraming;
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

/** Obfuscated UDP datagrams of 120 bytes as the Kad socket receives and sends them. */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class DatagramDecodeBench {

    private static final Hash16 USER_HASH =
            Hash16.of(new byte[] {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16});
    private static final Hash16 KAD_ID = Hash16.of(new byte[] {9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9});
    private static final byte[] SENDER_IP = {81, 2, (byte) 133, 47};
    private static final int VERIFY_KEY = 0x1234_5678;

    private final SecureRandom random = new SecureRandom();
    private byte[] plain;
    private byte[] byUserHash;
    private byte[] byVerifyKey;

    @Setup
    public void setUp() {
        this.plain = UdpFraming.encode(Packet.of(Protocol.KAD, 0x21, new byte[118]));
        this.byUserHash = DatagramObfuscation.encryptForClient(this.plain, USER_HASH, SENDER_IP, this.random);
        this.byVerifyKey =
                DatagramObfuscation.encryptForKad(this.plain, Optional.empty(), VERIFY_KEY, 0x0BAD_F00D, this.random);
    }

    /** One key attempt: the marker points at the user hash first. */
    @Benchmark
    public DatagramObfuscation.Received decodeKeyedByTheUserHash() throws DecodeException {
        return decode(this.byUserHash);
    }

    /** The marker points at the verify key first. */
    @Benchmark
    public DatagramObfuscation.Received decodeKeyedByTheVerifyKey() throws DecodeException {
        return decode(this.byVerifyKey);
    }

    @Benchmark
    public byte[] encryptForKad() {
        return DatagramObfuscation.encryptForKad(this.plain, Optional.of(KAD_ID), VERIFY_KEY, 1, this.random);
    }

    private static DatagramObfuscation.Received decode(byte[] datagram) throws DecodeException {
        return DatagramObfuscation.decodeFromClient(
                datagram, 0, datagram.length, USER_HASH, SENDER_IP, Optional.of(KAD_ID), VERIFY_KEY);
    }
}
