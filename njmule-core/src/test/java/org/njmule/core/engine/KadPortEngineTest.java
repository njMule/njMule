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
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.OptionalInt;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.core.FreePorts;
import org.njmule.core.api.EngineConfig;
import org.njmule.core.api.EngineStatus;
import org.njmule.core.api.NjMuleEngine;
import org.njmule.core.config.AppDirectories;
import org.njmule.core.kad.KadIdentity;

/** Kad whose UDP port another program holds: it waits, tries the port again, and starts once the port is free. */
@Timeout(60)
class KadPortEngineTest {

    // Beyond the engine's own retry interval of 10 seconds.
    private static final Duration RETRY_WAIT = Duration.ofSeconds(25);

    @TempDir
    Path dir;

    private int udpPort;
    private DatagramSocket otherProgram;
    private NjMuleEngine engine;
    private EngineStatus status;

    @AfterEach
    void stop() {
        if (this.engine != null) {
            this.engine.close();
        }
        if (this.otherProgram != null) {
            this.otherProgram.close();
        }
    }

    @Test
    void shouldTellTheKadPortAnotherProgramHolds() throws Exception {
        givenAProfileWithKad();
        givenAnotherProgramHoldsTheKadPort();

        whenNjMuleStarts();

        thenKadWaitsForItsPort();
        thenKadDoesNotRun();
    }

    @Test
    void shouldStartKadOnceTheOtherProgramFreesItsPort() throws Exception {
        givenAProfileWithKad();
        givenAnotherProgramHoldsTheKadPort();
        givenNjMuleRunning();

        whenTheOtherProgramFreesThePort();

        thenKadRunsWithin(RETRY_WAIT);
        thenKadWaitsForNoPort();
    }

    @Test
    void shouldStopWaitingForTheKadPortWhenKadIsStoppedByHand() throws Exception {
        givenAProfileWithKad();
        givenAnotherProgramHoldsTheKadPort();
        givenNjMuleRunning();

        whenKadIsStoppedByHand();

        thenKadWaitsForNoPort();
    }

    @Test
    void shouldStopWaitingForTheKadPortWhenKadIsTurnedOff() throws Exception {
        givenAProfileWithKad();
        givenAnotherProgramHoldsTheKadPort();
        givenNjMuleRunning();

        whenKadIsTurnedOffInThePreferences();

        thenKadWaitsForNoPort();
    }

    @Test
    void shouldNotWaitForAPortWhenKadCannotStartForAnotherReason() throws Exception {
        givenAProfileWithKad();
        givenKadCannotWriteItsKeyFile();

        whenNjMuleStarts();

        thenKadWaitsForNoPort();
        thenKadDoesNotRun();
    }

    private void givenAProfileWithKad() throws IOException {
        AppDirectories dirs = AppDirectories.underRoot(this.dir.resolve("njmule"), false);
        Files.createDirectories(dirs.config());
        this.udpPort = FreePorts.udp();
        Files.writeString(
                dirs.config().resolve("njmule.properties"),
                "net.tcpPort=" + FreePorts.tcp() + "\nnet.udpPort=" + this.udpPort
                        + "\nnet.kad.enabled=true\nnet.autoConnect=false\n");
    }

    private void givenAnotherProgramHoldsTheKadPort() throws IOException {
        this.otherProgram = new DatagramSocket(new InetSocketAddress(this.udpPort));
    }

    // A folder that is not empty where Kad writes its UDP key: the write fails, the port is free.
    private void givenKadCannotWriteItsKeyFile() throws IOException {
        Path key = AppDirectories.underRoot(this.dir.resolve("njmule"), false)
                .config()
                .resolve(KadIdentity.KEY_FILE_NAME);
        Files.createDirectories(key);
        Files.writeString(key.resolve("inside.txt"), "taken");
    }

    private void givenNjMuleRunning() throws Exception {
        startNjMule();
    }

    private void whenNjMuleStarts() throws Exception {
        startNjMule();
    }

    private void startNjMule() throws Exception {
        this.engine = NjMuleEngine.start(new EngineConfig(AppDirectories.underRoot(this.dir.resolve("njmule"), false)));
        readStatus();
    }

    private void whenTheOtherProgramFreesThePort() {
        this.otherProgram.close();
    }

    private void whenKadIsStoppedByHand() throws Exception {
        this.engine.disconnectKad().get(5, TimeUnit.SECONDS);
        readStatus();
    }

    private void whenKadIsTurnedOffInThePreferences() throws Exception {
        this.engine
                .updatePreferences(p -> p.toBuilder().kadEnabled(false).build())
                .get(5, TimeUnit.SECONDS);
        readStatus();
    }

    private void readStatus() throws Exception {
        this.status = this.engine.status().get(5, TimeUnit.SECONDS);
    }

    private void thenKadWaitsForItsPort() {
        assertThat(this.status.kadPortTaken()).isEqualTo(OptionalInt.of(this.udpPort));
    }

    private void thenKadWaitsForNoPort() {
        assertThat(this.status.kadPortTaken()).isEmpty();
    }

    private void thenKadDoesNotRun() {
        assertThat(this.status.kad()).isEmpty();
    }

    private void thenKadRunsWithin(Duration wait) throws Exception {
        long end = System.nanoTime() + wait.toNanos();
        do {
            Thread.sleep(500);
            readStatus();
        } while (this.status.kad().isEmpty() && System.nanoTime() < end);
        assertThat(this.status.kad()).as("Kad started on the freed port").isPresent();
    }
}
