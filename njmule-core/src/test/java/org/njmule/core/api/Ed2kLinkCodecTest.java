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
package org.njmule.core.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class Ed2kLinkCodecTest {

    private String text;
    private String result;

    @ParameterizedTest
    @MethodSource("encodings")
    void shouldPercentEncodeWhatALinkCannotCarry(String original, String expected) {
        givenTheText(original);

        whenItIsEncoded();

        thenTheResultIs(expected);
    }

    @Property
    void shouldEncodeAsBefore(@ForAll("anyText") String original) {
        givenTheText(original);

        whenItIsEncoded();

        thenTheResultIs(encodedByHand(original));
    }

    @Property
    void shouldDecodeWhatItEncodes(@ForAll("printableText") String original) {
        givenTheText(Ed2kLinkCodec.encode(original));

        whenItIsDecoded();

        thenTheResultIs(original);
    }

    static Stream<Arguments> encodings() {
        String punctuation = "!\"#$&'()*+,-./:;<=>?@[\\]^_`{}~";
        return Stream.of(
                Arguments.of("a b|c%d", "a%20b%7Cc%25d"),
                Arguments.of("Café.avi", "Caf%C3%A9.avi"),
                Arguments.of("x\uD800y", "x?y"),
                Arguments.of("\u007F\u0001", "%7F%01"),
                Arguments.of(punctuation, punctuation));
    }

    @Provide
    Arbitrary<String> anyText() {
        return Arbitraries.strings().all().ofMaxLength(40);
    }

    @Provide
    Arbitrary<String> printableText() {
        return Arbitraries.strings()
                .withCharRange(' ', '퟿')
                .ofMaxLength(40)
                .filter(t -> t.chars().noneMatch(c -> c == 0x7F || (c >= 0x80 && c < 0xA0)));
    }

    private void givenTheText(String value) {
        this.text = value;
    }

    private void whenItIsEncoded() {
        this.result = Ed2kLinkCodec.encode(this.text);
    }

    private void whenItIsDecoded() {
        this.result = Ed2kLinkCodec.decode(this.text);
    }

    private void thenTheResultIs(String expected) {
        assertThat(this.result).isEqualTo(expected);
    }

    // The encoding the escaper replaced, as the reference.
    private static String encodedByHand(String original) {
        StringBuilder out = new StringBuilder();
        for (byte b : original.getBytes(StandardCharsets.UTF_8)) {
            int v = b & 0xFF;
            if (v == '%' || v == ' ' || v == '|' || v < 0x20 || v >= 0x7F) {
                out.append(String.format("%%%02X", v));
            } else {
                out.append((char) v);
            }
        }
        return out.toString();
    }
}
