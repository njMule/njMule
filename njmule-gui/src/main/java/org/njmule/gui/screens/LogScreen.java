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
import java.util.Locale;
import java.util.Optional;
import javafx.collections.ListChangeListener;
import javafx.collections.transformation.FilteredList;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import org.njmule.core.api.LogLine;
import org.njmule.gui.infra.Formats;
import org.njmule.gui.model.EngineModel;
import org.njmule.gui.ui.FilterChips;
import org.njmule.gui.ui.Fx;
import org.njmule.gui.ui.Gui;
import org.njmule.gui.ui.Icons;
import org.njmule.gui.ui.LogView;
import org.njmule.gui.ui.Screen;

/**
 * What happened, in the network's place: the log, narrowed to the problems if wanted, what the servers said, and what
 * the others see of us.
 */
public final class LogScreen implements Screen {

    /** The lines the chips keep. */
    enum Level {
        ALL("IDS_ALL"),
        ERROR("IDS_ERROR"),
        WARNING("IDS_WARNING"),
        INFO("IDS_INFO");

        private final String title;

        Level(String title) {
            this.title = title;
        }

        // Successes and the details go with the news.
        boolean keeps(LogLine line) {
            return switch (this) {
                case ALL -> true;
                case ERROR -> line.level() == LogLine.Level.ERROR;
                case WARNING -> line.level() == LogLine.Level.WARNING;
                case INFO ->
                    line.level() == LogLine.Level.INFO
                            || line.level() == LogLine.Level.SUCCESS
                            || line.level() == LogLine.Level.DEBUG;
            };
        }
    }

    private final Gui gui;
    private final MyInfoPane myInfo;
    private Node view;
    private TabPane tabs;
    private Tab logTab;
    private FilterChips<Level> levels;
    private LogView<EngineModel.ServerNote> serverInfoView;
    private LogView<LogLine> logView;

    public LogScreen(Gui gui) {
        this.gui = gui;
        this.myInfo = new MyInfoPane(gui);
    }

    @Override
    public String id() {
        return "log";
    }

    @Override
    public String title() {
        return this.gui.t("IDS_SV_LOG");
    }

    @Override
    public Icons icon() {
        return Icons.LOG;
    }

    @Override
    public void shown() {
        this.myInfo.readWebUi();
    }

    @Override
    public Node view() {
        if (this.view == null) {
            this.view = build();
        }
        return this.view;
    }

    private Node build() {
        // Both logs read left to right, the lines starting on the left, in every language; their text is chosen with
        // the mouse.
        String copy = this.gui.t("IDS_COPY");
        String selectAll = this.gui.t("IDS_SELECTALL");
        LogView<EngineModel.ServerNote> info = new LogView<>(n -> noteLine(this.gui, n), false, copy, selectAll);
        info.setAll(this.gui.model().serverInfo());
        this.gui.subscriptions().onListChange(this.gui.model().serverInfo(), c -> info.changed(c));
        FilteredList<LogLine> shownLog = new FilteredList<>(this.gui.model().log());
        LogView<LogLine> log = new LogView<>(l -> logLine(this.gui.formats(), l), true, copy, selectAll);
        log.setAll(shownLog);
        shownLog.addListener((ListChangeListener<LogLine>) c -> log.changed(c));
        this.serverInfoView = info;
        this.logView = log;
        this.levels = new FilterChips<>(List.of(Level.values()), l -> this.gui.t(l.title));
        this.levels.node().setId("log-levels");
        // The log follows the change of the lines kept as it follows new lines.
        this.levels.valueProperty().addListener((o, was, now) -> shownLog.setPredicate(now::keeps));
        this.gui.subscriptions().onListChange(this.gui.model().log(), c -> countLevels());
        countLevels();
        Tab logTab = new Tab(this.gui.t("IDS_SV_LOG"), log.node());
        this.logTab = logTab;
        Tab infoTab = new Tab(this.gui.t("IDS_SV_SERVERINFO"), info.node());
        Tab mine = new Tab(this.gui.t("IDS_MYINFO"), this.myInfo.node());
        this.tabs = new TabPane(logTab, infoTab, mine);
        this.tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        this.tabs.setId("log-tabs");
        Button clear = Fx.button(this.gui.t("IDS_PW_RESET"), Icons.CLEAR_ALL, () -> {
            if (infoTab.isSelected()) {
                this.gui.model().clearServerInfo();
            } else {
                this.gui.model().clearLog();
            }
        });
        clear.getStyleClass().add(Styles.FLAT);
        // The levels are the log's; My Info has nothing to reset.
        this.levels.node().visibleProperty().bind(logTab.selectedProperty());
        clear.visibleProperty().bind(mine.selectedProperty().not());
        var tools = Fx.toolRow(this.levels.node(), Fx.spacer(), clear);
        // Shown under the network, which gives the title.
        return Fx.part(tools, this.tabs);
    }

    private void countLevels() {
        for (Level level : Level.values()) {
            this.levels.count(
                    level, this.gui.model().log().stream().filter(level::keeps).count());
        }
    }

    /** Brings the log to the front. */
    public void showLog() {
        view();
        this.tabs.getSelectionModel().select(this.logTab);
    }

    MyInfoPane myInfo() {
        return this.myInfo;
    }

    TabPane logTabs() {
        return this.tabs;
    }

    FilterChips<Level> levels() {
        return this.levels;
    }

    LogView<EngineModel.ServerNote> serverInfoView() {
        return this.serverInfoView;
    }

    LogView<LogLine> logView() {
        return this.logView;
    }

    /**
     * An event after its date and time as the system shows them, with its level as style; problems and successes carry
     * an icon besides their colour.
     */
    static LogView.Line logLine(Formats formats, LogLine line) {
        Optional<Icons> icon = switch (line.level()) {
            case ERROR -> Optional.of(Icons.ERROR);
            case WARNING -> Optional.of(Icons.WARNING);
            case SUCCESS -> Optional.of(Icons.CHECK_CIRCLE);
            case INFO, DEBUG -> Optional.empty();
        };
        return new LogView.Line(
                formats.dateTime(line.time()) + ": " + line.message(),
                List.of(line.level().name().toLowerCase(Locale.ROOT)),
                icon);
    }

    /** A session's start with its date and time, or a line the server sent as it is. */
    static LogView.Line noteLine(Gui gui, EngineModel.ServerNote note) {
        return switch (note.kind()) {
            case CONNECTED, CONNECTED_OBFUSCATED -> {
                String key = note.kind() == EngineModel.ServerNote.Kind.CONNECTED
                        ? "IDS_CONNECTEDTO"
                        : "IDS_CONNECTEDTOOBFUSCATED";
                yield new LogView.Line(
                        gui.formats().dateTime(note.time()) + ": " + gui.f(key, note.text()),
                        List.of("success"),
                        Optional.empty());
            }
            default -> new LogView.Line(note.text(), List.of(), Optional.empty());
        };
    }
}
