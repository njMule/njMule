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
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.core.api.Hash16;
import org.njmule.core.engine.EngineLoop;
import org.njmule.core.util.Ipv4;

@Timeout(30)
class KadSocketTest {

    @TempDir
    Path dir;

    private final EngineLoop loop = EngineLoop.start(1024, "test-engine");
    private final SecureRandom random = new SecureRandom();

    @AfterEach
    void stop() {
        this.loop.close();
    }

    @Test
    void twoNodesTalkOverObfuscatedDatagrams() throws Exception {
        KadSocket socketA = this.socket("a");
        KadSocket socketB = this.socket("b");
        try (socketA;
                socketB) {
            KadNode a = this.node("a", socketA);
            KadNode b = this.node("b", socketB);
            int loopback = Ipv4.of(127, 0, 0, 1);

            this.onLoop(() -> {
                a.bootstrap(loopback, socketB.port());
                return null;
            });
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while (this.onLoop(a::contacts) == 0) {
                assertThat(System.nanoTime()).isLessThan(deadline);
                Thread.sleep(20);
            }
            // The answer came back obfuscated with the key A gave B: B is a verified contact.
            assertThat(this.onLoop(a::verifiedContacts)).isEqualTo(1);
            assertThat(this.onLoop(a::isConnected)).isTrue();
            assertThat(this.onLoop(b::contacts))
                    .as("a bootstrap request adds nobody")
                    .isZero();
        }
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
