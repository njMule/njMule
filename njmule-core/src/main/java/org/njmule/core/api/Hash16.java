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
package org.njmule.core.api;

import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;

/** An immutable 16-byte identifier: eD2K file hash (MD4), user hash or Kad key bytes. */
public final class Hash16 implements Comparable<Hash16> {

    public static final int LENGTH = 16;
    public static final Hash16 ZERO = new Hash16(new byte[LENGTH]);

    private static final HexFormat HEX = HexFormat.of().withUpperCase();

    private final byte[] bytes;

    private Hash16(byte[] bytes) {
        this.bytes = bytes;
    }

    public static Hash16 of(byte[] bytes) {
        if (bytes.length != LENGTH) {
            throw new IllegalArgumentException("a Hash16 needs 16 bytes, got " + bytes.length);
        }
        return new Hash16(bytes.clone());
    }

    public static Hash16 of(byte[] source, int offset) {
        Objects.checkFromIndexSize(offset, LENGTH, source.length);
        return new Hash16(Arrays.copyOfRange(source, offset, offset + LENGTH));
    }

    /** Parses 32 hexadecimal digits, in either case. */
    public static Hash16 parseHex(CharSequence hex) {
        if (hex.length() != 2 * LENGTH) {
            throw new IllegalArgumentException("a Hash16 needs 32 hex digits, got " + hex.length());
        }
        return new Hash16(HEX.parseHex(hex));
    }

    public byte[] toByteArray() {
        return this.bytes.clone();
    }

    public void copyTo(byte[] target, int offset) {
        System.arraycopy(this.bytes, 0, target, offset, LENGTH);
    }

    public byte get(int index) {
        return this.bytes[index];
    }

    public boolean isZero() {
        return Arrays.equals(this.bytes, ZERO.bytes);
    }

    /** Upper-case hexadecimal, as in eD2K links. */
    public String toHex() {
        return HEX.formatHex(this.bytes);
    }

    @Override
    public int compareTo(Hash16 other) {
        return Arrays.compareUnsigned(this.bytes, other.bytes);
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof Hash16 other && Arrays.equals(this.bytes, other.bytes);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(this.bytes);
    }

    @Override
    public String toString() {
        return toHex();
    }
}
