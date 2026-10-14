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

import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.BitSet;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.core.api.Ed2kLink;
import org.njmule.core.api.Hash16;
import org.njmule.core.engine.EngineLoop;
import org.njmule.core.files.PartFile;
import org.njmule.core.files.hash.Ed2k;

/** How the downloads behave when the temp disk runs out of space. */
@Timeout(60)
class DownloadDiskSpaceTest {

    @TempDir
    Path dir;

    private final EngineLoop loop = EngineLoop.start(1024, "test-engine");
    private final BlockingQueue<String> events = new LinkedBlockingQueue<>();
    private final AtomicLong minFree = new AtomicLong();
    private final BitSet allParts = new BitSet();
    private DownloadQueue queue;
    private DownloadQueue.Download download;
    private FileChannel other;
    private FileLock lock;

    @AfterEach
    void stop() throws Exception {
        this.release();
        if (this.queue != null) {
            this.onLoop(() -> {
                this.queue.close();
                return null;
            });
        }
        this.loop.close();
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void aWriteThatFailsOnADiskOutOfSpaceMakesTheDownloadsWaitInsteadOfFailing() throws Exception {
        this.givenADownload();
        this.givenItsDataCannotBeWritten();

        this.whenDataArrivesWhileTheDiskIsOutOfSpace();

        this.thenTheDownloadsWaitForSpaceInsteadOfFailing();
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void aDownloadThatWaitsForRoomAfterAFailedWriteGoesOnByItself() throws Exception {
        this.givenADownload();
        this.givenItsDataCannotBeWritten();
        this.givenDataArrivedWhileTheDiskWasOutOfSpace();

        this.whenThereIsRoomAgain();

        this.thenTheDownloadGoesOnAndAsksForTheDataAgain();
    }

    private void givenADownload() throws Exception {
        this.queue = this.onLoop(() -> DownloadQueue.load(
                this.loop,
                this.dir.resolve("temp"),
                file -> this.dir.resolve("incoming"),
                this.minFree::get,
                new DownloadQueue.Listener() {
                    @Override
                    public void diskSpace(boolean enough) {
                        DownloadDiskSpaceTest.this.events.add(enough ? "space enough" : "space short");
                    }

                    @Override
                    public void added(DownloadQueue.Download d) {}

                    @Override
                    public void progress(DownloadQueue.Download d) {}

                    @Override
                    public void completed(DownloadQueue.Download d, Path file) {}

                    @Override
                    public void failed(DownloadQueue.Download d, String reason) {
                        DownloadDiskSpaceTest.this.events.add("failed " + reason);
                    }
                }));
        this.download = this.onLoop(
                () -> this.queue.add(Ed2kLink.FileLink.of("full.bin", 400_000, Hash16.parseHex("d1".repeat(16)))));
        this.allParts.set(0);
        this.onLoop(() -> this.download.file().nextBlocks(this.allParts, 1));
    }

    // Windows refuses writes into a range another handle locked, as it refuses those to a full disk.
    private void givenItsDataCannotBeWritten() throws Exception {
        this.other = FileChannel.open(this.download.file().dataFile(), StandardOpenOption.WRITE);
        this.lock = this.other.lock();
    }

    private void givenDataArrivedWhileTheDiskWasOutOfSpace() throws Exception {
        this.whenDataArrivesWhileTheDiskIsOutOfSpace();
        assertThat(this.events.poll(30, TimeUnit.SECONDS)).isNotNull();
    }

    private void whenDataArrivesWhileTheDiskIsOutOfSpace() throws Exception {
        this.onLoop(() -> {
            this.minFree.set(Long.MAX_VALUE);
            this.queue.received(this.download, 0, new byte[Ed2k.EMBLOCKSIZE]);
            return null;
        });
    }

    private void thenTheDownloadsWaitForSpaceInsteadOfFailing() throws Exception {
        assertThat(this.events.poll(30, TimeUnit.SECONDS)).isEqualTo("space short");
        assertThat(this.onLoop(() -> this.download.file().status())).isEqualTo(PartFile.Status.DOWNLOADING);
        assertThat(this.onLoop(this.download::isActive)).isFalse();
    }

    private void whenThereIsRoomAgain() throws Exception {
        this.release();
        this.onLoop(() -> {
            this.minFree.set(1);
            this.queue.checkSpace();
            return null;
        });
    }

    private void thenTheDownloadGoesOnAndAsksForTheDataAgain() throws Exception {
        assertThat(this.events.poll(30, TimeUnit.SECONDS)).isEqualTo("space enough");
        assertThat(this.onLoop(this.download::isActive)).isTrue();
        assertThat(this.onLoop(() -> this.download.file().nextBlocks(this.allParts, 1)))
                .isEqualTo(List.of(new PartFile.Block(0, Ed2k.EMBLOCKSIZE)));
    }

    private void release() throws Exception {
        if (this.lock != null) {
            this.lock.release();
            this.other.close();
            this.lock = null;
        }
    }

    private <T> T onLoop(Callable<T> task) throws Exception {
        return this.loop.submit(task).get(30, TimeUnit.SECONDS);
    }
}
