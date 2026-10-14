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

import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.TimeUnit;
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

    @Test
    void theDownloadLimitHoldsAlsoWhenSetWhileRunning() throws Exception {
        byte[] data = new byte[1_500_000];
        new Random(31).nextBytes(data);
        Path shared = Files.createDirectories(this.dir.resolve("shared")).resolve("limited.bin");
        Files.write(shared, data);
        byte[] seed = new byte[16];
        new Random(31).nextBytes(seed);
        try (FakePeer peer = FakePeer.start(
                        InetAddress.getLoopbackAddress(),
                        shared,
                        new FakePeer.Config(Hash16.of(seed), false, FakePeer.Slot.NOW, 0, Set.of()));
                NjMuleEngine engine = this.engine()) {
            // Unlimited first, for a while, then limited.
            Thread.sleep(500);
            engine.updatePreferences(
                            p -> p.toBuilder().maxDownloadKiBps(LIMIT_KIBPS).build())
                    .get(10, TimeUnit.SECONDS);

            long start = System.nanoTime();
            DownloadInfo added = engine.addDownload(peer.link()).get(10, TimeUnit.SECONDS);
            long deadline = start + Duration.ofSeconds(60).toNanos();
            while (engine.downloads().get(10, TimeUnit.SECONDS).stream()
                    .map(DownloadInfo::hash)
                    .toList()
                    .contains(added.hash())) {
                assertThat(System.nanoTime()).as("the download goes on").isLessThan(deadline);
                Thread.sleep(50);
            }
            Duration took = Duration.ofNanos(System.nanoTime() - start);

            // A second of burst and one block ahead at most; unlimited, it takes a fraction of a second.
            long allowed = LIMIT_KIBPS * 1024L + 184_320;
            assertThat(took)
                    .as("%d bytes at %d KiB/s", data.length, LIMIT_KIBPS)
                    .isGreaterThan(Duration.ofMillis((data.length - allowed) * 1000L / (LIMIT_KIBPS * 1024L)));
            assertThat(peer.bytesSent()).isEqualTo(data.length);
        }
    }

    private NjMuleEngine engine() throws Exception {
        AppDirectories dirs = AppDirectories.underRoot(this.dir.resolve("njmule"), true);
        Files.createDirectories(dirs.config());
        int port;
        try (ServerSocket probe = new ServerSocket(0)) {
            port = probe.getLocalPort();
        }
        Files.writeString(dirs.config().resolve("njmule.properties"), "net.tcpPort=" + port + "\n");
        return NjMuleEngine.start(new EngineConfig(dirs));
    }
}
