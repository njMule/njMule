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
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Supplier;
import javafx.geometry.HPos;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;
import org.njmule.core.api.EngineStatus;
import org.njmule.gui.model.EngineModel;
import org.njmule.gui.ui.Fx;
import org.njmule.gui.ui.Gui;
import org.njmule.gui.ui.Icon;
import org.njmule.gui.ui.Icons;

/**
 * A small window over the others with njMule at a glance — connected, the rates with their last half minute, the
 * downloads finished in this run and the free space where they go — and buttons to the incoming folder, the options and
 * the main window. A click on the notification area icon opens it. FX thread only.
 */
public final class MiniMule {

    /** What the buttons do. */
    public interface Actions {
        void openIncoming();

        void options();

        void restore();
    }

    private final Gui gui;
    private final Supplier<Path> incoming;
    private final Stage stage;
    private final Label connected = new Label();
    private final Label upload = new Label();
    private final Label download = new Label();
    private final Label completed = new Label();
    private final Label space = new Label();
    private final Region dot = new Region();
    private static final double WIDTH = 300;

    public MiniMule(Gui gui, Window owner, Supplier<Path> incoming, Actions actions) {
        this.gui = gui;
        this.incoming = incoming;
        this.stage = new Stage(StageStyle.UTILITY);
        this.stage.initOwner(owner);
        this.stage.setAlwaysOnTop(true);
        this.stage.setResizable(false);
        this.stage.setTitle("MiniMule");
        this.connected.getStyleClass().addAll("minimule-state", "minimule-value");
        this.dot.getStyleClass().add("minimule-dot");
        HBox state = new HBox(8, this.dot, this.connected);
        state.setAlignment(Pos.CENTER_LEFT);
        GridPane grid = new GridPane();
        grid.getStyleClass().add("minimule-grid");
        row(grid, 0, Icons.ARROW_DOWN, gui.t("IDS_PW_CON_DOWNLBL"), this.download, "minimule-down");
        grid.add(new Sparkline(gui.model().rates()), 0, 1, 2, 1);
        row(grid, 2, Icons.ARROW_UP, gui.t("IDS_PW_CON_UPLBL"), this.upload, "minimule-up");
        row(grid, 3, null, gui.t("IDS_DL_TRANSFCOMPL"), this.completed, "");
        row(grid, 4, null, gui.t("IDS_STATS_FREESPACE"), this.space, "");
        ColumnConstraints names = new ColumnConstraints();
        names.setHgrow(Priority.ALWAYS);
        grid.getColumnConstraints().addAll(names, new ColumnConstraints());
        FlowPane buttons = new FlowPane(
                6,
                6,
                button(gui.t("IDS_OPENINC"), Icons.FOLDER_OPEN, actions::openIncoming),
                button(gui.t("IDS_EM_PREFS"), Icons.OPTIONS, actions::options),
                button(gui.t("nj.tray.show"), Icons.OPEN_EXTERNAL, actions::restore));
        buttons.getStyleClass().add("minimule-buttons");
        VBox root = new VBox(state, grid, buttons);
        root.getStyleClass().addAll("njmule", "minimule");
        root.setPrefWidth(WIDTH);
        Scene scene = new Scene(root);
        gui.attach(scene);
        this.stage.setScene(scene);
    }

    /** Opens the window in the corner of the screen next to the notification area. */
    public void show() {
        refresh();
        this.stage.sizeToScene();
        this.stage.show();
        Rectangle2D bounds = Screen.getPrimary().getVisualBounds();
        this.stage.setX(bounds.getMaxX() - this.stage.getWidth() - 8);
        this.stage.setY(bounds.getMaxY() - this.stage.getHeight() - 8);
        this.stage.toFront();
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

    /** Shows the current state; called every second while open. */
    public void refresh() {
        EngineModel model = this.gui.model();
        EngineStatus status = model.statusProperty().get();
        boolean online = model.connectionProperty().get() == EngineModel.Connection.CONNECTED
                || (status != null && status.kad().map(k -> k.connected()).orElse(false));
        this.connected.setText(this.gui.t(online ? "IDS_CONNECTED" : "IDS_NOTCONNECTED"));
        this.dot.getStyleClass().remove("online");
        if (online) {
            this.dot.getStyleClass().add("online");
        }
        long up = status == null ? 0 : status.uploadBytesPerSecond();
        long down = status == null ? 0 : status.downloadBytesPerSecond();
        this.upload.setText(this.gui.formats().rate(up));
        this.download.setText(this.gui.formats().rate(down));
        this.completed.setText(String.valueOf(model.completedProperty().get()));
        this.space.setText(freeSpace());
    }

    private String freeSpace() {
        Path dir = this.incoming.get();
        try {
            Path existing = dir;
            while (existing != null && !Files.exists(existing)) {
                existing = existing.getParent();
            }
            return existing == null
                    ? "?"
                    : this.gui.formats().size(Files.getFileStore(existing).getUsableSpace());
        } catch (IOException e) {
            return "?";
        }
    }

    private void row(GridPane grid, int row, Icons icon, String name, Label value, String tone) {
        Label label = new Label(name, icon == null ? null : new Icon(icon));
        if (!tone.isEmpty()) {
            label.getStyleClass().add(tone);
        } else {
            label.getStyleClass().add(Styles.TEXT_MUTED);
        }
        value.getStyleClass().add("minimule-value");
        GridPane.setHalignment(value, HPos.RIGHT);
        grid.addRow(row, label, value);
    }

    private Button button(String text, Icons icon, Runnable action) {
        Button button = Fx.button(text, icon, action);
        button.getStyleClass().add(Styles.SMALL);
        return button;
    }
}
