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

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import javafx.scene.control.TreeItem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.core.api.EngineEvent;
import org.njmule.core.api.EngineStatus;
import org.njmule.core.api.Hash16;
import org.njmule.core.api.KadInfo;
import org.njmule.core.api.ServerInfo;
import org.njmule.core.api.SharedFileInfo;
import org.njmule.core.api.SourceInfo;
import org.njmule.core.api.UploadInfo;
import org.njmule.core.api.UploadPriority;
import org.njmule.gui.Engines;
import org.njmule.gui.FxTesting;
import org.njmule.gui.Statuses;
import org.njmule.gui.TestGui;
import org.njmule.webui.WebUiSettings;

@Timeout(120)
class StatsAndInfoTest {

    private static final Hash16 FILE = Hash16.parseHex("0123456789ABCDEF0123456789ABCDEF");
    private static final ServerInfo BIG = Statuses.server("81.2.3.4:4661", "Big", false);

    @TempDir
    Path dir;

    private TestGui test;
    private final AtomicReference<EngineStatus> status = new AtomicReference<>();
    private final AtomicReference<List<SourceInfo>> sources = new AtomicReference<>(List.of());

    @AfterEach
    void close() {
        if (this.test != null) {
            this.test.close();
        }
    }

    @Test
    void theStatisticsCountTheClientsServersAndFiles() throws Exception {
        this.status.set(status(Optional.empty(), 0, false, false, "", Optional.empty(), 1000, 2500, 0, 500));
        this.sources.set(List.of(source("10.0.0.1:4662", "eMule"), source("10.0.0.2:4662", "")));
        this.start();
        FxTesting.onFx(this.test.model::tick);
        FxTesting.await("the clients", () -> this.test.model.uploads().size() == 2);
        StatsTree tree = FxTesting.onFx(() -> new StatsTree(this.test.gui));

        List<StatsTree.Entry> entries = FxTesting.onFx(tree::entries);
        assertThat(texts(entries.getFirst()))
                .containsExactly("Session UL:DL Ratio: 1 : 2.50", "Cumulative UL:DL Ratio: 0 : 1");
        // The same client as source and upload counts once, by the software it has now.
        assertThat(this.entry(entries, "Clients").text()).isEqualTo("Clients (3)");
        assertThat(texts(this.entry(entries, "Clients"))).containsExactly("aMule: 2 (66.7 %)", "?: 1 (33.3 %)");
        assertThat(texts(this.entry(entries, "Servers")))
                .containsExactly(
                        "Working Servers: 2",
                        "Failed Servers: 1",
                        "Total: 4",
                        "Users on Working Servers: 1,300",
                        "Files on Working Servers: 34,000",
                        "Total Users: 1,350",
                        "Total Files: 34,000");
        assertThat(texts(this.entry(entries, "Shared Files")))
                .containsExactly(
                        "Total: 2",
                        "Total size of Shared Files: 2.93 KB",
                        "Average file size: 1.46 KB",
                        "Largest Shared File: notes.txt (1.95 KB)");
        List<StatsTree.Entry> disk = entries.getLast().children();
        assertThat(disk).hasSize(2);
        assertThat(FxTesting.onFx(tree::entries).getLast().children())
                .as("read again only after a while")
                .isSameAs(entries.getLast().children());

        // Fewer clients: the tree drops the lines that went.
        FxTesting.onFx(tree::refresh);
        TreeItem<String> clients = FxTesting.onFx(() -> this.item(tree, "Clients"));
        assertThat(FxTesting.onFx(() -> clients.getChildren().size())).isEqualTo(2);
        this.sources.set(List.of());
        FxTesting.onFx(this.test.model::tick);
        FxTesting.onFx(this.test.model::tick);
        FxTesting.await("one client left", () -> this.test.model.sources().size() == 0);
        FxTesting.onFx(tree::refresh);
        assertThat(FxTesting.onFx(() -> this.item(tree, "Clients").getChildren().stream()
                        .map(TreeItem::getValue)
                        .toList()))
                .containsExactly("aMule: 2 (100.0 %)");
    }

    @Test
    void myInfoShowsTheServerKadAndTheWebInterface() throws Exception {
        new WebUiSettings(true, 4712, false, "hash")
                .save(this.dir.resolve("njmule").resolve("config"));
        this.status.set(
                status(Optional.of(BIG), 7, true, true, "", Optional.of(Statuses.kad(false, false, "")), 0, 0, 0, 0));
        this.start();
        FxTesting.onFx(() -> this.test.model.handle(new EngineEvent.ServerConnected(BIG, 7, true, true)));
        MyInfoPane pane = new MyInfoPane(this.test.gui);
        this.test.show(FxTesting.onFx(pane::node));

        List<String> lines = this.lines(pane);
        assertThat(lines)
                .contains(
                        "Status: Connected",
                        "IP:Port: " + this.test.gui.t("IDS_UNKNOWN"),
                        "ID: 7",
                        this.test.gui.t("IDS_IDLOW"),
                        "Name: Big",
                        "IP:Port: 81.2.3.4:4661",
                        "Connections: " + this.test.gui.t("IDS_OBFUSCATED"),
                        "Status: Connecting",
                        "Status: Enabled",
                        "URL: http://127.0.0.1:4712/");

        this.status(status(
                Optional.of(BIG),
                7,
                false,
                false,
                "5.6.7.8",
                Optional.of(Statuses.kad(true, true, "1.2.3.4")),
                0,
                0,
                0,
                0));
        assertThat(this.lines(pane))
                .contains(
                        "IP:Port: 5.6.7.8:4662",
                        this.test.gui.t("IDS_IDHIGH"),
                        "Connections: Normal",
                        "Status: Firewalled",
                        "UDP Status: " + this.test.gui.t("IDS_UNKNOWN"),
                        "IP:Port: 1.2.3.4:4672",
                        "ID: 16909060");
        this.status(status(
                Optional.of(BIG), 7, false, false, "5.6.7.8", Optional.of(Statuses.kad(true, false, "")), 0, 0, 0, 0));
        assertThat(this.lines(pane)).contains("Status: Open", "ID:");

        // On the LAN the address is this computer's own, when it has one.
        new WebUiSettings(true, 4712, true, "hash")
                .save(this.dir.resolve("njmule").resolve("config"));
        FxTesting.onFx(pane::readWebUi);
        assertThat(this.lines(pane)).anyMatch(l -> l.startsWith("URL: http://") && l.endsWith(":4712/"));
    }

    private void start() throws Exception {
        Map<String, Function<Object[], Object>> commands = new HashMap<>();
        commands.put("status", args -> CompletableFuture.completedFuture(this.status.get()));
        commands.put("sources", args -> CompletableFuture.completedFuture(this.sources.get()));
        commands.put(
                "uploads",
                args -> CompletableFuture.completedFuture(
                        List.of(upload("10.0.0.1:4662", "aMule"), upload("10.0.0.3:4662", "aMule"))));
        ServerInfo failed = new ServerInfo(
                "81.2.3.6:4661", "Gone", "", 50, 0, 0, 3, ServerInfo.Priority.LOW, 0, 0, 0, 0, "", false, false);
        ServerInfo idle = new ServerInfo(
                "81.2.3.7:4661", "Idle", "", 0, 0, 0, 0, ServerInfo.Priority.LOW, 0, 0, 0, 0, "", false, false);
        ServerInfo users = new ServerInfo(
                "81.2.3.8:4661", "Users", "", 100, 0, 0, 0, ServerInfo.Priority.LOW, 0, 0, 0, 0, "", false, false);
        commands.put("servers", args -> CompletableFuture.completedFuture(List.of(BIG, failed, idle, users)));
        commands.put(
                "sharedFiles",
                args -> CompletableFuture.completedFuture(List.of(
                        new SharedFileInfo(
                                FILE, "notes.txt", 2000, this.dir.resolve("notes.txt"), UploadPriority.NORMAL),
                        new SharedFileInfo(
                                Hash16.parseHex("00112233445566778899AABBCCDDEEFF"),
                                "small.txt",
                                1000,
                                this.dir.resolve("small.txt"),
                                UploadPriority.NORMAL))));
        this.test = TestGui.start(this.dir.resolve("njmule"), real -> Engines.overriding(real, commands));
        FxTesting.await("the state", () -> this.test.model.statusProperty().get() == this.status.get());
        FxTesting.await("the servers", () -> this.test.model.servers().size() == 4);
        FxTesting.await("the files", () -> this.test.model.sharedFiles().size() == 2);
    }

    private void status(EngineStatus next) throws Exception {
        this.status.set(next);
        FxTesting.onFx(this.test.model::tick);
        FxTesting.await("the state", () -> this.test.model.statusProperty().get() == next);
    }

    private List<String> lines(MyInfoPane pane) throws Exception {
        return FxTesting.onFx(() -> pane.lines().stream()
                .map(l -> (l.label() + " " + l.value()).strip())
                .toList());
    }

    private StatsTree.Entry entry(List<StatsTree.Entry> entries, String prefix) {
        return entries.stream()
                .filter(e -> e.text().startsWith(prefix))
                .findFirst()
                .orElseThrow();
    }

    private TreeItem<String> item(StatsTree tree, String prefix) {
        return tree.view().getRoot().getChildren().stream()
                .filter(i -> i.getValue().startsWith(prefix))
                .findFirst()
                .orElseThrow();
    }

    private static List<String> texts(StatsTree.Entry entry) {
        return entry.children().stream().map(StatsTree.Entry::text).toList();
    }

    private static EngineStatus status(
            Optional<ServerInfo> server,
            long clientId,
            boolean lowId,
            boolean obfuscated,
            String publicIp,
            Optional<KadInfo> kad,
            long uploaded,
            long downloaded,
            long totalUploaded,
            long totalDownloaded) {
        return new EngineStatus(
                server,
                clientId,
                lowId,
                obfuscated,
                kad,
                0,
                0,
                downloaded,
                uploaded,
                0,
                0,
                0,
                0,
                totalDownloaded,
                totalUploaded,
                1,
                60,
                60,
                publicIp,
                4662,
                false);
    }

    private static SourceInfo source(String address, String software) {
        return new SourceInfo(
                FILE, "film.mkv", address, Optional.empty(), software, SourceInfo.State.QUEUED, 1, 0, 0, 0);
    }

    private static UploadInfo upload(String address, String software) {
        return new UploadInfo(
                address,
                Hash16.parseHex("00112233445566778899AABBCCDDEEFF"),
                Optional.empty(),
                software,
                "notes.txt",
                UploadInfo.State.UPLOADING,
                0,
                0,
                0);
    }
}
