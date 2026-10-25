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
import static org.assertj.core.api.Assertions.within;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.core.api.DownloadInfo;
import org.njmule.core.api.DownloadPriority;
import org.njmule.core.api.Hash16;
import org.njmule.core.api.PartMap;
import org.njmule.core.api.SourceInfo;
import org.njmule.gui.Engines;
import org.njmule.gui.FxTesting;
import org.njmule.gui.TestGui;

@Timeout(60)
class DownloadDetailsTest {

    private static final long SIZE = 4 * PartMap.PART_SIZE;
    private static final DownloadInfo FILM = new DownloadInfo(
            Hash16.parseHex("0123456789ABCDEF0123456789ABCDEF"),
            "film.mkv",
            SIZE,
            2 * PartMap.PART_SIZE,
            DownloadInfo.Status.DOWNLOADING,
            DownloadPriority.HIGH,
            0,
            7,
            2,
            2048,
            new PartMap(
                    SIZE,
                    List.of(
                            new PartMap.Span(PartMap.PART_SIZE, PartMap.PART_SIZE + 10),
                            new PartMap.Span(3 * PartMap.PART_SIZE, SIZE)),
                    List.of(),
                    List.of(1, 2, 3, 0)),
            false,
            false);

    @TempDir
    Path dir;

    private TestGui test;
    private TransfersScreen screen;
    private int complete;
    private double leftAt;
    private final List<String> renamed = new CopyOnWriteArrayList<>();

    @AfterEach
    void close() {
        if (this.test != null) {
            this.test.close();
        }
    }

    @Test
    void shouldNotCountAPartWithAnyByteMissingAsComplete() {
        whenTheCompletePartsAreCounted(FILM.parts(), 4);

        thenTheyAre(2);
    }

    @Test
    void shouldOpenTheDetailsFromTheirButtonAndShowTheDownloadChosen() throws Exception {
        givenTheTransfersWith(FILM);
        givenTheDetailsAre(true);
        givenTheDetailsButtonPressed();
        givenTheDetailsAre(false);
        givenTheSettingsRemember(false);
        givenTheDetailsButtonPressed();
        givenTheDetailsTell("Choose one download");

        whenTheDownloadIsChosen();

        thenTheDetailsTell("film.mkv", "High", "50.0 %");
        thenTheSettingsRemember(true);
    }

    @Test
    void shouldTellTheFactsOfTheDownloadInTheirTab() throws Exception {
        givenTheTransfersWith(FILM);
        givenTheDownloadChosen();

        whenTheTabIsShown(DownloadDetails.Tab.FACTS);

        thenTheDetailsTell("2 / 4", "7", "High");
    }

    @Test
    void shouldRememberHowTallTheDetailsAreLeftWhenTheirDividerIsDragged() throws Exception {
        givenTheTransfersWith(FILM);
        givenTheDetailsAre(true);

        whenTheDividerIsDraggedTo(0.7);

        thenTheSettingsRememberTheDetailsAsTallAsTheRestOfTheSplit(0.3);
    }

    @Test
    void shouldBringTheDetailsBackAsTallAsTheyWereLeft() throws Exception {
        givenTheDetailsWereLeft(250);

        whenTheTransfersShow(FILM);

        thenTheDetailsAreAbout(250);
    }

    @Test
    void shouldGiveTheDetailsBackTheirHeightWhenTheWindowGrowsFromALowOne() throws Exception {
        givenTheDetailsWereLeft(250);
        givenTheTransfersWith(FILM);
        givenTheWindowHigh(330);

        whenTheWindowIsHigh(720);

        thenTheDetailsAreAbout(250);
        thenTheSettingsStillHaveTheDetails(250);
    }

    @Test
    void shouldShowTheButtonsOfTheChosenDownloadUntilTheDividerIsMoved() throws Exception {
        givenTheTransfersWith(FILM);
        givenTheDetailsAre(true);

        whenTheDownloadIsChosen();

        thenTheButtonsOfTheDownloadShowWithRoomUnder();
    }

    @Test
    void shouldOpenTheDetailsWithAltEnter() throws Exception {
        givenTheTransfersWith(FILM);
        givenTheDetailsButtonPressed();
        givenTheDetailsAre(false);

        whenAltEnterIsPressed();

        thenTheDetailsAre(true);
    }

    @Test
    void shouldListTheNamesTheSourcesGiveTheMostGivenFirst() throws Exception {
        givenTheTransfersWithTheSourcesNaming(FILM, "film.avi", "Film (2010).mkv", "Film (2010).mkv");

        whenTheDownloadIsChosen();

        thenTheNamesAre(
                new DownloadDetails.SourceName("Film (2010).mkv", 2), new DownloadDetails.SourceName("film.avi", 1));
    }

    @Test
    void shouldTakeOverTheNameChosenAsTheNewName() throws Exception {
        givenTheTransfersWithTheSourcesNaming(FILM, "Film (2010).mkv", "film.avi");
        givenTheDownloadChosen();
        givenTheNameChosen("film.avi");

        whenPressed("take-over");

        thenTheNewNameIs("film.avi");
    }

    @Test
    void shouldCleanUpTheNewName() throws Exception {
        givenTheTransfersWithTheSourcesNaming(FILM);
        givenTheDownloadChosen();
        givenTheNewNameTyped("Film_(2010).sponsored.mkv");

        whenPressed("clean-up");

        thenTheNewNameIs("Film (2010).mkv");
    }

    @Test
    void shouldAskTheEngineToRenameTheDownloadAsWritten() throws Exception {
        givenTheTransfersWithTheSourcesNaming(FILM);
        givenTheDownloadChosen();
        givenTheNewNameTyped("Film (2010).mkv");

        whenPressed("rename");

        thenTheEngineIsAskedToRename(FILM.hash(), "Film (2010).mkv");
    }

    private void givenTheTransfersWith(DownloadInfo download) throws Exception {
        givenTheTransfersWithTheSourcesNaming(download);
    }

    // One source of the download per name, and one of another file, whose name is not counted.
    private void givenTheTransfersWithTheSourcesNaming(DownloadInfo download, String... names) throws Exception {
        List<SourceInfo> sources = new ArrayList<>();
        for (int i = 0; i < names.length; i++) {
            sources.add(source(download.hash(), i, names[i]));
        }
        if (names.length > 0) {
            sources.add(source(Hash16.parseHex("FF".repeat(16)), names.length, names[0]));
        }
        Map<String, Engines.Command> commands = Map.of(
                "downloads",
                a -> CompletableFuture.completedFuture(List.of(download)),
                "sources",
                a -> CompletableFuture.completedFuture(sources),
                "renameDownload",
                a -> {
                    this.renamed.add(a[0] + " " + a[1]);
                    return CompletableFuture.completedFuture(true);
                });
        this.test = TestGui.start(this.dir.resolve("njmule"), real -> Engines.overriding(real, commands));
        if (this.leftAt > 0) {
            this.test.gui.settings().setTransfersDetailsHeight(this.leftAt);
        }
        this.screen = new TransfersScreen(this.test.gui);
        this.test.show(FxTesting.onFx(this.screen::view));
        FxTesting.onFx(this.test.model::tick);
        FxTesting.onFx(this.test.model::tick);
        FxTesting.await("the download", () -> this.screen.downloads().getItems().size() == 1);
        FxTesting.await("the sources", () -> this.test.model.sources().size() == sources.size());
    }

    private void givenTheDownloadChosen() throws Exception {
        whenTheDownloadIsChosen();
    }

    private void givenTheNameChosen(String name) throws Exception {
        FxTesting.onFx(() -> {
            TableView<DownloadDetails.SourceName> names = this.screen.details().names();
            names.getItems().stream()
                    .filter(n -> n.name().equals(name))
                    .findFirst()
                    .ifPresent(n -> names.getSelectionModel().select(n));
        });
    }

    private void givenTheNewNameTyped(String name) throws Exception {
        FxTesting.onFx(() -> newName().setText(name));
    }

    private void givenTheDetailsWereLeft(double height) {
        this.leftAt = height;
    }

    private void givenTheWindowHigh(double height) throws Exception {
        whenTheWindowIsHigh(height);
    }

    // As a window shows first at a size and then at another, maximised.
    private void whenTheWindowIsHigh(double height) throws Exception {
        FxTesting.onFx(() -> {
            Stage stage = (Stage) this.screen.split().getScene().getWindow();
            stage.setHeight(height);
            this.screen.split().getScene().getRoot().applyCss();
            this.screen.split().getScene().getRoot().layout();
        });
    }

    private void thenTheSettingsStillHaveTheDetails(double height) throws Exception {
        assertThat(FxTesting.onFx(() -> this.test.gui.settings().transfersDetailsHeight()))
                .hasValue(height);
    }

    private void whenTheTransfersShow(DownloadInfo download) throws Exception {
        givenTheTransfersWith(download);
    }

    // As the mouse leaves it: the split hears the release on its divider.
    private void whenTheDividerIsDraggedTo(double share) throws Exception {
        FxTesting.onFx(() -> {
            this.screen.split().setDividerPositions(share);
            this.screen
                    .split()
                    .lookup(".split-pane-divider")
                    .fireEvent(new MouseEvent(
                            MouseEvent.MOUSE_RELEASED,
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
                            false,
                            false,
                            false,
                            false,
                            false,
                            false,
                            null));
        });
    }

    private void thenTheSettingsRememberTheDetailsAsTallAsTheRestOfTheSplit(double share) throws Exception {
        double expected = FxTesting.onFx(() -> this.screen.split().getHeight() * share);
        assertThat(FxTesting.onFx(() -> this.test.gui.settings().transfersDetailsHeight()))
                .hasValueCloseTo(expected, within(1.0));
    }

    // The divider takes a few pixels of what is kept.
    private void thenTheDetailsAreAbout(double height) throws Exception {
        FxTesting.assertEventually(
                "the details " + height + " tall",
                () -> Math.abs(this.screen.details().view().getHeight() - height) < 12);
    }

    private void thenTheButtonsOfTheDownloadShowWithRoomUnder() throws Exception {
        FxTesting.assertEventually("the buttons of the download with room under them", () -> {
            Node button = this.screen.details().view().lookup("#details-first");
            Node details = this.screen.details().view();
            return button.localToScene(button.getLayoutBounds()).getMaxY() + 8
                    <= details.localToScene(details.getLayoutBounds()).getMaxY();
        });
    }

    private void whenPressed(String action) throws Exception {
        FxTesting.onFx(() -> this.screen.details().button(action).fire());
    }

    private void thenTheNamesAre(DownloadDetails.SourceName... expected) throws Exception {
        assertThat(FxTesting.onFx(
                        () -> List.copyOf(this.screen.details().names().getItems())))
                .containsExactly(expected);
    }

    private void thenTheNewNameIs(String name) throws Exception {
        assertThat(FxTesting.onFx(() -> newName().getText())).isEqualTo(name);
    }

    private void thenTheEngineIsAskedToRename(Hash16 download, String name) throws Exception {
        FxTesting.await("the rename", () -> !this.renamed.isEmpty());
        assertThat(this.renamed).containsExactly(download + " " + name);
    }

    private TextField newName() {
        return this.screen.details().newName();
    }

    private static SourceInfo source(Hash16 file, int n, String name) {
        return new SourceInfo(
                file,
                "film.mkv",
                "10.0.0." + (n + 1) + ":4662",
                Optional.empty(),
                "eMule",
                SourceInfo.State.QUEUED,
                n + 1,
                0,
                1,
                4,
                Optional.empty(),
                "",
                Optional.of(name));
    }

    private void whenTheCompletePartsAreCounted(PartMap map, int total) {
        this.complete = DownloadDetails.complete(map, total);
    }

    private void givenTheDetailsButtonPressed() throws Exception {
        FxTesting.onFx(() -> ((ToggleButton) this.screen.view().lookup("#details")).fire());
    }

    private void givenTheDetailsAre(boolean shown) throws Exception {
        detailsAre(shown);
    }

    private void givenTheDetailsTell(String... texts) throws Exception {
        detailsTell(texts);
    }

    private void givenTheSettingsRemember(boolean shown) throws Exception {
        thenTheSettingsRemember(shown);
    }

    private void whenTheTabIsShown(DownloadDetails.Tab tab) throws Exception {
        FxTesting.onFx(() -> this.screen.details().showTab(tab));
    }

    private void whenAltEnterIsPressed() throws Exception {
        FxTesting.onFx(() -> this.screen
                .downloads()
                .fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER, false, false, true, false)));
    }

    private void whenTheDownloadIsChosen() throws Exception {
        FxTesting.onFx(() -> this.screen.downloads().getSelectionModel().select(0));
    }

    private void thenTheyAre(int expected) {
        assertThat(this.complete).isEqualTo(expected);
    }

    private void thenTheDetailsAre(boolean shown) throws Exception {
        detailsAre(shown);
    }

    private void thenTheDetailsTell(String... texts) throws Exception {
        detailsTell(texts);
    }

    private void thenTheSettingsRemember(boolean shown) throws Exception {
        assertThat(FxTesting.onFx(() -> this.test.gui.settings().transfersDetails()))
                .isEqualTo(shown);
    }

    private void detailsAre(boolean shown) throws Exception {
        assertThat(FxTesting.onFx(this.screen::detailsShown)).isEqualTo(shown);
    }

    // The details follow the chosen download a moment later.
    private void detailsTell(String... texts) throws Exception {
        FxTesting.assertEventually(
                "the details tell " + List.of(texts),
                () -> List.of(texts).stream()
                        .allMatch(text -> shownTexts().stream().anyMatch(t -> t != null && t.contains(text))));
        List<String> shown = FxTesting.onFx(this::shownTexts);
        for (String text : texts) {
            assertThat(shown).anyMatch(t -> t != null && t.contains(text));
        }
    }

    private List<String> shownTexts() {
        Node view = this.screen.details().view();
        view.applyCss();
        return view.lookupAll(".label").stream()
                .filter(Node::isVisible)
                .filter(n -> n.getParent() == null || n.getParent().isVisible())
                .map(n -> ((Label) n).getText())
                .toList();
    }
}
