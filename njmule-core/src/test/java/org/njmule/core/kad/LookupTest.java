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
import org.njmule.core.protocol.kad.KadMessages;
import org.njmule.core.util.Ipv4;

class LookupTest {

    private static final long SECOND = 1_000_000_000L;
    private static final long T0 = 1_000 * SECOND;
    private static final int PORT = 4672;

    private final SecureRandom random = new SecureRandom();
    private long clock = T0;
    private final UInt128 target = UInt128.random(this.random);
    private final List<String> asks = new ArrayList<>();
    private final List<KadMessages.Contact> requested = new ArrayList<>();
    private final Lookup.Requests requests = new Lookup.Requests() {
        @Override
        public void ask(Lookup lookup, KadMessages.Contact contact, int count) {
            LookupTest.this.asks.add(Ipv4.format(contact.ip()) + "/" + count);
        }

        @Override
        public void request(Lookup lookup, KadMessages.Contact contact) {
            LookupTest.this.requested.add(contact);
        }
    };

    @Test
    void aSearchWhoseNodesAllStaySilentEndsAfterTheLateAnswerWait() {
        Lookup lookup = this.lookup(Lookup.Kind.KEYWORD, 1, 2, 3);
        assertThat(this.asks).hasSize(3);

        this.tickUntil(lookup, 3);
        assertThat(lookup.isStopped()).as("waiting for late answers").isFalse();
        this.tickUntil(lookup, 17);
        assertThat(lookup.isStopped()).isFalse();
        this.tickUntil(lookup, 18);
        assertThat(lookup.isStopped())
                .as("15 s after the stall, not at the end of its 45 s")
                .isTrue();
    }

    @Test
    void aNodeLookupWhoseNodesAllStaySilentEndsAtTheStall() {
        Lookup lookup = this.lookup(Lookup.Kind.NODE_COMPLETE, 1, 2);

        this.tickUntil(lookup, 2);
        assertThat(lookup.isStopped()).isFalse();
        this.tickUntil(lookup, 3);
        assertThat(lookup.isStopped()).isTrue();
    }

    @Test
    void aSearchAsksTheClosestNodeThatAnsweredForMoreWhenTheTwoClosestAreSilent() {
        Lookup lookup = this.lookup(Lookup.Kind.KEYWORD, 1, 2, 3, 4, 5, 6, 7, 8);
        // The third closest answers, the two closest stay silent.
        lookup.answered(ip(3), PORT, List.of(), T0);
        this.tickUntil(lookup, 10);

        assertThat(this.asks).contains(Ipv4.format(ip(3)) + "/11");
        assertThat(this.requested).extracting(KadMessages.Contact::ip).contains(ip(3));
        // Only the node asked again may send that many contacts.
        int closer = Ipv4.of(30, 0, 0, 1);
        lookup.answered(
                ip(4),
                PORT,
                List.of(
                        this.contactAt(new UInt128(2L << 32 | 1, 0), closer),
                        this.contactAt(new UInt128(2L << 32 | 2, 0), Ipv4.of(31, 0, 0, 1)),
                        this.contactAt(new UInt128(2L << 32 | 3, 0), Ipv4.of(32, 0, 0, 1))),
                this.clock);
        assertThat(this.asks).noneMatch(a -> a.startsWith(Ipv4.format(closer)));
    }

    @Test
    void aPublishNeverAsksANodeAgainForMore() {
        Lookup lookup = this.lookup(Lookup.Kind.STORE_KEYWORD, 1, 2, 3, 4, 5, 6, 7, 8);
        // Six nodes asked, then the sixth answers while it still waits for its publish.
        while (this.asks.size() < 6) {
            this.tickUntil(lookup, (this.clock - T0) / SECOND + 1);
        }
        lookup.answered(ip(6), PORT, List.of(), this.clock);
        this.tickUntil(lookup, (this.clock - T0) / SECOND + 10);

        assertThat(this.asks).noneMatch(a -> a.endsWith("/11"));
    }

    @Test
    void anAnswerMayNotFillTheRespondersOwnSubnetButLanAddressesAreExempt() {
        Lookup lookup = this.lookup(Lookup.Kind.NODE_COMPLETE, 100, 101, 102);
        int responder = ip(100);
        int sameSubnet = responder & 0xFFFF_FF00;

        lookup.answered(
                responder,
                PORT,
                List.of(this.contactAt(distance(1), sameSubnet | 0x10), this.contactAt(distance(2), sameSubnet | 0x11)),
                T0);
        assertThat(this.asks).as("one more of the responder's /24 at most").hasSize(4);

        Lookup lan = this.lookup(Lookup.Kind.NODE_COMPLETE, 100, 101, 102);
        this.asks.clear();
        lan.answered(
                responder,
                PORT,
                List.of(
                        this.contactAt(distance(1), Ipv4.of(192, 168, 1, 1)),
                        this.contactAt(distance(2), Ipv4.of(192, 168, 1, 2)),
                        this.contactAt(distance(3), Ipv4.of(192, 168, 1, 3))),
                T0);
        assertThat(this.asks).as("LAN contacts are not held to the /24 rule").hasSize(3);
    }

    private Lookup lookup(Lookup.Kind kind, long... distances) {
        RoutingTable table = new RoutingTable(UInt128.random(this.random), Ipv4::isLan);
        for (long d : distances) {
            KadContact c = new KadContact(this.target.xor(distance(d)), ip(d), PORT, 4662, 8, 0);
            c.setVerified(true);
            assertThat(table.add(c, false, NodesDat.UdpKey.NONE, 0, 0)).isEqualTo(RoutingTable.Outcome.ADDED);
        }
        Lookup lookup = new Lookup(this.target, kind, this.requests, Ipv4::isLan, T0);
        lookup.start(table);
        return lookup;
    }

    // Ticks once a second up to {@code second} seconds after the start.
    private void tickUntil(Lookup lookup, long second) {
        while (this.clock < T0 + second * SECOND) {
            this.clock += SECOND;
            lookup.tick(this.clock);
        }
    }

    private KadMessages.Contact contactAt(UInt128 distance, int ip) {
        return new KadMessages.Contact(this.target.xor(distance), ip, PORT, 4662, 8);
    }

    // Each node in a /24 of its own; small distances are within the request tolerance.
    private static int ip(long d) {
        return Ipv4.of(20, (int) (d >>> 8) & 0xFF, (int) d & 0xFF, 1);
    }

    private static UInt128 distance(long d) {
        return new UInt128(d << 32, 0);
    }
}
