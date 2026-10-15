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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class Hash16Test {

    private byte[] source;
    private Hash16 hash;
    private boolean zero;
    private int comparison;
    private Throwable exception;

    @Test
    void shouldParseAndFormatUpperCaseHex() {
        whenTheHexIsParsed("31d6cfe0d16ae931b73c59d7e0c089c0");

        thenTheHexIs("31D6CFE0D16AE931B73C59D7E0C089C0");
        thenTheHashEqualsItsHexParsedBack();
    }

    @Test
    void shouldNotTakeAHashWithBitsSetForZero() {
        givenTheHash(Hash16.parseHex("31d6cfe0d16ae931b73c59d7e0c089c0"));

        whenItIsCheckedForZero();

        thenItIsZero(false);
    }

    @Test
    void shouldTakeTheZeroHashForZero() {
        givenTheHash(Hash16.ZERO);

        whenItIsCheckedForZero();

        thenItIsZero(true);
    }

    @Test
    void shouldStayTheSameWhenTheBytesItWasMadeOfChange() {
        givenAHashMadeOfZeroBytes();

        whenTheBytesItWasMadeOfChange();

        thenTheHashIs(Hash16.ZERO);
    }

    @Test
    void shouldStayTheSameWhenTheBytesItGaveChange() {
        givenAHashMadeOfZeroBytes();

        whenTheBytesItGaveChange();

        thenTheHashIs(Hash16.ZERO);
    }

    @Test
    void shouldCompareUnsigned() {
        whenTheHashesAreCompared("80" + "00".repeat(15), "7F" + "FF".repeat(15));

        thenTheFirstIsGreater();
    }

    @Test
    void shouldRejectBytesOfTheWrongLength() {
        whenAHashIsMadeOf(new byte[15]);

        thenExceptionOccurred(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectHexOfTheWrongLength() {
        whenTheHexIsParsed("00");

        thenExceptionOccurred(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectCharactersThatAreNotHexDigits() {
        whenTheHexIsParsed("G".repeat(32));

        thenExceptionOccurred(IllegalArgumentException.class);
    }

    @Test
    void shouldReadFromInsideTheSource() {
        givenASourceOfBytes(20);
        givenTheSourceByteAt(4, (byte) 7);

        whenAHashIsReadFrom(4);

        thenTheFirstByteIs((byte) 7);
    }

    @ParameterizedTest
    @CsvSource({"20, 5", "10, 0", "20, -1"})
    void shouldNotReadFromOutsideTheSource(int sourceLength, int offset) {
        givenASourceOfBytes(sourceLength);

        whenAHashIsReadFrom(offset);

        thenExceptionOccurred(IndexOutOfBoundsException.class);
    }

    private void givenTheHash(Hash16 given) {
        this.hash = given;
    }

    private void givenAHashMadeOfZeroBytes() {
        this.source = new byte[16];
        this.hash = Hash16.of(this.source);
    }

    private void givenASourceOfBytes(int length) {
        this.source = new byte[length];
    }

    private void givenTheSourceByteAt(int index, byte value) {
        this.source[index] = value;
    }

    private void whenTheHexIsParsed(String hex) {
        this.exception = catchThrowable(() -> this.hash = Hash16.parseHex(hex));
    }

    private void whenItIsCheckedForZero() {
        this.zero = this.hash.isZero();
    }

    private void whenTheBytesItWasMadeOfChange() {
        this.source[0] = 1;
    }

    private void whenTheBytesItGaveChange() {
        this.hash.toByteArray()[1] = 1;
    }

    private void whenTheHashesAreCompared(String hex, String otherHex) {
        this.comparison = Hash16.parseHex(hex).compareTo(Hash16.parseHex(otherHex));
    }

    private void whenAHashIsMadeOf(byte[] bytes) {
        this.exception = catchThrowable(() -> this.hash = Hash16.of(bytes));
    }

    private void whenAHashIsReadFrom(int offset) {
        this.exception = catchThrowable(() -> this.hash = Hash16.of(this.source, offset));
    }

    private void thenTheHexIs(String expected) {
        assertThat(this.exception).isNull();
        assertThat(this.hash.toHex()).isEqualTo(expected);
    }

    private void thenTheHashEqualsItsHexParsedBack() {
        assertThat(this.hash).isEqualTo(Hash16.parseHex(this.hash.toHex())).hasSameHashCodeAs(this.hash);
    }

    private void thenItIsZero(boolean expected) {
        assertThat(this.zero).isEqualTo(expected);
    }

    private void thenTheHashIs(Hash16 expected) {
        assertThat(this.hash).isEqualTo(expected);
    }

    private void thenTheFirstIsGreater() {
        assertThat(this.comparison).isPositive();
    }

    private void thenTheFirstByteIs(byte expected) {
        assertThat(this.exception).isNull();
        assertThat(this.hash.get(0)).isEqualTo(expected);
    }

    private void thenExceptionOccurred(Class<? extends Throwable> type) {
        assertThat(this.exception).isInstanceOf(type);
    }
}
