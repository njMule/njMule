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
import java.util.List;
import org.junit.jupiter.api.Test;
import org.njmule.core.files.hash.Ed2k;
import org.njmule.core.persist.format.NodesDat;
import org.njmule.core.protocol.packet.Opcodes.Kad;
import org.njmule.core.util.Ipv4;

class KadBasicsTest {

    private static final long MINUTE = 60_000_000_000L;

    @Test
    void keywordsAreLongEnoughLowerCaseWordsWithoutTheExtension() {
        assertThat(Keywords.of("Ubuntu-24.04.iso")).containsExactly("ubuntu");
        assertThat(Keywords.of("The.Matrix.1999.mkv")).containsExactly("the", "matrix", "1999");
        assertThat(Keywords.of("song - SONG (live).flac"))
                .as("duplicates move to the end; a 4-letter extension stays")
                .containsExactly("song", "live", "flac");
        assertThat(Keywords.of("readme.txt."))
                .as("a trailing delimiter keeps the last word")
                .containsExactly("readme", "txt");
        assertThat(Keywords.of("iso")).containsExactly("iso");
        assertThat(Keywords.of("ÀÉÎ ΣΟΦΙΑ")).containsExactly("àéî", "σοφια");

        byte[] utf8 = "ubuntu".getBytes(StandardCharsets.UTF_8);
        assertThat(Keywords.id("ubuntu")).isEqualTo(UInt128.of(Ed2k.md4(utf8, 0, utf8.length)));
    }

    @Test
    void theLowerCaseTableIsTheNetworkOneNotTodaysUnicode() {
        assertThat(KadLowerCase.toLowerCase('A')).isEqualTo('a');
        assertThat(KadLowerCase.toLowerCase('İ'))
                .as("dotted capital I unchanged")
                .isEqualTo('İ');
        assertThat(KadLowerCase.toLowerCase('ῃ')).isEqualTo('ῌ');
        assertThat(KadLowerCase.toLowerCase('7')).isEqualTo('7');
    }

    @Test
    void theRoutingTableKeepsOneContactPerAddressAndFewPerSubnet() {
        SecureRandom random = new SecureRandom();
        UInt128 own = UInt128.random(random);
        RoutingTable table = new RoutingTable(own, ip -> false);
        int ip = Ipv4.of(1, 2, 3, 4);

        assertThat(table.add(contact(random, ip, 4672), false, NodesDat.UdpKey.NONE, 0, 0))
                .isEqualTo(RoutingTable.Outcome.ADDED);
        assertThat(table.add(contact(random, ip, 4673), false, NodesDat.UdpKey.NONE, 0, 0))
                .as("same address")
                .isEqualTo(RoutingTable.Outcome.REFUSED);
        for (int port : new int[] {0, 0x1_0000, -1}) {
            assertThat(table.add(contact(random, Ipv4.of(9, 8, 7, 6), port), false, NodesDat.UdpKey.NONE, 0, 0))
                    .as("port " + port)
                    .isEqualTo(RoutingTable.Outcome.REFUSED);
        }
        assertThat(table.add(new KadContact(own, Ipv4.of(5, 5, 5, 5), 1, 1, 8, 0), false, NodesDat.UdpKey.NONE, 0, 0))
                .as("ourselves")
                .isEqualTo(RoutingTable.Outcome.REFUSED);
        int added = 0;
        for (int i = 1; i <= 20; i++) {
            if (table.add(contact(random, Ipv4.of(9, 9, 9, i), 4672), false, NodesDat.UdpKey.NONE, 0, 0)
                    == RoutingTable.Outcome.ADDED) {
                added++;
            }
        }
        assertThat(added).as("at most 10 per /24").isLessThanOrEqualTo(10);
        assertThat(table.size()).isEqualTo(1 + added);
    }

    @Test
    void theTableSplitsNearUsAndAnswersTheClosestContacts() {
        SecureRandom random = new SecureRandom();
        UInt128 own = UInt128.random(random);
        RoutingTable table = new RoutingTable(own, ip -> false);
        for (int i = 0; i < 2_000; i++) {
            KadContact c = contact(random, random.nextInt(0x7000_0000) + 0x0100_0000, 4672);
            table.add(c, false, NodesDat.UdpKey.NONE, 0, 0);
            c.setVerified(true);
            c.alive(0);
        }
        assertThat(table.size()).isGreaterThan(RoutingTable.K * 16);
        assertThat(table.leaves().size()).isGreaterThan(16);

        UInt128 target = UInt128.random(random);
        List<KadContact> closest = table.closest(target, 3, 5);
        assertThat(closest).hasSize(5);
        for (int i = 1; i < closest.size(); i++) {
            assertThat(closest.get(i - 1).id().xor(target))
                    .isLessThanOrEqualTo(closest.get(i).id().xor(target));
        }
        for (RoutingTable.Zone zone : table.leaves()) {
            UInt128 id = table.randomIdIn(zone, random);
            for (int bit = 0; bit < zone.level(); bit++) {
                assertThat(id.xor(own).bit(bit))
                        .as("random ids stay in their zone")
                        .isEqualTo(zone.index().bit(UInt128.BITS - zone.level() + bit));
            }
        }
    }

    @Test
    void aContactProvenByItsKeyCannotBeTakenOver() {
        SecureRandom random = new SecureRandom();
        RoutingTable table = new RoutingTable(UInt128.random(random), ip -> false);
        KadContact real = contact(random, Ipv4.of(1, 1, 1, 1), 4672);
        int ourIp = Ipv4.of(2, 2, 2, 2);
        table.add(real, false, new NodesDat.UdpKey(77, ourIp), ourIp, 0);

        KadContact impostor = new KadContact(real.id(), Ipv4.of(3, 3, 3, 3), 4672, 4662, 8, 0);
        assertThat(table.add(impostor, true, new NodesDat.UdpKey(78, ourIp), ourIp, MINUTE))
                .isEqualTo(RoutingTable.Outcome.REFUSED);
        KadContact moved = new KadContact(real.id(), Ipv4.of(3, 3, 3, 3), 4672, 4662, 9, 0);
        assertThat(table.add(moved, true, new NodesDat.UdpKey(77, ourIp), ourIp, MINUTE))
                .isEqualTo(RoutingTable.Outcome.UPDATED);
        assertThat(table.get(real.id()).orElseThrow().ip()).isEqualTo(Ipv4.of(3, 3, 3, 3));
        assertThat(table.get(real.id()).orElseThrow().type()).isEqualTo(2);
    }

    @Test
    void floodsAreDroppedThenBannedAndOnlyAskedAnswersAreTaken() {
        PacketTracking tracking = new PacketTracking();
        int ip = Ipv4.of(4, 4, 4, 4);
        int allowed = 0;
        for (int i = 0; i < 12; i++) {
            if (tracking.allow(ip, Kad.PING, 1000)) {
                allowed++;
            }
        }
        assertThat(allowed).as("two pings a minute").isEqualTo(2);
        assertThat(tracking.isBanned(ip, 1000)).as("9 back to back ban").isTrue();
        assertThat(tracking.isBanned(ip, 1000 + PacketTracking.BAN_TIME.toMillis()))
                .isFalse();

        assertThat(tracking.expected(ip, 4672, Kad.PONG, 2000)).isFalse();
        tracking.sent(ip, 4672, Kad.PING, 2000);
        assertThat(tracking.expected(ip, 4673, Kad.PONG, 2100)).as("other port").isFalse();
        assertThat(tracking.expected(ip, 4672, Kad.PONG, 2100)).isTrue();
        assertThat(tracking.expected(ip, 4672, Kad.PONG, 2200)).as("consumed").isFalse();
        tracking.sent(ip, 4672, Kad.REQ, 3000);
        assertThat(tracking.expected(ip, 4672, Kad.RES, 3000 + PacketTracking.ANSWER_WINDOW.toMillis() + 1))
                .as("too late")
                .isFalse();
    }

    private static KadContact contact(SecureRandom random, int ip, int port) {
        return new KadContact(UInt128.random(random), ip, port, 4662, 8, 0);
    }
}
