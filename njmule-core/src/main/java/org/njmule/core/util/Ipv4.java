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

import com.google.common.net.InetAddresses;
import com.google.common.primitives.Ints;
import java.net.Inet4Address;
import java.net.InetSocketAddress;

/**
 * IPv4 addresses as {@code int}s holding {@code a.b.c.d} as {@code a<<24 | b<<16 | c<<8 | d}. Kad files store this
 * value little-endian; eD2K files store the four bytes in network order, which read as a little-endian u32 gives the
 * byte-swapped value ({@link #fromEd2k}).
 */
public final class Ipv4 {

    private Ipv4() {}

    public static int of(int a, int b, int c, int d) {
        return Ints.fromBytes((byte) a, (byte) b, (byte) c, (byte) d);
    }

    public static int fromBytes(byte[] bytes) {
        if (bytes.length != 4) {
            throw new IllegalArgumentException("IPv4 addresses have 4 bytes");
        }
        return Ints.fromByteArray(bytes);
    }

    public static byte[] toBytes(int ip) {
        return Ints.toByteArray(ip);
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
        return InetAddresses.fromInteger(ip).getHostAddress();
    }

    /** The socket address of {@code ip}, made without a round trip through its text. */
    public static InetSocketAddress socketAddress(int ip, int port) {
        return new InetSocketAddress(InetAddresses.fromInteger(ip), port);
    }

    /** Parses an IPv4 literal without any name lookup. */
    public static int parse(String text) {
        return fromBytes(Inet4Address.ofLiteral(text).getAddress());
    }

    /** A private address (RFC 1918) or one of the "this network" block, 0.x. */
    public static boolean isLan(int ip) {
        int a = ip >>> 24;
        int b = (ip >>> 16) & 0xFF;
        return a == 0 || a == 10 || (a == 172 && b >= 16 && b < 32) || (a == 192 && b == 168);
    }

    /** Usable as a peer address: not 0.x, loopback, multicast, broadcast or reserved. */
    public static boolean isUnicastPublicCandidate(int ip) {
        int a = ip >>> 24;
        return a != 0 && a != 127 && a < 224;
    }
}
