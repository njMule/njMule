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

import java.net.URI;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.UnaryOperator;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.ReadOnlyIntegerProperty;
import javafx.beans.property.ReadOnlyIntegerWrapper;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import org.njmule.core.api.BanInfo;
import org.njmule.core.api.Category;
import org.njmule.core.api.DownloadInfo;
import org.njmule.core.api.DownloadPriority;
import org.njmule.core.api.Ed2kLink;
import org.njmule.core.api.EngineEvent;
import org.njmule.core.api.EngineStatus;
import org.njmule.core.api.FileComment;
import org.njmule.core.api.FileHistory;
import org.njmule.core.api.Friend;
import org.njmule.core.api.Hash16;
import org.njmule.core.api.ImportOptions;
import org.njmule.core.api.ImportReport;
import org.njmule.core.api.KadContactInfo;
import org.njmule.core.api.KadNote;
import org.njmule.core.api.KadSearchInfo;
import org.njmule.core.api.LogLine;
import org.njmule.core.api.NewVersion;
import org.njmule.core.api.NjMuleEngine;
import org.njmule.core.api.NjMuleInfo;
import org.njmule.core.api.PeerComment;
import org.njmule.core.api.PortTest;
import org.njmule.core.api.SavedSearch;
import org.njmule.core.api.SearchId;
import org.njmule.core.api.SearchRequest;
import org.njmule.core.api.SearchResult;
import org.njmule.core.api.ServerAddress;
import org.njmule.core.api.ServerInfo;
import org.njmule.core.api.SharedDirectory;
import org.njmule.core.api.SharedFileInfo;
import org.njmule.core.api.SourceInfo;
import org.njmule.core.api.SpamMarks;
import org.njmule.core.api.UpdateStatus;
import org.njmule.core.api.UploadInfo;
import org.njmule.core.api.UploadPriority;
import org.njmule.core.config.Preferences;
import org.njmule.core.config.Schedule;

/**
 * The engine as the interface sees it: observable state on the FX thread, refreshed from the engine's events and by
 * polling once a second, and the commands the screens send. The engine is never waited for on the FX thread.
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
    // The results and the end of a search heard of before its id.
    private static final class Early {
        private final List<SearchResult> results = new ArrayList<>();
        private boolean ended;
    }

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

    /** Minutes of mean rates kept, for the graph of the last day. */
    public static final int RATE_MINUTES = 1_440;

    // Searches kept from before their id came back: a few, as those started elsewhere never open here.
    private static final int EARLY_SEARCHES = 4;
    private static final EngineStatus NO_STATUS = new EngineStatus(
            Optional.empty(), 0, false, false, Optional.empty(), 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, "", 0, false);

    private final NjMuleEngine engine;
    private final Executor fx;
    private final ObjectProperty<EngineStatus> status = new SimpleObjectProperty<>(NO_STATUS);
    private final ObjectProperty<SpamMarks> spamMarks = new SimpleObjectProperty<>(SpamMarks.NONE);
    private final ObjectProperty<FileHistory> history = new SimpleObjectProperty<>(FileHistory.NONE);
    private final ObjectProperty<Optional<Hash16>> friendSlot = new SimpleObjectProperty<>(Optional.empty());
    private final ObjectProperty<Connection> connection = new SimpleObjectProperty<>(Connection.DISCONNECTED);
    private final StringProperty serverName = new SimpleStringProperty("");
    private final StringProperty lastLog = new SimpleStringProperty("");
    private final ObjectProperty<Preferences> preferences;
    private final ObservableList<LogLine> log = FXCollections.observableArrayList();
    private final ObservableList<ServerNote> serverInfo = FXCollections.observableArrayList(
            new ServerNote(Instant.now(), ServerNote.Kind.TITLE, NjMuleInfo.NAME + " " + NjMuleInfo.version()));
    private final StringProperty serverAddress = new SimpleStringProperty("");
    private final ObservableList<Category> categories = FXCollections.observableArrayList();
    // One view for all: the list holds its views only weakly, so a view made per call loses its listeners in a GC.
    private final ObservableList<Category> categoriesView = FXCollections.unmodifiableObservableList(this.categories);
    private final ObservableList<RateSample> rates = FXCollections.observableArrayList();
    private final ObservableList<RateSample> minuteRates = FXCollections.observableArrayList();
    private long minuteDown;
    private long minuteUp;
    private int minuteSamples;
    private final LiveList<String, ServerInfo> servers = new LiveList<>(ServerInfo::address);
    private final LiveList<String, KadContactInfo> kadContacts = new LiveList<>(KadContactInfo::id);
    private final LiveList<String, KadSearchInfo> kadSearches = new LiveList<>(s -> s.kind() + ":" + s.target());
    private final LiveList<Hash16, DownloadInfo> downloads = new LiveList<>(DownloadInfo::hash);
    private final LiveList<String, UploadInfo> uploads = new LiveList<>(u -> u.userHash() + "@" + u.address());
    private final LiveList<String, SourceInfo> sources =
            new LiveList<>(s -> s.file().toHex() + "@" + s.address());
    private final LiveList<String, BanInfo> bans = new LiveList<>(BanInfo::address);
    private final LiveList<Path, SharedFileInfo> shared = new LiveList<>(SharedFileInfo::path);
    // The hashes of the shared files, built again after the list changed.
    private Set<Hash16> sharedHashes;
    private final Map<SearchId, Search> searches = new LinkedHashMap<>();
    // Every search whose results show, running, ended or kept from the last run: a closed one goes with its tab.
    private final Set<Search> shown = Collections.newSetFromMap(new WeakHashMap<>());
    // What a server that answers at once sends before the FX thread hears of the search it was asked for.
    private final Map<SearchId, Early> early = new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<SearchId, Early> eldest) {
            return size() > EARLY_SEARCHES;
        }
    };
    private final Chats chats = new Chats();
    private final LiveList<Hash16, Friend> friends = new LiveList<>(Friend::userHash);
    private Consumer<String> errors = message -> {};
    private Consumer<DownloadInfo> completed = download -> {};
    private Consumer<NewVersion> newVersion = version -> {};
    private final ObjectProperty<UpdateStatus> update;
    private final ReadOnlyIntegerWrapper completedCount = new ReadOnlyIntegerWrapper();
    private Flow.Subscription subscription;
    private long ticks;
    // The poll whose status is shown.
    private long statusPoll;
    private long maxDown;
    private long maxUp;
    // Restored searches have ids of their own, below those of the engine.
    private long restoredIds;

    /** @param fx runs tasks on the FX thread (in tests, any thread) */
    public EngineModel(NjMuleEngine engine, Executor fx) {
        this.engine = Objects.requireNonNull(engine, "engine");
        this.fx = Objects.requireNonNull(fx, "fx");
        this.shared.rows().addListener((ListChangeListener<LiveList.Row<SharedFileInfo>>)
                c -> this.sharedHashes = null);
        this.preferences = new SimpleObjectProperty<>(engine.preferences());
        this.update = new SimpleObjectProperty<>(engine.updateStatus());
    }

    /** Subscribes to the engine's events and reads the state a first time. */
    public void start() {
        later(this.engine.spamMarks(), this.spamMarks::set);
        this.engine.events().subscribe(this);
        refreshServers();
        refreshShared();
        refreshFriends();
        refreshCategories();
        tick();
    }

    /** The download categories, in the order of their ids. */
    public ObservableList<Category> categories() {
        return this.categoriesView;
    }

    /** Replaces the categories, then runs {@code done}; a refusal shows as an error. */
    public void setCategories(List<Category> categories, Runnable done) {
        later(this.engine.setCategories(categories).thenCompose(ok -> this.engine.categories()), now -> {
            this.categories.setAll(now);
            refreshDownloads();
            done.run();
        });
    }

    /** Puts a download in a category; {@link Category#NONE} takes it out of any. */
    public void setDownloadCategory(Hash16 file, int category) {
        later(this.engine.setDownloadCategory(file, category), ok -> refreshDownloads());
    }

    private void refreshCategories() {
        later(this.engine.categories(), this.categories::setAll);
    }

    /** Where failed commands are told; called on the FX thread. */
    public void onError(Consumer<String> sink) {
        this.errors = Objects.requireNonNull(sink, "sink");
    }

    /** Where finished downloads are told; called on the FX thread. */
    public void onCompleted(Consumer<DownloadInfo> sink) {
        this.completed = Objects.requireNonNull(sink, "sink");
    }

    /** Where a new version that njMule found on its own is told, once a run; called on the FX thread. */
    public void onNewVersion(Consumer<NewVersion> sink) {
        this.newVersion = Objects.requireNonNull(sink, "sink");
    }

    /** What is known of new versions of njMule. */
    public ReadOnlyObjectProperty<UpdateStatus> updateProperty() {
        return this.update;
    }

    /** Looks for a new version now; the status tells the outcome. */
    public void checkForUpdate() {
        later(this.engine.checkForUpdate(), this.update::set);
    }

    /** Stops telling of {@code version}, unless it fixes a security problem. */
    public void skipUpdate(String version) {
        later(this.engine.skipUpdate(version), this.update::set);
    }

    /** Runs once a second on the FX thread: polls what events do not tell. */
    public void tick() {
        long poll = ++this.ticks;
        later(this.engine.status(), s -> {
            // A slow answer to an earlier poll must not hide the newer state already shown.
            if (poll < this.statusPoll) {
                return;
            }
            this.statusPoll = poll;
            this.status.set(s);
            this.maxDown = Math.max(this.maxDown, s.downloadBytesPerSecond());
            this.maxUp = Math.max(this.maxUp, s.uploadBytesPerSecond());
            RateSample sample = new RateSample(Instant.now(), s.downloadBytesPerSecond(), s.uploadBytesPerSecond());
            this.rates.add(sample);
            if (this.rates.size() > RATE_HISTORY) {
                this.rates.remove(0, this.rates.size() - RATE_HISTORY);
            }
            addToMinute(sample);
        });
        later(this.engine.downloads(), this::showDownloads);
        if (this.ticks % 2 == 0) {
            later(this.engine.uploads(), this.uploads::update);
            later(this.engine.sources(), this.sources::update);
            later(this.engine.bans(), this.bans::update);
        }
        if (this.ticks % 10 == 0) {
            refreshServers();
        }
        if (this.ticks % 15 == 0) {
            refreshShared();
        }
    }

    public NjMuleEngine engine() {
        return this.engine;
    }

    public ReadOnlyObjectProperty<EngineStatus> statusProperty() {
        return this.status;
    }

    /** Downloads finished since the start. */
    public ReadOnlyIntegerProperty completedProperty() {
        return this.completedCount.getReadOnlyProperty();
    }

    public ReadOnlyObjectProperty<Connection> connectionProperty() {
        return this.connection;
    }

    /** The address of the server connected to or being tried, empty without one. */
    public ReadOnlyStringProperty serverAddressProperty() {
        return this.serverAddress;
    }

    /** What the servers said: each session's start, then the lines its server sent. */
    public ObservableList<ServerNote> serverInfo() {
        return this.serverInfo;
    }

    public void clearServerInfo() {
        this.serverInfo.clear();
    }

    public void clearLog() {
        this.log.clear();
    }

    /** Starts Kad from the known contacts, or looks for contacts at once when it runs. */
    public void connectKad() {
        later(this.engine.connectKad(), ok -> tick());
    }

    public void disconnectKad() {
        later(this.engine.disconnectKad(), ok -> tick());
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

    /** The highest download rate of this session, in bytes per second. */
    public long maxDownloadRate() {
        return this.maxDown;
    }

    public long maxUploadRate() {
        return this.maxUp;
    }

    // Sixty samples, one a second, make the mean rates of a minute.
    private void addToMinute(RateSample sample) {
        this.minuteDown += sample.down();
        this.minuteUp += sample.up();
        if (++this.minuteSamples < 60) {
            return;
        }
        this.minuteRates.add(new RateSample(sample.time(), this.minuteDown / 60, this.minuteUp / 60));
        if (this.minuteRates.size() > RATE_MINUTES) {
            this.minuteRates.remove(0, this.minuteRates.size() - RATE_MINUTES);
        }
        this.minuteDown = 0;
        this.minuteUp = 0;
        this.minuteSamples = 0;
    }

    /** The mean rates of each minute, the oldest first. */
    public ObservableList<RateSample> minuteRates() {
        return this.minuteRates;
    }

    public ObservableList<RateSample> rates() {
        return this.rates;
    }

    public LiveList<String, ServerInfo> servers() {
        return this.servers;
    }

    /** Whether {@code file} is one of our downloads. */
    public boolean isDownloading(Hash16 file) {
        return this.downloads.contains(file);
    }

    /** Whether we share {@code file}. */
    public boolean isShared(Hash16 file) {
        if (this.sharedHashes == null) {
            Set<Hash16> hashes = new HashSet<>();
            for (LiveList.Row<SharedFileInfo> row : this.shared.rows()) {
                hashes.add(row.get().hash());
            }
            this.sharedHashes = hashes;
        }
        return this.sharedHashes.contains(file);
    }

    public LiveList<Hash16, DownloadInfo> downloads() {
        return this.downloads;
    }

    /** The files downloaded or cancelled before, as last read. */
    public ReadOnlyObjectProperty<FileHistory> historyProperty() {
        return this.history;
    }

    /** The spam markers, as last read; {@link #markSpam} changes them. */
    public ReadOnlyObjectProperty<SpamMarks> spamMarksProperty() {
        return this.spamMarks;
    }

    /** Marks results as spam, or clears them; the results of every search shown are rated again. */
    public void markSpam(List<SearchResult> results, boolean isSpam) {
        later(this.engine.markSpam(results, isSpam), marks -> {
            this.spamMarks.set(marks);
            rateShown();
        });
    }

    // Only the ratings change: what came for a result meanwhile stays.
    private void rateShown() {
        for (Search search : List.copyOf(this.shown)) {
            rate(search);
        }
    }

    private void rate(Search search) {
        List<SearchResult> held =
                search.results.rows().stream().map(LiveList.Row::get).toList();
        if (held.isEmpty()) {
            return;
        }
        later(this.engine.rateSpam(held), rated -> {
            for (SearchResult r : rated) {
                search.results
                        .find(r.hash())
                        .filter(now -> now.spamRating() != r.spamRating())
                        .ifPresent(now -> search.results.put(now.withSpamRating(r.spamRating())));
            }
        });
    }

    public LiveList<String, SourceInfo> sources() {
        return this.sources;
    }

    public LiveList<String, BanInfo> bans() {
        return this.bans;
    }

    public void unban(String ip) {
        later(this.engine.unban(ip), lifted -> later(this.engine.bans(), this.bans::update));
    }

    public LiveList<String, UploadInfo> uploads() {
        return this.uploads;
    }

    public LiveList<Path, SharedFileInfo> sharedFiles() {
        return this.shared;
    }

    public Chats chats() {
        return this.chats;
    }

    public LiveList<Hash16, Friend> friends() {
        return this.friends;
    }

    /** Sends {@code text} to the client at {@code address} ("ip:port"); it shows at once. */
    public void sendMessage(String address, String text) {
        if (text.isBlank()) {
            return;
        }
        ServerAddress to;
        try {
            to = ServerAddress.parse(address);
        } catch (IllegalArgumentException e) {
            this.errors.accept(e.getMessage());
            return;
        }
        this.chats.sent(address, text);
        later(this.engine.sendMessage(to, text), done -> {});
    }

    public void addFriend(Friend friend) {
        later(this.engine.addFriend(friend), done -> refreshFriends());
    }

    public void removeFriend(Hash16 userHash) {
        later(this.engine.removeFriend(userHash), done -> refreshFriends());
    }

    /** The friend with the friend slot, as last read. */
    public ReadOnlyObjectProperty<Optional<Hash16>> friendSlotProperty() {
        return this.friendSlot;
    }

    /** Gives the friend slot to {@code friend}, or to nobody. */
    public void setFriendSlot(Optional<Hash16> friend) {
        later(this.engine.setFriendSlot(friend), done -> refreshFriends());
    }

    private void refreshFriends() {
        later(this.engine.friends(), this.friends::update);
        later(this.engine.friendSlot(), this.friendSlot::set);
    }

    // Commands: errors go to the error sink, results come back on the FX thread.

    public void connect(Optional<ServerAddress> server) {
        Connection before = this.connection.get();
        this.connection.set(Connection.CONNECTING);
        later(this.engine.connect(server), done -> {}, failure -> {
            this.errors.accept(failure);
            // The engine started nothing and kept the connection it had, which the events then still tell.
            if (this.connection.get() == Connection.CONNECTING) {
                this.connection.set(before);
            }
        });
    }

    public void disconnect() {
        run(this.engine.disconnect());
    }

    public void addServers(Path serverMet) {
        later(this.engine.addServers(serverMet), added -> refreshServers());
    }

    public void removeServer(ServerAddress server) {
        later(this.engine.removeServer(server), removed -> refreshServers());
    }

    public void serverListAddresses(Consumer<List<URI>> done) {
        later(this.engine.serverListAddresses(), done);
    }

    public void setServerListAddresses(List<URI> addresses) {
        run(this.engine.setServerListAddresses(addresses));
    }

    public void removeServers(List<ServerAddress> servers) {
        later(this.engine.removeServers(servers), removed -> refreshServers());
    }

    public void addServer(ServerAddress server) {
        later(this.engine.addServer(server), added -> refreshServers());
    }

    public void setServerPriority(ServerAddress server, ServerInfo.Priority priority) {
        later(this.engine.setServerPriority(server, priority), done -> refreshServers());
    }

    public void setStaticServer(ServerAddress server, boolean isStatic) {
        later(this.engine.setStaticServer(server, isStatic), done -> refreshServers());
    }

    public void addDownload(Ed2kLink.FileLink link) {
        addDownload(link, added -> {});
    }

    /** Adds a download in {@code category}, or in that of its name when empty, and paused when told. */
    public void addDownload(Ed2kLink.FileLink link, OptionalInt category, boolean paused) {
        later(this.engine.addDownload(link, category, paused), this.downloads::put);
    }

    /** Adds a download; {@code added} learns of it on the FX thread once the engine took it. */
    public void addDownload(Ed2kLink.FileLink link, Consumer<DownloadInfo> added) {
        later(this.engine.addDownload(link), download -> {
            this.downloads.put(download);
            added.accept(download);
        });
    }

    public void pause(Hash16 file) {
        later(this.engine.pauseDownload(file), ok -> refreshDownloads());
    }

    public void resume(Hash16 file) {
        later(this.engine.resumeDownload(file), ok -> refreshDownloads());
    }

    /** Pauses the download and lets it forget its sources. */
    public void stop(Hash16 file) {
        later(this.engine.stopDownload(file), ok -> refreshDownloads());
    }

    public void setPriority(Hash16 file, DownloadPriority priority) {
        later(this.engine.setDownloadPriority(file, priority), ok -> refreshDownloads());
    }

    /** Takes the finished downloads off the list. */
    public void clearCompleted() {
        this.downloads.rows().stream()
                .map(LiveList.Row::get)
                .filter(d -> d.status() == DownloadInfo.Status.COMPLETE)
                .map(DownloadInfo::hash)
                .toList()
                .forEach(this.downloads::remove);
    }

    public void cancel(Hash16 file) {
        later(this.engine.cancelDownload(file), ok -> this.downloads.remove(file));
    }

    /** Takes back a download cancelled moments ago; it comes back with the engine's event. */
    public void restore(Hash16 file) {
        later(this.engine.restoreDownload(file), ok -> {});
    }

    /** Renames a download; it shows the new name with the engine's news of it. */
    public void renameDownload(Hash16 file, String name) {
        later(this.engine.renameDownload(file, name), ok -> {});
    }

    /** Renames a shared file; a name taken already is told through the errors. */
    public void renameShared(Hash16 file, String name) {
        later(this.engine.renameSharedFile(file, name), ok -> refreshShared());
    }

    /** Our comment on a file; {@code then} gets it on the FX thread when there is one. */
    public void fileComment(Hash16 file, Consumer<FileComment> then) {
        later(this.engine.fileComment(file), c -> c.ifPresent(then));
    }

    public void setFileComment(Hash16 file, FileComment comment, Runnable done) {
        later(this.engine.setFileComment(file, comment), ok -> done.run());
    }

    /** What the sources of a download said of the file; {@code then} gets it on the FX thread. */
    public void peerComments(Hash16 download, Consumer<List<PeerComment>> then) {
        later(this.engine.peerComments(download), then);
    }

    /** What Kad nodes said of a file, as far as found; {@code then} gets it on the FX thread. */
    public void kadNotes(Hash16 file, Consumer<List<KadNote>> then) {
        later(this.engine.kadNotes(file), then);
    }

    /**
     * Searches Kad for what nodes said of a file; {@code found} gets all the notes known once the search ends, or
     * {@code failed} why it could not run, on the FX thread.
     */
    public void searchKadNotes(Hash16 file, Consumer<List<KadNote>> found, Consumer<String> failed) {
        later(this.engine.searchKadNotes(file), found, failed);
    }

    /** Adds a source by hand; {@code added} tells on the FX thread whether it was new. */
    public void addSource(Hash16 download, ServerAddress source, Consumer<Boolean> added) {
        later(this.engine.addSource(download, source), added);
    }

    /** Fetches the first and last parts of a download first, for a preview, or stops. */
    public void setPreviewFirst(Hash16 file, boolean value) {
        later(this.engine.setDownloadPreview(file, value), ok -> refreshDownloads());
    }

    /** Swaps the A4AF sources of a download to it; {@code swapped} tells on the FX thread how many moved. */
    public void swapSourcesHere(Hash16 file, Consumer<Integer> swapped) {
        later(this.engine.swapSourcesToDownload(file), count -> {
            refreshSources();
            swapped.accept(count);
        });
    }

    /** Swaps the sources asked for a download to other downloads; {@code swapped} tells how many moved. */
    public void swapSourcesAway(Hash16 file, Consumer<Integer> swapped) {
        later(this.engine.swapSourcesFromDownload(file), count -> {
            refreshSources();
            swapped.accept(count);
        });
    }

    /** Has a download take its A4AF sources whenever they are free to move, or stops. */
    public void setAutoSwap(Hash16 file, boolean value) {
        later(this.engine.setDownloadAutoSwap(file, value), ok -> {
            refreshDownloads();
            refreshSources();
        });
    }

    /** Swaps one A4AF source of a download to it; {@code swapped} tells on the FX thread whether it moved. */
    public void swapSource(Hash16 file, ServerAddress source, Consumer<Boolean> swapped) {
        later(this.engine.swapSourceToDownload(file, source), moved -> {
            refreshSources();
            swapped.accept(moved);
        });
    }

    private void refreshSources() {
        later(this.engine.sources(), this.sources::update);
    }

    /**
     * The data file of a download, for a player; {@code then} gets it on the FX thread, empty for an unknown download
     * and until the start of the file has come.
     */
    public void downloadData(Hash16 file, Consumer<Optional<Path>> then) {
        later(this.engine.downloadData(file), then);
    }

    public void setUploadPriority(Hash16 file, UploadPriority priority) {
        later(this.engine.setUploadPriority(file, priority), ok -> refreshShared());
    }

    public void shareDirectory(Path directory) {
        shareDirectory(directory, false);
    }

    /** Shares {@code directory} with its subdirectories or alone. */
    public void shareDirectory(Path directory, boolean subdirectories) {
        later(this.engine.shareDirectory(directory, subdirectories), ok -> refreshShared());
    }

    /** Stops sharing {@code directory}, then runs {@code done}. */
    public void unshareDirectory(Path directory, Runnable done) {
        later(this.engine.unshareDirectory(directory), ok -> {
            refreshShared();
            done.run();
        });
    }

    /** Hands the shared directories to {@code sink}, on the FX thread. */
    public void sharedDirectories(Consumer<List<SharedDirectory>> sink) {
        later(this.engine.sharedDirectories(), sink);
    }

    /** Adds the servers of the server.met at {@code url}, then hands {@code done} how many were new. */
    public void addServers(URI url, IntConsumer done) {
        later(this.engine.addServers(url), added -> {
            refreshServers();
            done.accept(added);
        });
    }

    /** Replaces the IP filter with the list at {@code url}, then hands {@code done} how many ranges it has. */
    public void updateIpFilter(URI url, IntConsumer done) {
        later(this.engine.updateIpFilter(url), done::accept);
    }

    /** Replaces the IP filter with the lists at {@code urls} joined; {@code done} learns how many ranges it has. */
    public void updateIpFilter(List<URI> urls, IntConsumer done) {
        later(this.engine.updateIpFilter(urls), done::accept);
    }

    /** Adds the Kad contacts of the nodes.dat at {@code url}, then hands {@code done} how many were new. */
    public void addKadContacts(URI url, IntConsumer done) {
        later(this.engine.addKadContacts(url), done::accept);
    }

    /** Asks a client for its shared files; {@code found} or {@code failed} gets the outcome on the FX thread. */
    // The follow-up only hands the outcome to the FX thread, which shows a failure itself.
    @SuppressWarnings("FutureReturnValueIgnored")
    public void browse(ServerAddress client, Consumer<List<SearchResult>> found, Consumer<String> failed) {
        this.engine
                .browseSharedFiles(client)
                .whenComplete((files, failure) -> this.fx.execute(() -> {
                    if (failure == null) {
                        found.accept(files);
                    } else {
                        failed.accept(message(failure));
                    }
                }));
    }

    /** The Kad contacts, as last read with {@link #refreshKadLists}. */
    public LiveList<String, KadContactInfo> kadContacts() {
        return this.kadContacts;
    }

    public LiveList<String, KadSearchInfo> kadSearches() {
        return this.kadSearches;
    }

    /** Reads the Kad contacts and lookups again; the Kad screen asks while it shows. */
    public void refreshKadLists() {
        later(this.engine.kadContacts(), this.kadContacts::update);
        later(this.engine.kadSearches(), this.kadSearches::update);
    }

    public void recheckKadFirewall() {
        run(this.engine.recheckKadFirewall());
    }

    /** Tests our ports as the preferences give them; {@code done} gets the outcome on the FX thread. */
    public void testPorts(Consumer<PortTest> done) {
        later(this.engine.testPorts(), done);
    }

    /** Reads how far the test of our ports went, for {@code then} on the FX thread. */
    public void portTest(Consumer<Optional<PortTest>> then) {
        later(this.engine.portTest(), then);
    }

    public void kadBootstrap(ServerAddress node) {
        run(this.engine.kadBootstrap(node));
    }

    /** Starts a search; {@code started} gets it on the FX thread. */
    public void search(SearchRequest request, Consumer<Search> started) {
        later(this.engine.search(request), id -> started.accept(open(id, request)));
    }

    /** Follows the results of a search the engine started. */
    Search open(SearchId id, SearchRequest request) {
        Search search = new Search(id, request);
        Early before = this.early.remove(id);
        if (before != null) {
            before.results.forEach(search.results::put);
            search.running.set(!before.ended);
        }
        this.searches.put(id, search);
        this.shown.add(search);
        return search;
    }

    /** A search kept from the last run: its results, and no more to come. */
    public Search restored(SavedSearch saved) {
        this.restoredIds--;
        Search search = new Search(new SearchId(this.restoredIds), saved.request());
        search.running.set(false);
        search.results.update(saved.results());
        this.shown.add(search);
        rate(search);
        return search;
    }

    /** The searches kept from the last run; {@code then} gets them on the FX thread. */
    public void savedSearches(Consumer<List<SavedSearch>> then) {
        later(this.engine.savedSearches(), then);
    }

    public CompletableFuture<Void> saveSearches(List<SavedSearch> searches) {
        return this.engine.saveSearches(searches);
    }

    public void moreResults(Search search) {
        later(this.engine.moreResults(search.id()), more -> {
            if (more) {
                search.running.set(true);
            }
        });
    }

    public void stopSearch(Search search) {
        search.running.set(false);
        this.searches.remove(search.id());
        run(this.engine.stopSearch(search.id()));
    }

    public void updatePreferences(UnaryOperator<Preferences> change, Consumer<Preferences> done) {
        later(this.engine.updatePreferences(change), p -> {
            Preferences before = this.preferences.get();
            this.preferences.set(p);
            if (before != null && before.searchSpamFilter() != p.searchSpamFilter()) {
                rateShown();
            }
            done.accept(p);
        });
    }

    public void updateSchedule(Schedule schedule, Consumer<Schedule> done) {
        later(this.engine.updateSchedule(schedule), done);
    }

    /** Imports an eMule profile; {@code done} gets the report, or {@code failed} the error, on the FX thread. */
    public void importFromEmule(Path dir, ImportOptions options, Consumer<ImportReport> done, Consumer<String> failed) {
        later(
                this.engine.importFromEmule(dir, options),
                report -> {
                    done.accept(report);
                    this.preferences.set(this.engine.preferences());
                    refreshServers();
                    refreshShared();
                },
                failed);
    }

    @Override
    public void onSubscribe(Flow.Subscription subscription) {
        this.subscription = subscription;
        subscription.request(Long.MAX_VALUE);
    }

    @Override
    public void onNext(EngineEvent event) {
        this.fx.execute(() -> handle(event));
    }

    @Override
    public void onError(Throwable throwable) {
        this.fx.execute(() -> this.errors.accept(message(throwable)));
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

    /** Applies one engine event; FX thread. Events normally arrive through the subscription. */
    public void handle(EngineEvent event) {
        switch (event) {
            case EngineEvent.Log e -> addLog(e.line());
            case EngineEvent.ServerMessage e -> addServerNote(ServerNote.Kind.MESSAGE, e.text());
            case EngineEvent.ServerConnecting e -> {
                this.serverAddress.set(e.server().address());
                this.connection.set(Connection.CONNECTING);
                this.serverName.set(e.server().name());
            }
            case EngineEvent.ServerConnected e -> {
                this.serverAddress.set(e.server().address());
                addServerNote(ServerNote.Kind.GAP, "");
                addServerNote(
                        e.obfuscated() ? ServerNote.Kind.CONNECTED_OBFUSCATED : ServerNote.Kind.CONNECTED,
                        e.server().name() + " (" + e.server().address() + ")");
                this.connection.set(Connection.CONNECTED);
                this.serverName.set(e.server().name());
                refreshServers();
            }
            case EngineEvent.ServerDisconnected e -> {
                this.serverAddress.set("");
                this.connection.set(Connection.DISCONNECTED);
                this.serverName.set("");
            }
            case EngineEvent.ServerUpdated e -> this.servers.put(e.server());
            case EngineEvent.ServersChanged e -> refreshServers();
            case EngineEvent.SearchResults e -> {
                Search search = this.searches.get(e.search());
                if (search != null) {
                    e.results().forEach(search.results::put);
                } else {
                    this.early
                            .computeIfAbsent(e.search(), id -> new Early())
                            .results
                            .addAll(e.results());
                }
            }
            case EngineEvent.SearchEnded e -> {
                Search search = this.searches.get(e.search());
                if (search != null) {
                    search.running.set(false);
                } else {
                    this.early.computeIfAbsent(e.search(), id -> new Early()).ended = true;
                }
            }
            case EngineEvent.DownloadAdded e -> this.downloads.put(e.download());
            case EngineEvent.DownloadProgress e -> this.downloads.put(e.download());
            case EngineEvent.DownloadCompleted e -> {
                this.downloads.put(e.download());
                refreshShared();
                this.completedCount.set(this.completedCount.get() + 1);
                this.completed.accept(e.download());
            }
            case EngineEvent.DownloadFailed e -> this.downloads.put(e.download());
            case EngineEvent.DownloadRemoved e -> {
                this.downloads.remove(e.hash());
                refreshHistory();
            }
            case EngineEvent.PreferencesChanged e -> {
                this.preferences.set(e.preferences());
                refreshHistory();
            }
            case EngineEvent.MessageReceived e -> this.chats.received(e.address(), e.userHash(), e.nick(), e.text());
            case EngineEvent.CaptchaRequested e -> this.chats.captcha(e.address(), e.userHash(), e.image());
            case EngineEvent.CaptchaAnswered e ->
                this.chats.notice(e.address(), e.solved() ? "IDS_CAPTCHASOLVED" : "IDS_CAPTCHAFAILED", "");
            case EngineEvent.MessageFailed e -> this.chats.notice(e.address(), "nj.chat.failed", e.reason());
            case EngineEvent.UpdateStatusChanged e -> this.update.set(e.status());
            case EngineEvent.UpdateAvailable e -> this.newVersion.accept(e.version());
            default -> {
                // Started, stopping and server messages show through the log.
            }
        }
    }

    private void addServerNote(ServerNote.Kind kind, String text) {
        this.serverInfo.add(new ServerNote(Instant.now(), kind, text));
        if (this.serverInfo.size() > LOG_LINES) {
            this.serverInfo.remove(0, this.serverInfo.size() - LOG_LINES);
        }
    }

    /** A line of the server information. */
    public record ServerNote(Instant time, Kind kind, String text) {
        public enum Kind {
            /** njMule and its version, at the top. */
            TITLE,
            /** The empty line before a session. */
            GAP,
            /** A session starts: the server's name and address. */
            CONNECTED,
            CONNECTED_OBFUSCATED,
            /** A line the server sent. */
            MESSAGE
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
        later(this.engine.servers(), this.servers::update);
    }

    // Shared files are known files: the history may have grown too.
    private void refreshShared() {
        later(this.engine.sharedFiles(), this.shared::update);
        refreshHistory();
    }

    private void refreshHistory() {
        later(this.engine.fileHistory(), this.history::set);
    }

    private void refreshDownloads() {
        later(this.engine.downloads(), this::showDownloads);
    }

    // The engine forgets finished downloads; they stay listed until cleared.
    private void showDownloads(List<DownloadInfo> active) {
        List<DownloadInfo> all = new ArrayList<>(active);
        for (LiveList.Row<DownloadInfo> row : this.downloads.rows()) {
            if (row.get().status() == DownloadInfo.Status.COMPLETE) {
                all.add(row.get());
            }
        }
        this.downloads.update(all);
    }

    private void run(CompletableFuture<?> work) {
        later(work, done -> {});
    }

    private <T> void later(CompletableFuture<T> work, Consumer<T> then) {
        later(work, then, message -> this.errors.accept(message));
    }

    // The follow-up only hands the outcome to the FX thread, where failed gets the failure's message.
    @SuppressWarnings("FutureReturnValueIgnored")
    private <T> void later(CompletableFuture<T> work, Consumer<T> then, Consumer<String> failed) {
        work.whenComplete((value, failure) -> this.fx.execute(() -> {
            if (failure != null) {
                failed.accept(message(failure));
            } else {
                then.accept(value);
            }
        }));
    }

    private static String message(Throwable failure) {
        Throwable cause =
                failure instanceof CompletionException && failure.getCause() != null ? failure.getCause() : failure;
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }

    /** The searches that run now, for tests. */
    List<Search> searches() {
        return List.copyOf(this.searches.values());
    }
}
