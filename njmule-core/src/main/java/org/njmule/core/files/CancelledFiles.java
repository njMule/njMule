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
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import java.util.random.RandomGenerator;
import org.njmule.core.api.Hash16;
import org.njmule.core.persist.AtomicFiles;
import org.njmule.core.persist.format.CancelledMet;
import org.njmule.core.protocol.io.DecodeException;

/**
 * The downloads cancelled before, in {@code cancelled.met}: each file under a key made with a seed of its own, so that
 * the list tells whether a file was cancelled but not which files were.
 */
public final class CancelledFiles {

    public static final String FILE_NAME = "cancelled.met";

    private final Path file;
    private final RandomGenerator random;
    private int seed;
    private final Set<Hash16> keys = new LinkedHashSet<>();

    private CancelledFiles(Path file, RandomGenerator random) {
        this.file = Objects.requireNonNull(file, "file");
        this.random = Objects.requireNonNull(random, "random");
    }

    /** An empty list, saved to {@code file} when a download is cancelled. */
    public static CancelledFiles empty(Path file, RandomGenerator random) {
        return new CancelledFiles(file, random);
    }

    /** Reads {@code file}; a missing one is an empty list. */
    public static CancelledFiles load(Path file, RandomGenerator random) throws IOException {
        CancelledFiles cancelled = new CancelledFiles(file, random);
        AtomicFiles.cleanupInterruptedWrite(file);
        byte[] data;
        try {
            data = Files.readAllBytes(file);
        } catch (NoSuchFileException e) {
            return cancelled;
        }
        try {
            CancelledMet.Contents contents = CancelledMet.read(data);
            if (contents.legacy()) {
                // The old format holds the file hashes themselves: they get keys.
                cancelled.seed = cancelled.newSeed();
                contents.keys().forEach(h -> cancelled.keys.add(CancelledMet.key(cancelled.seed, h)));
            } else {
                cancelled.seed = contents.seed();
                cancelled.keys.addAll(contents.keys());
            }
        } catch (DecodeException e) {
            throw new IOException(file + ": " + e.getMessage(), e);
        }
        return cancelled;
    }

    public boolean contains(Hash16 fileHash) {
        return this.seed != 0 && this.keys.contains(CancelledMet.key(this.seed, fileHash));
    }

    /** Remembers that the download of {@code fileHash} was cancelled. */
    public void add(Hash16 fileHash) throws IOException {
        if (this.seed == 0) {
            this.seed = this.newSeed();
        }
        if (this.keys.add(CancelledMet.key(this.seed, fileHash))) {
            this.save();
        }
    }

    /** Forgets every cancelled download. */
    public void clear() throws IOException {
        if (!this.keys.isEmpty()) {
            this.keys.clear();
            this.save();
        }
    }

    public int size() {
        return this.keys.size();
    }

    /** Whether a file was cancelled, as the list is now. */
    public Predicate<Hash16> snapshot() {
        int at = this.seed;
        Set<Hash16> now = Set.copyOf(this.keys);
        return h -> at != 0 && now.contains(CancelledMet.key(at, h));
    }

    private void save() throws IOException {
        AtomicFiles.writeBytes(this.file, CancelledMet.write(this.seed, List.copyOf(this.keys)));
    }

    // Never 0, which means "no seed yet".
    private int newSeed() {
        int s;
        do {
            s = this.random.nextInt();
        } while (s == 0);
        return s;
    }
}
