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
package org.njmule.core.protocol.io;

import java.util.Arrays;
import org.njmule.core.api.Hash16;
import org.njmule.core.api.Hash20;

/** Little-endian writer into a growable buffer, with an overall size cap. */
public final class PacketWriter {

    private static final int DEFAULT_CAPACITY = 64;

    private final int maxSize;
    private byte[] data;
    private int size;

    public PacketWriter() {
        this(Integer.MAX_VALUE - 8);
    }

    public PacketWriter(int maxSize) {
        this.maxSize = maxSize;
        this.data = new byte[Math.min(DEFAULT_CAPACITY, maxSize)];
    }

    public int size() {
        return this.size;
    }

    public PacketWriter u8(int value) {
        this.ensure(1);
        this.data[this.size++] = (byte) value;
        return this;
    }

    public PacketWriter u16(int value) {
        this.ensure(2);
        this.put16(this.size, value);
        this.size += 2;
        return this;
    }

    public PacketWriter u32(long value) {
        this.ensure(4);
        this.put32(this.size, (int) value);
        this.size += 4;
        return this;
    }

    public PacketWriter u64(long value) {
        this.ensure(8);
        this.put32(this.size, (int) value);
        this.put32(this.size + 4, (int) (value >>> 32));
        this.size += 8;
        return this;
    }

    public PacketWriter f32(float value) {
        return this.u32(Float.floatToRawIntBits(value));
    }

    public PacketWriter bytes(byte[] value) {
        return this.bytes(value, 0, value.length);
    }

    public PacketWriter bytes(byte[] value, int offset, int length) {
        this.ensure(length);
        System.arraycopy(value, offset, this.data, this.size, length);
        this.size += length;
        return this;
    }

    public PacketWriter hash16(Hash16 hash) {
        this.ensure(Hash16.LENGTH);
        hash.copyTo(this.data, this.size);
        this.size += Hash16.LENGTH;
        return this;
    }

    public PacketWriter hash20(Hash20 hash) {
        this.ensure(Hash20.LENGTH);
        hash.copyTo(this.data, this.size);
        this.size += Hash20.LENGTH;
        return this;
    }

    /** A {@code <u16 length><bytes>} string. */
    public PacketWriter string16(String value, StringEncoding encoding) {
        byte[] raw = EmuleStrings.encode(value, encoding);
        if (raw.length > 0xFFFF) {
            throw new IllegalArgumentException("string too long: " + raw.length + " bytes");
        }
        return this.u16(raw.length).bytes(raw);
    }

    /** Overwrites a previously written u16, e.g. a count only known at the end. */
    public void setU16(int position, int value) {
        this.checkPatch(position, 2);
        this.put16(position, value);
    }

    /** Overwrites a previously written u32. */
    public void setU32(int position, long value) {
        this.checkPatch(position, 4);
        this.put32(position, (int) value);
    }

    public void setU8(int position, int value) {
        this.checkPatch(position, 1);
        this.data[position] = (byte) value;
    }

    public byte[] toByteArray() {
        return Arrays.copyOf(this.data, this.size);
    }

    private void put16(int at, int value) {
        this.data[at] = (byte) value;
        this.data[at + 1] = (byte) (value >>> 8);
    }

    private void put32(int at, int value) {
        this.data[at] = (byte) value;
        this.data[at + 1] = (byte) (value >>> 8);
        this.data[at + 2] = (byte) (value >>> 16);
        this.data[at + 3] = (byte) (value >>> 24);
    }

    private void checkPatch(int position, int length) {
        if (position < 0 || position > this.size - length) {
            throw new IndexOutOfBoundsException("position " + position + " of " + this.size);
        }
    }

    private void ensure(int extra) {
        if (extra > this.maxSize - this.size) {
            throw new IllegalStateException(
                    "packet would exceed "
                            + this.maxSize
                            + " bytes ("
                            + this.size
                            + " + "
                            + extra
                            + ")");
        }
        int needed = this.size + extra;
        if (needed > this.data.length) {
            int grown = (int) Math.min(this.maxSize, Math.max(needed, 2L * this.data.length));
            this.data = Arrays.copyOf(this.data, grown);
        }
    }
}
