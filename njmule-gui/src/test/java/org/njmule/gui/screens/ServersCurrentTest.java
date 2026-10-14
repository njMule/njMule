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
import java.util.concurrent.TimeUnit;
import javafx.css.PseudoClass;
import javafx.scene.Node;
import javafx.scene.control.TableRow;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.core.api.EngineEvent;
import org.njmule.core.api.ServerInfo;
import org.njmule.gui.Engines;
import org.njmule.gui.FxTesting;
import org.njmule.gui.Statuses;
import org.njmule.gui.TestGui;
import org.njmule.gui.model.LiveList;

/** The server in use stands out in the list, whether njMule chose it or the user did. */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class ServersCurrentTest {

    private static final ServerInfo BIG = Statuses.server("81.2.3.4:4661", "Big", false);
    private static final ServerInfo SMALL = Statuses.server("81.2.3.5:4242", "Small", false);
    private static final PseudoClass CURRENT = PseudoClass.getPseudoClass("current-server");
    private static final PseudoClass TRYING = PseudoClass.getPseudoClass("trying-server");

    @TempDir
    Path dir;

    private TestGui test;
    private ServersScreen screen;

    @AfterEach
    void close() {
        if (this.test != null) {
            this.test.close();
        }
    }

    @Test
    void shouldMarkTheServerBeingTriedThenTheOneConnectedTo() throws Exception {
        this.givenTheServersListed();

        this.whenNjMuleConnectsTo(BIG);
        this.thenTheServerIsBeingTried(BIG);
        this.thenTheServerIsNotMarked(SMALL);

        this.whenTheConnectionIsMadeTo(BIG);
        this.thenTheServerIsInUse(BIG);
    }

    @Test
    void shouldMoveTheMarkWhenTheUserConnectsToAnotherServer() throws Exception {
        this.givenTheServersListed();
        this.givenConnectedTo(BIG);

        this.whenTheUserConnectsTo(SMALL);

        this.thenTheServerIsNotMarked(BIG);
        this.thenTheServerIsInUse(SMALL);
    }

    @Test
    void shouldUnmarkTheServerWhenTheConnectionEnds() throws Exception {
        this.givenTheServersListed();
        this.givenConnectedTo(BIG);

        this.whenTheConnectionEnds(BIG);

        this.thenTheServerIsNotMarked(BIG);
    }

    private void givenTheServersListed() throws Exception {
        Map<String, Engines.Command> commands = new HashMap<>();
        commands.put("servers", args -> CompletableFuture.completedFuture(List.of(BIG, SMALL)));
        this.test = TestGui.start(this.dir.resolve("njmule"), real -> Engines.overriding(real, commands));
        this.screen = new ServersScreen(this.test.gui);
        this.test.show(FxTesting.onFx(this.screen::view));
        FxTesting.await(
                "the servers listed", () -> this.screen.table().getItems().size() == 2);
    }

    private void givenConnectedTo(ServerInfo server) throws Exception {
        this.whenNjMuleConnectsTo(server);
        this.whenTheConnectionIsMadeTo(server);
    }

    private void whenNjMuleConnectsTo(ServerInfo server) throws Exception {
        this.event(new EngineEvent.ServerConnecting(server));
    }

    private void whenTheConnectionIsMadeTo(ServerInfo server) throws Exception {
        this.event(new EngineEvent.ServerConnected(server, 0x12345678L, false, false));
    }

    // A connection the user asks for comes through the same events: the old one ends, the new one is tried.
    private void whenTheUserConnectsTo(ServerInfo server) throws Exception {
        this.event(new EngineEvent.ServerDisconnected(Optional.of(BIG), "the user chose another server"));
        this.whenNjMuleConnectsTo(server);
        this.whenTheConnectionIsMadeTo(server);
    }

    private void whenTheConnectionEnds(ServerInfo server) throws Exception {
        this.event(new EngineEvent.ServerDisconnected(Optional.of(server), "bye"));
    }

    private void event(EngineEvent event) throws Exception {
        FxTesting.onFx(() -> {
            this.test.model.handle(event);
            this.screen.table().applyCss();
            this.screen.table().layout();
        });
    }

    private void thenTheServerIsBeingTried(ServerInfo server) throws Exception {
        this.thenTheRowIs(server, false, true, "Connecting to this server");
    }

    private void thenTheServerIsInUse(ServerInfo server) throws Exception {
        this.thenTheRowIs(server, true, false, "Connected to this server");
    }

    private void thenTheServerIsNotMarked(ServerInfo server) throws Exception {
        this.thenTheRowIs(server, false, false, null);
    }

    private void thenTheRowIs(ServerInfo server, boolean current, boolean trying, String tooltip) throws Exception {
        TableRow<?> row = this.rowOf(server);
        assertThat(FxTesting.onFx(() -> row.getPseudoClassStates().contains(CURRENT)))
                .as("%s in use", server.name())
                .isEqualTo(current);
        assertThat(FxTesting.onFx(() -> row.getPseudoClassStates().contains(TRYING)))
                .as("%s being tried", server.name())
                .isEqualTo(trying);
        assertThat(FxTesting.onFx(
                        () -> row.getTooltip() == null ? null : row.getTooltip().getText()))
                .as("%s tooltip", server.name())
                .isEqualTo(tooltip);
    }

    private TableRow<?> rowOf(ServerInfo server) throws Exception {
        return FxTesting.onFx(() -> {
            for (Node node : this.screen.table().lookupAll(".table-row-cell")) {
                if (node instanceof TableRow<?> r
                        && r.getItem() instanceof LiveList.Row<?> item
                        && item.get() instanceof ServerInfo s
                        && s.address().equals(server.address())) {
                    return r;
                }
            }
            throw new AssertionError("no row for " + server.address());
        });
    }
}
