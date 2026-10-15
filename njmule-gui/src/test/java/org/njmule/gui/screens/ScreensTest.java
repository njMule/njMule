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

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TreeItem;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.core.api.Category;
import org.njmule.core.api.DownloadInfo;
import org.njmule.core.api.DownloadPriority;
import org.njmule.core.api.EngineEvent;
import org.njmule.core.api.Hash16;
import org.njmule.core.api.KadInfo;
import org.njmule.core.api.PartMap;
import org.njmule.core.api.SavedSearch;
import org.njmule.core.api.SearchRequest;
import org.njmule.core.api.SearchResult;
import org.njmule.core.api.ServerInfo;
import org.njmule.core.api.UploadPriority;
import org.njmule.core.config.ProxySettings;
import org.njmule.gui.FxTesting;
import org.njmule.gui.TestGui;
import org.njmule.gui.i18n.I18n;
import org.njmule.gui.infra.GuiSettings;
import org.njmule.webui.WebUiSettings;

@Timeout(120)
class ScreensTest {

    private static final String LINK = "ed2k://|file|test%20file.bin|10000|0123456789ABCDEF0123456789ABCDEF|/";

    @TempDir
    Path dir;

    private TestGui test;
    private SettingsScreen settings;
    private List<String> languageNames;
    private KadInfo kad;

    @AfterEach
    void close() throws Exception {
        if (this.test != null) {
            this.test.close();
        }
    }

    @Test
    void transfersAddPauseResumeCopyAndCancelDownloads() throws Exception {
        this.test = TestGui.start(this.dir.resolve("njmule"));
        TransfersScreen screen = new TransfersScreen(this.test.gui);
        this.test.show(FxTesting.onFx(screen::view));

        FxTesting.onFx(() -> {
            screen.linkField().setText(LINK);
            screen.addLink();
        });
        FxTesting.await("the download row", () -> screen.downloads().getItems().size() == 1);
        assertThat(FxTesting.onFx(() -> screen.linkField().getText())).isEmpty();

        FxTesting.onFx(() -> {
            screen.downloads().getSelectionModel().select(0);
            screen.pauseSelected();
        });
        FxTesting.await(
                "paused", () -> screen.downloads().getItems().getFirst().get().status() == DownloadInfo.Status.PAUSED);
        FxTesting.onFx(screen::stopSelected);
        FxTesting.await(
                "stopped",
                () -> screen.downloads().getItems().getFirst().get().status() == DownloadInfo.Status.STOPPED);
        FxTesting.onFx(() -> screen.prioritize(DownloadPriority.HIGH));
        FxTesting.await(
                "high priority",
                () -> screen.downloads().getItems().getFirst().get().priority() == DownloadPriority.HIGH);
        FxTesting.onFx(screen::resumeSelected);
        FxTesting.await(
                "downloading again",
                () -> screen.downloads().getItems().getFirst().get().status() == DownloadInfo.Status.DOWNLOADING);
        FxTesting.onFx(screen::copyLinks);
        assertThat(FxTesting.onFx(() -> Clipboard.getSystemClipboard().getString()))
                .startsWith("ed2k://|file|test%20file.bin|10000|");

        List<DownloadInfo> selected =
                FxTesting.onFx(() -> screen.downloads().getSelectionModel().getSelectedItems().stream()
                        .map(r -> r.get())
                        .toList());
        FxTesting.onFx(() -> screen.cancel(selected));
        FxTesting.await("no download", () -> screen.downloads().getItems().isEmpty());

        FxTesting.onFx(() -> {
            screen.linkField().setText("not a link");
            screen.addLink();
        });
        assertThat(this.test.told).contains("Not an eD2K file link.");
        assertThat(TransfersScreen.remaining(new DownloadInfo(
                        selected.getFirst().hash(),
                        "x",
                        1000,
                        400,
                        DownloadInfo.Status.DOWNLOADING,
                        DownloadPriority.AUTO,
                        0,
                        1,
                        1,
                        100,
                        PartMap.EMPTY)))
                .isEqualTo(6);
    }

    @Test
    void transfersShowTheDownloadsOfACategory() throws Exception {
        this.test = TestGui.start(this.dir.resolve("njmule"));
        TransfersScreen screen = new TransfersScreen(this.test.gui);
        this.test.show(FxTesting.onFx(screen::view));
        FxTesting.onFx(() -> this.test
                .gui
                .model()
                .setCategories(
                        List.of(
                                new Category(
                                        1, "Films", Optional.empty(), "", 1, DownloadPriority.AUTO, "*.mkv", false),
                                new Category(
                                        2, "Tests", Optional.empty(), "", 4, DownloadPriority.HIGH, "test", false)),
                        () -> {}));
        FxTesting.await(
                "the categories", () -> this.test.gui.model().categories().size() == 2);

        FxTesting.onFx(() -> {
            screen.linkField().setText(LINK);
            screen.addLink();
        });
        FxTesting.await("the download row", () -> screen.downloads().getItems().size() == 1);
        assertThat(FxTesting.onFx(
                        () -> screen.downloads().getItems().getFirst().get().category()))
                .as("its name matches the second category")
                .isEqualTo(2);
        FxTesting.onFx(() -> screen.showCategory(1));
        assertThat(FxTesting.onFx(() -> screen.downloads().getItems().size())).isZero();
        FxTesting.onFx(() -> screen.showCategory(2));
        assertThat(FxTesting.onFx(() -> screen.downloads().getItems().size())).isEqualTo(1);

        FxTesting.onFx(() -> {
            screen.downloads().getSelectionModel().select(0);
            screen.categorize(Category.NONE);
        });
        FxTesting.await(
                "out of the category", () -> screen.downloads().getItems().isEmpty());
        FxTesting.onFx(() -> {
            FxTesting.snapshot(screen.view(), "transfers-categories");
            return null;
        });
    }

    @Test
    void anIpFilterFromALocalAddressIsRefused() throws Exception {
        this.test = TestGui.start(this.dir.resolve("njmule"));
        SettingsScreen screen = new SettingsScreen(this.test.gui);
        this.test.show(FxTesting.onFx(screen::view));

        FxTesting.onFx(() -> {
            screen.ipFilterBox().node().getEditor().setText("http://127.0.0.1:9/ipfilter.zip");
            screen.updateIpFilter();
        });
        FxTesting.await("refused", () -> this.test.told.stream().anyMatch(m -> m.contains("local or private")));
        assertThat(this.test.gui.settings().history("ipFilter")).isEmpty();
    }

    @Test
    void aServerListFromTheWebIsRememberedOnlyOnceItWorked() throws Exception {
        this.test = TestGui.start(this.dir.resolve("njmule"));
        ServersScreen screen = new ServersScreen(this.test.gui);
        this.test.show(FxTesting.onFx(screen::view));

        FxTesting.onFx(() -> {
            screen.listBox().node().getEditor().setText("http://127.0.0.1:9/server.met");
            screen.updateFromUrl();
        });
        FxTesting.await("refused", () -> this.test.told.stream().anyMatch(m -> m.contains("local or private")));
        assertThat(this.test.gui.settings().history("serverMet"))
                .as("a failed address is not kept")
                .isEmpty();

        URI worked = URI.create("https://example.org/server.met");
        FxTesting.onFx(() -> screen.listBox().worked(worked));
        assertThat(FxTesting.onFx(() -> screen.listBox().node().getItems())).containsExactly(worked.toString());
        assertThat(GuiSettings.load(this.test.engine.directories().gui()).history("serverMet"))
                .containsExactly(worked.toString());
    }

    @Test
    void serversAddFromAFileRemoveAndRejectBadAddresses() throws Exception {
        this.test = TestGui.start(this.dir.resolve("njmule"));
        ServersScreen screen = new ServersScreen(this.test.gui);
        this.test.show(FxTesting.onFx(screen::view));
        Path met = this.dir.resolve("server.met");
        // Two public servers: version byte, count, then ip, port and no tags each.
        Files.write(met, new byte[] {
            (byte) 0xE0, 2, 0, 0, 0, 81, 2, 3, 4, 0x35, 0x12, 0, 0, 0, 0, 81, 2, 3, 5, 0x36, 0x12, 0, 0, 0, 0
        });

        FxTesting.onFx(() -> screen.addServerMet(met));
        FxTesting.await("two servers", () -> screen.table().getItems().size() == 2);
        assertThat(FxTesting.onFx(() -> screen.countLabel().getText())).isEqualTo("Servers (2)");
        assertThat(FxTesting.onFx(() -> screen.myInfo().lines()))
                .extracting(l -> (l.label() + " " + l.value()).strip())
                .containsExactly(
                        "eD2K Network",
                        "Status: Disconnected",
                        "Kad Network",
                        "Status: Disconnected",
                        "Web Interface",
                        "Status: Disabled");
        assertThat(FxTesting.onFx(() -> screen.table().getColumns().stream()
                        .filter(c -> !c.isVisible())
                        .map(c -> c.getText())
                        .toList()))
                .containsExactly("Hard File Limit", "Version");

        // The box narrows the list as one types.
        FxTesting.onFx(() -> screen.filterField().setText("2.3.5"));
        assertThat(FxTesting.onFx(() -> screen.table().getItems().size())).isEqualTo(1);
        assertThat(FxTesting.onFx(() -> screen.countLabel().getText())).isEqualTo("Servers (1/2)");
        FxTesting.onFx(() -> screen.filterField().clear());
        assertThat(FxTesting.onFx(() -> screen.table().getItems().size())).isEqualTo(2);

        // Static list, priority and links in the server menu.
        Path statics = this.test.engine.directories().config().resolve("staticservers.dat");
        FxTesting.onFx(() -> {
            screen.table().getSelectionModel().clearSelection();
            screen.table().getSelectionModel().select(0);
            screen.setStatic(true);
            screen.setPriority(ServerInfo.Priority.HIGH);
            screen.copyLinks();
        });
        String firstAddress =
                FxTesting.onFx(() -> screen.table().getItems().getFirst().get().address());
        FxTesting.await(
                "static and high",
                () -> screen.table().getItems().stream()
                        .map(r -> r.get())
                        .anyMatch(s -> s.address().equals(firstAddress)
                                && s.isStatic()
                                && s.priority() == ServerInfo.Priority.HIGH));
        assertThat(Files.exists(statics)).isTrue();
        String link = FxTesting.onFx(() -> Clipboard.getSystemClipboard().getString());
        assertThat(link).isEqualTo("ed2k://|server|" + firstAddress.replace(":", "|") + "|/");
        assertThat(ServersScreen.serverLinks("see " + link + " and ed2k://|server|5.6.7.8|4242|/ ed2k://|file|x|1|"
                        + "0123456789ABCDEF0123456789ABCDEF|/"))
                .hasSize(2);

        FxTesting.onFx(() -> {
            ClipboardContent content = new ClipboardContent();
            content.putString("ed2k://|server|81.2.3.6|4663|/");
            Clipboard.getSystemClipboard().setContent(content);
            screen.pasteLinks();
        });
        FxTesting.await("pasted", () -> screen.table().getItems().size() == 3);
        FxTesting.onFx(screen::removeAll);
        FxTesting.await("none left", () -> screen.table().getItems().isEmpty());
        FxTesting.onFx(() -> screen.addServerMet(met));
        FxTesting.await("two again", () -> screen.table().getItems().size() == 2);

        // Connect becomes Disconnect on the server we are on, and stays Connect on the others.
        FxTesting.onFx(() -> {
            ServerInfo first = screen.table().getItems().getFirst().get();
            this.test.model.handle(new EngineEvent.ServerConnected(first, 99, true, false));
            screen.table().getSelectionModel().select(0);
        });
        assertThat(FxTesting.onFx(() -> screen.connectButton().getText())).isEqualTo("Disconnect");
        FxTesting.onFx(() -> screen.table().getSelectionModel().select(1));
        assertThat(FxTesting.onFx(() -> screen.connectButton().getText())).isEqualTo("Connect");
        assertThat(FxTesting.onFx(() -> screen.logTabs().getTabs()))
                .extracting(t -> t.getText())
                .containsExactly("Server Info", "Log", "My Info");
        FxTesting.onFx(() -> this.test.model.handle(new EngineEvent.ServerDisconnected(Optional.empty(), "test")));
        FxTesting.onFx(() -> {
            screen.table().getSelectionModel().clearSelection();
            screen.table().getSelectionModel().select(0);
            screen.removeSelected();
        });
        FxTesting.await("one server", () -> screen.table().getItems().size() == 1);

        FxTesting.onFx(() -> {
            screen.addressField().setText("nowhere");
            screen.connectToAddress();
        });
        assertThat(this.test.told).anyMatch(m -> m.contains("host:port: nowhere"));
    }

    @Test
    void aSearchWithoutAServerIsRefused() throws Exception {
        this.test = TestGui.start(this.dir.resolve("njmule"));
        SearchScreen screen = new SearchScreen(this.test.gui);
        this.test.show(FxTesting.onFx(screen::view));

        FxTesting.onFx(() -> {
            screen.queryField().setText("ubuntu");
            screen.methodBox().setValue(SearchScreen.Method.SERVER);
            screen.typeBox().setValue(SearchScreen.FileType.VIDEO);
            screen.minSizeField().setText("100");
            screen.start();
            screen.more();
            screen.stop();
            screen.downloadSelected();
        });

        FxTesting.await("a refusal", () -> !this.test.told.isEmpty());
        assertThat(FxTesting.onFx(() -> screen.tabs().getTabs().size())).isZero();
        FxTesting.onFx(() -> {
            screen.queryField().setText("  ");
            screen.start();
        });
    }

    @Test
    void theSearchesLeftOpenComeBackWithTheirResults() throws Exception {
        this.test = TestGui.start(this.dir.resolve("njmule"));
        SearchResult film = new SearchResult(
                Hash16.parseHex("0123456789ABCDEF0123456789ABCDEF"), "film.mkv", 700_000_000, 4, 1, Optional.empty());
        this.test
                .engine
                .saveSearches(List.of(new SavedSearch(
                        "", new SearchRequest("film @type=Video", SearchRequest.Scope.SERVER), List.of(film))))
                .get(10, TimeUnit.SECONDS);
        SearchScreen screen = new SearchScreen(this.test.gui);
        this.test.show(FxTesting.onFx(screen::view));

        FxTesting.await("the kept tab", () -> screen.tabs().getTabs().size() == 1);
        assertThat(FxTesting.onFx(() -> screen.tabs().getTabs().getFirst().getText()))
                .isEqualTo("film (1)");

        FxTesting.onFx(() -> {
            screen.tabs().getTabs().clear();
            screen.closing();
        });
        assertThat(this.test.engine.savedSearches().get(10, TimeUnit.SECONDS))
                .as("the tabs closed before the exit are not kept")
                .isEmpty();
    }

    @Test
    void sharingADirectoryListsItsFiles() throws Exception {
        this.test = TestGui.start(this.dir.resolve("njmule"));
        SharedScreen screen = new SharedScreen(this.test.gui);
        this.test.show(FxTesting.onFx(screen::view));
        Path folder = Files.createDirectories(this.dir.resolve("share"));
        Files.write(folder.resolve("a.txt"), new byte[3000]);

        FxTesting.onFx(() -> screen.share(folder, true));
        for (int i = 0; i < 40 && FxTesting.onFx(() -> screen.table().getItems().isEmpty()); i++) {
            Thread.sleep(250);
            FxTesting.onFx(this.test.model::tick);
        }

        assertThat(FxTesting.onFx(
                        () -> screen.table().getItems().getFirst().get().name()))
                .isEqualTo("a.txt");
        FxTesting.onFx(() -> {
            screen.table().getSelectionModel().select(0);
            screen.copyLinks();
        });
        assertThat(FxTesting.onFx(() -> Clipboard.getSystemClipboard().getString()))
                .startsWith("ed2k://|file|a.txt|3000|");
        FxTesting.onFx(() -> screen.prioritize(UploadPriority.RELEASE));
        FxTesting.await(
                "the release priority",
                () -> screen.table().getItems().getFirst().get().priority() == UploadPriority.RELEASE);
    }

    @Test
    void kadConnectsAndDisconnectsByHand() throws Exception {
        this.test = TestGui.start(this.dir.resolve("njmule"));
        KadScreen screen = new KadScreen(this.test.gui);
        this.test.show(FxTesting.onFx(screen::view));
        assertThat(FxTesting.onFx(() -> screen.connectButton().getText())).isEqualTo("Connect");
        assertThat(FxTesting.onFx(() -> screen.recheckButton().isDisabled()))
                .as("no firewall to check without Kad")
                .isTrue();

        FxTesting.onFx(screen::connectOrDisconnect);
        this.tickUntil(() -> this.test.model.statusProperty().get().kad().isPresent());
        FxTesting.await(
                "the button follows", () -> screen.connectButton().getText().equals("Disconnect"));
        assertThat(this.test.engine.recheckKadFirewall().get(10, TimeUnit.SECONDS))
                .isTrue();
        FxTesting.onFx(() -> screen.recheckButton().fire());
        FxTesting.onFx(screen::connectOrDisconnect);
        this.tickUntil(() -> this.test.model.statusProperty().get().kad().isEmpty());
        assertThat(this.test.model.statusProperty().get().kad()).isEmpty();
    }

    @Test
    void kadShowsItsStateAndBootstrapNeedsKad() throws Exception {
        this.test = TestGui.start(this.dir.resolve("njmule"));
        KadScreen screen = new KadScreen(this.test.gui);
        this.test.show(FxTesting.onFx(screen::view));

        assertThat(FxTesting.onFx(() -> screen.stateLabel().getText())).isEqualTo("Off");
        FxTesting.onFx(() -> {
            screen.nodeField().setText("bad");
            screen.bootstrap();
            screen.nodeField().setText("127.0.0.1:4672");
            screen.bootstrap();
        });
        FxTesting.await("two messages", () -> this.test.told.size() >= 2);
        assertThat(this.test.told).anyMatch(m -> m.contains("Kad is not running"));
    }

    @Test
    void kadNamesTheTestedUdpStateAndTheBuddy() throws Exception {
        this.givenTheGui();

        this.whenKadIs(new KadInfo(true, 10, 5, true, "", "", 4672, false, true, KadInfo.Buddy.SERVING));

        this.thenKadReads("Open", "Buddy of a firewalled client");

        this.whenKadIs(new KadInfo(true, 10, 5, true, "", "", 4672, false, false, KadInfo.Buddy.CONNECTING));

        this.thenKadReads("Unknown", "Connecting");
    }

    private void givenTheGui() throws Exception {
        this.test = TestGui.start(this.dir.resolve("njmule"));
    }

    private void whenKadIs(KadInfo kad) {
        this.kad = kad;
    }

    private void thenKadReads(String udp, String buddy) {
        assertThat(KadScreen.udpState(this.test.gui, this.kad)).isEqualTo(udp);
        assertThat(KadScreen.buddyState(this.test.gui, this.kad)).isEqualTo(buddy);
    }

    @Test
    void statisticsShowTheSession() throws Exception {
        this.test = TestGui.start(this.dir.resolve("njmule"));
        StatisticsScreen screen = new StatisticsScreen(this.test.gui);
        this.test.show(FxTesting.onFx(screen::view));

        FxTesting.onFx(this.test.model::tick);
        FxTesting.await("totals", () -> screen.downloadedLabel().getText().equals("0 B"));
        assertThat(FxTesting.onFx(() -> screen.downloadedLabel().getText())).isEqualTo("0 B");
        List<String> sections = FxTesting.onFx(() -> screen.tree().view().getRoot().getChildren().stream()
                .map(TreeItem::getValue)
                .toList());
        assertThat(sections)
                .startsWith("Transfers", "Uploads", "Downloads", "Connection", "Time Statistics")
                .contains("Clients (0)", "Servers", "Shared Files", "Disk Space");
        TreeItem<String> transfers = FxTesting.onFx(
                () -> screen.tree().view().getRoot().getChildren().getFirst());
        assertThat(FxTesting.onFx(transfers::isExpanded)).isTrue();
        FxTesting.onFx(() -> {
            transfers.setExpanded(false);
            screen.tree().refresh();
        });
        assertThat(FxTesting.onFx(
                        () -> screen.tree().view().getRoot().getChildren().getFirst()))
                .as("the same item, still closed")
                .isSameAs(transfers)
                .satisfies(t -> assertThat(t.isExpanded()).isFalse());
        assertThat(FxTesting.onFx(() -> screen.tree()
                        .view()
                        .getRoot()
                        .getChildren()
                        .getLast()
                        .getChildren()
                        .size()))
                .as("temp and incoming")
                .isEqualTo(2);
        FxTesting.onFx(() -> {
            FxTesting.snapshot(screen.view(), "statistics-alone");
            return null;
        });
    }

    @Test
    void schedulerRulesAreEditedAndSaved() throws Exception {
        this.test = TestGui.start(this.dir.resolve("njmule"));
        SettingsScreen settings = new SettingsScreen(this.test.gui);
        this.test.show(FxTesting.onFx(settings::view));
        SchedulerPane pane = FxTesting.onFx(settings::schedulerPane);

        FxTesting.onFx(() -> {
            pane.nameField().setText("all week");
            pane.dayBoxes().values().forEach(b -> b.setSelected(true));
            pane.uploadField().setText("5");
            pane.add();
            pane.startField().setText("25:00");
            pane.add();
        });
        assertThat(FxTesting.onFx(() -> pane.rulesTable().getItems().size())).isEqualTo(1);
        assertThat(this.test.told).anyMatch(m -> m.startsWith("Not saved"));

        FxTesting.onFx(() -> {
            pane.rulesTable().getSelectionModel().select(0);
            pane.nameField().setText("every day");
            pane.startField().setText("00:00");
            pane.replace();
            pane.save();
        });
        FxTesting.await("the rule applies", () -> this.test.engine.activeRule().isPresent());
        assertThat(this.test.engine.schedule().rules().getFirst().name()).isEqualTo("every day");
        FxTesting.await("shown", () -> pane.activeLabel().getText().equals("Now: every day"));

        FxTesting.onFx(() -> {
            pane.rulesTable().getSelectionModel().select(0);
            pane.remove();
            pane.save();
        });
        FxTesting.await("no rule", () -> this.test.engine.activeRule().isEmpty());
        FxTesting.onFx(pane::load);
        assertThat(FxTesting.onFx(() -> pane.rulesTable().getItems().isEmpty())).isTrue();
    }

    @Test
    void settingsApplyValidateAndRelaunchForALanguage() throws Exception {
        this.test = TestGui.start(this.dir.resolve("njmule"));
        SettingsScreen screen = new SettingsScreen(this.test.gui);
        this.test.show(FxTesting.onFx(screen::view));

        FxTesting.onFx(() -> {
            screen.shown();
            screen.nickField().setText("Tester");
            screen.modeBox().setValue(GuiSettings.Mode.DARK);
            screen.apply();
        });
        FxTesting.await("saved", () -> this.test.told.contains("Settings saved."));
        assertThat(this.test.engine.preferences().nick()).isEqualTo("Tester");

        FxTesting.onFx(() -> {
            screen.proxyTypeBox().setValue(ProxySettings.Type.SOCKS);
            screen.proxyHostField().setText("proxy.lan");
            screen.apply();
        });
        FxTesting.await(
                "the proxy", () -> this.test.engine.preferences().proxy().host().equals("proxy.lan"));
        FxTesting.onFx(() -> {
            screen.proxyTypeBox().setValue(ProxySettings.Type.NONE);
            screen.apply();
        });
        FxTesting.await(
                "no proxy", () -> !this.test.engine.preferences().proxy().isEnabled());
        assertThat(FxTesting.onFx(() -> this.test.gui.themes().darkProperty().get()))
                .isTrue();
        assertThat(GuiSettings.load(this.test.engine.directories().gui()).mode())
                .isEqualTo(GuiSettings.Mode.DARK);

        FxTesting.onFx(() -> {
            screen.tcpPortField().setText("port");
            screen.apply();
        });
        assertThat(this.test.told).anyMatch(m -> m.startsWith("Not saved: TCP"));

        FxTesting.onFx(() -> {
            screen.load(this.test.engine.preferences());
            screen.languageBox().setValue(Optional.of(Locale.ITALIAN));
            screen.apply();
        });
        assertThat(this.test.relaunches()).isEqualTo(1);
    }

    @Test
    void webInterfaceSettingsNeedAMatchingPassword() throws Exception {
        this.test = TestGui.start(this.dir.resolve("njmule"));
        SettingsScreen screen = new SettingsScreen(this.test.gui);
        this.test.show(FxTesting.onFx(screen::view));
        Path config = this.test.engine.directories().config();

        FxTesting.onFx(() -> {
            screen.shown();
            screen.webUiBox().setSelected(true);
            screen.apply();
        });
        assertThat(this.test.told).contains("Not saved: the web interface needs a password");
        FxTesting.onFx(() -> {
            screen.webUiPasswordField().setText("short");
            screen.webUiAgainField().setText("short");
            screen.apply();
        });
        assertThat(this.test.told).anyMatch(m -> m.contains("at least 8 characters"));
        FxTesting.onFx(() -> {
            screen.webUiPasswordField().setText("long enough");
            screen.webUiAgainField().setText("long enough!");
            screen.apply();
        });
        assertThat(this.test.told).anyMatch(m -> m.contains("differ"));
        FxTesting.onFx(() -> {
            screen.webUiPortField().setText("70000");
            screen.apply();
        });
        assertThat(this.test.told).anyMatch(m -> m.contains("65535"));
        assertThat(WebUiSettings.load(config)).isEqualTo(WebUiSettings.DEFAULTS);

        FxTesting.onFx(() -> {
            screen.webUiPortField().setText("4712");
            screen.webUiAgainField().setText("long enough");
            screen.apply();
        });
        WebUiSettings saved = WebUiSettings.load(config);
        assertThat(saved.usable()).isTrue();
        assertThat(saved.port()).isEqualTo(4712);
        assertThat(FxTesting.onFx(() -> screen.webUiPasswordField().getText())).isEmpty();

        FxTesting.onFx(() -> {
            screen.webUiBox().setSelected(false);
            screen.apply();
        });
        WebUiSettings off = WebUiSettings.load(config);
        assertThat(off.enabled()).isFalse();
        assertThat(off.passwordHash()).isEqualTo(saved.passwordHash());
        FxTesting.onFx(screen::shown);
        assertThat(FxTesting.onFx(() -> screen.webUiPortField().getText())).isEqualTo("4712");
    }

    @Test
    void pastedLinksMayHaveSpacesInTheirNames() throws Exception {
        this.test = TestGui.start(this.dir.resolve("njmule"));
        TransfersScreen screen = new TransfersScreen(this.test.gui);
        this.test.show(FxTesting.onFx(screen::view));
        FxTesting.onFx(() -> {
            screen.linkField()
                    .setText("ed2k://|file|my holiday video.mkv|5000|"
                            + "00112233445566778899AABBCCDDEEFF|/\n"
                            + "  ed2k://|file|notes.txt|10|"
                            + "FFEEDDCCBBAA99887766554433221100|/");
            screen.addLink();
        });
        FxTesting.await("both downloads", () -> screen.downloads().getItems().size() == 2);
        assertThat(this.test.told).isEmpty();
        assertThat(this.test.engine.downloads().get())
                .extracting(DownloadInfo::name)
                .containsExactlyInAnyOrder("my holiday video.mkv", "notes.txt");
    }

    @Test
    void theLanguageBoxNamesEachLanguageInItself() throws Exception {
        this.givenTheSettingsScreen();

        this.whenTheLanguagesOfTheLanguageBoxAreRead();

        this.thenTheSystemLanguageComesFirst();
        this.thenEveryLanguageFollowsByItsOwnName();
    }

    private void givenTheSettingsScreen() throws Exception {
        this.test = TestGui.start(this.dir.resolve("njmule"));
        this.settings = new SettingsScreen(this.test.gui);
        this.test.show(FxTesting.onFx(this.settings::view));
    }

    private void whenTheLanguagesOfTheLanguageBoxAreRead() throws Exception {
        this.languageNames = FxTesting.onFx(() -> {
            ComboBox<Optional<Locale>> box = this.settings.languageBox();
            ListView<Optional<Locale>> list = new ListView<>(box.getItems());
            List<String> names = new ArrayList<>();
            for (int i = 0; i < box.getItems().size(); i++) {
                ListCell<Optional<Locale>> cell = box.getCellFactory().call(list);
                cell.updateListView(list);
                cell.updateIndex(i);
                names.add(cell.getText());
            }
            return names;
        });
    }

    private void thenTheSystemLanguageComesFirst() {
        assertThat(this.languageNames).first().isEqualTo("As the system");
    }

    private void thenEveryLanguageFollowsByItsOwnName() {
        assertThat(this.languageNames.subList(1, this.languageNames.size()))
                .isEqualTo(I18n.LANGUAGES.stream().map(l -> I18n.of(l).name()).toList())
                .doesNotHaveDuplicates()
                .noneMatch(name -> name.startsWith("["));
    }

    // The model learns the engine's state on its ticks, which these tests drive.
    private void tickUntil(BooleanSupplier done) throws Exception {
        for (int i = 0; i < 80 && !done.getAsBoolean(); i++) {
            FxTesting.onFx(this.test.model::tick);
            Thread.sleep(250);
        }
    }
}
