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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.core.api.FileComment;
import org.njmule.core.api.Hash16;
import org.njmule.core.api.ImportReport;
import org.njmule.core.api.SharedDirectory;
import org.njmule.gui.Engines;
import org.njmule.gui.FxTesting;
import org.njmule.gui.TestGui;

@Timeout(60)
class DialogsTest {

    private static final Hash16 FILE = Hash16.parseHex("0123456789ABCDEF0123456789ABCDEF");

    @TempDir
    Path dir;

    private TestGui test;
    private Stage stage;
    private CategoriesDialog categories;
    private SharedFoldersDialog sharedFolders;
    private ImportDialog importDialog;
    private CommentDialog comment;
    private AboutDialog about;
    private final CompletableFuture<Optional<FileComment>> stored = new CompletableFuture<>();
    private Path folder;
    private Optional<Path> guess;

    @AfterEach
    void close() throws Exception {
        if (this.stage != null) {
            FxTesting.onFx(this.stage::close);
        }
        if (this.test != null) {
            this.test.close();
        }
    }

    @Test
    void shouldAddChangeAndRemoveCategories() throws Exception {
        givenTheGui();
        givenTheCategoriesDialogShown();
        givenACategorySaved("Films", "*.mkv|*.avi");
        givenTheModelHasOneCategory();
        givenTheEngineKeptTheCategory(1, "a.mkv");
        givenTheCategoryIsSelected();
        givenTheCategoryRenamed("Movies");
        givenTheOnlyCategoryIsNamed("Movies");

        whenTheFirstCategoryIsRemoved();

        thenTheModelHasNoCategory();
    }

    @Test
    void shouldListAndUnshareSharedFolders() throws Exception {
        givenTheGui();
        givenASharedFolder("films");
        givenTheSharedFoldersDialogShown();
        givenTheDialogListsTheFolder();

        whenTheFirstFolderIsUnshared();

        thenTheDialogListsNoFolder();
    }

    @Test
    void shouldCheckAnImportThenTakeItAndRefuseAMissingFolder() throws Exception {
        givenTheGui();
        givenAnEmuleConfigFolderWith("preferences.ini", "[eMule]\r\nNick=FromEmule\r\n");
        givenTheImportDialogShown();
        givenTheFolderChecked();
        givenTheCheckSays("preferences.ini", ImportReport.Outcome.WOULD_IMPORT);
        givenTheNickIsNot("FromEmule");
        givenTheImportTaken();
        givenTheNickBecomes("FromEmule");

        whenAFolderThatDoesNotExistIsChecked();

        thenTheUserWasToldSomethingStartingWith("Not a folder");
    }

    @Test
    void shouldGuessTheEmuleDirectoryWithoutFailing() {
        whenTheEmuleDirectoryIsGuessed();

        thenThereIsAGuess();
    }

    @Test
    void shouldLetTheDialogBeUsedAgainAfterAFailedImport() throws Exception {
        givenTheGuiWithAnImportThatFails("preferences.ini is locked");
        givenAnEmptyEmuleConfigFolder();
        givenTheImportDialogShown();

        whenTheFolderIsChecked();

        thenTheCheckCanBeRunAgain();
        thenTheSummaryContains("preferences.ini is locked");
    }

    @Test
    void shouldNeverReplaceWhatTheUserTypedWithALateComment() throws Exception {
        givenTheGuiWithTheStoredCommentStillComing();
        givenTheCommentDialogShown("film.mkv");
        givenTheUserTyped("my own words");

        whenTheStoredCommentArrives("the old comment", 3);

        thenTheCommentFieldShows("my own words");
        thenTheRatingIs(3);
    }

    @Test
    void shouldShowTheAboutDialog() throws Exception {
        givenTheGui();

        whenTheAboutDialogIsShown();

        thenTheAboutDialogShows();
    }

    private void givenTheGui() throws Exception {
        this.test = TestGui.start(this.dir.resolve("njmule"));
    }

    private void givenTheGuiWithAnImportThatFails(String message) throws Exception {
        this.test = TestGui.start(
                this.dir.resolve("njmule"),
                real -> Engines.overriding(
                        real,
                        Map.of("importFromEmule", args -> CompletableFuture.failedFuture(new IOException(message)))));
    }

    private void givenTheGuiWithTheStoredCommentStillComing() throws Exception {
        this.test = TestGui.start(
                this.dir.resolve("njmule"),
                real -> Engines.overriding(real, Map.of("fileComment", args -> this.stored)));
    }

    private void givenTheCategoriesDialogShown() throws Exception {
        FxTesting.onFx(() -> {
            this.categories = new CategoriesDialog(this.test.gui, new Stage());
            this.categories.show();
            this.stage = this.categories.stage();
        });
    }

    private void givenACategorySaved(String name, String patterns) throws Exception {
        FxTesting.onFx(() -> {
            this.categories.nameField().setText(name);
            this.categories.patternsField().setText(patterns);
            this.categories.save();
        });
    }

    private void givenTheModelHasOneCategory() throws Exception {
        FxTesting.await("the category", () -> this.test.gui.model().categories().size() == 1);
    }

    private void givenTheEngineKeptTheCategory(int id, String matchingFile) throws Exception {
        assertThat(this.test.engine.categories().get(10, TimeUnit.SECONDS))
                .singleElement()
                .satisfies(c -> {
                    assertThat(c.id()).isEqualTo(id);
                    assertThat(c.matches(matchingFile)).isTrue();
                });
    }

    private void givenTheCategoryIsSelected() throws Exception {
        FxTesting.await(
                "selected", () -> this.categories.list().getSelectionModel().getSelectedItem() != null);
    }

    private void givenTheCategoryRenamed(String name) throws Exception {
        FxTesting.onFx(() -> {
            this.categories.nameField().setText(name);
            this.categories.save();
        });
    }

    private void givenTheOnlyCategoryIsNamed(String name) throws Exception {
        FxTesting.await(
                "renamed",
                () -> this.test.gui.model().categories().getFirst().name().equals(name));
        assertThat(this.test.gui.model().categories()).hasSize(1);
    }

    private void givenASharedFolder(String name) throws Exception {
        this.folder = Files.createDirectories(this.dir.resolve(name));
        this.test.engine.shareDirectory(this.folder, true).get(10, TimeUnit.SECONDS);
    }

    private void givenTheSharedFoldersDialogShown() throws Exception {
        FxTesting.onFx(() -> {
            this.sharedFolders = new SharedFoldersDialog(this.test.gui, new Stage());
            this.sharedFolders.show();
            this.stage = this.sharedFolders.stage();
        });
    }

    private void givenTheDialogListsTheFolder() throws Exception {
        FxTesting.await("the folder", () -> this.sharedFolders.list().getItems().size() == 1);
        assertThat(FxTesting.onFx(() -> this.sharedFolders.list().getItems().getFirst()))
                .isEqualTo(new SharedDirectory(this.folder.toAbsolutePath(), true));
    }

    private void givenAnEmuleConfigFolderWith(String file, String content) throws Exception {
        this.folder = Files.createDirectories(this.dir.resolve("emule-config"));
        Files.writeString(this.folder.resolve(file), content);
    }

    private void givenAnEmptyEmuleConfigFolder() throws Exception {
        this.folder = Files.createDirectories(this.dir.resolve("emule-config"));
    }

    private void givenTheImportDialogShown() throws Exception {
        FxTesting.onFx(() -> {
            this.importDialog = new ImportDialog(this.test.gui, new Stage());
            this.importDialog.show();
            this.stage = this.importDialog.stage();
        });
    }

    private void givenTheFolderChecked() throws Exception {
        check(this.folder);
    }

    private void givenTheCheckSays(String file, ImportReport.Outcome outcome) throws Exception {
        FxTesting.await(
                "the check", () -> !this.importDialog.reportTable().getItems().isEmpty());
        assertThat(FxTesting.onFx(() -> this.importDialog.reportTable().getItems().stream()
                        .filter(i -> i.file().equals(file))
                        .findFirst()
                        .orElseThrow()
                        .outcome()))
                .isEqualTo(outcome);
    }

    private void givenTheNickIsNot(String nick) {
        assertThat(this.test.engine.preferences().nick()).isNotEqualTo(nick);
    }

    private void givenTheImportTaken() throws Exception {
        FxTesting.onFx(() -> this.importDialog.run(false));
    }

    private void givenTheNickBecomes(String nick) throws Exception {
        FxTesting.await(
                "the import", () -> this.test.engine.preferences().nick().equals(nick));
    }

    private void givenTheCommentDialogShown(String name) throws Exception {
        FxTesting.onFx(() -> {
            this.comment = new CommentDialog(this.test.gui, new Stage(), FILE, name, false);
            this.comment.show();
            this.stage = this.comment.stage();
        });
    }

    private void givenTheUserTyped(String text) throws Exception {
        FxTesting.onFx(() -> this.comment.textField().setText(text));
    }

    private void whenTheFirstCategoryIsRemoved() throws Exception {
        FxTesting.onFx(() -> {
            this.categories.list().getSelectionModel().select(0);
            this.categories.remove();
        });
    }

    private void whenTheFirstFolderIsUnshared() throws Exception {
        FxTesting.onFx(() -> {
            this.sharedFolders.list().getSelectionModel().select(0);
            this.sharedFolders.unshareSelected();
        });
    }

    private void whenAFolderThatDoesNotExistIsChecked() throws Exception {
        check(this.dir.resolve("none"));
    }

    private void whenTheEmuleDirectoryIsGuessed() {
        this.guess = ImportDialog.guessEmuleDirectory();
    }

    private void whenTheFolderIsChecked() throws Exception {
        check(this.folder);
    }

    private void whenTheStoredCommentArrives(String text, int rating) throws Exception {
        this.stored.complete(Optional.of(FileComment.of(text, rating)));
        FxTesting.onFx(() -> {});
    }

    private void whenTheAboutDialogIsShown() throws Exception {
        FxTesting.onFx(() -> {
            this.about = new AboutDialog(this.test.gui, new Stage());
            this.about.show();
            this.stage = this.about.stage();
        });
    }

    private void thenTheModelHasNoCategory() throws Exception {
        FxTesting.await("no category", () -> this.test.gui.model().categories().isEmpty());
    }

    private void thenTheDialogListsNoFolder() throws Exception {
        FxTesting.await("no folder", () -> this.sharedFolders.list().getItems().isEmpty());
    }

    private void thenTheUserWasToldSomethingStartingWith(String start) {
        assertThat(this.test.told).anyMatch(m -> m.startsWith(start));
    }

    private void thenThereIsAGuess() {
        assertThat(this.guess).isNotNull();
    }

    private void thenTheCheckCanBeRunAgain() throws Exception {
        FxTesting.await("the failure", () -> !this.importDialog.checkButton().isDisabled());
    }

    private void thenTheSummaryContains(String text) throws Exception {
        assertThat(FxTesting.onFx(() -> this.importDialog.summaryLabel().getText()))
                .contains(text);
    }

    private void thenTheCommentFieldShows(String text) throws Exception {
        assertThat(FxTesting.onFx(() -> this.comment.textField().getText())).isEqualTo(text);
    }

    private void thenTheRatingIs(int rating) throws Exception {
        assertThat(FxTesting.onFx(() -> this.comment.ratingBox().getValue()))
                .as("the rating was not touched")
                .isEqualTo(rating);
    }

    private void thenTheAboutDialogShows() throws Exception {
        assertThat(FxTesting.onFx(() -> this.about.stage().isShowing())).isTrue();
    }

    private void check(Path profile) throws Exception {
        FxTesting.onFx(() -> {
            this.importDialog.directoryField().setText(profile.toString());
            this.importDialog.run(true);
        });
    }
}
