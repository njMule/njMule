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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javafx.scene.Node;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.ListCell;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import org.kordamp.ikonli.Ikon;
import org.kordamp.ikonli.material2.Material2AL;
import org.kordamp.ikonli.material2.Material2MZ;
import org.njmule.core.api.SearchRequest;
import org.njmule.core.api.SearchResult;
import org.njmule.gui.model.EngineModel;
import org.njmule.gui.model.LiveList;
import org.njmule.gui.ui.Fx;
import org.njmule.gui.ui.Gui;
import org.njmule.gui.ui.Screen;

/** Searches on the server, all known servers or Kad, one tab each. */
public final class SearchScreen implements Screen {

    private final Gui gui;
    private Node view;
    private final TextField query = new TextField();
    private final ComboBox<SearchRequest.Scope> scope = new ComboBox<>();
    private final TabPane tabs = new TabPane();
    private final Map<Tab, Results> results = new HashMap<>();

    /** The table of one search. */
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
        this.query.setPromptText(this.gui.t("IDS_SEARCH_TEXT"));
        this.query.setPrefColumnCount(30);
        this.query.setOnAction(e -> this.start());
        this.scope.getItems().addAll(SearchRequest.Scope.values());
        this.scope.setValue(SearchRequest.Scope.SERVER);
        this.scope.setCellFactory(l -> new ScopeCell());
        this.scope.setButtonCell(new ScopeCell());
        var tools = Fx.toolRow(
                this.query,
                this.scope,
                Fx.button(this.gui.t("nj.search.start"), Material2MZ.SEARCH, this::start),
                Fx.button(this.gui.t("nj.search.more"), Material2AL.ADD, this::more),
                Fx.button(this.gui.t("IDS_DL_STOP"), Material2MZ.STOP, this::stop),
                Fx.spacer(),
                Fx.button(this.gui.t("IDS_DOWNLOAD"), Material2AL.CLOUD_DOWNLOAD, this::downloadSelected));
        this.tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.ALL_TABS);
        return Fx.screen(this.title(), tools, this.tabs);
    }

    private String scopeName(SearchRequest.Scope s) {
        return switch (s) {
            case SERVER -> this.gui.t("IDS_SERVER");
            case GLOBAL -> this.gui.t("IDS_GLOBALSEARCH");
            case KAD -> this.gui.t("IDS_KADEMLIA");
        };
    }

    void start() {
        String expression = this.query.getText().strip();
        if (expression.isEmpty()) {
            return;
        }
        SearchRequest request = new SearchRequest(expression, this.scope.getValue());
        this.gui.model().search(request, this::opened);
    }

    private void opened(EngineModel.Search search) {
        var formats = this.gui.formats();
        TableView<LiveList.Row<SearchResult>> table = Fx.table(search.results(), this.gui.t("nj.search.waiting"));
        table.getColumns()
                .addAll(List.of(
                        Fx.column(this.gui.t("IDS_DL_FILENAME"), 380, SearchResult::name),
                        Fx.column(this.gui.t("IDS_DL_SIZE"), 90, SearchResult::size, formats::size),
                        Fx.column(this.gui.t("IDS_SEARCHAVAIL"), 90, SearchResult::sources),
                        Fx.column(this.gui.t("IDS_COMPLETE"), 90, SearchResult::completeSources),
                        Fx.column(
                                this.gui.t("nj.search.type"), 90, r -> r.type().orElse(""))));
        table.setRowFactory(t -> {
            TableRow<LiveList.Row<SearchResult>> row = new TableRow<>();
            row.setOnMouseClicked(e -> {
                if (e.getClickCount() == 2 && !row.isEmpty()) {
                    this.gui.model().addDownload(row.getItem().get().link());
                }
            });
            return row;
        });
        MenuItem download = new MenuItem(this.gui.t("IDS_DOWNLOAD"));
        download.setOnAction(e -> this.downloadSelected());
        table.setContextMenu(new ContextMenu(download));
        Tab tab = new Tab(search.request().expression(), table);
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

    TextField queryField() {
        return this.query;
    }

    ComboBox<SearchRequest.Scope> scopeBox() {
        return this.scope;
    }

    TabPane tabs() {
        return this.tabs;
    }

    private final class ScopeCell extends ListCell<SearchRequest.Scope> {
        @Override
        protected void updateItem(SearchRequest.Scope item, boolean empty) {
            super.updateItem(item, empty);
            this.setText(empty || item == null ? null : SearchScreen.this.scopeName(item));
        }
    }
}
