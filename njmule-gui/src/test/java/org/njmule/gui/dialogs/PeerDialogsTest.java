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

    @AfterEach
    void close() {
        if (this.test != null) {
            this.test.close();
        }
    }

    @Test
    void theFilesOfAClientAreListedAndTheChosenOnesDownloaded() throws Exception {
        SearchResult film = new SearchResult(FILE, "film.mkv", 700_000_000, 1, 1, Optional.of("Video"));
        this.test = TestGui.start(
                this.dir.resolve("njmule"),
                real -> Engines.overriding(
                        real, Map.of("browseSharedFiles", args -> CompletableFuture.completedFuture(List.of(film)))));
        ClientFilesDialog dialog = this.browse();

        FxTesting.await("the list", () -> this.table(dialog).getItems().size() == 1);
        assertThat(FxTesting.onFx(() -> this.state(dialog))).isEqualTo("1 shared files");
        assertThat(FxTesting.onFx(() -> dialog.stage().getTitle())).isEqualTo("View Shared Files: Peer");
        assertThat(FxTesting.onFx(() -> this.button(dialog, "Download").isDisabled()))
                .as("nothing chosen")
                .isTrue();
        FxTesting.onFx(() -> {
            this.table(dialog).getSelectionModel().select(0);
            this.button(dialog, "Download").fire();
        });
        FxTesting.await("the download", () -> this.test.model.downloads().size() == 1);
        assertThat(this.test.engine.downloads().get(10, TimeUnit.SECONDS))
                .extracting(DownloadInfo::name)
                .containsExactly("film.mkv");
        FxTesting.onFx(() -> this.button(dialog, "Close").fire());
        assertThat(FxTesting.onFx(() -> dialog.stage().isShowing())).isFalse();
    }

    @Test
    void aClientWithoutFilesOrThatRefusesSaysSo() throws Exception {
        AtomicReference<CompletableFuture<List<SearchResult>>> answer =
                new AtomicReference<>(CompletableFuture.completedFuture(List.of()));
        this.test = TestGui.start(
                this.dir.resolve("njmule"),
                real -> Engines.overriding(real, Map.of("browseSharedFiles", args -> answer.get())));

        ClientFilesDialog empty = this.browse();
        FxTesting.await("told", () -> this.state(empty).equals("The client shows no files"));
        FxTesting.onFx(() -> empty.stage().close());

        answer.set(CompletableFuture.failedFuture(new IOException("the client refused")));
        ClientFilesDialog refused = this.browse();
        FxTesting.await("told", () -> this.state(refused).equals("No list: the client refused"));
        FxTesting.onFx(() -> refused.stage().close());
    }

    @Test
    void aDownloadsCommentsShowWhatItsSourcesSaid() throws Exception {
        CompletableFuture<Void> saved = new CompletableFuture<>();
        AtomicReference<FileComment> stored = new AtomicReference<>();
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
                                    stored.set((FileComment) args[1]);
                                    return saved;
                                })));
        AtomicReference<CommentDialog> dialog = new AtomicReference<>();
        FxTesting.onFx(() -> {
            dialog.set(new CommentDialog(this.test.gui, new Stage(), FILE, "film.mkv", true));
            dialog.get().show();
        });
        // The list of the dialog, not the one of the rating box.
        ListView<?> heard =
                FxTesting.onFx(() -> dialog.get().stage().getScene().getRoot().getChildrenUnmodifiable().stream()
                        .filter(ListView.class::isInstance)
                        .map(ListView.class::cast)
                        .findFirst()
                        .orElseThrow());
        FxTesting.await("the comments heard", () -> heard.getItems().size() == 2);
        FxTesting.await("their lines", () -> !FxTesting.cellTexts(heard).isEmpty());
        assertThat(FxTesting.onFx(() -> FxTesting.cellTexts(heard))).contains("Bob: [Good] nice", "Ann: no rating");
        assertThat(FxTesting.onFx(() -> dialog.get().textField().getText())).isEqualTo("kept");
        assertThat(FxTesting.onFx(() -> dialog.get().ratingBox().getValue())).isEqualTo(2);
        assertThat(FxTesting.onFx(() -> FxTesting.cellTexts(dialog.get().ratingBox())))
                .contains("Poor");

        FxTesting.onFx(() -> {
            dialog.get().textField().setText("x".repeat(FileComment.MAX_LENGTH + 20));
            dialog.get().ratingBox().setValue(5);
            dialog.get().save();
        });
        assertThat(stored.get()).isEqualTo(FileComment.of("x".repeat(FileComment.MAX_LENGTH), 5));
        assertThat(FxTesting.onFx(() -> dialog.get().stage().isShowing()))
                .as("open until stored")
                .isTrue();
        saved.complete(null);
        FxTesting.await("closed", () -> !dialog.get().stage().isShowing());
    }

    private ClientFilesDialog browse() throws Exception {
        return FxTesting.onFx(() -> {
            ClientFilesDialog dialog = new ClientFilesDialog(this.test.gui, new Stage(), "Peer");
            dialog.show(PEER);
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
