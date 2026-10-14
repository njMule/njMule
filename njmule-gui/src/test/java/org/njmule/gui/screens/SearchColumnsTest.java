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
import javafx.scene.control.Tab;
import javafx.scene.control.TableColumn;
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
class SearchColumnsTest {

    private static final SearchResult LONG_SONG = song(
            "0123456789ABCDEF0123456789ABCDEF",
            "long song.mp3",
            new SearchResult.Media("Some Band", "First Album", "Long Song", 3723, 96, "mp3"));
    private static final SearchResult SHORT_SONG = song(
            "00112233445566778899AABBCCDDEEFF",
            "short song.flac",
            new SearchResult.Media("", "", "", 205, 1000, "flac"));

    @TempDir
    Path dir;

    private TestGui test;
    private TableView<LiveList.Row<SearchResult>> table;

    @AfterEach
    void close() {
        if (this.test != null) {
            this.test.close();
        }
    }

    @Test
    void shouldShowTheMediaColumnsAndOfferTheFileIdFromTheColumnMenu() throws Exception {
        this.givenASearchTab();

        this.whenTheServerFinds(LONG_SONG, SHORT_SONG);

        this.thenTheHiddenColumnsAre("File ID");
        this.thenTheShownColumnsInclude("Artist", "Album", "Title", "Length", "Bitrate", "Codec");
        this.thenTheCellsShow("Some Band", "First Album", "Long Song", "1:02:03", "96 Kbit/s", "3:25", "1,000 Kbit/s");
    }

    @Test
    void shouldGiveTheFileIdColumnTheHashOfTheFile() throws Exception {
        this.givenASearchTab();
        this.givenTheServerFound(LONG_SONG);

        this.whenTheColumnIsShown("File ID");

        this.thenTheCellsShow("0123456789ABCDEF0123456789ABCDEF");
    }

    @Test
    void shouldSortTheBitrateAsNumbers() throws Exception {
        this.givenASearchTab();
        this.givenTheServerFound(SHORT_SONG, LONG_SONG);

        this.whenTheResultsAreSortedBy("Bitrate");

        this.thenTheResultsAreInTheOrder("long song.mp3", "short song.flac");
    }

    @Test
    void shouldSortTheLengthAsNumbers() throws Exception {
        this.givenASearchTab();
        this.givenTheServerFound(LONG_SONG, SHORT_SONG);

        this.whenTheResultsAreSortedBy("Length");

        this.thenTheResultsAreInTheOrder("short song.flac", "long song.mp3");
    }

    private void givenASearchTab() throws Exception {
        Map<String, Engines.Command> commands =
                Map.of("search", args -> CompletableFuture.completedFuture(new SearchId(1)));
        this.test = TestGui.start(this.dir.resolve("njmule"), real -> Engines.overriding(real, commands));
        SearchScreen screen = new SearchScreen(this.test.gui);
        this.test.show(FxTesting.onFx(screen::view));
        FxTesting.onFx(() -> {
            screen.queryField().setText("song");
            screen.methodBox().setValue(SearchScreen.Method.SERVER);
            screen.start();
        });
        FxTesting.await("the tab", () -> screen.tabs().getTabs().size() == 1);
        Tab tab = FxTesting.onFx(() -> screen.tabs().getTabs().getFirst());
        this.table = FxTesting.onFx(() -> screen.table(tab));
    }

    private void givenTheServerFound(SearchResult... results) throws Exception {
        this.whenTheServerFinds(results);
    }

    private void whenTheServerFinds(SearchResult... results) throws Exception {
        FxTesting.onFx(() -> this.test.model.handle(new EngineEvent.SearchResults(new SearchId(1), List.of(results))));
        FxTesting.await("the results", () -> this.table.getItems().size() == results.length);
    }

    private void whenTheColumnIsShown(String title) throws Exception {
        FxTesting.onFx(() -> this.column(title).setVisible(true));
    }

    private void whenTheResultsAreSortedBy(String title) throws Exception {
        FxTesting.onFx(() -> {
            TableColumn<LiveList.Row<SearchResult>, ?> column = this.column(title);
            column.setSortType(TableColumn.SortType.ASCENDING);
            this.table.getSortOrder().setAll(List.of(column));
        });
    }

    private void thenTheHiddenColumnsAre(String... titles) throws Exception {
        assertThat(FxTesting.onFx(() -> this.table.getColumns().stream()
                        .filter(c -> !c.isVisible())
                        .map(TableColumn::getText)
                        .toList()))
                .containsExactly(titles);
    }

    private void thenTheShownColumnsInclude(String... titles) throws Exception {
        assertThat(FxTesting.onFx(() -> this.table.getColumns().stream()
                        .filter(TableColumn::isVisible)
                        .map(TableColumn::getText)
                        .toList()))
                .contains(titles);
    }

    private void thenTheCellsShow(String... texts) throws Exception {
        List<String> expected = List.of(texts);
        FxTesting.await("the cells", () -> FxTesting.cellTexts(this.table).containsAll(expected));
        assertThat(FxTesting.onFx(() -> FxTesting.cellTexts(this.table))).containsAll(expected);
    }

    private void thenTheResultsAreInTheOrder(String... names) throws Exception {
        assertThat(FxTesting.onFx(() ->
                        this.table.getItems().stream().map(r -> r.get().name()).toList()))
                .containsExactly(names);
    }

    private TableColumn<LiveList.Row<SearchResult>, ?> column(String title) {
        return this.table.getColumns().stream()
                .filter(c -> title.equals(c.getText()))
                .findFirst()
                .orElseThrow();
    }

    private static SearchResult song(String hash, String name, SearchResult.Media media) {
        return new SearchResult(Hash16.parseHex(hash), name, 4_000_000, 2, 1, Optional.of("Audio"), media);
    }
}
