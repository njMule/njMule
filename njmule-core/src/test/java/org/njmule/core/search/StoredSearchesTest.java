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

import java.io.IOException;
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
import org.njmule.core.protocol.tag.TagName;

class StoredSearchesTest {

    private static final SearchResult FILM =
            new SearchResult(hash(1), "film.mkv", 5_000_000_000L, 12, 3, Optional.of("Video"));
    private static final SearchResult SONG = new SearchResult(hash(2), "song.mp3", 4_000_000, 1, 0, Optional.empty());

    @TempDir
    Path dir;

    private List<SavedSearch> saved;
    private List<SavedSearch> loaded;

    @Test
    void shouldKeepTheOpenSearchesWithTheirResults() throws IOException {
        givenNoStoredSearchesYet();
        givenTheOpenSearches(
                new SavedSearch("", new SearchRequest("film @type=Video", SearchRequest.Scope.KAD), List.of(FILM)),
                new SavedSearch(
                        "Related",
                        new SearchRequest("related::" + hash(1).toHex(), SearchRequest.Scope.SERVER),
                        List.of(FILM, SONG)));

        whenTheSearchesAreSavedAndLoaded();

        thenTheLoadedSearchesAreTheSaved();
        thenTheFileStoresTheMethods(3, 1);
    }

    @Test
    void shouldLeaveOutResultsThatCannotBeDownloaded() throws IOException {
        givenAFileWithTheSearches(stored(
                1,
                false,
                "film",
                "",
                List.of(
                        result(1, "good.mkv", 1000),
                        result(2, " ", 1000),
                        result(3, "zero.bin", 0),
                        result(4, "huge.bin", Ed2k.MAX_FILE_SIZE + 1),
                        result(5, "largest.bin", Ed2k.MAX_FILE_SIZE))));

        whenTheSearchesAreLoaded();

        thenTheFirstSearchHasTheResults("good.mkv", "largest.bin");
        thenEveryResultLinksToItsOwnSize();
    }

    @Test
    void shouldKeepTheFileTypeOfAnImportedSearchAndSkipBrowsedClients() throws IOException {
        givenAFileWithTheSearches(
                stored(1, false, "film", "Video", List.of()),
                stored(2, true, "SomeUser", "", List.of(result(1, "shared.mkv", 1000))),
                stored(2, false, "setup", "Pro", List.of()),
                stored(2, false, "set", "EmuleCollection", List.of()),
                stored(2, false, "anything", "", List.of()));

        whenTheSearchesAreLoaded();

        thenTheExpressionsAre("film @type=Video", "setup @type=Pro", "set", "anything");
        thenTheFirstExpressionMeansTheSameAs("film @type=video");
    }

    @Test
    void shouldFallBackToTheBackupOfACorruptFile() throws IOException {
        givenTheSavedSearch("first");
        givenTheSavedSearch("second");
        givenTheFileIsCorrupted();

        whenTheSearchesAreLoaded();

        thenTheLoadedSearchIs("first");
    }

    @Test
    void shouldStoreNoCompleteCountTheNetworkDidNotTellAndReadItBackUnknown() throws IOException {
        givenAnOpenSearchWith(
                new SearchResult(hash(8), "film.mkv", 5_000_000, 3, SearchResult.UNKNOWN, Optional.of("Video")));

        whenTheSearchesAreSavedAndLoaded();

        thenTheLoadedSearchesAreTheSaved();
        thenTheFileHoldsNoCompleteCount();
    }

    @Test
    void shouldKeepTheMediaDetailsOfTheResults() throws IOException {
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

    private void givenNoStoredSearchesYet() throws IOException {
        assertThat(StoredSearches.load(storedFile())).as("no file yet").isEmpty();
    }

    private void givenTheOpenSearches(SavedSearch... searches) {
        this.saved = List.of(searches);
    }

    private void givenAnOpenSearchWith(SearchResult result) {
        this.saved = List.of(new SavedSearch("", new SearchRequest("song", SearchRequest.Scope.KAD), List.of(result)));
    }

    private void givenAFileWithTheSearches(SearchFiles.StoredSearch... searches) throws IOException {
        Files.write(storedFile(), SearchFiles.writeStored(List.of(searches)));
    }

    private void givenTheSavedSearch(String expression) throws IOException {
        StoredSearches.save(storedFile(), serverSearch(expression));
    }

    private void givenTheFileIsCorrupted() throws IOException {
        Files.write(storedFile(), new byte[] {0x0F, 1, 5});
    }

    private void whenTheSearchesAreSavedAndLoaded() throws IOException {
        StoredSearches.save(storedFile(), this.saved);
        this.loaded = StoredSearches.load(storedFile());
    }

    private void whenTheSearchesAreLoaded() throws IOException {
        this.loaded = StoredSearches.load(storedFile());
    }

    private void thenTheLoadedSearchesAreTheSaved() {
        assertThat(this.loaded).isEqualTo(this.saved);
    }

    // Other clients read a count of 0 as told: one not told stays out.
    private void thenTheFileHoldsNoCompleteCount() throws IOException {
        assertThat(SearchFiles.readStored(Files.readAllBytes(storedFile()))
                        .getFirst()
                        .results()
                        .getFirst()
                        .tags())
                .noneMatch(t -> t.name().equals(TagName.id(FileTag.COMPLETE_SOURCES)));
    }

    private void thenTheLoadedSearchIs(String expression) {
        assertThat(this.loaded).isEqualTo(serverSearch(expression));
    }

    private void thenTheFileStoresTheMethods(Integer... types) throws IOException {
        assertThat(SearchFiles.readStored(Files.readAllBytes(storedFile())))
                .extracting(SearchFiles.StoredSearch::type)
                .as("the numbers other clients use for the methods")
                .containsExactly(types);
    }

    private void thenTheFirstSearchHasTheResults(String... names) {
        assertThat(this.loaded.getFirst().results())
                .extracting(SearchResult::name)
                .containsExactly(names);
    }

    private void thenEveryResultLinksToItsOwnSize() {
        assertThat(this.loaded.getFirst().results())
                .allSatisfy(r -> assertThat(r.link().size()).isEqualTo(r.size()));
    }

    private void thenTheExpressionsAre(String... expressions) {
        assertThat(this.loaded).extracting(s -> s.request().expression()).containsExactly(expressions);
    }

    private void thenTheFirstExpressionMeansTheSameAs(String expression) {
        assertThat(SearchParser.parse(this.loaded.getFirst().request().expression()))
                .isEqualTo(SearchParser.parse(expression));
    }

    private Path storedFile() {
        return this.dir.resolve(StoredSearches.FILE_NAME);
    }

    private static List<SavedSearch> serverSearch(String expression) {
        return List.of(new SavedSearch("", new SearchRequest(expression, SearchRequest.Scope.SERVER), List.of()));
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
