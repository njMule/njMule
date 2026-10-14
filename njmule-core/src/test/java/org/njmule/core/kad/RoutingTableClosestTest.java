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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import org.njmule.core.persist.format.NodesDat;
import org.njmule.core.util.Ipv4;

/** The contacts closest to a target are those a full sort of the table by distance puts first. */
class RoutingTableClosestTest {

    private static final int PORT = 4672;

    private RoutingTable table;
    private Random random;
    private UInt128 target;
    private int maxType;
    private List<KadContact> closest;

    @Property(tries = 200)
    void shouldFindTheSameContactsAsAFullSort(
            @ForAll long seed,
            @ForAll @IntRange(min = 0, max = 300) int contacts,
            @ForAll @IntRange(max = 60) int count) {
        this.givenATableOfRandomContacts(seed, contacts);
        this.givenARandomTargetNearOrFarFromUs();

        this.whenTheClosestAreAskedFor(count);

        this.thenTheyAreTheFirstOfAFullSort(count);
    }

    private void givenATableOfRandomContacts(long seed, int contacts) {
        this.random = new Random(seed);
        this.table = new RoutingTable(this.randomId(), ip -> false);
        for (int i = 0; i < contacts; i++) {
            KadContact c = new KadContact(
                    this.randomId(), Ipv4.of(20 + i / 200, i % 200, this.random.nextInt(4), 1), PORT, 4662, 8, 0);
            c.setVerified(this.random.nextInt(5) > 0);
            if (this.random.nextBoolean()) {
                c.alive(0);
            }
            this.table.add(c, false, NodesDat.UdpKey.NONE, 0, 0);
        }
        if (this.random.nextBoolean()) {
            this.table.consolidate();
        }
        this.maxType = this.random.nextBoolean() ? KadContact.NEW : 2;
    }

    private void givenARandomTargetNearOrFarFromUs() {
        UInt128 own = this.table.own();
        this.target = switch (this.random.nextInt(3)) {
            case 0 -> own;
            case 1 -> own.xor(new UInt128(0, this.random.nextLong()));
            default -> this.randomId();
        };
    }

    private void whenTheClosestAreAskedFor(int count) {
        this.closest = this.table.closest(this.target, this.maxType, count);
    }

    private void thenTheyAreTheFirstOfAFullSort(int count) {
        List<KadContact> all = new ArrayList<>();
        for (KadContact c : this.table.all()) {
            if (c.isVerified() && c.type() <= this.maxType) {
                all.add(c);
            }
        }
        all.sort(Comparator.comparing(c -> c.id().xor(this.target)));
        assertThat(this.closest).containsExactlyElementsOf(all.subList(0, Math.min(count, all.size())));
    }

    private UInt128 randomId() {
        return new UInt128(this.random.nextLong(), this.random.nextLong());
    }
}
