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
import static org.assertj.core.api.Assertions.catchThrowable;

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
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
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
    private Throwable exception;

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
    void shouldShowTheClientsAndTheBansInTheListsBelow() throws Exception {
        givenTheTransfersScreen();
        givenTheListsBelowRead();
        givenTheTabsBelowRead("Uploading (1)", "On Queue (1)", "Downloading (6)", "Banned (1)");
        givenTheListBelowShows(0, "Peer", "eMule", "film.mkv");
        givenTheListBelowShows(1, "10.0.0.2:4662");
        givenTheListBelowShows(2, "Waiting");
        givenTheSourcesShow("Waiting", "Connecting", "Asking", "On Queue", "7", "50 %", "0 %");
        givenTheSourcesShowAfterScrollingTo(4, "Transferring");
        givenTheListBelowShows(3, "10.0.0.9");
        givenTheFirstBanLifted();
        givenTheEngineAskedTo("unban 10.0.0.9");
        // View Shared Files opens the client's list, for one client only.
        givenTheSharedFilesViewedWithNoClientChosen();
        givenTheSharedFilesOfTheFirstUploadViewed();
        givenAWindowShows("View Shared Files: Peer");
        givenTheCallsInclude("browse 10.0.0.1:4662");
        givenTheWindowClosed("View Shared Files: Peer");

        whenTheSharedFilesOfTheFirstSourceAreViewed();

        thenAWindowShows("View Shared Files: 10.0.0.3:4662");
    }

    @Test
    void shouldActOnTheChosenDownloadsFromTheDownloadMenu() throws Exception {
        givenTheTransfersScreen();
        givenTheCategories(new Category(1, "Films", Optional.empty(), "", 1, DownloadPriority.AUTO, "*.mkv", false));
        givenTheCategoriesKnown(1);
        givenADownloadFromTheLink();
        givenTheMenuOfTheFirstDownloadShown();
        givenTheCategoriesOfTheMenuAre("(Unassign)", "Films");
        givenTheCategoryOfTheMenuChosen("Films");
        givenTheFirstDownloadInTheCategory(1);
        givenThePreviewPartsFirstChosen();
        givenTheFirstDownloadWithPreviewPartsFirst();
        givenThePreviewPartsFirstCheckedWhenTheMenuShowsAgain();
        givenThePriorityChosen("High");
        givenTheFirstDownloadWithPriority(DownloadPriority.HIGH);
        givenTheItemOfTheMenuChosen("IDS_DL_PAUSE");
        givenTheFirstDownloadIs(DownloadInfo.Status.PAUSED);
        givenTheItemOfTheMenuChosen("IDS_DL_STOP");
        givenTheFirstDownloadIs(DownloadInfo.Status.STOPPED);
        givenTheItemOfTheMenuChosen("IDS_DL_RESUME");
        givenTheFirstDownloadIs(DownloadInfo.Status.DOWNLOADING);
        givenTheLinkCopied("IDS_DL_LINK2", "<a href=\"ed2k://|file|test%20file.bin|");
        givenTheLinkCopied("nj.copyLink", "ed2k://|file|test%20file.bin|");
        givenTheItemOfTheMenuChosen("IDS_DL_CLEAR");
        givenTheDownloadsLeftAfterTheClear(1);
        // Cancel asks nothing first: its message offers to undo it.
        givenTheCancelOfTheMenuChosen();
        givenNoDownloadLeft();
        givenTheCancelUndone();
        givenTheDownloadBack();
        givenTheChosenCancelled();
        givenNoDownloadLeft();

        whenTheChosenAreCancelledAgain();

        thenNoExceptionOccurred();
    }

    @Test
    void shouldAddASourceByHandAndOpenTheComments() throws Exception {
        givenTheTransfersScreen();
        givenADownloadFromTheLink();
        givenASourceAddedWithNoDownloadChosen();
        givenTheFirstDownloadChosen();
        givenASourceTyped("nowhere");
        givenTheUserToldAlready("Not an address of the form host:port: nowhere");
        givenASourceTyped(" 10.0.0.5:4662 ");
        givenTheUserTold("Source 10.0.0.5:4662 added");
        givenTheSourcesRefusedByTheEngine();
        givenASourceTyped("10.0.0.6:4662");
        givenTheUserTold("Source 10.0.0.6:4662 not added: known already or refused by the IP filter");
        givenTheSourceDialogCancelled();
        givenTheCallsAre("source 10.0.0.5:4662", "source 10.0.0.6:4662");
        givenTheItemOfTheMenuChosen("IDS_CMT_ADD");
        givenAWindowShows("Comments: test file.bin");

        whenTheCategoriesButtonIsPressed();

        thenAWindowShows("Categories");
    }

    @Test
    void shouldSwapSourcesWithTheSourceHandlingMenuOfADownload() throws Exception {
        givenTheTransfersScreenWithADownload();

        whenEveryA4afItemOfTheDownloadMenuIsUsed();

        thenTheEngineIsAskedTo("sources here " + FILE, "sources away " + FILE);
        thenTheUserIsTold("Sources swapped: 2", "Sources swapped: 1");
        thenTheDownloadTakesA4afSourcesByItself();
    }

    @Test
    void shouldShowAnA4afSourceWithTheFileItIsAskedForAndSwapIt() throws Exception {
        givenTheListOfSourcesIsShown();

        whenTheA4afSourceIsSwappedToItsDownload();

        thenTheSourcesShow("Asked for another file", "other.bin");
        thenTheEngineIsAskedTo("source here 10.0.0.3:4667");
        thenTheUserIsTold("The source cannot move now: it is being asked or sends data");
    }

    @Test
    void shouldNeedAPlayerForAPreviewAndTellWhyItCannotStart() throws Exception {
        givenTheTransfersScreen();
        givenTheLinkAdded();
        givenAPreviewAskedWithNoDownloadChosen();
        givenTheDownloadRowsAre(1);
        givenTheFirstDownloadChosen();
        givenTheItemOfTheMenuChosen("IDS_DL_PREVIEW");
        givenTheUserToldExactly("Choose a video player in the settings first");
        // A player that is not there: nothing starts, the user learns why.
        givenTheVideoPlayer("no such player", "--fullscreen");

        whenAPreviewOfTheChosenIsAsked();

        thenTheUserIsToldTimes(2);
        thenNothingIsAt("no such player");
    }

    @Test
    void shouldShowTheStateAndPriorityOfEachDownload() throws Exception {
        givenTheDownloads(
                download("00000000000000000000000000000001", "paused.bin", DownloadInfo.Status.PAUSED, 5),
                download("00000000000000000000000000000002", "stopped.bin", DownloadInfo.Status.STOPPED, 50),
                download("00000000000000000000000000000003", "full.bin", DownloadInfo.Status.NO_SPACE, 500),
                download("00000000000000000000000000000004", "broken.bin", DownloadInfo.Status.ERROR, 0));
        givenTheTransfersScreen();
        givenTheDownloadsRead();
        givenTheDownloadsShow(
                "Paused",
                "Stopped",
                "Insufficient disk space",
                "Erroneous",
                "Auto [Hi]",
                "Auto [No]",
                "Auto [Lo]",
                "?");
        // Sorted by priority, as each download acts: the rarest first.
        givenTheDownloadsSortedByPriorityDescending();
        givenTheDownloadsEndWith("stopped.bin", "full.bin");
        // The bar of the categories: All, then one button each; a click on the chosen one keeps it chosen.
        givenTheCategories(new Category(3, "Music", Optional.empty(), "", 2, DownloadPriority.AUTO, "*.mp3", false));
        givenTheCategoryButtons(2);
        givenTheCategoryButtonPressed(1);
        givenTheDownloadsShownAre(0);
        givenTheChosenCategoryButtonClickedAgain(1);
        givenTheCategoryButtonPressed(0);
        givenTheDownloadsShownAre(4);
        // The category shown stays chosen when the categories change.
        givenTheFirstCategoryShown();
        givenACategoryAddedAfterTheFirst(
                new Category(4, "Films", Optional.empty(), "", 1, DownloadPriority.AUTO, "", false));
        givenTheCategoryButtons(3);
        givenTheCategoryButtonChosen(1);
        givenTheCategoryShown(3);

        whenTheCategoriesAreRemoved();

        thenAllTheDownloadsShowAgain(4);
    }

    private void givenTheTransfersScreen() throws Exception {
        this.transfers = screen();
    }

    private void givenTheTransfersScreenWithADownload() throws Exception {
        givenTheTransfersScreen();
        givenADownloadFromTheLink();
    }

    private void givenTheListOfSourcesIsShown() throws Exception {
        this.transfers = screen();
        FxTesting.onFx(this.test.model::tick);
        FxTesting.onFx(this.test.model::tick);
        showTab(this.transfers, 2);
        FxTesting.await("the A4AF source", () -> a4afRow() >= 0);
    }

    private void givenTheListsBelowRead() throws Exception {
        FxTesting.onFx(this.test.model::tick);
        FxTesting.await(
                "the lower lists", () -> this.transfers.bans().getItems().size() == 1);
    }

    private void givenTheTabsBelowRead(String... titles) throws Exception {
        assertThat(FxTesting.onFx(this.transfers::lowerNames)).containsExactly(titles);
    }

    private void givenTheListBelowShows(int tab, String... texts) throws Exception {
        showTab(this.transfers, tab);
        TableView<?> list = switch (tab) {
            case 0 -> this.transfers.uploads();
            case 1 -> this.transfers.queue();
            case 2 -> this.transfers.sources();
            default -> this.transfers.bans();
        };
        FxTesting.await("the list below " + tab, () -> FxTesting.cellTexts(list).containsAll(List.of(texts)));
    }

    private void givenTheSourcesShow(String... texts) throws Exception {
        assertThat(FxTesting.onFx(() -> FxTesting.cellTexts(this.transfers.sources())))
                .contains(texts);
    }

    private void givenTheSourcesShowAfterScrollingTo(int row, String text) throws Exception {
        FxTesting.onFx(() -> this.transfers.sources().scrollTo(row));
        FxTesting.await(
                "the last source",
                () -> FxTesting.cellTexts(this.transfers.sources()).contains(text));
    }

    private void givenTheFirstBanLifted() throws Exception {
        FxTesting.onFx(() -> {
            this.transfers.bans().getSelectionModel().select(0);
            this.transfers.bans().getContextMenu().getItems().getFirst().fire();
        });
    }

    private void givenTheEngineAskedTo(String... asked) throws Exception {
        thenTheEngineIsAskedTo(asked);
    }

    private void givenTheSharedFilesViewedWithNoClientChosen() throws Exception {
        FxTesting.onFx(() -> {
            ContextMenu menu = this.transfers.uploads().getContextMenu();
            MenuItem view = menu.getItems().getFirst();
            this.transfers.uploads().getSelectionModel().clearSelection();
            menu.getOnShowing().handle(null);
            assertThat(view.isDisable()).isTrue();
            view.fire();
        });
    }

    private void givenTheSharedFilesOfTheFirstUploadViewed() throws Exception {
        FxTesting.onFx(() -> {
            ContextMenu menu = this.transfers.uploads().getContextMenu();
            MenuItem view = menu.getItems().getFirst();
            this.transfers.uploads().getSelectionModel().select(0);
            menu.getOnShowing().handle(null);
            assertThat(view.isDisable()).isFalse();
            view.fire();
        });
    }

    private void givenAWindowShows(String title) throws Exception {
        thenAWindowShows(title);
    }

    private void givenTheCallsInclude(String call) {
        assertThat(this.calls).contains(call);
    }

    private void givenTheWindowClosed(String title) throws Exception {
        FxTesting.onFx(() -> window(title).hide());
    }

    private void givenTheCategories(Category... categories) throws Exception {
        FxTesting.onFx(() -> this.test.gui.model().setCategories(List.of(categories), () -> {}));
    }

    private void givenTheCategoriesKnown(int count) throws Exception {
        FxTesting.await("the category", () -> this.test.gui.model().categories().size() == count);
    }

    private void givenTheLinkAdded() throws Exception {
        FxTesting.onFx(() -> {
            this.transfers.linkField().setText(LINK);
            this.transfers.addLink();
        });
    }

    private void givenTheDownloadRowsAre(int count) throws Exception {
        FxTesting.await(
                "the download row", () -> this.transfers.downloads().getItems().size() == count);
    }

    private void givenADownloadFromTheLink() throws Exception {
        givenTheLinkAdded();
        givenTheDownloadRowsAre(1);
    }

    private void givenTheMenuOfTheFirstDownloadShown() throws Exception {
        FxTesting.onFx(() -> {
            this.transfers.downloads().getSelectionModel().select(0);
            downloadMenu().getOnShowing().handle(null);
        });
    }

    private void givenTheCategoriesOfTheMenuAre(String... names) throws Exception {
        FxTesting.onFx(() -> {
            Menu category = (Menu) item(downloadMenu(), "IDS_CAT");
            assertThat(category.getItems()).extracting(MenuItem::getText).containsExactly(names);
        });
    }

    private void givenTheCategoryOfTheMenuChosen(String name) throws Exception {
        FxTesting.onFx(() -> ((Menu) item(downloadMenu(), "IDS_CAT"))
                .getItems().stream()
                        .filter(i -> name.equals(i.getText()))
                        .findFirst()
                        .orElseThrow()
                        .fire());
    }

    private void givenTheFirstDownloadInTheCategory(int category) throws Exception {
        FxTesting.await("in the category", () -> first(this.transfers).category() == category);
    }

    private void givenThePreviewPartsFirstChosen() throws Exception {
        FxTesting.onFx(() -> {
            CheckMenuItem first = (CheckMenuItem) item(downloadMenu(), "IDS_DOWNLOADMOVIECHUNKS");
            assertThat(first.isSelected()).isFalse();
            first.setSelected(true);
            first.fire();
        });
    }

    private void givenTheFirstDownloadWithPreviewPartsFirst() throws Exception {
        FxTesting.await("preview parts first", () -> first(this.transfers).previewFirst());
    }

    private void givenThePreviewPartsFirstCheckedWhenTheMenuShowsAgain() throws Exception {
        FxTesting.onFx(() -> {
            downloadMenu().getOnShowing().handle(null);
            assertThat(((CheckMenuItem) item(downloadMenu(), "IDS_DOWNLOADMOVIECHUNKS")).isSelected())
                    .isTrue();
        });
    }

    private void givenThePriorityChosen(String priority) throws Exception {
        FxTesting.onFx(() -> ((Menu) item(downloadMenu(), "IDS_PRIORITY"))
                .getItems().stream()
                        .filter(i -> i.getText().equals(priority))
                        .findFirst()
                        .orElseThrow()
                        .fire());
    }

    private void givenTheFirstDownloadWithPriority(DownloadPriority priority) throws Exception {
        FxTesting.await("the priority", () -> first(this.transfers).priority() == priority);
    }

    private void givenTheItemOfTheMenuChosen(String key) throws Exception {
        FxTesting.onFx(() -> item(downloadMenu(), key).fire());
    }

    private void givenTheFirstDownloadIs(DownloadInfo.Status status) throws Exception {
        FxTesting.await("the status", () -> first(this.transfers).status() == status);
    }

    private void givenTheLinkCopied(String key, String start) throws Exception {
        String copied = FxTesting.onFx(() -> {
            item(downloadMenu(), key).fire();
            return Clipboard.getSystemClipboard().getString();
        });
        assertThat(copied).startsWith(start);
    }

    private void givenTheDownloadsLeftAfterTheClear(int count) throws Exception {
        assertThat(FxTesting.onFx(() -> this.transfers.downloads().getItems().size()))
                .as("only finished downloads are cleared")
                .isEqualTo(count);
    }

    private void givenTheCancelOfTheMenuChosen() throws Exception {
        FxTesting.onFx(() -> item(downloadMenu(), "IDS_CANCEL").fire());
    }

    private void givenTheCancelUndone() throws Exception {
        assertThat(this.test.told).last().isEqualTo("Downloads cancelled: 1");
        FxTesting.onFx(() -> this.test.offered.getLast().run());
    }

    private void givenTheDownloadBack() throws Exception {
        FxTesting.await(
                "taken back", () -> this.transfers.downloads().getItems().size() == 1);
    }

    private void givenTheChosenCancelled() throws Exception {
        FxTesting.onFx(() -> {
            this.transfers.downloads().getSelectionModel().select(0);
            this.transfers.cancelSelected();
        });
    }

    private void givenNoDownloadLeft() throws Exception {
        FxTesting.await("cancelled", () -> this.transfers.downloads().getItems().isEmpty());
    }

    private void givenASourceAddedWithNoDownloadChosen() throws Exception {
        FxTesting.onFx(this.transfers::addSourceToSelected);
    }

    private void givenTheFirstDownloadChosen() throws Exception {
        FxTesting.onFx(() -> this.transfers.downloads().getSelectionModel().select(0));
    }

    private void givenASourceTyped(String address) throws Exception {
        FxTesting.onFx(() -> {
            FxTesting.answer(ButtonType.OK, pane -> type(pane, address));
            this.transfers.addSourceToSelected();
        });
    }

    private void givenTheUserToldAlready(String message) {
        assertThat(this.test.told).contains(message);
    }

    private void givenTheUserTold(String... messages) throws Exception {
        thenTheUserIsTold(messages);
    }

    private void givenTheSourcesRefusedByTheEngine() {
        this.sourceTaken.set(false);
    }

    private void givenTheSourceDialogCancelled() throws Exception {
        FxTesting.onFx(() -> {
            FxTesting.answer(ButtonType.CANCEL);
            this.transfers.addSourceToSelected();
        });
    }

    private void givenTheCallsAre(String... calls) {
        assertThat(this.calls).containsExactly(calls);
    }

    private void givenAPreviewAskedWithNoDownloadChosen() throws Exception {
        FxTesting.onFx(this.transfers::previewSelected);
    }

    private void givenTheUserToldExactly(String... messages) {
        assertThat(this.test.told).containsExactly(messages);
    }

    private void givenTheVideoPlayer(String name, String args) throws Exception {
        Path player = this.dir.resolve(name);
        Preferences prefs = this.test
                .engine
                .updatePreferences(p -> p.toBuilder()
                        .videoPlayer(player.toString())
                        .videoPlayerArgs(args)
                        .build())
                .get(10, TimeUnit.SECONDS);
        FxTesting.onFx(() -> this.test.model.handle(new EngineEvent.PreferencesChanged(prefs)));
    }

    private void givenTheDownloads(DownloadInfo... downloads) {
        this.downloads.set(List.of(downloads));
    }

    private void givenTheDownloadsRead() throws Exception {
        FxTesting.onFx(this.test.model::tick);
    }

    private void givenTheDownloadsShow(String... texts) throws Exception {
        FxTesting.await(
                "the states",
                () -> FxTesting.cellTexts(this.transfers.downloads()).containsAll(List.of(texts)));
    }

    private void givenTheDownloadsSortedByPriorityDescending() throws Exception {
        FxTesting.onFx(() -> {
            TableColumn<LiveList.Row<DownloadInfo>, ?> priority = this.transfers.downloads().getColumns().stream()
                    .filter(c -> "Priority".equals(c.getText()))
                    .findFirst()
                    .orElseThrow();
            priority.setSortType(TableColumn.SortType.DESCENDING);
            this.transfers.downloads().getSortOrder().setAll(List.of(priority));
        });
    }

    private void givenTheDownloadsEndWith(String... names) throws Exception {
        assertThat(FxTesting.onFx(() -> this.transfers.downloads().getItems().stream()
                        .map(r -> r.get().name())
                        .toList()))
                .endsWith(names);
    }

    private void givenTheCategoryButtons(int count) throws Exception {
        FxTesting.await(
                "the category buttons", () -> categoryButtons(this.transfers).size() == count);
    }

    private void givenTheCategoryButtonPressed(int index) throws Exception {
        FxTesting.onFx(() -> categoryButtons(this.transfers).get(index).fire());
    }

    private void givenTheDownloadsShownAre(int count) throws Exception {
        assertThat(FxTesting.onFx(() -> this.transfers.downloads().getItems().size()))
                .isEqualTo(count);
    }

    private void givenTheChosenCategoryButtonClickedAgain(int index) throws Exception {
        FxTesting.onFx(() -> {
            ToggleButton button = categoryButtons(this.transfers).get(index);
            button.setSelected(false);
            assertThat(button.isSelected()).as("still chosen").isTrue();
        });
    }

    private void givenTheFirstCategoryShown() throws Exception {
        FxTesting.onFx(() -> this.transfers.showCategory(
                this.test.gui.model().categories().getFirst().id()));
    }

    private void givenACategoryAddedAfterTheFirst(Category category) throws Exception {
        FxTesting.onFx(() -> this.test
                .gui
                .model()
                .setCategories(List.of(this.test.gui.model().categories().getFirst(), category), () -> {}));
    }

    private void givenTheCategoryButtonChosen(int index) throws Exception {
        assertThat(FxTesting.onFx(
                        () -> categoryButtons(this.transfers).get(index).isSelected()))
                .isTrue();
    }

    private void givenTheCategoryShown(int id) throws Exception {
        FxTesting.onFx(() -> this.transfers.showCategory(id));
    }

    private void whenTheSharedFilesOfTheFirstSourceAreViewed() throws Exception {
        FxTesting.onFx(() -> {
            this.transfers.sources().getSelectionModel().select(0);
            this.transfers.sources().getContextMenu().getItems().getFirst().fire();
        });
    }

    private void whenTheChosenAreCancelledAgain() {
        this.exception = catchThrowable(() -> FxTesting.onFx(this.transfers::cancelSelected));
    }

    private void whenTheCategoriesButtonIsPressed() throws Exception {
        FxTesting.onFx(() -> this.transfers.view().lookupAll(".button").stream()
                .filter(n -> n instanceof Button b && "Categories".equals(b.getAccessibleText()))
                .map(Button.class::cast)
                .findFirst()
                .orElseThrow()
                .fire());
    }

    private void whenAPreviewOfTheChosenIsAsked() throws Exception {
        FxTesting.onFx(this.transfers::previewSelected);
    }

    private void whenTheCategoriesAreRemoved() throws Exception {
        FxTesting.onFx(() -> this.test.gui.model().setCategories(List.of(), () -> {}));
    }

    private void whenEveryA4afItemOfTheDownloadMenuIsUsed() throws Exception {
        ContextMenu menu = FxTesting.onFx(() -> this.transfers.downloads().getContextMenu());
        FxTesting.onFx(() -> {
            this.transfers.downloads().getSelectionModel().select(0);
            menu.getOnShowing().handle(null);
            Menu handling = (Menu) item(menu, "IDS_A4AF");
            MenuItem here = item(handling.getItems(), "nj.a4af.toThis");
            assertThat(here.isDisable()).as("the download runs").isFalse();
            here.fire();
            CheckMenuItem auto = (CheckMenuItem) item(handling.getItems(), "nj.a4af.auto");
            assertThat(auto.isSelected()).isFalse();
            auto.setSelected(true);
            auto.fire();
            item(handling.getItems(), "nj.a4af.toOther").fire();
        });
    }

    private void whenTheA4afSourceIsSwappedToItsDownload() throws Exception {
        FxTesting.onFx(() -> {
            int row = a4afRow();
            this.transfers.sources().scrollTo(row);
            this.transfers.sources().getSelectionModel().clearAndSelect(row);
            ContextMenu menu = this.transfers.sources().getContextMenu();
            menu.getOnShowing().handle(null);
            MenuItem swap = item(menu.getItems(), "nj.a4af.swapToThis");
            assertThat(swap.isDisable()).as("an A4AF source").isFalse();
            swap.fire();
        });
    }

    private void thenTheEngineIsAskedTo(String... asked) throws Exception {
        FxTesting.assertEventually("the engine asked", () -> this.calls.containsAll(List.of(asked)));
    }

    private void thenTheUserIsTold(String... messages) throws Exception {
        FxTesting.assertEventually("the user told", () -> this.test.told.containsAll(List.of(messages)));
    }

    private void thenTheDownloadTakesA4afSourcesByItself() throws Exception {
        FxTesting.assertEventually(
                "taking A4AF sources", () -> first(this.transfers).autoSwap());
        FxTesting.onFx(() -> {
            ContextMenu menu = this.transfers.downloads().getContextMenu();
            menu.getOnShowing().handle(null);
            Menu handling = (Menu) item(menu, "IDS_A4AF");
            assertThat(((CheckMenuItem) item(handling.getItems(), "nj.a4af.auto")).isSelected())
                    .isTrue();
        });
    }

    private void thenTheSourcesShow(String... texts) throws Exception {
        FxTesting.assertEventually(
                "the A4AF texts",
                () -> FxTesting.cellTexts(this.transfers.sources()).containsAll(List.of(texts)));
        FxTesting.onFx(() -> {
            FxTesting.snapshot(this.transfers.view(), "transfers-a4af");
            return null;
        });
    }

    private void thenAWindowShows(String title) throws Exception {
        FxTesting.assertEventually("the window " + title, () -> window(title) != null);
    }

    private void thenNoExceptionOccurred() {
        assertThat(this.exception).isNull();
    }

    private void thenTheUserIsToldTimes(int count) throws Exception {
        FxTesting.assertEventually("told", () -> this.test.told.size() == count);
    }

    private void thenNothingIsAt(String name) {
        assertThat(Files.exists(this.dir.resolve(name))).isFalse();
    }

    private void thenAllTheDownloadsShowAgain(int count) throws Exception {
        FxTesting.assertEventually(
                "all shown again", () -> this.transfers.downloads().getItems().size() == count);
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
        swapCommands(commands);
        this.test = TestGui.start(this.dir.resolve("njmule"), real -> Engines.overriding(real, with(real, commands)));
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
        FxTesting.onFx(() -> screen.showLower(index));
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

    private ContextMenu downloadMenu() {
        return this.transfers.downloads().getContextMenu();
    }

    private MenuItem item(ContextMenu menu, String key) {
        return item(menu.getItems(), key);
    }

    private MenuItem item(List<MenuItem> items, String key) {
        String text = this.test.gui.t(key);
        return items.stream().filter(i -> text.equals(i.getText())).findFirst().orElseThrow();
    }
}
