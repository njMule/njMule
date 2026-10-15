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

import com.google.common.base.Ticker;
import java.io.IOException;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.core.api.Hash16;
import org.njmule.core.persist.format.NodesDat;
import org.njmule.core.protocol.kad.KadMessages;
import org.njmule.core.protocol.packet.Opcodes;
import org.njmule.core.protocol.packet.Packet;
import org.njmule.core.protocol.tag.FileTag;
import org.njmule.core.protocol.tag.Tag;
import org.njmule.core.protocol.tag.TagName;
import org.njmule.core.util.Ipv4;

/** The load the nodes storing a keyword report: a heavily loaded keyword is left alone for days. */
class KadNodePublishLoadTest {

    private static final int PORT = 4672;
    private static final int PEER = Ipv4.of(20, 1, 1, 1);
    private static final long SECOND = 1_000_000_000L;
    private static final String WORD = "ubuntu";

    @TempDir
    Path dir;

    private final SecureRandom random = new SecureRandom();
    private final AtomicLong time = new AtomicLong(1_000 * SECOND);
    private final Ticker ticker = new Ticker() {
        @Override
        public long read() {
            return KadNodePublishLoadTest.this.time.get();
        }
    };
    private final List<Packet> sent = new ArrayList<>();
    private KadNode node;
    private boolean started;

    @BeforeEach
    void create() throws IOException {
        this.node = new KadNode(
                KadIdentity.loadOrCreate(this.dir, this.random),
                (ip, port, packet, receiverId, receiverKey, senderKey) -> this.sent.add(packet),
                (ip, port, userHash, options) -> {},
                new KadNode.Settings(4662, Hash16.ZERO, 0, ip -> false, ip -> false, PORT),
                this.dir,
                this.random,
                this.ticker);
        this.node.addContacts(new NodesDat.Contents(
                2,
                false,
                List.of(new NodesDat.Contact(Keywords.id(WORD), PEER, PORT, 4662, 8, 0, NodesDat.UdpKey.NONE, true))));
    }

    @Test
    void shouldLeaveAHeavilyLoadedKeywordAloneForDays() {
        givenAKeywordPublishWhoseNodeReportsTheLoad(60);

        whenTheKeywordIsPublishedAgainAfter(Duration.ofDays(4));

        thenThePublishStarted(false);

        whenTheKeywordIsPublishedAgainAfter(Duration.ofHours(5));

        thenThePublishStarted(true);
    }

    @Test
    void shouldPublishALightlyLoadedKeywordAgainAtOnce() {
        givenAKeywordPublishWhoseNodeReportsTheLoad(20);

        whenTheKeywordIsPublishedAgainAfter(Duration.ZERO);

        thenThePublishStarted(true);
    }

    private void givenAKeywordPublishWhoseNodeReportsTheLoad(int load) {
        UInt128 target = Keywords.id(WORD);
        assertThat(this.node.publishKeyword(WORD, List.of(file()))).isTrue();
        this.node.received(PEER, PORT, new KadMessages.Answer(target, List.of()).encode(), false, 0, 0);
        seconds(4);
        assertThat(this.sent)
                .as("the publish")
                .anySatisfy(p -> assertThat(p.opcode()).isEqualTo(Opcodes.Kad.PUBLISH_KEY_REQ.code()));
        this.node.received(PEER, PORT, new KadMessages.PublishAnswer(target, load).encode(), false, 0, 0);
        seconds(30);
        assertThat(this.node.runningKeywordPublishes()).as("the publish ended").isZero();
    }

    private void whenTheKeywordIsPublishedAgainAfter(Duration wait) {
        this.time.addAndGet(wait.toNanos());
        this.started = this.node.publishKeyword(WORD, List.of(file()));
    }

    private void thenThePublishStarted(boolean started) {
        assertThat(this.started).isEqualTo(started);
    }

    private void seconds(int seconds) {
        for (int s = 0; s < seconds; s++) {
            this.time.addAndGet(SECOND);
            this.node.tick();
        }
    }

    private static KadMessages.Entry file() {
        return new KadMessages.Entry(
                new UInt128(7, 7),
                List.of(
                        new Tag.StringTag(TagName.id(FileTag.FILENAME), "ubuntu.iso"),
                        new Tag.IntTag(TagName.id(FileTag.FILESIZE), 1_000)));
    }
}
