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
package org.njmule.core.search;

import com.google.common.math.IntMath;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.njmule.core.api.SearchResult;
import org.njmule.core.files.FileTypes;

/** The names a search finds a file under, and the one the file shows. */
final class ResultNames {

    /** Names kept for one file: a spammer naming it more often changes no name. */
    static final int MAX_NAMES = 64;

    private ResultNames() {}

    /**
     * {@code known} found again as {@code found}: the source counts add up, or with {@code addCounts} false the larger
     * stands, as Kad nodes report the same sources; so do those of a name both have, in any case. The file shows the
     * name most sources give, the first found of those as many give, with the type of its extension.
     */
    static SearchResult foundAgain(SearchResult known, SearchResult found, boolean addCounts) {
        List<SearchResult.Name> names = new ArrayList<>(names(known));
        for (SearchResult.Name name : names(found)) {
            int same = indexOf(names, name.name());
            if (same >= 0) {
                SearchResult.Name was = names.get(same);
                names.set(
                        same,
                        new SearchResult.Name(
                                was.name(),
                                count(was.sources(), name.sources(), addCounts),
                                count(was.completeSources(), name.completeSources(), addCounts)));
            } else if (names.size() < MAX_NAMES) {
                names.add(name);
            }
        }
        // A stable sort: of names as many sources give, the first found stays first.
        names.sort(Comparator.comparingInt(SearchResult.Name::sources).reversed());
        String shown = names.getFirst().name();
        return new SearchResult(
                known.hash(),
                shown,
                known.size(),
                count(known.sources(), found.sources(), addCounts),
                count(known.completeSources(), found.completeSources(), addCounts),
                FileTypes.of(shown).or(() -> known.type().or(found::type)),
                known.media(),
                known.origin(),
                known.spamRating(),
                known.aichHash(),
                names.size() > 1 ? names : List.of());
    }

    private static List<SearchResult.Name> names(SearchResult result) {
        return result.names().isEmpty()
                ? List.of(new SearchResult.Name(result.name(), result.sources(), result.completeSources()))
                : result.names();
    }

    private static int indexOf(List<SearchResult.Name> names, String name) {
        for (int i = 0; i < names.size(); i++) {
            if (names.get(i).name().equalsIgnoreCase(name)) {
                return i;
            }
        }
        return -1;
    }

    // A count not told (negative) adds nothing.
    private static int count(int known, int found, boolean add) {
        return add && known >= 0 && found >= 0 ? IntMath.saturatedAdd(known, found) : Math.max(known, found);
    }
}
