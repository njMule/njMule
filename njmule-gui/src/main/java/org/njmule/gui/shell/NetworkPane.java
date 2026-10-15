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
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.material2.Material2MZ;
import org.njmule.gui.ui.Fx;
import org.njmule.gui.ui.Screen;

/**
 * The network: one place for the servers and Kad, which are two ways into the same network, with a switch between them
 * under one title.
 */
final class NetworkPane {

    private final String title;
    private final Map<String, Screen> parts = new LinkedHashMap<>();
    private final Map<String, ToggleButton> switches = new LinkedHashMap<>();
    private final Consumer<String> show;
    private final StackPane shown = new StackPane();
    private VBox view;

    /** {@code parts} in switch order; {@code show} opens one of them by its id, as the rail does. */
    NetworkPane(String title, List<Screen> parts, Consumer<String> show) {
        this.title = title;
        parts.forEach(p -> this.parts.put(p.id(), p));
        this.show = show;
    }

    boolean holds(String id) {
        return this.parts.containsKey(id);
    }

    /** The view with the part {@code id} in it. */
    Node view(String id) {
        if (this.view == null) {
            this.view = this.build();
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
            ToggleButton button = new ToggleButton(part.title(), Fx.icon(part.icon()));
            button.setToggleGroup(group);
            button.getStyleClass()
                    .add(i == 0 ? Styles.LEFT_PILL : i == all.size() - 1 ? Styles.RIGHT_PILL : Styles.CENTER_PILL);
            button.setId("network-" + part.id());
            // The chosen part stays chosen: a click on it shows it again.
            button.setOnAction(e -> {
                button.setSelected(true);
                this.show.accept(part.id());
            });
            Fx.whole(button);
            this.switches.put(part.id(), button);
            segments.getChildren().add(button);
        }
        HBox head = new HBox(16, Fx.title(this.title), segments);
        head.setAlignment(Pos.CENTER_LEFT);
        VBox box = new VBox(head, this.shown);
        box.getStyleClass().add("screen");
        VBox.setVgrow(this.shown, Priority.ALWAYS);
        return box;
    }

    /** The icon the rail shows for the network. */
    static FontIcon icon() {
        return new FontIcon(Material2MZ.PUBLIC);
    }
}
