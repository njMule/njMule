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
import java.time.Duration;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
    private final AtomicReference<Boolean> keptAfterCancel = new AtomicReference<>();
    private DownloadQueue queue;
    private DownloadQueue.Download download;
    private Ed2kLink.FileLink link;
    private byte[] data;
    private byte[] bad;
    private List<Hash16> parts;
    private Path met;
    private Path file;
    private List<PartFile.Block> blocks;
    private boolean accepted;
    private Duration cancelGrace = Duration.ZERO;
    private Optional<DownloadQueue.Download> restored = Optional.empty();

    @AfterEach
    void stop() {
        this.loop.close();
    }

    @Test
    void shouldWriteVerifyAndMoveAFinishedFile() throws Exception {
        givenTheData(random(400_000, 1));
        givenAQueue();
        givenADownloadOfTheData("../evil:name.bin");
        givenTheDownloadWasAnnounced();

        whenTheDataIsReceivedInTwoPiecesLastFirst(200_000);

        thenTheDownloadCompletesWithTheData();
        thenTheCompletedFileIsInTheIncomingDirectory();
        thenTheCompletedFileIsNamed(".._evil_name.bin");
        thenTheTempDirectoryHasNo("001.part", "001.part.met");
    }

    @Test
    void shouldDownloadACorruptPartAgain() throws Exception {
        givenTheData(random((int) Ed2k.PARTSIZE + 1000, 2));
        givenAQueue();
        givenADownloadOfTheDataWithItsPartHashes("two.bin");
        givenTheDataReceivedWithAByteFlippedAt(123);
        givenTheCorruptFirstPartTakenBack();

        whenTheFirstPartIsReceivedAgain();

        thenTheDownloadCompletesWithTheData();
    }

    @Test
    void shouldStillDownloadALinkWithAWrongHashSet() throws Exception {
        givenAQueue();
        givenALinkWithAWrongHashSet();

        whenTheLinkIsAdded();

        thenTheDownloadIsAnnounced();
        thenTheDownloadHasNoHashSet();
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void shouldAskAgainForARangeThatCouldNotBeWritten() throws Exception {
        givenTheData(random(400_000, 5));
        givenAQueue();
        givenADownloadOfTheData("locked.bin");
        givenTheDownloadWasAnnounced();
        givenTheFirstBlocksAskedFor();
        givenTheFirstBlockCouldNotBeWrittenWhileAnotherProcessLockedTheData();

        whenTheBlocksAreAskedForAfterAPauseAndResume();

        thenTheBlocksAskedForAre(new PartFile.Block(0, Ed2k.EMBLOCKSIZE));
    }

    @Test
    void shouldNotCancelADownloadWhileItsFileIsMoved() throws Exception {
        givenTheData(random(1000, 6));
        givenAQueueWhoseUserCancelsAndAddsTheDownloadAgainWhileItsFileIsMoved();
        givenADownloadOfTheData("moved.bin");

        whenTheDataIsReceived();

        thenTheDownloadCompletesWithTheData();
        thenTheDownloadWasKeptAfterTheCancel();
        thenTheQueueIsEmpty();
        thenNothingIsLeftInTheTempDirectory();
        thenTheDiskThreadOfTheDownloadEnds();
    }

    @Test
    void shouldMoveOnResumeAFileThatCouldNotBeMoved() throws Exception {
        givenTheData(random(2000, 7));
        givenAFileWhereTheIncomingDirectoryShouldBe();
        givenAQueue();
        givenADownloadOfTheData("stuck.bin");
        givenTheDataReceived();
        givenTheDownloadFailedWith("cannot move");
        givenTheDownloadWentIntoError();
        givenTheIncomingDirectoryFreed();

        whenTheDownloadIsPausedAndResumed();

        thenTheDownloadCompletesWithTheData();
        thenTheQueueNoLongerHasTheDownload();
    }

    @Test
    void shouldLoadTwoPartFilesOfOneFileAsOneDownload() throws Exception {
        givenAQueue();
        givenADownload("twice.bin", 1000, Hash16.parseHex("9c".repeat(16)));
        givenTheQueueClosed();
        givenThePartFilesCopied("001", "002");

        whenTheQueueIsLoadedAgain();

        thenOnlyTheFirstOfThemIsLoaded();
        thenTheQueueCloses();
    }

    @ParameterizedTest
    @ValueSource(longs = {Ed2k.PARTSIZE, Ed2k.PARTSIZE + 1})
    void shouldCompleteFilesOfExactlyOnePartAndOneByteMore(long size) throws Exception {
        givenTheData(random((int) size, size));
        givenAQueue();
        givenADownloadOfTheDataWithItsPartHashes("edge" + size + ".bin");
        givenTheDownloadHasItsHashSet();

        whenTheDataIsReceivedBackwardsInPiecesOf(1_000_000);

        thenTheDownloadCompletesWithTheData();
        thenTheCompletedFileHasTheSize(size);
    }

    @Test
    void shouldReloadDownloadsFromTheirPartFiles() throws Exception {
        givenTheData(random(300_000, 3));
        givenAQueue();
        givenADownloadOfTheData("kept.bin");
        givenTheDataReceivedUpTo(100_000);
        givenTheQueueClosed();

        whenTheQueueIsLoadedAgain();

        thenTheReloadedDownloadIsNamed("kept.bin");
        thenTheReloadedDownloadHasCompleted(100_000);
    }

    @Test
    void shouldCheckTheCompletePartsOfAResumedDownload() throws Exception {
        givenTheData(random(200_000, 4));
        givenAQueue();
        givenADownloadOfTheData("resumed.bin");
        givenTheDataMarkedWrittenButNotChecked();
        givenTheDataOnTheDiskWithAByteFlippedAt(7);
        givenTheQueueClosed();

        whenTheQueueIsLoadedAgain();

        thenTheCorruptDataOfTheReloadedDownloadIsTakenBack();
        thenNoPartOfTheReloadedDownloadIsVerified();
    }

    @Test
    void shouldLoadADownloadOfWhichOnlyTheBackupIsLeftAndNeverReuseItsNumber() throws Exception {
        givenAQueue();
        givenADownload("kept.bin", 1000, Hash16.parseHex("12".repeat(16)));
        givenTheQueueClosed();
        givenOnlyTheBackupOfThePartFileLeft("001");
        givenADataFileLeftByACrash("002");
        givenTheQueueLoadedAgain();
        givenTheQueueHasTheDownload();

        whenADownloadIsAdded("new.bin", 1000, Hash16.parseHex("34".repeat(16)));

        thenItsNumberIs(3);
        thenTheQueueCloses();
    }

    @Test
    void shouldIgnoreAWriteThatEndsAfterTheCancel() throws Exception {
        givenTheData(random(1000, 3));
        givenAQueue();
        givenADownloadOfTheData("gone.bin");
        givenTheDownloadWasAnnounced();

        whenTheDataIsReceivedAndTheDownloadCancelledAtOnce();

        thenNoEventComesWithin(Duration.ofSeconds(1));
        thenNothingIsCompleted();
        thenItsFilesAreGoneOnceTheQueueCloses();
    }

    @Test
    void shouldKeepTheNumbersOfLeftoversInTheTempDirectory() throws Exception {
        givenLeftoversInTheTempDirectory();
        givenAQueue();
        givenTheUnreadablePartFilesLeftAlone();

        whenADownloadIsAdded("fresh.bin", 5000, Hash16.parseHex("13".repeat(16)));

        thenItsNumberIs(5);
        thenTheLeftoverPartFileIsKept();
    }

    @Test
    void shouldTakeNoDataBeyondTheEndOfADownload() throws Exception {
        givenAQueue();
        givenADownload("small.bin", 5000, Hash16.parseHex("14".repeat(16)));
        givenTheDownloadWasAnnounced();

        whenDataIsReceivedAt(4990, 20);

        thenNoEventComesWithin(Duration.ofMillis(300));
        thenNothingIsCompleted();
    }

    @Test
    void shouldTakeNoDataForAPausedDownload() throws Exception {
        givenAQueue();
        givenADownload("small.bin", 5000, Hash16.parseHex("14".repeat(16)));
        givenTheDownloadWasAnnounced();
        givenTheDownloadPaused();

        whenDataIsReceivedAt(0, 100);

        thenNoEventComesWithin(Duration.ofMillis(300));
        thenNothingIsCompleted();
        thenTheDownloadHasNoSources();
    }

    @Test
    void shouldSetAPausedDownloadToPreviewFirst() throws Exception {
        givenAQueue();
        givenADownload("small.bin", 5000, Hash16.parseHex("14".repeat(16)));
        givenTheDownloadWasAnnounced();
        givenTheDownloadPaused();

        whenTheDownloadIsSetToPreviewFirst();

        thenNoEventComesWithin(Duration.ofMillis(300));
        thenTheDownloadIsPreviewedFirst();
    }

    @Test
    void shouldCheckAPartOnceItsHashIsKnownAndTrustItAfterARestart() throws Exception {
        givenTheData(random(1000, 8));
        givenAQueue();
        givenADownloadWithoutHashSetWhoseSecondPartIsTheData("late.bin");
        givenTheSecondPartReceived();
        givenNoPartVerifiedForWantOfItsHash();
        givenTheHashSetKnown();
        givenTheSecondPartVerified();
        givenTheQueueClosed();

        whenTheQueueIsLoadedAgain();

        thenTheVerifiedPartsOfTheReloadedDownloadAre(BitSet.valueOf(new long[] {2}));
        thenTheQueueCloses();
    }

    @Test
    void shouldMoveAFinishedDownloadThatCouldNotBeMovedWhenLoadedAgain() throws Exception {
        givenTheData(random(5000, 9));
        givenAFileWhereTheIncomingDirectoryShouldBe();
        givenAQueue();
        givenADownloadOfTheData("again.bin");
        givenTheDataReceived();
        givenTheDownloadFailedWith("cannot move");
        givenTheQueueClosed();
        givenTheIncomingDirectoryFreed();

        whenTheQueueIsLoadedAgain();

        thenTheDownloadCompletesWithTheData();
    }

    @Test
    void shouldFailADownloadWhoseDataCannotBeOpenedAgain() throws Exception {
        givenTheData(random(5000, 10));
        givenAFileWhereTheIncomingDirectoryShouldBe();
        givenAQueue();
        givenADownloadOfTheData("lost.bin");
        givenTheDataReceived();
        givenTheDownloadFailedWith("cannot move");
        givenADirectoryInTheWayOfItsData();

        whenTheDownloadIsResumed();

        thenItIsAccepted();
        thenTheDownloadFailsWith("cannot open the data");
        thenTheDownloadIsInError();
    }

    @Test
    void shouldIgnoreACheckThatEndsAfterTheCancel() throws Exception {
        givenTheData(random(400_000, 11));
        givenAQueueWhoseListenerCancelsADownloadOnItsProgress();
        givenADownloadOfTheData("checked.bin");

        whenTheDataIsReceived();

        thenTheDownloadProgresses();
        thenTheDownloadDoesNotComplete();
    }

    @Test
    void shouldIgnoreRecoveriesThatEndAfterTheCancel() throws Exception {
        givenAQueueWhoseListenerCancelsADownloadOnItsProgress();
        givenADownload("recovered.bin", 400_000, Hash16.parseHex("16".repeat(16)));

        whenRecoveryDataArrivesBeforeAndAfterTheCancel();

        thenTheNextEventIs("added");
        thenNoEventComesWithin(Duration.ofMillis(500));
    }

    @Test
    void shouldTakeBackOnlyTheBlocksWhoseRecoveryHashesMatch() throws Exception {
        givenTheData(random(400_000, 12));
        givenAQueueThatTellsEveryEvent();
        givenADownloadOfTheData("aich.bin");
        givenTheDataReceivedWithAByteFlippedAt(399_999);
        givenThePartFoundCorrupt(0);
        givenRecoveryDataReceived(List.of(Hash20.parseBase32("A".repeat(32))));
        givenTheRecoveryHashesDidNotFitThePart();

        whenRecoveryDataProvingTheBlocksOnTheDiskIsReceived();

        thenThePartIsFoundCorrupt(0);
    }

    @Test
    void shouldChangeNothingForTheFreeSpaceOfAVanishedTempDirectory() throws Exception {
        givenAMinimumFreeSpaceOf(1);
        givenAQueue();
        givenTheTempDirectoryDeleted();

        whenTheFreeSpaceIsChecked();

        thenTheSpaceIsNotShort();
        thenNoEventComesWithin(Duration.ofMillis(200));
    }

    @Test
    void shouldLeaveBehindTheBackupOfAFinishedDownloadThatCannotBeDeleted() throws Exception {
        givenTheData(random(5000, 13));
        givenAQueue();
        givenADownloadOfTheData("done.bin");
        givenADirectoryInTheWayOfDeletingItsBackup();

        whenTheDataIsReceived();

        thenTheDownloadCompletesWithTheData();
        thenItsPartFileIsGone();
        thenItsBackupIsLeftBehind();
    }

    @Test
    void shouldLeaveBehindThePartFilesOfACancelledDownloadThatCannotBeSaved() throws Exception {
        givenAQueue();
        givenADownload("kept.bin", 5000, Hash16.parseHex("17".repeat(16)));
        givenDirectoriesInTheWayOfSavingAndDeletingItsPartFile();
        givenTheDownloadPaused();
        givenTheQueueClosed();
        givenThePausedStateCouldNotBeSaved();

        whenTheDownloadIsCancelled();

        thenItIsAccepted();
        thenTheQueueIsEmpty();
        thenItsBackupIsLeftBehindSetAside();
    }

    @Test
    void shouldLetDownloadsWaitForFreeSpaceAndGoOnByThemselves() throws Exception {
        givenAQueue();
        givenADownload("wait.bin", 5000, Hash16.parseHex("ab".repeat(16)));
        givenTheDownloadIsActive();
        givenAMinimumFreeSpaceOf(Long.MAX_VALUE);
        givenTheFreeSpaceChecked();
        givenTheDownloadWaitsForFreeSpace();
        givenAMinimumFreeSpaceOf(1);

        whenTheFreeSpaceIsChecked();

        thenTheNextSpaceEventIs("space enough");
        thenTheDownloadIsActive();
    }

    private void givenTheData(byte[] bytes) {
        this.data = bytes;
    }

    @Test
    void shouldKeepTheFilesOfACancelledDownloadWhileItCanBeTakenBack() throws Exception {
        givenAQueueKeepingCancelledDownloadsFor(Duration.ofMinutes(1));
        givenADownload("undo.bin", 5000, Hash16.parseHex("21".repeat(16)));

        whenTheDownloadIsCancelled();

        thenItsDataIsKeptButNoStartLoadsIt();
    }

    @Test
    void shouldTakeBackACancelledDownloadAsItWas() throws Exception {
        givenAQueueKeepingCancelledDownloadsFor(Duration.ofMinutes(1));
        givenADownload("undo.bin", 5000, Hash16.parseHex("22".repeat(16)));
        givenTheDownloadPaused();
        givenTheDownloadCancelled();

        whenTheDownloadIsTakenBack();

        thenItIsBackPaused();
    }

    @Test
    void shouldDeleteTheFilesOfACancelledDownloadOnceItCanNoLongerBeTakenBack() throws Exception {
        givenAQueueKeepingCancelledDownloadsFor(Duration.ofMillis(100));
        givenADownload("undo.bin", 5000, Hash16.parseHex("23".repeat(16)));
        givenTheDownloadCancelled();
        givenNothingIsLeftInTheTempDirectorySoon();

        whenTheDownloadIsTakenBack();

        thenNothingIsTakenBack();
    }

    @Test
    void shouldNotTakeBackACancelledDownloadWhoseLinkWasAddedAgain() throws Exception {
        givenAQueueKeepingCancelledDownloadsFor(Duration.ofMinutes(1));
        givenADownload("undo.bin", 5000, Hash16.parseHex("24".repeat(16)));
        givenTheDownloadCancelled();
        givenTheLinkAddedAgain();

        whenTheDownloadIsTakenBack();

        thenNothingIsTakenBack();
    }

    // What a cancel set aside goes last: until then the next start deletes it, and no new download may take its number.
    @Test
    void shouldNotGiveANewDownloadTheNumberOfAMetSetAsideByACancel() throws Exception {
        givenAQueue();
        givenOnlyTheSetAsideMetLeftForTheNumber(1);

        whenADownloadIsAdded("new.bin", 1000, Hash16.parseHex("35".repeat(16)));

        thenItsNumberIs(2);
        thenTheQueueCloses();
    }

    @Test
    void shouldDeleteWhatACancelKeptWhenNjMuleStopped() throws Exception {
        givenTheFilesACancelKeptForTheNumber(7);

        whenTheQueueIsLoadedAgain();

        thenTheQueueIsEmpty();
        thenNothingIsLeftInTheTempDirectory();
    }

    private void givenAQueueKeepingCancelledDownloadsFor(Duration grace) throws Exception {
        this.cancelGrace = grace;
        givenAQueue();
    }

    private void givenTheDownloadCancelled() throws Exception {
        whenTheDownloadIsCancelled();
        thenItIsAccepted();
    }

    private void givenTheLinkAddedAgain() throws Exception {
        Hash16 hash = this.download.hash();
        onLoop(() -> this.queue.add(Ed2kLink.FileLink.of("undo.bin", 5000, hash)));
    }

    private void givenNothingIsLeftInTheTempDirectorySoon() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline && !tempDirectoryIsEmpty()) {
            Thread.sleep(20);
        }
        thenNothingIsLeftInTheTempDirectory();
    }

    private void givenTheFilesACancelKeptForTheNumber(int number) throws IOException {
        Path temp = Files.createDirectories(this.dir.resolve("temp"));
        String base = PartFile.fileName(number);
        Files.write(temp.resolve(base + PartFile.MET_SUFFIX + ".cancelled"), new byte[] {1});
        Files.write(temp.resolve(base + PartFile.MET_SUFFIX + ".bak.cancelled"), new byte[] {1});
        Files.write(temp.resolve(base + PartFile.DATA_SUFFIX), new byte[] {1});
    }

    private void givenOnlyTheSetAsideMetLeftForTheNumber(int number) throws IOException {
        Path temp = Files.createDirectories(this.dir.resolve("temp"));
        Files.write(temp.resolve(PartFile.fileName(number) + PartFile.MET_SUFFIX + ".cancelled"), new byte[] {1});
    }

    private void whenTheDownloadIsTakenBack() throws Exception {
        Hash16 hash = this.download.hash();
        this.restored = onLoop(() -> this.queue.restore(hash)).get(30, TimeUnit.SECONDS);
    }

    private void thenItsDataIsKeptButNoStartLoadsIt() throws Exception {
        onLoop(() -> this.download.storage().whenClosed()).get(30, TimeUnit.SECONDS);
        Path temp = this.dir.resolve("temp");
        String base = PartFile.fileName(this.download.file().number());
        assertThat(temp.resolve(base + PartFile.DATA_SUFFIX)).exists();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline && Files.exists(temp.resolve(base + PartFile.MET_SUFFIX))) {
            Thread.sleep(20);
        }
        assertThat(temp.resolve(base + PartFile.MET_SUFFIX)).doesNotExist();
        assertThat(temp.resolve(base + PartFile.MET_SUFFIX + ".cancelled")).exists();
    }

    private void thenItIsBackPaused() throws Exception {
        assertThat(this.restored).isPresent();
        assertThat(onLoop(() ->
                        this.queue.get(this.download.hash()).map(d -> d.file().status())))
                .contains(PartFile.Status.PAUSED);
        assertThat(this.dir
                        .resolve("temp")
                        .resolve(PartFile.fileName(this.download.file().number()) + PartFile.MET_SUFFIX))
                .exists();
    }

    private void thenNothingIsTakenBack() {
        assertThat(this.restored).isEmpty();
    }

    private boolean tempDirectoryIsEmpty() throws IOException {
        try (Stream<Path> left = Files.list(this.dir.resolve("temp"))) {
            return left.findAny().isEmpty();
        }
    }

    private void givenAQueue() throws Exception {
        this.queue = queue();
    }

    private void givenAQueueThatTellsEveryEvent() throws Exception {
        this.queue = queue(file -> this.dir.resolve("incoming"), new Recorder());
    }

    private void givenAQueueWhoseListenerCancelsADownloadOnItsProgress() throws Exception {
        this.queue = queue(file -> this.dir.resolve("incoming"), new Recorder() {
            @Override
            public void progress(DownloadQueue.Download d) {
                // The check of the part just written is on its way.
                DownloadQueueTest.this.queue.cancel(d);
                super.progress(d);
            }
        });
    }

    private void givenAQueueWhoseUserCancelsAndAddsTheDownloadAgainWhileItsFileIsMoved() throws Exception {
        this.queue = queue(file -> {
            // The user cancels and adds the link again while the finished file is being moved.
            this.loop.post(() -> {
                DownloadQueue q = this.queue;
                q.cancel(q.get(this.link.hash()).orElseThrow());
                this.keptAfterCancel.set(q.get(this.link.hash()).isPresent());
                try {
                    q.add(this.link);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
            return this.dir.resolve("incoming");
        });
    }

    private void givenAFileWhereTheIncomingDirectoryShouldBe() throws IOException {
        Files.writeString(this.dir.resolve("incoming"), "a file where the directory should be");
    }

    private void givenAMinimumFreeSpaceOf(long bytes) {
        this.minFree.set(bytes);
    }

    private void givenLeftoversInTheTempDirectory() throws IOException {
        Path temp = Files.createDirectories(this.dir.resolve("temp"));
        Files.write(temp.resolve("001" + PartFile.MET_SUFFIX), new byte[] {1, 2, 3});
        Files.write(AtomicFiles.bakPath(temp.resolve("002" + PartFile.MET_SUFFIX)), new byte[] {1, 2, 3});
        Files.write(AtomicFiles.tmpPath(temp.resolve("003" + PartFile.MET_SUFFIX)), new byte[] {1});
        Files.write(temp.resolve("004" + PartFile.DATA_SUFFIX), new byte[] {1});
    }

    private void givenADownloadOfTheData(String name) throws Exception {
        this.link = Ed2kLink.FileLink.of(name, this.data.length, Ed2k.md4(this.data, 0, this.data.length));
        this.download = onLoop(() -> this.queue.add(this.link));
    }

    private void givenADownloadOfTheDataWithItsPartHashes(String name) throws Exception {
        this.parts = partHashes(this.data);
        this.link = new Ed2kLink.FileLink(
                name, this.data.length, Ed2k.fileHash(this.parts), Optional.empty(), this.parts, List.of(), List.of());
        this.download = onLoop(() -> this.queue.add(this.link));
    }

    private void givenADownloadWithoutHashSetWhoseSecondPartIsTheData(String name) throws Exception {
        this.parts = List.of(Hash16.parseHex("15".repeat(16)), Ed2k.md4(this.data, 0, this.data.length));
        this.link = new Ed2kLink.FileLink(
                name,
                Ed2k.PARTSIZE + this.data.length,
                Ed2k.fileHash(this.parts),
                Optional.empty(),
                List.of(),
                List.of(),
                List.of());
        this.download = onLoop(() -> this.queue.add(this.link));
    }

    private void givenADownload(String name, long size, Hash16 hash) throws Exception {
        this.download = onLoop(() -> this.queue.add(Ed2kLink.FileLink.of(name, size, hash)));
    }

    private void givenALinkWithAWrongHashSet() {
        this.link = new Ed2kLink.FileLink(
                "exact.bin",
                Ed2k.PARTSIZE,
                Hash16.parseHex("56".repeat(16)),
                Optional.empty(),
                List.of(Hash16.parseHex("78".repeat(16))),
                List.of(),
                List.of());
    }

    private void givenTheDownloadWasAnnounced() throws InterruptedException {
        assertThat(this.events.take()).isEqualTo("added");
    }

    private void givenTheDownloadHasItsHashSet() {
        assertThat(this.download.file().hasHashSet()).isTrue();
    }

    private void givenTheDataReceived() throws Exception {
        onLoop(() -> {
            this.queue.received(this.download, 0, this.data);
            return null;
        });
    }

    private void givenTheDataReceivedUpTo(int end) throws Exception {
        onLoop(() -> {
            this.queue.received(this.download, 0, Arrays.copyOfRange(this.data, 0, end));
            return null;
        });
        awaitPrefix("progress");
    }

    // Received a part at a time.
    private void givenTheDataReceivedWithAByteFlippedAt(int at) throws Exception {
        this.bad = this.data.clone();
        this.bad[at] ^= 1;
        onLoop(() -> {
            for (long start = 0; start < this.bad.length; start += Ed2k.PARTSIZE) {
                int end = (int) Math.min(this.bad.length, start + Ed2k.PARTSIZE);
                this.queue.received(this.download, start, Arrays.copyOfRange(this.bad, (int) start, end));
            }
            return null;
        });
    }

    private void givenTheCorruptFirstPartTakenBack() throws Exception {
        awaitCorruptPart0(this.download);
    }

    private void givenTheFirstBlocksAskedFor() throws Exception {
        onLoop(() -> this.download.file().nextBlocks(firstPart(), 3));
    }

    // Another process holds a lock on the data: the write fails.
    private void givenTheFirstBlockCouldNotBeWrittenWhileAnotherProcessLockedTheData() throws Exception {
        try (FileChannel other =
                        FileChannel.open(this.dir.resolve("temp").resolve("001.part"), StandardOpenOption.WRITE);
                FileLock _ = other.lock()) {
            onLoop(() -> {
                this.queue.received(this.download, 0, Arrays.copyOf(this.data, Ed2k.EMBLOCKSIZE));
                return null;
            });
            assertThat(awaitPrefix("failed")).contains("cannot write");
        }
    }

    private void givenTheDownloadFailedWith(String reason) throws InterruptedException {
        assertThat(awaitPrefix("failed")).contains(reason);
    }

    private void givenTheDownloadWentIntoError() throws Exception {
        assertThat(onLoop(() -> this.download.file().status())).isEqualTo(PartFile.Status.ERROR);
    }

    private void givenTheIncomingDirectoryFreed() throws IOException {
        Files.delete(this.dir.resolve("incoming"));
    }

    private void givenTheQueueClosed() throws Exception {
        closeTheQueue();
    }

    private void givenThePartFilesCopied(String from, String to) throws IOException {
        Path temp = this.dir.resolve("temp");
        Files.copy(temp.resolve(from + PartFile.MET_SUFFIX), temp.resolve(to + PartFile.MET_SUFFIX));
        Files.copy(temp.resolve(from + PartFile.DATA_SUFFIX), temp.resolve(to + PartFile.DATA_SUFFIX));
    }

    private void givenOnlyTheBackupOfThePartFileLeft(String number) throws IOException {
        Path partMet = this.dir.resolve("temp").resolve(number + PartFile.MET_SUFFIX);
        Files.move(partMet, AtomicFiles.bakPath(partMet), StandardCopyOption.REPLACE_EXISTING);
    }

    private void givenADataFileLeftByACrash(String number) throws IOException {
        Files.writeString(this.dir.resolve("temp").resolve(number + PartFile.DATA_SUFFIX), "left by a crash");
    }

    private void givenTheQueueLoadedAgain() throws Exception {
        this.queue = queue();
    }

    private void givenTheQueueHasTheDownload() throws Exception {
        assertThat(onLoop(() -> this.queue.get(this.download.hash()))).isPresent();
    }

    private void givenTheUnreadablePartFilesLeftAlone() throws Exception {
        assertThat(onLoop(this.queue::all))
                .as("unreadable part files are left alone")
                .isEmpty();
    }

    private void givenTheDownloadPaused() throws Exception {
        onLoop(() -> {
            this.queue.pause(this.download);
            return null;
        });
    }

    private void givenADirectoryInTheWayOfItsData() throws IOException {
        Path data0 = this.download.file().dataFile();
        Files.delete(data0);
        Files.createDirectories(data0.resolve("in the way"));
    }

    private void givenThePartFoundCorrupt(int part) throws InterruptedException {
        assertThat(awaitPrefix("corrupt")).isEqualTo("corrupt " + part);
    }

    private void givenRecoveryDataReceived(List<Hash20> blocks) throws Exception {
        onLoop(() -> {
            this.queue.recovered(this.download, 0, blocks);
            return null;
        });
    }

    private void givenTheRecoveryHashesDidNotFitThePart() throws Exception {
        assertThat(awaitPrefix("progress")).as("the hashes do not fit the part").isEqualTo("progress");
        assertThat(onLoop(() -> this.download.file().completedBytes())).isZero();
    }

    private void givenTheSecondPartReceived() throws Exception {
        onLoop(() -> {
            this.queue.received(this.download, Ed2k.PARTSIZE, this.data);
            return null;
        });
        assertThat(awaitPrefix("progress")).isEqualTo("progress");
    }

    private void givenNoPartVerifiedForWantOfItsHash() throws Exception {
        assertThat(onLoop(() -> this.download.verifiedParts().isEmpty()))
                .as("no hash to check it with")
                .isTrue();
    }

    private void givenTheHashSetKnown() throws Exception {
        onLoop(() -> {
            this.download.file().setHashSet(this.parts);
            this.queue.hashSetKnown(this.download);
            return null;
        });
    }

    private void givenTheSecondPartVerified() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!onLoop(() -> this.download.verifiedParts().get(1))) {
            assertThat(System.nanoTime()).isLessThan(deadline);
            Thread.sleep(20);
        }
    }

    private void givenTheTempDirectoryDeleted() throws IOException {
        Files.delete(this.dir.resolve("temp"));
    }

    private void givenTheDownloadIsActive() throws Exception {
        assertThat(onLoop(this.download::isActive)).isTrue();
    }

    private void givenTheFreeSpaceChecked() throws Exception {
        checkTheFreeSpace();
    }

    private void givenTheDownloadWaitsForFreeSpace() throws Exception {
        assertThat(awaitPrefix("space")).isEqualTo("space short");
        assertThat(onLoop(this.download::isActive)).isFalse();
        assertThat(onLoop(this.queue::isSpaceShort)).isTrue();
    }

    private void givenADirectoryInTheWayOfDeletingItsBackup() throws IOException {
        this.met = this.download.file().metFile();
        Files.createDirectories(AtomicFiles.bakPath(this.met).resolve("x"));
    }

    // Directories in the way of the saving and of the deleting.
    private void givenDirectoriesInTheWayOfSavingAndDeletingItsPartFile() throws IOException {
        this.met = this.download.file().metFile();
        Files.createDirectories(AtomicFiles.tmpPath(this.met).resolve("x"));
        Files.createDirectories(AtomicFiles.bakPath(this.met).resolve("x"));
    }

    private void givenThePausedStateCouldNotBeSaved() {
        assertThat(this.met).as("the paused state could not be saved").exists();
    }

    // Written, then closed before the check could run: as after a crash.
    private void givenTheDataMarkedWrittenButNotChecked() throws Exception {
        onLoop(() -> {
            this.download.file().written(0, this.data.length);
            this.download.file().save();
            return null;
        });
    }

    private void givenTheDataOnTheDiskWithAByteFlippedAt(int at) throws IOException {
        this.bad = this.data.clone();
        this.bad[at] ^= 1;
        Files.write(this.dir.resolve("temp").resolve("001.part"), this.bad);
    }

    private void whenTheDataIsReceivedInTwoPiecesLastFirst(int middle) throws Exception {
        onLoop(() -> {
            this.queue.received(this.download, middle, Arrays.copyOfRange(this.data, middle, this.data.length));
            this.queue.received(this.download, 0, Arrays.copyOfRange(this.data, 0, middle));
            return null;
        });
    }

    private void whenTheFirstPartIsReceivedAgain() throws Exception {
        onLoop(() -> {
            this.queue.received(this.download, 0, Arrays.copyOfRange(this.data, 0, (int) Ed2k.PARTSIZE));
            return null;
        });
    }

    private void whenTheLinkIsAdded() throws Exception {
        this.download = onLoop(() -> this.queue.add(this.link));
    }

    private void whenTheBlocksAreAskedForAfterAPauseAndResume() throws Exception {
        this.blocks = onLoop(() -> {
            this.queue.pause(this.download);
            this.queue.resume(this.download);
            return this.download.file().nextBlocks(firstPart(), 3);
        });
    }

    private void whenTheDataIsReceived() throws Exception {
        onLoop(() -> {
            this.queue.received(this.download, 0, this.data);
            return null;
        });
    }

    private void whenTheDownloadIsPausedAndResumed() throws Exception {
        onLoop(() -> {
            this.queue.pause(this.download);
            this.queue.resume(this.download);
            return null;
        });
    }

    // An empty piece first, then the pieces from the end to the start.
    private void whenTheDataIsReceivedBackwardsInPiecesOf(int piece) throws Exception {
        onLoop(() -> {
            this.queue.received(this.download, 0, new byte[0]);
            for (int at = this.data.length; at > 0; at -= piece) {
                int from = Math.max(0, at - piece);
                this.queue.received(this.download, from, Arrays.copyOfRange(this.data, from, at));
            }
            return null;
        });
    }

    private void whenTheQueueIsLoadedAgain() throws Exception {
        this.queue = queue();
    }

    private void whenADownloadIsAdded(String name, long size, Hash16 hash) throws Exception {
        this.download = onLoop(() -> this.queue.add(Ed2kLink.FileLink.of(name, size, hash)));
    }

    private void whenTheDataIsReceivedAndTheDownloadCancelledAtOnce() throws Exception {
        onLoop(() -> {
            this.queue.received(this.download, 0, this.data);
            this.queue.cancel(this.download);
            return null;
        });
    }

    private void whenDataIsReceivedAt(long start, int length) throws Exception {
        onLoop(() -> {
            this.queue.received(this.download, start, new byte[length]);
            return null;
        });
    }

    private void whenTheDownloadIsSetToPreviewFirst() throws Exception {
        onLoop(() -> {
            this.queue.setPreviewFirst(this.download, true);
            return null;
        });
    }

    private void whenTheDownloadIsResumed() throws Exception {
        this.accepted = onLoop(() -> this.queue.resume(this.download));
    }

    private void whenRecoveryDataArrivesBeforeAndAfterTheCancel() throws Exception {
        onLoop(() -> {
            this.queue.recovered(this.download, 0, List.of());
            this.queue.cancel(this.download);
            this.queue.recovered(this.download, 0, List.of());
            return null;
        });
    }

    // Hashes that prove the blocks on the disk, wrong as they are: the part is whole again, and checked again.
    private void whenRecoveryDataProvingTheBlocksOnTheDiskIsReceived() throws Exception {
        onLoop(() -> {
            this.queue.recovered(this.download, 0, blockHashes(this.bad));
            return null;
        });
    }

    private void whenTheFreeSpaceIsChecked() throws Exception {
        checkTheFreeSpace();
    }

    private void whenTheDownloadIsCancelled() throws Exception {
        this.accepted = onLoop(() -> this.queue.cancel(this.download));
    }

    private void thenTheDownloadCompletesWithTheData() throws Exception {
        this.file = Path.of(awaitPrefix("completed ").substring("completed ".length()));
        assertThat(Files.readAllBytes(this.file)).isEqualTo(this.data);
    }

    private void thenTheCompletedFileIsInTheIncomingDirectory() {
        assertThat(this.file.getParent())
                .isEqualTo(this.dir.resolve("incoming").toAbsolutePath().normalize());
    }

    private void thenTheCompletedFileIsNamed(String name) {
        assertThat(this.file.getFileName().toString()).isEqualTo(name);
    }

    private void thenTheCompletedFileHasTheSize(long size) throws IOException {
        assertThat(Files.size(this.file)).isEqualTo(size);
    }

    private void thenTheTempDirectoryHasNo(String... names) {
        for (String name : names) {
            assertThat(this.dir.resolve("temp").resolve(name)).doesNotExist();
        }
    }

    private void thenTheDownloadIsAnnounced() throws InterruptedException {
        assertThat(this.events.take()).isEqualTo("added");
    }

    private void thenTheDownloadHasNoHashSet() {
        assertThat(this.download.file().hasHashSet())
                .as("asked from the sources")
                .isFalse();
    }

    private void thenTheBlocksAskedForAre(PartFile.Block... expected) {
        assertThat(this.blocks).containsExactly(expected);
    }

    private void thenTheDownloadWasKeptAfterTheCancel() {
        assertThat(this.keptAfterCancel.get())
                .as("the cancel waits for nothing it could undo")
                .isTrue();
    }

    private void thenTheQueueIsEmpty() throws Exception {
        assertThat(onLoop(this.queue::all)).isEmpty();
    }

    private void thenNothingIsLeftInTheTempDirectory() throws IOException {
        try (Stream<Path> left = Files.list(this.dir.resolve("temp"))) {
            assertThat(left).as("nothing left behind").isEmpty();
        }
    }

    private void thenTheDiskThreadOfTheDownloadEnds() throws Exception {
        assertThat(onLoop(() -> this.download.storage().isOpen()))
                .as("its disk thread ends")
                .isFalse();
    }

    private void thenTheQueueNoLongerHasTheDownload() throws Exception {
        assertThat(onLoop(() -> this.queue.get(this.download.hash()))).isEmpty();
    }

    private void thenOnlyTheFirstOfThemIsLoaded() throws Exception {
        assertThat(onLoop(this.queue::all))
                .singleElement()
                .satisfies(d ->
                        assertThat(d.file().number()).as("the first is kept").isEqualTo(1));
    }

    private void thenTheQueueCloses() throws Exception {
        closeTheQueue();
    }

    private void thenTheReloadedDownloadIsNamed(String name) {
        assertThat(reloaded().file().name()).isEqualTo(name);
    }

    private void thenTheReloadedDownloadHasCompleted(long bytes) {
        assertThat(reloaded().file().completedBytes()).isEqualTo(bytes);
    }

    private void thenTheCorruptDataOfTheReloadedDownloadIsTakenBack() throws Exception {
        DownloadQueue.Download reloaded = reloaded();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (onLoop(() -> reloaded.file().completedBytes()) != 0) {
            assertThat(System.nanoTime()).isLessThan(deadline);
            this.events.poll(100, TimeUnit.MILLISECONDS);
        }
    }

    private void thenNoPartOfTheReloadedDownloadIsVerified() throws Exception {
        DownloadQueue.Download reloaded = reloaded();
        assertThat(onLoop(() -> reloaded.verifiedParts().isEmpty())).isTrue();
    }

    private void thenTheVerifiedPartsOfTheReloadedDownloadAre(BitSet expected) throws Exception {
        assertThat(onLoop(() -> this.queue.get(this.link.hash()).orElseThrow().verifiedParts()))
                .as("checked before the restart, on data that did not change since")
                .isEqualTo(expected);
    }

    private void thenItsNumberIs(int number) {
        assertThat(this.download.file().number()).isEqualTo(number);
    }

    private void thenNoEventComesWithin(Duration wait) throws InterruptedException {
        assertThat(this.events.poll(wait.toMillis(), TimeUnit.MILLISECONDS)).isNull();
    }

    private void thenNothingIsCompleted() throws Exception {
        assertThat(onLoop(() -> this.download.file().completedBytes())).isZero();
    }

    // The write, the save, the set-aside and the deletes of a cancel run off the engine thread and may outlast the
    // test, then race JUnit's deletion of the directory; closing waits for them.
    private void thenItsFilesAreGoneOnceTheQueueCloses() throws Exception {
        closeTheQueue();
        thenNothingIsLeftInTheTempDirectory();
    }

    private void thenTheLeftoverPartFileIsKept() {
        assertThat(this.dir.resolve("temp").resolve("001" + PartFile.MET_SUFFIX))
                .hasBinaryContent(new byte[] {1, 2, 3});
    }

    private void thenTheDownloadHasNoSources() {
        assertThat(this.download.sources()).isEmpty();
    }

    private void thenTheDownloadIsPreviewedFirst() throws Exception {
        assertThat(onLoop(() -> this.download.file().previewFirst())).isTrue();
    }

    private void thenItIsAccepted() {
        assertThat(this.accepted).isTrue();
    }

    private void thenTheDownloadFailsWith(String reason) throws InterruptedException {
        assertThat(awaitPrefix("failed")).contains(reason);
    }

    private void thenTheDownloadIsInError() throws Exception {
        assertThat(onLoop(() -> this.download.file().status())).isEqualTo(PartFile.Status.ERROR);
    }

    private void thenTheDownloadProgresses() throws InterruptedException {
        assertThat(awaitPrefix("progress")).isEqualTo("progress");
    }

    private void thenTheDownloadDoesNotComplete() throws InterruptedException {
        assertThat(this.events.poll(500, TimeUnit.MILLISECONDS))
                .as("no completion")
                .isNull();
    }

    private void thenTheNextEventIs(String event) throws InterruptedException {
        assertThat(this.events.poll(500, TimeUnit.MILLISECONDS)).isEqualTo(event);
    }

    private void thenThePartIsFoundCorrupt(int part) throws InterruptedException {
        assertThat(awaitPrefix("corrupt")).isEqualTo("corrupt " + part);
    }

    private void thenTheSpaceIsNotShort() throws Exception {
        assertThat(onLoop(this.queue::isSpaceShort)).isFalse();
    }

    private void thenTheNextSpaceEventIs(String event) throws InterruptedException {
        assertThat(awaitPrefix("space")).isEqualTo(event);
    }

    private void thenTheDownloadIsActive() throws Exception {
        assertThat(onLoop(this.download::isActive)).isTrue();
    }

    private void thenItsPartFileIsGone() {
        assertThat(this.met).doesNotExist();
    }

    private void thenItsBackupIsLeftBehind() {
        assertThat(AtomicFiles.bakPath(this.met)).isDirectory();
    }

    // Set aside for an undo, then left where nothing loads it; the next start tries again to delete it.
    private void thenItsBackupIsLeftBehindSetAside() throws Exception {
        Path aside = AtomicFiles.bakPath(this.met)
                .resolveSibling(AtomicFiles.bakPath(this.met).getFileName() + ".cancelled");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline && !Files.isDirectory(aside)) {
            Thread.sleep(20);
        }
        assertThat(aside).isDirectory();
    }

    private DownloadQueue.Download reloaded() {
        return this.queue.get(this.download.hash()).orElseThrow();
    }

    private void closeTheQueue() throws Exception {
        onLoop(() -> {
            this.queue.close();
            return null;
        });
    }

    private void checkTheFreeSpace() throws Exception {
        onLoop(() -> {
            this.queue.checkSpace();
            return null;
        });
    }

    // The sources have the first part.
    private static BitSet firstPart() {
        BitSet parts = new BitSet();
        parts.set(0);
        return parts;
    }

    // One per eD2K part, with the empty hash of the extra part of a size that is a multiple of the part size.
    private static List<Hash16> partHashes(byte[] data) {
        List<Hash16> hashes = new ArrayList<>();
        for (int part = 0; part < Ed2k.ed2kPartCount(data.length); part++) {
            int start = (int) Ed2k.partStart(part);
            hashes.add(Ed2k.md4(data, start, (int) Math.min(Ed2k.PARTSIZE, data.length - start)));
        }
        return hashes;
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
        return onLoop(() -> keeping(
                DownloadQueue.load(this.loop, this.dir.resolve("temp"), incoming, this.minFree::get, listener)));
    }

    // Cancelled downloads go at once here, unless a test keeps them for an undo.
    private DownloadQueue keeping(DownloadQueue queue) {
        queue.keepCancelledFor(this.cancelGrace);
        return queue;
    }

    private void awaitCorruptPart0(DownloadQueue.Download download) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline) {
            this.events.poll(100, TimeUnit.MILLISECONDS);
            Boolean back = onLoop(
                    () -> !download.file().isComplete(0) && download.file().isComplete(1));
            if (back && onLoop(() -> download.file().completedBytes() == 1000)) {
                return;
            }
        }
        throw new AssertionError("the corrupt part was not taken back");
    }

    private DownloadQueue queue() throws Exception {
        return queue(file -> this.dir.resolve("incoming"));
    }

    private DownloadQueue queue(Function<PartFile, Path> incoming) throws Exception {
        return onLoop(() -> keeping(DownloadQueue.load(
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
                })));
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
