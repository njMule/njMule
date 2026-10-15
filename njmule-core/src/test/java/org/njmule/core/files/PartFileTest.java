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
package org.njmule.core.files;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.google.common.collect.Range;
import com.google.common.collect.RangeSet;
import com.google.common.collect.TreeRangeSet;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.random.RandomGenerator;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.LongRange;
import net.jqwik.api.constraints.Size;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.njmule.core.api.DownloadPriority;
import org.njmule.core.api.Hash16;
import org.njmule.core.api.Hash20;
import org.njmule.core.api.PartMap;
import org.njmule.core.files.PartFile.Block;
import org.njmule.core.files.hash.Ed2k;
import org.njmule.core.persist.format.PartMet;

class PartFileTest {

    private static final long P = Ed2k.PARTSIZE;
    private static final int B = Ed2k.EMBLOCKSIZE;
    private static final long BIG = 5L << 30;
    private static final long HIGH = (9L << 29) + 12_345;
    private static final long SPARSE_SIZE = (5L << 30) + 3;
    private static final int SPARSE_PART = (int) ((9L << 29) / P);
    private static final List<Hash16> EXACT = List.of(hash(1), Ed2k.EMPTY_PART_HASH);
    private static final Hash16 EXACT_HASH = Ed2k.fileHash(EXACT);

    @TempDir
    Path dir;

    private PartFile file;
    private RandomGenerator ties = new FixedTies(Integer.MAX_VALUE);
    private BitSet sourceParts;
    private List<Block> handedOut;
    private List<Block> blocks;
    private RangeSet<Long> missing;
    private PartMap map;
    private BitSet previewParts;
    private Throwable exception;
    private PartFileStorage storage;
    private byte[] data;
    private int dataOffset;
    private Hash16 partHash;
    private List<Hash20> blockHashes;
    private Path moved;
    private final List<Path> finished = new ArrayList<>();

    @AfterEach
    void closeTheStorage() {
        if (this.storage != null) {
            this.storage.close();
        }
    }

    @Test
    void shouldHandOutEachMissingBlockOnceUntilItIsWrittenOrReleased() {
        givenASmallFileOf(500_000);
        givenTheBlocksHandedOutAre(3, new Block(0, B), new Block(B, 2L * B), new Block(2L * B, 500_000));
        givenNoBlockIsLeftWhenAskingFor(3);
        givenTheBytesAreWritten(0, B);
        givenTheHandedOutBlockIsReleased(2);

        whenBlocksAreAskedFor(3);

        thenTheBlocksAre(new Block(2L * B, 500_000));
        thenTheCompletedBytesAre(B);
        thenTheFileHasItsHashSet();
        thenThePartHashIsTheFileHash(0);
    }

    @ParameterizedTest
    @ValueSource(longs = {1, B - 1, B, B + 1, P - 1, P, P + 1, 2 * P - 1, 2 * P, 2 * P + 1})
    void shouldCoverFilesOfEverySizeAroundThePartBoundariesWithBlocks(long size) {
        givenAFileOf(1, "edge.bin", size);

        whenAllBlocksAreDrained(3);

        thenThePartCountRoundsTheSizeUpToWholeParts();
        thenTheBlocksAreCutOnTheAichBoundaries();
        thenTheLastBlockEndsAt(size);
    }

    @Test
    void shouldCutAWholePartIntoItsAichBlocks() {
        givenAFileOf(1, "one.bin", P);

        whenAllBlocksAreDrained(3);

        thenThereAreBlocks(53);
        thenTheLastBlockIsBytesLong(P - 52L * B);
    }

    @Test
    void shouldRefuseAnEmptyFile() {
        whenAFileIsCreated(1, "empty.bin", 0, hash(1), List.of());

        thenExceptionOccurred(IllegalArgumentException.class);
    }

    @Property(tries = 300)
    void shouldHandOutBlocksOnceWithinTheMissingBytesOfOnePart(
            @ForAll @LongRange(min = 1, max = 3 * Ed2k.PARTSIZE + 5) long size,
            @ForAll @Size(max = 8) List<@LongRange(min = 0, max = 3 * Ed2k.PARTSIZE + 5) Long> writes,
            @ForAll @IntRange(min = 1, max = 3) int perCall) {
        givenAFileNeverOnDiskOf(size);
        givenTheWrites(writes);

        whenAllBlocksAreDrained(perCall);

        thenEachBlockIsHandedOutOnceAndTheyCoverTheMissingBytes();
        thenTheBlocksAreCutOnTheAichBoundaries();
    }

    @Property(tries = 300)
    void shouldCompleteTheFileWithTheBlocksHandedOut(
            @ForAll @LongRange(min = 1, max = 3 * Ed2k.PARTSIZE + 5) long size,
            @ForAll @Size(max = 8) List<@LongRange(min = 0, max = 3 * Ed2k.PARTSIZE + 5) Long> writes,
            @ForAll @IntRange(min = 1, max = 3) int perCall) {
        givenAFileNeverOnDiskOf(size);
        givenTheWrites(writes);
        givenAllBlocksAreDrained(perCall);

        whenTheBlocksAreWritten();

        thenTheFileIsComplete();
    }

    @Test
    void shouldHandleOffsetsAboveFourGibibytes() throws Exception {
        givenAFileOf(9, "big.iso", BIG);
        givenTheBytesAreWritten(0, HIGH);
        givenTheBytesAreWritten(HIGH + 1000, BIG - 7);
        givenTheBlocksHandedOutInThePartOfAre(HIGH, 1, new Block(HIGH, HIGH + 1000));
        givenTheBlocksHandedOutInTheLastPartAre(1, new Block(BIG - 7, BIG));
        givenTheFileIsSaved();

        whenTheFileIsLoaded();

        thenTheMissingBytesAre(1007);
        thenTheFileIsCompleteBetween(0, HIGH);
        thenTheFileIsNotCompleteBetween(HIGH, HIGH + 1);
        thenTheMapMisses(new PartMap.Span(HIGH, HIGH + 1000), new PartMap.Span(BIG - 7, BIG));
    }

    @Test
    void shouldHashAPartAboveFourGibibytesOfASparseFile() throws Exception {
        givenAStorage("big.part");
        givenRandomBytesWrittenIntoThePart(SPARSE_PART, 1_000_000, 50_000);
        givenTheLastByteOfAFileOfIsWritten(SPARSE_SIZE);

        whenThePartIsHashedWithin(SPARSE_SIZE, SPARSE_PART, Duration.ofSeconds(30));

        thenThePartHashIsThatOfTheWrittenBytesAmongZeros();
    }

    @Test
    void shouldHashTheBlocksOfAPartAboveFourGibibytesOfASparseFile() throws Exception {
        givenAStorage("big.part");
        givenRandomBytesWrittenIntoThePart(SPARSE_PART, 1_000_000, 50_000);
        givenTheLastByteOfAFileOfIsWritten(SPARSE_SIZE);

        whenTheBlocksOfThePartAreHashedWithin(SPARSE_SIZE, SPARSE_PART, Duration.ofSeconds(30));

        thenThereAreBlockHashes(53);
    }

    @Test
    void shouldKeepASparseFileAboveFourGibibytesAsLongAsItsLastByte() throws Exception {
        givenAStorage("big.part");
        givenRandomBytesWrittenIntoThePart(SPARSE_PART, 1_000_000, 50_000);
        givenTheLastByteOfAFileOfIsWritten(SPARSE_SIZE);

        whenTheStorageIsClosed();

        thenTheStorageFileIsBytesLong("big.part", SPARSE_SIZE);
    }

    @Test
    void shouldHandOutAReleasedRangeAgain() {
        givenASmallFileOf(500_000);
        givenBlocksAreHandedOut(3);
        givenTheRangeIsReleased(B + 1000, B + 2000);

        whenAllBlocksAreDrained(3);

        thenTheBlocksAre(new Block(B + 1000, B + 2000));
    }

    @Test
    void shouldPickTheRarestPartTheSourceHas() {
        givenAFileWithTheHashSet(1, "big.bin", 2 * P + 100, hash(1), hash(2), hash(3));
        givenTheSourcesOfThePartsChangeBy(1, 0, 1, 2);
        givenTheSourcesOfThePartsChangeBy(1, 0, 2);
        givenASourceWithTheParts(0, 1, 2);

        whenBlocksAreAskedFor(1);

        thenTheBlocksAre(new Block(P, P + B));
    }

    @Test
    void shouldStartWithAnyOfTheEquallyRareParts() {
        givenAFileWithTheHashSet(1, "big.bin", 2 * P + 100, hash(1), hash(2), hash(3));
        givenTiesGoToTheLastEqualPartSeen();
        givenASourceWithTheParts(0, 1, 2);

        whenBlocksAreAskedFor(1);

        thenTheFirstBlockStartsAt(2 * P, "the last of the equally rare parts, by the chance given");
    }

    @Test
    void shouldTakeBackACorruptPart() {
        givenAFileWithTheHashSet(1, "big.bin", 2 * P + 100, hash(1), hash(2), hash(3));
        givenTheBytesAreWritten(0, P);
        givenThePartIsComplete(0);

        whenThePartIsFoundCorrupt(0);

        thenThePartIsNotComplete(0);
        thenNoPartIsComplete();
    }

    @Test
    void shouldFetchTheRarestPartFirst() {
        givenAFileWithTheHashSet(3, "film.mkv", 3 * P + 100, hash(1), hash(2), hash(3), hash(4));
        givenTheSourcesOfThePartsChangeBy(5, 0, 1, 2, 3);
        givenTheSourcesOfThePartsChangeBy(-4, 1);
        givenASourceWithTheParts(0, 1, 2, 3);

        whenBlocksAreAskedFor(1);

        thenTheFirstBlockStartsAt(P, "the rarest part");
    }

    @Test
    void shouldFetchTheFirstPartBeforeRarerOnesForAPreview() {
        givenAFileWithTheHashSet(3, "film.mkv", 3 * P + 100, hash(1), hash(2), hash(3), hash(4));
        givenTheSourcesOfThePartsChangeBy(5, 0, 1, 2, 3);
        givenTheSourcesOfThePartsChangeBy(-4, 1);
        givenThePreviewComesFirst();
        givenASourceWithTheParts(0, 2, 3);

        whenBlocksAreAskedFor(1);

        thenTheFirstBlockStartsAt(0, "the first part, before rarer ones");
    }

    @Test
    void shouldFetchTheLastPartsBeforeRarerOnesForAPreview() {
        givenAFileWithTheHashSet(3, "film.mkv", 3 * P + 100, hash(1), hash(2), hash(3), hash(4));
        givenTheSourcesOfThePartsChangeBy(5, 0, 1, 2, 3);
        givenTheSourcesOfThePartsChangeBy(-4, 1);
        givenThePreviewComesFirst();
        givenASourceWithTheParts(2, 3);

        whenBlocksAreAskedFor(1);

        thenTheFirstBlockStartsAt(2 * P, "the last part, and the one before as the last is short");
    }

    @Test
    void shouldRememberThatThePreviewComesFirst() throws Exception {
        givenAFileWithTheHashSet(3, "film.mkv", 3 * P + 100, hash(1), hash(2), hash(3), hash(4));
        givenThePreviewComesFirst();
        givenTheFileIsSaved();

        whenTheFileIsLoaded();

        thenThePreviewComesFirst();
    }

    @Test
    void shouldPreviewNoFileThatNoPlayerShows() {
        givenAFileWithTheHashSet(4, "notes.txt", 3 * P + 100, hash(1), hash(2), hash(3), hash(4));
        givenThePreviewComesFirst();

        whenThePartsForAPreviewAreChosen();

        thenNoPartIsForAPreview();
    }

    @Test
    void shouldFinishABegunPartFirstAndCutBlocksOnTheAichBoundaries() {
        givenAFileWithTheHashSet(2, "big.bin", 2 * P + 100, hash(1), hash(2), hash(3));
        givenTheSourcesOfThePartsChangeBy(1, 0, 1, 2);
        givenTheBytesAreWritten(P, P + 1000);
        givenASourceWithTheParts(0, 1, 2);

        whenBlocksAreAskedFor(2);

        thenTheBlocksOfTheBegunPartAre(new Block(P + 1000, P + B), new Block(P + B, P + 2L * B));
    }

    @Test
    void shouldMapWhatIsMissingRequestedAndAvailable() {
        givenASmallFileOf(500_000);
        givenTheBytesAreWritten(0, 100_000);
        givenBlocksAreHandedOut(1);
        givenTheSourcesOfThePartsChangeBy(2, 0);

        whenTheFileIsMapped();

        thenTheMapSizeIs(500_000);
        thenTheMapMisses(new PartMap.Span(100_000, 500_000));
        thenTheMapRequestsTheBlockHandedOut();
        thenTheMapCountsTheSources(2);
    }

    @Test
    void shouldHaveNoHashSetFromTheFileHashAlone() {
        whenAFileIsCreated(1, "f", P + 1, Ed2k.fileHash(List.of(hash(1), hash(2))), List.of());

        thenTheFileHasNoHashSet();
        thenThereIsNoHashOfPart(1);
    }

    @Test
    void shouldRefuseAHashSetThatDoesNotMatchTheFileHash() {
        givenAFileWithoutItsHashSet(1, "f", P + 1, hash(1), hash(2));

        whenTheHashSetIsSet(hash(2), hash(1));

        thenExceptionOccurred(IllegalArgumentException.class);
    }

    @Test
    void shouldAcceptAHashSetThatMatchesTheFileHash() {
        givenAFileWithoutItsHashSet(1, "f", P + 1, hash(1), hash(2));

        whenTheHashSetIsSet(hash(1), hash(2));

        thenThePartHashIs(1, hash(2));
    }

    @Test
    void shouldAskTheSourcesForTheHashSetWhenALinkLeavesOutTheEmptyPartOfAnExactMultiple() {
        whenAFileIsCreated(1, "a", P, EXACT_HASH, List.of(hash(1)));

        thenTheFileHasNoHashSet("an exact multiple also hashes the empty part after it");
    }

    @Test
    void shouldAskTheSourcesForTheHashSetWhenTheOneOfALinkDoesNotGiveTheFileHash() {
        whenAFileIsCreated(2, "b", P + 1, Ed2k.fileHash(List.of(hash(1), hash(2))), List.of(hash(2), hash(1)));

        thenTheFileHasNoHashSet();
        thenThereAreNoPartHashes();
    }

    @Test
    void shouldNeedOnlyTheFileHashForOnePart() {
        whenAFileIsCreated(3, "c", 1000, hash(3), List.of(hash(3)));

        thenTheFileHasItsHashSet();
        thenThereAreNoPartHashes("one part needs only the file hash");
    }

    @Test
    void shouldTakeTheHashSetOfALinkThatProvesTheFileHash() {
        whenAFileIsCreated(4, "d", P, EXACT_HASH, EXACT);

        thenThePartHashesAre(EXACT);
        thenThePartCountIs(1);
        thenThePartHashIs(0, hash(1));
    }

    @Test
    void shouldDropASavedHashSetThatDoesNotGiveTheFileHash() throws Exception {
        givenAFileWithTheHashSet(5, "e", P + 1, hash(1), hash(2));
        givenTheFileIsSaved();
        givenTheSavedHashSetIs(hash(2), hash(1));

        whenTheFileIsLoaded();

        thenTheFileHasNoHashSet("asked from the sources again");
        thenThereIsNoHashOfPart(0);
    }

    @Test
    void shouldSaveAndLoadItsProgress() throws Exception {
        givenAFileWithTheHashSet(7, "film.mkv", 2 * P + 5, hash(1), hash(2), hash(3));
        givenTheBytesAreWritten(0, P);
        givenTheBytesAreWritten(P + 10, P + 20);
        givenTheStatusIs(PartFile.Status.PAUSED);
        givenTheFileIsSaved();

        whenTheFileIsLoaded();

        thenTheNameIs("film.mkv");
        thenTheSizeIs(2 * P + 5);
        thenTheCompletedBytesAre(P + 10);
        thenThePartIsComplete(0);
        thenTheStatusIs(PartFile.Status.PAUSED);
        thenThePartHashIs(2, hash(3));
        thenTheMetFileIsNamed("007.part.met");
    }

    @Test
    void shouldTrustThePartsCheckedBeforeOnlyWhileTheDataIsUnchanged() throws Exception {
        givenAFileWithTheHashSet(8, "film.mkv", 2 * P + 5, hash(1), hash(2), hash(3));
        givenTheDataFileHolds(new byte[] {1, 2, 3});
        givenTheBytesAreWritten(0, P);
        givenTheBytesAreWritten(P, 2 * P);
        givenThePartIsVerified(0);
        givenTheFileIsSaved();
        givenTheFileAsLoadedTrustsOnlyThePart(0);
        givenTheDataFileChangesSecondsAfterTheSave(5);

        whenTheFileIsLoaded();

        thenNoPartIsTrustedAsTheDataChangedSinceTheSave();
    }

    @Test
    void shouldFollowTheRarityWhenNew() {
        whenAFileIsCreated(3, "a.bin", 5000, hash(1), List.of());

        thenThePriorityIs(DownloadPriority.AUTO, "new downloads follow their rarity");
    }

    @ParameterizedTest
    @EnumSource(DownloadPriority.class)
    void shouldKeepItsPriority(DownloadPriority priority) throws Exception {
        givenAFileOf(3, "a.bin", 5000);
        givenThePriorityIs(priority);
        givenTheFileIsSaved();

        whenTheFileIsLoaded();

        thenThePriorityIs(priority);
    }

    @Test
    void shouldKeepWhetherItIsStoppedAndItsCategory() throws Exception {
        givenAFileOf(3, "a.bin", 5000);
        givenThePriorityIs(DownloadPriority.HIGH);
        givenTheStatusIs(PartFile.Status.STOPPED);
        givenTheCategoryIs(4);
        givenTheFileIsSaved();

        whenTheFileIsLoaded();

        thenTheCategoryIs(4);
        thenTheStatusIs(PartFile.Status.STOPPED);
        thenTheSavedFileShowsOtherClientsAPausedFile();
        thenTheSavedFileGivesOtherClientsTheDownloadPriority(2);
    }

    @Test
    void shouldHashAPartWrittenOutOfOrderFromDisk() throws Exception {
        givenAStorage("001.part");
        givenRandomBytesWrittenFromTheMiddleThenFromTheStart(300_000, 100_000);

        whenThePartIsHashedWithin(300_000, 0, Duration.ofSeconds(5));

        thenThePartHashIsThatOfTheWrittenBytes();
    }

    @Test
    void shouldMoveTheFinishedFile() throws Exception {
        givenAStorage("001.part");
        givenRandomBytesWrittenFromTheMiddleThenFromTheStart(300_000, 100_000);

        whenTheStorageFinishesAs("done.bin");

        thenTheFinishedFileIs("incoming", "done.bin");
        thenTheFinishedFileHoldsTheWrittenBytes();
        thenTheStorageFileIsGone("001.part");
    }

    @Test
    void shouldNeverReplaceAFileWithTheNameOfAFinishedOne() throws Exception {
        givenTheIncomingFileHolds("same.bin", "already here");
        givenADownloadFinishedAs("000.part", new byte[] {0, 1, 2}, "same.bin");

        whenADownloadFinishesAs("001.part", new byte[] {1, 1, 2}, "same.bin");

        thenTheFinishedFilesAreNamed("same (2).bin", "same (3).bin");
        thenTheIncomingFileStillHolds("same.bin", "already here");
        thenTheLastFinishedFileHolds(1, 1, 2);
    }

    private void givenASmallFileOf(long size) {
        this.file = small(size);
        this.sourceParts = all(this.file);
    }

    private void givenAFileOf(int number, String name, long size) {
        this.file = PartFile.create(number, this.dir, name, size, hash(1), Optional.empty(), List.of());
        this.sourceParts = all(this.file);
    }

    private void givenAFileWithTheHashSet(int number, String name, long size, Hash16... parts) {
        List<Hash16> hashSet = List.of(parts);
        this.file = PartFile.create(number, this.dir, name, size, Ed2k.fileHash(hashSet), Optional.empty(), hashSet);
        this.sourceParts = all(this.file);
    }

    private void givenAFileWithoutItsHashSet(int number, String name, long size, Hash16... parts) {
        this.file = PartFile.create(
                number, this.dir, name, size, Ed2k.fileHash(List.of(parts)), Optional.empty(), List.of());
    }

    // No file is written: jqwik gives no temporary directory.
    private void givenAFileNeverOnDiskOf(long size) {
        this.file = PartFile.create(1, Path.of("unused"), "any.bin", size, hash(1), Optional.empty(), List.of());
        this.sourceParts = all(this.file);
        this.missing = TreeRangeSet.create();
        this.missing.add(Range.closedOpen(0L, size));
    }

    private void givenTheWrites(List<Long> writes) {
        long size = this.file.size();
        for (long at : writes) {
            long start = Math.min(at, size - 1);
            long end = Math.min(size, start + 1 + at % (2L * B));
            this.file.written(start, end);
            this.missing.remove(Range.closedOpen(start, end));
        }
        long expected = this.missing.asRanges().stream()
                .mapToLong(r -> r.upperEndpoint() - r.lowerEndpoint())
                .sum();
        assertThat(this.file.missingBytes()).isEqualTo(expected);
    }

    private void givenAllBlocksAreDrained(int perCall) {
        this.blocks = drain(this.file, this.sourceParts, perCall);
    }

    private void givenTheBlocksHandedOutAre(int count, Block... expected) {
        this.handedOut = nextBlocks(this.file, this.sourceParts, count);
        assertThat(this.handedOut).containsExactly(expected);
    }

    private void givenBlocksAreHandedOut(int count) {
        this.handedOut = nextBlocks(this.file, this.sourceParts, count);
        assertThat(this.handedOut).hasSize(count);
    }

    private void givenNoBlockIsLeftWhenAskingFor(int count) {
        assertThat(nextBlocks(this.file, this.sourceParts, count)).isEmpty();
    }

    private void givenTheBlocksHandedOutInThePartOfAre(long offset, int count, Block... expected) {
        BitSet part = new BitSet();
        part.set((int) (offset / P));
        assertThat(nextBlocks(this.file, part, count)).containsExactly(expected);
    }

    private void givenTheBlocksHandedOutInTheLastPartAre(int count, Block... expected) {
        BitSet last = new BitSet();
        last.set(Ed2k.partCount(this.file.size()) - 1);
        assertThat(nextBlocks(this.file, last, count)).containsExactly(expected);
    }

    private void givenTheBytesAreWritten(long start, long end) {
        this.file.written(start, end);
    }

    private void givenTheHandedOutBlockIsReleased(int index) {
        this.file.release(this.handedOut.get(index));
    }

    private void givenTheRangeIsReleased(long start, long end) {
        this.file.release(start, end);
    }

    private void givenTheSourcesOfThePartsChangeBy(int delta, int... parts) {
        this.file.addAvailability(bits(parts), delta);
    }

    private void givenTiesGoToTheLastEqualPartSeen() {
        this.ties = new FixedTies(0);
    }

    private void givenASourceWithTheParts(int... parts) {
        this.sourceParts = bits(parts);
    }

    private void givenThePartIsComplete(int part) {
        assertThat(this.file.isComplete(part)).isTrue();
    }

    private void givenThePreviewComesFirst() {
        this.file.setPreviewFirst(true);
    }

    private void givenTheFileIsSaved() throws IOException {
        this.file.save();
    }

    private void givenTheSavedHashSetIs(Hash16... parts) throws IOException {
        PartMet.Contents saved = PartMet.read(Files.readAllBytes(this.file.metFile()));
        Files.write(
                this.file.metFile(),
                PartMet.write(new PartMet.Contents(
                        saved.version(), saved.mtime(), saved.fileHash(), List.of(parts), saved.tags())));
    }

    private void givenTheStatusIs(PartFile.Status status) {
        this.file.setStatus(status);
    }

    private void givenThePriorityIs(DownloadPriority priority) {
        this.file.setPriority(priority);
    }

    private void givenTheCategoryIs(int category) {
        this.file.setCategory(category);
    }

    private void givenTheDataFileHolds(byte[] bytes) throws IOException {
        Files.write(this.file.dataFile(), bytes);
    }

    private void givenThePartIsVerified(int part) {
        this.file.verified(part);
    }

    private void givenTheFileAsLoadedTrustsOnlyThePart(int part) throws IOException {
        assertThat(PartFile.load(this.dir, this.file.number()).trustedParts().stream()
                        .boxed()
                        .toList())
                .containsExactly(part);
    }

    private void givenTheDataFileChangesSecondsAfterTheSave(int seconds) throws IOException {
        Files.setLastModifiedTime(
                this.file.dataFile(),
                FileTime.fromMillis(
                        Files.getLastModifiedTime(this.file.dataFile()).toMillis() + seconds * 1000L));
    }

    private void givenAStorage(String name) throws IOException {
        this.storage = PartFileStorage.open(this.dir.resolve(name));
    }

    private void givenRandomBytesWrittenIntoThePart(int part, int offset, int length) throws Exception {
        this.data = new byte[length];
        new Random(2).nextBytes(this.data);
        this.dataOffset = offset;
        this.storage.write(Ed2k.partStart(part) + offset, this.data).get(10, TimeUnit.SECONDS);
    }

    private void givenTheLastByteOfAFileOfIsWritten(long size) throws Exception {
        this.storage.write(size - 1, new byte[] {1}).get(10, TimeUnit.SECONDS);
    }

    private void givenRandomBytesWrittenFromTheMiddleThenFromTheStart(int length, int middle) throws Exception {
        this.data = new byte[length];
        new Random(1).nextBytes(this.data);
        this.storage
                .write(middle, Arrays.copyOfRange(this.data, middle, this.data.length))
                .get(5, TimeUnit.SECONDS);
        this.storage.write(0, Arrays.copyOfRange(this.data, 0, middle)).get(5, TimeUnit.SECONDS);
    }

    private void givenTheIncomingFileHolds(String name, String text) throws IOException {
        Files.writeString(Files.createDirectories(incoming()).resolve(name), text);
    }

    private void givenADownloadFinishedAs(String part, byte[] bytes, String name) throws Exception {
        finish(part, bytes, name);
    }

    private void whenBlocksAreAskedFor(int count) {
        this.blocks = nextBlocks(this.file, this.sourceParts, count);
    }

    private void whenAllBlocksAreDrained(int perCall) {
        this.blocks = drain(this.file, this.sourceParts, perCall);
    }

    private void whenTheBlocksAreWritten() {
        for (Block block : this.blocks) {
            this.file.written(block.start(), block.end());
        }
    }

    private void whenAFileIsCreated(int number, String name, long size, Hash16 fileHash, List<Hash16> hashSet) {
        this.exception = catchThrowable(
                () -> this.file = PartFile.create(number, this.dir, name, size, fileHash, Optional.empty(), hashSet));
    }

    private void whenTheHashSetIsSet(Hash16... parts) {
        this.exception = catchThrowable(() -> this.file.setHashSet(List.of(parts)));
    }

    private void whenThePartIsFoundCorrupt(int part) {
        this.file.corrupt(part);
    }

    private void whenThePartsForAPreviewAreChosen() {
        this.previewParts = this.file.previewParts(false);
    }

    private void whenTheFileIsMapped() {
        this.map = this.file.map();
    }

    private void whenTheFileIsLoaded() throws IOException {
        this.file = PartFile.load(this.dir, this.file.number());
        this.map = this.file.map();
    }

    private void whenThePartIsHashedWithin(long size, int part, Duration timeout) throws Exception {
        this.partHash = this.storage.hashPart(size, part).get(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    private void whenTheBlocksOfThePartAreHashedWithin(long size, int part, Duration timeout) throws Exception {
        this.blockHashes = this.storage.hashBlocks(size, part).get(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    private void whenTheStorageIsClosed() {
        this.storage.close();
    }

    private void whenTheStorageFinishesAs(String name) throws Exception {
        this.moved = this.storage.finish(incoming(), name).get(5, TimeUnit.SECONDS);
    }

    private void whenADownloadFinishesAs(String part, byte[] bytes, String name) throws Exception {
        finish(part, bytes, name);
    }

    private void thenTheBlocksAre(Block... expected) {
        assertThat(this.blocks).containsExactly(expected);
    }

    private void thenTheBlocksOfTheBegunPartAre(Block... expected) {
        assertThat(this.blocks).as("part 1 was begun").containsExactly(expected);
    }

    private void thenTheFirstBlockStartsAt(long offset, String description) {
        assertThat(this.blocks.getFirst().start()).as(description).isEqualTo(offset);
    }

    private void thenThereAreBlocks(int count) {
        assertThat(this.blocks).hasSize(count);
    }

    private void thenTheLastBlockEndsAt(long end) {
        assertThat(this.blocks.getLast().end()).isEqualTo(end);
    }

    private void thenTheLastBlockIsBytesLong(long length) {
        assertThat(this.blocks.getLast().length()).isEqualTo(length);
    }

    private void thenTheBlocksAreCutOnTheAichBoundaries() {
        assertCutAsAich(this.blocks, this.file.size());
    }

    private void thenEachBlockIsHandedOutOnceAndTheyCoverTheMissingBytes() {
        RangeSet<Long> handed = TreeRangeSet.create();
        for (Block block : this.blocks) {
            Range<Long> range = Range.closedOpen(block.start(), block.end());
            assertThat(this.missing.encloses(range)).as("%s is missing", block).isTrue();
            assertThat(handed.intersects(range)).as("%s handed out once", block).isFalse();
            handed.add(range);
        }
        assertThat(handed).isEqualTo(this.missing);
    }

    private void thenThePartCountRoundsTheSizeUpToWholeParts() {
        long size = this.file.size();
        assertThat(this.file.partCount()).as("%d bytes", size).isEqualTo((int) ((size + P - 1) / P));
    }

    private void thenThePartCountIs(int count) {
        assertThat(this.file.partCount()).isEqualTo(count);
    }

    private void thenTheFileIsComplete() {
        assertThat(this.file.isComplete()).isTrue();
    }

    private void thenTheCompletedBytesAre(long bytes) {
        assertThat(this.file.completedBytes()).isEqualTo(bytes);
    }

    private void thenTheMissingBytesAre(long bytes) {
        assertThat(this.file.missingBytes()).isEqualTo(bytes);
    }

    private void thenTheFileIsCompleteBetween(long start, long end) {
        assertThat(this.file.isComplete(start, end)).isTrue();
    }

    private void thenTheFileIsNotCompleteBetween(long start, long end) {
        assertThat(this.file.isComplete(start, end)).isFalse();
    }

    private void thenThePartIsComplete(int part) {
        assertThat(this.file.isComplete(part)).isTrue();
    }

    private void thenThePartIsNotComplete(int part) {
        assertThat(this.file.isComplete(part)).isFalse();
    }

    private void thenNoPartIsComplete() {
        assertThat(this.file.completeParts().isEmpty()).isTrue();
    }

    private void thenTheFileHasItsHashSet() {
        assertThat(this.file.hasHashSet()).isTrue();
    }

    private void thenTheFileHasNoHashSet() {
        assertThat(this.file.hasHashSet()).isFalse();
    }

    private void thenTheFileHasNoHashSet(String description) {
        assertThat(this.file.hasHashSet()).as(description).isFalse();
    }

    private void thenThePartHashIsTheFileHash(int part) {
        assertThat(this.file.partHash(part)).contains(this.file.hash());
    }

    private void thenThePartHashIs(int part, Hash16 expected) {
        assertThat(this.file.partHash(part)).contains(expected);
    }

    private void thenThereIsNoHashOfPart(int part) {
        assertThat(this.file.partHash(part)).isEmpty();
    }

    private void thenThePartHashesAre(List<Hash16> expected) {
        assertThat(this.file.partHashes()).isEqualTo(expected);
    }

    private void thenThereAreNoPartHashes() {
        assertThat(this.file.partHashes()).isEmpty();
    }

    private void thenThereAreNoPartHashes(String description) {
        assertThat(this.file.partHashes()).as(description).isEmpty();
    }

    private void thenThePreviewComesFirst() {
        assertThat(this.file.previewFirst()).isTrue();
    }

    private void thenNoPartIsForAPreview() {
        assertThat(this.previewParts.isEmpty()).as("no player shows it").isTrue();
    }

    private void thenTheMapSizeIs(long size) {
        assertThat(this.map.size()).isEqualTo(size);
    }

    private void thenTheMapMisses(PartMap.Span... spans) {
        assertThat(this.map.missing()).containsExactly(spans);
    }

    private void thenTheMapRequestsTheBlockHandedOut() {
        assertThat(this.map.requested())
                .containsExactly(new PartMap.Span(
                        this.handedOut.getFirst().start(),
                        this.handedOut.getFirst().end()));
    }

    private void thenTheMapCountsTheSources(int sources) {
        assertThat(this.map.sources()).containsExactly(sources);
    }

    private void thenTheNameIs(String name) {
        assertThat(this.file.name()).isEqualTo(name);
    }

    private void thenTheSizeIs(long size) {
        assertThat(this.file.size()).isEqualTo(size);
    }

    private void thenTheStatusIs(PartFile.Status status) {
        assertThat(this.file.status()).isEqualTo(status);
    }

    private void thenTheMetFileIsNamed(String name) {
        assertThat(this.file.metFile().getFileName().toString()).isEqualTo(name);
    }

    private void thenNoPartIsTrustedAsTheDataChangedSinceTheSave() {
        assertThat(this.file.trustedParts().isEmpty())
                .as("the data changed since the save")
                .isTrue();
    }

    private void thenThePriorityIs(DownloadPriority priority) {
        assertThat(this.file.priority()).isEqualTo(priority);
    }

    private void thenThePriorityIs(DownloadPriority priority, String description) {
        assertThat(this.file.priority()).as(description).isEqualTo(priority);
    }

    private void thenTheCategoryIs(int category) {
        assertThat(this.file.category()).isEqualTo(category);
    }

    private void thenTheSavedFileShowsOtherClientsAPausedFile() throws IOException {
        PartMet.Contents met = PartMet.read(Files.readAllBytes(this.file.metFile()));
        assertThat(met.paused()).as("other clients see a paused file").isTrue();
    }

    private void thenTheSavedFileGivesOtherClientsTheDownloadPriority(int priority) throws IOException {
        PartMet.Contents met = PartMet.read(Files.readAllBytes(this.file.metFile()));
        assertThat(met.downloadPriority()).as("the value other clients write").hasValue(priority);
    }

    private void thenThePartHashIsThatOfTheWrittenBytesAmongZeros() {
        byte[] expected = new byte[(int) P];
        System.arraycopy(this.data, 0, expected, this.dataOffset, this.data.length);
        assertThat(this.partHash).isEqualTo(Ed2k.md4(expected, 0, expected.length));
    }

    private void thenThePartHashIsThatOfTheWrittenBytes() {
        assertThat(this.partHash).isEqualTo(Ed2k.md4(this.data, 0, this.data.length));
    }

    private void thenThereAreBlockHashes(int count) {
        assertThat(this.blockHashes).hasSize(count);
    }

    private void thenTheStorageFileIsBytesLong(String name, long size) throws IOException {
        assertThat(Files.size(this.dir.resolve(name))).isEqualTo(size);
    }

    private void thenTheFinishedFileIs(String directory, String name) {
        assertThat(this.moved).isEqualTo(this.dir.resolve(directory).resolve(name));
    }

    private void thenTheFinishedFileHoldsTheWrittenBytes() throws IOException {
        assertThat(Files.readAllBytes(this.moved)).isEqualTo(this.data);
    }

    private void thenTheStorageFileIsGone(String name) {
        assertThat(this.dir.resolve(name)).doesNotExist();
    }

    private void thenTheFinishedFilesAreNamed(String... names) {
        assertThat(this.finished).extracting(p -> p.getFileName().toString()).containsExactly(names);
    }

    private void thenTheIncomingFileStillHolds(String name, String text) throws IOException {
        assertThat(Files.readString(incoming().resolve(name))).isEqualTo(text);
    }

    private void thenTheLastFinishedFileHolds(int... bytes) throws IOException {
        assertThat(Files.readAllBytes(this.finished.getLast())).containsExactly(bytes);
    }

    private void thenExceptionOccurred(Class<? extends Throwable> type) {
        assertThat(this.exception).isInstanceOf(type);
    }

    private void finish(String part, byte[] bytes, String name) throws Exception {
        try (PartFileStorage finishing = PartFileStorage.open(this.dir.resolve(part))) {
            finishing.write(0, bytes).get(5, TimeUnit.SECONDS);
            this.finished.add(finishing.finish(incoming(), name).get(5, TimeUnit.SECONDS));
        }
    }

    private Path incoming() {
        return this.dir.resolve("incoming");
    }

    // The expectations here follow the parts in order: ties go to the lowest, unless a test says otherwise.
    private List<Block> nextBlocks(PartFile file, BitSet parts, int count) {
        file.breakTiesWith(this.ties);
        return file.nextBlocks(parts, count);
    }

    private PartFile small(long size) {
        return PartFile.create(1, this.dir, "small.bin", size, hash(9), Optional.empty(), List.of());
    }

    // Draws blocks until none is left, as sources asking in turn would.
    private List<Block> drain(PartFile file, BitSet parts, int perCall) {
        List<Block> all = new ArrayList<>();
        for (List<Block> next = nextBlocks(file, parts, perCall);
                !next.isEmpty();
                next = nextBlocks(file, parts, perCall)) {
            assertThat(next.size()).isLessThanOrEqualTo(perCall);
            all.addAll(next);
        }
        return all;
    }

    // At most one block long, within one part and within one 180 KiB AICH block of it.
    private static void assertCutAsAich(List<Block> blocks, long size) {
        for (Block block : blocks) {
            int part = (int) (block.start() / P);
            long inPart = block.start() - Ed2k.partStart(part);
            assertThat(block.end()).as("%s", block).isLessThanOrEqualTo(Math.min(size, Ed2k.partStart(part) + P));
            assertThat(Ed2k.partStart(part) + (inPart / B + 1) * B)
                    .as("%s within its AICH block", block)
                    .isGreaterThanOrEqualTo(block.end());
        }
    }

    private static BitSet all(PartFile file) {
        BitSet parts = new BitSet();
        parts.set(0, file.partCount());
        return parts;
    }

    private static BitSet bits(int... indexes) {
        BitSet bits = new BitSet();
        for (int index : indexes) {
            bits.set(index);
        }
        return bits;
    }

    private static Hash16 hash(int seed) {
        byte[] bytes = new byte[16];
        bytes[0] = (byte) seed;
        return Hash16.of(bytes);
    }

    // Ties broken as told: 0 takes each equal part seen, anything else keeps the first one.
    private record FixedTies(int draw) implements RandomGenerator {
        @Override
        public long nextLong() {
            return this.draw;
        }

        @Override
        public int nextInt(int bound) {
            return Math.min(this.draw, bound - 1);
        }
    }
}
