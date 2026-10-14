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
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.core.api.FileComment;
import org.njmule.core.api.Hash16;
import org.njmule.core.protocol.packet.Packet;
import org.njmule.core.util.Ipv4;

/** Notes travel through a network of in-process nodes: one node's comment reaches another's search. */
@Timeout(120)
class KadNotesSimulationTest {

    private static final int NODES = 60;
    private static final int PORT = 4672;
    private static final long SECOND = 1_000_000_000L;
    private static final long SIZE = 123_456_789L;
    // A small network may miss the node near the file at one try: like a user, the searcher tries again.
    private static final int SEARCHES = 3;
    private static final int PUBLISHER = 7;
    private static final int SEARCHER = 42;

    @TempDir
    Path dir;

    // A fixed seed: the same simulated network at every run, so that a failure can be looked into.
    private final SecureRandom random = seeded();
    private final AtomicLong time = new AtomicLong(1_000 * SECOND);
    private final Ticker ticker = new Ticker() {
        @Override
        public long read() {
            return KadNotesSimulationTest.this.time.get();
        }
    };
    private final Map<Integer, KadNode> byIp = new HashMap<>();
    private final List<KadNode> nodes = new ArrayList<>();
    private final List<Integer> ips = new ArrayList<>();
    private final Deque<Runnable> wire = new ArrayDeque<>();
    private final List<KadNode.Note> found = new ArrayList<>();
    private Hash16 file;
    private KadNode publisher;

    private static SecureRandom seeded() {
        try {
            SecureRandom random = SecureRandom.getInstance("SHA1PRNG");
            random.setSeed(20_261_014L);
            return random;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void shouldFindTheNoteAnotherNodePublished() throws Exception {
        this.givenANetworkThatFoundItself();
        this.givenAFileSomeNodeIsCloseTo();
        this.givenTheNodeAtPublishesItsComment(PUBLISHER, FileComment.of("the real thing", 5));

        this.whenTheNodeAtSearchesForNotes(SEARCHER);

        this.thenSomeNodesStoreTheNote();
        this.thenTheSearchFoundTheNoteOf(this.publisher, FileComment.of("the real thing", 5));
    }

    private void givenANetworkThatFoundItself() throws Exception {
        for (int i = 0; i < NODES; i++) {
            this.node(i);
        }
        for (int i = 1; i < NODES; i++) {
            this.nodes.get(i).bootstrap(this.ips.get(0), PORT);
            if (i > 1) {
                this.nodes.get(i).bootstrap(this.ips.get(this.random.nextInt(i)), PORT);
            }
            this.deliver();
        }
        this.nodes.get(0).bootstrap(this.ips.get(1), PORT);
        // The second lookup of their own ids, after four hours, has the nodes know their nearest neighbours.
        this.run(4 * 60 * 60 + 5 * 60);
    }

    // With few nodes, the file is picked so that a node besides the publisher and the searcher may store its notes.
    private void givenAFileSomeNodeIsCloseTo() {
        while (true) {
            byte[] bytes = new byte[Hash16.LENGTH];
            this.random.nextBytes(bytes);
            Hash16 hash = Hash16.of(bytes);
            UInt128 target = UInt128.of(hash);
            for (int i = 0; i < NODES; i++) {
                if (i != PUBLISHER
                        && i != SEARCHER
                        && this.nodes.get(i).id().xor(target).top32() <= Lookup.TOLERANCE) {
                    this.file = hash;
                    return;
                }
            }
        }
    }

    private void givenTheNodeAtPublishesItsComment(int index, FileComment comment) {
        this.publisher = this.nodes.get(index);
        assertThat(this.publisher.publishNote(this.file, "Night Sky.zip", SIZE, () -> Optional.of(comment)))
                .isTrue();
        // A note publish lives 100 s.
        this.run(110);
    }

    private void whenTheNodeAtSearchesForNotes(int index) {
        KadNode searcher = this.nodes.get(index);
        for (int attempt = 0; attempt < SEARCHES && this.found.isEmpty(); attempt++) {
            assertThat(searcher.findNotes(this.file, SIZE, this.found::addAll, () -> {}))
                    .isTrue();
            this.run(60);
        }
    }

    private void thenSomeNodesStoreTheNote() {
        assertThat(this.nodes.stream().filter(n -> n.indexedNotes() > 0).count())
                .as("nodes that store the note")
                .isPositive();
    }

    private void thenTheSearchFoundTheNoteOf(KadNode node, FileComment comment) {
        assertThat(this.found).isNotEmpty().allSatisfy(n -> {
            assertThat(n.publisher()).isEqualTo(node.id());
            assertThat(n.fileName()).isEqualTo("Night Sky.zip");
            assertThat(n.size()).isEqualTo(SIZE);
            assertThat(n.comment()).isEqualTo(comment);
        });
    }

    private void node(int i) throws Exception {
        int ip = Ipv4.of(30 + i / 200, i % 200, 9, 1);
        Path config = Files.createDirectories(this.dir.resolve("node" + i));
        byte[] hash = new byte[Hash16.LENGTH];
        this.random.nextBytes(hash);
        KadNode node = new KadNode(
                KadIdentity.loadOrCreate(config, this.random),
                (to, port, packet, receiverId, receiverKey, senderKey) ->
                        this.wire.add(() -> this.arrive(ip, to, port, packet, receiverKey, senderKey)),
                (to, port, userHash, options) ->
                        this.wire.add(() -> this.byIp.get(to).reachableConfirmed(ip)),
                new KadNode.Settings(4662, Hash16.of(hash), 1, x -> false, x -> false),
                config,
                this.random,
                this.ticker);
        this.byIp.put(ip, node);
        this.nodes.add(node);
        this.ips.add(ip);
    }

    private void arrive(int from, int to, int port, Packet packet, int receiverKey, int senderKey) {
        KadNode target = this.byIp.get(to);
        if (target != null && port == PORT) {
            target.received(from, PORT, packet, receiverKey != 0, receiverKey, senderKey);
        }
    }

    private void run(int seconds) {
        for (int s = 0; s < seconds; s++) {
            this.time.addAndGet(SECOND);
            for (KadNode node : this.nodes) {
                node.tick();
            }
            this.deliver();
        }
    }

    private void deliver() {
        int budget = 2_000_000;
        while (!this.wire.isEmpty() && budget-- > 0) {
            this.wire.poll().run();
        }
    }
}
