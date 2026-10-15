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
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.core.api.Hash16;
import org.njmule.core.protocol.kad.KadMessages;
import org.njmule.core.protocol.packet.Opcodes;
import org.njmule.core.protocol.packet.Packet;
import org.njmule.core.protocol.packet.Protocol;
import org.njmule.core.protocol.tag.FileTag;
import org.njmule.core.protocol.tag.Tag;
import org.njmule.core.protocol.tag.TagName;
import org.njmule.core.util.Ipv4;

/**
 * A node that proves one id at its address and soon another one moves around the network to place itself where it
 * likes: it is banned; and an answer under another id than the one proven there is not taken.
 */
class KadNodeIdChangeTest {

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
            return KadNodeIdChangeTest.this.time.get();
        }
    };
    private final List<Packet> sentToPeer = new ArrayList<>();
    private final List<KadNode.FileResult> found = new ArrayList<>();
    private KadIdentity identity;
    private KadNode node;

    @BeforeEach
    void create() throws IOException {
        this.identity = KadIdentity.loadOrCreate(this.dir, this.random);
        this.node = new KadNode(
                this.identity,
                (ip, port, packet, receiverId, receiverKey, senderKey) -> {
                    if (ip == PEER) {
                        this.sentToPeer.add(packet);
                    }
                },
                (ip, port, userHash, options) -> {},
                new KadNode.Settings(4662, Hash16.ZERO, 0, ip -> false, ip -> false, PORT),
                this.dir,
                this.random,
                this.ticker);
    }

    @Test
    void shouldBanANodeThatProvesAnotherIdWithinAnHour() {
        givenThePeerProvesTheId(new UInt128(1, 1));

        whenTimePasses(Duration.ofMinutes(30));
        whenThePeerProvesTheId(new UInt128(2, 2));

        thenThePeerIsIgnored(true);
        thenTheNodeHasContacts(0);
    }

    @Test
    void shouldLetANodeChangeItsIdAfterAnHour() {
        givenThePeerProvesTheId(new UInt128(1, 1));

        whenTimePasses(Duration.ofMinutes(61));
        whenThePeerProvesTheId(new UInt128(2, 2));

        thenThePeerIsIgnored(false);
    }

    @Test
    void shouldIgnoreASearchAnswerUnderAnotherIdThanTheOneProvenThere() {
        givenThePeerProvesTheId(Keywords.id(WORD));
        givenAKeywordSearchThatAskedThePeer();

        whenThePeerAnswersUnderTheId(new UInt128(3, 3), "ubuntu forged.iso");
        whenThePeerAnswersUnderTheId(Keywords.id(WORD), "ubuntu real.iso");

        thenTheFilesFoundAre("ubuntu real.iso");
    }

    private void givenThePeerProvesTheId(UInt128 id) {
        whenThePeerProvesTheId(id);
    }

    private void givenAKeywordSearchThatAskedThePeer() {
        assertThat(this.node.searchKeyword(WORD, Optional.empty(), this.found::addAll))
                .isPresent();
        this.node.received(
                PEER,
                PORT,
                new KadMessages.Answer(Keywords.id(WORD), List.of()).encode(),
                true,
                this.identity.verifyKey(PEER),
                55);
        this.time.addAndGet(4 * SECOND);
        this.node.tick();
    }

    // A hello carrying the key we give that address: the node there knows it from us.
    private void whenThePeerProvesTheId(UInt128 id) {
        KadMessages.Self sender = new KadMessages.Self(id, 4662, 8);
        this.node.received(
                PEER,
                PORT,
                new KadMessages.Hello(sender, List.of()).encode(false),
                true,
                this.identity.verifyKey(PEER),
                55);
    }

    private void whenTimePasses(Duration duration) {
        this.time.addAndGet(duration.toNanos());
    }

    private void whenThePeerAnswersUnderTheId(UInt128 sender, String name) {
        KadMessages.Entry file = new KadMessages.Entry(
                UInt128.random(this.random),
                List.of(
                        new Tag.StringTag(TagName.id(FileTag.FILENAME), name),
                        new Tag.IntTag(TagName.id(FileTag.FILESIZE), 1_000)));
        this.node.received(
                PEER,
                PORT,
                new KadMessages.SearchAnswer(sender, Keywords.id(WORD), List.of(file)).encode(),
                true,
                this.identity.verifyKey(PEER),
                55);
    }

    private void thenThePeerIsIgnored(boolean ignored) {
        this.sentToPeer.clear();
        this.node.received(PEER, PORT, Packet.of(Protocol.KAD, Opcodes.Kad.PING.code(), new byte[0]), true, 0, 55);
        assertThat(this.sentToPeer).as("the answer to a ping").hasSize(ignored ? 0 : 1);
    }

    private void thenTheNodeHasContacts(int count) {
        assertThat(this.node.contacts()).isEqualTo(count);
    }

    private void thenTheFilesFoundAre(String... names) {
        assertThat(this.found).extracting(KadNode.FileResult::name).containsExactly(names);
    }
}
