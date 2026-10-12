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
package org.njmule.core.api;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.function.LongConsumer;
import org.njmule.core.files.hash.FileHasher;

/** Hashes local files the way the network identifies them. */
public final class FileHashing {

    private FileHashing() {}

    /**
     * The link of {@code file}: name, size, file hash, AICH hash and part hashes. {@code progress}
     * receives the bytes hashed so far; interrupting the thread cancels.
     */
    public static Ed2kLink.FileLink link(Path file, LongConsumer progress) throws IOException {
        Objects.requireNonNull(progress, "progress");
        FileHasher.Result result = FileHasher.hash(file, progress);
        Path name = file.getFileName();
        if (name == null || result.ed2k().size() == 0) {
            throw new IOException("cannot link an empty file or a directory: " + file);
        }
        return new Ed2kLink.FileLink(
                name.toString(),
                result.ed2k().size(),
                result.ed2k().fileHash(),
                result.aich().map(aich -> aich.masterHash()),
                result.ed2k().partHashes(),
                List.of(),
                List.of());
    }
}
