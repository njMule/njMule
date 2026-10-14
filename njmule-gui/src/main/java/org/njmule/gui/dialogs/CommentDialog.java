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
package org.njmule.gui.dialogs;

import java.util.List;
import java.util.stream.IntStream;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.kordamp.ikonli.material2.Material2AL;
import org.kordamp.ikonli.material2.Material2MZ;
import org.njmule.core.api.FileComment;
import org.njmule.core.api.Hash16;
import org.njmule.core.api.PeerComment;
import org.njmule.gui.ui.Fx;
import org.njmule.gui.ui.Gui;

/** Our comment and rating of a file, which its downloaders hear, and what the sources of a download said of it. */
public final class CommentDialog {

    // The ratings from none to the best, as clients number them.
    private static final List<String> RATINGS = List.of(
            "IDS_CMT_NOTRATED", "IDS_CMT_FAKE", "IDS_CMT_POOR", "IDS_CMT_FAIR", "IDS_CMT_GOOD", "IDS_CMT_EXCELLENT");

    private final Gui gui;
    private final Hash16 file;
    private final Stage stage = new Stage();
    private final ComboBox<Integer> rating = new ComboBox<>();
    private final TextField text = new TextField();
    private final ListView<PeerComment> heard = new ListView<>();

    public CommentDialog(Gui gui, Window owner, Hash16 file, String name, boolean download) {
        this.gui = gui;
        this.file = file;
        this.stage.initOwner(owner);
        this.stage.initModality(Modality.WINDOW_MODAL);
        this.stage.setTitle(gui.t("IDS_COMMENT") + ": " + name);
        this.rating
                .getItems()
                .setAll(IntStream.rangeClosed(0, FileComment.MAX_RATING).boxed().toList());
        this.rating.setCellFactory(l -> new RatingCell());
        this.rating.setButtonCell(new RatingCell());
        this.rating.setValue(0);
        this.text.setPrefColumnCount(40);
        this.text.setPromptText(gui.t("IDS_CMT_QUEST"));
        this.text.textProperty().addListener((o, was, now) -> {
            if (now.length() > FileComment.MAX_LENGTH) {
                this.text.setText(now.substring(0, FileComment.MAX_LENGTH));
            }
        });
        GridPane form = new GridPane();
        form.setHgap(10);
        form.setVgap(6);
        form.addRow(0, new Label(gui.t("IDS_CMT_RATEQUEST")), this.rating);
        form.addRow(1, new Label(gui.t("IDS_CMT_READ")), this.text);
        VBox root = new VBox(10, form);
        if (download) {
            this.heard.setPlaceholder(new Label(gui.t("IDS_CMT_NONE")));
            this.heard.setCellFactory(l -> new ListCell<>() {
                @Override
                protected void updateItem(PeerComment item, boolean empty) {
                    super.updateItem(item, empty);
                    this.setText(empty || item == null ? null : CommentDialog.this.line(item));
                }
            });
            Label title = new Label(gui.t("IDS_CMT_READALL"));
            title.getStyleClass().add("settings-section");
            root.getChildren().addAll(title, this.heard);
            VBox.setVgrow(this.heard, Priority.ALWAYS);
            gui.model().peerComments(file, this.heard.getItems()::setAll);
        }
        root.getChildren()
                .add(Fx.toolRow(
                        Fx.primary(Fx.button(gui.t("nj.categories.save"), Material2MZ.SAVE, this::save)),
                        Fx.spacer(),
                        Fx.button(gui.t("IDS_FD_CLOSE"), Material2AL.CLOSE, this.stage::close)));
        root.setPadding(new Insets(14));
        Scene scene = new Scene(root, 620, download ? 420 : 180);
        gui.themes().attach(scene);
        this.stage.setScene(scene);
        gui.model().fileComment(file, c -> {
            this.rating.setValue(c.rating());
            this.text.setText(c.text());
        });
    }

    public void show() {
        this.stage.show();
    }

    void save() {
        FileComment comment = FileComment.of(this.text.getText(), this.rating.getValue());
        this.gui.model().setFileComment(this.file, comment, this.stage::close);
    }

    private String ratingName(int value) {
        return this.gui.t(RATINGS.get(Math.clamp(value, 0, RATINGS.size() - 1)));
    }

    private String line(PeerComment c) {
        String rated = c.comment().rating() == 0
                ? ""
                : "[" + this.ratingName(c.comment().rating()) + "] ";
        return c.user() + ": " + rated + c.comment().text();
    }

    private final class RatingCell extends ListCell<Integer> {
        @Override
        protected void updateItem(Integer item, boolean empty) {
            super.updateItem(item, empty);
            this.setText(empty || item == null ? null : CommentDialog.this.ratingName(item));
        }
    }

    Stage stage() {
        return this.stage;
    }
}
