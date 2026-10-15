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
package org.njmule.core.protocol.tag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.OptionalInt;
import java.util.stream.Stream;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.Size;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.njmule.core.api.Hash16;
import org.njmule.core.protocol.io.DecodeException;
import org.njmule.core.protocol.io.PacketReader;
import org.njmule.core.protocol.io.PacketWriter;
import org.njmule.core.protocol.io.StringEncoding;
import org.njmule.core.protocol.tag.Tag.BlobTag;
import org.njmule.core.protocol.tag.Tag.BoolArrayTag;
import org.njmule.core.protocol.tag.Tag.BoolTag;
import org.njmule.core.protocol.tag.Tag.BsobTag;
import org.njmule.core.protocol.tag.Tag.FloatTag;
import org.njmule.core.protocol.tag.Tag.HashTag;
import org.njmule.core.protocol.tag.Tag.IntTag;
import org.njmule.core.protocol.tag.Tag.StringTag;
import org.njmule.core.util.Bytes;

class TagCodecTest {

    private static final HexFormat HEX = HexFormat.of();

    private List<Tag> tags;
    private Tag tag;
    private byte[] bytes;
    private String written;
    private Tag read;
    private OptionalInt port;
    private PacketWriter out;
    private PacketReader in;
    private List<Tag> readTags;
    private Throwable exception;

    // Expected bytes follow the old and new eD2K tag encodings and the Kad tag encoding.

    @Test
    void shouldTakeAPortWithinItsRange() {
        givenTheIntTagsFromTheId1(4662, 0, 0x1_0000, 0xFFFF);

        whenThePortOfTheTagIsRead(1);

        thenThePortIs(4662);
    }

    @Test
    void shouldTakeTheHighestPort() {
        givenTheIntTagsFromTheId1(4662, 0, 0x1_0000, 0xFFFF);

        whenThePortOfTheTagIsRead(4);

        thenThePortIs(0xFFFF);
    }

    @Test
    void shouldNotTakeAPortOfZero() {
        givenTheIntTagsFromTheId1(4662, 0, 0x1_0000, 0xFFFF);

        whenThePortOfTheTagIsRead(2);

        thenThereIsNoPort();
    }

    @Test
    void shouldNotTakeAPortBeyondSixteenBits() {
        givenTheIntTagsFromTheId1(4662, 0, 0x1_0000, 0xFFFF);

        whenThePortOfTheTagIsRead(3);

        thenThereIsNoPort();
    }

    @Test
    void shouldTakeNoPortWithoutTheTag() {
        givenTheIntTagsFromTheId1(4662, 0, 0x1_0000, 0xFFFF);

        whenThePortOfTheTagIsRead(5);

        thenThereIsNoPort();
    }

    @ParameterizedTest
    @MethodSource("integersInTheNewEncoding")
    void shouldUseTheSmallestIntegerTypeInTheNewEncoding(long size, String expected) {
        givenTheTag(Tag.of(FileTag.FILESIZE, size));

        whenItIsWrittenInTheNewEd2kEncoding();

        thenTheBytesWrittenAre(expected);
    }

    @ParameterizedTest
    @MethodSource("stringsInTheNewEncoding")
    void shouldUseShortStringsInTheNewEncoding(String name, String expected) {
        givenTheTag(Tag.of(FileTag.FILENAME, name));

        whenItIsWrittenInTheNewEd2kEncoding();

        thenTheBytesWrittenAre(expected);
    }

    @ParameterizedTest
    @MethodSource("tagsInTheOldEncoding")
    void shouldWriteNamesWithTheirLengthAndWideIntegersInTheOldEncoding(Tag oldTag, String expected) {
        givenTheTag(oldTag);

        whenItIsWrittenInTheOldEd2kEncoding();

        thenTheBytesWrittenAre(expected);
    }

    @Test
    void shouldKeepTheLongFormForStringNamesInTheNewEncoding() {
        givenTheTag(new IntTag(TagName.of("users"), 7));

        whenItIsWrittenInTheNewEd2kEncoding();

        thenTheBytesWrittenAre("09050075736572730" + "7");
    }

    @Test
    void shouldAlwaysPrefixKadNamesWithTheirLength() {
        givenTheTag(Tag.of(FileTag.SOURCEPORT, 4662));

        whenItIsWrittenInTheKadEncoding();

        thenTheBytesWrittenAre("080100fd3612");
    }

    @ParameterizedTest
    @MethodSource("tagsInBothEd2kEncodings")
    void shouldReadBothEd2kEncodings(String hex, Tag expected) {
        givenTheBytes(hex);

        whenAnEd2kTagIsRead();

        thenTheTagReadIs(expected);
    }

    @Test
    void shouldReadTheOddByteCountOfABoolArray() {
        // 8 bits occupy 8/8+1 = 2 bytes on the wire.
        givenTheBytes("8601" + "0800" + "ff00");

        whenAnEd2kTagIsRead();

        thenTheTagReadIs(new BoolArrayTag(TagName.id(1), 8, Bytes.of(new byte[] {-1, 0})));
    }

    @Test
    void shouldReadABsobInEd2kTags() {
        givenTheBytes("8a01" + "02" + "abcd");

        whenAnEd2kTagIsRead();

        thenTheTagReadIs(new BsobTag(TagName.id(1), Bytes.of(HEX.parseHex("abcd"))));
    }

    @Test
    void shouldNeverWriteABsobInEd2kTags() {
        givenTheTag(new BsobTag(TagName.id(1), Bytes.EMPTY));

        whenItIsWrittenInTheNewEd2kEncoding();

        thenExceptionOccurred(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectAnUnknownType() {
        givenTheBytes("8c0100");

        whenAnEd2kTagIsRead();

        thenExceptionOccurred(DecodeException.class);
    }

    @Test
    void shouldRejectAnOversizedBlob() {
        givenTheBytes("8701" + "ffffff7f" + "00");

        whenAnEd2kTagIsRead();

        thenExceptionOccurred(DecodeException.class);
    }

    @Test
    void shouldRejectATypeKadDoesNotKnow() {
        givenTheBytes("0701000100");

        whenAKadTagIsRead();

        thenExceptionOccurred(DecodeException.class);
    }

    @Test
    void shouldCheckTheCountOfAListAgainstTheData() {
        givenTheBytes("890205");

        whenAnEd2kListIsRead(2, 100);

        thenExceptionOccurred(DecodeException.class);
    }

    @Property
    void shouldRoundTripTagsInTheNewEd2kEncoding(@ForAll("ed2kTags") Tag anyTag) throws DecodeException {
        givenTheTag(anyTag);

        whenItIsWrittenAndReadInTheEd2kEncoding(Ed2kTagFormat.NEW, StringEncoding.UTF8);

        thenTheTagReadIsTheWrittenOne();
    }

    @Property
    void shouldRoundTripTagsInTheOldEd2kEncoding(@ForAll("ed2kTags") Tag anyTag) throws DecodeException {
        givenTheTag(anyTag);

        whenItIsWrittenAndReadInTheEd2kEncoding(Ed2kTagFormat.OLD, StringEncoding.UTF8_BOM_IF_NEEDED);

        thenTheTagReadIsTheWrittenOne();
    }

    @Property
    void shouldRoundTripTagListsInTheKadEncoding(@ForAll("kadTags") List<Tag> anyTags) throws DecodeException {
        givenTheTags(anyTags);
        givenTheyAreWrittenAsAKadList();

        whenAKadListIsRead();

        thenTheTagsReadAreTheWrittenOnes();
        thenNothingIsLeftToRead();
    }

    @Property
    void shouldNeverLetGarbageEscapeAnEd2kListAsAnUncheckedException(@ForAll @Size(max = 64) byte[] data) {
        givenTheRawBytes(data);

        whenAnEd2kListIsRead(data.length, 64);

        thenItIsReadOrRefusedAsMalformed();
    }

    @Property
    void shouldNeverLetGarbageEscapeAKadListAsAnUncheckedException(@ForAll @Size(max = 64) byte[] data) {
        givenTheRawBytes(data);

        whenAKadListOfTheBytesIsRead();

        thenItIsReadOrRefusedAsMalformed();
    }

    private static Stream<Arguments> integersInTheNewEncoding() {
        return Stream.of(
                Arguments.of(5L, "890205"),
                Arguments.of(0x1234L, "88023412"),
                Arguments.of(0x12345678L, "830278563412"),
                Arguments.of(0x1_0000_0000L, "8b020000000001000000"));
    }

    private static Stream<Arguments> stringsInTheNewEncoding() {
        return Stream.of(
                Arguments.of("abc", "9301616263"),
                Arguments.of("a".repeat(17), "82011100" + "61".repeat(17)),
                Arguments.of("", "82010000"));
    }

    private static Stream<Arguments> tagsInTheOldEncoding() {
        return Stream.of(
                Arguments.of(Tag.of(FileTag.FILESIZE, 5), "0301000205000000"),
                Arguments.of(new IntTag(TagName.of(ServerTag.USERS), 10), "0305007573657273" + "0a000000"),
                Arguments.of(new IntTag(TagName.of("\u00090"), 0x1_0000_0000L), "0b02000930" + "0000000001000000"),
                Arguments.of(Tag.of(FileTag.FILENAME, "abc"), "020100010300616263"));
    }

    private static Stream<Arguments> tagsInBothEd2kEncodings() {
        return Stream.of(
                Arguments.of("0301000205000000", Tag.of(FileTag.FILESIZE, 5)),
                Arguments.of("88023412", Tag.of(FileTag.FILESIZE, 0x1234)),
                Arguments.of("9301616263", Tag.of(FileTag.FILENAME, "abc")));
    }

    private void givenTheIntTagsFromTheId1(long... values) {
        this.tags = new ArrayList<>();
        for (int i = 0; i < values.length; i++) {
            this.tags.add(new IntTag(TagName.id(i + 1), values[i]));
        }
    }

    private void givenTheTag(Tag value) {
        this.tag = value;
    }

    private void givenTheTags(List<Tag> values) {
        this.tags = values;
    }

    private void givenTheBytes(String hex) {
        this.bytes = HEX.parseHex(hex);
    }

    private void givenTheRawBytes(byte[] data) {
        this.bytes = data;
    }

    private void givenTheyAreWrittenAsAKadList() {
        this.out = new PacketWriter();
        TagCodec.writeKadList(this.out, this.tags);
    }

    private void whenThePortOfTheTagIsRead(int id) {
        this.port = Tags.portValue(this.tags, id);
    }

    private void whenItIsWrittenInTheNewEd2kEncoding() {
        this.exception = catchThrowable(() -> this.written = newEd2k(this.tag));
    }

    private void whenItIsWrittenInTheOldEd2kEncoding() {
        this.exception = catchThrowable(() -> this.written = oldEd2k(this.tag));
    }

    private void whenItIsWrittenInTheKadEncoding() {
        PacketWriter kad = new PacketWriter();
        TagCodec.writeKad(kad, this.tag);
        this.written = HEX.formatHex(kad.toByteArray());
    }

    private void whenAnEd2kTagIsRead() {
        this.exception = catchThrowable(() -> this.read = readEd2k(this.bytes));
    }

    private void whenAKadTagIsRead() {
        this.exception = catchThrowable(() -> TagCodec.readKad(PacketReader.of(this.bytes)));
    }

    private void whenAnEd2kListIsRead(long count, int maxCount) {
        byte[] data = this.bytes;
        this.exception = catchThrowable(() -> TagCodec.readEd2kList(PacketReader.of(data), count, maxCount));
    }

    private void whenAKadListOfTheBytesIsRead() {
        byte[] data = this.bytes;
        this.exception = catchThrowable(() -> TagCodec.readKadList(PacketReader.of(data)));
    }

    private void whenItIsWrittenAndReadInTheEd2kEncoding(Ed2kTagFormat format, StringEncoding encoding)
            throws DecodeException {
        this.read = roundTripEd2k(this.tag, format, encoding);
    }

    private void whenAKadListIsRead() throws DecodeException {
        this.in = PacketReader.of(this.out.toByteArray());
        this.readTags = TagCodec.readKadList(this.in);
    }

    private void thenThePortIs(int expected) {
        assertThat(this.port).hasValue(expected);
    }

    private void thenThereIsNoPort() {
        assertThat(this.port).isEmpty();
    }

    private void thenTheBytesWrittenAre(String hex) {
        assertThat(this.exception).isNull();
        assertThat(this.written).isEqualTo(hex);
    }

    private void thenTheTagReadIs(Tag expected) {
        assertThat(this.exception).isNull();
        assertThat(this.read).isEqualTo(expected);
    }

    private void thenTheTagReadIsTheWrittenOne() {
        assertThat(this.read).isEqualTo(this.tag);
    }

    private void thenTheTagsReadAreTheWrittenOnes() {
        assertThat(this.readTags).isEqualTo(this.tags);
    }

    private void thenNothingIsLeftToRead() {
        assertThatCode(this.in::expectEnd).doesNotThrowAnyException();
    }

    private void thenExceptionOccurred(Class<? extends Exception> type) {
        assertThat(this.exception).isInstanceOf(type);
    }

    private void thenItIsReadOrRefusedAsMalformed() {
        if (this.exception != null) {
            assertThat(this.exception).isInstanceOf(DecodeException.class);
        }
    }

    @Provide
    Arbitrary<Tag> ed2kTags() {
        Arbitrary<TagName> names = names();
        return Arbitraries.oneOf(
                Combinators.combine(names, Arbitraries.longs()).as(IntTag::new),
                Combinators.combine(names, strings()).as(StringTag::new),
                Combinators.combine(names, hashes()).as(HashTag::new),
                Combinators.combine(names, Arbitraries.floats()).as(FloatTag::new),
                Combinators.combine(names, Arbitraries.of(true, false)).as(BoolTag::new),
                Combinators.combine(names, Arbitraries.integers().between(0, 100))
                        .as((n, bits) -> new BoolArrayTag(n, bits, Bytes.of(new byte[bits / 8 + 1]))),
                Combinators.combine(names, bytes(300)).as(BlobTag::new));
    }

    @Provide
    Arbitrary<List<Tag>> kadTags() {
        Arbitrary<TagName> names = names();
        Arbitrary<Tag> tag = Arbitraries.oneOf(
                Combinators.combine(names, Arbitraries.longs()).as(IntTag::new),
                Combinators.combine(names, strings()).as(StringTag::new),
                Combinators.combine(names, hashes()).as(HashTag::new),
                Combinators.combine(names, Arbitraries.floats()).as(FloatTag::new),
                Combinators.combine(names, bytes(255)).as(BsobTag::new));
        return tag.list().ofMaxSize(8);
    }

    private static Arbitrary<TagName> names() {
        return Arbitraries.oneOf(
                Arbitraries.integers().between(0, 0xFF).map(TagName::id),
                Arbitraries.strings().withCharRange('\0', 'ÿ').ofMaxLength(12).map(TagName::of));
    }

    private static Arbitrary<String> strings() {
        // Unicode without surrogates or a leading BOM: every value has one UTF-8 form.
        return Arbitraries.strings().withCharRange('\u0001', '퟿').ofMaxLength(40);
    }

    private static Arbitrary<Hash16> hashes() {
        return Arbitraries.bytes().array(byte[].class).ofSize(16).map(Hash16::of);
    }

    private static Arbitrary<Bytes> bytes(int max) {
        return Arbitraries.bytes().array(byte[].class).ofMaxSize(max).map(Bytes::of);
    }

    private static Tag roundTripEd2k(Tag tag, Ed2kTagFormat format, StringEncoding encoding) throws DecodeException {
        PacketWriter out = new PacketWriter();
        TagCodec.writeEd2k(out, tag, format, encoding);
        PacketReader in = PacketReader.of(out.toByteArray());
        Tag read = TagCodec.readEd2k(in);
        in.expectEnd();
        return read;
    }

    private static String newEd2k(Tag tag) {
        PacketWriter out = new PacketWriter();
        TagCodec.writeEd2k(out, tag, Ed2kTagFormat.NEW, StringEncoding.UTF8);
        return HEX.formatHex(out.toByteArray());
    }

    private static String oldEd2k(Tag tag) {
        PacketWriter out = new PacketWriter();
        TagCodec.writeEd2k(out, tag, Ed2kTagFormat.OLD, StringEncoding.UTF8_BOM_IF_NEEDED);
        return HEX.formatHex(out.toByteArray());
    }

    private static Tag readEd2k(byte[] data) throws DecodeException {
        PacketReader in = PacketReader.of(data);
        Tag tag = TagCodec.readEd2k(in);
        in.expectEnd();
        return tag;
    }
}
