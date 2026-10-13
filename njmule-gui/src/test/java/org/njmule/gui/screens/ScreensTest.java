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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import javafx.scene.input.Clipboard;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.core.api.DownloadInfo;
import org.njmule.core.api.PartMap;
import org.njmule.core.api.SearchRequest;
import org.njmule.core.config.ProxySettings;
import org.njmule.gui.FxTesting;
import org.njmule.gui.TestGui;
import org.njmule.gui.infra.GuiSettings;

@Timeout(120)
class ScreensTest {

    private static final String LINK =
            "ed2k://|file|test%20file.bin|10000|0123456789ABCDEF0123456789ABCDEF|/";

    @TempDir Path dir;

    private TestGui test;

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

        FxTesting.onFx(
                () -> {
                    screen.linkField().setText(LINK);
                    screen.addLink();
                });
        FxTesting.await("the download row", () -> screen.downloads().getItems().size() == 1);
        assertThat(FxTesting.onFx(() -> screen.linkField().getText())).isEmpty();

        FxTesting.onFx(
                () -> {
                    screen.downloads().getSelectionModel().select(0);
                    screen.pauseSelected();
                });
        FxTesting.await(
                "paused",
                () ->
                        screen.downloads().getItems().getFirst().get().status()
                                == DownloadInfo.Status.PAUSED);
        FxTesting.onFx(screen::resumeSelected);
        FxTesting.await(
                "downloading again",
                () ->
                        screen.downloads().getItems().getFirst().get().status()
                                == DownloadInfo.Status.DOWNLOADING);
        FxTesting.onFx(screen::copyLinks);
        assertThat(FxTesting.onFx(() -> Clipboard.getSystemClipboard().getString()))
                .startsWith("ed2k://|file|test%20file.bin|10000|");

        List<DownloadInfo> selected =
                FxTesting.onFx(
                        () ->
                                screen.downloads().getSelectionModel().getSelectedItems().stream()
                                        .map(r -> r.get())
                                        .toList());
        FxTesting.onFx(() -> screen.cancel(selected));
        FxTesting.await("no download", () -> screen.downloads().getItems().isEmpty());

        FxTesting.onFx(
                () -> {
                    screen.linkField().setText("not a link");
                    screen.addLink();
                });
        assertThat(this.test.told).contains("Not an eD2K file link.");
        assertThat(
                        TransfersScreen.remaining(
                                new DownloadInfo(
                                        selected.getFirst().hash(),
                                        "x",
                                        1000,
                                        400,
                                        DownloadInfo.Status.DOWNLOADING,
                                        1,
                                        1,
                                        100,
                                        PartMap.EMPTY)))
                .isEqualTo(6);
    }

    @Test
    void serversAddFromAFileRemoveAndRejectBadAddresses() throws Exception {
        this.test = TestGui.start(this.dir.resolve("njmule"));
        ServersScreen screen = new ServersScreen(this.test.gui);
        this.test.show(FxTesting.onFx(screen::view));
        Path met = this.dir.resolve("server.met");
        // Two public servers: version byte, count, then ip, port and no tags each.
        Files.write(
                met,
                new byte[] {
                    (byte) 0xE0,
                    2,
                    0,
                    0,
                    0,
                    81,
                    2,
                    3,
                    4,
                    0x35,
                    0x12,
                    0,
                    0,
                    0,
                    0,
                    81,
                    2,
                    3,
                    5,
                    0x36,
                    0x12,
                    0,
                    0,
                    0,
                    0
                });

        FxTesting.onFx(() -> screen.addServerMet(met));
        FxTesting.await("two servers", () -> screen.table().getItems().size() == 2);
        FxTesting.onFx(
                () -> {
                    screen.table().getSelectionModel().clearSelection();
                    screen.table().getSelectionModel().select(0);
                    screen.removeSelected();
                });
        FxTesting.await("one server", () -> screen.table().getItems().size() == 1);

        FxTesting.onFx(
                () -> {
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

        FxTesting.onFx(
                () -> {
                    screen.queryField().setText("ubuntu");
                    screen.scopeBox().setValue(SearchRequest.Scope.SERVER);
                    screen.start();
                    screen.more();
                    screen.stop();
                    screen.downloadSelected();
                });

        FxTesting.await("a refusal", () -> !this.test.told.isEmpty());
        assertThat(FxTesting.onFx(() -> screen.tabs().getTabs().size())).isZero();
        FxTesting.onFx(
                () -> {
                    screen.queryField().setText("  ");
                    screen.start();
                });
    }

    @Test
    void sharingADirectoryListsItsFiles() throws Exception {
        this.test = TestGui.start(this.dir.resolve("njmule"));
        SharedScreen screen = new SharedScreen(this.test.gui);
        this.test.show(FxTesting.onFx(screen::view));
        Path folder = Files.createDirectories(this.dir.resolve("share"));
        Files.write(folder.resolve("a.txt"), new byte[3000]);

        FxTesting.onFx(() -> screen.share(folder));
        for (int i = 0; i < 40 && FxTesting.onFx(() -> screen.table().getItems().isEmpty()); i++) {
            Thread.sleep(250);
            FxTesting.onFx(this.test.model::tick);
        }

        assertThat(FxTesting.onFx(() -> screen.table().getItems().getFirst().get().name()))
                .isEqualTo("a.txt");
        FxTesting.onFx(
                () -> {
                    screen.table().getSelectionModel().select(0);
                    screen.copyLinks();
                });
        assertThat(FxTesting.onFx(() -> Clipboard.getSystemClipboard().getString()))
                .startsWith("ed2k://|file|a.txt|3000|");
    }

    @Test
    void kadShowsItsStateAndBootstrapNeedsKad() throws Exception {
        this.test = TestGui.start(this.dir.resolve("njmule"));
        KadScreen screen = new KadScreen(this.test.gui);
        this.test.show(FxTesting.onFx(screen::view));

        assertThat(FxTesting.onFx(() -> screen.stateLabel().getText())).isEqualTo("Off");
        FxTesting.onFx(
                () -> {
                    screen.nodeField().setText("bad");
                    screen.bootstrap();
                    screen.nodeField().setText("127.0.0.1:4672");
                    screen.bootstrap();
                });
        FxTesting.await("two messages", () -> this.test.told.size() >= 2);
        assertThat(this.test.told).anyMatch(m -> m.contains("Kad is not running"));
    }

    @Test
    void statisticsShowTheSession() throws Exception {
        this.test = TestGui.start(this.dir.resolve("njmule"));
        StatisticsScreen screen = new StatisticsScreen(this.test.gui);
        this.test.show(FxTesting.onFx(screen::view));

        FxTesting.onFx(this.test.model::tick);
        FxTesting.await("totals", () -> screen.downloadedLabel().getText().equals("0 B"));
        FxTesting.onFx(
                () -> {
                    FxTesting.snapshot(screen.view(), "statistics-alone");
                    return null;
                });
    }

    @Test
    void settingsApplyValidateAndRelaunchForALanguage() throws Exception {
        this.test = TestGui.start(this.dir.resolve("njmule"));
        SettingsScreen screen = new SettingsScreen(this.test.gui);
        this.test.show(FxTesting.onFx(screen::view));

        FxTesting.onFx(
                () -> {
                    screen.shown();
                    screen.nickField().setText("Tester");
                    screen.modeBox().setValue(GuiSettings.Mode.DARK);
                    screen.apply();
                });
        FxTesting.await("saved", () -> this.test.told.contains("Settings saved."));
        assertThat(this.test.engine.preferences().nick()).isEqualTo("Tester");

        FxTesting.onFx(
                () -> {
                    screen.proxyTypeBox().setValue(ProxySettings.Type.SOCKS);
                    screen.proxyHostField().setText("proxy.lan");
                    screen.apply();
                });
        FxTesting.await(
                "the proxy",
                () -> this.test.engine.preferences().proxy().host().equals("proxy.lan"));
        FxTesting.onFx(
                () -> {
                    screen.proxyTypeBox().setValue(ProxySettings.Type.NONE);
                    screen.apply();
                });
        FxTesting.await("no proxy", () -> !this.test.engine.preferences().proxy().isEnabled());
        assertThat(FxTesting.onFx(() -> this.test.gui.themes().darkProperty().get())).isTrue();
        assertThat(GuiSettings.load(this.test.engine.directories().gui()).mode())
                .isEqualTo(GuiSettings.Mode.DARK);

        FxTesting.onFx(
                () -> {
                    screen.tcpPortField().setText("port");
                    screen.apply();
                });
        assertThat(this.test.told).anyMatch(m -> m.startsWith("Not saved: TCP"));

        FxTesting.onFx(
                () -> {
                    screen.load(this.test.engine.preferences());
                    screen.languageBox().setValue(Optional.of(Locale.ITALIAN));
                    screen.apply();
                });
        assertThat(this.test.relaunches()).isEqualTo(1);
    }
}
