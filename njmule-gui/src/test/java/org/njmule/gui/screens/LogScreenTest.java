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

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import javafx.scene.control.Button;
import javafx.scene.control.Tab;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.core.api.EngineEvent;
import org.njmule.core.api.LogLine;
import org.njmule.core.api.ServerInfo;
import org.njmule.gui.FxTesting;
import org.njmule.gui.Statuses;
import org.njmule.gui.TestGui;

@Timeout(120)
class LogScreenTest {

    private static final ServerInfo BIG = Statuses.server("81.2.3.4:4661", "Big", false);
    private static final ServerInfo SMALL = new ServerInfo(
            "81.2.3.5:4242", "Small", "", 0, 0, 0, 2, ServerInfo.Priority.HIGH, 0, 0, 0, 0, "", false, true);

    @TempDir
    Path dir;

    private TestGui test;
    private LogScreen screen;

    @AfterEach
    void close() {
        if (this.test != null) {
            this.test.close();
        }
    }

    @Test
    void shouldShowTheLogTheServerInfoAndMyInfoInTabs() throws Exception {
        givenTheGui();

        whenTheLogScreenShows();

        thenTheTabsAre("Log", "Server Info", "My Info");
        thenTheLogTabIsChosen();
    }

    @Test
    void shouldTellWhatOthersSeeOfUsInMyInfo() throws Exception {
        givenTheGui();

        whenTheLogScreenShows();

        thenMyInfoReads(
                "eD2K Network",
                "Status: Disconnected",
                "Kad Network",
                "Status: Disconnected",
                "Web Interface",
                "Status: Disabled");
    }

    @Test
    void shouldShowTheSessionsInTheServerInfo() throws Exception {
        givenTheLogScreen();

        whenTwoSessionsAMessageAndLinesOfEachLevelCome();

        thenTheServerInfoShowsTheSessions();
        thenTheServerInfoShowsSuccesses(2);
    }

    @Test
    void shouldCountTheLinesOfEachLevelOnTheChips() throws Exception {
        givenTheLogScreen();

        whenTwoSessionsAMessageAndLinesOfEachLevelCome();

        thenTheChipSays(LogScreen.Level.ALL, "All, 4");
        thenTheChipSays(LogScreen.Level.ERROR, "Error, 1");
        thenTheChipSays(LogScreen.Level.WARNING, "Warning, 1");
        thenTheChipSays(LogScreen.Level.INFO, "Info, 2");
    }

    @Test
    void shouldKeepOnlyTheWarningsWhenTheirChipIsChosen() throws Exception {
        givenTheLogScreen();
        givenTwoSessionsAMessageAndLinesOfEachLevel();
        givenTheLogShows("trouble", "careful", "fine", "done");

        whenTheLevelIsChosen(LogScreen.Level.WARNING);

        thenTheLogShows("careful");
    }

    @Test
    void shouldShowEveryLineAgainWhenAllIsChosen() throws Exception {
        givenTheLogScreen();
        givenTwoSessionsAMessageAndLinesOfEachLevel();
        givenTheLevelChosen(LogScreen.Level.ERROR);
        givenTheLogShows("trouble");

        whenTheLevelIsChosen(LogScreen.Level.ALL);

        thenTheLogShows("trouble", "careful", "fine", "done");
    }

    @Test
    void shouldResetTheServerInfoAndKeepTheLog() throws Exception {
        givenTheLogScreen();
        givenTwoSessionsAMessageAndLinesOfEachLevel();
        givenTheServerInfoTabChosen();
        givenTheServerInfoShowsTheSessions();

        whenResetIsPressed();

        thenTheServerInfoIsEmpty();
        thenTheLogIsKept();
    }

    @Test
    void shouldResetTheLog() throws Exception {
        givenTheLogScreen();
        givenTwoSessionsAMessageAndLinesOfEachLevel();
        givenTheLogShows("trouble", "careful", "fine", "done");

        whenResetIsPressed();

        thenTheLogIsEmpty();
    }

    private void givenTheGui() throws Exception {
        this.test = TestGui.start(this.dir.resolve("njmule"));
    }

    // The log starts empty: the line of the start, once come, is gone.
    private void givenTheLogScreen() throws Exception {
        givenTheGui();
        FxTesting.await("the start in the log", () -> !this.test.model.log().isEmpty());
        FxTesting.onFx(() -> this.test.model.clearLog());
        whenTheLogScreenShows();
    }

    private void givenTwoSessionsAMessageAndLinesOfEachLevel() throws Exception {
        whenTwoSessionsAMessageAndLinesOfEachLevelCome();
    }

    private void givenTheLogShows(String... messages) throws Exception {
        thenTheLogShows(messages);
    }

    private void givenTheLevelChosen(LogScreen.Level level) throws Exception {
        whenTheLevelIsChosen(level);
    }

    private void givenTheServerInfoTabChosen() throws Exception {
        FxTesting.onFx(() -> this.screen.logTabs().getSelectionModel().select(1));
    }

    private void givenTheServerInfoShowsTheSessions() throws Exception {
        thenTheServerInfoShowsTheSessions();
    }

    private void whenTheLogScreenShows() throws Exception {
        this.screen = FxTesting.onFx(() -> new LogScreen(this.test.gui));
        this.test.show(FxTesting.onFx(this.screen::view));
        FxTesting.onFx(this.screen::shown);
    }

    private void whenTwoSessionsAMessageAndLinesOfEachLevelCome() throws Exception {
        FxTesting.onFx(() -> {
            this.test.model.handle(new EngineEvent.ServerConnected(BIG, 99, false, true));
            this.test.model.handle(new EngineEvent.ServerConnected(SMALL, 99, true, false));
            this.test.model.handle(new EngineEvent.ServerMessage(BIG, "Welcome to Big"));
            this.test.model.handle(log(LogLine.Level.ERROR, "trouble"));
            this.test.model.handle(log(LogLine.Level.WARNING, "careful"));
            this.test.model.handle(log(LogLine.Level.INFO, "fine"));
            this.test.model.handle(log(LogLine.Level.SUCCESS, "done"));
        });
    }

    private void whenTheLevelIsChosen(LogScreen.Level level) throws Exception {
        FxTesting.onFx(() -> this.screen.levels().chip(level).fire());
    }

    private void whenResetIsPressed() throws Exception {
        FxTesting.onFx(() -> reset().fire());
    }

    private void thenTheTabsAre(String... titles) throws Exception {
        assertThat(FxTesting.onFx(() -> this.screen.logTabs().getTabs().stream()
                        .map(Tab::getText)
                        .toList()))
                .containsExactly(titles);
    }

    private void thenTheLogTabIsChosen() throws Exception {
        assertThat(FxTesting.onFx(
                        () -> this.screen.logTabs().getSelectionModel().getSelectedIndex()))
                .isZero();
    }

    private void thenMyInfoReads(String... lines) throws Exception {
        assertThat(FxTesting.onFx(() -> this.screen.myInfo().lines()))
                .extracting(l -> (l.label() + " " + l.value()).strip())
                .containsExactly(lines);
    }

    private void thenTheServerInfoShowsTheSessions() throws Exception {
        FxTesting.assertEventually("the sessions", () -> {
            List<String> lines = List.of(this.screen.serverInfoView().text().split("\n"));
            return lines.stream().anyMatch(t -> t.contains("Obfuscated connection established"))
                    && lines.stream().anyMatch(t -> t.contains("Connection established"))
                    && lines.contains("Welcome to Big");
        });
    }

    private void thenTheServerInfoShowsSuccesses(int count) throws Exception {
        assertThat(FxTesting.onFx(() -> this.screen.serverInfoView().count("success")))
                .isEqualTo(count);
    }

    private void thenTheChipSays(LogScreen.Level level, String said) throws Exception {
        FxTesting.assertEventually(
                level + " says " + said,
                () -> said.equals(this.screen.levels().chip(level).getAccessibleText()));
    }

    // The log's lines, each after its date and time, in the order they came.
    private void thenTheLogShows(String... messages) throws Exception {
        FxTesting.assertEventually("the log shows " + List.of(messages), () -> {
            String text = this.screen.logView().text();
            List<String> lines = text.isEmpty() ? List.of() : List.of(text.split("\n"));
            if (lines.size() != messages.length) {
                return false;
            }
            for (int i = 0; i < messages.length; i++) {
                if (!lines.get(i).endsWith(": " + messages[i])) {
                    return false;
                }
            }
            return true;
        });
    }

    private void thenTheServerInfoIsEmpty() throws Exception {
        FxTesting.assertEventually(
                "no server info", () -> this.test.model.serverInfo().isEmpty());
    }

    private void thenTheLogIsKept() {
        assertThat(this.test.model.log()).isNotEmpty();
    }

    private void thenTheLogIsEmpty() throws Exception {
        FxTesting.assertEventually("an empty log", () -> this.test.model.log().isEmpty());
    }

    private Button reset() {
        return this.screen.logTabs().getParent().lookupAll(".button").stream()
                .map(Button.class::cast)
                .filter(b -> "Reset".equals(b.getText()))
                .findFirst()
                .orElseThrow();
    }

    private static EngineEvent.Log log(LogLine.Level level, String message) {
        return new EngineEvent.Log(new LogLine(Instant.now(), level, message));
    }
}
