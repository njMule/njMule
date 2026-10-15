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

/**
 * A search waits for silent nodes as long as nodes have been taking to answer, never longer than three seconds; a
 * publish always waits three seconds.
 */
class LookupResponseTimeTest {

    private static final long SECOND = 1_000_000_000L;
    private static final long MILLI = 1_000_000L;
    private static final long T0 = 1_000 * SECOND;
    private static final int PORT = 4672;

    private final SecureRandom random = new SecureRandom();
    private final UInt128 target = UInt128.random(this.random);
    private final ResponseTimes times = new ResponseTimes();
    private final List<Integer> asked = new ArrayList<>();
    private final Lookup.Requests requests = new Lookup.Requests() {
        @Override
        public void ask(Lookup lookup, KadMessages.Contact contact, int count) {
            LookupResponseTimeTest.this.asked.add(contact.ip());
        }

        @Override
        public void request(Lookup lookup, KadMessages.Contact contact) {}
    };
    private long clock = T0;
    private Lookup lookup;

    @Test
    void shouldMoveOnSoonerWhileNodesAnswerFast() {
        givenNodesAnsweredIn(100, 50 * MILLI);
        givenALookupOfSilentNodes(Lookup.Kind.KEYWORD);

        whenSecondsPass(1);

        thenNodesAsked(4);
    }

    @Test
    void shouldWaitNoLongerThanThreeSecondsWhileNodesAnswerSlowly() {
        givenNodesAnsweredIn(100, 10 * SECOND);
        givenALookupOfSilentNodes(Lookup.Kind.KEYWORD);

        whenSecondsPass(2);

        thenNodesAsked(3);

        whenSecondsPass(1);

        thenNodesAsked(4);
    }

    @Test
    void shouldWaitThreeSecondsUntilEnoughAnswersWereTimed() {
        givenNodesAnsweredIn(5, 50 * MILLI);
        givenALookupOfSilentNodes(Lookup.Kind.KEYWORD);

        whenSecondsPass(2);

        thenNodesAsked(3);
    }

    @Test
    void shouldAlwaysGiveAPublishThreeSeconds() {
        givenNodesAnsweredIn(100, 50 * MILLI);
        givenALookupOfSilentNodes(Lookup.Kind.STORE_KEYWORD);

        whenSecondsPass(2);

        thenNodesAsked(3);
    }

    @Test
    void shouldTimeTheAnswersOfTheNodesItAsked() {
        givenALookupOfSilentNodes(Lookup.Kind.KEYWORD);

        whenTheAskedNodesAnswerAfter(200 * MILLI);

        thenAnswersTimed(3);
    }

    private void givenNodesAnsweredIn(int nodes, long nanos) {
        for (int i = 0; i < nodes; i++) {
            this.times.answered(Ipv4.of(50, i, 1, 1), nanos);
        }
    }

    private void givenALookupOfSilentNodes(Lookup.Kind kind) {
        RoutingTable table = new RoutingTable(UInt128.random(this.random), ip -> false);
        for (int i = 1; i <= 20; i++) {
            KadContact c = new KadContact(UInt128.random(this.random), Ipv4.of(20, i, 1, 1), PORT, 4662, 8, 0);
            c.setVerified(true);
            table.add(c, false, NodesDat.UdpKey.NONE, 0, 0);
        }
        this.lookup = new Lookup(this.target, kind, this.requests, ip -> false, this.times, T0);
        this.lookup.start(table);
    }

    private void whenSecondsPass(int seconds) {
        for (int s = 0; s < seconds; s++) {
            this.clock += SECOND;
            this.lookup.tick(this.clock);
        }
    }

    private void whenTheAskedNodesAnswerAfter(long nanos) {
        for (Integer ip : List.copyOf(this.asked)) {
            this.lookup.answered(ip, PORT, List.of(), this.clock + nanos);
        }
    }

    private void thenNodesAsked(int count) {
        assertThat(this.asked).hasSize(count);
    }

    private void thenAnswersTimed(int count) {
        assertThat(this.times.samples()).isEqualTo(count);
    }
}
