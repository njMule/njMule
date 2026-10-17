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
import java.net.DatagramSocket;
import java.net.SocketException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.core.api.Hash16;
import org.njmule.core.engine.EngineLoop;
import org.njmule.core.net.FakeSocks5Proxy;
import org.njmule.core.net.Socks5Udp;
import org.njmule.core.net.UdpRoute;
import org.njmule.core.util.Ipv4;

@Timeout(30)
class KadSocketTest {

    @TempDir
    Path dir;

    // Enough rounds to meet the close race, which most rounds lose on Linux when the port is not waited for.
    private static final int ROUNDS = 20;

    private final EngineLoop loop = EngineLoop.start(1024, "test-engine");
    private final SecureRandom random = new SecureRandom();
    private int busy;
    private KadSocket socketA;
    private KadSocket socketB;
    private KadNode nodeA;
    private KadNode nodeB;
    private FakeSocks5Proxy proxy;
    private Socks5Udp association;

    @AfterEach
    void stop() throws IOException {
        for (KadSocket socket : new KadSocket[] {this.socketA, this.socketB}) {
            if (socket != null) {
                socket.close();
            }
        }
        if (this.association != null) {
            this.association.close();
        }
        if (this.proxy != null) {
            this.proxy.close();
        }
        this.loop.close();
    }

    @Test
    void shouldLetTwoNodesTalkOverObfuscatedDatagrams() throws Exception {
        givenTwoNodesOnTheirOwnSockets();

        whenTheFirstNodeBootstrapsFromTheSecond();

        thenTheFirstNodeLearnsAContactWithinSeconds(20);
        thenTheSecondNodeIsAVerifiedContactOfTheFirst();
        thenTheFirstNodeIsConnected();
        thenTheSecondNodeAddsNobody();
    }

    // The other node sees the relay, answers it, and the first node learns the other's own address.
    @Test
    void shouldLetANodeTalkThroughTheUdpRelayOfASocksProxy() throws Exception {
        givenTwoNodesOnTheirOwnSockets();
        givenTheFirstSocketGoesThroughTheRelayOfAProxy();

        whenTheFirstNodeBootstrapsFromTheSecond();

        thenTheFirstNodeLearnsAContactWithinSeconds(20);
        thenTheSecondNodeIsAVerifiedContactOfTheFirst();
        thenTheProxyRelayedToTheSecondSocket();
    }

    @Test
    void shouldDropADatagramThatCameAroundTheRelay() throws Exception {
        givenTwoNodesOnTheirOwnSockets();
        givenTheFirstSocketGoesThroughTheRelayOfAProxy();

        whenTheSecondNodeBootstrapsFromTheFirstSocketDirectly();

        thenTheFirstNodeHeardNothingWithinSeconds(2);
    }

    @Test
    void shouldLeaveThePortOfAClosedSocketFreeAtOnce() throws Exception {
        whenSocketsAreClosedAndTheirPortsBoundAgain(ROUNDS);

        thenThePortWasBusyTimes(0);
    }

    private void givenTheFirstSocketGoesThroughTheRelayOfAProxy() throws Exception {
        this.proxy = FakeSocks5Proxy.start();
        this.association = Socks5Udp.associate(this.proxy.settings(), null, Duration.ofSeconds(5));
        this.socketA.route(UdpRoute.through(this.association));
    }

    private void givenTwoNodesOnTheirOwnSockets() throws Exception {
        this.socketA = socket("a");
        this.socketB = socket("b");
        this.nodeA = node("a", this.socketA);
        this.nodeB = node("b", this.socketB);
    }

    private void whenTheFirstNodeBootstrapsFromTheSecond() throws Exception {
        onLoop(() -> {
            this.nodeA.bootstrap(Ipv4.of(127, 0, 0, 1), this.socketB.port());
            return null;
        });
    }

    private void whenTheSecondNodeBootstrapsFromTheFirstSocketDirectly() throws Exception {
        onLoop(() -> {
            this.nodeB.bootstrap(Ipv4.of(127, 0, 0, 1), this.socketA.port());
            return null;
        });
    }

    // Each socket receives (its reader waits on it) when it closes, as a running Kad does.
    private void whenSocketsAreClosedAndTheirPortsBoundAgain(int rounds) throws Exception {
        for (int i = 0; i < rounds; i++) {
            KadSocket socket = socket("reuse");
            int port = socket.port();
            Thread.sleep(5);
            socket.close();
            try (DatagramSocket again = new DatagramSocket(port)) {
                assertThat(again.getLocalPort()).isEqualTo(port);
            } catch (SocketException e) {
                this.busy++;
            }
        }
    }

    private void thenTheFirstNodeLearnsAContactWithinSeconds(int seconds) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (onLoop(this.nodeA::contacts) == 0) {
            assertThat(System.nanoTime()).isLessThan(deadline);
            Thread.sleep(20);
        }
    }

    // The answer came back obfuscated with the key A gave B: B is a verified contact.
    private void thenTheSecondNodeIsAVerifiedContactOfTheFirst() throws Exception {
        assertThat(onLoop(this.nodeA::verifiedContacts)).isEqualTo(1);
    }

    private void thenTheFirstNodeIsConnected() throws Exception {
        assertThat(onLoop(this.nodeA::isConnected)).isTrue();
    }

    private void thenTheSecondNodeAddsNobody() throws Exception {
        assertThat(onLoop(this.nodeB::contacts))
                .as("a bootstrap request adds nobody")
                .isZero();
    }

    private void thenTheProxyRelayedToTheSecondSocket() {
        assertThat(this.proxy.seen()).contains("relay to 127.0.0.1:" + this.socketB.port());
    }

    // Had the first socket answered, the second node would have learnt it as a contact.
    private void thenTheFirstNodeHeardNothingWithinSeconds(int seconds) throws Exception {
        Thread.sleep(TimeUnit.SECONDS.toMillis(seconds));
        assertThat(onLoop(this.nodeB::contacts)).isZero();
        assertThat(this.proxy.seen()).noneMatch(e -> e.startsWith("relay to"));
    }

    private void thenThePortWasBusyTimes(int times) {
        assertThat(this.busy).isEqualTo(times);
    }

    private KadSocket socket(String name) throws Exception {
        Path config = Files.createDirectories(this.dir.resolve(name));
        KadIdentity identity = KadIdentity.loadOrCreate(config, this.random);
        return KadSocket.open(0, this.loop, Hash16.ZERO, identity, ip -> false, this.random);
    }

    private KadNode node(String name, KadSocket socket) throws Exception {
        Path config = this.dir.resolve(name);
        KadNode node = new KadNode(
                KadIdentity.loadOrCreate(config, this.random),
                socket,
                (ip, port, hash, options) -> {},
                new KadNode.Settings(4662, Hash16.ZERO, 1, ip -> true, ip -> false),
                config,
                this.random,
                Ticker.systemTicker());
        socket.deliverTo(node);
        return node;
    }

    private <T> T onLoop(Callable<T> task) throws Exception {
        return this.loop.submit(task).get(10, TimeUnit.SECONDS);
    }
}
