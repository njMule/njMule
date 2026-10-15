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
import org.junit.jupiter.api.AfterEach;
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

    private DatagramSocket taken;
    private int udpPort;
    private NjMuleEngine engine;

    @AfterEach
    void close() {
        if (this.engine != null) {
            this.engine.close();
        }
        if (this.taken != null) {
            this.taken.close();
        }
    }

    @Test
    void shouldKeepKadConnectedByHandWhenOtherSettingsChange() throws Exception {
        givenAFreeUdpPort();
        givenAnEngineWithKad(false);
        givenKadIsConnectedByHand();

        whenTheNickIsChangedTo("renamed");

        thenKadIsStillConnected();
    }

    @Test
    void shouldStartKadThatCouldNotTakeItsPortOnTheNextOne() throws Exception {
        givenAUdpPortTakenByAnotherProgram();
        givenAnEngineWithKad(true);
        givenKadDidNotStart();

        whenTheUdpPortIsChangedToAFreeOne();

        thenKadRuns();
    }

    @Test
    void shouldKeepKadStoppedByHandStoppedWhenThePortChanges() throws Exception {
        givenAFreeUdpPort();
        givenAnEngineWithKad(true);
        givenKadStarted();
        givenKadIsDisconnectedByHand();

        whenTheUdpPortIsChangedToAFreeOne();

        thenKadDoesNotRun();
    }

    private void givenAFreeUdpPort() throws IOException {
        this.udpPort = freeUdpPort();
    }

    private void givenAUdpPortTakenByAnotherProgram() throws IOException {
        this.taken = new DatagramSocket(0);
        this.udpPort = this.taken.getLocalPort();
    }

    private void givenAnEngineWithKad(boolean kad) throws IOException {
        this.engine = start(this.udpPort, kad);
    }

    private void givenKadIsConnectedByHand() throws Exception {
        this.engine.connectKad().get(10, TimeUnit.SECONDS);
        assertThat(this.engine.kad().get(5, TimeUnit.SECONDS)).isPresent();
    }

    private void givenKadIsDisconnectedByHand() throws Exception {
        this.engine.disconnectKad().get(10, TimeUnit.SECONDS);
    }

    private void givenKadStarted() throws Exception {
        assertThat(this.engine.kad().get(5, TimeUnit.SECONDS)).isPresent();
    }

    private void givenKadDidNotStart() throws Exception {
        assertThat(this.engine.kad().get(5, TimeUnit.SECONDS)).isEmpty();
    }

    private void whenTheNickIsChangedTo(String nick) throws Exception {
        this.engine.updatePreferences(p -> p.toBuilder().nick(nick).build()).get(10, TimeUnit.SECONDS);
    }

    private void whenTheUdpPortIsChangedToAFreeOne() throws Exception {
        int free = freeUdpPort();
        this.engine.updatePreferences(p -> p.toBuilder().udpPort(free).build()).get(10, TimeUnit.SECONDS);
    }

    private void thenKadIsStillConnected() throws Exception {
        assertThat(this.engine.kad().get(5, TimeUnit.SECONDS))
                .as("still connected")
                .isPresent();
    }

    private void thenKadRuns() throws Exception {
        assertThat(this.engine.kad().get(5, TimeUnit.SECONDS)).isPresent();
    }

    private void thenKadDoesNotRun() throws Exception {
        assertThat(this.engine.kad().get(5, TimeUnit.SECONDS)).isEmpty();
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
