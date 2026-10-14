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
package org.njmule.core.kad;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.njmule.core.persist.format.NodesDat;
import org.njmule.core.util.Ipv4;

class RoutingTableTest {

    private static final int PORT = 4672;

    private final SecureRandom random = new SecureRandom();
    private final RoutingTable table = new RoutingTable(UInt128.random(this.random), ip -> false);

    @Test
    void aContactMayMoveInsideItsOwnFullSubnet() {
        List<KadContact> subnet = this.fillSubnet(Ipv4.of(9, 9, 9, 0));
        assertThat(subnet).hasSize(10);
        assertThat(this.add(this.contact(Ipv4.of(9, 9, 9, 251)), false))
                .as("the /24 is full")
                .isEqualTo(RoutingTable.Outcome.REFUSED);

        KadContact moving = subnet.getFirst();
        int before = moving.ip();
        KadContact moved = new KadContact(moving.id(), Ipv4.of(9, 9, 9, 251), PORT, 4662, 8, 0);

        assertThat(this.add(moved, true)).isEqualTo(RoutingTable.Outcome.UPDATED);
        assertThat(this.table.byAddress(Ipv4.of(9, 9, 9, 251))).containsSame(moving);
        assertThat(this.table.byAddress(before)).isEmpty();
    }

    // Spreads contacts over many zones, then adds contacts of one /24 until it holds the most allowed.
    private List<KadContact> fillSubnet(int subnet) {
        for (int i = 0; i < 80; i++) {
            this.add(this.contact(Ipv4.of(30 + i, 1, 1, 1)), false);
        }
        List<KadContact> added = new ArrayList<>();
        for (int i = 1; i < 10_000 && added.size() < 10; i++) {
            KadContact c = this.contact(subnet | (i % 250 + 1));
            if (this.add(c, false) == RoutingTable.Outcome.ADDED) {
                added.add(c);
            }
        }
        return added;
    }

    private RoutingTable.Outcome add(KadContact contact, boolean update) {
        return this.table.add(contact, update, NodesDat.UdpKey.NONE, 0, 0);
    }

    private KadContact contact(int ip) {
        return new KadContact(UInt128.random(this.random), ip, PORT, 4662, 8, 0);
    }
}
