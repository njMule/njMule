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

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import javafx.collections.ListChangeListener;
import javafx.scene.Node;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.layout.StackPane;
import org.fxmisc.flowless.VirtualizedScrollPane;
import org.fxmisc.richtext.Caret;
import org.fxmisc.richtext.StyleClassedTextArea;
import org.fxmisc.richtext.util.UndoUtils;
import org.kordamp.ikonli.Ikon;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.material2.Material2AL;
import org.kordamp.ikonli.material2.Material2MZ;

/**
 * A log of lines, each a paragraph with its style classes and an icon beside it if it has one. Its text reads left to
 * right and is chosen freely with the mouse, also across lines, then copied with the system's shortcut or the context
 * menu. New lines show at the bottom, unless some text is chosen.
 */
public final class LogView<T> {

    /** How one line shows: its text, the style classes of the text and of its icon, the icon if any. */
    public record Line(String text, List<String> styles, Optional<Ikon> icon) {
        public Line {
            Objects.requireNonNull(text, "text");
            styles = List.copyOf(styles);
            Objects.requireNonNull(icon, "icon");
        }
    }

    private final StyleClassedTextArea area = new StyleClassedTextArea();
    private final Node node;
    private final Function<? super T, Line> show;
    private final boolean icons;
    // The lines shown, one per paragraph, for their icons.
    private final List<Line> shown = new ArrayList<>();

    /** @param icons whether the lines have a place for an icon, also those that have none */
    public LogView(Function<? super T, Line> show, boolean icons, String copy, String selectAll) {
        this.show = Objects.requireNonNull(show, "show");
        this.icons = icons;
        this.area.getStyleClass().add("log-area");
        this.area.setEditable(false);
        this.area.setWrapText(true);
        this.area.setShowCaret(Caret.CaretVisibility.OFF);
        // Nothing is typed, so there is nothing to undo.
        this.area.setUndoManager(UndoUtils.noOpUndoManager());
        this.area.setParagraphGraphicFactory(this::icon);
        MenuItem copyItem = Fx.menuItem(copy, Material2AL.CONTENT_COPY, this.area::copy);
        ContextMenu menu =
                new ContextMenu(copyItem, Fx.menuItem(selectAll, Material2MZ.SELECT_ALL, this.area::selectAll));
        menu.setOnShowing(e -> copyItem.setDisable(this.area.getSelection().getLength() == 0));
        this.area.setContextMenu(menu);
        this.node = Fx.leftToRight(new VirtualizedScrollPane<>(this.area));
    }

    public Node node() {
        return this.node;
    }

    /** The text shown, a line a paragraph. */
    public String text() {
        return this.area.getText();
    }

    /** Shows {@code lines} in place of what was shown. */
    public void setAll(List<? extends T> lines) {
        this.area.clear();
        this.shown.clear();
        append(lines);
    }

    /**
     * Follows a change of the list shown: lines added at its end and taken from its start; anything else shows anew.
     */
    public void changed(ListChangeListener.Change<? extends T> change) {
        while (change.next()) {
            if (change.wasPermutated() || change.wasUpdated() || change.wasReplaced()) {
                setAll(change.getList());
                return;
            }
            if (change.wasRemoved()) {
                if (change.getFrom() != 0) {
                    setAll(change.getList());
                    return;
                }
                removeFirst(change.getRemovedSize());
            }
            if (change.wasAdded()) {
                if (change.getFrom() != this.shown.size()) {
                    setAll(change.getList());
                    return;
                }
                append(change.getAddedSubList());
            }
        }
    }

    private void append(List<? extends T> lines) {
        if (lines.isEmpty()) {
            return;
        }
        StringBuilder text = new StringBuilder();
        List<Line> added = new ArrayList<>(lines.size());
        for (T item : lines) {
            Line line = this.show.apply(item);
            if (!this.shown.isEmpty() || !added.isEmpty()) {
                text.append('\n');
            }
            text.append(line.text());
            added.add(line);
        }
        // A paragraph a line: the first line fills the empty paragraph an empty area has.
        int first = this.shown.size();
        this.shown.addAll(added);
        this.area.insertText(this.area.getLength(), text.toString());
        for (int i = 0; i < added.size(); i++) {
            this.area.setStyle(first + i, added.get(i).styles());
        }
        if (this.area.getSelection().getLength() == 0) {
            this.area.showParagraphAtBottom(this.shown.size() - 1);
        }
    }

    private void removeFirst(int count) {
        if (count >= this.shown.size()) {
            this.area.clear();
            this.shown.clear();
            return;
        }
        this.area.deleteText(0, this.area.getAbsolutePosition(count, 0));
        this.shown.subList(0, count).clear();
    }

    /** How many lines show with the style class {@code style}. */
    public long count(String style) {
        return this.shown.stream().filter(l -> l.styles().contains(style)).count();
    }

    // Lines without an icon keep the same indent as those with one.
    private Node icon(int paragraph) {
        if (!this.icons) {
            return null;
        }
        Line line = paragraph >= 0 && paragraph < this.shown.size() ? this.shown.get(paragraph) : null;
        FontIcon icon = new FontIcon(
                line == null || line.icon().isEmpty()
                        ? Material2AL.INFO
                        : line.icon().get());
        icon.getStyleClass().add("log-icon");
        if (line != null) {
            icon.getStyleClass().addAll(line.styles());
        }
        icon.setVisible(line != null && line.icon().isPresent());
        StackPane box = new StackPane(icon);
        box.getStyleClass().add("log-icon-box");
        return box;
    }
}
