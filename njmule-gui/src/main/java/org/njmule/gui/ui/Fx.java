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

import atlantafx.base.theme.Styles;
import java.text.Bidi;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import javafx.beans.binding.Bindings;
import javafx.beans.value.ObservableValue;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.collections.transformation.SortedList;
import javafx.geometry.NodeOrientation;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBoxBase;
import javafx.scene.control.Label;
import javafx.scene.control.Labeled;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;
import org.kordamp.ikonli.Ikon;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.material2.Material2AL;
import org.kordamp.ikonli.material2.Material2MZ;
import org.njmule.gui.model.LiveList;

/** Small builders shared by the screens. */
public final class Fx {

    // Icons that point the way text runs: a right-to-left layout shows them mirrored.
    private static final Set<Ikon> DIRECTIONAL = Set.of(
            Material2AL.CLEAR_ALL,
            Material2AL.EXIT_TO_APP,
            Material2AL.INPUT,
            Material2AL.LOGIN,
            Material2AL.LOW_PRIORITY,
            Material2MZ.PLAYLIST_ADD,
            Material2MZ.SEND);

    private Fx() {}

    public static FontIcon icon(Ikon ikon) {
        FontIcon icon = new FontIcon(ikon);
        if (DIRECTIONAL.contains(ikon)) {
            icon.scaleXProperty()
                    .bind(icon.effectiveNodeOrientationProperty()
                            .map(o -> o == NodeOrientation.RIGHT_TO_LEFT ? -1.0 : 1.0));
        }
        return icon;
    }

    /**
     * Keeps {@code node} left to right in every language, for what reads only so: paths, links, hashes, addresses,
     * numbers and log lines. A text keeps to the side where the layout around it starts.
     */
    public static <T extends Node> T leftToRight(T node) {
        node.setNodeOrientation(NodeOrientation.LEFT_TO_RIGHT);
        if (node instanceof Labeled labeled) {
            startWithTheLayout(labeled);
        }
        return node;
    }

    /** As {@link #leftToRight(Node)}, for the cells of {@code column}; call it after setting their factory. */
    public static <S, T> TableColumn<S, T> leftToRight(TableColumn<S, T> column) {
        var cells = column.getCellFactory();
        column.setCellFactory(c -> leftToRight(cells.call(c)));
        return column;
    }

    /**
     * Lays the cells of {@code column} out in the direction of their text's first letter, for names, which may be in
     * any language: left to right in every language, unless the name starts with a right-to-left letter. Call it after
     * setting their factory.
     */
    public static <S, T> TableColumn<S, T> ownDirection(TableColumn<S, T> column) {
        var cells = column.getCellFactory();
        column.setCellFactory(c -> ownDirection(cells.call(c)));
        return column;
    }

    /** As {@link #ownDirection(TableColumn)}, for one label or cell. */
    public static <T extends Labeled> T ownDirection(T labeled) {
        labeled.nodeOrientationProperty()
                .bind(labeled.textProperty().map(Fx::directionOf).orElse(NodeOrientation.INHERIT));
        startWithTheLayout(labeled);
        return labeled;
    }

    // Left to right unless the first letter is written right to left; then as the layout around, which in a
    // right-to-left interface is right to left.
    private static NodeOrientation directionOf(String text) {
        return !text.isEmpty() && new Bidi(text, Bidi.DIRECTION_DEFAULT_LEFT_TO_RIGHT).baseIsLeftToRight()
                ? NodeOrientation.LEFT_TO_RIGHT
                : NodeOrientation.INHERIT;
    }

    // A text in another direction than the layout around it keeps to the side where that layout starts.
    private static void startWithTheLayout(Labeled labeled) {
        labeled.alignmentProperty()
                .bind(labeled.parentProperty()
                        .flatMap(Node::effectiveNodeOrientationProperty)
                        .flatMap(around -> labeled.effectiveNodeOrientationProperty()
                                .map(own -> own == around ? Pos.CENTER_LEFT : Pos.CENTER_RIGHT))
                        .orElse(Pos.CENTER_LEFT));
    }

    /** A column of numbers, on the right of their cells and left to right in every language. */
    public static <S, T> TableColumn<S, T> numeric(TableColumn<S, T> column) {
        column.getStyleClass().add("numeric");
        var cells = column.getCellFactory();
        column.setCellFactory(c -> {
            TableCell<S, T> cell = cells.call(c);
            cell.setNodeOrientation(NodeOrientation.LEFT_TO_RIGHT);
            return cell;
        });
        return column;
    }

    public static Button button(String text, Ikon ikon, Runnable action) {
        Button button = new Button(text, icon(ikon));
        button.setOnAction(e -> action.run());
        return whole(button);
    }

    /** A dot of a category's tint, an outline for none; the category's name goes beside it. */
    public static Circle categoryDot(int color) {
        Circle dot = new Circle(5);
        dot.getStyleClass().add("category-dot");
        if (color > 0) {
            dot.getStyleClass().add("cat-" + color);
        }
        return dot;
    }

    /** A button showing only its icon, with the text as tooltip and accessible name. */
    public static MenuItem menuItem(String text, Ikon ikon, Runnable action) {
        MenuItem item = new MenuItem(text, icon(ikon));
        item.setOnAction(e -> action.run());
        return item;
    }

    public static Button iconButton(String text, Ikon ikon, Runnable action) {
        Button button = new Button(null, icon(ikon));
        button.setTooltip(new Tooltip(text));
        button.setAccessibleText(text);
        button.setOnAction(e -> action.run());
        return whole(button);
    }

    /**
     * Gives {@code alert} two answers named by their outcome, {@code go} and {@code stay}, and returns the type of
     * {@code go}. A {@code risky} go is marked as dangerous and never the default: Enter keeps things as they are.
     */
    public static ButtonType answers(Alert alert, String go, String stay, boolean risky) {
        ButtonType yes = new ButtonType(go, ButtonBar.ButtonData.OK_DONE);
        ButtonType no = new ButtonType(stay, ButtonBar.ButtonData.CANCEL_CLOSE);
        alert.getButtonTypes().setAll(yes, no);
        if (risky) {
            Button goButton = (Button) alert.getDialogPane().lookupButton(yes);
            goButton.setDefaultButton(false);
            goButton.getStyleClass().add(Styles.DANGER);
            ((Button) alert.getDialogPane().lookupButton(no)).setDefaultButton(true);
        }
        return yes;
    }

    /** Marks {@code button} as the main action of its screen, shown in the brand colour. */
    public static Button primary(Button button) {
        button.getStyleClass().add("accent");
        return button;
    }

    /** Keeps {@code control} at least as wide as its content, so a narrow window never cuts its text. */
    public static <T extends Region> T whole(T control) {
        control.setMinWidth(Region.USE_PREF_SIZE);
        return control;
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
        for (Node node : nodes) {
            if (node instanceof ButtonBase || node instanceof ComboBoxBase<?>) {
                whole((Region) node);
            }
        }
        HBox row = new HBox(nodes);
        row.getStyleClass().add("tool-row");
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    /** What an empty list shows: an icon, what is missing and how to get it, and the buttons that do it. */
    public static VBox emptyState(Ikon ikon, String text, Node... actions) {
        FontIcon icon = icon(ikon);
        icon.getStyleClass().add("empty-icon");
        Label words = new Label(text);
        words.setWrapText(true);
        words.setMaxWidth(420);
        words.getStyleClass().add("empty-text");
        HBox buttons = new HBox(8, actions);
        buttons.setAlignment(Pos.CENTER);
        VBox box = actions.length == 0 ? new VBox(10, icon, words) : new VBox(10, icon, words, buttons);
        box.setAlignment(Pos.CENTER);
        box.getStyleClass().add("empty-state");
        return box;
    }

    /** A screen: a title, a tool row and a content that takes the rest. */
    public static VBox screen(String title, HBox tools, Node content) {
        VBox box = new VBox(title(title), tools, content);
        box.getStyleClass().add("screen");
        VBox.setVgrow(content, Priority.ALWAYS);
        return box;
    }

    /** A screen shown inside another, which gives the title and the margins: a tool row and a content. */
    public static VBox part(HBox tools, Node content) {
        VBox box = new VBox(tools, content);
        box.getStyleClass().add("screen-part");
        VBox.setVgrow(content, Priority.ALWAYS);
        return box;
    }

    /** A table over live rows, sortable by any column, with several rows selectable. */
    public static <V> TableView<LiveList.Row<V>> table(LiveList<?, V> rows, String empty) {
        return table(rows.rows(), empty);
    }

    /** As above, with only the rows {@code shown} takes; a changed value can show or hide its row. */
    public static <V> TableView<LiveList.Row<V>> table(
            LiveList<?, V> rows, String empty, ObservableValue<Predicate<V>> shown) {
        FilteredList<LiveList.Row<V>> filtered = new FilteredList<>(rows.rows());
        filtered.predicateProperty()
                .bind(Bindings.createObjectBinding(
                        () -> {
                            Predicate<V> test = shown.getValue();
                            return row -> test.test(row.get());
                        },
                        shown));
        return table(filtered, empty);
    }

    private static <V> TableView<LiveList.Row<V>> table(ObservableList<LiveList.Row<V>> rows, String empty) {
        TableView<LiveList.Row<V>> table = new TableView<>();
        // Rows two thirds as tall as the theme's: a list of transfers is read many rows at a time.
        table.getStyleClass().add(Styles.DENSE);
        SortedList<LiveList.Row<V>> sorted = new SortedList<>(rows);
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
        column.setCellValueFactory(c -> Bindings.createObjectBinding(
                () -> value.apply(c.getValue().get()), c.getValue().valueProperty()));
        column.setComparator(Comparator.nullsFirst(Comparator.naturalOrder()));
        column.setCellFactory(c -> new TableCell<>() {
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
