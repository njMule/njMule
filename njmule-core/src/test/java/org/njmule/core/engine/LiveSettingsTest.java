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
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.core.api.EngineConfig;
import org.njmule.core.api.NjMuleEngine;
import org.njmule.core.config.AppDirectories;

@Timeout(60)
class LiveSettingsTest {

    @TempDir
    Path dir;

    @Test
    void newPortsAndKadApplyWithoutARestart() throws Exception {
        AppDirectories dirs = AppDirectories.underRoot(this.dir.resolve("njmule"), true);
        Files.createDirectories(dirs.config());
        int first = freeTcpPort();
        Files.writeString(
                dirs.config().resolve("njmule.properties"),
                "net.tcpPort=" + first + "\nnet.udpPort=" + freeUdpPort() + "\nnet.kad.enabled=false\n");
        try (NjMuleEngine engine = NjMuleEngine.start(new EngineConfig(dirs))) {
            assertThat(accepts(first)).isTrue();
            int second = freeTcpPort();

            engine.updatePreferences(p -> p.toBuilder().tcpPort(second).build()).get(10, TimeUnit.SECONDS);

            assertThat(accepts(second)).as("the new port listens at once").isTrue();
            assertThat(accepts(first)).as("the old one is let go").isFalse();

            assertThat(engine.kad().get(5, TimeUnit.SECONDS)).isEmpty();
            engine.connectKad().get(10, TimeUnit.SECONDS);
            assertThat(engine.kad().get(5, TimeUnit.SECONDS))
                    .as("connected by hand")
                    .isPresent();
            engine.disconnectKad().get(10, TimeUnit.SECONDS);
            assertThat(engine.kad().get(5, TimeUnit.SECONDS)).isEmpty();

            engine.updatePreferences(p -> p.toBuilder().kadEnabled(true).build())
                    .get(10, TimeUnit.SECONDS);
            assertThat(engine.kad().get(5, TimeUnit.SECONDS))
                    .as("turned on in the settings")
                    .isPresent();
            engine.updatePreferences(p -> p.toBuilder().kadEnabled(false).build())
                    .get(10, TimeUnit.SECONDS);
            assertThat(engine.kad().get(5, TimeUnit.SECONDS)).isEmpty();
        }
        assertThat(accepts(first)).as("closed with the engine").isFalse();
    }

    private static boolean accepts(int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 2000);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static int freeTcpPort() throws IOException {
        try (ServerSocket probe = new ServerSocket(0)) {
            return probe.getLocalPort();
        }
    }

    private static int freeUdpPort() throws IOException {
        try (DatagramSocket probe = new DatagramSocket(0)) {
            return probe.getLocalPort();
        }
    }
}
