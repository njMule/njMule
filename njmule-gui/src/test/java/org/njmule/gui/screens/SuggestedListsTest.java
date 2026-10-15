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
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.gui.FxTesting;
import org.njmule.gui.TestGui;
import org.njmule.gui.ui.UrlBox;

/** The server and node lists filled in while the user has used none. */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class SuggestedListsTest {

    private static final String SERVER_MET = "http://upd.emule-security.org/server.met";
    private static final String NODES_DAT = "http://upd.emule-security.org/nodes.dat";

    @TempDir
    Path dir;

    private TestGui test;
    private UrlBox box;

    @AfterEach
    void close() throws Exception {
        if (this.test != null) {
            this.test.close();
        }
    }

    @Test
    void shouldFillInAWellKnownServerListWhileNoneWasUsed() throws Exception {
        givenNjMuleStarted();

        whenTheServersAreShown();

        thenTheBoxHolds(SERVER_MET);
        thenNothingIsRemembered("serverMet");
    }

    @Test
    void shouldFillInAWellKnownNodesListWhileNoneWasUsed() throws Exception {
        givenNjMuleStarted();

        whenKadIsShown();

        thenTheBoxHolds(NODES_DAT);
        thenNothingIsRemembered("nodesDat");
    }

    @Test
    void shouldOfferTheListsUsedBeforeInsteadOfTheWellKnownOne() throws Exception {
        givenNjMuleStarted();
        givenTheServerListUsedBefore("https://example.org/server.met");

        whenTheServersAreShown();

        thenTheBoxOffersOnly("https://example.org/server.met");
    }

    private void givenNjMuleStarted() throws Exception {
        this.test = TestGui.start(this.dir.resolve("njmule"));
    }

    private void givenTheServerListUsedBefore(String url) {
        this.test.gui.settings().remember("serverMet", url);
    }

    private void whenTheServersAreShown() throws Exception {
        ServersScreen screen = FxTesting.onFx(() -> new ServersScreen(this.test.gui));
        this.test.show(FxTesting.onFx(screen::view));
        this.box = screen.listBox();
    }

    private void whenKadIsShown() throws Exception {
        KadScreen screen = FxTesting.onFx(() -> new KadScreen(this.test.gui));
        this.test.show(FxTesting.onFx(screen::view));
        this.box = screen.nodesBox();
    }

    private void thenTheBoxHolds(String url) throws Exception {
        assertThat(FxTesting.onFx(() -> this.box.node().getEditor().getText())).isEqualTo(url);
        assertThat(FxTesting.onFx(() -> this.box.node().getItems())).containsExactly(url);
    }

    private void thenTheBoxOffersOnly(String url) throws Exception {
        assertThat(FxTesting.onFx(() -> this.box.node().getItems())).containsExactly(url);
        assertThat(FxTesting.onFx(() -> this.box.node().getEditor().getText())).isEmpty();
    }

    private void thenNothingIsRemembered(String history) {
        assertThat(this.test.gui.settings().history(history)).isEmpty();
    }
}
