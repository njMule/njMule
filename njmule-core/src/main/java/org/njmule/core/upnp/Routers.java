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

import java.net.InetSocketAddress;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Where the router that forwards our ports is looked for: UPnP's search address, then the NAT-PMP and PCP port of the
 * default gateway, read again at each search; and the PCP port of the IPv6 default gateway, which may open its IPv6
 * firewall when UPnP does not.
 */
public record Routers(
        InetSocketAddress ssdp,
        Supplier<Optional<InetSocketAddress>> natPmp,
        Supplier<Optional<InetSocketAddress>> pcpIpv6) {

    /** This computer's: SSDP's multicast address, and port 5351 of the default gateways. */
    public static final Routers OF_THIS_COMPUTER = new Routers(
            Igd.SSDP,
            () -> DefaultGateway.find().map(gateway -> new InetSocketAddress(gateway, NatPmp.PORT)),
            () -> DefaultGateway.findIpv6().map(gateway -> new InetSocketAddress(gateway, NatPmp.PORT)));

    public Routers {
        Objects.requireNonNull(ssdp, "ssdp");
        Objects.requireNonNull(natPmp, "natPmp");
        Objects.requireNonNull(pcpIpv6, "pcpIpv6");
    }

    /** Without an IPv6 gateway to ask. */
    public Routers(InetSocketAddress ssdp, Supplier<Optional<InetSocketAddress>> natPmp) {
        this(ssdp, natPmp, Optional::empty);
    }

    /** UPnP's search at {@code ssdp} only. */
    public static Routers upnpOnly(InetSocketAddress ssdp) {
        return new Routers(ssdp, Optional::empty);
    }
}
