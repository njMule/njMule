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

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.Function;
import javafx.geometry.Pos;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.util.StringConverter;
import org.njmule.core.config.Preferences;

/**
 * A speed typed in the unit beside it, KB/s or MB/s with decimals, or the speed of a line in Mbit/s, kept in whole
 * KiB/s as the settings are. A limit can also be no limit at all.
 */
public final class RateField<T> {

    /** A unit a speed is typed in, with the bytes per second one of it is. */
    public enum Unit {
        KB(1024),
        MB(1024 * 1024),
        MBIT(125_000);

        final long bytes;

        Unit(long bytes) {
            this.bytes = bytes;
        }
    }

    private static final int MAX_TEXT = 12;

    private final Gui gui;
    private final TextField text = new TextField();
    private final ComboBox<Unit> unit = new ComboBox<>();
    private final Optional<CheckBox> none;
    private final Label about = new Label();
    private final HBox box = new HBox(8);
    private final int min;
    private final Input<T> input;
    private final DecimalFormat format;
    private final DecimalFormatSymbols symbols;
    // What was shown last, so that an untouched field gives back the exact KiB/s it was shown.
    private String shownText = "";
    private Unit shownUnit;
    private int shownKib;
    private boolean converting;

    private RateField(
            Gui gui,
            String name,
            List<Unit> units,
            boolean unlimited,
            int min,
            Function<RateField<T>, Input.Parser<T>> parser) {
        this.gui = gui;
        this.min = min;
        this.symbols = DecimalFormatSymbols.getInstance(gui.i18n().numberLocale());
        this.format = new DecimalFormat("0.##", this.symbols);
        this.unit.getItems().setAll(units);
        this.unit.setValue(units.getFirst());
        this.unit.setConverter(new StringConverter<>() {
            @Override
            public String toString(Unit u) {
                return u == null ? "" : RateField.this.unitName(u);
            }

            @Override
            public Unit fromString(String s) {
                throw new UnsupportedOperationException();
            }
        });
        this.text.setPrefColumnCount(7);
        this.text.setAlignment(Pos.CENTER_RIGHT);
        this.none = unlimited ? Optional.of(new CheckBox(gui.t("nj.rate.noLimit"))) : Optional.empty();
        this.input = Input.of(
                this.text,
                name,
                gui.f(
                        "nj.input.rate",
                        gui.formats().rate(min * 1024L),
                        gui.formats().rate(Preferences.MAX_RATE_KIBPS * 1024L)),
                c -> Character.isDigit(c) || c == '.' || c == ',',
                MAX_TEXT,
                parser.apply(this));
        this.unit.valueProperty().addListener((o, was, now) -> this.unitChanged(was, now));
        this.none.ifPresent(n -> {
            this.text.disableProperty().bind(n.selectedProperty());
            this.unit.disableProperty().bind(n.selectedProperty());
            n.selectedProperty().addListener((o, was, now) -> this.input.check());
        });
        this.box.setAlignment(Pos.CENTER_LEFT);
        if (units.size() > 1) {
            this.box.getChildren().addAll(this.text, this.unit);
        } else {
            this.box.getChildren().addAll(this.text, new Label(this.unitName(units.getFirst())));
        }
        this.none.ifPresent(this.box.getChildren()::add);
        if (units.contains(Unit.MBIT)) {
            // A line is sold in bits; what njMule shows is bytes: the same speed in both.
            this.about.getStyleClass().add("rate-about");
            this.text.textProperty().addListener((o, was, now) -> this.showAbout());
            this.box.getChildren().add(this.about);
        }
    }

    /** A limit in KB/s or MB/s, or no limit (0). */
    public static RateField<Integer> limit(Gui gui, String name) {
        return new RateField<>(gui, name, List.of(Unit.KB, Unit.MB), true, 1, f -> t -> f.isNone() ? 0 : f.required(t));
    }

    /** The speed of a line in Mbit/s, shown in bytes too. */
    public static RateField<Integer> line(Gui gui, String name) {
        return new RateField<>(gui, name, List.of(Unit.MBIT), false, 1, f -> f::required);
    }

    /** Nothing (no change), no limit (0), or a limit in KB/s or MB/s. */
    public static RateField<OptionalInt> optionalLimit(Gui gui, String name) {
        return new RateField<>(
                gui, name, List.of(Unit.KB, Unit.MB), true, 1, f -> t -> f.isNone() ? OptionalInt.of(0) : f.kib(t));
    }

    public HBox node() {
        return this.box;
    }

    public Input<T> input() {
        return this.input;
    }

    public TextField field() {
        return this.text;
    }

    /** Shows {@code kib}: no limit for 0 where there can be none, else in the unit that suits it. */
    public void show(int kib) {
        this.showOptional(OptionalInt.of(kib));
    }

    /** Shows {@code kib}, or an empty field for nothing. */
    public void showOptional(OptionalInt kib) {
        boolean unlimited = kib.isPresent() && kib.getAsInt() == 0 && this.none.isPresent();
        this.none.ifPresent(n -> n.setSelected(unlimited));
        if (kib.isEmpty() || unlimited) {
            this.shown("", this.unit.getValue(), 0);
            return;
        }
        int value = kib.getAsInt();
        Unit suited = this.unit.getItems().contains(Unit.MBIT)
                ? Unit.MBIT
                : value >= 1024 && this.unit.getItems().contains(Unit.MB) ? Unit.MB : Unit.KB;
        this.shown(this.text(value, suited), suited, value);
    }

    private void shown(String text, Unit unit, int kib) {
        this.converting = true;
        try {
            this.unit.setValue(unit);
        } finally {
            this.converting = false;
        }
        this.shownText = text;
        this.shownUnit = unit;
        this.shownKib = kib;
        this.text.setText(text);
        this.input.check();
    }

    // Another unit keeps the speed typed: the number is written again in it.
    private void unitChanged(Unit was, Unit now) {
        if (this.converting || was == null || now == null) {
            return;
        }
        OptionalInt kib;
        try {
            kib = this.kib(this.text.getText().strip(), was);
        } catch (IllegalArgumentException e) {
            this.input.check();
            return;
        }
        if (kib.isPresent()) {
            this.text.setText(this.text(kib.getAsInt(), now));
        }
        this.input.check();
    }

    private boolean isNone() {
        return this.none.map(CheckBox::isSelected).orElse(false);
    }

    private int required(String typed) {
        return this.kib(typed).orElseThrow(() -> new IllegalArgumentException("empty"));
    }

    private OptionalInt kib(String typed) {
        return this.kib(typed, this.unit.getValue());
    }

    // The KiB/s of a typed number in unit, empty for nothing typed.
    private OptionalInt kib(String typed, Unit in) {
        if (typed.isEmpty()) {
            return OptionalInt.empty();
        }
        if (typed.equals(this.shownText) && in == this.shownUnit) {
            return OptionalInt.of(this.shownKib);
        }
        BigDecimal number;
        try {
            number = new BigDecimal(typed.replace(',', '.'));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(typed, e);
        }
        BigDecimal kib =
                number.multiply(BigDecimal.valueOf(in.bytes)).divide(BigDecimal.valueOf(1024), 0, RoundingMode.HALF_UP);
        if (kib.compareTo(BigDecimal.valueOf(this.min)) < 0
                || kib.compareTo(BigDecimal.valueOf(Preferences.MAX_RATE_KIBPS)) > 0) {
            throw new IllegalArgumentException(typed);
        }
        return OptionalInt.of(kib.intValueExact());
    }

    private String text(int kib, Unit in) {
        BigDecimal value =
                BigDecimal.valueOf(kib * 1024L).divide(BigDecimal.valueOf(in.bytes), 2, RoundingMode.HALF_UP);
        return this.format.format(in == Unit.MBIT ? value.setScale(1, RoundingMode.HALF_UP) : value);
    }

    private void showAbout() {
        try {
            OptionalInt kib = this.kib(this.text.getText().strip());
            this.about.setText(kib.isPresent() ? "≈ " + this.gui.formats().rate(kib.getAsInt() * 1024L) : "");
        } catch (IllegalArgumentException e) {
            this.about.setText("");
        }
    }

    /** The unit chosen, the no-limit box and the speed in bytes, for tests. */
    ComboBox<Unit> unitBox() {
        return this.unit;
    }

    Optional<CheckBox> noLimitBox() {
        return this.none;
    }

    Label aboutLabel() {
        return this.about;
    }

    private String unitName(Unit u) {
        return switch (u) {
            case KB -> this.gui.t("IDS_KBYTESPERSEC");
            case MB -> this.gui.t("IDS_MBYTESPERSEC");
            case MBIT -> this.gui.t("nj.rate.mbits");
        };
    }
}
