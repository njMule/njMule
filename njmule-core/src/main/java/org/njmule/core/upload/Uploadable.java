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
package org.njmule.core.upload;

import java.nio.file.Path;
import java.util.BitSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.njmule.core.api.Hash16;
import org.njmule.core.download.DownloadQueue;
import org.njmule.core.files.SharedFiles;
import org.njmule.core.files.hash.Ed2k;

/**
 * A file other clients may download from us: a shared file, or a download whose checked parts are shared while it goes
 * on.
 *
 * @param parts the parts that can be sent, empty for a complete file
 */
record Uploadable(Hash16 hash, String name, long size, List<Hash16> partHashes, Optional<BitSet> parts, Path data) {

    // Already compressed: zlib would only cost time.
    private static final Set<String> PACKED = Set.of(
            "7z", "aac", "ape", "avi", "bz2", "cab", "flac", "flv", "gif", "gz", "jpeg", "jpg", "m4a", "m4v", "mkv",
            "mov", "mp3", "mp4", "mpeg", "mpg", "ogg", "ogm", "opus", "png", "rar", "rm", "rmvb", "webm", "webp", "wma",
            "wmv", "xz", "zip", "zst");

    Uploadable {
        Objects.requireNonNull(hash, "hash");
        Objects.requireNonNull(name, "name");
        partHashes = List.copyOf(partHashes);
        parts = parts.map(p -> (BitSet) p.clone());
        Objects.requireNonNull(data, "data");
    }

    static Uploadable of(SharedFiles.SharedFile file) {
        return new Uploadable(file.hash(), file.name(), file.size(), file.partHashes(), Optional.empty(), file.path());
    }

    static Uploadable of(DownloadQueue.Download download) {
        return new Uploadable(
                download.hash(),
                download.file().name(),
                download.file().size(),
                download.file().partHashes(),
                Optional.of(download.verifiedParts()),
                download.file().dataFile());
    }

    @Override
    public Optional<BitSet> parts() {
        return this.parts.map(p -> (BitSet) p.clone());
    }

    boolean isComplete() {
        return this.parts.isEmpty();
    }

    /** Whether every byte of {@code [start, end)} can be sent. */
    boolean has(long start, long end) {
        if (start < 0 || end > this.size || start >= end) {
            return false;
        }
        if (this.parts.isEmpty()) {
            return true;
        }
        for (int part = (int) (start / Ed2k.PARTSIZE); part <= (end - 1) / Ed2k.PARTSIZE; part++) {
            if (!this.parts.get().get(part)) {
                return false;
            }
        }
        return true;
    }

    /** Whether blocks of the file are worth compressing. */
    boolean compressible() {
        int dot = this.name.lastIndexOf('.');
        return dot < 0 || !PACKED.contains(this.name.substring(dot + 1).toLowerCase(Locale.ROOT));
    }
}
