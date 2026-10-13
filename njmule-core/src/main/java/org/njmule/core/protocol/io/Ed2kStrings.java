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
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
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

    public static String decode(byte[] raw, int offset, int length) {
        if (length >= 3 && raw[offset] == BOM[0] && raw[offset + 1] == BOM[1] && raw[offset + 2] == BOM[2]) {
            String utf8 = strictUtf8(raw, offset + 3, length - 3);
            return utf8 != null ? utf8 : latin1(raw, offset + 3, length - 3);
        }
        String utf8 = strictUtf8(raw, offset, length);
        return utf8 != null ? utf8 : cp1252(raw, offset, length);
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

    private static String strictUtf8(byte[] raw, int offset, int length) {
        try {
            CharBuffer chars = StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(raw, offset, length));
            return chars.toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }

    private static String latin1(byte[] raw, int offset, int length) {
        return new String(raw, offset, length, StandardCharsets.ISO_8859_1);
    }

    private static String cp1252(byte[] raw, int offset, int length) {
        return new String(raw, offset, length, WINDOWS_1252);
    }
}
