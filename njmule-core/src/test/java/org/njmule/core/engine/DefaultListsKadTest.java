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
package org.njmule.core.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.common.base.Ticker;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.core.api.Hash16;
import org.njmule.core.kad.KadIdentity;
import org.njmule.core.kad.KadNode;
import org.njmule.core.kad.UInt128;
import org.njmule.core.persist.format.NodesDat;
import org.njmule.core.protocol.packet.Opcodes;
import org.njmule.core.protocol.packet.Packet;
import org.njmule.core.util.Ipv4;

/** The Kad contacts shipped with njMule: asked when the profile saved none, or when none of the saved ones answers. */
class DefaultListsKadTest {

    private static final long SECOND = 1_000_000_000L;
    private static final int PORT = 4672;

    /** A packet the node sent. */
    private record Sent(int ip, Packet packet) {}

    @TempDir
    Path dir;

    private final SecureRandom random = new SecureRandom();
    private final AtomicLong time = new AtomicLong(1_000 * SECOND);
    private final Ticker ticker = new Ticker() {
        @Override
        public long read() {
            return DefaultListsKadTest.this.time.get();
        }
    };
    private final List<Sent> sent = new ArrayList<>();
    private KadNode node;
    private String disabled;

    @BeforeEach
    void allowTheShippedLists() throws IOException {
        this.disabled = System.clearProperty(DefaultLists.DISABLED_PROPERTY);
        this.node = new KadNode(
                KadIdentity.loadOrCreate(this.dir, this.random),
                (ip, port, packet, receiverId, receiverKey, senderKey) -> this.sent.add(new Sent(ip, packet)),
                (ip, port, userHash, options) -> {},
                new KadNode.Settings(4662, Hash16.ZERO, 0, ip -> false, ip -> false, PORT),
                this.dir,
                this.random,
                this.ticker);
    }

    @AfterEach
    void restoreTheTestSetting() {
        if (this.disabled != null) {
            System.setProperty(DefaultLists.DISABLED_PROPERTY, this.disabled);
        } else {
            System.clearProperty(DefaultLists.DISABLED_PROPERTY);
        }
    }

    @Test
    void shouldAskTheShippedContactsAtOnceWithoutANodesDat() {
        givenKadLoadsItsContacts();

        whenSecondsPass(1);

        thenOnlyShippedContactsWereAsked();
    }

    @Test
    void shouldAskTheShippedContactsWhenNoSavedContactAnswers() throws IOException {
        givenANodesDatOfContactsThatNeverAnswer();
        givenKadLoadsItsContacts();

        whenSecondsPass(240);

        thenOnlyShippedContactsWereAsked();
    }

    @Test
    void shouldNotAskTheShippedContactsWhenTheDefaultListsAreDisabled() throws IOException {
        givenTheDefaultListsAreDisabled();
        givenANodesDatOfContactsThatNeverAnswer();
        givenKadLoadsItsContacts();

        whenSecondsPass(240);

        thenNoContactWasAskedToBootstrap();
    }

    private void givenTheDefaultListsAreDisabled() {
        System.setProperty(DefaultLists.DISABLED_PROPERTY, "true");
    }

    private void givenANodesDatOfContactsThatNeverAnswer() throws IOException {
        List<NodesDat.Contact> contacts = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            contacts.add(new NodesDat.Contact(
                    UInt128.random(this.random),
                    Ipv4.of(30 + i, 1, 1, 1),
                    PORT,
                    4662,
                    8,
                    0,
                    NodesDat.UdpKey.NONE,
                    true));
        }
        Files.write(this.dir.resolve(KadNode.NODES_FILE), NodesDat.write(contacts));
    }

    private void givenKadLoadsItsContacts() {
        Network.loadKadContacts(this.node);
    }

    private void whenSecondsPass(int seconds) {
        for (int s = 0; s < seconds; s++) {
            this.node.tick();
            this.time.addAndGet(SECOND);
        }
    }

    private void thenOnlyShippedContactsWereAsked() {
        List<Integer> shipped = DefaultLists.nodes().contacts().stream()
                .map(NodesDat.Contact::ip)
                .toList();
        assertThat(bootstrapRequests()).extracting(Sent::ip).isNotEmpty().isSubsetOf(shipped);
    }

    private void thenNoContactWasAskedToBootstrap() {
        assertThat(bootstrapRequests()).isEmpty();
    }

    private List<Sent> bootstrapRequests() {
        return this.sent.stream()
                .filter(s -> s.packet().opcode() == Opcodes.Kad.BOOTSTRAP_REQ.code())
                .toList();
    }
}
