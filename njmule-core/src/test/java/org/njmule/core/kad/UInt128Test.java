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
package org.njmule.core.kad;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigInteger;
import java.util.HexFormat;
import java.util.stream.Stream;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.njmule.core.protocol.io.DecodeException;

class UInt128Test {

    private static final UInt128 SAMPLE = UInt128.parseHex("00112233445566778899AABBCCDDEEFF");

    private UInt128 id;
    private UInt128 other;
    private UInt128 third;
    private byte[] bytes;
    private UInt128 result;
    private UInt128 reverse;
    private int comparison;
    private int bit;
    private int[] bits;
    private long top32;
    private String hex;
    private Exception exception;

    @Test
    void shouldWriteEachWordLittleEndianMostSignificantFirstOnTheWire() {
        givenTheId(SAMPLE);

        whenItIsWrittenForTheWire();

        thenTheBytesAre("33221100" + "77665544" + "bbaa9988" + "ffeeddcc");
    }

    @Test
    void shouldReadAnIdBackFromItsWireBytes() {
        givenTheWireBytesOf(SAMPLE);

        whenTheBytesAreReadFromTheWire();

        thenTheIdReadIs(SAMPLE);
    }

    @Test
    void shouldWriteTheIdBigEndian() {
        givenTheId(SAMPLE);

        whenItIsWrittenBigEndian();

        thenTheBytesAre("00112233445566778899aabbccddeeff");
    }

    @Test
    void shouldRefuseWireBytesOfTheWrongLength() {
        givenTheBytes(new byte[15]);

        whenTheBytesAreReadFromTheWire();

        thenExceptionOccurred(DecodeException.class);
    }

    @Test
    void shouldRefuseBigEndianBytesOfTheWrongLength() {
        givenTheBytes(new byte[17]);

        whenTheBytesAreReadBigEndian();

        thenExceptionOccurred(IllegalArgumentException.class);
    }

    @Test
    void shouldWriteTheHexInUpperCaseMostSignificantFirst() {
        givenTheId(new UInt128(0x0011_2233_4455_6677L, 0x8899_AABB_CCDD_EEFFL));

        whenItIsWrittenAsHex();

        thenTheHexIs("00112233445566778899AABBCCDDEEFF");
    }

    @Test
    void shouldRefuseHexWithASign() {
        whenTheHexIsParsed("+0112233445566778899AABBCCDDEEFF");

        thenExceptionOccurred(IllegalArgumentException.class);
    }

    @Test
    void shouldRefuseHexOfTheWrongLength() {
        whenTheHexIsParsed("00");

        thenExceptionOccurred(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @MethodSource("unsignedOrders")
    void shouldOrderIdsAsUnsignedNumbers(UInt128 greater, UInt128 smaller) {
        givenTheIds(greater, smaller);

        whenTheyAreCompared();

        thenTheFirstIsGreater();
    }

    @ParameterizedTest
    @MethodSource("bitsFromTheMostSignificant")
    void shouldNumberTheBitsFromTheMostSignificant(UInt128 value, int index, int expected) {
        givenTheId(value);

        whenTheBitIsRead(index);

        thenTheBitIs(expected);
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 128})
    void shouldRefuseABitOutsideTheId(int index) {
        givenTheId(new UInt128(-1, -1));

        whenTheBitIsRead(index);

        thenExceptionOccurred(IndexOutOfBoundsException.class);
    }

    @Test
    void shouldTakeTheTop32BitsAsAnUnsignedNumber() {
        givenTheId(new UInt128(-1, -1));

        whenTheTop32BitsAreTaken();

        thenTheTop32BitsAre(0xFFFF_FFFFL);
    }

    @Property
    void shouldMeasureNoDistanceFromAnIdToItself(@ForAll long a1, @ForAll long a2) {
        givenTheId(new UInt128(a1, a2));

        whenTheDistanceToItselfIsMeasured();

        thenTheDistanceIsZero();
    }

    @Property
    void shouldMeasureTheSameDistanceBothWays(@ForAll long a1, @ForAll long a2, @ForAll long b1, @ForAll long b2) {
        givenTheIds(new UInt128(a1, a2), new UInt128(b1, b2));

        whenTheDistanceIsMeasuredBothWays();

        thenBothWaysGiveTheSameDistance();
    }

    @Property
    void shouldCombineTheDistancesThroughAMiddleIdIntoTheDirectOne(
            @ForAll long a1, @ForAll long a2, @ForAll long b1, @ForAll long b2, @ForAll long c1) {
        givenTheIds(new UInt128(a1, a2), new UInt128(b1, b2));
        givenTheThirdId(new UInt128(c1, a2 ^ b2));

        whenTheDistancesThroughTheMiddleIdAreCombined();

        thenTheCombinedDistanceIsTheOneFromTheFirstToTheThird();
    }

    @Property
    void shouldOrderIdsAsBigIntegerDoes(@ForAll long a1, @ForAll long a2, @ForAll long b1, @ForAll long b2) {
        givenTheIds(new UInt128(a1, a2), new UInt128(b1, b2));

        whenTheyAreCompared();

        thenTheOrderAgreesWithBigInteger();
    }

    @Property
    void shouldNumberTheBitsAsBigIntegerDoesFromTheTop(@ForAll long a1, @ForAll long a2) {
        givenTheId(new UInt128(a1, a2));

        whenAllBitsAreRead();

        thenTheBitsAgreeWithBigInteger();
    }

    @Property
    void shouldRoundTripThroughHex(@ForAll long high, @ForAll long low) {
        givenTheId(new UInt128(high, low));

        whenItGoesThroughHex();

        thenTheSameIdComesBack();
    }

    @Property
    void shouldRoundTripThroughTheWire(@ForAll long high, @ForAll long low) throws DecodeException {
        givenTheId(new UInt128(high, low));

        whenItGoesThroughTheWire();

        thenTheSameIdComesBack();
    }

    @Property
    void shouldRoundTripThroughBigEndianBytes(@ForAll long high, @ForAll long low) {
        givenTheId(new UInt128(high, low));

        whenItGoesThroughBigEndianBytes();

        thenTheSameIdComesBack();
    }

    @Property
    void shouldRoundTripThroughAHash(@ForAll long high, @ForAll long low) {
        givenTheId(new UInt128(high, low));

        whenItGoesThroughAHash();

        thenTheSameIdComesBack();
    }

    @Property
    void shouldTakeTheFirst32BitsAsTheTopBits(@ForAll long high, @ForAll @IntRange(min = 0, max = 31) int index) {
        givenTheId(new UInt128(high, 0));

        whenTheTop32BitsAreTaken();

        thenBitOfTheTop32IsTheSameBitOfTheId(index);
    }

    private void givenTheId(UInt128 value) {
        this.id = value;
    }

    private void givenTheIds(UInt128 first, UInt128 second) {
        this.id = first;
        this.other = second;
    }

    private void givenTheThirdId(UInt128 value) {
        this.third = value;
    }

    private void givenTheBytes(byte[] value) {
        this.bytes = value;
    }

    private void givenTheWireBytesOf(UInt128 value) {
        this.bytes = value.toWireBytes();
    }

    private void whenItIsWrittenForTheWire() {
        this.bytes = this.id.toWireBytes();
    }

    private void whenItIsWrittenBigEndian() {
        this.bytes = this.id.toBigEndian();
    }

    private void whenTheBytesAreReadFromTheWire() {
        try {
            this.result = UInt128.fromWireBytes(this.bytes);
        } catch (DecodeException | RuntimeException e) {
            this.exception = e;
        }
    }

    private void whenTheBytesAreReadBigEndian() {
        try {
            this.result = UInt128.fromBigEndian(this.bytes);
        } catch (RuntimeException e) {
            this.exception = e;
        }
    }

    private void whenTheHexIsParsed(String hex) {
        try {
            this.result = UInt128.parseHex(hex);
        } catch (RuntimeException e) {
            this.exception = e;
        }
    }

    private void whenItIsWrittenAsHex() {
        this.hex = this.id.toHex();
    }

    private void whenTheyAreCompared() {
        this.comparison = this.id.compareTo(this.other);
    }

    private void whenTheBitIsRead(int index) {
        try {
            this.bit = this.id.bit(index);
        } catch (RuntimeException e) {
            this.exception = e;
        }
    }

    private void whenAllBitsAreRead() {
        this.bits = new int[UInt128.BITS];
        for (int i = 0; i < UInt128.BITS; i++) {
            this.bits[i] = this.id.bit(i);
        }
    }

    private void whenTheTop32BitsAreTaken() {
        this.top32 = this.id.top32();
    }

    private void whenTheDistanceToItselfIsMeasured() {
        this.result = this.id.xor(this.id);
    }

    private void whenTheDistanceIsMeasuredBothWays() {
        this.result = this.id.xor(this.other);
        this.reverse = this.other.xor(this.id);
    }

    private void whenTheDistancesThroughTheMiddleIdAreCombined() {
        this.result = this.id.xor(this.other).xor(this.other.xor(this.third));
    }

    private void whenItGoesThroughHex() {
        this.result = UInt128.parseHex(this.id.toHex());
    }

    private void whenItGoesThroughTheWire() throws DecodeException {
        this.result = UInt128.fromWireBytes(this.id.toWireBytes());
    }

    private void whenItGoesThroughBigEndianBytes() {
        this.result = UInt128.fromBigEndian(this.id.toBigEndian());
    }

    private void whenItGoesThroughAHash() {
        this.result = UInt128.of(this.id.toHash16());
    }

    private void thenTheHexIs(String expected) {
        assertThat(this.hex).isEqualTo(expected);
    }

    private void thenTheBytesAre(String hex) {
        assertThat(HexFormat.of().formatHex(this.bytes)).isEqualTo(hex);
    }

    private void thenTheIdReadIs(UInt128 expected) {
        assertThat(this.result).isEqualTo(expected);
    }

    private void thenTheSameIdComesBack() {
        assertThat(this.result).isEqualTo(this.id);
    }

    private void thenTheFirstIsGreater() {
        assertThat(this.comparison).isPositive();
    }

    private void thenTheBitIs(int expected) {
        assertThat(this.bit).isEqualTo(expected);
    }

    private void thenTheTop32BitsAre(long expected) {
        assertThat(this.top32).isEqualTo(expected);
    }

    private void thenTheDistanceIsZero() {
        assertThat(this.result.isZero()).isTrue();
    }

    private void thenBothWaysGiveTheSameDistance() {
        assertThat(this.result).isEqualTo(this.reverse);
    }

    private void thenTheCombinedDistanceIsTheOneFromTheFirstToTheThird() {
        assertThat(this.result).isEqualTo(this.id.xor(this.third));
    }

    private void thenTheOrderAgreesWithBigInteger() {
        assertThat(Integer.signum(this.comparison))
                .isEqualTo(Integer.signum(big(this.id).compareTo(big(this.other))));
    }

    private void thenTheBitsAgreeWithBigInteger() {
        for (int i = 0; i < UInt128.BITS; i++) {
            assertThat(this.bits[i] == 1).isEqualTo(big(this.id).testBit(UInt128.BITS - 1 - i));
        }
    }

    private void thenBitOfTheTop32IsTheSameBitOfTheId(int index) {
        assertThat(this.top32 >>> (31 - index) & 1).isEqualTo((long) this.id.bit(index));
    }

    private void thenExceptionOccurred(Class<? extends Exception> type) {
        assertThat(this.exception).isInstanceOf(type);
    }

    private static Stream<Arguments> unsignedOrders() {
        UInt128 top = new UInt128(Long.MIN_VALUE, 0);
        return Stream.of(
                Arguments.of(top, new UInt128(Long.MAX_VALUE, -1)),
                Arguments.of(new UInt128(0, Long.MIN_VALUE), new UInt128(0, Long.MAX_VALUE)),
                Arguments.of(new UInt128(-1, -1), top));
    }

    private static Stream<Arguments> bitsFromTheMostSignificant() {
        UInt128 lowSign = new UInt128(0, Long.MIN_VALUE);
        return Stream.of(
                Arguments.of(new UInt128(Long.MIN_VALUE, 0), 0, 1),
                Arguments.of(lowSign, 64, 1),
                Arguments.of(lowSign, 63, 0),
                Arguments.of(new UInt128(-1, -1), 127, 1));
    }

    private static BigInteger big(UInt128 value) {
        return new BigInteger(1, value.toBigEndian());
    }
}
