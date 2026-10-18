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
import atlantafx.base.theme.Tweaks;
import java.text.Bidi;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.BooleanBinding;
import javafx.beans.value.ObservableValue;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.collections.transformation.SortedList;
import javafx.geometry.NodeOrientation;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ComboBoxBase;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.Labeled;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.Separator;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;
import org.njmule.core.api.FileKind;
import org.njmule.gui.model.LiveList;

/** Small builders shared by the screens. */
public final class Fx {

    private Fx() {}

    /** An icon in the colour of the text beside it; a directional one is mirrored in a right-to-left layout. */
    public static Icon icon(Icons glyph) {
        return new Icon(glyph);
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

    /**
     * Runs {@code action} when Enter is pressed in an editable box whose list is closed. The box keeps the focus and
     * its skin takes Enter to commit the text, so the action of the editor never comes.
     */
    public static void onEnter(ComboBox<?> box, Runnable action) {
        box.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.ENTER && !box.isShowing()) {
                e.consume();
                action.run();
            }
        });
    }

    /**
     * Shows the hint of an editable box's editor, set by an input, as the box's own tooltip, which the box's skin gives
     * its editor; it is left out while the box's list shows, which it would cover.
     */
    public static void hintOnBox(ComboBox<?> box) {
        Tooltip hint = box.getEditor().getTooltip();
        box.setTooltip(hint);
        box.showingProperty().addListener((o, was, showing) -> {
            if (showing && hint != null) {
                hint.hide();
            }
            box.setTooltip(showing ? null : hint);
        });
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

    public static Button button(String text, Icons glyph, Runnable action) {
        Button button = new Button(text, icon(glyph));
        button.setOnAction(e -> action.run());
        return whole(button);
    }

    /**
     * A state in words on a pastel pill of its {@code tone} (one of {@link ThinBar#TONES}), with an icon of its own:
     * the colour is never the only cue.
     */
    public static Label chip(String text, Icons glyph, String tone) {
        Label chip = new Label();
        chip.getStyleClass().add("state-chip");
        chip(chip, text, glyph, tone);
        return chip;
    }

    /** Shows {@code text}, {@code glyph} and {@code tone} on {@code chip}. */
    public static void chip(Label chip, String text, Icons glyph, String tone) {
        chip.setText(text);
        if (chip.getGraphic() instanceof Icon icon) {
            icon.setGlyph(glyph);
        } else {
            chip.setGraphic(icon(glyph));
        }
        chip.getStyleClass().removeAll(ThinBar.TONES);
        chip.getStyleClass().add(tone);
    }

    /** The icon of what a file holds. */
    public static Icons fileIcon(FileKind kind) {
        return switch (kind) {
            case VIDEO -> Icons.FILE_VIDEO;
            case AUDIO -> Icons.FILE_AUDIO;
            case IMAGE -> Icons.FILE_IMAGE;
            case DOCUMENT -> Icons.FILE_DOCUMENT;
            case ARCHIVE -> Icons.FILE_ARCHIVE;
            case DISC_IMAGE -> Icons.FILE_DISC;
            case PROGRAM -> Icons.FILE_APP;
            case OTHER -> Icons.FILE;
        };
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
    public static MenuItem menuItem(String text, Icons glyph, Runnable action) {
        MenuItem item = new MenuItem(text, icon(glyph));
        item.setOnAction(e -> action.run());
        return item;
    }

    public static Button iconButton(String text, Icons glyph, Runnable action) {
        Button button = new Button(null, icon(glyph));
        button.setTooltip(new Tooltip(text));
        button.setAccessibleText(text);
        button.setOnAction(e -> action.run());
        return whole(button);
    }

    /**
     * Gives {@code dialog} two answers named by their outcome, {@code go} and {@code stay}, and returns the type of
     * {@code go}. A {@code risky} go is marked as dangerous and never the default: Enter keeps things as they are, and
     * the safe answer is ringed rather than filled.
     */
    public static ButtonType answers(Dialog<?> dialog, String go, String stay, boolean risky) {
        ButtonType yes = new ButtonType(go, ButtonBar.ButtonData.OK_DONE);
        ButtonType no = new ButtonType(stay, ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().setAll(yes, no);
        if (risky) {
            Button goButton = (Button) dialog.getDialogPane().lookupButton(yes);
            goButton.setDefaultButton(false);
            goButton.getStyleClass().addAll(Styles.DANGER, Styles.BUTTON_OUTLINED);
            Button stayButton = (Button) dialog.getDialogPane().lookupButton(no);
            stayButton.setDefaultButton(true);
            stayButton.getStyleClass().add("safe");
        }
        return yes;
    }

    /** The logo at {@code size} pixels, from a picture twice as large so that it stays sharp; never mirrored. */
    public static ImageView logo(double size) {
        ImageView logo = leftToRight(new ImageView());
        var picture = Fx.class.getResource("/org/njmule/gui/icons/njmule-" + (size > 32 ? 128 : 64) + ".png");
        if (picture != null) {
            logo.setImage(new Image(picture.toExternalForm(), size * 2, size * 2, true, true));
        }
        logo.setFitWidth(size);
        logo.setFitHeight(size);
        return logo;
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

    public static Region spacer() {
        Region region = new Region();
        HBox.setHgrow(region, Priority.ALWAYS);
        return region;
    }

    /** A row of tools; in a narrow window a button with an icon shows only the icon, its text as tooltip. */
    public static HBox toolRow(Node... nodes) {
        for (Node node : nodes) {
            if (node instanceof ButtonBase || node instanceof ComboBoxBase<?>) {
                whole((Region) node);
            }
            if (node instanceof ButtonBase button
                    && button.getGraphic() != null
                    && button.getText() != null
                    && !button.getText().isEmpty()) {
                button.getStyleClass().add("tool-button");
                if (button.getTooltip() == null) {
                    button.setTooltip(new Tooltip(button.getText()));
                }
            }
        }
        HBox row = new HBox(nodes);
        row.getStyleClass().add("tool-row");
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    /** A thin line between groups of a tool row: what acts on the selection apart from what acts on the list. */
    public static Separator toolGap() {
        Separator gap = new Separator(Orientation.VERTICAL);
        gap.getStyleClass().add("tool-gap");
        return gap;
    }

    /** What an empty list shows: an icon, what is missing and how to get it, and the buttons that do it. */
    public static VBox emptyState(Icons glyph, String text, Node... actions) {
        Icon icon = icon(glyph);
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

    /** A screen under the top bar, which gives its title: a tool row and a content that takes the rest. */
    public static VBox screen(HBox tools, Node content) {
        VBox box = new VBox(tools, content);
        box.getStyleClass().add("screen");
        VBox.setVgrow(content, Priority.ALWAYS);
        return box;
    }

    /** A screen under the top bar with no tools of its own: a content that takes it all. */
    public static VBox screen(Node content) {
        VBox box = new VBox(content);
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

    /**
     * {@code content} filling the room it gets and scrolling only where the window is too short for it, as a small
     * window may be.
     */
    public static ScrollPane scrollWhenShort(Region content) {
        ScrollPane scroll = new VerticalScroll(content);
        scroll.setFitToHeight(true);
        scroll.getStyleClass().add(Tweaks.EDGE_TO_EDGE);
        return scroll;
    }

    /** A table over live rows, sortable by any column, with several rows selectable. */
    public static <V> TableView<LiveList.Row<V>> table(LiveList<?, V> rows, String empty) {
        return table(rows.rows(), empty);
    }

    /** As above, with only the rows {@code shown} takes; a changed value can show or hide its row. */
    public static <V> TableView<LiveList.Row<V>> table(
            LiveList<?, V> rows, String empty, ObservableValue<Predicate<V>> shown) {
        return table(rows.rows(), empty, shown.map(test -> (Predicate<LiveList.Row<V>>) row -> test.test(row.get())));
    }

    /** A table over {@code rows}, with only the rows {@code shown} takes; a changed value can show or hide its row. */
    public static <V> TableView<LiveList.Row<V>> table(
            ObservableList<LiveList.Row<V>> rows, String empty, ObservableValue<Predicate<LiveList.Row<V>>> shown) {
        FilteredList<LiveList.Row<V>> filtered = new FilteredList<>(rows);
        filtered.predicateProperty().bind(shown);
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
                setText(empty || item == null ? null : text.apply(item));
            }
        });
        return column;
    }

    /** Disables {@code controls}, which act on the selected rows, while {@code table} has none selected. */
    public static void needSelection(TableView<?> table, Node... controls) {
        BooleanBinding none = Bindings.isEmpty(table.getSelectionModel().getSelectedItems());
        for (Node control : controls) {
            control.disableProperty().bind(none);
        }
    }

    /** The values of the selected rows. */
    public static <V> List<V> selected(TableView<LiveList.Row<V>> table) {
        return table.getSelectionModel().getSelectedItems().stream()
                .map(LiveList.Row::get)
                .toList();
    }
}
