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

import java.util.List;
import org.njmule.core.api.Hash16;
import org.njmule.core.crypto.Md4;

/** eD2K file geometry and hash rules. */
public final class Ed2k {

    /** Size of an eD2K part ("chunk"), the unit of MD4 part hashes and of sharing. */
    public static final long PARTSIZE = 9_728_000L;

    /** Size of a transfer block and of an AICH leaf. */
    public static final int EMBLOCKSIZE = 184_320;

    /** Largest file the network supports (2^38 bytes = 256 GiB). */
    public static final long MAX_FILE_SIZE = 1L << 38;

    /** Largest file before 64-bit sizes: (2^32 - 1) / PARTSIZE * PARTSIZE. */
    public static final long OLD_MAX_FILE_SIZE = 4_290_048_000L;

    /** MD4 of no data: the hash of the empty part that ends files sized a multiple of PARTSIZE. */
    public static final Hash16 EMPTY_PART_HASH = md4(new byte[0], 0, 0);

    private Ed2k() {}

    /** Number of data parts: ⌈size / PARTSIZE⌉. */
    public static int partCount(long size) {
        checkSize(size);
        return (int) ((size + PARTSIZE - 1) / PARTSIZE);
    }

    /**
     * Number of eD2K parts (part hashes, OP_FILESTATUS bits): {@code size / PARTSIZE + 1}, which counts an extra empty
     * part when the size is an exact multiple of PARTSIZE.
     */
    public static int ed2kPartCount(long size) {
        checkSize(size);
        return size == 0 ? 0 : (int) (size / PARTSIZE + 1);
    }

    /** Part hashes in the hash set: none below one part, otherwise {@link #ed2kPartCount}. */
    public static int hashSetSize(long size) {
        return size < PARTSIZE ? 0 : ed2kPartCount(size);
    }

    public static long partStart(int part) {
        return part * PARTSIZE;
    }

    /** Bytes in a data part ({@code part} < {@link #partCount}). */
    public static long partLength(long size, int part) {
        return Math.min(PARTSIZE, size - part * PARTSIZE);
    }

    /** The file hash: the only part hash for small files, else MD4 of the part hashes. */
    public static Hash16 fileHash(List<Hash16> partHashes) {
        if (partHashes.isEmpty()) {
            throw new IllegalArgumentException("no part hashes");
        }
        if (partHashes.size() == 1) {
            return partHashes.getFirst();
        }
        byte[] all = new byte[partHashes.size() * Hash16.LENGTH];
        for (int i = 0; i < partHashes.size(); i++) {
            partHashes.get(i).copyTo(all, i * Hash16.LENGTH);
        }
        return md4(all, 0, all.length);
    }

    /** MD4 (RFC 1320), the digest of eD2K part and file hashes. */
    public static Hash16 md4(byte[] data, int offset, int length) {
        return Md4.hash(data, offset, length);
    }

    public static void checkSize(long size) {
        if (size < 0 || size > MAX_FILE_SIZE) {
            throw new IllegalArgumentException("file size out of range: " + size);
        }
    }
}
