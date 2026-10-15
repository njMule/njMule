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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.core.api.Hash16;
import org.njmule.core.persist.format.NodesDat;
import org.njmule.core.protocol.io.DecodeException;
import org.njmule.core.protocol.kad.KadMessages;
import org.njmule.core.protocol.packet.Opcodes;
import org.njmule.core.protocol.packet.Packet;
import org.njmule.core.protocol.packet.Protocol;
import org.njmule.core.protocol.tag.FileTag;
import org.njmule.core.protocol.tag.Tag;
import org.njmule.core.protocol.tag.TagName;
import org.njmule.core.util.Ipv4;

/** Packets and contacts a node does not act upon: plain datagrams from the DNS port, contacts it could not reach. */
class KadNodeUnusableContactsTest {

    private static final int PORT = 4672;
    private static final int PEER = Ipv4.of(20, 1, 1, 1);
    private static final int OTHER = Ipv4.of(21, 1, 1, 1);
    private static final long SECOND = 1_000_000_000L;
    private static final Hash16 FILE = Hash16.parseHex("0123456789abcdef0123456789abcdef");

    /** A packet the node sent. */
    private record Sent(int ip, int port, Packet packet) {}

    @TempDir
    Path dir;

    private final SecureRandom random = new SecureRandom();
    private final AtomicLong time = new AtomicLong(1_000 * SECOND);
    private final Ticker ticker = new Ticker() {
        @Override
        public long read() {
            return KadNodeUnusableContactsTest.this.time.get();
        }
    };
    private final List<Sent> sent = new ArrayList<>();
    private final List<KadNode.FileResult> found = new ArrayList<>();
    private final UInt128 target = UInt128.of(FILE);
    private KadNode node;

    @BeforeEach
    void create() throws IOException {
        this.node = new KadNode(
                KadIdentity.loadOrCreate(this.dir, this.random),
                (ip, port, packet, receiverId, receiverKey, senderKey) -> this.sent.add(new Sent(ip, port, packet)),
                (ip, port, userHash, options) -> {},
                new KadNode.Settings(4662, Hash16.ZERO, 0, ip -> false, ip -> false, PORT),
                this.dir,
                this.random,
                this.ticker);
    }

    @Test
    void shouldDropAPlainPacketFromTheDnsPort() {
        whenAPingArrivesFrom(PEER, 53, 0);

        thenNothingWentTo(PEER);
    }

    @Test
    void shouldAnswerAnObfuscatedPacketFromTheDnsPort() {
        whenAPingArrivesFrom(PEER, 53, 77);

        thenAPongWentTo(PEER, 53);
    }

    @Test
    void shouldNotAskAContactWithoutAUdpPort() {
        givenAContactAt(PEER, farFromTarget());
        givenASourceSearch();

        whenTheContactAnswersWith(
                new KadMessages.Contact(nearTarget(1), Ipv4.of(30, 1, 1, 1), 0, 4662, 8),
                new KadMessages.Contact(nearTarget(2), Ipv4.of(31, 1, 1, 1), PORT, 4662, 8));

        thenNothingWentTo(Ipv4.of(30, 1, 1, 1));
        thenALookupRequestWentTo(Ipv4.of(31, 1, 1, 1));
    }

    @Test
    void shouldNotAskAContactAtTheAddressOfAnotherOfOurContacts() throws DecodeException {
        givenAContactAt(PEER, farFromTarget());
        givenAContactAt(OTHER, farFromTarget());
        givenASourceSearch();

        whenTheContactAnswersWith(new KadMessages.Contact(nearTarget(1), OTHER, PORT, 4662, 8));

        thenNoLookupRequestWentToTheId(OTHER, nearTarget(1));
    }

    @Test
    void shouldTakeAnAvailabilityBeyondAnyRealOneAsUnknown() {
        givenAContactAt(PEER, Keywords.id("ubuntu"));
        givenAKeywordSearchThatAskedTheContact("ubuntu");

        whenTheContactFindsFilesWithSources(70_000, 65_500);

        thenTheSourcesFoundAre(0, 65_500);
    }

    private void givenAContactAt(int ip, UInt128 id) {
        this.node.addContacts(new NodesDat.Contents(
                2, false, List.of(new NodesDat.Contact(id, ip, PORT, 4662, 8, 0, NodesDat.UdpKey.NONE, true))));
    }

    private void givenASourceSearch() {
        assertThat(this.node.findSources(FILE, 1_000, sources -> {})).isTrue();
    }

    private void givenAKeywordSearchThatAskedTheContact(String word) {
        assertThat(this.node.searchKeyword(word, Optional.empty(), this.found::addAll))
                .isPresent();
        this.node.received(PEER, PORT, new KadMessages.Answer(Keywords.id(word), List.of()).encode(), false, 0, 0);
        this.time.addAndGet(4 * SECOND);
        this.node.tick();
    }

    private void whenAPingArrivesFrom(int ip, int port, int senderKey) {
        this.node.received(
                ip, port, Packet.of(Protocol.KAD, Opcodes.Kad.PING.code(), new byte[0]), senderKey != 0, 0, senderKey);
    }

    private void whenTheContactAnswersWith(KadMessages.Contact... contacts) {
        this.node.received(PEER, PORT, new KadMessages.Answer(this.target, List.of(contacts)).encode(), false, 0, 0);
    }

    private void whenTheContactFindsFilesWithSources(long... sources) {
        List<KadMessages.Entry> files = new ArrayList<>();
        for (int i = 0; i < sources.length; i++) {
            files.add(new KadMessages.Entry(
                    new UInt128(i + 1, i + 1),
                    List.of(
                            new Tag.StringTag(TagName.id(FileTag.FILENAME), "ubuntu " + i + ".iso"),
                            new Tag.IntTag(TagName.id(FileTag.FILESIZE), 1_000),
                            new Tag.IntTag(TagName.id(FileTag.SOURCES), sources[i]))));
        }
        UInt128 word = Keywords.id("ubuntu");
        this.node.received(PEER, PORT, new KadMessages.SearchAnswer(word, word, files).encode(), false, 0, 0);
    }

    private void thenNothingWentTo(int ip) {
        assertThat(this.sent).extracting(Sent::ip).doesNotContain(ip);
    }

    private void thenAPongWentTo(int ip, int port) {
        assertThat(this.sent).anySatisfy(s -> {
            assertThat(s.ip()).isEqualTo(ip);
            assertThat(s.port()).isEqualTo(port);
            assertThat(s.packet().opcode()).isEqualTo(Opcodes.Kad.PONG.code());
        });
    }

    private void thenALookupRequestWentTo(int ip) {
        assertThat(this.sent).anySatisfy(s -> {
            assertThat(s.ip()).isEqualTo(ip);
            assertThat(s.packet().opcode()).isEqualTo(Opcodes.Kad.REQ.code());
        });
    }

    private void thenNoLookupRequestWentToTheId(int ip, UInt128 id) throws DecodeException {
        for (Sent s : this.sent) {
            if (s.ip() == ip && s.packet().opcode() == Opcodes.Kad.REQ.code()) {
                assertThat(KadMessages.Request.decode(s.packet()).receiver()).isNotEqualTo(id);
            }
        }
    }

    private void thenTheSourcesFoundAre(int... sources) {
        assertThat(this.found).extracting(KadNode.FileResult::sources).containsExactly(toObjects(sources));
    }

    // Ids the search target shares its top bits with, closer the smaller n.
    private UInt128 nearTarget(long n) {
        return this.target.xor(new UInt128(0, n));
    }

    private UInt128 farFromTarget() {
        return this.target.xor(new UInt128(-1L - this.random.nextInt(1_000), 0));
    }

    private static Integer[] toObjects(int... values) {
        Integer[] boxed = new Integer[values.length];
        for (int i = 0; i < values.length; i++) {
            boxed[i] = values[i];
        }
        return boxed;
    }
}
