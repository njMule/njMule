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

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.control.Toggle;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.FlowPane;

/**
 * Rounded chips that narrow a list to one kind of row, each with how many rows it keeps; one is always chosen, and the
 * row wraps when the window is narrow.
 */
public final class FilterChips<T> {

    private final FlowPane box = new FlowPane(6, 6);
    private final ToggleGroup group = new ToggleGroup();
    private final Map<T, ToggleButton> chips = new LinkedHashMap<>();
    private final Map<T, Label> counts = new HashMap<>();
    private final Map<T, String> names = new HashMap<>();
    private final Map<Toggle, T> values = new HashMap<>();
    private final ObjectProperty<T> value = new SimpleObjectProperty<>();

    /** The {@code choices} in order, named by {@code text}; the first is chosen. */
    public FilterChips(List<T> choices, Function<T, String> text) {
        for (T choice : choices) {
            Label count = new Label("0");
            count.getStyleClass().add("chip-count");
            ToggleButton chip = Fx.whole(new ToggleButton(text.apply(choice), count));
            chip.setContentDisplay(ContentDisplay.RIGHT);
            chip.setToggleGroup(this.group);
            chip.getStyleClass().add("filter-chip");
            this.chips.put(choice, chip);
            this.counts.put(choice, count);
            this.names.put(choice, text.apply(choice));
            this.values.put(chip, choice);
            this.box.getChildren().add(chip);
        }
        this.box.getStyleClass().add("filter-chips");
        // A click on the chosen one leaves it chosen: there is always a choice.
        this.group.selectedToggleProperty().addListener((o, was, now) -> {
            if (now == null) {
                was.setSelected(true);
            } else {
                this.value.set(this.values.get(now));
            }
        });
        this.value.addListener((o, was, now) -> {
            ToggleButton chip = this.chips.get(Objects.requireNonNull(now, "choice"));
            if (chip != null) {
                chip.setSelected(true);
            }
        });
        this.value.set(choices.getFirst());
    }

    public FlowPane node() {
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

    /** Tells how many rows {@code choice} keeps; said too, not only shown. */
    public void count(T choice, long count) {
        Label label = this.counts.get(choice);
        if (label != null) {
            label.setText(Long.toString(count));
            this.chips.get(choice).setAccessibleText(this.names.get(choice) + ", " + count);
        }
    }

    /** The chip of {@code choice}, for tests. */
    public ToggleButton chip(T choice) {
        return this.chips.get(choice);
    }
}
