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
package org.njmule.gui.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.njmule.core.api.DownloadPriority.AUTO;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.core.api.DownloadInfo;
import org.njmule.core.api.Ed2kLink;
import org.njmule.core.api.EngineEvent;
import org.njmule.core.api.Hash16;
import org.njmule.core.api.ImportOptions;
import org.njmule.core.api.ImportReport;
import org.njmule.core.api.LogLine;
import org.njmule.core.api.NjMuleEngine;
import org.njmule.core.api.PartMap;
import org.njmule.core.api.SearchId;
import org.njmule.core.api.SearchRequest;
import org.njmule.core.api.SearchResult;
import org.njmule.core.api.ServerAddress;
import org.njmule.core.api.ServerInfo;
import org.njmule.gui.FxTesting;

@Timeout(60)
class EngineModelTest {

    private static final Hash16 FILE = Hash16.parseHex("0123456789ABCDEF0123456789ABCDEF");

    @TempDir
    Path dir;

    private NjMuleEngine engine;
    private EngineModel model;
    private final List<String> errors = new CopyOnWriteArrayList<>();

    @AfterEach
    void close() {
        if (this.model != null) {
            this.model.close();
        }
        if (this.engine != null) {
            this.engine.close();
        }
    }

    // A direct executor: the model's state changes on the engine's threads, which tests poll.
    private EngineModel start() throws Exception {
        this.engine = FxTesting.engine(this.dir.resolve("njmule"));
        this.model = new EngineModel(this.engine, Runnable::run);
        this.model.onError(this.errors::add);
        this.model.start();
        return this.model;
    }

    @Test
    void pollsTheStatusAndKeepsTheRates() throws Exception {
        EngineModel m = this.start();

        until(() -> m.rates().size() == 1);
        m.tick();
        until(() -> m.rates().size() == 2);

        assertThat(m.statusProperty().get().server()).isEmpty();
        assertThat(m.connectionProperty().get()).isEqualTo(EngineModel.Connection.DISCONNECTED);
        assertThat(m.engine()).isSameAs(this.engine);
        assertThat(m.preferencesProperty().get()).isEqualTo(this.engine.preferences());
    }

    @Test
    void downloadsAreAddedPausedAndCancelled() throws Exception {
        EngineModel m = this.start();

        m.addDownload(Ed2kLink.FileLink.of("a.bin", 5000, FILE));
        until(() -> m.downloads().size() == 1);
        m.pause(FILE);
        until(() -> m.downloads().rows().getFirst().get().status() == DownloadInfo.Status.PAUSED);
        m.resume(FILE);
        until(() -> m.downloads().rows().getFirst().get().status() == DownloadInfo.Status.DOWNLOADING);
        m.cancel(FILE);
        until(() -> m.downloads().size() == 0);
        assertThat(this.engine.downloads().get(5, TimeUnit.SECONDS)).isEmpty();
    }

    @Test
    void failuresReachTheErrorSink() throws Exception {
        EngineModel m = this.start();

        AtomicReference<EngineModel.Search> started = new AtomicReference<>();
        m.search(new SearchRequest("ubuntu", SearchRequest.Scope.SERVER), started::set);
        m.kadBootstrap(ServerAddress.parse("127.0.0.1:4672"));

        until(() -> this.errors.size() >= 2);
        assertThat(started.get()).isNull();
        assertThat(this.errors).anyMatch(e -> e.contains("Kad is not running"));
    }

    @Test
    void eventsUpdateTheState() throws Exception {
        EngineModel m = this.start();
        ServerInfo server = new ServerInfo("81.2.3.4:4661", "Big", "", 10, 20, 30, 0, ServerInfo.Priority.NORMAL);

        m.handle(new EngineEvent.ServerConnecting(server));
        assertThat(m.connectionProperty().get()).isEqualTo(EngineModel.Connection.CONNECTING);
        assertThat(m.serverNameProperty().get()).isEqualTo("Big");
        m.handle(new EngineEvent.ServerConnected(server, 0x12345678L, false, true));
        assertThat(m.connectionProperty().get()).isEqualTo(EngineModel.Connection.CONNECTED);
        m.handle(new EngineEvent.ServerUpdated(server));
        assertThat(m.servers().size()).isEqualTo(1);
        m.handle(new EngineEvent.ServerDisconnected(Optional.of(server), "bye"));
        assertThat(m.connectionProperty().get()).isEqualTo(EngineModel.Connection.DISCONNECTED);
        assertThat(m.serverNameProperty().get()).isEmpty();

        m.handle(new EngineEvent.Log(new LogLine(Instant.now(), LogLine.Level.INFO, "one")));
        m.handle(new EngineEvent.Log(new LogLine(Instant.now(), LogLine.Level.DEBUG, "two")));
        assertThat(m.lastLogProperty().get()).isEqualTo("one");
        assertThat(m.log()).extracting(LogLine::message).endsWith("one", "two");
        for (int i = 0; i < EngineModel.LOG_LINES + 5; i++) {
            m.handle(new EngineEvent.Log(new LogLine(Instant.now(), LogLine.Level.INFO, "x")));
        }
        assertThat(m.log()).hasSize(EngineModel.LOG_LINES);

        DownloadInfo download =
                new DownloadInfo(FILE, "a.bin", 100, 0, DownloadInfo.Status.DOWNLOADING, AUTO, 0, 0, 0, PartMap.EMPTY);
        m.handle(new EngineEvent.DownloadAdded(download));
        m.handle(new EngineEvent.DownloadProgress(download));
        m.handle(new EngineEvent.DownloadFailed(download, "disk"));
        assertThat(m.downloads().size()).isEqualTo(1);
        m.handle(new EngineEvent.DownloadRemoved(FILE));
        assertThat(m.downloads().size()).isZero();
        List<String> finished = new CopyOnWriteArrayList<>();
        m.onCompleted(d -> finished.add(d.name()));
        m.handle(new EngineEvent.DownloadAdded(download));
        DownloadInfo done =
                new DownloadInfo(FILE, "a.bin", 100, 100, DownloadInfo.Status.COMPLETE, AUTO, 0, 0, 0, PartMap.EMPTY);
        m.handle(new EngineEvent.DownloadCompleted(done, this.dir.resolve("a.bin")));
        assertThat(m.downloads().size()).as("finished downloads stay listed").isEqualTo(1);
        assertThat(finished).containsExactly("a.bin");
        m.clearCompleted();
        assertThat(m.downloads().size()).isZero();
        m.handle(new EngineEvent.PreferencesChanged(this.engine.preferences()));
        m.handle(new EngineEvent.Started("x"));
    }

    @Test
    void searchResultsGoToTheirSearch() throws Exception {
        EngineModel m = this.start();
        SearchId id = new SearchId(7);
        // As if the engine had accepted the search.
        EngineModel.Search search = m.open(id, new SearchRequest("x", SearchRequest.Scope.KAD));

        SearchResult result = new SearchResult(FILE, "x.iso", 10, 3, 1, Optional.of("Iso"));
        m.handle(new EngineEvent.SearchResults(id, List.of(result)));
        m.handle(new EngineEvent.SearchResults(new SearchId(8), List.of(result)));
        assertThat(search.results().size()).isEqualTo(1);
        assertThat(search.runningProperty().get()).isTrue();
        m.handle(new EngineEvent.SearchEnded(id, 1));
        assertThat(search.runningProperty().get()).isFalse();
        assertThat(m.searches()).containsExactly(search);
        assertThat(search.id()).isEqualTo(id);
        assertThat(search.request().expression()).isEqualTo("x");
        m.moreResults(search);
        m.stopSearch(search);
        assertThat(m.searches()).isEmpty();
    }

    @Test
    void sharesPreferencesAndImports() throws Exception {
        EngineModel m = this.start();
        Path folder = Files.createDirectories(this.dir.resolve("share"));
        Files.write(folder.resolve("b.txt"), new byte[100]);
        Path emule = Files.createDirectories(this.dir.resolve("emule"));
        Files.writeString(emule.resolve("preferences.ini"), "[eMule]\r\nNick=Imported\r\n");
        AtomicReference<ImportReport> report = new AtomicReference<>();
        AtomicReference<String> nick = new AtomicReference<>();

        m.shareDirectory(folder);
        m.updatePreferences(p -> p.toBuilder().nick("Changed").build(), p -> nick.set(p.nick()));
        until(() -> "Changed".equals(nick.get()));
        m.importFromEmule(emule, new ImportOptions(false, false, false), report::set);
        until(() -> report.get() != null);

        assertThat(m.preferencesProperty().get().nick()).isEqualTo("Imported");
        for (int i = 0; i < 80 && m.sharedFiles().size() == 0; i++) {
            Thread.sleep(100);
            for (int t = 0; t < 15; t++) {
                m.tick();
            }
        }
        assertThat(m.sharedFiles().size()).isEqualTo(1);
    }

    private static void until(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + 20_000_000_000L;
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("timed out");
            }
            Thread.sleep(20);
        }
    }
}
