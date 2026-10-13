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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.core.api.Ed2kLink;
import org.njmule.core.api.Hash16;
import org.njmule.core.engine.EngineLoop;
import org.njmule.core.files.hash.Ed2k;

@Timeout(60)
class DownloadQueueTest {

    @TempDir Path dir;

    private final EngineLoop loop = EngineLoop.start(1024, "test-engine");
    private final BlockingQueue<String> events = new LinkedBlockingQueue<>();

    @AfterEach
    void stop() {
        this.loop.close();
    }

    @Test
    void writesVerifiesAndMovesAFinishedFile() throws Exception {
        byte[] data = random(400_000, 1);
        DownloadQueue queue = this.queue();
        DownloadQueue.Download download =
                this.onLoop(
                        () ->
                                queue.add(
                                        Ed2kLink.FileLink.of(
                                                "../evil:name.bin",
                                                data.length,
                                                Ed2k.md4(data, 0, data.length))));
        assertThat(this.events.take()).isEqualTo("added");

        this.onLoop(
                () -> {
                    queue.received(
                            download, 200_000, Arrays.copyOfRange(data, 200_000, data.length));
                    queue.received(download, 0, Arrays.copyOfRange(data, 0, 200_000));
                    return null;
                });

        String completed = this.awaitPrefix("completed ");
        Path file = Path.of(completed.substring("completed ".length()));
        assertThat(file.getParent())
                .isEqualTo(this.dir.resolve("incoming").toAbsolutePath().normalize());
        assertThat(file.getFileName().toString()).isEqualTo(".._evil_name.bin");
        assertThat(Files.readAllBytes(file)).isEqualTo(data);
        assertThat(this.dir.resolve("temp").resolve("001.part")).doesNotExist();
        assertThat(this.dir.resolve("temp").resolve("001.part.met")).doesNotExist();
    }

    @Test
    void downloadsACorruptPartAgain() throws Exception {
        int size = (int) Ed2k.PARTSIZE + 1000;
        byte[] data = random(size, 2);
        List<Hash16> parts =
                List.of(
                        Ed2k.md4(data, 0, (int) Ed2k.PARTSIZE),
                        Ed2k.md4(data, (int) Ed2k.PARTSIZE, 1000));
        DownloadQueue queue = this.queue();
        DownloadQueue.Download download =
                this.onLoop(
                        () ->
                                queue.add(
                                        new Ed2kLink.FileLink(
                                                "two.bin",
                                                size,
                                                Ed2k.fileHash(parts),
                                                Optional.empty(),
                                                parts,
                                                List.of(),
                                                List.of())));
        byte[] bad = data.clone();
        bad[123] ^= 1;

        this.onLoop(
                () -> {
                    queue.received(download, 0, Arrays.copyOfRange(bad, 0, (int) Ed2k.PARTSIZE));
                    queue.received(
                            download,
                            Ed2k.PARTSIZE,
                            Arrays.copyOfRange(data, (int) Ed2k.PARTSIZE, size));
                    return null;
                });
        this.awaitCorruptPart0(download);

        this.onLoop(
                () -> {
                    queue.received(download, 0, Arrays.copyOfRange(data, 0, (int) Ed2k.PARTSIZE));
                    return null;
                });
        Path file = Path.of(this.awaitPrefix("completed ").substring("completed ".length()));
        assertThat(Files.readAllBytes(file)).isEqualTo(data);
    }

    @Test
    void reloadsDownloadsFromTheirPartFiles() throws Exception {
        byte[] data = random(300_000, 3);
        DownloadQueue first = this.queue();
        DownloadQueue.Download download =
                this.onLoop(
                        () ->
                                first.add(
                                        Ed2kLink.FileLink.of(
                                                "kept.bin",
                                                data.length,
                                                Ed2k.md4(data, 0, data.length))));
        this.onLoop(
                () -> {
                    first.received(download, 0, Arrays.copyOfRange(data, 0, 100_000));
                    return null;
                });
        this.awaitPrefix("progress");
        this.onLoop(
                () -> {
                    first.close();
                    return null;
                });

        DownloadQueue second = this.queue();
        DownloadQueue.Download reloaded = second.get(download.hash()).orElseThrow();
        assertThat(reloaded.file().name()).isEqualTo("kept.bin");
        assertThat(reloaded.file().completedBytes()).isEqualTo(100_000);
    }

    @Test
    void checksTheCompletePartsOfAResumedDownload() throws Exception {
        byte[] data = random(200_000, 4);
        DownloadQueue first = this.queue();
        DownloadQueue.Download download =
                this.onLoop(
                        () ->
                                first.add(
                                        Ed2kLink.FileLink.of(
                                                "resumed.bin",
                                                data.length,
                                                Ed2k.md4(data, 0, data.length))));
        byte[] bad = data.clone();
        bad[7] ^= 1;
        // Written, then closed before the check could run: as after a crash.
        this.onLoop(
                () -> {
                    download.file().written(0, data.length);
                    download.file().save();
                    return null;
                });
        Files.write(this.dir.resolve("temp").resolve("001.part"), bad);
        this.onLoop(
                () -> {
                    first.close();
                    return null;
                });

        DownloadQueue second = this.queue();
        DownloadQueue.Download reloaded = second.get(download.hash()).orElseThrow();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (this.onLoop(() -> reloaded.file().completedBytes()) != 0) {
            assertThat(System.nanoTime()).isLessThan(deadline);
            this.events.poll(100, TimeUnit.MILLISECONDS);
        }
        assertThat(this.onLoop(() -> reloaded.verifiedParts().isEmpty())).isTrue();
    }

    private void awaitCorruptPart0(DownloadQueue.Download download) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline) {
            this.events.poll(100, TimeUnit.MILLISECONDS);
            Boolean back =
                    this.onLoop(
                            () -> !download.file().isComplete(0) && download.file().isComplete(1));
            if (back && this.onLoop(() -> download.file().completedBytes() == 1000)) {
                return;
            }
        }
        throw new AssertionError("the corrupt part was not taken back");
    }

    private DownloadQueue queue() throws Exception {
        return this.onLoop(
                () ->
                        DownloadQueue.load(
                                this.loop,
                                this.dir.resolve("temp"),
                                () -> this.dir.resolve("incoming"),
                                new DownloadQueue.Listener() {
                                    @Override
                                    public void added(DownloadQueue.Download d) {
                                        DownloadQueueTest.this.events.add("added");
                                    }

                                    @Override
                                    public void progress(DownloadQueue.Download d) {
                                        DownloadQueueTest.this.events.add("progress");
                                    }

                                    @Override
                                    public void completed(DownloadQueue.Download d, Path file) {
                                        DownloadQueueTest.this.events.add("completed " + file);
                                    }

                                    @Override
                                    public void failed(DownloadQueue.Download d, String reason) {
                                        DownloadQueueTest.this.events.add("failed " + reason);
                                    }
                                }));
    }

    private String awaitPrefix(String prefix) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline) {
            String event = this.events.poll(100, TimeUnit.MILLISECONDS);
            if (event != null && event.startsWith(prefix)) {
                return event;
            }
            if (event != null) {
                assertThat(event).as("unexpected failure").doesNotStartWith("failed");
            }
        }
        throw new AssertionError("no event " + prefix);
    }

    private <T> T onLoop(Callable<T> task) throws Exception {
        return this.loop.submit(task).get(30, TimeUnit.SECONDS);
    }

    private static byte[] random(int size, long seed) {
        byte[] data = new byte[size];
        new Random(seed).nextBytes(data);
        return data;
    }
}
