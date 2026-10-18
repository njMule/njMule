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

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Puts the unpacked files of a new version in the place of the installed ones, so that both can be told apart until the
 * new version proves itself: each file is copied beside its old self as {@code name.new}, the old one renamed
 * {@code name.old} and the new one to the name. Windows lets a running program and its libraries be renamed, so the
 * swap works while they run; anything the package does not hold (a {@code portable.flag}, the data) stays as it is.
 * What the swap did is kept in a journal, so that it can be undone ({@link #rollBack}) or made final ({@link #cleanUp})
 * by a later process. For the macOS app the bundle is the one file swapped.
 */
final class Swap {

    static final String OLD = ".old";
    static final String NEW = ".new";

    private static final Logger logger = LoggerFactory.getLogger(Swap.class);
    private static final String REPLACED = "replaced ";
    private static final String ADDED = "added ";
    private static final String FOLDER = "folder ";
    private static final String BUNDLE = "bundle";

    private final Path staged;
    private final Path target;
    private final Path journal;
    private final Copier copier;
    private final boolean bundle;

    /** Copies a file or a whole bundle. */
    @FunctionalInterface
    interface Copier {
        void copy(Path from, Path to) throws IOException;
    }

    /** A copy that keeps the permissions; a bundle is copied file by file. */
    static final Copier FILES = Swap::copyTree;

    /** The bundle copied by macOS's {@code ditto}, which keeps the extended attributes a signature may need. */
    static Copier ditto(Commands commands) {
        return (from, to) -> commands.require(List.of("ditto", from.toString(), to.toString()), Duration.ofMinutes(5));
    }

    private Swap(Path staged, Path target, Path journal, Copier copier, boolean bundle) {
        this.staged = Objects.requireNonNull(staged, "staged");
        this.target = Objects.requireNonNull(target, "target");
        this.journal = Objects.requireNonNull(journal, "journal");
        this.copier = Objects.requireNonNull(copier, "copier");
        this.bundle = bundle;
    }

    /**
     * The files unpacked in {@code staged} into the installed folder {@code target}; the steps go to {@code journal}.
     */
    static Swap folder(Path staged, Path target, Path journal) {
        return folder(staged, target, journal, FILES);
    }

    /** As above, each file copied by {@code copier}. */
    static Swap folder(Path staged, Path target, Path journal, Copier copier) {
        return new Swap(staged, target, journal, copier, false);
    }

    /** The bundle {@code staged} in the place of the installed bundle {@code target}, copied by {@code copier}. */
    static Swap bundle(Path staged, Path target, Path journal, Copier copier) {
        return new Swap(staged, target, journal, copier, true);
    }

    /** Puts the new files in place; on any failure puts the old ones back and fails. */
    void apply() throws IOException {
        Files.createDirectories(UpdateFiles.parent(this.journal));
        // Each step is written as soon as it is done: a process stopped half-way can be undone by the next.
        List<String> done = new ArrayList<>();
        try (BufferedWriter steps = Files.newBufferedWriter(this.journal, StandardCharsets.UTF_8)) {
            try {
                if (this.bundle) {
                    applyBundle(done, steps);
                } else {
                    applyFiles(done, steps);
                }
            } catch (IOException | RuntimeException e) {
                undo(done);
                throw e;
            }
        }
    }

    private static void done(List<String> done, BufferedWriter steps, String step) throws IOException {
        steps.write(step);
        steps.newLine();
        steps.flush();
        done.add(step);
    }

    // Copied beside it first: the same volume, so that both renames are instant.
    private void applyBundle(List<String> done, BufferedWriter steps) throws IOException {
        Path old = sibling(this.target, OLD);
        Path fresh = sibling(this.target, NEW);
        UpdateFiles.deleteTree(old);
        UpdateFiles.deleteTree(fresh);
        this.copier.copy(this.staged, fresh);
        Files.move(this.target, old);
        done(done, steps, BUNDLE);
        Files.move(fresh, this.target);
    }

    private void applyFiles(List<String> done, BufferedWriter steps) throws IOException {
        List<String> files = files(this.staged);
        // Every copy first: a full disk stops the swap before anything was renamed.
        for (String name : files) {
            Path to = this.target.resolve(name);
            Path parent = to.getParent();
            for (Path missing : missing(parent)) {
                Files.createDirectory(missing);
                done(
                        done,
                        steps,
                        FOLDER + this.target.relativize(missing).toString().replace('\\', '/'));
            }
            Path fresh = sibling(to, NEW);
            Files.deleteIfExists(fresh);
            this.copier.copy(this.staged.resolve(name), fresh);
        }
        for (String name : files) {
            Path to = this.target.resolve(name);
            Path fresh = sibling(to, NEW);
            if (Files.exists(to, LinkOption.NOFOLLOW_LINKS)) {
                Path old = sibling(to, OLD);
                // What an earlier update left, not yet made final: this version runs, so it is no longer needed.
                Files.deleteIfExists(old);
                Files.move(to, old);
                done(done, steps, REPLACED + name);
            } else {
                done(done, steps, ADDED + name);
            }
            Files.move(fresh, to);
        }
    }

    // The folders to create for parent, outermost first.
    private List<Path> missing(Path parent) {
        List<Path> missing = new ArrayList<>();
        for (Path p = parent; p != null && !p.equals(this.target) && !Files.exists(p); p = p.getParent()) {
            missing.addFirst(p);
        }
        return missing;
    }

    /** Puts back what the journal says the swap replaced or added. */
    void rollBack() throws IOException {
        undo(readJournal());
        Files.deleteIfExists(this.journal);
    }

    /** Removes the old files the swap kept: the new version is there to stay. */
    void cleanUp() throws IOException {
        IOException first = null;
        for (String step : readJournal()) {
            if (!step.startsWith(REPLACED) && !step.equals(BUNDLE)) {
                continue;
            }
            Path old = sibling(step.equals(BUNDLE) ? this.target : place(step.substring(REPLACED.length())), OLD);
            try {
                UpdateFiles.deleteTree(old);
            } catch (IOException e) {
                // A file still in use, such as the program that waited for this one: tried again later.
                if (first == null) {
                    first = e;
                }
            }
        }
        if (first != null) {
            throw first;
        }
        Files.deleteIfExists(this.journal);
    }

    // In reverse: the files back, then the folders the swap made.
    private void undo(List<String> done) throws IOException {
        IOException first = null;
        for (String step : done.reversed()) {
            try {
                if (step.startsWith(REPLACED) || step.equals(BUNDLE)) {
                    Path place = step.equals(BUNDLE) ? this.target : place(step.substring(REPLACED.length()));
                    Path old = sibling(place, OLD);
                    if (Files.exists(old, LinkOption.NOFOLLOW_LINKS)) {
                        UpdateFiles.deleteTree(place);
                        Files.move(old, place);
                    }
                } else if (step.startsWith(ADDED)) {
                    Files.deleteIfExists(place(step.substring(ADDED.length())));
                } else if (step.startsWith(FOLDER)) {
                    Path folder = place(step.substring(FOLDER.length()));
                    try (var left = Files.list(folder)) {
                        if (left.findAny().isEmpty()) {
                            Files.delete(folder);
                        }
                    }
                }
            } catch (IOException e) {
                logger.warn("Cannot undo \"{}\" of the update: {}", step, e.toString());
                if (first == null) {
                    first = e;
                }
            }
        }
        if (first != null) {
            throw first;
        }
    }

    private Path place(String name) {
        return this.target.resolve(name);
    }

    private List<String> readJournal() throws IOException {
        if (!Files.isRegularFile(this.journal)) {
            return List.of();
        }
        List<String> steps = new ArrayList<>();
        for (String line : Files.readAllLines(this.journal, StandardCharsets.UTF_8)) {
            String step = line.strip();
            if (step.equals(BUNDLE)) {
                steps.add(step);
                continue;
            }
            String name = step.startsWith(REPLACED)
                    ? step.substring(REPLACED.length())
                    : step.startsWith(ADDED)
                            ? step.substring(ADDED.length())
                            : step.startsWith(FOLDER) ? step.substring(FOLDER.length()) : null;
            // The journal is ours, in the cache; still, no step leaves the target.
            if (name == null || !Archives.checked(name).equals(name)) {
                throw new IOException("a damaged update journal: " + this.journal);
            }
            steps.add(step);
        }
        return steps;
    }

    private static Path sibling(Path path, String suffix) {
        return path.resolveSibling(path.getFileName() + suffix);
    }

    // The regular files below root, by their names relative to it with forward slashes.
    private static List<String> files(Path root) throws IOException {
        List<String> names = new ArrayList<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (!attrs.isRegularFile()) {
                    throw new IOException("not a regular file: " + file);
                }
                names.add(root.relativize(file).toString().replace('\\', '/'));
                return FileVisitResult.CONTINUE;
            }
        });
        names.sort(null);
        return names;
    }

    private static void copyTree(Path from, Path to) throws IOException {
        if (!Files.isDirectory(from, LinkOption.NOFOLLOW_LINKS)) {
            Files.copy(from, to, StandardCopyOption.COPY_ATTRIBUTES, LinkOption.NOFOLLOW_LINKS);
            return;
        }
        Files.walkFileTree(from, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Files.createDirectories(to.resolve(from.relativize(dir).toString()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.copy(
                        file,
                        to.resolve(from.relativize(file).toString()),
                        StandardCopyOption.COPY_ATTRIBUTES,
                        LinkOption.NOFOLLOW_LINKS);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
