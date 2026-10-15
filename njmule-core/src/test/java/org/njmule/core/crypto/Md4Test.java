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
package org.njmule.core.crypto;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;
import org.bouncycastle.crypto.digests.MD4Digest;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.njmule.core.api.Hash16;

class Md4Test {

    private byte[] data;
    private Hash16 digest;

    // RFC 1320, appendix A.5.
    @ParameterizedTest
    @CsvSource({
        "'',31D6CFE0D16AE931B73C59D7E0C089C0",
        "a,BDE52CB31DE33E46245E05FBDBD6FB24",
        "abc,A448017AAF21D8525FC10AE87AA6729D",
        "message digest,D9130A8164549FE818874806E1C7014B",
        "abcdefghijklmnopqrstuvwxyz,D79E1C308AA5BBCDEEA8ED63DF412DA9",
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789,043F8582F241DB351CE627E153E7F0E4",
        "12345678901234567890123456789012345678901234567890123456789012345678901234567890,"
                + "E33B4DDC9C38F2199C3E7B164FCC0536"
    })
    void shouldMatchTheRfcVectors(String message, String expected) {
        givenTheData(message.getBytes(StandardCharsets.US_ASCII));

        whenTheDataIsHashedAtOnce();

        thenTheDigestIs(Hash16.parseHex(expected));
    }

    @Property
    void shouldGiveTheSameDigestWhateverPiecesTheDataIsFedIn(
            @ForAll @Size(max = 300) byte[] data, @ForAll @IntRange(min = 1, max = 70) int piece) {
        givenTheData(data);

        whenTheDataIsHashedInPiecesOf(piece);

        thenTheDigestIs(Md4.hash(data, 0, data.length));
    }

    @Property
    void shouldStartAgainAfterADigest(@ForAll @Size(max = 100) byte[] data) {
        givenTheData(data);

        whenTheDataIsHashedAfterAnotherDigest();

        thenTheDigestIs(Md4.hash(data, 0, data.length));
    }

    @Property
    void shouldAgreeWithBouncyCastle(@ForAll @Size(max = 1000) byte[] data) {
        givenTheData(data);

        whenTheDataIsHashedAtOnce();

        thenTheDigestIs(bouncyCastleMd4(data));
    }

    private void givenTheData(byte[] data) {
        this.data = data;
    }

    private void whenTheDataIsHashedAtOnce() {
        this.digest = Md4.hash(this.data, 0, this.data.length);
    }

    private void whenTheDataIsHashedInPiecesOf(int piece) {
        Md4 md4 = new Md4();
        for (int at = 0; at < this.data.length; at += piece) {
            md4.update(this.data, at, Math.min(piece, this.data.length - at));
        }
        this.digest = md4.digest();
    }

    private void whenTheDataIsHashedAfterAnotherDigest() {
        Md4 md4 = new Md4();
        md4.update(new byte[] {1, 2, 3}, 0, 3);
        md4.digest();
        md4.update(this.data, 0, this.data.length);
        this.digest = md4.digest();
    }

    private void thenTheDigestIs(Hash16 expected) {
        assertThat(this.digest).isEqualTo(expected);
    }

    private static Hash16 bouncyCastleMd4(byte[] data) {
        MD4Digest md4 = new MD4Digest();
        md4.update(data, 0, data.length);
        byte[] out = new byte[Hash16.LENGTH];
        md4.doFinal(out, 0);
        return Hash16.of(out);
    }
}
