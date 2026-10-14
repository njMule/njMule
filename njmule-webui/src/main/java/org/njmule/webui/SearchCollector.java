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
package org.njmule.webui;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SequencedMap;
import org.njmule.core.api.EngineEvent;
import org.njmule.core.api.Hash16;
import org.njmule.core.api.SearchResult;

/**
 * The results of the latest searches started through the web interface or the API, collected from the engine's events.
 * Results of other searches, such as the desktop window's, which may be ours whose id is not noted yet, are kept apart
 * so that they never push ours out.
 */
final class SearchCollector {

    static final int SEARCHES_KEPT = 10;
    static final int RESULTS_KEPT = 1000;

    // By search id, oldest first.
    private final LinkedHashMap<Long, Map<Hash16, SearchResult>> ours = new LinkedHashMap<>();
    private final LinkedHashMap<Long, Map<Hash16, SearchResult>> others = new LinkedHashMap<>();

    /** Notes a search started here, with what came before its id was known. */
    synchronized void started(long search) {
        Map<Hash16, SearchResult> early = this.others.remove(search);
        this.ours.put(search, early == null ? new LinkedHashMap<>() : early);
        trim(this.ours);
    }

    /** Forgets a search started here. */
    synchronized boolean forget(long search) {
        return this.ours.remove(search) != null;
    }

    /** The results of a search started here, in the order they came; empty for an unknown one. */
    synchronized Optional<List<SearchResult>> results(long search) {
        Map<Hash16, SearchResult> found = this.ours.get(search);
        return found == null ? Optional.empty() : Optional.of(new ArrayList<>(found.values()));
    }

    synchronized Optional<SearchResult> result(long search, Hash16 hash) {
        return Optional.ofNullable(this.ours.getOrDefault(search, Map.of()).get(hash));
    }

    // Results may arrive before the request that started the search notes its id.
    synchronized void add(EngineEvent.SearchResults r) {
        long search = r.search().value();
        Map<Hash16, SearchResult> found = this.ours.get(search);
        if (found == null) {
            found = this.others.computeIfAbsent(search, k -> new LinkedHashMap<>());
            trim(this.others);
        }
        for (SearchResult result : r.results()) {
            if (found.size() < RESULTS_KEPT || found.containsKey(result.hash())) {
                found.put(result.hash(), result);
            }
        }
    }

    private static void trim(SequencedMap<Long, ?> searches) {
        while (searches.size() > SEARCHES_KEPT) {
            searches.pollFirstEntry();
        }
    }
}
