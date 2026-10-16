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

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;

/**
 * Little-endian values in byte arrays, the byte order of the eD2K and Kad formats, at any offset, through byte-array
 * views. The native image does not inline these calls: each costs about a nanosecond more than shifts written in place,
 * so loops over every byte, such as MD4's, keep their own view (njmule-bench LittleEndianBench).
 */
public final class LittleEndian {

    private static final VarHandle SHORT = MethodHandles.byteArrayViewVarHandle(short[].class, ByteOrder.LITTLE_ENDIAN);
    private static final VarHandle INT = MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.LITTLE_ENDIAN);
    private static final VarHandle LONG = MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);

    private LittleEndian() {}

    public static int getInt(byte[] bytes, int at) {
        return (int) INT.get(bytes, at);
    }

    /** Writes the low 16 bits of {@code value}. */
    public static void putShort(byte[] bytes, int at, int value) {
        SHORT.set(bytes, at, (short) value);
    }

    public static void putInt(byte[] bytes, int at, int value) {
        INT.set(bytes, at, value);
    }

    public static void putLong(byte[] bytes, int at, long value) {
        LONG.set(bytes, at, value);
    }

    /** The 4 bytes of {@code value}. */
    public static byte[] ofInt(int value) {
        byte[] out = new byte[4];
        putInt(out, 0, value);
        return out;
    }
}
