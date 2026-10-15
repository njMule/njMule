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
import javafx.scene.control.Button;
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
    void theFileMenuRenamesCommentsAndPrioritizesOneFile() throws Exception {
        SharedScreen screen = this.screen();
        ContextMenu menu = FxTesting.onFx(() -> screen.table().getContextMenu());

        FxTesting.onFx(() -> {
            screen.table().getSelectionModel().selectAll();
            menu.getOnShowing().handle(null);
            assertThat(this.item(menu, "IDS_RENAME").isDisable()).isTrue();
            assertThat(this.item(menu, "IDS_CMT_ADD").isDisable()).isTrue();
            assertThat(this.item(menu, "IDS_OPENFOLDER").isDisable()).isTrue();
            // Several files: none is renamed, commented or shown in its folder.
            screen.renameSelected();
            screen.commentSelected();
            screen.openFolder();
            this.item(menu, "IDS_DL_LINK2").fire();
        });
        assertThat(FxTesting.onFx(() -> Clipboard.getSystemClipboard().getString()))
                .contains("<a href=\"ed2k://|file|notes.txt|", "<a href=\"ed2k://|file|film.mkv|");

        FxTesting.onFx(() -> {
            this.select(screen, this.notes);
            menu.getOnShowing().handle(null);
            assertThat(this.item(menu, "IDS_RENAME").isDisable()).isFalse();
            FxTesting.answer(ButtonType.OK, pane -> this.type(pane, "  notes.txt "));
            this.item(menu, "IDS_RENAME").fire();
            FxTesting.answer(ButtonType.CANCEL, pane -> this.type(pane, "other.txt"));
            screen.renameSelected();
            FxTesting.answer(ButtonType.OK, pane -> this.type(pane, " letter.txt"));
            screen.renameSelected();
        });
        FxTesting.await("renamed", () -> this.calls.contains("rename notes.txt letter.txt"));
        assertThat(this.calls).as("the same name, or cancelled").containsExactly("rename notes.txt letter.txt");

        FxTesting.onFx(() -> {
            ((Menu) this.item(menu, "IDS_PRIORITY"))
                    .getItems().stream()
                            .filter(i -> i.getText().equals("Release"))
                            .findFirst()
                            .orElseThrow()
                            .fire();
            MenuButton tool = (MenuButton) screen.view().lookup(".menu-button");
            tool.getItems().stream()
                    .filter(i -> i.getText().equals("Very Low"))
                    .findFirst()
                    .orElseThrow()
                    .fire();
            this.item(menu, "IDS_CMT_ADD").fire();
        });
        assertThat(this.calls).contains("priority notes.txt RELEASE", "priority notes.txt VERY_LOW");
        FxTesting.await("the comment window", () -> this.window("Comments: notes.txt") != null);
        assertThat(FxTesting.onFx(() -> screen.view().lookupAll(".label").stream()
                        .map(n -> ((Label) n).getText())
                        .toList()))
                .contains("Number of Shared Files: 2");

        FxTesting.onFx(() -> this.button(screen, "Shared folders").fire());
        FxTesting.await("the shared folders", () -> this.window("Shared folders") != null);
    }

    @Test
    void sharingAFolderAsksFirstAndWarnsOfASensitiveOne() throws Exception {
        SharedScreen screen = this.screen();
        Path folder = this.dir.resolve("films");
        AtomicReference<String> warning = new AtomicReference<>();

        FxTesting.onFx(() -> {
            FxTesting.answer(ButtonBar.ButtonData.OK_DONE, pane -> {
                warning.set(this.warning(pane));
                ((CheckBox) pane.lookup(".check-box")).setSelected(false);
            });
            screen.confirmShare(folder);
        });
        assertThat(this.calls).containsExactly("share " + folder + " false");
        assertThat(warning.get().isEmpty())
                .as("a warning only for a sensitive folder")
                .isEqualTo(!SharedDirectory.isSensitive(folder));

        // The root of a disk is never shared without a warning; here the user steps back.
        Path root = this.dir.toAbsolutePath().getRoot();
        FxTesting.onFx(() -> {
            FxTesting.answer(ButtonBar.ButtonData.CANCEL_CLOSE, pane -> warning.set(this.warning(pane)));
            screen.confirmShare(root);
        });
        assertThat(warning.get()).contains(root.toString(), "personal or system files");
        FxTesting.onFx(() -> {
            FxTesting.answer(ButtonBar.ButtonData.OK_DONE);
            screen.confirmShare(folder);
        });
        assertThat(this.calls).containsExactly("share " + folder + " false", "share " + folder + " true");
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
            this.calls.add("rename " + this.name(args[0]) + " " + args[1]);
            return CompletableFuture.completedFuture(true);
        });
        commands.put("setUploadPriority", args -> {
            this.calls.add("priority " + this.name(args[0]) + " " + args[1]);
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

    private Button button(SharedScreen screen, String text) {
        return screen.view().lookupAll(".button").stream()
                .filter(n -> n instanceof Button b && text.equals(b.getText()))
                .map(Button.class::cast)
                .findFirst()
                .orElseThrow();
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
