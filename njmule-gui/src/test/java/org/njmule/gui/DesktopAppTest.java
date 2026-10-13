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

import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.app.LaunchOptions;
import org.njmule.gui.infra.GuiSettings;

@Timeout(60)
class DesktopAppTest {

    @TempDir Path dir;

    @BeforeAll
    static void toolkit() throws Exception {
        FxTesting.start();
    }

    @Test
    void opensShowsRemembersTheWindowAndCloses() throws Exception {
        Path config = Files.createDirectories(this.dir.resolve("config"));
        int port;
        try (ServerSocket probe = new ServerSocket(0)) {
            port = probe.getLocalPort();
        }
        Files.writeString(
                config.resolve("njmule.properties"),
                "net.tcpPort=" + port + "\nnet.kad.enabled=false\n");
        DesktopApp app = DesktopApp.open(LaunchOptions.parse("--config-dir", this.dir.toString()));
        AtomicReference<Stage> stage = new AtomicReference<>();
        try {
            FxTesting.onFx(
                    () -> {
                        stage.set(new Stage());
                        app.show(stage.get());
                    });
            assertThat(FxTesting.onFx(() -> stage.get().getTitle())).startsWith("njMule");
            assertThat(FxTesting.onFx(() -> app.window().current())).isEqualTo("transfers");
            FxTesting.onFx(
                    () -> {
                        app.window().show("search");
                        app.relaunch();
                    });
            assertThat(FxTesting.onFx(() -> app.window().current())).isEqualTo("search");
            assertThat(app.engine().isRunning()).isTrue();
            FxTesting.onFx(() -> stage.get().close());
        } finally {
            app.close();
        }
        assertThat(app.engine().isRunning()).isFalse();
        GuiSettings saved = GuiSettings.load(this.dir.resolve("gui"));
        assertThat(saved.existed()).isTrue();
        assertThat(saved.screen()).isEqualTo("search");
        assertThat(saved.window()).isPresent();
    }
}
