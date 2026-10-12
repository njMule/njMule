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

import java.util.ArrayList;
import java.util.List;
import org.njmule.core.protocol.io.DecodeException;
import org.njmule.core.protocol.io.EmuleStrings;
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

/**
 * Reads and writes tags in the eD2K encodings ({@code Packets.cpp:443-710}) and the Kad encoding
 * ({@code kademlia/io/DataIO.cpp:142-345}). Unlike eMule, unknown types are rejected instead of
 * being skipped without consuming their data, which desynchronises the rest of the list.
 */
public final class TagCodec {

    /** Smallest encoded eD2K tag: {@code <type|0x80><id><u8 value>}. */
    public static final int MIN_ED2K_TAG_SIZE = 3;

    /** Smallest encoded Kad tag: {@code <type><u16 0><u8 value>}. */
    public static final int MIN_KAD_TAG_SIZE = 4;

    private TagCodec() {}

    public static Tag readEd2k(PacketReader in) throws DecodeException {
        int type = in.u8();
        TagName name;
        if ((type & TagType.NEW_ED2K_FLAG) != 0) {
            type &= ~TagType.NEW_ED2K_FLAG;
            name = TagName.id(in.u8());
        } else {
            int length = in.u16();
            name = length == 1 ? TagName.id(in.u8()) : TagName.ofBytes(in.bytes(length));
        }
        return switch (type) {
            case TagType.HASH -> new HashTag(name, in.hash16());
            case TagType.STRING -> new StringTag(name, in.string16());
            case TagType.UINT32 -> new IntTag(name, in.u32());
            case TagType.UINT64 -> new IntTag(name, in.u64());
            case TagType.UINT16 -> new IntTag(name, in.u16());
            case TagType.UINT8 -> new IntTag(name, in.u8());
            case TagType.FLOAT32 -> new FloatTag(name, in.f32());
            case TagType.BOOL -> new BoolTag(name, in.u8() != 0);
            case TagType.BOOLARRAY -> {
                int bits = in.u16();
                yield new BoolArrayTag(name, bits, Bytes.of(in.bytes(bits / 8 + 1)));
            }
            case TagType.BLOB -> {
                long length = in.u32();
                if (length > in.remaining()) {
                    throw new DecodeException("blob of " + length + " bytes in tag " + name);
                }
                yield new BlobTag(name, Bytes.of(in.bytes((int) length)));
            }
            case TagType.BSOB -> new BsobTag(name, Bytes.of(in.bytes(in.u8())));
            default -> {
                if (type >= TagType.STR1 && type <= TagType.STR16) {
                    yield new StringTag(
                            name, EmuleStrings.decode(in.bytes(type - TagType.STR1 + 1)));
                }
                throw new DecodeException(
                        String.format("unknown tag type 0x%02X for tag %s", type, name));
            }
        };
    }

    /** Reads {@code count} eD2K tags, after checking that they can fit in the remaining data. */
    public static List<Tag> readEd2kList(PacketReader in, long count, int maxCount)
            throws DecodeException {
        int n = in.count(count, MIN_ED2K_TAG_SIZE, maxCount);
        List<Tag> tags = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            tags.add(readEd2k(in));
        }
        return List.copyOf(tags);
    }

    public static void writeEd2k(
            PacketWriter out, Tag tag, Ed2kTagFormat format, StringEncoding encoding) {
        byte[] string = null;
        int type =
                switch (tag) {
                    case IntTag t ->
                            format == Ed2kTagFormat.NEW
                                    ? smallestIntType(t.value())
                                    : (fits32(t.value()) ? TagType.UINT32 : TagType.UINT64);
                    case StringTag t -> {
                        string = EmuleStrings.encode(t.value(), encoding);
                        yield format == Ed2kTagFormat.NEW
                                        && string.length >= 1
                                        && string.length <= 16
                                ? TagType.STR1 + string.length - 1
                                : TagType.STRING;
                    }
                    case HashTag t -> TagType.HASH;
                    case FloatTag t -> TagType.FLOAT32;
                    case BoolTag t -> TagType.BOOL;
                    case BoolArrayTag t -> TagType.BOOLARRAY;
                    case BlobTag t -> TagType.BLOB;
                    case BsobTag t ->
                            throw new IllegalArgumentException(
                                    "eMule cannot read BSOB in eD2K tags: " + t.name());
                };

        TagName name = tag.name();
        if (format == Ed2kTagFormat.NEW && name.isId()) {
            out.u8(type | TagType.NEW_ED2K_FLAG).u8(name.id());
        } else {
            byte[] raw = name.toBytes();
            out.u8(type).u16(raw.length).bytes(raw);
        }
        writeEd2kValue(out, tag, type, string);
    }

    public static void writeEd2kList(
            PacketWriter out, List<Tag> tags, Ed2kTagFormat format, StringEncoding encoding) {
        for (Tag tag : tags) {
            writeEd2k(out, tag, format, encoding);
        }
    }

    public static Tag readKad(PacketReader in) throws DecodeException {
        int type = in.u8();
        TagName name = TagName.ofBytes(in.bytes(in.u16()));
        return switch (type) {
            case TagType.HASH -> new HashTag(name, in.hash16());
            case TagType.STRING -> new StringTag(name, in.string16());
            case TagType.UINT64 -> new IntTag(name, in.u64());
            case TagType.UINT32 -> new IntTag(name, in.u32());
            case TagType.UINT16 -> new IntTag(name, in.u16());
            case TagType.UINT8 -> new IntTag(name, in.u8());
            case TagType.FLOAT32 -> new FloatTag(name, in.f32());
            case TagType.BSOB -> new BsobTag(name, Bytes.of(in.bytes(in.u8())));
            default ->
                    throw new DecodeException(
                            String.format(
                                    "unsupported Kad tag type 0x%02X for tag %s", type, name));
        };
    }

    /** A Kad tag list: {@code <u8 count><tags>}. */
    public static List<Tag> readKadList(PacketReader in) throws DecodeException {
        int n = in.count(in.u8(), MIN_KAD_TAG_SIZE, 0xFF);
        List<Tag> tags = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            tags.add(readKad(in));
        }
        return List.copyOf(tags);
    }

    public static void writeKad(PacketWriter out, Tag tag) {
        int type =
                switch (tag) {
                    case IntTag t -> smallestIntType(t.value());
                    case StringTag t -> TagType.STRING;
                    case HashTag t -> TagType.HASH;
                    case FloatTag t -> TagType.FLOAT32;
                    case BsobTag t -> TagType.BSOB;
                    case BoolTag t -> throw unsupportedInKad(t);
                    case BoolArrayTag t -> throw unsupportedInKad(t);
                    case BlobTag t -> throw unsupportedInKad(t);
                };
        byte[] raw = tag.name().toBytes();
        out.u8(type).u16(raw.length).bytes(raw);
        byte[] string =
                tag instanceof StringTag s
                        ? EmuleStrings.encode(s.value(), StringEncoding.UTF8)
                        : null;
        writeEd2kValue(out, tag, type, string);
    }

    public static void writeKadList(PacketWriter out, List<Tag> tags) {
        if (tags.size() > 0xFF) {
            throw new IllegalArgumentException("a Kad tag list holds at most 255 tags");
        }
        out.u8(tags.size());
        for (Tag tag : tags) {
            writeKad(out, tag);
        }
    }

    private static void writeEd2kValue(PacketWriter out, Tag tag, int type, byte[] string) {
        switch (tag) {
            case IntTag t -> {
                switch (type) {
                    case TagType.UINT8 -> out.u8((int) t.value());
                    case TagType.UINT16 -> out.u16((int) t.value());
                    case TagType.UINT32 -> out.u32(t.value());
                    default -> out.u64(t.value());
                }
            }
            case StringTag t -> {
                if (type == TagType.STRING) {
                    if (string.length > 0xFFFF) {
                        throw new IllegalArgumentException(
                                "string too long for tag " + t.name() + ": " + string.length);
                    }
                    out.u16(string.length);
                }
                out.bytes(string);
            }
            case HashTag t -> out.hash16(t.value());
            case FloatTag t -> out.f32(t.value());
            case BoolTag t -> out.u8(t.value() ? 1 : 0);
            case BoolArrayTag t -> {
                out.u16(t.bitCount());
                out.bytes(t.value().toByteArray());
            }
            case BlobTag t -> {
                out.u32(t.value().length());
                out.bytes(t.value().toByteArray());
            }
            case BsobTag t -> {
                out.u8(t.value().length());
                out.bytes(t.value().toByteArray());
            }
        }
    }

    private static int smallestIntType(long value) {
        if ((value & ~0xFFL) == 0) {
            return TagType.UINT8;
        }
        if ((value & ~0xFFFFL) == 0) {
            return TagType.UINT16;
        }
        return fits32(value) ? TagType.UINT32 : TagType.UINT64;
    }

    private static boolean fits32(long value) {
        return (value & 0xFFFF_FFFF_0000_0000L) == 0;
    }

    private static IllegalArgumentException unsupportedInKad(Tag tag) {
        return new IllegalArgumentException(
                "Kad tags cannot hold " + tag.getClass().getSimpleName() + ": " + tag.name());
    }
}
