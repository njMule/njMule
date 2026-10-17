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

import java.net.DatagramPacket;
import java.net.InetSocketAddress;
import java.util.Objects;
import java.util.Optional;

/**
 * Where the datagrams of a UDP socket go: straight to the network, through the relay of a SOCKS5 proxy, or nowhere
 * while that relay is being reached, so that none leaves outside the proxy.
 */
public final class UdpRoute {

    /** Straight to the network, and from it. */
    public static final UdpRoute DIRECT = new UdpRoute(null, true);

    /** Nothing goes or comes. */
    public static final UdpRoute HELD = new UdpRoute(null, false);

    /** A datagram received: its sender, and where its payload lies in the packet's data. */
    public record Received(InetSocketAddress from, int offset, int length) {}

    private final Socks5Udp relay;
    private final boolean direct;

    private UdpRoute(Socks5Udp relay, boolean direct) {
        this.relay = relay;
        this.direct = direct;
    }

    /** Through the relay of {@code association}; nothing goes once it has ended. */
    public static UdpRoute through(Socks5Udp association) {
        return new UdpRoute(Objects.requireNonNull(association, "association"), false);
    }

    public boolean isRelayed() {
        return this.relay != null;
    }

    /** The datagram that carries {@code data} to {@code to} (an IPv4 address), if one may go now. */
    public Optional<DatagramPacket> outgoing(InetSocketAddress to, byte[] data) {
        if (this.relay != null) {
            if (!this.relay.isOpen()) {
                return Optional.empty();
            }
            byte[] wrapped = Socks5Udp.wrap(to, data);
            return Optional.of(new DatagramPacket(wrapped, wrapped.length, this.relay.relay()));
        }
        return this.direct ? Optional.of(new DatagramPacket(data, data.length, to)) : Optional.empty();
    }

    /**
     * The sender and payload of {@code packet}, just received; empty for one to drop, such as one that reached the
     * socket outside the relay, whose answer would leave outside the proxy.
     */
    public Optional<Received> incoming(DatagramPacket packet) {
        if (!(packet.getSocketAddress() instanceof InetSocketAddress from)) {
            return Optional.empty();
        }
        if (this.relay != null) {
            return from.equals(this.relay.relay())
                    ? Socks5Udp.unwrap(packet.getData(), packet.getOffset(), packet.getLength())
                    : Optional.empty();
        }
        return this.direct ? Optional.of(new Received(from, packet.getOffset(), packet.getLength())) : Optional.empty();
    }

    @Override
    public String toString() {
        return this.relay != null ? "through " + this.relay.relay() : this.direct ? "direct" : "held";
    }
}
