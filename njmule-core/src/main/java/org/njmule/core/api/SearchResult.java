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

import java.util.Objects;
import java.util.Optional;

/** A file found by a search; the source counts add up over the servers that reported it. */
public record SearchResult(
        Hash16 hash,
        String name,
        long size,
        int sources,
        int completeSources,
        Optional<String> type) {

    public SearchResult {
        Objects.requireNonNull(hash, "hash");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(type, "type");
    }

    /** The ed2k link of the file. */
    public Ed2kLink.FileLink link() {
        return Ed2kLink.FileLink.of(this.name, this.size, this.hash);
    }
}
