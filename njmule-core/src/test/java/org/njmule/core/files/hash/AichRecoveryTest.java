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

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.njmule.core.api.Hash20;

class AichRecoveryTest {

    private static final int PER_PART = 53;

    @Test
    void everyPartOfAFileOfSeveralPartsProvesItsBlocks() {
        long size = 3 * Ed2k.PARTSIZE + 1_000_000;
        AichTree tree = tree(size, 1);

        for (int part = 0; part < 4; part++) {
            byte[] data = AichRecovery.create(tree, part);
            List<Hash20> blocks =
                    AichRecovery.verify(size, part, tree.masterHash(), data).orElseThrow();
            int first = part * PER_PART;
            assertThat(blocks)
                    .isEqualTo(tree.blockHashes()
                            .subList(
                                    first,
                                    Math.min(
                                            first + PER_PART, tree.blockHashes().size())));
        }
    }

    @Test
    void theLayoutHasOneSiblingPerLevelThenTheBlocks() {
        long size = 2 * Ed2k.PARTSIZE;
        AichTree tree = tree(size, 2);

        ByteBuffer data = ByteBuffer.wrap(AichRecovery.create(tree, 0)).order(ByteOrder.LITTLE_ENDIAN);

        // Root (1), its right child (2) as the sibling, then the 53 blocks below the left child (3).
        int count = data.getShort() & 0xFFFF;
        assertThat(count).isEqualTo(1 + PER_PART);
        assertThat(data.getShort() & 0xFFFF).isEqualTo(2);
        data.position(data.position() + Hash20.LENGTH);
        int firstBlock = data.getShort() & 0xFFFF;
        assertThat(Integer.highestOneBit(firstBlock) >> 1 & firstBlock)
                .as("below node 3")
                .isNotZero();
        assertThat(data.capacity()).isEqualTo(2 + count * (2 + Hash20.LENGTH) + 2);
    }

    @Test
    void wrongDataProvesNothing() {
        long size = 2 * Ed2k.PARTSIZE + 5;
        AichTree tree = tree(size, 3);
        byte[] data = AichRecovery.create(tree, 1);
        data[data.length - 3] ^= 1;

        assertThat(AichRecovery.verify(size, 1, tree.masterHash(), data)).isEmpty();
        assertThat(AichRecovery.verify(size, 1, tree(size, 4).masterHash(), AichRecovery.create(tree, 1)))
                .isEmpty();
        assertThat(AichRecovery.verify(size, 1, tree.masterHash(), new byte[] {1}))
                .isEmpty();
        assertThat(AichRecovery.hasRecovery(Ed2k.PARTSIZE, 0))
                .as("one part has nothing to recover from")
                .isFalse();
    }

    @Test
    void largeFilesUseLongIdentifiers() {
        long size = AichRecovery.LARGE_FILE + Ed2k.PARTSIZE;
        AichTree tree = tree(size, 5);
        int last = (int) ((size - 1) / Ed2k.PARTSIZE);

        byte[] data = AichRecovery.create(tree, last);

        assertThat(data[0]).isZero();
        assertThat(data[1]).isZero();
        assertThat(AichRecovery.verify(size, last, tree.masterHash(), data)).isPresent();
    }

    private static AichTree tree(long size, long seed) {
        Random random = new Random(seed);
        List<Hash20> leaves = new ArrayList<>();
        for (long i = 0; i < AichTree.blockCount(size); i++) {
            byte[] hash = new byte[Hash20.LENGTH];
            random.nextBytes(hash);
            leaves.add(Hash20.of(hash));
        }
        return AichTree.fromBlockHashes(size, leaves);
    }
}
