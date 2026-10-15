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
package org.njmule.core.server;

import java.net.InetSocketAddress;
import java.util.Objects;
import java.util.Optional;
import org.njmule.core.config.ObfuscationMode;
import org.njmule.core.persist.format.ServerMet;
import org.njmule.core.protocol.ed2k.ServerDatagrams;
import org.njmule.core.util.Ipv4;

/** Where a datagram for a server goes, and the server's UDP key it is obfuscated with (0: sent plain). */
public record ServerRoute(InetSocketAddress target, int key) {

    public ServerRoute {
        Objects.requireNonNull(target, "target");
    }

    /**
     * Obfuscated to the server's obfuscated UDP port unless obfuscation is off, when the server takes obfuscated
     * datagrams and its key was given for the address we have now ({@code ourIp}, 0 when unknown); else plain to its
     * TCP port + 4, unless obfuscation is required: then none.
     */
    public static Optional<ServerRoute> of(ServerMet.Server server, int ourIp, ObfuscationMode mode) {
        ServerRoute route = of(server, ourIp, mode != ObfuscationMode.DISABLED);
        return mode == ObfuscationMode.REQUIRED && !route.isObfuscated() ? Optional.empty() : Optional.of(route);
    }

    private static ServerRoute of(ServerMet.Server server, int ourIp, boolean obfuscate) {
        long key = server.udpKey().orElse(0);
        int port = (int) server.obfuscatedUdpPort().orElse(0);
        boolean takesObfuscated = (server.udpFlags().orElse(0) & ServerDatagrams.UDP_OBFUSCATION) != 0;
        if (obfuscate
                && takesObfuscated
                && key != 0
                && port > 0
                && port <= 0xFFFF
                && ourIp != 0
                && server.udpKeyIp().orElse(0) == Ipv4.toEd2k(ourIp)) {
            return new ServerRoute(Ipv4.socketAddress(server.ip(), port), (int) key);
        }
        return new ServerRoute(Ipv4.socketAddress(server.ip(), server.port() + ServerUdp.PORT_OFFSET), 0);
    }

    public boolean isObfuscated() {
        return this.key != 0;
    }
}
