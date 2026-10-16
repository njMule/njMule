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
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.core.FreePorts;
import org.njmule.core.api.EngineConfig;
import org.njmule.core.api.NjMuleEngine;
import org.njmule.core.config.AppDirectories;

@Timeout(60)
class LiveSettingsTest {

    @TempDir
    Path dir;

    private int firstPort;
    private NjMuleEngine engine;

    @AfterEach
    void close() {
        if (this.engine != null) {
            this.engine.close();
        }
    }

    @Test
    void shouldApplyNewPortsAndKadWithoutARestart() throws Exception {
        givenAnEngineListeningOnAFreeTcpPort();
        givenTheTcpPortIsMovedToAnotherFreeOne();
        givenKadIsConnectedByHand();
        givenKadIsDisconnectedByHand();
        givenKadIsTurnedOnInTheSettings();
        givenKadIsTurnedOffInTheSettings();

        whenTheEngineIsClosed();

        thenTheFirstPortIsNoLongerListenedOn();
    }

    private void givenAnEngineListeningOnAFreeTcpPort() throws IOException {
        AppDirectories dirs = AppDirectories.underRoot(this.dir.resolve("njmule"), true);
        Files.createDirectories(dirs.config());
        this.firstPort = FreePorts.tcp();
        Files.writeString(
                dirs.config().resolve("njmule.properties"),
                "net.tcpPort=" + this.firstPort + "\nnet.udpPort=" + FreePorts.udp() + "\nnet.kad.enabled=false\n");
        this.engine = NjMuleEngine.start(new EngineConfig(dirs));
        assertThat(accepts(this.firstPort)).isTrue();
    }

    private void givenTheTcpPortIsMovedToAnotherFreeOne() throws Exception {
        int second = FreePorts.tcp();

        this.engine
                .updatePreferences(p -> p.toBuilder().tcpPort(second).build())
                .get(10, TimeUnit.SECONDS);

        assertThat(accepts(second)).as("the new port listens at once").isTrue();
        assertThat(accepts(this.firstPort)).as("the old one is let go").isFalse();
    }

    private void givenKadIsConnectedByHand() throws Exception {
        assertThat(this.engine.kad().get(5, TimeUnit.SECONDS)).isEmpty();
        this.engine.connectKad().get(10, TimeUnit.SECONDS);
        assertThat(this.engine.kad().get(5, TimeUnit.SECONDS))
                .as("connected by hand")
                .isPresent();
    }

    private void givenKadIsDisconnectedByHand() throws Exception {
        this.engine.disconnectKad().get(10, TimeUnit.SECONDS);
        assertThat(this.engine.kad().get(5, TimeUnit.SECONDS)).isEmpty();
    }

    private void givenKadIsTurnedOnInTheSettings() throws Exception {
        this.engine
                .updatePreferences(p -> p.toBuilder().kadEnabled(true).build())
                .get(10, TimeUnit.SECONDS);
        assertThat(this.engine.kad().get(5, TimeUnit.SECONDS))
                .as("turned on in the settings")
                .isPresent();
    }

    private void givenKadIsTurnedOffInTheSettings() throws Exception {
        this.engine
                .updatePreferences(p -> p.toBuilder().kadEnabled(false).build())
                .get(10, TimeUnit.SECONDS);
        assertThat(this.engine.kad().get(5, TimeUnit.SECONDS)).isEmpty();
    }

    private void whenTheEngineIsClosed() {
        this.engine.close();
    }

    private void thenTheFirstPortIsNoLongerListenedOn() {
        assertThat(accepts(this.firstPort)).as("closed with the engine").isFalse();
    }

    private static boolean accepts(int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 2000);
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
