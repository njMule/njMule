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
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.core.api.DownloadInfo;
import org.njmule.core.api.Ed2kLink;
import org.njmule.core.api.EngineConfig;
import org.njmule.core.api.NjMuleEngine;
import org.njmule.core.api.SharedFileInfo;
import org.njmule.core.config.AppDirectories;

@Timeout(90)
class SourceExchangeFlowTest {

    private static final Duration WAIT = Duration.ofSeconds(40);

    @TempDir
    Path dir;

    private final List<AutoCloseable> resources = new ArrayList<>();

    @AfterEach
    void closeEverything() throws Exception {
        Collections.reverse(this.resources);
        for (AutoCloseable resource : this.resources) {
            resource.close();
        }
    }

    @Test
    void anUploaderTellsWhoElseDownloadsTheFile() throws Exception {
        InetAddress lan = ServerFlowTest.lanAddress();
        byte[] data = new byte[2_000_000];
        new Random(11).nextBytes(data);
        Path uploaderRoot = this.dir.resolve("uploader");
        Files.write(Files.createDirectories(uploaderRoot.resolve("incoming")).resolve("x.bin"), data);
        // Slow, so the first downloader is still there when the second asks.
        NjMuleEngine uploader = this.engine(uploaderRoot, "limits.uploadKiBps=1\n");
        SharedFileInfo shared = awaitShared(uploader);
        Ed2kLink.FileLink link =
                (Ed2kLink.FileLink) Ed2kLink.parse(shared.link().toUri()
                        + "|sources,"
                        + lan.getHostAddress()
                        + ":"
                        + uploader.preferences().tcpPort()
                        + "|/");

        NjMuleEngine first = this.engine(this.dir.resolve("first"), "");
        first.addDownload(link).get(10, TimeUnit.SECONDS);
        this.awaitDownloading(first);

        NjMuleEngine second = this.engine(this.dir.resolve("second"), "");
        DownloadInfo added = second.addDownload(link).get(10, TimeUnit.SECONDS);
        assertThat(added.sources()).isEqualTo(1);

        long deadline = System.nanoTime() + WAIT.toNanos();
        while (second.downloads().get().getFirst().sources() < 2) {
            assertThat(System.nanoTime()).as("no source learned").isLessThan(deadline);
            Thread.sleep(100);
        }
    }

    private void awaitDownloading(NjMuleEngine engine) throws Exception {
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (engine.downloads().get().getFirst().transferring() == 0) {
            assertThat(System.nanoTime()).as("no slot").isLessThan(deadline);
            Thread.sleep(100);
        }
    }

    private static SharedFileInfo awaitShared(NjMuleEngine engine) throws Exception {
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (true) {
            List<SharedFileInfo> files = engine.sharedFiles().get(10, TimeUnit.SECONDS);
            if (!files.isEmpty()) {
                return files.getFirst();
            }
            assertThat(System.nanoTime()).as("nothing shared").isLessThan(deadline);
            Thread.sleep(50);
        }
    }

    private NjMuleEngine engine(Path root, String extra) throws IOException {
        AppDirectories dirs = AppDirectories.underRoot(root, true);
        Files.createDirectories(dirs.config());
        int port;
        try (ServerSocket probe = new ServerSocket(0)) {
            port = probe.getLocalPort();
        }
        Files.writeString(dirs.config().resolve("njmule.properties"), "net.tcpPort=" + port + "\n" + extra);
        NjMuleEngine engine = NjMuleEngine.start(new EngineConfig(dirs));
        this.resources.add(engine);
        return engine;
    }
}
