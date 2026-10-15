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
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.core.api.Ed2kLink;
import org.njmule.core.engine.EngineLoop;
import org.njmule.core.files.hash.Ed2k;

/** How a finished download reaches the incoming directory. */
@Timeout(60)
class DownloadCompletionTest {

    @TempDir
    Path dir;

    private final EngineLoop loop = EngineLoop.start(1024, "test-engine");
    private final BlockingQueue<String> events = new LinkedBlockingQueue<>();
    private final List<String> seen = new ArrayList<>();
    private final byte[] data = new byte[300_000];
    private DownloadQueue queue;
    private DownloadQueue.Download download;
    private InputStream holder;

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
    void shouldMoveAFinishedFileAnotherProgramHoldsOnceItIsFree() throws Exception {
        givenADownload();
        givenAnotherProgramHoldsItsDataFor(Duration.ofMillis(2500));

        whenItsLastDataArrives();

        thenItIsMovedToTheIncomingDirectoryWithoutFailing();
    }

    private void givenADownload() throws Exception {
        new Random(7).nextBytes(this.data);
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
                    public void completed(DownloadQueue.Download d, Path file) {
                        DownloadCompletionTest.this.events.add("completed " + file);
                    }

                    @Override
                    public void failed(DownloadQueue.Download d, String reason) {
                        DownloadCompletionTest.this.events.add("failed " + reason);
                    }
                }));
        this.download = onLoop(() -> this.queue.add(
                Ed2kLink.FileLink.of("held.bin", this.data.length, Ed2k.md4(this.data, 0, this.data.length))));
    }

    // A java.io stream on Windows shares a file for reading and writing but not for deleting, as a virus scanner
    // does: the file cannot be moved while it is open.
    private void givenAnotherProgramHoldsItsDataFor(Duration time) throws Exception {
        this.holder = new FileInputStream(this.download.file().dataFile().toFile());
        InputStream held = this.holder;
        Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(time);
                held.close();
            } catch (InterruptedException | IOException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    private void whenItsLastDataArrives() throws Exception {
        onLoop(() -> {
            this.queue.received(this.download, 0, this.data);
            return null;
        });
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(40);
        while (this.seen.stream().noneMatch(e -> e.startsWith("completed")) && System.nanoTime() < deadline) {
            String event = this.events.poll(100, TimeUnit.MILLISECONDS);
            if (event != null) {
                this.seen.add(event);
            }
        }
    }

    private void thenItIsMovedToTheIncomingDirectoryWithoutFailing() throws Exception {
        assertThat(this.seen).noneMatch(e -> e.startsWith("failed"));
        assertThat(this.seen).anyMatch(e -> e.startsWith("completed"));
        Path file = Path.of(this.seen.getLast().substring("completed ".length()));
        assertThat(Files.readAllBytes(file)).isEqualTo(this.data);
    }

    private <T> T onLoop(Callable<T> task) throws Exception {
        return this.loop.submit(task).get(30, TimeUnit.SECONDS);
    }
}
