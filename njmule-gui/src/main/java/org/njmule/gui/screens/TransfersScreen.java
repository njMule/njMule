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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
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
import javafx.scene.control.Tooltip;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import org.kordamp.ikonli.Ikon;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.material2.Material2AL;
import org.kordamp.ikonli.material2.Material2MZ;
import org.njmule.core.api.DownloadInfo;
import org.njmule.core.api.DownloadPriority;
import org.njmule.core.api.Ed2kLink;
import org.njmule.core.api.UploadInfo;
import org.njmule.gui.model.LiveList;
import org.njmule.gui.ui.Fx;
import org.njmule.gui.ui.Gui;
import org.njmule.gui.ui.Input;
import org.njmule.gui.ui.Inputs;
import org.njmule.gui.ui.PartBar;
import org.njmule.gui.ui.Screen;

/** Downloads with their progress, and below the clients we upload to or that wait for us. */
public final class TransfersScreen implements Screen {

    // Pasted links are separated by white space, which file names may contain too.

    private final Gui gui;
    private Node view;
    private TableView<LiveList.Row<DownloadInfo>> downloads;
    private TableView<LiveList.Row<UploadInfo>> uploads;
    private final TextField link = new TextField();
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
        this.downloads = Fx.table(this.gui.model().downloads(), this.gui.t("nj.transfers.none"));
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
                        Fx.column(
                                this.gui.t("IDS_DL_REMAINS"),
                                90,
                                TransfersScreen::remaining,
                                s -> s < 0 ? "?" : formats.duration(s))));
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
        MenuItem clear = new MenuItem(this.gui.t("IDS_DL_CLEAR"));
        clear.setOnAction(e -> this.gui.model().clearCompleted());
        this.downloads.setContextMenu(new ContextMenu(
                pause,
                resume,
                stop,
                cancel,
                this.priorityMenu(new Menu(this.gui.t("IDS_PRIORITY"))),
                new SeparatorMenuItem(),
                copy,
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
                Fx.spacer(),
                this.link,
                add);

        this.uploads = Fx.table(this.gui.model().uploads(), this.gui.t("nj.transfers.noUploads"));
        this.uploads
                .getColumns()
                .addAll(List.of(
                        Fx.column(
                                this.gui.t("IDS_QL_USERNAME"),
                                180,
                                u -> u.nick().orElse(u.address())),
                        Fx.column(this.gui.t("IDS_CD_CSOFT"), 130, UploadInfo::software),
                        Fx.column(this.gui.t("IDS_DL_FILENAME"), 260, UploadInfo::file),
                        Fx.column(this.gui.t("IDS_DL_SPEED"), 90, UploadInfo::bytesPerSecond, formats::rate),
                        Fx.column(this.gui.t("IDS_DL_TRANSF"), 90, UploadInfo::transferred, formats::size),
                        Fx.column(this.gui.t("nj.transfers.waited"), 90, UploadInfo::waitingSeconds, formats::duration),
                        Fx.column(
                                this.gui.t("nj.transfers.status"),
                                100,
                                UploadInfo::state,
                                s -> s == UploadInfo.State.UPLOADING
                                        ? this.gui.t("nj.transfers.uploading")
                                        : this.gui.t("IDS_ONQUEUE"))));
        Label uploadsTitle = new Label(this.gui.t("nj.transfers.uploadsTitle"));
        uploadsTitle.getStyleClass().add("settings-section");
        VBox lower = new VBox(4, uploadsTitle, this.uploads);
        VBox.setVgrow(this.uploads, Priority.ALWAYS);
        SplitPane split = new SplitPane(this.downloads, lower);
        split.setOrientation(Orientation.VERTICAL);
        split.setDividerPositions(0.6);
        return Fx.screen(this.title(), tools, split);
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

    void copyLinks() {
        List<DownloadInfo> selected = Fx.selected(this.downloads);
        if (!selected.isEmpty()) {
            ClipboardContent content = new ClipboardContent();
            content.putString(String.join(
                    "\n", selected.stream().map(d -> d.link().toUri()).toList()));
            Clipboard.getSystemClipboard().setContent(content);
        }
    }

    TableView<LiveList.Row<DownloadInfo>> downloads() {
        return this.downloads;
    }

    TableView<LiveList.Row<UploadInfo>> uploads() {
        return this.uploads;
    }

    TextField linkField() {
        return this.link;
    }

    /** The part bar of the download, with the percentage next to it. */
    private static final class ProgressCell extends TableCell<LiveList.Row<DownloadInfo>, Double> {
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
            this.text.setText(String.format(Locale.ROOT, "%.1f %%", value * 100));
            this.setGraphic(this.box);
        }
    }
}
