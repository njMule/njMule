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

import java.net.DatagramSocket;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javafx.geometry.Rectangle2D;
import javafx.scene.control.Label;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.app.LaunchOptions;
import org.njmule.core.api.EngineEvent;
import org.njmule.core.api.ServerInfo;
import org.njmule.gui.infra.GuiSettings;
import org.njmule.gui.shell.TrayMenu;

@Timeout(60)
class DesktopAppTest {

    @TempDir
    Path dir;

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
        Files.writeString(config.resolve("njmule.properties"), "net.tcpPort=" + port + "\nnet.kad.enabled=false\n");
        DesktopApp app = DesktopApp.open(LaunchOptions.parse("--config-dir", this.dir.toString()));
        AtomicReference<Stage> stage = new AtomicReference<>();
        List<Window> before = FxTesting.onFx(DesktopAppTest::showing);
        try {
            FxTesting.onFx(() -> {
                stage.set(new Stage());
                app.show(stage.get());
            });
            assertThat(FxTesting.onFx(() -> stage.get().getTitle())).startsWith("njMule");
            assertThat(FxTesting.onFx(() -> {
                        Stage dialog = new Stage();
                        dialog.initOwner(stage.get());
                        dialog.show();
                        int icons = dialog.getIcons().size();
                        dialog.close();
                        return icons;
                    }))
                    .as("a dialog shows the app's icon too")
                    .isPositive();
            assertThat(FxTesting.onFx(() -> app.window().current())).isEqualTo("transfers");
            TrayMenu menu = FxTesting.onFx(app::trayMenu);
            FxTesting.onFx(() -> {
                app.window().show("search");
                app.relaunch();
            });
            if (menu != null) {
                assertThat(FxTesting.onFx(app::trayMenu))
                        .as("the tray menu follows the language")
                        .isNotSameAs(menu);
            }
            assertThat(FxTesting.onFx(() -> app.window().current())).isEqualTo("search");
            assertThat(app.engine().isRunning()).isTrue();
            FxTesting.onFx(() -> stage.get().close());
            assertThat(FxTesting.onFx(DesktopAppTest::showing))
                    .as("no window of ours keeps njMule running")
                    .containsExactlyInAnyOrderElementsOf(before);
        } finally {
            app.close();
        }
        assertThat(app.engine().isRunning()).isFalse();
        GuiSettings saved = GuiSettings.load(this.dir.resolve("gui"));
        assertThat(saved.existed()).isTrue();
        assertThat(saved.screen()).isEqualTo("search");
        assertThat(saved.window()).isPresent();
    }

    @Test
    void aWindowPlaceOffEveryScreenIsLeftAndAnIconNeverSavesOne() throws Exception {
        Path gui = Files.createDirectories(this.dir.resolve("gui"));
        // As left by a monitor since unplugged.
        Files.writeString(
                gui.resolve(GuiSettings.FILE_NAME),
                "window.x=-50000\nwindow.y=-50000\nwindow.width=900\nwindow.height=700\n");
        DesktopApp app = this.open();
        AtomicReference<Stage> stage = new AtomicReference<>();
        try {
            FxTesting.onFx(() -> {
                stage.set(new Stage());
                app.show(stage.get());
            });
            Rectangle2D shown = FxTesting.onFx(() -> new Rectangle2D(
                    stage.get().getX(),
                    stage.get().getY(),
                    stage.get().getWidth(),
                    stage.get().getHeight()));
            assertThat(FxTesting.onFx(() -> Screen.getScreensForRectangle(shown)))
                    .as("the window shows on a screen")
                    .isNotEmpty();

            FxTesting.onFx(() -> {
                stage.get().setX(10);
                stage.get().setY(20);
            });
            assertThat(FxTesting.onFx(() -> stage.get().getX())).isEqualTo(10);
            // Minimised, the place a window reports is not where it shows: on Windows -32000.
            FxTesting.onFx(() -> {
                stage.get().setIconified(true);
                stage.get().close();
            });
        } finally {
            app.close();
        }
        assertThat(GuiSettings.load(gui).window())
                .as("the place before the window was minimised")
                .hasValueSatisfying(b -> assertThat(b[0]).isNotEqualTo(10));
    }

    @Test
    void theWindowLeftByALanguageChangeNoLongerFollowsTheEngine() throws Exception {
        DesktopApp app = this.open();
        AtomicReference<Stage> stage = new AtomicReference<>();
        try {
            FxTesting.onFx(() -> {
                stage.set(new Stage());
                app.show(stage.get());
            });
            Label oldChip = FxTesting.onFx(() -> (Label) app.window().root().lookup(".connection-chip"));
            String before = FxTesting.onFx(() -> oldChip.getText());

            FxTesting.onFx(app::relaunch);
            ServerInfo big = new ServerInfo(
                    "81.2.3.4:4661",
                    "Big",
                    "",
                    10,
                    20,
                    30,
                    0,
                    ServerInfo.Priority.NORMAL,
                    0,
                    0,
                    0,
                    0,
                    "",
                    false,
                    false);
            FxTesting.onFx(() -> app.model().handle(new EngineEvent.ServerConnecting(big)));

            Label newChip = FxTesting.onFx(() -> (Label) app.window().root().lookup(".connection-chip"));
            assertThat(FxTesting.onFx(() -> newChip.getText())).contains("Big");
            assertThat(FxTesting.onFx(() -> oldChip.getText()))
                    .as("the old window is not updated any more")
                    .isEqualTo(before);
            FxTesting.onFx(() -> stage.get().close());
        } finally {
            app.close();
        }
    }

    // An app in the test's folder, on free ports and without Kad: never on the ports of a running eMule.
    private DesktopApp open() throws Exception {
        Path config = Files.createDirectories(this.dir.resolve("config"));
        int port;
        try (ServerSocket probe = new ServerSocket(0)) {
            port = probe.getLocalPort();
        }
        int udp;
        try (DatagramSocket probe = new DatagramSocket(0)) {
            udp = probe.getLocalPort();
        }
        Files.writeString(
                config.resolve("njmule.properties"),
                "net.tcpPort=" + port + "\nnet.udpPort=" + udp + "\nnet.kad.enabled=false\n");
        return DesktopApp.open(LaunchOptions.parse("--config-dir", this.dir.toString()));
    }

    private static List<Window> showing() {
        return Window.getWindows().stream().filter(Window::isShowing).toList();
    }
}
