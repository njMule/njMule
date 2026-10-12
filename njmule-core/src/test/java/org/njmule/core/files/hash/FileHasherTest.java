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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.security.MessageDigest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.njmule.core.api.Hash16;
import org.njmule.core.api.Hash20;
import org.njmule.core.crypto.Md4;

class FileHasherTest {

    private static final long P = Ed2k.PARTSIZE;

    @Test
    void smallFilesHaveNoHashSet() throws IOException {
        Ed2kHashSet set = zeros(3).ed2k();

        assertThat(set.fileHash()).isEqualTo(Md4.hash(new byte[3]));
        assertThat(set.partHashes()).isEmpty();
        assertThat(set.partHash(0)).isEqualTo(set.fileHash());
    }

    @Test
    void anExactMultipleOfPartsizeEndsWithTheEmptyPartHash() throws IOException {
        Hash16 zeroPart = Md4.hash(new byte[(int) P]);

        Ed2kHashSet one = zeros(P).ed2k();
        assertThat(one.partHashes()).containsExactly(zeroPart, Ed2k.EMPTY_PART_HASH);
        assertThat(one.fileHash()).isEqualTo(Ed2k.fileHash(one.partHashes()));
        assertThat(Ed2k.EMPTY_PART_HASH).isEqualTo(hex("31D6CFE0D16AE931B73C59D7E0C089C0"));

        assertThat(zeros(2 * P).ed2k().partHashes())
                .containsExactly(zeroPart, zeroPart, Ed2k.EMPTY_PART_HASH);
    }

    @Test
    void partialLastPartsAreHashedAsTheyAre() throws IOException {
        Ed2kHashSet set = zeros(P + 1).ed2k();

        assertThat(set.partHashes())
                .containsExactly(Md4.hash(new byte[(int) P]), Md4.hash(new byte[1]));
        assertThat(set.isConsistent()).isTrue();
        assertThat(set.partHash(1)).isEqualTo(Md4.hash(new byte[1]));
    }

    @Test
    void emptyFilesHashToTheEmptyMd4AndHaveNoAich() throws IOException {
        FileHasher.Result result = zeros(0);

        assertThat(result.ed2k().fileHash()).isEqualTo(Ed2k.EMPTY_PART_HASH);
        assertThat(result.aich()).isEmpty();
    }

    @Test
    void aichOfASingleBlockIsTheSha1OfTheData() throws Exception {
        AichTree tree = zeros(1000).aich().orElseThrow();

        assertThat(tree.masterHash())
                .isEqualTo(Hash20.of(MessageDigest.getInstance("SHA-1").digest(new byte[1000])));
        assertThat(tree.partHashes()).isEmpty();
        assertThat(tree.blockHashes()).hasSize(1);
    }

    @Test
    void aichLeavesRestartAtEveryPart() throws IOException {
        AichTree tree = zeros(2 * P + 5).aich().orElseThrow();

        assertThat(tree.blockHashes()).hasSize(53 + 53 + 1);
        assertThat(AichTree.blockCount(2 * P + 5)).isEqualTo(107);
        assertThat(tree.partHashes()).hasSize(3);
        // A left and a right branch split their blocks differently, so equal parts may differ.
        assertThat(AichTree.fromBlockHashes(tree.size(), tree.blockHashes()).masterHash())
                .isEqualTo(tree.masterHash());
    }

    @Test
    void rejectsShortDataAndWrongLeafCounts() {
        assertThatThrownBy(() -> FileHasher.hash(new Zeros(10), 11, b -> {}))
                .isInstanceOf(EOFException.class);
        assertThatThrownBy(() -> AichTree.fromBlockHashes(P + 1, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
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
}
