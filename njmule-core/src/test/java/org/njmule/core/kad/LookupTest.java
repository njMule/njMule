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
    private Lookup lookup;

    @Test
    void shouldKeepASilentSearchWaitingForLateAnswersAfterTheStall() {
        givenALookup(Lookup.Kind.KEYWORD, 1, 2, 3);
        givenItAskedNodes(3);

        whenTheClockReachesSecond(3);

        thenTheLookupGoesOnWaitingForLateAnswers();
    }

    @Test
    void shouldKeepASilentSearchGoingUntilSeventeenSeconds() {
        givenALookup(Lookup.Kind.KEYWORD, 1, 2, 3);
        givenItAskedNodes(3);

        whenTheClockReachesSecond(17);

        thenTheLookupGoesOn();
    }

    @Test
    void shouldEndASilentSearchAfterTheLateAnswerWait() {
        givenALookup(Lookup.Kind.KEYWORD, 1, 2, 3);
        givenItAskedNodes(3);

        whenTheClockReachesSecond(18);

        thenTheLookupStoppedFifteenSecondsAfterTheStall();
    }

    @Test
    void shouldKeepASilentNodeLookupGoingUntilTheStall() {
        givenALookup(Lookup.Kind.NODE_COMPLETE, 1, 2);

        whenTheClockReachesSecond(2);

        thenTheLookupGoesOn();
    }

    @Test
    void shouldEndASilentNodeLookupAtTheStall() {
        givenALookup(Lookup.Kind.NODE_COMPLETE, 1, 2);

        whenTheClockReachesSecond(3);

        thenTheLookupIsStopped();
    }

    @Test
    void shouldAskTheClosestNodeThatAnsweredForMoreWhenTheTwoClosestAreSilent() {
        givenALookup(Lookup.Kind.KEYWORD, 1, 2, 3, 4, 5, 6, 7, 8);
        // The third closest answers, the two closest stay silent.
        givenTheNodeAnswered(3);

        whenTheClockReachesSecond(10);

        thenTheNodeIsAskedFor(3, 11);
        thenTheNodeGetsTheRequest(3);
    }

    @Test
    void shouldTakeThatManyContactsOnlyFromTheNodeAskedAgain() {
        givenALookup(Lookup.Kind.KEYWORD, 1, 2, 3, 4, 5, 6, 7, 8);
        givenTheNodeAnswered(3);
        givenTheClockAtSecond(10);

        whenTheNodeAnswers(
                4,
                contactAt(new UInt128(2L << 32 | 1, 0), Ipv4.of(30, 0, 0, 1)),
                contactAt(new UInt128(2L << 32 | 2, 0), Ipv4.of(31, 0, 0, 1)),
                contactAt(new UInt128(2L << 32 | 3, 0), Ipv4.of(32, 0, 0, 1)));

        thenNoNodeIsAskedAt(Ipv4.of(30, 0, 0, 1));
    }

    @Test
    void shouldNeverAskANodeAgainForMoreInAPublish() {
        givenALookup(Lookup.Kind.STORE_KEYWORD, 1, 2, 3, 4, 5, 6, 7, 8);
        // Six nodes asked, then the sixth answers while it still waits for its publish.
        givenTheClockRunsUntilItAskedNodes(6);
        givenTheNodeAnswered(6);

        whenTheClockRunsForSeconds(10);

        thenNoNodeIsAskedForMore();
    }

    @Test
    void shouldNotLetAnAnswerFillTheRespondersOwnSubnet() {
        givenALookup(Lookup.Kind.NODE_COMPLETE, 100, 101, 102);

        whenTheNodeAnswers(
                100,
                contactAt(distance(1), inTheSubnetOf(100, 0x10)),
                contactAt(distance(2), inTheSubnetOf(100, 0x11)));

        thenTheNumberOfAsksIs(4, "one more of the responder's /24 at most");
    }

    @Test
    void shouldNotHoldLanAddressesToTheSubnetRule() {
        givenALookup(Lookup.Kind.NODE_COMPLETE, 100, 101, 102);
        givenTheAsksSoFarForgotten();

        whenTheNodeAnswers(
                100,
                contactAt(distance(1), Ipv4.of(192, 168, 1, 1)),
                contactAt(distance(2), Ipv4.of(192, 168, 1, 2)),
                contactAt(distance(3), Ipv4.of(192, 168, 1, 3)));

        thenTheNumberOfAsksIs(3, "LAN contacts are not held to the /24 rule");
    }

    @Test
    void shouldEndALookupWithoutContactsAtOnce() {
        whenALookupStarts(Lookup.Kind.KEYWORD);

        thenTheLookupIsStopped();
        thenNoNodeIsAsked();
    }

    @Test
    void shouldAskOneNodeInANodeLookup() {
        whenALookupStarts(Lookup.Kind.NODE, 1, 2, 3);

        thenOnlyTheNodeIsAskedFor(1, 11);
    }

    @Test
    void shouldEndANodeLookupWithTheAnswerOfItsNode() {
        givenALookup(Lookup.Kind.NODE, 1, 2, 3);

        whenTheNodeAnswers(1, contactAt(distance(0), Ipv4.of(30, 0, 0, 1)));

        thenTheLookupIsStopped();
    }

    @Test
    void shouldNotHearAnAskedNodeFromAnotherPort() {
        givenALookup(Lookup.Kind.KEYWORD, 100, 101, 102);

        whenAnAnswerComesFrom(ip(100), PORT + 1, contactAt(distance(1), Ipv4.of(30, 0, 0, 1)));

        thenTheNumberOfAsksIs(3, "another port");
    }

    @Test
    void shouldNotHearAStranger() {
        givenALookup(Lookup.Kind.KEYWORD, 100, 101, 102);

        whenAnAnswerComesFrom(Ipv4.of(40, 0, 0, 1), PORT, contactAt(distance(1), Ipv4.of(30, 0, 0, 1)));

        thenTheNumberOfAsksIs(3, "a stranger");
    }

    @Test
    void shouldNotHearAnAnswerWithMoreContactsThanAsked() {
        givenALookup(Lookup.Kind.KEYWORD, 100, 101, 102);

        whenTheNodeAnswers(
                100,
                contactAt(distance(1), Ipv4.of(30, 0, 0, 1)),
                contactAt(distance(2), Ipv4.of(31, 0, 0, 1)),
                contactAt(distance(3), Ipv4.of(32, 0, 0, 1)));

        thenTheNumberOfAsksIs(3, "too many contacts");
    }

    @Test
    void shouldAskTheSameContactOnlyOnce() {
        givenALookup(Lookup.Kind.KEYWORD, 100, 101, 102);
        givenTheIgnoredAnswersOfAStrangerAnotherPortAndTooManyContacts();

        whenTheNodeAnswers(
                100, contactAt(distance(1), Ipv4.of(30, 0, 0, 1)), contactAt(distance(1), Ipv4.of(31, 0, 0, 1)));

        thenTheNumberOfAsksIs(4, "the same contact twice is asked once");
    }

    @Test
    void shouldRequestOnlyNodesWithinTheTolerance() {
        givenALookup(Lookup.Kind.KEYWORD, Lookup.TOLERANCE, Lookup.TOLERANCE + 1);
        givenTheNodeAnswered(Lookup.TOLERANCE);
        givenTheNodeAnswered(Lookup.TOLERANCE + 1);

        whenTheClockReachesSecond(4);

        thenOnlyTheNodeGetsTheRequest(Lookup.TOLERANCE);
    }

    private void givenALookup(Lookup.Kind kind, long... distances) {
        this.lookup = lookup(kind, distances);
    }

    private void givenItAskedNodes(int count) {
        assertThat(this.asks).hasSize(count);
    }

    private void givenTheNodeAnswered(long node) {
        answer(ip(node), PORT);
    }

    private void givenTheClockAtSecond(long second) {
        tickUntil(this.lookup, second);
    }

    private void givenTheClockRunsUntilItAskedNodes(int count) {
        while (this.asks.size() < count) {
            tickUntil(this.lookup, (this.clock - T0) / SECOND + 1);
        }
    }

    private void givenTheAsksSoFarForgotten() {
        this.asks.clear();
    }

    private void givenTheIgnoredAnswersOfAStrangerAnotherPortAndTooManyContacts() {
        int closer = Ipv4.of(30, 0, 0, 1);
        this.lookup.answered(ip(100), PORT + 1, List.of(contactAt(distance(1), closer)), T0);
        this.lookup.answered(Ipv4.of(40, 0, 0, 1), PORT, List.of(contactAt(distance(1), closer)), T0);
        this.lookup.answered(
                ip(100),
                PORT,
                List.of(
                        contactAt(distance(1), closer),
                        contactAt(distance(2), Ipv4.of(31, 0, 0, 1)),
                        contactAt(distance(3), Ipv4.of(32, 0, 0, 1))),
                T0);
        assertThat(this.asks).as("a stranger, another port, too many contacts").hasSize(3);
    }

    private void whenALookupStarts(Lookup.Kind kind, long... distances) {
        this.lookup = lookup(kind, distances);
    }

    private void whenTheClockReachesSecond(long second) {
        tickUntil(this.lookup, second);
    }

    private void whenTheClockRunsForSeconds(long seconds) {
        tickUntil(this.lookup, (this.clock - T0) / SECOND + seconds);
    }

    private void whenTheNodeAnswers(long node, KadMessages.Contact... contacts) {
        answer(ip(node), PORT, contacts);
    }

    private void whenAnAnswerComesFrom(int ip, int port, KadMessages.Contact... contacts) {
        answer(ip, port, contacts);
    }

    private void thenTheLookupGoesOn() {
        assertThat(this.lookup.isStopped()).isFalse();
    }

    private void thenTheLookupGoesOnWaitingForLateAnswers() {
        assertThat(this.lookup.isStopped()).as("waiting for late answers").isFalse();
    }

    private void thenTheLookupIsStopped() {
        assertThat(this.lookup.isStopped()).isTrue();
    }

    private void thenTheLookupStoppedFifteenSecondsAfterTheStall() {
        assertThat(this.lookup.isStopped())
                .as("15 s after the stall, not at the end of its 45 s")
                .isTrue();
    }

    private void thenTheNodeIsAskedFor(long node, int count) {
        assertThat(this.asks).contains(Ipv4.format(ip(node)) + "/" + count);
    }

    private void thenOnlyTheNodeIsAskedFor(long node, int count) {
        assertThat(this.asks).containsExactly(Ipv4.format(ip(node)) + "/" + count);
    }

    private void thenTheNodeGetsTheRequest(long node) {
        assertThat(this.requested).extracting(KadMessages.Contact::ip).contains(ip(node));
    }

    private void thenOnlyTheNodeGetsTheRequest(long node) {
        assertThat(this.requested).extracting(KadMessages.Contact::ip).containsExactly(ip(node));
    }

    // Only the node asked again may send that many contacts.
    private void thenNoNodeIsAskedAt(int ip) {
        assertThat(this.asks).noneMatch(a -> a.startsWith(Ipv4.format(ip)));
    }

    private void thenNoNodeIsAskedForMore() {
        assertThat(this.asks).noneMatch(a -> a.endsWith("/11"));
    }

    private void thenNoNodeIsAsked() {
        assertThat(this.asks).isEmpty();
    }

    private void thenTheNumberOfAsksIs(int count, String why) {
        assertThat(this.asks).as(why).hasSize(count);
    }

    private Lookup lookup(Lookup.Kind kind, long... distances) {
        RoutingTable table = new RoutingTable(UInt128.random(this.random), Ipv4::isLan);
        for (long d : distances) {
            KadContact c = new KadContact(this.target.xor(distance(d)), ip(d), PORT, 4662, 8, 0);
            c.setVerified(true);
            assertThat(table.add(c, false, NodesDat.UdpKey.NONE, 0, 0)).isEqualTo(RoutingTable.Outcome.ADDED);
        }
        Lookup started = new Lookup(this.target, kind, this.requests, Ipv4::isLan, T0);
        started.start(table);
        return started;
    }

    private void answer(int ip, int port, KadMessages.Contact... contacts) {
        this.lookup.answered(ip, port, List.of(contacts), this.clock);
    }

    // Ticks once a second up to {@code second} seconds after the start.
    private void tickUntil(Lookup running, long second) {
        while (this.clock < T0 + second * SECOND) {
            this.clock += SECOND;
            running.tick(this.clock);
        }
    }

    private KadMessages.Contact contactAt(UInt128 distance, int ip) {
        return new KadMessages.Contact(this.target.xor(distance), ip, PORT, 4662, 8);
    }

    // Each node in a /24 of its own; small distances are within the request tolerance.
    private static int ip(long d) {
        return Ipv4.of(20, (int) (d >>> 8) & 0xFF, (int) d & 0xFF, 1);
    }

    private static int inTheSubnetOf(long node, int host) {
        return (ip(node) & 0xFFFF_FF00) | host;
    }

    private static UInt128 distance(long d) {
        return new UInt128(d << 32, 0);
    }
}
