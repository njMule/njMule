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
package org.njmule.core.download;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.core.api.Ed2kLink;
import org.njmule.core.api.Hash16;
import org.njmule.core.engine.EngineLoop;
import org.njmule.core.persist.format.PartMet;

/** How the part.met files of the downloads are saved. */
@Timeout(60)
class PartMetSavingTest {

    @TempDir
    Path dir;

    private final EngineLoop loop = EngineLoop.start(1024, "test-engine");
    private DownloadQueue queue;
    private DownloadQueue.Download download;
    private InputStream holder;
    private Duration engineBusy;

    @AfterEach
    void stop() throws Exception {
        if (this.holder != null) {
            this.holder.close();
        }
        if (this.queue != null) {
            onLoop(() -> {
                this.queue.close();
                return null;
            });
        }
        this.loop.close();
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void shouldNotHoldUpTheEngineWhileAPartMetIsSlowToReplace() throws Exception {
        givenADownload();
        givenAnotherProgramHoldsItsPartMet();

        whenTheDownloadIsPausedAndSaved();

        thenTheEngineWasNotHeldUp();
        thenThePartMetIsSavedOnceItIsFree();
    }

    private void givenADownload() throws Exception {
        this.queue = onLoop(() -> DownloadQueue.load(
                this.loop,
                this.dir.resolve("temp"),
                file -> this.dir.resolve("incoming"),
                () -> 0,
                new DownloadQueue.Listener() {
                    @Override
                    public void added(DownloadQueue.Download d) {}

                    @Override
                    public void progress(DownloadQueue.Download d) {}

                    @Override
                    public void completed(DownloadQueue.Download d, Path file) {}

                    @Override
                    public void failed(DownloadQueue.Download d, String reason) {}
                }));
        this.download =
                onLoop(() -> this.queue.add(Ed2kLink.FileLink.of("held.bin", 5000, Hash16.parseHex("4d".repeat(16)))));
        onLoop(() -> {
            this.queue.saveChanged();
            return null;
        });
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!Files.exists(this.download.file().metFile())) {
            assertThat(System.nanoTime()).as("saved").isLessThan(deadline);
            Thread.sleep(20);
        }
    }

    // A java.io stream on Windows shares the file for reading and writing but not for deleting: a file cannot be
    // renamed over it, as while a scanner or a backup program reads it. Saves retry such a rename for a moment.
    private void givenAnotherProgramHoldsItsPartMet() throws Exception {
        this.holder = new FileInputStream(this.download.file().metFile().toFile());
    }

    private void whenTheDownloadIsPausedAndSaved() throws Exception {
        this.engineBusy = onLoop(() -> {
            long start = System.nanoTime();
            this.queue.pause(this.download);
            this.queue.saveChanged();
            return Duration.ofNanos(System.nanoTime() - start);
        });
    }

    private void thenTheEngineWasNotHeldUp() {
        assertThat(this.engineBusy).isLessThan(Duration.ofMillis(200));
    }

    private void thenThePartMetIsSavedOnceItIsFree() throws Exception {
        this.holder.close();
        this.holder = null;
        onLoop(() -> {
            this.queue.close();
            return null;
        });
        this.queue = null;
        assertThat(PartMet.read(Files.readAllBytes(this.download.file().metFile()))
                        .paused())
                .isTrue();
    }

    private <T> T onLoop(Callable<T> task) throws Exception {
        return this.loop.submit(task).get(30, TimeUnit.SECONDS);
    }
}
