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
import static org.assertj.core.api.Assertions.catchThrowable;

import java.util.Locale;
import java.util.stream.Stream;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.Size;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

class Hash20Test {

    private byte[] source;
    private String text;
    private Hash20 hash;
    private Throwable exception;

    @Test
    void shouldFormatAnAichHashAs32Base32Characters() {
        givenTheHashOf(new byte[20]);

        whenItIsFormattedInBase32();

        thenTheTextIs("A".repeat(32));
    }

    @Test
    void shouldParse32Base32CharactersInLowerCase() {
        whenTheBase32IsParsed("a".repeat(32));

        thenTheHashIs(Hash20.of(new byte[20]));
    }

    @Test
    void shouldRefuseACharacterOutsideBase32() {
        whenTheBase32IsParsed("A".repeat(31) + "1");

        thenExceptionOccurred(IllegalArgumentException.class);
    }

    @Test
    void shouldRefuseFewerThan32Characters() {
        whenTheBase32IsParsed("A".repeat(31));

        thenExceptionOccurred(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @MethodSource("paddedBase32")
    void shouldRefusePaddedBase32(String padded) {
        whenTheBase32IsParsed(padded);

        thenExceptionOccurred(IllegalArgumentException.class);
    }

    @Test
    void shouldReadFromInsideTheSource() {
        givenASourceOfBytes(24);

        whenAHashIsReadFrom(4);

        thenTheHashIs(Hash20.of(new byte[20]));
    }

    @ParameterizedTest
    @CsvSource({"24, 5", "10, 0"})
    void shouldNotReadFromOutsideTheSource(int sourceLength, int offset) {
        givenASourceOfBytes(sourceLength);

        whenAHashIsReadFrom(offset);

        thenExceptionOccurred(IndexOutOfBoundsException.class);
    }

    @Property
    void shouldRoundTripBase32InEitherCase(@ForAll @Size(20) byte[] data) {
        givenTheHashOf(data);

        whenItIsFormattedInBase32();

        thenTheTextParsesBackToTheHash();
        thenTheTextInLowerCaseParsesBackToTheHash();
    }

    @Property
    void shouldFormatAnythingParsedBackToTheSameText(@ForAll("base32ish") String base32) {
        whenTheBase32IsParsed(base32);

        thenEitherItFormatsBackToTheSameTextOrItIsNoAichHash();
    }

    static Stream<String> paddedBase32() {
        return Stream.of(
                "A".repeat(29) + "===",
                "A".repeat(28) + "====",
                "A".repeat(31) + "=",
                "=".repeat(32),
                "A".repeat(26) + "======");
    }

    @Provide
    Arbitrary<String> base32ish() {
        return Arbitraries.strings()
                .withChars("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz234567=01")
                .ofMinLength(28)
                .ofMaxLength(34);
    }

    private void givenTheHashOf(byte[] data) {
        this.hash = Hash20.of(data);
    }

    private void givenASourceOfBytes(int length) {
        this.source = new byte[length];
    }

    private void whenItIsFormattedInBase32() {
        this.text = this.hash.toBase32();
    }

    private void whenTheBase32IsParsed(String base32) {
        this.text = base32;
        this.hash = null;
        this.exception = catchThrowable(() -> this.hash = Hash20.parseBase32(base32));
    }

    private void whenAHashIsReadFrom(int offset) {
        this.exception = catchThrowable(() -> this.hash = Hash20.of(this.source, offset));
    }

    private void thenTheTextIs(String expected) {
        assertThat(this.text).isEqualTo(expected);
    }

    private void thenTheHashIs(Hash20 expected) {
        assertThat(this.exception).isNull();
        assertThat(this.hash).isEqualTo(expected);
    }

    private void thenTheTextParsesBackToTheHash() {
        assertThat(Hash20.parseBase32(this.text)).isEqualTo(this.hash);
    }

    private void thenTheTextInLowerCaseParsesBackToTheHash() {
        assertThat(Hash20.parseBase32(this.text.toLowerCase(Locale.ROOT))).isEqualTo(this.hash);
    }

    private void thenEitherItFormatsBackToTheSameTextOrItIsNoAichHash() {
        if (this.exception == null) {
            assertThat(this.hash.toBase32()).isEqualTo(this.text.toUpperCase(Locale.ROOT));
        } else {
            assertThat(this.exception).isInstanceOf(IllegalArgumentException.class);
        }
    }

    private void thenExceptionOccurred(Class<? extends Throwable> type) {
        assertThat(this.exception).as(this.text).isInstanceOf(type);
    }
}
