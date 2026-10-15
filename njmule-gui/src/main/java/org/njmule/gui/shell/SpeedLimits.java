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

import java.util.function.BiFunction;
import java.util.function.ToIntFunction;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import org.kordamp.ikonli.Ikon;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.material2.Material2AL;
import org.njmule.core.config.Preferences;
import org.njmule.gui.ui.Gui;
import org.njmule.gui.ui.Input;
import org.njmule.gui.ui.Inputs;

/**
 * The download and upload limits as sliders over the line's capacity and as numbers, applied as they change; 0 is no
 * limit. Shown by the notification area's menu and by the rates of the top bar.
 */
final class SpeedLimits {

    private final Gui gui;
    private final Inputs inputs;
    private final Slider down = new Slider();
    private final Slider up = new Slider();
    private final TextField downText = new TextField();
    private final TextField upText = new TextField();
    private final GridPane grid = new GridPane();
    private final Input<Integer> downInput;
    private final Input<Integer> upInput;
    // Set while the preferences are shown: moving the sliders then is no change of the user's.
    private boolean loading;

    SpeedLimits(Gui gui) {
        this.gui = gui;
        this.inputs = new Inputs(gui);
        this.grid.getStyleClass().add("tray-speeds");
        this.downInput = this.speed(
                0,
                Material2AL.ARROW_DOWNWARD,
                gui.t("IDS_PW_CON_DOWNLBL"),
                this.down,
                this.downText,
                (p, v) -> p.toBuilder().maxDownloadKiBps(v).build());
        this.upInput = this.speed(
                1,
                Material2AL.ARROW_UPWARD,
                gui.t("IDS_PW_CON_UPLBL"),
                this.up,
                this.upText,
                (p, v) -> p.toBuilder().maxUploadKiBps(v).build());
    }

    Node view() {
        return this.grid;
    }

    /** Shows the limits the preferences hold now. */
    void load() {
        Preferences p = this.gui.model().preferencesProperty().get();
        if (p != null) {
            this.load(p);
        }
    }

    /** Sets both limits at once, as the preferences give them, then shows them. */
    void set(ToIntFunction<Preferences> download, ToIntFunction<Preferences> upload) {
        this.gui
                .model()
                .updatePreferences(
                        p -> p.toBuilder()
                                .maxDownloadKiBps(download.applyAsInt(p))
                                .maxUploadKiBps(upload.applyAsInt(p))
                                .build(),
                        this::load);
    }

    private Input<Integer> speed(
            int row,
            Ikon icon,
            String name,
            Slider slider,
            TextField text,
            BiFunction<Preferences, Integer, Preferences> change) {
        FontIcon arrow = new FontIcon(icon);
        arrow.getStyleClass().add(row == 0 ? "tray-down" : "tray-up");
        // A typed limit stays within the slider, whose top is the line's capacity or the limit set past it.
        Input<Integer> input =
                this.inputs.number(text, name, 0, () -> (int) slider.getMax(), Preferences.MAX_RATE_KIBPS);
        text.setPrefColumnCount(4);
        text.setAlignment(Pos.CENTER_RIGHT);
        slider.setPrefWidth(108);
        slider.setAccessibleText(name);
        slider.valueChangingProperty().addListener((o, was, now) -> {
            if (!now && !this.loading) {
                this.apply(change, (int) Math.round(slider.getValue()));
            }
        });
        slider.valueProperty().addListener((o, was, now) -> {
            text.setText(String.valueOf(Math.round(now.doubleValue())));
            if (!slider.isValueChanging() && !this.loading) {
                this.apply(change, (int) Math.round(now.doubleValue()));
            }
        });
        text.setOnAction(e -> this.typed(slider, input));
        text.focusedProperty().addListener((o, was, now) -> {
            if (!now) {
                this.typed(slider, input);
            }
        });
        this.grid.addRow(row, arrow, new Label(name), slider, text, new Label(this.gui.t("IDS_KBYTESPERSEC")));
        return input;
    }

    private void typed(Slider slider, Input<Integer> input) {
        if (input.isValid()) {
            slider.setValue(input.value());
        } else {
            input.field().setText(String.valueOf(Math.round(slider.getValue())));
        }
    }

    private void apply(BiFunction<Preferences, Integer, Preferences> change, int value) {
        Preferences current = this.gui.model().preferencesProperty().get();
        if (change.apply(current, value).equals(current)) {
            return;
        }
        this.gui.model().updatePreferences(p -> change.apply(p, value), p -> {});
    }

    // A new top first clamps the old position, which must not be taken for a choice.
    private void load(Preferences p) {
        this.loading = true;
        try {
            this.down.setMax(Math.max(p.downloadCapacityKiBps(), p.maxDownloadKiBps()));
            this.up.setMax(Math.max(p.uploadCapacityKiBps(), p.maxUploadKiBps()));
            this.down.setValue(p.maxDownloadKiBps());
            this.up.setValue(p.maxUploadKiBps());
            this.downText.setText(String.valueOf(p.maxDownloadKiBps()));
            this.upText.setText(String.valueOf(p.maxUploadKiBps()));
        } finally {
            this.loading = false;
        }
        this.bound(this.downInput, this.down);
        this.bound(this.upInput, this.up);
    }

    // The typed limit is checked against the slider's new top, which its hint tells.
    private void bound(Input<Integer> input, Slider slider) {
        input.check();
        input.field().getTooltip().setText(this.gui.f("nj.input.number", 0, (int) slider.getMax()));
    }
}
