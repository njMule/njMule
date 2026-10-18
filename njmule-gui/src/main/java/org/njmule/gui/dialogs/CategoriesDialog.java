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
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.njmule.core.api.Category;
import org.njmule.core.api.DownloadPriority;
import org.njmule.gui.ui.DialogFrame;
import org.njmule.gui.ui.Fx;
import org.njmule.gui.ui.Gui;
import org.njmule.gui.ui.Icons;
import org.njmule.gui.ui.Input;
import org.njmule.gui.ui.Inputs;

/** The download categories: the list, and a form to add, change or remove one. */
public final class CategoriesDialog {

    private static final List<String> COLOR_NAMES = List.of(
            "nj.category.color0",
            "nj.category.color1",
            "nj.category.color2",
            "nj.category.color3",
            "nj.category.color4",
            "nj.category.color5",
            "nj.category.color6",
            "nj.category.color7",
            "nj.category.color8");

    private final Gui gui;
    private final Stage stage = new Stage();
    private final ListView<Category> list = new ListView<>();
    private final TextField name = new TextField();
    private final TextField incoming = new TextField();
    private final TextField comment = new TextField();
    private final TextField patterns = new TextField();
    private final CheckBox regex = new CheckBox();
    private final ComboBox<Integer> color = new ComboBox<>();
    private final ComboBox<DownloadPriority> priority = new ComboBox<>();
    private final Input<String> nameInput;
    private final Input<Optional<Path>> incomingInput;
    private final Input<String> commentInput;
    private final Input<String> patternsInput;

    public CategoriesDialog(Gui gui, Window owner) {
        this.gui = gui;
        this.stage.initOwner(owner);
        this.stage.initModality(Modality.WINDOW_MODAL);
        this.stage.setTitle(gui.t("nj.categories.title"));
        Inputs inputs = new Inputs(gui);
        this.nameInput = inputs.text(this.name, gui.t("IDS_TITLE"), Category.MAX_NAME, true);
        this.incomingInput = inputs.folder(this.incoming, gui.t("IDS_PW_INCOMING"));
        this.incoming.setPromptText(gui.t("nj.settings.defaultDir"));
        this.commentInput = inputs.text(this.comment, gui.t("IDS_COMMENT"), Category.MAX_COMMENT, false);
        this.patternsInput = inputs.text(this.patterns, gui.t("IDS_AUTOCAT_LABEL"), Category.MAX_PATTERNS, false);
        this.patterns.setPromptText("*.mkv|*.avi");
        Fx.leftToRight(this.patterns);
        this.regex.setText(gui.t("IDS_REGEXPRESSION"));
        for (int c = 0; c <= Category.COLORS; c++) {
            this.color.getItems().add(c);
        }
        this.color.setCellFactory(l -> new ColorCell());
        this.color.setButtonCell(new ColorCell());
        this.priority.getItems().setAll(DownloadPriority.values());
        this.priority.setCellFactory(l -> new NamedCell<>(this::priorityName));
        this.priority.setButtonCell(new NamedCell<>(this::priorityName));

        this.list.setItems(gui.model().categories());
        this.list.setPlaceholder(new Label(gui.t("nj.categories.none")));
        this.list.setCellFactory(l -> new ListCell<>() {
            @Override
            protected void updateItem(Category item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.name());
                setGraphic(empty || item == null ? null : Fx.categoryDot(item.color()));
            }
        });
        this.list.getSelectionModel().selectedItemProperty().addListener((o, was, now) -> edit(now));
        this.list.setPrefWidth(200);
        this.list.setMinWidth(180);

        // The names stand above their fields: the long ones wrap rather than being cut.
        HBox incomingRow = new HBox(6, this.incoming, browse());
        HBox.setHgrow(this.incoming, Priority.ALWAYS);
        this.color.setMaxWidth(Double.MAX_VALUE);
        this.priority.setMaxWidth(Double.MAX_VALUE);
        VBox colorBox = DialogFrame.named(gui.t("IDS_COLOR"), this.color);
        VBox priorityBox = DialogFrame.named(gui.t("IDS_PRIORITY"), this.priority);
        HBox.setHgrow(colorBox, Priority.ALWAYS);
        HBox.setHgrow(priorityBox, Priority.ALWAYS);
        VBox form = new VBox(
                12,
                DialogFrame.named(gui.t("IDS_TITLE"), this.name),
                DialogFrame.named(gui.t("IDS_PW_INCOMING"), incomingRow),
                DialogFrame.named(gui.t("IDS_COMMENT"), this.comment),
                new HBox(12, colorBox, priorityBox),
                DialogFrame.named(gui.t("IDS_AUTOCAT_LABEL"), this.patterns),
                this.regex);
        this.name.setPrefColumnCount(24);

        Button save = Fx.primary(Fx.button(gui.t("nj.categories.save"), Icons.SAVE, this::save));
        save.disableProperty()
                .bind(Input.allValid(List.of(this.nameInput, this.incomingInput, this.commentInput, this.patternsInput))
                        .not());
        Button remove = Fx.button(gui.t("IDS_CAT_REMOVE"), Icons.DELETE, this::remove);
        remove.getStyleClass().addAll(Styles.DANGER, Styles.BUTTON_OUTLINED);
        remove.disableProperty()
                .bind(this.list.getSelectionModel().selectedItemProperty().isNull());
        Button fresh = Fx.button(gui.t("IDS_NEW"), Icons.ADD, this::fresh);
        fresh.getStyleClass().add(Styles.FLAT);
        VBox listBox = new VBox(6, this.list, fresh);
        VBox.setVgrow(this.list, Priority.ALWAYS);
        Button close = Fx.button(gui.t("IDS_FD_CLOSE"), Icons.CLOSE, this.stage::close);
        close.setCancelButton(true);
        HBox body = new HBox(16, listBox, form);
        HBox.setHgrow(form, Priority.ALWAYS);
        HBox removeBox = new HBox(remove);
        VBox root = DialogFrame.frame(
                DialogFrame.header(gui, gui.t("nj.categories.title"), this.stage::close),
                body,
                DialogFrame.footer(
                        removeBox,
                        DialogFrame.role(close, ButtonBar.ButtonData.CANCEL_CLOSE),
                        DialogFrame.role(save, ButtonBar.ButtonData.OK_DONE)));
        Scene scene = new Scene(root, 760, 520);
        gui.attach(scene);
        this.stage.setScene(scene);
        fresh();
    }

    public void show() {
        this.stage.show();
    }

    /** An empty form, for a new category. */
    void fresh() {
        this.list.getSelectionModel().clearSelection();
        this.name.clear();
        this.incoming.clear();
        this.comment.clear();
        this.patterns.clear();
        this.regex.setSelected(false);
        this.color.setValue(0);
        this.priority.setValue(DownloadPriority.AUTO);
    }

    private void edit(Category category) {
        if (category == null) {
            return;
        }
        this.name.setText(category.name());
        this.incoming.setText(category.incoming().map(Path::toString).orElse(""));
        this.comment.setText(category.comment());
        this.patterns.setText(category.patterns());
        this.regex.setSelected(category.regex());
        this.color.setValue(category.color());
        this.priority.setValue(category.priority());
    }

    /** The form as a new category, or as a change of the selected one. */
    void save() {
        Category selected = this.list.getSelectionModel().getSelectedItem();
        List<Category> all = new ArrayList<>(this.gui.model().categories());
        int id = selected != null
                ? selected.id()
                : all.stream().mapToInt(Category::id).max().orElse(0) + 1;
        Category category;
        try {
            category = new Category(
                    id,
                    this.nameInput.value(),
                    this.incomingInput.value(),
                    this.commentInput.value(),
                    this.color.getValue(),
                    this.priority.getValue(),
                    this.patternsInput.value(),
                    this.regex.isSelected());
        } catch (IllegalArgumentException e) {
            this.gui.warn().accept(this.gui.f("nj.settings.invalid", e.getMessage()));
            return;
        }
        all.removeIf(c -> c.id() == id);
        all.add(category);
        this.gui.model().setCategories(all, () -> select(id));
    }

    void remove() {
        Category selected = this.list.getSelectionModel().getSelectedItem();
        if (selected == null) {
            return;
        }
        List<Category> all = new ArrayList<>(this.gui.model().categories());
        all.removeIf(c -> c.id() == selected.id());
        this.gui.model().setCategories(all, this::fresh);
    }

    private void select(int id) {
        for (Category c : this.list.getItems()) {
            if (c.id() == id) {
                this.list.getSelectionModel().select(c);
            }
        }
    }

    private Button browse() {
        return Fx.button(this.gui.t("IDS_PW_BROWSE"), Icons.FOLDER_OPEN, () -> {
            DirectoryChooser chooser = new DirectoryChooser();
            var dir = chooser.showDialog(this.stage);
            if (dir != null) {
                this.incoming.setText(dir.getAbsolutePath());
            }
        });
    }

    private String priorityName(DownloadPriority p) {
        return switch (p) {
            case LOW -> this.gui.t("IDS_PRIOLOW");
            case NORMAL -> this.gui.t("IDS_PRIONORMAL");
            case HIGH -> this.gui.t("IDS_PRIOHIGH");
            case AUTO -> this.gui.t("IDS_PRIOAUTO");
        };
    }

    ListView<Category> list() {
        return this.list;
    }

    TextField nameField() {
        return this.name;
    }

    TextField patternsField() {
        return this.patterns;
    }

    Stage stage() {
        return this.stage;
    }

    /** A tint and its name: the colour is never the only cue. */
    private final class ColorCell extends ListCell<Integer> {
        @Override
        protected void updateItem(Integer item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setText(null);
                setGraphic(null);
            } else {
                setText(CategoriesDialog.this.gui.t(COLOR_NAMES.get(item)));
                setGraphic(Fx.categoryDot(item));
            }
        }
    }

    /** A cell showing a name for its item. */
    private static final class NamedCell<T> extends ListCell<T> {
        private final Function<T, String> text;

        NamedCell(Function<T, String> text) {
            this.text = text;
        }

        @Override
        protected void updateItem(T item, boolean empty) {
            super.updateItem(item, empty);
            setText(empty || item == null ? null : this.text.apply(item));
        }
    }
}
