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
package org.njmule.core.protocol.ed2k;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import org.njmule.core.api.Hash16;
import org.njmule.core.protocol.io.DecodeException;
import org.njmule.core.protocol.io.PacketReader;
import org.njmule.core.protocol.io.PacketWriter;
import org.njmule.core.protocol.io.StringEncoding;
import org.njmule.core.protocol.tag.Ed2kTagFormat;
import org.njmule.core.protocol.tag.FileTag;
import org.njmule.core.protocol.tag.Tag;
import org.njmule.core.protocol.tag.TagCodec;
import org.njmule.core.protocol.tag.Tags;

/**
 * A file as servers describe it in search results: hash, the client offering it and its tags. Layout: {@code <hash
 * 16><client id u32><port u16><tag count u32><tags>}.
 */
public record FileEntry(Hash16 hash, long clientId, int port, List<Tag> tags) {

    /** Smallest encoded entry: no tags. */
    public static final int MIN_SIZE = 16 + 4 + 2 + 4;

    /** Tags accepted per entry. */
    public static final int MAX_TAGS = 512;

    private static final int FILESIZE_HI = 0x3A;

    public FileEntry {
        Objects.requireNonNull(hash, "hash");
        tags = List.copyOf(tags);
    }

    public static FileEntry read(PacketReader in) throws DecodeException {
        Hash16 hash = in.hash16();
        long clientId = in.u32();
        int port = in.u16();
        List<Tag> tags = TagCodec.readEd2kList(in, in.u32(), MAX_TAGS);
        return new FileEntry(hash, clientId, port, tags);
    }

    /** Writes the entry with the new tag encoding, as servers do. */
    public void write(PacketWriter out) {
        this.write(out, Ed2kTagFormat.NEW);
    }

    /** Writes the entry with {@code format} tags; strings are UTF-8. */
    public void write(PacketWriter out, Ed2kTagFormat format) {
        out.hash16(this.hash).u32(this.clientId).u16(this.port).u32(this.tags.size());
        TagCodec.writeEd2kList(out, this.tags, format, StringEncoding.UTF8);
    }

    public Optional<String> name() {
        return Tags.stringValue(this.tags, FileTag.FILENAME);
    }

    /** The size: a 64-bit size tag, or the 32-bit one combined with its high half. */
    public OptionalLong size() {
        OptionalLong size = Tags.intValue(this.tags, FileTag.FILESIZE);
        if (size.isEmpty() || size.getAsLong() > 0xFFFF_FFFFL) {
            return size;
        }
        long high = Tags.intValue(this.tags, FILESIZE_HI).orElse(0);
        return OptionalLong.of(high << 32 | size.getAsLong());
    }

    public int sources() {
        return (int) Math.min(
                Integer.MAX_VALUE, Tags.intValue(this.tags, FileTag.SOURCES).orElse(0));
    }

    public int completeSources() {
        return (int) Math.min(
                Integer.MAX_VALUE,
                Tags.intValue(this.tags, FileTag.COMPLETE_SOURCES).orElse(0));
    }

    /** The type servers report, e.g. {@code Video} or {@code Pro}. */
    public Optional<String> type() {
        return Tags.stringValue(this.tags, FileTag.FILETYPE);
    }
}
