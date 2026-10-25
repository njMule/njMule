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
package org.njmule.testkit;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.core.api.DownloadInfo;
import org.njmule.core.api.EngineConfig;
import org.njmule.core.api.Hash16;
import org.njmule.core.api.NjMuleEngine;
import org.njmule.core.config.AppDirectories;

@Timeout(120)
class DownloadLimitFlowTest {

    private static final int LIMIT_KIBPS = 300;

    @TempDir
    Path dir;

    private byte[] data;
    private FakePeer peer;
    private NjMuleEngine engine;
    private Duration took;

    @AfterEach
    void stop() throws IOException {
        if (this.engine != null) {
            this.engine.close();
        }
        if (this.peer != null) {
            this.peer.close();
        }
    }

    @Test
    void shouldHoldTheDownloadLimitAlsoWhenSetWhileRunning() throws Exception {
        givenAPeerSharingRandomBytes("limited.bin", 1_500_000, 31);
        givenNjMuleRunning();
        givenItRanWithoutLimitForMillis(500);
        givenTheDownloadLimitSetTo(LIMIT_KIBPS);

        whenTheLinkOfThePeerIsDownloaded();

        thenTheDownloadTookAtLeastWhatTheLimitAllows(LIMIT_KIBPS);
        thenThePeerSentTheFileOnce();
    }

    private void givenAPeerSharingRandomBytes(String name, int size, long seed) throws IOException {
        this.data = new byte[size];
        new Random(seed).nextBytes(this.data);
        Path shared = Files.createDirectories(this.dir.resolve("shared")).resolve(name);
        Files.write(shared, this.data);
        byte[] userHash = new byte[16];
        new Random(seed).nextBytes(userHash);
        this.peer = FakePeer.start(
                InetAddress.getLoopbackAddress(),
                shared,
                new FakePeer.Config(Hash16.of(userHash), false, FakePeer.Slot.NOW, 0, Set.of()));
    }

    private void givenNjMuleRunning() throws IOException {
        AppDirectories dirs = AppDirectories.underRoot(this.dir.resolve("njmule"), true);
        Files.createDirectories(dirs.config());
        int port = FreePorts.tcp();
        Files.writeString(dirs.config().resolve("njmule.properties"), "net.tcpPort=" + port + "\n");
        this.engine = NjMuleEngine.start(new EngineConfig(dirs));
    }

    // Unlimited first, for a while, then limited.
    private void givenItRanWithoutLimitForMillis(long millis) throws InterruptedException {
        Thread.sleep(millis);
    }

    private void givenTheDownloadLimitSetTo(int kibps) throws Exception {
        this.engine
                .updatePreferences(p -> p.toBuilder().maxDownloadKiBps(kibps).build())
                .get(10, TimeUnit.SECONDS);
    }

    private void whenTheLinkOfThePeerIsDownloaded() throws Exception {
        long start = System.nanoTime();
        DownloadInfo added = this.engine.addDownload(this.peer.link()).get(10, TimeUnit.SECONDS);
        long deadline = start + Duration.ofSeconds(60).toNanos();
        while (this.engine.downloads().get(10, TimeUnit.SECONDS).stream()
                .noneMatch(d -> d.hash().equals(added.hash()) && d.status() == DownloadInfo.Status.COMPLETE)) {
            assertThat(System.nanoTime()).as("the download goes on").isLessThan(deadline);
            Thread.sleep(50);
        }
        this.took = Duration.ofNanos(System.nanoTime() - start);
    }

    // A second of burst and one block ahead at most; unlimited, it takes a fraction of a second.
    private void thenTheDownloadTookAtLeastWhatTheLimitAllows(int kibps) {
        long allowed = kibps * 1024L + 184_320;
        assertThat(this.took)
                .as("%d bytes at %d KiB/s", this.data.length, kibps)
                .isGreaterThan(Duration.ofMillis((this.data.length - allowed) * 1000L / (kibps * 1024L)));
    }

    private void thenThePeerSentTheFileOnce() {
        assertThat(this.peer.bytesSent()).isEqualTo(this.data.length);
    }
}
