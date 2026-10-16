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
package org.njmule.gui.screens;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import javafx.scene.control.Tab;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableColumn.SortType;
import javafx.scene.control.TableView;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.njmule.core.api.EngineEvent;
import org.njmule.core.api.Hash16;
import org.njmule.core.api.SearchId;
import org.njmule.core.api.SearchResult;
import org.njmule.gui.Engines;
import org.njmule.gui.FxTesting;
import org.njmule.gui.TestGui;
import org.njmule.gui.model.LiveList;

@Timeout(120)
class SearchOrderTest {

    @TempDir
    Path dir;

    private TestGui test;
    private TableView<LiveList.Row<SearchResult>> table;
    private final AtomicInteger hashes = new AtomicInteger();

    @AfterEach
    void close() {
        if (this.test != null) {
            this.test.close();
        }
    }

    @Test
    void shouldKeepTheOrderTheResultsCameInWithNoColumnChosen() throws Exception {
        givenASearchTab();

        whenTheServerFinds(named("b.avi"), named("c.avi"), named("a.avi"));

        thenTheResultsAreInTheOrder("b.avi", "c.avi", "a.avi");
    }

    @ParameterizedTest
    @EnumSource(SortType.class)
    void shouldPutSpamLastWhicheverWayTheNamesSort(SortType type) throws Exception {
        givenASearchTab();
        givenTheServerFound(named("a spam.avi").withSpamRating(80), named("b.avi"), named("c.avi"));

        whenTheResultsAreSortedBy("IDS_DL_FILENAME", type);

        thenTheResultsAreInTheOrder(
                type == SortType.ASCENDING ? "b.avi" : "c.avi",
                type == SortType.ASCENDING ? "c.avi" : "b.avi",
                "a spam.avi");
    }

    @Test
    void shouldSortTheNamesWhateverTheirCase() throws Exception {
        givenASearchTab();
        givenTheServerFound(named("cherry.avi"), named("Banana.avi"), named("apple.avi"));

        whenTheResultsAreSortedBy("IDS_DL_FILENAME", SortType.ASCENDING);

        thenTheResultsAreInTheOrder("apple.avi", "Banana.avi", "cherry.avi");
    }

    @Test
    void shouldSortTheCompleteSourcesByTheirShareOfTheSources() throws Exception {
        givenASearchTab();
        givenTheServerFound(sources("half.avi", 10, 5), sources("few.avi", 100, 9), sources("all.avi", 3, 3));

        whenTheResultsAreSortedBy("IDS_COMPLETE", SortType.ASCENDING);

        thenTheResultsAreInTheOrder("few.avi", "half.avi", "all.avi");
    }

    @Test
    void shouldSortTheTypesThenTheExtensionsWithFilesWithoutOneLast() throws Exception {
        givenASearchTab();
        givenTheServerFound(
                typed("x.mkv", "Video"), typed("z", "Video"), typed("y.avi", "Video"), typed("w.mp3", "Audio"));

        whenTheResultsAreSortedBy("nj.search.type", SortType.ASCENDING);

        thenTheResultsAreInTheOrder("w.mp3", "y.avi", "x.mkv", "z");
    }

    @ParameterizedTest
    @EnumSource(SortType.class)
    void shouldPutTheResultsWithoutAnArtistLastWhicheverWayTheyAreSorted(SortType type) throws Exception {
        givenASearchTab();
        givenTheServerFound(song("beta.mp3", "Beta"), song("none.mp3", ""), song("alpha.mp3", "Alpha"));

        whenTheResultsAreSortedBy("IDS_ARTIST", type);

        thenTheResultsAreInTheOrder(
                type == SortType.ASCENDING ? "alpha.mp3" : "beta.mp3",
                type == SortType.ASCENDING ? "beta.mp3" : "alpha.mp3",
                "none.mp3");
    }

    // A result that comes later goes among its ties as the column chosen before puts it.
    @Test
    void shouldBreakTiesByTheColumnChosenBefore() throws Exception {
        givenASearchTab();
        givenTheServerFound(sized("a.avi", 2000), sized("b.avi", 1000), sized("c.avi", 1000));
        givenTheResultsSortedBy("IDS_DL_FILENAME", SortType.DESCENDING);
        givenTheResultsSortedBy("IDS_DL_SIZE", SortType.ASCENDING);

        whenTheServerAlsoFinds(sized("d.avi", 1000));

        thenTheResultsAreInTheOrder("d.avi", "c.avi", "b.avi", "a.avi");
    }

    private void givenASearchTab() throws Exception {
        Map<String, Engines.Command> commands =
                Map.of("search", args -> CompletableFuture.completedFuture(new SearchId(1)));
        this.test = TestGui.start(this.dir.resolve("njmule"), real -> Engines.overriding(real, commands));
        SearchScreen screen = new SearchScreen(this.test.gui);
        this.test.show(FxTesting.onFx(screen::view));
        FxTesting.onFx(() -> {
            screen.queryField().setText("film");
            screen.methodBox().setValue(SearchScreen.Method.SERVER);
            screen.start();
        });
        FxTesting.await("the tab", () -> screen.tabs().getTabs().size() == 1);
        Tab tab = FxTesting.onFx(() -> screen.tabs().getTabs().getFirst());
        this.table = FxTesting.onFx(() -> screen.table(tab));
    }

    private void givenTheServerFound(SearchResult... results) throws Exception {
        whenTheServerFinds(results);
    }

    private void givenTheResultsSortedBy(String key, SortType type) throws Exception {
        whenTheResultsAreSortedBy(key, type);
    }

    private void whenTheServerFinds(SearchResult... results) throws Exception {
        FxTesting.onFx(() -> this.test.model.handle(new EngineEvent.SearchResults(new SearchId(1), List.of(results))));
        FxTesting.await("the results", () -> this.table.getItems().size() == results.length);
    }

    private void whenTheServerAlsoFinds(SearchResult result) throws Exception {
        int before = FxTesting.onFx(() -> this.table.getItems().size());
        FxTesting.onFx(() -> this.test.model.handle(new EngineEvent.SearchResults(new SearchId(1), List.of(result))));
        FxTesting.await("the result", () -> this.table.getItems().size() == before + 1);
    }

    private void whenTheResultsAreSortedBy(String key, SortType type) throws Exception {
        FxTesting.onFx(() -> {
            TableColumn<LiveList.Row<SearchResult>, ?> column = this.table.getColumns().stream()
                    .filter(c -> this.test.gui.t(key).equals(c.getText()))
                    .findFirst()
                    .orElseThrow();
            column.setSortType(type);
            this.table.getSortOrder().setAll(List.of(column));
        });
    }

    private void thenTheResultsAreInTheOrder(String... names) throws Exception {
        assertThat(FxTesting.onFx(() ->
                        this.table.getItems().stream().map(r -> r.get().name()).toList()))
                .containsExactly(names);
    }

    private SearchResult named(String name) {
        return file(name, 1000, 5, 1, "Video", SearchResult.Media.NONE);
    }

    private SearchResult sources(String name, int sources, int complete) {
        return file(name, 1000, sources, complete, "Video", SearchResult.Media.NONE);
    }

    private SearchResult typed(String name, String type) {
        return file(name, 1000, 5, 1, type, SearchResult.Media.NONE);
    }

    private SearchResult sized(String name, long size) {
        return file(name, size, 5, 1, "Video", SearchResult.Media.NONE);
    }

    private SearchResult song(String name, String artist) {
        return file(name, 1000, 5, 1, "Audio", new SearchResult.Media(artist, "", "", 200, 128, "mp3"));
    }

    private SearchResult file(
            String name, long size, int sources, int complete, String type, SearchResult.Media media) {
        Hash16 hash = Hash16.parseHex("%032X".formatted(this.hashes.incrementAndGet()));
        return new SearchResult(hash, name, size, sources, complete, Optional.of(type), media);
    }
}
