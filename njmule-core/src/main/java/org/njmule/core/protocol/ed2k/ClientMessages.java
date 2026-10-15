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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;
import java.util.Objects;
import org.njmule.core.api.Hash16;
import org.njmule.core.files.hash.Ed2k;
import org.njmule.core.protocol.io.DecodeException;
import org.njmule.core.protocol.io.PacketReader;
import org.njmule.core.protocol.io.PacketWriter;
import org.njmule.core.protocol.io.StringEncoding;
import org.njmule.core.protocol.packet.Opcodes;
import org.njmule.core.protocol.packet.Packet;
import org.njmule.core.protocol.packet.Protocol;

/**
 * Messages between two clients for a download, after the hello: asking for a file, its parts and hash set, a place in
 * the upload queue, then blocks of data.
 */
public final class ClientMessages {

    /** Blocks asked per request. */
    public static final int BLOCKS_PER_REQUEST = 3;

    private static final int MAX_PARTS = (int) (Ed2k.MAX_FILE_SIZE / Ed2k.PARTSIZE) + 2;

    private ClientMessages() {}

    /** A message that names its file first. */
    public static Packet forFile(Opcodes.Client opcode, Hash16 file) {
        return Packet.of(Protocol.EDONKEY, opcode.code(), new PacketWriter().hash16(file));
    }

    /** The file hash at the start of a message. */
    public static Hash16 fileOf(Packet packet) throws DecodeException {
        return packet.reader().hash16();
    }

    /** OP_REQFILENAMEANSWER: the name the uploader gives the file. */
    public record FileName(Hash16 file, String name) {
        public Packet encode() {
            return Packet.of(
                    Protocol.EDONKEY,
                    Opcodes.Client.REQFILENAMEANSWER.code(),
                    new PacketWriter().hash16(this.file).string16(this.name, StringEncoding.UTF8));
        }

        public static FileName decode(Packet packet) throws DecodeException {
            PacketReader in = packet.reader();
            return new FileName(in.hash16(), in.string16());
        }
    }

    /**
     * The parts a client has of a file: one bit per eD2K part, lowest bit first; a count of zero means the whole file.
     */
    public record PartStatus(int partCount, BitSet parts) {
        public static final PartStatus COMPLETE = new PartStatus(0, new BitSet());

        public PartStatus {
            parts = (BitSet) parts.clone();
        }

        /**
         * The status of a file of {@code size} being downloaded, with {@code complete} data parts; a size that is an
         * exact multiple of the part size counts one more part, empty and so complete.
         */
        public static PartStatus of(long size, BitSet complete) {
            int count = (int) (size / Ed2k.PARTSIZE) + 1;
            BitSet bits = (BitSet) complete.clone();
            if (size % Ed2k.PARTSIZE == 0) {
                bits.set(count - 1);
            }
            return new PartStatus(count, bits);
        }

        @Override
        public BitSet parts() {
            return (BitSet) this.parts.clone();
        }

        public void write(PacketWriter out) {
            out.count16(this.partCount);
            for (int i = 0; i < this.partCount; i += 8) {
                int bits = 0;
                for (int b = 0; b < 8 && i + b < this.partCount; b++) {
                    if (this.parts.get(i + b)) {
                        bits |= 1 << b;
                    }
                }
                out.u8(bits);
            }
        }

        public static PartStatus read(PacketReader in) throws DecodeException {
            int count = in.u16();
            if (count > MAX_PARTS) {
                throw new DecodeException("part count " + count + " exceeds any file size");
            }
            BitSet parts = new BitSet(count);
            for (int i = 0; i < count; i += 8) {
                int bits = in.u8();
                for (int b = 0; b < 8 && i + b < count; b++) {
                    if ((bits & 1 << b) != 0) {
                        parts.set(i + b);
                    }
                }
            }
            return new PartStatus(count, parts);
        }
    }

    /**
     * OP_FILESTATUS: the parts the uploader has, one bit per eD2K part, lowest bit first; a count of zero means the
     * whole file.
     */
    public record FileStatus(Hash16 file, int partCount, BitSet parts) {
        public FileStatus {
            Objects.requireNonNull(file, "file");
            parts = (BitSet) parts.clone();
        }

        /** Whether the uploader has {@code part}. */
        public boolean has(int part) {
            return this.partCount == 0 || this.parts.get(part);
        }

        /**
         * Whether the status can be of a file of {@code size}: none for a complete file, else one bit per part,
         * counting the empty last part of a size that is an exact multiple of the part size.
         */
        public boolean fits(long size) {
            return this.partCount == 0 || this.partCount == size / Ed2k.PARTSIZE + 1;
        }

        /** The data parts it has, for a file of {@code size}. */
        public BitSet dataParts(long size) {
            BitSet result = new BitSet();
            for (int part = 0; part < Ed2k.partCount(size); part++) {
                if (has(part)) {
                    result.set(part);
                }
            }
            return result;
        }

        @Override
        public BitSet parts() {
            return (BitSet) this.parts.clone();
        }

        public PartStatus status() {
            return new PartStatus(this.partCount, this.parts);
        }

        public Packet encode() {
            PacketWriter out = new PacketWriter().hash16(this.file);
            status().write(out);
            return Packet.of(Protocol.EDONKEY, Opcodes.Client.FILESTATUS.code(), out);
        }

        public static FileStatus decode(Packet packet) throws DecodeException {
            PacketReader in = packet.reader();
            Hash16 file = in.hash16();
            PartStatus status = PartStatus.read(in);
            return new FileStatus(file, status.partCount(), status.parts());
        }
    }

    /** OP_HASHSETANSWER: the part hashes of a file. */
    public record HashSet(Hash16 file, List<Hash16> parts) {
        public HashSet {
            parts = List.copyOf(parts);
        }

        public Packet encode() {
            PacketWriter out = new PacketWriter().hash16(this.file).count16(this.parts.size());
            this.parts.forEach(out::hash16);
            return Packet.of(Protocol.EDONKEY, Opcodes.Client.HASHSETANSWER.code(), out);
        }

        public static HashSet decode(Packet packet) throws DecodeException {
            PacketReader in = packet.reader();
            Hash16 file = in.hash16();
            int count = in.count(in.u16(), Hash16.LENGTH, MAX_PARTS);
            List<Hash16> parts = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                parts.add(in.hash16());
            }
            return new HashSet(file, parts);
        }
    }

    /** A block range {@code [start, end)} of a request. */
    public record Range(long start, long end) {
        public Range {
            if (start < 0 || end < start) {
                throw new IllegalArgumentException("invalid range " + start + "-" + end);
            }
        }

        public boolean isEmpty() {
            return this.start == this.end;
        }
    }

    /**
     * OP_REQUESTPARTS: three ranges, end exclusive; unused slots are empty ranges. Files above 4 GB need the 64-bit
     * OP_REQUESTPARTS_I64.
     */
    public record RequestParts(Hash16 file, List<Range> ranges) {
        public RequestParts {
            if (ranges.size() != BLOCKS_PER_REQUEST) {
                throw new IllegalArgumentException("a request carries three ranges");
            }
            ranges = List.copyOf(ranges);
        }

        public Packet encode(boolean wide) {
            PacketWriter out = new PacketWriter().hash16(this.file);
            for (Range r : this.ranges) {
                if (wide) {
                    out.u64(r.start());
                } else {
                    out.offset32(r.start());
                }
            }
            for (Range r : this.ranges) {
                if (wide) {
                    out.u64(r.end());
                } else {
                    out.offset32(r.end());
                }
            }
            return wide
                    ? Packet.of(Protocol.EMULE, Opcodes.Extended.REQUESTPARTS_I64.code(), out)
                    : Packet.of(Protocol.EDONKEY, Opcodes.Client.REQUESTPARTS.code(), out);
        }

        public static RequestParts decode(Packet packet) throws DecodeException {
            boolean wide = packet.protocol() == Protocol.EMULE;
            PacketReader in = packet.reader();
            Hash16 file = in.hash16();
            long[] starts = new long[BLOCKS_PER_REQUEST];
            for (int i = 0; i < BLOCKS_PER_REQUEST; i++) {
                starts[i] = wide ? in.u64() : in.u32();
            }
            List<Range> ranges = new ArrayList<>(BLOCKS_PER_REQUEST);
            for (int i = 0; i < BLOCKS_PER_REQUEST; i++) {
                long end = wide ? in.u64() : in.u32();
                // Some clients ask for the block ending at 4 GiB in 32 bits, its end wrapped to 0.
                if (!wide && end == 0 && starts[i] > 0) {
                    end = 1L << 32;
                }
                if (!isOffset(starts[i]) || !isOffset(end)) {
                    throw new DecodeException("invalid range " + starts[i] + "-" + end);
                }
                // A backwards range asks for nothing: skipped like an unused slot, not a reason to hang up.
                ranges.add(new Range(starts[i], Math.max(starts[i], end)));
            }
            return new RequestParts(file, ranges);
        }
    }

    /** OP_SENDINGPART(_I64): data of {@code [start, end)}. */
    // A message decoded or about to be sent, never compared; copying the data would cost.
    @SuppressWarnings("ArrayRecordComponent")
    public record SendingPart(Hash16 file, long start, long end, byte[] data) {
        public SendingPart {
            if (end - start != data.length || start < 0) {
                throw new IllegalArgumentException("data does not fill its range");
            }
            data = data.clone();
        }

        public Packet encode(boolean wide) {
            PacketWriter out = new PacketWriter().hash16(this.file);
            if (wide) {
                out.u64(this.start).u64(this.end);
            } else {
                out.offset32(this.start).offset32(this.end);
            }
            out.bytes(this.data);
            return wide
                    ? Packet.of(Protocol.EMULE, Opcodes.Extended.SENDINGPART_I64.code(), out)
                    : Packet.of(Protocol.EDONKEY, Opcodes.Client.SENDINGPART.code(), out);
        }

        public static SendingPart decode(Packet packet) throws DecodeException {
            boolean wide = packet.protocol() == Protocol.EMULE;
            PacketReader in = packet.reader();
            Hash16 file = in.hash16();
            long start = wide ? in.u64() : in.u32();
            long end = wide ? in.u64() : in.u32();
            if (!isOffset(start) || !isOffset(end) || end < start || end - start != in.remaining()) {
                throw new DecodeException("data of " + in.remaining() + " bytes for " + start + "-" + end);
            }
            return new SendingPart(file, start, end, in.rest());
        }

        @Override
        public byte[] data() {
            return this.data.clone();
        }

        @Override
        public boolean equals(Object obj) {
            return obj instanceof SendingPart other
                    && this.file.equals(other.file)
                    && this.start == other.start
                    && this.end == other.end
                    && Arrays.equals(this.data, other.data);
        }

        @Override
        public int hashCode() {
            return Objects.hash(this.file, this.start, this.end) * 31 + Arrays.hashCode(this.data);
        }

        @Override
        public String toString() {
            return "SendingPart[" + this.file + ", " + this.start + "-" + this.end + "]";
        }
    }

    /**
     * OP_COMPRESSEDPART(_I64): one chunk of the zlib stream of the block that starts at {@code blockStart}; every chunk
     * repeats the stream's total size.
     */
    // A message decoded or about to be sent, never compared; copying the data would cost.
    @SuppressWarnings("ArrayRecordComponent")
    public record CompressedPart(Hash16 file, long blockStart, long compressedSize, byte[] chunk) {
        public CompressedPart {
            chunk = chunk.clone();
        }

        @Override
        public byte[] chunk() {
            return this.chunk.clone();
        }

        @Override
        public boolean equals(Object obj) {
            return obj instanceof CompressedPart other
                    && this.file.equals(other.file)
                    && this.blockStart == other.blockStart
                    && this.compressedSize == other.compressedSize
                    && Arrays.equals(this.chunk, other.chunk);
        }

        @Override
        public int hashCode() {
            return Objects.hash(this.file, this.blockStart, this.compressedSize) * 31 + Arrays.hashCode(this.chunk);
        }

        @Override
        public String toString() {
            return "CompressedPart["
                    + this.file
                    + ", block "
                    + this.blockStart
                    + ", "
                    + this.chunk.length
                    + " of "
                    + this.compressedSize
                    + " bytes]";
        }

        public Packet encode(boolean wide) {
            PacketWriter out = new PacketWriter().hash16(this.file);
            if (wide) {
                out.u64(this.blockStart);
            } else {
                out.offset32(this.blockStart);
            }
            out.u32(this.compressedSize).bytes(this.chunk);
            return Packet.of(
                    Protocol.EMULE,
                    wide ? Opcodes.Extended.COMPRESSEDPART_I64.code() : Opcodes.Extended.COMPRESSEDPART.code(),
                    out);
        }

        public static CompressedPart decode(Packet packet) throws DecodeException {
            boolean wide = packet.opcode() == Opcodes.Extended.COMPRESSEDPART_I64.code();
            PacketReader in = packet.reader();
            Hash16 file = in.hash16();
            long start = wide ? in.u64() : in.u32();
            long total = in.u32();
            if (!isOffset(start) || in.remaining() == 0 || in.remaining() > total) {
                throw new DecodeException("invalid compressed chunk at " + start);
            }
            return new CompressedPart(file, start, total, in.rest());
        }
    }

    // u64 values above 2^63 read as negative longs; no file is larger than the network maximum.
    private static boolean isOffset(long value) {
        return value >= 0 && value <= Ed2k.MAX_FILE_SIZE;
    }

    /** OP_QUEUERANK: our place in the uploader's queue. */
    public static long decodeQueueRank(Packet packet) throws DecodeException {
        PacketReader in = packet.reader();
        return packet.protocol() == Protocol.EMULE ? in.u16() : in.u32();
    }
}
