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
    void connectsSearchesDownloadsAndDisconnects() throws Exception {
        InetAddress lan = lanAddress();
        long highId = Integer.toUnsignedLong(
                Integer.reverseBytes(ByteBuffer.wrap(lan.getAddress()).getInt()));
        this.server = FakeServer.start(
                lan,
                FakeServer.finding(
                        highId,
                        "welcome to the fake server",
                        5_000_000_000L,
                        "ubuntu 24.04 desktop.iso",
                        "ubuntu notes.txt"));
        this.test = TestGui.start(this.dir.resolve("njmule"));
        MainWindow window = FxTesting.onFx(() -> new MainWindow(this.test.gui));
        this.test.show(window.root());
        ServersScreen servers = (ServersScreen) window.screen("servers").orElseThrow();
        SearchScreen search = (SearchScreen) window.screen("search").orElseThrow();
        TransfersScreen transfers = (TransfersScreen) window.screen("transfers").orElseThrow();

        FxTesting.onFx(() -> {
            window.show("servers");
            servers.addressField().setText(lan.getHostAddress() + ":" + this.server.port());
            servers.connectToAddress();
        });
        FxTesting.await(
                "connected", () -> this.test.model.connectionProperty().get() == EngineModel.Connection.CONNECTED);
        FxTesting.onFx(this.test.model::tick);
        FxTesting.await(
                "the status with the server",
                () -> this.test.model.statusProperty().get().server().isPresent());
        FxTesting.await("the server row", () -> servers.table().getItems().size() == 1);
        Button connect = FxTesting.onFx(() -> (Button) window.root().lookup("#connect"));
        assertThat(FxTesting.onFx(connect::getText)).isEqualTo("Disconnect");

        FxTesting.onFx(() -> {
            window.show("search");
            search.queryField().setText("ubuntu");
            search.methodBox().setValue(SearchScreen.Method.SERVER);
            search.start();
        });
        FxTesting.await("a result tab", () -> search.tabs().getTabs().size() == 1);
        TableView<LiveList.Row<SearchResult>> results =
                FxTesting.onFx(() -> search.table(search.tabs().getTabs().getFirst()));
        FxTesting.await("two results", () -> results.getItems().size() == 2);
        FxTesting.onFx(() -> {
            FxTesting.snapshot(window.root(), "search-results");
            results.getSelectionModel().select(0);
            search.downloadSelected();
            search.more();
            search.stop();
            return null;
        });
        FxTesting.await(
                "a download",
                () -> transfers.view() != null
                        && !this.test.model.downloads().rows().isEmpty());
        FxTesting.onFx(() -> {
            window.show("transfers");
            search.tabs().getTabs().getFirst().getOnClosed().handle(null);
            search.tabs().getTabs().clear();
        });
        FxTesting.onFx(() -> {
            FxTesting.snapshot(window.root(), "transfers-download");
            return null;
        });

        Button theme = FxTesting.onFx(() -> (Button) window.root().lookup("#theme"));
        boolean dark =
                FxTesting.onFx(() -> this.test.gui.themes().darkProperty().get());
        FxTesting.onFx(theme::fire);
        assertThat(FxTesting.onFx(() -> this.test.gui.themes().darkProperty().get()))
                .isNotEqualTo(dark);

        FxTesting.onFx(connect::fire);
        FxTesting.await(
                "disconnected",
                () -> this.test.model.connectionProperty().get() == EngineModel.Connection.DISCONNECTED);
        FxTesting.onFx(connect::fire);
        FxTesting.await(
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
