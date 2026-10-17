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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.core.api.EngineEvent;
import org.njmule.core.api.ServerInfo;
import org.njmule.gui.Engines;
import org.njmule.gui.FxTesting;
import org.njmule.gui.TestGui;

@Timeout(60)
class ServersPanelTest {

    private static final ServerInfo BIG = new ServerInfo(
            "81.2.3.4:4661",
            "Big",
            "The big one",
            1_200,
            45_000,
            5_000,
            0,
            ServerInfo.Priority.NORMAL,
            40,
            1_000,
            2_000,
            300,
            "17.15",
            true,
            false);
    private static final ServerInfo SMALL = new ServerInfo(
            "81.2.3.5:4242", "Small", "", 0, 0, 0, 2, ServerInfo.Priority.HIGH, 0, 0, 0, 0, "", false, true);

    @TempDir
    Path dir;

    private TestGui test;
    private ServersScreen screen;
    private final List<String> calls = new CopyOnWriteArrayList<>();

    @AfterEach
    void close() {
        if (this.test != null) {
            this.test.close();
        }
    }

    @Test
    void shouldSayHowToSeeAServerWhenNoneIsChosen() throws Exception {
        givenTheServersScreen();

        whenNoServerIsChosen();

        thenTheServerShows(false);
    }

    @Test
    void shouldShowTheChosenServerWithItsFigures() throws Exception {
        givenTheServersScreen();

        whenTheServerIsChosen(BIG);

        thenTheServerShows(true);
        thenThePanelReads("Big", "81.2.3.4:4661", "The big one", "1,200", "45,000", "17.15", "40 ms");
        thenTheButtonsRead("Connect", "Add To Static List");
    }

    @Test
    void shouldOfferToTakeAStaticServerOutOfTheStaticList() throws Exception {
        givenTheServersScreen();
        givenTheServerChosen(SMALL);
        givenTheButtonsRead("Connect", "Remove From Static List");

        whenTheStaticButtonIsPressed();

        thenTheEngineWasAskedFor("static 81.2.3.5:4242 false");
    }

    @Test
    void shouldConnectToTheChosenServer() throws Exception {
        givenTheServersScreen();
        givenTheServerChosen(SMALL);

        whenTheActionIsPressed();

        thenTheEngineWasAskedFor("connect 81.2.3.5:4242");
    }

    @Test
    void shouldOfferToDisconnectFromTheServerWeAreOnAndShowWhatItSaid() throws Exception {
        givenTheServersScreen();
        givenTheServerChosen(BIG);

        whenWeConnectToAndHearFrom(BIG, "Welcome to Big");

        thenTheButtonsRead("Disconnect", "Add To Static List");
        thenTheMessageReads("Welcome to Big");
    }

    private void givenTheServersScreen() throws Exception {
        Map<String, Engines.Command> commands = new HashMap<>();
        commands.put("servers", args -> CompletableFuture.completedFuture(List.of(BIG, SMALL)));
        commands.put("connect", args -> {
            Optional<?> server = (Optional<?>) args[0];
            this.calls.add("connect " + server.map(String::valueOf).orElse("any"));
            return CompletableFuture.completedFuture(null);
        });
        commands.put("setStaticServer", args -> {
            this.calls.add("static " + args[0] + " " + args[1]);
            return CompletableFuture.completedFuture(true);
        });
        this.test = TestGui.start(this.dir.resolve("njmule"), real -> Engines.overriding(real, commands));
        this.screen = FxTesting.onFx(() -> new ServersScreen(this.test.gui));
        this.test.show(FxTesting.onFx(this.screen::view));
        FxTesting.await("the servers", () -> this.screen.table().getItems().size() == 2);
    }

    private void givenTheServerChosen(ServerInfo server) throws Exception {
        whenTheServerIsChosen(server);
    }

    private void givenTheButtonsRead(String action, String keep) throws Exception {
        thenTheButtonsRead(action, keep);
    }

    private void whenNoServerIsChosen() throws Exception {
        FxTesting.onFx(() -> this.screen.table().getSelectionModel().clearSelection());
    }

    private void whenTheServerIsChosen(ServerInfo server) throws Exception {
        FxTesting.onFx(() -> {
            var table = this.screen.table();
            table.getSelectionModel().clearSelection();
            for (int i = 0; i < table.getItems().size(); i++) {
                if (table.getItems().get(i).get().address().equals(server.address())) {
                    table.getSelectionModel().select(i);
                }
            }
        });
    }

    private void whenTheStaticButtonIsPressed() throws Exception {
        FxTesting.onFx(() -> this.screen.panel().staticButton().fire());
    }

    private void whenTheActionIsPressed() throws Exception {
        FxTesting.onFx(() -> this.screen.panel().actionButton().fire());
    }

    private void whenWeConnectToAndHearFrom(ServerInfo server, String message) throws Exception {
        FxTesting.onFx(() -> {
            this.test.model.handle(new EngineEvent.ServerConnected(server, 99, false, true));
            this.test.model.handle(new EngineEvent.ServerMessage(server, message));
        });
    }

    private void thenTheServerShows(boolean shown) throws Exception {
        assertThat(FxTesting.onFx(() -> this.screen.panel().showsOne())).isEqualTo(shown);
    }

    private void thenThePanelReads(String... texts) throws Exception {
        assertThat(FxTesting.onFx(() -> FxTesting.labelTexts(this.screen.panel().view())))
                .contains(texts);
    }

    private void thenTheButtonsRead(String action, String keep) throws Exception {
        FxTesting.assertEventually(
                "the buttons read " + action + " and " + keep,
                () -> action.equals(this.screen.panel().actionButton().getText())
                        && keep.equals(this.screen.panel().staticButton().getText()));
    }

    private void thenTheMessageReads(String text) throws Exception {
        FxTesting.assertEventually(
                "the message reads " + text,
                () -> text.equals(this.screen.panel().message().getText()));
    }

    private void thenTheEngineWasAskedFor(String expected) throws Exception {
        FxTesting.assertEventually("the engine asked for " + expected, () -> this.calls.contains(expected));
    }
}
