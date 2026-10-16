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

import java.util.Objects;

/**
 * What a test of our ports found, as far as it went: whether the router forwards them, and whether others reach them.
 *
 * @param tcpPort the port clients and servers connect to
 * @param udpPort the port Kad listens on, 0 when Kad does not run
 * @param router the host of the router that answered UPnP, empty when none did
 * @param done whether the test is over; what it could not tell by then is {@link Reach#UNKNOWN}
 */
public record PortTest(
        int tcpPort, int udpPort, Forwarding forwarding, String router, Reach tcp, Reach udp, boolean done) {

    /** Whether the router forwards our ports, as asked through UPnP. */
    public enum Forwarding {
        /** UPnP is off. */
        OFF,
        /** The router is being asked. */
        ASKING,
        FORWARDED,
        /** Some ports are forwarded, the router refused the others. */
        PARTLY,
        REFUSED,
        NO_ROUTER,
        /** The router stopped answering, or answered what cannot be read. */
        FAILED
    }

    /** Whether others reach one of our ports. */
    public enum Reach {
        TESTING,
        OPEN,
        CLOSED,
        /** Nothing could tell: no server took us and no Kad node tested it in time. */
        UNKNOWN
    }

    public PortTest {
        Objects.requireNonNull(forwarding, "forwarding");
        Objects.requireNonNull(router, "router");
        Objects.requireNonNull(tcp, "tcp");
        Objects.requireNonNull(udp, "udp");
    }
}
