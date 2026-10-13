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

import java.util.List;
import java.util.Objects;

/**
 * The state of a download's bytes, for drawing its bar: what is still missing, what is being requested from sources,
 * and how many known sources have each part.
 *
 * @param sources the sources that have each part, part by part
 */
public record PartMap(long size, List<Span> missing, List<Span> requested, List<Integer> sources) {

    /** The bytes from {@code start} (inclusive) to {@code end} (exclusive). */
    public record Span(long start, long end) {}

    /** The size of an eD2K part: hashes and source availability go part by part. */
    public static final long PART_SIZE = 9_728_000L;

    public static final PartMap EMPTY = new PartMap(0, List.of(), List.of(), List.of());

    public PartMap {
        missing = List.copyOf(Objects.requireNonNull(missing, "missing"));
        requested = List.copyOf(Objects.requireNonNull(requested, "requested"));
        sources = List.copyOf(Objects.requireNonNull(sources, "sources"));
    }
}
