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

import java.io.EOFException;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.LongConsumer;
import org.njmule.core.api.Hash16;
import org.njmule.core.api.Hash20;
import org.njmule.core.crypto.Md4;

/** Computes the eD2K hash set and the AICH tree in one pass over the data, block by block. */
public final class FileHasher {

    /** The hashes of a file; there is no AICH tree for an empty file. */
    public record Result(Ed2kHashSet ed2k, Optional<AichTree> aich) {}

    private FileHasher() {}

    public static Result hash(Path file) throws IOException {
        return hash(file, bytes -> {});
    }

    public static Result hash(Path file, LongConsumer progress) throws IOException {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            return hash(channel, channel.size(), progress);
        }
    }

    /**
     * Hashes exactly {@code size} bytes from {@code in}; {@code progress} receives the bytes hashed so far after each
     * block. Interrupting the thread cancels the hashing.
     */
    public static Result hash(ReadableByteChannel in, long size, LongConsumer progress) throws IOException {
        Ed2k.checkSize(size);
        Md4 md4 = new Md4();
        MessageDigest sha1 = AichTree.sha1();
        byte[] block = new byte[Ed2k.EMBLOCKSIZE];
        ByteBuffer buffer = ByteBuffer.wrap(block);
        List<Hash16> parts = new ArrayList<>();
        List<Hash20> blocks = new ArrayList<>();
        long done = 0;

        for (int part = 0, n = Ed2k.partCount(size); part < n; part++) {
            for (long left = Ed2k.partLength(size, part); left > 0; ) {
                int length = (int) Math.min(Ed2k.EMBLOCKSIZE, left);
                readFully(in, buffer, length);
                md4.update(block, 0, length);
                sha1.update(block, 0, length);
                blocks.add(Hash20.of(sha1.digest()));
                left -= length;
                done += length;
                progress.accept(done);
                if (Thread.interrupted()) {
                    throw new InterruptedIOException("hashing cancelled");
                }
            }
            parts.add(md4.digest());
        }

        Ed2kHashSet ed2k;
        if (size < Ed2k.PARTSIZE) {
            ed2k = new Ed2kHashSet(size, size == 0 ? Ed2k.EMPTY_PART_HASH : parts.getFirst(), List.of());
        } else {
            if (size % Ed2k.PARTSIZE == 0) {
                parts.add(Ed2k.EMPTY_PART_HASH);
            }
            ed2k = new Ed2kHashSet(size, Ed2k.fileHash(parts), parts);
        }
        Optional<AichTree> aich = size == 0 ? Optional.empty() : Optional.of(AichTree.fromBlockHashes(size, blocks));
        return new Result(ed2k, aich);
    }

    private static void readFully(ReadableByteChannel in, ByteBuffer buffer, int length) throws IOException {
        buffer.clear().limit(length);
        while (buffer.hasRemaining()) {
            if (in.read(buffer) < 0) {
                throw new EOFException("data ended before the expected size");
            }
        }
    }
}
