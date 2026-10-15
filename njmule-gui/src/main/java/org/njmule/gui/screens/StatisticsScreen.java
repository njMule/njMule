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

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SplitPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import org.kordamp.ikonli.Ikon;
import org.kordamp.ikonli.material2.Material2MZ;
import org.njmule.core.api.EngineStatus;
import org.njmule.gui.ui.Fx;
import org.njmule.gui.ui.Gui;
import org.njmule.gui.ui.RateGraph;
import org.njmule.gui.ui.Screen;

/** The rates of the last minutes and the totals of this session. */
public final class StatisticsScreen implements Screen {

    private final Gui gui;
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
    public Ikon icon() {
        return Material2MZ.SHOW_CHART;
    }

    @Override
    public Node view() {
        if (this.view == null) {
            this.view = this.build();
        }
        return this.view;
    }

    private Node build() {
        Region downSwatch = RateGraph.swatch("legend-down");
        Region upSwatch = RateGraph.swatch("legend-up");
        RateGraph graph = new RateGraph(
                this.gui.model().rates(), downSwatch, upSwatch, this.gui.subscriptions(), this.gui.formats()::rate);
        HBox legend = new HBox(
                8,
                downSwatch,
                new Label(this.gui.t("IDS_PW_CON_DOWNLBL")),
                upSwatch,
                new Label(this.gui.t("IDS_PW_CON_UPLBL")),
                Fx.spacer(),
                new Label(this.gui.f("nj.stats.span", RateGraph.SPAN / 60)));
        legend.setAlignment(Pos.CENTER_LEFT);
        FlowPane cards = new FlowPane(
                12,
                12,
                KadScreen.card(this.gui.t("nj.stats.downRate"), this.downRate),
                KadScreen.card(this.gui.t("nj.stats.upRate"), this.upRate),
                KadScreen.card(this.gui.t("IDS_CD_DOWN"), this.downloaded),
                KadScreen.card(this.gui.t("IDS_CD_UP"), this.uploaded),
                KadScreen.card(this.gui.t("nj.stats.downloads"), this.downloads),
                KadScreen.card(this.gui.t("nj.transfers.uploading"), this.uploads),
                KadScreen.card(this.gui.t("IDS_ONQUEUE"), this.queued),
                KadScreen.card(this.gui.t("IDS_SHAREDFILES"), this.shared),
                KadScreen.card(this.gui.t("IDS_CD_TDOWN"), this.totalDown),
                KadScreen.card(this.gui.t("IDS_CD_TUP"), this.totalUp),
                KadScreen.card(this.gui.t("nj.stats.sessions"), this.sessions),
                KadScreen.card(this.gui.t("nj.stats.runtime"), this.runtime));
        this.gui.subscriptions().onChange(this.gui.model().statusProperty(), (was, now) -> this.show(now));
        this.show(this.gui.model().statusProperty().get());
        // A short window scrolls the cards instead of setting a tall minimum for the whole window.
        ScrollPane scroll = new ScrollPane(new VBox(10, graph, legend, cards));
        scroll.setFitToWidth(true);
        // The tree on the left, the rates and totals beside it.
        SplitPane split = new SplitPane(this.tree.view(), scroll);
        split.setDividerPositions(0.34);
        SplitPane.setResizableWithParent(this.tree.view(), false);
        this.tree.refresh();
        return Fx.screen(this.title(), Fx.toolRow(), split);
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
