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

import com.google.common.io.BaseEncoding;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;

/** An immutable 20-byte SHA-1 value, used for AICH hashes (written in Base32 in links). */
public final class Hash20 implements Comparable<Hash20> {

    public static final int LENGTH = 20;

    private static final HexFormat HEX = HexFormat.of().withUpperCase();
    // RFC 4648; 20 bytes make exactly 32 characters, so padding marks a shorter value and is refused.
    private static final BaseEncoding BASE32 =
            BaseEncoding.base32().omitPadding().ignoreCase();

    private final byte[] bytes;

    private Hash20(byte[] bytes) {
        this.bytes = bytes;
    }

    public static Hash20 of(byte[] bytes) {
        if (bytes.length != LENGTH) {
            throw new IllegalArgumentException("a Hash20 needs 20 bytes, got " + bytes.length);
        }
        return new Hash20(bytes.clone());
    }

    public static Hash20 of(byte[] source, int offset) {
        Objects.checkFromIndexSize(offset, LENGTH, source.length);
        return new Hash20(Arrays.copyOfRange(source, offset, offset + LENGTH));
    }

    /** Parses 32 Base32 characters (RFC 4648 alphabet, either case). */
    public static Hash20 parseBase32(CharSequence base32) {
        if (base32.length() != 32) {
            throw new IllegalArgumentException("an AICH hash needs 32 Base32 characters, got " + base32.length());
        }
        return new Hash20(BASE32.decode(base32));
    }

    public byte[] toByteArray() {
        return this.bytes.clone();
    }

    public void copyTo(byte[] target, int offset) {
        System.arraycopy(this.bytes, 0, target, offset, LENGTH);
    }

    public String toBase32() {
        return BASE32.encode(this.bytes);
    }

    public String toHex() {
        return HEX.formatHex(this.bytes);
    }

    @Override
    public int compareTo(Hash20 other) {
        return Arrays.compareUnsigned(this.bytes, other.bytes);
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof Hash20 other && Arrays.equals(this.bytes, other.bytes);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(this.bytes);
    }

    @Override
    public String toString() {
        return this.toBase32();
    }
}
