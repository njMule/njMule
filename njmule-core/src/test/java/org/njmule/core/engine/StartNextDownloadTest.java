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
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.core.api.DownloadInfo;
import org.njmule.core.api.Ed2kLink;
import org.njmule.core.api.EngineConfig;
import org.njmule.core.api.Hash16;
import org.njmule.core.api.NjMuleEngine;
import org.njmule.core.config.AppDirectories;

/** Which paused download starts when another one leaves. */
@Timeout(30)
class StartNextDownloadTest {

    @TempDir
    Path root;

    @Test
    void cancellingADownloadThatWaitedStartsNoOther() throws Exception {
        Ed2kLink.FileLink waiting = Ed2kLink.FileLink.of("waiting.bin", 5000, Hash16.parseHex("b1".repeat(16)));
        Ed2kLink.FileLink paused = Ed2kLink.FileLink.of("paused.bin", 5000, Hash16.parseHex("b2".repeat(16)));
        Ed2kLink.FileLink running = Ed2kLink.FileLink.of("running.bin", 5000, Hash16.parseHex("b3".repeat(16)));
        try (NjMuleEngine engine = this.start()) {
            engine.addDownload(waiting).get(5, TimeUnit.SECONDS);
            engine.addDownload(paused).get(5, TimeUnit.SECONDS);
            engine.pauseDownload(waiting.hash()).get(5, TimeUnit.SECONDS);
            engine.pauseDownload(paused.hash()).get(5, TimeUnit.SECONDS);

            assertThat(engine.cancelDownload(waiting.hash()).get(5, TimeUnit.SECONDS))
                    .isTrue();
            assertThat(status(engine, paused.hash()))
                    .as("nothing was running in its place")
                    .isEqualTo(DownloadInfo.Status.PAUSED);

            engine.addDownload(running).get(5, TimeUnit.SECONDS);
            assertThat(engine.cancelDownload(running.hash()).get(5, TimeUnit.SECONDS))
                    .isTrue();
            assertThat(status(engine, paused.hash())).isEqualTo(DownloadInfo.Status.DOWNLOADING);
        }
    }

    private static DownloadInfo.Status status(NjMuleEngine engine, Hash16 hash) throws Exception {
        return engine.downloads().get(5, TimeUnit.SECONDS).stream()
                .filter(d -> d.hash().equals(hash))
                .findFirst()
                .orElseThrow()
                .status();
    }

    private NjMuleEngine start() throws IOException {
        AppDirectories dirs = AppDirectories.underRoot(this.root, true);
        Files.createDirectories(dirs.config());
        int port;
        try (ServerSocket probe = new ServerSocket(0)) {
            port = probe.getLocalPort();
        }
        Files.writeString(
                dirs.config().resolve("njmule.properties"), "net.tcpPort=" + port + "\ndownload.startNext=ANY\n");
        return NjMuleEngine.start(new EngineConfig(dirs));
    }
}
