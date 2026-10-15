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
import org.njmule.core.files.hash.Ed2k;
import org.njmule.core.persist.format.SearchFiles;
import org.njmule.core.protocol.tag.FileTag;
import org.njmule.core.protocol.tag.Tag;

class StoredSearchesTest {

    @TempDir
    Path dir;

    private List<SavedSearch> saved;
    private List<SavedSearch> loaded;

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

    @Test
    void leavesOutResultsThatCannotBeDownloaded() throws Exception {
        Path file = this.dir.resolve(StoredSearches.FILE_NAME);
        Files.write(
                file,
                SearchFiles.writeStored(List.of(stored(
                        1,
                        false,
                        "film",
                        "",
                        List.of(
                                result(1, "good.mkv", 1000),
                                result(2, " ", 1000),
                                result(3, "zero.bin", 0),
                                result(4, "huge.bin", Ed2k.MAX_FILE_SIZE + 1),
                                result(5, "largest.bin", Ed2k.MAX_FILE_SIZE))))));

        List<SearchResult> results = StoredSearches.load(file).getFirst().results();

        assertThat(results).extracting(SearchResult::name).containsExactly("good.mkv", "largest.bin");
        assertThat(results).allSatisfy(r -> assertThat(r.link().size()).isEqualTo(r.size()));
    }

    @Test
    void keepsTheFileTypeOfAnImportedSearchAndSkipsBrowsedClients() throws Exception {
        Path file = this.dir.resolve(StoredSearches.FILE_NAME);
        Files.write(
                file,
                SearchFiles.writeStored(List.of(
                        stored(1, false, "film", "Video", List.of()),
                        stored(2, true, "SomeUser", "", List.of(result(1, "shared.mkv", 1000))),
                        stored(2, false, "setup", "Pro", List.of()),
                        stored(2, false, "set", "EmuleCollection", List.of()),
                        stored(2, false, "anything", "", List.of()))));

        List<SavedSearch> searches = StoredSearches.load(file);

        assertThat(searches)
                .extracting(s -> s.request().expression())
                .containsExactly("film @type=Video", "setup @type=Pro", "set", "anything");
        assertThat(SearchParser.parse(searches.getFirst().request().expression()))
                .isEqualTo(SearchParser.parse("film @type=video"));
    }

    @Test
    void fallsBackToTheBackupOfACorruptFile() throws Exception {
        Path file = this.dir.resolve(StoredSearches.FILE_NAME);
        List<SavedSearch> first =
                List.of(new SavedSearch("", new SearchRequest("first", SearchRequest.Scope.SERVER), List.of()));
        StoredSearches.save(file, first);
        StoredSearches.save(
                file, List.of(new SavedSearch("", new SearchRequest("second", SearchRequest.Scope.SERVER), List.of())));
        Files.write(file, new byte[] {0x0F, 1, 5});

        assertThat(StoredSearches.load(file)).isEqualTo(first);
    }

    @Test
    void shouldKeepTheMediaDetailsOfTheResults() throws Exception {
        givenAnOpenSearchWith(new SearchResult(
                hash(7),
                "song.mp3",
                4_000_000,
                1,
                0,
                Optional.of("Audio"),
                new SearchResult.Media("Some Band", "First Album", "Opening Song", 205, 192, "mp3")));

        whenTheSearchesAreSavedAndLoaded();

        thenTheLoadedSearchesAreTheSaved();
    }

    private void givenAnOpenSearchWith(SearchResult result) {
        this.saved = List.of(new SavedSearch("", new SearchRequest("song", SearchRequest.Scope.KAD), List.of(result)));
    }

    private void whenTheSearchesAreSavedAndLoaded() throws Exception {
        Path file = this.dir.resolve(StoredSearches.FILE_NAME);
        StoredSearches.save(file, this.saved);
        this.loaded = StoredSearches.load(file);
    }

    private void thenTheLoadedSearchesAreTheSaved() {
        assertThat(this.loaded).isEqualTo(this.saved);
    }

    private static SearchFiles.StoredSearch stored(
            int type, boolean clientShared, String expression, String fileType, List<SearchFiles.Result> results) {
        return new SearchFiles.StoredSearch(0, type, clientShared, "", expression, fileType, results);
    }

    private static SearchFiles.Result result(int seed, String name, long size) {
        return new SearchFiles.Result(
                hash(seed), 0, 0, List.of(Tag.of(FileTag.FILENAME, name), Tag.of(FileTag.FILESIZE, size)));
    }

    private static Hash16 hash(int seed) {
        byte[] bytes = new byte[16];
        bytes[0] = (byte) seed;
        return Hash16.of(bytes);
    }
}
