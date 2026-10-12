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
package org.njmule.core.persist.format;

import java.util.ArrayList;
import java.util.List;
import org.njmule.core.api.Hash16;
import org.njmule.core.files.hash.Ed2k;
import org.njmule.core.protocol.io.DecodeException;
import org.njmule.core.protocol.io.PacketReader;
import org.njmule.core.protocol.io.PacketWriter;
import org.njmule.core.protocol.io.StringEncoding;
import org.njmule.core.protocol.tag.Ed2kTagFormat;
import org.njmule.core.protocol.tag.Tag;
import org.njmule.core.protocol.tag.TagCodec;

/** Pieces shared by the .met codecs. */
final class MetCodecs {

    /** Part hashes of the largest supported file, plus the empty part. */
    static final int MAX_PART_HASHES = (int) (Ed2k.MAX_FILE_SIZE / Ed2k.PARTSIZE + 1);

    /** Tags per record; part files carry two per gap, so this is generous. */
    static final int MAX_TAGS = 1_000_000;

    private MetCodecs() {}

    /** A file hash with its part hashes, {@code <hash 16><u16 count><hash 16 × count>}. */
    record HashSet(Hash16 fileHash, List<Hash16> partHashes) {}

    static HashSet readHashSet(PacketReader in) throws DecodeException {
        Hash16 fileHash = in.hash16();
        int count = in.count(in.u16(), Hash16.LENGTH, MAX_PART_HASHES);
        List<Hash16> parts = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            parts.add(in.hash16());
        }
        return new HashSet(fileHash, List.copyOf(parts));
    }

    static void writeHashSet(PacketWriter out, Hash16 fileHash, List<Hash16> partHashes) {
        if (partHashes.size() > MAX_PART_HASHES) {
            throw new IllegalArgumentException("too many part hashes: " + partHashes.size());
        }
        out.hash16(fileHash).u16(partHashes.size());
        for (Hash16 h : partHashes) {
            out.hash16(h);
        }
    }

    /** {@code <u32 count><old-encoding tags>}. */
    static List<Tag> readTags(PacketReader in) throws DecodeException {
        return TagCodec.readEd2kList(in, in.u32(), MAX_TAGS);
    }

    static void writeTags(PacketWriter out, List<Tag> tags) {
        out.u32(tags.size());
        TagCodec.writeEd2kList(out, tags, Ed2kTagFormat.OLD, StringEncoding.UTF8_BOM_IF_NEEDED);
    }

    static int header(PacketReader in, String file, int... accepted) throws DecodeException {
        int header = in.u8();
        for (int a : accepted) {
            if (header == a) {
                return header;
            }
        }
        throw new DecodeException(String.format("%s: unsupported header 0x%02X", file, header));
    }
}
