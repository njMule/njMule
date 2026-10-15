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
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.StackPane;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.njmule.core.api.DownloadInfo;
import org.njmule.core.api.DownloadPriority;
import org.njmule.core.api.Hash16;
import org.njmule.core.api.PartMap;
import org.njmule.core.api.SourceInfo;
import org.njmule.gui.Engines;
import org.njmule.gui.FxTesting;
import org.njmule.gui.TestGui;
import org.njmule.gui.ui.PartBar;

@Timeout(60)
class TransfersViewTest {

    private static final Hash16 FIRST = Hash16.parseHex("00000000000000000000000000000001");
    private static final Hash16 SECOND = Hash16.parseHex("00000000000000000000000000000002");

    @TempDir
    Path dir;

    private TestGui test;
    private TransfersScreen screen;
    private TransfersScreen.Shown shown;

    @AfterEach
    void close() {
        if (this.test != null) {
            this.test.close();
        }
    }

    @ParameterizedTest
    @CsvSource({
        "DOWNLOADING, 5, 2, DOWNLOADING",
        "DOWNLOADING, 5, 0, WAITING",
        "DOWNLOADING, 0, 0, NO_SOURCES",
        "PAUSED, 0, 0, PAUSED",
        "NO_SPACE, 9, 0, NO_SPACE",
        "COMPLETE, 0, 0, COMPLETE"
    })
    void shouldTellWhetherDataComesOrNoSourceIsKnownForARunningDownload(
            DownloadInfo.Status status, int sources, int transferring, TransfersScreen.Shown expected) {
        whenADownloadIs(status, sources, transferring);

        thenItShowsAs(expected);
    }

    @Test
    void shouldKeepTheDownloadsWhoseNameHoldsEveryWordOfTheFilter() throws Exception {
        givenTheDownloads("Debian 13 DVD.iso", "debian-netinst.iso", "Sintel.mkv");
        givenTheFilterReads("DEBIAN iso");
        givenTheDownloadsShownAre("Debian 13 DVD.iso", "debian-netinst.iso");
        givenTheFilterReads("dvd debian");
        givenTheDownloadsShownAre("Debian 13 DVD.iso");

        whenTheFilterReads("");

        thenTheDownloadsShownAre("Debian 13 DVD.iso", "debian-netinst.iso", "Sintel.mkv");
    }

    @Test
    void shouldShowBelowTheSourcesOfTheDownloadChosen() throws Exception {
        givenTheDownloads("first.bin", "second.bin");
        givenTheSourcesShownAre(3);
        givenTheDownloadChosen(1);
        givenTheSourcesShownAre(1);

        whenTheDownloadIsChosen(0);

        thenTheSourcesShownAre(2);
    }

    @Test
    void shouldKeepTheCommandsOnDownloadsOffUntilOneIsChosen() throws Exception {
        givenTheDownloads("first.bin", "second.bin");
        givenTheCommandsOnTheChosenAreOff(true);

        whenTheDownloadIsChosen(1);

        thenTheCommandsOnTheChosenAreOff(false);
    }

    @Test
    void shouldPutTheCursorInTheFilterOnCtrlF() throws Exception {
        givenTheDownloads("first.bin");

        whenCtrlFIsPressed();

        thenTheFilterHasTheCursor();
    }

    @Test
    void shouldShowTheProgressRightAfterTheSpeed() throws Exception {
        whenTheTransfersShowTheDownloads("film.mkv");

        thenTheColumnsBegin("IDS_DL_FILENAME", "IDS_DL_SIZE", "IDS_DL_TRANSFCOMPL", "IDS_DL_SPEED", "IDS_DL_PROGRESS");
    }

    @Test
    void shouldWriteThePercentageOverThePartBar() throws Exception {
        whenTheTransfersShowTheDownloads("film.mkv");

        thenThePercentageOverTheBarIs(this.test.gui.formats().percent(100, 1000));
    }

    private void givenTheDownloads(String... names) throws Exception {
        List<DownloadInfo> downloads = new ArrayList<>();
        for (int i = 0; i < names.length; i++) {
            downloads.add(download(
                    i == 0 ? FIRST : i == 1 ? SECOND : Hash16.parseHex(String.format("%032X", i + 1)),
                    names[i],
                    DownloadInfo.Status.DOWNLOADING,
                    1,
                    0));
        }
        List<SourceInfo> sources = List.of(source(FIRST, 1), source(FIRST, 2), source(SECOND, 3));
        Map<String, Engines.Command> commands = Map.of(
                "downloads", a -> CompletableFuture.completedFuture(List.copyOf(downloads)),
                "sources", a -> CompletableFuture.completedFuture(sources));
        this.test = TestGui.start(this.dir.resolve("njmule"), real -> Engines.overriding(real, commands));
        this.screen = new TransfersScreen(this.test.gui);
        this.test.show(FxTesting.onFx(this.screen::view));
        FxTesting.onFx(this.test.model::tick);
        FxTesting.onFx(this.test.model::tick);
        FxTesting.await(
                "the downloads", () -> this.screen.downloads().getItems().size() == names.length);
        FxTesting.await("the sources", () -> this.test.model.sources().rows().size() == sources.size());
    }

    private void whenTheTransfersShowTheDownloads(String... names) throws Exception {
        givenTheDownloads(names);
    }

    private void givenTheFilterReads(String text) throws Exception {
        whenTheFilterReads(text);
    }

    private void givenTheDownloadsShownAre(String... names) throws Exception {
        thenTheDownloadsShownAre(names);
    }

    private void givenTheSourcesShownAre(int count) throws Exception {
        thenTheSourcesShownAre(count);
    }

    private void givenTheDownloadChosen(int index) throws Exception {
        whenTheDownloadIsChosen(index);
    }

    private void givenTheCommandsOnTheChosenAreOff(boolean off) throws Exception {
        thenTheCommandsOnTheChosenAreOff(off);
    }

    private void whenADownloadIs(DownloadInfo.Status status, int sources, int transferring) {
        this.shown = TransfersScreen.shown(download(FIRST, "x.bin", status, sources, transferring));
    }

    private void whenTheFilterReads(String text) throws Exception {
        FxTesting.onFx(() -> filter().setText(text));
    }

    private void whenTheDownloadIsChosen(int index) throws Exception {
        FxTesting.onFx(() -> {
            this.screen.downloads().getSelectionModel().clearSelection();
            this.screen.downloads().getItems().stream()
                    .filter(r -> r.get().hash().equals(index == 0 ? FIRST : SECOND))
                    .findFirst()
                    .ifPresent(r -> this.screen.downloads().getSelectionModel().select(r));
        });
    }

    private void whenCtrlFIsPressed() throws Exception {
        FxTesting.onFx(() -> this.screen.downloads().fireEvent(FxTesting.key(KeyCode.F, true)));
    }

    private void thenTheColumnsBegin(String... keys) throws Exception {
        List<String> expected = Arrays.stream(keys).map(this.test.gui::t).toList();
        assertThat(FxTesting.onFx(() -> this.screen.downloads().getColumns().stream()
                        .map(TableColumn::getText)
                        .limit(keys.length)
                        .toList()))
                .isEqualTo(expected);
    }

    // The bar and its figure share the cell: the figure is drawn over the bar, not beside it.
    private void thenThePercentageOverTheBarIs(String percent) throws Exception {
        FxTesting.await("the progress cell", () -> progressCell().isPresent());
        StackPane cell = FxTesting.onFx(() -> progressCell().orElseThrow());
        assertThat(FxTesting.onFx(() -> cell.getChildren().stream()
                        .map(n -> n.getClass().getSimpleName())
                        .toList()))
                .containsExactly("PartBar", "Label");
        assertThat(FxTesting.onFx(() -> ((Label) cell.getChildren().getLast()).getText()))
                .isEqualTo(percent);
    }

    private Optional<StackPane> progressCell() {
        return this.screen.downloads().lookupAll(".table-cell").stream()
                .map(n -> ((TableCell<?, ?>) n).getGraphic())
                .filter(StackPane.class::isInstance)
                .map(StackPane.class::cast)
                .filter(p -> p.getChildren().stream().anyMatch(PartBar.class::isInstance))
                .findFirst();
    }

    private void thenItShowsAs(TransfersScreen.Shown expected) {
        assertThat(this.shown).isEqualTo(expected);
    }

    private void thenTheDownloadsShownAre(String... names) throws Exception {
        assertThat(FxTesting.onFx(() -> this.screen.downloads().getItems().stream()
                        .map(r -> r.get().name())
                        .toList()))
                .containsExactlyInAnyOrder(names);
    }

    private void thenTheSourcesShownAre(int count) throws Exception {
        FxTesting.assertEventually(
                "the sources shown", () -> this.screen.sources().getItems().size() == count);
    }

    private void thenTheCommandsOnTheChosenAreOff(boolean off) throws Exception {
        List<String> names = List.of("Pause", "Resume", "Stop", "Cancel");
        assertThat(FxTesting.onFx(() -> this.screen.view().lookupAll(".button").stream()
                        .filter(n -> n instanceof Button b && b.getText() != null && names.contains(b.getText()))
                        .map(Node::isDisabled)
                        .toList()))
                .hasSize(names.size())
                .containsOnly(off);
    }

    private void thenTheFilterHasTheCursor() throws Exception {
        assertThat(FxTesting.onFx(() -> filter().equals(filter().getScene().getFocusOwner())))
                .isTrue();
    }

    private TextField filter() {
        return this.screen.view().lookupAll(".text-field").stream()
                .map(TextField.class::cast)
                .filter(f -> this.test.gui.t("nj.transfers.filter").equals(f.getPromptText()))
                .findFirst()
                .orElseThrow();
    }

    private static DownloadInfo download(
            Hash16 hash, String name, DownloadInfo.Status status, int sources, int transferring) {
        return new DownloadInfo(
                hash,
                name,
                1000,
                100,
                status,
                DownloadPriority.AUTO,
                0,
                sources,
                transferring,
                0,
                PartMap.EMPTY,
                false,
                false);
    }

    private static SourceInfo source(Hash16 file, int n) {
        return new SourceInfo(
                file,
                "file.bin",
                "10.0.0." + n + ":4662",
                Optional.empty(),
                "eMule",
                SourceInfo.State.QUEUED,
                n,
                0,
                1,
                2,
                Optional.empty(),
                "",
                Optional.empty());
    }
}
