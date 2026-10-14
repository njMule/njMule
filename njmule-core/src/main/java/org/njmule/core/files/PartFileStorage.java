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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.bouncycastle.crypto.digests.MD4Digest;
import org.njmule.core.api.Hash16;
import org.njmule.core.api.Hash20;
import org.njmule.core.files.hash.AichTree;
import org.njmule.core.files.hash.Ed2k;
import org.njmule.core.persist.AtomicFiles;
import org.njmule.core.security.PathSanitizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The data file of a download. Every operation runs on the file's own virtual thread, one after the other, so writes
 * keep their order and the engine thread never waits for the disk; results come back as futures. The file is sparse
 * where the system supports it.
 */
public final class PartFileStorage implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(PartFileStorage.class);

    private static final int READ_CHUNK = 256 * 1024;

    private final Path path;
    private final FileChannel channel;
    private final ExecutorService io;

    private PartFileStorage(Path path, FileChannel channel) {
        this.path = path;
        this.channel = channel;
        this.io = Executors.newSingleThreadExecutor(
                Thread.ofVirtual().name("njmule-disk-" + path.getFileName()).factory());
    }

    /** Opens the data file, creating an empty sparse one when missing. */
    public static PartFileStorage open(Path path) throws IOException {
        FileChannel channel;
        try {
            // SPARSE applies only to a file being created: otherwise NTFS fills a fresh file with
            // zeros up to the first write, which may be gigabytes in.
            channel = FileChannel.open(
                    path,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.READ,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.SPARSE);
        } catch (FileAlreadyExistsException e) {
            channel = FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE);
        }
        return new PartFileStorage(path, channel);
    }

    /** Whether the file still takes operations: not after {@link #finish} or {@link #close}. */
    public boolean isOpen() {
        return this.channel.isOpen() && !this.io.isShutdown();
    }

    /** Writes {@code data} at {@code position}. */
    public CompletableFuture<Void> write(long position, byte[] data) {
        return CompletableFuture.runAsync(
                () -> {
                    try {
                        ByteBuffer buffer = ByteBuffer.wrap(data);
                        long at = position;
                        while (buffer.hasRemaining()) {
                            at += this.channel.write(buffer, at);
                        }
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                },
                this.io);
    }

    /** The SHA-1 of each 180 KiB block of {@code part}, as AICH hashes them, on the I/O thread. */
    public CompletableFuture<List<Hash20>> hashBlocks(long size, int part) {
        return CompletableFuture.supplyAsync(
                () -> {
                    try {
                        MessageDigest sha1 = AichTree.sha1();
                        ByteBuffer buffer = ByteBuffer.allocate(Ed2k.EMBLOCKSIZE);
                        List<Hash20> blocks = new ArrayList<>();
                        long start = Ed2k.partStart(part);
                        long end = start + Ed2k.partLength(size, part);
                        for (long at = start; at < end; at += Ed2k.EMBLOCKSIZE) {
                            int length = (int) Math.min(Ed2k.EMBLOCKSIZE, end - at);
                            buffer.clear().limit(length);
                            while (buffer.hasRemaining()) {
                                if (this.channel.read(buffer, at + buffer.position()) < 0) {
                                    break;
                                }
                            }
                            sha1.update(buffer.array(), 0, buffer.position());
                            blocks.add(Hash20.of(sha1.digest()));
                        }
                        return blocks;
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                },
                this.io);
    }

    /** The MD4 of a data part, read back from the disk after the writes queued before it. */
    public CompletableFuture<Hash16> hashPart(long size, int part) {
        return CompletableFuture.supplyAsync(
                () -> {
                    try {
                        MD4Digest md4 = new MD4Digest();
                        ByteBuffer buffer = ByteBuffer.allocate(READ_CHUNK);
                        long at = Ed2k.partStart(part);
                        long end = at + Ed2k.partLength(size, part);
                        while (at < end) {
                            buffer.clear().limit((int) Math.min(READ_CHUNK, end - at));
                            int n = this.channel.read(buffer, at);
                            if (n < 0) {
                                // A part of a sparse file never written reads as zeros elsewhere;
                                // past the end there is nothing, so the hash cannot match.
                                break;
                            }
                            md4.update(buffer.array(), 0, n);
                            at += n;
                        }
                        byte[] out = new byte[Hash16.LENGTH];
                        md4.doFinal(out, 0);
                        return Hash16.of(out);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                },
                this.io);
    }

    /**
     * Flushes, closes and moves the finished file to {@code target}, after the queued writes. The storage cannot be
     * used afterwards.
     */
    public CompletableFuture<Path> finish(Path directory, String name) {
        return CompletableFuture.supplyAsync(
                () -> {
                    try {
                        this.channel.force(true);
                        this.channel.close();
                        Files.createDirectories(directory);
                        // Reserved and moved in one task, so two finished files never take the
                        // same name.
                        Path target = PathSanitizer.reserve(directory, name);
                        try {
                            AtomicFiles.move(this.path, target);
                        } catch (AtomicMoveNotSupportedException e) {
                            this.copyTo(target);
                        } catch (IOException e) {
                            Files.deleteIfExists(target);
                            throw e;
                        }
                        return target;
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                },
                this.io);
    }

    // Another volume: a hidden copy, flushed, then renamed over the reserved name, so a crash never leaves a truncated
    // file to share; then the part file goes, and if it cannot, the copy is still complete.
    private void copyTo(Path target) throws IOException {
        Path copy = AtomicFiles.tmpPath(target);
        try {
            Files.copy(this.path, copy, StandardCopyOption.REPLACE_EXISTING);
            try (FileChannel channel = FileChannel.open(copy, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            AtomicFiles.move(copy, target);
        } catch (IOException e) {
            Files.deleteIfExists(copy);
            Files.deleteIfExists(target);
            throw e;
        }
        try {
            Files.delete(this.path);
        } catch (IOException e) {
            logger.warn("Cannot delete {} after copying it to {}", this.path, target, e);
        }
    }

    /** Closes the file after the queued operations; closing it again does nothing. */
    @Override
    public void close() {
        if (this.io.isShutdown()) {
            return;
        }
        this.io.execute(() -> {
            try {
                if (this.channel.isOpen()) {
                    this.channel.force(true);
                    this.channel.close();
                }
            } catch (IOException e) {
                // The data written so far stays on disk; the next start re-checks parts.
            }
        });
        this.io.shutdown();
    }
}
