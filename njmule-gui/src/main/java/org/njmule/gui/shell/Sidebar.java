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

import atlantafx.base.theme.Styles;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import javafx.beans.binding.Bindings;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.value.ObservableDoubleValue;
import javafx.css.PseudoClass;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;
import org.njmule.gui.infra.GuiSettings;
import org.njmule.gui.ui.Fx;
import org.njmule.gui.ui.Gui;
import org.njmule.gui.ui.Icon;
import org.njmule.gui.ui.Icons;

/**
 * The places down the side of the window: the logo and the name, a button for each place with what waits there, and the
 * options at the bottom. It folds to its icons with Ctrl+B, and by itself in a narrow window.
 */
final class Sidebar {

    /** A place of the sidebar and the keys that open it. */
    record Place(String id, String title, Icons icon, String shortcut) {}

    /** Below this width of the window the sidebar shows only its icons. */
    static final double FOLD_BELOW = 1000;

    private static final PseudoClass COLLAPSED_STATE = PseudoClass.getPseudoClass("collapsed");
    private static final int MAX_BADGE = 99;
    private static final double LOGO = 28;

    private final VBox box = new VBox();
    private final Map<String, ToggleButton> buttons = new LinkedHashMap<>();
    private final Map<String, Mark> marks = new LinkedHashMap<>();
    private final BooleanProperty collapsed = new SimpleBooleanProperty();
    private final BooleanProperty narrow = new SimpleBooleanProperty();
    private final DoubleProperty narrowBelow = new SimpleDoubleProperty(FOLD_BELOW);
    // Narrow or wide whatever the width, while the window is measured; null otherwise.
    private final ObjectProperty<Boolean> forced = new SimpleObjectProperty<>();
    private GuiSettings settings;

    /**
     * {@code groups} of places, apart by a line, the last one at the bottom; {@code open} opens a place by its id and
     * {@code marked} names the places that show a count, and {@code network} the one that shows the network's state.
     */
    Sidebar(Gui gui, List<List<Place>> groups, List<String> marked, String network, Consumer<String> open) {
        this.box.getStyleClass().add("nav-rail");
        ImageView logo = Fx.leftToRight(new ImageView());
        var icon = Sidebar.class.getResource("/org/njmule/gui/icons/njmule-64.png");
        if (icon != null) {
            logo.setImage(new Image(icon.toExternalForm(), LOGO * 2, LOGO * 2, true, true));
        }
        logo.setFitWidth(LOGO);
        logo.setFitHeight(LOGO);
        // The name is a mark, written as it is in every language.
        Label name = Fx.leftToRight(new Label("njMule"));
        name.getStyleClass().addAll("wordmark", "rail-wordmark");
        name.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(name, Priority.ALWAYS);
        String keys = new KeyCodeCombination(KeyCode.B, KeyCombination.SHORTCUT_DOWN).getDisplayText();
        Button collapse = Fx.iconButton(gui.t("nj.sidebar.collapse") + "  " + keys, Icons.SIDEBAR, this::toggle);
        collapse.setId("sidebar-collapse");
        collapse.getStyleClass().addAll(Styles.FLAT, "rail-fold");
        HBox head = new HBox(10, logo, name, collapse);
        head.getStyleClass().add("rail-head");
        hideWhenCollapsed(name);
        hideWhenCollapsed(collapse);
        this.box.getChildren().add(head);
        ToggleGroup group = new ToggleGroup();
        for (int g = 0; g < groups.size(); g++) {
            if (g == groups.size() - 1) {
                // The last group keeps to the bottom, apart from the places of every day.
                Region grow = new Region();
                VBox.setVgrow(grow, Priority.ALWAYS);
                this.box.getChildren().add(grow);
            } else if (g > 0) {
                Region line = new Region();
                line.getStyleClass().add("rail-separator");
                VBox.setMargin(line, new Insets(6, 8, 6, 8));
                this.box.getChildren().add(line);
            }
            for (Place place : groups.get(g)) {
                Mark mark = place.id().equals(network)
                        ? Mark.dot()
                        : marked.contains(place.id()) ? Mark.count() : Mark.none();
                this.box.getChildren().add(item(place, mark, group, open));
            }
        }
        Button expand = Fx.iconButton(gui.t("nj.sidebar.expand") + "  " + keys, Icons.SIDEBAR, this::toggle);
        expand.setId("sidebar-expand");
        expand.getStyleClass().addAll(Styles.FLAT, "rail-fold");
        expand.visibleProperty().bind(this.collapsed);
        expand.managedProperty().bind(this.collapsed);
        this.box.getChildren().add(expand);
        this.collapsed.addListener((o, was, now) -> showCollapsed(now));
        showCollapsed(false);
    }

    private Node item(Place place, Mark mark, ToggleGroup group, Consumer<String> open) {
        StackPane graphic = new StackPane(new Icon(place.icon()), mark.corner);
        graphic.getStyleClass().add("rail-graphic");
        StackPane.setAlignment(mark.corner, Pos.TOP_RIGHT);
        ToggleButton button = new ToggleButton(place.title(), graphic);
        // The tooltip tells the shortcut of the place, as menus show theirs; folded, it also names the place.
        button.setTooltip(new Tooltip(place.title() + "  " + place.shortcut()));
        button.setToggleGroup(group);
        button.setId("rail-" + place.id());
        button.setMaxWidth(Double.MAX_VALUE);
        button.setAlignment(Pos.CENTER_LEFT);
        // A button stays selected while its place shows.
        button.setOnAction(e -> open.accept(place.id()));
        // The count or the dot at the end of the row, where the text leaves it room; clicks go through it.
        mark.end.setMouseTransparent(true);
        if (mark.end.getStyleClass().contains("rail-badge")
                || mark.end.getStyleClass().contains("network-dot")) {
            button.getStyleClass().add("marked");
        }
        StackPane row = new StackPane(button, mark.end);
        row.getStyleClass().add("rail-item");
        StackPane.setAlignment(mark.end, Pos.CENTER_RIGHT);
        StackPane.setMargin(mark.end, new Insets(0, 10, 0, 10));
        this.buttons.put(place.id(), button);
        this.marks.put(place.id(), mark);
        return row;
    }

    Region view() {
        return this.box;
    }

    /** Selects the button of {@code place}. */
    void select(String place) {
        ToggleButton button = this.buttons.get(place);
        if (button != null) {
            button.setSelected(true);
        }
    }

    ToggleButton button(String place) {
        return this.buttons.get(place);
    }

    /** Shows {@code value} on {@code place}, nothing at zero; {@code help} says it to a screen reader. */
    void count(String place, long value, String help) {
        Mark mark = this.marks.get(place);
        if (mark.badge != null) {
            mark.badge.setText(value > MAX_BADGE ? MAX_BADGE + "+" : Long.toString(value));
        }
        mark.on = value > 0;
        showMark(mark);
        this.buttons.get(place).setAccessibleHelp(value > 0 ? help : null);
    }

    /** Whether a network is up ({@code up}) or being tried ({@code trying}), with the state in words. */
    void network(String place, boolean up, boolean trying, String state, String shortcut) {
        Mark mark = this.marks.get(place);
        for (Node dot : List.of(mark.end, mark.corner)) {
            dot.getStyleClass().removeAll("connected", "connecting");
            if (up) {
                dot.getStyleClass().add("connected");
            } else if (trying) {
                dot.getStyleClass().add("connecting");
            }
        }
        ToggleButton button = this.buttons.get(place);
        button.setTooltip(new Tooltip(state + "\n" + shortcut));
        button.setAccessibleHelp(state);
    }

    /**
     * Folds the sidebar in a window narrower than {@link #FOLD_BELOW} and opens it again as the user left it once
     * wider; Ctrl+B folds or opens it, remembered in {@code settings} while the window is wide.
     */
    void follow(ObservableDoubleValue width, GuiSettings settings) {
        this.settings = settings;
        this.narrow.bind(Bindings.createBooleanBinding(
                () -> this.forced.get() != null ? this.forced.get() : width.get() < this.narrowBelow.get(),
                width,
                this.narrowBelow,
                this.forced));
        this.narrow.addListener((o, was, now) -> refold());
        refold();
    }

    /** Folds or opens the sidebar. */
    void toggle() {
        this.collapsed.set(!this.collapsed.get());
        if (this.settings != null && !this.narrow.get()) {
            this.settings.setSidebarCollapsed(this.collapsed.get());
        }
    }

    ReadOnlyBooleanProperty collapsedProperty() {
        return this.collapsed;
    }

    /** Whether the window is narrow: the top bar keeps to its essentials too. */
    ReadOnlyBooleanProperty narrowProperty() {
        return this.narrow;
    }

    /** The width under which the sidebar folds by itself: never less than {@link #FOLD_BELOW}. */
    void foldBelow(double width) {
        this.narrowBelow.set(Math.max(FOLD_BELOW, width));
    }

    /** Folds the sidebar or opens it again as the window's width and the user's choice say. */
    void refold() {
        this.collapsed.set(this.narrow.get() || (this.settings != null && this.settings.sidebarCollapsed()));
    }

    /**
     * Lays the window out narrow ({@code true}) or wide ({@code false}) whatever its width, folded or open, to measure
     * it; {@code null} lets the width decide again.
     */
    void measureAs(Boolean narrow) {
        this.forced.set(narrow);
        if (narrow == null) {
            refold();
        } else {
            this.collapsed.set(narrow);
        }
    }

    private void showCollapsed(boolean value) {
        this.box.pseudoClassStateChanged(COLLAPSED_STATE, value);
        for (ToggleButton button : this.buttons.values()) {
            button.setContentDisplay(value ? ContentDisplay.GRAPHIC_ONLY : ContentDisplay.LEFT);
        }
        this.marks.values().forEach(this::showMark);
    }

    // At the end of the row while open; folded, at the corner of the icon, a count as a mere dot.
    private void showMark(Mark mark) {
        boolean folded = this.collapsed.get();
        mark.end.setVisible(mark.on && !folded);
        mark.corner.setVisible(mark.on && folded);
    }

    private void hideWhenCollapsed(Node node) {
        node.visibleProperty().bind(this.collapsed.not());
        node.managedProperty().bind(this.collapsed.not());
    }

    /** What a place shows beside its name: a count, the network's dot, or nothing. */
    private static final class Mark {
        private final Node end;
        private final Node corner;
        private final Label badge;
        private boolean on;

        private Mark(Node end, Node corner, Label badge, boolean on) {
            this.end = end;
            this.corner = corner;
            this.badge = badge;
            this.on = on;
        }

        static Mark count() {
            Label badge = new Label();
            badge.getStyleClass().add("rail-badge");
            Region pip = new Region();
            pip.getStyleClass().add("rail-pip");
            return new Mark(badge, pip, badge, false);
        }

        static Mark dot() {
            Circle end = new Circle(4);
            end.getStyleClass().add("network-dot");
            Circle corner = new Circle(4);
            corner.getStyleClass().add("network-dot");
            return new Mark(end, corner, null, true);
        }

        static Mark none() {
            Region end = new Region();
            Region corner = new Region();
            return new Mark(end, corner, null, false);
        }
    }
}
