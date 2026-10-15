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
    private boolean closed;
    private Stage stage;
    private Path config;
    private boolean delivered;
    private boolean mayExit;
    private TrayMenu menu;
    private final List<Stage> trayOwners = new ArrayList<>();
    private final AtomicBoolean minimised = new AtomicBoolean();
    private final List<String> told = new CopyOnWriteArrayList<>();
    private final AtomicReference<String> asked = new AtomicReference<>();
    private ContextMenu windowMenu;
    private Stage other;
    private ServerSocket taken;

    @BeforeAll
    static void toolkit() throws Exception {
        FxTesting.start();
    }

    @AfterEach
    void close() throws Exception {
        if (this.other != null) {
            FxTesting.onFx(this.other::close);
        }
        if (this.stage != null) {
            FxTesting.onFx(this.stage::close);
        }
        if (this.app != null && !this.closed) {
            this.app.close();
        }
        if (this.taken != null) {
            this.taken.close();
        }
    }

    @Test
    void shouldFollowTheLinksOfTheCommandLineAndOfLaterStarts() throws Exception {
        givenAnAppWithLinks(FILE_LINK, "ed2k://|server|81.2.3.4|4661|/");
        givenTheWindowShowed();
        givenTheWindowTold("Download added: test file.bin", "81.2.3.4:4661 added to the server list");
        givenTheDownloadsOfTheEngineAre("test file.bin");
        givenALaterStartFindsTheConfigDirectory();

        whenALaterStartHandsItsLinksOver(
                "ed2k://|serverlist|http://127.0.0.1:9/server.met|/",
                "ed2k://|nodeslist|http://127.0.0.1:9/nodes.dat|/",
                "ed2k://|serverlist|http://bad host/server.met|/",
                "ed2k://|search|film|/");

        thenTheLinksWereDelivered();
        thenTheWindowTellsTheListsRefusedAndTheBadAddress();
        thenTheWindowStillShows();
    }

    @Test
    void shouldAskBeforeExitingUnlessTheUserSaidNotTo() throws Exception {
        givenAnAppWithoutLinks();
        givenTheWindowShowed();
        givenTheUserRefusedToExit();
        givenTheUserRefusedToCloseTheWindow();
        givenTheWindowStillShows();
        givenTheUserExitedSayingNotToAskAgain();
        givenTheSettingsNoLongerConfirmExit();

        whenTheAppIsAskedToExit();

        thenTheAppMayExitWithoutAsking();
    }

    @Test
    void shouldStartTheWebInterfaceBesideTheWindowWhenItsPortIsFree() throws Exception {
        givenTheWebInterfaceOnAFreePort();

        whenTheAppOpens();

        thenTheWebInterfaceRuns();
    }

    @Test
    void shouldNotStartTheWebInterfaceWhenItsPortIsTaken() throws Exception {
        givenTheWebInterfaceOnATakenPort();

        whenTheAppOpens();

        thenTheWebInterfaceDidNotStartAsThePortIsTaken();
    }

    @Test
    void shouldStartAWindowMinimisedWhereItWasLeft() throws Exception {
        givenAnAppWithTheInterfaceSettings("""
                window.startMinimized=true
                window.minimizeToTray=false
                window.x=10
                window.y=20
                window.width=900
                window.height=700
                """);
        givenAWindowWhoseMinimisingIsWatched();

        whenTheAppShowsTheWindow();

        thenTheWindowWasAskedToMinimise();
        thenTheWindowIsAt(10.0, 20.0);
        thenTheWindowIsAtLeastWide(900);
    }

    @Test
    void shouldOpenTheOptionsAndAskBeforeExitingFromTheTrayMenuThenCloseItsWindows() throws Exception {
        givenAnAppWithoutLinks();
        givenTheWindowShowedWithTheTray();
        givenTheTrayMenu();
        givenTheTrayItemPressed("Options");
        givenTheCurrentScreenIs("settings");
        givenTheTrayItemPressed("Restore");
        givenTheUserRefusedToExitFromTheTray();
        givenTheEngineRuns();
        givenTheWindowStillShows();
        givenTheTrayOwnerNoted();
        givenTheAppRelaunched();
        givenTheTrayMenuWasReplaced();
        givenTheTrayOwnerNoted();

        whenTheWindowCloses();

        thenTheTraysWindowsWentWithTheMainOne();
    }

    @Test
    void shouldDownloadACopiedLinkOnceTheUserSaysSo() throws Exception {
        givenAnAppWithTheInterfaceSettings("links.watchClipboard=true\n");
        givenTheWindowShowed();
        givenWhatTheWindowTellsWhileHiddenIsNoted();
        givenTheClipboardWatchStarted();
        givenTheUserWillSayYes();

        whenLinksAreCopied(11);

        thenTheModelHasDownloads(11);
        thenTheUserWasToldWhileHidden("Download added: part1.bin", "Download added: part11.bin");
        thenTheQuestionListedTheFiles("part1.bin\npart2.bin", "part10.bin\n…");
    }

    @Test
    void shouldCloseOnlyItsOwnWindowsWhenTheWindowCloses() throws Exception {
        givenAnAppWithoutLinks();
        givenTheWindowShowed();
        givenAMenuOfTheWindowShows();
        givenAnotherWindowShows();

        whenTheWindowCloses();

        thenTheMenuOfTheWindowIsClosed();
        thenTheOtherWindowStillShows();
    }

    @Test
    void shouldFollowTheLinksMacOsOpenedBeforeAndAfterTheWindowShowed() throws Exception {
        givenAnAppWithoutLinks();
        givenMacOsOpened(FILE_LINK);
        givenTheWindowShowed();

        whenMacOsOpens("ed2k://|server|81.2.3.4|4661|/");

        thenTheWindowTells("Download added: test file.bin", "81.2.3.4:4661 added to the server list");
    }

    @Test
    void shouldCloseAnAppNeverShownToo() throws Exception {
        givenAnAppWithoutInterfaceSettings();

        whenTheAppCloses();

        thenTheEngineIsStopped();
        thenTheAppHasNoWebInterface();
    }

    private void givenAnAppWithoutLinks() throws Exception {
        this.app = open(this.dir.resolve("njmule"), ENGLISH);
    }

    private void givenAnAppWithLinks(String... links) throws Exception {
        this.app = open(this.dir.resolve("njmule"), ENGLISH, links);
    }

    private void givenAnAppWithTheInterfaceSettings(String gui) throws Exception {
        this.app = open(this.dir.resolve("njmule"), ENGLISH + gui);
    }

    private void givenAnAppWithoutInterfaceSettings() throws Exception {
        this.app = open(this.dir.resolve("njmule"), null);
    }

    private void givenMacOsOpened(String link) {
        MacLinks.SYSTEM.offer(link);
    }

    private void givenTheWindowShowed() throws Exception {
        show(false);
    }

    private void givenTheWindowShowedWithTheTray() throws Exception {
        show(true);
    }

    private void givenTheWindowTold(String... messages) throws Exception {
        FxTesting.await("the messages", () -> messages().containsAll(List.of(messages)));
    }

    private void givenTheDownloadsOfTheEngineAre(String... names) throws Exception {
        assertThat(this.app.engine().downloads().get(10, TimeUnit.SECONDS))
                .extracting(DownloadInfo::name)
                .containsExactly(names);
    }

    private void givenALaterStartFindsTheConfigDirectory() throws Exception {
        Path root = this.dir.resolve("njmule");
        this.config = DesktopApp.configDir(LaunchOptions.parse("--config-dir", root.toString()));
        assertThat(this.config).isEqualTo(root.resolve("config"));
    }

    private void givenTheUserRefusedToExit() throws Exception {
        FxTesting.onFx(() -> {
            FxTesting.answer(ButtonType.NO);
            assertThat(this.app.mayExit(this.stage)).isFalse();
        });
    }

    // The window's close button asks too.
    private void givenTheUserRefusedToCloseTheWindow() throws Exception {
        FxTesting.onFx(() -> {
            FxTesting.answer(ButtonType.NO);
            this.stage.fireEvent(new WindowEvent(this.stage, WindowEvent.WINDOW_CLOSE_REQUEST));
        });
    }

    private void givenTheWindowStillShows() throws Exception {
        assertThat(FxTesting.onFx(this.stage::isShowing)).isTrue();
    }

    private void givenTheUserExitedSayingNotToAskAgain() throws Exception {
        FxTesting.onFx(() -> {
            FxTesting.answer(ButtonType.YES, pane -> ((CheckBox) pane.lookup(".check-box")).setSelected(true));
            assertThat(this.app.mayExit(this.stage)).isTrue();
        });
    }

    private void givenTheSettingsNoLongerConfirmExit() {
        assertThat(GuiSettings.load(this.dir.resolve("njmule").resolve("gui")).confirmExit())
                .isFalse();
    }

    private void givenTheWebInterfaceOnAFreePort() throws Exception {
        new WebUiSettings(true, freePort(), false, "hash")
                .save(this.dir.resolve("njmule").resolve("config"));
    }

    private void givenTheWebInterfaceOnATakenPort() throws Exception {
        this.taken = new ServerSocket(0);
        new WebUiSettings(true, this.taken.getLocalPort(), false, "hash")
                .save(this.dir.resolve("njmule").resolve("config"));
    }

    // The headless platform keeps every window shown: what counts is that it was asked to minimise.
    private void givenAWindowWhoseMinimisingIsWatched() throws Exception {
        FxTesting.onFx(() -> {
            this.stage = new Stage();
            this.stage.iconifiedProperty().addListener((o, was, now) -> this.minimised.compareAndSet(false, now));
        });
    }

    private void givenTheTrayMenu() throws Exception {
        this.menu = FxTesting.onFx(this.app::trayMenu);
        assertThat(this.menu).isNotNull();
    }

    private void givenTheTrayItemPressed(String item) throws Exception {
        press(this.menu, item);
    }

    private void givenTheCurrentScreenIs(String screen) throws Exception {
        assertThat(FxTesting.onFx(() -> this.app.window().current())).isEqualTo(screen);
    }

    private void givenTheUserRefusedToExitFromTheTray() throws Exception {
        FxTesting.onFx(() -> {
            FxTesting.answer(ButtonType.NO);
            pressNow(this.menu, "Exit");
        });
    }

    private void givenTheEngineRuns() {
        assertThat(this.app.engine().isRunning()).isTrue();
    }

    private void givenTheTrayOwnerNoted() throws Exception {
        this.trayOwners.add(FxTesting.onFx(this.app::trayOwner));
    }

    private void givenTheAppRelaunched() throws Exception {
        FxTesting.onFx(this.app::relaunch);
    }

    private void givenTheTrayMenuWasReplaced() throws Exception {
        assertThat(FxTesting.onFx(this.app::trayMenu)).isNotSameAs(this.menu);
    }

    private void givenWhatTheWindowTellsWhileHiddenIsNoted() throws Exception {
        FxTesting.onFx(() -> this.app.window().whileHidden(this.told::add));
    }

    // The watch first notes what the clipboard held before: it looks once a second, as the window's timer
    // that adds a rate each time, which started just before it.
    private void givenTheClipboardWatchStarted() throws Exception {
        FxTesting.await("two ticks", () -> this.app.model().rates().size() >= 3);
    }

    private void givenTheUserWillSayYes() {
        FxTesting.answerLater(ButtonType.YES, pane -> this.asked.set(pane.getContentText()));
    }

    private void givenAMenuOfTheWindowShows() throws Exception {
        FxTesting.onFx(() -> {
            this.windowMenu = new ContextMenu(new MenuItem("item"));
            this.windowMenu.show(this.stage);
        });
    }

    private void givenAnotherWindowShows() throws Exception {
        FxTesting.onFx(() -> {
            this.other = new Stage();
            this.other.show();
        });
    }

    private void whenALaterStartHandsItsLinksOver(String... links) throws Exception {
        this.delivered = LinkHandoff.deliver(this.config, List.of(links));
    }

    private void whenTheAppIsAskedToExit() throws Exception {
        this.mayExit = FxTesting.onFx(() -> this.app.mayExit(this.stage));
    }

    private void whenTheAppOpens() throws Exception {
        this.app = open(this.dir.resolve("njmule"), ENGLISH);
    }

    private void whenTheAppShowsTheWindow() throws Exception {
        FxTesting.onFx(() -> this.app.show(this.stage, false));
    }

    private void whenTheWindowCloses() throws Exception {
        FxTesting.onFx(this.stage::close);
    }

    private void whenLinksAreCopied(int count) throws Exception {
        List<String> links = IntStream.rangeClosed(1, count)
                .mapToObj(i -> "ed2k://|file|part" + i + ".bin|1000|" + "%032X".formatted(i) + "|/")
                .toList();
        FxTesting.onFx(() -> {
            ClipboardContent content = new ClipboardContent();
            content.putString(String.join("\n", links));
            Clipboard.getSystemClipboard().setContent(content);
        });
    }

    private void whenMacOsOpens(String link) {
        MacLinks.SYSTEM.offer(link);
    }

    private void whenTheAppCloses() {
        this.closed = true;
        this.app.close();
    }

    private void thenTheLinksWereDelivered() {
        assertThat(this.delivered).isTrue();
    }

    private void thenTheWindowTellsTheListsRefusedAndTheBadAddress() throws Exception {
        FxTesting.await(
                "the lists refused and the bad address",
                () -> messages().stream()
                                        .filter(m -> m.contains("local or private"))
                                        .count()
                                == 2
                        && messages().stream().anyMatch(m -> m.contains("Illegal character")));
    }

    private void thenTheWindowStillShows() throws Exception {
        assertThat(FxTesting.onFx(this.stage::isShowing)).isTrue();
    }

    private void thenTheAppMayExitWithoutAsking() {
        assertThat(this.mayExit).as("not asked again").isTrue();
    }

    private void thenTheWebInterfaceRuns() {
        assertThat(this.app.webUi()).isPresent();
    }

    private void thenTheWebInterfaceDidNotStartAsThePortIsTaken() {
        assertThat(this.app.webUi()).as("the port is taken").isEmpty();
    }

    private void thenTheWindowWasAskedToMinimise() {
        assertThat(this.minimised).isTrue();
    }

    private void thenTheWindowIsAt(double x, double y) throws Exception {
        assertThat(FxTesting.onFx(() -> List.of(this.stage.getX(), this.stage.getY())))
                .containsExactly(x, y);
    }

    // The size grows only as far as the buttons need.
    private void thenTheWindowIsAtLeastWide(double width) throws Exception {
        assertThat(FxTesting.onFx(this.stage::getWidth)).isGreaterThanOrEqualTo(width);
    }

    // Only this app's windows: those an earlier test left open in the same JVM are not its own.
    private void thenTheTraysWindowsWentWithTheMainOne() throws Exception {
        List<Window> owners = new ArrayList<>(this.trayOwners);
        owners.add(this.stage);
        assertThat(FxTesting.onFx(() -> Window.getWindows().stream()
                        .filter(Window::isShowing)
                        .filter(w -> w instanceof Stage s && owners.contains(s.getOwner()))
                        .toList()))
                .as("the tray's windows went with the main one")
                .isEmpty();
    }

    private void thenTheModelHasDownloads(int count) throws Exception {
        FxTesting.await("the downloads", () -> this.app.model().downloads().size() == count);
    }

    private void thenTheUserWasToldWhileHidden(String... messages) {
        assertThat(this.told).contains(messages);
    }

    private void thenTheQuestionListedTheFiles(String first, String last) {
        assertThat(this.asked.get()).startsWith(first).endsWith(last);
    }

    private void thenTheMenuOfTheWindowIsClosed() throws Exception {
        assertThat(FxTesting.onFx(this.windowMenu::isShowing)).isFalse();
    }

    private void thenTheOtherWindowStillShows() throws Exception {
        assertThat(FxTesting.onFx(this.other::isShowing)).as("not ours").isTrue();
    }

    private void thenTheWindowTells(String... messages) throws Exception {
        FxTesting.await("the messages", () -> messages().containsAll(List.of(messages)));
    }

    private void thenTheEngineIsStopped() {
        assertThat(this.app.engine().isRunning()).isFalse();
    }

    private void thenTheAppHasNoWebInterface() {
        assertThat(this.app.webUi()).isEmpty();
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
        FxTesting.onFx(() -> pressNow(menu, item));
    }

    // Opens the menu and presses one of its items; FX thread. The menu of an app of an earlier test may still show.
    private void pressNow(TrayMenu menu, String item) {
        menu.show(600, 500);
        Parent root = Window.getWindows().stream()
                .filter(w -> w.isShowing() && w.getScene() != null)
                .filter(w -> w instanceof Stage s && this.app.trayOwner().equals(s.getOwner()))
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
