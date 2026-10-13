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
import java.util.concurrent.atomic.AtomicReference;
import javafx.scene.Scene;
import javafx.stage.Stage;
import javafx.stage.Window;
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

    @Test
    void visitsEverythingThenEndsWithTheThemeItFound() throws Exception {
        try (TestGui test = TestGui.start(this.dir.resolve("njmule"))) {
            AtomicReference<Stage> stage = new AtomicReference<>();
            CountDownLatch done = new CountDownLatch(1);
            FxTesting.onFx(() -> {
                MainWindow window = new MainWindow(test.gui);
                Stage s = new Stage();
                Scene scene = new Scene(window.root(), 1100, 720);
                test.gui.themes().attach(scene);
                s.setScene(scene);
                s.show();
                stage.set(s);
                GuiTour.start(test.gui, window, s, List.of(), done::countDown);
            });
            assertThat(done.await(90, TimeUnit.SECONDS)).isTrue();
            assertThat(FxTesting.onFx(() -> test.gui.themes().family())).isEqualTo(Themes.Family.NORD);
            assertThat(FxTesting.onFx(() -> test.gui.themes().mode())).isEqualTo(GuiSettings.Mode.LIGHT);
            assertThat(FxTesting.onFx(() -> Window.getWindows().stream()
                            .filter(Window::isShowing)
                            .filter(w -> w instanceof Stage)
                            .count()))
                    .isEqualTo(1);
            assertThat(GuiTour.requested()).isFalse();
            FxTesting.onFx(() -> stage.get().close());
        }
    }
}
