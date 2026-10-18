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

import java.util.List;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.StringBinding;
import javafx.beans.value.ObservableValue;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.TitledPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.TilePane;
import javafx.scene.layout.VBox;
import org.njmule.core.api.EngineStatus;
import org.njmule.gui.model.EngineModel;
import org.njmule.gui.ui.Fx;
import org.njmule.gui.ui.Gui;
import org.njmule.gui.ui.Icons;
import org.njmule.gui.ui.RateGraph;
import org.njmule.gui.ui.Screen;
import org.njmule.gui.ui.Segments;
import org.njmule.gui.ui.VerticalScroll;

/** What moves now, the rates over time, the totals of this session and of all, and every statistic below. */
public final class StatisticsScreen implements Screen {

    private final Gui gui;
    private StringBinding summary;
    private Segments<RateGraph.Range> range;
    private Node view;
    private final Label downRate = value();
    private final Label upRate = value();
    private final Label downloaded = fact();
    private final Label uploaded = fact();
    private final Label downloads = value();
    private final Label uploads = value();
    private final Label queued = value();
    private final Label shared = value();
    private final Label totalDown = fact();
    private final Label totalUp = fact();
    private final Label sessions = fact();
    private final Label runtime = fact();
    private final StatsTree tree;
    private RateGraph graph;

    public StatisticsScreen(Gui gui) {
        this.gui = gui;
        this.tree = new StatsTree(gui);
    }

    @Override
    public String id() {
        return "statistics";
    }

    @Override
    public String title() {
        return this.gui.t("IDS_EM_STATISTIC");
    }

    @Override
    public Icons icon() {
        return Icons.STATISTICS;
    }

    @Override
    public ObservableValue<String> summary() {
        if (this.summary == null) {
            EngineModel model = this.gui.model();
            // How long this session has run and what it moved.
            this.summary = Bindings.createStringBinding(
                    () -> {
                        EngineStatus status = model.statusProperty().get();
                        return this.gui.f(
                                "nj.summary.statistics",
                                this.gui.formats().duration(status.sessionSeconds()),
                                this.gui.formats().size(status.downloaded()),
                                this.gui.formats().size(status.uploaded()));
                    },
                    model.statusProperty());
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

    // In the units of the language: 10 mins, 1 h, 24 h.
    private String rangeName(RateGraph.Range range) {
        int seconds = range.seconds();
        return seconds < 3_600
                ? seconds / 60 + " " + this.gui.t("IDS_MINS")
                : seconds / 3_600 + " " + this.gui.t("IDS_HOURS");
    }

    Segments<RateGraph.Range> range() {
        return this.range;
    }

    private Node build() {
        Region downSwatch = RateGraph.swatch("legend-down");
        Region upSwatch = RateGraph.swatch("legend-up");
        RateGraph graph = new RateGraph(
                this.gui.model().rates(),
                this.gui.model().minuteRates(),
                downSwatch,
                upSwatch,
                this.gui.subscriptions(),
                this.gui.formats()::rate,
                this.gui.formats()::time);
        graph.setEmptyText(this.gui.t("nj.stats.noData"));
        graph.setDescription(s -> this.gui.f(
                "nj.stats.graphSummary",
                rangeName(s.range()),
                this.gui.formats().rate(s.down()),
                this.gui.formats().rate(s.up()),
                this.gui.formats().rate(s.highest())));
        this.graph = graph;
        this.range = new Segments<>(List.of(RateGraph.Range.values()), this::rangeName);
        this.range.setValue(graph.range());
        this.range.valueProperty().addListener((o, was, now) -> graph.setRange(now));
        Label ratesTitle = new Label(this.gui.t("nj.stats.rates"));
        ratesTitle.getStyleClass().add("card-heading");
        HBox legend = new HBox(
                8,
                downSwatch,
                new Label(this.gui.t("IDS_PW_CON_DOWNLBL")),
                upSwatch,
                new Label(this.gui.t("IDS_PW_CON_UPLBL")));
        legend.setAlignment(Pos.CENTER_LEFT);
        FlowPane head = new FlowPane(16, 8, ratesTitle, legend, this.range.node());
        head.setAlignment(Pos.CENTER_LEFT);
        VBox rates = card(head, graph);
        // What happens now first, then the rates over time, then the session and all sessions, then every figure.
        TilePane present = tiles(
                KadScreen.card(this.gui.t("nj.stats.downRate"), this.downRate),
                KadScreen.card(this.gui.t("nj.stats.upRate"), this.upRate),
                KadScreen.card(this.gui.t("nj.stats.downloads"), this.downloads),
                KadScreen.card(this.gui.t("nj.transfers.uploading"), this.uploads),
                KadScreen.card(this.gui.t("IDS_ONQUEUE"), this.queued),
                KadScreen.card(this.gui.t("IDS_SHAREDFILES"), this.shared));
        VBox session = facts(
                this.gui.t("IDS_STATS_SESSION"),
                List.of("IDS_CD_DOWN", "IDS_CD_UP", "nj.stats.runtime"),
                List.of(this.downloaded, this.uploaded, this.runtime));
        VBox cumulative = facts(
                this.gui.t("IDS_STATS_CUMULATIVE"),
                List.of("IDS_CD_TDOWN", "IDS_CD_TUP", "nj.stats.sessions"),
                List.of(this.totalDown, this.totalUp, this.sessions));
        TilePane totals = new TilePane(12, 12, session, cumulative);
        totals.setPrefColumns(2);
        totals.setTileAlignment(Pos.TOP_LEFT);
        for (VBox card : List.of(session, cumulative)) {
            card.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        }
        // Every figure in the tree, closed until wanted: the cards above say the main ones.
        this.tree.view().getStyleClass().add("stats-tree");
        this.tree.view().setPrefHeight(380);
        TitledPane every = new TitledPane(this.gui.t("nj.stats.all"), this.tree.view());
        every.setExpanded(false);
        every.getStyleClass().add("stats-all");
        every.setId("stats-all");
        this.gui.subscriptions().onChange(this.gui.model().statusProperty(), (was, now) -> show(now));
        show(this.gui.model().statusProperty().get());
        VBox all = new VBox(14, present, rates, totals, every);
        all.setPadding(new Insets(0, 4, 8, 0));
        // A short window scrolls the cards instead of setting a tall minimum for the whole window.
        VerticalScroll scroll = new VerticalScroll(all);
        scroll.setFitToWidth(true);
        this.tree.refresh();
        return Fx.screen(scroll);
    }

    // A card of the page: its heading row, then its content.
    private static VBox card(Node head, Node content) {
        VBox box = new VBox(10, head, content);
        box.getStyleClass().add("stats-card");
        return box;
    }

    // A card of named figures, the names muted on the start side, the figures on the end side.
    private VBox facts(String title, List<String> keys, List<Label> values) {
        Label heading = new Label(title);
        heading.getStyleClass().add("card-heading");
        GridPane grid = new GridPane();
        grid.getStyleClass().add("details-facts");
        for (int i = 0; i < keys.size(); i++) {
            Label name = new Label(this.gui.t(keys.get(i)));
            name.getStyleClass().add("text-muted");
            name.setMinWidth(Region.USE_PREF_SIZE);
            grid.addRow(i, name, values.get(i));
        }
        return card(heading, grid);
    }

    // Tiles of one size in a regular grid, as wide as the widest. Their names never wrap: JavaFX's text layout can
    // fail on some wrapped texts with the Linux fonts (an index out of bounds).
    private static TilePane tiles(VBox... cards) {
        TilePane pane = new TilePane(12, 12, cards);
        pane.setTileAlignment(Pos.TOP_LEFT);
        for (VBox card : cards) {
            card.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        }
        return pane;
    }

    private void show(EngineStatus s) {
        if (this.view != null) {
            this.tree.refresh();
        }
        var formats = this.gui.formats();
        this.downRate.setText(formats.rate(s.downloadBytesPerSecond()));
        this.upRate.setText(formats.rate(s.uploadBytesPerSecond()));
        this.downloaded.setText(formats.size(s.downloaded()));
        this.uploaded.setText(formats.size(s.uploaded()));
        this.downloads.setText(formats.count(s.downloads()));
        this.uploads.setText(formats.count(s.uploads()));
        this.queued.setText(formats.count(s.queued()));
        this.shared.setText(formats.count(s.sharedFiles()));
        this.totalDown.setText(formats.size(s.totalDownloaded()));
        this.totalUp.setText(formats.size(s.totalUploaded()));
        this.sessions.setText(formats.count(s.sessions()));
        this.runtime.setText(formats.duration(s.sessionSeconds()) + " / " + formats.duration(s.totalSeconds()));
    }

    Label downloadedLabel() {
        return this.downloaded;
    }

    StatsTree tree() {
        return this.tree;
    }

    private static Label value() {
        Label label = new Label("-");
        label.getStyleClass().add("stat-value");
        return label;
    }

    private static Label fact() {
        Label label = new Label("-");
        label.getStyleClass().add("kad-fact");
        return label;
    }

    RateGraph graph() {
        return this.graph;
    }
}
