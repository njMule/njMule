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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;
import org.njmule.core.api.Hash16;
import org.njmule.core.api.Hash20;
import org.njmule.core.api.SavedSearch;
import org.njmule.core.api.SearchRequest;
import org.njmule.core.api.SearchResult;
import tools.jackson.databind.JsonNode;

/**
 * The saved searches a client hands back to the remote-control API, read into the engine's records as {@link ApiJson}
 * wrote them. What the API leaves out comes back empty: a search result's origin and AICH hash.
 */
final class ApiReader {

    private ApiReader() {}

    static SearchResult result(JsonNode n) {
        JsonNode m = n.path("media");
        SearchResult.Media media = m.isObject()
                ? new SearchResult.Media(
                        text(m, "artist"),
                        text(m, "album"),
                        text(m, "title"),
                        m.path("length").asInt(),
                        m.path("bitrate").asInt(),
                        text(m, "codec"))
                : SearchResult.Media.NONE;
        return new SearchResult(
                hash(n, "hash"),
                text(n, "name"),
                n.path("size").asLong(),
                n.path("sources").asInt(),
                completeSources(n),
                optional(n, "type"),
                media,
                SearchResult.Origin.NONE,
                n.path("spamRating").asInt(),
                optional(n, "aich").flatMap(Hash20::tryParseBase32),
                list(
                        n.path("names"),
                        each -> new SearchResult.Name(
                                text(each, "name"), each.path("sources").asInt(), completeSources(each))));
    }

    static SavedSearch savedSearch(JsonNode n) {
        return new SavedSearch(
                text(n, "title"),
                new SearchRequest(text(n, "query"), constant(SearchRequest.Scope.class, n, "scope")),
                list(n.path("results"), ApiReader::result));
    }

    // Kad does not tell how many sources are complete.
    private static int completeSources(JsonNode n) {
        JsonNode count = n.path("completeSources");
        return count.isNumber() ? count.asInt() : -1;
    }

    static <T> List<T> list(JsonNode n, Function<JsonNode, T> each) {
        List<T> items = new ArrayList<>();
        n.values().forEach(item -> items.add(each.apply(item)));
        return List.copyOf(items);
    }

    private static Hash16 hash(JsonNode n, String name) {
        return Hash16.parseHex(text(n, name));
    }

    private static String text(JsonNode n, String name) {
        return n.path(name).asString("");
    }

    private static Optional<String> optional(JsonNode n, String name) {
        JsonNode value = n.path(name);
        return value.isString() ? Optional.of(value.asString()) : Optional.empty();
    }

    private static <E extends Enum<E>> E constant(Class<E> type, JsonNode n, String name) {
        return Enum.valueOf(type, text(n, name).toUpperCase(Locale.ROOT));
    }
}
