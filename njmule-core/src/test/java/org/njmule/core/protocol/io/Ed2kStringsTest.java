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
package org.njmule.core.protocol.io;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.stream.Stream;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Assume;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class Ed2kStringsTest {

    private static final HexFormat HEX = HexFormat.of();

    private byte[] bytes;
    private String text;
    private String decoded;
    private byte[] encoded;
    private Map<StringEncoding, String> roundTrips;

    @Test
    void shouldDecodeValidUtf8AsUtf8() {
        givenTheUtf8BytesOf("città €");

        whenTheBytesAreDecoded();

        thenTheTextIs("città €");
    }

    @Test
    void shouldTakeABomAsAMarkOfUtf8AndDropIt() {
        givenTheBytes("efbbbf63c3a0");

        whenTheBytesAreDecoded();

        thenTheTextIs("cà");
    }

    @Test
    void shouldFallBackToWindows1252ForInvalidUtf8() {
        // "Caf\xE9 \x80" is ANSI from an old client: é and the euro sign.
        givenTheBytes("436166e92080");

        whenTheBytesAreDecoded();

        thenTheTextIs("Café €");
    }

    @Test
    void shouldFallBackToWindows1252ForInvalidUtf8AfterABomToo() {
        givenTheBytes("efbbbf80e9");

        whenTheBytesAreDecoded();

        thenTheTextIs("€é");
    }

    @Test
    void shouldDecodeABomAloneAsNoText() {
        givenTheBytes("efbbbf");

        whenTheBytesAreDecoded();

        thenTheTextIsEmpty();
    }

    @ParameterizedTest
    @MethodSource("malformedUtf8")
    void shouldDecodeMalformedUtf8AsWindows1252(String description, String hex, String expected) {
        givenTheBytes(hex);

        whenTheBytesAreDecoded();

        thenTheTextIs(expected, description);
    }

    @Property
    void shouldRoundTripTextWithoutLoneSurrogatesInBothEncodings(@ForAll String original) {
        givenATextWithoutLoneSurrogatesNorALeadingBom(original);

        whenItIsEncodedAndDecodedInEveryEncoding();

        thenEveryEncodingGivesTheTextBack();
    }

    @Property
    void shouldTakeAsUtf8ExactlyWhatAStrictDecoderAccepts(@ForAll("nearlyUtf8") byte[] raw) {
        givenTheRawBytes(raw);

        whenTheBytesAreDecoded();

        thenTheTextIsWhatAStrictDecoderOrWindows1252Gives();
    }

    @Property
    void shouldDecodeAnyBytesToSomeText(@ForAll byte[] raw) {
        givenTheRawBytes(raw);

        whenTheBytesAreDecoded();

        thenSomeTextIsDecoded();
    }

    @Test
    void shouldWriteAsciiWithoutABom() {
        whenTheTextIsEncoded("plain", StringEncoding.UTF8_BOM_IF_NEEDED);

        thenTheEncodedBytesAreTheAsciiOf("plain");
    }

    @Test
    void shouldWriteUnicodeWithABom() {
        whenTheTextIsEncoded("à", StringEncoding.UTF8_BOM_IF_NEEDED);

        thenTheEncodedBytesAre("efbbbfc3a0");
    }

    @Test
    void shouldWriteUnicodeWithoutABomInPlainUtf8() {
        whenTheTextIsEncoded("à", StringEncoding.UTF8);

        thenTheEncodedBytesAre("c3a0");
    }

    // Valid UTF-8, the same with one byte changed, and plain random bytes.
    @Provide
    Arbitrary<byte[]> nearlyUtf8() {
        Arbitrary<byte[]> valid = Arbitraries.strings().ofMinLength(1).map(t -> t.getBytes(StandardCharsets.UTF_8));
        return Arbitraries.oneOf(
                valid,
                valid.flatMap(
                        b -> Arbitraries.integers().between(0, b.length - 1).map(i -> {
                            byte[] changed = b.clone();
                            changed[i] ^= (byte) 0x40;
                            return changed;
                        })),
                Arbitraries.bytes().array(byte[].class).ofMaxSize(40));
    }

    private static Stream<Arguments> malformedUtf8() {
        return Stream.of(
                Arguments.of("overlong /", "c0af", "À¯"),
                Arguments.of("an encoded surrogate", "eda080", "í" + Character.toString(0xA0) + "€"),
                Arguments.of("a cut BOM", "efbb", "ï»"),
                Arguments.of("a lone lead byte", "ef", "ï"),
                Arguments.of("a lead byte beyond U+10FFFF", "f5808080", "õ€€€"),
                Arguments.of("a sequence cut at the end", "41c3", "AÃ"),
                Arguments.of("no bytes", "", ""));
    }

    private void givenTheBytes(String hex) {
        this.bytes = HEX.parseHex(hex);
    }

    private void givenTheRawBytes(byte[] raw) {
        this.bytes = raw;
    }

    private void givenTheUtf8BytesOf(String original) {
        this.bytes = original.getBytes(StandardCharsets.UTF_8);
    }

    private void givenATextWithoutLoneSurrogatesNorALeadingBom(String original) {
        Assume.that(original.codePoints().noneMatch(c -> c >= 0xD800 && c <= 0xDFFF));
        Assume.that(!original.startsWith("﻿"));
        this.text = original;
    }

    private void whenTheBytesAreDecoded() {
        this.decoded = Ed2kStrings.decode(this.bytes);
    }

    private void whenItIsEncodedAndDecodedInEveryEncoding() {
        this.roundTrips = new EnumMap<>(StringEncoding.class);
        for (StringEncoding encoding : StringEncoding.values()) {
            this.roundTrips.put(encoding, Ed2kStrings.decode(Ed2kStrings.encode(this.text, encoding)));
        }
    }

    private void whenTheTextIsEncoded(String original, StringEncoding encoding) {
        this.encoded = Ed2kStrings.encode(original, encoding);
    }

    private void thenTheTextIs(String expected) {
        assertThat(this.decoded).isEqualTo(expected);
    }

    private void thenTheTextIs(String expected, String description) {
        assertThat(this.decoded).as(description).isEqualTo(expected);
    }

    private void thenTheTextIsEmpty() {
        assertThat(this.decoded).isEmpty();
    }

    private void thenTheTextIsWhatAStrictDecoderOrWindows1252Gives() {
        int at = this.bytes.length >= 3
                        && this.bytes[0] == (byte) 0xEF
                        && this.bytes[1] == (byte) 0xBB
                        && this.bytes[2] == (byte) 0xBF
                ? 3
                : 0;
        String expected;
        try {
            expected = StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(this.bytes, at, this.bytes.length - at))
                    .toString();
        } catch (CharacterCodingException e) {
            expected = new String(this.bytes, at, this.bytes.length - at, Charset.forName("windows-1252"));
        }
        assertThat(this.decoded).isEqualTo(expected);
    }

    private void thenSomeTextIsDecoded() {
        assertThat(this.decoded).isNotNull();
    }

    private void thenEveryEncodingGivesTheTextBack() {
        for (StringEncoding encoding : StringEncoding.values()) {
            assertThat(this.roundTrips.get(encoding)).isEqualTo(this.text);
        }
    }

    private void thenTheEncodedBytesAreTheAsciiOf(String expected) {
        assertThat(this.encoded).isEqualTo(expected.getBytes(StandardCharsets.US_ASCII));
    }

    private void thenTheEncodedBytesAre(String hex) {
        assertThat(HEX.formatHex(this.encoded)).isEqualTo(hex);
    }
}
