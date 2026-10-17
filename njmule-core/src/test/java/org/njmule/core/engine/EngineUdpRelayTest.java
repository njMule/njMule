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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.core.FreePorts;
import org.njmule.core.api.EngineConfig;
import org.njmule.core.api.NjMuleEngine;
import org.njmule.core.config.AppDirectories;
import org.njmule.core.config.ProxySettings;
import org.njmule.core.net.FakeSocks5Proxy;

/** The server and Kad UDP sockets of an engine with a SOCKS5 proxy go through its relay. */
@Timeout(60)
class EngineUdpRelayTest {

    @TempDir
    Path dir;

    private FakeSocks5Proxy proxy;
    private NjMuleEngine engine;

    @AfterEach
    void stop() throws IOException {
        if (this.engine != null) {
            this.engine.close();
        }
        if (this.proxy != null) {
            this.proxy.close();
        }
    }

    @Test
    void shouldAskTheProxyForARelayForTheServerAndKadSockets() throws Exception {
        givenASocksProxy();

        whenNjMuleStartsWith(proxySettings());

        thenTheProxyIsAskedForRelays(2);
    }

    @Test
    void shouldAskTheProxyForRelaysWhenItIsChosen() throws Exception {
        givenASocksProxy();
        givenNjMuleRunningWith("");

        whenTheProxyIsChosen();

        thenTheProxyIsAskedForRelays(2);
    }

    @Test
    void shouldEndTheRelaysWhenNjMuleStops() throws Exception {
        givenASocksProxy();
        givenNjMuleRunningWith(proxySettings());
        givenTheProxyWasAskedForRelays(2);

        whenNjMuleStops();

        thenTheProxySawRelaysEnd(2);
    }

    private void givenASocksProxy() throws IOException {
        this.proxy = FakeSocks5Proxy.start();
    }

    private void givenNjMuleRunningWith(String settings) throws IOException {
        whenNjMuleStartsWith(settings);
    }

    private void givenTheProxyWasAskedForRelays(int count) throws InterruptedException {
        thenTheProxyIsAskedForRelays(count);
    }

    private void whenNjMuleStartsWith(String settings) throws IOException {
        AppDirectories dirs = AppDirectories.underRoot(this.dir.resolve("njmule"), false);
        Files.createDirectories(dirs.config());
        Files.writeString(
                dirs.config().resolve("njmule.properties"),
                "net.tcpPort=" + FreePorts.tcp() + "\nnet.udpPort=" + FreePorts.udp() + "\nnet.kad.enabled=true\n"
                        + settings + "\n");
        this.engine = NjMuleEngine.start(new EngineConfig(dirs));
    }

    private void whenTheProxyIsChosen() throws Exception {
        ProxySettings chosen = this.proxy.settings();
        this.engine.updatePreferences(p -> p.toBuilder().proxy(chosen).build()).get(5, TimeUnit.SECONDS);
    }

    private void whenNjMuleStops() {
        this.engine.close();
        this.engine = null;
    }

    private void thenTheProxyIsAskedForRelays(int count) throws InterruptedException {
        waitFor("associate", count);
    }

    private void thenTheProxySawRelaysEnd(int count) throws InterruptedException {
        waitFor("ended", count);
    }

    private void waitFor(String event, int count) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (this.proxy.seen().stream().filter(event::equals).count() < count) {
            assertThat(System.nanoTime()).as(event + " in " + this.proxy.seen()).isLessThan(deadline);
            Thread.sleep(50);
        }
    }

    private String proxySettings() {
        ProxySettings settings = this.proxy.settings();
        return "proxy.host=" + settings.host() + "\nproxy.port=" + settings.port() + "\nproxy.type=SOCKS";
    }
}
