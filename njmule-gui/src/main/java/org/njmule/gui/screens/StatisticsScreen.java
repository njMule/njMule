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
import java.util.List;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.StringBinding;
import javafx.beans.value.ObservableValue;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SplitPane;
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

/** The rates of the last minutes and the totals of this session. */
public final class StatisticsScreen implements Screen {

    private final Gui gui;
    private StringBinding summary;
    private Segments<RateGraph.Range> range;
    private Node view;
    private final Label downRate = value();
    private final Label upRate = value();
    private final Label downloaded = value();
    private final Label uploaded = value();
    private final Label downloads = value();
    private final Label uploads = value();
    private final Label queued = value();
    private final Label shared = value();
    private final Label totalDown = value();
    private final Label totalUp = value();
    private final Label sessions = value();
    private final Label runtime = value();
    private final StatsTree tree;

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
        this.range = new Segments<>(List.of(RateGraph.Range.values()), this::rangeName);
        this.range.setValue(graph.range());
        this.range.valueProperty().addListener((o, was, now) -> graph.setRange(now));
        HBox legend = new HBox(
                8,
                downSwatch,
                new Label(this.gui.t("IDS_PW_CON_DOWNLBL")),
                upSwatch,
                new Label(this.gui.t("IDS_PW_CON_UPLBL")),
                Fx.spacer(),
                this.range.node());
        legend.setAlignment(Pos.CENTER_LEFT);
        // What happens now first, then the rates over time, then the session and all sessions.
        TilePane present = cards(
                KadScreen.card(this.gui.t("nj.stats.downRate"), this.downRate),
                KadScreen.card(this.gui.t("nj.stats.upRate"), this.upRate),
                KadScreen.card(this.gui.t("nj.stats.downloads"), this.downloads),
                KadScreen.card(this.gui.t("nj.transfers.uploading"), this.uploads),
                KadScreen.card(this.gui.t("IDS_ONQUEUE"), this.queued),
                KadScreen.card(this.gui.t("IDS_SHAREDFILES"), this.shared));
        TilePane session = cards(
                KadScreen.card(this.gui.t("IDS_CD_DOWN"), this.downloaded),
                KadScreen.card(this.gui.t("IDS_CD_UP"), this.uploaded),
                KadScreen.card(this.gui.t("nj.stats.runtime"), this.runtime));
        TilePane cumulative = cards(
                KadScreen.card(this.gui.t("IDS_CD_TDOWN"), this.totalDown),
                KadScreen.card(this.gui.t("IDS_CD_TUP"), this.totalUp),
                KadScreen.card(this.gui.t("nj.stats.sessions"), this.sessions));
        this.gui.subscriptions().onChange(this.gui.model().statusProperty(), (was, now) -> show(now));
        show(this.gui.model().statusProperty().get());
        // A short window scrolls the cards instead of setting a tall minimum for the whole window.
        VBox all = new VBox(
                10,
                present,
                graph,
                legend,
                heading(this.gui.t("IDS_STATS_SESSION")),
                session,
                heading(this.gui.t("IDS_STATS_CUMULATIVE")),
                cumulative);
        all.setPadding(new Insets(0, 4, 8, 0));
        ScrollPane scroll = new ScrollPane(all);
        scroll.setFitToWidth(true);
        // The tree on the left, the rates and totals beside it.
        this.tree.view().getStyleClass().add("stats-tree");
        SplitPane split = new SplitPane(this.tree.view(), scroll);
        split.setDividerPositions(0.34);
        SplitPane.setResizableWithParent(this.tree.view(), false);
        this.tree.refresh();
        return Fx.screen(split);
    }

    // Cards of one size in a regular grid, as wide as the widest. Their names never wrap: JavaFX's text layout can
    // fail on some wrapped texts with the Linux fonts (an index out of bounds).
    private static TilePane cards(VBox... cards) {
        TilePane pane = new TilePane(12, 12, cards);
        pane.setTileAlignment(Pos.TOP_LEFT);
        for (VBox card : cards) {
            card.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        }
        return pane;
    }

    private static Label heading(String text) {
        Label label = new Label(text);
        label.getStyleClass().add(Styles.TEXT_BOLD);
        VBox.setMargin(label, new Insets(6, 0, 0, 0));
        return label;
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
}
