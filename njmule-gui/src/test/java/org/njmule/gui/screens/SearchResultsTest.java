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

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Tab;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
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
    // Another file under the name of FILM_DVD.
    private static final SearchResult LOOKALIKE = result("0F0F0F0F0F0F0F0F0F0F0F0F0F0F0F0F", "film dvd RIP.avi");
    private static final SearchResult SHARED = result("11111111111111111111111111111111", "film shared.mkv");
    private static final SearchResult DOWNLOADED = result("22222222222222222222222222222222", "film old.mkv");
    private static final SearchResult CANCELLED = result("33333333333333333333333333333333", "film gone.mkv");
    private static final ServerInfo BIG = Statuses.server("81.2.3.4:4661", "Big", false);
    private static final ServerInfo BIG_STATIC = Statuses.server("81.2.3.4:4661", "Big", true);

    @TempDir
    Path dir;

    private TestGui test;
    private final List<String> calls = new CopyOnWriteArrayList<>();
    private final AtomicLong ids = new AtomicLong();
    private final AtomicReference<EngineStatus> status = new AtomicReference<>();
    private SearchScreen shownScreen;
    private Tab shownTab;
    // Commands a test replaces beside those of every test.
    private final Map<String, Engines.Command> extra = new HashMap<>();
    private Throwable exception;

    @AfterEach
    void close() {
        if (this.test != null) {
            this.test.close();
        }
    }

    @Test
    void shouldCountTheResultsOfAServerSearchInItsTab() throws Exception {
        givenTheSearchScreen();
        givenASearchFor("film", SearchScreen.Method.SERVER);
        givenTheEngineWasAskedFor("search film SERVER");
        givenTheTabReads("film (0)");

        whenTheResultsCome(FILM_DVD, FILM_HD, SONG);

        thenTheTabReads("film (3)");
    }

    @Test
    void shouldFilterTheResultsByEveryWordUntilEscape() throws Exception {
        givenTheSearchScreen();
        givenASearchFor("film", SearchScreen.Method.SERVER);
        givenTheResultsCome(FILM_DVD, FILM_HD, SONG);
        givenTheFilterTyped("  DVD   film ");
        givenTheTableShows(1, "Results (1/3)");
        givenAKeyPressedInTheFilter(KeyCode.A);
        givenTheFilterIsKeptByAnotherKey();

        whenAKeyIsPressedInTheFilter(KeyCode.ESCAPE);

        thenTheFilterIsEmpty();
        thenTheCountReads("Results (3)");
    }

    @Test
    void shouldMarkAChosenResultAsSpamFromTheMenu() throws Exception {
        givenTheSearchScreen();
        givenASearchFor("film", SearchScreen.Method.SERVER);
        givenTheResultsCome(FILM_DVD, FILM_HD, SONG);
        givenTheResultChosen(FILM_DVD);
        givenTheMenuShown();
        givenTheMenuItemIsOff("IDS_SEARCHRELATED", "the server finds no related files");
        givenTheMenuItemsAreOff(false, "IDS_MARKSPAM");
        givenTheMenuItemsAreOff(true, "IDS_MARKNOTSPAM");

        whenTheMenuItemIsFired("IDS_MARKSPAM");

        thenOneResultIsMarkedAsSpam();
    }

    @Test
    void shouldShowAsSpamAResultRatedSoWithoutAMark() throws Exception {
        givenTheSearchScreen();
        givenASearchFor("film", SearchScreen.Method.SERVER);

        whenTheResultsCome(FILM_DVD.withSpamRating(SearchResult.SPAM_THRESHOLD), FILM_HD, SONG);

        thenOneResultIsMarkedAsSpam();
    }

    @Test
    void shouldRateTheOtherResultsAgainOnceOneIsMarkedAsSpam() throws Exception {
        givenTheSearchScreen();
        givenASearchFor("film", SearchScreen.Method.SERVER);
        givenTheResultsCome(FILM_DVD, LOOKALIKE, FILM_HD);
        givenTheResultChosen(FILM_DVD);
        givenTheMenuShown();

        whenTheMenuItemIsFired("IDS_MARKSPAM");

        thenTheResultsMarkedAsSpamAre(2);
    }

    @Test
    void shouldMarkASpamResultBackFromTheMenu() throws Exception {
        givenTheSearchScreen();
        givenASearchFor("film", SearchScreen.Method.SERVER);
        givenTheResultsCome(FILM_DVD, FILM_HD, SONG);
        givenTheResultChosen(FILM_DVD);
        givenTheMenuShown();
        givenTheMenuItemFired("IDS_MARKSPAM");
        givenOneResultMarkedAsSpam();
        givenTheMenuShown();
        givenTheMenuItemsAreOff(true, "IDS_MARKSPAM");
        givenTheMenuItemsAreOff(false, "IDS_MARKNOTSPAM");

        whenTheMenuItemIsFired("IDS_MARKNOTSPAM");

        thenNoResultIsMarkedAsSpam();
    }

    @Test
    void shouldCopyTheLinkOfTheChosenResult() throws Exception {
        givenTheSearchScreen();
        givenASearchFor("film", SearchScreen.Method.SERVER);
        givenTheResultsCome(FILM_DVD, FILM_HD, SONG);
        givenTheResultChosen(FILM_DVD);
        givenTheMenuShown();

        whenTheMenuItemIsFired("nj.copyLink");

        thenTheClipboardStartsWith("ed2k://|file|Film%20DVD%20rip.avi|");
    }

    @Test
    void shouldCopyTheHtmlLinkOfTheChosenResult() throws Exception {
        givenTheSearchScreen();
        givenASearchFor("film", SearchScreen.Method.SERVER);
        givenTheResultsCome(FILM_DVD, FILM_HD, SONG);
        givenTheResultChosen(FILM_DVD);
        givenTheMenuShown();

        whenTheMenuItemIsFired("IDS_DL_LINK2");

        thenTheClipboardStartsWith("<a href=\"ed2k://|file|");
    }

    @Test
    void shouldDownloadTheChosenResultFromTheMenu() throws Exception {
        givenTheSearchScreen();
        givenASearchFor("film", SearchScreen.Method.SERVER);
        givenTheResultsCome(FILM_DVD, FILM_HD, SONG);
        givenTheResultChosen(FILM_DVD);
        givenTheMenuShown();

        whenTheMenuItemIsFired("IDS_DOWNLOAD");

        thenTheResultsDownloadingAre(1);
        thenTheEngineDownloads("Film DVD rip.avi");
    }

    @Test
    void shouldDownloadTheFileOfADoubleClickedRow() throws Exception {
        givenTheSearchScreen();
        givenASearchFor("film", SearchScreen.Method.SERVER);
        givenTheResultsCome(FILM_DVD, FILM_HD, SONG);
        givenTheResultChosen(FILM_DVD);
        givenTheMenuShown();
        givenTheMenuItemFired("IDS_DOWNLOAD");
        givenTheResultsDownloading(1);

        whenTheRowIsDoubleClicked(SONG);

        thenTheResultsDownloadingAre(2);
    }

    @Test
    void shouldShowTheRowOfAResultBeingDownloadedInBold() throws Exception {
        givenTheSearchScreen();
        givenASearchFor("film", SearchScreen.Method.SERVER);
        givenTheResultsCome(FILM_DVD, FILM_HD, SONG);
        givenTheRowsShown();

        whenTheRowIsDoubleClicked(FILM_HD);

        thenTheRowsInBoldAre(FILM_HD);
    }

    @Test
    void shouldKeepTheResultChosenWhenMoreSourcesOfItCome() throws Exception {
        givenTheSearchScreen();
        givenASearchFor("film", SearchScreen.Method.SERVER);
        givenTheResultsCome(FILM_DVD, FILM_HD, SONG);
        givenTheResultChosen(FILM_HD);

        whenTheResultsCome(withSources(FILM_HD, 40), withSources(SONG, 2));

        thenTheResultChosenIs(FILM_HD);
    }

    @Test
    void shouldKeepTheResultChosenWhenItIsDownloaded() throws Exception {
        givenTheSearchScreen();
        givenASearchFor("film", SearchScreen.Method.SERVER);
        givenTheResultsCome(FILM_DVD, FILM_HD, SONG);
        givenTheResultChosen(FILM_HD);
        givenTheMenuShown();

        whenTheMenuItemIsFired("IDS_DOWNLOAD");

        thenTheResultsDownloadingAre(1);
        thenTheResultChosenIs(FILM_HD);
    }

    @Test
    void shouldAskForMoreResultsAndStopASearch() throws Exception {
        givenTheSearchScreen();
        givenASearchFor("film", SearchScreen.Method.SERVER);
        givenTheResultsCome(FILM_DVD, FILM_HD, SONG);
        givenMoreResultsAskedFor();

        whenTheSearchIsStopped();

        thenTheEngineIsAskedFor("more", "stop");
    }

    @Test
    void shouldOfferToStopOnlyWhileTheSearchShownRuns() throws Exception {
        givenTheSearchScreen();
        givenSearchedFor("film", FILM_DVD);
        givenTheButtonsAreOff(false, "IDS_DL_STOP");

        whenTheSearchEnds();

        thenTheButtonsAreOff(true, "IDS_DL_STOP");
        thenTheButtonsAreOff(false, "nj.search.more");
    }

    @Test
    void shouldShowWhatWasDoneWithAFileBeforeItsSpamMark() throws Exception {
        givenTheSearchScreen();
        givenASearchFor("film", SearchScreen.Method.GLOBAL);
        givenTheEngineWasAskedFor("search film GLOBAL");

        whenTheResultsCome(SHARED, DOWNLOADED, CANCELLED, FILM_HD);

        thenTheKnownFilesAreMarked();
        thenTheCellsShow("Shared", "Downloaded", "Cancelled");
    }

    @Test
    void shouldSearchKadWithoutTheAttributesInTheTabTitle() throws Exception {
        givenTheEngineCanSearchRelatedFiles();
        givenTheSearchScreen();

        whenSearchedFor("film @size>=1m", SearchScreen.Method.KAD);

        thenTheEngineWasAskedFor("search film @size>=1m KAD");
        thenTheTabReads("film (0)", "no attributes in the title");
    }

    @Test
    void shouldOpenARelatedSearchInATabOfItsOwn() throws Exception {
        givenTheEngineCanSearchRelatedFiles();
        givenTheSearchScreen();
        givenASearchFor("film @size>=1m", SearchScreen.Method.KAD);
        givenTheResultsCome(FILM_DVD, FILM_HD);
        givenTheMenuShown();
        givenTheMenuItemIsOff("IDS_SEARCHRELATED", "nothing selected");
        givenARelatedSearchAndASpamMarkAskedForWithoutAChoice();
        givenTheResultChosen(FILM_HD);
        givenTheMenuShown();
        givenTheMenuItemsAreOff(false, "IDS_SEARCHRELATED");

        whenTheMenuItemIsFired("IDS_SEARCHRELATED");

        thenARelatedTabOpens();
        thenTheEngineWasAlsoAskedFor("search related::00112233445566778899AABBCCDDEEFF SERVER");
    }

    @Test
    void shouldStopTheSearchOfAClosedTab() throws Exception {
        givenTheEngineCanSearchRelatedFiles();
        givenTheSearchScreen();
        givenASearchFor("film @size>=1m", SearchScreen.Method.KAD);
        givenTheResultsCome(FILM_DVD, FILM_HD);
        givenARelatedSearchOpenedFor(FILM_HD);

        whenTheRelatedTabIsClosed();

        thenTheEngineIsAskedFor("stop");
        thenOnlyTheFirstTabIsLeft();
    }

    @Test
    void shouldKeepTheTabsLeftOpenWithTheirResultsAtTheExit() throws Exception {
        givenTheEngineCanSearchRelatedFiles();
        givenTheSearchScreen();
        givenASearchFor("film @size>=1m", SearchScreen.Method.KAD);
        givenTheResultsCome(FILM_DVD, FILM_HD);
        givenARelatedSearchOpenedFor(FILM_HD);
        givenTheRelatedTabClosed();

        whenTheScreenCloses();

        thenNoExceptionOccurred();
        thenTheSearchKeptIs("film @size>=1m", "film hd.mkv");
    }

    @Test
    void shouldRefuseAnAutomaticSearchWithoutANetwork() throws Exception {
        givenTheSearchScreen();

        whenTheSearchIsStartedFor("film");

        thenTheUserIsToldOnly("You are not connected to a server or the Kad network!");
    }

    @Test
    void shouldSearchTheNetworkThereIsAutomatically() throws Exception {
        givenTheSearchScreen();
        givenKadAlone();
        givenAnAutomaticSearchFor("kad only");
        givenAServerAlone();
        givenAnAutomaticSearchFor("server only");
        givenAServerAndKad(BIG);
        givenAnAutomaticSearchFor("both");
        givenAServerAndKad(BIG_STATIC);

        whenSearchedFor("static", SearchScreen.Method.AUTOMATIC);

        thenTheEngineWasAskedFor(
                "search kad only KAD", "search server only SERVER", "search both KAD", "search static SERVER");
        thenTheQueryReads("static");
        thenTheSearchHistoryStartsWith("static", "both", "server only");
    }

    @Test
    void shouldTellAWrongParameterOrExpressionAndNotSearch() throws Exception {
        givenTheSearchScreen();
        givenTheMethod(SearchScreen.Method.SERVER);
        givenTheExtension("tar.gz");
        givenTheSearchStartedFor("film");
        givenTheUserWasToldTimes(1);
        givenTheExtensionCleared();

        whenTheSearchIsStartedFor("(film");

        thenTheUserWasToldTimes(2);
        thenTheEngineWasAskedNothing();
    }

    @Test
    void shouldClearTheParametersOnReset() throws Exception {
        givenTheSearchScreen();
        givenTheType(SearchScreen.FileType.AUDIO);
        givenTheMinimumSize("5");
        givenTheExtension("mp3");

        whenTheParametersAreReset();

        thenTheTypeIs(SearchScreen.FileType.ANY);
        thenTheMinimumSizeAndTheExtensionAreEmpty();
    }

    @Test
    void shouldRefuseAnExpressionTooLongWithItsType() throws Exception {
        givenTheSearchScreen();
        givenARelatedSearchAnHtmlCopyAndASpamMarkAskedForWithoutATab();
        givenTheMethod(SearchScreen.Method.SERVER);
        givenTheType(SearchScreen.FileType.VIDEO);

        whenTheSearchIsStartedFor("x".repeat(SearchRequest.MAX_EXPRESSION_LENGTH));

        thenTheUserWasToldOnceThatTheExpressionIsTooLongWithItsType();
        thenTheEngineWasAskedNothing();
    }

    @Test
    void shouldLeaveTheMaximumItsOwnLowestValueWhenTheMinimumIsOutOfRange() throws Exception {
        givenTheSearchScreen();

        whenTheMinimumSizeIsTyped("999999");

        thenTheMaximumSizeTakes(1, 256 * 1024);
    }

    @Test
    void shouldCopyNothingWithoutAChosenResult() throws Exception {
        givenTheSearchScreen();
        givenTheExtension(".mkv");
        givenASearchFor("film", SearchScreen.Method.SERVER);
        givenTheEngineWasAskedFor("search film @ext=mkv SERVER");
        givenTheResultsCome(FILM_DVD);
        givenTheClipboardHolds("unchanged");
        givenNoResultChosen();
        givenASpamMarkAskedFor();

        whenTheLinksAreCopied();

        thenTheClipboardStillHolds("unchanged");
    }

    @Test
    void shouldGoOnWithTheExitWhenTheOpenSearchesCannotBeKept() throws Exception {
        givenKeepingTheSearchesFails();
        givenTheSearchScreen();
        givenASearchFor("film", SearchScreen.Method.SERVER);
        givenTheResultsCome(FILM_DVD);

        whenTheScreenCloses();

        thenNoExceptionOccurred();
    }

    @Test
    void shouldLetDownloadWaitForAChosenResultAndMoreAndStopForASearch() throws Exception {
        givenTheSearchScreen();
        givenTheButtonsAreOff(true, "nj.search.more", "IDS_DL_STOP", "IDS_DOWNLOAD");
        givenSearchedFor("film", FILM_DVD, FILM_HD);
        givenTheButtonsAreOff(false, "nj.search.more", "IDS_DL_STOP");
        givenTheButtonsAreOff(true, "IDS_DOWNLOAD");
        givenTheResultChosen(FILM_HD);
        givenTheButtonsAreOff(false, "IDS_DOWNLOAD");

        whenTheSearchIsClosed();

        thenTheButtonsAreOff(true, "nj.search.more", "IDS_DL_STOP", "IDS_DOWNLOAD");
    }

    @Test
    void shouldNotKeepTheSearchesWhenTheSettingsSaySo() throws Exception {
        givenTheSearchScreen();
        givenTheSettingsKeepNoSearches();
        givenASearchFor("film", SearchScreen.Method.SERVER);

        whenTheScreenCloses();

        thenNoExceptionOccurred();
        thenNoSearchIsKept();
    }

    private void givenTheSearchScreen() throws Exception {
        this.shownScreen = screen();
    }

    private void givenTheEngineCanSearchRelatedFiles() {
        this.status.set(Statuses.of(Optional.empty(), 0, false, Optional.empty(), true));
    }

    private void givenKeepingTheSearchesFails() {
        this.extra.put("saveSearches", args -> CompletableFuture.failedFuture(new IOException("disk full")));
    }

    private void givenTheSettingsKeepNoSearches() throws Exception {
        this.test
                .engine
                .updatePreferences(p -> p.toBuilder().storeSearches(false).build())
                .get(10, TimeUnit.SECONDS);
        FxTesting.onFx(
                () -> this.test.model.handle(new EngineEvent.PreferencesChanged(this.test.engine.preferences())));
    }

    private void givenASearchFor(String words, SearchScreen.Method method) throws Exception {
        whenSearchedFor(words, method);
    }

    private void givenSearchedFor(String words, SearchResult... results) throws Exception {
        givenASearchFor(words, SearchScreen.Method.SERVER);
        givenTheResultsCome(results);
    }

    private void givenTheEngineWasAskedFor(String... expected) {
        thenTheEngineWasAskedFor(expected);
    }

    private void givenTheTabReads(String text) throws Exception {
        thenTheTabReads(text);
    }

    private void givenTheResultsCome(SearchResult... results) throws Exception {
        whenTheResultsCome(results);
    }

    private void givenTheRowsShown() throws Exception {
        FxTesting.await(
                "the rows shown",
                () -> table().lookupAll(".table-row-cell").stream()
                        .anyMatch(node -> ((TableRow<?>) node).getItem() != null));
    }

    private void givenTheFilterTyped(String text) throws Exception {
        FxTesting.onFx(() -> filter().setText(text));
    }

    private void givenTheTableShows(int rows, String count) throws Exception {
        assertThat(FxTesting.onFx(() -> table().getItems().size())).isEqualTo(rows);
        thenTheCountReads(count);
    }

    private void givenAKeyPressedInTheFilter(KeyCode key) throws Exception {
        whenAKeyIsPressedInTheFilter(key);
    }

    private void givenTheFilterIsKeptByAnotherKey() throws Exception {
        assertThat(FxTesting.onFx(() -> filter().getText())).as("another key").isNotEmpty();
    }

    private void givenTheResultChosen(SearchResult result) throws Exception {
        FxTesting.onFx(() -> select(table(), result));
    }

    private void givenNoResultChosen() throws Exception {
        FxTesting.onFx(() -> table().getSelectionModel().clearSelection());
    }

    // The menu offers what the selection is not yet.
    private void givenTheMenuShown() throws Exception {
        FxTesting.onFx(() -> menu().getOnShowing().handle(null));
    }

    private void givenTheMenuItemIsOff(String key, String why) throws Exception {
        assertThat(FxTesting.onFx(() -> item(menu(), key).isDisable())).as(why).isTrue();
    }

    private void givenTheMenuItemsAreOff(boolean off, String... keys) throws Exception {
        for (String key : keys) {
            assertThat(FxTesting.onFx(() -> item(menu(), key).isDisable()))
                    .as(key)
                    .isEqualTo(off);
        }
    }

    private void givenTheMenuItemFired(String key) throws Exception {
        whenTheMenuItemIsFired(key);
    }

    private void givenOneResultMarkedAsSpam() throws Exception {
        thenOneResultIsMarkedAsSpam();
    }

    private void givenTheResultsDownloading(int count) throws Exception {
        thenTheResultsDownloadingAre(count);
    }

    private void givenMoreResultsAskedFor() throws Exception {
        FxTesting.onFx(this.shownScreen::more);
    }

    // Without a choice these do nothing.
    private void givenARelatedSearchAndASpamMarkAskedForWithoutAChoice() throws Exception {
        FxTesting.onFx(() -> {
            this.shownScreen.searchRelated();
            this.shownScreen.markSpam(true);
        });
    }

    // Without a tab these do nothing.
    private void givenARelatedSearchAnHtmlCopyAndASpamMarkAskedForWithoutATab() throws Exception {
        FxTesting.onFx(() -> {
            this.shownScreen.searchRelated();
            this.shownScreen.copyHtml();
            this.shownScreen.markSpam(true);
        });
    }

    private void givenASpamMarkAskedFor() throws Exception {
        FxTesting.onFx(() -> this.shownScreen.markSpam(true));
    }

    private void givenARelatedSearchOpenedFor(SearchResult result) throws Exception {
        givenTheResultChosen(result);
        givenTheMenuShown();
        givenTheMenuItemFired("IDS_SEARCHRELATED");
        thenARelatedTabOpens();
    }

    private void givenTheRelatedTabClosed() throws Exception {
        whenTheRelatedTabIsClosed();
        thenTheEngineIsAskedFor("stop");
    }

    private void givenKadAlone() throws Exception {
        status(Optional.empty(), Optional.of(Statuses.kad(true, false, "")));
    }

    private void givenAServerAlone() throws Exception {
        FxTesting.onFx(() -> this.test.model.handle(new EngineEvent.ServerConnected(BIG, 99, false, false)));
        status(Optional.of(BIG), Optional.empty());
    }

    // With both, Kad, unless the server is a static one.
    private void givenAServerAndKad(ServerInfo server) throws Exception {
        status(Optional.of(server), Optional.of(Statuses.kad(true, false, "")));
    }

    private void givenAnAutomaticSearchFor(String words) throws Exception {
        whenSearchedFor(words, SearchScreen.Method.AUTOMATIC);
    }

    private void givenTheMethod(SearchScreen.Method method) throws Exception {
        FxTesting.onFx(() -> this.shownScreen.methodBox().setValue(method));
    }

    private void givenTheType(SearchScreen.FileType type) throws Exception {
        FxTesting.onFx(() -> this.shownScreen.typeBox().setValue(type));
    }

    private void givenTheMinimumSize(String text) throws Exception {
        whenTheMinimumSizeIsTyped(text);
    }

    private void givenTheExtension(String text) throws Exception {
        FxTesting.onFx(() -> this.shownScreen.extensionField().setText(text));
    }

    private void givenTheExtensionCleared() throws Exception {
        FxTesting.onFx(() -> this.shownScreen.extensionField().clear());
    }

    private void givenTheSearchStartedFor(String words) throws Exception {
        whenTheSearchIsStartedFor(words);
    }

    private void givenTheUserWasToldTimes(int count) {
        thenTheUserWasToldTimes(count);
    }

    private void givenTheClipboardHolds(String text) throws Exception {
        FxTesting.onFx(() -> {
            ClipboardContent content = new ClipboardContent();
            content.putString(text);
            Clipboard.getSystemClipboard().setContent(content);
        });
    }

    private void givenTheButtonsAreOff(boolean off, String... keys) throws Exception {
        thenTheButtonsAreOff(off, keys);
    }

    private void whenSearchedFor(String words, SearchScreen.Method method) throws Exception {
        this.shownTab = search(this.shownScreen, words, method);
    }

    private void whenTheResultsCome(SearchResult... results) throws Exception {
        FxTesting.onFx(() ->
                this.test.model.handle(new EngineEvent.SearchResults(new SearchId(this.ids.get()), List.of(results))));
    }

    private void whenAKeyIsPressedInTheFilter(KeyCode key) throws Exception {
        FxTesting.onFx(() -> filter().fireEvent(FxTesting.key(key, false)));
    }

    private void whenTheMenuItemIsFired(String key) throws Exception {
        FxTesting.onFx(() -> item(menu(), key).fire());
    }

    // A double click downloads the file of its row.
    private void whenTheRowIsDoubleClicked(SearchResult result) throws Exception {
        FxTesting.onFx(() ->
                FxTesting.doubleClick(table(), r -> ((LiveList.Row<?>) r).get().equals(result)));
    }

    private void whenTheSearchEnds() throws Exception {
        FxTesting.onFx(() -> this.test.model.handle(new EngineEvent.SearchEnded(new SearchId(this.ids.get()), 1)));
    }

    private void whenTheSearchIsStopped() throws Exception {
        FxTesting.onFx(this.shownScreen::stop);
    }

    private void whenTheRelatedTabIsClosed() throws Exception {
        FxTesting.onFx(() -> {
            Tab related = this.shownScreen.tabs().getTabs().get(1);
            related.getOnClosed().handle(new Event(Tab.CLOSED_EVENT));
            this.shownScreen.tabs().getTabs().remove(related);
        });
    }

    private void whenTheScreenCloses() {
        this.exception = catchThrowable(() -> FxTesting.onFx(this.shownScreen::closing));
    }

    private void whenTheSearchIsStartedFor(String words) throws Exception {
        FxTesting.onFx(() -> {
            this.shownScreen.queryField().setText(words);
            this.shownScreen.start();
        });
    }

    private void whenTheParametersAreReset() throws Exception {
        FxTesting.onFx(this.shownScreen::resetParameters);
    }

    private void whenTheMinimumSizeIsTyped(String text) throws Exception {
        FxTesting.onFx(() -> this.shownScreen.minSizeField().setText(text));
    }

    private void whenTheLinksAreCopied() throws Exception {
        FxTesting.onFx(this.shownScreen::copyLinks);
    }

    private void whenTheSearchIsClosed() throws Exception {
        FxTesting.onFx(() -> this.shownScreen.tabs().getTabs().remove(this.shownTab));
    }

    private void thenTheButtonsAreOff(boolean off, String... keys) throws Exception {
        List<String> names = Arrays.stream(keys).map(this.test.gui::t).toList();
        assertThat(FxTesting.onFx(() -> this.shownScreen.view().lookupAll(".button").stream()
                        .filter(n -> n instanceof Button b && b.getText() != null && names.contains(b.getText()))
                        .map(Node::isDisabled)
                        .toList()))
                .hasSize(names.size())
                .containsOnly(off);
    }

    private void thenTheEngineWasAskedFor(String... expected) {
        assertThat(this.calls).containsExactly(expected);
    }

    private void thenTheEngineWasAlsoAskedFor(String... expected) {
        assertThat(this.calls).contains(expected);
    }

    private void thenTheEngineIsAskedFor(String... expected) throws Exception {
        List<String> all = List.of(expected);
        FxTesting.assertEventually(String.join(" and ", all), () -> this.calls.containsAll(all));
    }

    private void thenTheEngineWasAskedNothing() {
        assertThat(this.calls).isEmpty();
    }

    private void thenTheTabReads(String text) throws Exception {
        assertThat(FxTesting.onFx(this.shownTab::getText)).isEqualTo(text);
    }

    private void thenTheTabReads(String text, String why) throws Exception {
        assertThat(FxTesting.onFx(this.shownTab::getText)).as(why).isEqualTo(text);
    }

    private void thenTheFilterIsEmpty() throws Exception {
        assertThat(FxTesting.onFx(() -> filter().getText())).isEmpty();
    }

    private void thenTheCountReads(String text) throws Exception {
        assertThat(FxTesting.onFx(() -> count().getText())).isEqualTo(text);
    }

    private void thenOneResultIsMarkedAsSpam() throws Exception {
        FxTesting.assertEventually(
                "marked as spam", () -> table().lookupAll(".known-spam").size() == 1);
        assertThat(FxTesting.onFx(() -> FxTesting.cellTexts(table()))).contains("Spam");
    }

    private void thenTheResultsMarkedAsSpamAre(int count) throws Exception {
        FxTesting.assertEventually(
                count + " marked as spam",
                () -> table().lookupAll(".known-spam").size() == count);
    }

    private void thenNoResultIsMarkedAsSpam() throws Exception {
        FxTesting.assertEventually(
                "not spam any more", () -> table().lookupAll(".known-spam").isEmpty());
    }

    private void thenTheClipboardStartsWith(String text) throws Exception {
        assertThat(FxTesting.onFx(() -> Clipboard.getSystemClipboard().getString()))
                .startsWith(text);
    }

    private void thenTheClipboardStillHolds(String text) throws Exception {
        assertThat(FxTesting.onFx(() -> Clipboard.getSystemClipboard().getString()))
                .as("nothing selected")
                .isEqualTo(text);
    }

    private void thenTheResultsDownloadingAre(int count) throws Exception {
        FxTesting.assertEventually(
                count + " downloading",
                () -> table().lookupAll(".known-downloading").size() == count);
    }

    private void thenTheRowsInBoldAre(SearchResult... results) throws Exception {
        List<Hash16> expected = Arrays.stream(results).map(SearchResult::hash).toList();
        FxTesting.assertEventually(
                "rows in bold " + expected,
                () -> table().lookupAll(".table-row-cell").stream()
                        .map(node -> (TableRow<?>) node)
                        .filter(row -> row.getPseudoClassStates().contains(SearchScreen.DOWNLOADING_ROW))
                        .map(row -> ((LiveList.Row<?>) row.getItem()).get())
                        .map(value -> ((SearchResult) value).hash())
                        .toList()
                        .equals(expected));
    }

    private void thenTheResultChosenIs(SearchResult result) throws Exception {
        assertThat(FxTesting.onFx(() -> List.copyOf(table().getSelectionModel().getSelectedItems())))
                .extracting(r -> r.get().hash())
                .containsExactly(result.hash());
    }

    private void thenTheEngineDownloads(String... names) throws Exception {
        assertThat(this.test.engine.downloads().get(10, TimeUnit.SECONDS))
                .extracting(DownloadInfo::name)
                .containsExactly(names);
    }

    private void thenTheKnownFilesAreMarked() throws Exception {
        FxTesting.assertEventually(
                "the known files",
                () -> table().lookupAll(".known-shared").size() == 1
                        && table().lookupAll(".known-downloaded").size() == 1
                        && table().lookupAll(".known-cancelled").size() == 1);
    }

    private void thenTheCellsShow(String... texts) throws Exception {
        assertThat(FxTesting.onFx(() -> FxTesting.cellTexts(table()))).contains(texts);
    }

    private void thenARelatedTabOpens() throws Exception {
        FxTesting.assertEventually(
                "the related tab", () -> this.shownScreen.tabs().getTabs().size() == 2);
    }

    private void thenOnlyTheFirstTabIsLeft() throws Exception {
        assertThat(FxTesting.onFx(() -> this.shownScreen.tabs().getTabs())).containsExactly(this.shownTab);
    }

    private void thenNoExceptionOccurred() {
        assertThat(this.exception).isNull();
    }

    private void thenTheSearchKeptIs(String expression, String resultName) throws Exception {
        assertThat(this.test.engine.savedSearches().get(10, TimeUnit.SECONDS))
                .singleElement()
                .satisfies(s -> {
                    assertThat(s.request().expression()).isEqualTo(expression);
                    assertThat(s.results()).extracting(SearchResult::name).contains(resultName);
                });
    }

    private void thenNoSearchIsKept() throws Exception {
        assertThat(this.test.engine.savedSearches().get(10, TimeUnit.SECONDS)).isEmpty();
    }

    private void thenTheUserIsToldOnly(String message) {
        assertThat(this.test.told).containsExactly(message);
    }

    private void thenTheUserWasToldTimes(int count) {
        assertThat(this.test.told).hasSize(count);
    }

    private void thenTheUserWasToldOnceThatTheExpressionIsTooLongWithItsType() {
        assertThat(this.test.told).as("too long with its type").hasSize(1);
    }

    private void thenTheQueryReads(String text) throws Exception {
        assertThat(FxTesting.onFx(() -> this.shownScreen.queryField().getText()))
                .isEqualTo(text);
    }

    private void thenTheSearchHistoryStartsWith(String... words) {
        assertThat(this.test.gui.settings().history("search")).startsWith(words);
    }

    private void thenTheTypeIs(SearchScreen.FileType type) throws Exception {
        assertThat(FxTesting.onFx(() -> this.shownScreen.typeBox().getValue())).isEqualTo(type);
    }

    private void thenTheMinimumSizeAndTheExtensionAreEmpty() throws Exception {
        assertThat(FxTesting.onFx(() -> this.shownScreen.minSizeField().getText()
                        + this.shownScreen.extensionField().getText()))
                .isEmpty();
    }

    // A minimum out of range leaves the maximum its own lowest value.
    private void thenTheMaximumSizeTakes(int lowest, int highest) throws Exception {
        assertThat(FxTesting.onFx(
                        () -> this.shownScreen.maxSizeField().getTooltip().getText()))
                .isEqualTo(this.test.gui.f("nj.input.optionalNumber", lowest, highest));
    }

    private TableView<LiveList.Row<SearchResult>> table() {
        return this.shownScreen.table(this.shownTab);
    }

    private ContextMenu menu() {
        return table().getContextMenu();
    }

    private TextField filter() {
        return this.shownScreen.filterField();
    }

    private Label count() {
        return (Label) this.shownTab.getContent().lookup(".label");
    }

    private SearchScreen screen() throws Exception {
        Map<String, Engines.Command> commands = new HashMap<>();
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
        commands.putAll(this.extra);
        this.test = TestGui.start(this.dir.resolve("njmule"), real -> wrap(real, commands));
        SearchScreen screen = new SearchScreen(this.test.gui);
        this.test.show(FxTesting.onFx(screen::view));
        if (this.status.get() != null) {
            FxTesting.onFx(this.test.model::tick);
            FxTesting.await(
                    "the state",
                    () -> Objects.equals(this.test.model.statusProperty().get(), this.status.get()));
        }
        return screen;
    }

    private NjMuleEngine wrap(NjMuleEngine real, Map<String, Engines.Command> commands) {
        Map<String, Engines.Command> all = new HashMap<>(commands);
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
        FxTesting.await(
                "the state",
                () -> Objects.equals(this.test.model.statusProperty().get(), next));
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

    private MenuItem item(ContextMenu menu, String key) {
        String text = this.test.gui.t(key);
        return menu.getItems().stream()
                .filter(i -> text.equals(i.getText()))
                .findFirst()
                .orElseThrow();
    }

    private static SearchResult withSources(SearchResult result, int sources) {
        return new SearchResult(result.hash(), result.name(), result.size(), sources, 1, Optional.of("Video"));
    }

    private static SearchResult result(String hash, String name) {
        return new SearchResult(Hash16.parseHex(hash), name, 700_000_000, 4, 1, Optional.of("Video"));
    }
}
