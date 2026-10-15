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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.LongRange;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.njmule.core.api.Hash16;

class Ed2kTest {

    private static final long P = Ed2k.PARTSIZE;

    private byte[] message;
    private Hash16 digest;
    private List<Hash16> partHashes;
    private long size;
    private int dataParts;
    private int ed2kParts;
    private int hashSetSize;
    private List<Long> partStarts;
    private List<Long> partLengths;
    private long aichBlocks;
    private Ed2kHashSet hashSet;
    private Throwable exception;

    // RFC 1320, appendix A.5.
    @ParameterizedTest
    @CsvSource({
        "'',31D6CFE0D16AE931B73C59D7E0C089C0",
        "abc,A448017AAF21D8525FC10AE87AA6729D",
        "message digest,D9130A8164549FE818874806E1C7014B",
        "12345678901234567890123456789012345678901234567890123456789012345678901234567890,"
                + "E33B4DDC9C38F2199C3E7B164FCC0536"
    })
    void shouldMatchTheRfcVectorsOfMd4(String message, String digest) {
        givenTheAsciiMessage(message);

        whenTheMd4OfTheMessageIsComputed();

        thenTheDigestIs(digest);
    }

    // Reference part counts around the PARTSIZE boundaries.
    @ParameterizedTest
    @CsvSource({"1,1,1,0", "9727999,1,1,0", "9728000,1,2,2", "9728001,2,2,2", "19456000,2,3,3", "19456001,3,3,3"})
    void shouldCountThePartsAroundThePartsizeBoundaries(long size, int dataParts, int ed2kParts, int hashes) {
        whenThePartsOfAFileAreCounted(size);

        thenTheDataPartsAre(dataParts);
        thenTheEd2kPartsAre(ed2kParts);
        thenTheHashSetSizeIs(hashes);
    }

    // Reference hash sets: the file hash is the MD4 of the part hashes.
    @ParameterizedTest
    @CsvSource({
        "A72CA8DF7F07154E217C236C89C17619,4891ED2E5C9C49F442145A3A5F608299:31D6CFE0D16AE931B73C59D7E0C089C0",
        "2F620AE9D462CBB6A59FE8401D2B3D23,121795F0BEDE02DDC7C5426D0995F53F:C329E527945B8FE75B3C5E8826755747",
        "A54C5E562D5E03CA7D77961EB9A745A4,B3F5CE2A06BF403BFB9BFFF68BDDC4D9:509AA30C9EA8FC136B1159DF2F35B8A9:31D6CFE0D16AE931B73C59D7E0C089C0",
        "797ED552F34380CAFF8C958207E40355,FC7FD02CCD6987DCF1421F4C0AF94FB8:2FE466AF8A7C06DA3365317B75A5ACFE:873D3BF52629F7C1527C6E8E473C1C30:BCE50BEE7877BB07BB6FDA56BFE142FB"
    })
    void shouldHashAFileAsTheMd4OfItsPartHashes(String file, String parts) {
        givenThePartHashes(parts);

        whenTheFileHashIsComputed();

        thenTheFileHashIs(file);
    }

    @Property
    void shouldCoverAFileExactlyWithItsPartsAndBlocks(@ForAll @LongRange(min = 1, max = Ed2k.MAX_FILE_SIZE) long size) {
        whenAFileIsLaidOutInParts(size);

        thenEveryPartIsPositiveAndAtMostAPartsize();
        thenEveryPartStartsWhereThePreviousOneEnds();
        thenThePartsCoverTheWholeFile();
        thenTheAichBlocksAreThoseOfEveryPart();
        thenThereIsOneEd2kPartMoreThanTheWholePartsizes();
        thenTheHashSetIsEmptyBelowAPartsizeElseHasOneHashPerEd2kPart();
    }

    @Test
    void shouldRefuseAHashSetWithTooFewPartHashes() {
        whenAHashSetOfEmptyPartHashesIsMade(P, 1);

        thenExceptionOccurred(IllegalArgumentException.class);
    }

    @Test
    void shouldFindAHashSetInconsistentWhenItsFileHashIsNotThatOfItsParts() {
        whenAHashSetOfEmptyPartHashesIsMade(P, 2);

        thenTheHashSetIsNotConsistent();
    }

    @Test
    void shouldRefuseAFileAboveTheMaximumSize() {
        whenTheDataPartsOfAFileAreCounted(Ed2k.MAX_FILE_SIZE + 1);

        thenExceptionOccurred(IllegalArgumentException.class);
    }

    private void givenTheAsciiMessage(String message) {
        this.message = message.getBytes(StandardCharsets.US_ASCII);
    }

    private void givenThePartHashes(String parts) {
        this.partHashes = Arrays.stream(parts.split(":")).map(Hash16::parseHex).toList();
    }

    private void whenTheMd4OfTheMessageIsComputed() {
        this.digest = Ed2k.md4(this.message, 0, this.message.length);
    }

    private void whenTheFileHashIsComputed() {
        this.digest = Ed2k.fileHash(this.partHashes);
    }

    private void whenThePartsOfAFileAreCounted(long size) {
        this.size = size;
        this.dataParts = Ed2k.partCount(size);
        this.ed2kParts = Ed2k.ed2kPartCount(size);
        this.hashSetSize = Ed2k.hashSetSize(size);
    }

    private void whenAFileIsLaidOutInParts(long size) {
        whenThePartsOfAFileAreCounted(size);
        this.partStarts = new ArrayList<>();
        this.partLengths = new ArrayList<>();
        for (int part = 0; part < this.dataParts; part++) {
            this.partStarts.add(Ed2k.partStart(part));
            this.partLengths.add(Ed2k.partLength(size, part));
        }
        this.aichBlocks = AichTree.blockCount(size);
    }

    private void whenTheDataPartsOfAFileAreCounted(long size) {
        this.exception = catchThrowable(() -> Ed2k.partCount(size));
    }

    private void whenAHashSetOfEmptyPartHashesIsMade(long size, int partHashes) {
        this.exception = catchThrowable(() -> this.hashSet =
                new Ed2kHashSet(size, Ed2k.EMPTY_PART_HASH, Collections.nCopies(partHashes, Ed2k.EMPTY_PART_HASH)));
    }

    private void thenTheDigestIs(String hex) {
        assertThat(this.digest.toHex()).isEqualTo(hex);
    }

    private void thenTheFileHashIs(String hex) {
        assertThat(this.digest).isEqualTo(Hash16.parseHex(hex));
    }

    private void thenTheDataPartsAre(int expected) {
        assertThat(this.dataParts).isEqualTo(expected);
    }

    private void thenTheEd2kPartsAre(int expected) {
        assertThat(this.ed2kParts).isEqualTo(expected);
    }

    private void thenTheHashSetSizeIs(int expected) {
        assertThat(this.hashSetSize).isEqualTo(expected);
    }

    private void thenEveryPartIsPositiveAndAtMostAPartsize() {
        for (long length : this.partLengths) {
            assertThat(length).isPositive().isLessThanOrEqualTo(P);
        }
    }

    private void thenEveryPartStartsWhereThePreviousOneEnds() {
        long total = 0;
        for (int part = 0; part < this.partLengths.size(); part++) {
            assertThat(this.partStarts.get(part)).isEqualTo(total);
            total += this.partLengths.get(part);
        }
    }

    private void thenThePartsCoverTheWholeFile() {
        long total = 0;
        for (long length : this.partLengths) {
            total += length;
        }
        assertThat(total).isEqualTo(this.size);
    }

    private void thenTheAichBlocksAreThoseOfEveryPart() {
        long blocks = 0;
        for (long length : this.partLengths) {
            blocks += (length + Ed2k.EMBLOCKSIZE - 1) / Ed2k.EMBLOCKSIZE;
        }
        assertThat(this.aichBlocks).isEqualTo(blocks);
    }

    private void thenThereIsOneEd2kPartMoreThanTheWholePartsizes() {
        assertThat(this.ed2kParts).isEqualTo(this.size / P + 1);
    }

    private void thenTheHashSetIsEmptyBelowAPartsizeElseHasOneHashPerEd2kPart() {
        assertThat(this.hashSetSize).isEqualTo(this.size < P ? 0 : (int) (this.size / P + 1));
    }

    private void thenTheHashSetIsNotConsistent() {
        assertThat(this.exception).isNull();
        assertThat(this.hashSet.isConsistent()).isFalse();
    }

    private void thenExceptionOccurred(Class<? extends Throwable> type) {
        assertThat(this.exception).isInstanceOf(type);
    }
}
