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

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.njmule.core.files.hash.Ed2k;
import org.njmule.core.persist.format.NodesDat;
import org.njmule.core.protocol.packet.Opcodes.Kad;
import org.njmule.core.util.Ipv4;

class KadBasicsTest {

    private static final long MINUTE = 60_000_000_000L;
    private static final int ADDRESS = Ipv4.of(4, 4, 4, 4);

    private final SecureRandom random = new SecureRandom();
    private final PacketTracking tracking = new PacketTracking();
    private List<String> keywords;
    private UInt128 keywordId;
    private char lowerCase;
    private UInt128 own;
    private RoutingTable table;
    private RoutingTable.Outcome outcome;
    private int added;
    private UInt128 target;
    private List<KadContact> closest;
    private final Map<RoutingTable.Zone, UInt128> randomIds = new LinkedHashMap<>();
    private KadContact real;
    private int ourIp;
    private int pingsLetThrough;
    private boolean answerTaken;

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "Ubuntu-24.04.iso | ubuntu",
                "The.Matrix.1999.mkv | the matrix 1999",
                "iso | iso",
                "ÀÉÎ ΣΟΦΙΑ | àéî σοφια"
            })
    void shouldMakeKeywordsOfLongEnoughLowerCaseWordsWithoutTheExtension(String name, String keywords) {
        whenTheKeywordsOfANameAreMade(name);

        thenTheKeywordsAre(keywords);
    }

    @Test
    void shouldMoveDuplicateKeywordsToTheEndAndKeepAFourLetterExtension() {
        whenTheKeywordsOfANameAreMade("song - SONG (live).flac");

        thenTheKeywordsAre("song live flac", "duplicates move to the end; a 4-letter extension stays");
    }

    @Test
    void shouldKeepTheLastWordBeforeATrailingDelimiter() {
        whenTheKeywordsOfANameAreMade("readme.txt.");

        thenTheKeywordsAre("readme txt", "a trailing delimiter keeps the last word");
    }

    @Test
    void shouldPublishAKeywordUnderTheMd4OfItsUtf8Bytes() {
        whenTheIdOfAKeywordIsMade("ubuntu");

        thenTheIdIsTheMd4OfTheUtf8Bytes("ubuntu");
    }

    @ParameterizedTest
    @CsvSource({"A, a", "ῃ, ῌ", "7, 7"})
    void shouldLowerTheCaseWithTheNetworkTableNotTodaysUnicode(char character, char expected) {
        whenTheCaseOfACharacterIsLowered(character);

        thenTheLowerCaseIs(expected);
    }

    @Test
    void shouldLeaveTheDottedCapitalIUnchanged() {
        whenTheCaseOfACharacterIsLowered('İ');

        thenTheLowerCaseIs('İ', "dotted capital I unchanged");
    }

    @Test
    void shouldAddAContact() {
        givenAnEmptyRoutingTable();

        whenAContactIsAddedAt(Ipv4.of(1, 2, 3, 4), 4672);

        thenTheOutcomeIs(RoutingTable.Outcome.ADDED);
    }

    @Test
    void shouldKeepOneContactPerAddress() {
        givenAnEmptyRoutingTable();
        givenAContactAt(Ipv4.of(1, 2, 3, 4), 4672);

        whenAContactIsAddedAt(Ipv4.of(1, 2, 3, 4), 4673);

        thenTheOutcomeIs(RoutingTable.Outcome.REFUSED, "same address");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 0x1_0000, -1})
    void shouldRefuseAContactWithAnInvalidPort(int port) {
        givenAnEmptyRoutingTable();
        givenAContactAt(Ipv4.of(1, 2, 3, 4), 4672);

        whenAContactIsAddedAt(Ipv4.of(9, 8, 7, 6), port);

        thenTheOutcomeIs(RoutingTable.Outcome.REFUSED, "port " + port);
    }

    @Test
    void shouldRefuseOurselves() {
        givenAnEmptyRoutingTable();
        givenAContactAt(Ipv4.of(1, 2, 3, 4), 4672);

        whenOurOwnIdIsAddedAt(Ipv4.of(5, 5, 5, 5));

        thenTheOutcomeIs(RoutingTable.Outcome.REFUSED, "ourselves");
    }

    @Test
    void shouldKeepFewContactsPerSubnet() {
        givenAnEmptyRoutingTable();
        givenAContactAt(Ipv4.of(1, 2, 3, 4), 4672);

        whenContactsAreAddedInTheSubnetOf(Ipv4.of(9, 9, 9, 0), 20);

        thenAtMostContactsWereAdded(10);
        thenTheTableHoldsTheAddedContactsAnd(1);
    }

    @Test
    void shouldSplitTheTableNearUs() {
        givenAnEmptyRoutingTable();

        whenRandomContactsAreAddedAndVerified(2_000);

        thenTheTableHoldsMoreContactsThan(RoutingTable.K * 16);
        thenTheTableHasMoreLeavesThan(16);
    }

    @Test
    void shouldAnswerTheClosestContactsInOrderOfDistance() {
        givenAnEmptyRoutingTable();
        givenRandomContactsAddedAndVerified(2_000);

        whenTheClosestContactsToARandomTargetAreAsked(3, 5);

        thenTheClosestContactsAre(5);
        thenTheClosestContactsAreInOrderOfDistance();
    }

    @Test
    void shouldDrawRandomIdsThatStayInTheirZone() {
        givenAnEmptyRoutingTable();
        givenRandomContactsAddedAndVerified(2_000);

        whenARandomIdIsDrawnInEveryZone();

        thenEveryRandomIdStaysInItsZone();
    }

    @Test
    void shouldRefuseAnImpostorOfAContactProvenByItsKey() {
        givenAContactProvenByTheKey(77);

        whenAContactWithItsIdIsAddedFrom(Ipv4.of(3, 3, 3, 3), 8, 78);

        thenTheOutcomeIs(RoutingTable.Outcome.REFUSED);
    }

    @Test
    void shouldMoveAContactProvenByItsKey() {
        givenAContactProvenByTheKey(77);

        whenAContactWithItsIdIsAddedFrom(Ipv4.of(3, 3, 3, 3), 9, 77);

        thenTheOutcomeIs(RoutingTable.Outcome.UPDATED);
        thenTheContactIsAt(Ipv4.of(3, 3, 3, 3));
        thenTheTypeOfTheContactIs(2);
    }

    @Test
    void shouldDropAFloodThenBanItsSender() {
        whenPingsArriveFrom(ADDRESS, 12, 1000);

        thenThePingsLetThroughAre(2);
        thenTheSenderIsBannedAt(1000);
        thenTheSenderIsNoLongerBannedAt(1000 + PacketTracking.BAN_TIME.toMillis());
    }

    @Test
    void shouldNotTakeAnAnswerNotAskedFor() {
        whenAnAnswerArrives(ADDRESS, Kad.PONG, 2000);

        thenTheAnswerIsNotTaken();
    }

    @Test
    void shouldNotTakeAnAnswerFromAnotherAddress() {
        givenARequestSentTo(ADDRESS, Kad.PING, 2000);

        whenAnAnswerArrives(ADDRESS + 1, Kad.PONG, 2100);

        thenTheAnswerIsNotTaken("other address");
    }

    @Test
    void shouldNotTakeAnotherAnswer() {
        givenARequestSentTo(ADDRESS, Kad.PING, 2000);

        whenAnAnswerArrives(ADDRESS, Kad.RES, 2100);

        thenTheAnswerIsNotTaken("other answer");
    }

    @Test
    void shouldTakeTheAnswerAskedFor() {
        givenARequestSentTo(ADDRESS, Kad.PING, 2000);

        whenAnAnswerArrives(ADDRESS, Kad.PONG, 2100);

        thenTheAnswerIsTaken();
    }

    @Test
    void shouldTakeAnAnswerOnlyOnce() {
        givenARequestSentTo(ADDRESS, Kad.PING, 2000);
        givenTheAnswerIsTaken(ADDRESS, Kad.PONG, 2100);

        whenAnAnswerArrives(ADDRESS, Kad.PONG, 2200);

        thenTheAnswerIsNotTaken("consumed");
    }

    @Test
    void shouldNotTakeAnAnswerTooLate() {
        givenARequestSentTo(ADDRESS, Kad.REQ, 3000);

        whenAnAnswerArrives(ADDRESS, Kad.RES, 3000 + PacketTracking.ANSWER_WINDOW.toMillis() + 1);

        thenTheAnswerIsNotTaken("too late");
    }

    private void givenAnEmptyRoutingTable() {
        this.own = UInt128.random(this.random);
        this.table = new RoutingTable(this.own, ip -> false);
    }

    private void givenAContactAt(int ip, int port) {
        assertThat(this.table.add(contact(this.random, ip, port), false, NodesDat.UdpKey.NONE, 0, 0))
                .isEqualTo(RoutingTable.Outcome.ADDED);
    }

    private void givenRandomContactsAddedAndVerified(int count) {
        addRandomVerifiedContacts(count);
    }

    private void givenAContactProvenByTheKey(int key) {
        this.table = new RoutingTable(UInt128.random(this.random), ip -> false);
        this.real = contact(this.random, Ipv4.of(1, 1, 1, 1), 4672);
        this.ourIp = Ipv4.of(2, 2, 2, 2);
        this.table.add(this.real, false, new NodesDat.UdpKey(key, this.ourIp), this.ourIp, 0);
    }

    private void givenARequestSentTo(int ip, Kad request, long now) {
        this.tracking.sent(ip, request, now);
    }

    private void givenTheAnswerIsTaken(int ip, Kad answer, long now) {
        assertThat(this.tracking.expected(ip, answer, now)).isTrue();
    }

    private void whenTheKeywordsOfANameAreMade(String name) {
        this.keywords = Keywords.of(name);
    }

    private void whenTheIdOfAKeywordIsMade(String keyword) {
        this.keywordId = Keywords.id(keyword);
    }

    private void whenTheCaseOfACharacterIsLowered(char character) {
        this.lowerCase = KadLowerCase.toLowerCase(character);
    }

    private void whenAContactIsAddedAt(int ip, int port) {
        this.outcome = this.table.add(contact(this.random, ip, port), false, NodesDat.UdpKey.NONE, 0, 0);
    }

    private void whenOurOwnIdIsAddedAt(int ip) {
        this.outcome = this.table.add(new KadContact(this.own, ip, 1, 1, 8, 0), false, NodesDat.UdpKey.NONE, 0, 0);
    }

    private void whenContactsAreAddedInTheSubnetOf(int subnet, int count) {
        for (int i = 1; i <= count; i++) {
            if (this.table.add(contact(this.random, subnet + i, 4672), false, NodesDat.UdpKey.NONE, 0, 0)
                    == RoutingTable.Outcome.ADDED) {
                this.added++;
            }
        }
    }

    private void whenRandomContactsAreAddedAndVerified(int count) {
        addRandomVerifiedContacts(count);
    }

    private void whenTheClosestContactsToARandomTargetAreAsked(int maxType, int count) {
        this.target = UInt128.random(this.random);
        this.closest = this.table.closest(this.target, maxType, count);
    }

    private void whenARandomIdIsDrawnInEveryZone() {
        for (RoutingTable.Zone zone : this.table.leaves()) {
            this.randomIds.put(zone, this.table.randomIdIn(zone, this.random));
        }
    }

    private void whenAContactWithItsIdIsAddedFrom(int ip, int version, int key) {
        KadContact contact = new KadContact(this.real.id(), ip, 4672, 4662, version, 0);
        this.outcome = this.table.add(contact, true, new NodesDat.UdpKey(key, this.ourIp), this.ourIp, MINUTE);
    }

    private void whenPingsArriveFrom(int ip, int count, long now) {
        for (int i = 0; i < count; i++) {
            if (this.tracking.allow(ip, Kad.PING, now)) {
                this.pingsLetThrough++;
            }
        }
    }

    private void whenAnAnswerArrives(int ip, Kad answer, long now) {
        this.answerTaken = this.tracking.expected(ip, answer, now);
    }

    private void thenTheKeywordsAre(String expected) {
        assertThat(this.keywords).containsExactly(expected.split(" "));
    }

    private void thenTheKeywordsAre(String expected, String why) {
        assertThat(this.keywords).as(why).containsExactly(expected.split(" "));
    }

    private void thenTheIdIsTheMd4OfTheUtf8Bytes(String keyword) {
        byte[] utf8 = keyword.getBytes(StandardCharsets.UTF_8);
        assertThat(this.keywordId).isEqualTo(UInt128.of(Ed2k.md4(utf8, 0, utf8.length)));
    }

    private void thenTheLowerCaseIs(char expected) {
        assertThat(this.lowerCase).isEqualTo(expected);
    }

    private void thenTheLowerCaseIs(char expected, String why) {
        assertThat(this.lowerCase).as(why).isEqualTo(expected);
    }

    private void thenTheOutcomeIs(RoutingTable.Outcome expected) {
        assertThat(this.outcome).isEqualTo(expected);
    }

    private void thenTheOutcomeIs(RoutingTable.Outcome expected, String why) {
        assertThat(this.outcome).as(why).isEqualTo(expected);
    }

    private void thenAtMostContactsWereAdded(int count) {
        assertThat(this.added).as("at most 10 per /24").isLessThanOrEqualTo(count);
    }

    private void thenTheTableHoldsTheAddedContactsAnd(int others) {
        assertThat(this.table.size()).isEqualTo(others + this.added);
    }

    private void thenTheTableHoldsMoreContactsThan(int count) {
        assertThat(this.table.size()).isGreaterThan(count);
    }

    private void thenTheTableHasMoreLeavesThan(int count) {
        assertThat(this.table.leaves().size()).isGreaterThan(count);
    }

    private void thenTheClosestContactsAre(int count) {
        assertThat(this.closest).hasSize(count);
    }

    private void thenTheClosestContactsAreInOrderOfDistance() {
        for (int i = 1; i < this.closest.size(); i++) {
            assertThat(this.closest.get(i - 1).id().xor(this.target))
                    .isLessThanOrEqualTo(this.closest.get(i).id().xor(this.target));
        }
    }

    private void thenEveryRandomIdStaysInItsZone() {
        this.randomIds.forEach((zone, id) -> {
            for (int bit = 0; bit < zone.level(); bit++) {
                assertThat(id.xor(this.own).bit(bit))
                        .as("random ids stay in their zone")
                        .isEqualTo(zone.index().bit(UInt128.BITS - zone.level() + bit));
            }
        });
    }

    private void thenTheContactIsAt(int ip) {
        assertThat(this.table.get(this.real.id()).orElseThrow().ip()).isEqualTo(ip);
    }

    private void thenTheTypeOfTheContactIs(int type) {
        assertThat(this.table.get(this.real.id()).orElseThrow().type()).isEqualTo(type);
    }

    private void thenThePingsLetThroughAre(int count) {
        assertThat(this.pingsLetThrough).as("two pings a minute").isEqualTo(count);
    }

    private void thenTheSenderIsBannedAt(long now) {
        assertThat(this.tracking.isBanned(ADDRESS, now))
                .as("9 back to back ban")
                .isTrue();
    }

    private void thenTheSenderIsNoLongerBannedAt(long now) {
        assertThat(this.tracking.isBanned(ADDRESS, now)).isFalse();
    }

    private void thenTheAnswerIsTaken() {
        assertThat(this.answerTaken).isTrue();
    }

    private void thenTheAnswerIsNotTaken() {
        assertThat(this.answerTaken).isFalse();
    }

    private void thenTheAnswerIsNotTaken(String why) {
        assertThat(this.answerTaken).as(why).isFalse();
    }

    private void addRandomVerifiedContacts(int count) {
        for (int i = 0; i < count; i++) {
            KadContact c = contact(this.random, this.random.nextInt(0x7000_0000) + 0x0100_0000, 4672);
            this.table.add(c, false, NodesDat.UdpKey.NONE, 0, 0);
            c.setVerified(true);
            c.alive(0);
        }
    }

    private static KadContact contact(SecureRandom random, int ip, int port) {
        return new KadContact(UInt128.random(random), ip, port, 4662, 8, 0);
    }
}
