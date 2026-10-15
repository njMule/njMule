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
package org.njmule.core.files.hash;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.njmule.core.api.Hash16;
import org.njmule.core.api.Hash20;

class FileHasherTest {

    private static final long P = Ed2k.PARTSIZE;

    private long size;
    private FileHasher.Result result;
    private Throwable exception;

    @Test
    void shouldGiveSmallFilesNoHashSet() throws IOException {
        whenAFileOfZerosIsHashed(3);

        thenTheFileHashIsTheMd4OfZeros(3);
        thenThereAreNoPartHashes();
        thenTheHashOfTheOnlyPartIsTheFileHash();
    }

    @Test
    void shouldEndAFileOfOnePartsizeWithTheEmptyPartHash() throws IOException {
        whenAFileOfZerosIsHashed(P);

        thenThePartHashesAreZeroPartsThenTheEmptyPartHash(1);
        thenTheFileHashIsTheMd4OfThePartHashes();
        thenTheEmptyPartHashIs("31D6CFE0D16AE931B73C59D7E0C089C0");
    }

    @Test
    void shouldEndAFileOfTwoPartsizesWithTheEmptyPartHash() throws IOException {
        whenAFileOfZerosIsHashed(2 * P);

        thenThePartHashesAreZeroPartsThenTheEmptyPartHash(2);
    }

    @Test
    void shouldHashAPartialLastPartAsItIs() throws IOException {
        whenAFileOfZerosIsHashed(P + 1);

        thenThePartHashesAreTheMd4OfZerosOf((int) P, 1);
        thenTheHashSetIsConsistent();
        thenTheHashOfPartIsTheMd4OfZeros(1, 1);
    }

    @Test
    void shouldHashAnEmptyFileToTheEmptyMd4WithoutAich() throws IOException {
        whenAFileOfZerosIsHashed(0);

        thenTheFileHashIsTheEmptyPartHash();
        thenThereIsNoAichTree();
    }

    @Test
    void shouldMakeTheAichOfASingleBlockTheSha1OfTheData() throws IOException, NoSuchAlgorithmException {
        whenAFileOfZerosIsHashed(1000);

        thenTheAichMasterHashIsTheSha1OfZeros(1000);
        thenTheAichTreeHasPartHashes(0);
        thenTheAichTreeHasBlockHashes(1);
    }

    @Test
    void shouldRestartTheAichLeavesAtEveryPart() throws IOException {
        whenAFileOfZerosIsHashed(2 * P + 5);

        thenTheAichTreeHasBlockHashes(53 + 53 + 1);
        thenTheAichBlockCountIs(107);
        thenTheAichTreeHasPartHashes(3);
        thenTheAichTreeRebuiltFromItsBlocksHasTheSameMasterHash();
    }

    @Test
    void shouldRefuseDataShorterThanAnnounced() {
        whenZerosAreHashedAs(10, 11);

        thenExceptionOccurred(EOFException.class);
    }

    @Test
    void shouldRefuseAWrongNumberOfAichLeaves() {
        whenAnAichTreeIsBuiltWithoutBlockHashes(P + 1);

        thenExceptionOccurred(IllegalArgumentException.class);
    }

    private void whenAFileOfZerosIsHashed(long size) throws IOException {
        this.size = size;
        this.result = zeros(size);
    }

    private void whenZerosAreHashedAs(long size, long announced) {
        this.exception = catchThrowable(() -> FileHasher.hash(new Zeros(size), announced, b -> {}));
    }

    private void whenAnAichTreeIsBuiltWithoutBlockHashes(long size) {
        this.exception = catchThrowable(() -> AichTree.fromBlockHashes(size, List.of()));
    }

    private void thenTheFileHashIsTheMd4OfZeros(int length) {
        assertThat(this.result.ed2k().fileHash()).isEqualTo(md4(new byte[length]));
    }

    private void thenTheFileHashIsTheEmptyPartHash() {
        assertThat(this.result.ed2k().fileHash()).isEqualTo(Ed2k.EMPTY_PART_HASH);
    }

    private void thenTheFileHashIsTheMd4OfThePartHashes() {
        Ed2kHashSet set = this.result.ed2k();
        assertThat(set.fileHash()).isEqualTo(Ed2k.fileHash(set.partHashes()));
    }

    private void thenThereAreNoPartHashes() {
        assertThat(this.result.ed2k().partHashes()).isEmpty();
    }

    private void thenTheHashOfTheOnlyPartIsTheFileHash() {
        Ed2kHashSet set = this.result.ed2k();
        assertThat(set.partHash(0)).isEqualTo(set.fileHash());
    }

    private void thenThePartHashesAreZeroPartsThenTheEmptyPartHash(int parts) {
        List<Hash16> expected = new ArrayList<>(Collections.nCopies(parts, md4(new byte[(int) P])));
        expected.add(Ed2k.EMPTY_PART_HASH);
        assertThat(this.result.ed2k().partHashes()).containsExactlyElementsOf(expected);
    }

    private void thenThePartHashesAreTheMd4OfZerosOf(int... lengths) {
        List<Hash16> expected = new ArrayList<>();
        for (int length : lengths) {
            expected.add(md4(new byte[length]));
        }
        assertThat(this.result.ed2k().partHashes()).containsExactlyElementsOf(expected);
    }

    private void thenTheHashOfPartIsTheMd4OfZeros(int part, int length) {
        assertThat(this.result.ed2k().partHash(part)).isEqualTo(md4(new byte[length]));
    }

    private void thenTheHashSetIsConsistent() {
        assertThat(this.result.ed2k().isConsistent()).isTrue();
    }

    private void thenTheEmptyPartHashIs(String hex) {
        assertThat(Ed2k.EMPTY_PART_HASH).isEqualTo(hex(hex));
    }

    private void thenThereIsNoAichTree() {
        assertThat(this.result.aich()).isEmpty();
    }

    private void thenTheAichMasterHashIsTheSha1OfZeros(int length) throws NoSuchAlgorithmException {
        assertThat(aichTree().masterHash())
                .isEqualTo(Hash20.of(MessageDigest.getInstance("SHA-1").digest(new byte[length])));
    }

    private void thenTheAichTreeHasPartHashes(int count) {
        assertThat(aichTree().partHashes()).hasSize(count);
    }

    private void thenTheAichTreeHasBlockHashes(int count) {
        assertThat(aichTree().blockHashes()).hasSize(count);
    }

    private void thenTheAichBlockCountIs(long count) {
        assertThat(AichTree.blockCount(this.size)).isEqualTo(count);
    }

    // A left and a right branch split their blocks differently, so equal parts may differ.
    private void thenTheAichTreeRebuiltFromItsBlocksHasTheSameMasterHash() {
        AichTree tree = aichTree();
        assertThat(AichTree.fromBlockHashes(tree.size(), tree.blockHashes()).masterHash())
                .isEqualTo(tree.masterHash());
    }

    private void thenExceptionOccurred(Class<? extends Throwable> type) {
        assertThat(this.exception).isInstanceOf(type);
    }

    private AichTree aichTree() {
        return this.result.aich().orElseThrow();
    }

    private static FileHasher.Result zeros(long size) throws IOException {
        return FileHasher.hash(new Zeros(size), size, b -> {});
    }

    private static Hash16 hex(String hex) {
        return Hash16.parseHex(hex);
    }

    /** A channel of {@code size} zero bytes, without allocating them. */
    private static final class Zeros implements ReadableByteChannel {
        private long left;

        Zeros(long size) {
            this.left = size;
        }

        @Override
        public int read(ByteBuffer dst) {
            if (this.left == 0) {
                return -1;
            }
            int n = (int) Math.min(dst.remaining(), this.left);
            dst.put(new byte[n]);
            this.left -= n;
            return n;
        }

        @Override
        public boolean isOpen() {
            return true;
        }

        @Override
        public void close() {}
    }

    private static Hash16 md4(byte[] data) {
        return Ed2k.md4(data, 0, data.length);
    }
}
