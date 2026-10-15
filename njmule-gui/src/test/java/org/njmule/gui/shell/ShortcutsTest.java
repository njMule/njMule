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
package org.njmule.gui.shell;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.input.KeyCode;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.gui.FxTesting;
import org.njmule.gui.TestGui;

@Timeout(60)
class ShortcutsTest {

    @TempDir
    Path dir;

    private TestGui test;
    private MainWindow window;

    @AfterEach
    void close() throws Exception {
        FxTesting.onFx(() -> List.copyOf(Window.getWindows()).stream()
                .filter(w -> w instanceof Stage s && "Keyboard shortcuts".equals(s.getTitle()))
                .forEach(Window::hide));
        if (this.test != null) {
            this.test.close();
        }
    }

    @Test
    void f1ListsTheShortcutsWithWhatTheyDo() throws Exception {
        givenTheWindow();
        whenF1IsPressed();
        thenTheShortcutsShow(MainWindow.shortcut(0), "Transfers", MainWindow.shortcut(4), "Network", "F1");
    }

    @Test
    void eachPlaceOfTheRailTellsItsShortcut() throws Exception {
        givenTheWindow();
        thenTheTooltipOf("Search", "Search  " + MainWindow.shortcut(1));
    }

    private void givenTheWindow() throws Exception {
        this.test = TestGui.start(this.dir.resolve("njmule"));
        this.window = FxTesting.onFx(() -> new MainWindow(this.test.gui));
        this.test.show(this.window.root());
    }

    private void whenF1IsPressed() throws Exception {
        FxTesting.onFx(() -> this.window.root().fireEvent(FxTesting.key(KeyCode.F1, false)));
    }

    private void thenTheShortcutsShow(String... texts) throws Exception {
        Optional<Window> shown = FxTesting.onFx(() -> Window.getWindows().stream()
                .filter(w -> w instanceof Stage s && s.isShowing() && "Keyboard shortcuts".equals(s.getTitle()))
                .findFirst());
        assertThat(shown).isPresent();
        List<String> labels = FxTesting.onFx(() -> shown.get().getScene().getRoot().lookupAll(".label").stream()
                .map(n -> ((Label) n).getText())
                .toList());
        assertThat(labels).contains(texts);
    }

    private void thenTheTooltipOf(String place, String text) throws Exception {
        assertThat(FxTesting.onFx(() -> this.window.root().lookupAll(".nav-rail .toggle-button").stream()
                        .map(ToggleButton.class::cast)
                        .filter(b -> place.equals(b.getText()))
                        .findFirst()
                        .orElseThrow()
                        .getTooltip()
                        .getText()))
                .isEqualTo(text);
    }
}
