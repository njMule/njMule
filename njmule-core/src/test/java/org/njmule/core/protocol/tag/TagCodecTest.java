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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HexFormat;
import java.util.List;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.Size;
import org.junit.jupiter.api.Test;
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

    // Expected bytes follow the old and new eD2K tag encodings and the Kad tag encoding.

    @Test
    void newEncodingUsesTheSmallestIntegerType() {
        assertThat(newEd2k(Tag.of(FileTag.FILESIZE, 5))).isEqualTo("890205");
        assertThat(newEd2k(Tag.of(FileTag.FILESIZE, 0x1234))).isEqualTo("88023412");
        assertThat(newEd2k(Tag.of(FileTag.FILESIZE, 0x12345678))).isEqualTo("830278563412");
        assertThat(newEd2k(Tag.of(FileTag.FILESIZE, 0x1_0000_0000L)))
                .isEqualTo("8b020000000001000000");
    }

    @Test
    void newEncodingUsesShortStrings() {
        assertThat(newEd2k(Tag.of(FileTag.FILENAME, "abc"))).isEqualTo("9301616263");
        assertThat(newEd2k(Tag.of(FileTag.FILENAME, "a".repeat(17))))
                .isEqualTo("82011100" + "61".repeat(17));
        assertThat(newEd2k(Tag.of(FileTag.FILENAME, ""))).isEqualTo("82010000");
    }

    @Test
    void oldEncodingWritesNamesWithLengthAndWideIntegers() {
        assertThat(oldEd2k(Tag.of(FileTag.FILESIZE, 5))).isEqualTo("0301000205000000");
        assertThat(oldEd2k(new IntTag(TagName.of(ServerTag.USERS), 10)))
                .isEqualTo("0305007573657273" + "0a000000");
        assertThat(oldEd2k(new IntTag(TagName.of("\u00090"), 0x1_0000_0000L)))
                .isEqualTo("0b02000930" + "0000000001000000");
        assertThat(oldEd2k(Tag.of(FileTag.FILENAME, "abc"))).isEqualTo("020100010300616263");
    }

    @Test
    void stringNamesInTheNewEncodingKeepTheLongForm() {
        assertThat(newEd2k(new IntTag(TagName.of("users"), 7)))
                .isEqualTo("09050075736572730" + "7");
    }

    @Test
    void kadNamesAreAlwaysLengthPrefixed() {
        PacketWriter out = new PacketWriter();
        TagCodec.writeKad(out, Tag.of(FileTag.SOURCEPORT, 4662));

        assertThat(HEX.formatHex(out.toByteArray())).isEqualTo("080100fd3612");
    }

    @Test
    void readsBothEd2kEncodings() throws DecodeException {
        assertThat(readEd2k("0301000205000000")).isEqualTo(Tag.of(FileTag.FILESIZE, 5));
        assertThat(readEd2k("88023412")).isEqualTo(Tag.of(FileTag.FILESIZE, 0x1234));
        assertThat(readEd2k("9301616263")).isEqualTo(Tag.of(FileTag.FILENAME, "abc"));
    }

    @Test
    void boolArraysUseTheOddByteCount() throws DecodeException {
        // 8 bits occupy 8/8+1 = 2 bytes on the wire.
        assertThat(readEd2k("8601" + "0800" + "ff00"))
                .isEqualTo(new BoolArrayTag(TagName.id(1), 8, Bytes.of(new byte[] {-1, 0})));
    }

    @Test
    void bsobIsReadInEd2kTagsButNeverWritten() throws DecodeException {
        assertThat(readEd2k("8a01" + "02" + "abcd"))
                .isEqualTo(new BsobTag(TagName.id(1), Bytes.of(HEX.parseHex("abcd"))));
        assertThatThrownBy(() -> newEd2k(new BsobTag(TagName.id(1), Bytes.EMPTY)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsUnknownTypesAndOversizedBlobs() {
        assertThatThrownBy(() -> readEd2k("8c0100")).isInstanceOf(DecodeException.class);
        assertThatThrownBy(() -> readEd2k("8701" + "ffffff7f" + "00"))
                .isInstanceOf(DecodeException.class);
        assertThatThrownBy(() -> TagCodec.readKad(PacketReader.of(HEX.parseHex("0701000100"))))
                .isInstanceOf(DecodeException.class);
    }

    @Test
    void listsCheckTheirCountAgainstTheData() {
        PacketReader in = PacketReader.of(HEX.parseHex("890205"));

        assertThatThrownBy(() -> TagCodec.readEd2kList(in, 2, 100))
                .isInstanceOf(DecodeException.class);
    }

    @Property
    void newEd2kRoundTrips(@ForAll("ed2kTags") Tag tag) throws DecodeException {
        assertThat(roundTripEd2k(tag, Ed2kTagFormat.NEW, StringEncoding.UTF8)).isEqualTo(tag);
    }

    @Property
    void oldEd2kRoundTrips(@ForAll("ed2kTags") Tag tag) throws DecodeException {
        assertThat(roundTripEd2k(tag, Ed2kTagFormat.OLD, StringEncoding.UTF8_BOM_IF_NEEDED))
                .isEqualTo(tag);
    }

    @Property
    void kadRoundTrips(@ForAll("kadTags") List<Tag> tags) throws DecodeException {
        PacketWriter out = new PacketWriter();
        TagCodec.writeKadList(out, tags);
        PacketReader in = PacketReader.of(out.toByteArray());

        assertThat(TagCodec.readKadList(in)).isEqualTo(tags);
        in.expectEnd();
    }

    @Property
    void garbageNeverEscapesAsAnUncheckedException(@ForAll @Size(max = 64) byte[] data) {
        try {
            TagCodec.readEd2kList(PacketReader.of(data), data.length, 64);
        } catch (DecodeException expected) {
            // the only acceptable failure
        }
        try {
            TagCodec.readKadList(PacketReader.of(data));
        } catch (DecodeException expected) {
            // the only acceptable failure
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
                        .as(
                                (n, bits) ->
                                        new BoolArrayTag(
                                                n, bits, Bytes.of(new byte[bits / 8 + 1]))),
                Combinators.combine(names, bytes(300)).as(BlobTag::new));
    }

    @Provide
    Arbitrary<List<Tag>> kadTags() {
        Arbitrary<TagName> names = names();
        Arbitrary<Tag> tag =
                Arbitraries.oneOf(
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

    private static Tag roundTripEd2k(Tag tag, Ed2kTagFormat format, StringEncoding encoding)
            throws DecodeException {
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

    private static Tag readEd2k(String hex) throws DecodeException {
        PacketReader in = PacketReader.of(HEX.parseHex(hex));
        Tag tag = TagCodec.readEd2k(in);
        in.expectEnd();
        return tag;
    }
}
