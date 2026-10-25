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
package org.njmule.core.remote;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SequencedMap;
import java.util.function.LongSupplier;
import org.njmule.core.api.EngineEvent;
import org.njmule.core.api.Hash16;
import org.njmule.core.api.SearchResult;

/**
 * The results of the latest searches started through the web interface or the API, collected from the engine's events.
 * Results of other searches, such as the desktop window's, which may be ours whose id is not noted yet, are kept apart
 * so that they never push ours out.
 */
public final class SearchCollector {

    public static final int SEARCHES_KEPT = 10;
    public static final int RESULTS_KEPT = 1000;

    /** How long a search counts as running at most, should its end never be told. */
    public static final Duration LONGEST_SEARCH = Duration.ofMinutes(3);

    /** What a search started here asked for. */
    public record Asked(String expression, String scope) {}

    // By search id, oldest first.
    private final LinkedHashMap<Long, Found> ours = new LinkedHashMap<>();
    private final LinkedHashMap<Long, Found> others = new LinkedHashMap<>();
    private final LongSupplier ticker;

    public SearchCollector() {
        this(System::nanoTime);
    }

    public SearchCollector(LongSupplier ticker) {
        this.ticker = ticker;
    }

    /** Notes a search started here, with what came before its id was known. */
    public synchronized void started(long search) {
        started(search, new Asked("", ""));
    }

    /** Notes a search started here and what it asked for, with what came before its id was known. */
    public synchronized void started(long search, Asked asked) {
        Found early = this.others.remove(search);
        Found found = new Found(asked, this.ticker.getAsLong());
        if (early != null) {
            found.results.putAll(early.results);
            found.ended = early.ended;
        }
        this.ours.put(search, found);
        trim(this.ours);
    }

    /** Forgets a search started here. */
    public synchronized boolean forget(long search) {
        return this.ours.remove(search) != null;
    }

    /** The results of a search started here, in the order they came; empty for an unknown one. */
    public synchronized Optional<List<SearchResult>> results(long search) {
        Found found = this.ours.get(search);
        return found == null ? Optional.empty() : Optional.of(new ArrayList<>(found.results.values()));
    }

    public synchronized Optional<SearchResult> result(long search, Hash16 hash) {
        Found found = this.ours.get(search);
        return found == null ? Optional.empty() : Optional.ofNullable(found.results.get(hash));
    }

    /** What a search started here asked for; empty for an unknown one. */
    public synchronized Optional<Asked> asked(long search) {
        return Optional.ofNullable(this.ours.get(search)).map(f -> f.asked);
    }

    /** The searches started here, oldest first. */
    public synchronized List<Listed> searches() {
        return this.ours.entrySet().stream()
                .map(e -> new Listed(
                        e.getKey(), e.getValue().asked, e.getValue().results.size(), running(e.getKey())))
                .toList();
    }

    /** A search started here, with the results it has and whether more may come. */
    public record Listed(long id, Asked asked, int results, boolean running) {}

    /** Whether a search started here may still bring results. */
    public synchronized boolean running(long search) {
        Found found = this.ours.get(search);
        return found != null && !found.ended && this.ticker.getAsLong() - found.startedAt < LONGEST_SEARCH.toNanos();
    }

    // Results may arrive before the request that started the search notes its id.
    public synchronized void add(EngineEvent.SearchResults r) {
        Map<Hash16, SearchResult> found = find(r.search().value()).results;
        for (SearchResult result : r.results()) {
            if (found.size() < RESULTS_KEPT || found.containsKey(result.hash())) {
                found.put(result.hash(), result);
            }
        }
    }

    public synchronized void ended(EngineEvent.SearchEnded e) {
        find(e.search().value()).ended = true;
    }

    private Found find(long search) {
        Found found = this.ours.get(search);
        if (found == null) {
            found = this.others.computeIfAbsent(search, k -> new Found(new Asked("", ""), this.ticker.getAsLong()));
            trim(this.others);
        }
        return found;
    }

    private static void trim(SequencedMap<Long, ?> searches) {
        while (searches.size() > SEARCHES_KEPT) {
            searches.pollFirstEntry();
        }
    }

    private static final class Found {
        private final Map<Hash16, SearchResult> results = new LinkedHashMap<>();
        private final Asked asked;
        private final long startedAt;
        private boolean ended;

        private Found(Asked asked, long startedAt) {
            this.asked = asked;
            this.startedAt = startedAt;
        }
    }
}
