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
import static org.assertj.core.api.Assertions.within;

import com.google.common.base.Ticker;
import java.io.IOException;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.njmule.core.api.Hash16;
import org.njmule.core.protocol.kad.KadMessages;
import org.njmule.core.protocol.tag.FileTag;
import org.njmule.core.protocol.tag.Tag;
import org.njmule.core.protocol.tag.TagName;
import org.njmule.core.util.Ipv4;

/** How many nodes and files a node estimates the Kad network holds. */
class KadNodeEstimateTest {

    private static final int PORT = 4672;
    private static final long SECOND = 1_000_000_000L;

    @TempDir
    Path dir;

    private final SecureRandom random = new SecureRandom();
    private final AtomicLong time = new AtomicLong(1_000 * SECOND);
    private final Ticker ticker = new Ticker() {
        @Override
        public long read() {
            return KadNodeEstimateTest.this.time.get();
        }
    };
    private KadNode node;
    private int greetings;
    private long estimate;
    private double share;
    private double modifier;

    @BeforeEach
    void create() throws IOException {
        this.node = new KadNode(
                KadIdentity.loadOrCreate(this.dir, this.random),
                (ip, port, packet, receiverId, receiverKey, senderKey) -> {},
                (ip, port, userHash, options) -> {},
                new KadNode.Settings(4662, Hash16.ZERO, 0, ip -> false, ip -> false),
                this.dir,
                this.random,
                this.ticker);
    }

    @Test
    void shouldEstimateAFullBinOfNodesWithoutContacts() {
        whenTheNodesAreEstimated();

        thenTheEstimateIs(RoutingTable.K);
    }

    @Test
    void shouldEstimateTheFilesOfAnAverageNodeWhenTheServersTellNone() {
        whenTheFilesAreEstimatedWithTheServersAverage(0);

        thenTheEstimateIs(108L * RoutingTable.K);
    }

    @Test
    void shouldEstimateTheFilesFromTheServersAverageWhenLarger() {
        whenTheFilesAreEstimatedWithTheServersAverage(500);

        thenTheEstimateIs(500L * RoutingTable.K);
    }

    @Test
    void shouldTellTheShareOfGreetingNodesThatAreUdpFirewalled() {
        givenNodesGreetUs(11, false);
        givenNodesGreetUs(1, true);

        whenTheFirewalledShareIsTaken();

        thenTheShareIs(1.0 / 12);
    }

    @Test
    void shouldTellNoFirewalledShareUntilMoreThanTenOpenNodesGreetUs() {
        givenNodesGreetUs(10, false);
        givenNodesGreetUs(5, true);

        whenTheFirewalledShareIsTaken();

        thenTheShareIs(0);
    }

    @ParameterizedTest(name = "firewalled {0}, share {1}, newer {2} -> {3}")
    @CsvSource({
        "false, 0,    0.5, 1.20",
        "true,  0,    0,   1.20",
        "true,  0,    0.5, 1.30",
        "false, 0.25, 1,   1.25",
        "false, 0.25, 0,   1.20",
        "true,  0.25, 1,   1.40"
    })
    void shouldWeighTheFirewalledNodesByTheShareOfNewerContacts(
            boolean firewalledUdp, double firewalledShare, double newerShare, double expected) {
        whenTheModifierIsTaken(firewalledUdp, firewalledShare, newerShare);

        thenTheModifierIs(expected);
    }

    private void givenNodesGreetUs(int count, boolean udpFirewalled) {
        for (int i = 0; i < count; i++) {
            KadMessages.Self sender = new KadMessages.Self(UInt128.random(this.random), 4662, 8);
            List<Tag> tags =
                    udpFirewalled ? List.of(new Tag.IntTag(TagName.id(FileTag.KADMISCOPTIONS), 0x01)) : List.of();
            int ip = Ipv4.of(20 + this.greetings++, 1, 1, 1);
            this.node.received(ip, PORT, new KadMessages.Hello(sender, tags).encode(false), false, 0, 0);
        }
    }

    private void whenTheNodesAreEstimated() {
        this.estimate = this.node.estimatedNodes();
    }

    private void whenTheFilesAreEstimatedWithTheServersAverage(long average) {
        this.estimate = this.node.estimatedFiles(average);
    }

    private void whenTheFirewalledShareIsTaken() {
        this.share = this.node.udpFirewalledShare();
    }

    private void whenTheModifierIsTaken(boolean firewalledUdp, double firewalledShare, double newerShare) {
        this.modifier = KadNode.firewalledModifier(firewalledUdp, firewalledShare, newerShare);
    }

    private void thenTheEstimateIs(long expected) {
        assertThat(this.estimate).isEqualTo(expected);
    }

    private void thenTheShareIs(double expected) {
        assertThat(this.share).isCloseTo(expected, within(1e-9));
    }

    private void thenTheModifierIs(double expected) {
        assertThat(this.modifier).isCloseTo(expected, within(1e-9));
    }
}
