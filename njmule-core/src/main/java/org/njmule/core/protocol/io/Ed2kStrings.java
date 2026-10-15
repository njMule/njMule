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

import com.google.common.base.Utf8;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * String encoding on the eD2K network and in its data files: a UTF-8 BOM marks UTF-8, valid UTF-8 is taken as such,
 * anything else is the Windows ANSI code page (Windows-1252).
 */
public final class Ed2kStrings {

    private static final byte[] BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    // Present in native images because the class is initialised at build time
    // (META-INF/native-image/org.njmule/njmule-core/native-image.properties).
    private static final Charset WINDOWS_1252 = Charset.forName("windows-1252");

    private Ed2kStrings() {}

    public static String decode(byte[] raw) {
        return decode(raw, 0, raw.length);
    }

    // Guava's check rejects what a strict decoder does (overlong forms, surrogates, beyond U+10FFFF), without the
    // exception a decoder throws for every string that is not UTF-8.
    public static String decode(byte[] raw, int offset, int length) {
        int at = offset;
        int n = length;
        if (n >= 3 && raw[at] == BOM[0] && raw[at + 1] == BOM[1] && raw[at + 2] == BOM[2]) {
            at += 3;
            n -= 3;
        }
        return Utf8.isWellFormed(raw, at, n)
                ? new String(raw, at, n, StandardCharsets.UTF_8)
                : new String(raw, at, n, WINDOWS_1252);
    }

    public static byte[] encode(String value, StringEncoding encoding) {
        return switch (encoding) {
            case UTF8 -> value.getBytes(StandardCharsets.UTF_8);
            case UTF8_BOM_IF_NEEDED -> isAscii(value) ? value.getBytes(StandardCharsets.US_ASCII) : withBom(value);
        };
    }

    private static byte[] withBom(String value) {
        byte[] utf8 = value.getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[BOM.length + utf8.length];
        System.arraycopy(BOM, 0, out, 0, BOM.length);
        System.arraycopy(utf8, 0, out, BOM.length, utf8.length);
        return out;
    }

    private static boolean isAscii(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) >= 0x80) {
                return false;
            }
        }
        return true;
    }
}
