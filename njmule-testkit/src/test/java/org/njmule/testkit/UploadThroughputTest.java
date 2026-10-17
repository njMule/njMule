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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.core.api.Ed2kLink;
import org.njmule.core.api.EngineConfig;
import org.njmule.core.api.EngineEvent;
import org.njmule.core.api.NjMuleEngine;
import org.njmule.core.api.ServerAddress;
import org.njmule.core.api.SharedFileInfo;
import org.njmule.core.config.AppDirectories;

/**
 * How fast njMule sends a shared file to another njMule on this machine, without limits, to find what holds an upload
 * back on a LAN. Run with {@code -Ptest.tags=perf}; it prints its speed.
 */
@Tag("perf")
@Timeout(600)
class UploadThroughputTest {

    private static final int MEBIBYTE = 1024 * 1024;

    @TempDir
    Path dir;

    private NjMuleEngine sharer;
    private NjMuleEngine downloader;
    private int sharerPort;
    private SharedFileInfo shared;
    private final CompletableFuture<EngineEvent.DownloadCompleted> completed = new CompletableFuture<>();
    private double mebibytesPerSecond;
    // 0: none.
    private int uploadLimit;

    @AfterEach
    void close() throws Exception {
        for (NjMuleEngine engine : new NjMuleEngine[] {this.downloader, this.sharer}) {
            if (engine != null) {
                engine.close();
            }
        }
    }

    @Test
    void shouldSendAFileToAnotherNjMuleOnThisMachineAtTensOfMebibytesASecond() throws Exception {
        givenAnNjMuleSharingMebibytes(200);
        givenAnotherNjMuleRunning();

        whenTheOtherDownloadsTheFileFromTheFirst();

        thenTheSpeedIsAtLeast(20);
    }

    @Test
    void shouldKeepToTheUploadLimit() throws Exception {
        givenTheUploadLimitIs(4096);
        givenAnNjMuleSharingMebibytes(40);
        givenAnotherNjMuleRunning();

        whenTheOtherDownloadsTheFileFromTheFirst();

        thenTheSpeedIsAtMost(4.4);
    }

    private void givenAnNjMuleSharingMebibytes(int size) throws Exception {
        byte[] data = new byte[size * MEBIBYTE];
        new Random(7).nextBytes(data);
        Path folder = Files.createDirectories(this.dir.resolve("shared"));
        Files.write(folder.resolve("big.bin"), data);
        this.sharerPort = FreePorts.tcp();
        this.sharer = start("sharer", this.sharerPort);
        this.sharer.shareDirectory(folder).get(10, TimeUnit.SECONDS);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120);
        while (true) {
            List<SharedFileInfo> files = this.sharer.sharedFiles().get(10, TimeUnit.SECONDS);
            if (!files.isEmpty()) {
                this.shared = files.getFirst();
                return;
            }
            assertThat(System.nanoTime()).as("the file hashed").isLessThan(deadline);
            Thread.sleep(200);
        }
    }

    private void givenAnotherNjMuleRunning() throws Exception {
        this.downloader = start("downloader", FreePorts.tcp());
        this.downloader.events().subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(EngineEvent event) {
                if (event instanceof EngineEvent.DownloadCompleted done) {
                    UploadThroughputTest.this.completed.complete(done);
                }
            }

            @Override
            public void onError(Throwable throwable) {
                UploadThroughputTest.this.completed.completeExceptionally(throwable);
            }

            @Override
            public void onComplete() {
                // The engine closes after the test.
            }
        });
    }

    private void whenTheOtherDownloadsTheFileFromTheFirst() throws Exception {
        long start = System.nanoTime();
        this.downloader
                .addDownload(Ed2kLink.FileLink.of(this.shared.name(), this.shared.size(), this.shared.hash()))
                .get(10, TimeUnit.SECONDS);
        assertThat(this.downloader
                        .addSource(this.shared.hash(), new ServerAddress("127.0.0.1", this.sharerPort))
                        .get(10, TimeUnit.SECONDS))
                .isTrue();
        this.completed.get(500, TimeUnit.SECONDS);
        double seconds = (System.nanoTime() - start) / 1e9;
        this.mebibytesPerSecond = this.shared.size() / (double) MEBIBYTE / seconds;
        System.out.printf(
                "Uploaded %d MiB in %.1f s: %.1f MiB/s%n",
                this.shared.size() / MEBIBYTE, seconds, this.mebibytesPerSecond);
    }

    private void givenTheUploadLimitIs(int kibibytesPerSecond) {
        this.uploadLimit = kibibytesPerSecond;
    }

    private void thenTheSpeedIsAtMost(double mebibytesPerSecond) {
        assertThat(this.mebibytesPerSecond).isLessThanOrEqualTo(mebibytesPerSecond);
    }

    private void thenTheSpeedIsAtLeast(double mebibytesPerSecond) {
        assertThat(this.mebibytesPerSecond).isGreaterThanOrEqualTo(mebibytesPerSecond);
    }

    // No Kad, no download limit, the upload limit of the test, LAN addresses let in.
    private NjMuleEngine start(String name, int port) throws Exception {
        AppDirectories dirs = AppDirectories.underRoot(this.dir.resolve(name), true);
        Files.createDirectories(dirs.config());
        Files.writeString(
                dirs.config().resolve("njmule.properties"),
                "net.tcpPort=" + port + "\nnet.udpPort=" + FreePorts.udp()
                        + "\nsecurity.filterLan=false\nnet.kad.enabled=false\n"
                        + "limits.uploadKiBps=" + this.uploadLimit + "\nlimits.downloadKiBps=0\n");
        return NjMuleEngine.start(new EngineConfig(dirs));
    }
}
