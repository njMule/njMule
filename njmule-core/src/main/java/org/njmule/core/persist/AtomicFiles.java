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
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Crash-safe file replacement: write {@code name.tmp} + fsync, keep the current {@code name} as {@code name.bak} (a
 * hard link, or a copy where links are not supported), then rename {@code name.tmp} over {@code name}. The file is
 * never missing, even when the last rename fails, and {@link #read} falls back to the backup; a file rewritten in place
 * would be corrupted by a crash.
 */
public final class AtomicFiles {

    public static final String TMP_SUFFIX = ".tmp";
    public static final String BAK_SUFFIX = ".bak";

    private static final Logger logger = LoggerFactory.getLogger(AtomicFiles.class);
    private static final int MOVE_ATTEMPTS = 6;
    private static final long FIRST_RETRY_DELAY_MILLIS = 15;
    private static final Set<PosixFilePermission> OWNER_READ_WRITE = PosixFilePermissions.fromString("rw-------");
    private static final FileAttribute<Set<PosixFilePermission>> OWNER_ONLY =
            PosixFilePermissions.asFileAttribute(OWNER_READ_WRITE);

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
        write(target, writer, keepBackup, false);
    }

    /**
     * Replaces {@code target} atomically; on failure {@code target} is left unchanged.
     *
     * @param secret the file and its backup readable by their owner only, where the file system has POSIX permissions
     */
    public static void write(Path target, Writer writer, boolean keepBackup, boolean secret) throws IOException {
        Objects.requireNonNull(writer, "writer");
        Path absolute = target.toAbsolutePath();
        Path dir = absolute.getParent();
        if (dir != null) {
            Files.createDirectories(dir);
        }
        Path tmp = tmpPath(absolute);
        FileAttribute<?>[] attributes = new FileAttribute<?>[0];
        boolean ownerOnly =
                secret && tmp.getFileSystem().supportedFileAttributeViews().contains("posix");
        if (ownerOnly) {
            attributes = new FileAttribute<?>[] {OWNER_ONLY};
            // The permissions are set when the file is created, so not on a leftover.
            Files.deleteIfExists(tmp);
        }
        try {
            try (FileChannel channel = FileChannel.open(
                            tmp,
                            Set.of(
                                    StandardOpenOption.CREATE,
                                    StandardOpenOption.TRUNCATE_EXISTING,
                                    StandardOpenOption.WRITE),
                            attributes);
                    OutputStream out = new BufferedOutputStream(Channels.newOutputStream(channel), 64 * 1024)) {
                writer.write(out);
                out.flush();
                channel.force(true);
            }
            if (keepBackup && Files.exists(absolute)) {
                backup(absolute, bakPath(absolute), ownerOnly);
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

    /** As {@link #writeBytes}, for a file only its owner may read: a key or an identity. */
    public static void writeSecret(Path target, byte[] content) throws IOException {
        write(target, out -> out.write(content), true, true);
    }

    /**
     * Reads {@code target}, falling back to its backup when it is missing or rejected by {@code reader}. A target held
     * by another program is tried again for a moment first.
     *
     * @throws NoSuchFileException if neither file exists
     */
    public static <T> T read(Path target, Reader<T> reader) throws IOException {
        return read(target, reader, true);
    }

    /**
     * As {@link #read(Path, Reader)}; with {@code backupIfHeld} false a target that still cannot be opened fails
     * instead of yielding the older backup, which the caller would then save over it.
     */
    public static <T> T read(Path target, Reader<T> reader, boolean backupIfHeld) throws IOException {
        return readTelling(target, reader, backupIfHeld).value();
    }

    /** What a read returned, and whether it came from the backup because the file was missing or rejected. */
    public record Read<T>(T value, boolean fromBackup) {}

    /** As {@link #read(Path, Reader)}, telling whether the backup was read: the file is then worth saving again. */
    public static <T> Read<T> readTelling(Path target, Reader<T> reader) throws IOException {
        return readTelling(target, reader, true);
    }

    private static <T> Read<T> readTelling(Path target, Reader<T> reader, boolean backupIfHeld) throws IOException {
        Objects.requireNonNull(reader, "reader");
        Path absolute = target.toAbsolutePath();
        Path bak = bakPath(absolute);
        Exception primaryFailure = null;
        if (Files.exists(absolute)) {
            try {
                return new Read<>(readOne(absolute, reader), false);
            } catch (FileSystemException e) {
                if (!backupIfHeld && !(e instanceof NoSuchFileException)) {
                    throw e;
                }
                primaryFailure = e;
                logger.warn("Cannot open {}, trying backup", absolute, e);
            } catch (IOException | RuntimeException e) {
                primaryFailure = e;
                logger.warn("Cannot read {}, trying backup", absolute, e);
            }
        }
        if (Files.exists(bak)) {
            try {
                T value = readOne(bak, reader);
                logger.warn("Recovered {} from its backup", absolute);
                return new Read<>(value, true);
            } catch (IOException | RuntimeException e) {
                if (primaryFailure == null) {
                    throw e instanceof IOException io ? io : new IOException("Invalid file " + bak, e);
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
        try (InputStream in = open(file)) {
            return reader.read(in);
        }
    }

    // Retried like a move: a file being scanned or indexed is not corrupt.
    private static InputStream open(Path file) throws IOException {
        long delay = FIRST_RETRY_DELAY_MILLIS;
        for (int attempt = 1; ; attempt++) {
            try {
                return Files.newInputStream(file);
            } catch (FileSystemException e) {
                if (attempt >= MOVE_ATTEMPTS || e instanceof NoSuchFileException) {
                    throw e;
                }
            }
            pause(delay, file);
            delay *= 2;
        }
    }

    /**
     * Moves {@code from} over {@code to} atomically, retrying because on Windows antivirus scanners and indexers
     * briefly hold files open; fails at once when the move needs a copy.
     */
    public static void move(Path from, Path to) throws IOException {
        long delay = FIRST_RETRY_DELAY_MILLIS;
        for (int attempt = 1; ; attempt++) {
            try {
                Files.move(from, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                return;
            } catch (FileSystemException e) {
                if (attempt >= MOVE_ATTEMPTS
                        || e instanceof NoSuchFileException
                        || e instanceof AtomicMoveNotSupportedException) {
                    throw e;
                }
            }
            pause(delay, to);
            delay *= 2;
        }
    }

    /** Deletes {@code file} if it is there, retrying as {@link #move} does while another program holds it. */
    public static void delete(Path file) throws IOException {
        long delay = FIRST_RETRY_DELAY_MILLIS;
        for (int attempt = 1; ; attempt++) {
            try {
                Files.deleteIfExists(file);
                return;
            } catch (FileSystemException e) {
                if (attempt >= MOVE_ATTEMPTS || e instanceof DirectoryNotEmptyException) {
                    throw e;
                }
            }
            pause(delay, file);
            delay *= 2;
        }
    }

    private static void pause(long millis, Path file) throws IOException {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for " + file, e);
        }
    }

    // A link costs nothing; FAT volumes have none, so they get a copy. Without a fresh backup the old one stays.
    private static void backup(Path file, Path bak, boolean ownerOnly) {
        try {
            Files.deleteIfExists(bak);
            try {
                Files.createLink(bak, file);
            } catch (UnsupportedOperationException | FileSystemException e) {
                Files.copy(file, bak, StandardCopyOption.REPLACE_EXISTING);
            }
            // The backup is the file being replaced, which an older version may have left readable by all.
            if (ownerOnly) {
                Files.setPosixFilePermissions(bak, OWNER_READ_WRITE);
            }
        } catch (IOException e) {
            logger.warn("Cannot refresh the backup {}", bak, e);
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
            logger.trace("Directory sync not supported for {}", dir, e);
        }
    }

    private static boolean deleteQuietly(Path file) {
        try {
            return Files.deleteIfExists(file);
        } catch (IOException e) {
            logger.debug("Cannot delete {}", file, e);
            return false;
        }
    }
}
