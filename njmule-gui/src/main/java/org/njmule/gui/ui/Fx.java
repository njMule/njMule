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

import java.util.Comparator;
import java.util.List;
import java.util.function.Function;
import javafx.beans.binding.Bindings;
import javafx.collections.transformation.SortedList;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import org.kordamp.ikonli.Ikon;
import org.kordamp.ikonli.javafx.FontIcon;
import org.njmule.gui.model.LiveList;

/** Small builders shared by the screens. */
public final class Fx {

    private Fx() {}

    public static FontIcon icon(Ikon ikon) {
        return new FontIcon(ikon);
    }

    public static Button button(String text, Ikon ikon, Runnable action) {
        Button button = new Button(text, icon(ikon));
        button.setOnAction(e -> action.run());
        return button;
    }

    /** A button showing only its icon, with the text as tooltip and accessible name. */
    public static Button iconButton(String text, Ikon ikon, Runnable action) {
        Button button = new Button(null, icon(ikon));
        button.setTooltip(new Tooltip(text));
        button.setAccessibleText(text);
        button.setOnAction(e -> action.run());
        return button;
    }

    public static Label title(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("screen-title");
        return label;
    }

    public static Region spacer() {
        Region region = new Region();
        HBox.setHgrow(region, Priority.ALWAYS);
        return region;
    }

    public static HBox toolRow(Node... nodes) {
        HBox row = new HBox(nodes);
        row.getStyleClass().add("tool-row");
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    /** A screen: a title, a tool row and a content that takes the rest. */
    public static VBox screen(String title, HBox tools, Node content) {
        VBox box = new VBox(title(title), tools, content);
        box.getStyleClass().add("screen");
        VBox.setVgrow(content, Priority.ALWAYS);
        return box;
    }

    /** A table over live rows, sortable by any column, with several rows selectable. */
    public static <V> TableView<LiveList.Row<V>> table(LiveList<?, V> rows, String empty) {
        TableView<LiveList.Row<V>> table = new TableView<>();
        SortedList<LiveList.Row<V>> sorted = new SortedList<>(rows.rows());
        sorted.comparatorProperty().bind(table.comparatorProperty());
        table.setItems(sorted);
        table.setPlaceholder(new Label(empty));
        table.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        return table;
    }

    /** A column showing a value of the row, compared as itself. */
    public static <V, T extends Comparable<? super T>> TableColumn<LiveList.Row<V>, T> column(
            String title, double width, Function<V, T> value) {
        return column(title, width, value, String::valueOf);
    }

    /** A column sorted by a value and showing it formatted. */
    public static <V, T extends Comparable<? super T>> TableColumn<LiveList.Row<V>, T> column(
            String title, double width, Function<V, T> value, Function<T, String> text) {
        TableColumn<LiveList.Row<V>, T> column = new TableColumn<>(title);
        column.setPrefWidth(width);
        column.setCellValueFactory(
                c ->
                        Bindings.createObjectBinding(
                                () -> value.apply(c.getValue().get()),
                                c.getValue().valueProperty()));
        column.setComparator(Comparator.nullsFirst(Comparator.naturalOrder()));
        column.setCellFactory(
                c ->
                        new TableCell<>() {
                            @Override
                            protected void updateItem(T item, boolean empty) {
                                super.updateItem(item, empty);
                                this.setText(empty || item == null ? null : text.apply(item));
                            }
                        });
        return column;
    }

    /** The values of the selected rows. */
    public static <V> List<V> selected(TableView<LiveList.Row<V>> table) {
        return table.getSelectionModel().getSelectedItems().stream()
                .map(LiveList.Row::get)
                .toList();
    }
}
