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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.TreeMap;
import org.njmule.core.api.Hash16;
import org.njmule.core.api.Hash20;
import org.njmule.core.files.hash.Ed2k;
import org.njmule.core.protocol.io.DecodeException;
import org.njmule.core.protocol.io.PacketReader;
import org.njmule.core.protocol.io.PacketWriter;
import org.njmule.core.protocol.tag.FileTag;
import org.njmule.core.protocol.tag.Tag;
import org.njmule.core.protocol.tag.Tag.IntTag;
import org.njmule.core.protocol.tag.TagName;
import org.njmule.core.protocol.tag.Tags;

/**
 * A download's metadata, {@code NNN.part.met}: {@code <0xE0|0xE2><mtime u32><hash set><u32 tag count><tags>}; 0xE2
 * marks files larger than 4 GB. Missing ranges are stored as tag pairs named {@code "\x09<n>"} (start) and
 * {@code "\x0A<n>"} (end, exclusive).
 */
public final class PartMet {

    public static final int VERSION = 0xE0;
    public static final int VERSION_LARGE = 0xE2;

    /** A missing range {@code [start, end)}. */
    public record Gap(long start, long end) {
        public Gap {
            if (start < 0 || end <= start) {
                throw new IllegalArgumentException("invalid gap " + start + "-" + end);
            }
        }
    }

    public record Contents(int version, long mtime, Hash16 fileHash, List<Hash16> partHashes, List<Tag> tags) {
        public Contents {
            Objects.requireNonNull(fileHash, "fileHash");
            partHashes = List.copyOf(partHashes);
            tags = List.copyOf(tags);
        }

        public Optional<String> name() {
            return Tags.stringValue(this.tags, FileTag.FILENAME);
        }

        public OptionalLong size() {
            return Tags.intValue(this.tags, FileTag.FILESIZE);
        }

        public Optional<Hash20> aichHash() {
            return Tags.stringValue(this.tags, FileTag.AICH_HASH).flatMap(KnownMet::base32);
        }

        /** Paused or stopped (FT_STATUS ≠ 0). */
        public boolean paused() {
            return Tags.intValue(this.tags, FileTag.STATUS).orElse(0) != 0;
        }

        public OptionalLong category() {
            return Tags.intValue(this.tags, FileTag.CATEGORY);
        }

        /**
         * The missing ranges, clamped to the file size and sorted by start. Incomplete pairs and ranges starting beyond
         * the end are dropped; overlaps are left for the gap list to merge.
         */
        public List<Gap> gaps() {
            long size = this.size().orElse(0);
            Map<String, long[]> pairs = new HashMap<>();
            for (Tag tag : this.tags) {
                String raw = tag.name().raw();
                if (tag instanceof IntTag t && raw.length() > 1 && isGapName(raw)) {
                    long[] pair = pairs.computeIfAbsent(raw.substring(1), k -> new long[] {-1, -1});
                    pair[raw.charAt(0) == FileTag.GAPSTART ? 0 : 1] = t.value();
                }
            }
            TreeMap<Long, Long> sorted = new TreeMap<>();
            for (long[] pair : pairs.values()) {
                long start = pair[0];
                long end = Math.min(pair[1], size);
                if (start >= 0 && pair[1] >= 0 && start < size && start < end) {
                    sorted.merge(start, end, Math::max);
                }
            }
            List<Gap> gaps = new ArrayList<>(sorted.size());
            sorted.forEach((start, end) -> gaps.add(new Gap(start, end)));
            return gaps;
        }

        /** A copy whose gap tags describe {@code gaps}. */
        public Contents withGaps(List<Gap> gaps) {
            List<Tag> kept = new ArrayList<>(this.tags.size() + 2 * gaps.size());
            for (Tag tag : this.tags) {
                if (!isGapName(tag.name().raw())) {
                    kept.add(tag);
                }
            }
            for (int i = 0; i < gaps.size(); i++) {
                kept.add(new IntTag(
                        TagName.of((char) FileTag.GAPSTART + Integer.toString(i)),
                        gaps.get(i).start()));
                kept.add(new IntTag(
                        TagName.of((char) FileTag.GAPEND + Integer.toString(i)),
                        gaps.get(i).end()));
            }
            return new Contents(this.version, this.mtime, this.fileHash, this.partHashes, kept);
        }
    }

    private PartMet() {}

    public static Contents read(byte[] data) throws DecodeException {
        PacketReader in = PacketReader.of(data);
        int version = in.u8();
        if (version != VERSION && version != VERSION_LARGE) {
            throw new DecodeException(String.format("unsupported part.met version 0x%02X", version));
        }
        long mtime = in.u32();
        MetCodecs.HashSet set = MetCodecs.readHashSet(in);
        Contents contents = new Contents(version, mtime, set.fileHash(), set.partHashes(), MetCodecs.readTags(in));
        long size = contents.size().orElseThrow(() -> new DecodeException("part.met without file size"));
        if (size <= 0 || size > Ed2k.MAX_FILE_SIZE) {
            throw new DecodeException("part.met file size out of range: " + size);
        }
        return contents;
    }

    /** Writes the file; the version follows the size, as 64-bit values need 0xE2. */
    public static byte[] write(Contents contents) {
        long size = contents.size().orElseThrow(() -> new IllegalArgumentException("no file size"));
        PacketWriter out = new PacketWriter();
        out.u8(size > Ed2k.OLD_MAX_FILE_SIZE ? VERSION_LARGE : VERSION).u32(contents.mtime());
        MetCodecs.writeHashSet(out, contents.fileHash(), contents.partHashes());
        MetCodecs.writeTags(out, contents.tags());
        return out.toByteArray();
    }

    private static boolean isGapName(String raw) {
        if (raw.length() < 2 || (raw.charAt(0) != FileTag.GAPSTART && raw.charAt(0) != FileTag.GAPEND)) {
            return false;
        }
        for (int i = 1; i < raw.length(); i++) {
            if (raw.charAt(i) < '0' || raw.charAt(i) > '9') {
                return false;
            }
        }
        return true;
    }
}
