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
package org.njmule.core.update;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.function.LongConsumer;
import org.njmule.core.api.NewVersion;

/**
 * Downloads the package of a new version next to where it is kept, going on from what an earlier try left, and keeps it
 * only when its length and SHA-256 hash are those of the signed release information.
 */
final class PackageDownload {

    /** Streams a file to disk, as {@code WebLists.download} does. */
    @FunctionalInterface
    interface Transfer {
        void download(URI uri, Path part, long size, LongConsumer progress) throws IOException;
    }

    private PackageDownload() {}

    /** The package {@code download} at {@code file}, downloaded unless it is there already and whole. */
    static Path fetch(Transfer transfer, NewVersion.Download download, Path file, LongConsumer progress)
            throws IOException {
        if (matches(file, download)) {
            progress.accept(download.size());
            return file;
        }
        Files.createDirectories(UpdateFiles.parent(file));
        Files.deleteIfExists(file);
        Path part = file.resolveSibling(file.getFileName() + UpdateFiles.PART);
        transfer.download(download.uri(), part, download.size(), progress);
        if (!matches(part, download)) {
            // Whole but wrong: a new try starts from nothing.
            Files.deleteIfExists(part);
            throw new IOException("the downloaded package does not match its signed hash");
        }
        Files.move(part, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        return file;
    }

    /** Whether {@code file} has the length and hash of {@code download}. */
    static boolean matches(Path file, NewVersion.Download download) throws IOException {
        return Files.isRegularFile(file)
                && Files.size(file) == download.size()
                && sha256(file).equals(download.sha256());
    }

    static String sha256(Path file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("every Java has SHA-256", e);
        }
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            for (int n = in.read(buffer); n >= 0; n = in.read(buffer)) {
                digest.update(buffer, 0, n);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
