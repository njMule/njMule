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
package org.njmule.gui.screens;

import atlantafx.base.controls.CustomTextField;
import atlantafx.base.controls.ToggleSwitch;
import atlantafx.base.theme.Styles;
import atlantafx.base.theme.Tweaks;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.ToLongFunction;
import java.util.regex.Pattern;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.StringBinding;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.value.ObservableValue;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import org.njmule.core.api.Ed2kLink;
import org.njmule.core.api.FileKind;
import org.njmule.core.api.SharedDirectory;
import org.njmule.core.api.SharedFileInfo;
import org.njmule.core.api.UploadPriority;
import org.njmule.gui.dialogs.CommentDialog;
import org.njmule.gui.infra.Folders;
import org.njmule.gui.model.EngineModel;
import org.njmule.gui.model.LiveList;
import org.njmule.gui.ui.Ask;
import org.njmule.gui.ui.ClipboardWatch;
import org.njmule.gui.ui.Fx;
import org.njmule.gui.ui.Gui;
import org.njmule.gui.ui.Icon;
import org.njmule.gui.ui.Icons;
import org.njmule.gui.ui.Screen;
import org.njmule.gui.ui.Segments;
import org.njmule.gui.ui.TableLayout;

/**
 * The folders we share in a tree beside the files they hold: all of them, the incoming folder, and each folder shared
 * with the subfolders that hold files; under the tree, what to do with the folder chosen.
 */
public final class SharedScreen implements Screen {

    /** What the counters of the table count: this run, or every run. */
    enum Counter {
        SESSION,
        TOTAL
    }

    /** A place of the tree. */
    enum Kind {
        ALL,
        INCOMING,
        FOLDER,
        SUBFOLDER
    }

    /** A place of the tree: all files, the incoming folder, a folder shared or one of its subfolders. */
    record Place(Kind kind, Path path, boolean subdirectories) {
        /** Whether {@code file} lies in this place. */
        boolean holds(Path file) {
            return switch (this.kind) {
                case ALL -> true;
                case FOLDER -> this.subdirectories ? file.startsWith(this.path) : this.path.equals(file.getParent());
                case INCOMING, SUBFOLDER -> file.startsWith(this.path);
            };
        }
    }

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final KeyCombination FIND_KEYS = new KeyCodeCombination(KeyCode.F, KeyCombination.SHORTCUT_DOWN);

    private final Gui gui;
    private StringBinding summary;
    private Node view;
    private TableView<LiveList.Row<SharedFileInfo>> table;
    private final TreeView<Place> folders = new TreeView<>(new TreeItem<>());
    private List<SharedDirectory> directories = List.of();
    private final ObjectProperty<Predicate<SharedFileInfo>> shown = new SimpleObjectProperty<>(f -> true);
    private final CustomTextField filter = new CustomTextField();
    private Segments<Counter> counters;
    private final Label placeTitle = new Label();
    private final Label placeFacts = new Label();
    private final ToggleSwitch subfolders = new ToggleSwitch();
    private final Label subfoldersName = new Label();
    private final Button openPlace = new Button();
    private final Button unshare = new Button();
    private final Label incomingNote = new Label();
    private boolean placing;

    public SharedScreen(Gui gui) {
        this.gui = gui;
    }

    @Override
    public String id() {
        return "shared";
    }

    @Override
    public String title() {
        return this.gui.t("IDS_EM_FILES");
    }

    @Override
    public Icons icon() {
        return Icons.SHARED;
    }

    @Override
    public ObservableValue<String> summary() {
        if (this.summary == null) {
            EngineModel model = this.gui.model();
            // The files shared and the uploads running; nothing while nothing is shared.
            this.summary = Bindings.createStringBinding(
                    () -> model.sharedFiles().rows().isEmpty()
                            ? ""
                            : this.gui.f(
                                    "nj.summary.shared",
                                    model.sharedFiles().rows().size(),
                                    model.uploads().rows().size()),
                    model.sharedFiles().rows(),
                    model.uploads().rows());
        }
        return this.summary;
    }

    @Override
    public Node view() {
        if (this.view == null) {
            this.view = build();
        }
        return this.view;
    }

    private Node build() {
        this.counters = new Segments<>(List.of(Counter.values()), this::counterName);
        this.counters.node().setId("shared-counters");
        this.table = Fx.table(this.gui.model().sharedFiles(), this.gui.t("nj.shared.none"), this.shown);
        TableColumn<LiveList.Row<SharedFileInfo>, String> name =
                Fx.ownDirection(Fx.column(this.gui.t("IDS_DL_FILENAME"), 340, SharedFileInfo::name));
        name.setCellFactory(c -> new NameCell());
        TableColumn<LiveList.Row<SharedFileInfo>, Long> accepted =
                counted("IDS_SF_ACCEPTS", 100, s -> s.accepted(), s -> s.totalAccepted(), String::valueOf);
        TableColumn<LiveList.Row<SharedFileInfo>, String> parent = Fx.leftToRight(Fx.column(
                this.gui.t("nj.shared.folder"),
                260,
                (SharedFileInfo f) -> String.valueOf(f.path().getParent())));
        // The tree beside tells the folder; the column menu brings these back.
        accepted.setVisible(false);
        parent.setVisible(false);
        this.table
                .getColumns()
                .addAll(List.of(
                        name,
                        Fx.numeric(Fx.column(
                                this.gui.t("IDS_DL_SIZE"), 85, SharedFileInfo::size, this.gui.formats()::size)),
                        Fx.column(this.gui.t("IDS_PRIORITY"), 95, SharedFileInfo::priority, this::priorityName),
                        counted("IDS_SF_REQUESTS", 85, s -> s.requests(), s -> s.totalRequests(), String::valueOf),
                        accepted,
                        counted(
                                "IDS_SF_TRANSFERRED",
                                110,
                                s -> s.transferred(),
                                s -> s.totalTransferred(),
                                this.gui.formats()::size),
                        parent));
        TableLayout.remember(this.table, "shared", this.gui.settings());
        this.table.setPlaceholder(Fx.emptyState(
                Icons.SHARED,
                this.gui.t("nj.shared.none"),
                Fx.button(this.gui.t("IDS_SHAREDIR"), Icons.FOLDER_ADD, this::chooseDirectory)));
        // The counters count this run or every run: the cells read them again.
        this.counters.valueProperty().addListener((o, was, now) -> this.table.refresh());
        MenuItem copy = Fx.menuItem(this.gui.t("nj.copyLink"), Icons.COPY, this::copyLinks);
        MenuItem html = Fx.menuItem(this.gui.t("IDS_DL_LINK2"), Icons.CODE, this::copyHtml);
        Menu priority = new Menu(this.gui.t("IDS_PRIORITY"), Fx.icon(Icons.PRIORITY));
        priority.getItems().setAll(priorityItems());
        MenuItem rename = Fx.menuItem(this.gui.t("IDS_RENAME"), Icons.EDIT, this::renameSelected);
        MenuItem comment = Fx.menuItem(this.gui.t("IDS_CMT_ADD"), Icons.COMMENT, this::commentSelected);
        MenuItem folder = Fx.menuItem(this.gui.t("IDS_OPENFOLDER"), Icons.FOLDER_OPEN, this::openFolder);
        ContextMenu menu = new ContextMenu(folder, rename, comment, new SeparatorMenuItem(), priority, copy, html);
        menu.setOnShowing(e -> {
            int selected = Fx.selected(this.table).size();
            rename.setDisable(selected != 1);
            comment.setDisable(selected != 1);
            folder.setDisable(selected != 1);
            priority.setDisable(selected == 0);
            copy.setDisable(selected == 0);
            html.setDisable(selected == 0);
        });
        this.table.setContextMenu(menu);
        return Fx.screen(new HBox(16, side(), files()));
    }

    // The filter, the counters and what to do with the files chosen, above the table.
    private Node files() {
        this.filter.setPromptText(this.gui.t("nj.shared.filter"));
        this.filter.setLeft(Fx.icon(Icons.FILTER));
        this.filter.setPrefColumnCount(16);
        this.filter.setId("shared-filter");
        this.filter.textProperty().addListener((o, was, now) -> narrow());
        this.filter.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ESCAPE) {
                this.filter.clear();
            }
        });
        MenuButton priorities = new MenuButton(this.gui.t("IDS_PRIORITY"), Fx.icon(Icons.PRIORITY));
        priorities.getItems().setAll(priorityItems());
        Button copy = Fx.iconButton(this.gui.t("nj.copyLink"), Icons.COPY, this::copyLinks);
        MenuButton more = new MenuButton(
                null,
                Fx.icon(Icons.MORE),
                Fx.menuItem(this.gui.t("IDS_OPENFOLDER"), Icons.FOLDER_OPEN, this::openFolder),
                Fx.menuItem(this.gui.t("IDS_RENAME"), Icons.EDIT, this::renameSelected),
                Fx.menuItem(this.gui.t("IDS_CMT_ADD"), Icons.COMMENT, this::commentSelected),
                Fx.menuItem(this.gui.t("IDS_DL_LINK2"), Icons.CODE, this::copyHtml));
        more.getStyleClass().addAll(Styles.BUTTON_ICON, Tweaks.NO_ARROW);
        more.setTooltip(new Tooltip(this.gui.t("IDS_MORE")));
        more.setAccessibleText(this.gui.t("IDS_MORE"));
        Fx.needSelection(this.table, priorities, copy, more);
        var tools = Fx.toolRow(this.filter, this.counters.node(), Fx.spacer(), priorities, copy, more);
        VBox box = new VBox(10, tools, this.table);
        VBox.setVgrow(this.table, Priority.ALWAYS);
        HBox.setHgrow(box, Priority.ALWAYS);
        // Ctrl+F finds among the files, as in the transfers.
        box.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, e -> {
            if (FIND_KEYS.match(e)) {
                this.filter.requestFocus();
                e.consume();
            }
        });
        return box;
    }

    // The only way to share a folder, the tree of the places, and what to do with the place chosen.
    private Node side() {
        Button share = Fx.primary(Fx.button(this.gui.t("IDS_SHAREDIR"), Icons.FOLDER_ADD, this::chooseDirectory));
        share.setId("share-folder");
        share.setMaxWidth(Double.MAX_VALUE);
        this.folders.setShowRoot(false);
        this.folders.setCellFactory(t -> new PlaceCell());
        this.folders.getStyleClass().add("shared-places");
        this.folders.setId("shared-places");
        this.folders.getSelectionModel().selectedItemProperty().addListener((o, was, now) -> {
            if (!this.placing) {
                showPlace();
                narrow();
            }
        });
        // Sharing or unsharing changes the files shared, wherever it was done: the folders are read again then.
        this.gui.subscriptions().onListChange(this.gui.model().sharedFiles().rows(), c -> loadFolders());
        this.gui.subscriptions().onChange(this.gui.model().preferencesProperty(), (was, now) -> fillTree());
        loadFolders();
        VBox side = new VBox(10, share, this.folders, placePanel());
        VBox.setVgrow(this.folders, Priority.ALWAYS);
        side.getStyleClass().add("shared-side");
        side.setMinWidth(220);
        side.setPrefWidth(270);
        side.setMaxWidth(300);
        return side;
    }

    // The place chosen: its path, its files, and the switch and buttons that act on it.
    private Node placePanel() {
        this.placeTitle.getStyleClass().add(Styles.TEXT_BOLD);
        Fx.wrap(this.placeTitle);
        this.placeTitle.setMinHeight(Region.USE_PREF_SIZE);
        Fx.leftToRight(this.placeTitle);
        this.placeFacts.getStyleClass().add("text-muted");
        this.subfoldersName.setText(this.gui.t("IDS_SHAREDIRSUB"));
        Fx.wrap(this.subfoldersName);
        this.subfoldersName.setMinWidth(0);
        this.subfoldersName.setOnMouseClicked(e -> {
            if (!this.subfolders.isDisabled()) {
                this.subfolders.setSelected(!this.subfolders.isSelected());
            }
        });
        this.subfolders.setAccessibleText(this.gui.t("IDS_SHAREDIRSUB"));
        this.subfolders.selectedProperty().addListener((o, was, now) -> {
            Place place = chosen();
            if (!this.placing && place.kind() == Kind.FOLDER && now != place.subdirectories()) {
                share(place.path(), now);
            }
        });
        HBox.setHgrow(this.subfoldersName, Priority.ALWAYS);
        HBox subRow = new HBox(8, this.subfoldersName, this.subfolders);
        subRow.setAlignment(Pos.CENTER_LEFT);
        subRow.managedProperty().bind(subRow.visibleProperty());
        subRow.visibleProperty().bind(this.subfolders.disableProperty().not());
        this.openPlace.setText(this.gui.t("IDS_OPENFOLDER"));
        this.openPlace.setGraphic(Fx.icon(Icons.FOLDER_OPEN));
        this.openPlace.getStyleClass().add(Styles.SMALL);
        this.openPlace.setOnAction(e -> openPlace());
        this.unshare.setText(this.gui.t("IDS_UNSHAREDIR"));
        this.unshare.setGraphic(Fx.icon(Icons.REMOVE_CIRCLE));
        this.unshare.getStyleClass().addAll(Styles.SMALL, Styles.FLAT);
        this.unshare.setId("unshare-folder");
        this.unshare.setOnAction(e -> unshareFolder());
        for (Button b : List.of(this.openPlace, this.unshare)) {
            Fx.whole(b);
            b.managedProperty().bind(b.visibleProperty());
        }
        FlowPane buttons = new FlowPane(6, 6, this.openPlace, this.unshare);
        this.incomingNote.setText(this.gui.t("nj.shared.incomingNote"));
        Fx.wrap(this.incomingNote);
        this.incomingNote.setMinHeight(Region.USE_PREF_SIZE);
        this.incomingNote.getStyleClass().add("text-muted");
        this.incomingNote.managedProperty().bind(this.incomingNote.visibleProperty());
        VBox panel = new VBox(8, new VBox(2, this.placeTitle, this.placeFacts), subRow, buttons, this.incomingNote);
        panel.getStyleClass().add("shared-place");
        return panel;
    }

    void loadFolders() {
        this.gui.model().sharedDirectories(dirs -> {
            this.directories = List.copyOf(dirs);
            fillTree();
        });
    }

    // All files, the incoming folder, then each folder shared with the subfolders that hold files; the place chosen
    // stays chosen.
    private void fillTree() {
        List<Path> files = this.gui.model().sharedFiles().rows().stream()
                .map(r -> r.get().path())
                .toList();
        Place was = chosen();
        TreeItem<Place> root = this.folders.getRoot();
        List<TreeItem<Place>> top = new ArrayList<>();
        top.add(new TreeItem<>(new Place(Kind.ALL, Path.of(""), true)));
        Path incoming = incoming();
        top.add(new TreeItem<>(new Place(Kind.INCOMING, incoming, true)));
        this.directories.stream()
                .filter(d -> !d.path().equals(incoming))
                .sorted(Comparator.comparing(d -> d.path().toString()))
                .forEach(d -> {
                    TreeItem<Place> item = new TreeItem<>(new Place(Kind.FOLDER, d.path(), d.subdirectories()));
                    if (d.subdirectories()) {
                        subfoldersOf(d.path(), files)
                                .forEach(sub ->
                                        item.getChildren().add(new TreeItem<>(new Place(Kind.SUBFOLDER, sub, true))));
                        item.setExpanded(true);
                    }
                    top.add(item);
                });
        this.placing = true;
        try {
            root.getChildren().setAll(top);
            TreeItem<Place> again = find(root, was);
            this.folders.getSelectionModel().select(again == null ? top.getFirst() : again);
        } finally {
            this.placing = false;
        }
        this.folders.refresh();
        showPlace();
        narrow();
    }

    // The folders right under a shared one that hold files, in order.
    private static List<Path> subfoldersOf(Path folder, List<Path> files) {
        Set<Path> found = new LinkedHashSet<>();
        for (Path file : files) {
            Path parent = file.getParent();
            if (file.startsWith(folder) && parent != null && !parent.equals(folder)) {
                found.add(folder.resolve(folder.relativize(file).getName(0)));
            }
        }
        return found.stream().sorted().toList();
    }

    private static TreeItem<Place> find(TreeItem<Place> parent, Place place) {
        if (place == null) {
            return null;
        }
        for (TreeItem<Place> item : parent.getChildren()) {
            Place p = item.getValue();
            if (p.kind() == place.kind() && p.path().equals(place.path())) {
                return item;
            }
            TreeItem<Place> below = find(item, place);
            if (below != null) {
                return below;
            }
        }
        return null;
    }

    private Path incoming() {
        return this.gui.model().preferencesProperty().get().effectiveIncomingDir(this.gui.directories());
    }

    private Place chosen() {
        TreeItem<Place> item = this.folders.getSelectionModel().getSelectedItem();
        return item == null ? new Place(Kind.ALL, Path.of(""), true) : item.getValue();
    }

    // The files of the place chosen whose names hold every word typed.
    private void narrow() {
        Place place = chosen();
        List<String> words = WHITESPACE
                .splitAsStream(this.filter.getText().strip().toLowerCase(Locale.ROOT))
                .filter(w -> !w.isEmpty())
                .toList();
        this.shown.set(
                f -> place.holds(f.path()) && words.stream().allMatch(f.name().toLowerCase(Locale.ROOT)::contains));
    }

    private void showPlace() {
        Place place = chosen();
        List<SharedFileInfo> in = this.gui.model().sharedFiles().rows().stream()
                .map(LiveList.Row::get)
                .filter(f -> place.holds(f.path()))
                .toList();
        this.placeTitle.setText(
                switch (place.kind()) {
                    case ALL -> this.gui.t("nj.shared.all");
                    case INCOMING, FOLDER, SUBFOLDER -> place.path().toString();
                });
        this.placeFacts.setText(this.gui.f(
                "nj.shared.files",
                in.size(),
                this.gui
                        .formats()
                        .size(in.stream().mapToLong(SharedFileInfo::size).sum())));
        this.placing = true;
        try {
            this.subfolders.setSelected(place.subdirectories());
            this.subfolders.setDisable(place.kind() != Kind.FOLDER);
        } finally {
            this.placing = false;
        }
        this.openPlace.setVisible(place.kind() != Kind.ALL);
        this.unshare.setVisible(place.kind() == Kind.FOLDER);
        this.incomingNote.setVisible(place.kind() == Kind.INCOMING);
    }

    private void openPlace() {
        Place place = chosen();
        if (place.kind() != Kind.ALL) {
            try {
                Folders.open(place.path());
            } catch (IOException e) {
                this.gui.warn().accept(e.getMessage());
            }
        }
    }

    void unshareFolder() {
        Place place = chosen();
        if (place.kind() == Kind.FOLDER) {
            this.gui.model().unshareDirectory(place.path(), this::loadFolders);
        }
    }

    /** A place of the tree: its icon, its name, a lock on the incoming folder, and its files. */
    private final class PlaceCell extends TreeCell<Place> {
        private final Icon icon = Fx.icon(Icons.FOLDER_OPEN);
        private final Label name = new Label();
        private final Icon lock = Fx.icon(Icons.LOCK);
        private final Label count = new Label();
        private final HBox box;

        PlaceCell() {
            this.name.setMinWidth(0);
            this.count.getStyleClass().add("text-muted");
            this.count.setMinWidth(Region.USE_PREF_SIZE);
            Region gap = new Region();
            HBox.setHgrow(gap, Priority.ALWAYS);
            this.lock.getStyleClass().add("place-lock");
            this.box = new HBox(8, this.icon, this.name, this.lock, gap, this.count);
            this.box.setAlignment(Pos.CENTER_LEFT);
            // As wide as the tree, never wider: a long path ends in an ellipsis instead of a scroll bar.
            setPrefWidth(0);
        }

        @Override
        protected void updateItem(Place place, boolean empty) {
            super.updateItem(place, empty);
            setText(null);
            if (empty || place == null) {
                setGraphic(null);
                setTooltip(null);
                return;
            }
            SharedScreen self = SharedScreen.this;
            this.icon.setGlyph(
                    switch (place.kind()) {
                        case ALL -> Icons.SHARED;
                        case INCOMING -> Icons.DOWNLOAD;
                        case FOLDER, SUBFOLDER -> Icons.FOLDER_OPEN;
                    });
            this.name.setText(
                    switch (place.kind()) {
                        case ALL -> self.gui.t("nj.shared.all");
                        case INCOMING -> self.gui.t("IDS_PW_INCOMING");
                        case FOLDER -> place.path().toString();
                        case SUBFOLDER -> String.valueOf(place.path().getFileName());
                    });
            this.lock.setVisible(place.kind() == Kind.INCOMING);
            this.lock.setManaged(place.kind() == Kind.INCOMING);
            long files = self.gui.model().sharedFiles().rows().stream()
                    .filter(r -> place.holds(r.get().path()))
                    .count();
            this.count.setText(self.gui.formats().count(files));
            setTooltip(
                    place.kind() == Kind.ALL ? null : new Tooltip(place.path().toString()));
            setGraphic(this.box);
        }
    }

    /** The name with the icon of what the file holds. */
    private static final class NameCell extends TableCell<LiveList.Row<SharedFileInfo>, String> {
        private final Icon kind = Fx.icon(Icons.FILE);
        private final Label name = new Label();
        private final HBox box = new HBox(8, this.kind, this.name);

        NameCell() {
            this.kind.getStyleClass().add("file-kind");
            this.box.setAlignment(Pos.CENTER_LEFT);
            this.name.setMinWidth(0);
            Fx.ownDirection(this.name);
        }

        @Override
        protected void updateItem(String text, boolean empty) {
            super.updateItem(text, empty);
            setText(null);
            if (empty || text == null) {
                setGraphic(null);
                setTooltip(null);
                setAccessibleText(null);
                return;
            }
            this.kind.setGlyph(Fx.fileIcon(FileKind.of(text)));
            this.name.setText(text);
            setAccessibleText(text);
            setTooltip(new Tooltip(text));
            setGraphic(this.box);
        }
    }

    // A count of this run or of all runs, as the counters say; sorted by what it shows.
    private TableColumn<LiveList.Row<SharedFileInfo>, Long> counted(
            String title,
            double width,
            ToLongFunction<SharedFileInfo.Statistics> session,
            ToLongFunction<SharedFileInfo.Statistics> total,
            Function<Long, String> text) {
        return Fx.numeric(Fx.column(
                this.gui.t(title),
                width,
                (SharedFileInfo f) -> this.counters.getValue() == Counter.SESSION
                        ? session.applyAsLong(f.statistics())
                        : total.applyAsLong(f.statistics()),
                text));
    }

    private String counterName(Counter counter) {
        return this.gui.t(counter == Counter.SESSION ? "IDS_STATS_SESSION" : "IDS_STATS_CUMULATIVE");
    }

    TreeView<Place> places() {
        return this.folders;
    }

    Segments<Counter> counters() {
        return this.counters;
    }

    ToggleSwitch subfoldersSwitch() {
        return this.subfolders;
    }

    Label placeTitle() {
        return this.placeTitle;
    }

    CustomTextField filterField() {
        return this.filter;
    }

    /** Chooses the place of {@code path} in the tree, for tests. */
    void choosePlace(Path path) {
        TreeItem<Place> item = findPath(this.folders.getRoot(), path);
        if (item != null) {
            this.folders.getSelectionModel().select(item);
        }
    }

    private static TreeItem<Place> findPath(TreeItem<Place> parent, Path path) {
        for (TreeItem<Place> item : parent.getChildren()) {
            if (Objects.equals(item.getValue().path(), path) && item.getValue().kind() != Kind.ALL) {
                return item;
            }
            TreeItem<Place> below = findPath(item, path);
            if (below != null) {
                return below;
            }
        }
        return null;
    }

    private void chooseDirectory() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle(this.gui.t("IDS_SHAREDIR"));
        var dir = chooser.showDialog(this.view.getScene().getWindow());
        if (dir != null && !confirmShare(dir.toPath()) && SharedDirectory.isSensitive(dir.toPath())) {
            // The safe answer to sharing a whole drive or a personal folder is choosing another one.
            chooseDirectory();
        }
    }

    /**
     * Asks to share {@code directory}, with its subfolders unless unticked; a folder where personal or system files may
     * be gets a warning whose default is not sharing it. Whether it was shared.
     */
    boolean confirmShare(Path directory) {
        CheckBox subs = new CheckBox(this.gui.t("IDS_SHAREDIRSUB"));
        subs.setSelected(true);
        boolean sensitive = SharedDirectory.isSensitive(directory);
        Ask ask = Ask.about(this.gui, this.gui.t(sensitive ? "nj.shared.shareAllTitle" : "nj.shared.shareTitle"))
                .names(List.of(directory.toString()));
        if (sensitive) {
            ask.warning()
                    .says(this.gui.t("nj.shared.sensitiveText"))
                    .with(subs)
                    .note(Icons.SHIELD, this.gui.t("nj.shared.asksAlso"))
                    .risky(this.gui.t("nj.shared.shareAll"), this.gui.t("nj.shared.chooseOther"));
        } else {
            ask.says(this.gui.t("nj.shared.shareText"))
                    .with(subs)
                    .answers(this.gui.t("nj.shared.share"), this.gui.t("IDS_CANCEL"));
        }
        boolean yes = ask.owner(this.view.getScene().getWindow()).ask();
        if (yes) {
            share(directory, subs.isSelected());
        }
        return yes;
    }

    void share(Path directory, boolean subdirectories) {
        this.gui.model().shareDirectory(directory, subdirectories);
    }

    // Each level, for every selected file.
    private List<MenuItem> priorityItems() {
        List<MenuItem> items = new ArrayList<>();
        for (UploadPriority priority : UploadPriority.values()) {
            MenuItem item = new MenuItem(priorityName(priority));
            item.setOnAction(e -> prioritize(priority));
            items.add(item);
        }
        return items;
    }

    void prioritize(UploadPriority priority) {
        Fx.selected(this.table).forEach(f -> this.gui.model().setUploadPriority(f.hash(), priority));
    }

    private String priorityName(UploadPriority priority) {
        return switch (priority) {
            case VERY_LOW -> this.gui.t("IDS_PRIOVERYLOW");
            case LOW -> this.gui.t("IDS_PRIOLOW");
            case NORMAL -> this.gui.t("IDS_PRIONORMAL");
            case HIGH -> this.gui.t("IDS_PRIOHIGH");
            case RELEASE -> this.gui.t("IDS_PRIORELEASE");
            case AUTO -> this.gui.t("IDS_PRIOAUTO");
        };
    }

    // One file at a time, in its own directory.
    void renameSelected() {
        List<SharedFileInfo> selected = Fx.selected(this.table);
        if (selected.size() != 1) {
            return;
        }
        SharedFileInfo file = selected.getFirst();
        TextField name = Fx.leftToRight(new TextField(file.name()));
        Ask ask = Ask.about(this.gui, this.gui.t("nj.shared.renameTitle"))
                .field(this.gui.t("nj.shared.newName"), name, this.gui.t("nj.shared.renameHint"))
                .answers(this.gui.t("IDS_RENAME"), this.gui.t("IDS_CANCEL"))
                .owner(this.table.getScene().getWindow());
        Platform.runLater(() -> {
            name.requestFocus();
            name.selectAll();
        });
        if (ask.ask()) {
            String typed = name.getText().strip();
            if (!typed.isEmpty() && !typed.equals(file.name())) {
                this.gui.model().renameShared(file.hash(), typed);
            }
        }
    }

    void commentSelected() {
        List<SharedFileInfo> selected = Fx.selected(this.table);
        if (selected.size() == 1) {
            SharedFileInfo f = selected.getFirst();
            new CommentDialog(this.gui, this.table.getScene().getWindow(), f.hash(), f.name(), false).show();
        }
    }

    void openFolder() {
        List<SharedFileInfo> selected = Fx.selected(this.table);
        if (selected.size() == 1) {
            try {
                Folders.reveal(selected.getFirst().path());
            } catch (IOException e) {
                this.gui.warn().accept(e.getMessage());
            }
        }
    }

    void copyLinks() {
        copy(Ed2kLink.FileLink::toUri);
    }

    void copyHtml() {
        copy(Ed2kLink.FileLink::toHtml);
    }

    private void copy(Function<Ed2kLink.FileLink, String> form) {
        List<SharedFileInfo> selected = Fx.selected(this.table);
        if (!selected.isEmpty()) {
            ClipboardWatch.copy(String.join(
                    "\n", selected.stream().map(f -> form.apply(f.link())).toList()));
        }
    }

    TableView<LiveList.Row<SharedFileInfo>> table() {
        return this.table;
    }
}
