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
package org.njmule.core.upnp;

import java.io.IOException;
import java.io.Serial;
import java.net.Inet6Address;
import java.util.OptionalInt;

/** A router that forwards our ports: one found with UPnP, or one that answers NAT-PMP or PCP. */
public interface Gateway extends AutoCloseable {

    /** A router's refusal of a mapping, with its reason. */
    class Refused extends IOException {
        @Serial
        private static final long serialVersionUID = 1L;

        Refused(String reason) {
            super(reason);
        }
    }

    /** The protocol it speaks, as the log names it: "UPnP", "NAT-PMP" or "PCP". */
    String protocol();

    /** Its host, as the reports name it. */
    String host();

    /**
     * Forwards {@code port} of {@code protocol} ("TCP" or "UDP") to our address towards it, for {@code lease} seconds
     * (0: until removed); returns the seconds it granted, 0 for until removed.
     *
     * @throws Refused when the router will not
     */
    long addMapping(String protocol, int port, String description, long lease) throws IOException;

    /** Removes the forwarding of {@code port}. */
    void deleteMapping(String protocol, int port) throws IOException;

    /**
     * Opens the router's IPv6 firewall to {@code port} of {@code protocol} at {@code address} for {@code lease}
     * seconds; returns the pinhole's id, empty when the router has no IPv6 firewall to open.
     *
     * @throws Refused when the router will not
     */
    default OptionalInt openPinhole(String protocol, Inet6Address address, int port, long lease) throws IOException {
        return OptionalInt.empty();
    }

    /** Keeps the pinhole {@code id} open for {@code lease} seconds more. */
    default void renewPinhole(int id, long lease) throws IOException {
        throw new IOException("no pinholes");
    }

    /** Closes the pinhole {@code id}. */
    default void closePinhole(int id) throws IOException {
        throw new IOException("no pinholes");
    }

    @Override
    void close();
}
