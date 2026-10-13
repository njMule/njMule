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

import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** The search results marked as spam: by file hash and by full name (in lower case), less the hashes cleared again. */
public record SpamMarks(Set<Hash16> spam, Set<Hash16> notSpam, Set<String> names) {

    public static final SpamMarks NONE = new SpamMarks(Set.of(), Set.of(), Set.of());

    public SpamMarks {
        spam = Set.copyOf(spam);
        notSpam = Set.copyOf(notSpam);
        names = Set.copyOf(names);
    }

    /** Whether {@code result} counts as spam. */
    public boolean isSpam(SearchResult result) {
        Objects.requireNonNull(result, "result");
        if (this.notSpam.contains(result.hash())) {
            return false;
        }
        return this.spam.contains(result.hash())
                || this.names.contains(result.name().toLowerCase(Locale.ROOT));
    }
}
