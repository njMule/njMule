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
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.input.KeyCode;
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
import org.njmule.gui.model.EngineModel;
import org.njmule.gui.ui.Fx;
import org.njmule.gui.ui.Gui;

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
    private final SpeedLimits limits;
    private final Button connect;
    private final Button disconnect;

    public TrayMenu(Gui gui, Window owner, Actions actions) {
        this.gui = gui;
        this.limits = new SpeedLimits(gui);
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
                this.limits.view(),
                this.item(
                        gui.t("IDS_PW_UA"),
                        Material2MZ.SPEED,
                        () -> this.limits.set(p -> p.downloadCapacityKiBps(), p -> p.uploadCapacityKiBps())),
                this.item(gui.t("IDS_PW_PA"), Material2MZ.SLOW_MOTION_VIDEO, () -> this.limits.set(p -> 1, p -> 1)),
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
        gui.attach(scene);
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
        this.limits.load();
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

    private Button item(String text, Ikon icon, Runnable action) {
        Button button = new Button(text, Fx.icon(icon));
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
