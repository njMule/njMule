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

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Timestamped zip copies of the configuration, the latest few kept: what a disk failure or a bad edit would otherwise
 * take. Lock and handoff files, temporary and backup copies are left out, and so are the AICH hash sets, which can be
 * large and are rebuilt by hashing the files again.
 */
public final class ConfigBackup {

    /** The folder of the copies, below the data directory. */
    public static final String FOLDER = "backups";

    private static final String PREFIX = "njmule-config-";
    private static final String SUFFIX = ".zip";
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT);
    private static final Pattern NAME =
            Pattern.compile(Pattern.quote(PREFIX) + "\\d{8}-\\d{6}" + Pattern.quote(SUFFIX));
    private static final Set<String> LEFT_OUT = Set.of("njmule.lock", "handoff", "known2_64.met");

    private ConfigBackup() {}

    /**
     * Writes a copy of the files of {@code config} into {@code folder} and removes the oldest copies beyond
     * {@code keep}; returns the new copy.
     */
    public static Path write(Path config, Path folder, int keep, Clock clock) throws IOException {
        if (keep < 1) {
            throw new IllegalArgumentException("at least one copy is kept: " + keep);
        }
        Files.createDirectories(folder);
        // In UTC: local time repeats an hour when summer time ends, and the names must sort as the copies were made.
        String name = PREFIX + STAMP.format(LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)) + SUFFIX;
        Path target = folder.resolve(name);
        List<Path> files = files(config);
        AtomicFiles.write(
                target,
                out -> {
                    // Finished, not closed: the stream belongs to the atomic write, which forces it to disk.
                    ZipOutputStream zip = new ZipOutputStream(out);
                    for (Path file : files) {
                        zip.putNextEntry(new ZipEntry(config.relativize(file).toString()));
                        Files.copy(file, zip);
                        zip.closeEntry();
                    }
                    zip.finish();
                },
                false,
                true);
        prune(folder, keep, target);
        return target;
    }

    /** The copies in {@code folder}, oldest first. */
    public static List<Path> copies(Path folder) throws IOException {
        List<Path> copies = new ArrayList<>();
        if (!Files.isDirectory(folder)) {
            return copies;
        }
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(folder, PREFIX + "*" + SUFFIX)) {
            for (Path entry : entries) {
                // A file of the user that only looks like a copy is not ours to prune.
                if (NAME.matcher(String.valueOf(entry.getFileName())).matches()) {
                    copies.add(entry);
                }
            }
        }
        // The stamp sorts as the time.
        copies.sort(null);
        return copies;
    }

    private static List<Path> files(Path config) throws IOException {
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(config)) {
            for (Path entry : entries) {
                String name = config.relativize(entry).toString();
                if (Files.isRegularFile(entry)
                        && !LEFT_OUT.contains(name)
                        && !name.endsWith(".tmp")
                        && !name.endsWith(".bak")) {
                    files.add(entry);
                }
            }
        }
        files.sort(null);
        return files;
    }

    // The new copy stays whatever its name sorts after: a clock set back gives it an older one.
    private static void prune(Path folder, int keep, Path written) throws IOException {
        List<Path> others = copies(folder);
        others.remove(written);
        for (int i = 0; i < others.size() - (keep - 1); i++) {
            Files.deleteIfExists(others.get(i));
        }
    }
}
