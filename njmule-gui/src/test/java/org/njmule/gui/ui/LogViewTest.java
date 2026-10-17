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
package org.njmule.gui.ui;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import org.fxmisc.richtext.StyleClassedTextArea;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.njmule.gui.FxTesting;

/** A log whose lines follow a list and whose text the mouse chooses. */
class LogViewTest {

    private final ObservableList<String> lines = FXCollections.observableArrayList();
    private LogView<String> log;
    private String chosen;

    @BeforeAll
    static void toolkit() throws Exception {
        FxTesting.start();
    }

    @Test
    void shouldShowALineAParagraphWithItsStyle() throws Exception {
        givenTheLog();

        whenTheListHolds("started", "! disk full");

        thenTheTextIs("started\n! disk full");
        thenTheLinesStyled("warning", 1);
    }

    @Test
    void shouldAddTheLinesTheListAddsAtItsEnd() throws Exception {
        givenTheLog();
        givenTheListHolds("one");

        whenTheListAdds("two", "three");

        thenTheTextIs("one\ntwo\nthree");
    }

    @Test
    void shouldDropTheLinesTheListDropsFromItsStart() throws Exception {
        givenTheLog();
        givenTheListHolds("one", "two", "three");

        whenTheListDropsItsFirst(2);

        thenTheTextIs("three");
    }

    @Test
    void shouldShowAnewAListChangedInTheMiddle() throws Exception {
        givenTheLog();
        givenTheListHolds("one", "two", "three");

        whenTheListReplaces(1, "! two again");

        thenTheTextIs("one\n! two again\nthree");
        thenTheLinesStyled("warning", 1);
    }

    @Test
    void shouldLetTextBeChosenAcrossLinesThoughNothingCanBeTyped() throws Exception {
        givenTheLog();
        givenTheListHolds("one", "two");

        whenTextIsChosenFrom(1, 6);

        thenTheTextChosenIs("ne\ntw");
    }

    private void givenTheLog() throws Exception {
        this.log = FxTesting.onFx(() -> {
            LogView<String> view = new LogView<>(LogViewTest::line, true, "Copy", "Select all");
            this.lines.addListener((ListChangeListener<String>) view::changed);
            return view;
        });
    }

    private void givenTheListHolds(String... texts) throws Exception {
        whenTheListHolds(texts);
    }

    private void whenTheListHolds(String... texts) throws Exception {
        FxTesting.onFx(() -> {
            this.lines.setAll(texts);
            return null;
        });
    }

    private void whenTheListAdds(String... texts) throws Exception {
        FxTesting.onFx(() -> this.lines.addAll(texts));
    }

    private void whenTheListDropsItsFirst(int count) throws Exception {
        FxTesting.onFx(() -> {
            this.lines.remove(0, count);
            return null;
        });
    }

    private void whenTheListReplaces(int index, String text) throws Exception {
        FxTesting.onFx(() -> this.lines.set(index, text));
    }

    private void whenTextIsChosenFrom(int start, int end) throws Exception {
        this.chosen = FxTesting.onFx(() -> {
            StyleClassedTextArea area = (StyleClassedTextArea) this.log.node().lookup(".log-area");
            area.selectRange(start, end);
            return area.isEditable() ? "editable" : area.getSelectedText();
        });
    }

    private void thenTheTextIs(String text) throws Exception {
        assertThat(FxTesting.onFx(this.log::text)).isEqualTo(text);
    }

    private void thenTheLinesStyled(String style, long count) throws Exception {
        assertThat(FxTesting.onFx(() -> this.log.count(style))).isEqualTo(count);
    }

    private void thenTheTextChosenIs(String text) {
        assertThat(this.chosen).isEqualTo(text);
    }

    // A line that starts with ! is a warning.
    private static LogView.Line line(String text) {
        boolean warning = text.startsWith("!");
        return new LogView.Line(
                text,
                warning ? List.of("warning") : List.of(),
                warning ? Optional.of(Icons.WARNING) : Optional.empty());
    }
}
