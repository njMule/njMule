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
 * How a search or a publish ends: it stops sending twenty seconds before its time is up, or once it has all it wants,
 * and still takes the answers that arrive in the next fifteen.
 */
class LookupStoppingTest {

    private static final long SECOND = 1_000_000_000L;
    private static final long T0 = 1_000 * SECOND;
    private static final int PORT = 4672;

    private final SecureRandom random = new SecureRandom();
    private final UInt128 target = UInt128.random(this.random);
    private final List<Integer> sent = new ArrayList<>();
    private final Lookup.Requests requests = new Lookup.Requests() {
        @Override
        public void ask(Lookup lookup, KadMessages.Contact contact, int count) {
            LookupStoppingTest.this.sent.add(contact.ip());
        }

        @Override
        public void request(Lookup lookup, KadMessages.Contact contact) {
            LookupStoppingTest.this.sent.add(contact.ip());
        }
    };
    private long clock = T0;
    private Lookup lookup;
    private int sentBefore;

    @Test
    void shouldSendNothingInTheLastTwentySecondsOfASearch() {
        givenASearchWithSilentNodes(Lookup.Kind.SOURCES, 80);

        whenSecondsPass(25);

        thenItStillSends();

        whenSecondsPass(14);

        thenItSentNothingMore();
        thenItRuns();

        whenSecondsPass(1);

        thenItHasEnded();
    }

    @Test
    void shouldStillTakeAnswersOnceItHasAllItWants() {
        givenASearchWithSilentNodes(Lookup.Kind.KEYWORD, 3);

        whenValuesArrive(300);
        whenTheClosestNodeAnswersWithCloserContacts();
        whenSecondsPass(14);

        thenItSentNothingMore();
        thenItRuns();

        whenSecondsPass(1);

        thenItHasEnded();
    }

    @Test
    void shouldKeepAPublishWhoseNodesAnswerGoingUntilTwentySecondsBeforeItsEnd() {
        givenASearchWithSilentNodes(Lookup.Kind.STORE_SOURCE, 2);

        whenANodeAnswersEverySecondFor(Lookup.Kind.STORE_SOURCE.lifetime().toSeconds() - 6);

        thenItRuns();

        whenANodeAnswersEverySecondFor(1);

        thenItHasEnded();
    }

    private void givenASearchWithSilentNodes(Lookup.Kind kind, int nodes) {
        RoutingTable table = new RoutingTable(UInt128.random(this.random), Ipv4::isLan);
        for (int d = 1; d <= nodes; d++) {
            KadContact c = new KadContact(UInt128.random(this.random), ip(d), PORT, 4662, 8, 0);
            c.setVerified(true);
            table.add(c, false, NodesDat.UdpKey.NONE, 0, 0);
        }
        assertThat(table.size()).isGreaterThanOrEqualTo(Math.min(nodes, 40));
        this.lookup = new Lookup(this.target, kind, this.requests, Ipv4::isLan, T0);
        this.lookup.start(table);
    }

    private void whenSecondsPass(int seconds) {
        this.sentBefore = this.sent.size();
        for (int s = 0; s < seconds; s++) {
            this.clock += SECOND;
            this.lookup.tick(this.clock);
        }
    }

    private void whenValuesArrive(int values) {
        this.lookup.valueReceived(values, this.clock);
    }

    private void whenTheClosestNodeAnswersWithCloserContacts() {
        this.sentBefore = this.sent.size();
        this.lookup.answered(
                ip(1),
                PORT,
                List.of(new KadMessages.Contact(this.target, Ipv4.of(30, 0, 0, 1), PORT, 4662, 8)),
                this.clock);
    }

    private void whenANodeAnswersEverySecondFor(long seconds) {
        for (long s = 0; s < seconds; s++) {
            this.clock += SECOND;
            this.lookup.answered(ip(1), PORT, List.of(), this.clock);
            this.lookup.tick(this.clock);
        }
    }

    private void thenItStillSends() {
        assertThat(this.sent.size()).isGreaterThan(this.sentBefore);
    }

    private void thenItSentNothingMore() {
        assertThat(this.sent).hasSize(this.sentBefore);
    }

    private void thenItRuns() {
        assertThat(this.lookup.isStopped()).isFalse();
    }

    private void thenItHasEnded() {
        assertThat(this.lookup.isStopped()).isTrue();
    }

    // Each node in a /24 of its own.
    private static int ip(long d) {
        return Ipv4.of(20, (int) (d >>> 8) & 0xFF, (int) d & 0xFF, 1);
    }
}
