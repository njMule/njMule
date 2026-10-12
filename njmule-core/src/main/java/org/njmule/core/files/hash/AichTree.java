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
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import org.njmule.core.api.Hash20;

/**
 * The AICH SHA-1 hash tree ({@code SHAHashSet.cpp}). Leaves are the 180 KiB blocks of each part;
 * the tree is binary but splits at whole PARTSIZE boundaries above the part level and gives the odd
 * element to the left subtree of a left branch and to the right subtree of a right branch.
 */
public final class AichTree {

    private final long size;
    private final List<Hash20> blockHashes;
    private final Hash20 masterHash;
    private final List<Hash20> partHashes;

    private AichTree(long size, List<Hash20> blockHashes) {
        this.size = size;
        this.blockHashes = List.copyOf(blockHashes);
        List<Hash20> parts = new ArrayList<>();
        Builder builder = new Builder(this.blockHashes, size > Ed2k.PARTSIZE ? parts : null);
        this.masterHash = builder.node(size, true, baseSize(size));
        this.partHashes = List.copyOf(parts);
    }

    /** Rebuilds the tree from its leaves in file order, as stored in known2_64.met. */
    public static AichTree fromBlockHashes(long size, List<Hash20> blockHashes) {
        if (size <= 0) {
            throw new IllegalArgumentException("AICH needs a non-empty file");
        }
        Ed2k.checkSize(size);
        if (blockHashes.size() != blockCount(size)) {
            throw new IllegalArgumentException(
                    "a file of "
                            + size
                            + " bytes has "
                            + blockCount(size)
                            + " AICH blocks, got "
                            + blockHashes.size());
        }
        return new AichTree(size, blockHashes);
    }

    /** Leaves: 180 KiB blocks restarting at every part boundary. */
    public static long blockCount(long size) {
        Ed2k.checkSize(size);
        long fullParts = size / Ed2k.PARTSIZE;
        long rest = size % Ed2k.PARTSIZE;
        long perPart = (Ed2k.PARTSIZE + Ed2k.EMBLOCKSIZE - 1) / Ed2k.EMBLOCKSIZE;
        return fullParts * perPart + (rest + Ed2k.EMBLOCKSIZE - 1) / Ed2k.EMBLOCKSIZE;
    }

    public long size() {
        return this.size;
    }

    public Hash20 masterHash() {
        return this.masterHash;
    }

    /** One hash per data part for files larger than one part, otherwise empty. */
    public List<Hash20> partHashes() {
        return this.partHashes;
    }

    public List<Hash20> blockHashes() {
        return this.blockHashes;
    }

    static long baseSize(long dataSize) {
        return dataSize <= Ed2k.PARTSIZE ? Ed2k.EMBLOCKSIZE : Ed2k.PARTSIZE;
    }

    static MessageDigest sha1() {
        try {
            return MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 is required by the Java platform", e);
        }
    }

    private static final class Builder {
        private final List<Hash20> leaves;
        private final List<Hash20> parts;
        private final MessageDigest sha1 = sha1();
        private int next;

        Builder(List<Hash20> leaves, List<Hash20> parts) {
            this.leaves = leaves;
            this.parts = parts;
        }

        // CAICHHashTree::FindHash / LoadLowestLevelHashes: the same split, depth ≤ 22. A part node
        // is the first node below a PARTSIZE-based one.
        Hash20 node(long dataSize, boolean left, long base) {
            if (dataSize <= base) {
                return this.leaves.get(this.next++);
            }
            long blocks = (dataSize + base - 1) / base;
            long leftSize = (blocks + (left ? 1 : 0)) / 2 * base;
            long rightSize = dataSize - leftSize;
            Hash20 l = this.child(leftSize, true, base);
            Hash20 r = this.child(rightSize, false, base);
            byte[] both = new byte[2 * Hash20.LENGTH];
            l.copyTo(both, 0);
            r.copyTo(both, Hash20.LENGTH);
            return Hash20.of(this.sha1.digest(both));
        }

        private Hash20 child(long dataSize, boolean left, long parentBase) {
            long base = baseSize(dataSize);
            Hash20 hash = this.node(dataSize, left, base);
            if (this.parts != null && parentBase == Ed2k.PARTSIZE && base == Ed2k.EMBLOCKSIZE) {
                this.parts.add(hash);
            }
            return hash;
        }
    }
}
