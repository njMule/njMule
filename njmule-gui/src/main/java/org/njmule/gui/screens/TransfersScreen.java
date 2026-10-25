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
import atlantafx.base.theme.Styles;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.StringBinding;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.value.ObservableValue;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.css.PseudoClass;
import javafx.event.EventTarget;
import javafx.geometry.HPos;
import javafx.geometry.NodeOrientation;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.Skin;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.control.skin.TableRowSkin;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.Clipboard;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;
import org.njmule.core.api.BanInfo;
import org.njmule.core.api.Category;
import org.njmule.core.api.DownloadInfo;
import org.njmule.core.api.DownloadPriority;
import org.njmule.core.api.Ed2kLink;
import org.njmule.core.api.FileKind;
import org.njmule.core.api.Hash16;
import org.njmule.core.api.KnownClient;
import org.njmule.core.api.ServerAddress;
import org.njmule.core.api.SourceInfo;
import org.njmule.core.api.UploadInfo;
import org.njmule.core.config.Preferences;
import org.njmule.gui.dialogs.AddLinksDialog;
import org.njmule.gui.dialogs.CategoriesDialog;
import org.njmule.gui.dialogs.ClientFilesDialog;
import org.njmule.gui.dialogs.CommentDialog;
import org.njmule.gui.infra.Folders;
import org.njmule.gui.infra.Preview;
import org.njmule.gui.model.EngineModel;
import org.njmule.gui.model.LiveList;
import org.njmule.gui.ui.Ask;
import org.njmule.gui.ui.ClipboardWatch;
import org.njmule.gui.ui.FilterChips;
import org.njmule.gui.ui.Flags;
import org.njmule.gui.ui.Fx;
import org.njmule.gui.ui.Gui;
import org.njmule.gui.ui.Icon;
import org.njmule.gui.ui.Icons;
import org.njmule.gui.ui.Navigate;
import org.njmule.gui.ui.Screen;
import org.njmule.gui.ui.Segments;
import org.njmule.gui.ui.SystemIcons;
import org.njmule.gui.ui.TableLayout;
import org.njmule.gui.ui.ThinBar;

/**
 * The transfers: the downloads, narrowed by state, category and name, with the details of the chosen one beside them;
 * and, one list at a time, the clients we upload to, those waiting and the banned.
 */
public final class TransfersScreen implements Screen {

    // Every download, whatever its category.
    private static final int SHOW_ALL = -1;
    // The names a cancel question lists at most.
    private static final int CANCEL_NAMES_SHOWN = 5;
    // The details at first, until the head of a download asks for more; the room under its buttons; what the list and
    // the details keep at least.
    private static final double DETAILS_HEIGHT = 300;
    private static final double DETAILS_MARGIN = 16;
    private static final double LEAST_HEIGHT = 120;
    // A download opened onto its sources highlights its own line only.
    private static final PseudoClass OPENED_ROW = PseudoClass.getPseudoClass("opened");
    private static final KeyCombination COPY_LINKS = new KeyCodeCombination(KeyCode.C, KeyCombination.SHORTCUT_DOWN);

    /** The lists of the screen, one shown at a time. */
    enum Shelf {
        DOWNLOADS("IDS_TW_DOWNLOADS"),
        UPLOADS("IDS_TW_UPLOADS"),
        QUEUE("IDS_ONQUEUE"),
        BANNED("IDS_BANNED"),
        KNOWN("IDS_CLIENTLIST");

        private final String title;

        Shelf(String title) {
            this.title = title;
        }
    }

    /** The states the chips narrow the downloads to. */
    enum StateFilter {
        ALL("IDS_ALL"),
        DOWNLOADING("IDS_DOWNLOADING"),
        WAITING("IDS_WAITING"),
        NO_SOURCES("nj.transfers.stateNoSources"),
        PAUSED("IDS_PAUSED"),
        ERRORS("IDS_ERRORLIKE"),
        COMPLETE("IDS_COMPLETE");

        private final String title;

        StateFilter(String title) {
            this.title = title;
        }

        // Paused and stopped go together, as a full disk and other errors do.
        boolean keeps(DownloadInfo d) {
            Shown state = shown(d);
            return switch (this) {
                case ALL -> true;
                case DOWNLOADING -> state == Shown.DOWNLOADING;
                case WAITING -> state == Shown.WAITING;
                case NO_SOURCES -> state == Shown.NO_SOURCES;
                case PAUSED -> state == Shown.PAUSED || state == Shown.STOPPED;
                case ERRORS -> state == Shown.NO_SPACE || state == Shown.ERROR;
                case COMPLETE -> state == Shown.COMPLETE;
            };
        }
    }

    private final Gui gui;
    private StringBinding summary;
    private Node view;
    private TableView<LiveList.Row<DownloadInfo>> downloads;
    // The downloads opened onto their sources in the list, by hash.
    private final Set<Hash16> opened = new HashSet<>();
    // What each source had sent at the last reading, and its speed since, by file and address.
    private final Map<String, long[]> sourceSeen = new HashMap<>();
    private final Map<String, Long> sourceRates = new HashMap<>();
    // The rows of the downloads the list made, to show the sources of the opened ones as they change.
    private final Set<DownloadRow> rows = Collections.newSetFromMap(new WeakHashMap<>());
    private TableView<LiveList.Row<UploadInfo>> uploads;
    private TableView<LiveList.Row<UploadInfo>> queue;
    private ListView<LiveList.Row<SourceInfo>> sources;
    private TableView<LiveList.Row<BanInfo>> bans;
    private TableView<LiveList.Row<KnownClient>> known;
    private Segments<Shelf> shelves;
    private FilterChips<StateFilter> states;
    private final ComboBox<Integer> category = new ComboBox<>();
    // How a cancel is asked: a dialog, or the answer a test gives.
    private BiPredicate<String, Integer> cancelAnswer = this::askCancel;
    // How the links to add are confirmed: the dialog shown, or the answer a test gives.
    private Predicate<AddLinksDialog> addAnswer = AddLinksDialog::ask;
    private final ObjectProperty<Predicate<DownloadInfo>> shown = new SimpleObjectProperty<>(d -> true);
    private final ObjectProperty<Predicate<LiveList.Row<SourceInfo>>> shownSources =
            new SimpleObjectProperty<>(s -> false);
    private final CustomTextField filter = new CustomTextField();
    private final ToggleButton detailsButton = new ToggleButton();
    // The downloads over the details of the chosen one, placed once the split has a height.
    private SplitPane split;
    // Whether the user holds the divider: only a height left by the mouse is remembered, not one a layout gives.
    private boolean dividerHeld;
    private DownloadDetails details;
    private int shownCategory = SHOW_ALL;

    public TransfersScreen(Gui gui) {
        this.gui = gui;
    }

    @Override
    public String id() {
        return "transfers";
    }

    @Override
    public String title() {
        return this.gui.t("IDS_EM_TRANS");
    }

    @Override
    public Icons icon() {
        return Icons.TRANSFERS;
    }

    @Override
    public ObservableValue<String> summary() {
        if (this.summary == null) {
            EngineModel model = this.gui.model();
            this.summary = Bindings.createStringBinding(
                    this::summaryText, model.downloads().rows(), model.statusProperty());
        }
        return this.summary;
    }

    // The downloads running and the rate of all of them; nothing while there are none.
    private String summaryText() {
        EngineModel model = this.gui.model();
        if (model.downloads().rows().isEmpty()) {
            return "";
        }
        long running = model.downloads().rows().stream()
                .filter(r -> r.get().status() == DownloadInfo.Status.DOWNLOADING)
                .count();
        return this.gui.f(
                "nj.summary.transfers",
                running,
                this.gui.formats().rate(model.statusProperty().get().downloadBytesPerSecond()));
    }

    @Override
    public Node view() {
        if (this.view == null) {
            this.view = build();
        }
        return this.view;
    }

    private Node build() {
        this.downloads = Fx.table(this.gui.model().downloads(), this.gui.t("nj.transfers.none"), this.shown);
        this.downloads.setId("downloads");
        this.downloads.setRowFactory(t -> new DownloadRow());
        downloadColumns();
        // The sources shown under opened downloads follow theirs, with the speed each sends at.
        // In place: recreating the rows each time would make the list flicker.
        this.gui.subscriptions().onListChange(this.gui.model().sources().rows(), c -> {
            measureSources();
            showOpened();
        });
        this.gui.subscriptions().onListChange(this.downloads.getVisibleLeafColumns(), c -> {
            this.rows.forEach(DownloadRow::forgetSources);
            showOpened();
        });
        this.downloads.setContextMenu(downloadMenu());
        // Delete cancels the chosen downloads, after the question; Ctrl+C copies their links.
        this.downloads.addEventHandler(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.DELETE) {
                askCancelSelected();
                e.consume();
            } else if (e.getCode() == KeyCode.RIGHT || e.getCode() == KeyCode.LEFT) {
                showSources(Fx.selected(this.downloads), e.getCode() == KeyCode.RIGHT);
                e.consume();
            } else if (e.getCode() == KeyCode.ENTER
                    && Fx.selected(this.downloads).size() == 1) {
                activate(Fx.selected(this.downloads).getFirst());
                e.consume();
            } else if (COPY_LINKS.match(e)) {
                copyLinks();
                e.consume();
            }
        });
        // An empty list says how to fill it, with the two ways to.
        Button search = Fx.button(
                this.gui.t("IDS_EM_SEARCH"), Icons.SEARCH, () -> this.downloads.fireEvent(new Navigate("search")));
        Button paste = Fx.button(this.gui.t("IDS_SW_DIRECTDOWNLOAD"), Icons.PASTE, () -> addLinks(clipboardLinks()));
        this.downloads.setPlaceholder(Fx.emptyState(Icons.DOWNLOAD, this.gui.t("nj.transfers.none"), search, paste));
        this.gui.subscriptions().onListChange(this.gui.model().categories(), c -> {
            showCategories();
            this.downloads.refresh();
        });

        this.uploads = uploadTable(UploadInfo.State.UPLOADING, "uploads");
        this.queue = uploadTable(UploadInfo.State.QUEUED, "queue");
        this.bans = banTable();
        this.known = knownTable();
        this.sources = sourceList();
        buildDetails();

        // One list at a time, chosen by segments that tell how many rows each has.
        Map<Shelf, Node> lists = new EnumMap<>(Shelf.class);
        lists.put(Shelf.DOWNLOADS, downloadsPart());
        lists.put(Shelf.UPLOADS, this.uploads);
        lists.put(Shelf.QUEUE, this.queue);
        lists.put(Shelf.BANNED, this.bans);
        lists.put(Shelf.KNOWN, this.known);
        this.shelves = new Segments<>(List.of(Shelf.values()), s -> this.gui.t(s.title));
        this.shelves.node().setId("shelves");
        StackPane shownList = new StackPane(lists.get(Shelf.DOWNLOADS));
        this.shelves
                .valueProperty()
                .addListener((o, was, now) -> shownList.getChildren().setAll(lists.get(now)));
        countShelf(Shelf.DOWNLOADS, this.gui.model().downloads().rows());
        countShelf(Shelf.UPLOADS, this.uploads.getItems());
        countShelf(Shelf.QUEUE, this.queue.getItems());
        countShelf(Shelf.BANNED, this.bans.getItems());
        countShelf(Shelf.KNOWN, this.known.getItems());

        Button clear = Fx.button(this.gui.t("IDS_DL_CLEAR"), Icons.CLEAR_ALL, this.gui.model()::clearCompleted);
        clear.getStyleClass().add(Styles.FLAT);
        clear.setId("clear-completed");
        clear.visibleProperty().bind(this.shelves.valueProperty().isEqualTo(Shelf.DOWNLOADS));
        clear.managedProperty().bind(clear.visibleProperty());
        // Links on the clipboard are in the box already.
        Button add =
                Fx.primary(Fx.button(this.gui.t("nj.transfers.addLinks"), Icons.ADD, () -> addLinks(clipboardLinks())));
        add.setId("add-links");
        // Details, like Clear Completed, are of the downloads only.
        this.detailsButton.visibleProperty().bind(clear.visibleProperty());
        this.detailsButton.managedProperty().bind(clear.visibleProperty());
        var tools = Fx.toolRow(this.shelves.node(), Fx.spacer(), this.detailsButton, clear, add);
        VBox screen = Fx.screen(tools, shownList);
        // Ctrl+F (Cmd+F) narrows the downloads by name; Alt+Enter shows the details of the chosen one.
        screen.addEventHandler(KeyEvent.KEY_PRESSED, e -> {
            if (e.isShortcutDown() && e.getCode() == KeyCode.F) {
                this.shelves.setValue(Shelf.DOWNLOADS);
                this.filter.requestFocus();
                e.consume();
            } else if (e.isAltDown() && e.getCode() == KeyCode.ENTER) {
                showDetails(true);
                e.consume();
            }
        });
        return screen;
    }

    // The chips of the states, the category and the filter above the downloads, the details beside them.
    private Node downloadsPart() {
        this.states = new FilterChips<>(List.of(StateFilter.values()), s -> this.gui.t(s.title));
        this.states.node().setId("state-chips");
        this.states.valueProperty().addListener((o, was, now) -> narrow());
        this.gui.subscriptions().onListChange(this.gui.model().downloads().rows(), c -> countStates());
        this.gui.subscriptions().onChange(this.gui.model().statusProperty(), (was, now) -> countStates());
        countStates();
        this.category.setId("category");
        this.category.setCellFactory(l -> new CategoryCell());
        this.category.setButtonCell(new CategoryCell());
        this.category.setAccessibleText(this.gui.t("IDS_CAT"));
        this.category.valueProperty().addListener((o, was, now) -> {
            if (now != null && now != this.shownCategory) {
                showCategory(now);
            }
        });
        showCategories();
        Button categories =
                Fx.iconButton(this.gui.t("nj.categories.title"), Icons.CATEGORY, this::showCategoriesDialog);
        categories.getStyleClass().add(Styles.FLAT);
        this.filter.setId("filter");
        this.filter.setPromptText(this.gui.t("nj.transfers.filter"));
        this.filter.setPrefColumnCount(10);
        this.filter.setLeft(Fx.icon(Icons.FILTER));
        Label keys = Fx.leftToRight(
                new Label(new KeyCodeCombination(KeyCode.F, KeyCombination.SHORTCUT_DOWN).getDisplayText()));
        keys.getStyleClass().add("shortcut-key");
        this.filter.setRight(keys);
        this.filter.textProperty().addListener((o, was, now) -> narrow());
        this.filter.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ESCAPE) {
                this.filter.clear();
            }
        });
        // The chips take the room there is and wrap only when there is none; the rest keeps to the end.
        HBox narrowing = new HBox(8, this.states.node(), this.category, categories, this.filter);
        HBox.setHgrow(this.states.node(), Priority.ALWAYS);
        narrowing.setAlignment(Pos.CENTER_LEFT);
        narrowing.getStyleClass().add("narrowing");
        Fx.whole(this.category);
        VBox list = new VBox(this.downloads);
        VBox.setVgrow(this.downloads, Priority.ALWAYS);
        this.split = new SplitPane(list);
        this.split.setOrientation(Orientation.VERTICAL);
        this.split.getStyleClass().add("transfers-split");
        // A taller window gives the room to the list; the details keep theirs. Only a divider the user drags is
        // remembered, as the height of the details.
        SplitPane.setResizableWithParent(this.details.view(), false);
        this.split.heightProperty().addListener((o, was, now) -> {
            placeDetails();
            // A window that grows from its first size, or back from a smaller one, gives the details their height
            // again once the split has laid out its new size.
            Platform.runLater(this::placeDetails);
        });
        this.split.addEventFilter(MouseEvent.MOUSE_PRESSED, e -> this.dividerHeld = onDivider(e.getTarget()));
        this.split.addEventFilter(MouseEvent.MOUSE_RELEASED, e -> keepDetailsHeight());
        showBelow(this.detailsButton.isSelected());
        this.detailsButton.selectedProperty().addListener((o, was, now) -> showBelow(now));
        VBox part = new VBox(10, narrowing, this.split);
        VBox.setVgrow(this.split, Priority.ALWAYS);
        return part;
    }

    private void downloadColumns() {
        var formats = this.gui.formats();
        TableColumn<LiveList.Row<DownloadInfo>, String> name =
                Fx.ownDirection(Fx.column(this.gui.t("IDS_DL_FILENAME"), 220, DownloadInfo::name));
        name.setCellFactory(c -> new NameCell());
        TableColumn<LiveList.Row<DownloadInfo>, Double> progress =
                Fx.column(this.gui.t("IDS_DL_PROGRESS"), 120, TransfersScreen::fraction);
        progress.setCellFactory(c -> new ProgressCell());
        TableColumn<LiveList.Row<DownloadInfo>, Integer> sources =
                Fx.numeric(Fx.column(this.gui.t("IDS_DL_SOURCES"), 70, DownloadInfo::sources, n -> String.valueOf(n)));
        sources.setCellFactory(c -> new SourcesCell());
        TableColumn<LiveList.Row<DownloadInfo>, ?> completed =
                Fx.numeric(Fx.column(this.gui.t("IDS_DL_TRANSFCOMPL"), 90, DownloadInfo::completed, formats::size));
        TableColumn<LiveList.Row<DownloadInfo>, ?> priority =
                Fx.column(this.gui.t("IDS_PRIORITY"), 85, TransfersScreen::shownPriority, this::priorityText);
        TableColumn<LiveList.Row<DownloadInfo>, ?> categoryColumn =
                Fx.ownDirection(Fx.column(this.gui.t("IDS_CAT"), 95, DownloadInfo::category, this::categoryName));
        // Read each time a row is looked at; the column menu brings these back.
        for (TableColumn<?, ?> rarer : List.of(completed, priority, categoryColumn)) {
            rarer.setVisible(false);
        }
        List<TableColumn<LiveList.Row<DownloadInfo>, ?>> columns = List.of(
                name,
                Fx.numeric(Fx.column(this.gui.t("IDS_DL_SIZE"), 80, DownloadInfo::size, formats::size)),
                progress,
                Fx.numeric(Fx.column(this.gui.t("IDS_DL_SPEED"), 90, DownloadInfo::bytesPerSecond, this::speedText)),
                sources,
                statusColumn(),
                Fx.numeric(Fx.column(this.gui.t("IDS_DL_REMAINS"), 130, TransfersScreen::left, this::leftText)),
                completed,
                priority,
                categoryColumn);
        // Known by name rather than place, a saved layout stays right when the columns move.
        List<String> ids = List.of(
                "name", "size", "progress", "speed", "sources", "status", "left", "completed", "priority", "category");
        // A narrow list cuts the names, never a figure.
        List<Integer> least = List.of(100, 76, 96, 88, 52, 104, 120, 76, 72, 72);
        for (int i = 0; i < columns.size(); i++) {
            columns.get(i).setId("download-" + ids.get(i));
            columns.get(i).setMinWidth(least.get(i));
        }
        this.downloads.getColumns().addAll(columns);
        this.downloads.setTableMenuButtonVisible(true);
        TableLayout.remember(this.downloads, "downloads", this.gui.settings());
    }

    // What one does to the chosen downloads, the common first; the rarer under More, in plain words. Clearing the
    // completed ones is of the whole list, so it comes last.
    private ContextMenu downloadMenu() {
        MenuItem pause = Fx.menuItem(this.gui.t("IDS_DL_PAUSE"), Icons.PAUSE, this::pauseSelected);
        MenuItem resume = Fx.menuItem(this.gui.t("IDS_DL_RESUME"), Icons.PLAY, this::resumeSelected);
        MenuItem stop = Fx.menuItem(this.gui.t("IDS_DL_STOP"), Icons.STOP, this::stopSelected);
        MenuItem cancel = Fx.menuItem(this.gui.t("nj.transfers.cancelOne"), Icons.DELETE, this::askCancelSelected);
        MenuItem details = Fx.menuItem(this.gui.t("IDS_DETAILS"), Icons.INFO, () -> showDetails(true));
        MenuItem preview = Fx.menuItem(this.gui.t("IDS_DL_PREVIEW"), Icons.PREVIEW, this::previewSelected);
        MenuItem folder = Fx.menuItem(this.gui.t("IDS_OPENFOLDER"), Icons.FOLDER_OPEN, this::revealSelected);
        MenuItem comments = Fx.menuItem(this.gui.t("IDS_CMT_ADD"), Icons.COMMENT, this::commentSelected);
        Menu priority = priorityMenu(new Menu(this.gui.t("IDS_PRIORITY"), Fx.icon(Icons.PRIORITY)));
        Menu category = new Menu(this.gui.t("IDS_CAT"), Fx.icon(Icons.LABEL));
        MenuItem addSource = Fx.menuItem(this.gui.t("IDS_ADDSRCMANUALLY"), Icons.PERSON_ADD, this::addSourceToSelected);
        CheckMenuItem previewFirst = new CheckMenuItem(this.gui.t("IDS_DOWNLOADMOVIECHUNKS"));
        previewFirst.setOnAction(e -> Fx.selected(this.downloads)
                .forEach(d -> this.gui.model().setPreviewFirst(d.hash(), previewFirst.isSelected())));
        // A client that has the files of several downloads is asked for one at a time: its sources can move.
        MenuItem swapHere = new MenuItem(this.gui.t("nj.a4af.toThis"));
        swapHere.setOnAction(e -> swapSourcesHere());
        CheckMenuItem autoSwap = new CheckMenuItem(this.gui.t("nj.a4af.auto"));
        autoSwap.setOnAction(e -> Fx.selected(this.downloads)
                .forEach(d -> this.gui.model().setAutoSwap(d.hash(), autoSwap.isSelected())));
        MenuItem swapAway = new MenuItem(this.gui.t("nj.a4af.toOther"));
        swapAway.setOnAction(e -> swapSourcesAway());
        Menu shared = new Menu(this.gui.t("nj.a4af.menu"), Fx.icon(Icons.SWAP), swapHere, autoSwap, swapAway);
        Menu more = new Menu(this.gui.t("IDS_MORE"), Fx.icon(Icons.MORE), addSource, previewFirst, shared);
        MenuItem copy = Fx.menuItem(this.gui.t("nj.copyLink"), Icons.COPY, this::copyLinks);
        MenuItem html = Fx.menuItem(this.gui.t("IDS_DL_LINK2"), Icons.CODE, this::copyHtml);
        MenuItem clearCompleted =
                Fx.menuItem(this.gui.t("IDS_DL_CLEAR"), Icons.CLEAR_ALL, this.gui.model()::clearCompleted);
        ContextMenu menu = new ContextMenu(
                pause,
                resume,
                stop,
                cancel,
                new SeparatorMenuItem(),
                details,
                preview,
                folder,
                comments,
                priority,
                category,
                more,
                new SeparatorMenuItem(),
                copy,
                html,
                new SeparatorMenuItem(),
                clearCompleted);
        menu.setOnShowing(e -> {
            category.getItems().setAll(categoryItems());
            List<DownloadInfo> selected = Fx.selected(this.downloads);
            boolean none = selected.isEmpty();
            for (MenuItem item : List.of(details, copy, html)) {
                item.setDisable(none);
            }
            pause.setDisable(selected.stream().noneMatch(TransfersScreen::pausable));
            resume.setDisable(selected.stream().noneMatch(TransfersScreen::resumable));
            stop.setDisable(selected.stream().noneMatch(TransfersScreen::stoppable));
            cancel.setDisable(cancellable().isEmpty());
            clearCompleted.setDisable(this.gui.model().downloads().rows().stream()
                    .noneMatch(r -> r.get().status() == DownloadInfo.Status.COMPLETE));
            // A completed download has no part file any more: there is nothing to preview or to fetch.
            boolean active = selected.size() == 1 && selected.getFirst().status() != DownloadInfo.Status.COMPLETE;
            preview.setDisable(!active || selected.getFirst().completed() == 0);
            folder.setDisable(selected.size() != 1 || selected.getFirst().status() != DownloadInfo.Status.COMPLETE);
            comments.setDisable(selected.size() != 1);
            addSource.setDisable(!active);
            previewFirst.setSelected(!none && selected.stream().allMatch(DownloadInfo::previewFirst));
            boolean unfinished = !none && selected.stream().noneMatch(d -> d.status() == DownloadInfo.Status.COMPLETE);
            previewFirst.setDisable(!unfinished);
            priority.setDisable(cancellable().isEmpty());
            category.setDisable(cancellable().isEmpty());
            // Sources move only to a download that runs.
            swapHere.setDisable(none || selected.stream().anyMatch(d -> d.status() != DownloadInfo.Status.DOWNLOADING));
            swapAway.setDisable(!unfinished);
            autoSwap.setDisable(!unfinished);
            autoSwap.setSelected(unfinished && selected.stream().allMatch(DownloadInfo::autoSwap));
        });
        return menu;
    }

    private void countShelf(Shelf shelf, ObservableList<?> rows) {
        Runnable count = () -> this.shelves.count(shelf, this.gui.t(shelf.title), rows.size());
        rows.addListener((ListChangeListener<Object>) c -> count.run());
        count.run();
    }

    // How many downloads each chip keeps, whatever the category and the filter.
    private void countStates() {
        List<DownloadInfo> all = this.gui.model().downloads().rows().stream()
                .map(LiveList.Row::get)
                .toList();
        for (StateFilter state : StateFilter.values()) {
            this.states.count(state, all.stream().filter(state::keeps).count());
        }
    }

    // The downloads the chips, the category and the filter keep.
    private void narrow() {
        int id = this.shownCategory;
        StateFilter state = this.states == null ? StateFilter.ALL : this.states.getValue();
        Predicate<DownloadInfo> named = named(this.filter.getText());
        this.shown.set(d -> (id == SHOW_ALL || d.category() == id) && state.keeps(d) && named.test(d));
    }

    // All categories, then each one; a category gone shows them all again.
    private void showCategories() {
        List<Integer> choices = new ArrayList<>(List.of(SHOW_ALL));
        this.gui.model().categories().forEach(c -> choices.add(c.id()));
        if (!choices.contains(this.shownCategory)) {
            this.shownCategory = SHOW_ALL;
        }
        this.category.getItems().setAll(choices);
        this.category.setValue(this.shownCategory);
        narrow();
    }

    void showCategory(int id) {
        this.shownCategory = id;
        if (!Integer.valueOf(id).equals(this.category.getValue())) {
            this.category.setValue(id);
        }
        narrow();
    }

    /** Shows only the downloads in {@code state}. */
    void showState(StateFilter state) {
        this.states.setValue(state);
    }

    private List<MenuItem> categoryItems() {
        List<MenuItem> items = new ArrayList<>();
        MenuItem none = new MenuItem(this.gui.t("IDS_CAT_UNASSIGN"));
        none.setOnAction(e -> categorize(Category.NONE));
        items.add(none);
        for (Category c : this.gui.model().categories()) {
            MenuItem item = new MenuItem(c.name(), Fx.categoryDot(c.color()));
            item.setOnAction(e -> categorize(c.id()));
            items.add(item);
        }
        return items;
    }

    void categorize(int category) {
        Fx.selected(this.downloads).forEach(d -> this.gui.model().setDownloadCategory(d.hash(), category));
    }

    private String categoryName(int id) {
        for (Category c : this.gui.model().categories()) {
            if (c.id() == id) {
                return c.name();
            }
        }
        return "";
    }

    private int categoryColor(int id) {
        for (Category c : this.gui.model().categories()) {
            if (c.id() == id) {
                return c.color();
            }
        }
        return 0;
    }

    private void showCategoriesDialog() {
        new CategoriesDialog(this.gui, this.view.getScene().getWindow()).show();
    }

    private Menu priorityMenu(Menu menu) {
        menu.getItems().setAll(priorityItems());
        return menu;
    }

    // Low, Normal, High and Auto, for each selected download.
    private List<MenuItem> priorityItems() {
        List<MenuItem> items = new ArrayList<>();
        for (DownloadPriority priority : DownloadPriority.values()) {
            MenuItem item = new MenuItem(priorityName(priority));
            item.setOnAction(e -> prioritize(priority));
            items.add(item);
        }
        return items;
    }

    void prioritize(DownloadPriority priority) {
        Fx.selected(this.downloads).forEach(d -> this.gui.model().setPriority(d.hash(), priority));
    }

    private String priorityName(DownloadPriority priority) {
        return switch (priority) {
            case LOW -> this.gui.t("IDS_PRIOLOW");
            case NORMAL -> this.gui.t("IDS_PRIONORMAL");
            case HIGH -> this.gui.t("IDS_PRIOHIGH");
            case AUTO -> this.gui.t("IDS_PRIOAUTO");
        };
    }

    /** The priority a download acts with, and whether it follows its rarity; sorted by the first. */
    record ShownPriority(DownloadPriority effective, boolean auto) implements Comparable<ShownPriority> {
        private static final Comparator<ShownPriority> ORDER =
                Comparator.comparing(ShownPriority::effective).thenComparing(ShownPriority::auto);

        @Override
        public int compareTo(ShownPriority other) {
            return ORDER.compare(this, other);
        }
    }

    static ShownPriority shownPriority(DownloadInfo d) {
        return new ShownPriority(d.priority().effective(d.sources()), d.priority() == DownloadPriority.AUTO);
    }

    private String priorityText(ShownPriority shown) {
        if (!shown.auto()) {
            return priorityName(shown.effective());
        }
        return switch (shown.effective()) {
            case LOW -> this.gui.t("IDS_PRIOAUTOLOW");
            case HIGH -> this.gui.t("IDS_PRIOAUTOHIGH");
            default -> this.gui.t("IDS_PRIOAUTONORMAL");
        };
    }

    /** How a download shows: a running one waits while no source sends, and says so when none is known. */
    enum Shown {
        DOWNLOADING,
        WAITING,
        NO_SOURCES,
        PAUSED,
        STOPPED,
        NO_SPACE,
        ERROR,
        COMPLETE
    }

    static Shown shown(DownloadInfo d) {
        return switch (d.status()) {
            case DOWNLOADING ->
                d.transferring() > 0 ? Shown.DOWNLOADING : d.sources() > 0 ? Shown.WAITING : Shown.NO_SOURCES;
            case PAUSED -> Shown.PAUSED;
            case STOPPED -> Shown.STOPPED;
            case NO_SPACE -> Shown.NO_SPACE;
            case ERROR -> Shown.ERROR;
            case COMPLETE -> Shown.COMPLETE;
        };
    }

    /** The tone of a state, for its chip and its bar: pastel, saturated only for real problems. */
    static String tone(Shown shown) {
        return switch (shown) {
            case DOWNLOADING -> "accent";
            case NO_SOURCES -> "warning";
            case NO_SPACE, ERROR -> "danger";
            case COMPLETE -> "success";
            case WAITING, PAUSED, STOPPED -> "neutral";
        };
    }

    /** Downloads whose name holds every word of {@code text}, in any case. */
    static Predicate<DownloadInfo> named(String text) {
        List<String> words = Arrays.stream(text.strip().toLowerCase(Locale.ROOT).split("\\s+"))
                .filter(w -> !w.isEmpty())
                .toList();
        return d -> words.stream().allMatch(d.name().toLowerCase(Locale.ROOT)::contains);
    }

    // The details beside the list, shown or not as last time.
    private void buildDetails() {
        var formats = this.gui.formats();
        this.details = new DownloadDetails(
                this.gui,
                new DownloadDetails.Words(
                        d -> shownText(shown(d)),
                        d -> priorityText(shownPriority(d)),
                        d -> categoryName(d.category()),
                        d -> remaining(d) < 0 ? "—" : formats.duration(remaining(d))),
                new DownloadDetails.Actions(
                        this::pauseSelected,
                        this::resumeSelected,
                        this::stopSelected,
                        this::askCancelSelected,
                        this::copyLinks,
                        this::commentSelected,
                        this::previewSelected,
                        this::addSourceToSelected,
                        this::openIncoming,
                        () -> showDetails(false)),
                this.sources);
        this.details.priorityMenu(this::priorityItems);
        this.details.categoryMenu(this::categoryItems);
        this.detailsButton.setText(this.gui.t("IDS_DETAILS"));
        this.detailsButton.setGraphic(Fx.icon(Icons.INFO));
        this.detailsButton.setId("details");
        this.detailsButton.setTooltip(new Tooltip(this.gui.t("IDS_DETAILS") + "  "
                + new KeyCodeCombination(KeyCode.ENTER, KeyCombination.ALT_DOWN).getDisplayText()));
        Fx.whole(this.detailsButton);
        this.detailsButton.setSelected(this.gui.settings().transfersDetails());
        this.detailsButton.selectedProperty().addListener((o, was, now) -> {
            this.gui.settings().setTransfersDetails(now);
            refreshDetails();
        });
        this.downloads.getSelectionModel().getSelectedItems().addListener((ListChangeListener<
                        LiveList.Row<DownloadInfo>>)
                c -> {
                    showSourcesOfSelected();
                    refreshDetails();
                });
        // The figures move with each second's news.
        this.gui.subscriptions().onChange(this.gui.model().statusProperty(), (was, now) -> refreshDetails());
    }

    void showDetails(boolean on) {
        this.detailsButton.setSelected(on);
        refreshDetails();
    }

    private void refreshDetails() {
        if (this.details == null || !this.detailsButton.isSelected()) {
            return;
        }
        this.details.show(Fx.selected(this.downloads));
        fitDetails();
    }

    /** The details beside the list, for tests. */
    DownloadDetails details() {
        return this.details;
    }

    /** The clients met, for tests. */
    TableView<LiveList.Row<KnownClient>> knownClients() {
        return this.known;
    }

    /** The downloads over their details, for tests. */
    SplitPane split() {
        return this.split;
    }

    /** Whether the details show under the downloads. */
    boolean detailsShown() {
        return this.split.getItems().contains(this.details.view());
    }

    private void showBelow(boolean shown) {
        Region below = this.details.view();
        if (!shown) {
            this.split.getItems().remove(below);
        } else if (!this.split.getItems().contains(below)) {
            this.split.getItems().add(below);
            // The details show the chosen download before they are measured.
            refreshDetails();
            placeDetails();
            this.split.getDividers().getFirst().positionProperty().addListener((o, was, now) -> {
                if (this.dividerHeld) {
                    fitDetails();
                }
            });
        }
    }

    private static boolean onDivider(EventTarget target) {
        Node node = target instanceof Node n ? n : null;
        while (node != null) {
            if (node.getStyleClass().contains("split-pane-divider")) {
                return true;
            }
            node = node.getParent();
        }
        return false;
    }

    // Where the user lets the divider go is how tall the details are wanted from then on.
    private void keepDetailsHeight() {
        if (this.dividerHeld) {
            this.dividerHeld = false;
            if (detailsShown() && this.split.getHeight() > 0) {
                this.split.layout();
                fitDetails();
                this.gui.settings().setTransfersDetailsHeight(detailsHeight());
            }
        }
    }

    // As tall as the user left them, else as the app chooses, and tall enough for the buttons of the download.
    private void placeDetails() {
        if (detailsShown() && this.split.getHeight() > 0 && !this.dividerHeld) {
            setDetailsHeight(
                    Math.max(this.gui.settings().transfersDetailsHeight().orElse(DETAILS_HEIGHT), neededHeight()));
        }
    }

    // However low the divider goes, the buttons of the download stay in sight. A minimum size of the details would
    // raise that of the window instead.
    private void fitDetails() {
        if (detailsShown() && this.split.getHeight() > 0 && detailsHeight() < neededHeight() - 1) {
            setDetailsHeight(neededHeight());
        }
    }

    private double neededHeight() {
        return this.details.headHeight() + DETAILS_MARGIN;
    }

    private double detailsHeight() {
        return this.split.getHeight() * (1 - this.split.getDividerPositions()[0]);
    }

    // The list keeps some rows in any case.
    private void setDetailsHeight(double wanted) {
        double height = this.split.getHeight();
        double kept =
                Math.clamp(wanted, Math.min(LEAST_HEIGHT, height / 2), Math.max(height - LEAST_HEIGHT, height / 2));
        this.split.setDividerPositions(1 - kept / height);
    }

    // The sources in the details are those of the download chosen.
    private void showSourcesOfSelected() {
        List<DownloadInfo> selected = Fx.selected(this.downloads);
        if (selected.size() == 1) {
            Hash16 file = selected.getFirst().hash();
            this.shownSources.set(s -> s.get().file().equals(file));
        } else {
            this.shownSources.set(s -> false);
        }
    }

    private TableColumn<LiveList.Row<DownloadInfo>, Shown> statusColumn() {
        TableColumn<LiveList.Row<DownloadInfo>, Shown> column =
                Fx.column(this.gui.t("nj.transfers.status"), 128, TransfersScreen::shown, this::shownText);
        column.setCellFactory(c -> new StatusCell());
        return column;
    }

    String shownText(Shown shown) {
        return switch (shown) {
            case DOWNLOADING -> this.gui.t("IDS_DOWNLOADING");
            case WAITING -> this.gui.t("IDS_WAITING");
            case NO_SOURCES -> this.gui.t("nj.transfers.stateNoSources");
            case PAUSED -> status(DownloadInfo.Status.PAUSED);
            case STOPPED -> status(DownloadInfo.Status.STOPPED);
            case NO_SPACE -> status(DownloadInfo.Status.NO_SPACE);
            case ERROR -> status(DownloadInfo.Status.ERROR);
            case COMPLETE -> status(DownloadInfo.Status.COMPLETE);
        };
    }

    static Icons shownIcon(Shown shown) {
        return switch (shown) {
            case DOWNLOADING -> Icons.ARROW_DOWN;
            case WAITING -> Icons.CLOCK;
            case NO_SOURCES -> Icons.NO_SOURCES;
            case PAUSED -> Icons.PAUSE;
            case STOPPED -> Icons.STOP;
            case NO_SPACE, ERROR -> Icons.ERROR;
            case COMPLETE -> Icons.CHECK_CIRCLE;
        };
    }

    private String speedText(long bytesPerSecond) {
        return bytesPerSecond <= 0 ? "—" : this.gui.formats().rate(bytesPerSecond);
    }

    /** A download's state in words on a chip of its tone, with an icon of its own: the colour is never the only cue. */
    private final class StatusCell extends TableCell<LiveList.Row<DownloadInfo>, Shown> {
        private final Label chip = Fx.chip("", Icons.CLOCK, "neutral");

        @Override
        protected void updateItem(Shown shown, boolean empty) {
            super.updateItem(shown, empty);
            setText(null);
            if (empty || shown == null) {
                setGraphic(null);
                setAccessibleText(null);
                return;
            }
            Fx.chip(this.chip, TransfersScreen.this.shownText(shown), shownIcon(shown), tone(shown));
            setAccessibleText(this.chip.getText());
            setGraphic(this.chip);
        }
    }

    /** The name with the icon of what the file holds and the dot of its category, named in the tooltip. */
    private final class NameCell extends TableCell<LiveList.Row<DownloadInfo>, String> {
        private final Icon kind = Fx.icon(Icons.FILE);
        private final Circle dot = Fx.categoryDot(0);
        private final Label name = new Label();
        // The icon the system gives the kind, the program's that opens it, in place of the app's own when there is one.
        private final ImageView program = new ImageView();
        private final HBox box = new HBox(8, this.program, this.kind, this.dot, this.name);

        NameCell() {
            this.kind.getStyleClass().add("file-kind");
            this.program.setFitWidth(16);
            this.program.setFitHeight(16);
            this.program.setSmooth(true);
            this.box.setAlignment(Pos.CENTER_LEFT);
            this.name.setMinWidth(0);
            Fx.ownDirection(this.name);
        }

        @Override
        protected void updateItem(String text, boolean empty) {
            super.updateItem(text, empty);
            setText(null);
            LiveList.Row<DownloadInfo> row =
                    getTableRow() == null ? null : getTableRow().getItem();
            if (empty || text == null || row == null) {
                setGraphic(null);
                setTooltip(null);
                setAccessibleText(null);
                return;
            }
            DownloadInfo d = row.get();
            this.kind.setGlyph(Fx.fileIcon(FileKind.of(d.name())));
            Optional<Image> system = SystemIcons.of(d.name(), TransfersScreen.this.downloads::refresh);
            this.program.setImage(system.orElse(null));
            this.program.setVisible(system.isPresent());
            this.program.setManaged(system.isPresent());
            this.kind.setVisible(system.isEmpty());
            this.kind.setManaged(system.isEmpty());
            int color = TransfersScreen.this.categoryColor(d.category());
            this.dot.getStyleClass().removeIf(s -> s.startsWith("cat-"));
            if (color > 0) {
                this.dot.getStyleClass().add("cat-" + color);
            }
            this.dot.setVisible(d.category() != Category.NONE);
            this.dot.setManaged(d.category() != Category.NONE);
            this.name.setText(text);
            String category = TransfersScreen.this.categoryName(d.category());
            setTooltip(new Tooltip(category.isEmpty() ? text : text + "\n" + category));
            setAccessibleText(category.isEmpty() ? text : text + ", " + category);
            setGraphic(this.box);
        }
    }

    /** The sources that send now out of those known; a finished download has none to tell. */
    private static final class SourcesCell extends TableCell<LiveList.Row<DownloadInfo>, Integer> {
        @Override
        protected void updateItem(Integer count, boolean empty) {
            super.updateItem(count, empty);
            LiveList.Row<DownloadInfo> row =
                    getTableRow() == null ? null : getTableRow().getItem();
            if (empty || count == null || row == null) {
                setText(null);
                return;
            }
            DownloadInfo d = row.get();
            setText(d.status() == DownloadInfo.Status.COMPLETE ? "—" : d.transferring() + "/" + d.sources());
        }
    }

    private String status(DownloadInfo.Status status) {
        return switch (status) {
            case DOWNLOADING -> this.gui.t("IDS_DOWNLOADING");
            case PAUSED -> this.gui.t("IDS_PAUSED");
            case STOPPED -> this.gui.t("IDS_STOPPED");
            case NO_SPACE -> this.gui.t("IDS_INSUFFICIENT");
            case ERROR -> this.gui.t("IDS_ERRORLIKE");
            case COMPLETE -> this.gui.t("IDS_COMPLETE");
        };
    }

    static double fraction(DownloadInfo d) {
        return d.size() <= 0 ? 0 : (double) d.completed() / d.size();
    }

    /**
     * What a double click or Enter does to a download: a complete one opens in the program of its kind, the others open
     * onto their sources, or close.
     */
    void activate(DownloadInfo d) {
        if (d.status() == DownloadInfo.Status.COMPLETE) {
            openCompleted(d);
        } else if (!this.opened.remove(d.hash())) {
            this.opened.add(d.hash());
        }
        showOpened();
    }

    /** Whether the download of {@code hash} shows its sources under it. */
    boolean isOpened(Hash16 hash) {
        return this.opened.contains(hash);
    }

    // Right opens the chosen downloads onto their sources, Left closes them.
    private void showSources(List<DownloadInfo> chosen, boolean show) {
        for (DownloadInfo d : chosen) {
            if (show && d.status() != DownloadInfo.Status.COMPLETE) {
                this.opened.add(d.hash());
            } else {
                this.opened.remove(d.hash());
            }
        }
        showOpened();
    }

    private void showOpened() {
        this.rows.forEach(DownloadRow::showSources);
    }

    private void openCompleted(DownloadInfo d) {
        completedFile(d).ifPresent(file -> {
            try {
                Folders.openFile(file);
            } catch (IOException e) {
                this.gui.warn().accept(e.getMessage());
            }
        });
    }

    // The folder of the one completed download chosen, in the system's file manager, the file selected where it can.
    void revealSelected() {
        List<DownloadInfo> selected = Fx.selected(this.downloads);
        if (selected.size() != 1 || selected.getFirst().status() != DownloadInfo.Status.COMPLETE) {
            return;
        }
        completedFile(selected.getFirst()).ifPresent(file -> {
            try {
                Folders.reveal(file);
            } catch (IOException e) {
                this.gui.warn().accept(e.getMessage());
            }
        });
    }

    // Where a completed download is; when its file is gone, the user is told.
    private Optional<Path> completedFile(DownloadInfo d) {
        Optional<Path> file = this.gui.model().completedFile(d.hash()).filter(Files::isRegularFile);
        if (file.isEmpty()) {
            this.gui.warn().accept(this.gui.t("IDS_ERR_REQ_FNF") + ": " + d.name());
        }
        return file;
    }

    // A source's speed is what it sent since the last reading, over the time between.
    private void measureSources() {
        long now = System.nanoTime();
        Map<String, long[]> seen = new HashMap<>();
        for (LiveList.Row<SourceInfo> row : this.gui.model().sources().rows()) {
            SourceInfo source = row.get();
            String key = sourceKey(source);
            long[] before = this.sourceSeen.get(key);
            if (before != null && now > before[1] && source.received() >= before[0]) {
                this.sourceRates.put(key, (source.received() - before[0]) * 1_000_000_000L / (now - before[1]));
            }
            seen.put(key, new long[] {source.received(), now});
        }
        this.sourceSeen.clear();
        this.sourceSeen.putAll(seen);
        this.sourceRates.keySet().retainAll(seen.keySet());
    }

    // The sources of an opened download, those sending first, then by their place in the queues.
    private List<SourceInfo> sourcesOf(Hash16 hash) {
        return this.gui.model().sources().rows().stream()
                .map(LiveList.Row::get)
                .filter(s -> s.file().equals(hash))
                .sorted(Comparator.comparing((SourceInfo s) -> s.state() != SourceInfo.State.DOWNLOADING)
                        .thenComparing(s -> s.queueRank() == 0)
                        .thenComparingInt(SourceInfo::queueRank))
                .toList();
    }

    /**
     * One source in the columns of the downloads: its name under the file's, what it sent under the size, its parts
     * under the progress, its speed, its program and place in the queue under the sources, what it does. Built once,
     * then shown again as the source changes.
     */
    private final class SourceLine {
        private final HBox box = new HBox();
        private final List<Consumer<SourceInfo>> shows = new ArrayList<>();

        SourceLine() {
            this.box.getStyleClass().add("source-line");
            this.box.setAlignment(Pos.CENTER_LEFT);
            for (TableColumn<LiveList.Row<DownloadInfo>, ?> column :
                    TransfersScreen.this.downloads.getVisibleLeafColumns()) {
                Region cell = sourceCell(String.valueOf(column.getId()), this.shows);
                cell.minWidthProperty().bind(column.widthProperty());
                cell.prefWidthProperty().bind(column.widthProperty());
                cell.maxWidthProperty().bind(column.widthProperty());
                this.box.getChildren().add(cell);
            }
        }

        void show(SourceInfo s) {
            this.shows.forEach(show -> show.accept(s));
        }
    }

    // The cell of a source under a column; what shows the source in it goes into shows.
    private Region sourceCell(String column, List<Consumer<SourceInfo>> shows) {
        var formats = this.gui.formats();
        return switch (column) {
            case "download-name" -> {
                Label name = Fx.ownDirection(new Label());
                Label dot = sourceDot();
                Tooltip address = new Tooltip();
                name.setGraphic(dot);
                name.getStyleClass().add("source-name");
                name.setTooltip(address);
                shows.add(s -> {
                    name.setText(s.nick().orElse(s.address()));
                    address.setText(s.address());
                    showDot(dot, shownState(s));
                });
                yield name;
            }
            case "download-size" -> {
                Label figure = figure();
                shows.add(s -> figure.setText(s.received() > 0 ? formats.size(s.received()) : ""));
                yield figure;
            }
            case "download-progress" -> {
                ThinBar parts = new ThinBar();
                shows.add(s -> {
                    parts.set(s.partCount() == 0 ? 0 : (double) s.parts() / s.partCount(), "accent");
                    parts.setAccessibleText(formats.percent(s.parts(), s.partCount()));
                });
                StackPane box = new StackPane(parts);
                box.getStyleClass().add("source-cell");
                yield box;
            }
            case "download-speed" -> {
                Label figure = figure();
                shows.add(s -> {
                    long rate = this.sourceRates.getOrDefault(sourceKey(s), 0L);
                    figure.setText(s.state() == SourceInfo.State.DOWNLOADING && rate > 0 ? formats.rate(rate) : "");
                });
                yield figure;
            }
            case "download-sources" -> {
                Label program = Fx.leftToRight(new Label());
                program.getStyleClass().add("source-cell");
                shows.add(s -> program.setText(s.software() + (s.queueRank() > 0 ? " QR: " + s.queueRank() : "")));
                yield program;
            }
            case "download-status" -> {
                Label state = new Label();
                state.getStyleClass().add("source-cell");
                shows.add(s -> state.setText(sourceState(shownState(s))));
                yield state;
            }
            default -> new Region();
        };
    }

    private static String sourceKey(SourceInfo s) {
        return s.file().toHex() + " " + s.address();
    }

    // Left to right and on the right in every language, as the figures of the downloads above.
    private static Label figure() {
        Label figure = new Label();
        figure.setNodeOrientation(NodeOrientation.LEFT_TO_RIGHT);
        figure.getStyleClass().add("source-cell");
        figure.setAlignment(Pos.CENTER_RIGHT);
        figure.setMaxWidth(Double.MAX_VALUE);
        return figure;
    }

    /**
     * A download's row, which shows the download's sources under it when it is opened, one line each in the columns of
     * the list; a double click opens or closes it, or opens a complete download's file.
     */
    private final class DownloadRow extends TableRow<LiveList.Row<DownloadInfo>> {
        private final VBox lines = new VBox();
        // The lines of the sources shown, by file and address, shown again in place.
        private final Map<String, SourceLine> shown = new HashMap<>();
        private final Label none = new Label();

        DownloadRow() {
            this.lines.getStyleClass().add("source-lines");
            this.none.getStyleClass().addAll("source-cell", "source-none");
            TransfersScreen.this.rows.add(this);
            setOnMouseClicked(e -> {
                if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2 && !isEmpty() && getItem() != null) {
                    activate(getItem().get());
                    e.consume();
                }
            });
        }

        // A download that completed has no sources left to show.
        boolean opened() {
            return !isEmpty()
                    && getItem() != null
                    && getItem().get().status() != DownloadInfo.Status.COMPLETE
                    && TransfersScreen.this.opened.contains(getItem().get().hash());
        }

        @Override
        protected Skin<?> createDefaultSkin() {
            return new OpenedRowSkin(this);
        }

        @Override
        protected void updateItem(LiveList.Row<DownloadInfo> item, boolean empty) {
            super.updateItem(item, empty);
            showSources();
        }

        // The lines change only where the sources did; the row grows or shrinks with their number.
        void showSources() {
            pseudoClassStateChanged(OPENED_ROW, opened());
            if (!opened()) {
                if (!this.lines.getChildren().isEmpty()) {
                    forgetSources();
                    requestLayout();
                }
                return;
            }
            List<Node> wanted = new ArrayList<>();
            Map<String, SourceLine> kept = new HashMap<>();
            for (SourceInfo s : sourcesOf(getItem().get().hash())) {
                SourceLine line = this.shown.get(sourceKey(s));
                if (line == null) {
                    line = new SourceLine();
                }
                line.show(s);
                kept.put(sourceKey(s), line);
                wanted.add(line.box);
            }
            if (wanted.isEmpty()) {
                this.none.setText(TransfersScreen.this.gui.t("nj.transfers.noSources"));
                wanted.add(this.none);
            }
            this.shown.clear();
            this.shown.putAll(kept);
            if (!this.lines.getChildren().equals(wanted)) {
                this.lines.getChildren().setAll(wanted);
                requestLayout();
            }
        }

        void forgetSources() {
            this.lines.getChildren().clear();
            this.shown.clear();
        }
    }

    /** Lays the cells of a download's row out on its first line, and the lines of its sources under them. */
    private static final class OpenedRowSkin extends TableRowSkin<LiveList.Row<DownloadInfo>> {
        private final DownloadRow row;

        OpenedRowSkin(DownloadRow row) {
            super(row);
            this.row = row;
        }

        @Override
        protected double computePrefHeight(double width, double top, double right, double bottom, double left) {
            double cells = super.computePrefHeight(width, top, right, bottom, left);
            return this.row.opened() ? cells + this.row.lines.prefHeight(width) : cells;
        }

        @Override
        protected void layoutChildren(double x, double y, double w, double h) {
            if (!this.row.opened()) {
                getChildren().remove(this.row.lines);
                super.layoutChildren(x, y, w, h);
                return;
            }
            if (!getChildren().contains(this.row.lines)) {
                getChildren().add(this.row.lines);
            }
            double cells = super.computePrefHeight(
                    w, snappedTopInset(), snappedRightInset(), snappedBottomInset(), snappedLeftInset());
            super.layoutChildren(x, y, w, cells);
            this.row.lines.resizeRelocate(x, y + cells, w, Math.max(0, h - cells));
        }
    }

    /**
     * What a download still lacks: the time at its speed, -1 when it gets nothing, and the bytes; sorted by the time.
     */
    record Left(long seconds, long bytes) implements Comparable<Left> {
        @Override
        public int compareTo(Left other) {
            return Comparator.comparingLong(Left::seconds)
                    .thenComparingLong(Left::bytes)
                    .compare(this, other);
        }
    }

    static Left left(DownloadInfo d) {
        return new Left(remaining(d), Math.max(0, d.size() - d.completed()));
    }

    // The time, then the bytes in the units of the speed, as "9:13 min (151.9 MB)"; nothing once complete.
    private String leftText(Left left) {
        if (left.bytes() == 0) {
            return "";
        }
        var formats = this.gui.formats();
        return (left.seconds() < 0 ? "—" : formats.duration(left.seconds())) + " (" + formats.amount(left.bytes())
                + ")";
    }

    /** Seconds left at the current rate; -1 when nothing arrives. */
    static long remaining(DownloadInfo d) {
        long left = d.size() - d.completed();
        if (left <= 0) {
            return 0;
        }
        return d.bytesPerSecond() <= 0 ? -1 : left / d.bytesPerSecond();
    }

    // The links on the clipboard, if any, to start the dialog with.
    private static String clipboardLinks() {
        String text = Clipboard.getSystemClipboard().getString();
        return text == null || ClipboardWatch.fileLinks(text).isEmpty() ? "" : text;
    }

    /** Opens the dialog to add eD2K file links, with {@code text} in its box; the files ticked are added. */
    void addLinks(String text) {
        AddLinksDialog dialog = new AddLinksDialog(
                this.gui,
                this.view == null || this.view.getScene() == null
                        ? null
                        : this.view.getScene().getWindow(),
                text,
                this.shownCategory == SHOW_ALL ? OptionalInt.empty() : OptionalInt.of(this.shownCategory));
        if (this.addAnswer.test(dialog)) {
            dialog.addChosen();
        }
    }

    /** Answers the dialog of the links with {@code answer} instead of showing it. */
    void answerAdds(Predicate<AddLinksDialog> answer) {
        this.addAnswer = answer;
    }

    void pauseSelected() {
        Fx.selected(this.downloads).stream()
                .filter(TransfersScreen::pausable)
                .forEach(d -> this.gui.model().pause(d.hash()));
    }

    void stopSelected() {
        Fx.selected(this.downloads).stream()
                .filter(TransfersScreen::stoppable)
                .forEach(d -> this.gui.model().stop(d.hash()));
    }

    void resumeSelected() {
        Fx.selected(this.downloads).stream()
                .filter(TransfersScreen::resumable)
                .forEach(d -> this.gui.model().resume(d.hash()));
    }

    // A download pauses while it runs, resumes once paused, stopped or failed, and stops until it is stopped.
    static boolean pausable(DownloadInfo d) {
        return d.status() == DownloadInfo.Status.DOWNLOADING || d.status() == DownloadInfo.Status.NO_SPACE;
    }

    static boolean resumable(DownloadInfo d) {
        return d.status() == DownloadInfo.Status.PAUSED
                || d.status() == DownloadInfo.Status.STOPPED
                || d.status() == DownloadInfo.Status.ERROR;
    }

    static boolean stoppable(DownloadInfo d) {
        return d.status() != DownloadInfo.Status.STOPPED && d.status() != DownloadInfo.Status.COMPLETE;
    }

    // A cancel deletes what was downloaded: asked first.
    void askCancelSelected() {
        List<DownloadInfo> selected = cancellable();
        if (!selected.isEmpty() && this.cancelAnswer.test(cancelQuestion(selected), selected.size())) {
            cancelSelected();
        }
    }

    // The chosen downloads but the completed ones, whose files stay: clearing takes those off the list.
    private List<DownloadInfo> cancellable() {
        return Fx.selected(this.downloads).stream()
                .filter(d -> d.status() != DownloadInfo.Status.COMPLETE)
                .toList();
    }

    private boolean askCancel(String question, int count) {
        return cancelDialog(question, count)
                .owner(this.downloads.getScene().getWindow())
                .ask();
    }

    /**
     * The modal question before cancelling {@code count} downloads, whose title and names are {@code question}:
     * cancelling is red, keeping them the default.
     */
    Ask cancelDialog(String question, int count) {
        List<String> lines = question.lines().toList();
        return Ask.about(this.gui, lines.getFirst())
                .says(this.gui.t("nj.transfers.cancelText"))
                .names(lines.stream().skip(1).filter(l -> !l.isEmpty()).toList())
                .risky(
                        this.gui.t(count == 1 ? "nj.transfers.cancelOne" : "nj.transfers.cancelMany"),
                        this.gui.t("nj.transfers.keepDownloading"));
    }

    /** Answers the cancel questions with {@code answer} instead of a dialog. */
    void answerCancels(Predicate<String> answer) {
        this.cancelAnswer = (question, count) -> answer.test(question);
    }

    /** The question before a cancel: whether to cancel the download, or the downloads, named below it. */
    String cancelQuestion(List<DownloadInfo> selected) {
        String question = selected.size() == 1
                ? this.gui.t("nj.transfers.cancelTitleOne")
                : this.gui.f("nj.transfers.cancelTitleMany", selected.size());
        StringBuilder text = new StringBuilder(question).append('\n');
        selected.stream()
                .limit(CANCEL_NAMES_SHOWN)
                .forEach(d -> text.append('\n').append(this.gui.i18n().embed(d.name())));
        if (selected.size() > CANCEL_NAMES_SHOWN) {
            text.append("\n…");
        }
        return text.toString();
    }

    // The engine keeps what was downloaded for a while, and the message offers to take it back.
    void cancelSelected() {
        List<DownloadInfo> selected = cancellable();
        if (selected.isEmpty()) {
            return;
        }
        cancel(selected);
        this.gui.offer().show(this.gui.f("nj.transfers.cancelled", selected.size()), this.gui.t("IDS_UNDO"), () -> {
            selected.forEach(d -> this.gui.model().restore(d.hash()));
        });
    }

    void cancel(List<DownloadInfo> selected) {
        selected.forEach(d -> this.gui.model().cancel(d.hash()));
    }

    // A source the user knows of, typed as host:port.
    void addSourceToSelected() {
        List<DownloadInfo> selected = Fx.selected(this.downloads);
        if (selected.size() != 1) {
            return;
        }
        DownloadInfo d = selected.getFirst();
        TextField where = Fx.leftToRight(new TextField());
        where.setPromptText("192.0.2.10:4662");
        boolean yes = Ask.about(this.gui, this.gui.t("nj.transfers.addSourceTitle"))
                .says(this.gui.f("nj.transfers.sourceFor", this.gui.i18n().embed(d.name())))
                .field(this.gui.t("nj.transfers.sourceAddress"), where, this.gui.t("nj.transfers.sourceHint"))
                .answers(this.gui.t("nj.transfers.addSource"), this.gui.t("IDS_CANCEL"))
                .owner(this.downloads.getScene().getWindow())
                .ask();
        Optional.of(where.getText().strip()).filter(t -> yes && !t.isEmpty()).ifPresent(text -> {
            ServerAddress address;
            try {
                address = ServerAddress.parse(text);
            } catch (IllegalArgumentException e) {
                this.gui.warn().accept(this.gui.f("nj.servers.badAddress", text));
                return;
            }
            this.gui
                    .model()
                    .addSource(
                            d.hash(),
                            address,
                            added -> (added ? this.gui.inform() : this.gui.warn())
                                    .accept(this.gui.f(
                                            added ? "nj.transfers.sourceAdded" : "nj.transfers.sourceRefused",
                                            address.host() + ":" + address.port())));
        });
    }

    void swapSourcesHere() {
        for (DownloadInfo d : Fx.selected(this.downloads)) {
            this.gui.model().swapSourcesHere(d.hash(), this::toldSwapped);
        }
    }

    void swapSourcesAway() {
        for (DownloadInfo d : Fx.selected(this.downloads)) {
            this.gui.model().swapSourcesAway(d.hash(), this::toldSwapped);
        }
    }

    private void toldSwapped(int count) {
        this.gui.inform().accept(this.gui.f("nj.a4af.swapped", count));
    }

    // The chosen source, asked for another download, is asked next for the download shown.
    void swapSelectedSource() {
        LiveList.Row<SourceInfo> selected = this.sources.getSelectionModel().getSelectedItem();
        if (selected == null || selected.get().state() != SourceInfo.State.A4AF) {
            return;
        }
        SourceInfo source = selected.get();
        this.gui.model().swapSource(source.file(), ServerAddress.parse(source.address()), moved -> {
            if (!moved) {
                this.gui.warn().accept(this.gui.t("nj.a4af.cannotSwap"));
            }
        });
    }

    void commentSelected() {
        List<DownloadInfo> selected = Fx.selected(this.downloads);
        if (selected.size() == 1) {
            DownloadInfo d = selected.getFirst();
            new CommentDialog(this.gui, this.downloads.getScene().getWindow(), d.hash(), d.name(), true).show();
        }
    }

    // The player of the settings shows the data downloaded so far.
    void previewSelected() {
        List<DownloadInfo> selected = Fx.selected(this.downloads);
        if (selected.size() != 1) {
            return;
        }
        String player = this.gui.model().preferencesProperty().get().videoPlayer();
        if (!Preview.isSet(player)) {
            this.gui.warn().accept(this.gui.t("nj.preview.noPlayer"));
            return;
        }
        String args = this.gui.model().preferencesProperty().get().videoPlayerArgs();
        this.gui.model().downloadData(selected.getFirst().hash(), data -> {
            if (data.isEmpty()) {
                this.gui.warn().accept(this.gui.t("nj.preview.notYet"));
                return;
            }
            try {
                Preview.start(player, args, data.get());
            } catch (IOException e) {
                this.gui.warn().accept(e.getMessage());
            }
        });
    }

    // Where finished downloads go, in the system's file manager.
    private void openIncoming() {
        Preferences preferences = this.gui.model().preferencesProperty().get();
        if (preferences == null) {
            return;
        }
        try {
            Folders.open(preferences.effectiveIncomingDir(this.gui.directories()));
        } catch (IOException e) {
            this.gui.warn().accept(e.getMessage());
        }
    }

    void copyLinks() {
        copy(Ed2kLink.FileLink::toUri);
    }

    void copyHtml() {
        copy(Ed2kLink.FileLink::toHtml);
    }

    private void copy(Function<Ed2kLink.FileLink, String> form) {
        List<DownloadInfo> selected = Fx.selected(this.downloads);
        if (!selected.isEmpty()) {
            ClipboardWatch.copy(String.join(
                    "\n", selected.stream().map(d -> form.apply(d.link())).toList()));
        }
    }

    TableView<LiveList.Row<DownloadInfo>> downloads() {
        return this.downloads;
    }

    TableView<LiveList.Row<UploadInfo>> uploads() {
        return this.uploads;
    }

    TableView<LiveList.Row<UploadInfo>> queue() {
        return this.queue;
    }

    ListView<LiveList.Row<SourceInfo>> sources() {
        return this.sources;
    }

    TableView<LiveList.Row<BanInfo>> bans() {
        return this.bans;
    }

    FilterChips<StateFilter> states() {
        return this.states;
    }

    /** The names of the lists, in order, with their counts. */
    List<String> shelfNames() {
        return this.shelves.names();
    }

    /** Shows the list at {@code index}: the downloads, the uploads, the queue, the banned or the clients known. */
    void showShelf(int index) {
        this.shelves.setValue(Shelf.values()[index]);
    }

    private TableView<LiveList.Row<UploadInfo>> uploadTable(UploadInfo.State state, String layout) {
        var formats = this.gui.formats();
        TableView<LiveList.Row<UploadInfo>> table = Fx.table(
                this.gui.model().uploads(),
                this.gui.t("nj.transfers.noUploads"),
                new SimpleObjectProperty<Predicate<UploadInfo>>(u -> u.state() == state));
        table.setId(layout);
        table.getColumns()
                .addAll(List.of(
                        Fx.ownDirection(Fx.column(
                                this.gui.t("IDS_QL_USERNAME"),
                                180,
                                (UploadInfo u) -> u.nick().orElse(u.address()))),
                        countryColumn(),
                        Fx.leftToRight(Fx.column(this.gui.t("IDS_CD_CSOFT"), 130, UploadInfo::software)),
                        Fx.ownDirection(Fx.column(this.gui.t("IDS_DL_FILENAME"), 260, UploadInfo::file)),
                        Fx.numeric(
                                Fx.column(this.gui.t("IDS_DL_SPEED"), 90, UploadInfo::bytesPerSecond, formats::rate)),
                        Fx.numeric(Fx.column(this.gui.t("IDS_DL_TRANSF"), 90, UploadInfo::transferred, formats::size)),
                        Fx.numeric(Fx.column(
                                this.gui.t("nj.transfers.waited"),
                                90,
                                UploadInfo::waitingSeconds,
                                formats::duration))));
        TableLayout.remember(table, layout, this.gui.settings());
        browseMenu(table, UploadInfo::address, u -> u.nick().orElse(u.address()));
        return table;
    }

    // View Shared Files: the client's list in a window of its own.
    private <T> void browseMenu(
            TableView<LiveList.Row<T>> table, Function<T, String> address, Function<T, String> name) {
        MenuItem view = Fx.menuItem(this.gui.t("IDS_VIEWFILES"), Icons.FOLDER_OPEN, () -> {
            List<T> selected = Fx.selected(table);
            if (selected.size() == 1) {
                showFiles(address.apply(selected.getFirst()), name.apply(selected.getFirst()));
            }
        });
        ContextMenu menu = new ContextMenu(view);
        menu.setOnShowing(e -> view.setDisable(Fx.selected(table).size() != 1));
        table.setContextMenu(menu);
    }

    private void showFiles(String address, String name) {
        new ClientFilesDialog(this.gui, this.view.getScene().getWindow(), name).show(ServerAddress.parse(address));
    }

    // The sources of the download chosen, for the details: who they are, what they do, how much they have.
    private ListView<LiveList.Row<SourceInfo>> sourceList() {
        FilteredList<LiveList.Row<SourceInfo>> rows =
                new FilteredList<>(this.gui.model().sources().rows());
        rows.predicateProperty().bind(this.shownSources);
        ListView<LiveList.Row<SourceInfo>> list = new ListView<>(rows);
        list.setId("sources");
        list.getStyleClass().add("source-list");
        list.getSelectionModel().setSelectionMode(SelectionMode.SINGLE);
        list.setCellFactory(l -> new SourceCell());
        MenuItem view = Fx.menuItem(this.gui.t("IDS_VIEWFILES"), Icons.FOLDER_OPEN, () -> {
            LiveList.Row<SourceInfo> chosen = list.getSelectionModel().getSelectedItem();
            if (chosen != null) {
                SourceInfo s = chosen.get();
                showFiles(s.address(), s.nick().orElse(s.address()));
            }
        });
        MenuItem swap = Fx.menuItem(this.gui.t("nj.a4af.swapToThis"), Icons.SWAP, this::swapSelectedSource);
        ContextMenu menu = new ContextMenu(view, swap);
        menu.setOnShowing(e -> {
            LiveList.Row<SourceInfo> chosen = list.getSelectionModel().getSelectedItem();
            view.setDisable(chosen == null);
            swap.setDisable(chosen == null || chosen.get().state() != SourceInfo.State.A4AF);
        });
        list.setContextMenu(menu);
        return list;
    }

    String sourceState(SourceInfo.State state) {
        return this.gui.t(
                switch (state) {
                    case WAITING -> "IDS_WAITING";
                    case CONNECTING -> "IDS_CONNECTING";
                    case ASKING -> "IDS_ASKING";
                    case QUEUED -> "IDS_ONQUEUE";
                    case DOWNLOADING -> "IDS_TRANSFERRING";
                    case A4AF -> "IDS_ASKED4ANOTHERFILE";
                    case QUEUE_FULL -> "IDS_QUEUEFULL";
                    case NO_NEEDED_PARTS -> "IDS_NONEEDEDPARTS";
                });
    }

    // Between two asks, a source that keeps us in its queue is still queued.
    static SourceInfo.State shownState(SourceInfo s) {
        return s.state() == SourceInfo.State.WAITING && s.queueRank() > 0 ? SourceInfo.State.QUEUED : s.state();
    }

    // What a source does at a glance, beside the state written out: a question mark for one not asked yet.
    private static Label sourceDot() {
        Label dot = new Label();
        dot.getStyleClass().add("source-dot");
        return dot;
    }

    static void showDot(Label dot, SourceInfo.State state) {
        String tone = switch (state) {
            case DOWNLOADING -> "dot-downloading";
            case QUEUED -> "dot-queued";
            case CONNECTING, ASKING -> "dot-reaching";
            case QUEUE_FULL, NO_NEEDED_PARTS, A4AF -> "dot-useless";
            case WAITING -> "dot-unknown";
        };
        dot.getStyleClass().removeIf(c -> c.startsWith("dot-"));
        dot.getStyleClass().add(tone);
        dot.setText(state == SourceInfo.State.WAITING ? "?" : "");
    }

    private TableView<LiveList.Row<BanInfo>> banTable() {
        TableView<LiveList.Row<BanInfo>> table = Fx.table(this.gui.model().bans(), this.gui.t("nj.transfers.noBans"));
        table.setId("bans");
        table.getColumns()
                .addAll(List.of(
                        Fx.leftToRight(Fx.column(this.gui.t("IDS_IP"), 200, BanInfo::address)),
                        Fx.numeric(Fx.column(
                                this.gui.t("nj.transfers.banLeft"),
                                120,
                                BanInfo::secondsLeft,
                                this.gui.formats()::duration))));
        TableLayout.remember(table, "bans", this.gui.settings());
        MenuItem unban = Fx.menuItem(this.gui.t("IDS_UNBAN"), Icons.UNLOCK, this::unbanSelected);
        ContextMenu menu = new ContextMenu(unban);
        menu.setOnShowing(e -> unban.setDisable(Fx.selected(table).isEmpty()));
        table.setContextMenu(menu);
        return table;
    }

    // The clients met in the last 90 days, never their addresses; the totals are those of the credits.
    private TableView<LiveList.Row<KnownClient>> knownTable() {
        var formats = this.gui.formats();
        TableView<LiveList.Row<KnownClient>> table =
                Fx.table(this.gui.model().knownClients(), this.gui.t("nj.clients.none"));
        table.setId("known");
        TableColumn<LiveList.Row<KnownClient>, String> country = Fx.column(
                this.gui.t("nj.clients.country"),
                70,
                (KnownClient k) -> k.country().orElse(""));
        country.setCellFactory(c -> new TableCell<>() {
            @Override
            protected void updateItem(String code, boolean empty) {
                super.updateItem(code, empty);
                Optional<String> where = empty || code == null || code.isEmpty() ? Optional.empty() : Optional.of(code);
                Flags.show(this, where, TransfersScreen.this.gui.i18n().locale());
            }
        });
        table.getColumns()
                .addAll(List.of(
                        Fx.ownDirection(Fx.column(
                                this.gui.t("IDS_QL_USERNAME"),
                                180,
                                (KnownClient k) ->
                                        k.nick().isEmpty() ? k.userHash().toHex() : k.nick())),
                        Fx.leftToRight(country),
                        Fx.leftToRight(Fx.column(this.gui.t("IDS_CD_CSOFT"), 150, KnownClient::software)),
                        Fx.column(
                                this.gui.t("nj.clients.firstSeen"),
                                150,
                                KnownClient::firstSeen,
                                s -> formats.dateTime(Instant.ofEpochSecond(s))),
                        Fx.column(
                                label("IDS_LASTSEEN"),
                                150,
                                KnownClient::lastSeen,
                                s -> formats.dateTime(Instant.ofEpochSecond(s))),
                        Fx.numeric(Fx.column(label("IDS_CD_TUP"), 110, KnownClient::uploaded, formats::size)),
                        Fx.numeric(Fx.column(label("IDS_CD_TDOWN"), 110, KnownClient::downloaded, formats::size))));
        TableLayout.remember(table, "known", this.gui.settings());
        return table;
    }

    // A label of eMule's texts as a column title, without the colon it ends with.
    private String label(String key) {
        String text = this.gui.t(key).stripTrailing();
        return text.endsWith(":") ? text.substring(0, text.length() - 1).stripTrailing() : text;
    }

    void unbanSelected() {
        for (BanInfo ban : Fx.selected(this.bans)) {
            this.gui.model().unban(ban.address());
        }
    }

    /**
     * A source in two lines: its name and program, what it does with its rate or its place in the queue, and how much
     * of the file it has. An A4AF source tells which download it is asked for.
     */
    private final class SourceCell extends ListCell<LiveList.Row<SourceInfo>> {
        private final Label name = new Label();
        private final Label software = new Label();
        private final Label country = Fx.leftToRight(new Label());
        private final Label figure = Fx.leftToRight(new Label());
        private final Label state = new Label();
        private final Label dot = sourceDot();
        private final ThinBar has = new ThinBar();
        private final GridPane grid = new GridPane();

        SourceCell() {
            this.name.getStyleClass().add("source-name");
            this.name.setMinWidth(0);
            Fx.ownDirection(this.name);
            this.software.getStyleClass().add("source-software");
            Fx.leftToRight(this.software);
            this.figure.getStyleClass().add("source-figure");
            this.state.getStyleClass().add("source-state");
            this.state.setGraphic(this.dot);
            this.has.setPrefWidth(96);
            this.has.setMaxWidth(96);
            this.country.getStyleClass().add("source-software");
            HBox who = new HBox(6, this.name, this.country, this.software);
            who.setAlignment(Pos.BASELINE_LEFT);
            who.setMinWidth(0);
            this.grid.add(who, 0, 0);
            this.grid.add(this.figure, 1, 0);
            this.grid.add(this.state, 0, 1);
            this.grid.add(this.has, 1, 1);
            GridPane.setHgrow(who, Priority.ALWAYS);
            GridPane.setHalignment(this.figure, HPos.RIGHT);
            this.grid.setHgap(12);
            this.grid.setVgap(3);
            this.grid.getStyleClass().add("source-row");
        }

        @Override
        protected void updateItem(LiveList.Row<SourceInfo> row, boolean empty) {
            super.updateItem(row, empty);
            setText(null);
            if (empty || row == null) {
                setGraphic(null);
                setTooltip(null);
                return;
            }
            SourceInfo s = row.get();
            this.name.setText(s.nick().orElse(s.address()));
            this.software.setText(s.software());
            Flags.show(
                    this.country, s.country(), TransfersScreen.this.gui.i18n().locale());
            this.figure.setText(
                    s.state() == SourceInfo.State.DOWNLOADING
                            ? TransfersScreen.this.gui.formats().size(s.received())
                            : s.queueRank() > 0
                                    ? TransfersScreen.this.gui.t("nj.transfers.queueRank") + " " + s.queueRank()
                                    : "");
            this.state.setText(TransfersScreen.this.sourceState(shownState(s)));
            showDot(this.dot, shownState(s));
            this.has.set(s.partCount() == 0 ? 0 : (double) s.parts() / s.partCount(), "accent");
            this.has.setAccessibleText(TransfersScreen.this.gui.formats().percent(s.parts(), s.partCount()));
            setAccessibleText(String.join(
                    ", ",
                    this.name.getText(),
                    this.state.getText(),
                    this.figure.getText(),
                    this.has.getAccessibleText()));
            String other = s.askedForName();
            setTooltip(
                    s.state() == SourceInfo.State.A4AF && !other.isEmpty()
                            ? new Tooltip(TransfersScreen.this.gui.t("nj.transfers.askedFor") + ": " + other)
                            : null);
            setGraphic(this.grid);
        }
    }

    // The country of the client, its flag beside its code; sorted by the code.
    private TableColumn<LiveList.Row<UploadInfo>, String> countryColumn() {
        TableColumn<LiveList.Row<UploadInfo>, String> column = Fx.column(
                this.gui.t("nj.clients.country"),
                70,
                (UploadInfo u) -> u.country().orElse(""));
        column.setCellFactory(c -> new TableCell<>() {
            @Override
            protected void updateItem(String code, boolean empty) {
                super.updateItem(code, empty);
                Optional<String> country =
                        empty || code == null || code.isEmpty() ? Optional.empty() : Optional.of(code);
                Flags.show(this, country, TransfersScreen.this.gui.i18n().locale());
            }
        });
        return Fx.leftToRight(column);
    }

    /** A thin bar of how much is downloaded, in the tone of the state, the percentage beside it. */
    private final class ProgressCell extends TableCell<LiveList.Row<DownloadInfo>, Double> {
        private final ThinBar bar = new ThinBar();
        private final Label text = Fx.leftToRight(new Label());
        private final HBox box = new HBox(8, this.bar, this.text);

        ProgressCell() {
            this.bar.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(this.bar, Priority.ALWAYS);
            this.text.getStyleClass().add("progress-percent");
            this.text.setMinWidth(Region.USE_PREF_SIZE);
            this.box.setAlignment(Pos.CENTER_LEFT);
        }

        @Override
        protected void updateItem(Double value, boolean empty) {
            super.updateItem(value, empty);
            setText(null);
            LiveList.Row<DownloadInfo> row =
                    getTableRow() == null ? null : getTableRow().getItem();
            if (empty || value == null || row == null) {
                setGraphic(null);
                setAccessibleText(null);
                return;
            }
            DownloadInfo d = row.get();
            this.bar.set(value, tone(shown(d)));
            this.text.setText(TransfersScreen.this.gui.formats().percent(d.completed(), d.size()));
            this.bar.setAccessibleText(this.text.getText());
            setAccessibleText(this.text.getText());
            setGraphic(this.box);
        }
    }

    /** A category in the list of categories: its dot and name, or all of them. */
    private final class CategoryCell extends ListCell<Integer> {
        @Override
        protected void updateItem(Integer id, boolean empty) {
            super.updateItem(id, empty);
            if (empty || id == null) {
                setText(null);
                setGraphic(null);
            } else if (id == SHOW_ALL) {
                setText(TransfersScreen.this.gui.t("nj.transfers.allCategories"));
                setGraphic(null);
            } else {
                setText(TransfersScreen.this.categoryName(id));
                setGraphic(Fx.categoryDot(TransfersScreen.this.categoryColor(id)));
            }
        }
    }
}
