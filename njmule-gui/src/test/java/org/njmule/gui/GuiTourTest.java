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
package org.njmule.gui;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javafx.scene.Scene;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.gui.infra.GuiSettings;
import org.njmule.gui.infra.Themes;
import org.njmule.gui.shell.MainWindow;

@Timeout(120)
class GuiTourTest {

    @TempDir
    Path dir;

    private final CountDownLatch done = new CountDownLatch(1);
    private TestGui test;
    private MainWindow window;
    private Stage stage;

    @AfterEach
    void close() throws Exception {
        if (this.stage != null) {
            FxTesting.onFx(() -> this.stage.close());
        }
        if (this.test != null) {
            this.test.close();
        }
    }

    @Test
    void shouldVisitEverythingThenEndWithTheThemeItFound() throws Exception {
        givenTheGui();
        givenTheMainWindowShown();

        whenTheTourStarts();

        thenTheTourEndsWithinSeconds(90);
        thenTheThemeIs(Themes.Family.NORD, GuiSettings.Mode.LIGHT);
        thenTheShowingStagesAre(1);
        thenTheTourIsNotRequested();
    }

    private void givenTheGui() throws Exception {
        this.test = TestGui.start(this.dir.resolve("njmule"));
    }

    private void givenTheMainWindowShown() throws Exception {
        FxTesting.onFx(() -> {
            this.window = new MainWindow(this.test.gui);
            Stage s = new Stage();
            Scene scene = new Scene(this.window.root(), 1100, 720);
            this.test.gui.themes().attach(scene);
            s.setScene(scene);
            s.show();
            this.stage = s;
        });
    }

    private void whenTheTourStarts() throws Exception {
        FxTesting.onFx(() -> GuiTour.start(this.test.gui, this.window, this.stage, List.of(), this.done::countDown));
    }

    private void thenTheTourEndsWithinSeconds(long seconds) throws InterruptedException {
        assertThat(this.done.await(seconds, TimeUnit.SECONDS)).isTrue();
    }

    private void thenTheThemeIs(Themes.Family family, GuiSettings.Mode mode) throws Exception {
        assertThat(FxTesting.onFx(() -> this.test.gui.themes().family())).isEqualTo(family);
        assertThat(FxTesting.onFx(() -> this.test.gui.themes().mode())).isEqualTo(mode);
    }

    private void thenTheShowingStagesAre(long count) throws Exception {
        assertThat(FxTesting.onFx(() -> Window.getWindows().stream()
                        .filter(Window::isShowing)
                        .filter(w -> w instanceof Stage)
                        .count()))
                .isEqualTo(count);
    }

    private void thenTheTourIsNotRequested() {
        assertThat(GuiTour.requested()).isFalse();
    }
}
