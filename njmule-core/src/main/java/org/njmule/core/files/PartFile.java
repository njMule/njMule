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
package org.njmule.core.files;

import com.google.common.collect.Range;
import com.google.common.collect.RangeSet;
import com.google.common.collect.TreeRangeSet;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import org.njmule.core.api.Category;
import org.njmule.core.api.DownloadPriority;
import org.njmule.core.api.Hash16;
import org.njmule.core.api.Hash20;
import org.njmule.core.api.PartMap;
import org.njmule.core.files.hash.Ed2k;
import org.njmule.core.persist.AtomicFiles;
import org.njmule.core.persist.format.PartMet;
import org.njmule.core.protocol.tag.FileTag;
import org.njmule.core.protocol.tag.Tag;
import org.njmule.core.protocol.tag.TagName;
import org.njmule.core.util.Bytes;

/**
 * A file being downloaded, {@code NNN.part} with its metadata {@code NNN.part.met}: which byte ranges are still
 * missing, which are being requested, and the part hashes that verify each 9.28 MiB part. Used on the engine thread
 * only; the data itself is read and written by {@link PartFileStorage}.
 */
public final class PartFile {

    // njMule's own tag, which other clients skip: the parts that passed their check.
    private static final TagName VERIFIED_TAG = TagName.of("njm_verified");

    /** Where a download stands. */
    public enum Status {
        DOWNLOADING,
        PAUSED,
        /** Paused, and its sources are forgotten. */
        STOPPED,
        /** Disk failure: the file waits for the user. */
        ERROR,
        COMPLETE
    }

    /** A range {@code [start, end)} within one part, at most one block long. */
    public record Block(long start, long end) {
        public Block {
            if (start < 0 || end <= start || end - start > Ed2k.EMBLOCKSIZE) {
                throw new IllegalArgumentException("invalid block " + start + "-" + end);
            }
        }

        public long length() {
            return this.end - this.start;
        }
    }

    public static final String DATA_SUFFIX = ".part";
    public static final String MET_SUFFIX = ".part.met";

    private final int number;
    private final Path directory;
    private final Hash16 hash;
    private final long size;
    private final Optional<Hash20> aichHash;
    // The parts checked in this run and before, and those trusted from the last save.
    private final BitSet verified = new BitSet();
    private final BitSet trusted = new BitSet();
    private final RangeSet<Long> missing = TreeRangeSet.create();
    private final RangeSet<Long> requested = TreeRangeSet.create();
    private final int[] availability;
    private String name;
    private List<Hash16> partHashes;
    private Status status = Status.DOWNLOADING;
    private DownloadPriority priority = DownloadPriority.AUTO;
    private int category = Category.NONE;

    private PartFile(
            int number,
            Path directory,
            String name,
            long size,
            Hash16 hash,
            Optional<Hash20> aichHash,
            List<Hash16> partHashes) {
        Ed2k.checkSize(size);
        if (size == 0) {
            throw new IllegalArgumentException("nothing to download in an empty file");
        }
        this.number = number;
        this.directory = Objects.requireNonNull(directory, "directory");
        this.name = Objects.requireNonNull(name, "name");
        this.size = size;
        this.hash = Objects.requireNonNull(hash, "hash");
        this.aichHash = Objects.requireNonNull(aichHash, "aichHash");
        this.partHashes = List.copyOf(partHashes);
        this.availability = new int[Ed2k.partCount(size)];
    }

    /** A new, empty download; its hash set may be given (from the link) or arrive later. */
    public static PartFile create(
            int number,
            Path directory,
            String name,
            long size,
            Hash16 hash,
            Optional<Hash20> aichHash,
            List<Hash16> partHashes) {
        PartFile file = new PartFile(number, directory, name, size, hash, aichHash, List.of());
        file.missing.add(Range.closedOpen(0L, size));
        if (!partHashes.isEmpty()) {
            file.setHashSet(partHashes);
        }
        return file;
    }

    /** Reads {@code NNN.part.met} (or its backup) from {@code directory}. */
    public static PartFile load(Path directory, int number) throws IOException {
        Path met = directory.resolve(fileName(number) + MET_SUFFIX);
        PartMet.Contents contents = AtomicFiles.read(met, in -> PartMet.read(in.readAllBytes()));
        long size = contents.size().orElseThrow();
        PartFile file = new PartFile(
                number,
                directory,
                contents.name().orElse(fileName(number)),
                size,
                contents.fileHash(),
                contents.aichHash(),
                contents.partHashes().size() == Ed2k.hashSetSize(size) ? contents.partHashes() : List.of());
        for (PartMet.Gap gap : contents.gaps()) {
            file.missing.add(Range.closedOpen(gap.start(), gap.end()));
        }
        if (contents.stopped()) {
            file.status = Status.STOPPED;
        } else if (contents.paused()) {
            file.status = Status.PAUSED;
        }
        contents.downloadPriority().ifPresent(code -> file.priority = priority(code));
        contents.category().ifPresent(id -> file.category = Math.clamp(id, 0, Integer.MAX_VALUE));
        // Parts checked before are trusted only while the data file is as it was saved.
        if (dataDate(file.dataFile()).equals(OptionalLong.of(contents.mtime()))) {
            for (Tag tag : contents.tags()) {
                if (tag instanceof Tag.BlobTag blob && blob.name().equals(VERIFIED_TAG)) {
                    file.trusted.or(BitSet.valueOf(blob.value().toByteArray()));
                }
            }
            file.trusted.clear(file.partCount(), Math.max(file.partCount(), file.trusted.length()));
            file.verified.or(file.trusted);
        }
        return file;
    }

    /** Records that {@code part} passed its check, so that a restart need not check it again. */
    public void verified(int part) {
        this.verified.set(part);
    }

    /** The parts that passed their check before the last save, when the data has not changed since. */
    public BitSet trustedParts() {
        return (BitSet) this.trusted.clone();
    }

    /** Writes {@code NNN.part.met} crash-safely, keeping the previous one as backup. */
    public void save() throws IOException {
        List<Tag> tags = new ArrayList<>();
        tags.add(new Tag.StringTag(TagName.id(FileTag.FILENAME), this.name));
        tags.add(new Tag.IntTag(TagName.id(FileTag.FILESIZE), this.size));
        tags.add(new Tag.StringTag(TagName.id(FileTag.PARTFILENAME), this.dataName()));
        tags.add(new Tag.IntTag(TagName.id(FileTag.STATUS), statusCode(this.status)));
        tags.add(new Tag.IntTag(TagName.id(FileTag.DLPRIORITY), priorityCode(this.priority)));
        if (this.category != Category.NONE) {
            tags.add(new Tag.IntTag(TagName.id(FileTag.CATEGORY), this.category));
        }
        this.aichHash.ifPresent(h -> tags.add(new Tag.StringTag(TagName.id(FileTag.AICH_HASH), h.toBase32())));
        List<PartMet.Gap> gaps = new ArrayList<>();
        for (Range<Long> gap : this.missing.asRanges()) {
            gaps.add(new PartMet.Gap(gap.lowerEndpoint(), gap.upperEndpoint()));
        }
        // The date is that of the data: a data file changed since the save has its parts checked again.
        long dataDate = dataDate(this.dataFile()).orElse(System.currentTimeMillis() / 1000);
        if (!this.verified.isEmpty()) {
            tags.add(new Tag.BlobTag(VERIFIED_TAG, Bytes.of(this.verified.toByteArray())));
        }
        PartMet.Contents contents =
                new PartMet.Contents(PartMet.VERSION, dataDate, this.hash, this.partHashes, tags).withGaps(gaps);
        AtomicFiles.writeBytes(this.metFile(), PartMet.write(contents));
    }

    private static OptionalLong dataDate(Path data) {
        try {
            return OptionalLong.of(Files.getLastModifiedTime(data).toMillis() / 1000);
        } catch (IOException e) {
            return OptionalLong.empty();
        }
    }

    public int number() {
        return this.number;
    }

    public Hash16 hash() {
        return this.hash;
    }

    public long size() {
        return this.size;
    }

    public String name() {
        return this.name;
    }

    public void rename(String name) {
        this.name = Objects.requireNonNull(name, "name");
    }

    public Optional<Hash20> aichHash() {
        return this.aichHash;
    }

    public Status status() {
        return this.status;
    }

    public void setStatus(Status status) {
        this.status = Objects.requireNonNull(status, "status");
    }

    public DownloadPriority priority() {
        return this.priority;
    }

    public void setPriority(DownloadPriority priority) {
        this.priority = Objects.requireNonNull(priority, "priority");
    }

    /** The id of its category, {@link Category#NONE} for none. */
    public int category() {
        return this.category;
    }

    public void setCategory(int category) {
        if (category < 0) {
            throw new IllegalArgumentException("no category has the id " + category);
        }
        this.category = category;
    }

    public Path dataFile() {
        return this.directory.resolve(this.dataName());
    }

    public Path metFile() {
        return this.directory.resolve(fileName(this.number) + MET_SUFFIX);
    }

    public int partCount() {
        return Ed2k.partCount(this.size);
    }

    public long missingBytes() {
        long total = 0;
        for (Range<Long> gap : this.missing.asRanges()) {
            total += gap.upperEndpoint() - gap.lowerEndpoint();
        }
        return total;
    }

    public long completedBytes() {
        return this.size - this.missingBytes();
    }

    /** Whether every part can be verified: files below one part need only the file hash. */
    public boolean hasHashSet() {
        return this.size < Ed2k.PARTSIZE || this.partHashes.size() == Ed2k.hashSetSize(this.size);
    }

    /**
     * Sets the part hashes once a source sent them.
     *
     * @throws IllegalArgumentException if they do not hash to the file hash
     */
    public void setHashSet(List<Hash16> hashes) {
        if (hashes.size() != Ed2k.hashSetSize(this.size)
                || !Ed2k.fileHash(hashes).equals(this.hash)) {
            throw new IllegalArgumentException("the hash set does not match the file hash");
        }
        this.partHashes = List.copyOf(hashes);
    }

    /** The part hashes, empty until known and for files below one part. */
    public List<Hash16> partHashes() {
        return this.partHashes;
    }

    /** The hash that verifies {@code part}, once known. */
    public Optional<Hash16> partHash(int part) {
        if (this.size < Ed2k.PARTSIZE) {
            return Optional.of(this.hash);
        }
        return this.partHashes.isEmpty() ? Optional.empty() : Optional.of(this.partHashes.get(part));
    }

    public boolean isComplete() {
        return this.missing.isEmpty();
    }

    public boolean isComplete(int part) {
        return this.missing.subRangeSet(partRange(this.size, part)).isEmpty();
    }

    /** The parts a source has, as it reports them, update the rarity of each part. */
    public void addAvailability(BitSet parts, int delta) {
        for (int part = parts.nextSetBit(0);
                part >= 0 && part < this.availability.length;
                part = parts.nextSetBit(part + 1)) {
            this.availability[part] = Math.max(0, this.availability[part] + delta);
        }
    }

    /** The bytes still missing, the requested ones and the sources of each part, to show. */
    public PartMap map() {
        List<PartMap.Span> gaps = new ArrayList<>();
        for (Range<Long> r : this.missing.asRanges()) {
            gaps.add(new PartMap.Span(r.lowerEndpoint(), r.upperEndpoint()));
        }
        List<PartMap.Span> asked = new ArrayList<>();
        for (Range<Long> r : this.requested.asRanges()) {
            asked.add(new PartMap.Span(r.lowerEndpoint(), r.upperEndpoint()));
        }
        List<Integer> sources = new ArrayList<>(this.availability.length);
        for (int count : this.availability) {
            sources.add(count);
        }
        return new PartMap(this.size, gaps, asked, sources);
    }

    /**
     * Up to {@code max} blocks to request from a source that has {@code sourceParts}: missing and not already
     * requested, from the rarest incomplete part the source has. They are marked as requested until {@link #written} or
     * {@link #release}.
     */
    public List<Block> nextBlocks(BitSet sourceParts, int max) {
        List<Block> blocks = new ArrayList<>(max);
        int part = this.pickPart(sourceParts);
        if (part < 0) {
            return blocks;
        }
        RangeSet<Long> wanted = TreeRangeSet.create(this.missing.subRangeSet(partRange(this.size, part)));
        wanted.removeAll(this.requested);
        long partStart = Ed2k.partStart(part);
        for (Range<Long> gap : wanted.asRanges()) {
            long start = gap.lowerEndpoint();
            while (start < gap.upperEndpoint() && blocks.size() < max) {
                // Blocks end on the 180 KiB boundaries of the part, as AICH cuts it: a bad block is one AICH block.
                long boundary = partStart + ((start - partStart) / Ed2k.EMBLOCKSIZE + 1) * Ed2k.EMBLOCKSIZE;
                long end = Math.min(gap.upperEndpoint(), boundary);
                blocks.add(new Block(start, end));
                this.requested.add(Range.closedOpen(start, end));
                start = end;
            }
            if (blocks.size() >= max) {
                break;
            }
        }
        return blocks;
    }

    /** A requested block that will not arrive (the source is gone). */
    public void release(Block block) {
        this.requested.remove(Range.closedOpen(block.start(), block.end()));
    }

    /** Data in {@code [start, end)} reached the disk. */
    public void written(long start, long end) {
        Range<Long> range = Range.closedOpen(start, end);
        this.missing.remove(range);
        this.requested.remove(range);
    }

    /** A part failed its hash check: all of it is missing again. */
    public void corrupt(int part) {
        this.missing.add(partRange(this.size, part));
        this.verified.clear(part);
        this.trusted.clear(part);
    }

    /** The parts that are complete, as reported to other clients. */
    public BitSet completeParts() {
        BitSet parts = new BitSet(this.partCount());
        for (int part = 0; part < this.partCount(); part++) {
            if (this.isComplete(part)) {
                parts.set(part);
            }
        }
        return parts;
    }

    /** {@code [start, end)} of a data part. */
    public static Range<Long> partRange(long size, int part) {
        long start = Ed2k.partStart(part);
        return Range.closedOpen(start, start + Ed2k.partLength(size, part));
    }

    /** {@code 001}, {@code 002}, ...: the base name of download number {@code n}. */
    public static String fileName(int number) {
        return String.format(Locale.ROOT, "%03d", number);
    }

    private static int statusCode(Status status) {
        return switch (status) {
            case PAUSED -> PartMet.PAUSED;
            case STOPPED -> PartMet.STOPPED;
            default -> 0;
        };
    }

    // The values other clients write too, so imported downloads keep their priority.
    private static int priorityCode(DownloadPriority priority) {
        return switch (priority) {
            case LOW -> 0;
            case NORMAL -> 1;
            case HIGH -> 2;
            case AUTO -> 5;
        };
    }

    private static DownloadPriority priority(long code) {
        return switch ((int) code) {
            case 0, 4 -> DownloadPriority.LOW;
            case 2, 3 -> DownloadPriority.HIGH;
            case 5 -> DownloadPriority.AUTO;
            default -> DownloadPriority.NORMAL;
        };
    }

    private String dataName() {
        return fileName(this.number) + DATA_SUFFIX;
    }

    // Rarest first; among equally rare parts, one begun already, so that parts complete, are checked and can be shared
    // sooner; then the lowest, which helps previews.
    private int pickPart(BitSet sourceParts) {
        int best = -1;
        boolean bestBegun = false;
        for (int part = sourceParts.nextSetBit(0);
                part >= 0 && part < this.partCount();
                part = sourceParts.nextSetBit(part + 1)) {
            Range<Long> range = partRange(this.size, part);
            RangeSet<Long> missingHere = this.missing.subRangeSet(range);
            RangeSet<Long> wanted = TreeRangeSet.create(missingHere);
            wanted.removeAll(this.requested);
            if (wanted.isEmpty()) {
                continue;
            }
            boolean begun = !missingHere.encloses(range);
            if (best < 0
                    || this.availability[part] < this.availability[best]
                    || (this.availability[part] == this.availability[best] && begun && !bestBegun)) {
                best = part;
                bestBegun = begun;
            }
        }
        return best;
    }
}
