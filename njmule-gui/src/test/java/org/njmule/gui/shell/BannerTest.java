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
package org.njmule.gui.shell;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Optional;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.njmule.core.api.EngineStatus;
import org.njmule.core.api.KadInfo;
import org.njmule.gui.FxTesting;
import org.njmule.gui.Statuses;
import org.njmule.gui.TestGui;
import org.njmule.gui.model.EngineModel;

@Timeout(60)
class BannerTest {

    @TempDir
    Path dir;

    private TestGui test;
    private MainWindow window;
    private Banner.Kind kind;

    @AfterEach
    void close() {
        if (this.test != null) {
            this.test.close();
        }
    }

    @ParameterizedTest
    @CsvSource({
        "DISCONNECTED, false, none, OFFLINE",
        "CONNECTING, false, none, NONE",
        "DISCONNECTED, false, bootstrapping, NONE",
        "CONNECTED, false, none, NONE",
        "CONNECTED, true, none, UNREACHABLE",
        "CONNECTED, true, open, NONE",
        "CONNECTED, false, firewalled, NONE",
        "DISCONNECTED, false, firewalled, UNREACHABLE",
        "DISCONNECTED, false, open, NONE"
    })
    void theBannerTellsOfNoNetworkAndOfANetworkThatCannotReachUs(
            EngineModel.Connection connection, boolean lowId, String kad, Banner.Kind expected) {
        whenTheStateIs(connection, lowId, kad);
        thenTheBannerTellsOf(expected);
    }

    @Test
    void offlineTheBannerOffersToConnectAndOnceClosedStaysAway() throws Exception {
        givenTheWindowOffline();
        thenTheBannerShowsAfterAMoment(Banner.Kind.OFFLINE, "Connect");
        whenTheBannerIsClosed();
        thenTheBannerIsGone();
    }

    private void givenTheWindowOffline() throws Exception {
        this.test = TestGui.start(this.dir.resolve("njmule"));
        this.window = FxTesting.onFx(() -> new MainWindow(this.test.gui));
        this.test.show(this.window.root());
    }

    private void whenTheStateIs(EngineModel.Connection connection, boolean lowId, String kad) {
        Optional<KadInfo> info = switch (kad) {
            case "bootstrapping" -> Optional.of(Statuses.kad(false, false, ""));
            case "firewalled" -> Optional.of(Statuses.kad(true, true, "1.2.3.4"));
            case "open" -> Optional.of(Statuses.kad(true, false, "1.2.3.4"));
            default -> Optional.empty();
        };
        EngineStatus status = Statuses.of(Optional.empty(), 1, lowId, info, false);
        this.kind = Banner.kindOf(connection, status);
    }

    private void whenTheBannerIsClosed() throws Exception {
        FxTesting.onFx(() -> ((Button) this.window.banner().view().getChildren().getLast()).fire());
    }

    private void thenTheBannerTellsOf(Banner.Kind expected) {
        assertThat(this.kind).isEqualTo(expected);
    }

    private void thenTheBannerShowsAfterAMoment(Banner.Kind expected, String action) throws Exception {
        FxTesting.await("the banner", () -> this.window.banner().shown() == expected);
        assertThat(FxTesting.onFx(() -> this.window.banner().view().isVisible()))
                .isTrue();
        assertThat(FxTesting.onFx(() -> this.window.banner().view().getChildren().stream()
                        .filter(Button.class::isInstance)
                        .map(n -> ((Button) n).getText())
                        .toList()))
                .contains(action);
        assertThat(FxTesting.onFx(() -> this.window.banner().view().getChildren().stream()
                        .filter(Label.class::isInstance)
                        .map(n -> ((Label) n).getText())
                        .toList()))
                .anyMatch(t -> t.startsWith("Not connected"));
    }

    private void thenTheBannerIsGone() throws Exception {
        assertThat(FxTesting.onFx(() -> this.window.banner().shown())).isEqualTo(Banner.Kind.NONE);
        assertThat(FxTesting.onFx(() -> this.window.banner().view().isManaged()))
                .isFalse();
    }
}
