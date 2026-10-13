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

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.core.api.Hash16;
import org.njmule.core.api.SavedSearch;
import org.njmule.core.api.SearchRequest;
import org.njmule.core.api.SearchResult;
import org.njmule.core.persist.format.SearchFiles;

class StoredSearchesTest {

    @TempDir
    Path dir;

    @Test
    void keepsTheOpenSearchesWithTheirResults() throws Exception {
        Path file = this.dir.resolve(StoredSearches.FILE_NAME);
        assertThat(StoredSearches.load(file)).as("no file yet").isEmpty();
        SearchResult film = new SearchResult(hash(1), "film.mkv", 5_000_000_000L, 12, 3, Optional.of("Video"));
        SearchResult song = new SearchResult(hash(2), "song.mp3", 4_000_000, 1, 0, Optional.empty());
        List<SavedSearch> open = List.of(
                new SavedSearch("", new SearchRequest("film @type=Video", SearchRequest.Scope.KAD), List.of(film)),
                new SavedSearch(
                        "Related",
                        new SearchRequest("related::" + hash(1).toHex(), SearchRequest.Scope.SERVER),
                        List.of(film, song)));

        StoredSearches.save(file, open);

        assertThat(StoredSearches.load(file)).isEqualTo(open);
        assertThat(SearchFiles.readStored(Files.readAllBytes(file)))
                .extracting(SearchFiles.StoredSearch::type)
                .as("the numbers other clients use for the methods")
                .containsExactly(3, 1);
    }

    private static Hash16 hash(int seed) {
        byte[] bytes = new byte[16];
        bytes[0] = (byte) seed;
        return Hash16.of(bytes);
    }
}
