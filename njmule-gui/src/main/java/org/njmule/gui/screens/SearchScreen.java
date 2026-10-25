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
import atlantafx.base.controls.Popover;
import atlantafx.base.controls.RingProgressIndicator;
import atlantafx.base.theme.Styles;
import atlantafx.base.theme.Tweaks;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.beans.value.ObservableValue;
import javafx.collections.ListChangeListener;
import javafx.css.PseudoClass;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Side;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.RadioMenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import org.njmule.core.api.Ed2kLink;
import org.njmule.core.api.FileHistory;
import org.njmule.core.api.Hash16;
import org.njmule.core.api.KadInfo;
import org.njmule.core.api.SavedSearch;
import org.njmule.core.api.SearchRequest;
import org.njmule.core.api.SearchResult;
import org.njmule.gui.model.EngineModel;
import org.njmule.gui.model.LiveList;
import org.njmule.gui.ui.ClipboardWatch;
import org.njmule.gui.ui.FilterChips;
import org.njmule.gui.ui.Fx;
import org.njmule.gui.ui.Gui;
import org.njmule.gui.ui.Icon;
import org.njmule.gui.ui.Icons;
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
    // The row of one name a file was found under.
    private static final PseudoClass NAME_ROW = PseudoClass.getPseudoClass("name-row");
    static final PseudoClass DOWNLOADING_ROW = PseudoClass.getPseudoClass("downloading");
    // What a result is to us tells its colour: ours already (downloading or shared), known from before (downloaded or
    // cancelled), spam; a new one goes from the text's colour towards the accent's, by thirteenths, as it has more
    // sources.
    static final PseudoClass OURS_ROW = PseudoClass.getPseudoClass("ours");
    static final PseudoClass KNOWN_ROW = PseudoClass.getPseudoClass("known");
    static final PseudoClass SPAM_ROW = PseudoClass.getPseudoClass("spam");
    private static final int SHADES = 13;

    private final Gui gui;
    private final StringProperty summary = new SimpleStringProperty("");
    private final Region plainText = Fx.swatch("result-plain");
    private final Region richText = Fx.swatch("result-rich");
    private Node view;
    private final ComboBox<String> query = new ComboBox<>();
    private Input<String> queryInput;
    private Segments<Method> method;
    private FilterChips<FileType> type;
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
    private final Button filtersButton = new Button();
    private Popover filters;
    private final BooleanProperty noneChosen = new SimpleBooleanProperty(true);
    // Whether the search shown still runs, which Stop acts on.
    private final BooleanProperty shownRunning = new SimpleBooleanProperty();
    private final VBox content = new VBox(8);
    private VBox parameters;
    private Node empty;
    private Node found;
    private final Label chosenName = new Label();
    private final Label chosenFacts = new Label();
    private final Map<Tab, Results> results = new HashMap<>();
    // One filter, right of the tabs, for the results of the tab shown, on the column chosen by its title.
    private final CustomTextField filter = new CustomTextField();
    private final StringProperty filterColumn = new SimpleStringProperty();

    // title: the tab's own, such as a kept related search's; empty = from the expression.
    private record Results(
            EngineModel.Search search, TableView<LiveList.Row<SearchResult>> table, ResultRows rows, String title) {}

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
    public Icons icon() {
        return Icons.SEARCH;
    }

    @Override
    public ObservableValue<String> summary() {
        return this.summary;
    }

    // The searches open and those still running; nothing while there are none.
    private void showSummary() {
        long running = this.results.values().stream()
                .filter(r -> Boolean.TRUE.equals(r.search().runningProperty().get()))
                .count();
        this.summary.set(this.results.isEmpty() ? "" : this.gui.f("nj.summary.search", this.results.size(), running));
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
        Fx.onEnter(this.query, this::start);
        this.queryInput = inputs.searchExpression(this.query.getEditor(), this.gui.t("IDS_SEARCH_TEXT"))
                .quietWhenEmpty();
        Fx.hintOnBox(this.query);
        Button start = Fx.primary(Fx.button(this.gui.t("nj.search.start"), Icons.SEARCH, this::start));
        start.disableProperty().bind(this.queryInput.validProperty().not());
        this.method = new Segments<>(List.of(Method.values()), this::methodName);
        // More and Stop act on the search shown, Download on the results chosen in it.
        Button more = Fx.button(this.gui.t("nj.search.more"), Icons.ADD, this::more);
        Button stop = Fx.button(this.gui.t("IDS_DL_STOP"), Icons.STOP, this::stop);
        more.disableProperty().bind(Bindings.isEmpty(this.tabs.getTabs()));
        stop.disableProperty().bind(this.shownRunning.not());
        stop.getStyleClass().add("stop-search");
        for (Button button : List.of(more, stop)) {
            button.getStyleClass().addAll(Styles.FLAT, Styles.SMALL);
            Fx.whole(button);
        }
        this.tabs.getSelectionModel().selectedItemProperty().addListener((o, was, now) -> {
            followChoice();
            followShown();
        });
        // What to look for, where, with which filters, in one row.
        this.parameters = parameters(inputs);
        this.filtersButton.setGraphic(Fx.icon(Icons.FILTER));
        this.filtersButton.setId("search-parameters");
        this.filtersButton.setOnAction(e -> showParameters(true));
        for (TextField f : List.of(this.minSize, this.maxSize, this.availability, this.complete, this.extension)) {
            f.textProperty().addListener((o, was, now) -> countParameters());
        }
        this.type.valueProperty().addListener((o, was, now) -> countParameters());
        countParameters();
        HBox.setHgrow(this.query, Priority.ALWAYS);
        var tools = Fx.toolRow(this.query, this.method.node(), this.filtersButton, start);
        this.tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.ALL_TABS);
        // A file downloaded or shared since shows so in every list; a spam rating comes with its result.
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
        VBox results = new VBox(6, tabsWithFilter(more, stop), selectionBar());
        VBox.setVgrow(results.getChildren().getFirst(), Priority.ALWAYS);
        this.found = results;
        this.empty = emptyState();
        this.tabs.getTabs().addListener((ListChangeListener<Tab>) c -> showFoundOrEmpty());
        showFoundOrEmpty();
        VBox screen = Fx.screen(tools, this.content);
        screen.getChildren().addAll(this.plainText, this.richText);
        // Another theme, other shades.
        this.richText.backgroundProperty().addListener(o -> refreshAll());
        return screen;
    }

    // The tabs of results while there are some, else how to search.
    private void showFoundOrEmpty() {
        Node shown = this.tabs.getTabs().isEmpty() ? this.empty : this.found;
        if (!this.content.getChildren().equals(List.of(shown))) {
            this.content.getChildren().setAll(shown);
            VBox.setVgrow(shown, Priority.ALWAYS);
        }
    }

    // No search yet: what the screen does, and three ways to word a search.
    private Node emptyState() {
        Icon icon = Fx.icon(Icons.SEARCH);
        icon.getStyleClass().add("empty-icon");
        Label title = new Label(this.gui.t("nj.search.emptyTitle"));
        title.getStyleClass().addAll("empty-title", "display");
        Label text = new Label(this.gui.t("nj.search.emptyText"));
        Fx.wrap(text);
        text.getStyleClass().add("empty-text");
        FlowPane tips = new FlowPane(
                12,
                12,
                tip(Icons.LABEL, "nj.search.tipExact", "\"night of the living dead\""),
                tip(Icons.REMOVE_CIRCLE, "nj.search.tipWithout", "debian -netinst"),
                tip(Icons.LINK, "nj.search.tipLink", "ed2k://|file|…|/"));
        tips.setAlignment(Pos.CENTER);
        VBox box = new VBox(12, icon, title, text, tips);
        box.setAlignment(Pos.CENTER);
        box.setMaxWidth(720);
        box.getStyleClass().addAll("empty-state", "search-empty");
        StackPane centred = new StackPane(box);
        centred.setId("search-empty");
        return centred;
    }

    // A way to word a search: what to do, and an example left to right in every language.
    private Node tip(Icons glyph, String key, String example) {
        Label words = new Label(this.gui.t(key));
        Fx.wrap(words);
        words.getStyleClass().add("tip-words");
        Label shown = Fx.leftToRight(new Label(example));
        Fx.wrap(shown);
        shown.getStyleClass().add("tip-example");
        VBox text = new VBox(2, words, shown);
        text.setMinWidth(0);
        HBox tip = new HBox(10, Fx.icon(glyph), text);
        tip.getStyleClass().add("search-tip");
        tip.setPrefWidth(220);
        return tip;
    }

    // What is chosen in the tab shown, and what to do with it: download, copy, search the related files.
    private Node selectionBar() {
        this.chosenName.getStyleClass().add("chosen-name");
        this.chosenName.setMinWidth(0);
        Fx.ownDirection(this.chosenName);
        this.chosenFacts.getStyleClass().add("chosen-facts");
        Fx.whole(this.chosenFacts);
        Button download = Fx.button(this.gui.t("IDS_DOWNLOAD"), Icons.DOWNLOAD, this::downloadSelected);
        download.disableProperty().bind(this.noneChosen);
        Button copy = Fx.iconButton(this.gui.t("nj.copyLink"), Icons.COPY, this::copyLinks);
        Button related = Fx.iconButton(this.gui.t("IDS_SEARCHRELATED"), Icons.SEARCH, this::searchRelated);
        MenuItem html = Fx.menuItem(this.gui.t("IDS_DL_LINK2"), Icons.CODE, this::copyHtml);
        MenuItem spam = Fx.menuItem(this.gui.t("IDS_MARKSPAM"), Icons.BLOCK, () -> markSpam(true));
        MenuItem notSpam = Fx.menuItem(this.gui.t("IDS_MARKNOTSPAM"), Icons.CHECK, () -> markSpam(false));
        MenuButton more = new MenuButton(null, Fx.icon(Icons.MORE), html, spam, notSpam);
        more.getStyleClass().addAll(Styles.BUTTON_ICON, Tweaks.NO_ARROW);
        more.setTooltip(new Tooltip(this.gui.t("IDS_MORE")));
        more.setAccessibleText(this.gui.t("IDS_MORE"));
        Fx.whole(more);
        HBox.setHgrow(this.chosenName, Priority.SOMETIMES);
        HBox bar = new HBox(8, this.chosenName, this.chosenFacts, Fx.spacer(), download, copy, related, more);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.getStyleClass().add("selection-bar");
        bar.setId("selection-bar");
        bar.visibleProperty().bind(this.noneChosen.not());
        bar.managedProperty().bind(bar.visibleProperty());
        return bar;
    }

    // The filters of the next search: the type and the attributes the server or Kad filter on, in a popup.
    private VBox parameters(Inputs inputs) {
        this.type = new FilterChips<>(List.of(FileType.values()), t -> this.gui.t(t.text));
        this.type.node().setId("search-types");
        for (TextField f : List.of(this.minSize, this.maxSize, this.availability, this.complete)) {
            f.setPrefColumnCount(6);
        }
        this.extension.setPrefColumnCount(8);
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
        Label title = new Label(this.gui.t("nj.search.filters"));
        title.getStyleClass().add("popover-title");
        Button reset = Fx.button(this.gui.t("IDS_PW_RESET"), Icons.REFRESH, this::resetParameters);
        reset.getStyleClass().addAll(Styles.FLAT, Styles.SMALL);
        HBox head = new HBox(8, title, Fx.spacer(), reset);
        head.setAlignment(Pos.CENTER_LEFT);
        GridPane fields = new GridPane();
        fields.setHgap(12);
        fields.setVgap(10);
        fields.add(labelled("IDS_SEARCHMINSIZE", this.minSize), 0, 0);
        fields.add(labelled("IDS_SEARCHMAXSIZE", this.maxSize), 1, 0);
        fields.add(labelled("IDS_SEARCHAVAIL", this.availability), 0, 1);
        fields.add(labelled("IDS_COMPLSOURCES", this.complete), 1, 1);
        fields.add(labelled("IDS_SEARCHEXTENTION", this.extension), 0, 2, 2, 1);
        Label note = new Label(this.gui.t("nj.search.filtersNote"));
        Fx.wrap(note);
        note.getStyleClass().add("text-muted");
        Button done = Fx.button(this.gui.t("IDS_FD_CLOSE"), Icons.CHECK, () -> showParameters(false));
        HBox foot = new HBox(12, note, done);
        HBox.setHgrow(note, Priority.ALWAYS);
        foot.setAlignment(Pos.CENTER_LEFT);
        VBox box = new VBox(12, head, labelled("IDS_TYPE", this.type.node()), fields, foot);
        box.getStyleClass().add("search-parameters");
        box.setPrefWidth(440);
        return box;
    }

    // A label above its field, at its whole width.
    private VBox labelled(String key, Node field) {
        Label label = new Label(this.gui.t(key));
        label.setMinWidth(Region.USE_PREF_SIZE);
        label.setLabelFor(field);
        label.getStyleClass().add("field-label");
        return new VBox(4, label, field);
    }

    private int lowestMaxSize() {
        return this.minSizeInput != null && this.minSizeInput.isValid()
                ? Math.max(1, this.minSizeInput.value().orElse(1))
                : 1;
    }

    /** Shows or hides the filters, under their button. */
    void showParameters(boolean on) {
        if (this.filters == null) {
            this.filters = new Popover(this.parameters);
            this.filters.setHeaderAlwaysVisible(false);
            this.filters.setDetachable(false);
            this.filters.setArrowLocation(Popover.ArrowLocation.TOP_RIGHT);
        }
        if (on
                && this.filtersButton.getScene() != null
                && this.filtersButton.getScene().getWindow() != null) {
            this.filters.show(this.filtersButton);
        } else if (!on) {
            this.filters.hide();
        }
    }

    /** Whether the filters show, for tests. */
    boolean parametersShown() {
        return this.filters != null && this.filters.isShowing();
    }

    /** The filters' pane, for tests. */
    VBox parametersPane() {
        return this.parameters;
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
        String name = this.gui.t("nj.search.filters");
        this.filtersButton.setText(used == 0 ? name : name + " (" + used + ")");
    }

    void resetParameters() {
        this.type.setValue(FileType.ANY);
        for (TextField f : List.of(this.minSize, this.maxSize, this.availability, this.complete, this.extension)) {
            f.clear();
        }
        countParameters();
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

    // Automatic: the network we are connected to, Kad when both unless the server is a static or a big one.
    private Optional<SearchRequest.Scope> scope() {
        if (this.method.getValue() != Method.AUTOMATIC) {
            return Optional.of(
                    switch (this.method.getValue()) {
                        case SERVER, AUTOMATIC -> SearchRequest.Scope.SERVER;
                        case GLOBAL -> SearchRequest.Scope.GLOBAL;
                        case KAD -> SearchRequest.Scope.KAD;
                    });
        }
        var status = this.gui.model().statusProperty().get();
        return SearchRequest.automatic(
                this.gui.model().connectionProperty().get() == EngineModel.Connection.CONNECTED,
                status.kad().map(KadInfo::connected).orElse(false),
                status.server(),
                this.gui.model().servers().size());
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
        ResultRows rows = new ResultRows(search.results());
        // The names of a file show with it.
        TableView<LiveList.Row<SearchResult>> table = Fx.table(
                rows.rows(), this.gui.t("nj.search.waiting"), shown.map(test -> (Predicate<LiveList.Row<SearchResult>>)
                        row -> test.test(rows.fileOf(row).get())));
        TableColumn<LiveList.Row<SearchResult>, Known> known =
                Fx.column(this.gui.t("nj.search.known"), 150, r -> known(r));
        known.setCellFactory(c -> new KnownCell());
        TableColumn<LiveList.Row<SearchResult>, String> fileId = Fx.leftToRight(Fx.column(
                this.gui.t("IDS_FILEID"), 220, (SearchResult r) -> r.hash().toHex()));
        // Offered in the column menu.
        fileId.setVisible(false);
        String kbits = this.gui.t("IDS_KBITSSEC");
        String yes = this.gui.t("IDS_YES");
        Function<Integer, String> lengthText = s -> s == 0 ? "" : formats.playingTime(s);
        Function<Integer, String> bitrateText = b -> b == 0 ? "" : formats.count(b) + " " + kbits;
        Comparator<String> text = SearchOrder.text(this.gui.i18n().locale());
        List<TableColumn<LiveList.Row<SearchResult>, ?>> media = List.of(
                textLast(
                        Fx.ownDirection(Fx.column(
                                this.gui.t("IDS_ARTIST"),
                                100,
                                (SearchResult r) -> r.media().artist())),
                        text),
                textLast(
                        Fx.ownDirection(Fx.column(
                                this.gui.t("IDS_ALBUM"),
                                100,
                                (SearchResult r) -> r.media().album())),
                        text),
                textLast(
                        Fx.ownDirection(Fx.column(
                                this.gui.t("IDS_TITLE"),
                                100,
                                (SearchResult r) -> r.media().title())),
                        text),
                numberLast(Fx.leftToRight(Fx.column(
                        this.gui.t("IDS_LENGTH"),
                        60,
                        (SearchResult r) -> r.media().length(),
                        lengthText))),
                numberLast(Fx.leftToRight(Fx.column(
                        this.gui.t("IDS_BITRATE"),
                        80,
                        (SearchResult r) -> r.media().bitrate(),
                        bitrateText))),
                textLast(
                        Fx.leftToRight(Fx.column(
                                this.gui.t("IDS_CODEC"),
                                60,
                                (SearchResult r) -> r.media().codec())),
                        text));
        TableColumn<LiveList.Row<SearchResult>, String> name =
                Fx.column(this.gui.t("IDS_DL_FILENAME"), 380, SearchResult::name);
        name.setCellFactory(c -> new NameCell(rows));
        Fx.ownDirection(name);
        name.setComparator(text);
        // Columns are remembered by their place: new ones go last.
        table.getColumns()
                .addAll(List.of(
                        name,
                        Fx.leftToRight(Fx.column(this.gui.t("IDS_DL_SIZE"), 90, SearchResult::size, formats::size)),
                        Fx.leftToRight(Fx.column(this.gui.t("IDS_SEARCHAVAIL"), 90, SearchResult::sources)),
                        Fx.leftToRight(Fx.column(
                                this.gui.t("IDS_COMPLETE"),
                                90,
                                (SearchResult r) ->
                                        new SearchOrder.Complete(r.completeSources(), r.sources(), r.size()),
                                c -> c.shown(yes))),
                        Fx.leftToRight(Fx.column(
                                this.gui.t("nj.search.type"),
                                90,
                                (SearchResult r) -> SearchOrder.Kind.of(r.type().orElse(""), r.name()),
                                SearchOrder.Kind::type)),
                        known,
                        fileId));
        table.getColumns().addAll(media);
        TableLayout.remember(table, "search", this.gui.settings());
        SearchOrder.apply(table, rows, name);
        // + and - open and close the files chosen that were found under several names.
        table.addEventHandler(KeyEvent.KEY_PRESSED, e -> {
            boolean open = e.getCode() == KeyCode.ADD || e.getCode() == KeyCode.PLUS;
            if (open || e.getCode() == KeyCode.SUBTRACT || e.getCode() == KeyCode.MINUS) {
                for (LiveList.Row<SearchResult> row :
                        List.copyOf(table.getSelectionModel().getSelectedItems())) {
                    if (open) {
                        rows.open(row);
                    } else {
                        rows.close(row);
                    }
                }
                e.consume();
            }
        });
        MediaColumns mediaColumns = new MediaColumns(media);
        mediaColumns.follow(search.results().rows());
        this.gui.subscriptions().onListChange(search.results().rows(), c -> mediaColumns.follow(c.getList()));
        table.setRowFactory(t -> {
            TableRow<LiveList.Row<SearchResult>> row = new TableRow<>() {
                // A file being downloaded stands out in bold, its whole row, beside its state.
                @Override
                protected void updateItem(LiveList.Row<SearchResult> item, boolean empty) {
                    super.updateItem(item, empty);
                    Known k = empty || item == null ? Known.NONE : known(item.get());
                    pseudoClassStateChanged(DOWNLOADING_ROW, k == Known.DOWNLOADING);
                    pseudoClassStateChanged(OURS_ROW, k == Known.DOWNLOADING || k == Known.SHARED);
                    pseudoClassStateChanged(KNOWN_ROW, k == Known.DOWNLOADED || k == Known.CANCELLED);
                    pseudoClassStateChanged(SPAM_ROW, k == Known.SPAM);
                    setStyle(
                            empty || item == null || k != Known.NONE
                                    ? ""
                                    : "-nj-result-fg: " + shade(item.get().sources()) + ";");
                }
            };
            row.setOnMouseClicked(e -> {
                if (e.getClickCount() == 2 && !row.isEmpty()) {
                    this.gui.model().addDownload(row.getItem().get().link());
                }
            });
            return row;
        });
        MenuItem download = Fx.menuItem(this.gui.t("IDS_DOWNLOAD"), Icons.DOWNLOAD, this::downloadSelected);
        MenuItem copy = Fx.menuItem(this.gui.t("nj.copyLink"), Icons.COPY, this::copyLinks);
        MenuItem html = Fx.menuItem(this.gui.t("IDS_DL_LINK2"), Icons.CODE, this::copyHtml);
        MenuItem related = Fx.menuItem(this.gui.t("IDS_SEARCHRELATED"), Icons.SEARCH, this::searchRelated);
        MenuItem spam = Fx.menuItem(this.gui.t("IDS_MARKSPAM"), Icons.BLOCK, () -> markSpam(true));
        MenuItem notSpam = Fx.menuItem(this.gui.t("IDS_MARKNOTSPAM"), Icons.CHECK, () -> markSpam(false));
        ContextMenu menu = new ContextMenu(download, copy, html, related, new SeparatorMenuItem(), spam, notSpam);
        menu.setOnShowing(e -> {
            List<SearchResult> selected = Fx.selected(table);
            download.setDisable(
                    selected.stream().allMatch(r -> known(r) == Known.DOWNLOADING || known(r) == Known.SHARED));
            copy.setDisable(selected.isEmpty());
            html.setDisable(selected.isEmpty());
            related.setDisable(selected.isEmpty()
                    || !this.gui.model().statusProperty().get().relatedSearch());
            spam.setDisable(selected.stream().allMatch(SearchResult::isSpam));
            notSpam.setDisable(selected.stream().noneMatch(SearchResult::isSpam));
        });
        table.setContextMenu(menu);

        // What the filter beside the tabs looks for: the text each column shows.
        Map<String, Function<SearchResult, String>> texts = new HashMap<>();
        texts.put(this.gui.t("IDS_DL_FILENAME"), SearchResult::name);
        texts.put(this.gui.t("IDS_DL_SIZE"), r -> formats.size(r.size()));
        texts.put(this.gui.t("IDS_SEARCHAVAIL"), r -> Integer.toString(r.sources()));
        texts.put(
                this.gui.t("IDS_COMPLETE"),
                r -> new SearchOrder.Complete(r.completeSources(), r.sources(), r.size()).shown(yes));
        texts.put(this.gui.t("nj.search.type"), r -> r.type().orElse(""));
        texts.put(this.gui.t("nj.search.known"), r -> knownText(known(r)));
        texts.put(this.gui.t("IDS_FILEID"), r -> r.hash().toHex());
        texts.put(this.gui.t("IDS_ARTIST"), r -> r.media().artist());
        texts.put(this.gui.t("IDS_ALBUM"), r -> r.media().album());
        texts.put(this.gui.t("IDS_TITLE"), r -> r.media().title());
        texts.put(this.gui.t("IDS_LENGTH"), r -> lengthText.apply(r.media().length()));
        texts.put(this.gui.t("IDS_BITRATE"), r -> bitrateText.apply(r.media().bitrate()));
        texts.put(this.gui.t("IDS_CODEC"), r -> r.media().codec());
        shown.bind(Bindings.createObjectBinding(
                () -> matching(this.filter.getText(), texts.getOrDefault(this.filterColumn.get(), SearchResult::name)),
                this.filter.textProperty(),
                this.filterColumn));
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
            int visible =
                    (int) table.getItems().stream().filter(r -> !rows.isName(r)).count();
            tab.setText(title + " (" + all + ")");
            count.setText(this.gui.f("nj.search.count", visible == all ? String.valueOf(all) : visible + "/" + all));
        };
        table.getItems().addListener((ListChangeListener<LiveList.Row<SearchResult>>) c -> counted.run());
        this.gui.subscriptions().onListChange(search.results().rows(), c -> counted.run());
        counted.run();
        HBox head = new HBox(8, count);
        head.setAlignment(Pos.CENTER_LEFT);
        VBox pane = new VBox(6, head, table);
        VBox.setVgrow(table, Priority.ALWAYS);
        tab.setContent(pane);
        tab.setTooltip(new Tooltip(search.request().expression()));

        // A ring rather than the theme's fading dots, which hardly show at the size of a tab.
        RingProgressIndicator running = new RingProgressIndicator(ProgressIndicator.INDETERMINATE_PROGRESS, false);
        running.getStyleClass().add("tab-spinner");
        running.visibleProperty().bind(search.runningProperty());
        running.managedProperty().bind(search.runningProperty());
        tab.setGraphic(running);
        tab.setOnClosed(e -> closed(tab));
        this.results.put(tab, new Results(search, table, rows, ownTitle));
        search.runningProperty().addListener((o, was, now) -> showSummary());
        showSummary();
        table.getSelectionModel().getSelectedItems().addListener((ListChangeListener<LiveList.Row<SearchResult>>)
                c -> followChoice());
        this.tabs.getTabs().add(tab);
        this.tabs.getSelectionModel().select(tab);
    }

    // A text a result lacks goes last, whichever way the column sorts.
    private static TableColumn<LiveList.Row<SearchResult>, String> textLast(
            TableColumn<LiveList.Row<SearchResult>, String> column, Comparator<String> text) {
        column.setComparator(SearchOrder.missingLast(column, String::isEmpty, text));
        return column;
    }

    // A number a result lacks, 0, goes last, whichever way the column sorts.
    private static TableColumn<LiveList.Row<SearchResult>, Integer> numberLast(
            TableColumn<LiveList.Row<SearchResult>, Integer> column) {
        column.setComparator(SearchOrder.missingLast(column, n -> n == 0, Comparator.naturalOrder()));
        return column;
    }

    /** Results whose name holds every word of {@code text}, in any case, and none of the words after a minus. */
    static Predicate<SearchResult> matching(String text) {
        return matching(text, SearchResult::name);
    }

    /**
     * Results whose {@code shown} text holds every word of {@code text}, in any case, and none of the words after a
     * minus.
     */
    static Predicate<SearchResult> matching(String text, Function<SearchResult, String> shown) {
        List<String> words = WHITESPACE
                .splitAsStream(text.strip().toLowerCase(Locale.ROOT))
                .filter(w -> !w.isEmpty() && !"-".equals(w))
                .toList();
        if (words.isEmpty()) {
            return r -> true;
        }
        return r -> {
            String target = shown.apply(r).toLowerCase(Locale.ROOT);
            for (String word : words) {
                boolean without = word.startsWith("-");
                if (target.contains(without ? word.substring(1) : word) == without) {
                    return false;
                }
            }
            return true;
        };
    }

    // The filter sits right of the tabs, in room their row keeps free for it.
    private Node tabsWithFilter(Button more, Button stop) {
        this.filterColumn.set(this.gui.t("IDS_DL_FILENAME"));
        this.filter.promptTextProperty().bind(this.filterColumn);
        this.filter.setTooltip(new Tooltip(this.gui.t("nj.search.filterHint")));
        this.filter.getStyleClass().add("results-filter");
        Button columns = new Button(null, Fx.icon(Icons.SEARCH));
        columns.getStyleClass().addAll(Styles.FLAT, Styles.BUTTON_ICON);
        columns.setTooltip(new Tooltip(this.gui.t("nj.search.filterColumn")));
        columns.disableProperty().bind(Bindings.isEmpty(this.tabs.getTabs()));
        columns.setOnAction(e -> filterColumnsMenu().show(columns, Side.BOTTOM, 0, 0));
        Button clear = new Button(null, Fx.icon(Icons.CLOSE));
        clear.getStyleClass().addAll(Styles.FLAT, Styles.BUTTON_ICON);
        clear.visibleProperty().bind(this.filter.textProperty().isNotEmpty());
        clear.setOnAction(e -> this.filter.clear());
        this.filter.setLeft(columns);
        this.filter.setRight(clear);
        this.filter.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ESCAPE) {
                this.filter.clear();
            }
        });
        this.tabs.getStyleClass().add("search-tabs");
        HBox overTabs = new HBox(6, more, stop, this.filter);
        overTabs.setAlignment(Pos.CENTER_RIGHT);
        overTabs.setPickOnBounds(false);
        overTabs.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        StackPane both = new StackPane(this.tabs, overTabs);
        StackPane.setAlignment(overTabs, Pos.TOP_RIGHT);
        StackPane.setMargin(overTabs, new Insets(2, 0, 0, 0));
        VBox.setVgrow(both, Priority.ALWAYS);
        return both;
    }

    // The columns shown in the tab shown, in their order, the one filtered on checked.
    ContextMenu filterColumnsMenu() {
        ContextMenu menu = new ContextMenu();
        Results current = current();
        if (current == null) {
            return menu;
        }
        ToggleGroup group = new ToggleGroup();
        for (TableColumn<LiveList.Row<SearchResult>, ?> column : current.table().getVisibleLeafColumns()) {
            RadioMenuItem item = new RadioMenuItem(column.getText());
            item.setToggleGroup(group);
            item.setSelected(column.getText().equals(this.filterColumn.get()));
            item.setOnAction(e -> this.filterColumn.set(column.getText()));
            menu.getItems().add(item);
        }
        return menu;
    }

    CustomTextField filterField() {
        return this.filter;
    }

    private String knownText(Known known) {
        return switch (known) {
            case DOWNLOADING -> this.gui.t("IDS_DOWNLOADING");
            case SHARED -> this.gui.t("IDS_SHARED");
            case DOWNLOADED -> this.gui.t("IDS_DOWNLOADED");
            case CANCELLED -> this.gui.t("IDS_CANCELLED");
            case SPAM -> this.gui.t("IDS_SPAM");
            case NONE -> "";
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
        return result.isSpam() ? Known.SPAM : Known.NONE;
    }

    // The colour of a new result: one source or none keeps the text's, each more a thirteenth closer to the accent's.
    private String shade(int sources) {
        int step = Math.clamp(sources - 1L, 0, SHADES - 1);
        Color plain = Fx.swatchColor(this.plainText, Color.BLACK);
        Color c = plain.interpolate(Fx.swatchColor(this.richText, Color.BLUE), (double) step / SHADES);
        return "rgb(" + Math.round(c.getRed() * 255) + "," + Math.round(c.getGreen() * 255) + ","
                + Math.round(c.getBlue() * 255) + ")";
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
        showSummary();
        if (closed != null) {
            this.gui.model().stopSearch(closed.search());
        }
    }

    private Results current() {
        return this.results.get(this.tabs.getSelectionModel().getSelectedItem());
    }

    private void followShown() {
        Results current = current();
        this.shownRunning.unbind();
        if (current == null) {
            this.shownRunning.set(false);
        } else {
            this.shownRunning.bind(current.search().runningProperty());
        }
    }

    private void followChoice() {
        Results current = current();
        this.noneChosen.set(
                current == null || current.table().getSelectionModel().isEmpty());
        if (current == null) {
            return;
        }
        List<SearchResult> chosen = Fx.selected(current.table());
        if (chosen.size() == 1) {
            SearchResult r = chosen.getFirst();
            this.chosenName.setText(r.name());
            this.chosenFacts.setText("· " + this.gui.formats().size(r.size()) + " · " + this.gui.t("IDS_DL_SOURCES")
                    + ": " + r.sources());
        } else {
            this.chosenName.setText(this.gui.f("nj.search.chosen", chosen.size()));
            this.chosenFacts.setText("");
        }
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
            // A file chosen with its names downloads once, under the first name chosen.
            Map<Hash16, SearchResult> files = new LinkedHashMap<>();
            Fx.selected(current.table()).forEach(r -> files.putIfAbsent(r.hash(), r));
            files.values().forEach(r -> this.gui.model().addDownload(r.link()));
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
        selected.stream()
                .map(SearchResult::hash)
                .distinct()
                .forEach(h -> expression.append("::").append(h.toHex()));
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

    ComboBox<String> queryBox() {
        return this.query;
    }

    Segments<Method> methodBox() {
        return this.method;
    }

    FilterChips<FileType> typeChips() {
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

    /** The rows of the results table of {@code tab}. */
    ResultRows rows(Tab tab) {
        return this.results.get(tab).rows();
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

    /**
     * The name of a file; one found under several names opens to show them, and closes, on its arrow. Its names show
     * under it, one step further in.
     */
    private static final class NameCell extends TableCell<LiveList.Row<SearchResult>, String> {
        private final ResultRows rows;
        private final Label sign = new Label();
        private final Icon closed = Fx.icon(Icons.CHEVRON_RIGHT);
        private final Icon open = Fx.icon(Icons.CHEVRON_DOWN);

        NameCell(ResultRows rows) {
            this.rows = rows;
            this.sign.getStyleClass().add("names-sign");
            this.sign.setOnMouseClicked(e -> {
                LiveList.Row<SearchResult> row = row();
                if (row != null && rows.hasNames(row)) {
                    rows.toggle(row);
                    e.consume();
                }
            });
            rows.toggled().addListener(o -> show());
        }

        @Override
        protected void updateItem(String item, boolean empty) {
            super.updateItem(item, empty);
            show();
        }

        private void show() {
            LiveList.Row<SearchResult> row = row();
            if (row == null) {
                setText(null);
                setGraphic(null);
                return;
            }
            boolean opens = this.rows.hasNames(row);
            this.sign.pseudoClassStateChanged(NAME_ROW, this.rows.isName(row));
            this.sign.setGraphic(opens ? (this.rows.isOpen(row) ? this.open : this.closed) : null);
            this.sign.setCursor(opens ? Cursor.HAND : Cursor.DEFAULT);
            setText(getItem());
            setGraphic(this.sign);
        }

        // The row of the cell's place, which its table row may not show yet while it updates.
        private LiveList.Row<SearchResult> row() {
            int index = getIndex();
            TableView<LiveList.Row<SearchResult>> table = getTableView();
            return isEmpty()
                            || table == null
                            || index < 0
                            || index >= table.getItems().size()
                    ? null
                    : table.getItems().get(index);
        }
    }

    /**
     * What we did with the file on a chip of its tone, with an icon and the words, so colour is not the only cue: the
     * download running in the accent's blue. The cell keeps a style class of the kind.
     */
    private final class KnownCell extends TableCell<LiveList.Row<SearchResult>, Known> {
        private final Label chip = Fx.chip("", Icons.DOWNLOAD, "accent");

        @Override
        protected void updateItem(Known item, boolean empty) {
            super.updateItem(item, empty);
            setText(null);
            getStyleClass()
                    .removeAll(
                            "known-downloading", "known-shared", "known-downloaded", "known-cancelled", "known-spam");
            if (empty || item == null || item == Known.NONE) {
                setGraphic(null);
                setAccessibleText(null);
                return;
            }
            setAccessibleText(knownText(item));
            Fx.chip(
                    this.chip,
                    knownText(item),
                    switch (item) {
                        case DOWNLOADING -> Icons.DOWNLOAD;
                        case SHARED -> Icons.SHARED;
                        case DOWNLOADED -> Icons.CHECK_CIRCLE;
                        case CANCELLED -> Icons.BLOCK;
                        case SPAM, NONE -> Icons.SPAM;
                    },
                    switch (item) {
                        case DOWNLOADING -> "accent";
                        case SHARED -> "success";
                        case SPAM, NONE -> "warning";
                        case DOWNLOADED, CANCELLED -> "neutral";
                    });
            setGraphic(this.chip);
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
