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
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.core.api.Hash16;
import org.njmule.core.persist.format.NodesDat;
import org.njmule.core.protocol.kad.KadMessages;
import org.njmule.core.protocol.packet.Opcodes;
import org.njmule.core.protocol.packet.Packet;
import org.njmule.core.protocol.packet.Protocol;
import org.njmule.core.protocol.tag.FileTag;
import org.njmule.core.protocol.tag.Tag;
import org.njmule.core.protocol.tag.TagName;
import org.njmule.core.util.Ipv4;

class KadNodeTest {

    private static final int PORT = 4672;
    private static final int PEER = Ipv4.of(20, 1, 1, 1);
    private static final long SECOND = 1_000_000_000L;

    @TempDir
    Path dir;

    private final SecureRandom random = new SecureRandom();
    private final AtomicLong time = new AtomicLong(1_000 * SECOND);
    private final Ticker ticker = new Ticker() {
        @Override
        public long read() {
            return KadNodeTest.this.time.get();
        }
    };
    private final List<Packet> sentToPeer = new ArrayList<>();
    private final Set<Integer> blocked = new HashSet<>();

    @Test
    void learnsContactsOnlyFromAnAnswerToOneOfItsLookups() throws Exception {
        KadNode node = this.node();
        node.addContacts(new NodesDat.Contents(2, false, List.of(this.record(PEER))));
        int blockedIp = Ipv4.of(50, 1, 1, 1);
        this.blocked.add(blockedIp);

        assertThat(node.searchKeyword("ubuntu", Optional.empty(), results -> {}))
                .isTrue();
        this.answer(
                node,
                UInt128.random(this.random),
                this.contact(Ipv4.of(30, 1, 1, 1)),
                this.contact(Ipv4.of(40, 1, 1, 1)));
        assertThat(node.contacts()).as("an answer for another target").isEqualTo(1);

        assertThat(node.findSources(this.hash(), 1000, sources -> {})).isTrue();
        KadMessages.Request request = this.lastRequest();
        List<KadMessages.Contact> tooMany = new ArrayList<>();
        for (int i = 0; i <= request.count(); i++) {
            tooMany.add(this.contact(Ipv4.of(60 + i, 1, 1, 1)));
        }
        this.answer(node, request.target(), tooMany.toArray(KadMessages.Contact[]::new));
        assertThat(node.contacts()).as("more contacts than asked for").isEqualTo(1);

        assertThat(node.findSources(this.hash(), 1000, sources -> {})).isTrue();
        this.answer(node, this.lastRequest().target(), this.contact(Ipv4.of(30, 1, 1, 1)), this.contact(blockedIp));
        assertThat(node.contacts()).as("the filtered address is left out").isEqualTo(2);
    }

    @Test
    void aStoppedKeywordSearchCanStartAgainAtOnce() throws Exception {
        KadNode node = this.node();
        node.addContacts(new NodesDat.Contents(2, false, List.of(this.record(PEER))));

        assertThat(node.searchKeyword("ubuntu", Optional.empty(), results -> {}))
                .isTrue();
        assertThat(node.searchKeyword("ubuntu", Optional.empty(), results -> {}))
                .isFalse();
        node.stopKeywordSearch("ubuntu");
        assertThat(node.searchKeyword("ubuntu", Optional.empty(), results -> {}))
                .isTrue();
    }

    @Test
    void anAnswerThatComesAfterTheLastRequestStillReachesTheSearch() throws Exception {
        KadNode node = this.node();
        UInt128 target = Keywords.id("ubuntu");
        node.addContacts(new NodesDat.Contents(
                2, false, List.of(new NodesDat.Contact(target, PEER, PORT, 4662, 8, 0, NodesDat.UdpKey.NONE, true))));
        List<KadNode.FileResult> found = new ArrayList<>();
        assertThat(node.searchKeyword("ubuntu", Optional.empty(), found::addAll))
                .isTrue();
        this.answer(node, target);
        this.time.addAndGet(4 * SECOND);
        node.tick();
        this.time.addAndGet(4 * SECOND);
        node.tick();

        KadMessages.Entry file = new KadMessages.Entry(
                UInt128.random(this.random),
                List.of(
                        new Tag.StringTag(TagName.id(FileTag.FILENAME), "ubuntu.iso"),
                        new Tag.IntTag(TagName.id(FileTag.FILESIZE), 1000)));
        node.received(PEER, PORT, new KadMessages.SearchAnswer(target, target, List.of(file)).encode(), false, 0, 0);

        assertThat(found).extracting(KadNode.FileResult::name).containsExactly("ubuntu.iso");
    }

    @Test
    void unusableResultsDoNotEndAKeywordSearch() throws Exception {
        KadNode node = this.node();
        UInt128 target = Keywords.id("ubuntu");
        node.addContacts(new NodesDat.Contents(
                2, false, List.of(new NodesDat.Contact(target, PEER, PORT, 4662, 8, 0, NodesDat.UdpKey.NONE, true))));
        assertThat(node.searchKeyword("ubuntu", Optional.empty(), results -> {}))
                .isTrue();
        this.answer(node, target);
        this.time.addAndGet(4 * SECOND);
        node.tick();
        assertThat(this.sentToPeer).extracting(Packet::opcode).contains(Opcodes.Kad.SEARCH_KEY_REQ.code());

        List<KadMessages.Entry> junk = new ArrayList<>();
        for (int i = 0; i < 300; i++) {
            junk.add(new KadMessages.Entry(UInt128.random(this.random), List.of()));
        }
        node.received(PEER, PORT, new KadMessages.SearchAnswer(target, target, junk).encode(), false, 0, 0);
        node.tick();

        assertThat(node.searchKeyword("ubuntu", Optional.empty(), results -> {}))
                .as("the search still runs")
                .isFalse();

        // With no node left to ask, it ends after waiting a little for late answers.
        this.time.addAndGet(16 * SECOND);
        node.tick();
        node.tick();
        assertThat(node.searchKeyword("ubuntu", Optional.empty(), results -> {}))
                .isTrue();
    }

    @Test
    void aFewContactsDoNotReplaceASavedNodesDat() throws Exception {
        KadNode node = this.node();
        node.addContacts(new NodesDat.Contents(2, false, List.of(this.record(PEER))));
        Path file = this.dir.resolve(KadNode.NODES_FILE);

        node.saveNodes();
        assertThat(NodesDat.read(Files.readAllBytes(file)).contacts()).hasSize(1);

        List<NodesDat.Contact> many = new ArrayList<>();
        for (int i = 0; i < KadNode.MIN_SAVED_CONTACTS; i++) {
            many.add(this.record(Ipv4.of(70 + i, 1, 1, 1)));
        }
        Files.write(file, NodesDat.write(many));
        node.saveNodes();
        assertThat(NodesDat.read(Files.readAllBytes(file)).contacts()).hasSize(KadNode.MIN_SAVED_CONTACTS);
    }

    @Test
    void isNoLongerConnectedWhenNothingArrivesForTwentyMinutes() throws Exception {
        KadNode node = this.node();
        node.addContacts(new NodesDat.Contents(2, false, List.of(this.record(PEER))));
        node.received(PEER, PORT, Packet.of(Protocol.KAD, Opcodes.Kad.PING.code(), new byte[0]), false, 0, 0);
        assertThat(node.isConnected()).isTrue();

        for (int s = 0; s < 21 * 60; s += 10) {
            this.time.addAndGet(10 * SECOND);
            node.tick();
        }

        assertThat(node.isConnected()).isFalse();
    }

    private KadNode node() throws IOException {
        return new KadNode(
                KadIdentity.loadOrCreate(this.dir, this.random),
                (ip, port, packet, receiverId, receiverKey, senderKey) -> {
                    if (ip == PEER) {
                        this.sentToPeer.add(packet);
                    }
                },
                (ip, port, userHash, options) -> {},
                new KadNode.Settings(4662, Hash16.ZERO, 0, ip -> false, this.blocked::contains),
                this.dir,
                this.random,
                this.ticker);
    }

    private void answer(KadNode node, UInt128 target, KadMessages.Contact... contacts) {
        node.received(PEER, PORT, new KadMessages.Answer(target, List.of(contacts)).encode(), false, 0, 0);
    }

    private KadMessages.Request lastRequest() throws Exception {
        for (int i = this.sentToPeer.size() - 1; i >= 0; i--) {
            Packet packet = this.sentToPeer.get(i);
            if (packet.opcode() == Opcodes.Kad.REQ.code()) {
                return KadMessages.Request.decode(packet);
            }
        }
        throw new AssertionError("no lookup request sent");
    }

    private Hash16 hash() {
        byte[] bytes = new byte[16];
        this.random.nextBytes(bytes);
        return Hash16.of(bytes);
    }

    private KadMessages.Contact contact(int ip) {
        return new KadMessages.Contact(UInt128.random(this.random), ip, PORT, 4662, 8);
    }

    private NodesDat.Contact record(int ip) {
        return new NodesDat.Contact(UInt128.random(this.random), ip, PORT, 4662, 8, 0, NodesDat.UdpKey.NONE, true);
    }
}
