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

import atlantafx.base.theme.Styles;
import atlantafx.base.theme.Tweaks;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;
import javafx.beans.property.ReadOnlyIntegerWrapper;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.ListChangeListener;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.njmule.core.api.DownloadInfo;
import org.njmule.core.api.FileComment;
import org.njmule.core.api.FileKind;
import org.njmule.core.api.FileNames;
import org.njmule.core.api.Hash16;
import org.njmule.core.api.PartMap;
import org.njmule.core.api.PeerComment;
import org.njmule.core.api.SourceInfo;
import org.njmule.core.config.Preferences;
import org.njmule.gui.model.LiveList;
import org.njmule.gui.ui.ClipboardWatch;
import org.njmule.gui.ui.Fx;
import org.njmule.gui.ui.Gui;
import org.njmule.gui.ui.Icon;
import org.njmule.gui.ui.Icons;
import org.njmule.gui.ui.PartBar;

/**
 * The details of the download chosen, beside the list: what it is, how far it got and its parts drawn large with what
 * each colour means, what to do now, and in tabs its sources, the facts the columns leave out, the names its sources
 * give it, to rename it by, and what others said of it.
 */
final class DownloadDetails {

    /** A name the sources give the download, and how many give it. */
    record SourceName(String name, int sources) {}

    /** How the screen words what the details show. */
    record Words(
            Function<DownloadInfo, String> state,
            Function<DownloadInfo, String> priority,
            Function<DownloadInfo, String> category,
            Function<DownloadInfo, String> remaining) {}

    /** What the details do to the downloads chosen, through the screen. */
    record Actions(
            Runnable pause,
            Runnable resume,
            Runnable stop,
            Runnable cancel,
            Runnable copyLink,
            Runnable comments,
            Runnable preview,
            Runnable addSource,
            Runnable openFolder,
            Runnable close) {}

    /** The tabs of the details. */
    enum Tab {
        SOURCES,
        FACTS,
        NAMES,
        COMMENTS
    }

    // The ratings from none to the best, as clients number them, with the tone of each.
    private static final List<String> RATINGS = List.of(
            "IDS_CMT_NOTRATED", "IDS_CMT_FAKE", "IDS_CMT_POOR", "IDS_CMT_FAIR", "IDS_CMT_GOOD", "IDS_CMT_EXCELLENT");
    private static final List<String> RATING_TONES =
            List.of("neutral", "danger", "warning", "neutral", "accent", "success");
    // What the download is, beside its tabs: wide enough for most names on one line and the legend on two, so that
    // little height shows it whole.
    private static final double HEAD_WIDTH = 440;

    private final Gui gui;
    private final Words words;
    private final Actions actions;
    private final Icon kind = Fx.icon(Icons.FILE);
    private final Label name = new Label();
    private final Label meta = new Label();
    private final Label percent = Fx.leftToRight(new Label());
    private final Label done = Fx.leftToRight(new Label());
    private final Label rate = new Label();
    private final PartBar bar = new PartBar();
    private final Label note = new Label();
    private final Icon noteIcon = Fx.icon(Icons.WARNING);
    private final Button first = new Button();
    private final Button second = new Button();
    private final Button close;
    private final MenuButton more = new MenuButton(null, Fx.icon(Icons.MORE));
    private final Menu priority;
    private final Menu category;
    private final GridPane facts = new GridPane();
    private final TextField hash = Fx.leftToRight(new TextField());
    private final TableView<SourceName> names = new TableView<>();
    private final TextField newName = new TextField();
    private final Button takeOver;
    private final Button cleanUp;
    private final Button rename;
    private final ListView<PeerComment> comments = new ListView<>();
    private final ListView<LiveList.Row<SourceInfo>> sources;
    private final Map<Tab, ToggleButton> tabs = new EnumMap<>(Tab.class);
    private final Map<Tab, Node> pages = new EnumMap<>(Tab.class);
    private final StackPane page = new StackPane();
    private final Label chosen = new Label();
    // The download the new name is for: it is reset only when another one is shown, not as the figures move.
    private Hash16 renaming;
    // The download shown, whose comments are asked for once.
    private Hash16 shownHash;
    private final Region one;
    private final Tooltip nameTip = new Tooltip();
    private VBox head;
    private final VBox many;
    private final Label none;
    private final StackPane view;

    DownloadDetails(Gui gui, Words words, Actions actions, ListView<LiveList.Row<SourceInfo>> sources) {
        this.gui = gui;
        this.words = words;
        this.actions = actions;
        this.sources = sources;
        this.close = Fx.iconButton(gui.t("IDS_FD_CLOSE"), Icons.CLOSE, actions.close());
        this.close.getStyleClass().add(Styles.FLAT);
        this.priority = new Menu(gui.t("IDS_PRIORITY"), Fx.icon(Icons.PRIORITY));
        this.category = new Menu(gui.t("IDS_CAT"), Fx.icon(Icons.LABEL));
        this.takeOver = Fx.button(gui.t("IDS_TAKEOVER"), Icons.ALIGN_BOTTOM, this::takeOver);
        this.takeOver.setId("take-over");
        this.cleanUp = Fx.button(gui.t("IDS_CLEANUP"), Icons.CLEAN, this::cleanUp);
        this.cleanUp.setId("clean-up");
        this.rename = Fx.button(gui.t("IDS_RENAME"), Icons.EDIT, this::rename);
        this.rename.setId("rename");
        this.one = Fx.scrollWhenShort(oneDownload());
        this.many = manyDownloads();
        this.none = new Label(gui.t("nj.details.none"));
        Fx.wrap(this.none);
        this.none.getStyleClass().add("details-none");
        this.view = new StackPane(this.one, this.many, this.none);
        this.view.getStyleClass().add("download-details");
        StackPane.setAlignment(this.none, Pos.TOP_LEFT);
        show(List.of());
    }

    // One download: its head, the tabs beside it.
    private HBox oneDownload() {
        this.kind.getStyleClass().add("details-kind-icon");
        StackPane kindBox = new StackPane(this.kind);
        kindBox.getStyleClass().add("details-kind");
        this.name.getStyleClass().add("details-name");
        // One line, cut in the middle so that the end of the name shows, whole in its tooltip: the head is as tall for
        // every download, and the details open as tall as they stay.
        this.name.setText(" ");
        this.name.setTextOverrun(OverrunStyle.CENTER_ELLIPSIS);
        this.name.setTooltip(this.nameTip);
        Fx.ownDirection(this.name);
        this.meta.getStyleClass().add("details-meta");
        Fx.wrap(this.meta);
        VBox title = new VBox(2, this.name, this.meta);
        title.setMinWidth(0);
        HBox.setHgrow(title, Priority.ALWAYS);
        HBox top = new HBox(12, kindBox, title);
        top.setAlignment(Pos.TOP_LEFT);
        this.percent.getStyleClass().addAll("details-percent", "display");
        this.done.getStyleClass().add("details-figure");
        this.rate.getStyleClass().add("details-figure");
        VBox figures = new VBox(this.done, this.rate);
        figures.setAlignment(Pos.BOTTOM_RIGHT);
        HBox progress = new HBox(12, this.percent, Fx.spacer(), figures);
        progress.setAlignment(Pos.BOTTOM_LEFT);
        this.bar.setPrefHeight(22);
        this.bar.setMinHeight(22);
        this.note.setGraphic(this.noteIcon);
        Fx.wrap(this.note);
        this.note.setMaxWidth(Double.MAX_VALUE);
        this.note.setMinHeight(Region.USE_PREF_SIZE);
        this.note.getStyleClass().add("details-note");
        for (Button button : List.of(this.first, this.second)) {
            Fx.whole(button);
        }
        this.first.setId("details-first");
        this.second.setId("details-second");
        Button copy = Fx.iconButton(
                this.gui.t("nj.copyLink") + "  "
                        + new KeyCodeCombination(KeyCode.C, KeyCombination.SHORTCUT_DOWN).getDisplayText(),
                Icons.COPY,
                this.actions.copyLink());
        copy.setId("details-copy");
        MenuItem preview = Fx.menuItem(this.gui.t("IDS_DL_PREVIEW"), Icons.PREVIEW, this.actions.preview());
        MenuItem comment = Fx.menuItem(this.gui.t("IDS_CMT_ADD"), Icons.COMMENT, this.actions.comments());
        MenuItem source = Fx.menuItem(this.gui.t("IDS_ADDSRCMANUALLY"), Icons.PERSON_ADD, this.actions.addSource());
        MenuItem cancel = Fx.menuItem(this.gui.t("nj.transfers.cancelOne"), Icons.DELETE, this.actions.cancel());
        cancel.getStyleClass().add("danger-item");
        this.more
                .getItems()
                .setAll(preview, comment, this.priority, this.category, source, new SeparatorMenuItem(), cancel);
        this.more.setId("details-more");
        this.more.getStyleClass().addAll(Styles.BUTTON_ICON, Tweaks.NO_ARROW);
        this.more.setTooltip(new Tooltip(this.gui.t("IDS_MORE")));
        this.more.setAccessibleText(this.gui.t("IDS_MORE"));
        // In a narrow pane the two buttons go one under the other; the icons keep to the end.
        FlowPane toDo = new FlowPane(8, 8, this.first, this.second);
        toDo.setMinWidth(0);
        HBox.setHgrow(toDo, Priority.ALWAYS);
        HBox actionRow = new HBox(8, toDo, copy, this.more);
        actionRow.setAlignment(Pos.TOP_LEFT);
        this.head = new VBox(8, top, progress, new VBox(6, this.bar, legend()), this.note, actionRow);
        this.head.getStyleClass().add("details-head");
        this.head.setPrefWidth(HEAD_WIDTH);
        this.head.setMinWidth(HEAD_WIDTH);
        this.head.setMaxWidth(HEAD_WIDTH);

        this.pages.put(Tab.SOURCES, sourcesPage());
        this.pages.put(Tab.FACTS, factsPage());
        this.pages.put(Tab.NAMES, namesPage());
        this.pages.put(Tab.COMMENTS, commentsPage());
        ToggleGroup group = new ToggleGroup();
        FlowPane tabRow = new FlowPane(14, 0);
        for (Tab tab : Tab.values()) {
            Label count = new Label();
            count.getStyleClass().add("chip-count");
            ToggleButton button = new ToggleButton(tabTitle(tab), count);
            button.setContentDisplay(ContentDisplay.RIGHT);
            button.setToggleGroup(group);
            button.getStyleClass().add("details-tab");
            button.setId("details-tab-" + tab.name().toLowerCase(Locale.ROOT));
            button.setOnAction(e -> showTab(tab));
            Fx.whole(button);
            this.tabs.put(tab, button);
            tabRow.getChildren().add(button);
        }
        group.selectedToggleProperty().addListener((o, was, now) -> {
            if (now == null && was != null) {
                was.setSelected(true);
            }
        });
        this.sources.getItems().addListener((ListChangeListener<LiveList.Row<SourceInfo>>) c -> countSources());
        showTab(Tab.SOURCES);
        VBox.setVgrow(this.page, Priority.ALWAYS);
        // The tabs, and at the end of their row the button that closes the details.
        HBox.setHgrow(tabRow, Priority.ALWAYS);
        HBox tabBar = new HBox(8, tabRow, this.close);
        tabBar.setAlignment(Pos.CENTER_LEFT);
        tabBar.getStyleClass().add("details-tabs");
        VBox tabbed = new VBox(tabBar, this.page);
        HBox.setHgrow(tabbed, Priority.ALWAYS);
        tabbed.setMinWidth(0);
        HBox box = new HBox(this.head, tabbed);
        box.getStyleClass().add("details-content");
        return box;
    }

    // Several downloads: how many, and what can be done to all of them.
    private VBox manyDownloads() {
        this.chosen.getStyleClass().add("details-name");
        FlowPane buttons = new FlowPane(
                8,
                8,
                Fx.button(this.gui.t("IDS_DL_PAUSE"), Icons.PAUSE, this.actions.pause()),
                Fx.button(this.gui.t("IDS_DL_RESUME"), Icons.PLAY, this.actions.resume()),
                Fx.button(this.gui.t("IDS_DL_STOP"), Icons.STOP, this.actions.stop()),
                Fx.button(this.gui.t("nj.transfers.cancelMany"), Icons.DELETE, this.actions.cancel()),
                Fx.button(this.gui.t("nj.copyLink"), Icons.COPY, this.actions.copyLink()));
        VBox box = new VBox(12, this.chosen, buttons);
        box.getStyleClass().add("details-many");
        return box;
    }

    private Node sourcesPage() {
        Label empty = new Label(this.gui.t("nj.transfers.noSources"));
        Fx.wrap(empty);
        empty.getStyleClass().add("empty-text");
        Button add = Fx.button(this.gui.t("IDS_ADDSRCMANUALLY"), Icons.PERSON_ADD, this.actions.addSource());
        VBox placeholder = new VBox(10, Fx.icon(Icons.NO_SOURCES), empty, add);
        placeholder.setAlignment(Pos.CENTER);
        placeholder.getStyleClass().add("empty-state");
        this.sources.setPlaceholder(placeholder);
        return this.sources;
    }

    private Node factsPage() {
        this.hash.setEditable(false);
        this.hash.getStyleClass().add("details-hash");
        this.hash.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(this.hash, Priority.ALWAYS);
        this.facts.getStyleClass().add("details-facts");
        Button copyHash =
                Fx.iconButton(this.gui.t("IDS_COPY"), Icons.COPY, () -> ClipboardWatch.copy(this.hash.getText()));
        copyHash.getStyleClass().add(Styles.FLAT);
        Label hashName = label("IDS_FD_HASH");
        VBox box = new VBox(10, this.facts, hashName, new HBox(6, this.hash, copyHash));
        box.getStyleClass().add("details-page");
        return box;
    }

    private Node namesPage() {
        this.newName.setId("new-name");
        this.newName.setAccessibleText(this.gui.t("IDS_RENAME"));
        this.newName.setMaxWidth(Double.MAX_VALUE);
        // The name the whole width, the buttons below it: the pane is narrow.
        FlowPane renameActions = new FlowPane(8, 8, this.takeOver, this.cleanUp, this.rename);
        VBox box = new VBox(10, namesTable(), this.newName, renameActions);
        VBox.setVgrow(this.names, Priority.ALWAYS);
        box.getStyleClass().add("details-page");
        return box;
    }

    private Node commentsPage() {
        this.comments.setId("comments");
        this.comments.getStyleClass().add("comment-list");
        Label empty = new Label(this.gui.t("IDS_CMT_NONE"));
        empty.getStyleClass().add("empty-text");
        this.comments.setPlaceholder(empty);
        this.comments.setCellFactory(l -> new CommentCell());
        Button rate = Fx.button(this.gui.t("IDS_CMT_ADD"), Icons.COMMENT, this.actions.comments());
        VBox box = new VBox(10, this.comments, rate);
        VBox.setVgrow(this.comments, Priority.ALWAYS);
        box.getStyleClass().add("details-page");
        return box;
    }

    private String tabTitle(Tab tab) {
        return this.gui.t(
                switch (tab) {
                    case SOURCES -> "IDS_DL_SOURCES";
                    case FACTS -> "IDS_DETAILS";
                    case NAMES -> "IDS_SOURCENAMES";
                    case COMMENTS -> "IDS_COMMENT";
                });
    }

    void showTab(Tab tab) {
        this.tabs.get(tab).setSelected(true);
        this.page.getChildren().setAll(this.pages.get(tab));
    }

    /** The items of the priority menu, made anew each time it shows. */
    void priorityMenu(Supplier<List<MenuItem>> items) {
        this.more.setOnShowing(e -> this.priority.getItems().setAll(items.get()));
        this.priority.getItems().setAll(items.get());
    }

    /** The items of the category menu, made anew each time it shows. */
    void categoryMenu(Supplier<List<MenuItem>> items) {
        var before = this.more.getOnShowing();
        this.more.setOnShowing(e -> {
            if (before != null) {
                before.handle(e);
            }
            this.category.getItems().setAll(items.get());
        });
    }

    Region view() {
        return this.view;
    }

    /** How tall the details must be to show the download's head, its buttons included. */
    double headHeight() {
        return this.head.prefHeight(HEAD_WIDTH) + this.view.snappedTopInset() + this.view.snappedBottomInset();
    }

    TableView<SourceName> names() {
        return this.names;
    }

    TextField newName() {
        return this.newName;
    }

    ListView<PeerComment> comments() {
        return this.comments;
    }

    /** The button of {@code action}: take-over, clean-up or rename. */
    Button button(String action) {
        return switch (action) {
            case "take-over" -> this.takeOver;
            case "clean-up" -> this.cleanUp;
            case "rename" -> this.rename;
            default -> throw new IllegalArgumentException(action);
        };
    }

    /** The two buttons of what to do now, for tests. */
    List<Button> stateButtons() {
        return List.of(this.first, this.second).stream().filter(Node::isVisible).toList();
    }

    Label note() {
        return this.note;
    }

    /** Shows the downloads {@code chosen}: one in full, several by their number, none with a word on choosing. */
    void show(List<DownloadInfo> chosen) {
        this.one.setVisible(chosen.size() == 1);
        this.many.setVisible(chosen.size() > 1);
        this.none.setVisible(chosen.isEmpty());
        if (chosen.size() == 1) {
            fill(chosen.getFirst());
        } else {
            this.shownHash = null;
            this.chosen.setText(this.gui.f("nj.details.chosen", chosen.size()));
        }
    }

    private void fill(DownloadInfo d) {
        var f = this.gui.formats();
        TransfersScreen.Shown shown = TransfersScreen.shown(d);
        this.kind.setGlyph(Fx.fileIcon(FileKind.of(d.name())));
        this.name.setText(d.name());
        this.nameTip.setText(d.name());
        String category = this.words.category().apply(d);
        this.meta.setText(f.size(d.size()) + (category.isEmpty() ? "" : " · " + category) + " · "
                + this.words.priority().apply(d));
        String share = f.percent(d.completed(), d.size());
        this.percent.setText(share);
        this.done.setText(this.gui.f("nj.details.doneOf", f.size(d.completed()), f.size(d.size())));
        this.rate.setText(
                shown == TransfersScreen.Shown.DOWNLOADING
                        ? "↓ " + f.rate(d.bytesPerSecond()) + " · "
                                + this.words.remaining().apply(d)
                        : this.words.state().apply(d));
        this.bar.set(d.parts(), d.completed());
        int total = (int) ((d.size() + PartMap.PART_SIZE - 1) / PartMap.PART_SIZE);
        int have = d.status() == DownloadInfo.Status.COMPLETE
                ? total
                : d.parts().size() == 0 ? (int) (d.completed() / PartMap.PART_SIZE) : complete(d.parts(), total);
        this.bar.setAccessibleText(share + ", " + have + "/" + total);
        showNote(shown);
        showActions(shown);
        fillFacts(d, have, total, share);
        fillNames(d);
        countSources();
        if (!d.hash().equals(this.shownHash)) {
            this.shownHash = d.hash();
            Hash16 asked = d.hash();
            this.comments.getItems().clear();
            this.tabs.get(Tab.COMMENTS).getGraphic().setVisible(false);
            this.gui.model().peerComments(asked, found -> {
                if (asked.equals(this.shownHash)) {
                    this.comments.getItems().setAll(found);
                    count(Tab.COMMENTS, found.size());
                }
            });
        }
    }

    // A word for a state that wants the user, in the tone of the state.
    private void showNote(TransfersScreen.Shown shown) {
        String key = switch (shown) {
            case NO_SOURCES -> "nj.details.noSourcesNote";
            case NO_SPACE -> "nj.details.diskFullNote";
            case ERROR -> "nj.details.errorNote";
            default -> null;
        };
        this.note.setVisible(key != null);
        this.note.setManaged(key != null);
        this.note.getStyleClass().removeAll("warning", "danger");
        if (key != null) {
            this.note.setText(this.gui.t(key));
            boolean problem = shown != TransfersScreen.Shown.NO_SOURCES;
            this.note.getStyleClass().add(problem ? "danger" : "warning");
            this.noteIcon.setGlyph(problem ? Icons.ERROR : Icons.WARNING);
        }
    }

    // What to do now, as the state asks: pause or resume, stop, find a source, see the folder.
    private void showActions(TransfersScreen.Shown shown) {
        switch (shown) {
            case DOWNLOADING, WAITING -> {
                action(this.first, "IDS_DL_PAUSE", Icons.PAUSE, this.actions.pause());
                action(this.second, "IDS_DL_STOP", Icons.STOP, this.actions.stop());
            }
            case NO_SOURCES -> {
                action(this.first, "IDS_DL_PAUSE", Icons.PAUSE, this.actions.pause());
                action(this.second, "IDS_ADDSRCMANUALLY", Icons.PERSON_ADD, this.actions.addSource());
            }
            case PAUSED -> {
                action(this.first, "IDS_DL_RESUME", Icons.PLAY, this.actions.resume());
                action(this.second, "IDS_DL_STOP", Icons.STOP, this.actions.stop());
            }
            case STOPPED -> {
                action(this.first, "IDS_DL_RESUME", Icons.PLAY, this.actions.resume());
                action(this.second, null, null, null);
            }
            // Short of disk space it still runs, and goes on by itself once there is room.
            case NO_SPACE -> {
                action(this.first, "IDS_DL_PAUSE", Icons.PAUSE, this.actions.pause());
                action(this.second, "IDS_OPENINC", Icons.FOLDER_OPEN, this.actions.openFolder());
            }
            case ERROR -> {
                action(this.first, "IDS_DL_RESUME", Icons.PLAY, this.actions.resume());
                action(this.second, "IDS_OPENINC", Icons.FOLDER_OPEN, this.actions.openFolder());
            }
            case COMPLETE -> {
                action(this.first, "IDS_OPENINC", Icons.FOLDER_OPEN, this.actions.openFolder());
                action(this.second, null, null, null);
            }
        }
    }

    private void action(Button button, String key, Icons glyph, Runnable run) {
        boolean shown = key != null;
        button.setVisible(shown);
        button.setManaged(shown);
        if (shown) {
            button.setText(this.gui.t(key));
            button.setGraphic(Fx.icon(glyph));
            button.setOnAction(e -> run.run());
        }
    }

    private void fillFacts(DownloadInfo d, int have, int total, String share) {
        var f = this.gui.formats();
        this.facts.getChildren().clear();
        int row = 0;
        row = fact(row, "nj.transfers.status", this.words.state().apply(d));
        row = fact(row, "IDS_FD_SIZE", f.size(d.size()));
        row = fact(row, "IDS_FD_COMPSIZE", f.size(d.completed()) + " (" + share + ")");
        row = fact(row, "IDS_FD_PARTS", have + " / " + total);
        row = fact(row, "IDS_FD_DATARATE", f.rate(d.bytesPerSecond()));
        row = fact(row, "IDS_DL_SOURCES", String.valueOf(d.sources()));
        row = fact(row, "IDS_TRANSFERRING", String.valueOf(d.transferring()));
        row = fact(row, "IDS_PRIORITY", this.words.priority().apply(d));
        row = fact(row, "IDS_CAT", this.words.category().apply(d));
        fact(row, "IDS_DL_REMAINS", this.words.remaining().apply(d));
        this.hash.setText(d.hash().toString());
    }

    private void countSources() {
        count(Tab.SOURCES, this.sources.getItems().size());
    }

    private void count(Tab tab, int count) {
        if (this.tabs.get(tab).getGraphic() instanceof Label figure) {
            figure.setText(Integer.toString(count));
            figure.setVisible(true);
            this.tabs.get(tab).setAccessibleText(tabTitle(tab) + ", " + count);
        }
    }

    // The names of the sources of the download, the most given first; a choice made stays while the list moves.
    private void fillNames(DownloadInfo d) {
        Map<String, Integer> counts = new HashMap<>();
        for (LiveList.Row<SourceInfo> row : this.gui.model().sources().rows()) {
            SourceInfo source = row.get();
            if (source.file().equals(d.hash())) {
                source.reportedName().ifPresent(n -> counts.merge(n, 1, Integer::sum));
            }
        }
        List<SourceName> shownNames = counts.entrySet().stream()
                .map(e -> new SourceName(e.getKey(), e.getValue()))
                .sorted(Comparator.comparingInt(SourceName::sources).reversed().thenComparing(SourceName::name))
                .toList();
        if (!this.names.getItems().equals(shownNames)) {
            SourceName picked = this.names.getSelectionModel().getSelectedItem();
            this.names.getItems().setAll(shownNames);
            shownNames.stream()
                    .filter(n -> picked != null && n.name().equals(picked.name()))
                    .findFirst()
                    .ifPresent(n -> this.names.getSelectionModel().select(n));
        }
        count(Tab.NAMES, shownNames.size());
        if (!d.hash().equals(this.renaming)) {
            this.renaming = d.hash();
            this.newName.setText(d.name());
        }
        // A finished download has its file already.
        boolean renamable = d.status() != DownloadInfo.Status.COMPLETE;
        this.newName.setDisable(!renamable);
        this.takeOver.setDisable(!renamable);
        this.cleanUp.setDisable(!renamable);
        this.rename.setDisable(!renamable);
    }

    private TableView<SourceName> namesTable() {
        TableColumn<SourceName, String> nameColumn = new TableColumn<>(this.gui.t("IDS_DL_FILENAME"));
        nameColumn.setCellValueFactory(
                c -> new ReadOnlyStringWrapper(c.getValue().name()));
        Fx.ownDirection(nameColumn);
        TableColumn<SourceName, Number> countColumn = new TableColumn<>(this.gui.t("IDS_DL_SOURCES"));
        countColumn.setCellValueFactory(
                c -> new ReadOnlyIntegerWrapper(c.getValue().sources()));
        countColumn.setMaxWidth(90);
        for (TableColumn<SourceName, ?> column : List.of(nameColumn, countColumn)) {
            column.setSortable(false);
        }
        this.names.getColumns().setAll(List.of(nameColumn, countColumn));
        this.names.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        this.names.setPlaceholder(new Label());
        this.names.setPrefHeight(150);
        this.names.setMinHeight(90);
        this.names.setId("source-names");
        this.names.setRowFactory(t -> {
            TableRow<SourceName> row = new TableRow<>();
            row.setOnMouseClicked(e -> {
                if (e.getClickCount() == 2 && !row.isEmpty()) {
                    takeOver();
                }
            });
            return row;
        });
        return this.names;
    }

    private void takeOver() {
        SourceName picked = this.names.getSelectionModel().getSelectedItem();
        if (picked != null && !this.newName.isDisabled()) {
            this.newName.setText(picked.name());
        }
    }

    private void cleanUp() {
        Preferences preferences = this.gui.model().preferencesProperty().get();
        String cleanups = preferences == null ? Preferences.DEFAULT_NAME_CLEANUPS : preferences.nameCleanups();
        this.newName.setText(FileNames.cleanUp(this.newName.getText(), cleanups));
    }

    private void rename() {
        String picked = this.newName.getText().strip();
        if (this.renaming != null && !picked.isEmpty()) {
            this.gui.model().renameDownload(this.renaming, picked);
        }
    }

    private int fact(int row, String key, String value) {
        Label shownValue = new Label(value);
        Fx.wrap(shownValue);
        shownValue.getStyleClass().add("details-value");
        this.facts.add(label(key), 0, row);
        this.facts.add(shownValue, 1, row);
        return row + 1;
    }

    private Label label(String key) {
        String text = this.gui.t(key).strip();
        Label label = new Label(text.endsWith(":") ? text.substring(0, text.length() - 1) : text);
        label.getStyleClass().add("text-muted");
        label.setMinWidth(Region.USE_PREF_SIZE);
        return label;
    }

    // What each colour of the bar means, flowing over as many lines as the pane needs; the words are there, not only
    // the colours.
    private Node legend() {
        FlowPane legend = new FlowPane(12, 4);
        legend.getStyleClass().add("details-legend");
        for (List<String> entry : List.of(
                List.of("bar-have", "nj.legend.have"),
                List.of("bar-requested", "nj.legend.requested"),
                List.of("bar-sources-max", "nj.legend.sources"),
                List.of("bar-missing", "nj.legend.noSource"),
                List.of("bar-complete", "nj.legend.done"))) {
            Region swatch = new Region();
            swatch.getStyleClass().addAll("legend-swatch", entry.get(0));
            Label label = new Label(this.gui.t(entry.get(1)), swatch);
            Fx.wrap(label);
            legend.getChildren().add(label);
        }
        return legend;
    }

    // The parts with no byte missing.
    static int complete(PartMap map, int total) {
        boolean[] missing = new boolean[total];
        for (PartMap.Span gap : map.missing()) {
            int first = (int) (gap.start() / PartMap.PART_SIZE);
            int last = (int) (Math.max(gap.start(), gap.end() - 1) / PartMap.PART_SIZE);
            for (int p = first; p <= last && p < total; p++) {
                missing[p] = true;
            }
        }
        int have = 0;
        for (boolean m : missing) {
            if (!m) {
                have++;
            }
        }
        return have;
    }

    /** A comment heard from a source: who, the rating on a chip of its tone, and the words. */
    private final class CommentCell extends ListCell<PeerComment> {
        @Override
        protected void updateItem(PeerComment comment, boolean empty) {
            super.updateItem(comment, empty);
            setText(null);
            if (empty || comment == null) {
                setGraphic(null);
                return;
            }
            FileComment said = comment.comment();
            int rating = Math.clamp(said.rating(), 0, FileComment.MAX_RATING);
            Label who = new Label(comment.user());
            who.getStyleClass().add("comment-user");
            Fx.ownDirection(who);
            HBox head = new HBox(8, who);
            head.setAlignment(Pos.CENTER_LEFT);
            if (rating > 0) {
                head.getChildren()
                        .add(Fx.chip(
                                DownloadDetails.this.gui.t(RATINGS.get(rating)), Icons.STAR, RATING_TONES.get(rating)));
            }
            Label words = new Label(said.text());
            Fx.wrap(words);
            words.getStyleClass().add("comment-text");
            Fx.ownDirection(words);
            VBox box = new VBox(4, head, words);
            box.setMinWidth(0);
            setGraphic(box);
        }
    }
}
