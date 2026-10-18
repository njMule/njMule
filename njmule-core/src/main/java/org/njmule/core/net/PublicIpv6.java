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
package org.njmule.core.net;

import com.google.common.net.InetAddresses;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.DatagramSocket;
import java.net.Inet6Address;
import java.net.InetSocketAddress;
import java.util.Optional;
import org.njmule.core.config.BindInterface;
import org.njmule.core.util.Ipv6;

/**
 * Our IPv6 address on the Internet: the one the system sends from, which no NAT translates, so that peers can reach it
 * there.
 */
public final class PublicIpv6 {

    // Any address on the IPv6 Internet does: only the route towards it is looked up, nothing is ever sent there.
    private static final InetSocketAddress INTERNET =
            new InetSocketAddress(InetAddresses.forString("2001:4860:4860::8888"), 53); // NOSONAR: never contacted

    private PublicIpv6() {}

    /**
     * Empty without a route to the IPv6 Internet, or while njMule keeps to an interface of its own, such as a VPN's.
     */
    public static Optional<Inet6Address> find() {
        if (!(TcpConnector.currentBinding() instanceof BindInterface.Binding.Any)) {
            return Optional.empty();
        }
        try (DatagramSocket probe = new DatagramSocket()) {
            probe.connect(INTERNET);
            return probe.getLocalAddress() instanceof Inet6Address ours && Ipv6.isPublic(ours)
                    ? Optional.of(ours)
                    : Optional.empty();
        } catch (IOException | UncheckedIOException e) {
            return Optional.empty();
        }
    }
}
