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
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.core.api.EngineStatus;
import org.njmule.core.api.KadInfo;
import org.njmule.gui.Engines;
import org.njmule.gui.FxTesting;
import org.njmule.gui.Statuses;
import org.njmule.gui.TestGui;

@Timeout(60)
class KadWordsTest {

    private static final int PORT = 4662;

    @TempDir
    Path dir;

    private TestGui test;
    private KadScreen screen;
    private final AtomicReference<EngineStatus> status =
            new AtomicReference<>(Statuses.kadOnly(Optional.empty(), PORT));

    @AfterEach
    void close() {
        if (this.test != null) {
            this.test.close();
        }
    }

    @Test
    void shouldSayHowToStartKadWhileItIsOffAndOpenTheWaysIn() throws Exception {
        whenTheKadScreenShows();

        thenKadSays("Kad is off. It finds sources and files without servers: connect to start it.");
        thenTheWaysInAreOpen(true);
    }

    @Test
    void shouldCloseTheWaysInAndSayOthersReachUsOnceKadIsOn() throws Exception {
        givenTheKadScreen();

        whenKadIs(Statuses.kad(true, false, "192.0.2.7"));

        thenKadSays("Kad is on, and other nodes can reach you.");
        thenTheToneIs("success");
        thenTheWaysInAreOpen(false);
    }

    @Test
    void shouldNameThePortToOpenWhenKadIsBehindAFirewall() throws Exception {
        givenTheKadScreen();

        whenKadIs(Statuses.kad(true, true, "192.0.2.7"));

        thenKadSays("Kad is on, but other nodes cannot reach you on port 4662: open it in your router, or turn"
                + " on UPnP in the options.");
        thenTheToneIs("warning");
    }

    @Test
    void shouldKeepTheWaysInAsTheUserLeftThemUntilKadConnectsOrStops() throws Exception {
        givenTheKadScreen();
        givenKadIs(new KadInfo(false, 3, 0, false, "", "", 4672));
        givenKadSays("Kad is looking for the nodes it knows. If it finds none, give it some below.");
        givenTheUserClosedTheWaysIn();
        givenKadIs(new KadInfo(false, 5, 1, false, "", "", 4672));
        givenTheWaysInAreOpen(false);
        givenKadIs(Statuses.kad(true, false, "192.0.2.7"));

        whenKadStops();

        thenTheWaysInAreOpen(true);
    }

    private void givenTheKadScreen() throws Exception {
        showTheKadScreen();
    }

    private void givenKadIs(KadInfo kad) throws Exception {
        status(Statuses.kadOnly(Optional.of(kad), PORT));
    }

    private void givenKadSays(String words) throws Exception {
        assertThat(FxTesting.onFx(() -> this.screen.sayLabel().getText())).isEqualTo(words);
    }

    private void givenTheUserClosedTheWaysIn() throws Exception {
        FxTesting.onFx(() -> this.screen.joinPane().setExpanded(false));
    }

    private void givenTheWaysInAreOpen(boolean open) throws Exception {
        assertThat(FxTesting.onFx(() -> this.screen.joinPane().isExpanded())).isEqualTo(open);
    }

    private void whenTheKadScreenShows() throws Exception {
        showTheKadScreen();
    }

    private void whenKadIs(KadInfo kad) throws Exception {
        status(Statuses.kadOnly(Optional.of(kad), PORT));
    }

    private void whenKadStops() throws Exception {
        status(Statuses.kadOnly(Optional.empty(), PORT));
    }

    private void thenKadSays(String words) throws Exception {
        assertThat(FxTesting.onFx(() -> this.screen.sayLabel().getText())).isEqualTo(words);
    }

    private void thenTheToneIs(String tone) throws Exception {
        assertThat(FxTesting.onFx(() -> this.screen.sayLabel().getStyleClass())).contains(tone);
    }

    private void thenTheWaysInAreOpen(boolean open) throws Exception {
        assertThat(FxTesting.onFx(() -> this.screen.joinPane().isExpanded())).isEqualTo(open);
    }

    private void showTheKadScreen() throws Exception {
        this.test = TestGui.start(
                this.dir.resolve("njmule"),
                real -> Engines.overriding(
                        real, Map.of("status", args -> CompletableFuture.completedFuture(this.status.get()))));
        this.screen = FxTesting.onFx(() -> new KadScreen(this.test.gui));
        this.test.show(FxTesting.onFx(this.screen::view));
        status(this.status.get());
    }

    private void status(EngineStatus next) throws Exception {
        this.status.set(next);
        FxTesting.onFx(this.test.model::tick);
        FxTesting.await(
                "the state",
                () -> Objects.equals(this.test.model.statusProperty().get(), next));
    }
}
