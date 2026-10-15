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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import javafx.scene.Node;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TextField;
import javafx.scene.input.Clipboard;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.core.api.Hash16;
import org.njmule.core.api.SharedDirectory;
import org.njmule.core.api.SharedFileInfo;
import org.njmule.core.api.UploadPriority;
import org.njmule.gui.Engines;
import org.njmule.gui.FxTesting;
import org.njmule.gui.TestGui;

@Timeout(120)
class SharedMenusTest {

    @TempDir
    Path dir;

    private TestGui test;
    private final List<String> calls = new CopyOnWriteArrayList<>();
    private SharedFileInfo notes;
    private SharedFileInfo film;
    private SharedScreen shownScreen;
    private final AtomicReference<String> shownWarning = new AtomicReference<>();

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
    void shouldOfferNoRenameCommentOrFolderForSeveralFiles() throws Exception {
        givenTheSharedScreen();
        givenAllFilesChosen();

        whenTheMenuShows();

        thenTheMenuItemsAreOff(true, "IDS_RENAME", "IDS_CMT_ADD", "IDS_OPENFOLDER");
    }

    @Test
    void shouldCopyTheHtmlLinksOfSeveralFiles() throws Exception {
        givenTheSharedScreen();
        givenAllFilesChosen();
        givenTheMenuShown();
        givenARenameACommentAndTheFolderAskedFor();

        whenTheMenuItemIsFired("IDS_DL_LINK2");

        thenTheClipboardHolds("<a href=\"ed2k://|file|notes.txt|", "<a href=\"ed2k://|file|film.mkv|");
        thenTheEngineWasAskedNothing();
    }

    @Test
    void shouldRenameAFileOnlyToAnotherNameTheUserConfirms() throws Exception {
        givenTheSharedScreen();
        givenTheFileChosen("notes.txt");
        givenTheMenuShown();
        givenTheMenuItemsAreOff(false, "IDS_RENAME");
        givenTheFileRenamedFromTheMenuTo("  notes.txt ");
        givenTheRenameCancelledAfterTyping("other.txt");

        whenTheFileIsRenamedTo(" letter.txt");

        thenTheOnlyRenameIs("rename notes.txt letter.txt");
    }

    @Test
    void shouldSetThePriorityFromTheMenuAndTheToolbar() throws Exception {
        givenTheSharedScreen();
        givenTheFileChosen("notes.txt");
        givenTheMenuShown();
        givenThePriorityChosenFromTheMenu("Release");

        whenThePriorityIsChosenFromTheToolbar("Very Low");

        thenTheEngineWasAlsoAskedFor("priority notes.txt RELEASE", "priority notes.txt VERY_LOW");
    }

    @Test
    void shouldOpenTheCommentsOfTheChosenFile() throws Exception {
        givenTheSharedScreen();
        givenTheFileChosen("notes.txt");
        givenTheMenuShown();

        whenTheMenuItemIsFired("IDS_CMT_ADD");

        thenTheWindowShows("Comments: notes.txt");
    }

    @Test
    void shouldCountTheSharedFiles() throws Exception {
        whenTheSharedScreenShows();

        thenTheLabelsInclude("Number of Shared Files: 2");
    }

    @Test
    void shouldAskFirstAndShareAFolderWithoutTheSubfoldersTheUserUnticked() throws Exception {
        givenTheSharedScreen();

        whenSharingIsConfirmedWithoutSubfoldersFor("films");

        thenTheEngineWasAskedToShare("films", false);
        thenAWarningShowsOnlyForASensitiveFolder("films");
    }

    @Test
    void shouldWarnOfTheRootOfADiskAndShareNothingWhenTheUserStepsBack() throws Exception {
        givenTheSharedScreen();

        whenSharingTheRootOfTheDiskIsCancelled();

        thenTheWarningNamesTheRootAnd("personal or system files");
        thenTheEngineWasAskedNothing();
    }

    @Test
    void shouldShareAFolderWithItsSubfoldersAsOffered() throws Exception {
        givenTheSharedScreen();

        whenSharingIsConfirmedFor("films");

        thenTheEngineWasAskedToShare("films", true);
    }

    private void givenTheSharedScreen() throws Exception {
        whenTheSharedScreenShows();
    }

    private void givenAllFilesChosen() throws Exception {
        FxTesting.onFx(() -> this.shownScreen.table().getSelectionModel().selectAll());
    }

    private void givenTheFileChosen(String name) throws Exception {
        SharedFileInfo file = this.notes.name().equals(name) ? this.notes : this.film;
        FxTesting.onFx(() -> select(this.shownScreen, file));
    }

    private void givenTheMenuShown() throws Exception {
        whenTheMenuShows();
    }

    private void givenTheMenuItemsAreOff(boolean off, String... keys) throws Exception {
        thenTheMenuItemsAreOff(off, keys);
    }

    // Several files: none is renamed, commented or shown in its folder.
    private void givenARenameACommentAndTheFolderAskedFor() throws Exception {
        FxTesting.onFx(() -> {
            this.shownScreen.renameSelected();
            this.shownScreen.commentSelected();
            this.shownScreen.openFolder();
        });
    }

    private void givenTheFileRenamedFromTheMenuTo(String typed) throws Exception {
        FxTesting.onFx(() -> {
            FxTesting.answer(ButtonType.OK, pane -> type(pane, typed));
            item(menu(), "IDS_RENAME").fire();
        });
    }

    private void givenTheRenameCancelledAfterTyping(String typed) throws Exception {
        FxTesting.onFx(() -> {
            FxTesting.answer(ButtonType.CANCEL, pane -> type(pane, typed));
            this.shownScreen.renameSelected();
        });
    }

    private void givenThePriorityChosenFromTheMenu(String priority) throws Exception {
        FxTesting.onFx(() -> ((Menu) item(menu(), "IDS_PRIORITY"))
                .getItems().stream()
                        .filter(i -> i.getText().equals(priority))
                        .findFirst()
                        .orElseThrow()
                        .fire());
    }

    private void whenTheSharedScreenShows() throws Exception {
        this.shownScreen = screen();
    }

    private void whenTheMenuShows() throws Exception {
        FxTesting.onFx(() -> menu().getOnShowing().handle(null));
    }

    private void whenTheMenuItemIsFired(String key) throws Exception {
        FxTesting.onFx(() -> item(menu(), key).fire());
    }

    private void whenTheFileIsRenamedTo(String typed) throws Exception {
        FxTesting.onFx(() -> {
            FxTesting.answer(ButtonType.OK, pane -> type(pane, typed));
            this.shownScreen.renameSelected();
        });
    }

    private void whenThePriorityIsChosenFromTheToolbar(String priority) throws Exception {
        FxTesting.onFx(() -> {
            MenuButton tool = (MenuButton) this.shownScreen.view().lookup(".menu-button");
            tool.getItems().stream()
                    .filter(i -> i.getText().equals(priority))
                    .findFirst()
                    .orElseThrow()
                    .fire();
        });
    }

    private void whenSharingIsConfirmedWithoutSubfoldersFor(String folder) throws Exception {
        FxTesting.onFx(() -> {
            FxTesting.answer(ButtonBar.ButtonData.OK_DONE, pane -> {
                this.shownWarning.set(warning(pane));
                ((CheckBox) pane.lookup(".check-box")).setSelected(false);
            });
            this.shownScreen.confirmShare(this.dir.resolve(folder));
        });
    }

    // The root of a disk is never shared without a warning; here the user steps back.
    private void whenSharingTheRootOfTheDiskIsCancelled() throws Exception {
        FxTesting.onFx(() -> {
            FxTesting.answer(ButtonBar.ButtonData.CANCEL_CLOSE, pane -> this.shownWarning.set(warning(pane)));
            this.shownScreen.confirmShare(root());
        });
    }

    private void whenSharingIsConfirmedFor(String folder) throws Exception {
        FxTesting.onFx(() -> {
            FxTesting.answer(ButtonBar.ButtonData.OK_DONE);
            this.shownScreen.confirmShare(this.dir.resolve(folder));
        });
    }

    private void thenTheMenuItemsAreOff(boolean off, String... keys) throws Exception {
        for (String key : keys) {
            assertThat(FxTesting.onFx(() -> item(menu(), key).isDisable()))
                    .as(key)
                    .isEqualTo(off);
        }
    }

    private void thenTheClipboardHolds(String... parts) throws Exception {
        assertThat(FxTesting.onFx(() -> Clipboard.getSystemClipboard().getString()))
                .contains(parts);
    }

    private void thenTheEngineWasAskedNothing() {
        assertThat(this.calls).isEmpty();
    }

    private void thenTheEngineWasAlsoAskedFor(String... expected) {
        assertThat(this.calls).contains(expected);
    }

    private void thenTheOnlyRenameIs(String rename) throws Exception {
        FxTesting.assertEventually("renamed", () -> this.calls.contains(rename));
        assertThat(this.calls).as("the same name, or cancelled").containsExactly(rename);
    }

    private void thenTheWindowShows(String title) throws Exception {
        FxTesting.assertEventually("the window " + title, () -> window(title) != null);
    }

    private void thenTheLabelsInclude(String text) throws Exception {
        assertThat(FxTesting.onFx(() -> this.shownScreen.view().lookupAll(".label").stream()
                        .map(n -> ((Label) n).getText())
                        .toList()))
                .contains(text);
    }

    private void thenTheEngineWasAskedToShare(String folder, boolean subfolders) {
        assertThat(this.calls).containsExactly("share " + this.dir.resolve(folder) + " " + subfolders);
    }

    private void thenAWarningShowsOnlyForASensitiveFolder(String folder) {
        assertThat(this.shownWarning.get().isEmpty())
                .as("a warning only for a sensitive folder")
                .isEqualTo(!SharedDirectory.isSensitive(this.dir.resolve(folder)));
    }

    private void thenTheWarningNamesTheRootAnd(String text) {
        assertThat(this.shownWarning.get()).contains(root().toString(), text);
    }

    private Path root() {
        return this.dir.toAbsolutePath().getRoot();
    }

    private ContextMenu menu() {
        return this.shownScreen.table().getContextMenu();
    }

    private SharedScreen screen() throws Exception {
        this.notes = new SharedFileInfo(
                Hash16.parseHex("0123456789ABCDEF0123456789ABCDEF"),
                "notes.txt",
                3000,
                this.dir.resolve("notes.txt"),
                UploadPriority.NORMAL);
        this.film = new SharedFileInfo(
                Hash16.parseHex("00112233445566778899AABBCCDDEEFF"),
                "film.mkv",
                700_000_000,
                this.dir.resolve("film.mkv"),
                UploadPriority.AUTO);
        Map<String, Engines.Command> commands = new HashMap<>();
        commands.put("sharedFiles", args -> CompletableFuture.completedFuture(List.of(this.notes, this.film)));
        commands.put("renameSharedFile", args -> {
            this.calls.add("rename " + name(args[0]) + " " + args[1]);
            return CompletableFuture.completedFuture(true);
        });
        commands.put("setUploadPriority", args -> {
            this.calls.add("priority " + name(args[0]) + " " + args[1]);
            return CompletableFuture.completedFuture(true);
        });
        commands.put("shareDirectory", args -> {
            this.calls.add("share " + args[0] + " " + (args.length > 1 ? args[1] : ""));
            return CompletableFuture.completedFuture(null);
        });
        this.test = TestGui.start(this.dir.resolve("njmule"), real -> Engines.overriding(real, commands));
        SharedScreen screen = new SharedScreen(this.test.gui);
        this.test.show(FxTesting.onFx(screen::view));
        FxTesting.await("the files", () -> screen.table().getItems().size() == 2);
        return screen;
    }

    private String name(Object hash) {
        return hash.equals(this.notes.hash()) ? this.notes.name() : this.film.name();
    }

    // The warning under the folder, or nothing.
    private String warning(DialogPane pane) {
        return pane.getContent().lookupAll(".label").stream()
                .map(n -> ((Label) n).getText())
                .filter(t -> t.contains("personal"))
                .findFirst()
                .orElse("");
    }

    private void select(SharedScreen screen, SharedFileInfo file) {
        var table = screen.table();
        table.getSelectionModel().clearSelection();
        for (int i = 0; i < table.getItems().size(); i++) {
            if (table.getItems().get(i).get().equals(file)) {
                table.getSelectionModel().select(i);
            }
        }
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
        String text = this.test.gui.t(key);
        return menu.getItems().stream()
                .filter(i -> text.equals(i.getText()))
                .findFirst()
                .orElseThrow();
    }
}
