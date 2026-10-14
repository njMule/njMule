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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.core.api.Ed2kLink;
import org.njmule.core.api.Hash16;
import org.njmule.core.api.Hash20;
import org.njmule.core.engine.EngineLoop;
import org.njmule.core.files.PartFile;
import org.njmule.core.files.hash.AichTree;
import org.njmule.core.files.hash.Ed2k;
import org.njmule.core.persist.AtomicFiles;

@Timeout(60)
class DownloadQueueTest {

    @TempDir
    Path dir;

    private final EngineLoop loop = EngineLoop.start(1024, "test-engine");
    private final BlockingQueue<String> events = new LinkedBlockingQueue<>();
    private final AtomicLong minFree = new AtomicLong();

    @AfterEach
    void stop() {
        this.loop.close();
    }

    @Test
    void writesVerifiesAndMovesAFinishedFile() throws Exception {
        byte[] data = random(400_000, 1);
        DownloadQueue queue = this.queue();
        DownloadQueue.Download download = this.onLoop(
                () -> queue.add(Ed2kLink.FileLink.of("../evil:name.bin", data.length, Ed2k.md4(data, 0, data.length))));
        assertThat(this.events.take()).isEqualTo("added");

        this.onLoop(() -> {
            queue.received(download, 200_000, Arrays.copyOfRange(data, 200_000, data.length));
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
        List<Hash16> parts = List.of(Ed2k.md4(data, 0, (int) Ed2k.PARTSIZE), Ed2k.md4(data, (int) Ed2k.PARTSIZE, 1000));
        DownloadQueue queue = this.queue();
        DownloadQueue.Download download = this.onLoop(() -> queue.add(new Ed2kLink.FileLink(
                "two.bin", size, Ed2k.fileHash(parts), Optional.empty(), parts, List.of(), List.of())));
        byte[] bad = data.clone();
        bad[123] ^= 1;

        this.onLoop(() -> {
            queue.received(download, 0, Arrays.copyOfRange(bad, 0, (int) Ed2k.PARTSIZE));
            queue.received(download, Ed2k.PARTSIZE, Arrays.copyOfRange(data, (int) Ed2k.PARTSIZE, size));
            return null;
        });
        this.awaitCorruptPart0(download);

        this.onLoop(() -> {
            queue.received(download, 0, Arrays.copyOfRange(data, 0, (int) Ed2k.PARTSIZE));
            return null;
        });
        Path file = Path.of(this.awaitPrefix("completed ").substring("completed ".length()));
        assertThat(Files.readAllBytes(file)).isEqualTo(data);
    }

    @Test
    void aLinkWithAWrongHashSetIsStillDownloaded() throws Exception {
        DownloadQueue queue = this.queue();
        Ed2kLink.FileLink link = new Ed2kLink.FileLink(
                "exact.bin",
                Ed2k.PARTSIZE,
                Hash16.parseHex("56".repeat(16)),
                Optional.empty(),
                List.of(Hash16.parseHex("78".repeat(16))),
                List.of(),
                List.of());

        DownloadQueue.Download download = this.onLoop(() -> queue.add(link));

        assertThat(this.events.take()).isEqualTo("added");
        assertThat(download.file().hasHashSet()).as("asked from the sources").isFalse();
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void aRangeThatCouldNotBeWrittenIsAskedForAgain() throws Exception {
        byte[] data = random(400_000, 5);
        DownloadQueue queue = this.queue();
        DownloadQueue.Download download = this.onLoop(
                () -> queue.add(Ed2kLink.FileLink.of("locked.bin", data.length, Ed2k.md4(data, 0, data.length))));
        assertThat(this.events.take()).isEqualTo("added");
        BitSet all = new BitSet();
        all.set(0);
        this.onLoop(() -> download.file().nextBlocks(all, 3));

        // Another process holds a lock on the data: the write fails.
        try (FileChannel other =
                        FileChannel.open(this.dir.resolve("temp").resolve("001.part"), StandardOpenOption.WRITE);
                FileLock lock = other.lock()) {
            this.onLoop(() -> {
                queue.received(download, 0, Arrays.copyOf(data, Ed2k.EMBLOCKSIZE));
                return null;
            });
            assertThat(this.awaitPrefix("failed")).contains("cannot write");
        }

        List<PartFile.Block> again = this.onLoop(() -> {
            queue.pause(download);
            queue.resume(download);
            return download.file().nextBlocks(all, 3);
        });
        assertThat(again).containsExactly(new PartFile.Block(0, Ed2k.EMBLOCKSIZE));
    }

    @Test
    void aDownloadIsNotCancelledWhileItsFileIsMoved() throws Exception {
        byte[] data = random(1000, 6);
        Ed2kLink.FileLink link = Ed2kLink.FileLink.of("moved.bin", data.length, Ed2k.md4(data, 0, data.length));
        AtomicReference<DownloadQueue> queue = new AtomicReference<>();
        AtomicReference<Boolean> keptAfterCancel = new AtomicReference<>();
        queue.set(this.queue(file -> {
            // The user cancels and adds the link again while the finished file is being moved.
            this.loop.post(() -> {
                DownloadQueue q = queue.get();
                q.cancel(q.get(link.hash()).orElseThrow());
                keptAfterCancel.set(q.get(link.hash()).isPresent());
                try {
                    q.add(link);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
            return this.dir.resolve("incoming");
        }));
        DownloadQueue.Download download = this.onLoop(() -> queue.get().add(link));

        this.onLoop(() -> {
            queue.get().received(download, 0, data);
            return null;
        });

        Path file = Path.of(this.awaitPrefix("completed ").substring("completed ".length()));
        assertThat(Files.readAllBytes(file)).isEqualTo(data);
        assertThat(keptAfterCancel.get())
                .as("the cancel waits for nothing it could undo")
                .isTrue();
        assertThat(this.onLoop(() -> queue.get().all())).isEmpty();
        try (Stream<Path> left = Files.list(this.dir.resolve("temp"))) {
            assertThat(left).as("nothing left behind").isEmpty();
        }
        assertThat(this.onLoop(() -> download.storage().isOpen()))
                .as("its disk thread ends")
                .isFalse();
    }

    @Test
    void aFileThatCouldNotBeMovedIsMovedOnResume() throws Exception {
        byte[] data = random(2000, 7);
        Path incoming = this.dir.resolve("incoming");
        Files.writeString(incoming, "a file where the directory should be");
        DownloadQueue queue = this.queue(file -> incoming);
        DownloadQueue.Download download = this.onLoop(
                () -> queue.add(Ed2kLink.FileLink.of("stuck.bin", data.length, Ed2k.md4(data, 0, data.length))));
        this.onLoop(() -> {
            queue.received(download, 0, data);
            return null;
        });
        assertThat(this.awaitPrefix("failed")).contains("cannot move");
        assertThat(this.onLoop(() -> download.file().status())).isEqualTo(PartFile.Status.ERROR);

        Files.delete(incoming);
        this.onLoop(() -> {
            queue.pause(download);
            queue.resume(download);
            return null;
        });

        Path file = Path.of(this.awaitPrefix("completed ").substring("completed ".length()));
        assertThat(Files.readAllBytes(file)).isEqualTo(data);
        assertThat(this.onLoop(() -> queue.get(download.hash()))).isEmpty();
    }

    @Test
    void twoPartFilesOfOneFileLoadAsOneDownload() throws Exception {
        Hash16 hash = Hash16.parseHex("9c".repeat(16));
        DownloadQueue first = this.queue();
        this.onLoop(() -> first.add(Ed2kLink.FileLink.of("twice.bin", 1000, hash)));
        this.onLoop(() -> {
            first.close();
            return null;
        });
        Path temp = this.dir.resolve("temp");
        Files.copy(temp.resolve("001" + PartFile.MET_SUFFIX), temp.resolve("002" + PartFile.MET_SUFFIX));
        Files.copy(temp.resolve("001" + PartFile.DATA_SUFFIX), temp.resolve("002" + PartFile.DATA_SUFFIX));

        DownloadQueue again = this.queue();

        assertThat(this.onLoop(again::all))
                .singleElement()
                .satisfies(d ->
                        assertThat(d.file().number()).as("the first is kept").isEqualTo(1));
        this.onLoop(() -> {
            again.close();
            return null;
        });
    }

    @Test
    void filesOfExactlyOnePartAndOneByteMoreComplete() throws Exception {
        DownloadQueue queue = this.queue();
        for (int size : new int[] {(int) Ed2k.PARTSIZE, (int) Ed2k.PARTSIZE + 1}) {
            byte[] data = random(size, size);
            List<Hash16> parts = size == Ed2k.PARTSIZE
                    ? List.of(Ed2k.md4(data, 0, size), Ed2k.EMPTY_PART_HASH)
                    : List.of(Ed2k.md4(data, 0, (int) Ed2k.PARTSIZE), Ed2k.md4(data, (int) Ed2k.PARTSIZE, 1));
            DownloadQueue.Download download = this.onLoop(() -> queue.add(new Ed2kLink.FileLink(
                    "edge" + size + ".bin",
                    size,
                    Ed2k.fileHash(parts),
                    Optional.empty(),
                    parts,
                    List.of(),
                    List.of())));
            assertThat(download.file().hasHashSet()).isTrue();

            this.onLoop(() -> {
                queue.received(download, 0, new byte[0]);
                for (int at = size; at > 0; at -= 1_000_000) {
                    int from = Math.max(0, at - 1_000_000);
                    queue.received(download, from, Arrays.copyOfRange(data, from, at));
                }
                return null;
            });

            Path file = Path.of(this.awaitPrefix("completed ").substring("completed ".length()));
            assertThat(Files.size(file)).isEqualTo(size);
            assertThat(Files.readAllBytes(file)).isEqualTo(data);
        }
    }

    @Test
    void reloadsDownloadsFromTheirPartFiles() throws Exception {
        byte[] data = random(300_000, 3);
        DownloadQueue first = this.queue();
        DownloadQueue.Download download = this.onLoop(
                () -> first.add(Ed2kLink.FileLink.of("kept.bin", data.length, Ed2k.md4(data, 0, data.length))));
        this.onLoop(() -> {
            first.received(download, 0, Arrays.copyOfRange(data, 0, 100_000));
            return null;
        });
        this.awaitPrefix("progress");
        this.onLoop(() -> {
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
        DownloadQueue.Download download = this.onLoop(
                () -> first.add(Ed2kLink.FileLink.of("resumed.bin", data.length, Ed2k.md4(data, 0, data.length))));
        byte[] bad = data.clone();
        bad[7] ^= 1;
        // Written, then closed before the check could run: as after a crash.
        this.onLoop(() -> {
            download.file().written(0, data.length);
            download.file().save();
            return null;
        });
        Files.write(this.dir.resolve("temp").resolve("001.part"), bad);
        this.onLoop(() -> {
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

    @Test
    void loadsADownloadOfWhichOnlyTheBackupIsLeftAndNeverReusesItsNumber() throws Exception {
        DownloadQueue queue = this.queue();
        Hash16 hash = Hash16.parseHex("12".repeat(16));
        this.onLoop(() -> queue.add(Ed2kLink.FileLink.of("kept.bin", 1000, hash)));
        this.onLoop(() -> {
            queue.close();
            return null;
        });
        Path temp = this.dir.resolve("temp");
        Path met = temp.resolve("001" + PartFile.MET_SUFFIX);
        Files.move(met, AtomicFiles.bakPath(met), StandardCopyOption.REPLACE_EXISTING);
        Files.writeString(temp.resolve("002" + PartFile.DATA_SUFFIX), "left by a crash");

        DownloadQueue again = this.queue();
        assertThat(this.onLoop(() -> again.get(hash))).isPresent();
        DownloadQueue.Download next =
                this.onLoop(() -> again.add(Ed2kLink.FileLink.of("new.bin", 1000, Hash16.parseHex("34".repeat(16)))));
        assertThat(next.file().number()).isEqualTo(3);
        this.onLoop(() -> {
            again.close();
            return null;
        });
    }

    @Test
    void aWriteThatEndsAfterTheCancelIsIgnored() throws Exception {
        byte[] data = random(1000, 3);
        DownloadQueue queue = this.queue();
        DownloadQueue.Download download = this.onLoop(
                () -> queue.add(Ed2kLink.FileLink.of("gone.bin", data.length, Ed2k.md4(data, 0, data.length))));
        assertThat(this.events.take()).isEqualTo("added");

        this.onLoop(() -> {
            queue.received(download, 0, data);
            queue.cancel(download);
            return null;
        });

        assertThat(this.events.poll(1, TimeUnit.SECONDS)).isNull();
        assertThat(this.onLoop(() -> download.file().completedBytes())).isZero();
    }

    @Test
    void leftoversInTheTempDirectoryKeepTheirNumbers() throws Exception {
        Path temp = Files.createDirectories(this.dir.resolve("temp"));
        Files.write(temp.resolve("001" + PartFile.MET_SUFFIX), new byte[] {1, 2, 3});
        Files.write(AtomicFiles.bakPath(temp.resolve("002" + PartFile.MET_SUFFIX)), new byte[] {1, 2, 3});
        Files.write(AtomicFiles.tmpPath(temp.resolve("003" + PartFile.MET_SUFFIX)), new byte[] {1});
        Files.write(temp.resolve("004" + PartFile.DATA_SUFFIX), new byte[] {1});

        DownloadQueue queue = this.queue();
        assertThat(this.onLoop(queue::all))
                .as("unreadable part files are left alone")
                .isEmpty();
        DownloadQueue.Download download =
                this.onLoop(() -> queue.add(Ed2kLink.FileLink.of("fresh.bin", 5000, Hash16.parseHex("13".repeat(16)))));

        assertThat(download.file().number()).isEqualTo(5);
        assertThat(temp.resolve("001" + PartFile.MET_SUFFIX)).hasBinaryContent(new byte[] {1, 2, 3});
    }

    @Test
    void aPausedDownloadTakesNoDataAndNoneBeyondItsEnd() throws Exception {
        DownloadQueue queue = this.queue();
        DownloadQueue.Download download =
                this.onLoop(() -> queue.add(Ed2kLink.FileLink.of("small.bin", 5000, Hash16.parseHex("14".repeat(16)))));
        assertThat(this.events.take()).isEqualTo("added");

        this.onLoop(() -> {
            queue.received(download, 4990, new byte[20]);
            queue.pause(download);
            queue.received(download, 0, new byte[100]);
            queue.setPreviewFirst(download, true);
            return null;
        });

        assertThat(this.events.poll(300, TimeUnit.MILLISECONDS)).isNull();
        assertThat(this.onLoop(() -> download.file().completedBytes())).isZero();
        assertThat(this.onLoop(() -> download.file().previewFirst())).isTrue();
        assertThat(download.sources()).isEmpty();
    }

    @Test
    void aPartIsCheckedOnceItsHashIsKnownAndTrustedAfterARestart() throws Exception {
        byte[] last = random(1000, 8);
        List<Hash16> parts = List.of(Hash16.parseHex("15".repeat(16)), Ed2k.md4(last, 0, last.length));
        Ed2kLink.FileLink link = new Ed2kLink.FileLink(
                "late.bin",
                Ed2k.PARTSIZE + last.length,
                Ed2k.fileHash(parts),
                Optional.empty(),
                List.of(),
                List.of(),
                List.of());
        DownloadQueue queue = this.queue();
        DownloadQueue.Download download = this.onLoop(() -> queue.add(link));

        this.onLoop(() -> {
            queue.received(download, Ed2k.PARTSIZE, last);
            return null;
        });
        assertThat(this.awaitPrefix("progress")).isEqualTo("progress");
        assertThat(this.onLoop(() -> download.verifiedParts().isEmpty()))
                .as("no hash to check it with")
                .isTrue();

        this.onLoop(() -> {
            download.file().setHashSet(parts);
            queue.hashSetKnown(download);
            return null;
        });
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!this.onLoop(() -> download.verifiedParts().get(1))) {
            assertThat(System.nanoTime()).isLessThan(deadline);
            Thread.sleep(20);
        }
        this.onLoop(() -> {
            queue.close();
            return null;
        });

        DownloadQueue again = this.queue();
        assertThat(this.onLoop(() -> again.get(link.hash()).orElseThrow().verifiedParts()))
                .as("checked before the restart, on data that did not change since")
                .isEqualTo(BitSet.valueOf(new long[] {2}));
        this.onLoop(() -> {
            again.close();
            return null;
        });
    }

    @Test
    void aFinishedDownloadThatCouldNotBeMovedIsMovedWhenLoadedAgain() throws Exception {
        byte[] data = random(5000, 9);
        Path incoming = this.dir.resolve("incoming");
        Files.writeString(incoming, "a file where the directory should be");
        DownloadQueue queue = this.queue(file -> incoming);
        DownloadQueue.Download download = this.onLoop(
                () -> queue.add(Ed2kLink.FileLink.of("again.bin", data.length, Ed2k.md4(data, 0, data.length))));
        this.onLoop(() -> {
            queue.received(download, 0, data);
            return null;
        });
        assertThat(this.awaitPrefix("failed")).contains("cannot move");
        this.onLoop(() -> {
            queue.close();
            return null;
        });

        Files.delete(incoming);
        this.queue(file -> incoming);

        Path file = Path.of(this.awaitPrefix("completed ").substring("completed ".length()));
        assertThat(Files.readAllBytes(file)).isEqualTo(data);
    }

    @Test
    void aDownloadWhoseDataCannotBeOpenedAgainFails() throws Exception {
        byte[] data = random(5000, 10);
        Path incoming = this.dir.resolve("incoming");
        Files.writeString(incoming, "a file where the directory should be");
        DownloadQueue queue = this.queue(file -> incoming);
        DownloadQueue.Download download = this.onLoop(
                () -> queue.add(Ed2kLink.FileLink.of("lost.bin", data.length, Ed2k.md4(data, 0, data.length))));
        this.onLoop(() -> {
            queue.received(download, 0, data);
            return null;
        });
        assertThat(this.awaitPrefix("failed")).contains("cannot move");

        Path data0 = download.file().dataFile();
        Files.delete(data0);
        Files.createDirectories(data0.resolve("in the way"));
        assertThat(this.onLoop(() -> queue.resume(download))).isTrue();

        assertThat(this.awaitPrefix("failed")).contains("cannot open the data");
        assertThat(this.onLoop(() -> download.file().status())).isEqualTo(PartFile.Status.ERROR);
    }

    @Test
    void checksAndRecoveriesThatEndAfterTheCancelAreIgnored() throws Exception {
        byte[] data = random(400_000, 11);
        AtomicReference<DownloadQueue> queue = new AtomicReference<>();
        queue.set(this.queue(file -> this.dir.resolve("incoming"), new Recorder() {
            @Override
            public void progress(DownloadQueue.Download download) {
                // The check of the part just written is on its way.
                queue.get().cancel(download);
                super.progress(download);
            }
        }));
        DownloadQueue.Download checked = this.onLoop(() ->
                queue.get().add(Ed2kLink.FileLink.of("checked.bin", data.length, Ed2k.md4(data, 0, data.length))));
        this.onLoop(() -> {
            queue.get().received(checked, 0, data);
            return null;
        });
        assertThat(this.awaitPrefix("progress")).isEqualTo("progress");
        assertThat(this.events.poll(500, TimeUnit.MILLISECONDS))
                .as("no completion")
                .isNull();

        DownloadQueue.Download recovered = this.onLoop(() ->
                queue.get().add(Ed2kLink.FileLink.of("recovered.bin", data.length, Hash16.parseHex("16".repeat(16)))));
        this.onLoop(() -> {
            queue.get().recovered(recovered, 0, List.of());
            queue.get().cancel(recovered);
            queue.get().recovered(recovered, 0, List.of());
            return null;
        });
        assertThat(this.events.poll(500, TimeUnit.MILLISECONDS)).isEqualTo("added");
        assertThat(this.events.poll(500, TimeUnit.MILLISECONDS)).isNull();
    }

    @Test
    void recoveryDataTakesBackOnlyTheBlocksWhoseHashesMatch() throws Exception {
        byte[] data = random(400_000, 12);
        byte[] bad = data.clone();
        bad[data.length - 1] ^= 1;
        DownloadQueue queue = this.queue(file -> this.dir.resolve("incoming"), new Recorder());
        DownloadQueue.Download download = this.onLoop(
                () -> queue.add(Ed2kLink.FileLink.of("aich.bin", data.length, Ed2k.md4(data, 0, data.length))));
        this.onLoop(() -> {
            queue.received(download, 0, bad);
            return null;
        });
        assertThat(this.awaitPrefix("corrupt")).isEqualTo("corrupt 0");

        this.onLoop(() -> {
            queue.recovered(download, 0, List.of(Hash20.parseBase32("A".repeat(32))));
            return null;
        });
        assertThat(this.awaitPrefix("progress"))
                .as("the hashes do not fit the part")
                .isEqualTo("progress");
        assertThat(this.onLoop(() -> download.file().completedBytes())).isZero();

        // Hashes that prove the blocks on the disk, wrong as they are: the part is whole again, and checked again.
        this.onLoop(() -> {
            queue.recovered(download, 0, blockHashes(bad));
            return null;
        });
        assertThat(this.awaitPrefix("corrupt")).isEqualTo("corrupt 0");
    }

    @Test
    void theFreeSpaceOfAVanishedTempDirectoryChangesNothing() throws Exception {
        this.minFree.set(1);
        DownloadQueue queue = this.queue();
        Files.delete(this.dir.resolve("temp"));

        this.onLoop(() -> {
            queue.checkSpace();
            return null;
        });

        assertThat(this.onLoop(queue::isSpaceShort)).isFalse();
        assertThat(this.events.poll(200, TimeUnit.MILLISECONDS)).isNull();
    }

    @Test
    void filesThatCannotBeSavedOrDeletedAreLeftBehind() throws Exception {
        byte[] data = random(5000, 13);
        DownloadQueue queue = this.queue();
        DownloadQueue.Download kept =
                this.onLoop(() -> queue.add(Ed2kLink.FileLink.of("kept.bin", 5000, Hash16.parseHex("17".repeat(16)))));
        DownloadQueue.Download done = this.onLoop(
                () -> queue.add(Ed2kLink.FileLink.of("done.bin", data.length, Ed2k.md4(data, 0, data.length))));
        Path keptMet = kept.file().metFile();
        Path doneMet = done.file().metFile();
        // Directories in the way of the saving and of the deleting.
        Files.createDirectories(AtomicFiles.tmpPath(keptMet).resolve("x"));
        Files.createDirectories(AtomicFiles.bakPath(keptMet).resolve("x"));
        Files.createDirectories(AtomicFiles.bakPath(doneMet).resolve("x"));

        this.onLoop(() -> {
            queue.pause(kept);
            queue.received(done, 0, data);
            return null;
        });
        Path file = Path.of(this.awaitPrefix("completed ").substring("completed ".length()));
        assertThat(Files.readAllBytes(file)).isEqualTo(data);
        assertThat(doneMet).doesNotExist();
        assertThat(AtomicFiles.bakPath(doneMet)).isDirectory();

        this.onLoop(() -> {
            queue.close();
            return null;
        });
        assertThat(keptMet).as("the paused state could not be saved").exists();
        assertThat(this.onLoop(() -> queue.cancel(kept))).isTrue();
        assertThat(this.onLoop(queue::all)).isEmpty();
        assertThat(AtomicFiles.bakPath(keptMet)).isDirectory();
    }

    private static List<Hash20> blockHashes(byte[] data) {
        List<Hash20> blocks = new ArrayList<>();
        for (int at = 0; at < data.length; at += Ed2k.EMBLOCKSIZE) {
            MessageDigest sha1 = AichTree.sha1();
            sha1.update(data, at, Math.min(Ed2k.EMBLOCKSIZE, data.length - at));
            blocks.add(Hash20.of(sha1.digest()));
        }
        return blocks;
    }

    /** Tells every event, a corrupt part too; free space it leaves alone. */
    private class Recorder implements DownloadQueue.Listener {
        @Override
        public void added(DownloadQueue.Download download) {
            DownloadQueueTest.this.events.add("added");
        }

        @Override
        public void progress(DownloadQueue.Download download) {
            DownloadQueueTest.this.events.add("progress");
        }

        @Override
        public void completed(DownloadQueue.Download download, Path file) {
            DownloadQueueTest.this.events.add("completed " + file);
        }

        @Override
        public void failed(DownloadQueue.Download download, String reason) {
            DownloadQueueTest.this.events.add("failed " + reason);
        }

        @Override
        public void corrupt(DownloadQueue.Download download, int part) {
            DownloadQueueTest.this.events.add("corrupt " + part);
        }
    }

    private DownloadQueue queue(Function<PartFile, Path> incoming, DownloadQueue.Listener listener) throws Exception {
        return this.onLoop(
                () -> DownloadQueue.load(this.loop, this.dir.resolve("temp"), incoming, this.minFree::get, listener));
    }

    private void awaitCorruptPart0(DownloadQueue.Download download) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline) {
            this.events.poll(100, TimeUnit.MILLISECONDS);
            Boolean back = this.onLoop(
                    () -> !download.file().isComplete(0) && download.file().isComplete(1));
            if (back && this.onLoop(() -> download.file().completedBytes() == 1000)) {
                return;
            }
        }
        throw new AssertionError("the corrupt part was not taken back");
    }

    @Test
    void downloadsWaitForFreeSpaceAndGoOnByThemselves() throws Exception {
        DownloadQueue queue = this.queue();
        DownloadQueue.Download download =
                this.onLoop(() -> queue.add(Ed2kLink.FileLink.of("wait.bin", 5000, Hash16.parseHex("ab".repeat(16)))));
        assertThat(this.onLoop(download::isActive)).isTrue();

        this.minFree.set(Long.MAX_VALUE);
        this.onLoop(() -> {
            queue.checkSpace();
            return null;
        });
        assertThat(this.awaitPrefix("space")).isEqualTo("space short");
        assertThat(this.onLoop(download::isActive)).isFalse();
        assertThat(this.onLoop(queue::isSpaceShort)).isTrue();

        this.minFree.set(1);
        this.onLoop(() -> {
            queue.checkSpace();
            return null;
        });
        assertThat(this.awaitPrefix("space")).isEqualTo("space enough");
        assertThat(this.onLoop(download::isActive)).isTrue();
    }

    private DownloadQueue queue() throws Exception {
        return this.queue(file -> this.dir.resolve("incoming"));
    }

    private DownloadQueue queue(Function<PartFile, Path> incoming) throws Exception {
        return this.onLoop(() -> DownloadQueue.load(
                this.loop, this.dir.resolve("temp"), incoming, this.minFree::get, new DownloadQueue.Listener() {
                    @Override
                    public void diskSpace(boolean enough) {
                        DownloadQueueTest.this.events.add(enough ? "space enough" : "space short");
                    }

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
            if (event != null && !prefix.startsWith("failed")) {
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
