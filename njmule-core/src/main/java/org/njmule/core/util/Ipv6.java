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

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Optional;

/** IPv6 addresses as the peers who hold them: one home or host gets a whole /64 and picks any address in it. */
public final class Ipv6 {

    private Ipv6() {}

    /**
     * Whether peers on the Internet may reach {@code address}: global unicast IPv6, but no 6to4 or Teredo address,
     * which need relays.
     */
    public static boolean isPublic(InetAddress address) {
        if (!(address instanceof Inet6Address)) {
            return false;
        }
        byte[] bytes = address.getAddress();
        int first = (bytes[0] & 0xFF) << 8 | (bytes[1] & 0xFF);
        int second = (bytes[2] & 0xFF) << 8 | (bytes[3] & 0xFF);
        boolean global = (bytes[0] & 0xE0) == 0x20;
        boolean sixToFour = first == 0x2002;
        boolean teredo = first == 0x2001 && second == 0;
        return global && !sixToFour && !teredo;
    }

    /** The 16 bytes as an IPv6 address, if peers on the Internet may reach it. */
    public static Optional<Inet6Address> publicOf(byte[] bytes) {
        try {
            return bytes.length == 16
                            && InetAddress.getByAddress(bytes) instanceof Inet6Address address
                            && isPublic(address)
                    ? Optional.of(address)
                    : Optional.empty();
        } catch (UnknownHostException e) {
            return Optional.empty();
        }
    }

    /** The /64 network of an IPv6 address, which bans and limits count as one address; IPv4 addresses as they are. */
    public static InetAddress network(InetAddress address) {
        if (!(address instanceof Inet6Address)) {
            return address;
        }
        byte[] bytes = address.getAddress();
        Arrays.fill(bytes, 8, 16, (byte) 0);
        try {
            return InetAddress.getByAddress(bytes);
        } catch (UnknownHostException e) {
            throw new IllegalStateException(e);
        }
    }
}
