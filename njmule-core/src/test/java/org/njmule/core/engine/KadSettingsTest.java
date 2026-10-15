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
import java.net.ServerSocket;
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
class KadSettingsTest {

    @TempDir
    Path dir;

    @Test
    void kadConnectedByHandSurvivesOtherSettings() throws Exception {
        try (NjMuleEngine engine = start(freeUdpPort(), false)) {
            engine.connectKad().get(10, TimeUnit.SECONDS);
            assertThat(engine.kad().get(5, TimeUnit.SECONDS)).isPresent();

            engine.updatePreferences(p -> p.toBuilder().nick("renamed").build()).get(10, TimeUnit.SECONDS);

            assertThat(engine.kad().get(5, TimeUnit.SECONDS))
                    .as("still connected")
                    .isPresent();
        }
    }

    @Test
    void kadThatCouldNotTakeItsPortStartsOnTheNextOne() throws Exception {
        try (DatagramSocket taken = new DatagramSocket(0);
                NjMuleEngine engine = start(taken.getLocalPort(), true)) {
            assertThat(engine.kad().get(5, TimeUnit.SECONDS)).isEmpty();
            int free = freeUdpPort();

            engine.updatePreferences(p -> p.toBuilder().udpPort(free).build()).get(10, TimeUnit.SECONDS);

            assertThat(engine.kad().get(5, TimeUnit.SECONDS)).isPresent();
        }
    }

    @Test
    void kadStoppedByHandStaysStoppedWhenThePortChanges() throws Exception {
        try (NjMuleEngine engine = start(freeUdpPort(), true)) {
            assertThat(engine.kad().get(5, TimeUnit.SECONDS)).isPresent();
            engine.disconnectKad().get(10, TimeUnit.SECONDS);
            int free = freeUdpPort();

            engine.updatePreferences(p -> p.toBuilder().udpPort(free).build()).get(10, TimeUnit.SECONDS);

            assertThat(engine.kad().get(5, TimeUnit.SECONDS)).isEmpty();
        }
    }

    private NjMuleEngine start(int udpPort, boolean kad) throws IOException {
        AppDirectories dirs = AppDirectories.underRoot(this.dir.resolve("njmule"), false);
        Files.createDirectories(dirs.config());
        Files.writeString(
                dirs.config().resolve("njmule.properties"),
                "net.tcpPort=" + freeTcpPort() + "\nnet.udpPort=" + udpPort + "\nnet.kad.enabled=" + kad + "\n");
        return NjMuleEngine.start(new EngineConfig(dirs));
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
