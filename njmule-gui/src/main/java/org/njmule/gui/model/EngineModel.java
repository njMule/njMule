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

import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import org.njmule.core.api.DownloadInfo;
import org.njmule.core.api.Ed2kLink;
import org.njmule.core.api.EngineEvent;
import org.njmule.core.api.EngineStatus;
import org.njmule.core.api.Hash16;
import org.njmule.core.api.ImportOptions;
import org.njmule.core.api.ImportReport;
import org.njmule.core.api.LogLine;
import org.njmule.core.api.NjMuleEngine;
import org.njmule.core.api.SearchId;
import org.njmule.core.api.SearchRequest;
import org.njmule.core.api.SearchResult;
import org.njmule.core.api.ServerAddress;
import org.njmule.core.api.ServerInfo;
import org.njmule.core.api.SharedFileInfo;
import org.njmule.core.api.UploadInfo;
import org.njmule.core.config.Preferences;
import org.njmule.core.config.Schedule;

/**
 * The engine as the interface sees it: observable state on the FX thread, refreshed from the
 * engine's events and by polling once a second, and the commands the screens send. The engine is
 * never waited for on the FX thread.
 */
public final class EngineModel implements Flow.Subscriber<EngineEvent>, AutoCloseable {

    /** The state of the eD2K server connection. */
    public enum Connection {
        DISCONNECTED,
        CONNECTING,
        CONNECTED
    }

    /** The rates of one second, for the graphs. */
    public record RateSample(Instant time, long down, long up) {}

    /** One search with its results. */
    public static final class Search {
        private final SearchId id;
        private final SearchRequest request;
        private final LiveList<Hash16, SearchResult> results = new LiveList<>(SearchResult::hash);
        private final SimpleObjectProperty<Boolean> running = new SimpleObjectProperty<>(true);

        Search(SearchId id, SearchRequest request) {
            this.id = id;
            this.request = request;
        }

        public SearchId id() {
            return this.id;
        }

        public SearchRequest request() {
            return this.request;
        }

        public LiveList<Hash16, SearchResult> results() {
            return this.results;
        }

        public ReadOnlyObjectProperty<Boolean> runningProperty() {
            return this.running;
        }
    }

    /** Lines of the log pane kept at most. */
    public static final int LOG_LINES = 5_000;

    /** Seconds of rate history kept for the graphs. */
    public static final int RATE_HISTORY = 3_600;

    private static final EngineStatus NO_STATUS =
            new EngineStatus(
                    Optional.empty(), 0, false, false, Optional.empty(), 0, 0, 0, 0, 0, 0, 0, 0);

    private final NjMuleEngine engine;
    private final Executor fx;
    private final ObjectProperty<EngineStatus> status = new SimpleObjectProperty<>(NO_STATUS);
    private final ObjectProperty<Connection> connection =
            new SimpleObjectProperty<>(Connection.DISCONNECTED);
    private final StringProperty serverName = new SimpleStringProperty("");
    private final StringProperty lastLog = new SimpleStringProperty("");
    private final ObjectProperty<Preferences> preferences;
    private final ObservableList<LogLine> log = FXCollections.observableArrayList();
    private final ObservableList<RateSample> rates = FXCollections.observableArrayList();
    private final LiveList<String, ServerInfo> servers = new LiveList<>(ServerInfo::address);
    private final LiveList<Hash16, DownloadInfo> downloads = new LiveList<>(DownloadInfo::hash);
    private final LiveList<String, UploadInfo> uploads =
            new LiveList<>(u -> u.userHash() + "@" + u.address());
    private final LiveList<Path, SharedFileInfo> shared = new LiveList<>(SharedFileInfo::path);
    private final Map<SearchId, Search> searches = new LinkedHashMap<>();
    private Consumer<String> errors = message -> {};
    private Consumer<DownloadInfo> completed = download -> {};
    private Flow.Subscription subscription;
    private long ticks;

    /**
     * @param fx runs tasks on the FX thread (in tests, any thread)
     */
    public EngineModel(NjMuleEngine engine, Executor fx) {
        this.engine = Objects.requireNonNull(engine, "engine");
        this.fx = Objects.requireNonNull(fx, "fx");
        this.preferences = new SimpleObjectProperty<>(engine.preferences());
    }

    /** Subscribes to the engine's events and reads the state a first time. */
    public void start() {
        this.engine.events().subscribe(this);
        this.refreshServers();
        this.refreshShared();
        this.tick();
    }

    /** Where failed commands are told; called on the FX thread. */
    public void onError(Consumer<String> sink) {
        this.errors = Objects.requireNonNull(sink, "sink");
    }

    /** Where finished downloads are told; called on the FX thread. */
    public void onCompleted(Consumer<DownloadInfo> sink) {
        this.completed = Objects.requireNonNull(sink, "sink");
    }

    /** Runs once a second on the FX thread: polls what events do not tell. */
    public void tick() {
        this.ticks++;
        this.later(
                this.engine.status(),
                s -> {
                    this.status.set(s);
                    this.rates.add(
                            new RateSample(
                                    Instant.now(),
                                    s.downloadBytesPerSecond(),
                                    s.uploadBytesPerSecond()));
                    if (this.rates.size() > RATE_HISTORY) {
                        this.rates.remove(0, this.rates.size() - RATE_HISTORY);
                    }
                });
        this.later(this.engine.downloads(), this.downloads::update);
        if (this.ticks % 2 == 0) {
            this.later(this.engine.uploads(), this.uploads::update);
        }
        if (this.ticks % 10 == 0) {
            this.refreshServers();
        }
        if (this.ticks % 15 == 0) {
            this.refreshShared();
        }
    }

    public NjMuleEngine engine() {
        return this.engine;
    }

    public ReadOnlyObjectProperty<EngineStatus> statusProperty() {
        return this.status;
    }

    public ReadOnlyObjectProperty<Connection> connectionProperty() {
        return this.connection;
    }

    /** The server being connected to or connected to; empty when none. */
    public ReadOnlyStringProperty serverNameProperty() {
        return this.serverName;
    }

    public ReadOnlyStringProperty lastLogProperty() {
        return this.lastLog;
    }

    public ReadOnlyObjectProperty<Preferences> preferencesProperty() {
        return this.preferences;
    }

    public ObservableList<LogLine> log() {
        return this.log;
    }

    public ObservableList<RateSample> rates() {
        return this.rates;
    }

    public LiveList<String, ServerInfo> servers() {
        return this.servers;
    }

    public LiveList<Hash16, DownloadInfo> downloads() {
        return this.downloads;
    }

    public LiveList<String, UploadInfo> uploads() {
        return this.uploads;
    }

    public LiveList<Path, SharedFileInfo> sharedFiles() {
        return this.shared;
    }

    // Commands: errors go to the error sink, results come back on the FX thread.

    public void connect(Optional<ServerAddress> server) {
        this.connection.set(Connection.CONNECTING);
        this.run(this.engine.connect(server));
    }

    public void disconnect() {
        this.run(this.engine.disconnect());
    }

    public void addServers(Path serverMet) {
        this.later(this.engine.addServers(serverMet), added -> this.refreshServers());
    }

    public void removeServer(ServerAddress server) {
        this.later(this.engine.removeServer(server), removed -> this.refreshServers());
    }

    public void addDownload(Ed2kLink.FileLink link) {
        this.later(this.engine.addDownload(link), this.downloads::put);
    }

    public void pause(Hash16 file) {
        this.later(this.engine.pauseDownload(file), ok -> this.refreshDownloads());
    }

    public void resume(Hash16 file) {
        this.later(this.engine.resumeDownload(file), ok -> this.refreshDownloads());
    }

    public void cancel(Hash16 file) {
        this.later(this.engine.cancelDownload(file), ok -> this.downloads.remove(file));
    }

    public void shareDirectory(Path directory) {
        this.later(this.engine.shareDirectory(directory), ok -> this.refreshShared());
    }

    public void kadBootstrap(ServerAddress node) {
        this.run(this.engine.kadBootstrap(node));
    }

    /** Starts a search; {@code started} gets it on the FX thread. */
    public void search(SearchRequest request, Consumer<Search> started) {
        this.later(this.engine.search(request), id -> started.accept(this.open(id, request)));
    }

    /** Follows the results of a search the engine started. */
    Search open(SearchId id, SearchRequest request) {
        Search search = new Search(id, request);
        this.searches.put(id, search);
        return search;
    }

    public void moreResults(Search search) {
        this.later(
                this.engine.moreResults(search.id()),
                more -> {
                    if (more) {
                        search.running.set(true);
                    }
                });
    }

    public void stopSearch(Search search) {
        search.running.set(false);
        this.searches.remove(search.id());
        this.run(this.engine.stopSearch(search.id()));
    }

    public void updatePreferences(UnaryOperator<Preferences> change, Consumer<Preferences> done) {
        this.later(
                this.engine.updatePreferences(change),
                p -> {
                    this.preferences.set(p);
                    done.accept(p);
                });
    }

    public void updateSchedule(Schedule schedule, Consumer<Schedule> done) {
        this.later(this.engine.updateSchedule(schedule), done);
    }

    public void importFromEmule(Path dir, ImportOptions options, Consumer<ImportReport> done) {
        this.later(
                this.engine.importFromEmule(dir, options),
                report -> {
                    done.accept(report);
                    this.preferences.set(this.engine.preferences());
                    this.refreshServers();
                    this.refreshShared();
                });
    }

    @Override
    public void onSubscribe(Flow.Subscription subscription) {
        this.subscription = subscription;
        subscription.request(Long.MAX_VALUE);
    }

    @Override
    public void onNext(EngineEvent event) {
        this.fx.execute(() -> this.handle(event));
    }

    @Override
    public void onError(Throwable throwable) {
        this.fx.execute(() -> this.errors.accept(String.valueOf(throwable.getMessage())));
    }

    @Override
    public void onComplete() {
        this.fx.execute(() -> this.connection.set(Connection.DISCONNECTED));
    }

    @Override
    public void close() {
        if (this.subscription != null) {
            this.subscription.cancel();
        }
    }

    void handle(EngineEvent event) {
        switch (event) {
            case EngineEvent.Log e -> this.addLog(e.line());
            case EngineEvent.ServerConnecting e -> {
                this.connection.set(Connection.CONNECTING);
                this.serverName.set(e.server().name());
            }
            case EngineEvent.ServerConnected e -> {
                this.connection.set(Connection.CONNECTED);
                this.serverName.set(e.server().name());
                this.refreshServers();
            }
            case EngineEvent.ServerDisconnected e -> {
                this.connection.set(Connection.DISCONNECTED);
                this.serverName.set("");
            }
            case EngineEvent.ServerUpdated e -> this.servers.put(e.server());
            case EngineEvent.SearchResults e -> {
                Search search = this.searches.get(e.search());
                if (search != null) {
                    e.results().forEach(search.results::put);
                }
            }
            case EngineEvent.SearchEnded e -> {
                Search search = this.searches.get(e.search());
                if (search != null) {
                    search.running.set(false);
                }
            }
            case EngineEvent.DownloadAdded e -> this.downloads.put(e.download());
            case EngineEvent.DownloadProgress e -> this.downloads.put(e.download());
            case EngineEvent.DownloadCompleted e -> {
                this.downloads.remove(e.download().hash());
                this.refreshShared();
                this.completed.accept(e.download());
            }
            case EngineEvent.DownloadFailed e -> this.downloads.put(e.download());
            case EngineEvent.DownloadRemoved e -> this.downloads.remove(e.hash());
            case EngineEvent.PreferencesChanged e -> this.preferences.set(e.preferences());
            default -> {
                // Started, stopping and server messages show through the log.
            }
        }
    }

    private void addLog(LogLine line) {
        this.log.add(line);
        if (this.log.size() > LOG_LINES) {
            this.log.remove(0, this.log.size() - LOG_LINES);
        }
        if (line.level() != LogLine.Level.DEBUG) {
            this.lastLog.set(line.message());
        }
    }

    private void refreshServers() {
        this.later(this.engine.servers(), this.servers::update);
    }

    private void refreshShared() {
        this.later(this.engine.sharedFiles(), this.shared::update);
    }

    private void refreshDownloads() {
        this.later(this.engine.downloads(), this.downloads::update);
    }

    private void run(CompletableFuture<?> work) {
        this.later(work, done -> {});
    }

    private <T> void later(CompletableFuture<T> work, Consumer<T> then) {
        work.whenComplete(
                (value, failure) ->
                        this.fx.execute(
                                () -> {
                                    if (failure != null) {
                                        this.errors.accept(message(failure));
                                        if (this.connection.get() == Connection.CONNECTING
                                                && this.serverName.get().isEmpty()) {
                                            this.connection.set(Connection.DISCONNECTED);
                                        }
                                    } else {
                                        then.accept(value);
                                    }
                                }));
    }

    private static String message(Throwable failure) {
        Throwable cause =
                failure instanceof CompletionException && failure.getCause() != null
                        ? failure.getCause()
                        : failure;
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }

    /** The searches that run now, for tests. */
    List<Search> searches() {
        return List.copyOf(this.searches.values());
    }
}
