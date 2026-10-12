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
package org.njmule.core.files.hash;

import java.util.List;
import java.util.Objects;
import org.njmule.core.api.Hash16;

/**
 * A file hash with its part hashes; {@code partHashes} is empty for files smaller than one part,
 * whose file hash is the MD4 of the data itself.
 */
public record Ed2kHashSet(long size, Hash16 fileHash, List<Hash16> partHashes) {

    public Ed2kHashSet {
        Ed2k.checkSize(size);
        Objects.requireNonNull(fileHash, "fileHash");
        partHashes = List.copyOf(partHashes);
        if (partHashes.size() != Ed2k.hashSetSize(size)) {
            throw new IllegalArgumentException(
                    "a file of "
                            + size
                            + " bytes has "
                            + Ed2k.hashSetSize(size)
                            + " part hashes, got "
                            + partHashes.size());
        }
    }

    /** True when the part hashes produce the file hash. */
    public boolean isConsistent() {
        return this.partHashes.isEmpty() || Ed2k.fileHash(this.partHashes).equals(this.fileHash);
    }

    /** The MD4 a downloaded data part must match. */
    public Hash16 partHash(int part) {
        Objects.checkIndex(part, Ed2k.partCount(this.size));
        return this.partHashes.isEmpty() ? this.fileHash : this.partHashes.get(part);
    }
}
