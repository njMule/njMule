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
package org.njmule.core.util;

import java.util.Arrays;
import java.util.HexFormat;

/** An immutable byte sequence with value semantics (for records and maps). */
public final class Bytes {

    public static final Bytes EMPTY = new Bytes(new byte[0]);

    private static final int TO_STRING_LIMIT = 32;

    private final byte[] data;

    private Bytes(byte[] data) {
        this.data = data;
    }

    public static Bytes of(byte[] data) {
        return data.length == 0 ? EMPTY : new Bytes(data.clone());
    }

    public static Bytes of(byte[] data, int offset, int length) {
        return length == 0 ? EMPTY : new Bytes(Arrays.copyOfRange(data, offset, Math.addExact(offset, length)));
    }

    public int length() {
        return this.data.length;
    }

    public byte get(int index) {
        return this.data[index];
    }

    public byte[] toByteArray() {
        return this.data.clone();
    }

    public void copyTo(byte[] target, int offset) {
        System.arraycopy(this.data, 0, target, offset, this.data.length);
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof Bytes other && Arrays.equals(this.data, other.data);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(this.data);
    }

    @Override
    public String toString() {
        HexFormat hex = HexFormat.of();
        return this.data.length <= TO_STRING_LIMIT
                ? hex.formatHex(this.data)
                : hex.formatHex(this.data, 0, TO_STRING_LIMIT) + "…(" + this.data.length + " bytes)";
    }
}
