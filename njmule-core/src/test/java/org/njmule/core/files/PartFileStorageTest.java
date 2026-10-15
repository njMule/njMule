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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.ClosedChannelException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.core.api.Hash16;
import org.njmule.core.api.Hash20;
import org.njmule.core.files.hash.AichTree;
import org.njmule.core.files.hash.Ed2k;

@Timeout(30)
class PartFileStorageTest {

    private static final long SIZE = 2L * Ed2k.EMBLOCKSIZE + 1000;

    @TempDir
    Path dir;

    private Path data;
    private PartFileStorage storage;
    private Path moved;
    private Hash16 partHash;
    private List<Hash20> blockHashes;
    private Throwable exception;

    @AfterEach
    void closeTheStorage() {
        if (this.storage != null) {
            this.storage.close();
        }
    }

    @Test
    void shouldHashAPartNeverWrittenAsNothing() throws Exception {
        givenAStorageAt("001.part");

        whenAPartIsHashed(SIZE, 0);

        thenThePartHashIs(Ed2k.EMPTY_PART_HASH);
    }

    @Test
    void shouldHashTheBlocksNeverWrittenAsNothing() throws Exception {
        givenAStorageAt("001.part");

        whenTheBlocksOfAPartAreHashed(SIZE, 0);

        thenThereIsAHashOfNoBytesPerBlock(3);
    }

    @Test
    void shouldMoveTheDataAwayAtTheFinish() throws Exception {
        givenAStorageAt("002.part");
        givenTheStorageHolds(new byte[] {1, 2, 3});

        whenTheStorageFinishesAs("done.bin");

        thenTheFinishedFileHolds(new byte[] {1, 2, 3});
        thenTheStorageIsNotOpen();
    }

    @Test
    void shouldHashNoPartAfterTheFinish() throws Exception {
        givenAStorageAt("002.part");
        givenTheStorageHolds(new byte[] {1, 2, 3});
        givenTheStorageFinishedAs("done.bin");

        whenAPartIsHashed(3, 0);

        thenExceptionOccurred(ExecutionException.class);
        thenTheRootCauseIs(ClosedChannelException.class);
    }

    @Test
    void shouldHashNoBlocksAfterTheFinish() throws Exception {
        givenAStorageAt("002.part");
        givenTheStorageHolds(new byte[] {1, 2, 3});
        givenTheStorageFinishedAs("done.bin");

        whenTheBlocksOfAPartAreHashed(3, 0);

        thenTheRootCauseIs(ClosedChannelException.class);
    }

    @Test
    void shouldBeClosedAgainWithoutHarmAfterTheFinish() throws Exception {
        givenAStorageAt("002.part");
        givenTheStorageHolds(new byte[] {1, 2, 3});
        givenTheStorageFinishedAs("done.bin");
        givenTheStorageIsClosed();

        whenTheStorageIsClosed();

        thenNoExceptionOccurred();
        thenTheStorageIsNotOpen();
    }

    @Test
    void shouldGiveTheReservedNameBackWhenAFinishCannotMoveTheData() throws Exception {
        givenAStorageAt("003.part");
        givenTheStorageHolds(new byte[] {4, 5, 6});
        givenItsDataFileIsDeleted();

        whenTheStorageFinishesAs("lost.bin");

        thenExceptionOccurred(ExecutionException.class);
        thenTheCauseIs(UncheckedIOException.class);
        thenTheRootCauseIs(NoSuchFileException.class);
        thenNothingIsLeftInTheIncomingDirectory();
    }

    private void givenAStorageAt(String name) throws IOException {
        this.data = this.dir.resolve(name);
        this.storage = PartFileStorage.open(this.data);
    }

    private void givenTheStorageHolds(byte[] bytes) throws Exception {
        this.storage.write(0, bytes).get(10, TimeUnit.SECONDS);
    }

    private void givenItsDataFileIsDeleted() throws IOException {
        Files.delete(this.data);
    }

    private void givenTheStorageFinishedAs(String name) throws Exception {
        this.moved = this.storage.finish(incoming(), name).get(10, TimeUnit.SECONDS);
    }

    private void givenTheStorageIsClosed() {
        this.storage.close();
    }

    private void whenAPartIsHashed(long size, int part) {
        this.exception = catchThrowable(
                () -> this.partHash = this.storage.hashPart(size, part).get(10, TimeUnit.SECONDS));
    }

    private void whenTheBlocksOfAPartAreHashed(long size, int part) {
        this.exception = catchThrowable(
                () -> this.blockHashes = this.storage.hashBlocks(size, part).get(10, TimeUnit.SECONDS));
    }

    private void whenTheStorageFinishesAs(String name) {
        this.exception = catchThrowable(
                () -> this.moved = this.storage.finish(incoming(), name).get(10, TimeUnit.SECONDS));
    }

    private void whenTheStorageIsClosed() {
        this.exception = catchThrowable(() -> this.storage.close());
    }

    private void thenThePartHashIs(Hash16 expected) {
        assertThat(this.partHash).isEqualTo(expected);
    }

    private void thenThereIsAHashOfNoBytesPerBlock(int blocks) {
        Hash20 empty = Hash20.of(AichTree.sha1().digest());
        assertThat(this.blockHashes)
                .as("a hash per block, of the bytes there are")
                .containsExactlyElementsOf(Collections.nCopies(blocks, empty));
    }

    private void thenTheFinishedFileHolds(byte[] bytes) {
        assertThat(this.moved).hasBinaryContent(bytes);
    }

    private void thenTheStorageIsNotOpen() {
        assertThat(this.storage.isOpen()).isFalse();
    }

    private void thenNothingIsLeftInTheIncomingDirectory() throws IOException {
        try (Stream<Path> left = Files.list(incoming())) {
            assertThat(left).as("the name reserved for it is free again").isEmpty();
        }
    }

    private void thenExceptionOccurred(Class<? extends Throwable> type) {
        assertThat(this.exception).isInstanceOf(type);
    }

    private void thenTheCauseIs(Class<? extends Throwable> type) {
        assertThat(this.exception).hasCauseInstanceOf(type);
    }

    private void thenTheRootCauseIs(Class<? extends Throwable> type) {
        assertThat(this.exception).hasRootCauseInstanceOf(type);
    }

    private void thenNoExceptionOccurred() {
        assertThat(this.exception).isNull();
    }

    private Path incoming() {
        return this.dir.resolve("incoming");
    }
}
