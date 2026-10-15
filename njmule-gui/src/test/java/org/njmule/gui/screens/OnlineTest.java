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
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.Collections;
import javafx.scene.control.Button;
import javafx.scene.control.TableView;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.core.api.SearchResult;
import org.njmule.gui.FxTesting;
import org.njmule.gui.TestGui;
import org.njmule.gui.model.EngineModel;
import org.njmule.gui.model.LiveList;
import org.njmule.gui.shell.MainWindow;
import org.njmule.testkit.FakeServer;

/** The window against a fake server: connecting, searching, downloading, disconnecting. */
@Timeout(120)
class OnlineTest {

    @TempDir
    Path dir;

    private TestGui test;
    private FakeServer server;
    private InetAddress lan;
    private MainWindow window;
    private ServersScreen servers;
    private SearchScreen search;
    private TransfersScreen transfers;
    private Button connect;
    private TableView<LiveList.Row<SearchResult>> results;

    @AfterEach
    void close() throws Exception {
        if (this.test != null) {
            this.test.close();
        }
        if (this.server != null) {
            this.server.close();
        }
    }

    @Test
    void shouldConnectSearchDownloadDisconnectAndConnectAgain() throws Exception {
        givenAFakeServerOnTheLan("ubuntu 24.04 desktop.iso", "ubuntu notes.txt");
        givenTheMainWindow();
        givenTheServerConnectedFromTheServersScreen();
        givenTheServerListed();
        givenTheConnectButtonReads("Disconnect");
        givenAServerSearchFor("ubuntu");
        givenTheResults(2);
        givenTheFirstResultDownloadedAndTheSearchStopped();
        givenTheTransfersListADownload();
        givenTheTransfersShownAndTheResultsClosed();
        givenTheThemeButtonSwitchesTheTheme();
        givenTheConnectButtonDisconnects();

        whenTheConnectButtonIsPressed();

        thenTheModelConnectsAgain();
    }

    private void givenAFakeServerOnTheLan(String... files) throws Exception {
        this.lan = lanAddress();
        long highId = Integer.toUnsignedLong(
                Integer.reverseBytes(ByteBuffer.wrap(this.lan.getAddress()).getInt()));
        this.server = FakeServer.start(
                this.lan, FakeServer.finding(highId, "welcome to the fake server", 5_000_000_000L, files));
    }

    private void givenTheMainWindow() throws Exception {
        this.test = TestGui.start(this.dir.resolve("njmule"));
        this.window = FxTesting.onFx(() -> new MainWindow(this.test.gui));
        this.test.show(this.window.root());
        this.servers = (ServersScreen) this.window.screen("servers").orElseThrow();
        this.search = (SearchScreen) this.window.screen("search").orElseThrow();
        this.transfers = (TransfersScreen) this.window.screen("transfers").orElseThrow();
    }

    private void givenTheServerConnectedFromTheServersScreen() throws Exception {
        FxTesting.onFx(() -> {
            this.window.show("servers");
            this.servers.addressField().setText(this.lan.getHostAddress() + ":" + this.server.port());
            this.servers.connectToAddress();
        });
        FxTesting.await(
                "connected", () -> this.test.model.connectionProperty().get() == EngineModel.Connection.CONNECTED);
        FxTesting.onFx(this.test.model::tick);
        FxTesting.await(
                "the status with the server",
                () -> this.test.model.statusProperty().get().server().isPresent());
    }

    private void givenTheServerListed() throws Exception {
        FxTesting.await("the server row", () -> this.servers.table().getItems().size() == 1);
    }

    private void givenTheConnectButtonReads(String text) throws Exception {
        this.connect = FxTesting.onFx(() -> (Button) this.window.root().lookup("#connect"));
        assertThat(FxTesting.onFx(this.connect::getText)).isEqualTo(text);
    }

    private void givenAServerSearchFor(String query) throws Exception {
        FxTesting.onFx(() -> {
            this.window.show("search");
            this.search.queryField().setText(query);
            this.search.methodBox().setValue(SearchScreen.Method.SERVER);
            this.search.start();
        });
        FxTesting.await("a result tab", () -> this.search.tabs().getTabs().size() == 1);
    }

    private void givenTheResults(int count) throws Exception {
        this.results = FxTesting.onFx(
                () -> this.search.table(this.search.tabs().getTabs().getFirst()));
        FxTesting.await("two results", () -> this.results.getItems().size() == count);
    }

    private void givenTheFirstResultDownloadedAndTheSearchStopped() throws Exception {
        FxTesting.onFx(() -> {
            FxTesting.snapshot(this.window.root(), "search-results");
            this.results.getSelectionModel().select(0);
            this.search.downloadSelected();
            this.search.more();
            this.search.stop();
            return null;
        });
    }

    private void givenTheTransfersListADownload() throws Exception {
        FxTesting.await(
                "a download",
                () -> this.transfers.view() != null
                        && !this.test.model.downloads().rows().isEmpty());
    }

    private void givenTheTransfersShownAndTheResultsClosed() throws Exception {
        FxTesting.onFx(() -> {
            this.window.show("transfers");
            this.search.tabs().getTabs().getFirst().getOnClosed().handle(null);
            this.search.tabs().getTabs().clear();
        });
        FxTesting.onFx(() -> {
            FxTesting.snapshot(this.window.root(), "transfers-download");
            return null;
        });
    }

    private void givenTheThemeButtonSwitchesTheTheme() throws Exception {
        Button theme = FxTesting.onFx(() -> (Button) this.window.root().lookup("#theme"));
        boolean dark =
                FxTesting.onFx(() -> this.test.gui.themes().darkProperty().get());
        FxTesting.onFx(theme::fire);
        assertThat(FxTesting.onFx(() -> this.test.gui.themes().darkProperty().get()))
                .isNotEqualTo(dark);
    }

    private void givenTheConnectButtonDisconnects() throws Exception {
        FxTesting.onFx(this.connect::fire);
        FxTesting.await(
                "disconnected",
                () -> this.test.model.connectionProperty().get() == EngineModel.Connection.DISCONNECTED);
    }

    private void whenTheConnectButtonIsPressed() throws Exception {
        FxTesting.onFx(this.connect::fire);
    }

    private void thenTheModelConnectsAgain() throws Exception {
        FxTesting.assertEventually(
                "connected again",
                () -> this.test.model.connectionProperty().get() != EngineModel.Connection.DISCONNECTED);
    }

    private static InetAddress lanAddress() throws Exception {
        for (NetworkInterface nic : Collections.list(NetworkInterface.getNetworkInterfaces())) {
            if (!nic.isUp() || nic.isLoopback() || nic.isVirtual()) {
                continue;
            }
            for (InetAddress address : Collections.list(nic.getInetAddresses())) {
                if (address instanceof Inet4Address && !address.isLinkLocalAddress()) {
                    return address;
                }
            }
        }
        assumeTrue(false, "no LAN address");
        throw new IllegalStateException();
    }
}
