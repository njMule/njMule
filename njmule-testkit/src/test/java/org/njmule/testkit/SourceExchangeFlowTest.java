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
    private NjMuleEngine uploader;
    private Ed2kLink.FileLink link;
    private NjMuleEngine second;
    private DownloadInfo added;

    @AfterEach
    void closeEverything() throws Exception {
        Collections.reverse(this.resources);
        for (AutoCloseable resource : this.resources) {
            resource.close();
        }
    }

    @Test
    void shouldLetAnUploaderTellWhoElseDownloadsTheFile() throws Exception {
        givenAnUploaderSharingRandomBytes("x.bin", 2_000_000, 11);
        givenTheLinkOfTheSharedFileWithTheUploaderAsSource();
        givenAFirstDownloaderGettingTheFile();
        givenASecondDownloaderRunning();

        whenTheSecondDownloaderAddsTheLink();

        thenTheSecondDownloaderStartsWithSources(1);
        thenTheSecondDownloaderSoonLearnsAnotherSource();
    }

    // Slow, so the first downloader is still there when the second asks.
    private void givenAnUploaderSharingRandomBytes(String name, int size, long seed) throws IOException {
        byte[] data = new byte[size];
        new Random(seed).nextBytes(data);
        Path uploaderRoot = this.dir.resolve("uploader");
        Files.write(Files.createDirectories(uploaderRoot.resolve("incoming")).resolve(name), data);
        this.uploader = engine(uploaderRoot, "limits.uploadKiBps=1\n");
    }

    private void givenTheLinkOfTheSharedFileWithTheUploaderAsSource() throws Exception {
        SharedFileInfo shared = awaitShared(this.uploader);
        this.link = (Ed2kLink.FileLink) Ed2kLink.parse(shared.link().toUri()
                + "|sources,"
                + ServerFlowTest.lanAddress().getHostAddress()
                + ":"
                + this.uploader.preferences().tcpPort()
                + "|/");
    }

    private void givenAFirstDownloaderGettingTheFile() throws Exception {
        NjMuleEngine first = engine(this.dir.resolve("first"), "");
        first.addDownload(this.link).get(10, TimeUnit.SECONDS);
        awaitDownloading(first);
    }

    private void givenASecondDownloaderRunning() throws IOException {
        this.second = engine(this.dir.resolve("second"), "");
    }

    private void whenTheSecondDownloaderAddsTheLink() throws Exception {
        this.added = this.second.addDownload(this.link).get(10, TimeUnit.SECONDS);
    }

    private void thenTheSecondDownloaderStartsWithSources(int sources) {
        assertThat(this.added.sources()).isEqualTo(sources);
    }

    private void thenTheSecondDownloaderSoonLearnsAnotherSource() throws Exception {
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (this.second.downloads().get().getFirst().sources() < 2) {
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
        int port = FreePorts.tcp();
        // The fake servers and peers listen on this machine's LAN address, which must be let in.
        Files.writeString(
                dirs.config().resolve("njmule.properties"),
                "net.tcpPort=" + port + "\nsecurity.filterLan=false\n" + extra);
        NjMuleEngine engine = NjMuleEngine.start(new EngineConfig(dirs));
        this.resources.add(engine);
        return engine;
    }
}
