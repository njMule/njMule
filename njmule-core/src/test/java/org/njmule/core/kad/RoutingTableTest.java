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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.njmule.core.persist.format.NodesDat;
import org.njmule.core.util.Ipv4;

class RoutingTableTest {

    private static final int PORT = 4672;

    private final SecureRandom random = new SecureRandom();
    private final RoutingTable table = new RoutingTable(UInt128.random(this.random), ip -> false);

    private List<KadContact> fullSubnet;
    private KadContact moving;
    private int movedFrom;
    private KadContact first;
    private KadContact second;
    private KadContact oldClient;
    private KadContact removed;
    private KadContact verified;
    private KadContact unverified;
    private UInt128 target;
    private KadContact exact;
    private KadContact other;
    private RoutingTable lanTable;
    private int lanAdded;
    private RoutingTable.Outcome outcome;
    private List<KadContact> closest;
    private UInt128 askedId;
    private int askedIp;
    private int askedPort;
    private boolean acceptable;
    private Random walk;
    private RoutingTable walked;
    private List<KadContact> known;

    @Test
    void shouldLetAContactMoveInsideItsOwnFullSubnet() {
        givenAFullSubnet(Ipv4.of(9, 9, 9, 0));
        givenTheFullSubnetRefusesANewContactAt(Ipv4.of(9, 9, 9, 251));

        whenAContactOfTheSubnetMovesTo(Ipv4.of(9, 9, 9, 251));

        thenTheOutcomeIs(RoutingTable.Outcome.UPDATED);
        thenTheMovedContactIsFoundAt(Ipv4.of(9, 9, 9, 251));
        thenNoContactIsFoundWhereItWas();
    }

    @Test
    void shouldRefuseItself() {
        whenItsOwnIdIsOfferedAt(Ipv4.of(1, 1, 1, 1));

        thenTheContactIsRefusedAs("ourselves");
    }

    @Test
    void shouldRefuseAContactOfVersionOne() {
        whenANewContactIsOffered(Ipv4.of(1, 1, 1, 2), PORT, 1);

        thenTheContactIsRefusedAs("version 1");
    }

    @Test
    void shouldRefusePort53OfAnOldClient() {
        whenANewContactIsOffered(Ipv4.of(1, 1, 1, 3), 53, 5);

        thenTheContactIsRefusedAs("port 53 of an old client");
    }

    @Test
    void shouldAddPort53OfANewerClient() {
        whenANewContactIsOffered(Ipv4.of(2, 1, 1, 3), 53, 6);

        thenTheOutcomeIs(RoutingTable.Outcome.ADDED);
    }

    @Test
    void shouldAddTheHighestPort() {
        whenANewContactIsOffered(Ipv4.of(3, 1, 1, 3), 0xFFFF, 8);

        thenTheOutcomeIs(RoutingTable.Outcome.ADDED);
    }

    @Test
    void shouldRefuseAKnownContactAtAnotherAddressUnlessItIsAnUpdate() {
        givenTwoContactsAt(Ipv4.of(1, 1, 1, 1), Ipv4.of(2, 2, 2, 2));

        whenTheFirstContactIsOffered(Ipv4.of(4, 4, 4, 4), 8, false);

        thenTheContactIsRefusedAs("not an update");
        thenTheTwoContactsStayAt(Ipv4.of(1, 1, 1, 1), Ipv4.of(2, 2, 2, 2));
    }

    @Test
    void shouldRefuseToMoveAKnownContactOntoAnotherOnesAddress() {
        givenTwoContactsAt(Ipv4.of(1, 1, 1, 1), Ipv4.of(2, 2, 2, 2));

        whenTheFirstContactIsOffered(Ipv4.of(2, 2, 2, 2), 8, true);

        thenTheContactIsRefusedAs("onto another contact's address");
        thenTheTwoContactsStayAt(Ipv4.of(1, 1, 1, 1), Ipv4.of(2, 2, 2, 2));
    }

    @Test
    void shouldRefuseToUpdateAKnownContactToAnOlderVersion() {
        givenTwoContactsAt(Ipv4.of(1, 1, 1, 1), Ipv4.of(2, 2, 2, 2));

        whenTheFirstContactIsOffered(Ipv4.of(1, 1, 1, 1), 7, true);

        thenTheContactIsRefusedAs("an older version");
        thenTheTwoContactsStayAt(Ipv4.of(1, 1, 1, 1), Ipv4.of(2, 2, 2, 2));
    }

    @Test
    void shouldKeepThePortOfAnOldClientThatSaidHello() {
        givenAnOldClientThatSaidHelloAt(Ipv4.of(5, 5, 5, 5));

        whenTheOldClientIsUpdatedWithThePort(PORT + 1);

        thenTheContactIsRefusedAs("an old client that said hello keeps its port");
    }

    @Test
    void shouldUpdateAnOldClientThatSaidHelloOnItsOwnPort() {
        givenAnOldClientThatSaidHelloAt(Ipv4.of(5, 5, 5, 5));
        givenTheOldClientRefusedWithThePort(PORT + 1);

        whenTheOldClientIsUpdatedWithThePort(PORT);

        thenTheOutcomeIs(RoutingTable.Outcome.UPDATED);
    }

    @Test
    void shouldNotHoldLanAddressesToTheSubnetLimits() {
        givenATableTakingLanAddresses();

        whenContactsOfTheLanSubnetAreAdded(Ipv4.of(192, 168, 1, 0), 30);

        thenMoreContactsAreAddedThan(10);
    }

    @Test
    void shouldForgetARemovedContact() {
        givenAContactAt(Ipv4.of(7, 7, 7, 7));

        whenTheContactIsRemoved();

        thenNoContactIsFoundAt(Ipv4.of(7, 7, 7, 7));
    }

    @Test
    void shouldFreeThePlaceOfARemovedContact() {
        givenAContactAt(Ipv4.of(7, 7, 7, 7));
        givenTheContactRemoved();

        whenANewContactIsOffered(Ipv4.of(7, 7, 7, 7), PORT, 8);

        thenTheOutcomeIs(RoutingTable.Outcome.ADDED);
    }

    @Test
    void shouldFindNoClosestContactInAnEmptyTable() {
        givenATarget();

        whenTheClosestAreAsked(3, 5);

        thenNoContactIsTheClosest();
    }

    @Test
    void shouldGiveNoClosestContactWhenNoneIsWanted() {
        givenATarget();
        givenThreeContactsOfWhichTheTargetAndAnotherAreVerified();

        whenTheClosestAreAsked(3, 0);

        thenNoContactIsTheClosest();
    }

    @Test
    void shouldGiveTheVerifiedContactsClosestFirst() {
        givenATarget();
        givenThreeContactsOfWhichTheTargetAndAnotherAreVerified();

        whenTheClosestAreAsked(3, 10);

        thenTheClosestAreTheTargetAndTheOtherVerifiedContact();
    }

    @Test
    void shouldGiveOnlyTheClosestContactsOfLowEnoughType() {
        givenATarget();
        givenThreeContactsOfWhichTheTargetAndAnotherAreVerified();
        givenTheOtherVerifiedContactAnswered();

        whenTheClosestAreAsked(2, 10);

        thenTheOnlyClosestIsTheContactThatAnswered();
    }

    @ParameterizedTest
    @CsvSource({
        "VERIFIED,   1.1.1.1,   4672, true",
        "VERIFIED,   5.5.5.5,   4672, false",
        "VERIFIED,   1.1.1.1,   4673, false",
        "UNVERIFIED, 5.5.5.5,   4672, true",
        "UNKNOWN,    2.2.2.2,   4672, false",
        "UNKNOWN,    9.9.9.251, 4672, false",
        "UNKNOWN,    5.5.5.5,   4672, true"
    })
    void shouldLetALookupAskOnlyContactsThatAgreeWithTheTable(Asked who, String ip, int port, boolean expected) {
        givenAContactOfTheTable(Ipv4.of(1, 1, 1, 1), true);
        givenAContactOfTheTable(Ipv4.of(2, 2, 2, 2), false);
        givenAFullSubnet(Ipv4.of(9, 9, 9, 0));

        whenALookupWouldAsk(who, Ipv4.parse(ip), port);

        thenALookupMayAsk(expected);
    }

    @Property(tries = 50)
    void shouldKeepTheTableInShapeWhileContactsComeAndGo(@ForAll long seed) {
        givenARandomWalkFromTheSeed(seed);
        givenATableWhoseLanIsTenSlashEight();

        whenContactsComeAndGoForSteps(400);

        thenTheTableIsInShape();
    }

    private void givenAFullSubnet(int range) {
        this.fullSubnet = fillSubnet(range);
        assertThat(this.fullSubnet).hasSize(10);
    }

    private void givenTheFullSubnetRefusesANewContactAt(int ip) {
        assertThat(add(contact(ip), false)).as("the /24 is full").isEqualTo(RoutingTable.Outcome.REFUSED);
    }

    private void givenTwoContactsAt(int firstIp, int secondIp) {
        this.first = contact(firstIp);
        this.second = contact(secondIp);
        add(this.first, false);
        add(this.second, false);
    }

    private void givenAnOldClientThatSaidHelloAt(int ip) {
        this.oldClient = new KadContact(UInt128.random(this.random), ip, PORT, 4662, 5, 0);
        add(this.oldClient, false);
        this.oldClient.setHelloReceived();
    }

    private void givenTheOldClientRefusedWithThePort(int port) {
        assertThat(add(new KadContact(this.oldClient.id(), this.oldClient.ip(), port, 4662, 5, 0), true))
                .as("an old client that said hello keeps its port")
                .isEqualTo(RoutingTable.Outcome.REFUSED);
    }

    private void givenATableTakingLanAddresses() {
        this.lanTable = new RoutingTable(UInt128.random(this.random), Ipv4::isLan);
    }

    private void givenAContactAt(int ip) {
        this.removed = contact(ip);
        add(this.removed, false);
    }

    private void givenTheContactRemoved() {
        this.table.remove(this.removed);
        assertThat(this.table.byAddress(this.removed.ip())).isEmpty();
    }

    private void givenATarget() {
        this.target = UInt128.random(this.random);
    }

    private void givenThreeContactsOfWhichTheTargetAndAnotherAreVerified() {
        this.exact = new KadContact(this.target, Ipv4.of(1, 1, 1, 1), PORT, 4662, 8, 0);
        KadContact notVerified = contact(Ipv4.of(2, 2, 2, 2));
        this.other = contact(Ipv4.of(3, 3, 3, 3));
        for (KadContact c : List.of(this.exact, notVerified, this.other)) {
            add(c, false);
        }
        this.exact.setVerified(true);
        this.other.setVerified(true);
    }

    private void givenTheOtherVerifiedContactAnswered() {
        this.other.alive(0);
    }

    private void givenAContactOfTheTable(int ip, boolean isVerified) {
        KadContact c = contact(ip);
        c.setVerified(isVerified);
        assertThat(add(c, false)).isEqualTo(RoutingTable.Outcome.ADDED);
        if (isVerified) {
            this.verified = c;
        } else {
            this.unverified = c;
        }
    }

    private void givenARandomWalkFromTheSeed(long seed) {
        this.walk = new Random(seed);
    }

    private void givenATableWhoseLanIsTenSlashEight() {
        this.walked =
                new RoutingTable(new UInt128(this.walk.nextLong(), this.walk.nextLong()), ip -> (ip >>> 24) == 10);
        this.known = new ArrayList<>();
    }

    private void whenAContactOfTheSubnetMovesTo(int ip) {
        this.moving = this.fullSubnet.getFirst();
        this.movedFrom = this.moving.ip();
        this.outcome = add(new KadContact(this.moving.id(), ip, PORT, 4662, 8, 0), true);
    }

    private void whenItsOwnIdIsOfferedAt(int ip) {
        this.outcome = add(new KadContact(this.table.own(), ip, PORT, 4662, 8, 0), false);
    }

    private void whenANewContactIsOffered(int ip, int udpPort, int version) {
        this.outcome = add(new KadContact(UInt128.random(this.random), ip, udpPort, 4662, version, 0), false);
    }

    private void whenTheFirstContactIsOffered(int ip, int version, boolean update) {
        this.outcome = add(new KadContact(this.first.id(), ip, PORT, 4662, version, 0), update);
    }

    private void whenTheOldClientIsUpdatedWithThePort(int port) {
        this.outcome = add(new KadContact(this.oldClient.id(), this.oldClient.ip(), port, 4662, 5, 0), true);
    }

    private void whenContactsOfTheLanSubnetAreAdded(int range, int count) {
        for (int i = 1; i <= count; i++) {
            KadContact c = contact(range | i);
            if (this.lanTable.add(c, false, NodesDat.UdpKey.NONE, 0, 0) == RoutingTable.Outcome.ADDED) {
                this.lanAdded++;
            }
        }
    }

    private void whenTheContactIsRemoved() {
        this.table.remove(this.removed);
    }

    private void whenTheClosestAreAsked(int maxType, int count) {
        this.closest = this.table.closest(this.target, maxType, count);
    }

    private void whenALookupWouldAsk(Asked who, int ip, int port) {
        this.askedId = switch (who) {
            case VERIFIED -> this.verified.id();
            case UNVERIFIED -> this.unverified.id();
            case UNKNOWN -> UInt128.random(this.random);
        };
        this.askedIp = ip;
        this.askedPort = port;
        this.acceptable = this.table.isAcceptable(this.askedId, ip, port);
    }

    private void whenContactsComeAndGoForSteps(int steps) {
        for (int step = 0; step < steps; step++) {
            int ip = Ipv4.of(
                    this.walk.nextBoolean() ? 10 : 20 + this.walk.nextInt(3),
                    this.walk.nextInt(3),
                    1,
                    this.walk.nextInt(40));
            int op = this.walk.nextInt(10);
            if (op < 7 || this.known.isEmpty()) {
                KadContact c =
                        new KadContact(new UInt128(this.walk.nextLong(), this.walk.nextLong()), ip, PORT, 4662, 8, 0);
                if (this.walked.add(c, false, NodesDat.UdpKey.NONE, 0, 0) == RoutingTable.Outcome.ADDED) {
                    this.known.add(c);
                }
            } else if (op < 9) {
                KadContact c = this.known.get(this.walk.nextInt(this.known.size()));
                this.walked.add(new KadContact(c.id(), ip, PORT, 4662, 8, 0), true, NodesDat.UdpKey.NONE, 0, 0);
            } else {
                this.walked.remove(this.known.remove(this.walk.nextInt(this.known.size())));
            }
            if (step % 97 == 0) {
                this.walked.consolidate();
            }
        }
    }

    private void thenTheOutcomeIs(RoutingTable.Outcome expected) {
        assertThat(this.outcome).isEqualTo(expected);
    }

    private void thenTheContactIsRefusedAs(String why) {
        assertThat(this.outcome).as(why).isEqualTo(RoutingTable.Outcome.REFUSED);
    }

    private void thenTheMovedContactIsFoundAt(int ip) {
        assertThat(this.table.byAddress(ip)).containsSame(this.moving);
    }

    private void thenNoContactIsFoundWhereItWas() {
        assertThat(this.table.byAddress(this.movedFrom)).isEmpty();
    }

    private void thenTheTwoContactsStayAt(int firstIp, int secondIp) {
        assertThat(this.table.byAddress(firstIp)).containsSame(this.first);
        assertThat(this.table.byAddress(secondIp)).containsSame(this.second);
    }

    private void thenMoreContactsAreAddedThan(int limit) {
        assertThat(this.lanAdded).isGreaterThan(limit);
    }

    private void thenNoContactIsFoundAt(int ip) {
        assertThat(this.table.byAddress(ip)).isEmpty();
    }

    private void thenNoContactIsTheClosest() {
        assertThat(this.closest).isEmpty();
    }

    private void thenTheClosestAreTheTargetAndTheOtherVerifiedContact() {
        assertThat(this.closest).containsExactly(this.exact, this.other);
    }

    private void thenTheOnlyClosestIsTheContactThatAnswered() {
        assertThat(this.closest).as("only those that answered").containsExactly(this.other);
    }

    private void thenALookupMayAsk(boolean expected) {
        assertThat(this.acceptable)
                .as("%s at %s:%d", this.askedId, Ipv4.format(this.askedIp), this.askedPort)
                .isEqualTo(expected);
    }

    private void thenTheTableIsInShape() {
        assertShape(this.walked, this.known.size());
    }

    enum Asked {
        VERIFIED,
        UNVERIFIED,
        UNKNOWN
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
            add(contact(Ipv4.of(30 + i, 1, 1, 1)), false);
        }
        List<KadContact> added = new ArrayList<>();
        for (int i = 1; i < 10_000 && added.size() < 10; i++) {
            KadContact c = contact(subnet | (i % 250 + 1));
            if (add(c, false) == RoutingTable.Outcome.ADDED) {
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
