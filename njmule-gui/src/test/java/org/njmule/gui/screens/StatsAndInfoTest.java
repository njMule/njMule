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

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
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
    private StatsTree tree;
    private List<StatsTree.Entry> entries;
    private MyInfoPane pane;

    @AfterEach
    void close() {
        if (this.test != null) {
            this.test.close();
        }
    }

    @Test
    void shouldCountTheClientsServersAndFilesInTheStatistics() throws Exception {
        givenTheTraffic(1000, 2500, 0, 500);
        givenTheSources(source("10.0.0.1:4662", "eMule"), source("10.0.0.2:4662", ""));
        givenTheEngine();
        givenTheClientsRead(2);
        givenTheStatistics();

        whenTheStatisticsAreRead();

        thenTheFirstEntryHolds("Session UL:DL Ratio: 1 : 2.50", "Cumulative UL:DL Ratio: 0 : 1");
        // The same client as source and upload counts once, by the software it has now.
        thenTheEntryReads("Clients", "Clients (3)");
        thenTheEntryHolds("Clients", "aMule: 2 (66.7 %)", "?: 1 (33.3 %)");
        thenTheEntryHolds(
                "Servers",
                "Working Servers: 2",
                "Failed Servers: 1",
                "Total: 4",
                "Users on Working Servers: 1,300",
                "Files on Working Servers: 34,000",
                "Total Users: 1,350",
                "Total Files: 34,000");
        thenTheEntryHolds(
                "Shared Files",
                "Total: 2",
                "Total size of Shared Files: 2.93 KB",
                "Average file size: 1.46 KB",
                "Largest Shared File: notes.txt (1.95 KB)");
        thenTheLastEntryHoldsLines(2);
        thenTheLastEntryIsReadAgainOnlyAfterAWhile();
    }

    @Test
    void shouldDropTheLinesOfTheClientsThatWentFromTheStatistics() throws Exception {
        givenTheTraffic(1000, 2500, 0, 500);
        givenTheSources(source("10.0.0.1:4662", "eMule"), source("10.0.0.2:4662", ""));
        givenTheEngine();
        givenTheClientsRead(2);
        givenTheStatistics();
        givenTheStatisticsRefreshed();
        givenTheLinesUnder("Clients", 2);
        givenTheSourcesGone();

        whenTheStatisticsAreRefreshed();

        thenTheLinesUnderAre("Clients", "aMule: 2 (100.0 %)");
    }

    @Test
    void shouldShowTheServerKadAndTheWebInterfaceInMyInfo() throws Exception {
        givenTheWebInterfaceOn(4712, false);
        givenTheStatusOnBig(true, true, "", Statuses.kad(false, false, ""));
        givenTheEngine();
        givenConnectedToBig();
        givenMyInfoShown();
        givenMyInfoReads(
                "Status: Connected",
                "IP:Port: " + text("IDS_UNKNOWN"),
                "ID: 7",
                text("IDS_IDLOW"),
                "Name: Big",
                "IP:Port: 81.2.3.4:4661",
                "Connections: " + text("IDS_OBFUSCATED"),
                "Status: Connecting",
                "Status: Enabled",
                "URL: http://127.0.0.1:4712/");
        givenTheStatusOnBigBecomes(false, false, "5.6.7.8", Statuses.kad(true, true, "1.2.3.4"));
        givenMyInfoReads(
                "IP:Port: 5.6.7.8:4662",
                text("IDS_IDHIGH"),
                "Connections: Normal",
                "Status: Firewalled",
                "UDP Status: " + text("IDS_UNKNOWN"),
                "IP:Port: 1.2.3.4:4672",
                "ID: 16909060");
        givenTheStatusOnBigBecomes(false, false, "5.6.7.8", Statuses.kad(true, false, ""));
        givenMyInfoReads("Status: Open", "ID:");
        givenTheWebInterfaceOn(4712, true);

        whenMyInfoReadsTheWebInterfaceAgain();

        thenMyInfoShowsAUrlOfThisComputerEndingWith(":4712/");
    }

    private void givenTheTraffic(long uploaded, long downloaded, long totalUploaded, long totalDownloaded) {
        this.status.set(status(
                Optional.empty(),
                0,
                false,
                false,
                "",
                Optional.empty(),
                uploaded,
                downloaded,
                totalUploaded,
                totalDownloaded));
    }

    private void givenTheSources(SourceInfo... sources) {
        this.sources.set(List.of(sources));
    }

    private void givenTheEngine() throws Exception {
        start();
    }

    private void givenTheClientsRead(int uploads) throws Exception {
        FxTesting.onFx(this.test.model::tick);
        FxTesting.await("the clients", () -> this.test.model.uploads().size() == uploads);
    }

    private void givenTheStatistics() throws Exception {
        this.tree = FxTesting.onFx(() -> new StatsTree(this.test.gui));
    }

    private void givenTheStatisticsRefreshed() throws Exception {
        whenTheStatisticsAreRefreshed();
    }

    private void givenTheLinesUnder(String prefix, int count) throws Exception {
        TreeItem<String> item = FxTesting.onFx(() -> item(this.tree, prefix));
        assertThat(FxTesting.onFx(() -> item.getChildren().size())).isEqualTo(count);
    }

    // Fewer clients: the tree drops the lines that went.
    private void givenTheSourcesGone() throws Exception {
        this.sources.set(List.of());
        FxTesting.onFx(this.test.model::tick);
        FxTesting.onFx(this.test.model::tick);
        FxTesting.await("one client left", () -> this.test.model.sources().size() == 0);
    }

    private void givenTheWebInterfaceOn(int port, boolean lan) throws IOException {
        new WebUiSettings(true, port, lan, "hash")
                .save(this.dir.resolve("njmule").resolve("config"));
    }

    private void givenTheStatusOnBig(boolean lowId, boolean obfuscated, String publicIp, KadInfo kad) {
        this.status.set(status(Optional.of(BIG), 7, lowId, obfuscated, publicIp, Optional.of(kad), 0, 0, 0, 0));
    }

    private void givenConnectedToBig() throws Exception {
        FxTesting.onFx(() -> this.test.model.handle(new EngineEvent.ServerConnected(BIG, 7, true, true)));
    }

    private void givenMyInfoShown() throws Exception {
        this.pane = new MyInfoPane(this.test.gui);
        this.test.show(FxTesting.onFx(this.pane::node));
    }

    private void givenMyInfoReads(String... lines) throws Exception {
        assertThat(lines(this.pane)).contains(lines);
    }

    private void givenTheStatusOnBigBecomes(boolean lowId, boolean obfuscated, String publicIp, KadInfo kad)
            throws Exception {
        status(status(Optional.of(BIG), 7, lowId, obfuscated, publicIp, Optional.of(kad), 0, 0, 0, 0));
    }

    private void whenTheStatisticsAreRead() throws Exception {
        this.entries = FxTesting.onFx(this.tree::entries);
    }

    private void whenTheStatisticsAreRefreshed() throws Exception {
        FxTesting.onFx(this.tree::refresh);
    }

    private void whenMyInfoReadsTheWebInterfaceAgain() throws Exception {
        FxTesting.onFx(this.pane::readWebUi);
    }

    private void thenTheFirstEntryHolds(String... lines) {
        assertThat(texts(this.entries.getFirst())).containsExactly(lines);
    }

    private void thenTheEntryReads(String prefix, String text) {
        assertThat(entry(this.entries, prefix).text()).isEqualTo(text);
    }

    private void thenTheEntryHolds(String prefix, String... lines) {
        assertThat(texts(entry(this.entries, prefix))).containsExactly(lines);
    }

    private void thenTheLastEntryHoldsLines(int count) {
        assertThat(this.entries.getLast().children()).hasSize(count);
    }

    private void thenTheLastEntryIsReadAgainOnlyAfterAWhile() throws Exception {
        assertThat(FxTesting.onFx(this.tree::entries).getLast().children())
                .as("read again only after a while")
                .isSameAs(this.entries.getLast().children());
    }

    private void thenTheLinesUnderAre(String prefix, String... lines) throws Exception {
        assertThat(FxTesting.onFx(() -> item(this.tree, prefix).getChildren().stream()
                        .map(TreeItem::getValue)
                        .toList()))
                .containsExactly(lines);
    }

    // On the LAN the address is this computer's own, when it has one.
    private void thenMyInfoShowsAUrlOfThisComputerEndingWith(String end) throws Exception {
        assertThat(lines(this.pane)).anyMatch(l -> l.startsWith("URL: http://") && l.endsWith(end));
    }

    private String text(String key) {
        return this.test.gui.t(key);
    }

    private void start() throws Exception {
        Map<String, Engines.Command> commands = new HashMap<>();
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
        FxTesting.await(
                "the state",
                () -> Objects.equals(this.test.model.statusProperty().get(), this.status.get()));
        FxTesting.await("the servers", () -> this.test.model.servers().size() == 4);
        FxTesting.await("the files", () -> this.test.model.sharedFiles().size() == 2);
    }

    private void status(EngineStatus next) throws Exception {
        this.status.set(next);
        FxTesting.onFx(this.test.model::tick);
        FxTesting.await(
                "the state",
                () -> Objects.equals(this.test.model.statusProperty().get(), next));
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
