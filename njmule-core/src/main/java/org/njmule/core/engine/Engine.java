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
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
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
import org.njmule.core.api.KadSearchInfo;
import org.njmule.core.api.LogLine;
import org.njmule.core.api.NjMuleEngine;
import org.njmule.core.api.NjMuleInfo;
import org.njmule.core.api.PeerComment;
import org.njmule.core.api.PeerInfo;
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
import org.njmule.core.api.UploadInfo;
import org.njmule.core.api.UploadPriority;
import org.njmule.core.config.AppDirectories;
import org.njmule.core.config.Preferences;
import org.njmule.core.config.Schedule;
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
    private static final Duration SCHEDULE_CHECK = Duration.ofSeconds(15);

    private final AppDirectories directories;
    private final Path preferencesFile;
    private final EngineLoop loop;
    private final ExecutorService eventExecutor;
    private final SubmissionPublisher<EngineEvent> events;
    private final AtomicBoolean closed = new AtomicBoolean();
    // Written only on the engine thread, read from anywhere.
    private volatile Preferences preferences;
    // The preferences with the scheduler's rule applied: what the network uses.
    private volatile Preferences effective;
    private volatile Schedule schedule;
    private volatile Schedule.Rule activeRule;
    private final Path scheduleFile;
    private final Network network;
    private final BooleanSupplier emuleRunning;
    private final InstanceLock lock;

    private Engine(
            AppDirectories directories,
            Preferences preferences,
            EngineLoop loop,
            Hash16 userHash,
            BooleanSupplier emuleRunning,
            InstanceLock lock)
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
        this.network = new Network(
                loop,
                directories.config(),
                () -> this.effective,
                this::publish,
                this::userLog,
                userHash,
                preferences.effectiveTempDir(directories),
                () -> this.preferences.effectiveIncomingDir(directories));
    }

    public static Engine start(EngineConfig config) throws IOException {
        return start(config, EmuleImporter::isEmuleRunning);
    }

    /** As {@link #start(EngineConfig)}, telling whether an eMule runs as tests need. */
    static Engine start(EngineConfig config, BooleanSupplier emuleRunning) throws IOException {
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
            PreferencesCodec.save(prefsFile, prefs);
            EmuleImporter.applyPendingIdentity(dirs.config(), emuleRunning);
            EmuleImporter.applyPendingFiles(dirs.config());

            Hash16 userHash = UserHashStore.loadOrCreate(dirs.config(), new SecureRandom());
            EngineLoop loop = EngineLoop.start();
            try {
                engine = new Engine(dirs, prefs, loop, userHash, emuleRunning, lock);
            } catch (IOException | RuntimeException e) {
                loop.close();
                throw e;
            }
        } catch (IOException | RuntimeException e) {
            lock.close();
            throw e;
        }
        engine.loop
                .submit(() -> {
                    engine.refreshSchedule();
                    engine.network.start();
                    // A rule that holds at start acts on the downloads once they are there.
                    Schedule.Rule active = engine.activeRule;
                    if (active != null) {
                        engine.network.scheduleCategories(active.stopCategory(), active.resumeCategory());
                    }
                    engine.loop.scheduleRepeating("scheduler", SCHEDULE_CHECK, SCHEDULE_CHECK, engine::refreshSchedule);
                    engine.loop.scheduleRepeating("config backup", BACKUP_EVERY, BACKUP_EVERY, engine::backUp);
                    return null;
                })
                .join();
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
                this.refreshSchedule();
                this.publish(new EngineEvent.PreferencesChanged(next));
                this.network.reconfigure(current, next);
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
            this.schedule = changed;
            this.refreshSchedule();
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

    // Engine thread: which rule holds now, and the preferences it gives.
    private void refreshSchedule() {
        // The rules are in the user's local time.
        Schedule.Rule rule =
                this.schedule.active(LocalDateTime.now(ZoneId.systemDefault())).orElse(null);
        Preferences base = this.preferences;
        this.effective = rule == null ? base : rule.applyTo(base);
        if (!Objects.equals(rule, this.activeRule)) {
            this.activeRule = rule;
            if (rule != null) {
                this.network.scheduleCategories(rule.stopCategory(), rule.resumeCategory());
            }
            this.userLog(
                    LogLine.Level.INFO,
                    rule == null
                            ? "Scheduler: the preferences apply again"
                            : "Scheduler: \"" + rule.name() + "\" applies now");
        }
    }

    @Override
    public Flow.Publisher<EngineEvent> events() {
        return this.events;
    }

    @Override
    public CompletableFuture<Integer> addServers(URI url) {
        Objects.requireNonNull(url, "url");
        return this.fetch(url, ServerMet::read)
                .thenCompose(read -> this.loop.submit(() -> this.network.addServers(read)));
    }

    @Override
    public CompletableFuture<List<URI>> serverListAddresses() {
        return this.loop.submit(this::readAddresses);
    }

    @Override
    public CompletableFuture<Void> setServerListAddresses(List<URI> addresses) {
        List<String> lines = addresses.stream().map(URI::toString).toList();
        return this.loop.submit(() -> {
            AtomicFiles.writeBytes(this.addressesFile(), TextFiles.writeLines(lines));
            return null;
        });
    }

    // The server lists of addresses.dat, taken again at each start when the preferences ask for it.
    @SuppressWarnings("FutureReturnValueIgnored") // each fetch reports its own outcome in the log
    private void updateServerLists() {
        this.serverListAddresses().thenAccept(addresses -> {
            for (URI url : addresses) {
                this.addServers(url).whenComplete((added, failure) -> {
                    if (failure == null) {
                        this.userLog(LogLine.Level.INFO, added + " new servers from " + url.getHost());
                    } else {
                        Throwable cause = failure instanceof CompletionException c && c.getCause() != null
                                ? c.getCause()
                                : failure;
                        this.userLog(
                                LogLine.Level.WARNING,
                                "Cannot update the server list from " + url.getHost() + ": " + cause.getMessage());
                    }
                });
            }
        });
    }

    private List<URI> readAddresses() throws IOException {
        Path file = this.addressesFile();
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
        return this.fetch(url, data -> ipRanges(data, url))
                .thenCompose(ranges -> this.loop.submit(() -> this.network.setIpFilter(ranges)));
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
        return this.fetch(url, NodesDat::read)
                .thenCompose(contents -> this.loop.submit(() -> this.network.addKadContacts(contents)));
    }

    /** Reads what the web gives, on a thread of its own: the engine thread never waits for the network. */
    private <T> CompletableFuture<T> fetch(URI url, Parser<T> parser) {
        return CompletableFuture.supplyAsync(
                () -> {
                    try {
                        return parser.parse(this.web.fetch(url));
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
        return resolve(server.host())
                .thenCompose(ip ->
                        this.loop.submit(() -> this.network.removeServer(new ServerList.Address(ip, server.port()))));
    }

    @Override
    public CompletableFuture<Boolean> addServer(ServerAddress server) {
        return this.onServer(server, this.network::addServer);
    }

    @Override
    public CompletableFuture<Integer> removeServers(Collection<ServerAddress> servers) {
        List<CompletableFuture<ServerList.Address>> addresses = new ArrayList<>();
        for (ServerAddress server : servers) {
            addresses.add(resolve(server.host()).thenApply(ip -> new ServerList.Address(ip, server.port())));
        }
        return CompletableFuture.allOf(addresses.toArray(CompletableFuture<?>[]::new))
                .thenCompose(done -> this.loop.submit(() -> this.network.removeServers(
                        addresses.stream().map(CompletableFuture::join).toList())));
    }

    @Override
    public CompletableFuture<Boolean> setServerPriority(ServerAddress server, ServerInfo.Priority priority) {
        Objects.requireNonNull(priority, "priority");
        return this.onServer(server, address -> this.network.setServerPriority(address, priority));
    }

    @Override
    public CompletableFuture<Boolean> setStaticServer(ServerAddress server, boolean isStatic) {
        return this.onServer(server, address -> this.network.setStaticServer(address, isStatic));
    }

    private CompletableFuture<Boolean> onServer(ServerAddress server, Predicate<ServerList.Address> action) {
        Objects.requireNonNull(server, "server");
        return resolve(server.host())
                .thenCompose(ip -> this.loop.submit(() -> action.test(new ServerList.Address(ip, server.port()))));
    }

    @Override
    public CompletableFuture<EngineStatus> status() {
        return this.loop.submit(this.network::status);
    }

    @Override
    public CompletableFuture<List<UploadInfo>> uploads() {
        return this.loop.submit(this.network::uploads);
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
    public CompletableFuture<List<SourceInfo>> sources() {
        return this.loop.submit(this.network::sources);
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
        int port = server.get().port();
        return resolve(server.get().host())
                .thenCompose(ip -> this.loop.submit(() -> {
                    this.network.connect(Optional.of(new ServerList.Address(ip, port)));
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
        return resolve(peer.host())
                .thenCompose(ip -> this.network.hello(new InetSocketAddress(Ipv4.format(ip), peer.port())));
    }

    @Override
    public CompletableFuture<DownloadInfo> addDownload(Ed2kLink.FileLink link) {
        Objects.requireNonNull(link, "link");
        return this.loop.submit(() -> this.network.addDownload(link));
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
        return resolve(source.host())
                .thenCompose(ip -> this.loop.submit(
                        () -> this.network.addSource(download, new InetSocketAddress(Ipv4.format(ip), source.port()))));
    }

    @Override
    public CompletableFuture<Boolean> setDownloadPreview(Hash16 hash, boolean previewFirst) {
        Objects.requireNonNull(hash, "hash");
        return this.loop.submit(() -> this.network.setDownloadPreview(hash, previewFirst));
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
            this.network.setCategories(copy);
            return null;
        });
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
    public CompletableFuture<Boolean> renameSharedFile(Hash16 hash, String name) {
        Objects.requireNonNull(hash, "hash");
        Objects.requireNonNull(name, "name");
        return this.loop.submit(() -> this.network.renameSharedFile(hash, name));
    }

    @Override
    public CompletableFuture<Void> shareDirectory(Path directory) {
        return this.shareDirectory(directory, false);
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
                .thenCompose(ip -> this.loop.submit(
                        () -> this.network.browse(new InetSocketAddress(Ipv4.format(ip), client.port()))))
                .thenCompose(files -> files);
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
        public String adoptDownload(Path met, Path data) throws IOException {
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
                        .submit(() -> Engine.this.network.adoptDownload(stagedMet, stagedData))
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
                    this.network.sendMessage(new InetSocketAddress(Ipv4.format(ip), to.port()), text);
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
        this.publish(new EngineEvent.Stopping());
        logger.info("Stopping {}", NjMuleInfo.NAME);
        try {
            this.loop
                    .submit(() -> {
                        this.network.close();
                        // After the last saves.
                        this.backUp();
                        return null;
                    })
                    .get(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException e) {
            logger.warn("Closing the network failed", e);
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
        this.lock.close();
    }

    // The configuration, copied into the data directory as the preferences ask; on the engine thread, between saves.
    private void backUp() {
        int keep = this.preferences.backupCopies();
        if (keep == 0) {
            return;
        }
        try {
            Path copy = ConfigBackup.write(
                    this.directories.config(),
                    this.directories.data().resolve(ConfigBackup.FOLDER),
                    keep,
                    // The copy is named by the local time.
                    Clock.system(ZoneId.systemDefault()));
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
        this.publish(new EngineEvent.Log(new LogLine(Instant.now(), level, message)));
    }

    // Name lookups block, so they run on a virtual thread; the eD2K network is IPv4 only.
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
        if (!this.events.isClosed()) {
            this.events.offer(event, (subscriber, dropped) -> {
                logger.debug("Dropped {} for a slow subscriber", dropped);
                return false;
            });
        }
    }
}
