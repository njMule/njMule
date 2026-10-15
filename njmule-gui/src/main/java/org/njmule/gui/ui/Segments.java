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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.scene.control.Toggle;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;

/** A few choices shown at once as joined buttons, one of which is always chosen. */
public final class Segments<T> {

    private final HBox box = new HBox();
    private final ToggleGroup group = new ToggleGroup();
    private final Map<T, ToggleButton> buttons = new LinkedHashMap<>();
    private final Map<Toggle, T> values = new HashMap<>();
    private final ObjectProperty<T> value = new SimpleObjectProperty<>();

    /** The {@code choices} in order, named by {@code text}; the first is chosen. */
    public Segments(List<T> choices, Function<T, String> text) {
        for (int i = 0; i < choices.size(); i++) {
            T choice = choices.get(i);
            ToggleButton button = Fx.whole(new ToggleButton(text.apply(choice)));
            button.setToggleGroup(this.group);
            button.getStyleClass()
                    .add(i == 0 ? Styles.LEFT_PILL : i == choices.size() - 1 ? Styles.RIGHT_PILL : Styles.CENTER_PILL);
            this.buttons.put(choice, button);
            this.values.put(button, choice);
            this.box.getChildren().add(button);
        }
        this.box.getStyleClass().add("segments");
        // A click on the chosen one leaves it chosen: there is always a choice.
        this.group.selectedToggleProperty().addListener((o, was, now) -> {
            if (now == null) {
                was.setSelected(true);
            } else {
                this.value.set(this.values.get(now));
            }
        });
        this.value.addListener((o, was, now) -> {
            ToggleButton button = this.buttons.get(Objects.requireNonNull(now, "choice"));
            if (button != null) {
                button.setSelected(true);
            }
        });
        this.value.set(choices.getFirst());
    }

    public HBox node() {
        return this.box;
    }

    public ObjectProperty<T> valueProperty() {
        return this.value;
    }

    public T getValue() {
        return this.value.get();
    }

    public void setValue(T choice) {
        this.value.set(choice);
    }

    /** Names {@code choice} anew, as when a count in its name changes. */
    public void rename(T choice, String text) {
        ToggleButton button = this.buttons.get(choice);
        if (button != null) {
            button.setText(text);
        }
    }

    /** The names of the choices, in order. */
    public List<String> names() {
        return this.buttons.values().stream().map(ToggleButton::getText).toList();
    }

    /** The button of {@code choice}, for tests. */
    ToggleButton button(T choice) {
        return this.buttons.get(choice);
    }
}
