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
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.collections.ListChangeListener;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
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
import org.njmule.core.api.ServerAddress;
import org.njmule.core.api.SourceInfo;
import org.njmule.core.api.UploadInfo;
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
import org.njmule.gui.ui.PartBar;
import org.njmule.gui.ui.Screen;
import org.njmule.gui.ui.TableLayout;

/** Downloads with their progress, and below the clients we upload to or that wait for us. */
public final class TransfersScreen implements Screen {

    // Every download, whatever its category.
    private static final int SHOW_ALL = -1;

    // Pasted links are separated by white space, which file names may contain too.

    private final Gui gui;
    private Node view;
    private TableView<LiveList.Row<DownloadInfo>> downloads;
    private TableView<LiveList.Row<UploadInfo>> uploads;
    private TableView<LiveList.Row<UploadInfo>> queue;
    private TableView<LiveList.Row<SourceInfo>> sources;
    private TableView<LiveList.Row<BanInfo>> bans;
    private TabPane lowerTabs;
    private final TextField link = new TextField();
    private final HBox categoryBar = new HBox();
    private final ObjectProperty<Predicate<DownloadInfo>> shown = new SimpleObjectProperty<>(d -> true);
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
            this.view = this.build();
        }
        return this.view;
    }

    private Node build() {
        var formats = this.gui.formats();
        this.downloads = Fx.table(this.gui.model().downloads(), this.gui.t("nj.transfers.none"), this.shown);
        this.gui.subscriptions().onListChange(this.gui.model().categories(), c -> {
            this.showCategories();
            this.downloads.refresh();
        });
        TableColumn<LiveList.Row<DownloadInfo>, Double> progress =
                Fx.column(this.gui.t("IDS_DL_PROGRESS"), 170, TransfersScreen::fraction);
        progress.setCellFactory(c -> new ProgressCell());
        this.downloads
                .getColumns()
                .addAll(List.of(
                        Fx.column(this.gui.t("IDS_DL_FILENAME"), 300, DownloadInfo::name),
                        Fx.column(this.gui.t("IDS_DL_SIZE"), 90, DownloadInfo::size, formats::size),
                        progress,
                        Fx.column(this.gui.t("IDS_DL_TRANSFCOMPL"), 90, DownloadInfo::completed, formats::size),
                        Fx.column(this.gui.t("IDS_DL_SPEED"), 90, DownloadInfo::bytesPerSecond, formats::rate),
                        Fx.column(this.gui.t("IDS_DL_SOURCES"), 80, DownloadInfo::sources, n -> String.valueOf(n)),
                        Fx.column(this.gui.t("nj.transfers.status"), 110, DownloadInfo::status, this::status),
                        Fx.column(this.gui.t("IDS_PRIORITY"), 100, TransfersScreen::shownPriority, this::priorityText),
                        Fx.column(this.gui.t("IDS_CAT"), 110, DownloadInfo::category, this::categoryName),
                        Fx.column(
                                this.gui.t("IDS_DL_REMAINS"),
                                90,
                                TransfersScreen::remaining,
                                s -> s < 0 ? "?" : formats.duration(s))));
        TableLayout.remember(this.downloads, "downloads", this.gui.settings());
        MenuItem pause = new MenuItem(this.gui.t("IDS_DL_PAUSE"));
        pause.setOnAction(e -> this.pauseSelected());
        MenuItem resume = new MenuItem(this.gui.t("IDS_DL_RESUME"));
        resume.setOnAction(e -> this.resumeSelected());
        MenuItem stop = new MenuItem(this.gui.t("IDS_DL_STOP"));
        stop.setOnAction(e -> this.stopSelected());
        MenuItem cancel = new MenuItem(this.gui.t("IDS_CANCEL"));
        cancel.setOnAction(e -> this.cancelSelected());
        MenuItem copy = new MenuItem(this.gui.t("nj.copyLink"));
        copy.setOnAction(e -> this.copyLinks());
        MenuItem html = new MenuItem(this.gui.t("IDS_DL_LINK2"));
        html.setOnAction(e -> this.copyHtml());
        MenuItem clear = new MenuItem(this.gui.t("IDS_DL_CLEAR"));
        clear.setOnAction(e -> this.gui.model().clearCompleted());
        MenuItem addSource = new MenuItem(this.gui.t("IDS_ADDSRCMANUALLY"));
        addSource.setOnAction(e -> this.addSourceToSelected());
        MenuItem comments = new MenuItem(this.gui.t("IDS_CMT_ADD"));
        comments.setOnAction(e -> this.commentSelected());
        MenuItem preview = new MenuItem(this.gui.t("IDS_DL_PREVIEW"));
        preview.setOnAction(e -> this.previewSelected());
        CheckMenuItem previewFirst = new CheckMenuItem(this.gui.t("IDS_DOWNLOADMOVIECHUNKS"));
        previewFirst.setOnAction(e -> Fx.selected(this.downloads)
                .forEach(d -> this.gui.model().setPreviewFirst(d.hash(), previewFirst.isSelected())));
        Menu category = new Menu(this.gui.t("IDS_CAT"));
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
                this.priorityMenu(new Menu(this.gui.t("IDS_PRIORITY"))),
                category,
                new SeparatorMenuItem(),
                copy,
                html,
                clear));

        this.link.setPromptText("ed2k://|file|…|/");
        this.link.setPrefColumnCount(34);
        this.link.setOnAction(e -> this.addLink());
        this.linkInput = new Inputs(this.gui)
                .fileLinks(this.link, this.gui.t("IDS_DOWNLOAD"))
                .quietWhenEmpty();
        Button add = Fx.primary(Fx.button(this.gui.t("IDS_DOWNLOAD"), Material2AL.CLOUD_DOWNLOAD, this::addLink));
        add.disableProperty().bind(this.linkInput.validProperty().not());
        var tools = Fx.toolRow(
                Fx.button(this.gui.t("IDS_DL_PAUSE"), Material2MZ.PAUSE, this::pauseSelected),
                Fx.button(this.gui.t("IDS_DL_RESUME"), Material2MZ.PLAY_ARROW, this::resumeSelected),
                Fx.button(this.gui.t("IDS_DL_STOP"), Material2MZ.STOP, this::stopSelected),
                Fx.button(this.gui.t("IDS_CANCEL"), Material2AL.DELETE, this::cancelSelected),
                this.priorityMenu(new MenuButton(null, new FontIcon(Material2AL.LOW_PRIORITY))),
                Fx.iconButton(this.gui.t("nj.copyLink"), Material2AL.CONTENT_COPY, this::copyLinks),
                Fx.iconButton(this.gui.t("IDS_DL_CLEAR"), Material2AL.CLEAR_ALL, this.gui.model()::clearCompleted),
                Fx.iconButton(this.gui.t("nj.categories.title"), Material2AL.CATEGORY, this::showCategoriesDialog),
                Fx.spacer(),
                this.link,
                add);

        // The lower lists: the clients we upload to, those waiting, those we download from, and the banned.
        this.uploads = this.uploadTable(UploadInfo.State.UPLOADING, "uploads");
        this.queue = this.uploadTable(UploadInfo.State.QUEUED, "queue");
        this.sources = this.sourceTable();
        this.bans = this.banTable();
        TabPane lowerTabs = new TabPane(
                this.counted(this.gui.t("IDS_UPLOADING"), this.uploads),
                this.counted(this.gui.t("IDS_ONQUEUE"), this.queue),
                this.counted(this.gui.t("IDS_DOWNLOADING"), this.sources),
                this.counted(this.gui.t("IDS_BANNED"), this.bans));
        lowerTabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        this.lowerTabs = lowerTabs;
        VBox lower = new VBox(4, lowerTabs);
        VBox.setVgrow(lowerTabs, Priority.ALWAYS);
        this.downloads.getContextMenu().setOnShowing(e -> {
            category.getItems().setAll(this.categoryItems());
            List<DownloadInfo> selected = Fx.selected(this.downloads);
            // A finished download has left the engine: there is nothing to preview or to fetch any more.
            boolean active = selected.size() == 1 && selected.getFirst().status() != DownloadInfo.Status.COMPLETE;
            preview.setDisable(!active || selected.getFirst().completed() == 0);
            comments.setDisable(selected.size() != 1);
            addSource.setDisable(!active);
            previewFirst.setSelected(!selected.isEmpty() && selected.stream().allMatch(DownloadInfo::previewFirst));
        });
        this.categoryBar.getStyleClass().add("category-bar");
        this.showCategories();
        VBox upper = new VBox(this.categoryBar, this.downloads);
        VBox.setVgrow(this.downloads, Priority.ALWAYS);
        SplitPane split = new SplitPane(upper, lower);
        split.setOrientation(Orientation.VERTICAL);
        split.setDividerPositions(0.6);
        return Fx.screen(this.title(), tools, split);
    }

    // All downloads, then one button per category; the chosen one filters the list.
    private void showCategories() {
        ToggleGroup group = new ToggleGroup();
        List<Node> buttons = new ArrayList<>();
        ToggleButton all = new ToggleButton(this.gui.t("IDS_ALL"));
        all.setToggleGroup(group);
        all.setUserData(SHOW_ALL);
        all.setOnAction(e -> this.showCategory(SHOW_ALL));
        buttons.add(all);
        boolean found = this.shownCategory == SHOW_ALL;
        for (Category c : this.gui.model().categories()) {
            ToggleButton button = new ToggleButton(c.name(), Fx.categoryDot(c.color()));
            button.setToggleGroup(group);
            button.setUserData(c.id());
            button.setOnAction(e -> this.showCategory(c.id()));
            if (c.id() == this.shownCategory) {
                button.setSelected(true);
                found = true;
            }
            buttons.add(button);
        }
        if (!found) {
            this.showCategory(SHOW_ALL);
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
        this.shown.set(id == SHOW_ALL ? d -> true : d -> d.category() == id);
        for (Node node : this.categoryBar.getChildren()) {
            if (node instanceof ToggleButton button) {
                button.setSelected(Integer.valueOf(id).equals(button.getUserData()));
            }
        }
    }

    private List<MenuItem> categoryItems() {
        List<MenuItem> items = new ArrayList<>();
        MenuItem none = new MenuItem(this.gui.t("IDS_CAT_UNASSIGN"));
        none.setOnAction(e -> this.categorize(Category.NONE));
        items.add(none);
        for (Category c : this.gui.model().categories()) {
            MenuItem item = new MenuItem(c.name(), Fx.categoryDot(c.color()));
            item.setOnAction(e -> this.categorize(c.id()));
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
        menu.getItems().setAll(this.priorityItems());
        return menu;
    }

    // On the tool row the button shows only its icon.
    private MenuButton priorityMenu(MenuButton button) {
        button.getItems().setAll(this.priorityItems());
        button.setTooltip(new Tooltip(this.gui.t("IDS_PRIORITY")));
        button.setAccessibleText(this.gui.t("IDS_PRIORITY"));
        return button;
    }

    // Low, Normal, High and Auto, for each selected download.
    private List<MenuItem> priorityItems() {
        List<MenuItem> items = new ArrayList<>();
        for (DownloadPriority priority : DownloadPriority.values()) {
            MenuItem item = new MenuItem(this.priorityName(priority));
            item.setOnAction(e -> this.prioritize(priority));
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
            return this.priorityName(shown.effective());
        }
        return switch (shown.effective()) {
            case LOW -> this.gui.t("IDS_PRIOAUTOLOW");
            case HIGH -> this.gui.t("IDS_PRIOAUTOHIGH");
            default -> this.gui.t("IDS_PRIOAUTONORMAL");
        };
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
            this.gui.inform().accept(this.gui.t("nj.transfers.badLink"));
            return;
        }
        this.linkInput.value().forEach(file -> this.gui.model().addDownload(file));
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

    void cancelSelected() {
        List<DownloadInfo> selected = Fx.selected(this.downloads);
        if (selected.isEmpty()) {
            return;
        }
        Alert confirm = new Alert(
                Alert.AlertType.CONFIRMATION,
                this.gui.f("nj.transfers.cancelConfirm", selected.size()),
                ButtonType.OK,
                ButtonType.CANCEL);
        confirm.initOwner(this.view.getScene().getWindow());
        confirm.showAndWait().filter(b -> b == ButtonType.OK).ifPresent(b -> this.cancel(selected));
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
        TextInputDialog ask = new TextInputDialog();
        ask.setTitle(this.gui.t("IDS_ADDSRCMANUALLY"));
        ask.setHeaderText(d.name());
        ask.setContentText(this.gui.t("nj.input.address"));
        ask.initOwner(this.downloads.getScene().getWindow());
        ask.showAndWait().map(String::strip).filter(t -> !t.isEmpty()).ifPresent(text -> {
            ServerAddress address;
            try {
                address = ServerAddress.parse(text);
            } catch (IllegalArgumentException e) {
                this.gui.inform().accept(this.gui.f("nj.servers.badAddress", text));
                return;
            }
            this.gui
                    .model()
                    .addSource(
                            d.hash(),
                            address,
                            added -> this.gui
                                    .inform()
                                    .accept(this.gui.f(
                                            added ? "nj.transfers.sourceAdded" : "nj.transfers.sourceRefused",
                                            address.host() + ":" + address.port())));
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
            this.gui.inform().accept(this.gui.t("nj.preview.noPlayer"));
            return;
        }
        String args = this.gui.model().preferencesProperty().get().videoPlayerArgs();
        this.gui.model().downloadData(selected.getFirst().hash(), data -> {
            try {
                Preview.start(player, args, data);
            } catch (IOException e) {
                this.gui.inform().accept(e.getMessage());
            }
        });
    }

    void copyLinks() {
        this.copy(Ed2kLink.FileLink::toUri);
    }

    void copyHtml() {
        this.copy(Ed2kLink.FileLink::toHtml);
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

    TabPane lowerTabs() {
        return this.lowerTabs;
    }

    // A tab whose title tells how many rows its list has.
    private <T> Tab counted(String title, TableView<T> table) {
        Tab tab = new Tab(title, table);
        Runnable count = () -> tab.setText(title + " (" + table.getItems().size() + ")");
        table.getItems().addListener((ListChangeListener<T>) c -> count.run());
        count.run();
        return tab;
    }

    private TableView<LiveList.Row<UploadInfo>> uploadTable(UploadInfo.State state, String layout) {
        var formats = this.gui.formats();
        TableView<LiveList.Row<UploadInfo>> table = Fx.table(
                this.gui.model().uploads(),
                this.gui.t("nj.transfers.noUploads"),
                new SimpleObjectProperty<Predicate<UploadInfo>>(u -> u.state() == state));
        table.getColumns()
                .addAll(List.of(
                        Fx.column(
                                this.gui.t("IDS_QL_USERNAME"),
                                180,
                                u -> u.nick().orElse(u.address())),
                        Fx.column(this.gui.t("IDS_CD_CSOFT"), 130, UploadInfo::software),
                        Fx.column(this.gui.t("IDS_DL_FILENAME"), 260, UploadInfo::file),
                        Fx.column(this.gui.t("IDS_DL_SPEED"), 90, UploadInfo::bytesPerSecond, formats::rate),
                        Fx.column(this.gui.t("IDS_DL_TRANSF"), 90, UploadInfo::transferred, formats::size),
                        Fx.column(
                                this.gui.t("nj.transfers.waited"), 90, UploadInfo::waitingSeconds, formats::duration)));
        TableLayout.remember(table, layout, this.gui.settings());
        this.browseMenu(table, UploadInfo::address, u -> u.nick().orElse(u.address()));
        return table;
    }

    // View Shared Files: the client's list in a window of its own.
    private <T> void browseMenu(
            TableView<LiveList.Row<T>> table, Function<T, String> address, Function<T, String> name) {
        MenuItem view = Fx.menuItem(this.gui.t("IDS_VIEWFILES"), Material2AL.FOLDER_OPEN, () -> {
            List<T> selected = Fx.selected(table);
            if (selected.size() == 1) {
                T client = selected.getFirst();
                new ClientFilesDialog(this.gui, table.getScene().getWindow(), name.apply(client))
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
                Fx.table(this.gui.model().sources(), this.gui.t("nj.transfers.noSources"));
        table.getColumns()
                .addAll(List.of(
                        Fx.column(
                                this.gui.t("IDS_QL_USERNAME"),
                                180,
                                s -> s.nick().orElse(s.address())),
                        Fx.column(this.gui.t("IDS_CD_CSOFT"), 130, SourceInfo::software),
                        Fx.column(this.gui.t("IDS_DL_FILENAME"), 260, SourceInfo::fileName),
                        Fx.column(this.gui.t("IDS_STATUS"), 120, SourceInfo::state, this::sourceState),
                        Fx.column(
                                this.gui.t("nj.transfers.queueRank"),
                                90,
                                SourceInfo::queueRank,
                                r -> r == 0 ? "" : String.valueOf(r)),
                        Fx.column(
                                this.gui.t("IDS_AVAILABLEPARTS"),
                                100,
                                s -> s.partCount() == 0 ? 0.0 : (double) s.parts() / s.partCount(),
                                f -> Math.round(f * 100) + " %"),
                        Fx.column(this.gui.t("IDS_DL_TRANSF"), 90, SourceInfo::received, formats::size)));
        TableLayout.remember(table, "sources", this.gui.settings());
        this.browseMenu(table, SourceInfo::address, s -> s.nick().orElse(s.address()));
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
                });
    }

    private TableView<LiveList.Row<BanInfo>> banTable() {
        TableView<LiveList.Row<BanInfo>> table = Fx.table(this.gui.model().bans(), this.gui.t("nj.transfers.noBans"));
        table.getColumns()
                .addAll(List.of(
                        Fx.column(this.gui.t("IDS_IP"), 200, BanInfo::address),
                        Fx.column(
                                this.gui.t("nj.transfers.banLeft"),
                                120,
                                BanInfo::secondsLeft,
                                this.gui.formats()::duration)));
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

    /** The part bar of the download, with the percentage next to it. */
    private final class ProgressCell extends TableCell<LiveList.Row<DownloadInfo>, Double> {
        private final PartBar bar = new PartBar();
        private final Label text = new Label();
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
                    this.getTableRow() == null ? null : this.getTableRow().getItem();
            if (empty || value == null || row == null) {
                this.setGraphic(null);
                return;
            }
            DownloadInfo d = row.get();
            this.bar.set(d.parts(), d.completed());
            this.text.setText(TransfersScreen.this.gui.formats().percent(d.completed(), d.size()));
            this.setGraphic(this.box);
        }
    }
}
