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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.input.Clipboard;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.core.api.BanInfo;
import org.njmule.core.api.Category;
import org.njmule.core.api.DownloadInfo;
import org.njmule.core.api.DownloadPriority;
import org.njmule.core.api.EngineEvent;
import org.njmule.core.api.Hash16;
import org.njmule.core.api.NjMuleEngine;
import org.njmule.core.api.PartMap;
import org.njmule.core.api.SearchResult;
import org.njmule.core.api.ServerAddress;
import org.njmule.core.api.SourceInfo;
import org.njmule.core.api.UploadInfo;
import org.njmule.core.config.Preferences;
import org.njmule.gui.Engines;
import org.njmule.gui.FxTesting;
import org.njmule.gui.TestGui;
import org.njmule.gui.model.LiveList;

@Timeout(120)
class TransfersMenusTest {

    private static final String LINK = "ed2k://|file|test%20file.bin|10000|0123456789ABCDEF0123456789ABCDEF|/";
    private static final Hash16 FILE = Hash16.parseHex("0123456789ABCDEF0123456789ABCDEF");
    private static final Hash16 PEER = Hash16.parseHex("00112233445566778899AABBCCDDEEFF");
    private static final Hash16 OTHER = Hash16.parseHex("FEDCBA9876543210FEDCBA9876543210");

    @TempDir
    Path dir;

    private TestGui test;
    private final List<String> calls = new CopyOnWriteArrayList<>();
    private final AtomicBoolean sourceTaken = new AtomicBoolean(true);
    private final AtomicReference<List<DownloadInfo>> downloads = new AtomicReference<>();
    private TransfersScreen transfers;

    @AfterEach
    void close() throws Exception {
        if (this.test != null) {
            FxTesting.onFx(() -> List.copyOf(Window.getWindows()).stream()
                    .filter(w -> w instanceof Stage s && s.getOwner() != null)
                    .forEach(Window::hide));
            this.test.close();
        }
    }

    @Test
    void theListsBelowShowTheClientsAndTheBans() throws Exception {
        TransfersScreen screen = this.screen();
        FxTesting.onFx(this.test.model::tick);
        FxTesting.await("the lower lists", () -> screen.bans().getItems().size() == 1);

        assertThat(FxTesting.onFx(() -> screen.lowerTabs().getTabs().stream()
                        .map(t -> t.getText())
                        .toList()))
                .containsExactly("Uploading (1)", "On Queue (1)", "Downloading (6)", "Banned (1)");
        this.showTab(screen, 0);
        FxTesting.await(
                "the upload",
                () -> FxTesting.cellTexts(screen.uploads()).containsAll(List.of("Peer", "eMule", "film.mkv")));
        this.showTab(screen, 1);
        FxTesting.await(
                "the waiting client", () -> FxTesting.cellTexts(screen.queue()).contains("10.0.0.2:4662"));
        this.showTab(screen, 2);
        FxTesting.await(
                "the sources", () -> FxTesting.cellTexts(screen.sources()).contains("Waiting"));
        assertThat(FxTesting.onFx(() -> FxTesting.cellTexts(screen.sources())))
                .contains("Waiting", "Connecting", "Asking", "On Queue", "7", "50 %", "0 %");
        FxTesting.onFx(() -> screen.sources().scrollTo(4));
        FxTesting.await(
                "the last source", () -> FxTesting.cellTexts(screen.sources()).contains("Transferring"));
        this.showTab(screen, 3);
        FxTesting.await("the ban", () -> FxTesting.cellTexts(screen.bans()).contains("10.0.0.9"));

        FxTesting.onFx(() -> {
            screen.bans().getSelectionModel().select(0);
            screen.bans().getContextMenu().getItems().getFirst().fire();
        });
        FxTesting.await("unbanned", () -> this.calls.contains("unban 10.0.0.9"));

        // View Shared Files opens the client's list, for one client only.
        ContextMenu menu = FxTesting.onFx(() -> screen.uploads().getContextMenu());
        MenuItem view = FxTesting.onFx(() -> menu.getItems().getFirst());
        FxTesting.onFx(() -> {
            screen.uploads().getSelectionModel().clearSelection();
            menu.getOnShowing().handle(null);
            assertThat(view.isDisable()).isTrue();
            view.fire();
            screen.uploads().getSelectionModel().select(0);
            menu.getOnShowing().handle(null);
            assertThat(view.isDisable()).isFalse();
            view.fire();
        });
        FxTesting.await("the client's files", () -> this.window("View Shared Files: Peer") != null);
        assertThat(this.calls).contains("browse 10.0.0.1:4662");
        FxTesting.onFx(() -> {
            this.window("View Shared Files: Peer").hide();
            screen.sources().getSelectionModel().select(0);
            screen.sources().getContextMenu().getItems().getFirst().fire();
        });
        FxTesting.await("the source's files", () -> this.window("View Shared Files: 10.0.0.3:4662") != null);
    }

    @Test
    void theDownloadMenuActsOnTheSelectedDownloads() throws Exception {
        TransfersScreen screen = this.screen();
        FxTesting.onFx(() -> this.test
                .gui
                .model()
                .setCategories(
                        List.of(new Category(
                                1, "Films", Optional.empty(), "", 1, DownloadPriority.AUTO, "*.mkv", false)),
                        () -> {}));
        FxTesting.await("the category", () -> this.test.gui.model().categories().size() == 1);
        FxTesting.onFx(() -> {
            screen.linkField().setText(LINK);
            screen.addLink();
        });
        FxTesting.await("the download row", () -> screen.downloads().getItems().size() == 1);
        ContextMenu menu = FxTesting.onFx(() -> screen.downloads().getContextMenu());

        FxTesting.onFx(() -> {
            screen.downloads().getSelectionModel().select(0);
            menu.getOnShowing().handle(null);
            Menu category = (Menu) this.item(menu, "IDS_CAT");
            assertThat(category.getItems()).extracting(MenuItem::getText).containsExactly("(Unassign)", "Films");
            category.getItems().get(1).fire();
        });
        FxTesting.await("in the category", () -> this.first(screen).category() == 1);
        FxTesting.onFx(() -> {
            CheckMenuItem first = (CheckMenuItem) this.item(menu, "IDS_DOWNLOADMOVIECHUNKS");
            assertThat(first.isSelected()).isFalse();
            first.setSelected(true);
            first.fire();
        });
        FxTesting.await("preview parts first", () -> this.first(screen).previewFirst());
        FxTesting.onFx(() -> {
            menu.getOnShowing().handle(null);
            assertThat(((CheckMenuItem) this.item(menu, "IDS_DOWNLOADMOVIECHUNKS")).isSelected())
                    .isTrue();
            ((Menu) this.item(menu, "IDS_PRIORITY"))
                    .getItems().stream()
                            .filter(i -> i.getText().equals("High"))
                            .findFirst()
                            .orElseThrow()
                            .fire();
        });
        FxTesting.await("high", () -> this.first(screen).priority() == DownloadPriority.HIGH);
        FxTesting.onFx(() -> this.item(menu, "IDS_DL_PAUSE").fire());
        FxTesting.await("paused", () -> this.first(screen).status() == DownloadInfo.Status.PAUSED);
        FxTesting.onFx(() -> this.item(menu, "IDS_DL_STOP").fire());
        FxTesting.await("stopped", () -> this.first(screen).status() == DownloadInfo.Status.STOPPED);
        FxTesting.onFx(() -> this.item(menu, "IDS_DL_RESUME").fire());
        FxTesting.await("resumed", () -> this.first(screen).status() == DownloadInfo.Status.DOWNLOADING);
        FxTesting.onFx(() -> {
            this.item(menu, "IDS_DL_LINK2").fire();
            assertThat(Clipboard.getSystemClipboard().getString())
                    .startsWith("<a href=\"ed2k://|file|test%20file.bin|");
            this.item(menu, "nj.copyLink").fire();
            assertThat(Clipboard.getSystemClipboard().getString()).startsWith("ed2k://|file|test%20file.bin|");
            this.item(menu, "IDS_DL_CLEAR").fire();
        });
        assertThat(FxTesting.onFx(() -> screen.downloads().getItems().size()))
                .as("only finished downloads are cleared")
                .isEqualTo(1);

        // Cancel asks first.
        FxTesting.onFx(() -> {
            FxTesting.answer(ButtonBar.ButtonData.CANCEL_CLOSE);
            this.item(menu, "IDS_CANCEL").fire();
        });
        assertThat(this.test.engine.downloads().get(10, TimeUnit.SECONDS)).hasSize(1);
        FxTesting.onFx(() -> {
            FxTesting.answer(ButtonBar.ButtonData.OK_DONE);
            screen.cancelSelected();
        });
        FxTesting.await("cancelled", () -> screen.downloads().getItems().isEmpty());
        FxTesting.onFx(screen::cancelSelected);
    }

    @Test
    void aSourceIsAddedByHandAndCommentsOpen() throws Exception {
        TransfersScreen screen = this.screen();
        FxTesting.onFx(() -> {
            screen.linkField().setText(LINK);
            screen.addLink();
        });
        FxTesting.await("the download row", () -> screen.downloads().getItems().size() == 1);
        FxTesting.onFx(() -> {
            screen.addSourceToSelected();
            screen.downloads().getSelectionModel().select(0);
            FxTesting.answer(ButtonType.OK, pane -> this.type(pane, "nowhere"));
            screen.addSourceToSelected();
        });
        assertThat(this.test.told).contains("Not an address of the form host:port: nowhere");

        FxTesting.onFx(() -> {
            FxTesting.answer(ButtonType.OK, pane -> this.type(pane, " 10.0.0.5:4662 "));
            screen.addSourceToSelected();
        });
        FxTesting.await("added", () -> this.test.told.contains("Source 10.0.0.5:4662 added"));
        this.sourceTaken.set(false);
        FxTesting.onFx(() -> {
            FxTesting.answer(ButtonType.OK, pane -> this.type(pane, "10.0.0.6:4662"));
            screen.addSourceToSelected();
        });
        FxTesting.await(
                "refused",
                () -> this.test.told.contains(
                        "Source 10.0.0.6:4662 not added: known already or refused by the IP filter"));
        FxTesting.onFx(() -> {
            FxTesting.answer(ButtonType.CANCEL);
            screen.addSourceToSelected();
        });
        assertThat(this.calls).containsExactly("source 10.0.0.5:4662", "source 10.0.0.6:4662");

        FxTesting.onFx(() ->
                this.item(screen.downloads().getContextMenu(), "IDS_CMT_ADD").fire());
        FxTesting.await("the comment window", () -> this.window("Comments: test file.bin") != null);

        FxTesting.onFx(() -> screen.view().lookupAll(".button").stream()
                .filter(n -> n instanceof Button b && "Categories".equals(b.getAccessibleText()))
                .map(Button.class::cast)
                .findFirst()
                .orElseThrow()
                .fire());
        FxTesting.await("the categories window", () -> this.window("Categories") != null);
    }

    @Test
    void shouldSwapSourcesWithTheSourceHandlingMenuOfADownload() throws Exception {
        this.givenTheTransfersScreenWithADownload();

        this.whenEveryA4afItemOfTheDownloadMenuIsUsed();

        this.thenTheEngineIsAskedTo("sources here " + FILE, "sources away " + FILE);
        this.thenTheUserIsTold("Sources swapped: 2", "Sources swapped: 1");
        this.thenTheDownloadTakesA4afSourcesByItself();
    }

    @Test
    void shouldShowAnA4afSourceWithTheFileItIsAskedForAndSwapIt() throws Exception {
        this.givenTheListOfSourcesIsShown();

        this.whenTheA4afSourceIsSwappedToItsDownload();

        this.thenTheSourcesShow("Asked for another file", "other.bin");
        this.thenTheEngineIsAskedTo("source here 10.0.0.3:4667");
        this.thenTheUserIsTold("The source cannot move now: it is being asked or sends data");
    }

    private void givenTheTransfersScreenWithADownload() throws Exception {
        this.transfers = this.screen();
        FxTesting.onFx(() -> {
            this.transfers.linkField().setText(LINK);
            this.transfers.addLink();
        });
        FxTesting.await(
                "the download row", () -> this.transfers.downloads().getItems().size() == 1);
    }

    private void givenTheListOfSourcesIsShown() throws Exception {
        this.transfers = this.screen();
        FxTesting.onFx(this.test.model::tick);
        FxTesting.onFx(this.test.model::tick);
        this.showTab(this.transfers, 2);
        FxTesting.await("the A4AF source", () -> this.a4afRow() >= 0);
    }

    private void whenEveryA4afItemOfTheDownloadMenuIsUsed() throws Exception {
        ContextMenu menu = FxTesting.onFx(() -> this.transfers.downloads().getContextMenu());
        FxTesting.onFx(() -> {
            this.transfers.downloads().getSelectionModel().select(0);
            menu.getOnShowing().handle(null);
            Menu handling = (Menu) this.item(menu, "IDS_A4AF");
            MenuItem here = this.item(handling.getItems(), "nj.a4af.toThis");
            assertThat(here.isDisable()).as("the download runs").isFalse();
            here.fire();
            CheckMenuItem auto = (CheckMenuItem) this.item(handling.getItems(), "nj.a4af.auto");
            assertThat(auto.isSelected()).isFalse();
            auto.setSelected(true);
            auto.fire();
            this.item(handling.getItems(), "nj.a4af.toOther").fire();
        });
    }

    private void whenTheA4afSourceIsSwappedToItsDownload() throws Exception {
        FxTesting.onFx(() -> {
            int row = this.a4afRow();
            this.transfers.sources().scrollTo(row);
            this.transfers.sources().getSelectionModel().clearAndSelect(row);
            ContextMenu menu = this.transfers.sources().getContextMenu();
            menu.getOnShowing().handle(null);
            MenuItem swap = this.item(menu.getItems(), "nj.a4af.swapToThis");
            assertThat(swap.isDisable()).as("an A4AF source").isFalse();
            swap.fire();
        });
    }

    private void thenTheEngineIsAskedTo(String... asked) throws Exception {
        FxTesting.await("the engine asked", () -> this.calls.containsAll(List.of(asked)));
    }

    private void thenTheUserIsTold(String... messages) throws Exception {
        FxTesting.await("the user told", () -> this.test.told.containsAll(List.of(messages)));
    }

    private void thenTheDownloadTakesA4afSourcesByItself() throws Exception {
        FxTesting.await("taking A4AF sources", () -> this.first(this.transfers).autoSwap());
        FxTesting.onFx(() -> {
            ContextMenu menu = this.transfers.downloads().getContextMenu();
            menu.getOnShowing().handle(null);
            Menu handling = (Menu) this.item(menu, "IDS_A4AF");
            assertThat(((CheckMenuItem) this.item(handling.getItems(), "nj.a4af.auto")).isSelected())
                    .isTrue();
        });
    }

    private void thenTheSourcesShow(String... texts) throws Exception {
        FxTesting.await(
                "the A4AF texts",
                () -> FxTesting.cellTexts(this.transfers.sources()).containsAll(List.of(texts)));
        FxTesting.onFx(() -> {
            FxTesting.snapshot(this.transfers.view(), "transfers-a4af");
            return null;
        });
    }

    // The row of the source asked for another file, -1 while there is none.
    private int a4afRow() {
        List<LiveList.Row<SourceInfo>> rows = this.transfers.sources().getItems();
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).get().state() == SourceInfo.State.A4AF) {
                return i;
            }
        }
        return -1;
    }

    @Test
    void aPreviewNeedsAPlayerAndTellsWhyItCannotStart() throws Exception {
        TransfersScreen screen = this.screen();
        FxTesting.onFx(() -> {
            screen.linkField().setText(LINK);
            screen.addLink();
            screen.previewSelected();
        });
        FxTesting.await("the download row", () -> screen.downloads().getItems().size() == 1);
        FxTesting.onFx(() -> {
            screen.downloads().getSelectionModel().select(0);
            this.item(screen.downloads().getContextMenu(), "IDS_DL_PREVIEW").fire();
        });
        assertThat(this.test.told).containsExactly("Choose a video player in the settings first");

        // A player that is not there: nothing starts, the user learns why.
        Path player = this.dir.resolve("no such player");
        Preferences prefs = this.test
                .engine
                .updatePreferences(p -> p.toBuilder()
                        .videoPlayer(player.toString())
                        .videoPlayerArgs("--fullscreen")
                        .build())
                .get(10, TimeUnit.SECONDS);
        FxTesting.onFx(() -> this.test.model.handle(new EngineEvent.PreferencesChanged(prefs)));
        FxTesting.onFx(screen::previewSelected);
        FxTesting.await("told", () -> this.test.told.size() == 2);
        assertThat(Files.exists(player)).isFalse();
    }

    @Test
    void eachDownloadShowsItsStateAndPriority() throws Exception {
        this.downloads.set(List.of(
                this.download("00000000000000000000000000000001", "paused.bin", DownloadInfo.Status.PAUSED, 5),
                this.download("00000000000000000000000000000002", "stopped.bin", DownloadInfo.Status.STOPPED, 50),
                this.download("00000000000000000000000000000003", "full.bin", DownloadInfo.Status.NO_SPACE, 500),
                this.download("00000000000000000000000000000004", "broken.bin", DownloadInfo.Status.ERROR, 0)));
        TransfersScreen screen = this.screen();
        FxTesting.onFx(this.test.model::tick);

        FxTesting.await(
                "the states",
                () -> FxTesting.cellTexts(screen.downloads())
                        .containsAll(List.of(
                                "Paused",
                                "Stopped",
                                "Insufficient disk space",
                                "Erroneous",
                                "Auto [Hi]",
                                "Auto [No]",
                                "Auto [Lo]",
                                "?")));

        // Sorted by priority, as each download acts: the rarest first.
        FxTesting.onFx(() -> {
            TableColumn<LiveList.Row<DownloadInfo>, ?> priority = screen.downloads().getColumns().stream()
                    .filter(c -> "Priority".equals(c.getText()))
                    .findFirst()
                    .orElseThrow();
            priority.setSortType(TableColumn.SortType.DESCENDING);
            screen.downloads().getSortOrder().setAll(List.of(priority));
        });
        assertThat(FxTesting.onFx(() -> screen.downloads().getItems().stream()
                        .map(r -> r.get().name())
                        .toList()))
                .endsWith("stopped.bin", "full.bin");

        // The bar of the categories: All, then one button each; a click on the chosen one keeps it chosen.
        FxTesting.onFx(() -> this.test
                .gui
                .model()
                .setCategories(
                        List.of(new Category(
                                3, "Music", Optional.empty(), "", 2, DownloadPriority.AUTO, "*.mp3", false)),
                        () -> {}));
        FxTesting.await(
                "the category button", () -> this.categoryButtons(screen).size() == 2);
        FxTesting.onFx(() -> {
            ToggleButton music = this.categoryButtons(screen).get(1);
            music.fire();
            assertThat(screen.downloads().getItems()).isEmpty();
            music.setSelected(false);
            assertThat(music.isSelected()).as("still chosen").isTrue();
            this.categoryButtons(screen).getFirst().fire();
        });
        assertThat(FxTesting.onFx(() -> screen.downloads().getItems().size())).isEqualTo(4);

        // The category shown stays chosen when the categories change.
        int music = FxTesting.onFx(
                () -> this.test.gui.model().categories().getFirst().id());
        FxTesting.onFx(() -> {
            screen.showCategory(music);
            this.test
                    .gui
                    .model()
                    .setCategories(
                            List.of(
                                    this.test.gui.model().categories().getFirst(),
                                    new Category(
                                            4, "Films", Optional.empty(), "", 1, DownloadPriority.AUTO, "", false)),
                            () -> {});
        });
        FxTesting.await(
                "two category buttons", () -> this.categoryButtons(screen).size() == 3);
        assertThat(FxTesting.onFx(() -> this.categoryButtons(screen).get(1).isSelected()))
                .isTrue();
        FxTesting.onFx(() -> screen.showCategory(3));
        FxTesting.onFx(() -> this.test.gui.model().setCategories(List.of(), () -> {}));
        FxTesting.await("all shown again", () -> screen.downloads().getItems().size() == 4);
    }

    private TransfersScreen screen() throws Exception {
        Map<String, Engines.Command> commands = new HashMap<>();
        commands.put(
                "uploads",
                args -> CompletableFuture.completedFuture(List.of(
                        new UploadInfo(
                                "10.0.0.1:4662",
                                PEER,
                                Optional.of("Peer"),
                                "eMule",
                                "film.mkv",
                                UploadInfo.State.UPLOADING,
                                1000,
                                100,
                                30),
                        new UploadInfo(
                                "10.0.0.2:4662",
                                PEER,
                                Optional.empty(),
                                "aMule",
                                "song.mp3",
                                UploadInfo.State.QUEUED,
                                0,
                                0,
                                600))));
        commands.put("sources", args -> {
            List<SourceInfo> sources = new ArrayList<>();
            int port = 4662;
            for (SourceInfo.State state : SourceInfo.State.values()) {
                boolean a4af = state == SourceInfo.State.A4AF;
                sources.add(new SourceInfo(
                        FILE,
                        "film.mkv",
                        "10.0.0.3:" + port++,
                        Optional.empty(),
                        "",
                        state,
                        state == SourceInfo.State.QUEUED ? 7 : 0,
                        2000,
                        state == SourceInfo.State.WAITING ? 0 : 2,
                        state == SourceInfo.State.WAITING ? 0 : 4,
                        a4af ? Optional.of(OTHER) : Optional.empty(),
                        a4af ? "other.bin" : ""));
            }
            return CompletableFuture.completedFuture(sources);
        });
        commands.put("bans", args -> CompletableFuture.completedFuture(List.of(new BanInfo("10.0.0.9", 3600))));
        commands.put("unban", args -> {
            this.calls.add("unban " + args[0]);
            return CompletableFuture.completedFuture(true);
        });
        commands.put("browseSharedFiles", args -> {
            this.calls.add("browse " + args[0]);
            return CompletableFuture.completedFuture(
                    List.of(new SearchResult(FILE, "film.mkv", 700_000_000, 1, 1, Optional.empty())));
        });
        commands.put("addSource", args -> {
            this.calls.add("source " + args[1]);
            return CompletableFuture.completedFuture(this.sourceTaken.get());
        });
        commands.put(
                "downloadData", args -> CompletableFuture.completedFuture(Optional.of(this.dir.resolve("data.part"))));
        this.swapCommands(commands);
        this.test =
                TestGui.start(this.dir.resolve("njmule"), real -> Engines.overriding(real, this.with(real, commands)));
        TransfersScreen screen = new TransfersScreen(this.test.gui);
        this.test.show(FxTesting.onFx(screen::view));
        return screen;
    }

    // The swaps of A4AF sources answer at once.
    private void swapCommands(Map<String, Engines.Command> commands) {
        commands.put("swapSourcesToDownload", args -> {
            this.calls.add("sources here " + args[0]);
            return CompletableFuture.completedFuture(2);
        });
        commands.put("swapSourcesFromDownload", args -> {
            this.calls.add("sources away " + args[0]);
            return CompletableFuture.completedFuture(1);
        });
        commands.put("swapSourceToDownload", args -> {
            ServerAddress source = (ServerAddress) args[1];
            this.calls.add("source here " + source.host() + ":" + source.port());
            return CompletableFuture.completedFuture(false);
        });
    }

    // The downloads are the engine's, unless the test gives its own.
    private Map<String, Engines.Command> with(NjMuleEngine real, Map<String, Engines.Command> commands) {
        commands.put("downloads", args -> {
            List<DownloadInfo> own = this.downloads.get();
            return own == null ? real.downloads() : CompletableFuture.completedFuture(own);
        });
        return commands;
    }

    private DownloadInfo download(String hash, String name, DownloadInfo.Status status, int sources) {
        return new DownloadInfo(
                Hash16.parseHex(hash), name, 1000, 100, status, DownloadPriority.AUTO, 0, sources, 0, 0, PartMap.EMPTY);
    }

    private List<ToggleButton> categoryButtons(TransfersScreen screen) {
        return screen.view().lookupAll(".toggle-button").stream()
                .filter(n ->
                        n.getParent() != null && n.getParent().getStyleClass().contains("category-bar"))
                .map(ToggleButton.class::cast)
                .toList();
    }

    private DownloadInfo first(TransfersScreen screen) {
        return screen.downloads().getItems().getFirst().get();
    }

    private void showTab(TransfersScreen screen, int index) throws Exception {
        FxTesting.onFx(() -> screen.lowerTabs().getSelectionModel().select(index));
    }

    private void type(Node pane, String text) {
        ((TextField) pane.lookup(".text-field")).setText(text);
    }

    private Window window(String title) {
        return Window.getWindows().stream()
                .filter(w -> w instanceof Stage s && title.equals(s.getTitle()) && s.isShowing())
                .findFirst()
                .orElse(null);
    }

    private MenuItem item(ContextMenu menu, String key) {
        return this.item(menu.getItems(), key);
    }

    private MenuItem item(List<MenuItem> items, String key) {
        String text = this.test.gui.t(key);
        return items.stream().filter(i -> text.equals(i.getText())).findFirst().orElseThrow();
    }
}
