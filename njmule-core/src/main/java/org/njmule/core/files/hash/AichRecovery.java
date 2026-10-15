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
package org.njmule.core.files.hash;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.njmule.core.api.Hash20;
import org.njmule.core.protocol.io.DecodeException;
import org.njmule.core.protocol.io.PacketReader;
import org.njmule.core.protocol.io.PacketWriter;

/**
 * AICH recovery data of one part: the hashes that tie the part's 180 KiB blocks to the master hash, as clients exchange
 * them. Each hash has an identifier, the path to its node: a leading 1 for the root, then one bit per level, 1 for a
 * left branch. The data holds the sibling of each node on the way down to the part, then the part's block hashes from
 * left to right; files above {@link #LARGE_FILE} bytes use 32-bit identifiers, smaller ones 16-bit.
 */
public final class AichRecovery {

    /** Above this size identifiers take 32 bits. */
    public static final long LARGE_FILE = 4_290_048_000L;

    // A path longer than this does not exist in a file of at most 2^38 bytes.
    private static final int MAX_LEVELS = 22;
    private static final long MAX_IDENT = 0x400000L;

    private record Node(long ident, Hash20 hash) {}

    private AichRecovery() {}

    /**
     * Whether part {@code part} of a file of {@code size} bytes has recovery data: a part of more than one block, also
     * the only part of a small file.
     */
    public static boolean hasRecovery(long size, int part) {
        return part >= 0 && (long) part * Ed2k.PARTSIZE + Ed2k.EMBLOCKSIZE < size;
    }

    /** The recovery data of part {@code part} of {@code tree}. */
    public static byte[] create(AichTree tree, int part) {
        long size = tree.size();
        if (!hasRecovery(size, part)) {
            throw new IllegalArgumentException("part " + part + " of " + size + " bytes has no recovery data");
        }
        long partStart = (long) part * Ed2k.PARTSIZE;
        long partSize = Math.min(Ed2k.PARTSIZE, size - partStart);
        List<Node> nodes = new ArrayList<>();
        new Walker(tree.blockHashes()).down(size, true, AichTree.baseSize(size), 0, 0, partStart, partSize, nodes);
        boolean large = size > LARGE_FILE;
        PacketWriter out = new PacketWriter();
        if (large) {
            out.u16(0);
        }
        out.u16(nodes.size());
        for (Node n : nodes) {
            if (large) {
                out.u32(n.ident());
            } else {
                out.u16((int) n.ident());
            }
            out.hash20(n.hash());
        }
        if (!large) {
            out.u16(0);
        }
        return out.toByteArray();
    }

    /**
     * The block hashes of part {@code part} of a file of {@code size} bytes, from left to right, when {@code data}
     * proves them against {@code master}; empty when it does not.
     */
    public static Optional<List<Hash20>> verify(long size, int part, Hash20 master, byte[] data) {
        if (!hasRecovery(size, part)) {
            return Optional.empty();
        }
        Map<Long, Hash20> byIdent;
        try {
            byIdent = read(data);
        } catch (DecodeException e) {
            return Optional.empty();
        }
        long partStart = (long) part * Ed2k.PARTSIZE;
        long partSize = Math.min(Ed2k.PARTSIZE, size - partStart);
        Checker checker = new Checker(byIdent);
        Optional<Hash20> root = checker.up(size, true, AichTree.baseSize(size), 0, 0, partStart, partSize, 0);
        if (root.isEmpty() || !root.get().equals(master)) {
            return Optional.empty();
        }
        return Optional.of(List.copyOf(checker.blocks));
    }

    // Both identifier widths; the master hash is never taken from a peer.
    private static Map<Long, Hash20> read(byte[] data) throws DecodeException {
        PacketReader in = PacketReader.of(data);
        Map<Long, Hash20> hashes = new HashMap<>();
        int short16 = in.u16();
        for (int i = 0; i < short16; i++) {
            put(hashes, in.u16(), Hash20.of(in.bytes(Hash20.LENGTH)));
        }
        if (in.remaining() >= 2) {
            int long32 = in.u16();
            for (int i = 0; i < long32; i++) {
                put(hashes, in.u32(), Hash20.of(in.bytes(Hash20.LENGTH)));
            }
        }
        return hashes;
    }

    private static void put(Map<Long, Hash20> hashes, long ident, Hash20 hash) throws DecodeException {
        if (ident <= 1 || ident > MAX_IDENT) {
            throw new DecodeException("bad AICH identifier " + ident);
        }
        hashes.putIfAbsent(ident, hash);
    }

    private static long leftSize(long dataSize, boolean left, long base) {
        long blocks = (dataSize + base - 1) / base;
        return (blocks + (left ? 1 : 0)) / 2 * base;
    }

    // The leaf of the block starting at byte offset {@code start}: blocks restart at each part.
    private static int leaf(long start) {
        long perPart = (Ed2k.PARTSIZE + Ed2k.EMBLOCKSIZE - 1) / Ed2k.EMBLOCKSIZE;
        return Math.toIntExact(start / Ed2k.PARTSIZE * perPart + start % Ed2k.PARTSIZE / Ed2k.EMBLOCKSIZE);
    }

    private static Hash20 combine(MessageDigest sha1, Hash20 left, Hash20 right) {
        byte[] both = new byte[2 * Hash20.LENGTH];
        left.copyTo(both, 0);
        right.copyTo(both, Hash20.LENGTH);
        return Hash20.of(sha1.digest(both));
    }

    /** Writes the recovery data from the leaves of a complete tree. */
    private static final class Walker {
        private final List<Hash20> leaves;
        private final MessageDigest sha1 = AichTree.sha1();

        Walker(List<Hash20> leaves) {
            this.leaves = leaves;
        }

        void down(
                long dataSize,
                boolean left,
                long base,
                long start,
                long ident,
                long partStart,
                long partSize,
                List<Node> out) {
            if (start == partStart && dataSize == partSize) {
                lowest(dataSize, left, base, start, ident, out);
                return;
            }
            long id = ident << 1 | (left ? 1 : 0);
            long leftSize = leftSize(dataSize, left, base);
            long rightSize = dataSize - leftSize;
            if (partStart < start + leftSize) {
                out.add(new Node(id << 1, hash(rightSize, false, start + leftSize)));
                down(leftSize, true, AichTree.baseSize(leftSize), start, id, partStart, partSize, out);
            } else {
                out.add(new Node(id << 1 | 1, hash(leftSize, true, start)));
                down(rightSize, false, AichTree.baseSize(rightSize), start + leftSize, id, partStart, partSize, out);
            }
        }

        private void lowest(long dataSize, boolean left, long base, long start, long ident, List<Node> out) {
            long id = ident << 1 | (left ? 1 : 0);
            if (dataSize <= base) {
                out.add(new Node(id, this.leaves.get(leaf(start))));
                return;
            }
            long leftSize = leftSize(dataSize, left, base);
            lowest(leftSize, true, AichTree.baseSize(leftSize), start, id, out);
            lowest(dataSize - leftSize, false, AichTree.baseSize(dataSize - leftSize), start + leftSize, id, out);
        }

        private Hash20 hash(long dataSize, boolean left, long start) {
            long base = AichTree.baseSize(dataSize);
            if (dataSize <= base) {
                return this.leaves.get(leaf(start));
            }
            long leftSize = leftSize(dataSize, left, base);
            return combine(this.sha1, hash(leftSize, true, start), hash(dataSize - leftSize, false, start + leftSize));
        }
    }

    /** Rebuilds the way up from the part to the root with the hashes received. */
    private static final class Checker {
        private final Map<Long, Hash20> byIdent;
        private final MessageDigest sha1 = AichTree.sha1();
        private final List<Hash20> blocks = new ArrayList<>();

        Checker(Map<Long, Hash20> byIdent) {
            this.byIdent = byIdent;
        }

        Optional<Hash20> up(
                long dataSize,
                boolean left,
                long base,
                long start,
                long ident,
                long partStart,
                long partSize,
                int level) {
            if (level > MAX_LEVELS) {
                return Optional.empty();
            }
            if (start == partStart && dataSize == partSize) {
                return part(dataSize, left, base, start, ident);
            }
            long id = ident << 1 | (left ? 1 : 0);
            long leftSize = leftSize(dataSize, left, base);
            long rightSize = dataSize - leftSize;
            if (partStart < start + leftSize) {
                Hash20 right = this.byIdent.get(id << 1);
                Optional<Hash20> below =
                        up(leftSize, true, AichTree.baseSize(leftSize), start, id, partStart, partSize, level + 1);
                return right == null ? Optional.empty() : below.map(l -> combine(this.sha1, l, right));
            }
            Hash20 leftHash = this.byIdent.get(id << 1 | 1);
            Optional<Hash20> below = up(
                    rightSize,
                    false,
                    AichTree.baseSize(rightSize),
                    start + leftSize,
                    id,
                    partStart,
                    partSize,
                    level + 1);
            return leftHash == null ? Optional.empty() : below.map(r -> combine(this.sha1, leftHash, r));
        }

        // The part's subtree from its blocks, which are kept in order for the caller.
        private Optional<Hash20> part(long dataSize, boolean left, long base, long start, long ident) {
            long id = ident << 1 | (left ? 1 : 0);
            if (dataSize <= base) {
                Hash20 block = this.byIdent.get(id);
                if (block != null) {
                    this.blocks.add(block);
                }
                return Optional.ofNullable(block);
            }
            long leftSize = leftSize(dataSize, left, base);
            Optional<Hash20> l = part(leftSize, true, AichTree.baseSize(leftSize), start, id);
            Optional<Hash20> r =
                    part(dataSize - leftSize, false, AichTree.baseSize(dataSize - leftSize), start + leftSize, id);
            return l.isPresent() && r.isPresent()
                    ? Optional.of(combine(this.sha1, l.get(), r.get()))
                    : Optional.empty();
        }
    }
}
