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

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.njmule.core.api.Hash16;

class Ed2kTest {

    // RFC 1320, appendix A.5.
    @ParameterizedTest
    @CsvSource({
        "'',31D6CFE0D16AE931B73C59D7E0C089C0",
        "abc,A448017AAF21D8525FC10AE87AA6729D",
        "message digest,D9130A8164549FE818874806E1C7014B",
        "12345678901234567890123456789012345678901234567890123456789012345678901234567890,"
                + "E33B4DDC9C38F2199C3E7B164FCC0536"
    })
    void md4MatchesTheRfcVectors(String message, String digest) {
        byte[] data = message.getBytes(StandardCharsets.US_ASCII);
        assertThat(Ed2k.md4(data, 0, data.length).toHex()).isEqualTo(digest);
    }

    private static final long P = Ed2k.PARTSIZE;

    // Reference part counts around the PARTSIZE boundaries.
    @ParameterizedTest
    @CsvSource({
        "1,1,1,0",
        "9727999,1,1,0",
        "9728000,1,2,2",
        "9728001,2,2,2",
        "19456000,2,3,3",
        "19456001,3,3,3"
    })
    void countsParts(long size, int dataParts, int ed2kParts, int hashes) {
        assertThat(Ed2k.partCount(size)).isEqualTo(dataParts);
        assertThat(Ed2k.ed2kPartCount(size)).isEqualTo(ed2kParts);
        assertThat(Ed2k.hashSetSize(size)).isEqualTo(hashes);
    }

    // Reference hash sets: the file hash is the MD4 of the part hashes.
    @ParameterizedTest
    @CsvSource({
        "A72CA8DF7F07154E217C236C89C17619,4891ED2E5C9C49F442145A3A5F608299:31D6CFE0D16AE931B73C59D7E0C089C0",
        "2F620AE9D462CBB6A59FE8401D2B3D23,121795F0BEDE02DDC7C5426D0995F53F:C329E527945B8FE75B3C5E8826755747",
        "A54C5E562D5E03CA7D77961EB9A745A4,B3F5CE2A06BF403BFB9BFFF68BDDC4D9:509AA30C9EA8FC136B1159DF2F35B8A9:31D6CFE0D16AE931B73C59D7E0C089C0",
        "797ED552F34380CAFF8C958207E40355,FC7FD02CCD6987DCF1421F4C0AF94FB8:2FE466AF8A7C06DA3365317B75A5ACFE:873D3BF52629F7C1527C6E8E473C1C30:BCE50BEE7877BB07BB6FDA56BFE142FB"
    })
    void fileHashIsTheMd4OfThePartHashes(String file, String parts) {
        List<Hash16> hashes = Arrays.stream(parts.split(":")).map(Hash16::parseHex).toList();

        assertThat(Ed2k.fileHash(hashes)).isEqualTo(Hash16.parseHex(file));
    }

    @Test
    void hashSetsValidateTheirShape() {
        Hash16 h = Ed2k.EMPTY_PART_HASH;

        assertThatThrownBy(() -> new Ed2kHashSet(P, h, List.of(h)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new Ed2kHashSet(P, h, List.of(h, h)).isConsistent()).isFalse();
        assertThatThrownBy(() -> Ed2k.partCount(Ed2k.MAX_FILE_SIZE + 1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
