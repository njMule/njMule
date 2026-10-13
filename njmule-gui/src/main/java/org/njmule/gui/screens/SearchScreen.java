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
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.collections.ListChangeListener;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import org.kordamp.ikonli.Ikon;
import org.kordamp.ikonli.material2.Material2AL;
import org.kordamp.ikonli.material2.Material2MZ;
import org.njmule.core.api.DownloadInfo;
import org.njmule.core.api.KadInfo;
import org.njmule.core.api.SearchRequest;
import org.njmule.core.api.SearchResult;
import org.njmule.core.api.ServerInfo;
import org.njmule.core.api.SharedFileInfo;
import org.njmule.core.api.SpamMarks;
import org.njmule.gui.model.EngineModel;
import org.njmule.gui.model.LiveList;
import org.njmule.gui.ui.Fx;
import org.njmule.gui.ui.Gui;
import org.njmule.gui.ui.Input;
import org.njmule.gui.ui.Inputs;
import org.njmule.gui.ui.Screen;
import org.njmule.gui.ui.TableLayout;

/** Searches with eMule's parameters, one tab of results each. */
public final class SearchScreen implements Screen {

    /** eMule's search methods: Automatic picks Kad or the server by what is connected. */
    enum Method {
        AUTOMATIC,
        SERVER,
        GLOBAL,
        KAD
    }

    /** The file types of eMule's search, with the value of the type attribute. */
    enum FileType {
        ANY("IDS_SEARCH_ANY", ""),
        ARCHIVE("IDS_SEARCH_ARC", "archive"),
        AUDIO("IDS_SEARCH_AUDIO", "audio"),
        CD_IMAGE("IDS_SEARCH_CDIMG", "cd"),
        PICTURE("IDS_SEARCH_PICS", "image"),
        PROGRAM("IDS_SEARCH_PRG", "program"),
        VIDEO("IDS_SEARCH_VIDEO", "video"),
        DOCUMENT("IDS_SEARCH_DOC", "document");

        final String text;
        final String attribute;

        FileType(String text, String attribute) {
            this.text = text;
            this.attribute = attribute;
        }
    }

    /** What we do with a result's file already: download it, or share it. */
    enum Known {
        NONE,
        DOWNLOADING,
        SHARED,
        SPAM
    }

    private static final String HISTORY = "search";
    private static final String EMULE_HISTORY = "AC_SearchStrings.dat";
    private static final int MAX_SIZE_MB = 256 * 1024;
    private static final int MAX_SOURCES = 1_000_000;
    private static final int MAX_EXTENSION = 10;
    private static final Pattern ATTRIBUTES = Pattern.compile("\\s*@\\S+");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private final Gui gui;
    private Node view;
    private final ComboBox<String> query = new ComboBox<>();
    private Input<String> queryInput;
    private final ComboBox<Method> method = new ComboBox<>();
    private final ComboBox<FileType> type = new ComboBox<>();
    private final TextField minSize = new TextField();
    private final TextField maxSize = new TextField();
    private final TextField availability = new TextField();
    private final TextField complete = new TextField();
    private final TextField extension = new TextField();
    private Input<OptionalInt> minSizeInput;
    private Input<OptionalInt> maxSizeInput;
    private Input<OptionalInt> availabilityInput;
    private Input<OptionalInt> completeInput;
    private Input<String> extensionInput;
    private final TabPane tabs = new TabPane();
    private final Map<Tab, Results> results = new HashMap<>();

    private record Results(EngineModel.Search search, TableView<LiveList.Row<SearchResult>> table) {}

    public SearchScreen(Gui gui) {
        this.gui = gui;
    }

    @Override
    public String id() {
        return "search";
    }

    @Override
    public String title() {
        return this.gui.t("IDS_EM_SEARCH");
    }

    @Override
    public Ikon icon() {
        return Material2MZ.SEARCH;
    }

    @Override
    public Node view() {
        if (this.view == null) {
            this.view = this.build();
        }
        return this.view;
    }

    private Node build() {
        Inputs inputs = new Inputs(this.gui);
        this.query.setEditable(true);
        this.query.setPrefWidth(320);
        this.query.setPromptText(this.gui.t("IDS_SEARCH_TEXT"));
        this.gui.settings().seedHistory(HISTORY, this.gui.directories().config().resolve(EMULE_HISTORY));
        this.query.getItems().setAll(this.gui.settings().history(HISTORY));
        this.query.getEditor().setOnAction(e -> this.start());
        this.queryInput = inputs.searchExpression(this.query.getEditor(), this.gui.t("IDS_SEARCH_TEXT"))
                .quietWhenEmpty();
        Button start = Fx.primary(Fx.button(this.gui.t("nj.search.start"), Material2MZ.SEARCH, this::start));
        start.disableProperty().bind(this.queryInput.validProperty().not());
        this.method.getItems().addAll(Method.values());
        this.method.setValue(Method.AUTOMATIC);
        this.method.setCellFactory(l -> new MethodCell());
        this.method.setButtonCell(new MethodCell());
        var tools = Fx.toolRow(
                this.query,
                this.method,
                start,
                Fx.button(this.gui.t("nj.search.more"), Material2AL.ADD, this::more),
                Fx.button(this.gui.t("IDS_DL_STOP"), Material2MZ.STOP, this::stop),
                Fx.spacer(),
                Fx.button(this.gui.t("IDS_DOWNLOAD"), Material2AL.CLOUD_DOWNLOAD, this::downloadSelected));
        this.tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.ALL_TABS);
        // A file downloaded, shared or marked since shows so in every list.
        this.gui.model().spamMarksProperty().addListener((o, was, now) -> this.refreshAll());
        this.gui.model().downloads().rows().addListener((ListChangeListener<LiveList.Row<DownloadInfo>>)
                this::knownChanged);
        this.gui.model().sharedFiles().rows().addListener((ListChangeListener<LiveList.Row<SharedFileInfo>>)
                this::knownChanged);
        VBox content = new VBox(8, this.parameters(inputs), this.tabs);
        VBox.setVgrow(this.tabs, Priority.ALWAYS);
        return Fx.screen(this.title(), tools, content);
    }

    // eMule's search parameters: the type and the attributes the server or Kad filter on.
    private Node parameters(Inputs inputs) {
        this.type.getItems().addAll(FileType.values());
        this.type.setValue(FileType.ANY);
        this.type.setCellFactory(l -> new TypeCell());
        this.type.setButtonCell(new TypeCell());
        for (TextField f : List.of(this.minSize, this.maxSize, this.availability, this.complete)) {
            f.setPrefColumnCount(6);
        }
        this.extension.setPrefColumnCount(5);
        this.minSizeInput = inputs.optionalNumber(this.minSize, this.gui.t("IDS_SEARCHMINSIZE"), 0, MAX_SIZE_MB);
        this.maxSizeInput = inputs.optionalNumber(this.maxSize, this.gui.t("IDS_SEARCHMAXSIZE"), 1, MAX_SIZE_MB);
        this.availabilityInput =
                inputs.optionalNumber(this.availability, this.gui.t("IDS_SEARCHAVAIL"), 1, MAX_SOURCES);
        this.completeInput = inputs.optionalNumber(this.complete, this.gui.t("IDS_COMPLSOURCES"), 1, MAX_SOURCES);
        this.extensionInput = inputs.text(this.extension, this.gui.t("IDS_SEARCHEXTENTION"), MAX_EXTENSION, false);
        HBox row = new HBox(
                8,
                new Label(this.gui.t("IDS_TYPE")),
                this.type,
                new Label(this.gui.t("IDS_SEARCHMINSIZE")),
                this.minSize,
                new Label(this.gui.t("IDS_SEARCHMAXSIZE")),
                this.maxSize,
                new Label(this.gui.t("IDS_SEARCHAVAIL")),
                this.availability,
                new Label(this.gui.t("IDS_COMPLSOURCES")),
                this.complete,
                new Label(this.gui.t("IDS_SEARCHEXTENTION")),
                this.extension,
                Fx.button(this.gui.t("IDS_PW_RESET"), Material2MZ.REFRESH, this::resetParameters));
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("search-parameters");
        return row;
    }

    void resetParameters() {
        this.type.setValue(FileType.ANY);
        for (TextField f : List.of(this.minSize, this.maxSize, this.availability, this.complete, this.extension)) {
            f.clear();
        }
    }

    /** The expression for the search box words with eMule's parameters as attributes. */
    static String expression(
            String words,
            FileType type,
            OptionalInt minMb,
            OptionalInt maxMb,
            OptionalInt availability,
            OptionalInt complete,
            String extension) {
        StringBuilder e = new StringBuilder(words.strip());
        if (type != FileType.ANY) {
            e.append(" @type=").append(type.attribute);
        }
        minMb.ifPresent(v -> e.append(" @size>=").append(v).append('m'));
        maxMb.ifPresent(v -> e.append(" @size<=").append(v).append('m'));
        availability.ifPresent(v -> e.append(" @sources>=").append(v));
        complete.ifPresent(v -> e.append(" @complete>=").append(v));
        String ext = extension.strip();
        if (!ext.isEmpty()) {
            e.append(" @ext=").append(ext.startsWith(".") ? ext.substring(1) : ext);
        }
        return e.toString();
    }

    private String methodName(Method m) {
        return switch (m) {
            case AUTOMATIC -> this.gui.t("IDS_AUTOMATIC");
            case SERVER -> this.gui.t("IDS_SERVER");
            case GLOBAL -> this.gui.t("IDS_GLOBALSEARCH");
            case KAD -> this.gui.t("IDS_KADEMLIA");
        };
    }

    // Automatic, as in eMule: the network we are connected to, Kad when both unless the server is a static one.
    private Optional<SearchRequest.Scope> scope() {
        if (this.method.getValue() != Method.AUTOMATIC) {
            return Optional.of(
                    switch (this.method.getValue()) {
                        case SERVER, AUTOMATIC -> SearchRequest.Scope.SERVER;
                        case GLOBAL -> SearchRequest.Scope.GLOBAL;
                        case KAD -> SearchRequest.Scope.KAD;
                    });
        }
        boolean server = this.gui.model().connectionProperty().get() == EngineModel.Connection.CONNECTED;
        var status = this.gui.model().statusProperty().get();
        boolean kad = status.kad().map(KadInfo::connected).orElse(false);
        if (server && kad) {
            return Optional.of(
                    status.server().map(ServerInfo::isStatic).orElse(false)
                            ? SearchRequest.Scope.SERVER
                            : SearchRequest.Scope.KAD);
        }
        if (server) {
            return Optional.of(SearchRequest.Scope.SERVER);
        }
        return kad ? Optional.of(SearchRequest.Scope.KAD) : Optional.empty();
    }

    void start() {
        String words = this.query.getEditor().getText();
        if (words.isBlank()) {
            return;
        }
        if (!this.queryInput.isValid()) {
            this.gui.inform().accept(this.query.getEditor().getTooltip().getText());
            return;
        }
        for (Input<?> input : List.of(
                this.minSizeInput,
                this.maxSizeInput,
                this.availabilityInput,
                this.completeInput,
                this.extensionInput)) {
            if (!input.isValid()) {
                this.gui.inform().accept(input.field().getTooltip().getText());
                return;
            }
        }
        String expression = expression(
                this.queryInput.value(),
                this.type.getValue(),
                this.minSizeInput.value(),
                this.maxSizeInput.value(),
                this.availabilityInput.value(),
                this.completeInput.value(),
                this.extensionInput.value());
        try {
            SearchRequest.checkExpression(expression);
        } catch (RuntimeException e) {
            this.gui.inform().accept(e.getMessage());
            return;
        }
        Optional<SearchRequest.Scope> scope = this.scope();
        if (scope.isEmpty()) {
            this.gui.inform().accept(this.gui.t("IDS_NOTCONNECTEDANY"));
            return;
        }
        this.remember(words.strip());
        this.gui.model().search(new SearchRequest(expression, scope.get()), this::opened);
    }

    private void remember(String words) {
        this.gui.settings().remember(HISTORY, words);
        try {
            this.gui.settings().save();
        } catch (IOException e) {
            this.gui.inform().accept(e.getMessage());
        }
        this.query.getItems().setAll(this.gui.settings().history(HISTORY));
        this.query.getEditor().setText(words);
    }

    private void opened(EngineModel.Search search) {
        var formats = this.gui.formats();
        ObjectProperty<Predicate<SearchResult>> shown = new SimpleObjectProperty<>(r -> true);
        TableView<LiveList.Row<SearchResult>> table =
                Fx.table(search.results(), this.gui.t("nj.search.waiting"), shown);
        TableColumn<LiveList.Row<SearchResult>, Known> known =
                Fx.column(this.gui.t("nj.search.known"), 110, r -> this.known(r));
        known.setCellFactory(c -> new KnownCell());
        table.getColumns()
                .addAll(List.of(
                        Fx.column(this.gui.t("IDS_DL_FILENAME"), 380, SearchResult::name),
                        Fx.column(this.gui.t("IDS_DL_SIZE"), 90, SearchResult::size, formats::size),
                        Fx.column(this.gui.t("IDS_SEARCHAVAIL"), 90, SearchResult::sources),
                        Fx.column(this.gui.t("IDS_COMPLETE"), 90, SearchResult::completeSources),
                        Fx.column(
                                this.gui.t("nj.search.type"), 90, r -> r.type().orElse("")),
                        known));
        TableLayout.remember(table, "search", this.gui.settings());
        table.setRowFactory(t -> {
            TableRow<LiveList.Row<SearchResult>> row = new TableRow<>();
            row.setOnMouseClicked(e -> {
                if (e.getClickCount() == 2 && !row.isEmpty()) {
                    this.gui.model().addDownload(row.getItem().get().link());
                }
            });
            return row;
        });
        MenuItem download = Fx.menuItem(this.gui.t("IDS_DOWNLOAD"), Material2AL.CLOUD_DOWNLOAD, this::downloadSelected);
        MenuItem copy = Fx.menuItem(this.gui.t("nj.copyLink"), Material2AL.CONTENT_COPY, this::copyLinks);
        MenuItem related = Fx.menuItem(this.gui.t("IDS_SEARCHRELATED"), Material2MZ.SEARCH, this::searchRelated);
        MenuItem spam = Fx.menuItem(this.gui.t("IDS_MARKSPAM"), Material2AL.BLOCK, () -> this.markSpam(true));
        MenuItem notSpam = Fx.menuItem(this.gui.t("IDS_MARKNOTSPAM"), Material2AL.CHECK, () -> this.markSpam(false));
        ContextMenu menu = new ContextMenu(download, copy, related, new SeparatorMenuItem(), spam, notSpam);
        menu.setOnShowing(e -> {
            List<SearchResult> selected = Fx.selected(table);
            SpamMarks marks = this.gui.model().spamMarksProperty().get();
            related.setDisable(selected.isEmpty()
                    || !this.gui.model().statusProperty().get().relatedSearch());
            spam.setDisable(selected.stream().allMatch(marks::isSpam));
            notSpam.setDisable(selected.stream().noneMatch(marks::isSpam));
        });
        table.setContextMenu(menu);

        TextField filter = new TextField();
        filter.setPromptText(this.gui.t("nj.search.filter"));
        filter.setPrefColumnCount(18);
        filter.textProperty().addListener((o, was, now) -> shown.set(matching(now)));
        filter.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ESCAPE) {
                filter.clear();
            }
        });
        Label count = new Label();
        count.getStyleClass().add(Styles.TEXT_BOLD);
        String title =
                ATTRIBUTES.matcher(search.request().expression()).replaceAll("").strip();
        Tab tab = new Tab(title);
        Runnable counted = () -> {
            int all = search.results().rows().size();
            int visible = table.getItems().size();
            tab.setText(title + " (" + all + ")");
            count.setText(this.gui.f("nj.search.count", visible == all ? String.valueOf(all) : visible + "/" + all));
        };
        table.getItems().addListener((ListChangeListener<LiveList.Row<SearchResult>>) c -> counted.run());
        search.results().rows().addListener((ListChangeListener<LiveList.Row<SearchResult>>) c -> counted.run());
        counted.run();
        HBox head = new HBox(8, count, Fx.spacer(), Fx.icon(Material2MZ.SEARCH), filter);
        head.setAlignment(Pos.CENTER_LEFT);
        VBox pane = new VBox(6, head, table);
        VBox.setVgrow(table, Priority.ALWAYS);
        tab.setContent(pane);
        tab.setTooltip(new Tooltip(search.request().expression()));

        ProgressIndicator running = new ProgressIndicator();
        running.setPrefSize(14, 14);
        running.visibleProperty().bind(search.runningProperty());
        running.managedProperty().bind(search.runningProperty());
        tab.setGraphic(running);
        tab.setOnClosed(e -> this.closed(tab));
        this.results.put(tab, new Results(search, table));
        this.tabs.getTabs().add(tab);
        this.tabs.getSelectionModel().select(tab);
    }

    /** Results whose name holds every word of {@code text}, in any case. */
    static Predicate<SearchResult> matching(String text) {
        List<String> words = WHITESPACE
                .splitAsStream(text.strip().toLowerCase(Locale.ROOT))
                .filter(w -> !w.isEmpty())
                .toList();
        if (words.isEmpty()) {
            return r -> true;
        }
        return r -> {
            String name = r.name().toLowerCase(Locale.ROOT);
            return words.stream().allMatch(name::contains);
        };
    }

    private Known known(SearchResult result) {
        if (this.gui.model().spamMarksProperty().get().isSpam(result)) {
            return Known.SPAM;
        }
        if (this.gui.model().isDownloading(result.hash())) {
            return Known.DOWNLOADING;
        }
        return this.gui.model().isShared(result.hash()) ? Known.SHARED : Known.NONE;
    }

    // Only files coming or going matter, not their progress.
    private void knownChanged(ListChangeListener.Change<?> change) {
        boolean membership = false;
        while (change.next()) {
            membership |= change.wasAdded() || change.wasRemoved();
        }
        if (membership) {
            this.refreshAll();
        }
    }

    private void refreshAll() {
        for (Results r : this.results.values()) {
            r.table().refresh();
        }
    }

    private void closed(Tab tab) {
        Results closed = this.results.remove(tab);
        if (closed != null) {
            this.gui.model().stopSearch(closed.search());
        }
    }

    private Results current() {
        return this.results.get(this.tabs.getSelectionModel().getSelectedItem());
    }

    void more() {
        Results current = this.current();
        if (current != null) {
            this.gui.model().moreResults(current.search());
        }
    }

    void stop() {
        Results current = this.current();
        if (current != null) {
            this.gui.model().stopSearch(current.search());
        }
    }

    void downloadSelected() {
        Results current = this.current();
        if (current != null) {
            Fx.selected(current.table()).forEach(r -> this.gui.model().addDownload(r.link()));
        }
    }

    /** eMule's related search: the server finds files people download along with the selected ones. */
    void searchRelated() {
        Results current = this.current();
        if (current == null) {
            return;
        }
        List<SearchResult> selected = Fx.selected(current.table());
        if (selected.isEmpty()) {
            return;
        }
        StringBuilder expression = new StringBuilder("related");
        for (SearchResult r : selected) {
            expression.append("::").append(r.hash().toHex());
        }
        this.gui.model().search(new SearchRequest(expression.toString(), SearchRequest.Scope.SERVER), this::opened);
    }

    void markSpam(boolean isSpam) {
        Results current = this.current();
        if (current != null && !Fx.selected(current.table()).isEmpty()) {
            this.gui.model().markSpam(Fx.selected(current.table()), isSpam);
        }
    }

    void copyLinks() {
        Results current = this.current();
        if (current == null) {
            return;
        }
        List<String> links =
                Fx.selected(current.table()).stream().map(r -> r.link().toUri()).toList();
        if (!links.isEmpty()) {
            ClipboardContent content = new ClipboardContent();
            content.putString(String.join("\n", links));
            Clipboard.getSystemClipboard().setContent(content);
        }
    }

    TextField queryField() {
        return this.query.getEditor();
    }

    ComboBox<Method> methodBox() {
        return this.method;
    }

    ComboBox<FileType> typeBox() {
        return this.type;
    }

    TextField minSizeField() {
        return this.minSize;
    }

    TabPane tabs() {
        return this.tabs;
    }

    /** The results table of {@code tab}. */
    TableView<LiveList.Row<SearchResult>> table(Tab tab) {
        return this.results.get(tab).table();
    }

    private final class MethodCell extends ListCell<Method> {
        @Override
        protected void updateItem(Method item, boolean empty) {
            super.updateItem(item, empty);
            this.setText(empty || item == null ? null : SearchScreen.this.methodName(item));
        }
    }

    private final class TypeCell extends ListCell<FileType> {
        @Override
        protected void updateItem(FileType item, boolean empty) {
            super.updateItem(item, empty);
            this.setText(empty || item == null ? null : SearchScreen.this.gui.t(item.text));
        }
    }

    /** "Downloading" or "Shared" on a tint of its own, with the text, so colour is not the only cue. */
    private final class KnownCell extends TableCell<LiveList.Row<SearchResult>, Known> {
        @Override
        protected void updateItem(Known item, boolean empty) {
            super.updateItem(item, empty);
            this.getStyleClass().removeAll("known-downloading", "known-shared", "known-spam");
            if (empty || item == null || item == Known.NONE) {
                this.setText(null);
                return;
            }
            this.setText(SearchScreen.this.gui.t(
                    switch (item) {
                        case DOWNLOADING -> "IDS_DOWNLOADING";
                        case SHARED -> "IDS_SHARED";
                        case SPAM, NONE -> "IDS_SPAM";
                    }));
            this.getStyleClass()
                    .add(
                            switch (item) {
                                case DOWNLOADING -> "known-downloading";
                                case SHARED -> "known-shared";
                                case SPAM, NONE -> "known-spam";
                            });
        }
    }
}
