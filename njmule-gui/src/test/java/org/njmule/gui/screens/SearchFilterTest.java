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
import javafx.scene.control.MenuItem;
import javafx.scene.control.RadioMenuItem;
import javafx.scene.control.Tab;
import javafx.scene.control.TableView;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.core.api.EngineEvent;
import org.njmule.core.api.Hash16;
import org.njmule.core.api.SearchId;
import org.njmule.core.api.SearchResult;
import org.njmule.gui.Engines;
import org.njmule.gui.FxTesting;
import org.njmule.gui.TestGui;
import org.njmule.gui.model.LiveList;

@Timeout(120)
class SearchFilterTest {

    @TempDir
    Path dir;

    private TestGui test;
    private SearchScreen screen;
    private final AtomicInteger searches = new AtomicInteger();
    private final AtomicInteger hashes = new AtomicInteger();

    @AfterEach
    void close() {
        if (this.test != null) {
            this.test.close();
        }
    }

    @Test
    void shouldFilterOnTheNamesAtFirstAndSayItInTheEmptyBox() throws Exception {
        givenASearchFoundIn(1, file("film dvd.avi", 700_000_000), file("song.mp3", 4_000_000));

        whenTheFilterIsTyped("DVD");

        thenTheTabShows(1, "film dvd.avi");
        thenTheEmptyBoxWouldRead("IDS_DL_FILENAME");
    }

    @Test
    void shouldLeaveOutTheResultsWithAWordAfterAMinus() throws Exception {
        givenASearchFoundIn(1, file("film dvd.avi", 1000), file("film hd.mkv", 1000), file("song.mp3", 1000));

        whenTheFilterIsTyped("film -dvd");

        thenTheTabShows(1, "film hd.mkv");
    }

    @Test
    void shouldFilterOnTheTextTheColumnChosenShows() throws Exception {
        givenASearchFoundIn(1, file("film dvd.avi", 3_000_000_000L), file("song.mp3", 4_000_000));
        givenTheColumnChosen("IDS_DL_SIZE");

        whenTheFilterIsTyped("gb");

        thenTheTabShows(1, "film dvd.avi");
        thenTheEmptyBoxWouldRead("IDS_DL_SIZE");
    }

    @Test
    void shouldOfferTheColumnsShownWithTheOneFilteredOnChecked() throws Exception {
        givenASearchFoundIn(1, file("film dvd.avi", 1000));

        whenTheColumnsAreOffered();

        thenTheOfferIncludes("IDS_DL_FILENAME", "IDS_DL_SIZE", "IDS_SEARCHAVAIL", "IDS_COMPLETE");
        thenTheOfferLeavesOut("IDS_FILEID");
        thenTheCheckedOneIs("IDS_DL_FILENAME");
    }

    @Test
    void shouldFilterTheOtherTabsOnTheSameWords() throws Exception {
        givenASearchFoundIn(1, file("film dvd.avi", 1000), file("song.mp3", 1000));
        givenASearchFoundIn(2, file("other film.avi", 1000), file("other song.mp3", 1000));

        whenTheFilterIsTyped("song");

        thenTheTabShows(1, "song.mp3");
        thenTheTabShows(2, "other song.mp3");
    }

    private void givenASearchFoundIn(int id, SearchResult... results) throws Exception {
        if (this.test == null) {
            Map<String, Engines.Command> commands = Map.of(
                    "search", args -> CompletableFuture.completedFuture(new SearchId(this.searches.incrementAndGet())));
            this.test = TestGui.start(this.dir.resolve("njmule"), real -> Engines.overriding(real, commands));
            this.screen = new SearchScreen(this.test.gui);
            this.test.show(FxTesting.onFx(this.screen::view));
        }
        FxTesting.onFx(() -> {
            this.screen.queryField().setText("search " + id);
            this.screen.methodBox().setValue(SearchScreen.Method.SERVER);
            this.screen.start();
        });
        FxTesting.await("the tab", () -> this.screen.tabs().getTabs().size() == id);
        FxTesting.onFx(() -> this.test.model.handle(new EngineEvent.SearchResults(new SearchId(id), List.of(results))));
        FxTesting.await("the results", () -> table(id).getItems().size() == results.length);
    }

    private void givenTheColumnChosen(String key) throws Exception {
        FxTesting.onFx(() -> this.screen.filterColumnsMenu().getItems().stream()
                .filter(i -> this.test.gui.t(key).equals(i.getText()))
                .findFirst()
                .orElseThrow()
                .fire());
    }

    private void whenTheFilterIsTyped(String text) throws Exception {
        FxTesting.onFx(() -> this.screen.filterField().setText(text));
    }

    private List<MenuItem> offered;

    private void whenTheColumnsAreOffered() throws Exception {
        this.offered = FxTesting.onFx(() -> this.screen.filterColumnsMenu().getItems());
    }

    private void thenTheTabShows(int id, String... names) throws Exception {
        assertThat(FxTesting.onFx(() ->
                        table(id).getItems().stream().map(r -> r.get().name()).toList()))
                .containsExactly(names);
    }

    private void thenTheEmptyBoxWouldRead(String key) throws Exception {
        assertThat(FxTesting.onFx(() -> this.screen.filterField().getPromptText()))
                .isEqualTo(this.test.gui.t(key));
    }

    private void thenTheOfferIncludes(String... keys) {
        assertThat(this.offered.stream().map(MenuItem::getText).toList())
                .containsSubsequence(
                        List.of(keys).stream().map(this.test.gui::t).toList());
    }

    private void thenTheOfferLeavesOut(String key) {
        assertThat(this.offered.stream().map(MenuItem::getText).toList()).doesNotContain(this.test.gui.t(key));
    }

    private void thenTheCheckedOneIs(String key) {
        assertThat(this.offered.stream()
                        .filter(i -> ((RadioMenuItem) i).isSelected())
                        .map(MenuItem::getText)
                        .toList())
                .containsExactly(this.test.gui.t(key));
    }

    private TableView<LiveList.Row<SearchResult>> table(int id) {
        Tab tab = this.screen.tabs().getTabs().get(id - 1);
        return this.screen.table(tab);
    }

    private SearchResult file(String name, long size) {
        Hash16 hash = Hash16.parseHex("%032X".formatted(this.hashes.incrementAndGet()));
        return new SearchResult(hash, name, size, 1, 0, Optional.of("Video"));
    }
}
