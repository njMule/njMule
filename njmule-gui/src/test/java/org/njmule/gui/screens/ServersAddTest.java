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
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import javafx.scene.control.Button;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.core.api.ServerInfo;
import org.njmule.gui.Engines;
import org.njmule.gui.FxTesting;
import org.njmule.gui.Statuses;
import org.njmule.gui.TestGui;

@Timeout(60)
class ServersAddTest {

    private static final List<ServerInfo> TWO = List.of(
            Statuses.server("192.0.2.10:4661", "First", false), Statuses.server("192.0.2.11:4661", "Second", false));

    @TempDir
    Path dir;

    private TestGui test;
    private ServersScreen screen;
    private final AtomicReference<List<ServerInfo>> servers = new AtomicReference<>(List.of());

    @AfterEach
    void close() {
        if (this.test != null) {
            this.test.close();
        }
    }

    @Test
    void shouldOfferTheWaysToAddOnAnEmptyList() throws Exception {
        givenTheServersScreenWith(List.of());
        givenTheWaysToAddShow(false);

        whenTheButtonOfTheEmptyListIsPressed("Add servers");

        thenTheWaysToAddShow(true);
    }

    @Test
    void shouldShowTheWaysToAddUnderTheirButton() throws Exception {
        givenTheServersScreenWith(TWO);
        givenTheWaysToAddShow(false);

        whenTheAddServersButtonIsPressed();

        thenTheWaysToAddShow(true);
    }

    private void givenTheServersScreenWith(List<ServerInfo> list) throws Exception {
        this.servers.set(list);
        this.test = TestGui.start(
                this.dir.resolve("njmule"),
                real -> Engines.overriding(
                        real, Map.of("servers", args -> CompletableFuture.completedFuture(this.servers.get()))));
        FxTesting.await("the servers", () -> this.test.model.servers().rows().size() == list.size());
        this.screen = FxTesting.onFx(() -> new ServersScreen(this.test.gui));
        this.test.show(FxTesting.onFx(this.screen::view));
    }

    private void givenTheWaysToAddShow(boolean shown) throws Exception {
        thenTheWaysToAddShow(shown);
    }

    private void whenTheButtonOfTheEmptyListIsPressed(String text) throws Exception {
        FxTesting.onFx(() -> this.screen.table().getPlaceholder().lookupAll(".button").stream()
                .map(Button.class::cast)
                .filter(b -> text.equals(b.getText()))
                .findFirst()
                .orElseThrow()
                .fire());
    }

    private void whenTheAddServersButtonIsPressed() throws Exception {
        FxTesting.onFx(() -> ((Button) this.screen.view().lookup("#add-servers")).fire());
    }

    private void thenTheWaysToAddShow(boolean shown) throws Exception {
        FxTesting.assertEventually("the ways to add shown " + shown, () -> this.screen.waysShown() == shown);
        assertThat(FxTesting.onFx(this.screen::waysShown)).isEqualTo(shown);
    }
}
