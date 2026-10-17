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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Supplier;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;
import org.njmule.core.api.EngineStatus;
import org.njmule.core.api.NjMuleInfo;
import org.njmule.gui.model.EngineModel;
import org.njmule.gui.ui.Fx;
import org.njmule.gui.ui.Gui;
import org.njmule.gui.ui.Icon;
import org.njmule.gui.ui.Icons;

/**
 * A small window over the others with njMule at a glance — connected, the rates, the downloads finished in this run and
 * the free space where they go — and buttons to the incoming folder, the options and the main window. A click on the
 * notification area icon opens it. FX thread only.
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

    public MiniMule(Gui gui, Window owner, Supplier<Path> incoming, Actions actions) {
        this.gui = gui;
        this.incoming = incoming;
        this.stage = new Stage(StageStyle.UTILITY);
        this.stage.initOwner(owner);
        this.stage.setAlwaysOnTop(true);
        this.stage.setResizable(false);
        this.stage.setTitle(NjMuleInfo.NAME + " " + NjMuleInfo.version());
        GridPane grid = new GridPane();
        grid.getStyleClass().add("minimule-grid");
        row(grid, 0, Icons.LINK, gui.t("IDS_CONNECTED"), this.connected);
        row(grid, 1, Icons.ARROW_UP, gui.t("IDS_PW_CON_UPLBL"), this.upload);
        row(grid, 2, Icons.ARROW_DOWN, gui.t("IDS_PW_CON_DOWNLBL"), this.download);
        row(grid, 3, Icons.DONE_ALL, gui.t("IDS_DL_TRANSFCOMPL"), this.completed);
        row(grid, 4, Icons.STORAGE, gui.t("IDS_STATS_FREESPACE"), this.space);
        HBox buttons = new HBox(
                button(gui.t("IDS_OPENINC"), Icons.FOLDER_OPEN, actions::openIncoming),
                button(gui.t("IDS_EM_PREFS"), Icons.OPTIONS, actions::options),
                button(gui.t("IDS_MAIN_POPUP_RESTORE"), Icons.OPEN_EXTERNAL, actions::restore));
        buttons.getStyleClass().add("minimule-buttons");
        buttons.setAlignment(Pos.CENTER_RIGHT);
        VBox root = new VBox(grid, new Separator(), buttons);
        root.getStyleClass().addAll("njmule", "minimule");
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
        this.connected.setText(this.gui.t(online ? "IDS_YES" : "IDS_NO"));
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

    private void row(GridPane grid, int row, Icons icon, String name, Label value) {
        Label label = new Label(name, new Icon(icon));
        value.getStyleClass().add("minimule-value");
        grid.addRow(row, label, value);
    }

    private Button button(String text, Icons icon, Runnable action) {
        Button button = Fx.iconButton(text, icon, action);
        button.getStyleClass().add("flat");
        return button;
    }
}
