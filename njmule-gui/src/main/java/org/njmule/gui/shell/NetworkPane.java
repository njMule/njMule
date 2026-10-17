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
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.njmule.gui.ui.Fx;
import org.njmule.gui.ui.Screen;

/**
 * The network: the state of both networks and our ports on cards, then one place for the servers, Kad and the log, with
 * a switch between them; the top bar gives the title.
 */
final class NetworkPane {

    private final Map<String, Screen> parts = new LinkedHashMap<>();
    private final Map<String, ToggleButton> switches = new LinkedHashMap<>();
    private final Map<String, Label> counts = new LinkedHashMap<>();
    private final Node cards;
    private final Consumer<String> show;
    private final StackPane shown = new StackPane();
    private VBox view;

    /**
     * {@code cards} above {@code parts} in switch order; {@code show} opens one of them by its id, as the rail does.
     */
    NetworkPane(Node cards, List<Screen> parts, Consumer<String> show) {
        this.cards = cards;
        parts.forEach(p -> this.parts.put(p.id(), p));
        this.show = show;
    }

    boolean holds(String id) {
        return this.parts.containsKey(id);
    }

    /** The view with the part {@code id} in it. */
    Node view(String id) {
        if (this.view == null) {
            this.view = build();
        }
        Screen part = this.parts.get(id);
        this.shown.getChildren().setAll(part.view());
        this.switches.get(id).setSelected(true);
        return this.view;
    }

    private VBox build() {
        ToggleGroup group = new ToggleGroup();
        HBox segments = new HBox();
        segments.getStyleClass().add("network-switch");
        List<Screen> all = List.copyOf(this.parts.values());
        for (int i = 0; i < all.size(); i++) {
            Screen part = all.get(i);
            // The name, then a muted figure once the part tells how many it holds.
            Label count = new Label();
            count.getStyleClass().add("segment-count");
            count.managedProperty().bind(count.textProperty().isNotEmpty());
            count.visibleProperty().bind(count.textProperty().isNotEmpty());
            this.counts.put(part.id(), count);
            HBox graphic = new HBox(6, Fx.icon(part.icon()), new Label(part.title()), count);
            graphic.setAlignment(Pos.CENTER_LEFT);
            ToggleButton button = new ToggleButton(null, graphic);
            button.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
            button.setAccessibleText(part.title());
            button.setToggleGroup(group);
            button.getStyleClass()
                    .add(i == 0 ? Styles.LEFT_PILL : i == all.size() - 1 ? Styles.RIGHT_PILL : Styles.CENTER_PILL);
            button.setId("part-" + part.id());
            // The chosen part stays chosen: a click on it shows it again.
            button.setOnAction(e -> {
                button.setSelected(true);
                this.show.accept(part.id());
            });
            Fx.whole(button);
            this.switches.put(part.id(), button);
            segments.getChildren().add(button);
        }
        VBox box = new VBox(this.cards, segments, this.shown);
        box.getStyleClass().add("screen");
        VBox.setVgrow(this.shown, Priority.ALWAYS);
        return box;
    }

    /** Tells how many the part {@code id} holds, after its name on the switch; a negative count says nothing. */
    void count(String id, String title, long count) {
        Label figure = this.counts.get(id);
        if (figure != null) {
            figure.setText(count < 0 ? "" : Long.toString(count));
            this.switches.get(id).setAccessibleText(count < 0 ? title : title + ", " + count);
        }
    }

    /** The names on the switch with their figures, for tests. */
    List<String> names() {
        return this.parts.keySet().stream()
                .map(id -> this.parts.get(id).title()
                        + (this.counts.get(id).getText().isEmpty()
                                ? ""
                                : " " + this.counts.get(id).getText()))
                .toList();
    }
}
