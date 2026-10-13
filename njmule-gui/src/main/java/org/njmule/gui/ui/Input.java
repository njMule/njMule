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
import java.util.List;
import java.util.function.Predicate;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.BooleanBinding;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyBooleanWrapper;
import javafx.beans.value.ObservableValue;
import javafx.scene.control.TextField;
import javafx.scene.control.TextFormatter;
import javafx.scene.control.Tooltip;

/**
 * A text field that holds one kind of value. Typing and pasting keep only the characters the value can have; while the
 * text is not a valid value the field shows the danger state, and its tooltip always says what is expected. Built on
 * JavaFX's {@link TextFormatter}. FX thread only.
 *
 * @param <T> the value
 */
public final class Input<T> {

    /**
     * Reads the value of a text, stripped unless told otherwise; throws {@link IllegalArgumentException} when it holds
     * none.
     */
    @FunctionalInterface
    public interface Parser<T> {
        T parse(String text);
    }

    private final TextField field;
    private final String name;
    private final String hint;
    private final Parser<T> parser;
    private final ReadOnlyBooleanWrapper valid = new ReadOnlyBooleanWrapper();
    private boolean quietWhenEmpty;
    private boolean strip = true;

    private Input(
            TextField field, String name, String hint, Predicate<Character> allowed, int maxLength, Parser<T> parser) {
        this.field = field;
        this.name = name;
        this.hint = hint;
        this.parser = parser;
        field.setTextFormatter(new TextFormatter<>(change -> filter(change, allowed, maxLength)));
        field.setTooltip(new Tooltip(hint));
        field.textProperty().addListener((o, was, now) -> this.check());
        this.check();
    }

    /**
     * An input of {@code field}: {@code name} labels it in messages, {@code hint} says what it takes, {@code allowed}
     * picks the characters it keeps and {@code maxLength} caps its text.
     */
    public static <T> Input<T> of(
            TextField field, String name, String hint, Predicate<Character> allowed, int maxLength, Parser<T> parser) {
        return new Input<>(field, name, hint, allowed, maxLength, parser);
    }

    /** Whether all {@code inputs} hold valid values, kept up to date. */
    public static BooleanBinding allValid(List<? extends Input<?>> inputs) {
        return Bindings.createBooleanBinding(
                () -> inputs.stream().allMatch(Input::isValid),
                inputs.stream().map(Input::validProperty).toArray(ObservableValue[]::new));
    }

    /** Leaves the field unmarked while empty: for a box that waits for something to act on, not a setting. */
    public Input<T> quietWhenEmpty() {
        this.quietWhenEmpty = true;
        this.check();
        return this;
    }

    /** Hands the text to the parser as typed, spaces and all: for passwords. */
    public Input<T> keepSpaces() {
        this.strip = false;
        this.check();
        return this;
    }

    /** The value; when there is none, the exception names the field and what it takes. */
    public T value() {
        try {
            return this.parser.parse(this.text());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(this.name + ": " + this.hint, e);
        }
    }

    public boolean isValid() {
        return this.valid.get();
    }

    public ReadOnlyBooleanProperty validProperty() {
        return this.valid.getReadOnlyProperty();
    }

    public TextField field() {
        return this.field;
    }

    /** Checks the text again: for a value whose validity hangs on another control too. */
    public void check() {
        boolean ok;
        try {
            this.parser.parse(this.text());
            ok = true;
        } catch (IllegalArgumentException e) {
            ok = false;
        }
        this.valid.set(ok);
        boolean quiet = this.quietWhenEmpty && this.field.getText().isEmpty();
        this.field.pseudoClassStateChanged(Styles.STATE_DANGER, !ok && !quiet);
    }

    private String text() {
        return this.strip ? this.field.getText().strip() : this.field.getText();
    }

    // What is typed or pasted loses the characters the value cannot have; a change past the cap is refused.
    private static TextFormatter.Change filter(
            TextFormatter.Change change, Predicate<Character> allowed, int maxLength) {
        if (!change.isContentChange()) {
            return change;
        }
        String typed = change.getText();
        StringBuilder kept = new StringBuilder(typed.length());
        for (int i = 0; i < typed.length(); i++) {
            if (allowed.test(typed.charAt(i))) {
                kept.append(typed.charAt(i));
            }
        }
        if (kept.length() != typed.length()) {
            change.setText(kept.toString());
            int caret = change.getRangeStart() + kept.length();
            change.selectRange(caret, caret);
        }
        if (change.getControlNewText().length() > maxLength) {
            return null;
        }
        return change;
    }
}
