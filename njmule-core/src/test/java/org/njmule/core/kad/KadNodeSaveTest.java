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
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.core.api.Hash16;
import org.njmule.core.persist.format.NodesDat;
import org.njmule.core.protocol.kad.KadMessages;
import org.njmule.core.util.Ipv4;

/**
 * What {@code nodes.dat} keeps: the contacts heard from since Kad started and still alive, and only once there are
 * enough of them to replace the file.
 */
class KadNodeSaveTest {

    private static final int PORT = 4672;
    private static final long SECOND = 1_000_000_000L;
    private static final int LOADED = 30;
    private static final int NODE = Ipv4.of(80, 1, 1, 1);

    @TempDir
    Path dir;

    private final SecureRandom random = new SecureRandom();
    private final AtomicLong time = new AtomicLong(1_000 * SECOND);
    private final Ticker ticker = new Ticker() {
        @Override
        public long read() {
            return KadNodeSaveTest.this.time.get();
        }
    };
    private final List<NodesDat.Contact> loaded = new ArrayList<>();
    private final List<UInt128> heardFrom = new ArrayList<>();
    private byte[] nodesDatBefore;
    private KadNode node;

    @BeforeEach
    void create() throws IOException {
        this.node = new KadNode(
                KadIdentity.loadOrCreate(this.dir, this.random),
                (ip, port, packet, receiverId, receiverKey, senderKey) -> {},
                (ip, port, userHash, options) -> {},
                new KadNode.Settings(4662, Hash16.ZERO, 0, ip -> false, ip -> false, PORT),
                this.dir,
                this.random,
                this.ticker);
    }

    @Test
    void shouldSaveTheLoadedContactsThatSaidHello() throws IOException {
        givenANodesDatWasLoaded();
        givenLoadedContactsSaidHello(KadNode.MIN_SAVED_CONTACTS);

        whenTheContactsAreSaved();

        thenNodesDatHoldsTheContactsHeardFrom();
    }

    @Test
    void shouldLeaveNodesDatAsItWasWhenNoLoadedContactAnswers() throws IOException {
        givenANodesDatWasLoaded();
        givenMinutesPass(2);

        whenTheContactsAreSaved();

        thenNodesDatIsLeftAsItWas();
    }

    @Test
    void shouldLeaveNodesDatAsItWasWhenTooFewLoadedContactsAnswer() throws IOException {
        givenANodesDatWasLoaded();
        givenLoadedContactsSaidHello(KadNode.MIN_SAVED_CONTACTS - 1);

        whenTheContactsAreSaved();

        thenNodesDatIsLeftAsItWas();
    }

    @Test
    void shouldSaveANewNodeThatSaidHello() throws IOException {
        givenANodeSaidHello(NODE);

        whenTheContactsAreSaved();

        thenNodesDatHoldsTheContactsHeardFrom();
    }

    // With no contact yet, those of a bootstrap answer are taken as verified: only the node that answered is proven.
    @Test
    void shouldSaveOnlyTheNodeThatAnsweredABootstrap() throws IOException {
        givenABootstrapAnswerFrom(NODE, 20);

        whenTheContactsAreSaved();

        thenNodesDatHoldsTheContactsHeardFrom();
    }

    @Test
    void shouldNotSaveANodeThatStoppedAnswering() {
        givenANodeSaidHello(NODE);
        givenTheNodeFailedItsCheck();

        whenTheContactsAreSaved();

        thenThereIsNoNodesDat();
    }

    private void givenANodesDatWasLoaded() throws IOException {
        for (int i = 0; i < LOADED; i++) {
            this.loaded.add(new NodesDat.Contact(
                    UInt128.random(this.random),
                    Ipv4.of(20 + i, 1, 1, 1),
                    PORT,
                    4662,
                    8,
                    0,
                    NodesDat.UdpKey.NONE,
                    true));
        }
        this.nodesDatBefore = NodesDat.write(this.loaded);
        Files.write(nodesFile(), this.nodesDatBefore);
        this.node.loadNodes();
        assertThat(this.node.contacts()).isEqualTo(LOADED);
    }

    private void givenLoadedContactsSaidHello(int count) {
        for (NodesDat.Contact c : this.loaded.subList(0, count)) {
            hello(c.id(), c.ip());
        }
    }

    private void givenANodeSaidHello(int ip) {
        hello(UInt128.random(this.random), ip);
        assertThat(this.node.contacts()).isEqualTo(1);
    }

    private void givenABootstrapAnswerFrom(int ip, int contacts) {
        List<KadMessages.Contact> told = new ArrayList<>();
        for (int i = 0; i < contacts; i++) {
            told.add(new KadMessages.Contact(UInt128.random(this.random), Ipv4.of(20 + i, 1, 1, 1), PORT, 4662, 8));
        }
        UInt128 id = UInt128.random(this.random);
        this.node.bootstrap(ip, PORT);
        this.node.received(
                ip,
                PORT,
                new KadMessages.BootstrapAnswer(new KadMessages.Self(id, 4662, 8), told).encode(),
                false,
                0,
                0);
        this.heardFrom.add(id);
        assertThat(this.node.contacts()).isEqualTo(contacts + 1);
        assertThat(this.node.verifiedContacts()).isEqualTo(contacts + 1);
    }

    // Its first check finds it new: it is asked once and counts as dead until it answers.
    private void givenTheNodeFailedItsCheck() {
        givenMinutesPass(1);
        assertThat(this.node.contactInfos())
                .singleElement()
                .satisfies(c -> assertThat(c.type()).isEqualTo(KadContact.DEAD));
    }

    private void givenMinutesPass(int minutes) {
        for (int s = 0; s < minutes * 60; s++) {
            this.time.addAndGet(SECOND);
            this.node.tick();
        }
    }

    private void whenTheContactsAreSaved() {
        this.node.saveNodes();
    }

    private void thenNodesDatHoldsTheContactsHeardFrom() throws IOException {
        assertThat(NodesDat.read(Files.readAllBytes(nodesFile())).contacts())
                .extracting(NodesDat.Contact::id)
                .containsExactlyInAnyOrderElementsOf(this.heardFrom);
    }

    private void thenNodesDatIsLeftAsItWas() throws IOException {
        assertThat(Files.readAllBytes(nodesFile())).isEqualTo(this.nodesDatBefore);
    }

    private void thenThereIsNoNodesDat() {
        assertThat(nodesFile()).doesNotExist();
    }

    private void hello(UInt128 id, int ip) {
        KadMessages.Self sender = new KadMessages.Self(id, 4662, 8);
        this.node.received(ip, PORT, new KadMessages.Hello(sender, List.of()).encode(false), false, 0, 0);
        this.heardFrom.add(id);
    }

    private Path nodesFile() {
        return this.dir.resolve(KadNode.NODES_FILE);
    }
}
