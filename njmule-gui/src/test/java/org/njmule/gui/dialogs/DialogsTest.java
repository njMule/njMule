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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.core.api.ImportReport;
import org.njmule.core.api.SharedDirectory;
import org.njmule.gui.FxTesting;
import org.njmule.gui.TestGui;

@Timeout(60)
class DialogsTest {

    @TempDir
    Path dir;

    private TestGui test;

    @AfterEach
    void close() throws Exception {
        if (this.test != null) {
            this.test.close();
        }
    }

    @Test
    void categoriesAreAddedChangedAndRemoved() throws Exception {
        this.test = TestGui.start(this.dir.resolve("njmule"));
        AtomicReference<CategoriesDialog> dialog = new AtomicReference<>();
        FxTesting.onFx(() -> {
            dialog.set(new CategoriesDialog(this.test.gui, new Stage()));
            dialog.get().show();
            dialog.get().nameField().setText("Films");
            dialog.get().patternsField().setText("*.mkv|*.avi");
            dialog.get().save();
        });
        FxTesting.await("the category", () -> this.test.gui.model().categories().size() == 1);
        assertThat(this.test.engine.categories().get(10, TimeUnit.SECONDS))
                .singleElement()
                .satisfies(c -> {
                    assertThat(c.id()).isEqualTo(1);
                    assertThat(c.matches("a.mkv")).isTrue();
                });

        FxTesting.await(
                "selected", () -> dialog.get().list().getSelectionModel().getSelectedItem() != null);
        FxTesting.onFx(() -> {
            dialog.get().nameField().setText("Movies");
            dialog.get().save();
        });
        FxTesting.await(
                "renamed",
                () -> this.test.gui.model().categories().getFirst().name().equals("Movies"));
        assertThat(this.test.gui.model().categories()).hasSize(1);

        FxTesting.onFx(() -> {
            dialog.get().list().getSelectionModel().select(0);
            dialog.get().remove();
        });
        FxTesting.await("no category", () -> this.test.gui.model().categories().isEmpty());
        FxTesting.onFx(() -> dialog.get().stage().close());
    }

    @Test
    void sharedFoldersAreListedAndUnshared() throws Exception {
        this.test = TestGui.start(this.dir.resolve("njmule"));
        Path folder = Files.createDirectories(this.dir.resolve("films"));
        this.test.engine.shareDirectory(folder, true).get(10, TimeUnit.SECONDS);
        AtomicReference<SharedFoldersDialog> dialog = new AtomicReference<>();
        FxTesting.onFx(() -> {
            dialog.set(new SharedFoldersDialog(this.test.gui, new Stage()));
            dialog.get().show();
        });
        FxTesting.await("the folder", () -> dialog.get().list().getItems().size() == 1);
        assertThat(FxTesting.onFx(() -> dialog.get().list().getItems().getFirst()))
                .isEqualTo(new SharedDirectory(folder.toAbsolutePath(), true));

        FxTesting.onFx(() -> {
            dialog.get().list().getSelectionModel().select(0);
            dialog.get().unshareSelected();
        });
        FxTesting.await("no folder", () -> dialog.get().list().getItems().isEmpty());
        FxTesting.onFx(() -> dialog.get().stage().close());
    }

    @Test
    void anImportIsCheckedThenTaken() throws Exception {
        this.test = TestGui.start(this.dir.resolve("njmule"));
        Path emule = Files.createDirectories(this.dir.resolve("emule-config"));
        Files.writeString(emule.resolve("preferences.ini"), "[eMule]\r\nNick=FromEmule\r\n");
        AtomicReference<ImportDialog> dialog = new AtomicReference<>();
        FxTesting.onFx(() -> {
            Stage owner = new Stage();
            dialog.set(new ImportDialog(this.test.gui, owner));
            dialog.get().show();
            dialog.get().directoryField().setText(emule.toString());
            dialog.get().run(true);
        });
        FxTesting.await(
                "the check", () -> !dialog.get().reportTable().getItems().isEmpty());
        assertThat(FxTesting.onFx(() -> dialog.get().reportTable().getItems().stream()
                        .filter(i -> i.file().equals("preferences.ini"))
                        .findFirst()
                        .orElseThrow()
                        .outcome()))
                .isEqualTo(ImportReport.Outcome.WOULD_IMPORT);
        assertThat(this.test.engine.preferences().nick()).isNotEqualTo("FromEmule");

        FxTesting.onFx(() -> dialog.get().run(false));
        FxTesting.await(
                "the import", () -> this.test.engine.preferences().nick().equals("FromEmule"));
        FxTesting.onFx(() -> {
            dialog.get().directoryField().setText(this.dir.resolve("none").toString());
            dialog.get().run(true);
            dialog.get().stage().close();
        });
        assertThat(this.test.told).anyMatch(m -> m.startsWith("Not a folder"));
        assertThat(ImportDialog.guessEmuleDirectory()).isNotNull();
    }

    @Test
    void aboutShowsTheVersion() throws Exception {
        this.test = TestGui.start(this.dir.resolve("njmule"));
        FxTesting.onFx(() -> {
            AboutDialog about = new AboutDialog(this.test.gui, new Stage());
            about.show();
            assertThat(about.stage().isShowing()).isTrue();
            about.stage().close();
        });
    }
}
