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

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.SecureRandom;
import org.njmule.core.api.Hash16;
import org.njmule.core.protocol.io.DecodeException;
import org.njmule.core.protocol.io.PacketReader;
import org.njmule.core.protocol.io.PacketWriter;

/**
 * A 128-bit Kad id or key, compared and XORed as an unsigned number. Bit 0 is the most significant. On the wire and in
 * files it is four little-endian 32-bit words, most significant word first; file hashes map to keys big-endian
 * ({@link #fromBigEndian}).
 */
public record UInt128(long high, long low) implements Comparable<UInt128> {

    public static final UInt128 ZERO = new UInt128(0, 0);
    public static final int BITS = 128;

    public static UInt128 random(SecureRandom random) {
        return new UInt128(random.nextLong(), random.nextLong());
    }

    public static UInt128 read(PacketReader in) throws DecodeException {
        long w0 = in.u32();
        long w1 = in.u32();
        long w2 = in.u32();
        long w3 = in.u32();
        return new UInt128(w0 << 32 | w1, w2 << 32 | w3);
    }

    public void write(PacketWriter out) {
        out.u32(this.high >>> 32).u32(this.high).u32(this.low >>> 32).u32(this.low);
    }

    /** The wire layout as 16 bytes (as used for obfuscation keys). */
    public byte[] toWireBytes() {
        return ByteBuffer.allocate(16)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt((int) (this.high >>> 32))
                .putInt((int) this.high)
                .putInt((int) (this.low >>> 32))
                .putInt((int) this.low)
                .array();
    }

    public static UInt128 fromWireBytes(byte[] bytes) throws DecodeException {
        if (bytes.length != 16) {
            throw new DecodeException("a 128-bit id has 16 bytes");
        }
        return read(PacketReader.of(bytes));
    }

    /** The key of a hash: its 16 bytes read as a big-endian number. */
    public static UInt128 fromBigEndian(byte[] bytes) {
        if (bytes.length != 16) {
            throw new IllegalArgumentException("a 128-bit id has 16 bytes");
        }
        long h = 0;
        long l = 0;
        for (int i = 0; i < 8; i++) {
            h = h << 8 | (bytes[i] & 0xFF);
            l = l << 8 | (bytes[8 + i] & 0xFF);
        }
        return new UInt128(h, l);
    }

    public static UInt128 of(Hash16 hash) {
        return fromBigEndian(hash.toByteArray());
    }

    public byte[] toBigEndian() {
        byte[] out = new byte[16];
        for (int i = 0; i < 8; i++) {
            out[i] = (byte) (this.high >>> (56 - 8 * i));
            out[8 + i] = (byte) (this.low >>> (56 - 8 * i));
        }
        return out;
    }

    public Hash16 toHash16() {
        return Hash16.of(toBigEndian());
    }

    public UInt128 xor(UInt128 other) {
        return new UInt128(this.high ^ other.high, this.low ^ other.low);
    }

    /** Bit {@code index}, 0 being the most significant. */
    public int bit(int index) {
        if (index < 0 || index >= BITS) {
            throw new IndexOutOfBoundsException("bit " + index);
        }
        return index < 64 ? (int) (this.high >>> (63 - index)) & 1 : (int) (this.low >>> (127 - index)) & 1;
    }

    /** The most significant 32 bits, used for the Kad search tolerance. */
    public long top32() {
        return this.high >>> 32;
    }

    public boolean isZero() {
        return this.high == 0 && this.low == 0;
    }

    @Override
    public int compareTo(UInt128 other) {
        int c = Long.compareUnsigned(this.high, other.high);
        return c != 0 ? c : Long.compareUnsigned(this.low, other.low);
    }

    /** 32 hex digits, most significant first. */
    public String toHex() {
        return String.format("%016X%016X", this.high, this.low);
    }

    public static UInt128 parseHex(String hex) {
        if (hex.length() != 32) {
            throw new IllegalArgumentException("a 128-bit id has 32 hex digits");
        }
        return new UInt128(
                Long.parseUnsignedLong(hex.substring(0, 16), 16), Long.parseUnsignedLong(hex.substring(16), 16));
    }

    @Override
    public String toString() {
        return toHex();
    }
}
