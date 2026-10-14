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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
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

    @Test
    void unusableContactsAreRefused() {
        assertThat(this.add(new KadContact(this.table.own(), Ipv4.of(1, 1, 1, 1), PORT, 4662, 8, 0), false))
                .as("ourselves")
                .isEqualTo(RoutingTable.Outcome.REFUSED);
        assertThat(this.add(new KadContact(UInt128.random(this.random), Ipv4.of(1, 1, 1, 2), PORT, 4662, 1, 0), false))
                .as("version 1")
                .isEqualTo(RoutingTable.Outcome.REFUSED);
        assertThat(this.add(new KadContact(UInt128.random(this.random), Ipv4.of(1, 1, 1, 3), 53, 4662, 5, 0), false))
                .as("port 53 of an old client")
                .isEqualTo(RoutingTable.Outcome.REFUSED);
        assertThat(this.add(new KadContact(UInt128.random(this.random), Ipv4.of(2, 1, 1, 3), 53, 4662, 6, 0), false))
                .isEqualTo(RoutingTable.Outcome.ADDED);
        assertThat(this.add(
                        new KadContact(UInt128.random(this.random), Ipv4.of(3, 1, 1, 3), 0xFFFF, 4662, 8, 0), false))
                .isEqualTo(RoutingTable.Outcome.ADDED);
    }

    @Test
    void aKnownContactChangesOnlyAsAnUpdateAndNeverOntoAnotherOnesAddress() {
        KadContact first = this.contact(Ipv4.of(1, 1, 1, 1));
        KadContact second = this.contact(Ipv4.of(2, 2, 2, 2));
        this.add(first, false);
        this.add(second, false);

        assertThat(this.add(new KadContact(first.id(), Ipv4.of(4, 4, 4, 4), PORT, 4662, 8, 0), false))
                .as("not an update")
                .isEqualTo(RoutingTable.Outcome.REFUSED);
        assertThat(this.add(new KadContact(first.id(), Ipv4.of(2, 2, 2, 2), PORT, 4662, 8, 0), true))
                .as("onto another contact's address")
                .isEqualTo(RoutingTable.Outcome.REFUSED);
        assertThat(this.add(new KadContact(first.id(), Ipv4.of(1, 1, 1, 1), PORT, 4662, 7, 0), true))
                .as("an older version")
                .isEqualTo(RoutingTable.Outcome.REFUSED);
        assertThat(this.table.byAddress(Ipv4.of(1, 1, 1, 1))).containsSame(first);
        assertThat(this.table.byAddress(Ipv4.of(2, 2, 2, 2))).containsSame(second);

        KadContact old = new KadContact(UInt128.random(this.random), Ipv4.of(5, 5, 5, 5), PORT, 4662, 5, 0);
        this.add(old, false);
        old.setHelloReceived();
        assertThat(this.add(new KadContact(old.id(), Ipv4.of(5, 5, 5, 5), PORT + 1, 4662, 5, 0), true))
                .as("an old client that said hello keeps its port")
                .isEqualTo(RoutingTable.Outcome.REFUSED);
        assertThat(this.add(new KadContact(old.id(), Ipv4.of(5, 5, 5, 5), PORT, 4662, 5, 0), true))
                .isEqualTo(RoutingTable.Outcome.UPDATED);
    }

    @Test
    void lanAddressesAreNotHeldToTheSubnetLimitsAndRemovingFreesThePlace() {
        RoutingTable lan = new RoutingTable(UInt128.random(this.random), Ipv4::isLan);
        int added = 0;
        for (int i = 1; i <= 30; i++) {
            KadContact c = this.contact(Ipv4.of(192, 168, 1, i));
            if (lan.add(c, false, NodesDat.UdpKey.NONE, 0, 0) == RoutingTable.Outcome.ADDED) {
                added++;
            }
        }
        assertThat(added).isGreaterThan(10);

        KadContact c = this.contact(Ipv4.of(7, 7, 7, 7));
        this.add(c, false);
        this.table.remove(c);
        assertThat(this.table.byAddress(c.ip())).isEmpty();
        assertThat(this.add(this.contact(Ipv4.of(7, 7, 7, 7)), false)).isEqualTo(RoutingTable.Outcome.ADDED);
    }

    @Test
    void theClosestAreVerifiedContactsOfLowEnoughTypeInOrder() {
        UInt128 target = UInt128.random(this.random);
        assertThat(this.table.closest(target, 3, 5)).isEmpty();
        KadContact exact = new KadContact(target, Ipv4.of(1, 1, 1, 1), PORT, 4662, 8, 0);
        KadContact unverified = this.contact(Ipv4.of(2, 2, 2, 2));
        KadContact other = this.contact(Ipv4.of(3, 3, 3, 3));
        for (KadContact c : List.of(exact, unverified, other)) {
            this.add(c, false);
        }
        exact.setVerified(true);
        other.setVerified(true);

        assertThat(this.table.closest(target, 3, 0)).isEmpty();
        assertThat(this.table.closest(target, 3, 10)).containsExactly(exact, other);
        other.alive(0);
        assertThat(this.table.closest(target, 2, 10))
                .as("only those that answered")
                .containsExactly(other);
    }

    @Property(tries = 50)
    void addingUpdatingAndRemovingKeepTheTableInShape(@ForAll long seed) {
        Random random = new Random(seed);
        RoutingTable t = new RoutingTable(new UInt128(random.nextLong(), random.nextLong()), ip -> (ip >>> 24) == 10);
        List<KadContact> known = new ArrayList<>();
        for (int step = 0; step < 400; step++) {
            int ip = Ipv4.of(
                    random.nextBoolean() ? 10 : 20 + random.nextInt(3), random.nextInt(3), 1, random.nextInt(40));
            int op = random.nextInt(10);
            if (op < 7 || known.isEmpty()) {
                KadContact c = new KadContact(new UInt128(random.nextLong(), random.nextLong()), ip, PORT, 4662, 8, 0);
                if (t.add(c, false, NodesDat.UdpKey.NONE, 0, 0) == RoutingTable.Outcome.ADDED) {
                    known.add(c);
                }
            } else if (op < 9) {
                KadContact c = known.get(random.nextInt(known.size()));
                t.add(new KadContact(c.id(), ip, PORT, 4662, 8, 0), true, NodesDat.UdpKey.NONE, 0, 0);
            } else {
                t.remove(known.remove(random.nextInt(known.size())));
            }
            if (step % 97 == 0) {
                t.consolidate();
            }
        }
        assertShape(t, known.size());
    }

    private static void assertShape(RoutingTable t, int size) {
        assertThat(t.size()).isEqualTo(size);
        Map<Integer, Integer> perSubnet = new HashMap<>();
        Set<Integer> ips = new HashSet<>();
        int inLeaves = 0;
        for (RoutingTable.Zone zone : t.leaves()) {
            List<KadContact> bin = zone.contacts();
            assertThat(bin).hasSizeLessThanOrEqualTo(RoutingTable.K);
            Map<Integer, Integer> inBin = new HashMap<>();
            for (KadContact c : bin) {
                inLeaves++;
                assertThat(ips.add(c.ip())).as("one contact per address").isTrue();
                for (int bit = 0; bit < zone.level(); bit++) {
                    assertThat(c.id().xor(t.own()).bit(bit))
                            .isEqualTo(zone.index().bit(UInt128.BITS - zone.level() + bit));
                }
                if ((c.ip() >>> 24) != 10) {
                    perSubnet.merge(c.ip() & 0xFFFF_FF00, 1, Integer::sum);
                    assertThat(inBin.merge(c.ip() & 0xFFFF_FF00, 1, Integer::sum))
                            .isLessThanOrEqualTo(2);
                }
            }
        }
        assertThat(inLeaves).isEqualTo(size);
        assertThat(perSubnet.values()).allMatch(n -> n <= 10);
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
