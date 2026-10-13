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
import java.util.Objects;
import org.njmule.core.api.Hash16;
import org.njmule.core.protocol.io.DecodeException;
import org.njmule.core.protocol.io.PacketReader;
import org.njmule.core.protocol.io.PacketWriter;
import org.njmule.core.protocol.io.StringEncoding;
import org.njmule.core.protocol.tag.Ed2kTagFormat;
import org.njmule.core.protocol.tag.Tag;
import org.njmule.core.protocol.tag.TagCodec;

/**
 * Search files: {@code StoredSearches.met} (open search tabs with their results, restored at start) and
 * {@code SearchSpam.met} (learned spam markers). Both start with {@code 0x0F}; results use the same record as server
 * search results, {@code <hash 16><client id u32><port u16><u32 tag count><new-encoding tags>}.
 */
public final class SearchFiles {

    private static final int HEADER = 0x0F;
    private static final int STORED_VERSION = 1;
    private static final int MAX_RESULTS = 1_000_000;

    /** Spam marker tag ids. */
    public static final int SPAM_FULL_NAME = 0x01;

    public static final int SPAM_HASH = 0x02;
    public static final int NOT_SPAM_HASH = 0x03;
    public static final int SPAM_SOURCE_IP = 0x04;
    public static final int SPAM_SERVER_IP = 0x05;
    public static final int SPAM_SIMILAR_NAME = 0x06;
    public static final int SPAM_SIZE = 0x07;
    public static final int SPAM_UDP_SERVER_RATIO = 0x08;

    /** A search result: the file and the source that offered it (a server or a client). */
    public record Result(Hash16 fileHash, long clientId, int port, List<Tag> tags) {
        public Result {
            Objects.requireNonNull(fileHash, "fileHash");
            tags = List.copyOf(tags);
        }

        public static Result read(PacketReader in) throws DecodeException {
            Hash16 hash = in.hash16();
            long id = in.u32();
            int port = in.u16();
            return new Result(hash, id, port, TagCodec.readEd2kList(in, in.u32(), 1024));
        }

        public void write(PacketWriter out) {
            out.hash16(this.fileHash).u32(this.clientId).u16(this.port).u32(this.tags.size());
            TagCodec.writeEd2kList(out, this.tags, Ed2kTagFormat.NEW, StringEncoding.UTF8);
        }
    }

    /** One stored search tab. {@code type} is the search method (automatic, server, global, Kad). */
    public record StoredSearch(
            long id,
            int type,
            boolean clientSharedFiles,
            String title,
            String expression,
            String fileType,
            List<Result> results) {
        public StoredSearch {
            Objects.requireNonNull(title, "title");
            Objects.requireNonNull(expression, "expression");
            Objects.requireNonNull(fileType, "fileType");
            results = List.copyOf(results);
        }
    }

    private SearchFiles() {}

    public static List<StoredSearch> readStored(byte[] data) throws DecodeException {
        PacketReader in = PacketReader.of(data);
        MetCodecs.header(in, "StoredSearches.met", HEADER);
        int version = in.u8();
        if (version != STORED_VERSION) {
            throw new DecodeException("unsupported StoredSearches.met version " + version);
        }
        int count = in.count(in.u16(), 12, 0xFFFF);
        List<StoredSearch> searches = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            long id = in.u32();
            int type = in.u8();
            boolean shared = in.u8() != 0;
            String title = in.string16();
            String expression = in.string16();
            String fileType = in.string16();
            int n = in.count(in.u32(), 26, MAX_RESULTS);
            List<Result> results = new ArrayList<>(n);
            for (int r = 0; r < n; r++) {
                results.add(Result.read(in));
            }
            searches.add(new StoredSearch(id, type, shared, title, expression, fileType, results));
        }
        return List.copyOf(searches);
    }

    public static byte[] writeStored(List<StoredSearch> searches) {
        PacketWriter out = new PacketWriter();
        out.u8(HEADER).u8(STORED_VERSION).u16(searches.size());
        for (StoredSearch s : searches) {
            out.u32(s.id()).u8(s.type()).u8(s.clientSharedFiles() ? 1 : 0);
            out.string16(s.title(), StringEncoding.UTF8)
                    .string16(s.expression(), StringEncoding.UTF8)
                    .string16(s.fileType(), StringEncoding.UTF8);
            out.u32(s.results().size());
            for (Result r : s.results()) {
                r.write(out);
            }
        }
        return out.toByteArray();
    }

    /** The spam markers, one tag each. */
    public static List<Tag> readSpam(byte[] data) throws DecodeException {
        PacketReader in = PacketReader.of(data);
        MetCodecs.header(in, "SearchSpam.met", HEADER);
        return TagCodec.readEd2kList(in, in.u32(), MAX_RESULTS);
    }

    public static byte[] writeSpam(List<Tag> markers) {
        PacketWriter out = new PacketWriter();
        out.u8(HEADER).u32(markers.size());
        TagCodec.writeEd2kList(out, markers, Ed2kTagFormat.NEW, StringEncoding.UTF8_BOM_IF_NEEDED);
        return out.toByteArray();
    }
}
