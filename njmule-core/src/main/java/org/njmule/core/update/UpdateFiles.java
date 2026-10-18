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
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The folder where new versions wait to be installed, {@code cache/update}: one folder for each version with its
 * package as downloaded ({@code .part} while it comes) and what it unpacks to, the plan of an installation handed over
 * and the mark a new version leaves once it has run long enough.
 */
final class UpdateFiles {

    static final String FOLDER = "update";
    static final String PLAN = "apply.properties";
    static final String UNPACKED = "files";
    static final String PART = ".part";

    private static final Logger logger = LoggerFactory.getLogger(UpdateFiles.class);
    // As Semantic Versioning writes them: never a step out of the folder.
    private static final Pattern VERSION = Pattern.compile("[0-9A-Za-z][0-9A-Za-z.+-]{0,63}");
    private static final String STARTED = "started-";

    private final Path root;

    UpdateFiles(Path root) {
        this.root = Objects.requireNonNull(root, "root");
    }

    /** The folder below the cache directory {@code cache}. */
    static UpdateFiles in(Path cache) {
        return new UpdateFiles(cache.resolve(FOLDER));
    }

    Path root() {
        return this.root;
    }

    Path version(String version) {
        if (!VERSION.matcher(version).matches() || version.contains("..")) {
            throw new IllegalArgumentException("not a version: " + version);
        }
        return this.root.resolve(version);
    }

    /** Where the package {@code name} of {@code version} is kept; the name is one the signed manifest allowed. */
    Path packageFile(String version, String name) {
        return version(version).resolve(name);
    }

    Path unpacked(String version) {
        return version(version).resolve(UNPACKED);
    }

    Path plan() {
        return this.root.resolve(PLAN);
    }

    /** The mark that {@code version} ran its engine long enough to be kept. */
    Path started(String version) {
        version(version);
        return this.root.resolve(STARTED + version);
    }

    /**
     * Removes the folders and marks of every version but {@code versions}; the mark of {@code running} stays, as an
     * installer may still wait for it.
     */
    void keepOnly(List<String> versions, String running) {
        if (!Files.isDirectory(this.root)) {
            return;
        }
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(this.root)) {
            for (Path entry : entries) {
                String name = String.valueOf(entry.getFileName());
                boolean kept = versions.contains(name)
                        || (name.startsWith(STARTED)
                                && (versions.contains(name.substring(STARTED.length()))
                                        || name.equals(STARTED + running)));
                if (kept || name.equals(PLAN)) {
                    continue;
                }
                if (Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) {
                    deleteTree(entry);
                } else if (name.startsWith(STARTED)) {
                    Files.deleteIfExists(entry);
                }
            }
        } catch (IOException e) {
            logger.debug("Cannot clean {}: {}", this.root, e.toString());
        }
    }

    /** The folder {@code file} lies in. */
    static Path parent(Path file) {
        Path parent = file.toAbsolutePath().getParent();
        if (parent == null) {
            throw new IllegalArgumentException("not a file in a folder: " + file);
        }
        return parent;
    }

    /** Deletes {@code tree} and all it holds, never following a link out of it. */
    static void deleteTree(Path tree) throws IOException {
        if (!Files.exists(tree, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        Files.walkFileTree(tree, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException e) throws IOException {
                if (e instanceof NoSuchFileException) {
                    return FileVisitResult.CONTINUE;
                }
                throw e;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException e) throws IOException {
                if (e != null) {
                    throw e;
                }
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
