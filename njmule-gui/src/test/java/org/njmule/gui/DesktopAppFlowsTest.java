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

import atlantafx.base.controls.Notification;
import java.net.DatagramSocket;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.stage.WindowEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.app.LaunchOptions;
import org.njmule.app.LinkHandoff;
import org.njmule.core.api.DownloadInfo;
import org.njmule.gui.infra.GuiSettings;
import org.njmule.gui.shell.TrayMenu;
import org.njmule.webui.WebUiSettings;

/** The desktop app around a real engine: links handed over, exit, the web interface and the window's settings. */
@Timeout(90)
class DesktopAppFlowsTest {

    private static final String FILE_LINK = "ed2k://|file|test%20file.bin|10000|0123456789ABCDEF0123456789ABCDEF|/";
    private static final String ENGLISH = "language=en\n";

    @TempDir
    Path dir;

    private DesktopApp app;
    private Stage stage;

    @BeforeAll
    static void toolkit() throws Exception {
        FxTesting.start();
    }

    @AfterEach
    void close() throws Exception {
        if (this.stage != null) {
            FxTesting.onFx(this.stage::close);
        }
        if (this.app != null) {
            this.app.close();
        }
    }

    @Test
    void linksOfTheCommandLineAndOfLaterStartsAreFollowed() throws Exception {
        Path root = this.dir.resolve("njmule");
        this.app = this.open(root, ENGLISH, FILE_LINK, "ed2k://|server|81.2.3.4|4661|/");
        this.show(false);

        FxTesting.await(
                "the download and the server",
                () -> this.messages()
                        .containsAll(
                                List.of("Download added: test file.bin", "81.2.3.4:4661 added to the server list")));
        assertThat(this.app.engine().downloads().get(10, TimeUnit.SECONDS))
                .extracting(DownloadInfo::name)
                .containsExactly("test file.bin");

        // A later start hands its links over.
        Path config = DesktopApp.configDir(LaunchOptions.parse("--config-dir", root.toString()));
        assertThat(config).isEqualTo(root.resolve("config"));
        assertThat(LinkHandoff.deliver(
                        config,
                        List.of(
                                "ed2k://|serverlist|http://127.0.0.1:9/server.met|/",
                                "ed2k://|nodeslist|http://127.0.0.1:9/nodes.dat|/",
                                "ed2k://|serverlist|http://bad host/server.met|/",
                                "ed2k://|search|film|/")))
                .isTrue();
        FxTesting.await(
                "the lists refused and the bad address",
                () -> this.messages().stream()
                                        .filter(m -> m.contains("local or private"))
                                        .count()
                                == 2
                        && this.messages().stream().anyMatch(m -> m.contains("Illegal character")));
        assertThat(FxTesting.onFx(this.stage::isShowing)).isTrue();
    }

    @Test
    void exitIsAskedFirstUnlessTheUserSaidNotTo() throws Exception {
        this.app = this.open(this.dir.resolve("njmule"), ENGLISH);
        this.show(false);

        FxTesting.onFx(() -> {
            FxTesting.answer(ButtonType.NO);
            assertThat(this.app.mayExit(this.stage)).isFalse();
            // The window's close button asks too.
            FxTesting.answer(ButtonType.NO);
            this.stage.fireEvent(new WindowEvent(this.stage, WindowEvent.WINDOW_CLOSE_REQUEST));
        });
        assertThat(FxTesting.onFx(this.stage::isShowing)).isTrue();

        FxTesting.onFx(() -> {
            FxTesting.answer(ButtonType.YES, pane -> ((CheckBox) pane.lookup(".check-box")).setSelected(true));
            assertThat(this.app.mayExit(this.stage)).isTrue();
        });
        assertThat(GuiSettings.load(this.dir.resolve("njmule").resolve("gui")).confirmExit())
                .isFalse();
        assertThat(FxTesting.onFx(() -> this.app.mayExit(this.stage)))
                .as("not asked again")
                .isTrue();
    }

    @Test
    void theWebInterfaceStartsBesideTheWindowWhenItsPortIsFree() throws Exception {
        Path first = this.dir.resolve("first");
        new WebUiSettings(true, freePort(), false, "hash").save(first.resolve("config"));
        DesktopApp served = this.open(first, ENGLISH);
        try {
            assertThat(served.webUi()).isPresent();
        } finally {
            served.close();
        }

        Path second = this.dir.resolve("second");
        try (ServerSocket taken = new ServerSocket(0)) {
            new WebUiSettings(true, taken.getLocalPort(), false, "hash").save(second.resolve("config"));
            this.app = this.open(second, ENGLISH);
            assertThat(this.app.webUi()).as("the port is taken").isEmpty();
        }
    }

    @Test
    void aWindowStartsMinimisedWhereItWasLeft() throws Exception {
        this.app = this.open(
                this.dir.resolve("njmule"),
                ENGLISH
                        + "window.startMinimized=true\nwindow.minimizeToTray=false\n"
                        + "window.x=10\nwindow.y=20\nwindow.width=900\nwindow.height=700\n");
        AtomicBoolean minimised = new AtomicBoolean();
        FxTesting.onFx(() -> {
            this.stage = new Stage();
            // The headless platform keeps every window shown: what counts is that it was asked to minimise.
            this.stage.iconifiedProperty().addListener((o, was, now) -> minimised.compareAndSet(false, now));
            this.app.show(this.stage, false);
        });

        assertThat(minimised).isTrue();
        // The size grows only as far as the buttons need.
        assertThat(FxTesting.onFx(() -> List.of(this.stage.getX(), this.stage.getY())))
                .containsExactly(10.0, 20.0);
        assertThat(FxTesting.onFx(this.stage::getWidth)).isGreaterThanOrEqualTo(900);
    }

    @Test
    void theTrayMenuOpensTheOptionsAndAsksBeforeExiting() throws Exception {
        this.app = this.open(this.dir.resolve("njmule"), ENGLISH);
        this.show(true);
        TrayMenu menu = FxTesting.onFx(this.app::trayMenu);
        assertThat(menu).isNotNull();

        this.press(menu, "Options");
        assertThat(FxTesting.onFx(() -> this.app.window().current())).isEqualTo("settings");
        this.press(menu, "Restore");
        FxTesting.onFx(() -> {
            FxTesting.answer(ButtonType.NO);
            this.pressNow(menu, "Exit");
        });
        assertThat(this.app.engine().isRunning()).isTrue();
        assertThat(FxTesting.onFx(this.stage::isShowing)).isTrue();

        FxTesting.onFx(this.app::relaunch);
        assertThat(FxTesting.onFx(this.app::trayMenu)).isNotSameAs(menu);
        FxTesting.onFx(this.stage::close);
        assertThat(FxTesting.onFx(() -> Window.getWindows().stream()
                        .filter(Window::isShowing)
                        .filter(w -> w instanceof Stage s && s.getOwner() != null)
                        .toList()))
                .as("the tray's windows went with the main one")
                .isEmpty();
    }

    @Test
    void aCopiedLinkIsDownloadedOnceTheUserSaysSo() throws Exception {
        this.app = this.open(this.dir.resolve("njmule"), ENGLISH + "links.watchClipboard=true\n");
        this.show(false);
        List<String> told = new CopyOnWriteArrayList<>();
        FxTesting.onFx(() -> this.app.window().whileHidden(told::add));
        // The watch first notes what the clipboard held before: it looks once a second, as the window's timer
        // that adds a rate each time, which started just before it.
        FxTesting.await("two ticks", () -> this.app.model().rates().size() >= 3);
        List<String> links = IntStream.rangeClosed(1, 11)
                .mapToObj(i -> "ed2k://|file|part" + i + ".bin|1000|" + "%032X".formatted(i) + "|/")
                .toList();
        AtomicReference<String> asked = new AtomicReference<>();
        FxTesting.answerLater(ButtonType.YES, pane -> asked.set(pane.getContentText()));
        FxTesting.onFx(() -> {
            ClipboardContent content = new ClipboardContent();
            content.putString(String.join("\n", links));
            Clipboard.getSystemClipboard().setContent(content);
        });

        FxTesting.await("the downloads", () -> this.app.model().downloads().size() == 11);
        assertThat(told).contains("Download added: part1.bin", "Download added: part11.bin");
        assertThat(asked.get()).startsWith("part1.bin\npart2.bin").endsWith("part10.bin\n…");
    }

    @Test
    void closingTheWindowClosesItsOwnWindowsOnly() throws Exception {
        this.app = this.open(this.dir.resolve("njmule"), ENGLISH);
        this.show(false);
        AtomicReference<ContextMenu> menu = new AtomicReference<>();
        AtomicReference<Stage> other = new AtomicReference<>();
        FxTesting.onFx(() -> {
            menu.set(new ContextMenu(new MenuItem("item")));
            menu.get().show(this.stage);
            other.set(new Stage());
            other.get().show();
            this.stage.close();
        });
        try {
            assertThat(FxTesting.onFx(menu.get()::isShowing)).isFalse();
            assertThat(FxTesting.onFx(other.get()::isShowing)).as("not ours").isTrue();
        } finally {
            FxTesting.onFx(other.get()::close);
        }
    }

    @Test
    void anAppNeverShownClosesToo() throws Exception {
        DesktopApp unseen = this.open(this.dir.resolve("njmule"), null);
        unseen.close();
        assertThat(unseen.engine().isRunning()).isFalse();
        assertThat(unseen.webUi()).isEmpty();
    }

    // An app in its own folder, on free ports and without Kad, with these interface settings.
    private DesktopApp open(Path root, String gui, String... links) throws Exception {
        Path config = Files.createDirectories(root.resolve("config"));
        int udp;
        try (DatagramSocket probe = new DatagramSocket(0)) {
            udp = probe.getLocalPort();
        }
        Files.writeString(
                config.resolve("njmule.properties"),
                "net.tcpPort=" + freePort() + "\nnet.udpPort=" + udp + "\nnet.kad.enabled=false\n");
        if (gui != null) {
            Files.writeString(Files.createDirectories(root.resolve("gui")).resolve(GuiSettings.FILE_NAME), gui);
        }
        List<String> args = new ArrayList<>(List.of("--config-dir", root.toString()));
        args.addAll(List.of(links));
        return DesktopApp.open(LaunchOptions.parse(args.toArray(String[]::new)));
    }

    private void show(boolean tray) throws Exception {
        FxTesting.onFx(() -> {
            this.stage = new Stage();
            this.app.show(this.stage, tray);
        });
    }

    // The messages floating over the window now.
    private List<String> messages() {
        return this.app.window().root().lookupAll(".notification").stream()
                .map(n -> ((Notification) n).getMessage())
                .toList();
    }

    private void press(TrayMenu menu, String item) throws Exception {
        FxTesting.onFx(() -> this.pressNow(menu, item));
    }

    // Opens the menu and presses one of its items; FX thread.
    private void pressNow(TrayMenu menu, String item) {
        menu.show(600, 500);
        Parent root = Window.getWindows().stream()
                .filter(w -> w.isShowing() && w.getScene() != null)
                .map(w -> w.getScene().getRoot())
                .filter(r -> r.getStyleClass().contains("tray-menu"))
                .findFirst()
                .orElseThrow();
        for (Node node : root.lookupAll(".tray-item")) {
            if (node instanceof Button button && item.equals(button.getText())) {
                button.fire();
                return;
            }
        }
        throw new AssertionError("no item " + item);
    }

    private static int freePort() throws Exception {
        try (ServerSocket probe = new ServerSocket(0)) {
            return probe.getLocalPort();
        }
    }
}
