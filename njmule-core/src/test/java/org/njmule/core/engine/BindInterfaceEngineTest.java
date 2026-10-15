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
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.core.api.EngineConfig;
import org.njmule.core.api.EngineStatus;
import org.njmule.core.api.NjMuleEngine;
import org.njmule.core.config.AppDirectories;

/** njMule bound to a network interface: offline while it is gone, online again once one that is there is chosen. */
@Timeout(60)
class BindInterfaceEngineTest {

    // An address of the documentation range, which no computer here has.
    private static final String GONE = "203.0.113.7";

    @TempDir
    Path dir;

    private int tcpPort;
    private NjMuleEngine engine;
    private EngineStatus status;

    @AfterEach
    void stop() {
        if (this.engine != null) {
            this.engine.close();
        }
    }

    @Test
    void shouldStayOfflineWhileTheChosenInterfaceIsGone() throws Exception {
        givenAProfileBoundTo(GONE);
        whenNjMuleStarts();
        thenItIsOfflineFor(GONE);
        thenItsTcpPortIsFree();
    }

    @Test
    void shouldComeBackOnlineWhenAnInterfaceThatIsThereIsChosen() throws Exception {
        givenAProfileBoundTo(GONE);
        givenNjMuleRunning();
        whenTheInterfaceChosenBecomes("");
        thenItIsOnline();
        thenItListensOnItsTcpPort();
    }

    private void givenAProfileBoundTo(String choice) throws IOException {
        AppDirectories dirs = AppDirectories.underRoot(this.dir.resolve("njmule"), false);
        Files.createDirectories(dirs.config());
        try (ServerSocket probe = new ServerSocket(0)) {
            this.tcpPort = probe.getLocalPort();
        }
        Files.writeString(
                dirs.config().resolve("njmule.properties"),
                "net.tcpPort=" + this.tcpPort + "\nnet.kad.enabled=false\nnet.bindInterface=" + choice + "\n");
    }

    private void givenNjMuleRunning() throws Exception {
        startNjMule();
    }

    private void whenNjMuleStarts() throws Exception {
        startNjMule();
    }

    private void startNjMule() throws Exception {
        this.engine = NjMuleEngine.start(new EngineConfig(AppDirectories.underRoot(this.dir.resolve("njmule"), false)));
        this.status = this.engine.status().get(5, TimeUnit.SECONDS);
    }

    private void whenTheInterfaceChosenBecomes(String choice) throws Exception {
        this.engine
                .updatePreferences(p -> p.toBuilder().bindInterface(choice).build())
                .get(5, TimeUnit.SECONDS);
        this.status = this.engine.status().get(5, TimeUnit.SECONDS);
    }

    private void thenItIsOfflineFor(String choice) {
        assertThat(this.status.missingInterface()).isEqualTo(Optional.of(choice));
    }

    private void thenItIsOnline() {
        assertThat(this.status.missingInterface()).isEmpty();
    }

    private void thenItsTcpPortIsFree() throws IOException {
        try (ServerSocket again = new ServerSocket()) {
            again.bind(new InetSocketAddress(this.tcpPort));
        }
    }

    private void thenItListensOnItsTcpPort() throws IOException {
        try (Socket call = new Socket(InetAddress.getLoopbackAddress(), this.tcpPort)) {
            assertThat(call.isConnected()).isTrue();
        }
    }
}
