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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Tab;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.input.Clipboard;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.core.api.DownloadInfo;
import org.njmule.core.api.EngineEvent;
import org.njmule.core.api.EngineStatus;
import org.njmule.core.api.FileHistory;
import org.njmule.core.api.Hash16;
import org.njmule.core.api.KadInfo;
import org.njmule.core.api.NjMuleEngine;
import org.njmule.core.api.SearchId;
import org.njmule.core.api.SearchRequest;
import org.njmule.core.api.SearchResult;
import org.njmule.core.api.ServerInfo;
import org.njmule.core.api.SharedFileInfo;
import org.njmule.core.api.UploadPriority;
import org.njmule.gui.Engines;
import org.njmule.gui.FxTesting;
import org.njmule.gui.Statuses;
import org.njmule.gui.TestGui;
import org.njmule.gui.model.LiveList;

@Timeout(120)
class SearchResultsTest {

    private static final SearchResult FILM_DVD = result("0123456789ABCDEF0123456789ABCDEF", "Film DVD rip.avi");
    private static final SearchResult FILM_HD = result("00112233445566778899AABBCCDDEEFF", "film hd.mkv");
    private static final SearchResult SONG = result("FFEEDDCCBBAA99887766554433221100", "song.mp3");
    private static final SearchResult SHARED = result("11111111111111111111111111111111", "film shared.mkv");
    private static final SearchResult DOWNLOADED = result("22222222222222222222222222222222", "film old.mkv");
    private static final SearchResult CANCELLED = result("33333333333333333333333333333333", "film gone.mkv");

    @TempDir
    Path dir;

    private TestGui test;
    private final List<String> calls = new CopyOnWriteArrayList<>();
    private final AtomicLong ids = new AtomicLong();
    private final AtomicReference<EngineStatus> status = new AtomicReference<>();

    @AfterEach
    void close() {
        if (this.test != null) {
            this.test.close();
        }
    }

    @Test
    void resultsAreFilteredMarkedDownloadedAndCopied() throws Exception {
        SearchScreen screen = this.screen();
        Tab tab = this.search(screen, "film", SearchScreen.Method.SERVER);
        assertThat(this.calls).containsExactly("search film SERVER");
        assertThat(FxTesting.onFx(tab::getText)).isEqualTo("film (0)");

        FxTesting.onFx(() -> this.test.model.handle(
                new EngineEvent.SearchResults(new SearchId(1), List.of(FILM_DVD, FILM_HD, SONG))));
        assertThat(FxTesting.onFx(tab::getText)).isEqualTo("film (3)");
        TableView<LiveList.Row<SearchResult>> table = FxTesting.onFx(() -> screen.table(tab));
        TextField filter = FxTesting.onFx(() -> (TextField) tab.getContent().lookup(".text-field"));
        Label count = FxTesting.onFx(() -> (Label) tab.getContent().lookup(".label"));

        FxTesting.onFx(() -> filter.setText("  DVD   film "));
        assertThat(FxTesting.onFx(() -> table.getItems().size())).isEqualTo(1);
        assertThat(FxTesting.onFx(count::getText)).isEqualTo("Results (1/3)");
        FxTesting.onFx(() -> filter.fireEvent(key(KeyCode.A)));
        assertThat(FxTesting.onFx(() -> filter.getText())).as("another key").isNotEmpty();
        FxTesting.onFx(() -> filter.fireEvent(key(KeyCode.ESCAPE)));
        assertThat(FxTesting.onFx(() -> filter.getText())).isEmpty();
        assertThat(FxTesting.onFx(count::getText)).isEqualTo("Results (3)");

        // Spam: the menu offers what the selection is not yet.
        ContextMenu menu = FxTesting.onFx(table::getContextMenu);
        FxTesting.onFx(() -> {
            this.select(table, FILM_DVD);
            menu.getOnShowing().handle(null);
            assertThat(this.item(menu, "IDS_SEARCHRELATED").isDisable())
                    .as("the server finds no related files")
                    .isTrue();
            assertThat(this.item(menu, "IDS_MARKSPAM").isDisable()).isFalse();
            assertThat(this.item(menu, "IDS_MARKNOTSPAM").isDisable()).isTrue();
            this.item(menu, "IDS_MARKSPAM").fire();
        });
        FxTesting.await("marked as spam", () -> table.lookupAll(".known-spam").size() == 1);
        assertThat(FxTesting.onFx(() -> FxTesting.cellTexts(table))).contains("Spam");
        FxTesting.onFx(() -> {
            menu.getOnShowing().handle(null);
            assertThat(this.item(menu, "IDS_MARKSPAM").isDisable()).isTrue();
            assertThat(this.item(menu, "IDS_MARKNOTSPAM").isDisable()).isFalse();
            this.item(menu, "IDS_MARKNOTSPAM").fire();
        });
        FxTesting.await(
                "not spam any more", () -> table.lookupAll(".known-spam").isEmpty());

        FxTesting.onFx(() -> {
            this.item(menu, "nj.copyLink").fire();
            assertThat(Clipboard.getSystemClipboard().getString()).startsWith("ed2k://|file|Film%20DVD%20rip.avi|");
            this.item(menu, "IDS_DL_LINK2").fire();
            assertThat(Clipboard.getSystemClipboard().getString()).startsWith("<a href=\"ed2k://|file|");
            this.item(menu, "IDS_DOWNLOAD").fire();
        });
        FxTesting.await(
                "downloading", () -> table.lookupAll(".known-downloading").size() == 1);
        assertThat(this.test.engine.downloads().get(10, TimeUnit.SECONDS))
                .extracting(DownloadInfo::name)
                .containsExactly("Film DVD rip.avi");

        // A double click downloads the file of its row.
        FxTesting.onFx(() -> this.doubleClick(table, SONG));
        FxTesting.await(
                "two downloads", () -> table.lookupAll(".known-downloading").size() == 2);

        FxTesting.onFx(() -> {
            screen.more();
            screen.stop();
        });
        FxTesting.await("more and stop", () -> this.calls.containsAll(List.of("more", "stop")));
    }

    @Test
    void whatWasDoneWithAFileShowsBeforeItsSpamMark() throws Exception {
        SearchScreen screen = this.screen();
        Tab tab = this.search(screen, "film", SearchScreen.Method.GLOBAL);
        assertThat(this.calls).containsExactly("search film GLOBAL");
        FxTesting.onFx(() -> this.test.model.handle(
                new EngineEvent.SearchResults(new SearchId(1), List.of(SHARED, DOWNLOADED, CANCELLED, FILM_HD))));
        TableView<LiveList.Row<SearchResult>> table = FxTesting.onFx(() -> screen.table(tab));

        FxTesting.await(
                "the known files",
                () -> table.lookupAll(".known-shared").size() == 1
                        && table.lookupAll(".known-downloaded").size() == 1
                        && table.lookupAll(".known-cancelled").size() == 1);
        assertThat(FxTesting.onFx(() -> FxTesting.cellTexts(table))).contains("Shared", "Downloaded", "Cancelled");
    }

    @Test
    void aRelatedSearchOpensATabOfItsOwnAndAClosedTabStops() throws Exception {
        this.status.set(Statuses.of(Optional.empty(), 0, false, Optional.empty(), true));
        SearchScreen screen = this.screen();
        Tab tab = this.search(screen, "film @size>=1m", SearchScreen.Method.KAD);
        assertThat(this.calls).containsExactly("search film @size>=1m KAD");
        assertThat(FxTesting.onFx(tab::getText))
                .as("no attributes in the title")
                .isEqualTo("film (0)");
        FxTesting.onFx(() ->
                this.test.model.handle(new EngineEvent.SearchResults(new SearchId(1), List.of(FILM_DVD, FILM_HD))));
        TableView<LiveList.Row<SearchResult>> table = FxTesting.onFx(() -> screen.table(tab));
        ContextMenu menu = FxTesting.onFx(table::getContextMenu);

        FxTesting.onFx(() -> {
            menu.getOnShowing().handle(null);
            assertThat(this.item(menu, "IDS_SEARCHRELATED").isDisable())
                    .as("nothing selected")
                    .isTrue();
            screen.searchRelated();
            screen.markSpam(true);
            this.select(table, FILM_HD);
            menu.getOnShowing().handle(null);
            assertThat(this.item(menu, "IDS_SEARCHRELATED").isDisable()).isFalse();
            this.item(menu, "IDS_SEARCHRELATED").fire();
        });
        FxTesting.await("the related tab", () -> screen.tabs().getTabs().size() == 2);
        assertThat(this.calls).contains("search related::00112233445566778899AABBCCDDEEFF SERVER");

        FxTesting.onFx(() -> {
            Tab related = screen.tabs().getTabs().get(1);
            related.getOnClosed().handle(new Event(Tab.CLOSED_EVENT));
            screen.tabs().getTabs().remove(related);
        });
        FxTesting.await("stopped", () -> this.calls.contains("stop"));
        assertThat(FxTesting.onFx(() -> screen.tabs().getTabs())).containsExactly(tab);

        // The tabs left open are kept with their results at the exit.
        FxTesting.onFx(screen::closing);
        assertThat(this.test.engine.savedSearches().get(10, TimeUnit.SECONDS))
                .singleElement()
                .satisfies(s -> {
                    assertThat(s.request().expression()).isEqualTo("film @size>=1m");
                    assertThat(s.results()).extracting(SearchResult::name).contains("film hd.mkv");
                });
    }

    @Test
    void automaticSearchesTheNetworkThereIs() throws Exception {
        SearchScreen screen = this.screen();
        FxTesting.onFx(() -> {
            screen.queryField().setText("film");
            screen.start();
        });
        assertThat(this.test.told).containsExactly("You are not connected to a server or the Kad network!");

        // Kad alone.
        this.status(Optional.empty(), Optional.of(Statuses.kad(true, false, "")));
        this.search(screen, "kad only", SearchScreen.Method.AUTOMATIC);
        // A server alone.
        FxTesting.onFx(() -> this.test.model.handle(
                new EngineEvent.ServerConnected(Statuses.server("81.2.3.4:4661", "Big", false), 99, false, false)));
        this.status(Optional.of(Statuses.server("81.2.3.4:4661", "Big", false)), Optional.empty());
        this.search(screen, "server only", SearchScreen.Method.AUTOMATIC);
        // Both: Kad, unless the server is a static one.
        this.status(
                Optional.of(Statuses.server("81.2.3.4:4661", "Big", false)),
                Optional.of(Statuses.kad(true, false, "")));
        this.search(screen, "both", SearchScreen.Method.AUTOMATIC);
        this.status(
                Optional.of(Statuses.server("81.2.3.4:4661", "Big", true)), Optional.of(Statuses.kad(true, false, "")));
        this.search(screen, "static", SearchScreen.Method.AUTOMATIC);

        assertThat(this.calls)
                .containsExactly(
                        "search kad only KAD", "search server only SERVER", "search both KAD", "search static SERVER");
        assertThat(FxTesting.onFx(() -> screen.queryField().getText())).isEqualTo("static");
        assertThat(this.test.gui.settings().history("search")).startsWith("static", "both", "server only");
    }

    @Test
    void wrongParametersAreToldAndResetClearsThem() throws Exception {
        SearchScreen screen = this.screen();
        FxTesting.onFx(() -> {
            screen.queryField().setText("film");
            screen.methodBox().setValue(SearchScreen.Method.SERVER);
            screen.extensionField().setText("tar.gz");
            screen.start();
        });
        assertThat(this.test.told).hasSize(1);
        FxTesting.onFx(() -> {
            screen.extensionField().clear();
            screen.queryField().setText("(film");
            screen.start();
        });
        assertThat(this.test.told).hasSize(2);
        assertThat(this.calls).isEmpty();

        FxTesting.onFx(() -> {
            screen.typeBox().setValue(SearchScreen.FileType.AUDIO);
            screen.minSizeField().setText("5");
            screen.extensionField().setText("mp3");
            screen.resetParameters();
        });
        assertThat(FxTesting.onFx(() -> screen.typeBox().getValue())).isEqualTo(SearchScreen.FileType.ANY);
        assertThat(FxTesting.onFx(() -> screen.minSizeField().getText()
                        + screen.extensionField().getText()))
                .isEmpty();
    }

    @Test
    void searchesAreNotKeptWhenTheSettingsSaySo() throws Exception {
        SearchScreen screen = this.screen();
        this.test
                .engine
                .updatePreferences(p -> p.toBuilder().storeSearches(false).build())
                .get(10, TimeUnit.SECONDS);
        FxTesting.onFx(
                () -> this.test.model.handle(new EngineEvent.PreferencesChanged(this.test.engine.preferences())));
        this.search(screen, "film", SearchScreen.Method.SERVER);
        FxTesting.onFx(screen::closing);
        assertThat(this.test.engine.savedSearches().get(10, TimeUnit.SECONDS)).isEmpty();
    }

    private SearchScreen screen() throws Exception {
        Map<String, Function<Object[], Object>> commands = new HashMap<>();
        commands.put("search", args -> {
            SearchRequest request = (SearchRequest) args[0];
            this.calls.add("search " + request.expression() + " " + request.scope());
            return CompletableFuture.completedFuture(new SearchId(this.ids.incrementAndGet()));
        });
        commands.put("moreResults", args -> {
            this.calls.add("more");
            return CompletableFuture.completedFuture(true);
        });
        commands.put("stopSearch", args -> {
            this.calls.add("stop");
            return CompletableFuture.completedFuture(null);
        });
        commands.put(
                "fileHistory",
                args -> CompletableFuture.completedFuture(
                        FileHistory.of(Set.of(DOWNLOADED.hash()), CANCELLED.hash()::equals)));
        commands.put(
                "sharedFiles",
                args -> CompletableFuture.completedFuture(List.of(new SharedFileInfo(
                        SHARED.hash(),
                        SHARED.name(),
                        SHARED.size(),
                        this.dir.resolve(SHARED.name()),
                        UploadPriority.NORMAL))));
        this.test = TestGui.start(this.dir.resolve("njmule"), real -> this.wrap(real, commands));
        SearchScreen screen = new SearchScreen(this.test.gui);
        this.test.show(FxTesting.onFx(screen::view));
        if (this.status.get() != null) {
            FxTesting.onFx(this.test.model::tick);
            FxTesting.await("the state", () -> this.test.model.statusProperty().get() == this.status.get());
        }
        return screen;
    }

    private NjMuleEngine wrap(NjMuleEngine real, Map<String, Function<Object[], Object>> commands) {
        Map<String, Function<Object[], Object>> all = new HashMap<>(commands);
        all.put("status", args -> {
            EngineStatus fixed = this.status.get();
            return fixed == null ? real.status() : CompletableFuture.completedFuture(fixed);
        });
        return Engines.overriding(real, all);
    }

    private void status(Optional<ServerInfo> server, Optional<KadInfo> kad) throws Exception {
        EngineStatus next = Statuses.of(server, 5, false, kad, false);
        this.status.set(next);
        FxTesting.onFx(this.test.model::tick);
        FxTesting.await("the state", () -> this.test.model.statusProperty().get() == next);
    }

    // Starts a search for words and returns its tab.
    private Tab search(SearchScreen screen, String words, SearchScreen.Method method) throws Exception {
        int before = FxTesting.onFx(() -> screen.tabs().getTabs().size());
        FxTesting.onFx(() -> {
            screen.queryField().setText(words);
            screen.methodBox().setValue(method);
            screen.start();
        });
        FxTesting.await("the tab", () -> screen.tabs().getTabs().size() == before + 1);
        return FxTesting.onFx(() -> screen.tabs().getTabs().getLast());
    }

    private void select(TableView<LiveList.Row<SearchResult>> table, SearchResult result) {
        table.getSelectionModel().clearSelection();
        for (int i = 0; i < table.getItems().size(); i++) {
            if (table.getItems().get(i).get().equals(result)) {
                table.getSelectionModel().select(i);
            }
        }
    }

    private void doubleClick(TableView<LiveList.Row<SearchResult>> table, SearchResult result) {
        for (Node node : table.lookupAll(".table-row-cell")) {
            if (node instanceof TableRow<?> row
                    && row.getItem() instanceof LiveList.Row<?> item
                    && item.get().equals(result)) {
                row.fireEvent(new MouseEvent(
                        MouseEvent.MOUSE_CLICKED,
                        0,
                        0,
                        0,
                        0,
                        MouseButton.PRIMARY,
                        2,
                        false,
                        false,
                        false,
                        false,
                        true,
                        false,
                        false,
                        true,
                        false,
                        false,
                        null));
                return;
            }
        }
        throw new AssertionError("no row of " + result.name());
    }

    private MenuItem item(ContextMenu menu, String key) {
        String text = this.test.gui.t(key);
        return menu.getItems().stream()
                .filter(i -> text.equals(i.getText()))
                .findFirst()
                .orElseThrow();
    }

    private static KeyEvent key(KeyCode code) {
        return new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, false, false, false);
    }

    private static SearchResult result(String hash, String name) {
        return new SearchResult(Hash16.parseHex(hash), name, 700_000_000, 4, 1, Optional.of("Video"));
    }
}
