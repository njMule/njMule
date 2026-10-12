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
package org.njmule.core.persist;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Crash-safe file replacement: write {@code name.tmp} + fsync, rename {@code name} to {@code
 * name.bak}, rename {@code name.tmp} to {@code name}. At any instant either the file or its backup
 * is complete, and {@link #read} falls back to the backup. (eMule rewrites {@code known.met},
 * {@code clients.met} and {@code emfriends.met} in place and corrupts them on a crash.)
 */
public final class AtomicFiles {

    public static final String TMP_SUFFIX = ".tmp";
    public static final String BAK_SUFFIX = ".bak";

    private static final Logger LOG = LoggerFactory.getLogger(AtomicFiles.class);
    private static final int MOVE_ATTEMPTS = 6;
    private static final long FIRST_RETRY_DELAY_MILLIS = 15;

    private AtomicFiles() {}

    /** Produces the file content; must not close the stream. */
    @FunctionalInterface
    public interface Writer {
        void write(OutputStream out) throws IOException;
    }

    /** Parses the file content; may throw to reject an invalid file. Must not close the stream. */
    @FunctionalInterface
    public interface Reader<T> {
        T read(InputStream in) throws IOException;
    }

    public static Path tmpPath(Path target) {
        return target.resolveSibling(target.getFileName() + TMP_SUFFIX);
    }

    public static Path bakPath(Path target) {
        return target.resolveSibling(target.getFileName() + BAK_SUFFIX);
    }

    /** Replaces {@code target} atomically, keeping the previous version as backup. */
    public static void write(Path target, Writer writer) throws IOException {
        write(target, writer, true);
    }

    /** Replaces {@code target} atomically; on failure {@code target} is left unchanged. */
    public static void write(Path target, Writer writer, boolean keepBackup) throws IOException {
        Objects.requireNonNull(writer, "writer");
        Path absolute = target.toAbsolutePath();
        Path dir = absolute.getParent();
        if (dir != null) {
            Files.createDirectories(dir);
        }
        Path tmp = tmpPath(absolute);
        try {
            try (FileChannel channel =
                            FileChannel.open(
                                    tmp,
                                    StandardOpenOption.CREATE,
                                    StandardOpenOption.TRUNCATE_EXISTING,
                                    StandardOpenOption.WRITE);
                    OutputStream out =
                            new BufferedOutputStream(
                                    Channels.newOutputStream(channel), 64 * 1024)) {
                writer.write(out);
                out.flush();
                channel.force(true);
            }
            if (keepBackup && Files.exists(absolute)) {
                move(absolute, bakPath(absolute));
            }
            move(tmp, absolute);
        } catch (IOException | RuntimeException e) {
            deleteQuietly(tmp);
            throw e;
        }
        syncDirectory(dir);
    }

    public static void writeBytes(Path target, byte[] content) throws IOException {
        write(target, out -> out.write(content));
    }

    /**
     * Reads {@code target}, falling back to its backup when it is missing or rejected by {@code
     * reader}.
     *
     * @throws NoSuchFileException if neither file exists
     */
    public static <T> T read(Path target, Reader<T> reader) throws IOException {
        Objects.requireNonNull(reader, "reader");
        Path absolute = target.toAbsolutePath();
        Path bak = bakPath(absolute);
        Exception primaryFailure = null;
        if (Files.exists(absolute)) {
            try {
                return readOne(absolute, reader);
            } catch (IOException | RuntimeException e) {
                primaryFailure = e;
                LOG.warn("Cannot read {}, trying backup", absolute, e);
            }
        }
        if (Files.exists(bak)) {
            try {
                T value = readOne(bak, reader);
                LOG.warn("Recovered {} from its backup", absolute);
                return value;
            } catch (IOException | RuntimeException e) {
                if (primaryFailure == null) {
                    throw e;
                }
                primaryFailure.addSuppressed(e);
            }
        }
        if (primaryFailure == null) {
            throw new NoSuchFileException(absolute.toString());
        }
        throw primaryFailure instanceof IOException io
                ? io
                : new IOException("Invalid file " + absolute, primaryFailure);
    }

    /** Removes the {@code .tmp} left by an interrupted write. */
    public static boolean cleanupInterruptedWrite(Path target) {
        return deleteQuietly(tmpPath(target.toAbsolutePath()));
    }

    private static <T> T readOne(Path file, Reader<T> reader) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            return reader.read(in);
        }
    }

    // Retried because on Windows antivirus scanners and indexers briefly hold files open.
    private static void move(Path from, Path to) throws IOException {
        long delay = FIRST_RETRY_DELAY_MILLIS;
        for (int attempt = 1; ; attempt++) {
            try {
                Files.move(
                        from,
                        to,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
                return;
            } catch (FileSystemException e) {
                if (attempt >= MOVE_ATTEMPTS || e instanceof NoSuchFileException) {
                    throw e;
                }
            }
            try {
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while replacing " + to, e);
            }
            delay *= 2;
        }
    }

    // Makes the renames durable on POSIX; Windows cannot open directories as channels.
    private static void syncDirectory(Path dir) {
        if (dir == null) {
            return;
        }
        try (FileChannel channel = FileChannel.open(dir, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (IOException | UnsupportedOperationException e) {
            LOG.trace("Directory sync not supported for {}", dir, e);
        }
    }

    private static boolean deleteQuietly(Path file) {
        try {
            return Files.deleteIfExists(file);
        } catch (IOException e) {
            LOG.debug("Cannot delete {}", file, e);
            return false;
        }
    }
}
