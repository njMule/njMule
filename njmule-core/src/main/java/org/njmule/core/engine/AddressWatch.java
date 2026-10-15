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
package org.njmule.core.engine;

/**
 * Tells when our public address changed. Once the server we are on and Kad saw us at the same address, one of them
 * seeing another means we moved, and the other has to look again; each new address is acted on once. While they never
 * agreed, as behind a NAT that gives each connection its own address, nothing is done. Engine thread only.
 */
final class AddressWatch {

    /** What a change asks for. */
    enum Reaction {
        NONE,
        /** The server sees the new address: Kad checks its firewall again, and learns it. */
        RECHECK_KAD,
        /** Kad sees the new address: the server still knows us at the old one. */
        RECONNECT_SERVER
    }

    private int agreed;
    private int reactedTo;

    /** The address the server and Kad last agreed on; 0 while they never did. */
    int agreed() {
        return this.agreed;
    }

    /** The addresses the server and Kad see us at now ({@code Ipv4} form, 0 when unknown), and what to do. */
    Reaction seen(int serverIp, int kadIp) {
        if (serverIp != 0 && serverIp == kadIp) {
            this.agreed = serverIp;
            this.reactedTo = 0;
            return Reaction.NONE;
        }
        if (this.agreed == 0) {
            return Reaction.NONE;
        }
        if (kadIp == this.agreed && isNew(serverIp)) {
            this.reactedTo = serverIp;
            return Reaction.RECHECK_KAD;
        }
        if (serverIp == this.agreed && isNew(kadIp)) {
            this.reactedTo = kadIp;
            return Reaction.RECONNECT_SERVER;
        }
        return Reaction.NONE;
    }

    private boolean isNew(int ip) {
        return ip != 0 && ip != this.agreed && ip != this.reactedTo;
    }
}
