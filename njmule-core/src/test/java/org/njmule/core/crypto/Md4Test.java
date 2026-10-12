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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class Md4Test {

    // RFC 1320, appendix A.5.
    @ParameterizedTest
    @CsvSource({
        "'',31D6CFE0D16AE931B73C59D7E0C089C0",
        "a,BDE52CB31DE33E46245E05FBDBD6FB24",
        "abc,A448017AAF21D8525FC10AE87AA6729D",
        "message digest,D9130A8164549FE818874806E1C7014B",
        "abcdefghijklmnopqrstuvwxyz,D79E1C308AA5BBCDEEA8ED63DF412DA9",
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789,"
                + "043F8582F241DB351CE627E153E7F0E4",
        "12345678901234567890123456789012345678901234567890123456789012345678901234567890,"
                + "E33B4DDC9C38F2199C3E7B164FCC0536"
    })
    void matchesTheRfcVectors(String message, String digest) {
        assertThat(Md4.hash(message.getBytes(StandardCharsets.US_ASCII)).toHex()).isEqualTo(digest);
    }

    @Property
    void chunkedUpdatesMatchOneShot(
            @ForAll @Size(max = 300) byte[] data, @ForAll @IntRange(min = 1, max = 70) int chunk) {
        Md4 md4 = new Md4();
        for (int i = 0; i < data.length; i += chunk) {
            md4.update(data, i, Math.min(chunk, data.length - i));
        }

        assertThat(md4.digest()).isEqualTo(Md4.hash(data));
    }

    @Property
    void digestResetsForReuse(@ForAll @Size(max = 100) byte[] data) {
        Md4 md4 = new Md4();
        md4.update(new byte[] {1, 2, 3});
        md4.digest();
        md4.update(data);

        assertThat(md4.digest()).isEqualTo(Md4.hash(data));
    }
}
