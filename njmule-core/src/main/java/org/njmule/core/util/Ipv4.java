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

/**
 * IPv4 addresses as {@code int}s holding {@code a.b.c.d} as {@code a<<24 | b<<16 | c<<8 | d}. Kad
 * files store this value little-endian; eD2K files store the four bytes in network order, which
 * read as a little-endian u32 gives the byte-swapped value ({@link #fromEd2k}).
 */
public final class Ipv4 {

    private Ipv4() {}

    public static int of(int a, int b, int c, int d) {
        return (a & 0xFF) << 24 | (b & 0xFF) << 16 | (c & 0xFF) << 8 | (d & 0xFF);
    }

    public static int fromBytes(byte[] bytes) {
        if (bytes.length != 4) {
            throw new IllegalArgumentException("IPv4 addresses have 4 bytes");
        }
        return of(bytes[0], bytes[1], bytes[2], bytes[3]);
    }

    public static byte[] toBytes(int ip) {
        return new byte[] {(byte) (ip >>> 24), (byte) (ip >>> 16), (byte) (ip >>> 8), (byte) ip};
    }

    /** The address from an eD2K field read as a little-endian u32. */
    public static int fromEd2k(long littleEndianValue) {
        return Integer.reverseBytes((int) littleEndianValue);
    }

    /** The little-endian u32 to write for an eD2K field. */
    public static long toEd2k(int ip) {
        return Integer.reverseBytes(ip) & 0xFFFF_FFFFL;
    }

    public static String format(int ip) {
        return (ip >>> 24) + "." + (ip >>> 16 & 0xFF) + "." + (ip >>> 8 & 0xFF) + "." + (ip & 0xFF);
    }

    /** Parses dotted decimal strictly (four parts, 0-255, no leading signs). */
    public static int parse(String text) {
        String[] parts = text.split("\\.", -1);
        if (parts.length != 4) {
            throw new IllegalArgumentException("not an IPv4 address: " + text);
        }
        int ip = 0;
        for (String part : parts) {
            if (part.isEmpty()
                    || part.length() > 3
                    || !part.chars().allMatch(c -> c >= '0' && c <= '9')) {
                throw new IllegalArgumentException("not an IPv4 address: " + text);
            }
            int value = Integer.parseInt(part);
            if (value > 255) {
                throw new IllegalArgumentException("not an IPv4 address: " + text);
            }
            ip = ip << 8 | value;
        }
        return ip;
    }

    /** Usable as a peer address: not 0.x, loopback, multicast, broadcast or reserved. */
    public static boolean isUnicastPublicCandidate(int ip) {
        int a = ip >>> 24;
        return a != 0 && a != 127 && a < 224;
    }
}
