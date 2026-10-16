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

import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.core.FreePorts;
import org.njmule.core.api.EngineConfig;
import org.njmule.core.api.EngineEvent;
import org.njmule.core.api.NjMuleEngine;
import org.njmule.core.api.ServerAddress;
import org.njmule.core.config.AppDirectories;
import org.njmule.core.persist.format.IpFilterDat;
import org.njmule.core.util.Ipv4;

/** The whitelist of the settings applies at once, against an IP filter that blocks the loopback server. */
@Timeout(30)
class IpWhitelistLiveTest {

    @TempDir
    Path dir;

    private final BlockingQueue<EngineEvent> events = new LinkedBlockingQueue<>();
    private ServerSocket server;
    private NjMuleEngine engine;
    private Throwable failure;

    @AfterEach
    void close() throws Exception {
        if (this.engine != null) {
            this.engine.close();
        }
        if (this.server != null) {
            this.server.close();
        }
    }

    @Test
    void shouldRefuseABlockedServerWithoutWhitelist() throws Exception {
        givenAnEngineWhoseFilterBlocksTheLoopbackServer("");

        whenTheServerIsConnected();

        thenTheConnectionIsRefused();
    }

    @Test
    void shouldConnectToABlockedServerOnceItIsAlwaysAllowed() throws Exception {
        givenAnEngineWhoseFilterBlocksTheLoopbackServer("");
        givenTheWhitelistIsSetTo("127.0.0.0/30");

        whenTheServerIsConnected();

        thenTheEngineConnects();
    }

    @Test
    void shouldLeaveTheServerOnceTheWhitelistNoLongerHoldsIt() throws Exception {
        givenAnEngineWhoseFilterBlocksTheLoopbackServer("127.0.0.1");
        givenTheServerIsConnected();

        whenTheWhitelistIsSetTo("# nobody now");

        thenTheServerIsLeft();
    }

    private void givenAnEngineWhoseFilterBlocksTheLoopbackServer(String whitelist) throws Exception {
        AppDirectories dirs = AppDirectories.underRoot(this.dir.resolve("njmule"), false);
        Files.createDirectories(dirs.config());
        int tcpPort = FreePorts.tcp();
        Files.writeString(
                dirs.config().resolve("njmule.properties"),
                "net.tcpPort=" + tcpPort + "\nnet.kad.enabled=false\nsecurity.ipFilterWhitelist=" + whitelist + "\n");
        Files.write(
                dirs.config().resolve(Network.IPFILTER_FILE),
                IpFilterDat.write(
                        List.of(new IpFilterDat.Range(Ipv4.of(127, 0, 0, 1), Ipv4.of(127, 0, 0, 3), 50, "loopback"))));
        this.server = new ServerSocket(0, 5, InetAddress.getLoopbackAddress());
        this.engine = NjMuleEngine.start(new EngineConfig(dirs));
        this.engine.events().subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(EngineEvent item) {
                IpWhitelistLiveTest.this.events.add(item);
            }

            @Override
            public void onError(Throwable throwable) {}

            @Override
            public void onComplete() {}
        });
    }

    private void givenTheWhitelistIsSetTo(String whitelist) throws Exception {
        whenTheWhitelistIsSetTo(whitelist);
    }

    private void givenTheServerIsConnected() throws Exception {
        whenTheServerIsConnected();
        thenTheEngineConnects();
    }

    private void whenTheWhitelistIsSetTo(String whitelist) throws Exception {
        this.engine
                .updatePreferences(
                        p -> p.toBuilder().ipFilterWhitelist(whitelist).build())
                .get(5, TimeUnit.SECONDS);
    }

    private void whenTheServerIsConnected() throws Exception {
        try {
            this.engine
                    .connect(Optional.of(new ServerAddress("127.0.0.1", this.server.getLocalPort())))
                    .get(5, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            this.failure = e.getCause();
        }
    }

    private void thenTheConnectionIsRefused() {
        assertThat(this.failure).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("IP filter");
    }

    private void thenTheEngineConnects() throws Exception {
        assertThat(this.failure).isNull();
        assertThat(next(EngineEvent.ServerConnecting.class)).isTrue();
    }

    private void thenTheServerIsLeft() throws Exception {
        assertThat(next(EngineEvent.ServerDisconnected.class)).isTrue();
    }

    private boolean next(Class<? extends EngineEvent> type) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (type.isInstance(this.events.poll(100, TimeUnit.MILLISECONDS))) {
                return true;
            }
        }
        return false;
    }
}
