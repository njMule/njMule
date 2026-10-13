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
import org.njmule.core.api.Hash16;
import org.njmule.core.protocol.kad.KadMessages;
import org.njmule.core.protocol.packet.Packet;
import org.njmule.core.protocol.tag.FileTag;
import org.njmule.core.protocol.tag.Tag;
import org.njmule.core.protocol.tag.TagName;
import org.njmule.core.util.Ipv4;

@Timeout(120)
class KadSimulationTest {

    private static final int NODES = 200;
    private static final int PORT = 4672;
    private static final long SECOND = 1_000_000_000L;
    // Publish lookups live 140 s: searching earlier may miss a store still on its way.
    private static final int PUBLISH_WAIT = 150;
    private static final int SEARCHES = 3;

    @TempDir
    Path dir;

    private final SecureRandom random = new SecureRandom();
    private final AtomicLong time = new AtomicLong(1_000 * SECOND);
    private final Ticker ticker = new Ticker() {
        @Override
        public long read() {
            return KadSimulationTest.this.time.get();
        }
    };
    private final Map<Integer, KadNode> byIp = new HashMap<>();
    private final List<KadNode> nodes = new ArrayList<>();
    private final List<Integer> ips = new ArrayList<>();
    private final Deque<Runnable> wire = new ArrayDeque<>();
    private final List<int[]> reachabilityChecks = new ArrayList<>();

    @Test
    void nodesFindEachOtherPublishAndSearch() throws Exception {
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
        // The first node only seeded the others; like any node it bootstraps too.
        this.nodes.get(0).bootstrap(this.ips.get(1), PORT);
        // All nodes start together, so their first self lookups (after 3 minutes) see a young
        // network; the second one, after 4 hours, finds the nearest nodes as joining a mature
        // network does.
        this.run(4 * 60 * 60 + 5 * 60);

        int connected = 0;
        long verified = 0;
        long total = 0;
        for (KadNode node : this.nodes) {
            if (node.isConnected()) {
                connected++;
            }
            verified += node.verifiedContacts();
            total += node.contacts();
        }
        List<String> lonely = new ArrayList<>();
        for (int i = 0; i < NODES; i++) {
            if (!this.nodes.get(i).isConnected()) {
                lonely.add(i + ":" + this.nodes.get(i).contacts());
            }
        }
        assertThat(lonely)
                .as(
                        "nodes not connected (index:contacts), %d open, %d checks",
                        this.nodes.stream().filter(n -> !n.isFirewalled()).count(), this.reachabilityChecks.size())
                .isEmpty();
        assertThat(connected).isEqualTo(NODES);
        assertThat(total / NODES)
                .as("contacts per node (%d verified)", verified / NODES)
                .isGreaterThanOrEqualTo(30);
        assertThat(verified / NODES).as("verified contacts per node").isGreaterThanOrEqualTo(10);
        assertThat(this.nodes.stream().filter(KadNode::isReady).count())
                .as("self lookups done")
                .isGreaterThan(NODES * 9 / 10);

        // A keyword published by one node is found by another; with few nodes, the keyword is
        // picked so that some node is close enough to store it.
        String word = this.wordNearSomeNode("pictures");
        Hash16 file = this.hashNearSomeNode();
        List<Tag> tags = List.of(
                new Tag.StringTag(TagName.id(FileTag.FILENAME), "Night Sky " + word + ".zip"),
                new Tag.IntTag(TagName.id(FileTag.FILESIZE), 123_456),
                new Tag.IntTag(TagName.id(FileTag.SOURCES), 3));
        KadNode publisher = this.nodes.get(17);
        assertThat(publisher.publishKeyword(word, List.of(new KadMessages.Entry(UInt128.of(file), tags))))
                .isTrue();
        this.run(PUBLISH_WAIT);
        assertThat(this.nodes.stream().filter(n -> n.indexed()[0] > 0).count())
                .as("nodes that store the keyword")
                .isPositive();
        // A lookup may miss the one node near the key in so small a network: like a user, the
        // searcher tries again.
        List<KadNode.FileResult> found = new ArrayList<>();
        for (int attempt = 0; attempt < SEARCHES && found.isEmpty(); attempt++) {
            assertThat(this.nodes.get(150).searchKeyword(word, Optional.empty(), found::addAll))
                    .isTrue();
            this.run(60);
        }
        assertThat(found).isNotEmpty();
        assertThat(found.getFirst().name()).isEqualTo("Night Sky " + word + ".zip");
        assertThat(found.getFirst().hash()).isEqualTo(file);
        assertThat(found.getFirst().size()).isEqualTo(123_456);

        // A source published by one node is found by another.
        KadNode source = this.nodes.get(42);
        assertThat(source.publishSource(file, 123_456)).isTrue();
        this.run(PUBLISH_WAIT);
        List<KadNode.Source> sources = new ArrayList<>();
        for (int attempt = 0; attempt < SEARCHES && sources.isEmpty(); attempt++) {
            assertThat(this.nodes.get(99).findSources(file, 123_456, sources::addAll))
                    .isTrue();
            this.run(60);
        }
        assertThat(sources).isNotEmpty();
        assertThat(sources.getFirst().ip()).isEqualTo(this.ips.get(42));
        assertThat(sources.getFirst().tcpPort()).isEqualTo(4662);

        // Nodes asked to check our TCP port connected back: most nodes know they are reachable
        // and where other nodes see them.
        assertThat(this.reachabilityChecks).isNotEmpty();
        long open = this.nodes.stream().filter(n -> !n.isFirewalled()).count();
        assertThat(open).isGreaterThan(NODES * 9 / 10);
        for (int i = 0; i < NODES; i++) {
            if (!this.nodes.get(i).isFirewalled()) {
                assertThat(this.nodes.get(i).publicIp()).isEqualTo(this.ips.get(i));
            }
        }

        // The contacts survive a restart through nodes.dat.
        KadNode first = this.nodes.get(5);
        first.saveNodes();
        Path config = this.dir.resolve("node5");
        assertThat(config.resolve(KadNode.NODES_FILE)).exists();
        KadNode again = new KadNode(
                KadIdentity.loadOrCreate(config, this.random),
                (ip, port, packet, id, rk, sk) -> {},
                (ip, port, hash, options) -> {},
                new KadNode.Settings(4662, Hash16.ZERO, 0, ip -> false),
                config,
                this.random,
                this.ticker);
        again.loadNodes();
        assertThat(again.contacts()).isGreaterThan(10);
        assertThat(again.id()).isEqualTo(first.id());
    }

    private String wordNearSomeNode(String base) {
        for (int n = 0; ; n++) {
            String word = base + n;
            if (this.someNodeNear(Keywords.id(word))) {
                return word;
            }
        }
    }

    private Hash16 hashNearSomeNode() {
        while (true) {
            byte[] bytes = new byte[16];
            this.random.nextBytes(bytes);
            Hash16 hash = Hash16.of(bytes);
            if (this.someNodeNear(UInt128.of(hash))) {
                return hash;
            }
        }
    }

    private boolean someNodeNear(UInt128 target) {
        for (KadNode node : this.nodes) {
            if (node.id().xor(target).top32() <= Lookup.TOLERANCE) {
                return true;
            }
        }
        return false;
    }

    private void node(int i) throws Exception {
        int ip = Ipv4.of(20 + i / 200, i % 200, 7, 1);
        Path config = Files.createDirectories(this.dir.resolve("node" + i));
        byte[] hash = new byte[16];
        this.random.nextBytes(hash);
        KadNode node = new KadNode(
                KadIdentity.loadOrCreate(config, this.random),
                (to, port, packet, receiverId, receiverKey, senderKey) ->
                        this.wire.add(() -> this.arrive(ip, to, port, packet, receiverKey, senderKey)),
                (to, port, userHash, options) -> {
                    // The helper connects back and the requester hears it worked.
                    this.reachabilityChecks.add(new int[] {ip, to});
                    this.wire.add(() -> this.byIp.get(to).reachableConfirmed(ip));
                },
                new KadNode.Settings(4662, Hash16.of(hash), 1, x -> false),
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
