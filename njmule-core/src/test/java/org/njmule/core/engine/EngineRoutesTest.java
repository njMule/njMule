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
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.core.FreePorts;
import org.njmule.core.api.EngineConfig;
import org.njmule.core.api.NjMuleEngine;
import org.njmule.core.config.AppDirectories;
import org.njmule.core.config.ProxySettings;
import org.njmule.core.net.ProxyRoute;
import org.njmule.core.net.TcpConnection;
import org.njmule.core.net.TcpConnector;

/** What a stopped engine leaves to the process, whose outgoing connections it routes: nothing of its own. */
@Timeout(60)
class EngineRoutesTest {

    // An address of the documentation range, which no computer here has.
    private static final String GONE = "203.0.113.7";

    @TempDir
    Path dir;

    private final Map<String, NjMuleEngine> engines = new LinkedHashMap<>();
    private ServerSocket listener;
    private int closedPort;
    private TcpConnection connection;
    private Exception exception;

    @AfterEach
    void stop() throws IOException {
        if (this.connection != null) {
            this.connection.close();
        }
        for (NjMuleEngine engine : this.engines.values()) {
            engine.close();
        }
        if (this.listener != null) {
            this.listener.close();
        }
    }

    @Test
    void shouldConnectFromAnyInterfaceOnceAnEngineBoundToAGoneOneStopped() throws Exception {
        givenNjMuleRanAndStopped("bound", "net.bindInterface=" + GONE);
        givenAListenerOnThisComputer();

        whenAConnectionIsMadeToTheListener();

        thenNoExceptionOccurred();
        thenTheListenerHasTheConnection();
    }

    @Test
    void shouldConnectDirectlyOnceAnEngineWithAProxyStopped() throws Exception {
        givenAPortNobodyListensOn();
        givenNjMuleRanAndStopped("proxied", socksProxyAtTheClosedPort());
        givenAListenerOnThisComputer();

        whenAConnectionIsMadeToTheListener();

        thenNoExceptionOccurred();
        thenTheListenerHasTheConnection();
        thenTheProxyIs(ProxySettings.NONE);
    }

    @Test
    void shouldKeepTheProxyOfANewerEngineWhenAnOlderOneStops() throws Exception {
        givenAPortNobodyListensOn();
        givenNjMuleRunning("older", "");
        givenNjMuleRunning("newer", socksProxyAtTheClosedPort());

        whenNjMuleStops("older");

        thenTheProxyIs(new ProxySettings(ProxySettings.Type.SOCKS, "127.0.0.1", this.closedPort, "", ""));
    }

    private void givenAPortNobodyListensOn() throws IOException {
        this.closedPort = FreePorts.tcp();
    }

    private void givenNjMuleRunning(String name, String settings) throws IOException {
        AppDirectories dirs = AppDirectories.underRoot(this.dir.resolve(name), false);
        Files.createDirectories(dirs.config());
        Files.writeString(
                dirs.config().resolve("njmule.properties"),
                "net.tcpPort=" + FreePorts.tcp() + "\nnet.kad.enabled=false\n" + settings + "\n");
        this.engines.put(name, NjMuleEngine.start(new EngineConfig(dirs)));
    }

    private void givenNjMuleRanAndStopped(String name, String settings) throws IOException {
        givenNjMuleRunning(name, settings);
        whenNjMuleStops(name);
    }

    private void givenAListenerOnThisComputer() throws IOException {
        this.listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
    }

    private void whenNjMuleStops(String name) {
        this.engines.remove(name).close();
    }

    private void whenAConnectionIsMadeToTheListener() {
        try {
            this.connection = TcpConnector.connectToServer(
                    new InetSocketAddress(InetAddress.getLoopbackAddress(), this.listener.getLocalPort()),
                    false,
                    Duration.ofSeconds(5),
                    new SecureRandom());
        } catch (IOException e) {
            this.exception = e;
        }
    }

    private void thenNoExceptionOccurred() {
        assertThat(this.exception).isNull();
    }

    private void thenTheListenerHasTheConnection() throws IOException {
        this.listener.setSoTimeout(5_000);
        try (Socket accepted = this.listener.accept()) {
            assertThat(accepted.isConnected()).isTrue();
        }
    }

    private void thenTheProxyIs(ProxySettings proxy) {
        assertThat(ProxyRoute.current()).isEqualTo(proxy);
    }

    private String socksProxyAtTheClosedPort() {
        return "proxy.host=127.0.0.1\nproxy.port=" + this.closedPort + "\nproxy.type=SOCKS";
    }
}
