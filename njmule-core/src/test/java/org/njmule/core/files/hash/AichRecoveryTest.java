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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.LongRange;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.njmule.core.api.Hash20;

class AichRecoveryTest {

    private static final int PER_PART = 53;

    private long size;
    private AichTree tree;
    private int part;
    private byte[] data;
    private Optional<List<Hash20>> proven;
    private Map<Integer, Optional<List<Hash20>>> provenByPart;
    private boolean recovery;

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3})
    void shouldProveTheBlocksOfEveryPartOfAFileOfSeveralParts(int part) {
        givenATreeOf(3 * Ed2k.PARTSIZE + 1_000_000, 1);
        givenTheRecoveryDataOfAPart(part);

        whenTheRecoveryDataIsVerified();

        thenThePartProvesItsBlocks();
    }

    @Test
    void shouldLayOutOneSiblingPerLevelThenTheBlocks() {
        givenATreeOf(2 * Ed2k.PARTSIZE, 2);

        whenTheRecoveryDataOfAPartIsCreated(0);

        thenTheRecoveryDataHoldsHashes(1 + PER_PART);
        thenTheFirstHashIsOfNode(2);
        thenTheSecondHashIsBelowNode3();
        thenTheRecoveryDataEndsAfterItsHashes();
    }

    @Test
    void shouldProveNothingWithCorruptedData() {
        givenATreeOf(2 * Ed2k.PARTSIZE + 5, 3);
        givenTheRecoveryDataOfAPart(1);
        givenTheRecoveryDataCorruptedAtBytesFromTheEnd(3);

        whenTheRecoveryDataIsVerified();

        thenNothingIsProven();
    }

    @Test
    void shouldProveNothingAgainstTheMasterHashOfAnotherFile() {
        givenATreeOf(2 * Ed2k.PARTSIZE + 5, 3);
        givenTheRecoveryDataOfAPart(1);

        whenTheRecoveryDataIsVerifiedAgainstTheMasterHashOfTheTreeOfSeed(4);

        thenNothingIsProven();
    }

    @Test
    void shouldProveNothingWithDataOfOneByte() {
        givenATreeOf(2 * Ed2k.PARTSIZE + 5, 3);
        givenTheRecoveryDataOfPart(1, new byte[] {1});

        whenTheRecoveryDataIsVerified();

        thenNothingIsProven();
    }

    @ParameterizedTest
    @ValueSource(longs = {Ed2k.EMBLOCKSIZE + 1, 1_000_000, Ed2k.PARTSIZE})
    void shouldProveAllTheBlocksOfAFileOfOnePartAboveOneBlock(long size) {
        givenATreeOf(size, 6);
        givenTheRecoveryDataOfAPart(0);

        whenTheRecoveryDataIsVerified();

        thenAllTheBlocksOfTheFileAreProven();
    }

    @Test
    void shouldHaveNoRecoveryDataForAFileOfOneBlock() {
        whenItIsAskedWhetherThereIsRecoveryDataFor(Ed2k.EMBLOCKSIZE, 0);

        thenThereIsNoRecoveryData("one block is the master hash itself");
    }

    @Test
    void shouldHaveNoRecoveryDataForALastPartOfOneBlock() {
        whenItIsAskedWhetherThereIsRecoveryDataFor(2 * Ed2k.PARTSIZE + Ed2k.EMBLOCKSIZE, 2);

        thenThereIsNoRecoveryData("a last part of one block");
    }

    @Test
    void shouldHaveRecoveryDataForALastPartAboveOneBlock() {
        whenItIsAskedWhetherThereIsRecoveryDataFor(2 * Ed2k.PARTSIZE + Ed2k.EMBLOCKSIZE + 1, 2);

        thenThereIsRecoveryData();
    }

    @Test
    void shouldHaveNoRecoveryDataForAPartBeyondTheFile() {
        whenItIsAskedWhetherThereIsRecoveryDataFor(2 * Ed2k.PARTSIZE, 2);

        thenThereIsNoRecoveryData("no such part");
    }

    @Test
    void shouldHaveNoRecoveryDataForANegativePart() {
        whenItIsAskedWhetherThereIsRecoveryDataFor(Ed2k.PARTSIZE, -1);

        thenThereIsNoRecoveryData("a negative part");
    }

    @Property(tries = 200)
    void shouldProveTheBlocksOfEveryPartOfMoreThanOneBlock(
            @ForAll @LongRange(min = 1, max = 4 * Ed2k.PARTSIZE + Ed2k.EMBLOCKSIZE) long size,
            @ForAll @LongRange(min = 0, max = 1000) long seed) {
        givenATreeOf(size, seed);

        whenTheRecoveryDataOfEveryPartOfMoreThanOneBlockIsCreatedAndVerified();

        thenEveryPartOfMoreThanOneBlockProvesItsBlocks();
        thenNoPartOfOneBlockHasRecoveryData();
        thenThereIsNoRecoveryDataBeyondTheLastPart();
    }

    @Test
    void shouldUseLongIdentifiersForLargeFiles() {
        givenATreeOf(AichRecovery.LARGE_FILE + Ed2k.PARTSIZE, 5);

        whenTheRecoveryDataOfTheLastPartIsCreated();

        thenTheRecoveryDataStartsWithTwoZeroBytes();
        thenTheRecoveryDataProvesThePart();
    }

    private void givenATreeOf(long size, long seed) {
        this.size = size;
        this.tree = tree(size, seed);
    }

    private void givenTheRecoveryDataOfAPart(int part) {
        this.part = part;
        this.data = AichRecovery.create(this.tree, part);
    }

    private void givenTheRecoveryDataOfPart(int part, byte[] data) {
        this.part = part;
        this.data = data;
    }

    private void givenTheRecoveryDataCorruptedAtBytesFromTheEnd(int distance) {
        this.data[this.data.length - distance] ^= 1;
    }

    private void whenTheRecoveryDataIsVerified() {
        this.proven = AichRecovery.verify(this.size, this.part, this.tree.masterHash(), this.data);
    }

    private void whenTheRecoveryDataIsVerifiedAgainstTheMasterHashOfTheTreeOfSeed(long seed) {
        this.proven =
                AichRecovery.verify(this.size, this.part, tree(this.size, seed).masterHash(), this.data);
    }

    private void whenTheRecoveryDataOfAPartIsCreated(int part) {
        this.part = part;
        this.data = AichRecovery.create(this.tree, part);
    }

    private void whenTheRecoveryDataOfTheLastPartIsCreated() {
        whenTheRecoveryDataOfAPartIsCreated((int) ((this.size - 1) / Ed2k.PARTSIZE));
    }

    private void whenItIsAskedWhetherThereIsRecoveryDataFor(long size, int part) {
        this.recovery = AichRecovery.hasRecovery(size, part);
    }

    private void whenTheRecoveryDataOfEveryPartOfMoreThanOneBlockIsCreatedAndVerified() {
        this.provenByPart = new HashMap<>();
        for (int p = 0; p < Ed2k.partCount(this.size); p++) {
            if (Ed2k.partLength(this.size, p) > Ed2k.EMBLOCKSIZE) {
                this.provenByPart.put(
                        p,
                        AichRecovery.verify(this.size, p, this.tree.masterHash(), AichRecovery.create(this.tree, p)));
            }
        }
    }

    private void thenThePartProvesItsBlocks() {
        int first = this.part * PER_PART;
        assertThat(this.proven)
                .contains(this.tree
                        .blockHashes()
                        .subList(
                                first,
                                Math.min(
                                        first + PER_PART,
                                        this.tree.blockHashes().size())));
    }

    private void thenAllTheBlocksOfTheFileAreProven() {
        assertThat(this.proven).as("%d bytes", this.size).contains(this.tree.blockHashes());
    }

    private void thenNothingIsProven() {
        assertThat(this.proven).isEmpty();
    }

    private void thenTheRecoveryDataHoldsHashes(int count) {
        assertThat(recoveryData().getShort(0) & 0xFFFF).isEqualTo(count);
    }

    // Root (1), its right child (2) as the sibling, then the 53 blocks below the left child (3).
    private void thenTheFirstHashIsOfNode(int node) {
        assertThat(recoveryData().getShort(2) & 0xFFFF).isEqualTo(node);
    }

    private void thenTheSecondHashIsBelowNode3() {
        int firstBlock = recoveryData().getShort(2 + 2 + Hash20.LENGTH) & 0xFFFF;
        assertThat(Integer.highestOneBit(firstBlock) >> 1 & firstBlock)
                .as("below node 3")
                .isNotZero();
    }

    private void thenTheRecoveryDataEndsAfterItsHashes() {
        ByteBuffer recoveryData = recoveryData();
        int count = recoveryData.getShort(0) & 0xFFFF;
        assertThat(recoveryData.capacity()).isEqualTo(2 + count * (2 + Hash20.LENGTH) + 2);
    }

    private void thenTheRecoveryDataStartsWithTwoZeroBytes() {
        assertThat(this.data[0]).isZero();
        assertThat(this.data[1]).isZero();
    }

    private void thenTheRecoveryDataProvesThePart() {
        assertThat(AichRecovery.verify(this.size, this.part, this.tree.masterHash(), this.data))
                .isPresent();
    }

    private void thenThereIsRecoveryData() {
        assertThat(this.recovery).isTrue();
    }

    private void thenThereIsNoRecoveryData(String why) {
        assertThat(this.recovery).as(why).isFalse();
    }

    private void thenEveryPartOfMoreThanOneBlockProvesItsBlocks() {
        for (int p = 0; p < Ed2k.partCount(this.size); p++) {
            long length = Ed2k.partLength(this.size, p);
            if (length > Ed2k.EMBLOCKSIZE) {
                int first = p * PER_PART;
                int count = (int) ((length + Ed2k.EMBLOCKSIZE - 1) / Ed2k.EMBLOCKSIZE);
                assertThat(this.provenByPart.get(p))
                        .as("part %d of %d bytes", p, this.size)
                        .contains(this.tree.blockHashes().subList(first, first + count));
            }
        }
    }

    private void thenNoPartOfOneBlockHasRecoveryData() {
        for (int p = 0; p < Ed2k.partCount(this.size); p++) {
            if (Ed2k.partLength(this.size, p) <= Ed2k.EMBLOCKSIZE) {
                assertThat(AichRecovery.hasRecovery(this.size, p)).isFalse();
            }
        }
    }

    private void thenThereIsNoRecoveryDataBeyondTheLastPart() {
        assertThat(AichRecovery.hasRecovery(this.size, Ed2k.partCount(this.size)))
                .isFalse();
    }

    private ByteBuffer recoveryData() {
        return ByteBuffer.wrap(this.data).order(ByteOrder.LITTLE_ENDIAN);
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
