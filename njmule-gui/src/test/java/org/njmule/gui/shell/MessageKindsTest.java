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

import atlantafx.base.controls.Notification;
import atlantafx.base.theme.Styles;
import java.nio.file.Path;
import java.util.List;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.layout.StackPane;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.gui.FxTesting;
import org.njmule.gui.TestGui;

@Timeout(60)
class MessageKindsTest {

    @TempDir
    Path dir;

    private TestGui test;
    private MainWindow window;

    @AfterEach
    void close() {
        if (this.test != null) {
            this.test.close();
        }
    }

    @Test
    void aProblemLooksLikeOneAndASuccessLikeOne() throws Exception {
        this.givenTheWindow();
        this.whenShown(List.of("saved"), List.of("finished"), List.of("refused"));
        this.thenTheMessagesAre("saved", "finished", "refused");
        this.thenTheStyleOf("saved", null);
        this.thenTheStyleOf("finished", Styles.SUCCESS);
        this.thenTheStyleOf("refused", Styles.DANGER);
    }

    @Test
    void theMessagesStackInTheBottomCornerAndTheOldestMakeRoom() throws Exception {
        this.givenTheWindow();
        this.whenShown(List.of("one", "two", "three"), List.of(), List.of("four", "five"));
        this.thenTheMessagesAre("two", "three", "four", "five");
        this.thenTheyStackAt(Pos.BOTTOM_RIGHT);
    }

    private void givenTheWindow() throws Exception {
        this.test = TestGui.start(this.dir.resolve("njmule"));
        this.window = FxTesting.onFx(() -> new MainWindow(this.test.gui));
        this.test.show(this.window.root());
    }

    private void whenShown(List<String> infos, List<String> successes, List<String> problems) throws Exception {
        FxTesting.onFx(() -> {
            infos.forEach(this.window::message);
            successes.forEach(this.window::success);
            problems.forEach(this.window::problem);
        });
    }

    private void thenTheMessagesAre(String... texts) throws Exception {
        assertThat(FxTesting.onFx(() -> this.window.messages().stream()
                        .map(n -> ((Notification) n).getMessage())
                        .toList()))
                .containsExactly(texts);
    }

    private void thenTheStyleOf(String text, String style) throws Exception {
        List<String> classes = FxTesting.onFx(() -> this.window.messages().stream()
                .map(Notification.class::cast)
                .filter(n -> n.getMessage().equals(text))
                .findFirst()
                .orElseThrow()
                .getStyleClass());
        for (String kind : List.of(Styles.SUCCESS, Styles.DANGER)) {
            assertThat(classes.contains(kind)).as(text + " " + kind).isEqualTo(kind.equals(style));
        }
    }

    private void thenTheyStackAt(Pos corner) throws Exception {
        Node stack = FxTesting.onFx(() -> this.window.messages().getFirst().getParent());
        assertThat(FxTesting.onFx(() -> StackPane.getAlignment(stack))).isEqualTo(corner);
    }
}
