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

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.Function;
import java.util.function.Predicate;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.collections.ListChangeListener;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.kordamp.ikonli.Ikon;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.material2.Material2AL;
import org.kordamp.ikonli.material2.Material2MZ;
import org.njmule.core.api.BanInfo;
import org.njmule.core.api.Category;
import org.njmule.core.api.DownloadInfo;
import org.njmule.core.api.DownloadPriority;
import org.njmule.core.api.Ed2kLink;
import org.njmule.core.api.Hash16;
import org.njmule.core.api.ServerAddress;
import org.njmule.core.api.SourceInfo;
import org.njmule.core.api.UploadInfo;
import org.njmule.gui.dialogs.AddLinksDialog;
import org.njmule.gui.dialogs.CategoriesDialog;
import org.njmule.gui.dialogs.ClientFilesDialog;
import org.njmule.gui.dialogs.CommentDialog;
import org.njmule.gui.infra.Preview;
import org.njmule.gui.model.LiveList;
import org.njmule.gui.ui.ClipboardWatch;
import org.njmule.gui.ui.Fx;
import org.njmule.gui.ui.Gui;
import org.njmule.gui.ui.Input;
import org.njmule.gui.ui.Inputs;
import org.njmule.gui.ui.Navigate;
import org.njmule.gui.ui.PartBar;
import org.njmule.gui.ui.Screen;
import org.njmule.gui.ui.Segments;
import org.njmule.gui.ui.TableLayout;

/** Downloads with their progress, and below the clients we upload to or that wait for us. */
public final class TransfersScreen implements Screen {

    // Every download, whatever its category.
    private static final int SHOW_ALL = -1;

    // Pasted links are separated by white space, which file names may contain too.

    // The lists below the downloads: the clients we upload to, those waiting, those we download from, the banned.
    private enum Lower {
        UPLOADING("IDS_UPLOADING", "uploads"),
        QUEUE("IDS_ONQUEUE", "queue"),
        DOWNLOADING("IDS_DOWNLOADING", "sources"),
        BANNED("IDS_BANNED", "bans");

        private final String title;
        private final String id;

        Lower(String title, String id) {
            this.title = title;
            this.id = id;
        }
    }

    private final Gui gui;
    private Node view;
    private TableView<LiveList.Row<DownloadInfo>> downloads;
    private TableView<LiveList.Row<UploadInfo>> uploads;
    private TableView<LiveList.Row<UploadInfo>> queue;
    private TableView<LiveList.Row<SourceInfo>> sources;
    private TableView<LiveList.Row<BanInfo>> bans;
    private Segments<Lower> lower;
    private final TextField link = new TextField();
    private final HBox categoryBar = new HBox();
    private final ObjectProperty<Predicate<DownloadInfo>> shown = new SimpleObjectProperty<>(d -> true);
    private final ObjectProperty<Predicate<SourceInfo>> shownSources = new SimpleObjectProperty<>(s -> true);
    private final TextField filter = new TextField();
    private final ToggleButton detailsButton = new ToggleButton();
    private final SplitPane upperSplit = new SplitPane();
    private DownloadDetails details;
    private int shownCategory = SHOW_ALL;
    private Input<List<Ed2kLink.FileLink>> linkInput;

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
    public Ikon icon() {
        return Material2MZ.SWAP_VERT;
    }

    @Override
    public Node view() {
        if (this.view == null) {
            this.view = build();
        }
        return this.view;
    }

    private Node build() {
        var formats = this.gui.formats();
        this.downloads = Fx.table(this.gui.model().downloads(), this.gui.t("nj.transfers.none"), this.shown);
        this.gui.subscriptions().onListChange(this.gui.model().categories(), c -> {
            showCategories();
            this.downloads.refresh();
        });
        TableColumn<LiveList.Row<DownloadInfo>, Double> progress =
                Fx.column(this.gui.t("IDS_DL_PROGRESS"), 170, TransfersScreen::fraction);
        progress.setCellFactory(c -> new ProgressCell());
        this.downloads
                .getColumns()
                .addAll(List.of(
                        Fx.ownDirection(Fx.column(this.gui.t("IDS_DL_FILENAME"), 300, DownloadInfo::name)),
                        Fx.leftToRight(Fx.column(this.gui.t("IDS_DL_SIZE"), 90, DownloadInfo::size, formats::size)),
                        progress,
                        Fx.leftToRight(Fx.column(
                                this.gui.t("IDS_DL_TRANSFCOMPL"), 90, DownloadInfo::completed, formats::size)),
                        Fx.leftToRight(
                                Fx.column(this.gui.t("IDS_DL_SPEED"), 90, DownloadInfo::bytesPerSecond, formats::rate)),
                        Fx.leftToRight(Fx.column(
                                this.gui.t("IDS_DL_SOURCES"), 80, DownloadInfo::sources, n -> String.valueOf(n))),
                        statusColumn(),
                        Fx.column(this.gui.t("IDS_PRIORITY"), 100, TransfersScreen::shownPriority, this::priorityText),
                        Fx.ownDirection(
                                Fx.column(this.gui.t("IDS_CAT"), 110, DownloadInfo::category, this::categoryName)),
                        Fx.leftToRight(Fx.column(
                                this.gui.t("IDS_DL_REMAINS"),
                                90,
                                TransfersScreen::remaining,
                                s -> s < 0 ? "?" : formats.duration(s)))));
        TableLayout.remember(this.downloads, "downloads", this.gui.settings());
        MenuItem pause = new MenuItem(this.gui.t("IDS_DL_PAUSE"), Fx.icon(Material2MZ.PAUSE));
        pause.setOnAction(e -> pauseSelected());
        MenuItem resume = new MenuItem(this.gui.t("IDS_DL_RESUME"), Fx.icon(Material2MZ.PLAY_ARROW));
        resume.setOnAction(e -> resumeSelected());
        MenuItem stop = new MenuItem(this.gui.t("IDS_DL_STOP"), Fx.icon(Material2MZ.STOP));
        stop.setOnAction(e -> stopSelected());
        MenuItem cancel = new MenuItem(this.gui.t("IDS_CANCEL"), Fx.icon(Material2AL.DELETE));
        cancel.setOnAction(e -> cancelSelected());
        MenuItem copy = new MenuItem(this.gui.t("nj.copyLink"), Fx.icon(Material2AL.CONTENT_COPY));
        copy.setOnAction(e -> copyLinks());
        MenuItem html = new MenuItem(this.gui.t("IDS_DL_LINK2"), Fx.icon(Material2AL.CODE));
        html.setOnAction(e -> copyHtml());
        MenuItem clear = new MenuItem(this.gui.t("IDS_DL_CLEAR"), Fx.icon(Material2AL.CLEAR_ALL));
        clear.setOnAction(e -> this.gui.model().clearCompleted());
        MenuItem addSource = new MenuItem(this.gui.t("IDS_ADDSRCMANUALLY"), Fx.icon(Material2MZ.PERSON_ADD));
        addSource.setOnAction(e -> addSourceToSelected());
        MenuItem comments = new MenuItem(this.gui.t("IDS_CMT_ADD"), Fx.icon(Material2MZ.RATE_REVIEW));
        comments.setOnAction(e -> commentSelected());
        MenuItem preview = new MenuItem(this.gui.t("IDS_DL_PREVIEW"), Fx.icon(Material2MZ.PREVIEW));
        preview.setOnAction(e -> previewSelected());
        CheckMenuItem previewFirst = new CheckMenuItem(this.gui.t("IDS_DOWNLOADMOVIECHUNKS"));
        previewFirst.setOnAction(e -> Fx.selected(this.downloads)
                .forEach(d -> this.gui.model().setPreviewFirst(d.hash(), previewFirst.isSelected())));
        // A client that is a source of several downloads is asked for one: the others list it as A4AF.
        MenuItem swapHere = new MenuItem(this.gui.t("nj.a4af.toThis"));
        swapHere.setOnAction(e -> swapSourcesHere());
        CheckMenuItem autoSwap = new CheckMenuItem(this.gui.t("nj.a4af.auto"));
        autoSwap.setOnAction(e -> Fx.selected(this.downloads)
                .forEach(d -> this.gui.model().setAutoSwap(d.hash(), autoSwap.isSelected())));
        MenuItem swapAway = new MenuItem(this.gui.t("nj.a4af.toOther"));
        swapAway.setOnAction(e -> swapSourcesAway());
        Menu sourceHandling =
                new Menu(this.gui.t("IDS_A4AF"), Fx.icon(Material2MZ.SWAP_HORIZ), swapHere, autoSwap, swapAway);
        Menu category = new Menu(this.gui.t("IDS_CAT"), Fx.icon(Material2AL.LABEL));
        this.downloads.setContextMenu(new ContextMenu(
                pause,
                resume,
                stop,
                cancel,
                new SeparatorMenuItem(),
                preview,
                previewFirst,
                comments,
                addSource,
                sourceHandling,
                priorityMenu(new Menu(this.gui.t("IDS_PRIORITY"), Fx.icon(Material2AL.LOW_PRIORITY))),
                category,
                new SeparatorMenuItem(),
                copy,
                html,
                clear));

        // An empty list says how to fill it, with the two ways to.
        Button search = Fx.button(
                this.gui.t("IDS_EM_SEARCH"),
                Material2MZ.SEARCH,
                () -> this.downloads.fireEvent(new Navigate("search")));
        Button paste = Fx.button(this.gui.t("IDS_SW_DIRECTDOWNLOAD"), Material2AL.CONTENT_PASTE, () -> {
            this.link.requestFocus();
            this.link.paste();
        });
        this.downloads.setPlaceholder(
                Fx.emptyState(Material2AL.CLOUD_DOWNLOAD, this.gui.t("nj.transfers.none"), search, paste));
        this.link.setPromptText("ed2k://|file|…|/");
        this.link.setPrefColumnCount(34);
        this.link.setOnAction(e -> addLink());
        this.linkInput = new Inputs(this.gui)
                .fileLinks(this.link, this.gui.t("IDS_DOWNLOAD"))
                .quietWhenEmpty();
        Button add = Fx.primary(Fx.button(this.gui.t("IDS_DOWNLOAD"), Material2AL.CLOUD_DOWNLOAD, this::addLink));
        add.disableProperty().bind(this.linkInput.validProperty().not());
        Button pauseButton = Fx.button(this.gui.t("IDS_DL_PAUSE"), Material2MZ.PAUSE, this::pauseSelected);
        Button resumeButton = Fx.button(this.gui.t("IDS_DL_RESUME"), Material2MZ.PLAY_ARROW, this::resumeSelected);
        Button stopButton = Fx.button(this.gui.t("IDS_DL_STOP"), Material2MZ.STOP, this::stopSelected);
        Button cancelButton = Fx.button(this.gui.t("IDS_CANCEL"), Material2AL.DELETE, this::cancelSelected);
        MenuButton priorityButton = priorityMenu(new MenuButton(null, Fx.icon(Material2AL.LOW_PRIORITY)));
        Button copyButton = Fx.iconButton(this.gui.t("nj.copyLink"), Material2AL.CONTENT_COPY, this::copyLinks);
        Fx.needSelection(
                this.downloads, pauseButton, resumeButton, stopButton, cancelButton, priorityButton, copyButton);
        var tools = Fx.toolRow(
                pauseButton,
                resumeButton,
                stopButton,
                cancelButton,
                priorityButton,
                copyButton,
                Fx.toolGap(),
                Fx.iconButton(this.gui.t("IDS_DL_CLEAR"), Material2AL.CLEAR_ALL, this.gui.model()::clearCompleted),
                Fx.iconButton(this.gui.t("nj.categories.title"), Material2AL.CATEGORY, this::showCategoriesDialog),
                this.detailsButton,
                Fx.spacer(),
                this.link,
                add);

        // The lower lists: the clients we upload to, those waiting, those we download from, and the banned.
        this.uploads = uploadTable(UploadInfo.State.UPLOADING, "uploads");
        this.queue = uploadTable(UploadInfo.State.QUEUED, "queue");
        this.sources = sourceTable();
        this.bans = banTable();
        Map<Lower, TableView<?>> tables = new EnumMap<>(Lower.class);
        tables.put(Lower.UPLOADING, this.uploads);
        tables.put(Lower.QUEUE, this.queue);
        tables.put(Lower.DOWNLOADING, this.sources);
        tables.put(Lower.BANNED, this.bans);
        tables.forEach((which, table) -> table.setId(which.id));
        // One list at a time, chosen by segments that tell how many rows each has.
        this.lower = new Segments<>(List.of(Lower.values()), which -> lowerName(which, tables.get(which)));
        StackPane lowerList = new StackPane(this.uploads);
        this.lower
                .valueProperty()
                .addListener((o, was, now) -> lowerList.getChildren().setAll(tables.get(now)));
        tables.forEach((which, table) -> table.getItems()
                .addListener((ListChangeListener<Object>) c -> this.lower.rename(which, lowerName(which, table))));
        VBox lower = new VBox(6, this.lower.node(), lowerList);
        VBox.setVgrow(lowerList, Priority.ALWAYS);
        this.downloads.getContextMenu().setOnShowing(e -> {
            category.getItems().setAll(categoryItems());
            List<DownloadInfo> selected = Fx.selected(this.downloads);
            // A finished download has left the engine: there is nothing to preview or to fetch any more.
            boolean active = selected.size() == 1 && selected.getFirst().status() != DownloadInfo.Status.COMPLETE;
            preview.setDisable(!active || selected.getFirst().completed() == 0);
            comments.setDisable(selected.size() != 1);
            addSource.setDisable(!active);
            previewFirst.setSelected(!selected.isEmpty() && selected.stream().allMatch(DownloadInfo::previewFirst));
            boolean unfinished =
                    !selected.isEmpty() && selected.stream().noneMatch(d -> d.status() == DownloadInfo.Status.COMPLETE);
            // Sources are swapped only to a download that runs.
            swapHere.setDisable(selected.isEmpty()
                    || selected.stream().anyMatch(d -> d.status() != DownloadInfo.Status.DOWNLOADING));
            swapAway.setDisable(!unfinished);
            autoSwap.setDisable(!unfinished);
            autoSwap.setSelected(unfinished && selected.stream().allMatch(DownloadInfo::autoSwap));
        });
        this.categoryBar.getStyleClass().add("category-bar");
        showCategories();
        this.filter.setPromptText(this.gui.t("nj.transfers.filter"));
        this.filter.setPrefColumnCount(16);
        this.filter.textProperty().addListener((o, was, now) -> showCategory(this.shownCategory));
        this.filter.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ESCAPE) {
                this.filter.clear();
            }
        });
        HBox head = new HBox(8, this.categoryBar, Fx.spacer(), Fx.icon(Material2MZ.SEARCH), this.filter);
        head.setAlignment(Pos.CENTER_LEFT);
        // The sources below are those of the download chosen, or of all when none or several are.
        this.downloads.getSelectionModel().getSelectedItems().addListener((ListChangeListener<
                        LiveList.Row<DownloadInfo>>)
                c -> showSourcesOfSelected());
        VBox upper = new VBox(head, this.downloads);
        VBox.setVgrow(this.downloads, Priority.ALWAYS);
        buildDetails(upper);
        SplitPane split = new SplitPane(this.upperSplit, lower);
        split.setOrientation(Orientation.VERTICAL);
        split.setDividerPositions(0.6);
        VBox screen = Fx.screen(title(), tools, split);
        // Ctrl+F (Cmd+F) narrows the downloads by name; Alt+Enter opens the details of the chosen one.
        screen.addEventHandler(KeyEvent.KEY_PRESSED, e -> {
            if (e.isShortcutDown() && e.getCode() == KeyCode.F) {
                this.filter.requestFocus();
                e.consume();
            } else if (e.isAltDown() && e.getCode() == KeyCode.ENTER) {
                showDetails(true);
                e.consume();
            }
        });
        return screen;
    }

    // All downloads, then one button per category; the chosen one filters the list.
    private void showCategories() {
        ToggleGroup group = new ToggleGroup();
        List<Node> buttons = new ArrayList<>();
        ToggleButton all = new ToggleButton(this.gui.t("IDS_ALL"));
        all.setToggleGroup(group);
        all.setUserData(SHOW_ALL);
        all.setOnAction(e -> showCategory(SHOW_ALL));
        buttons.add(all);
        boolean found = this.shownCategory == SHOW_ALL;
        for (Category c : this.gui.model().categories()) {
            ToggleButton button = new ToggleButton(c.name(), Fx.categoryDot(c.color()));
            button.setToggleGroup(group);
            button.setUserData(c.id());
            button.setOnAction(e -> showCategory(c.id()));
            if (c.id() == this.shownCategory) {
                button.setSelected(true);
                found = true;
            }
            buttons.add(button);
        }
        if (!found) {
            showCategory(SHOW_ALL);
        }
        all.setSelected(this.shownCategory == SHOW_ALL);
        // A click on the chosen button keeps it chosen.
        group.selectedToggleProperty().addListener((o, was, now) -> {
            if (now == null && was != null) {
                was.setSelected(true);
            }
        });
        this.categoryBar.getChildren().setAll(buttons);
    }

    void showCategory(int id) {
        this.shownCategory = id;
        Predicate<DownloadInfo> named = named(this.filter.getText());
        this.shown.set(id == SHOW_ALL ? named : d -> d.category() == id && named.test(d));
        for (Node node : this.categoryBar.getChildren()) {
            if (node instanceof ToggleButton button) {
                button.setSelected(Integer.valueOf(id).equals(button.getUserData()));
            }
        }
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

    private void showCategoriesDialog() {
        new CategoriesDialog(this.gui, this.view.getScene().getWindow()).show();
    }

    private Menu priorityMenu(Menu menu) {
        menu.getItems().setAll(priorityItems());
        return menu;
    }

    // On the tool row the button shows only its icon.
    private MenuButton priorityMenu(MenuButton button) {
        button.getItems().setAll(priorityItems());
        button.setTooltip(new Tooltip(this.gui.t("IDS_PRIORITY")));
        button.setAccessibleText(this.gui.t("IDS_PRIORITY"));
        return button;
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

    /** Downloads whose name holds every word of {@code text}, in any case. */
    static Predicate<DownloadInfo> named(String text) {
        List<String> words = Arrays.stream(text.strip().toLowerCase(Locale.ROOT).split("\\s+"))
                .filter(w -> !w.isEmpty())
                .toList();
        return d -> words.stream().allMatch(d.name().toLowerCase(Locale.ROOT)::contains);
    }

    // The details beside the list, shown or not as last time and as wide as they were left.
    private void buildDetails(Node upper) {
        var formats = this.gui.formats();
        this.details = new DownloadDetails(
                this.gui,
                new DownloadDetails.Words(
                        d -> shownText(shown(d)),
                        d -> priorityText(shownPriority(d)),
                        d -> categoryName(d.category()),
                        d -> remaining(d) < 0 ? "?" : formats.duration(remaining(d))),
                this::copyLinks,
                this::commentSelected);
        this.detailsButton.setText(this.gui.t("IDS_DETAILS"));
        this.detailsButton.setGraphic(Fx.icon(Material2AL.INFO));
        this.detailsButton.setId("details");
        Fx.whole(this.detailsButton);
        this.detailsButton.setOnAction(e -> showDetails(this.detailsButton.isSelected()));
        this.upperSplit.getItems().setAll(upper);
        SplitPane.setResizableWithParent(this.details.view(), false);
        showDetails(this.gui.settings().transfersDetails());
        this.downloads.getSelectionModel().getSelectedItems().addListener((ListChangeListener<
                        LiveList.Row<DownloadInfo>>)
                c -> refreshDetails());
        // The figures move with each second's news.
        this.gui.subscriptions().onChange(this.gui.model().statusProperty(), (was, now) -> refreshDetails());
    }

    void showDetails(boolean on) {
        this.detailsButton.setSelected(on);
        List<Node> items = this.upperSplit.getItems();
        if (on && !items.contains(this.details.view())) {
            items.add(this.details.view());
            // A new divider each time the details show: its place is remembered as it moves.
            this.upperSplit.setDividerPositions(this.gui.settings().transfersDetailsDivider());
            this.upperSplit
                    .getDividers()
                    .getFirst()
                    .positionProperty()
                    .addListener((o, was, now) -> this.gui.settings().setTransfersDetailsDivider(now.doubleValue()));
        } else if (!on) {
            items.remove(this.details.view());
        }
        this.gui.settings().setTransfersDetails(on);
        refreshDetails();
    }

    private void refreshDetails() {
        if (this.details == null || !this.detailsButton.isSelected()) {
            return;
        }
        List<DownloadInfo> selected = Fx.selected(this.downloads);
        this.details.show(selected.size() == 1 ? Optional.of(selected.getFirst()) : Optional.empty());
    }

    /** The details beside the list, for tests. */
    DownloadDetails details() {
        return this.details;
    }

    private void showSourcesOfSelected() {
        List<DownloadInfo> selected = Fx.selected(this.downloads);
        if (selected.size() == 1) {
            Hash16 file = selected.getFirst().hash();
            this.shownSources.set(s -> s.file().equals(file));
        } else {
            this.shownSources.set(s -> true);
        }
    }

    private TableColumn<LiveList.Row<DownloadInfo>, Shown> statusColumn() {
        TableColumn<LiveList.Row<DownloadInfo>, Shown> column =
                Fx.column(this.gui.t("nj.transfers.status"), 150, TransfersScreen::shown, this::shownText);
        column.setCellFactory(c -> new StatusCell());
        return column;
    }

    private String shownText(Shown shown) {
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

    private static Ikon shownIcon(Shown shown) {
        return switch (shown) {
            case DOWNLOADING -> Material2AL.ARROW_DOWNWARD;
            case WAITING -> Material2MZ.SCHEDULE;
            case NO_SOURCES -> Material2MZ.SEARCH_OFF;
            case PAUSED -> Material2MZ.PAUSE;
            case STOPPED -> Material2MZ.STOP;
            case NO_SPACE, ERROR -> Material2AL.ERROR_OUTLINE;
            case COMPLETE -> Material2AL.CHECK_CIRCLE_OUTLINE;
        };
    }

    /** A download's state in words, with an icon of its own: the colour is never the only cue. */
    private final class StatusCell extends TableCell<LiveList.Row<DownloadInfo>, Shown> {
        @Override
        protected void updateItem(Shown shown, boolean empty) {
            super.updateItem(shown, empty);
            if (empty || shown == null) {
                setText(null);
                setGraphic(null);
                return;
            }
            setText(TransfersScreen.this.shownText(shown));
            FontIcon icon = Fx.icon(shownIcon(shown));
            icon.getStyleClass()
                    .addAll(
                            "download-state",
                            shown.name().toLowerCase(Locale.ROOT).replace('_', '-'));
            setGraphic(icon);
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

    /** Seconds left at the current rate; -1 when nothing arrives. */
    static long remaining(DownloadInfo d) {
        long left = d.size() - d.completed();
        if (left <= 0) {
            return 0;
        }
        return d.bytesPerSecond() <= 0 ? -1 : left / d.bytesPerSecond();
    }

    void addLink() {
        if (!this.linkInput.isValid()) {
            this.gui.warn().accept(this.gui.t("nj.transfers.badLink"));
            return;
        }
        List<Ed2kLink.FileLink> links = this.linkInput.value();
        // A single file never seen goes at once; several, or one known already, are shown first.
        if (links.size() == 1 && AddLinksDialog.note(this.gui, links.getFirst()).isEmpty()) {
            this.gui.model().addDownload(links.getFirst());
        } else {
            new AddLinksDialog(
                            this.gui,
                            this.view.getScene().getWindow(),
                            links,
                            this.shownCategory == SHOW_ALL ? OptionalInt.empty() : OptionalInt.of(this.shownCategory))
                    .showAndAdd();
        }
        this.link.clear();
    }

    void pauseSelected() {
        Fx.selected(this.downloads).forEach(d -> this.gui.model().pause(d.hash()));
    }

    void stopSelected() {
        Fx.selected(this.downloads).forEach(d -> this.gui.model().stop(d.hash()));
    }

    void resumeSelected() {
        Fx.selected(this.downloads).forEach(d -> this.gui.model().resume(d.hash()));
    }

    // No question first: the engine keeps what was downloaded for a while, and the message offers to take it back.
    void cancelSelected() {
        List<DownloadInfo> selected = Fx.selected(this.downloads);
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
        TextInputDialog ask = this.gui.dialog(new TextInputDialog());
        ask.setTitle(this.gui.t("IDS_ADDSRCMANUALLY"));
        ask.setHeaderText(this.gui.i18n().embed(d.name()));
        ask.setContentText(this.gui.t("nj.input.address"));
        Fx.leftToRight(ask.getEditor());
        ask.initOwner(this.downloads.getScene().getWindow());
        ask.showAndWait().map(String::strip).filter(t -> !t.isEmpty()).ifPresent(text -> {
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

    // The selected A4AF source is asked next for the download of its row.
    void swapSelectedSource() {
        List<SourceInfo> selected = Fx.selected(this.sources);
        if (selected.size() != 1 || selected.getFirst().state() != SourceInfo.State.A4AF) {
            return;
        }
        SourceInfo source = selected.getFirst();
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

    TableView<LiveList.Row<SourceInfo>> sources() {
        return this.sources;
    }

    TableView<LiveList.Row<BanInfo>> bans() {
        return this.bans;
    }

    /** The names of the lower lists, in order, with their counts. */
    List<String> lowerNames() {
        return this.lower.names();
    }

    /** Shows the lower list at {@code index}. */
    void showLower(int index) {
        this.lower.setValue(Lower.values()[index]);
    }

    private String lowerName(Lower which, TableView<?> table) {
        return this.gui.t(which.title) + " (" + table.getItems().size() + ")";
    }

    private TableView<LiveList.Row<UploadInfo>> uploadTable(UploadInfo.State state, String layout) {
        var formats = this.gui.formats();
        TableView<LiveList.Row<UploadInfo>> table = Fx.table(
                this.gui.model().uploads(),
                this.gui.t("nj.transfers.noUploads"),
                new SimpleObjectProperty<Predicate<UploadInfo>>(u -> u.state() == state));
        table.getColumns()
                .addAll(List.of(
                        Fx.ownDirection(Fx.column(
                                this.gui.t("IDS_QL_USERNAME"),
                                180,
                                (UploadInfo u) -> u.nick().orElse(u.address()))),
                        Fx.leftToRight(Fx.column(this.gui.t("IDS_CD_CSOFT"), 130, UploadInfo::software)),
                        Fx.ownDirection(Fx.column(this.gui.t("IDS_DL_FILENAME"), 260, UploadInfo::file)),
                        Fx.leftToRight(
                                Fx.column(this.gui.t("IDS_DL_SPEED"), 90, UploadInfo::bytesPerSecond, formats::rate)),
                        Fx.leftToRight(
                                Fx.column(this.gui.t("IDS_DL_TRANSF"), 90, UploadInfo::transferred, formats::size)),
                        Fx.leftToRight(Fx.column(
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
        MenuItem view = Fx.menuItem(this.gui.t("IDS_VIEWFILES"), Material2AL.FOLDER_OPEN, () -> {
            List<T> selected = Fx.selected(table);
            if (selected.size() == 1) {
                T client = selected.getFirst();
                new ClientFilesDialog(this.gui, this.view.getScene().getWindow(), name.apply(client))
                        .show(ServerAddress.parse(address.apply(client)));
            }
        });
        ContextMenu menu = new ContextMenu(view);
        menu.setOnShowing(e -> view.setDisable(Fx.selected(table).size() != 1));
        table.setContextMenu(menu);
    }

    private TableView<LiveList.Row<SourceInfo>> sourceTable() {
        var formats = this.gui.formats();
        TableView<LiveList.Row<SourceInfo>> table =
                Fx.table(this.gui.model().sources(), this.gui.t("nj.transfers.noSources"), this.shownSources);
        TableColumn<LiveList.Row<SourceInfo>, SourceInfo.State> state =
                Fx.column(this.gui.t("IDS_STATUS"), 180, SourceInfo::state, this::sourceState);
        state.setCellFactory(c -> new SourceStateCell());
        table.getColumns()
                .addAll(List.of(
                        Fx.ownDirection(Fx.column(
                                this.gui.t("IDS_QL_USERNAME"),
                                180,
                                (SourceInfo s) -> s.nick().orElse(s.address()))),
                        Fx.leftToRight(Fx.column(this.gui.t("IDS_CD_CSOFT"), 130, SourceInfo::software)),
                        Fx.ownDirection(Fx.column(this.gui.t("IDS_DL_FILENAME"), 260, SourceInfo::fileName)),
                        state,
                        Fx.ownDirection(Fx.column(this.gui.t("nj.transfers.askedFor"), 200, SourceInfo::askedForName)),
                        Fx.leftToRight(Fx.column(
                                this.gui.t("nj.transfers.queueRank"),
                                90,
                                SourceInfo::queueRank,
                                r -> r == 0 ? "" : String.valueOf(r))),
                        Fx.leftToRight(Fx.column(
                                this.gui.t("IDS_AVAILABLEPARTS"),
                                100,
                                (SourceInfo s) -> s.partCount() == 0 ? 0.0 : (double) s.parts() / s.partCount(),
                                f -> Math.round(f * 100) + " %")),
                        Fx.leftToRight(
                                Fx.column(this.gui.t("IDS_DL_TRANSF"), 90, SourceInfo::received, formats::size))));
        TableLayout.remember(table, "sources", this.gui.settings());
        browseMenu(table, SourceInfo::address, s -> s.nick().orElse(s.address()));
        MenuItem swap = Fx.menuItem(this.gui.t("nj.a4af.swapToThis"), Material2MZ.SWAP_HORIZ, this::swapSelectedSource);
        ContextMenu menu = table.getContextMenu();
        menu.getItems().add(swap);
        var browsing = menu.getOnShowing();
        menu.setOnShowing(e -> {
            browsing.handle(e);
            List<SourceInfo> selected = Fx.selected(table);
            swap.setDisable(selected.size() != 1 || selected.getFirst().state() != SourceInfo.State.A4AF);
        });
        return table;
    }

    private String sourceState(SourceInfo.State state) {
        return this.gui.t(
                switch (state) {
                    case WAITING -> "IDS_WAITING";
                    case CONNECTING -> "IDS_CONNECTING";
                    case ASKING -> "IDS_ASKING";
                    case QUEUED -> "IDS_ONQUEUE";
                    case DOWNLOADING -> "IDS_TRANSFERRING";
                    case A4AF -> "IDS_ASKED4ANOTHERFILE";
                });
    }

    private static Ikon sourceIcon(SourceInfo.State state) {
        return switch (state) {
            case WAITING -> Material2MZ.SCHEDULE;
            case CONNECTING -> Material2AL.LINK;
            case ASKING -> Material2MZ.QUESTION_ANSWER;
            case QUEUED -> Material2MZ.QUEUE;
            case DOWNLOADING -> Material2AL.ARROW_DOWNWARD;
            case A4AF -> Material2MZ.SWAP_HORIZ;
        };
    }

    private TableView<LiveList.Row<BanInfo>> banTable() {
        TableView<LiveList.Row<BanInfo>> table = Fx.table(this.gui.model().bans(), this.gui.t("nj.transfers.noBans"));
        table.getColumns()
                .addAll(List.of(
                        Fx.leftToRight(Fx.column(this.gui.t("IDS_IP"), 200, BanInfo::address)),
                        Fx.leftToRight(Fx.column(
                                this.gui.t("nj.transfers.banLeft"),
                                120,
                                BanInfo::secondsLeft,
                                this.gui.formats()::duration))));
        TableLayout.remember(table, "bans", this.gui.settings());
        MenuItem unban = Fx.menuItem(this.gui.t("IDS_UNBAN"), Material2AL.LOCK_OPEN, this::unbanSelected);
        table.setContextMenu(new ContextMenu(unban));
        return table;
    }

    void unbanSelected() {
        for (BanInfo ban : Fx.selected(this.bans)) {
            this.gui.model().unban(ban.address());
        }
    }

    TextField linkField() {
        return this.link;
    }

    /** The state of a source with an icon of its own; an A4AF source tells which download it is asked for. */
    private final class SourceStateCell extends TableCell<LiveList.Row<SourceInfo>, SourceInfo.State> {
        @Override
        protected void updateItem(SourceInfo.State state, boolean empty) {
            super.updateItem(state, empty);
            LiveList.Row<SourceInfo> row =
                    getTableRow() == null ? null : getTableRow().getItem();
            if (empty || state == null) {
                setText(null);
                setGraphic(null);
                setTooltip(null);
                return;
            }
            setText(TransfersScreen.this.sourceState(state));
            FontIcon icon = Fx.icon(sourceIcon(state));
            icon.getStyleClass().addAll("source-state", state.name().toLowerCase(Locale.ROOT));
            setGraphic(icon);
            String other = row == null ? "" : row.get().askedForName();
            setTooltip(state == SourceInfo.State.A4AF && !other.isEmpty() ? new Tooltip(other) : null);
        }
    }

    /** The part bar of the download, with the percentage next to it. */
    private final class ProgressCell extends TableCell<LiveList.Row<DownloadInfo>, Double> {
        private final PartBar bar = new PartBar();
        private final Label text = Fx.leftToRight(new Label());
        private final HBox box = new HBox(6, this.bar, this.text);

        ProgressCell() {
            HBox.setHgrow(this.bar, Priority.ALWAYS);
            this.bar.setMaxWidth(Double.MAX_VALUE);
            this.text.setMinWidth(Region.USE_PREF_SIZE);
            this.box.setAlignment(Pos.CENTER_LEFT);
        }

        @Override
        protected void updateItem(Double value, boolean empty) {
            super.updateItem(value, empty);
            LiveList.Row<DownloadInfo> row =
                    getTableRow() == null ? null : getTableRow().getItem();
            if (empty || value == null || row == null) {
                setGraphic(null);
                return;
            }
            DownloadInfo d = row.get();
            this.bar.set(d.parts(), d.completed());
            this.text.setText(TransfersScreen.this.gui.formats().percent(d.completed(), d.size()));
            setGraphic(this.box);
        }
    }
}
