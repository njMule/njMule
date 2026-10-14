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

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import javafx.scene.control.ListCell;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.core.api.FileComment;
import org.njmule.core.api.Hash16;
import org.njmule.core.api.KadNote;
import org.njmule.core.api.PeerComment;
import org.njmule.gui.Engines;
import org.njmule.gui.FxTesting;
import org.njmule.gui.TestGui;

/** The comment dialog shows what Kad nodes said of a file beside what its sources said, and searches Kad for more. */
@Timeout(60)
class CommentDialogKadTest {

    private static final Hash16 FILE = Hash16.parseHex("0123456789ABCDEF0123456789ABCDEF");
    private static final KadNote NOTE =
            new KadNote("0f".repeat(16), "Film (copy).mkv", FileComment.of("the real thing", 5));

    @TempDir
    Path dir;

    private final Map<String, Engines.Command> engine = new HashMap<>();
    private final CompletableFuture<List<KadNote>> search = new CompletableFuture<>();
    private TestGui test;
    private CommentDialog dialog;

    @AfterEach
    void close() throws Exception {
        if (this.dialog != null) {
            FxTesting.onFx(() -> this.dialog.stage().close());
        }
        if (this.test != null) {
            this.test.close();
        }
    }

    @Test
    void shouldShowKadNotesMarkedBesideWhatTheSourcesSaid() throws Exception {
        this.givenTheSourcesSaid(new PeerComment("Bob", "film.mkv", FileComment.of("nice", 4)));
        this.givenKadNodesSaid(NOTE);

        this.whenTheDialogOpensFor(true);

        this.thenTheLinesAre("Bob: [Good] nice", "Kad: [Excellent] the real thing");
        this.thenOnlyTheKadLineHasTheKadIcon();
    }

    @Test
    void shouldShowTheKadNotesOfASharedFile() throws Exception {
        this.givenKadNodesSaid(NOTE);

        this.whenTheDialogOpensFor(false);

        this.thenTheLinesAre("Kad: [Excellent] the real thing");
    }

    @Test
    void shouldTellWhileKadSearches() throws Exception {
        this.givenKadNodesSaid();
        this.givenTheDialogOpenedFor(true);

        this.whenKadIsSearched();

        this.thenKadStateSays("(Kad search in progress...)");
        this.thenTheSearchButtonIsDisabled(true);
    }

    @Test
    void shouldTellWhenKadHasNoNotes() throws Exception {
        this.givenKadNodesSaid();
        this.givenTheDialogOpenedFor(true);
        this.givenKadIsSearched();

        this.whenTheSearchEndsWith();

        this.thenKadStateSays("Kad holds no notes on this file");
        this.thenTheSearchButtonIsDisabled(false);
        this.thenTheListShowsNothingHeard();
    }

    @Test
    void shouldShowTheNotesTheSearchFound() throws Exception {
        this.givenKadNodesSaid();
        this.givenTheDialogOpenedFor(false);
        this.givenKadIsSearched();

        this.whenTheSearchEndsWith(NOTE);

        this.thenKadStateSays("Notes from Kad: 1");
        this.thenTheLinesAre("Kad: [Excellent] the real thing");
    }

    @Test
    void shouldTellWhyKadCouldNotSearch() throws Exception {
        this.givenKadNodesSaid();
        this.givenTheDialogOpenedFor(true);
        this.givenKadIsSearched();

        this.whenTheSearchFails("Kad is not connected");

        this.thenKadStateSays("No Kad search: Kad is not connected");
        this.thenTheSearchButtonIsDisabled(false);
    }

    private void givenTheSourcesSaid(PeerComment... comments) {
        CompletableFuture<List<PeerComment>> said = CompletableFuture.completedFuture(List.of(comments));
        this.engine.put("peerComments", args -> said);
    }

    private void givenKadNodesSaid(KadNote... notes) {
        CompletableFuture<List<KadNote>> said = CompletableFuture.completedFuture(List.of(notes));
        this.engine.put("kadNotes", args -> said);
    }

    private void givenTheDialogOpenedFor(boolean download) throws Exception {
        this.whenTheDialogOpensFor(download);
    }

    private void givenKadIsSearched() throws Exception {
        this.whenKadIsSearched();
    }

    private void whenTheDialogOpensFor(boolean download) throws Exception {
        this.engine.put("searchKadNotes", args -> this.search);
        this.test = TestGui.start(this.dir.resolve("njmule"), real -> Engines.overriding(real, this.engine));
        this.dialog = FxTesting.onFx(() -> {
            CommentDialog opened = new CommentDialog(this.test.gui, new Stage(), FILE, "film.mkv", download);
            opened.show();
            return opened;
        });
        // What the engine holds is shown once the dialog heard from it.
        FxTesting.onFx(() -> {});
    }

    private void whenKadIsSearched() throws Exception {
        FxTesting.onFx(() -> this.dialog.kadButton().fire());
    }

    private void whenTheSearchEndsWith(KadNote... notes) throws Exception {
        this.search.complete(List.of(notes));
        FxTesting.await("the end of the search", () -> !this.dialog.kadButton().isDisabled());
    }

    private void whenTheSearchFails(String why) throws Exception {
        this.search.completeExceptionally(new IllegalStateException(why));
        FxTesting.await("the end of the search", () -> !this.dialog.kadButton().isDisabled());
    }

    private void thenTheLinesAre(String... lines) throws Exception {
        FxTesting.await("the comments", () -> this.dialog.heardList().getItems().size() == lines.length);
        FxTesting.await(
                "their lines",
                () -> FxTesting.cellTexts(this.dialog.heardList()).size() == lines.length);
        assertThat(FxTesting.onFx(() -> FxTesting.cellTexts(this.dialog.heardList())))
                .containsExactly(lines);
    }

    private void thenOnlyTheKadLineHasTheKadIcon() throws Exception {
        List<Boolean> icons = FxTesting.onFx(() -> this.dialog.heardList().lookupAll(".indexed-cell").stream()
                .map(n -> (ListCell<?>) n)
                .filter(c -> c.getText() != null)
                .map(c -> c.getGraphic() != null)
                .toList());
        assertThat(icons).containsExactly(false, true);
    }

    private void thenKadStateSays(String text) throws Exception {
        assertThat(FxTesting.onFx(() -> this.dialog.kadState().getText())).isEqualTo(text);
    }

    private void thenTheSearchButtonIsDisabled(boolean disabled) throws Exception {
        assertThat(FxTesting.onFx(() -> this.dialog.kadButton().isDisabled())).isEqualTo(disabled);
    }

    private void thenTheListShowsNothingHeard() throws Exception {
        assertThat(FxTesting.onFx(() -> this.dialog.heardList().getItems())).isEmpty();
    }
}
