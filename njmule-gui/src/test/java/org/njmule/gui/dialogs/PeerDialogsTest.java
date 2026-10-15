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
package org.njmule.gui.dialogs;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TableView;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.core.api.DownloadInfo;
import org.njmule.core.api.FileComment;
import org.njmule.core.api.Hash16;
import org.njmule.core.api.PeerComment;
import org.njmule.core.api.SearchResult;
import org.njmule.core.api.ServerAddress;
import org.njmule.gui.Engines;
import org.njmule.gui.FxTesting;
import org.njmule.gui.TestGui;

@Timeout(60)
class PeerDialogsTest {

    private static final Hash16 FILE = Hash16.parseHex("0123456789ABCDEF0123456789ABCDEF");
    private static final ServerAddress PEER = new ServerAddress("10.0.0.1", 4662);

    @TempDir
    Path dir;

    private TestGui test;
    private final List<Stage> stages = new ArrayList<>();
    private ClientFilesDialog files;
    private final AtomicReference<CompletableFuture<List<SearchResult>>> answer = new AtomicReference<>();
    private final CompletableFuture<Void> saved = new CompletableFuture<>();
    private final AtomicReference<FileComment> stored = new AtomicReference<>();
    private CommentDialog comment;
    private ListView<?> heard;

    @AfterEach
    void close() throws Exception {
        for (Stage stage : this.stages) {
            FxTesting.onFx(stage::close);
        }
        if (this.test != null) {
            this.test.close();
        }
    }

    @Test
    void shouldListTheFilesOfAClientDownloadTheChosenOnesAndClose() throws Exception {
        givenAClientSharing("film.mkv");
        givenItsFilesBrowsed();
        givenTheDialogListsFiles(1);
        givenTheStateReads("1 shared files");
        givenTheTitleIs("View Shared Files: Peer");
        givenDownloadIsOffWhileNothingIsChosen();
        givenTheFirstFileDownloaded();
        givenTheModelHasDownloads(1);
        givenTheEngineDownloads("film.mkv");

        whenCloseIsPressed();

        thenTheDialogIsClosed();
    }

    @Test
    void shouldSayWhenAClientHasNoFilesOrRefuses() throws Exception {
        givenAClientWithoutFiles();
        givenItsFilesBrowsed();
        givenTheStateBecomes("The client shows no files");
        givenTheDialogClosed();
        givenTheClientRefuses("the client refused");

        whenItsFilesAreBrowsed();

        thenTheStateBecomes("No list: the client refused");
    }

    @Test
    void shouldShowWhatTheSourcesOfADownloadSaidAndCloseOnceTheCommentIsStored() throws Exception {
        givenSourcesThatCommented();
        givenTheCommentDialogShown("film.mkv");
        givenTheCommentsHeardShow("Bob: [Good] nice", "Ann: no rating");
        givenTheOwnCommentShows("kept", 2);
        givenTheRatingsOffer("Poor");
        givenACommentSavedLongerThanAllowedWithTheRating(5);
        givenTheCommentStoredIsCut(5);
        givenTheDialogStaysOpenUntilStored();

        whenTheCommentIsStored();

        thenTheCommentDialogCloses();
    }

    private void givenAClientSharing(String name) throws Exception {
        SearchResult film = new SearchResult(FILE, name, 700_000_000, 1, 1, Optional.of("Video"));
        this.test = TestGui.start(
                this.dir.resolve("njmule"),
                real -> Engines.overriding(
                        real, Map.of("browseSharedFiles", args -> CompletableFuture.completedFuture(List.of(film)))));
    }

    private void givenAClientWithoutFiles() throws Exception {
        this.answer.set(CompletableFuture.completedFuture(List.of()));
        this.test = TestGui.start(
                this.dir.resolve("njmule"),
                real -> Engines.overriding(real, Map.of("browseSharedFiles", args -> this.answer.get())));
    }

    private void givenTheClientRefuses(String reason) {
        this.answer.set(CompletableFuture.failedFuture(new IOException(reason)));
    }

    private void givenSourcesThatCommented() throws Exception {
        this.test = TestGui.start(
                this.dir.resolve("njmule"),
                real -> Engines.overriding(
                        real,
                        Map.of(
                                "peerComments",
                                args -> CompletableFuture.completedFuture(List.of(
                                        new PeerComment("Bob", "film.mkv", FileComment.of("nice", 4)),
                                        new PeerComment("Ann", "film.mkv", FileComment.of("no rating", 0)))),
                                "fileComment",
                                args -> CompletableFuture.completedFuture(Optional.of(FileComment.of("kept", 2))),
                                "setFileComment",
                                args -> {
                                    this.stored.set((FileComment) args[1]);
                                    return this.saved;
                                })));
    }

    private void givenItsFilesBrowsed() throws Exception {
        this.files = browse();
    }

    private void givenTheDialogListsFiles(int count) throws Exception {
        FxTesting.await("the list", () -> table(this.files).getItems().size() == count);
    }

    private void givenTheStateReads(String state) throws Exception {
        assertThat(FxTesting.onFx(() -> state(this.files))).isEqualTo(state);
    }

    private void givenTheStateBecomes(String state) throws Exception {
        FxTesting.await("told", () -> state(this.files).equals(state));
    }

    private void givenTheTitleIs(String title) throws Exception {
        assertThat(FxTesting.onFx(() -> this.files.stage().getTitle())).isEqualTo(title);
    }

    private void givenDownloadIsOffWhileNothingIsChosen() throws Exception {
        assertThat(FxTesting.onFx(() -> button(this.files, "Download").isDisabled()))
                .as("nothing chosen")
                .isTrue();
    }

    private void givenTheFirstFileDownloaded() throws Exception {
        FxTesting.onFx(() -> {
            table(this.files).getSelectionModel().select(0);
            button(this.files, "Download").fire();
        });
    }

    private void givenTheModelHasDownloads(int count) throws Exception {
        FxTesting.await("the download", () -> this.test.model.downloads().size() == count);
    }

    private void givenTheEngineDownloads(String... names) throws Exception {
        assertThat(this.test.engine.downloads().get(10, TimeUnit.SECONDS))
                .extracting(DownloadInfo::name)
                .containsExactly(names);
    }

    private void givenTheDialogClosed() throws Exception {
        FxTesting.onFx(() -> this.files.stage().close());
    }

    private void givenTheCommentDialogShown(String name) throws Exception {
        FxTesting.onFx(() -> {
            this.comment = new CommentDialog(this.test.gui, new Stage(), FILE, name, true);
            this.comment.show();
            this.stages.add(this.comment.stage());
        });
        // The list of the dialog, not the one of the rating box.
        this.heard = FxTesting.onFx(() -> this.comment.stage().getScene().getRoot().getChildrenUnmodifiable().stream()
                .filter(ListView.class::isInstance)
                .map(ListView.class::cast)
                .findFirst()
                .orElseThrow());
    }

    private void givenTheCommentsHeardShow(String... lines) throws Exception {
        FxTesting.await("the comments heard", () -> this.heard.getItems().size() == lines.length);
        FxTesting.await("their lines", () -> !FxTesting.cellTexts(this.heard).isEmpty());
        assertThat(FxTesting.onFx(() -> FxTesting.cellTexts(this.heard))).contains(lines);
    }

    private void givenTheOwnCommentShows(String text, int rating) throws Exception {
        assertThat(FxTesting.onFx(() -> this.comment.textField().getText())).isEqualTo(text);
        assertThat(FxTesting.onFx(() -> this.comment.ratingBox().getValue())).isEqualTo(rating);
    }

    private void givenTheRatingsOffer(String rating) throws Exception {
        assertThat(FxTesting.onFx(() -> FxTesting.cellTexts(this.comment.ratingBox())))
                .contains(rating);
    }

    private void givenACommentSavedLongerThanAllowedWithTheRating(int rating) throws Exception {
        FxTesting.onFx(() -> {
            this.comment.textField().setText("x".repeat(FileComment.MAX_LENGTH + 20));
            this.comment.ratingBox().setValue(rating);
            this.comment.save();
        });
    }

    private void givenTheCommentStoredIsCut(int rating) {
        assertThat(this.stored.get()).isEqualTo(FileComment.of("x".repeat(FileComment.MAX_LENGTH), rating));
    }

    private void givenTheDialogStaysOpenUntilStored() throws Exception {
        assertThat(FxTesting.onFx(() -> this.comment.stage().isShowing()))
                .as("open until stored")
                .isTrue();
    }

    private void whenCloseIsPressed() throws Exception {
        FxTesting.onFx(() -> button(this.files, "Close").fire());
    }

    private void whenItsFilesAreBrowsed() throws Exception {
        this.files = browse();
    }

    private void whenTheCommentIsStored() {
        this.saved.complete(null);
    }

    private void thenTheDialogIsClosed() throws Exception {
        assertThat(FxTesting.onFx(() -> this.files.stage().isShowing())).isFalse();
    }

    private void thenTheStateBecomes(String state) throws Exception {
        FxTesting.await("told", () -> state(this.files).equals(state));
    }

    private void thenTheCommentDialogCloses() throws Exception {
        FxTesting.await("closed", () -> !this.comment.stage().isShowing());
    }

    private ClientFilesDialog browse() throws Exception {
        return FxTesting.onFx(() -> {
            ClientFilesDialog dialog = new ClientFilesDialog(this.test.gui, new Stage(), "Peer");
            dialog.show(PEER);
            this.stages.add(dialog.stage());
            return dialog;
        });
    }

    @SuppressWarnings("unchecked")
    private TableView<Object> table(ClientFilesDialog dialog) {
        return (TableView<Object>) dialog.stage().getScene().getRoot().lookup(".table-view");
    }

    // The line above the list.
    private String state(ClientFilesDialog dialog) {
        return ((Label) dialog.stage()
                        .getScene()
                        .getRoot()
                        .getChildrenUnmodifiable()
                        .getFirst())
                .getText();
    }

    private Button button(ClientFilesDialog dialog, String text) {
        return dialog.stage().getScene().getRoot().lookupAll(".button").stream()
                .filter(n -> n instanceof Button b && text.equals(b.getText()))
                .map(Button.class::cast)
                .findFirst()
                .orElseThrow();
    }
}
