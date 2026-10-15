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
import java.util.Optional;
import java.util.OptionalLong;
import org.njmule.core.api.Hash16;
import org.njmule.core.api.Hash20;
import org.njmule.core.files.hash.Ed2k;
import org.njmule.core.protocol.io.DecodeException;
import org.njmule.core.protocol.io.PacketReader;
import org.njmule.core.protocol.io.PacketWriter;
import org.njmule.core.protocol.tag.FileTag;
import org.njmule.core.protocol.tag.Tag;
import org.njmule.core.protocol.tag.Tags;

/**
 * Shared and once-shared files, {@code known.met}: {@code <0x0E|0x0F><u32 count>} then {@code <mtime u32><hash set><u32
 * tag count><tags>} per file; 0x0F allows 64-bit sizes.
 */
public final class KnownMet {

    public static final int HEADER = 0x0E;
    public static final int HEADER_64BIT = 0x0F;
    private static final int MAX_FILES = 1_000_000;

    public record KnownFile(long mtime, Hash16 fileHash, List<Hash16> partHashes, List<Tag> tags) {
        public KnownFile {
            Objects.requireNonNull(fileHash, "fileHash");
            partHashes = List.copyOf(partHashes);
            tags = List.copyOf(tags);
        }

        public Optional<String> name() {
            return Tags.stringValue(this.tags, FileTag.FILENAME);
        }

        /** FT_FILESIZE, with FT_FILESIZE_HI of old 32-bit entries. */
        public OptionalLong size() {
            OptionalLong low = Tags.intValue(this.tags, FileTag.FILESIZE);
            if (low.isEmpty()) {
                return low;
            }
            long high = Tags.intValue(this.tags, FileTag.FILESIZE_HI).orElse(0);
            return OptionalLong.of(high << 32 | low.getAsLong());
        }

        /** FT_ULPRIORITY: 0 low, 1 normal, 2 high, 3 release, 4 very low, 5 auto. */
        public OptionalLong uploadPriority() {
            return Tags.intValue(this.tags, FileTag.ULPRIORITY);
        }

        public Optional<Hash20> aichHash() {
            return Tags.stringValue(this.tags, FileTag.AICH_HASH).flatMap(KnownMet::base32);
        }

        /** All-time requests of the file (FT_ATREQUESTED). */
        public long requests() {
            return Tags.intValue(this.tags, FileTag.ATREQUESTED).orElse(0);
        }

        /** All-time requests that got an upload slot (FT_ATACCEPTED). */
        public long accepted() {
            return Tags.intValue(this.tags, FileTag.ATACCEPTED).orElse(0);
        }

        /** All-time uploaded bytes (FT_ATTRANSFERRED with FT_ATTRANSFERREDHI). */
        public long transferred() {
            return Tags.intValue(this.tags, FileTag.ATTRANSFERREDHI).orElse(0) << 32
                    | Tags.intValue(this.tags, FileTag.ATTRANSFERRED).orElse(0);
        }

        /** Whether the part hashes match the size and produce the file hash. */
        public boolean isConsistent() {
            OptionalLong size = size();
            if (size.isEmpty() || size.getAsLong() < 0 || size.getAsLong() > Ed2k.MAX_FILE_SIZE) {
                return false;
            }
            return this.partHashes.size() == Ed2k.hashSetSize(size.getAsLong())
                    && (this.partHashes.isEmpty()
                            || Ed2k.fileHash(this.partHashes).equals(this.fileHash));
        }
    }

    private KnownMet() {}

    public static List<KnownFile> read(byte[] data) throws DecodeException {
        PacketReader in = PacketReader.of(data);
        MetCodecs.header(in, "known.met", HEADER, HEADER_64BIT);
        int count = in.count(in.u32(), 4 + 16 + 2 + 4, MAX_FILES);
        List<KnownFile> files = new ArrayList<>(count);
        records(in, count, files);
        return List.copyOf(files);
    }

    /** The records of a damaged file that come before the damage; none when its header cannot be read. */
    public static List<KnownFile> salvage(byte[] data) {
        List<KnownFile> files = new ArrayList<>();
        try {
            PacketReader in = PacketReader.of(data);
            MetCodecs.header(in, "known.met", HEADER, HEADER_64BIT);
            records(in, (int) Math.min(in.u32(), MAX_FILES), files);
        } catch (DecodeException | IllegalArgumentException e) {
            // What follows the damage is lost.
        }
        return List.copyOf(files);
    }

    private static void records(PacketReader in, int count, List<KnownFile> files) throws DecodeException {
        for (int i = 0; i < count; i++) {
            long mtime = in.u32();
            MetCodecs.HashSet set = MetCodecs.readHashSet(in);
            files.add(new KnownFile(mtime, set.fileHash(), set.partHashes(), MetCodecs.readTags(in)));
        }
    }

    public static byte[] write(List<KnownFile> files) {
        boolean large = files.stream().anyMatch(f -> f.size().orElse(0) > Ed2k.OLD_MAX_FILE_SIZE);
        PacketWriter out = new PacketWriter();
        out.u8(large ? HEADER_64BIT : HEADER).u32(files.size());
        for (KnownFile f : files) {
            out.u32(f.mtime());
            MetCodecs.writeHashSet(out, f.fileHash(), f.partHashes());
            MetCodecs.writeTags(out, f.tags());
        }
        return out.toByteArray();
    }

    static Optional<Hash20> base32(String text) {
        try {
            return Optional.of(Hash20.parseBase32(text));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
