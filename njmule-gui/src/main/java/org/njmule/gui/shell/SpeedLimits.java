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
import java.util.function.IntSupplier;
import java.util.function.ToIntFunction;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.layout.GridPane;
import org.njmule.core.config.Preferences;
import org.njmule.gui.ui.Gui;
import org.njmule.gui.ui.Icon;
import org.njmule.gui.ui.Icons;

/**
 * The download and upload limits as sliders over the line's capacity, applied as they change, with the limit written
 * beside. Shown by the notification area's menu and by the rates of the top bar.
 */
final class SpeedLimits {

    /**
     * The slider's positions: the first is no limit, the others go from 1 KiB/s to the top on a logarithmic scale, so
     * that a few KB/s and many MB/s are both in reach.
     */
    static final double STEPS = 1000;

    private final Gui gui;
    private final Slider down = new Slider(0, STEPS, 0);
    private final Slider up = new Slider(0, STEPS, 0);
    private final Label downText = new Label();
    private final Label upText = new Label();
    private final GridPane grid = new GridPane();
    private int downTop = 1;
    private int upTop = 1;
    // Set while the preferences are shown: moving the sliders then is no change of the user's.
    private boolean loading;

    SpeedLimits(Gui gui) {
        this.gui = gui;
        this.grid.getStyleClass().add("tray-speeds");
        speed(
                0,
                Icons.ARROW_DOWN,
                gui.t("IDS_PW_CON_DOWNLBL"),
                this.down,
                this.downText,
                () -> this.downTop,
                (p, v) -> p.toBuilder().maxDownloadKiBps(v).build());
        speed(
                1,
                Icons.ARROW_UP,
                gui.t("IDS_PW_CON_UPLBL"),
                this.up,
                this.upText,
                () -> this.upTop,
                (p, v) -> p.toBuilder().maxUploadKiBps(v).build());
    }

    Node view() {
        return this.grid;
    }

    /** Shows the limits the preferences hold now. */
    void load() {
        Preferences p = this.gui.model().preferencesProperty().get();
        if (p != null) {
            load(p);
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

    /** The limit at {@code position} of a slider whose top is {@code top} KiB/s; 0 is no limit. */
    static int kib(double position, int top) {
        if (position < 1) {
            return 0;
        }
        if (position >= STEPS) {
            return top;
        }
        double value = Math.exp(Math.log(Math.max(1, top)) * (position - 1) / (STEPS - 1));
        return (int) Math.max(1, roundTwoDigits(value));
    }

    /** Where {@code kib} sits on a slider whose top is {@code top} KiB/s. */
    static double position(int kib, int top) {
        if (kib <= 0) {
            return 0;
        }
        if (top <= 1 || kib >= top) {
            return STEPS;
        }
        return 1 + Math.log(kib) / Math.log(top) * (STEPS - 1);
    }

    // Two significant digits: 1,237 KiB/s is set as 1,200, which reads 1.17 MB/s rather than an odd number.
    private static long roundTwoDigits(double value) {
        if (value < 100) {
            return Math.round(value);
        }
        double scale = Math.pow(10, Math.floor(Math.log10(value)) - 1);
        return Math.round(Math.round(value / scale) * scale);
    }

    private void speed(
            int row,
            Icons icon,
            String name,
            Slider slider,
            Label text,
            IntSupplier top,
            BiFunction<Preferences, Integer, Preferences> change) {
        Icon arrow = new Icon(icon);
        arrow.getStyleClass().add(row == 0 ? "tray-down" : "tray-up");
        slider.setPrefWidth(140);
        slider.setAccessibleText(name);
        text.getStyleClass().add("speed-value");
        slider.valueChangingProperty().addListener((o, was, now) -> {
            if (!now && !this.loading) {
                apply(change, kib(slider.getValue(), top.getAsInt()));
            }
        });
        slider.valueProperty().addListener((o, was, now) -> {
            int kib = kib(now.doubleValue(), top.getAsInt());
            text.setText(limitText(kib));
            slider.setAccessibleHelp(text.getText());
            if (!slider.isValueChanging() && !this.loading) {
                apply(change, kib);
            }
        });
        this.grid.addRow(row, arrow, new Label(name), slider, text);
    }

    private String limitText(int kib) {
        return kib == 0 ? this.gui.t("nj.rate.noLimit") : this.gui.formats().rate(kib * 1024L);
    }

    private void apply(BiFunction<Preferences, Integer, Preferences> change, int value) {
        Preferences current = this.gui.model().preferencesProperty().get();
        if (change.apply(current, value).equals(current)) {
            return;
        }
        this.gui.model().updatePreferences(p -> change.apply(p, value), p -> {});
    }

    // The slider's top is the line's capacity, or the limit set past it.
    private void load(Preferences p) {
        this.loading = true;
        try {
            this.downTop = Math.max(p.downloadCapacityKiBps(), p.maxDownloadKiBps());
            this.upTop = Math.max(p.uploadCapacityKiBps(), p.maxUploadKiBps());
            this.down.setValue(position(p.maxDownloadKiBps(), this.downTop));
            this.up.setValue(position(p.maxUploadKiBps(), this.upTop));
            // The limit as set, not as the position gives it back.
            this.downText.setText(limitText(p.maxDownloadKiBps()));
            this.upText.setText(limitText(p.maxUploadKiBps()));
        } finally {
            this.loading = false;
        }
    }

    /** The sliders' tops, for tests. */
    int downTop() {
        return this.downTop;
    }

    int upTop() {
        return this.upTop;
    }
}
