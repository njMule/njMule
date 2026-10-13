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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.bouncycastle.crypto.digests.MD4Digest;
import org.njmule.core.api.Hash16;
import org.njmule.core.files.hash.Ed2k;

/**
 * The data file of a download. Every operation runs on the file's own virtual thread, one after the
 * other, so writes keep their order and the engine thread never waits for the disk; results come
 * back as futures. The file is sparse where the system supports it.
 */
public final class PartFileStorage implements AutoCloseable {

    private static final int READ_CHUNK = 256 * 1024;

    private final Path path;
    private final FileChannel channel;
    private final ExecutorService io;

    private PartFileStorage(Path path, FileChannel channel) {
        this.path = path;
        this.channel = channel;
        this.io =
                Executors.newSingleThreadExecutor(
                        Thread.ofVirtual().name("njmule-disk-" + path.getFileName()).factory());
    }

    /** Opens the data file, creating an empty sparse one when missing. */
    public static PartFileStorage open(Path path) throws IOException {
        FileChannel channel =
                FileChannel.open(
                        path,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.READ,
                        StandardOpenOption.WRITE,
                        StandardOpenOption.SPARSE);
        return new PartFileStorage(path, channel);
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
     * Flushes, closes and moves the finished file to {@code target}, after the queued writes. The
     * storage cannot be used afterwards.
     */
    public CompletableFuture<Path> finish(Path target) {
        return CompletableFuture.supplyAsync(
                () -> {
                    try {
                        this.channel.force(true);
                        this.channel.close();
                        Path directory = target.toAbsolutePath().getParent();
                        if (directory != null) {
                            Files.createDirectories(directory);
                        }
                        return Files.move(this.path, target, StandardCopyOption.ATOMIC_MOVE);
                    } catch (IOException e) {
                        // Another volume: fall back to copy and delete.
                        try {
                            Files.copy(this.path, target);
                            Files.delete(this.path);
                            return target;
                        } catch (IOException again) {
                            again.addSuppressed(e);
                            throw new UncheckedIOException(again);
                        }
                    }
                },
                this.io);
    }

    /** Closes the file after the queued operations. */
    @Override
    public void close() {
        this.io.execute(
                () -> {
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
