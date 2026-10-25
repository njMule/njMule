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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TableColumnBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.core.api.DownloadInfo;
import org.njmule.core.api.DownloadPriority;
import org.njmule.core.api.EngineEvent;
import org.njmule.core.api.Hash16;
import org.njmule.core.api.PartMap;
import org.njmule.core.config.Preferences;
import org.njmule.gui.FxTesting;
import org.njmule.gui.TestGui;
import org.njmule.gui.i18n.I18n;
import org.njmule.gui.infra.Formats;
import org.njmule.gui.ui.Gui;

@Timeout(120)
class TransfersScreenTest {

    private static final Hash16 FILE = Hash16.parseHex("0123456789ABCDEF0123456789ABCDEF");

    @TempDir
    Path dir;

    private TestGui test;
    private TransfersScreen screen;
    private ContextMenu menu;
    private List<String> columns = List.of();

    @AfterEach
    void close() {
        if (this.test != null) {
            this.test.close();
        }
    }

    @Test
    void shouldOfferNeitherPreviewNorSourceForACompletedDownload() throws Exception {
        givenTheEngine();
        givenTheScreen();
        givenACompletedDownload();

        whenTheMenuOfTheFirstDownloadShows();

        thenTheItemIsOff("IDS_DL_PREVIEW", "preview");
        thenTheItemIsOff("IDS_ADDSRCMANUALLY", "add a source");
        thenTheItemIsOn("IDS_CMT_ADD", "comments");
    }

    @Test
    void shouldOfferToPauseAndStopButNotToResumeADownloadThatRuns() throws Exception {
        givenTheEngine();
        givenTheScreen();
        givenADownloadThatIs(DownloadInfo.Status.DOWNLOADING);

        whenTheMenuOfTheFirstDownloadShows();

        thenTheItemIsOn("IDS_DL_PAUSE", "pause");
        thenTheItemIsOn("IDS_DL_STOP", "stop");
        thenTheItemIsOff("IDS_DL_RESUME", "resume");
    }

    @Test
    void shouldOfferToResumeButNotToPauseOrStopAStoppedDownload() throws Exception {
        givenTheEngine();
        givenTheScreen();
        givenADownloadThatIs(DownloadInfo.Status.STOPPED);

        whenTheMenuOfTheFirstDownloadShows();

        thenTheItemIsOn("IDS_DL_RESUME", "resume");
        thenTheItemIsOff("IDS_DL_PAUSE", "pause");
        thenTheItemIsOff("IDS_DL_STOP", "stop");
    }

    @Test
    void shouldOfferToResumeAndStopButNotToPauseAPausedDownload() throws Exception {
        givenTheEngine();
        givenTheScreen();
        givenADownloadThatIs(DownloadInfo.Status.PAUSED);

        whenTheMenuOfTheFirstDownloadShows();

        thenTheItemIsOn("IDS_DL_RESUME", "resume");
        thenTheItemIsOn("IDS_DL_STOP", "stop");
        thenTheItemIsOff("IDS_DL_PAUSE", "pause");
    }

    @Test
    void shouldShowEveryItemOfTheMenuEvenWhenItIsOff() throws Exception {
        givenTheEngine();
        givenTheScreen();
        givenACompletedDownload();

        whenTheMenuOfTheFirstDownloadShows();

        thenEveryItemIsShown();
    }

    @Test
    void shouldOfferTheFolderOfACompletedDownload() throws Exception {
        givenTheEngine();
        givenTheScreen();
        givenACompletedDownload();

        whenTheMenuOfTheFirstDownloadShows();

        thenTheItemIsOn("IDS_OPENFOLDER", "open its folder");
    }

    @Test
    void shouldNotOfferTheFolderOfADownloadNotCompleteYet() throws Exception {
        givenTheEngine();
        givenTheScreen();
        givenAStartedDownload();

        whenTheMenuOfTheFirstDownloadShows();

        thenTheItemIsOff("IDS_OPENFOLDER", "open its folder");
    }

    @Test
    void shouldOfferToClearTheCompletedDownloadsWhenThereAreSome() throws Exception {
        givenTheEngine();
        givenTheScreen();
        givenACompletedDownload();

        whenTheMenuOfTheFirstDownloadShows();

        thenTheItemIsOn("IDS_DL_CLEAR", "clear the completed");
    }

    @Test
    void shouldNotOfferToClearTheCompletedDownloadsWithoutAny() throws Exception {
        givenTheEngine();
        givenTheScreen();
        givenAStartedDownload();

        whenTheMenuOfTheFirstDownloadShows();

        thenTheItemIsOff("IDS_DL_CLEAR", "clear the completed");
        thenTheItemIsOn("nj.transfers.cancelOne", "cancel");
    }

    @Test
    void shouldShowTheDetailsOfADownloadFromItsMenu() throws Exception {
        givenTheEngine();
        givenTheScreen();
        givenAStartedDownload();
        givenTheDetailsHidden();
        givenTheMenuOfTheFirstDownloadShown();

        whenTheItemIsChosen("IDS_DETAILS");

        thenTheDetailsShow();
    }

    @Test
    void shouldNameTheColumnsOfTheKnownClientsWithoutTheColonOfTheirTexts() throws Exception {
        givenTheEngine();
        givenTheScreen();

        whenTheColumnsOfTheKnownClientsAreRead();

        thenTheColumnsInclude("Uploaded total", "Last Seen");
    }

    @Test
    void shouldSaySoForAPreviewBeforeTheStartOfTheFileCame() throws Exception {
        givenTheEngine();
        givenTheVideoPlayer("player");
        givenTheScreen();
        givenAStartedDownload();

        whenThePreviewOfTheFirstDownloadIsAsked();

        thenTheUserIsTold("nj.preview.notYet");
    }

    @Test
    void shouldShowTheProgressInTheLanguageOfTheInterface() throws Exception {
        givenTheEngine();
        givenTheScreenIn(Locale.ITALIAN);

        whenADownloadCompletes();

        thenALabelReads("100,0 %");
    }

    private void givenTheEngine() throws Exception {
        this.test = TestGui.start(this.dir.resolve("njmule"));
    }

    private void givenTheScreen() throws Exception {
        show(new TransfersScreen(this.test.gui));
    }

    private void givenTheScreenIn(Locale language) throws Exception {
        Gui gui = FxTesting.onFx(() -> new Gui(
                I18n.of(language),
                new Formats(language),
                this.test.model,
                this.test.gui.themes(),
                this.test.gui.settings(),
                this.test.gui.directories(),
                this.test.told::add,
                () -> {}));
        show(new TransfersScreen(gui));
    }

    private void givenTheVideoPlayer(String player) throws Exception {
        Preferences prefs = this.test
                .engine
                .updatePreferences(p -> p.toBuilder().videoPlayer(player).build())
                .get(10, TimeUnit.SECONDS);
        FxTesting.onFx(() -> this.test.model.handle(new EngineEvent.PreferencesChanged(prefs)));
    }

    private void givenACompletedDownload() throws Exception {
        whenADownloadCompletes();
        FxTesting.await(
                "the download row", () -> this.screen.downloads().getItems().size() == 1);
    }

    private void givenAStartedDownload() throws Exception {
        givenADownloadThatIs(DownloadInfo.Status.DOWNLOADING);
    }

    private void givenADownloadThatIs(DownloadInfo.Status status) throws Exception {
        DownloadInfo started = new DownloadInfo(
                FILE, "film.mkv", 100_000, 5000, status, DownloadPriority.AUTO, 0, 1, 1, 0, PartMap.EMPTY);
        FxTesting.onFx(() -> this.test.model.handle(new EngineEvent.DownloadAdded(started)));
        FxTesting.await(
                "the download row", () -> this.screen.downloads().getItems().size() == 1);
    }

    private void whenTheColumnsOfTheKnownClientsAreRead() throws Exception {
        this.columns = FxTesting.onFx(() -> this.screen.knownClients().getColumns().stream()
                .map(TableColumnBase::getText)
                .toList());
    }

    private void thenTheColumnsInclude(String... titles) {
        assertThat(this.columns).contains(titles);
    }

    private void givenTheDetailsHidden() throws Exception {
        FxTesting.onFx(() -> this.screen.showDetails(false));
    }

    private void givenTheMenuOfTheFirstDownloadShown() throws Exception {
        whenTheMenuOfTheFirstDownloadShows();
    }

    private void whenADownloadCompletes() throws Exception {
        DownloadInfo done = new DownloadInfo(
                FILE,
                "film.mkv",
                1000,
                1000,
                DownloadInfo.Status.COMPLETE,
                DownloadPriority.AUTO,
                0,
                0,
                0,
                0,
                PartMap.EMPTY);
        FxTesting.onFx(() -> this.test.model.handle(new EngineEvent.DownloadCompleted(done, this.dir.resolve("f"))));
    }

    private void whenTheMenuOfTheFirstDownloadShows() throws Exception {
        this.menu = FxTesting.onFx(() -> {
            this.screen.downloads().getSelectionModel().select(0);
            ContextMenu shown = this.screen.downloads().getContextMenu();
            shown.getOnShowing().handle(null);
            return shown;
        });
    }

    private void whenThePreviewOfTheFirstDownloadIsAsked() throws Exception {
        FxTesting.onFx(() -> {
            this.screen.downloads().getSelectionModel().select(0);
            this.screen.previewSelected();
        });
    }

    private void whenTheItemIsChosen(String key) throws Exception {
        FxTesting.onFx(() -> item(this.menu, key).fire());
    }

    private void thenTheDetailsShow() throws Exception {
        assertThat(FxTesting.onFx(this.screen::detailsShown)).isTrue();
    }

    private void thenEveryItemIsShown() throws Exception {
        assertThat(FxTesting.onFx(() -> items(this.menu.getItems()).stream()
                        .filter(i -> !i.isVisible())
                        .map(MenuItem::getText)
                        .toList()))
                .isEmpty();
    }

    private void thenTheItemIsOff(String key, String what) throws Exception {
        assertThat(FxTesting.onFx(() -> item(this.menu, key).isDisable()))
                .as(what)
                .isTrue();
    }

    private void thenTheItemIsOn(String key, String what) throws Exception {
        assertThat(FxTesting.onFx(() -> item(this.menu, key).isDisable()))
                .as(what)
                .isFalse();
    }

    private void thenTheUserIsTold(String key) throws Exception {
        String text = this.test.gui.t(key);
        assertThat(text).isNotEqualTo(key);
        FxTesting.await("told so", () -> this.test.told.contains(text));
    }

    private void thenALabelReads(String text) throws Exception {
        FxTesting.assertEventually(
                "the percentage",
                () -> this.screen.downloads().lookupAll(".label").stream()
                        .anyMatch(n -> n instanceof Label l && text.equals(l.getText())));
    }

    private void show(TransfersScreen shown) throws Exception {
        this.screen = shown;
        this.test.show(FxTesting.onFx(shown::view));
    }

    // An item of the menu, or of its submenus.
    private MenuItem item(ContextMenu menu, String key) {
        String text = this.test.gui.t(key);
        return items(menu.getItems()).stream()
                .filter(i -> text.equals(i.getText()))
                .findFirst()
                .orElseThrow();
    }

    private static List<MenuItem> items(List<MenuItem> items) {
        List<MenuItem> all = new ArrayList<>();
        for (MenuItem item : items) {
            all.add(item);
            if (item instanceof Menu menu) {
                all.addAll(items(menu.getItems()));
            }
        }
        return all;
    }
}
