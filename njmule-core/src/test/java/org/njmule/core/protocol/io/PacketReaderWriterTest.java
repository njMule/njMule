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
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.util.HexFormat;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.njmule.core.api.Hash16;

class PacketReaderWriterTest {

    private static final HexFormat HEX = HexFormat.of();
    private static final Hash16 HASH = Hash16.parseHex("31D6CFE0D16AE931B73C59D7E0C089C0");

    private PacketWriter writer;
    private PacketReader reader;
    private PacketReader slice;
    private int length;
    private int count;
    private int readU8;
    private int readU16;
    private long readU32;
    private int readI32;
    private long readU64;
    private float readF32;
    private String string;
    private Hash16 hash;
    private Throwable exception;

    @Test
    void shouldWriteLittleEndian() {
        givenANewWriter();

        whenTheIntegersAreWritten(0xE3, 0x1234, 0x89ABCDEFL, 0x0102030405060708L);

        thenTheWriterHolds("e33412efcdab890807060504030201");
    }

    @Test
    void shouldReadLittleEndianAndUnsigned() throws DecodeException {
        givenAReaderOf("ff3412efcdab89ffffffffffffffff");

        whenTheUnsignedIntegersAreRead();

        thenTheIntegersReadAre(0xFF, 0x1234, 0x89ABCDEFL, -1L);
        thenNothingRemains();
    }

    @Test
    void shouldRefuseTruncatedBytesWithoutMovingOn() throws DecodeException {
        givenAReaderOf("ffff0102");
        givenALengthIsRead();

        whenThatManyBytesAreRead();

        thenExceptionOccurred(DecodeException.class, "need 65535 bytes");
        thenTheNextU16Is(0x0201);
    }

    @Test
    void shouldRefuseATruncatedU32WithoutMovingOn() throws DecodeException {
        givenAReaderOf("ffff0102");
        givenALengthIsRead();

        whenAU32IsRead();

        thenExceptionOccurred(DecodeException.class);
        thenTheNextU16Is(0x0201);
    }

    @Test
    void shouldAcceptACountThatFitsTheRemainingData() {
        givenAReaderOfZeroBytes(10);

        whenACountIsRead(5, 2, 100);

        thenTheCountIs(5);
    }

    @Test
    void shouldRefuseACountThatDoesNotFitTheRemainingData() {
        givenAReaderOfZeroBytes(10);

        whenACountIsRead(6, 2, 100);

        thenExceptionOccurred(DecodeException.class);
    }

    @Test
    void shouldRefuseACountAboveTheMaximum() {
        givenAReaderOfZeroBytes(10);

        whenACountIsRead(101, 0, 100);

        thenExceptionOccurred(DecodeException.class);
    }

    @Test
    void shouldAdvanceTheParentPastASlice() throws DecodeException {
        givenAReaderOf("0102030405");

        whenASliceIsTaken(3);

        thenNoExceptionOccurred();
        thenTheSliceHolds(1, 2, 3);
        thenTheNextU16Is(0x0504);
    }

    @Test
    void shouldRefuseASliceBeyondTheEnd() throws DecodeException {
        givenAReaderOf("0102030405");
        givenASliceIsTaken(3);
        givenTheNextU16IsRead();

        whenASliceIsTaken(1);

        thenExceptionOccurred(DecodeException.class);
    }

    @Test
    void shouldRefuseASliceOfNegativeLength() {
        givenAReaderOfZeroBytes(4);

        whenASliceIsTaken(-1);

        thenExceptionOccurred(DecodeException.class);
        thenThePositionIs(0);
    }

    @Test
    void shouldCheckTheLengthOfBytesBeforeAnyAllocation() {
        givenAReaderOfZeroBytes(8);

        whenBytesAreRead(Integer.MAX_VALUE);

        thenExceptionOccurred(DecodeException.class);
        thenThePositionIs(0);
    }

    @Test
    void shouldCheckTheLengthOfASkipBeforeMovingOn() {
        givenAReaderOfZeroBytes(8);

        whenBytesAreSkipped(Integer.MAX_VALUE);

        thenExceptionOccurred(DecodeException.class);
        thenThePositionIs(0);
    }

    @Test
    void shouldCheckTheLengthOfASliceBeforeMovingOn() {
        givenAReaderOfZeroBytes(8);

        whenASliceIsTaken(Integer.MAX_VALUE);

        thenExceptionOccurred(DecodeException.class);
        thenThePositionIs(0);
    }

    @Test
    void shouldRefuseANegativeCount() {
        givenAReaderOfZeroBytes(8);

        whenACountIsRead(-1, 1, 10);

        thenExceptionOccurred(DecodeException.class);
        thenThePositionIs(0);
    }

    @Test
    void shouldRefuseACountBeyondTheIntRange() {
        givenAReaderOfZeroBytes(8);

        whenACountIsRead(0xFFFF_FFFFL, 0, Integer.MAX_VALUE);

        thenExceptionOccurred(DecodeException.class);
        thenThePositionIs(0);
    }

    @Test
    void shouldAcceptACountOfItemsFillingTheRemainingData() {
        givenAReaderOfZeroBytes(8);

        whenACountIsRead(8, 1, 10);

        thenTheCountIs(8);
        thenThePositionIs(0);
    }

    @Test
    void shouldNotCheckTheFitOfACountWithoutItemSize() {
        givenAReaderOfZeroBytes(8);

        whenACountIsRead(1000, 0, 1000);

        thenTheCountIs(1000, "no item size, no fit check");
        thenThePositionIs(0);
    }

    @Test
    void shouldSliceTheWholeRemainingData() {
        givenAReaderOfZeroBytes(8);

        whenASliceIsTaken(8);

        thenNoExceptionOccurred();
        thenTheSliceHasRemaining(8);
    }

    @Test
    void shouldSliceNothingAtTheEnd() throws DecodeException {
        givenAReaderOfZeroBytes(8);
        givenASliceIsTaken(8);

        whenASliceIsTaken(0);

        thenNoExceptionOccurred();
        thenTheSliceIsEmpty();
        thenTheReaderIsAtItsEnd();
    }

    @Test
    void shouldRoundTripTheLongestString() {
        givenANewWriter();
        givenAStringIsWritten("a".repeat(0xFFFF));
        givenAReaderOfWhatWasWritten();

        whenAStringIsRead();

        thenNoExceptionOccurred();
        thenTheStringIs("a".repeat(0xFFFF));
    }

    @Test
    void shouldRefuseToWriteAStringLongerThanSixteenBits() {
        givenANewWriter();

        whenAStringIsWritten("a".repeat(0x10000));

        thenExceptionOccurred(IllegalArgumentException.class);
    }

    @Test
    void shouldCountTheLengthOfAStringInBytesNotCharacters() {
        givenANewWriter();

        whenAStringIsWritten("é".repeat(0x8000));

        thenExceptionOccurredBecause(IllegalArgumentException.class, "counted in bytes, not characters");
    }

    @Test
    void shouldRefuseAStringLongerThanTheData() {
        givenAReaderOf("ffff61");

        whenAStringIsRead();

        thenExceptionOccurred(DecodeException.class);
    }

    @Test
    void shouldPatchAU16InsideWhatWasWritten() {
        givenANewWriter();
        givenAU16IsWritten(0);
        givenAU32IsWritten(0);

        whenAU16IsPatchedAt(4, 0xBEEF);

        thenNoExceptionOccurred();
        thenTheWriterSizeIs(6);
    }

    @Test
    void shouldPatchAU32InsideWhatWasWritten() {
        givenANewWriter();
        givenAU16IsWritten(0);
        givenAU32IsWritten(0);

        whenAU32IsPatchedAt(2, 0xCAFEBABEL);

        thenNoExceptionOccurred();
        thenTheWriterSizeIs(6);
    }

    @Test
    void shouldPatchAU8InsideWhatWasWritten() {
        givenANewWriter();
        givenAU16IsWritten(0);
        givenAU32IsWritten(0);

        whenAU8IsPatchedAt(5, 1);

        thenNoExceptionOccurred();
        thenTheWriterSizeIs(6);
    }

    @Test
    void shouldRefuseToPatchAU16PastWhatWasWritten() {
        givenANewWriter();
        givenAU16IsWritten(0);
        givenAU32IsWritten(0);

        whenAU16IsPatchedAt(5, 1);

        thenExceptionOccurred(IndexOutOfBoundsException.class);
        thenTheWriterSizeIs(6);
    }

    @Test
    void shouldRefuseToPatchAU32BeforeTheStart() {
        givenANewWriter();
        givenAU16IsWritten(0);
        givenAU32IsWritten(0);

        whenAU32IsPatchedAt(-1, 1);

        thenExceptionOccurred(IndexOutOfBoundsException.class);
        thenTheWriterSizeIs(6);
    }

    @Test
    void shouldRefuseToPatchAU8PastWhatWasWritten() {
        givenANewWriter();
        givenAU16IsWritten(0);
        givenAU32IsWritten(0);

        whenAU8IsPatchedAt(6, 1);

        thenExceptionOccurred(IndexOutOfBoundsException.class);
        thenTheWriterSizeIs(6);
    }

    @Test
    void shouldWriteTheLargestCountsAndOffset() {
        givenANewWriter();

        whenACountAndAnOffsetAreWritten(255, 0xFFFF, 0xFFFF_FFFFL);

        thenNoExceptionOccurred();
        thenTheWrittenBytesHaveSize(7);
    }

    @ParameterizedTest
    @ValueSource(ints = {256, -1})
    void shouldRefuseACount8ThatDoesNotFitItsField(int value) {
        givenANewWriter();

        whenACount8IsWritten(value);

        thenExceptionOccurred(IllegalArgumentException.class);
    }

    @Test
    void shouldRefuseACount16ThatDoesNotFitItsField() {
        givenANewWriter();

        whenACount16IsWritten(0x10000);

        thenExceptionOccurred(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(longs = {0x1_0000_0000L, -1})
    void shouldRefuseAnOffset32ThatDoesNotFitItsField(long offset) {
        givenANewWriter();

        whenAnOffset32IsWritten(offset);

        thenExceptionOccurred(IllegalArgumentException.class);
    }

    @Test
    void shouldPatchACountWrittenEarlier() {
        givenANewWriter();
        givenAU32IsWritten(0);
        givenAU16IsWritten(7);

        whenAU32IsPatchedAt(0, 3);

        thenNoExceptionOccurred();
        thenTheWriterHolds("030000000700");
    }

    @Test
    void shouldRefuseToPatchAU32PastWhatWasWritten() {
        givenANewWriter();
        givenAU32IsWritten(0);
        givenAU16IsWritten(7);

        whenAU32IsPatchedAt(3, 1);

        thenExceptionOccurred(IndexOutOfBoundsException.class);
    }

    @Test
    void shouldEnforceTheSizeCap() {
        givenAWriterCappedAt(4);
        givenAU32IsWritten(1);

        whenAU8IsWritten(1);

        thenExceptionOccurred(IllegalStateException.class);
    }

    @Test
    void shouldRoundTripStringsAndHashes() throws DecodeException {
        givenANewWriter();
        givenAStringIsWritten("Ünïcødé");
        givenAHashIsWritten(HASH);
        givenAReaderOfWhatWasWritten();

        whenAStringAndAHashAreRead();

        thenTheStringIs("Ünïcødé");
        thenTheHashIs(HASH);
        thenTheReaderIsAtItsEnd();
    }

    @Property
    void shouldRoundTripIntegers(@ForAll byte a, @ForAll short b, @ForAll int c, @ForAll long d, @ForAll float e)
            throws DecodeException {
        givenANewWriter();
        givenTheValuesAreWritten(a, b, c, d, e);
        givenAReaderOfWhatWasWritten();

        whenTheValuesAreReadBack();

        thenTheValuesReadAre(a & 0xFF, b & 0xFFFF, c, d);
        thenTheFloatReadHasTheBitsOf(e);
    }

    private void givenANewWriter() {
        this.writer = new PacketWriter();
    }

    private void givenAWriterCappedAt(int maxSize) {
        this.writer = new PacketWriter(maxSize);
    }

    private void givenAU16IsWritten(int value) {
        this.writer.u16(value);
    }

    private void givenAU32IsWritten(long value) {
        this.writer.u32(value);
    }

    private void givenAStringIsWritten(String value) {
        this.writer.string16(value, StringEncoding.UTF8);
    }

    private void givenAHashIsWritten(Hash16 value) {
        this.writer.hash16(value);
    }

    private void givenTheValuesAreWritten(byte a, short b, int c, long d, float e) {
        this.writer.u8(a).u16(b).u32(c).u64(d).f32(e);
    }

    private void givenAReaderOf(String hex) {
        this.reader = PacketReader.of(HEX.parseHex(hex));
    }

    private void givenAReaderOfZeroBytes(int size) {
        this.reader = PacketReader.of(new byte[size]);
    }

    private void givenAReaderOfWhatWasWritten() {
        this.reader = PacketReader.of(this.writer.toByteArray());
    }

    private void givenALengthIsRead() throws DecodeException {
        this.length = this.reader.u16();
    }

    private void givenASliceIsTaken(int sliceLength) throws DecodeException {
        this.slice = this.reader.slice(sliceLength);
    }

    private void givenTheNextU16IsRead() throws DecodeException {
        this.reader.u16();
    }

    private void whenTheIntegersAreWritten(int u8, int u16, long u32, long u64) {
        this.writer.u8(u8).u16(u16).u32(u32).u64(u64);
    }

    private void whenTheUnsignedIntegersAreRead() throws DecodeException {
        this.readU8 = this.reader.u8();
        this.readU16 = this.reader.u16();
        this.readU32 = this.reader.u32();
        this.readU64 = this.reader.u64();
    }

    private void whenTheValuesAreReadBack() throws DecodeException {
        this.readU8 = this.reader.u8();
        this.readU16 = this.reader.u16();
        this.readI32 = this.reader.i32();
        this.readU64 = this.reader.u64();
        this.readF32 = this.reader.f32();
    }

    private void whenThatManyBytesAreRead() {
        this.exception = catchThrowable(() -> this.reader.bytes(this.length));
    }

    private void whenAU32IsRead() {
        this.exception = catchThrowable(() -> this.reader.u32());
    }

    private void whenACountIsRead(long announced, int minItemSize, int maxCount) {
        this.exception = catchThrowable(() -> this.count = this.reader.count(announced, minItemSize, maxCount));
    }

    private void whenASliceIsTaken(int sliceLength) {
        this.exception = catchThrowable(() -> this.slice = this.reader.slice(sliceLength));
    }

    private void whenBytesAreRead(int byteCount) {
        this.exception = catchThrowable(() -> this.reader.bytes(byteCount));
    }

    private void whenBytesAreSkipped(int byteCount) {
        this.exception = catchThrowable(() -> this.reader.skip(byteCount));
    }

    private void whenAStringIsRead() {
        this.exception = catchThrowable(() -> this.string = this.reader.string16());
    }

    private void whenAStringIsWritten(String value) {
        this.exception = catchThrowable(() -> this.writer.string16(value, StringEncoding.UTF8));
    }

    private void whenAStringAndAHashAreRead() throws DecodeException {
        this.string = this.reader.string16();
        this.hash = this.reader.hash16();
    }

    private void whenAU8IsWritten(int value) {
        this.exception = catchThrowable(() -> this.writer.u8(value));
    }

    private void whenAU8IsPatchedAt(int position, int value) {
        this.exception = catchThrowable(() -> this.writer.setU8(position, value));
    }

    private void whenAU16IsPatchedAt(int position, int value) {
        this.exception = catchThrowable(() -> this.writer.setU16(position, value));
    }

    private void whenAU32IsPatchedAt(int position, long value) {
        this.exception = catchThrowable(() -> this.writer.setU32(position, value));
    }

    private void whenACountAndAnOffsetAreWritten(int count8, int count16, long offset32) {
        this.exception =
                catchThrowable(() -> this.writer.count8(count8).count16(count16).offset32(offset32));
    }

    private void whenACount8IsWritten(int value) {
        this.exception = catchThrowable(() -> this.writer.count8(value));
    }

    private void whenACount16IsWritten(int value) {
        this.exception = catchThrowable(() -> this.writer.count16(value));
    }

    private void whenAnOffset32IsWritten(long offset) {
        this.exception = catchThrowable(() -> this.writer.offset32(offset));
    }

    private void thenNoExceptionOccurred() {
        assertThat(this.exception).isNull();
    }

    private void thenExceptionOccurred(Class<? extends Exception> type) {
        assertThat(this.exception).isInstanceOf(type);
    }

    private void thenExceptionOccurred(Class<? extends Exception> type, String messagePart) {
        assertThat(this.exception).isInstanceOf(type).hasMessageContaining(messagePart);
    }

    private void thenExceptionOccurredBecause(Class<? extends Exception> type, String description) {
        assertThat(this.exception).as(description).isInstanceOf(type);
    }

    private void thenTheWriterHolds(String hex) {
        assertThat(HEX.formatHex(this.writer.toByteArray())).isEqualTo(hex);
    }

    private void thenTheWriterSizeIs(int size) {
        assertThat(this.writer.size()).isEqualTo(size);
    }

    private void thenTheWrittenBytesHaveSize(int size) {
        assertThat(this.writer.toByteArray()).hasSize(size);
    }

    private void thenTheIntegersReadAre(int u8, int u16, long u32, long u64) {
        assertThat(this.readU8).isEqualTo(u8);
        assertThat(this.readU16).isEqualTo(u16);
        assertThat(this.readU32).isEqualTo(u32);
        assertThat(this.readU64).isEqualTo(u64);
    }

    private void thenTheValuesReadAre(int u8, int u16, int i32, long u64) {
        assertThat(this.readU8).isEqualTo(u8);
        assertThat(this.readU16).isEqualTo(u16);
        assertThat(this.readI32).isEqualTo(i32);
        assertThat(this.readU64).isEqualTo(u64);
    }

    private void thenTheFloatReadHasTheBitsOf(float value) {
        assertThat(Float.floatToRawIntBits(this.readF32)).isEqualTo(Float.floatToRawIntBits(value));
    }

    private void thenNothingRemains() {
        assertThat(this.reader.hasRemaining()).isFalse();
    }

    private void thenTheNextU16Is(int value) throws DecodeException {
        assertThat(this.reader.u16()).isEqualTo(value);
    }

    private void thenTheCountIs(int expected) {
        assertThat(this.exception).isNull();
        assertThat(this.count).isEqualTo(expected);
    }

    private void thenTheCountIs(int expected, String description) {
        assertThat(this.exception).isNull();
        assertThat(this.count).as(description).isEqualTo(expected);
    }

    private void thenThePositionIs(int position) {
        assertThat(this.reader.position()).isEqualTo(position);
    }

    private void thenTheSliceHolds(int... bytes) {
        assertThat(this.slice.rest()).containsExactly(bytes);
    }

    private void thenTheSliceHasRemaining(int remaining) {
        assertThat(this.slice.remaining()).isEqualTo(remaining);
    }

    private void thenTheSliceIsEmpty() {
        assertThat(this.slice.hasRemaining()).isFalse();
    }

    private void thenTheReaderIsAtItsEnd() {
        assertThatCode(this.reader::expectEnd).doesNotThrowAnyException();
    }

    private void thenTheStringIs(String expected) {
        assertThat(this.string).isEqualTo(expected);
    }

    private void thenTheHashIs(Hash16 expected) {
        assertThat(this.hash).isEqualTo(expected);
    }
}
