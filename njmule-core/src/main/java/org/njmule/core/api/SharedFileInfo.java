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

import java.nio.file.Path;
import java.util.Objects;

/** A file we share. */
public record SharedFileInfo(
        Hash16 hash, String name, long size, Path path, UploadPriority priority, Statistics statistics) {

    /**
     * How often the file was asked for, how many of those asks got a slot and how many bytes of it went out: in this
     * run and in all, as known.met keeps it.
     */
    public record Statistics(
            long requests,
            long accepted,
            long transferred,
            long totalRequests,
            long totalAccepted,
            long totalTransferred) {
        public static final Statistics NONE = new Statistics(0, 0, 0, 0, 0, 0);
    }

    public SharedFileInfo {
        Objects.requireNonNull(hash, "hash");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(priority, "priority");
        Objects.requireNonNull(statistics, "statistics");
    }

    /** A file never asked for. */
    public SharedFileInfo(Hash16 hash, String name, long size, Path path, UploadPriority priority) {
        this(hash, name, size, path, priority, Statistics.NONE);
    }

    /** The ed2k link of the file. */
    public Ed2kLink.FileLink link() {
        return Ed2kLink.FileLink.of(this.name, this.size, this.hash);
    }
}
