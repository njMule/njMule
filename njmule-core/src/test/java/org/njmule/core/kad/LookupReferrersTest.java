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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.njmule.core.persist.format.NodesDat;
import org.njmule.core.protocol.kad.KadMessages;
import org.njmule.core.util.Ipv4;

/**
 * Whom a lookup believes: a node two others place at the same address gets the search or publish first, one a single
 * node knows of only at the end, and one two nodes place at different addresses never.
 */
class LookupReferrersTest {

    private static final long SECOND = 1_000_000_000L;
    private static final long T0 = 1_000 * SECOND;
    private static final int PORT = 4672;
    // Nodes of our table: three far from the target, one near it.
    private static final int FIRST = 1;
    private static final int SECOND_NODE = 2;
    private static final int OURS_NEAR = 4;
    // Nodes the answers tell of, near the target.
    private static final int NEAR = 11;
    private static final int NEARER = 12;
    private static final int ELSEWHERE = Ipv4.of(60, 1, 1, 1);

    private final SecureRandom random = new SecureRandom();
    private final UInt128 target = UInt128.random(this.random);
    private final List<Integer> asked = new ArrayList<>();
    private final List<Integer> requested = new ArrayList<>();
    private final Lookup.Requests requests = new Lookup.Requests() {
        @Override
        public void ask(Lookup lookup, KadMessages.Contact contact, int count) {
            LookupReferrersTest.this.asked.add(contact.ip());
        }

        @Override
        public void request(Lookup lookup, KadMessages.Contact contact) {
            LookupReferrersTest.this.requested.add(contact.ip());
        }
    };
    private long clock = T0;
    private Lookup lookup;

    @BeforeEach
    void create() {
        RoutingTable table = new RoutingTable(UInt128.random(this.random), ip -> false);
        for (int node : new int[] {FIRST, SECOND_NODE, 3, OURS_NEAR}) {
            KadContact c = new KadContact(this.idOf(node), ip(node), PORT, 4662, 8, 0);
            c.setVerified(true);
            assertThat(table.add(c, false, NodesDat.UdpKey.NONE, 0, 0)).isEqualTo(RoutingTable.Outcome.ADDED);
        }
        this.lookup = new Lookup(this.target, Lookup.Kind.KEYWORD, this.requests, ip -> false, T0);
        this.lookup.start(table);
    }

    @Test
    void shouldSendTheSearchFirstToANodeTwoOthersVouchFor() {
        this.givenANodeTells(FIRST, this.contact(NEAR, ip(NEAR)), this.contact(NEARER, ip(NEARER)));
        this.givenANodeTells(SECOND_NODE, this.contact(NEAR, ip(NEAR)));

        this.whenTheseNodesAnswer(NEAR, NEARER);
        this.whenSecondsPass(3);

        this.thenTheSearchWentTo(ip(NEAR));

        this.whenSecondsPass(2);

        this.thenTheSearchWentTo(ip(NEAR), ip(NEARER));
    }

    @Test
    void shouldNeverSendTheSearchToANodeTwoOthersPlaceAtDifferentAddresses() {
        this.givenANodeTells(FIRST, this.contact(NEAR, ip(NEAR)));
        this.givenANodeTells(SECOND_NODE, this.contact(NEAR, ELSEWHERE));

        this.whenTheseNodesAnswer(NEAR);
        this.whenSecondsPass(30);

        this.thenTheSearchNeverWentTo(ip(NEAR));
        this.thenNoOneAsked(ELSEWHERE);
    }

    @Test
    void shouldKeepTheAddressOfOurOwnContact() {
        this.givenANodeTells(FIRST, this.contact(OURS_NEAR, ELSEWHERE));

        this.whenTheseNodesAnswer(OURS_NEAR);
        this.whenSecondsPass(4);

        this.thenTheSearchWentTo(ip(OURS_NEAR));
        this.thenNoOneAsked(ELSEWHERE);
    }

    private void givenANodeTells(int responder, KadMessages.Contact... contacts) {
        this.lookup.answered(ip(responder), PORT, List.of(contacts), this.clock);
    }

    private void whenTheseNodesAnswer(int... nodes) {
        for (int node : nodes) {
            this.lookup.answered(ip(node), PORT, List.of(), this.clock);
        }
    }

    private void whenSecondsPass(int seconds) {
        for (int s = 0; s < seconds; s++) {
            this.clock += SECOND;
            this.lookup.tick(this.clock);
        }
    }

    private void thenTheSearchWentTo(Integer... ips) {
        assertThat(this.requested).containsExactly(ips);
    }

    private void thenTheSearchNeverWentTo(int ip) {
        assertThat(this.requested).doesNotContain(ip);
    }

    private void thenNoOneAsked(int ip) {
        assertThat(this.asked).doesNotContain(ip);
    }

    private KadMessages.Contact contact(int node, int ip) {
        return new KadMessages.Contact(this.idOf(node), ip, PORT, 4662, 8);
    }

    // The nodes of our table below OURS_NEAR are beyond the request tolerance; the others within, nearer the higher.
    private UInt128 idOf(int node) {
        long top = node < OURS_NEAR ? (1L << 28) + node : 100 - node;
        return this.target.xor(new UInt128(top << 32, 0));
    }

    private static int ip(int node) {
        return Ipv4.of(20, node, 1, 1);
    }
}
