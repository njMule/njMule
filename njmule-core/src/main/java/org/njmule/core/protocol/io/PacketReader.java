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

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.njmule.core.api.Hash16;
import org.njmule.core.api.Hash20;

/**
 * Little-endian reader over an in-memory buffer. Every read is bounds-checked first, so a length
 * field can never make it allocate more than the data actually received.
 */
public final class PacketReader {

    private final ByteBuffer buffer;

    private PacketReader(ByteBuffer buffer) {
        this.buffer = buffer.slice().order(ByteOrder.LITTLE_ENDIAN);
    }

    public static PacketReader of(byte[] data) {
        return new PacketReader(ByteBuffer.wrap(data));
    }

    public static PacketReader of(byte[] data, int offset, int length) {
        return new PacketReader(ByteBuffer.wrap(data, offset, length));
    }

    /** Reads the remaining bytes of {@code buffer} without changing its position. */
    public static PacketReader of(ByteBuffer buffer) {
        return new PacketReader(buffer.duplicate());
    }

    public int position() {
        return this.buffer.position();
    }

    public int remaining() {
        return this.buffer.remaining();
    }

    public boolean hasRemaining() {
        return this.buffer.hasRemaining();
    }

    public int u8() throws DecodeException {
        this.need(1);
        return this.buffer.get() & 0xFF;
    }

    public int u16() throws DecodeException {
        this.need(2);
        return this.buffer.getShort() & 0xFFFF;
    }

    public long u32() throws DecodeException {
        this.need(4);
        return this.buffer.getInt() & 0xFFFF_FFFFL;
    }

    /** The raw 32 bits, for values compared as bit patterns (magic numbers, IP addresses). */
    public int i32() throws DecodeException {
        this.need(4);
        return this.buffer.getInt();
    }

    /** The raw 64 bits; callers range-check values that must not exceed {@code Long.MAX_VALUE}. */
    public long u64() throws DecodeException {
        this.need(8);
        return this.buffer.getLong();
    }

    public float f32() throws DecodeException {
        this.need(4);
        return this.buffer.getFloat();
    }

    public byte[] bytes(int length) throws DecodeException {
        if (length < 0) {
            throw new DecodeException("negative length " + length);
        }
        this.need(length);
        byte[] out = new byte[length];
        this.buffer.get(out);
        return out;
    }

    public Hash16 hash16() throws DecodeException {
        return Hash16.of(this.bytes(Hash16.LENGTH));
    }

    public Hash20 hash20() throws DecodeException {
        return Hash20.of(this.bytes(Hash20.LENGTH));
    }

    public void skip(int length) throws DecodeException {
        if (length < 0) {
            throw new DecodeException("negative length " + length);
        }
        this.need(length);
        this.buffer.position(this.buffer.position() + length);
    }

    /** A reader over the next {@code length} bytes, which this reader then skips. */
    public PacketReader slice(int length) throws DecodeException {
        this.need(length);
        ByteBuffer view = this.buffer.slice(this.buffer.position(), length);
        this.buffer.position(this.buffer.position() + length);
        return new PacketReader(view);
    }

    public byte[] rest() {
        byte[] out = new byte[this.buffer.remaining()];
        this.buffer.get(out);
        return out;
    }

    /**
     * A {@code <u16 length><bytes>} string decoded with the network's rules ({@link Ed2kStrings}).
     */
    public String string16() throws DecodeException {
        return Ed2kStrings.decode(this.bytes(this.u16()));
    }

    /** Checks that a count field is plausible for the bytes left (each item ≥ {@code minSize}). */
    public int count(long count, int minItemSize, int maxCount) throws DecodeException {
        if (count < 0 || count > maxCount) {
            throw new DecodeException("count " + count + " exceeds the limit of " + maxCount);
        }
        if (minItemSize > 0 && count > this.buffer.remaining() / minItemSize) {
            throw new DecodeException(
                    "count " + count + " does not fit in " + this.buffer.remaining() + " bytes");
        }
        return (int) count;
    }

    public void expectEnd() throws DecodeException {
        if (this.buffer.hasRemaining()) {
            throw new DecodeException(this.buffer.remaining() + " unexpected trailing bytes");
        }
    }

    private void need(int length) throws DecodeException {
        if (length > this.buffer.remaining()) {
            throw new DecodeException(
                    "truncated data: need "
                            + length
                            + " bytes at offset "
                            + this.buffer.position()
                            + ", "
                            + this.buffer.remaining()
                            + " left");
        }
    }
}
