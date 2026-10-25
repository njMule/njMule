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
package org.njmule.core.engine;

import com.google.common.net.InetAddresses;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SubmissionPublisher;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import org.njmule.core.api.BanInfo;
import org.njmule.core.api.Category;
import org.njmule.core.api.DownloadInfo;
import org.njmule.core.api.DownloadPriority;
import org.njmule.core.api.Ed2kLink;
import org.njmule.core.api.EngineConfig;
import org.njmule.core.api.EngineEvent;
import org.njmule.core.api.EngineStatus;
import org.njmule.core.api.FileComment;
import org.njmule.core.api.FileHistory;
import org.njmule.core.api.Friend;
import org.njmule.core.api.Hash16;
import org.njmule.core.api.ImportOptions;
import org.njmule.core.api.ImportReport;
import org.njmule.core.api.KadContactInfo;
import org.njmule.core.api.KadInfo;
import org.njmule.core.api.KadNote;
import org.njmule.core.api.KadSearchInfo;
import org.njmule.core.api.KnownClient;
import org.njmule.core.api.LogLine;
import org.njmule.core.api.NewVersion;
import org.njmule.core.api.NjMuleEngine;
import org.njmule.core.api.NjMuleInfo;
import org.njmule.core.api.NotificationResult;
import org.njmule.core.api.PeerComment;
import org.njmule.core.api.PeerInfo;
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
import org.njmule.core.api.UpdateHandOver;
import org.njmule.core.api.UpdateStatus;
import org.njmule.core.api.UploadInfo;
import org.njmule.core.api.UploadPriority;
import org.njmule.core.config.AppDirectories;
import org.njmule.core.config.OsFamily;
import org.njmule.core.config.Preferences;
import org.njmule.core.config.Schedule;
import org.njmule.core.config.UpdateMode;
import org.njmule.core.net.CountryTable;
import org.njmule.core.net.WebLists;
import org.njmule.core.persist.AtomicFiles;
import org.njmule.core.persist.ConfigBackup;
import org.njmule.core.persist.EmuleImporter;
import org.njmule.core.persist.PreferencesCodec;
import org.njmule.core.persist.ScheduleCodec;
import org.njmule.core.persist.UserHashStore;
import org.njmule.core.persist.format.IpFilterDat;
import org.njmule.core.persist.format.Known2Met;
import org.njmule.core.persist.format.KnownMet;
import org.njmule.core.persist.format.NodesDat;
import org.njmule.core.persist.format.ServerMet;
import org.njmule.core.persist.format.TextFiles;
import org.njmule.core.server.ServerList;
import org.njmule.core.update.Updates;
import org.njmule.core.upnp.Routers;
import org.njmule.core.util.Ipv4;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Engine implementation behind {@link NjMuleEngine}. */
public final class Engine implements NjMuleEngine {

    private final WebLists web = WebLists.create();

    private static final Logger logger = LoggerFactory.getLogger(Engine.class);
    private static final String ADDRESSES_FILE = "addresses.dat";
    private static final Duration BACKUP_EVERY = Duration.ofDays(1);
    private static final int EVENT_BUFFER = 1_024;
    private static final Duration UNMAP_WAIT = Duration.ofSeconds(5);
    private static final Duration SCHEDULE_CHECK = Duration.ofSeconds(15);
    private static final Duration KEEP_AWAKE_CHECK = Duration.ofSeconds(30);
    // How often the IP filter lists are looked at, and how long after a failed update they wait.
    private static final Duration IP_FILTER_CHECK = Duration.ofHours(1);
    private static final Duration IP_FILTER_RETRY = Duration.ofHours(6);
    private static final Duration COUNTRIES_CHECK = Duration.ofHours(1);
    private static final Duration COUNTRIES_RETRY = Duration.ofHours(6);
    // DB-IP publishes its database once a month.
    private static final Duration COUNTRIES_AGE = Duration.ofDays(32);
    private static final String COUNTRIES_FILE = "countries.dat";
    // Followed by the month, yyyy-MM, and .csv.gz.
    private static final String COUNTRIES_URL = "https://download.db-ip.com/free/dbip-country-lite-";
    // The first look for a new version waits for the start to settle; then whether one is due is asked often.
    private static final Duration UPDATE_FIRST = Duration.ofMinutes(2);
    private static final Duration UPDATE_TICK = Duration.ofMinutes(10);
    // A new version that ran its engine this long is kept: the installer stops watching it, the old files go.
    private static final Duration UPDATE_PROVEN = Duration.ofSeconds(30);

    private final AppDirectories directories;
    private final Path preferencesFile;
    private final EngineLoop loop;
    private final ExecutorService eventExecutor;
    private final SubmissionPublisher<EngineEvent> events;
    // What subscribers that come later are told first; guarded by itself, as is publishing.
    private final EventReplay replay = new EventReplay();
    private final Flow.Publisher<EngineEvent> replaying = this::subscribe;
    private final AtomicBoolean closed = new AtomicBoolean();
    // Written only on the engine thread, read from anywhere.
    private volatile Preferences preferences;
    private final Notifier notifier;
    private final KeepAwake keepAwake = KeepAwake.forThisSystem();
    // The preferences with the scheduler's rule applied: what the network uses.
    private volatile Preferences effective;
    // Engine thread only: an update of the IP filter lists under way, and when the last one was tried.
    private boolean ipFilterUpdating;
    private long ipFilterTried;
    private volatile CountryTable countries = CountryTable.EMPTY;
    private boolean countriesUpdating;
    private long countriesTried;
    private volatile Schedule schedule;
    private volatile Schedule.Rule activeRule;
    private final Path scheduleFile;
    private final Network network;
    private final BooleanSupplier emuleRunning;
    private final InstanceLock lock;
    private final Updates updates;

    private Engine(
            AppDirectories directories,
            Preferences preferences,
            EngineLoop loop,
            Hash16 userHash,
            BooleanSupplier emuleRunning,
            InstanceLock lock,
            Optional<Routers> upnp,
            boolean automaticConnections)
            throws IOException {
        this.directories = directories;
        this.lock = lock;
        this.emuleRunning = emuleRunning;
        this.preferencesFile = directories.config().resolve(PreferencesCodec.FILE_NAME);
        this.preferences = preferences;
        this.scheduleFile = directories.config().resolve(ScheduleCodec.FILE_NAME);
        this.schedule = ScheduleCodec.load(this.scheduleFile);
        this.effective = preferences;
        this.loop = loop;
        this.eventExecutor = Executors.newSingleThreadExecutor(
                Thread.ofPlatform().name("njmule-events").daemon(true).factory());
        this.events = new SubmissionPublisher<>(this.eventExecutor, EVENT_BUFFER);
        // Before the network starts, so that a disk short of space at the start runs its command too.
        this.events.subscribe(new EventCommandRunner(
                () -> this.preferences.eventCommands(),
                EventCommandRunner::launch,
                command -> Thread.ofVirtual().name("njmule-event-command").start(command),
                OsFamily.current() == OsFamily.WINDOWS));
        this.notifier = Notifier.create(() -> this.preferences.notifications());
        this.events.subscribe(this.notifier);
        this.updates = Updates.create(
                directories.config(), directories.cache(), this.web, preferences, new Updates.Listener() {
                    @Override
                    public void changed(UpdateStatus status) {
                        publish(new EngineEvent.UpdateStatusChanged(status));
                    }

                    @Override
                    public void found(NewVersion version) {
                        userLog(
                                LogLine.Level.INFO,
                                NjMuleInfo.NAME + " " + version.version() + " is available: " + version.page());
                        publish(new EngineEvent.UpdateAvailable(version));
                    }

                    @Override
                    public void ready(NewVersion version) {
                        publish(new EngineEvent.UpdateReady(version));
                    }

                    @Override
                    public void restart(String version) {
                        userLog(LogLine.Level.INFO, NjMuleInfo.NAME + " restarts to install " + version);
                        publish(new EngineEvent.UpdateRestart(version));
                    }
                });
        this.network = new Network(
                loop,
                directories.config(),
                () -> this.effective,
                this::publish,
                this::userLog,
                userHash,
                preferences.effectiveTempDir(directories),
                () -> this.preferences.effectiveIncomingDir(directories),
                upnp,
                automaticConnections);
    }

    public static Engine start(EngineConfig config) throws IOException {
        return start(config, EmuleImporter::isEmuleRunning);
    }

    /** As {@link #start(EngineConfig)}, telling whether an eMule runs as tests need. */
    static Engine start(EngineConfig config, BooleanSupplier emuleRunning) throws IOException {
        // Tests set the property: they must never change the mappings of the router they run behind.
        return launch(
                config,
                emuleRunning,
                Boolean.getBoolean(Network.NO_UPNP_PROPERTY)
                        ? Optional.empty()
                        : Optional.of(Routers.OF_THIS_COMPUTER));
    }

    /** As {@link #start(EngineConfig, BooleanSupplier)}, sending UPnP discovery to {@code upnp} only, for tests. */
    static Engine start(EngineConfig config, BooleanSupplier emuleRunning, Optional<InetSocketAddress> upnp)
            throws IOException {
        return launch(config, emuleRunning, upnp.map(Routers::upnpOnly));
    }

    private static Engine launch(EngineConfig config, BooleanSupplier emuleRunning, Optional<Routers> upnp)
            throws IOException {
        Objects.requireNonNull(config, "config");
        AppDirectories dirs = config.directories();
        dirs.createAll();
        // Before touching any file: another engine may be using them.
        InstanceLock lock = InstanceLock.acquire(dirs.config());
        Engine engine;
        try {
            Path prefsFile = dirs.config().resolve(PreferencesCodec.FILE_NAME);
            AtomicFiles.cleanupInterruptedWrite(prefsFile);
            Preferences prefs = PreferencesCodec.load(prefsFile);
            // Before a version that did not run here yet changes anything: the configuration as the last one left it.
            if (Updates.starting(dirs.config()) && prefs.backupCopies() > 0) {
                backUp(dirs, prefs.backupCopies());
            }
            PreferencesCodec.save(prefsFile, prefs);
            EmuleImporter.applyPendingIdentity(dirs.config(), emuleRunning);
            EmuleImporter.applyPendingFiles(dirs.config());

            Hash16 userHash = UserHashStore.loadOrCreate(dirs.config(), new SecureRandom());
            EngineLoop loop = EngineLoop.start();
            try {
                engine = new Engine(
                        dirs, prefs, loop, userHash, emuleRunning, lock, upnp, config.automaticConnections());
            } catch (IOException | RuntimeException e) {
                loop.close();
                throw e;
            }
        } catch (IOException | RuntimeException e) {
            lock.close();
            throw e;
        }
        try {
            engine.loop
                    .submit(() -> {
                        // The network starts with the limits of the rule in force, which then acts once.
                        engine.effective = engine.scheduled(engine.currentRule());
                        engine.network.start();
                        engine.refreshSchedule();
                        engine.loop.scheduleRepeating(
                                "scheduler", SCHEDULE_CHECK, SCHEDULE_CHECK, engine::refreshSchedule);
                        engine.loop.scheduleRepeating("config backup", BACKUP_EVERY, BACKUP_EVERY, engine::backUp);
                        engine.loop.scheduleRepeating(
                                "keep awake", KEEP_AWAKE_CHECK, KEEP_AWAKE_CHECK, engine::keepComputerAwake);
                        engine.loop.scheduleRepeating(
                                "IP filter lists", Duration.ofMinutes(1), IP_FILTER_CHECK, engine::updateIpFilterIfDue);
                        engine.loop.scheduleRepeating(
                                "countries", Duration.ofSeconds(5), COUNTRIES_CHECK, engine::updateCountriesIfDue);
                        engine.loop.scheduleRepeating(
                                "updates", UPDATE_FIRST, UPDATE_TICK, engine::checkForUpdateIfDue);
                        engine.loop.schedule("update proven", UPDATE_PROVEN, engine::provenVersion);
                        return null;
                    })
                    .join();
        } catch (CompletionException | CancellationException e) {
            // A start that failed half-way lets go of the engine thread, the event thread and the lock.
            engine.close();
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof IOException io) {
                throw io;
            }
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IOException("Cannot start the engine", cause);
        }
        engine.publish(new EngineEvent.Started(NjMuleInfo.version()));
        engine.userLog(LogLine.Level.INFO, NjMuleInfo.NAME + " " + NjMuleInfo.version() + " started");
        if (engine.preferences().updateServersAtStart()) {
            engine.updateServerLists();
        }
        return engine;
    }

    @Override
    public AppDirectories directories() {
        return this.directories;
    }

    @Override
    public Preferences preferences() {
        return this.preferences;
    }

    @Override
    public CompletableFuture<Preferences> updatePreferences(UnaryOperator<Preferences> change) {
        Objects.requireNonNull(change, "change");
        return this.loop.submit(() -> {
            Preferences current = this.preferences;
            Preferences next = Objects.requireNonNull(change.apply(current), "preferences");
            if (!next.equals(current)) {
                try {
                    PreferencesCodec.save(this.preferencesFile, next);
                } catch (IOException e) {
                    throw new UncheckedIOException("Cannot save " + this.preferencesFile, e);
                }
                this.preferences = next;
                refreshSchedule();
                publish(new EngineEvent.PreferencesChanged(next));
                this.network.reconfigure(current, next);
                this.updates.preferences(next);
            }
            return next;
        });
    }

    @Override
    public Schedule schedule() {
        return this.schedule;
    }

    @Override
    public CompletableFuture<Schedule> updateSchedule(Schedule changed) {
        Objects.requireNonNull(changed, "changed");
        return this.loop.submit(() -> {
            try {
                ScheduleCodec.save(this.scheduleFile, changed);
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot save " + this.scheduleFile, e);
            }
            Schedule before = this.schedule;
            this.schedule = changed;
            // A rule edited while it holds goes on, its actions done when it began: the same rule, or one in its place.
            Schedule.Rule was = this.activeRule;
            Schedule.Rule rule = currentRule();
            if (was != null
                    && rule != null
                    && (rule.equals(was)
                            || changed.rules().indexOf(rule) == before.rules().indexOf(was))) {
                this.activeRule = rule;
            }
            refreshSchedule();
            return changed;
        });
    }

    /** The preferences the network uses now, for tests. */
    Preferences effective() {
        return this.effective;
    }

    @Override
    public Optional<Schedule.Rule> activeRule() {
        return Optional.ofNullable(this.activeRule);
    }

    // Engine thread: the rule that holds now, in the user's local time.
    private Schedule.Rule currentRule() {
        return this.schedule.active(LocalDateTime.now(ZoneId.systemDefault())).orElse(null);
    }

    private Preferences scheduled(Schedule.Rule rule) {
        return rule == null ? this.preferences : rule.applyTo(this.preferences);
    }

    // Engine thread: which rule holds now, and the preferences it gives; a rule acts when it begins.
    private void refreshSchedule() {
        Schedule.Rule rule = currentRule();
        this.effective = scheduled(rule);
        if (!Objects.equals(rule, this.activeRule)) {
            this.activeRule = rule;
            if (rule != null) {
                this.network.scheduleCategories(rule.stopCategory(), rule.resumeCategory());
            }
            userLog(
                    LogLine.Level.INFO,
                    rule == null
                            ? "Scheduler: the preferences apply again"
                            : "Scheduler: \"" + rule.name() + "\" applies now");
        }
    }

    @Override
    public Flow.Publisher<EngineEvent> events() {
        return this.replaying;
    }

    // Hands a new subscriber what it missed first: the latest log lines and the server connection as it stands.
    private void subscribe(Flow.Subscriber<? super EngineEvent> subscriber) {
        synchronized (this.replay) {
            this.events.subscribe(new Replaying(subscriber, this.replay.missed()));
        }
    }

    /** Passes events on, after those a subscriber missed; one at a time, as asked for. */
    private static final class Replaying implements Flow.Subscriber<EngineEvent>, Flow.Subscription {
        private final Flow.Subscriber<? super EngineEvent> downstream;
        private final ArrayDeque<EngineEvent> missed;
        private Flow.Subscription upstream;

        Replaying(Flow.Subscriber<? super EngineEvent> downstream, ArrayDeque<EngineEvent> missed) {
            this.downstream = Objects.requireNonNull(downstream, "subscriber");
            this.missed = missed;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            synchronized (this) {
                this.upstream = subscription;
            }
            this.downstream.onSubscribe(this);
        }

        // The missed events go first; the live ones are asked for only once they are all out.
        @Override
        public synchronized void request(long n) {
            long left = n;
            while (left > 0 && !this.missed.isEmpty()) {
                this.downstream.onNext(this.missed.removeFirst());
                left--;
            }
            if (left > 0 || n <= 0) {
                this.upstream.request(n <= 0 ? n : left);
            }
        }

        @Override
        public synchronized void cancel() {
            this.missed.clear();
            this.upstream.cancel();
        }

        @Override
        public void onNext(EngineEvent item) {
            this.downstream.onNext(item);
        }

        @Override
        public void onError(Throwable throwable) {
            this.downstream.onError(throwable);
        }

        @Override
        public void onComplete() {
            this.downstream.onComplete();
        }
    }

    @Override
    public CompletableFuture<Integer> addServers(URI url) {
        Objects.requireNonNull(url, "url");
        return fetch(url, ServerMet::read).thenCompose(read -> this.loop.submit(() -> this.network.addServers(read)));
    }

    @Override
    public CompletableFuture<List<URI>> serverListAddresses() {
        return this.loop.submit(this::readAddresses);
    }

    @Override
    public CompletableFuture<Void> setServerListAddresses(List<URI> addresses) {
        List<String> lines = addresses.stream().map(URI::toString).toList();
        return this.loop.submit(() -> {
            AtomicFiles.writeBytes(addressesFile(), TextFiles.writeLines(lines));
            return null;
        });
    }

    // The server lists of addresses.dat, taken again at each start when the preferences ask for it.
    @SuppressWarnings("FutureReturnValueIgnored") // each fetch reports its own outcome in the log
    private void updateServerLists() {
        serverListAddresses().thenAccept(addresses -> {
            for (URI url : addresses) {
                addServers(url).whenComplete((added, failure) -> {
                    if (failure == null) {
                        userLog(LogLine.Level.INFO, added + " new servers from " + url.getHost());
                    } else {
                        Throwable cause = failure instanceof CompletionException c && c.getCause() != null
                                ? c.getCause()
                                : failure;
                        userLog(
                                LogLine.Level.WARNING,
                                "Cannot update the server list from " + url.getHost() + ": " + cause.getMessage());
                    }
                });
            }
        });
    }

    private List<URI> readAddresses() throws IOException {
        Path file = addressesFile();
        if (!Files.exists(file)) {
            return List.of();
        }
        List<URI> addresses = new ArrayList<>();
        for (String entry : TextFiles.entries(Files.readAllBytes(file))) {
            try {
                URI url = new URI(entry.strip());
                if ("http".equalsIgnoreCase(url.getScheme()) || "https".equalsIgnoreCase(url.getScheme())) {
                    addresses.add(url);
                }
            } catch (URISyntaxException e) {
                logger.debug("Not an address in {}: {}", file, entry);
            }
        }
        return addresses;
    }

    private Path addressesFile() {
        return this.directories.config().resolve(ADDRESSES_FILE);
    }

    @Override
    public CompletableFuture<Integer> updateIpFilter(URI url) {
        Objects.requireNonNull(url, "url");
        return fetch(url, true, data -> ipRanges(data, url))
                .thenCompose(ranges -> this.loop.submit(() -> this.network.setIpFilter(ranges)));
    }

    @Override
    public CompletableFuture<Integer> updateIpFilter(List<URI> urls) {
        List<URI> lists = List.copyOf(urls);
        if (lists.isEmpty()) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("no IP filter list"));
        }
        List<CompletableFuture<List<IpFilterDat.Range>>> fetched = lists.stream()
                .map(url -> fetch(url, true, data -> ipRanges(data, url)))
                .toList();
        return CompletableFuture.allOf(fetched.toArray(CompletableFuture[]::new))
                .handle((done, failure) -> joined(lists, fetched, text -> userLog(LogLine.Level.WARNING, text)))
                .thenCompose(ranges -> this.loop.submit(() -> this.network.setIpFilter(ranges)));
    }

    /**
     * The ranges of the lists that could be fetched and read, in the order of {@code lists}; each other one is told in
     * the log.
     *
     * @throws CompletionException when none could
     */
    static List<IpFilterDat.Range> joined(
            List<URI> lists, List<CompletableFuture<List<IpFilterDat.Range>>> fetched, Consumer<String> warn) {
        List<IpFilterDat.Range> ranges = new ArrayList<>();
        Throwable failure = null;
        int read = 0;
        for (int i = 0; i < lists.size(); i++) {
            CompletableFuture<List<IpFilterDat.Range>> list = fetched.get(i);
            Throwable cause = null;
            if (list.isCompletedExceptionally()) {
                cause = list.exceptionNow();
            } else if (list.join().isEmpty()) {
                cause = new IOException("no IP filter ranges");
            }
            if (cause == null) {
                ranges.addAll(list.join());
                read++;
            } else {
                Throwable reason =
                        cause instanceof CompletionException && cause.getCause() != null ? cause.getCause() : cause;
                warn.accept("IP filter list " + lists.get(i).getHost() + " left out: " + reason.getMessage());
                failure = failure == null ? reason : failure;
            }
        }
        if (read == 0) {
            throw new CompletionException(failure);
        }
        return ranges;
    }

    // The lists of the preferences fetched again once the filter is as old as they say; a failed update waits.
    // The computer stays awake while files are transferred, when the preferences ask for it.
    private void keepComputerAwake() {
        EngineStatus now = this.network.status();
        boolean transferring = now.downloadBytesPerSecond() > 0 || now.uploadBytesPerSecond() > 0;
        this.keepAwake.update(this.preferences.preventSleep(), transferring, Instant.now());
    }

    // The countries of DB-IP, kept in the cache: read at the first check, fetched again once a month old. Turned off,
    // the table is let go.
    private void updateCountriesIfDue() {
        if (!this.preferences.showCountries()) {
            this.countries = CountryTable.EMPTY;
            this.network.countries(CountryTable.EMPTY);
            return;
        }
        Path file = this.directories.cache().resolve(COUNTRIES_FILE);
        long now = System.currentTimeMillis();
        OptionalLong date = modified(file);
        boolean fetch = (date.isEmpty() || now - date.getAsLong() >= COUNTRIES_AGE.toMillis())
                && now - this.countriesTried >= COUNTRIES_RETRY.toMillis();
        boolean read = this.countries.size() == 0 && date.isPresent();
        if (this.countriesUpdating || !(fetch || read)) {
            return;
        }
        this.countriesUpdating = true;
        if (fetch) {
            this.countriesTried = now;
        }
        CompletableFuture<CountryTable> work = CompletableFuture.supplyAsync(
                () -> {
                    try {
                        return fetch ? fetchCountries(file) : CountryTable.read(Files.readAllBytes(file));
                    } catch (IOException e) {
                        throw new CompletionException(e);
                    }
                },
                task -> Thread.ofVirtual().name("njmule-countries").start(task));
        this.loop.whenDone(work, (table, failure) -> {
            this.countriesUpdating = false;
            if (failure == null) {
                this.countries = this.preferences.showCountries() ? table : CountryTable.EMPTY;
                this.network.countries(this.countries);
            } else {
                Throwable reason = failure instanceof CompletionException && failure.getCause() != null
                        ? failure.getCause()
                        : failure;
                userLog(LogLine.Level.WARNING, "Country database not updated: " + reason.getMessage());
            }
        });
    }

    // This month's database, or last month's while this one is not out yet.
    private CountryTable fetchCountries(Path file) throws IOException {
        YearMonth month = YearMonth.now(ZoneOffset.UTC);
        IOException first = null;
        for (YearMonth candidate : List.of(month, month.minusMonths(1))) {
            try {
                CountryTable table = CountryTable.parse(this.web.fetch(URI.create(COUNTRIES_URL
                        + candidate.format(DateTimeFormatter.ofPattern("yyyy-MM", Locale.ROOT)) + ".csv.gz")));
                Files.createDirectories(this.directories.cache());
                AtomicFiles.writeBytes(file, table.write());
                userLog(
                        LogLine.Level.INFO,
                        "Country database updated: " + table.size() + " ranges (IP Geolocation by DB-IP)");
                return table;
            } catch (IOException e) {
                if (first == null) {
                    first = e;
                }
            }
        }
        throw first;
    }

    private static OptionalLong modified(Path file) {
        try {
            return OptionalLong.of(Files.getLastModifiedTime(file).toMillis());
        } catch (IOException e) {
            return OptionalLong.empty();
        }
    }

    private void updateIpFilterIfDue() {
        Preferences now = this.preferences;
        long nowMillis = System.currentTimeMillis();
        if (this.ipFilterUpdating
                || nowMillis - this.ipFilterTried < IP_FILTER_RETRY.toMillis()
                || !isIpFilterDue(ipFilterDate(), nowMillis, now.ipFilterUpdateDays(), now.ipFilterListAddresses())) {
            return;
        }
        this.ipFilterUpdating = true;
        this.ipFilterTried = nowMillis;
        this.loop.whenDone(updateIpFilter(now.ipFilterListAddresses()), (ranges, failure) -> {
            this.ipFilterUpdating = false;
            if (failure == null) {
                this.ipFilterTried = 0;
            } else {
                Throwable reason = failure instanceof CompletionException && failure.getCause() != null
                        ? failure.getCause()
                        : failure;
                userLog(LogLine.Level.WARNING, "IP filter lists not updated: " + reason.getMessage());
            }
        });
    }

    /**
     * Whether the {@code lists} are due again: the filter was last written at {@code filterDate} (milliseconds since
     * the epoch, empty for no filter), {@code days} ago or more; never with no lists or {@code days} 0.
     */
    static boolean isIpFilterDue(OptionalLong filterDate, long nowMillis, int days, List<URI> lists) {
        if (lists.isEmpty() || days == 0) {
            return false;
        }
        return filterDate.isEmpty()
                || nowMillis - filterDate.getAsLong() >= Duration.ofDays(days).toMillis();
    }

    private OptionalLong ipFilterDate() {
        try {
            return OptionalLong.of(
                    Files.getLastModifiedTime(this.directories.config().resolve(Network.IPFILTER_FILE))
                            .toMillis());
        } catch (IOException e) {
            return OptionalLong.empty();
        }
    }

    // The text forms are told apart by the file name: the address's first, then PeerGuardian, then eMule's.
    static List<IpFilterDat.Range> ipRanges(byte[] data, URI url) {
        String path = url.getPath() == null ? "" : url.getPath();
        String name = path.substring(path.lastIndexOf('/') + 1).replaceFirst("(?i)\\.(gz|zip)$", "");
        for (String candidate : List.of(name, "guarding.p2p", "ipfilter.dat")) {
            List<IpFilterDat.Range> ranges = IpFilterDat.read(data, candidate);
            if (!ranges.isEmpty()) {
                return ranges;
            }
        }
        return List.of();
    }

    @Override
    public UpdateStatus updateStatus() {
        return this.updates.status();
    }

    @Override
    public CompletableFuture<UpdateStatus> checkForUpdate() {
        return this.updates.check(this.preferences.updateChannel(), false);
    }

    @Override
    public CompletableFuture<List<NotificationResult>> testNotifications() {
        return CompletableFuture.supplyAsync(
                () -> {
                    try {
                        return this.notifier.test();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new CompletionException(e);
                    }
                },
                task -> Thread.ofVirtual().name("njmule-notify-test").start(task));
    }

    @Override
    public CompletableFuture<UpdateStatus> skipUpdate(String version) {
        Objects.requireNonNull(version, "version");
        return CompletableFuture.supplyAsync(
                () -> this.updates.skip(version),
                task -> Thread.ofVirtual().name("njmule-update-skip").start(task));
    }

    @Override
    public CompletableFuture<UpdateStatus> downloadUpdate() {
        return this.updates.download();
    }

    @Override
    public CompletableFuture<UpdateStatus> installUpdate(boolean restart) {
        return this.updates.install(restart);
    }

    @Override
    public Optional<UpdateHandOver> updateHandOver() {
        return this.closed.get() ? this.updates.handOver() : Optional.empty();
    }

    // Engine thread: once at the start, then daily, unless the user looks only when asking; in the night hours a
    // version ready is installed, when the preferences say so.
    @SuppressWarnings("FutureReturnValueIgnored") // the check tells its outcome through events
    private void checkForUpdateIfDue() {
        Preferences now = this.preferences;
        if (now.updateMode() != UpdateMode.OFF && this.updates.isDue()) {
            this.updates.check(now.updateChannel(), true);
        }
        this.updates.installIfNight(LocalTime.now(ZoneId.systemDefault()));
    }

    // Engine thread: this version has run long enough to be kept.
    private void provenVersion() {
        this.updates.ranLongEnough().ifPresent(message -> userLog(LogLine.Level.SUCCESS, message));
    }

    @Override
    public CompletableFuture<Void> connectKad() {
        return this.loop.submit(() -> {
            this.network.connectKad();
            return null;
        });
    }

    @Override
    public CompletableFuture<Void> disconnectKad() {
        return this.loop.submit(() -> {
            this.network.disconnectKad();
            return null;
        });
    }

    @Override
    public CompletableFuture<Integer> addKadContacts(URI url) {
        Objects.requireNonNull(url, "url");
        return fetch(url, NodesDat::read)
                .thenCompose(contents -> this.loop.submit(() -> this.network.addKadContacts(contents)));
    }

    @Override
    public CompletableFuture<Integer> addKadContacts(Path nodesDat) {
        Objects.requireNonNull(nodesDat, "nodesDat");
        return read(nodesDat, NodesDat::read)
                .thenCompose(contents -> this.loop.submit(() -> this.network.addKadContacts(contents)));
    }

    /** Reads a file the user chose, on a thread of its own, no larger than a list from the web may be. */
    private static <T> CompletableFuture<T> read(Path file, Parser<T> parser) {
        return CompletableFuture.supplyAsync(
                () -> {
                    try {
                        if (Files.size(file) > WebLists.MAX_SIZE) {
                            throw new IOException(file.getFileName() + " is larger than a list may be");
                        }
                        return parser.parse(Files.readAllBytes(file));
                    } catch (IOException e) {
                        throw new CompletionException(e);
                    }
                },
                task -> Thread.ofVirtual().name("njmule-read").start(task));
    }

    /** Reads what the web gives, on a thread of its own: the engine thread never waits for the network. */
    private <T> CompletableFuture<T> fetch(URI url, Parser<T> parser) {
        return fetch(url, false, parser);
    }

    // A list of lines (an IP filter) is also checked for a cut the HTTP framing does not show.
    private <T> CompletableFuture<T> fetch(URI url, boolean lines, Parser<T> parser) {
        return CompletableFuture.supplyAsync(
                () -> {
                    try {
                        return parser.parse(lines ? this.web.fetchText(url) : this.web.fetch(url));
                    } catch (IOException e) {
                        throw new CompletionException(e);
                    }
                },
                task -> Thread.ofVirtual().name("njmule-fetch").start(task));
    }

    @FunctionalInterface
    private interface Parser<T> {
        T parse(byte[] data) throws IOException;
    }

    @Override
    public CompletableFuture<Integer> addServers(Path serverMet) {
        Objects.requireNonNull(serverMet, "serverMet");
        return this.loop.submit(() -> this.network.addServers(serverMet));
    }

    @Override
    public CompletableFuture<List<ServerInfo>> servers() {
        return this.loop.submit(this.network::servers);
    }

    @Override
    public CompletableFuture<Boolean> removeServer(ServerAddress server) {
        Objects.requireNonNull(server, "server");
        return serverAddress(server).thenCompose(address -> this.loop.submit(() -> this.network.removeServer(address)));
    }

    @Override
    public CompletableFuture<Boolean> addServer(ServerAddress server) {
        return onServer(server, this.network::addServer);
    }

    @Override
    public CompletableFuture<Integer> removeServers(Collection<ServerAddress> servers) {
        // A name that cannot be resolved names no server of the list: the others are removed all the same.
        List<CompletableFuture<Optional<ServerList.Address>>> addresses = new ArrayList<>();
        for (ServerAddress server : servers) {
            addresses.add(serverAddress(server).thenApply(Optional::of).exceptionally(failure -> {
                logger.debug("Cannot resolve {}: {}", server, failure.toString());
                return Optional.empty();
            }));
        }
        return CompletableFuture.allOf(addresses.toArray(CompletableFuture<?>[]::new))
                .thenCompose(done -> this.loop.submit(() -> this.network.removeServers(addresses.stream()
                        .map(CompletableFuture::join)
                        .flatMap(Optional::stream)
                        .toList())));
    }

    @Override
    public CompletableFuture<Boolean> setServerPriority(ServerAddress server, ServerInfo.Priority priority) {
        Objects.requireNonNull(priority, "priority");
        return onServer(server, address -> this.network.setServerPriority(address, priority));
    }

    @Override
    public CompletableFuture<Boolean> setStaticServer(ServerAddress server, boolean isStatic) {
        return onServer(server, address -> this.network.setStaticServer(address, isStatic));
    }

    private CompletableFuture<Boolean> onServer(ServerAddress server, Predicate<ServerList.Address> action) {
        Objects.requireNonNull(server, "server");
        return serverAddress(server).thenCompose(address -> this.loop.submit(() -> action.test(address)));
    }

    // A server known by its host name is that one, whatever the name resolves to; any other is known by its address.
    private CompletableFuture<ServerList.Address> serverAddress(ServerAddress server) {
        Optional<ServerList.Address> named = ServerList.Address.named(server.host(), server.port());
        CompletableFuture<Boolean> known = named.isEmpty()
                ? CompletableFuture.completedFuture(false)
                : this.loop.submit(() -> this.network.knowsServer(named.get()));
        return known.thenCompose(byName -> byName
                ? CompletableFuture.completedFuture(named.get())
                : resolve(server.host()).thenApply(ip -> new ServerList.Address(ip, server.port())));
    }

    @Override
    public CompletableFuture<EngineStatus> status() {
        return this.loop.submit(this.network::status);
    }

    @Override
    public CompletableFuture<List<UploadInfo>> uploads() {
        return this.loop.submit(this.network::uploads).thenApply(list -> {
            CountryTable table = this.countries;
            return table.size() == 0
                    ? list
                    : list.stream()
                            .map(u -> u.withCountry(table.countryOf(u.address())))
                            .toList();
        });
    }

    @Override
    public CompletableFuture<List<SavedSearch>> savedSearches() {
        return this.loop.submit(this.network::savedSearches);
    }

    @Override
    public CompletableFuture<Void> saveSearches(List<SavedSearch> searches) {
        List<SavedSearch> kept = List.copyOf(searches);
        return this.loop.submit(() -> {
            this.network.saveSearches(kept);
            return null;
        });
    }

    @Override
    public CompletableFuture<FileHistory> fileHistory() {
        return this.loop.submit(this.network::history);
    }

    @Override
    public CompletableFuture<SpamMarks> spamMarks() {
        return this.loop.submit(this.network::spamMarks);
    }

    @Override
    public CompletableFuture<SpamMarks> markSpam(List<SearchResult> results, boolean isSpam) {
        List<SearchResult> marked = List.copyOf(results);
        return this.loop.submit(() -> this.network.markSpam(marked, isSpam));
    }

    @Override
    public CompletableFuture<List<SearchResult>> rateSpam(List<SearchResult> results) {
        List<SearchResult> rated = List.copyOf(results);
        return this.loop.submit(() -> this.network.rateSpam(rated));
    }

    @Override
    public CompletableFuture<List<KnownClient>> knownClients() {
        return this.loop.submit(this.network::knownClients);
    }

    @Override
    public CompletableFuture<List<SourceInfo>> sources() {
        return this.loop.submit(this.network::sources).thenApply(list -> {
            CountryTable table = this.countries;
            return table.size() == 0
                    ? list
                    : list.stream()
                            .map(s -> s.withCountry(table.countryOf(s.address())))
                            .toList();
        });
    }

    @Override
    public CompletableFuture<List<BanInfo>> bans() {
        return this.loop.submit(this.network::bans);
    }

    @Override
    public CompletableFuture<Boolean> unban(String ip) {
        InetAddress address;
        try {
            address = Inet4Address.ofLiteral(Objects.requireNonNull(ip, "ip").strip());
        } catch (IllegalArgumentException e) {
            return CompletableFuture.failedFuture(e);
        }
        return this.loop.submit(() -> this.network.unban(address));
    }

    @Override
    public CompletableFuture<Void> connect(Optional<ServerAddress> server) {
        Objects.requireNonNull(server, "server");
        if (server.isEmpty()) {
            return this.loop.submit(() -> {
                this.network.connect(Optional.empty());
                return null;
            });
        }
        return serverAddress(server.get())
                .thenCompose(address -> this.loop.submit(() -> {
                    this.network.connect(Optional.of(address));
                    return null;
                }));
    }

    @Override
    public CompletableFuture<Void> disconnect() {
        return this.loop.submit(() -> {
            this.network.disconnect();
            return null;
        });
    }

    @Override
    public CompletableFuture<SearchId> search(SearchRequest request) {
        Objects.requireNonNull(request, "request");
        return this.loop.submit(() -> this.network.search(request));
    }

    @Override
    public CompletableFuture<Boolean> moreResults(SearchId search) {
        return this.loop.submit(() -> this.network.moreResults(search));
    }

    @Override
    public CompletableFuture<Void> stopSearch(SearchId search) {
        return this.loop.submit(() -> {
            this.network.stopSearch(search);
            return null;
        });
    }

    @Override
    public CompletableFuture<PeerInfo> hello(ServerAddress peer) {
        return resolveClient(peer.host())
                .thenCompose(address -> this.network.hello(new InetSocketAddress(address, peer.port())));
    }

    @Override
    public CompletableFuture<DownloadInfo> addDownload(Ed2kLink.FileLink link) {
        Objects.requireNonNull(link, "link");
        return this.loop.submit(() -> this.network.addDownload(link));
    }

    @Override
    public CompletableFuture<DownloadInfo> addDownload(Ed2kLink.FileLink link, OptionalInt category, boolean paused) {
        Objects.requireNonNull(link, "link");
        Objects.requireNonNull(category, "category");
        return this.loop.submit(() -> this.network.addDownload(link, category, paused));
    }

    @Override
    public CompletableFuture<List<DownloadInfo>> downloads() {
        return this.loop.submit(this.network::downloads);
    }

    @Override
    public CompletableFuture<Boolean> pauseDownload(Hash16 hash) {
        Objects.requireNonNull(hash, "hash");
        return this.loop.submit(() -> this.network.pauseDownload(hash));
    }

    @Override
    public CompletableFuture<Boolean> resumeDownload(Hash16 hash) {
        Objects.requireNonNull(hash, "hash");
        return this.loop.submit(() -> this.network.resumeDownload(hash));
    }

    @Override
    public CompletableFuture<Boolean> stopDownload(Hash16 hash) {
        Objects.requireNonNull(hash, "hash");
        return this.loop.submit(() -> this.network.stopDownload(hash));
    }

    @Override
    public CompletableFuture<Boolean> addSource(Hash16 download, ServerAddress source) {
        Objects.requireNonNull(download, "download");
        Objects.requireNonNull(source, "source");
        return resolveClient(source.host())
                .thenCompose(address -> this.loop.submit(
                        () -> this.network.addSource(download, new InetSocketAddress(address, source.port()))));
    }

    @Override
    public CompletableFuture<Boolean> setDownloadPreview(Hash16 hash, boolean previewFirst) {
        Objects.requireNonNull(hash, "hash");
        return this.loop.submit(() -> this.network.setDownloadPreview(hash, previewFirst));
    }

    @Override
    public CompletableFuture<Integer> swapSourcesToDownload(Hash16 hash) {
        Objects.requireNonNull(hash, "hash");
        return this.loop.submit(() -> this.network.swapSourcesToDownload(hash));
    }

    @Override
    public CompletableFuture<Integer> swapSourcesFromDownload(Hash16 hash) {
        Objects.requireNonNull(hash, "hash");
        return this.loop.submit(() -> this.network.swapSourcesFromDownload(hash));
    }

    @Override
    public CompletableFuture<Boolean> setDownloadAutoSwap(Hash16 hash, boolean autoSwap) {
        Objects.requireNonNull(hash, "hash");
        return this.loop.submit(() -> this.network.setDownloadAutoSwap(hash, autoSwap));
    }

    @Override
    public CompletableFuture<Boolean> swapSourceToDownload(Hash16 download, ServerAddress source) {
        Objects.requireNonNull(download, "download");
        Objects.requireNonNull(source, "source");
        return resolveClient(source.host())
                .thenCompose(address -> this.loop.submit(() ->
                        this.network.swapSourceToDownload(download, new InetSocketAddress(address, source.port()))));
    }

    @Override
    public CompletableFuture<Optional<Path>> downloadData(Hash16 hash) {
        Objects.requireNonNull(hash, "hash");
        return this.loop.submit(() -> this.network.downloadData(hash));
    }

    @Override
    public CompletableFuture<Boolean> setDownloadPriority(Hash16 hash, DownloadPriority priority) {
        Objects.requireNonNull(hash, "hash");
        Objects.requireNonNull(priority, "priority");
        return this.loop.submit(() -> this.network.setDownloadPriority(hash, priority));
    }

    @Override
    public CompletableFuture<List<Category>> categories() {
        return this.loop.submit(this.network::categories);
    }

    @Override
    public CompletableFuture<Void> setCategories(List<Category> categories) {
        List<Category> copy = List.copyOf(categories);
        return this.loop.submit(() -> {
            Set<Integer> deleted = new HashSet<>();
            this.network.categories().forEach(c -> deleted.add(c.id()));
            this.network.setCategories(copy);
            copy.forEach(c -> deleted.remove(c.id()));
            forgetCategories(deleted);
            return null;
        });
    }

    // Rules let go of deleted categories: a new category may take the same id.
    private void forgetCategories(Set<Integer> deleted) throws IOException {
        deleted.remove(Category.NONE);
        List<Schedule.Rule> rules = new ArrayList<>();
        boolean changed = false;
        for (Schedule.Rule r : this.schedule.rules()) {
            OptionalInt stop = r.stopCategory().stream()
                    .filter(id -> !deleted.contains(id))
                    .findFirst();
            OptionalInt resume = r.resumeCategory().stream()
                    .filter(id -> !deleted.contains(id))
                    .findFirst();
            if (stop.equals(r.stopCategory()) && resume.equals(r.resumeCategory())) {
                rules.add(r);
                continue;
            }
            Schedule.Rule kept = new Schedule.Rule(
                    r.name(),
                    r.enabled(),
                    r.days(),
                    r.start(),
                    r.end(),
                    r.uploadKiBps(),
                    r.downloadKiBps(),
                    r.maxConnections(),
                    r.maxSourcesPerFile(),
                    stop,
                    resume);
            if (r.equals(this.activeRule)) {
                this.activeRule = kept;
            }
            rules.add(kept);
            changed = true;
        }
        if (changed) {
            Schedule cleaned = new Schedule(rules);
            ScheduleCodec.save(this.scheduleFile, cleaned);
            this.schedule = cleaned;
        }
    }

    @Override
    public CompletableFuture<Boolean> setDownloadCategory(Hash16 hash, int category) {
        Objects.requireNonNull(hash, "hash");
        return this.loop.submit(() -> this.network.setDownloadCategory(hash, category));
    }

    @Override
    public CompletableFuture<Boolean> cancelDownload(Hash16 hash) {
        Objects.requireNonNull(hash, "hash");
        return this.loop.submit(() -> this.network.cancelDownload(hash));
    }

    @Override
    public CompletableFuture<Boolean> restoreDownload(Hash16 hash) {
        Objects.requireNonNull(hash, "hash");
        CompletableFuture<Boolean> restored = new CompletableFuture<>();
        this.loop.post(() -> this.loop.whenDone(this.network.restoreDownload(hash), (ok, failure) -> {
            if (failure == null) {
                restored.complete(ok);
            } else {
                restored.completeExceptionally(failure);
            }
        }));
        return restored;
    }

    @Override
    public CompletableFuture<List<SharedFileInfo>> sharedFiles() {
        return this.loop.submit(this.network::sharedFiles);
    }

    @Override
    public CompletableFuture<Boolean> setUploadPriority(Hash16 hash, UploadPriority priority) {
        Objects.requireNonNull(hash, "hash");
        Objects.requireNonNull(priority, "priority");
        return this.loop.submit(() -> this.network.setUploadPriority(hash, priority));
    }

    @Override
    public CompletableFuture<Boolean> renameDownload(Hash16 hash, String name) {
        Objects.requireNonNull(hash, "hash");
        Objects.requireNonNull(name, "name");
        return this.loop.submit(() -> this.network.renameDownload(hash, name));
    }

    @Override
    public CompletableFuture<Boolean> renameSharedFile(Hash16 hash, String name) {
        Objects.requireNonNull(hash, "hash");
        Objects.requireNonNull(name, "name");
        return this.loop.submit(() -> this.network.renameSharedFile(hash, name));
    }

    @Override
    public CompletableFuture<Void> shareDirectory(Path directory) {
        return shareDirectory(directory, false);
    }

    @Override
    public CompletableFuture<Void> shareDirectory(Path directory, boolean subdirectories) {
        Objects.requireNonNull(directory, "directory");
        return this.loop.submit(() -> {
            this.network.shareDirectory(directory, subdirectories);
            return null;
        });
    }

    @Override
    public CompletableFuture<Void> unshareDirectory(Path directory) {
        Objects.requireNonNull(directory, "directory");
        return this.loop.submit(() -> {
            this.network.unshareDirectory(directory);
            return null;
        });
    }

    @Override
    public CompletableFuture<List<SharedDirectory>> sharedDirectories() {
        return this.loop.submit(this.network::sharedDirectories);
    }

    /** Downloads and uploads in detail, for diagnosing tests. */
    String describe() throws Exception {
        return this.loop.submit(this.network::describe).get(10, TimeUnit.SECONDS);
    }

    @Override
    public CompletableFuture<Boolean> recheckKadFirewall() {
        return this.loop.submit(this.network::recheckKadFirewall);
    }

    @Override
    public CompletableFuture<PortTest> testPorts() {
        return this.loop.submitAsync(() -> this.network.testPorts(PortCheck.WAIT));
    }

    @Override
    public CompletableFuture<Optional<PortTest>> portTest() {
        return this.loop.submit(this.network::portTest);
    }

    @Override
    public CompletableFuture<Void> kadBootstrap(ServerAddress node) {
        Objects.requireNonNull(node, "node");
        return resolve(node.host())
                .thenCompose(ip -> this.loop.submit(() -> {
                    this.network.kadBootstrap(ip, node.port());
                    return null;
                }));
    }

    @Override
    public CompletableFuture<Optional<FileComment>> fileComment(Hash16 file) {
        Objects.requireNonNull(file, "file");
        return this.loop.submit(() -> this.network.fileComment(file));
    }

    @Override
    public CompletableFuture<Void> setFileComment(Hash16 file, FileComment comment) {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(comment, "comment");
        return this.loop.submit(() -> {
            this.network.setFileComment(file, comment);
            return null;
        });
    }

    @Override
    public CompletableFuture<List<PeerComment>> peerComments(Hash16 download) {
        Objects.requireNonNull(download, "download");
        return this.loop.submit(() -> this.network.peerComments(download));
    }

    @Override
    public CompletableFuture<List<KadNote>> kadNotes(Hash16 file) {
        Objects.requireNonNull(file, "file");
        return this.loop.submit(() -> this.network.kadNotes(file));
    }

    @Override
    public CompletableFuture<List<KadNote>> searchKadNotes(Hash16 file) {
        Objects.requireNonNull(file, "file");
        CompletableFuture<List<KadNote>> notes = new CompletableFuture<>();
        if (!this.loop.post(() -> this.network.searchKadNotes(file, notes))) {
            notes.completeExceptionally(new RejectedExecutionException("engine loop not accepting"));
        }
        return notes;
    }

    @Override
    public CompletableFuture<Optional<Hash16>> friendSlot() {
        return this.loop.submit(this.network::friendSlot);
    }

    @Override
    public CompletableFuture<Boolean> setFriendSlot(Optional<Hash16> friend) {
        Objects.requireNonNull(friend, "friend");
        return this.loop.submit(() -> this.network.setFriendSlot(friend));
    }

    @Override
    public CompletableFuture<List<SearchResult>> browseSharedFiles(ServerAddress client) {
        Objects.requireNonNull(client, "client");
        return resolve(client.host())
                .thenCompose(
                        ip -> this.loop.submitAsync(() -> this.network.browse(Ipv4.socketAddress(ip, client.port()))));
    }

    @Override
    public CompletableFuture<List<KadContactInfo>> kadContacts() {
        return this.loop.submit(this.network::kadContacts);
    }

    @Override
    public CompletableFuture<List<KadSearchInfo>> kadSearches() {
        return this.loop.submit(this.network::kadSearches);
    }

    @Override
    public CompletableFuture<Optional<KadInfo>> kad() {
        return this.loop.submit(this.network::kadInfo);
    }

    @Override
    public CompletableFuture<ImportReport> importFromEmule(Path emuleConfigDir, ImportOptions options) {
        Objects.requireNonNull(emuleConfigDir, "emuleConfigDir");
        Objects.requireNonNull(options, "options");
        CompletableFuture<ImportReport> report = new CompletableFuture<>();
        // Reading the profile and copying downloads take long: the engine thread only applies.
        Thread.ofVirtual().name("njmule-import").start(() -> {
            try {
                report.complete(
                        new EmuleImporter(emuleConfigDir, options, new ImportTarget(), this.emuleRunning).run());
            } catch (RuntimeException e) {
                report.completeExceptionally(e);
            }
        });
        return report;
    }

    /** Applies an import on the engine thread; called from the import thread. */
    private final class ImportTarget implements EmuleImporter.Target {
        @Override
        public Path configDir() {
            return Engine.this.directories.config();
        }

        @Override
        public void updatePreferences(UnaryOperator<Preferences> change) {
            Engine.this.updatePreferences(change).join();
        }

        @Override
        public int addServers(Path serverMet) {
            return Engine.this
                    .loop
                    .submit(() -> Engine.this.network.addServers(serverMet))
                    .join();
        }

        @Override
        public int addKadContacts(NodesDat.Contents contacts) {
            return Engine.this
                    .loop
                    .submit(() -> Engine.this.network.addKadContacts(contacts))
                    .join();
        }

        @Override
        public int addKnownFiles(List<KnownMet.KnownFile> files, List<Known2Met.Entry> trees) {
            return Engine.this
                    .loop
                    .submit(() -> Engine.this.network.addKnownFiles(files, trees))
                    .join();
        }

        @Override
        public void shareDirectory(Path directory) {
            Engine.this.shareDirectory(directory).join();
        }

        @Override
        public int addCategories(List<Category> categories) {
            return Engine.this
                    .loop
                    .submit(() -> {
                        if (!Engine.this.network.categories().isEmpty()) {
                            return 0;
                        }
                        Engine.this.network.setCategories(categories);
                        return categories.size();
                    })
                    .join();
        }

        @Override
        public String adoptDownload(Path met, Path data, int category) throws IOException {
            Path temp = Engine.this.loop.submit(Engine.this.network::tempDir).join();
            String stem = "import-" + UUID.randomUUID();
            // Names the download loader ignores, until the engine takes the copy over.
            Path stagedMet = temp.resolve(stem + ".met.tmp");
            Path stagedData = temp.resolve(stem + ".tmp");
            try {
                Files.copy(met, stagedMet);
                Files.copy(data, stagedData);
                return Engine.this
                        .loop
                        .submit(() -> {
                            return Engine.this.network.adoptDownload(stagedMet, stagedData, category);
                        })
                        .join()
                        .name();
            } finally {
                Files.deleteIfExists(stagedMet);
                Files.deleteIfExists(stagedData);
            }
        }
    }

    @Override
    public CompletableFuture<Void> sendMessage(ServerAddress to, String text) {
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(text, "text");
        return resolve(to.host())
                .thenCompose(ip -> this.loop.submit(() -> {
                    this.network.sendMessage(Ipv4.socketAddress(ip, to.port()), text);
                    return null;
                }));
    }

    @Override
    public CompletableFuture<List<Friend>> friends() {
        return this.loop.submit(this.network::friends);
    }

    @Override
    public CompletableFuture<Void> addFriend(Friend friend) {
        Objects.requireNonNull(friend, "friend");
        return this.loop.submit(() -> {
            this.network.addFriend(friend);
            return null;
        });
    }

    @Override
    public CompletableFuture<Boolean> removeFriend(Hash16 userHash) {
        Objects.requireNonNull(userHash, "userHash");
        return this.loop.submit(() -> this.network.removeFriend(userHash));
    }

    @Override
    public boolean isRunning() {
        return !this.closed.get() && this.loop.isRunning();
    }

    @Override
    public void close() {
        if (!this.closed.compareAndSet(false, true)) {
            return;
        }
        publish(new EngineEvent.Stopping());
        logger.info("Stopping {}", NjMuleInfo.NAME);
        this.updates.close();
        this.keepAwake.close();
        AtomicReference<CompletableFuture<Void>> unmapped =
                new AtomicReference<>(CompletableFuture.completedFuture(null));
        CompletableFuture<Void> closing = this.loop.submit(() -> {
            unmapped.set(this.network.close());
            // After the last saves.
            backUp();
            return null;
        });
        try {
            closing.get(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException e) {
            logger.warn("Closing the network failed", e);
        }
        try {
            // The router is told last; one that does not answer drops our ports when their lease ends.
            unmapped.get().get(UNMAP_WAIT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException e) {
            logger.info("The router may keep our ports until their lease ends: {}", e.toString());
        }
        this.loop.close();
        this.events.close();
        this.eventExecutor.shutdown();
        try {
            if (!this.eventExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                this.eventExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            this.eventExecutor.shutdownNow();
        }
        // Another engine may take the files only once this one is done writing them.
        if (closing.isDone()) {
            this.lock.close();
        } else {
            logger.warn("The network is still closing: the configuration stays locked until njMule ends");
        }
    }

    // The configuration, copied into the data directory as the preferences ask; on the engine thread, between saves.
    private void backUp() {
        int keep = this.preferences.backupCopies();
        if (keep == 0) {
            return;
        }
        backUp(this.directories, keep);
    }

    private static void backUp(AppDirectories dirs, int keep) {
        try {
            Path copy = ConfigBackup.write(
                    dirs.config(), dirs.data().resolve(ConfigBackup.FOLDER), keep, Clock.systemUTC());
            logger.info("Configuration backed up to {}", copy);
        } catch (IOException e) {
            logger.warn("Cannot back up the configuration: {}", e.toString());
        }
    }

    /** Logs a message both to SLF4J and to the front ends' log pane. */
    void userLog(LogLine.Level level, String message) {
        switch (level) {
            case DEBUG -> logger.debug("{}", message);
            case INFO, SUCCESS -> logger.info("{}", message);
            case WARNING -> logger.warn("{}", message);
            case ERROR -> logger.error("{}", message);
        }
        publish(new EngineEvent.Log(new LogLine(Instant.now(), level, message)));
    }

    // A client may be at an IPv6 address too: one given as such is taken, a name gives its IPv4 address first.
    private static CompletableFuture<InetAddress> resolveClient(String host) {
        if (InetAddresses.isInetAddress(host)) {
            return CompletableFuture.completedFuture(InetAddresses.forString(host));
        }
        CompletableFuture<InetAddress> found = new CompletableFuture<>();
        Thread.ofVirtual().name("njmule-resolve").start(() -> {
            try {
                InetAddress[] all = InetAddress.getAllByName(host);
                found.complete(Arrays.stream(all)
                        .filter(Inet4Address.class::isInstance)
                        .findFirst()
                        .orElse(all[0]));
            } catch (UnknownHostException e) {
                found.completeExceptionally(e);
            }
        });
        return found;
    }

    // Name lookups block, so they run on a virtual thread; servers and Kad nodes are at IPv4 addresses.
    private static CompletableFuture<Integer> resolve(String host) {
        CompletableFuture<Integer> ip = new CompletableFuture<>();
        Thread.ofVirtual().name("njmule-resolve").start(() -> {
            try {
                for (InetAddress address : InetAddress.getAllByName(host)) {
                    if (address instanceof Inet4Address v4) {
                        ip.complete(Ipv4.fromBytes(v4.getAddress()));
                        return;
                    }
                }
                ip.completeExceptionally(new UnknownHostException("no IPv4 address for " + host));
            } catch (UnknownHostException e) {
                ip.completeExceptionally(e);
            }
        });
        return ip;
    }

    // Slow subscribers lose events instead of stalling the engine.
    private void publish(EngineEvent event) {
        synchronized (this.replay) {
            this.replay.record(event);
            if (!this.events.isClosed()) {
                this.events.offer(event, (subscriber, dropped) -> {
                    logger.debug("Dropped {} for a slow subscriber", dropped);
                    return false;
                });
            }
        }
    }
}
