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

import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.ToIntFunction;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.control.Slider;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;
import org.kordamp.ikonli.Ikon;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.material2.Material2AL;
import org.kordamp.ikonli.material2.Material2MZ;
import org.njmule.core.api.NjMuleInfo;
import org.njmule.core.config.Preferences;
import org.njmule.gui.model.EngineModel;
import org.njmule.gui.ui.Gui;
import org.njmule.gui.ui.Input;
import org.njmule.gui.ui.Inputs;

/**
 * The menu of the notification area icon: the speed limits with their sliders, connecting and disconnecting, the
 * options, the window and exit, beside njMule's name and version. A window of its own, as a plain menu cannot hold
 * sliders; it closes when it loses the focus. FX thread only.
 */
public final class TrayMenu {

    /** What the menu does beyond the preferences and the connection. */
    public interface Actions {
        void restore();

        void options();

        void exit();
    }

    private final Gui gui;
    private final Stage stage;
    private final Slider down = new Slider();
    private final Slider up = new Slider();
    private final TextField downText = new TextField();
    private final TextField upText = new TextField();
    private final Inputs inputs;
    private final Button connect;
    private final Button disconnect;

    public TrayMenu(Gui gui, Window owner, Actions actions) {
        this.gui = gui;
        this.inputs = new Inputs(gui);
        this.stage = new Stage(StageStyle.UNDECORATED);
        this.stage.initOwner(owner);
        this.stage.setAlwaysOnTop(true);
        this.stage.setResizable(false);
        this.connect = this.item(
                gui.t("IDS_MAIN_BTN_CONNECT"),
                Material2AL.LINK,
                () -> gui.model().connect(Optional.empty()));
        this.disconnect = this.item(
                gui.t("IDS_MAIN_BTN_DISCONNECT"),
                Material2AL.LINK_OFF,
                () -> gui.model().disconnect());
        Button restore = this.item(gui.t("IDS_MAIN_POPUP_RESTORE"), Material2MZ.OPEN_IN_BROWSER, actions::restore);
        restore.getStyleClass().add("tray-default");
        VBox items = new VBox(
                this.title(),
                this.speeds(),
                this.item(
                        gui.t("IDS_PW_UA"),
                        Material2MZ.SPEED,
                        () -> this.setLimits(p -> p.downloadCapacityKiBps(), p -> p.uploadCapacityKiBps())),
                this.item(gui.t("IDS_PW_PA"), Material2MZ.SLOW_MOTION_VIDEO, () -> this.setLimits(p -> 1, p -> 1)),
                new Separator(),
                this.connect,
                this.disconnect,
                new Separator(),
                this.item(gui.t("IDS_EM_PREFS"), Material2MZ.SETTINGS, actions::options),
                new Separator(),
                restore,
                new Separator(),
                this.item(gui.t("IDS_EXIT"), Material2AL.EXIT_TO_APP, actions::exit));
        items.getStyleClass().add("tray-items");
        HBox root = new HBox(this.band(), items);
        root.getStyleClass().addAll("njmule", "tray-menu");
        Scene scene = new Scene(root);
        gui.themes().attach(scene);
        this.stage.setScene(scene);
        this.stage.focusedProperty().addListener((o, was, now) -> {
            if (!now) {
                this.stage.hide();
            }
        });
        scene.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ESCAPE) {
                this.stage.hide();
            }
        });
    }

    /** Opens the menu with a corner at {@code (x, y)}, on the side of it that fits the screen. */
    public void show(double x, double y) {
        this.load(this.gui.model().preferencesProperty().get());
        EngineModel.Connection state = this.gui.model().connectionProperty().get();
        this.connect.setDisable(state != EngineModel.Connection.DISCONNECTED);
        this.disconnect.setDisable(state == EngineModel.Connection.DISCONNECTED);
        this.stage.sizeToScene();
        this.stage.show();
        Rectangle2D bounds = Screen.getScreensForRectangle(x, y, 1, 1).stream()
                .findFirst()
                .orElse(Screen.getPrimary())
                .getVisualBounds();
        double width = this.stage.getWidth();
        double height = this.stage.getHeight();
        this.stage.setX(Math.clamp(x - width, bounds.getMinX(), Math.max(bounds.getMinX(), bounds.getMaxX() - width)));
        this.stage.setY(
                Math.clamp(y - height, bounds.getMinY(), Math.max(bounds.getMinY(), bounds.getMaxY() - height)));
        this.stage.toFront();
        this.stage.requestFocus();
    }

    public boolean isShowing() {
        return this.stage.isShowing();
    }

    /** The content, for tests. */
    Scene scene() {
        return this.stage.getScene();
    }

    public void hide() {
        this.stage.hide();
    }

    // The name runs up the left edge, as on the side of a box.
    private Node band() {
        Label name = new Label(NjMuleInfo.NAME + " " + NjMuleInfo.version());
        name.getStyleClass().add("tray-band-text");
        name.setRotate(-90);
        StackPane band = new StackPane(new Group(name));
        band.getStyleClass().add("tray-band");
        band.setAlignment(Pos.BOTTOM_CENTER);
        return band;
    }

    private Node title() {
        Label title = new Label(this.gui.t("nj.tray.speed"), new FontIcon(Material2MZ.SPEED));
        title.getStyleClass().add("tray-title");
        return title;
    }

    // Each limit as a slider over the line's capacity and as a number; 0 is no limit.
    private Node speeds() {
        GridPane grid = new GridPane();
        grid.getStyleClass().add("tray-speeds");
        this.speed(
                grid,
                0,
                Material2AL.ARROW_DOWNWARD,
                this.gui.t("IDS_PW_CON_DOWNLBL"),
                this.down,
                this.downText,
                (p, v) -> p.toBuilder().maxDownloadKiBps(v).build());
        this.speed(
                grid,
                1,
                Material2AL.ARROW_UPWARD,
                this.gui.t("IDS_PW_CON_UPLBL"),
                this.up,
                this.upText,
                (p, v) -> p.toBuilder().maxUploadKiBps(v).build());
        return grid;
    }

    private void speed(
            GridPane grid,
            int row,
            Ikon icon,
            String name,
            Slider slider,
            TextField text,
            BiFunction<Preferences, Integer, Preferences> change) {
        FontIcon arrow = new FontIcon(icon);
        arrow.getStyleClass().add(row == 0 ? "tray-down" : "tray-up");
        Input<Integer> input = this.inputs.number(text, name, 0, Preferences.MAX_RATE_KIBPS);
        text.setPrefColumnCount(4);
        text.setAlignment(Pos.CENTER_RIGHT);
        slider.setPrefWidth(90);
        slider.valueChangingProperty().addListener((o, was, now) -> {
            if (!now) {
                this.apply(change, (int) Math.round(slider.getValue()));
            }
        });
        slider.valueProperty().addListener((o, was, now) -> {
            text.setText(String.valueOf(Math.round(now.doubleValue())));
            if (!slider.isValueChanging()) {
                this.apply(change, (int) Math.round(now.doubleValue()));
            }
        });
        text.setOnAction(e -> this.typed(slider, input));
        text.focusedProperty().addListener((o, was, now) -> {
            if (!now) {
                this.typed(slider, input);
            }
        });
        grid.addRow(row, arrow, new Label(name), slider, text, new Label(this.gui.t("IDS_KBYTESPERSEC")));
    }

    private void typed(Slider slider, Input<Integer> input) {
        if (input.isValid()) {
            slider.setValue(Math.min(input.value(), slider.getMax()));
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

    private void setLimits(ToIntFunction<Preferences> download, ToIntFunction<Preferences> upload) {
        this.gui
                .model()
                .updatePreferences(
                        p -> p.toBuilder()
                                .maxDownloadKiBps(download.applyAsInt(p))
                                .maxUploadKiBps(upload.applyAsInt(p))
                                .build(),
                        this::load);
    }

    private void load(Preferences p) {
        this.down.setMax(Math.max(p.downloadCapacityKiBps(), p.maxDownloadKiBps()));
        this.up.setMax(Math.max(p.uploadCapacityKiBps(), p.maxUploadKiBps()));
        this.down.setValue(p.maxDownloadKiBps());
        this.up.setValue(p.maxUploadKiBps());
        this.downText.setText(String.valueOf(p.maxDownloadKiBps()));
        this.upText.setText(String.valueOf(p.maxUploadKiBps()));
    }

    private Button item(String text, Ikon icon, Runnable action) {
        Button button = new Button(text, new FontIcon(icon));
        button.getStyleClass().addAll("flat", "tray-item");
        button.setMaxWidth(Double.MAX_VALUE);
        button.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(button, Priority.ALWAYS);
        button.setOnAction(e -> {
            this.stage.hide();
            action.run();
        });
        return button;
    }
}
