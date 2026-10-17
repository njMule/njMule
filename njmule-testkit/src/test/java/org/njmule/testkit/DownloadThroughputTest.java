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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.core.api.DownloadInfo;
import org.njmule.core.api.EngineConfig;
import org.njmule.core.api.EngineEvent;
import org.njmule.core.api.Hash16;
import org.njmule.core.api.NjMuleEngine;
import org.njmule.core.config.AppDirectories;

/**
 * How fast a download from a peer on this machine goes, plain blocks, to find what holds the engine back on a LAN. Run
 * with {@code -Ptest.tags=perf}; it prints its speed.
 */
@Tag("perf")
@Timeout(600)
class DownloadThroughputTest {

    private static final int MEBIBYTE = 1024 * 1024;

    @TempDir
    Path dir;

    private FakePeer peer;
    private NjMuleEngine engine;
    private final CompletableFuture<EngineEvent.DownloadCompleted> completed = new CompletableFuture<>();
    private double mebibytesPerSecond;

    @AfterEach
    void close() throws Exception {
        if (this.engine != null) {
            this.engine.close();
        }
        if (this.peer != null) {
            this.peer.close();
        }
    }

    @Test
    void shouldDownloadFromAPeerOnThisMachineAtTensOfMebibytesASecond() throws Exception {
        givenAPeerSharingMebibytes(200);
        givenNjMuleRunning();

        whenItsFileIsDownloaded();

        thenTheSpeedIsAtLeast(20);
    }

    private void givenAPeerSharingMebibytes(int size) throws Exception {
        byte[] data = new byte[size * MEBIBYTE];
        new Random(5).nextBytes(data);
        Path shared = Files.createDirectories(this.dir.resolve("shared")).resolve("big.bin");
        Files.write(shared, data);
        this.peer = FakePeer.start(
                InetAddress.getLoopbackAddress(),
                shared,
                new FakePeer.Config(Hash16.parseHex("ab".repeat(16)), false, FakePeer.Slot.NOW, 0, Set.of()));
    }

    private void givenNjMuleRunning() throws Exception {
        AppDirectories dirs = AppDirectories.underRoot(this.dir.resolve("njmule"), true);
        Files.createDirectories(dirs.config());
        Files.writeString(
                dirs.config().resolve("njmule.properties"),
                "net.tcpPort=" + FreePorts.tcp() + "\nsecurity.filterLan=false\nnet.kad.enabled=false\n");
        this.engine = NjMuleEngine.start(new EngineConfig(dirs));
        this.engine.events().subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(EngineEvent event) {
                if (event instanceof EngineEvent.DownloadCompleted done) {
                    DownloadThroughputTest.this.completed.complete(done);
                }
            }

            @Override
            public void onError(Throwable throwable) {
                DownloadThroughputTest.this.completed.completeExceptionally(throwable);
            }

            @Override
            public void onComplete() {
                // The engine closes after the test.
            }
        });
    }

    private void whenItsFileIsDownloaded() throws Exception {
        long start = System.nanoTime();
        DownloadInfo added = this.engine.addDownload(this.peer.link()).get(10, TimeUnit.SECONDS);
        this.completed.get(500, TimeUnit.SECONDS);
        double seconds = (System.nanoTime() - start) / 1e9;
        this.mebibytesPerSecond = added.size() / (double) MEBIBYTE / seconds;
        System.out.printf(
                "Downloaded %d MiB in %.1f s: %.1f MiB/s%n", added.size() / MEBIBYTE, seconds, this.mebibytesPerSecond);
    }

    private void thenTheSpeedIsAtLeast(double mebibytesPerSecond) {
        assertThat(this.mebibytesPerSecond).isGreaterThanOrEqualTo(mebibytesPerSecond);
    }
}
