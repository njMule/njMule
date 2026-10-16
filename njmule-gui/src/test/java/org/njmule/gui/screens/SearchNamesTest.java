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

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableColumn.SortType;
import javafx.scene.control.TableView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.njmule.core.api.Ed2kLink;
import org.njmule.core.api.EngineEvent;
import org.njmule.core.api.Hash16;
import org.njmule.core.api.SearchId;
import org.njmule.core.api.SearchResult;
import org.njmule.gui.Engines;
import org.njmule.gui.FxTesting;
import org.njmule.gui.TestGui;
import org.njmule.gui.model.LiveList;

/** A file found under several names: its names show under it while it is open. */
@Timeout(120)
class SearchNamesTest {

    // Five sources call it film.avi, three Film HD.avi.
    private static final SearchResult FILM = new SearchResult(
            Hash16.parseHex("000000000000000000000000000000F1"),
            "film.avi",
            2000,
            8,
            2,
            Optional.of("Video"),
            SearchResult.Media.NONE,
            SearchResult.Origin.NONE,
            0,
            Optional.empty(),
            List.of(new SearchResult.Name("film.avi", 5, 1), new SearchResult.Name("Film HD.avi", 3, 1)));
    private static final SearchResult OTHER = new SearchResult(
            Hash16.parseHex("000000000000000000000000000000F2"), "other.avi", 1000, 4, 1, Optional.empty());

    @TempDir
    Path dir;

    private final List<Ed2kLink.FileLink> downloaded = new CopyOnWriteArrayList<>();
    private TestGui test;
    private SearchScreen screen;
    private TableView<LiveList.Row<SearchResult>> table;

    @AfterEach
    void close() {
        if (this.test != null) {
            this.test.close();
        }
    }

    @Test
    void shouldShowTheNamesUnderTheFileOncePlusOpensIt() throws Exception {
        givenTheServerFound(FILM, OTHER);
        givenTheRowChosen("film.avi");

        whenTheKeyIsPressed(KeyCode.ADD);

        thenTheRowsAre("film.avi", "  film.avi", "  Film HD.avi", "other.avi");
        thenTheCountReads("2");
    }

    @Test
    void shouldOpenTheFileOnItsArrow() throws Exception {
        givenTheServerFound(OTHER, FILM);

        whenTheArrowIsClicked();

        thenTheRowsAre("other.avi", "film.avi", "  film.avi", "  Film HD.avi");
    }

    @Test
    void shouldHideTheNamesOnceMinusClosesTheFile() throws Exception {
        givenTheServerFound(FILM, OTHER);
        givenTheRowChosen("film.avi");
        givenTheKeyPressed(KeyCode.ADD);
        givenTheRowChosen("  Film HD.avi");

        whenTheKeyIsPressed(KeyCode.SUBTRACT);

        thenTheRowsAre("film.avi", "other.avi");
    }

    @ParameterizedTest
    @EnumSource(SortType.class)
    void shouldKeepTheNamesUnderTheirFileWhicheverWayTheSizesSort(SortType type) throws Exception {
        givenTheServerFound(FILM, OTHER);
        givenTheRowChosen("film.avi");
        givenTheKeyPressed(KeyCode.ADD);

        whenTheResultsAreSortedBy("IDS_DL_SIZE", type);

        // Most sources first, turned round with the column.
        if (type == SortType.ASCENDING) {
            thenTheRowsAre("other.avi", "film.avi", "  film.avi", "  Film HD.avi");
        } else {
            thenTheRowsAre("film.avi", "  Film HD.avi", "  film.avi", "other.avi");
        }
    }

    @Test
    void shouldHideTheNamesOfAFileTheFilterHides() throws Exception {
        givenTheServerFound(FILM, OTHER);
        givenTheRowChosen("film.avi");
        givenTheKeyPressed(KeyCode.ADD);

        whenTheResultsAreFilteredFor("other");

        thenTheRowsAre("other.avi");
    }

    @Test
    void shouldDownloadTheFileUnderTheNameChosen() throws Exception {
        givenTheServerFound(FILM, OTHER);
        givenTheRowChosen("film.avi");
        givenTheKeyPressed(KeyCode.ADD);
        givenTheRowChosen("  Film HD.avi");

        whenTheChosenRowsAreDownloaded();

        thenTheLinksDownloadedAre("Film HD.avi");
    }

    private void givenTheServerFound(SearchResult... results) throws Exception {
        Map<String, Engines.Command> commands = Map.of(
                "search", args -> CompletableFuture.completedFuture(new SearchId(1)),
                "addDownload",
                        args -> {
                            this.downloaded.add((Ed2kLink.FileLink) args[0]);
                            return CompletableFuture.failedFuture(new IOException("not in this test"));
                        });
        this.test = TestGui.start(this.dir.resolve("njmule"), real -> Engines.overriding(real, commands));
        this.screen = new SearchScreen(this.test.gui);
        this.test.show(FxTesting.onFx(this.screen::view));
        FxTesting.onFx(() -> {
            this.screen.queryField().setText("film");
            this.screen.methodBox().setValue(SearchScreen.Method.SERVER);
            this.screen.start();
        });
        FxTesting.await("the tab", () -> this.screen.tabs().getTabs().size() == 1);
        Tab tab = FxTesting.onFx(() -> this.screen.tabs().getTabs().getFirst());
        this.table = FxTesting.onFx(() -> this.screen.table(tab));
        FxTesting.onFx(() -> this.test.model.handle(new EngineEvent.SearchResults(new SearchId(1), List.of(results))));
        FxTesting.await("the results", () -> this.table.getItems().size() == results.length);
    }

    private void givenTheRowChosen(String shown) throws Exception {
        FxTesting.onFx(() -> {
            this.table.getSelectionModel().clearSelection();
            this.table.getSelectionModel().select(shownRows().indexOf(shown));
        });
    }

    private void givenTheKeyPressed(KeyCode key) throws Exception {
        whenTheKeyIsPressed(key);
    }

    private void whenTheKeyIsPressed(KeyCode key) throws Exception {
        FxTesting.onFx(() -> Event.fireEvent(
                this.table, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", key, false, false, false, false)));
    }

    private void whenTheArrowIsClicked() throws Exception {
        FxTesting.await("the arrow", () -> arrow().isPresent());
        FxTesting.onFx(() -> {
            Node arrow = arrow().orElseThrow();
            Event.fireEvent(
                    arrow,
                    new MouseEvent(
                            MouseEvent.MOUSE_CLICKED,
                            0,
                            0,
                            0,
                            0,
                            MouseButton.PRIMARY,
                            1,
                            false,
                            false,
                            false,
                            false,
                            true,
                            false,
                            false,
                            false,
                            false,
                            true,
                            null));
        });
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

    private void whenTheResultsAreFilteredFor(String text) throws Exception {
        FxTesting.onFx(() -> this.screen.filterField().setText(text));
    }

    private void whenTheChosenRowsAreDownloaded() throws Exception {
        FxTesting.onFx(this.screen::downloadSelected);
        FxTesting.await("the download", () -> !this.downloaded.isEmpty());
    }

    // The names of a file are indented by two spaces.
    private void thenTheRowsAre(String... rows) throws Exception {
        assertThat(FxTesting.onFx(this::shownRows)).containsExactly(rows);
    }

    private void thenTheCountReads(String count) throws Exception {
        assertThat(FxTesting.onFx(() -> this.screen.tabs().getTabs().getFirst().getText()))
                .endsWith("(" + count + ")");
        assertThat(FxTesting.onFx(() -> this.table.getParent().lookupAll(".text-bold").stream()
                        .map(n -> ((Label) n).getText())
                        .toList()))
                .contains(this.test.gui.f("nj.search.count", count));
    }

    private void thenTheLinksDownloadedAre(String... names) {
        assertThat(this.downloaded).extracting(Ed2kLink.FileLink::name).containsExactly(names);
    }

    // The arrow of the file found under several names.
    private Optional<Node> arrow() {
        return this.table.lookupAll(".names-sign").stream()
                .filter(n -> ((Label) n).getGraphic() != null)
                .findFirst();
    }

    private List<String> shownRows() {
        ResultRows names = this.screen.rows(this.screen.tabs().getTabs().getFirst());
        return this.table.getItems().stream()
                .map(r -> (names.isName(r) ? "  " : "") + r.get().name())
                .toList();
    }
}
