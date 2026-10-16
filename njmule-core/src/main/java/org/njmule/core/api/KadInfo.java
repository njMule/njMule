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
 * The state of our Kad node.
 *
 * @param firewalled whether other nodes cannot connect to our TCP port
 * @param publicIp our address as other nodes see it, empty when unknown
 * @param udpPort the port Kad listens on
 * @param udpFirewalled whether nodes cannot send to our UDP port; while a test runs, the last outcome
 * @param udpVerified whether a UDP test came to an outcome: an unknown UDP state counts as open
 * @param buddy the buddy relaying callbacks to us while we are firewalled, or the firewalled node we serve
 * @param users how many nodes the network holds, as our routing table lets us estimate
 * @param files how many files they share, as estimated from the servers' average
 */
public record KadInfo(
        boolean connected,
        int contacts,
        int verifiedContacts,
        boolean firewalled,
        String id,
        String publicIp,
        int udpPort,
        boolean udpFirewalled,
        boolean udpVerified,
        Buddy buddy,
        long users,
        long files) {

    /** Our Kad buddy. */
    public enum Buddy {
        /** None: not needed, or not found yet. */
        NONE,
        /** We are firewalled and connect to one. */
        CONNECTING,
        /** We are firewalled and have one. */
        CONNECTED,
        /** We serve a firewalled node. */
        SERVING
    }

    public KadInfo {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(publicIp, "publicIp");
        Objects.requireNonNull(buddy, "buddy");
    }

    /** Without estimates of the network. */
    public KadInfo(
            boolean connected,
            int contacts,
            int verifiedContacts,
            boolean firewalled,
            String id,
            String publicIp,
            int udpPort,
            boolean udpFirewalled,
            boolean udpVerified,
            Buddy buddy) {
        this(
                connected,
                contacts,
                verifiedContacts,
                firewalled,
                id,
                publicIp,
                udpPort,
                udpFirewalled,
                udpVerified,
                buddy,
                0,
                0);
    }

    /** Without a UDP test or a buddy. */
    public KadInfo(
            boolean connected,
            int contacts,
            int verifiedContacts,
            boolean firewalled,
            String id,
            String publicIp,
            int udpPort) {
        this(connected, contacts, verifiedContacts, firewalled, id, publicIp, udpPort, false, false, Buddy.NONE);
    }
}
