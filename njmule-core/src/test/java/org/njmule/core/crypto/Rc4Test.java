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
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.HexFormat;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class Rc4Test {

    private static final HexFormat HEX = HexFormat.of();

    @ParameterizedTest
    @CsvSource({
        "Key,Plaintext,bbf316e8d940af0ad3",
        "Wiki,pedia,1021bf0420",
        "Secret,Attack at dawn,45a01f645fc35b383552544b9bf5"
    })
    void matchesTheClassicVectors(String key, String plain, String cipher) {
        byte[] data = plain.getBytes(StandardCharsets.US_ASCII);
        new Rc4(key.getBytes(StandardCharsets.US_ASCII)).apply(data, 0, data.length);

        assertThat(HEX.formatHex(data)).isEqualTo(cipher);
    }

    @Test
    void matchesRfc6229KeyStream() {
        // RFC 6229, 40-bit key 0x0102030405, offsets 0 and 16.
        byte[] stream = new byte[32];
        new Rc4(HEX.parseHex("0102030405")).apply(stream, 0, stream.length);

        assertThat(HEX.formatHex(stream))
                .isEqualTo("b2396305f03dc027ccc3524a0a1118a8" + "6982944f18fc82d589c403a47a0d0919");
    }

    @Property
    void skipEqualsDiscardingOutput(
            @ForAll @Size(min = 1, max = 32) byte[] key, @ForAll @IntRange(max = 2048) int skip) {
        byte[] full = new byte[skip + 16];
        new Rc4(key).apply(full, 0, full.length);
        byte[] tail = new byte[16];
        Rc4 rc4 = new Rc4(key);
        rc4.skip(skip);
        rc4.apply(tail, 0, tail.length);

        assertThat(tail).isEqualTo(Arrays.copyOfRange(full, skip, full.length));
    }

    @Property
    void agreesWithTheJdkCipher(
            @ForAll @Size(min = 5, max = 64) byte[] key, @ForAll @Size(max = 200) byte[] data)
            throws GeneralSecurityException {
        Cipher jdk = Cipher.getInstance("ARCFOUR");
        jdk.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "ARCFOUR"));
        byte[] expected = jdk.update(data);
        byte[] actual = data.clone();
        new Rc4(key).apply(actual, 0, actual.length);

        assertThat(actual).isEqualTo(expected == null ? new byte[0] : expected);
    }
}
