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

import atlantafx.base.theme.Styles;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.njmule.core.api.FileComment;
import org.njmule.core.api.Hash16;
import org.njmule.core.api.KadNote;
import org.njmule.core.api.PeerComment;
import org.njmule.gui.ui.DialogFrame;
import org.njmule.gui.ui.Fx;
import org.njmule.gui.ui.Gui;
import org.njmule.gui.ui.Icons;

/**
 * Our comment and rating of a file, which its downloaders hear, and what others said of it: the sources of a download,
 * and the Kad nodes that hold notes on the file.
 */
public final class CommentDialog {

    // The ratings from none to the best, as clients number them.
    private static final List<String> RATINGS = List.of(
            "IDS_CMT_NOTRATED", "IDS_CMT_FAKE", "IDS_CMT_POOR", "IDS_CMT_FAIR", "IDS_CMT_GOOD", "IDS_CMT_EXCELLENT");

    private final Gui gui;
    private final Hash16 file;
    private final Stage stage = new Stage();
    private final ComboBox<Integer> rating = new ComboBox<>();
    private final TextField text = new TextField();
    private final ListView<Heard> heard = new ListView<>();
    private final Button searchKad;
    private final Label kadState = new Label();
    private List<PeerComment> peers = List.of();
    private List<KadNote> notes = List.of();
    private boolean textChanged;
    private boolean ratingChanged;

    /** One comment heard, from a source over eD2K or from a Kad node. */
    private record Heard(String who, String fileName, FileComment comment, boolean kad) {}

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
        Label fileName = Fx.ownDirection(new Label(name, Fx.icon(Icons.FILE)));
        fileName.getStyleClass().add(Styles.TEXT_MUTED);
        this.text.setMaxWidth(Double.MAX_VALUE);
        VBox root = new VBox(
                12,
                fileName,
                DialogFrame.named(gui.t("IDS_CMT_RATEQUEST"), this.rating),
                DialogFrame.named(gui.t("IDS_CMT_READ"), this.text));
        this.heard.setPlaceholder(new Label(gui.t("IDS_CMT_NONE")));
        this.heard.setCellFactory(l -> new HeardCell());
        Label title = new Label(gui.t("IDS_CMT_READALL"));
        title.getStyleClass().add("card-heading");
        this.searchKad = Fx.button(gui.t("IDS_SEARCHKAD"), Icons.SEARCH, this::searchKad);
        this.searchKad.getStyleClass().add(Styles.FLAT);
        this.kadState.getStyleClass().add(Styles.TEXT_MUTED);
        HBox heardHead = Fx.toolRow(title, Fx.spacer(), this.kadState, this.searchKad);
        root.getChildren().addAll(heardHead, this.heard);
        VBox.setVgrow(this.heard, Priority.ALWAYS);
        if (download) {
            gui.model().peerComments(file, found -> {
                this.peers = found;
                showHeard();
            });
        }
        gui.model().kadNotes(file, this::kadNotes);
        Button close = Fx.button(gui.t("IDS_FD_CLOSE"), Icons.CLOSE, this.stage::close);
        close.setCancelButton(true);
        VBox frame = DialogFrame.frame(
                DialogFrame.header(gui, gui.t("nj.comments.title"), this.stage::close),
                root,
                DialogFrame.footer(
                        new Region(),
                        DialogFrame.role(close, ButtonBar.ButtonData.CANCEL_CLOSE),
                        DialogFrame.role(
                                Fx.primary(Fx.button(gui.t("nj.categories.save"), Icons.SAVE, this::save)),
                                ButtonBar.ButtonData.OK_DONE)));
        Scene scene = new Scene(frame, 620, 520);
        gui.attach(scene);
        this.stage.setScene(scene);
        // The stored comment may come after the user began to type: what the user changed stays.
        this.text.textProperty().addListener((o, was, now) -> this.textChanged = true);
        this.rating.valueProperty().addListener((o, was, now) -> this.ratingChanged = true);
        gui.model().fileComment(file, c -> {
            if (!this.ratingChanged) {
                this.rating.setValue(c.rating());
            }
            if (!this.textChanged) {
                this.text.setText(c.text());
            }
        });
    }

    public void show() {
        this.stage.show();
    }

    // The button waits for the end of the search: pressed again it would only join it.
    void searchKad() {
        this.searchKad.setDisable(true);
        this.kadState.setText(this.gui.t("IDS_KADSEARCHACTIVE"));
        this.gui
                .model()
                .searchKadNotes(
                        this.file,
                        found -> {
                            kadNotes(found);
                            this.searchKad.setDisable(false);
                            this.kadState.setText(
                                    found.isEmpty()
                                            ? this.gui.t("nj.comments.kadNone")
                                            : this.gui.f("nj.comments.kadCount", found.size()));
                        },
                        why -> {
                            this.searchKad.setDisable(false);
                            this.kadState.setText(this.gui.f("nj.comments.kadFailed", why));
                        });
    }

    private void kadNotes(List<KadNote> found) {
        this.notes = found;
        showHeard();
    }

    private void showHeard() {
        List<Heard> all = new ArrayList<>();
        this.peers.forEach(c -> all.add(new Heard(c.user(), c.fileName(), c.comment(), false)));
        this.notes.forEach(n -> all.add(new Heard(this.gui.t("IDS_KADEMLIA"), n.fileName(), n.comment(), true)));
        this.heard.getItems().setAll(all);
    }

    void save() {
        FileComment comment = FileComment.of(this.text.getText(), this.rating.getValue());
        this.gui.model().setFileComment(this.file, comment, this.stage::close);
    }

    private String ratingName(int value) {
        return this.gui.t(RATINGS.get(Math.clamp(value, 0, RATINGS.size() - 1)));
    }

    private String line(Heard c) {
        String rated =
                c.comment().rating() == 0 ? "" : "[" + ratingName(c.comment().rating()) + "] ";
        return this.gui.i18n().embed(c.who()) + ": " + rated
                + this.gui.i18n().embed(c.comment().text());
    }

    // A note from Kad has the Kad icon, as on the Kad screen; the name the commenter gave the file is a hover away.
    private final class HeardCell extends ListCell<Heard> {
        @Override
        protected void updateItem(Heard item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setText(null);
                setGraphic(null);
                setTooltip(null);
                return;
            }
            setText(CommentDialog.this.line(item));
            setGraphic(item.kad() ? Fx.icon(Icons.KAD) : null);
            setTooltip(
                    item.fileName().isEmpty()
                            ? null
                            : new Tooltip(CommentDialog.this.gui.t("IDS_DL_FILENAME") + ": "
                                    + CommentDialog.this.gui.i18n().embed(item.fileName())));
        }
    }

    private final class RatingCell extends ListCell<Integer> {
        @Override
        protected void updateItem(Integer item, boolean empty) {
            super.updateItem(item, empty);
            setText(empty || item == null ? null : CommentDialog.this.ratingName(item));
        }
    }

    Stage stage() {
        return this.stage;
    }

    TextField textField() {
        return this.text;
    }

    ComboBox<Integer> ratingBox() {
        return this.rating;
    }

    ListView<?> heardList() {
        return this.heard;
    }

    Button kadButton() {
        return this.searchKad;
    }

    Label kadState() {
        return this.kadState;
    }
}
