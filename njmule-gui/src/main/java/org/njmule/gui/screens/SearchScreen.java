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
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import javafx.beans.binding.Bindings;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
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
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import org.kordamp.ikonli.Ikon;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.material2.Material2AL;
import org.kordamp.ikonli.material2.Material2MZ;
import org.njmule.core.api.Ed2kLink;
import org.njmule.core.api.FileHistory;
import org.njmule.core.api.Hash16;
import org.njmule.core.api.KadInfo;
import org.njmule.core.api.SavedSearch;
import org.njmule.core.api.SearchRequest;
import org.njmule.core.api.SearchResult;
import org.njmule.core.api.ServerInfo;
import org.njmule.core.api.SpamMarks;
import org.njmule.gui.model.EngineModel;
import org.njmule.gui.model.LiveList;
import org.njmule.gui.ui.ClipboardWatch;
import org.njmule.gui.ui.Fx;
import org.njmule.gui.ui.Gui;
import org.njmule.gui.ui.Input;
import org.njmule.gui.ui.Inputs;
import org.njmule.gui.ui.Screen;
import org.njmule.gui.ui.Segments;
import org.njmule.gui.ui.TableLayout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Searches with their parameters, one tab of results each. */
public final class SearchScreen implements Screen {

    /** The search methods: Automatic picks Kad or the server by what is connected. */
    enum Method {
        AUTOMATIC,
        SERVER,
        GLOBAL,
        KAD
    }

    /** The file types a search can ask for, with the value of the type attribute. */
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

    /** What we did or do with a result's file: download it, share it, downloaded or cancelled it before. */
    enum Known {
        NONE,
        DOWNLOADING,
        SHARED,
        DOWNLOADED,
        CANCELLED,
        SPAM
    }

    private static final Logger logger = LoggerFactory.getLogger(SearchScreen.class);
    // At exit the open searches wait this long to be kept.
    private static final Duration SAVE_TIMEOUT = Duration.ofSeconds(5);
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
    private Segments<Method> method;
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
    private final ToggleButton parametersButton = new ToggleButton();
    private final BooleanProperty noneChosen = new SimpleBooleanProperty(true);
    private final VBox content = new VBox(8);
    private Node parameters;
    private final Map<Tab, Results> results = new HashMap<>();

    // title: the tab's own, such as a kept related search's; empty = from the expression.
    private record Results(EngineModel.Search search, TableView<LiveList.Row<SearchResult>> table, String title) {}

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
            this.view = build();
        }
        return this.view;
    }

    private Node build() {
        Inputs inputs = new Inputs(this.gui);
        this.query.setEditable(true);
        this.query.setPrefWidth(320);
        this.query.setMaxWidth(Double.MAX_VALUE);
        this.query.setPromptText(this.gui.t("IDS_SEARCH_TEXT"));
        this.gui.settings().seedHistory(HISTORY, this.gui.directories().config().resolve(EMULE_HISTORY));
        this.query.getItems().setAll(this.gui.settings().history(HISTORY));
        this.query.getEditor().setOnAction(e -> start());
        this.queryInput = inputs.searchExpression(this.query.getEditor(), this.gui.t("IDS_SEARCH_TEXT"))
                .quietWhenEmpty();
        Fx.hintOnBox(this.query);
        Button start = Fx.primary(Fx.button(this.gui.t("nj.search.start"), Material2MZ.SEARCH, this::start));
        start.disableProperty().bind(this.queryInput.validProperty().not());
        this.method = new Segments<>(List.of(Method.values()), this::methodName);
        // More and Stop act on the search shown, Download on the results chosen in it.
        Button more = Fx.button(this.gui.t("nj.search.more"), Material2AL.ADD, this::more);
        Button stop = Fx.button(this.gui.t("IDS_DL_STOP"), Material2MZ.STOP, this::stop);
        more.disableProperty().bind(Bindings.isEmpty(this.tabs.getTabs()));
        stop.disableProperty().bind(Bindings.isEmpty(this.tabs.getTabs()));
        Button download = Fx.button(this.gui.t("IDS_DOWNLOAD"), Material2AL.CLOUD_DOWNLOAD, this::downloadSelected);
        download.disableProperty().bind(this.noneChosen);
        this.tabs.getSelectionModel().selectedItemProperty().addListener((o, was, now) -> followChoice());
        // Two rows: what to look for, then where and with what, beside what acts on the results.
        HBox.setHgrow(this.query, Priority.ALWAYS);
        var tools = Fx.toolRow(this.query, start);
        var where =
                Fx.toolRow(this.method.node(), this.parametersButton, Fx.toolGap(), more, stop, Fx.spacer(), download);
        this.tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.ALL_TABS);
        // A file downloaded, shared or marked since shows so in every list.
        this.gui.subscriptions().onChange(this.gui.model().spamMarksProperty(), (was, now) -> refreshAll());
        this.gui.subscriptions().onChange(this.gui.model().historyProperty(), (was, now) -> refreshAll());
        this.gui.subscriptions().onListChange(this.gui.model().downloads().rows(), this::knownChanged);
        this.gui.subscriptions().onListChange(this.gui.model().sharedFiles().rows(), this::knownChanged);
        // The tabs left open at the last exit come back with the results they had.
        if (this.gui.model().preferencesProperty().get().storeSearches()) {
            this.gui.model().savedSearches(saved -> {
                for (SavedSearch s : saved) {
                    opened(this.gui.model().restored(s), s.title());
                }
            });
        }
        this.parameters = parameters(inputs);
        this.content.getChildren().setAll(this.tabs);
        VBox.setVgrow(this.tabs, Priority.ALWAYS);
        // The parameters fold away until wanted; the button counts those in use, and they show when any is.
        this.parametersButton.setGraphic(Fx.icon(Material2MZ.TUNE));
        this.parametersButton.setId("search-parameters");
        this.parametersButton.setOnAction(e -> showParameters(this.parametersButton.isSelected()));
        for (TextField f : List.of(this.minSize, this.maxSize, this.availability, this.complete, this.extension)) {
            f.textProperty().addListener((o, was, now) -> countParameters());
        }
        this.type.valueProperty().addListener((o, was, now) -> countParameters());
        countParameters();
        showParameters(parametersInUse() > 0);
        VBox below = new VBox(where, this.content);
        VBox.setVgrow(this.content, Priority.ALWAYS);
        return Fx.screen(title(), tools, below);
    }

    // The search parameters: the type and the attributes the server or Kad filter on.
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
        // The maximum is never below the minimum, which its hint tells.
        this.maxSizeInput =
                inputs.optionalNumber(this.maxSize, this.gui.t("IDS_SEARCHMAXSIZE"), this::lowestMaxSize, MAX_SIZE_MB);
        this.minSize.textProperty().addListener((o, was, now) -> {
            this.maxSizeInput.check();
            this.maxSize.getTooltip().setText(this.gui.f("nj.input.optionalNumber", lowestMaxSize(), MAX_SIZE_MB));
        });
        this.availabilityInput =
                inputs.optionalNumber(this.availability, this.gui.t("IDS_SEARCHAVAIL"), 1, MAX_SOURCES);
        this.completeInput = inputs.optionalNumber(this.complete, this.gui.t("IDS_COMPLSOURCES"), 1, MAX_SOURCES);
        this.extensionInput = inputs.extension(this.extension, this.gui.t("IDS_SEARCHEXTENTION"), MAX_EXTENSION);
        // Wraps instead of squeezing: longer translations never cut a label short.
        FlowPane row = new FlowPane(
                14,
                6,
                labelled("IDS_TYPE", this.type),
                labelled("IDS_SEARCHMINSIZE", this.minSize),
                labelled("IDS_SEARCHMAXSIZE", this.maxSize),
                labelled("IDS_SEARCHAVAIL", this.availability),
                labelled("IDS_COMPLSOURCES", this.complete),
                labelled("IDS_SEARCHEXTENTION", this.extension),
                Fx.button(this.gui.t("IDS_PW_RESET"), Material2MZ.REFRESH, this::resetParameters));
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("search-parameters");
        return row;
    }

    // A label kept beside its field, at its whole width.
    private HBox labelled(String key, Node field) {
        Label label = new Label(this.gui.t(key));
        label.setMinWidth(Region.USE_PREF_SIZE);
        label.setLabelFor(field);
        HBox pair = new HBox(6, label, field);
        pair.setAlignment(Pos.CENTER_LEFT);
        return pair;
    }

    private int lowestMaxSize() {
        return this.minSizeInput != null && this.minSizeInput.isValid()
                ? Math.max(1, this.minSizeInput.value().orElse(1))
                : 1;
    }

    void showParameters(boolean on) {
        this.parametersButton.setSelected(on);
        if (on && !this.content.getChildren().contains(this.parameters)) {
            this.content.getChildren().addFirst(this.parameters);
        } else if (!on) {
            this.content.getChildren().remove(this.parameters);
        }
    }

    private int parametersInUse() {
        int used = this.type.getValue() == FileType.ANY ? 0 : 1;
        for (TextField f : List.of(this.minSize, this.maxSize, this.availability, this.complete, this.extension)) {
            used += f.getText().isBlank() ? 0 : 1;
        }
        return used;
    }

    private void countParameters() {
        int used = parametersInUse();
        String name = this.gui.t("IDS_SEARCHPARAMS");
        this.parametersButton.setText(used == 0 ? name : name + " (" + used + ")");
    }

    void resetParameters() {
        this.type.setValue(FileType.ANY);
        for (TextField f : List.of(this.minSize, this.maxSize, this.availability, this.complete, this.extension)) {
            f.clear();
        }
    }

    /** The expression for the search box words with the parameters as attributes. */
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

    // Automatic: the network we are connected to, Kad when both unless the server is a static one.
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

    /** Searches the network for {@code words}, as typed in the search box and started. */
    public void searchFor(String words) {
        view();
        this.query.getEditor().setText(words);
        start();
    }

    void start() {
        String words = this.query.getEditor().getText();
        if (words.isBlank()) {
            return;
        }
        if (!this.queryInput.isValid()) {
            this.gui.warn().accept(this.query.getEditor().getTooltip().getText());
            return;
        }
        for (Input<?> input : List.of(
                this.minSizeInput,
                this.maxSizeInput,
                this.availabilityInput,
                this.completeInput,
                this.extensionInput)) {
            if (!input.isValid()) {
                this.gui.warn().accept(input.field().getTooltip().getText());
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
            this.gui.warn().accept(e.getMessage());
            return;
        }
        Optional<SearchRequest.Scope> scope = scope();
        if (scope.isEmpty()) {
            this.gui.warn().accept(this.gui.t("IDS_NOTCONNECTEDANY"));
            return;
        }
        remember(words.strip());
        this.gui.model().search(new SearchRequest(expression, scope.get()), this::opened);
    }

    private void remember(String words) {
        this.gui.settings().remember(HISTORY, words);
        try {
            this.gui.settings().save();
        } catch (IOException e) {
            this.gui.warn().accept(e.getMessage());
        }
        this.query.getItems().setAll(this.gui.settings().history(HISTORY));
        this.query.getEditor().setText(words);
    }

    @Override
    public void closing() {
        // Never opened: the searches kept last time stay as they are.
        if (this.view == null) {
            return;
        }
        List<SavedSearch> open = new ArrayList<>();
        if (this.gui.model().preferencesProperty().get().storeSearches()) {
            for (Tab tab : this.tabs.getTabs()) {
                Results r = this.results.get(tab);
                if (r != null) {
                    List<SearchResult> found = r.search().results().rows().stream()
                            .map(LiveList.Row::get)
                            .toList();
                    open.add(new SavedSearch(r.title(), r.search().request(), found));
                }
            }
        }
        try {
            this.gui.model().saveSearches(open).get(SAVE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (ExecutionException | TimeoutException e) {
            logger.warn("Cannot keep the open searches: {}", e.toString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void opened(EngineModel.Search search) {
        opened(search, "");
    }

    private void opened(EngineModel.Search search, String ownTitle) {
        var formats = this.gui.formats();
        ObjectProperty<Predicate<SearchResult>> shown = new SimpleObjectProperty<>(r -> true);
        TableView<LiveList.Row<SearchResult>> table =
                Fx.table(search.results(), this.gui.t("nj.search.waiting"), shown);
        TableColumn<LiveList.Row<SearchResult>, Known> known =
                Fx.column(this.gui.t("nj.search.known"), 130, r -> known(r));
        known.setCellFactory(c -> new KnownCell());
        TableColumn<LiveList.Row<SearchResult>, String> fileId = Fx.leftToRight(Fx.column(
                this.gui.t("IDS_FILEID"), 220, (SearchResult r) -> r.hash().toHex()));
        // Offered in the column menu.
        fileId.setVisible(false);
        String kbits = this.gui.t("IDS_KBITSSEC");
        List<TableColumn<LiveList.Row<SearchResult>, ?>> media = List.of(
                Fx.ownDirection(Fx.column(
                        this.gui.t("IDS_ARTIST"),
                        100,
                        (SearchResult r) -> r.media().artist())),
                Fx.ownDirection(Fx.column(
                        this.gui.t("IDS_ALBUM"),
                        100,
                        (SearchResult r) -> r.media().album())),
                Fx.ownDirection(Fx.column(
                        this.gui.t("IDS_TITLE"),
                        100,
                        (SearchResult r) -> r.media().title())),
                Fx.leftToRight(Fx.column(
                        this.gui.t("IDS_LENGTH"),
                        60,
                        (SearchResult r) -> r.media().length(),
                        s -> s == 0 ? "" : formats.playingTime(s))),
                Fx.leftToRight(Fx.column(
                        this.gui.t("IDS_BITRATE"),
                        80,
                        (SearchResult r) -> r.media().bitrate(),
                        b -> b == 0 ? "" : formats.count(b) + " " + kbits)),
                Fx.leftToRight(Fx.column(
                        this.gui.t("IDS_CODEC"),
                        60,
                        (SearchResult r) -> r.media().codec())));
        // Columns are remembered by their place: new ones go last.
        table.getColumns()
                .addAll(List.of(
                        Fx.ownDirection(Fx.column(this.gui.t("IDS_DL_FILENAME"), 380, SearchResult::name)),
                        Fx.leftToRight(Fx.column(this.gui.t("IDS_DL_SIZE"), 90, SearchResult::size, formats::size)),
                        Fx.leftToRight(Fx.column(this.gui.t("IDS_SEARCHAVAIL"), 90, SearchResult::sources)),
                        Fx.leftToRight(Fx.column(this.gui.t("IDS_COMPLETE"), 90, SearchResult::completeSources)),
                        Fx.leftToRight(Fx.column(
                                this.gui.t("nj.search.type"),
                                90,
                                (SearchResult r) -> r.type().orElse(""))),
                        known,
                        fileId));
        table.getColumns().addAll(media);
        TableLayout.remember(table, "search", this.gui.settings());
        MediaColumns mediaColumns = new MediaColumns(media);
        mediaColumns.follow(search.results().rows());
        this.gui.subscriptions().onListChange(search.results().rows(), c -> mediaColumns.follow(c.getList()));
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
        MenuItem html = Fx.menuItem(this.gui.t("IDS_DL_LINK2"), Material2AL.CODE, this::copyHtml);
        MenuItem related = Fx.menuItem(this.gui.t("IDS_SEARCHRELATED"), Material2MZ.SEARCH, this::searchRelated);
        MenuItem spam = Fx.menuItem(this.gui.t("IDS_MARKSPAM"), Material2AL.BLOCK, () -> markSpam(true));
        MenuItem notSpam = Fx.menuItem(this.gui.t("IDS_MARKNOTSPAM"), Material2AL.CHECK, () -> markSpam(false));
        ContextMenu menu = new ContextMenu(download, copy, html, related, new SeparatorMenuItem(), spam, notSpam);
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
        String title = ownTitle.isEmpty()
                ? ATTRIBUTES
                        .matcher(search.request().expression())
                        .replaceAll("")
                        .strip()
                : ownTitle;
        Tab tab = new Tab(title);
        Runnable counted = () -> {
            int all = search.results().rows().size();
            int visible = table.getItems().size();
            tab.setText(title + " (" + all + ")");
            count.setText(this.gui.f("nj.search.count", visible == all ? String.valueOf(all) : visible + "/" + all));
        };
        table.getItems().addListener((ListChangeListener<LiveList.Row<SearchResult>>) c -> counted.run());
        this.gui.subscriptions().onListChange(search.results().rows(), c -> counted.run());
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
        tab.setOnClosed(e -> closed(tab));
        this.results.put(tab, new Results(search, table, ownTitle));
        table.getSelectionModel().getSelectedItems().addListener((ListChangeListener<LiveList.Row<SearchResult>>)
                c -> followChoice());
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

    // What we did with a file tells more than a spam mark.
    private Known known(SearchResult result) {
        Hash16 hash = result.hash();
        FileHistory history = this.gui.model().historyProperty().get();
        if (this.gui.model().isDownloading(hash)) {
            return Known.DOWNLOADING;
        } else if (this.gui.model().isShared(hash)) {
            return Known.SHARED;
        } else if (history.downloaded(hash)) {
            return Known.DOWNLOADED;
        } else if (history.cancelled(hash)) {
            return Known.CANCELLED;
        }
        return this.gui.model().spamMarksProperty().get().isSpam(result) ? Known.SPAM : Known.NONE;
    }

    // Only files coming or going matter, not their progress.
    private void knownChanged(ListChangeListener.Change<?> change) {
        boolean membership = false;
        while (change.next()) {
            membership |= change.wasAdded() || change.wasRemoved();
        }
        if (membership) {
            refreshAll();
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

    private void followChoice() {
        Results current = current();
        this.noneChosen.set(
                current == null || current.table().getSelectionModel().isEmpty());
    }

    void more() {
        Results current = current();
        if (current != null) {
            this.gui.model().moreResults(current.search());
        }
    }

    void stop() {
        Results current = current();
        if (current != null) {
            this.gui.model().stopSearch(current.search());
        }
    }

    void downloadSelected() {
        Results current = current();
        if (current != null) {
            Fx.selected(current.table()).forEach(r -> this.gui.model().addDownload(r.link()));
        }
    }

    /** Related search: the server finds files people download along with the selected ones. */
    void searchRelated() {
        Results current = current();
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
        Results current = current();
        if (current != null && !Fx.selected(current.table()).isEmpty()) {
            this.gui.model().markSpam(Fx.selected(current.table()), isSpam);
        }
    }

    void copyLinks() {
        copy(Ed2kLink.FileLink::toUri);
    }

    void copyHtml() {
        copy(Ed2kLink.FileLink::toHtml);
    }

    private void copy(Function<Ed2kLink.FileLink, String> form) {
        Results current = current();
        if (current == null) {
            return;
        }
        List<String> links = Fx.selected(current.table()).stream()
                .map(r -> form.apply(r.link()))
                .toList();
        if (!links.isEmpty()) {
            ClipboardWatch.copy(String.join("\n", links));
        }
    }

    TextField queryField() {
        return this.query.getEditor();
    }

    Segments<Method> methodBox() {
        return this.method;
    }

    ComboBox<FileType> typeBox() {
        return this.type;
    }

    TextField minSizeField() {
        return this.minSize;
    }

    TextField maxSizeField() {
        return this.maxSize;
    }

    Input<OptionalInt> maxSizeInput() {
        return this.maxSizeInput;
    }

    TextField extensionField() {
        return this.extension;
    }

    Input<String> extensionInput() {
        return this.extensionInput;
    }

    TabPane tabs() {
        return this.tabs;
    }

    /** The results table of {@code tab}. */
    TableView<LiveList.Row<SearchResult>> table(Tab tab) {
        return this.results.get(tab).table();
    }

    private final class TypeCell extends ListCell<FileType> {
        @Override
        protected void updateItem(FileType item, boolean empty) {
            super.updateItem(item, empty);
            setText(empty || item == null ? null : SearchScreen.this.gui.t(item.text));
        }
    }

    /** Whether a result is a song or a film: its type says so, or it has media details. */
    static boolean isMedia(SearchResult result) {
        String type = result.type().orElse("");
        return type.equalsIgnoreCase("audio")
                || type.equalsIgnoreCase("video")
                || !result.media().equals(SearchResult.Media.NONE);
    }

    /**
     * The media columns of a list of results: shown while it holds songs or films, hidden otherwise, until the column
     * menu shows or hides one of them for this list.
     */
    private static final class MediaColumns {
        private final List<TableColumn<LiveList.Row<SearchResult>, ?>> columns;
        private boolean chosen;
        private boolean following;

        MediaColumns(List<TableColumn<LiveList.Row<SearchResult>, ?>> columns) {
            this.columns = columns;
            for (TableColumn<LiveList.Row<SearchResult>, ?> column : columns) {
                column.visibleProperty().addListener((o, was, now) -> this.chosen |= !this.following);
            }
        }

        void follow(List<? extends LiveList.Row<SearchResult>> rows) {
            if (this.chosen) {
                return;
            }
            boolean shown = rows.stream().anyMatch(r -> isMedia(r.get()));
            this.following = true;
            try {
                this.columns.forEach(c -> c.setVisible(shown));
            } finally {
                this.following = false;
            }
        }
    }

    /** What we did with the file, on a tint of its own, with an icon and the text, so colour is not the only cue. */
    private final class KnownCell extends TableCell<LiveList.Row<SearchResult>, Known> {
        @Override
        protected void updateItem(Known item, boolean empty) {
            super.updateItem(item, empty);
            getStyleClass()
                    .removeAll(
                            "known-downloading", "known-shared", "known-downloaded", "known-cancelled", "known-spam");
            if (empty || item == null || item == Known.NONE) {
                setText(null);
                setGraphic(null);
                return;
            }
            FontIcon icon = Fx.icon(
                    switch (item) {
                        case DOWNLOADING -> Material2AL.CLOUD_DOWNLOAD;
                        case SHARED -> Material2AL.FOLDER_SHARED;
                        case DOWNLOADED -> Material2AL.CHECK_CIRCLE_OUTLINE;
                        case CANCELLED -> Material2AL.BLOCK;
                        case SPAM, NONE -> Material2MZ.REPORT;
                    });
            icon.getStyleClass().add("known-icon");
            setGraphic(icon);
            setText(SearchScreen.this.gui.t(
                    switch (item) {
                        case DOWNLOADING -> "IDS_DOWNLOADING";
                        case SHARED -> "IDS_SHARED";
                        case DOWNLOADED -> "IDS_DOWNLOADED";
                        case CANCELLED -> "IDS_CANCELLED";
                        case SPAM, NONE -> "IDS_SPAM";
                    }));
            getStyleClass()
                    .add(
                            switch (item) {
                                case DOWNLOADING -> "known-downloading";
                                case SHARED -> "known-shared";
                                case DOWNLOADED -> "known-downloaded";
                                case CANCELLED -> "known-cancelled";
                                case SPAM, NONE -> "known-spam";
                            });
        }
    }
}
