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

import com.google.common.base.Ticker;
import com.google.common.math.LongMath;
import com.google.common.net.InetAddresses;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Supplier;
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
import org.njmule.core.api.KadContactInfo;
import org.njmule.core.api.KadInfo;
import org.njmule.core.api.KadNote;
import org.njmule.core.api.KadSearchInfo;
import org.njmule.core.api.LogLine;
import org.njmule.core.api.PeerComment;
import org.njmule.core.api.PeerInfo;
import org.njmule.core.api.SavedSearch;
import org.njmule.core.api.SearchId;
import org.njmule.core.api.SearchRequest;
import org.njmule.core.api.SearchResult;
import org.njmule.core.api.ServerInfo;
import org.njmule.core.api.SharedDirectory;
import org.njmule.core.api.SharedFileInfo;
import org.njmule.core.api.SourceInfo;
import org.njmule.core.api.SpamMarks;
import org.njmule.core.api.UploadInfo;
import org.njmule.core.api.UploadPriority;
import org.njmule.core.config.ObfuscationMode;
import org.njmule.core.config.Preferences;
import org.njmule.core.config.SharedFilesVisibility;
import org.njmule.core.download.DownloadManager;
import org.njmule.core.files.CancelledFiles;
import org.njmule.core.files.CommentFilter;
import org.njmule.core.files.FileComments;
import org.njmule.core.files.PartFile;
import org.njmule.core.files.SharedFiles;
import org.njmule.core.kad.KadIdentity;
import org.njmule.core.kad.KadNode;
import org.njmule.core.kad.KadNotes;
import org.njmule.core.kad.KadPublisher;
import org.njmule.core.kad.KadSocket;
import org.njmule.core.kad.UInt128;
import org.njmule.core.net.ProxyRoute;
import org.njmule.core.net.TcpConnection;
import org.njmule.core.net.TcpConnector;
import org.njmule.core.net.TcpListener;
import org.njmule.core.peer.BuddyLink;
import org.njmule.core.peer.Identities;
import org.njmule.core.peer.IdentityHandshake;
import org.njmule.core.peer.PeerGreetings;
import org.njmule.core.peer.PeerLink;
import org.njmule.core.peer.PeerLinks;
import org.njmule.core.peer.SharedFilesBrowser;
import org.njmule.core.persist.AtomicFiles;
import org.njmule.core.persist.CategoriesCodec;
import org.njmule.core.persist.format.IpFilterDat;
import org.njmule.core.persist.format.Known2Met;
import org.njmule.core.persist.format.KnownMet;
import org.njmule.core.persist.format.NodesDat;
import org.njmule.core.persist.format.ServerMet;
import org.njmule.core.protocol.ed2k.Hello;
import org.njmule.core.protocol.ed2k.LowIdMessages;
import org.njmule.core.protocol.ed2k.ServerDatagrams;
import org.njmule.core.protocol.ed2k.ServerMessages;
import org.njmule.core.protocol.io.DecodeException;
import org.njmule.core.protocol.kad.KadMessages;
import org.njmule.core.protocol.packet.FrameLimits;
import org.njmule.core.protocol.packet.Opcodes;
import org.njmule.core.protocol.packet.Packet;
import org.njmule.core.protocol.packet.Packing;
import org.njmule.core.protocol.packet.Protocol;
import org.njmule.core.protocol.tag.ServerTag;
import org.njmule.core.protocol.tag.Tag;
import org.njmule.core.protocol.tag.TagName;
import org.njmule.core.search.SearchManager;
import org.njmule.core.search.SpamFilter;
import org.njmule.core.search.StoredSearches;
import org.njmule.core.security.ConnectionGuard;
import org.njmule.core.security.IpFilter;
import org.njmule.core.security.IpWhitelist;
import org.njmule.core.server.FilePublisher;
import org.njmule.core.server.ServerList;
import org.njmule.core.server.ServerPinger;
import org.njmule.core.server.ServerSession;
import org.njmule.core.server.ServerUdp;
import org.njmule.core.social.ChatFilter;
import org.njmule.core.social.Friends;
import org.njmule.core.social.Messaging;
import org.njmule.core.upload.Credits;
import org.njmule.core.upload.UploadManager;
import org.njmule.core.upnp.PortForwarding;
import org.njmule.core.util.Bytes;
import org.njmule.core.util.Ipv4;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The network side of the engine: the listening socket, the server session, server UDP queries, searches, the hello
 * exchange with peers, the downloads, the shared files and the uploads. Engine thread only, except the methods marked
 * otherwise.
 */
final class Network {

    static final String IPFILTER_FILE = "ipfilter.dat";

    /** How long a hello waits for the identification after the answer. */
    static final Duration IDENTIFY_WAIT = Duration.ofSeconds(10);

    // Server updates are saved together, at most this long after the first one.
    private static final Duration SAVE_DELAY = Duration.ofSeconds(30);
    private static final Duration TOTALS_SAVE = Duration.ofMinutes(5);

    // Connect options of a callback request: the client requests or requires obfuscation.
    private static final int CRYPT_REQUESTED_OR_REQUIRED = 0x02 | 0x04;
    private static final int CRYPT_SUPPORTED = 0x01;
    private static final int CRYPT_REQUESTED = 0x02;

    /** A client may ask us for a direct callback this rarely, as clients expect. */
    static final Duration DIRECT_CALLBACK_REPEAT = Duration.ofMinutes(3);

    private static final int MAX_DIRECT_CALLERS = 256;

    /** How long the link to a UDP test helper waits for the helper's datagram before the test counts as failed. */
    static final Duration UDP_CHECK_WAIT = PeerGreetings.IDLE_TIMEOUT;

    private static final Logger logger = LoggerFactory.getLogger(Network.class);
    /** Set to {@code true}, no port is ever forwarded: tests must not touch the router of the machine they run on. */
    public static final String NO_UPNP_PROPERTY = "njmule.upnp.disabled";

    // How long a new connection waits for the engine to take it.
    private static final Duration ROUTE_WAIT = Duration.ofSeconds(5);

    /** What our hello says about the server we are on. */
    private record ServerContext(long clientId, int serverIp, int serverPort) {
        static final ServerContext NONE = new ServerContext(0, 0, 0);
    }

    /** Callback connections asked for by the server, open at once, and how soon one repeats. */
    static final int MAX_CALLBACKS_OPEN = 8;

    static final Duration CALLBACK_REPEAT = Duration.ofMinutes(1);

    private final EngineLoop loop;
    private final Supplier<Preferences> preferences;
    private final Consumer<EngineEvent> publish;
    private final UserLog userLog;
    private final Hash16 userHash;
    private final Path serverMet;
    private final Path staticServers;
    private final Path spamFile;
    private final SpamFilter spam;
    private final CancelledFiles cancelled;
    private final SharedFilesBrowser browser;
    private final FileComments comments;
    // The friend served first, for this run only; only friends get it.
    private Optional<Hash16> friendSlot = Optional.empty();
    private final ServerList servers;
    private final ServerSession session;
    private final SearchManager searches;
    private final ServerPinger pinger;
    private final PeerGreetings greetings;
    private final PeerLinks links;
    // Replaced from the engine thread, read by the connection threads too.
    private volatile IpFilter ipFilter;
    // Clients the server asked us to call back, by address and when; engine thread only.
    private final Map<InetSocketAddress, Long> callbacks = new HashMap<>();
    // The clients being called back now, until they greet us or fail.
    private final Set<InetSocketAddress> calling = new HashSet<>();
    // Clients that asked us for a direct callback, by address and when; engine thread only.
    private final Map<Integer, Long> directCallers = new HashMap<>();
    private final BuddyLink buddyLink;
    // The server logged in to or being tried, read by the listener's threads; 0 for none.
    private volatile int serverIp;
    private final DownloadManager downloads;
    private final Credits credits;
    private final UploadManager uploads;
    private final FilePublisher publisher;
    // Replaced on the engine thread, read by the connection threads too.
    private volatile KadNode kad;
    private KadSocket kadSocket;
    // Kad disconnected by the user: new ports do not start it again.
    private volatile boolean kadStoppedByHand;
    // Where UPnP discovery goes; empty: the router is never asked.
    private final Optional<InetSocketAddress> upnp;
    // Changes of the router's mappings run one after the other, off the engine thread, so old mappings are gone before
    // new ones are asked for and none is left behind.
    private CompletableFuture<Void> upnpChanges = CompletableFuture.completedFuture(null);
    // Whether mappings were last asked for; engine thread.
    private boolean forwardingWanted;
    // Touched only by the steps of upnpChanges.
    private PortForwarding forwarding;
    private final Totals totals;
    private final Messaging messaging;
    private final Friends friends;
    private final long started;
    private KadPublisher kadPublisher;
    private final KadNotes kadNotes;
    private final Identities identities;
    private final SecureRandom random;
    private final Path configDir;
    private List<Category> categories;
    private final Supplier<Path> incomingDir;
    private SharedFiles shares;
    private TcpListener listener;
    private ConnectionGuard guard;
    private ScheduledFuture<?> kadTicking;
    private volatile boolean closed;
    private ScheduledFuture<?> pendingSave;
    private ServerUdp udp;
    private volatile ServerContext context = ServerContext.NONE;

    /** Writes a line to the front ends' log. */
    @FunctionalInterface
    interface UserLog {
        void log(LogLine.Level level, String message);
    }

    Network(
            EngineLoop loop,
            Path configDir,
            Supplier<Preferences> preferences,
            Consumer<EngineEvent> publish,
            UserLog log,
            Hash16 userHash,
            Path tempDir,
            Supplier<Path> incomingDir,
            Optional<InetSocketAddress> upnp)
            throws IOException {
        this.upnp = Objects.requireNonNull(upnp, "upnp");
        this.loop = Objects.requireNonNull(loop, "loop");
        this.preferences = Objects.requireNonNull(preferences, "preferences");
        this.publish = Objects.requireNonNull(publish, "publish");
        this.userLog = Objects.requireNonNull(log, "log");
        this.userHash = Objects.requireNonNull(userHash, "userHash");
        SecureRandom random = new SecureRandom();
        this.configDir = configDir;
        this.incomingDir = incomingDir;
        this.credits = Credits.load(configDir, Clock.systemUTC());
        this.totals = Totals.load(configDir.resolve(Totals.FILE_NAME));
        this.friends = Friends.load(configDir);
        this.categories = CategoriesCodec.load(configDir.resolve(CategoriesCodec.FILE_NAME));
        this.started = System.nanoTime();
        this.random = random;
        Identities identities = new Identities(
                IdentityHandshake.Identity.of(Identities.loadOrCreateKey(configDir, random)),
                hash -> this.credits
                        .get(hash)
                        .map(Credits.Credit::publicKey)
                        .filter(key -> key.length() > 0)
                        .map(Bytes::toByteArray),
                (hash, key) -> this.credits.identified(hash, Bytes.of(key)),
                this::ourPublicAddress,
                random);
        this.serverMet = configDir.resolve(ServerList.FILE_NAME);
        AtomicFiles.cleanupInterruptedWrite(this.serverMet);
        this.servers = ServerList.load(this.serverMet);
        // eMule's list of servers to keep, taken over by the import.
        this.staticServers = configDir.resolve(ServerList.STATIC_FILE_NAME);
        AtomicFiles.cleanupInterruptedWrite(this.staticServers);
        this.servers.loadStatics(this.staticServers);
        // Results marked as spam, eMule's file taken over by the import.
        this.spamFile = configDir.resolve(SpamFilter.FILE_NAME);
        AtomicFiles.cleanupInterruptedWrite(this.spamFile);
        this.spam = loadSpam(this.spamFile);
        this.cancelled = loadCancelled(configDir.resolve(CancelledFiles.FILE_NAME), random);
        this.comments = loadComments(configDir.resolve(FileComments.FILE_NAME));
        if (!preferences.get().rememberCancelledFiles()) {
            this.forgetCancelled();
        }
        this.ipFilter = loadIpFilter(configDir.resolve(IPFILTER_FILE), preferences.get());
        this.servers.filter(ip -> this.ipFilter.blocks(ip) || this.isFilteredLan(ip));
        this.session = new ServerSession(
                loop, this.servers, preferences, userHash, this::listenPort, new SessionEvents(), random);
        this.searches = new SearchManager(
                loop,
                this.session,
                this.servers,
                () -> Optional.ofNullable(this.udp),
                () -> Optional.ofNullable(this.kad).map(k -> this.kadSearches(k)),
                new SearchEvents());
        this.pinger = new ServerPinger(
                loop,
                this.servers,
                () -> Optional.ofNullable(this.udp),
                () -> this.session.id().map(ServerMessages.IdChange::publicIp).orElse(0),
                this::serverUpdated,
                () -> this.preferences.get().deadServerRetries(),
                this::deadServer,
                random,
                Clock.systemUTC());
        this.greetings = new PeerGreetings(
                userHash,
                this::ourHello,
                () -> preferences.get().obfuscation() != ObfuscationMode.REQUIRED,
                this::isOurServer,
                random);
        this.links = new PeerLinks(loop, identities, this::uploadSide);
        this.links.onGreeted(hello -> this.loop.post(() -> this.serverOf(hello)));
        this.buddyLink = new BuddyLink(
                loop,
                this.links,
                this::ourHello,
                options ->
                        (options & CRYPT_SUPPORTED) != 0 && preferences.get().obfuscation() != ObfuscationMode.DISABLED,
                this::relayedCallback,
                random,
                Ticker.systemTicker());
        this.downloads = DownloadManager.open(
                loop,
                tempDir,
                incomingDir,
                this::ourHello,
                () -> {
                    Preferences prefs = preferences.get();
                    return new DownloadManager.Settings(
                            prefs.obfuscation(),
                            prefs.maxSourcesPerFile(),
                            this.listenPort(),
                            prefs.minFreeSpaceMiB() * 1024L * 1024L,
                            prefs.filterLanAddresses(),
                            prefs.previewFirst(),
                            prefs.commentFilter(),
                            prefs.maxDownloadKiBps());
                },
                new ServerLinkImpl(),
                ip -> this.ipFilter.blocks(ip),
                random,
                Ticker.systemTicker(),
                this.links,
                this.credits::downloaded,
                () -> Optional.ofNullable(this.kad).map(k -> this.kadSources(k)),
                () -> this.categories,
                new DownloadEvents());
        this.kadNotes = new KadNotes(
                () -> Optional.ofNullable(this.kad).filter(KadNode::isConnected).map(k -> k),
                this::runningDownloads,
                () -> CommentFilter.of(this.preferences.get().commentFilter()),
                Ticker.systemTicker());
        this.uploads = new UploadManager(
                loop,
                hash -> this.shares == null ? Optional.empty() : this.shares.get(hash),
                hash -> this.shares == null ? Optional.empty() : this.shares.aichTree(hash),
                this::visibleSharedFiles,
                h -> this.friends.contains(h),
                hash -> this.comments.get(hash),
                this.downloads::find,
                this.downloads::exchangeable,
                this::ourHello,
                () -> new UploadManager.Settings(
                        preferences.get().maxUploadKiBps(),
                        preferences.get().obfuscation(),
                        preferences.get().uploadQueueSize(),
                        this.friendSlot),
                this.links,
                this.credits,
                ip -> {
                    if (this.kad != null) {
                        this.kad.reachableConfirmed(ip);
                    }
                },
                random,
                Ticker.systemTicker());
        this.identities = identities;
        this.browser = new SharedFilesBrowser(loop, this::ourHello, random);
        this.messaging = new Messaging(
                loop, this::ourHello, random, new ChatEvents(), this::chatFilter, h -> this.friends.contains(h));
        this.uploads.onChat(this.messaging::incoming);
        this.uploads.onUdpCheck((ip, request, known) -> {
            KadNode node = this.kad;
            if (node != null) {
                boolean source = this.downloads.hasSourceAt(InetAddresses.fromInteger(ip));
                node.udpCheckRequested(
                        ip, request.internalPort(), request.externalPort(), request.senderKey(), known || source);
            }
        });
        this.uploads.priorities(hash -> this.shares == null ? UploadPriority.AUTO : this.shares.priority(hash));
        this.publisher = new FilePublisher(this.session, this::offers, this::listenPort, Ticker.systemTicker());
        this.greetings.routeTo(this::route);
    }

    // A client that greeted us over its own connection, on the connection's thread: a source calling
    // back with a slot goes to its download; any other client may want our files.
    private Optional<TcpConnection.Handler> route(TcpConnection connection, Hello peer) {
        try {
            return Optional.of(this.loop
                    .submit(() -> {
                        PeerLink link = this.links.incoming(connection, peer);
                        // The firewalled node we agreed to serve keeps its link as a buddy link too.
                        boolean buddy = this.buddyLink.adopt(link);
                        if (!this.downloads.adopt(link)
                                && this.uploads.attach(link).isEmpty()
                                && !buddy) {
                            link.disconnect();
                        }
                        return link;
                    })
                    .get(ROUTE_WAIT.toMillis(), TimeUnit.MILLISECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (ExecutionException | TimeoutException e) {
            logger.debug("Cannot take the connection from {}", connection.remoteAddress(), e);
            return Optional.empty();
        }
    }

    private Optional<PeerLink.Role> uploadSide(PeerLink link) {
        return this.uploads.attach(link);
    }

    /** Opens the listening socket and the server UDP socket; failures leave us reachable less. */
    void start() {
        ProxyRoute.install(() -> this.preferences.get().proxy());
        this.guard = new ConnectionGuard(
                () -> this.ipFilter,
                // Refusing the server or a Kad node that checks our port would leave us a LowID or firewalled.
                ip -> this.isOurServer(ip) || this.kadAwaits(ip),
                ip -> this.uploads.isBanned(InetAddresses.fromInteger(ip)),
                () -> new ConnectionGuard.Limits(
                        this.preferences.get().maxConnections(),
                        ConnectionGuard.Limits.DEFAULT.maxPerAddress(),
                        this.preferences.get().newConnectionsPerFiveSeconds(),
                        ConnectionGuard.Limits.DEFAULT.rateWindow()),
                Ticker.systemTicker());
        this.links.countIn(this.guard);
        this.listen(this.preferences.get().tcpPort());
        try {
            this.udp = ServerUdp.open(0, (from, packet) -> this.loop.post(() -> this.datagram(from, packet)));
        } catch (IOException e) {
            this.userLog.log(LogLine.Level.WARNING, "No server UDP socket: global searches are off");
        }
        this.shares = SharedFiles.open(
                this.loop, this.configDir, this::incomingDirs, () -> List.of(this.tempDir()), new ShareEvents());
        this.shares.rememberDownloaded(this.preferences.get().rememberDownloadedFiles());
        this.uploads.start();
        if (this.preferences.get().kadEnabled()) {
            this.startKad();
        }
        this.restartForwarding();
        this.loop.scheduleRepeating("save totals", TOTALS_SAVE, TOTALS_SAVE, this::saveTotals);
        this.loop.scheduleRepeating(
                "offer shared files", Duration.ofSeconds(1), Duration.ofSeconds(1), this.publisher::tick);
        logger.info(
                "{} servers known, {} IP filter ranges, user hash {}",
                this.servers.size(),
                this.ipFilter.rangeCount(),
                this.userHash);
    }

    List<ServerInfo> servers() {
        List<ServerInfo> all = new ArrayList<>();
        for (ServerMet.Server server : this.servers.all()) {
            all.add(this.info(server));
        }
        return all;
    }

    boolean knowsServer(ServerList.Address address) {
        return this.servers.get(address).isPresent();
    }

    boolean removeServer(ServerList.Address address) {
        return this.removeServers(List.of(address)) == 1;
    }

    /** Removes the known ones of {@code addresses}, from the static list too; returns how many. */
    int removeServers(List<ServerList.Address> addresses) {
        int removed = 0;
        boolean statics = false;
        for (ServerList.Address address : addresses) {
            statics |= this.servers.isStatic(address);
            if (this.servers.remove(address)) {
                removed++;
            }
        }
        if (removed > 0) {
            this.saveServers();
        }
        if (statics) {
            this.saveStatics();
        }
        return removed;
    }

    /** Adds a server without connecting to it; false when known or not usable. */
    boolean addServer(ServerList.Address address) {
        boolean added = this.servers.add(this.byHand(address));
        if (added) {
            this.saveServers();
        }
        return added;
    }

    // A server the user gave: high priority when the preferences ask for it.
    private ServerMet.Server byHand(ServerList.Address address) {
        List<Tag> tags = this.preferences.get().manualServersHighPriority()
                ? List.of(new Tag.IntTag(TagName.id(ServerTag.PREFERENCE), ServerList.PRIORITY_HIGH))
                : List.of();
        return new ServerMet.Server(address.ip(), address.port(), tags);
    }

    /** False for an unknown server. */
    boolean setServerPriority(ServerList.Address address, ServerInfo.Priority priority) {
        if (!this.servers.setPriority(address, priority)) {
            return false;
        }
        this.saveServers();
        if (this.servers.isStatic(address)) {
            this.saveStatics();
        }
        this.servers.get(address).ifPresent(this::serverChanged);
        return true;
    }

    /** Puts a server in the static list or takes it out; false for an unknown server. */
    boolean setStaticServer(ServerList.Address address, boolean isStatic) {
        if (this.servers.get(address).isEmpty()) {
            return false;
        }
        if (this.servers.setStatic(address, isStatic)) {
            this.saveStatics();
            this.servers.get(address).ifPresent(this::serverChanged);
        }
        return true;
    }

    private void serverChanged(ServerMet.Server server) {
        this.publish.accept(new EngineEvent.ServerUpdated(this.info(server)));
    }

    EngineStatus status() {
        Optional<ServerMessages.IdChange> id = this.session.id();
        return new EngineStatus(
                id.isPresent() ? this.session.server().map(this::info) : Optional.empty(),
                id.map(ServerMessages.IdChange::clientId).orElse(0L),
                id.map(ServerMessages.IdChange::isLowId).orElse(false),
                this.session.isObfuscated(),
                this.kadInfo(),
                this.downloads.rate(),
                this.uploads.rate(),
                this.downloads.downloaded(),
                this.uploads.uploaded(),
                this.downloads.downloads().size(),
                this.uploads.slotCount(),
                this.uploads.waitingCount(),
                this.shares == null ? 0 : this.shares.all().size(),
                LongMath.saturatedAdd(this.totals.downloaded(), this.downloads.downloaded()),
                LongMath.saturatedAdd(this.totals.uploaded(), this.uploads.uploaded()),
                LongMath.saturatedAdd(this.totals.sessions(), 1),
                this.sessionSeconds(),
                LongMath.saturatedAdd(this.totals.seconds(), this.sessionSeconds()),
                id.map(ServerMessages.IdChange::publicIp)
                        .filter(ip -> ip != 0)
                        .map(Ipv4::format)
                        .orElse(""),
                this.listenPort(),
                id.map(i -> (i.serverFlags() & ServerMessages.TCP_RELATED_SEARCH) != 0)
                        .orElse(false));
    }

    private long sessionSeconds() {
        return (System.nanoTime() - this.started) / 1_000_000_000L;
    }

    private void saveTotals() {
        this.totals.save(this.downloads.downloaded(), this.uploads.uploaded(), this.sessionSeconds());
    }

    List<UploadInfo> uploads() {
        return this.uploads.uploads();
    }

    List<SourceInfo> sources() {
        return this.downloads.sources();
    }

    SpamMarks spamMarks() {
        return this.spam.marks();
    }

    SpamMarks markSpam(List<SearchResult> results, boolean isSpam) {
        for (SearchResult result : results) {
            this.spam.mark(result.hash(), result.name(), isSpam);
        }
        try {
            this.spam.save(this.spamFile);
        } catch (IOException e) {
            logger.warn("Cannot save {}", this.spamFile, e);
        }
        return this.spam.marks();
    }

    List<BanInfo> bans() {
        return this.uploads.bans();
    }

    boolean unban(InetAddress ip) {
        boolean lifted = this.uploads.unban(ip);
        if (lifted) {
            this.userLog.log(LogLine.Level.INFO, "Ban lifted on " + ip.getHostAddress());
        }
        return lifted;
    }

    void sendMessage(InetSocketAddress to, String text) {
        this.messaging.send(to, text);
    }

    List<Friend> friends() {
        return this.friends.all();
    }

    void addFriend(Friend friend) throws IOException {
        this.friends.add(friend);
    }

    boolean removeFriend(Hash16 userHash) throws IOException {
        if (this.friendSlot.filter(userHash::equals).isPresent()) {
            this.friendSlot = Optional.empty();
        }
        return this.friends.remove(userHash);
    }

    /** Conversations tell the front ends. */
    private final class ChatEvents implements Messaging.Listener {
        @Override
        public void received(Messaging.Correspondent from, String text) {
            Network.this.publish.accept(
                    new EngineEvent.MessageReceived(address(from), from.userHash(), from.nick(), text));
        }

        @Override
        public void captcha(Messaging.Correspondent from, byte[] image) {
            Network.this.publish.accept(new EngineEvent.CaptchaRequested(address(from), from.userHash(), image));
        }

        @Override
        public void captchaResult(Messaging.Correspondent from, boolean solved) {
            Network.this.publish.accept(new EngineEvent.CaptchaAnswered(address(from), solved));
        }

        @Override
        public void failed(InetSocketAddress to, String reason) {
            Network.this.publish.accept(
                    new EngineEvent.MessageFailed(to.getAddress().getHostAddress() + ":" + to.getPort(), reason));
        }

        private static String address(Messaging.Correspondent c) {
            return c.address().getAddress().getHostAddress() + ":" + c.address().getPort();
        }
    }

    /** Adds the servers of another client's server.met; returns how many were new. */
    int addServers(Path file) throws IOException {
        return this.addServers(ServerMet.read(Files.readAllBytes(file)));
    }

    int addServers(List<ServerMet.Server> read) throws IOException {
        int added = this.servers.addAll(read);
        if (added > 0) {
            this.servers.save(this.serverMet);
        }
        return added;
    }

    /** Connects to the server at {@code ip:port} (added to the list if new), or to any. */
    void connect(Optional<ServerList.Address> address) {
        if (address.isEmpty()) {
            if (this.servers.connectable().isEmpty()) {
                throw new IllegalStateException("no known server: add a server.met or give one");
            }
            this.session.connect(Optional.empty());
            return;
        }
        // A server known by its name is checked once the name resolves.
        int ip = address.get().ip();
        if (ip != 0 && (this.ipFilter.blocks(ip) || this.isFilteredLan(ip))) {
            throw new IllegalArgumentException(
                    (this.isFilteredLan(ip) ? "LAN addresses are filtered: " : "The IP filter blocks ")
                            + address.get());
        }
        ServerMet.Server server = this.servers.get(address.get()).orElseGet(() -> this.byHand(address.get()));
        if (this.servers.add(server)) {
            this.saveServers();
        }
        this.session.connect(Optional.of(server));
    }

    void disconnect() {
        this.session.disconnect();
    }

    SearchId search(SearchRequest request) {
        return this.searches.start(request);
    }

    boolean moreResults(SearchId id) {
        return this.searches.more(id);
    }

    void stopSearch(SearchId id) {
        this.searches.stop(id);
    }

    DownloadInfo addDownload(Ed2kLink.FileLink link) throws IOException {
        boolean fresh = this.downloads.download(link.hash()).isEmpty();
        DownloadInfo info = this.downloads.add(link);
        if (fresh && this.preferences.get().addDownloadsPaused()) {
            this.downloads.pause(info.hash());
            info = this.downloads.download(info.hash()).orElse(info);
        }
        this.userLog.log(LogLine.Level.INFO, "Downloading " + info.name());
        return info;
    }

    List<DownloadInfo> downloads() {
        return this.downloads.downloads();
    }

    boolean pauseDownload(Hash16 hash) {
        return this.downloads.pause(hash);
    }

    boolean resumeDownload(Hash16 hash) {
        return this.downloads.resume(hash);
    }

    boolean stopDownload(Hash16 hash) {
        return this.downloads.stop(hash);
    }

    boolean addSource(Hash16 download, InetSocketAddress source) {
        return this.downloads.addSourceByHand(download, source);
    }

    boolean setDownloadPreview(Hash16 hash, boolean previewFirst) {
        return this.downloads.setPreviewFirst(hash, previewFirst);
    }

    int swapSourcesToDownload(Hash16 hash) {
        return this.downloads.swapSourcesHere(hash);
    }

    int swapSourcesFromDownload(Hash16 hash) {
        return this.downloads.swapSourcesAway(hash);
    }

    boolean setDownloadAutoSwap(Hash16 hash, boolean autoSwap) {
        return this.downloads.setAutoSwap(hash, autoSwap);
    }

    boolean swapSourceToDownload(Hash16 download, InetSocketAddress source) {
        return this.downloads.swapSource(download, source);
    }

    Optional<Path> downloadData(Hash16 hash) {
        return this.downloads.dataFile(hash);
    }

    boolean setDownloadPriority(Hash16 hash, DownloadPriority priority) {
        return this.downloads.setPriority(hash, priority);
    }

    /** Stops the downloads of one category and resumes those of another, as a scheduler rule asks when it begins. */
    void scheduleCategories(OptionalInt stop, OptionalInt resume) {
        for (DownloadInfo d : this.downloads.downloads()) {
            if (stop.isPresent() && d.category() == stop.getAsInt()) {
                this.downloads.stopBySchedule(d.hash());
            } else if (resume.isPresent()
                    && d.category() == resume.getAsInt()
                    && (d.status() == DownloadInfo.Status.PAUSED || d.status() == DownloadInfo.Status.STOPPED)) {
                this.downloads.resume(d.hash());
            }
        }
        stop.ifPresent(c -> this.userLog.log(LogLine.Level.INFO, "Scheduler: downloads of category " + c + " stopped"));
        resume.ifPresent(
                c -> this.userLog.log(LogLine.Level.INFO, "Scheduler: downloads of category " + c + " resumed"));
    }

    boolean cancelDownload(Hash16 hash) {
        Optional<DownloadInfo> download = this.downloads.download(hash);
        int category = download.map(DownloadInfo::category).orElse(Category.NONE);
        // Only a download that was running leaves a place for another.
        boolean running = download.map(d ->
                        d.status() == DownloadInfo.Status.DOWNLOADING || d.status() == DownloadInfo.Status.NO_SPACE)
                .orElse(false);
        boolean cancelled = this.downloads.cancel(hash);
        if (cancelled && this.preferences.get().rememberCancelledFiles()) {
            try {
                this.cancelled.add(hash);
            } catch (IOException e) {
                logger.warn("Cannot save {}", CancelledFiles.FILE_NAME, e);
            }
        }
        if (cancelled && running) {
            this.startNext(category);
        }
        return cancelled;
    }

    // A download left: a paused one may take its place, as the preferences say.
    private void startNext(int category) {
        this.downloads
                .resumeNext(category, this.preferences.get().nextDownload())
                .ifPresent(next -> this.userLog.log(LogLine.Level.INFO, "Resuming " + next.name()));
    }

    List<SavedSearch> savedSearches() {
        try {
            return StoredSearches.load(this.configDir.resolve(StoredSearches.FILE_NAME));
        } catch (IOException e) {
            logger.warn("Cannot read the searches left open", e);
            return List.of();
        }
    }

    void saveSearches(List<SavedSearch> searches) throws IOException {
        StoredSearches.save(this.configDir.resolve(StoredSearches.FILE_NAME), searches);
    }

    FileHistory history() {
        return FileHistory.of(
                this.shares == null ? Set.of() : this.shares.knownHashes(),
                this.preferences.get().rememberCancelledFiles() ? this.cancelled.snapshot() : h -> false);
    }

    private void forgetCancelled() {
        try {
            this.cancelled.clear();
        } catch (IOException e) {
            logger.warn("Cannot save {}", CancelledFiles.FILE_NAME, e);
        }
    }

    /** Downloads and uploads in detail, for diagnostics. */
    String describe() {
        return "downloads:\n" + this.downloads.describe() + "uploads: " + this.uploads.describe();
    }

    boolean renameSharedFile(Hash16 hash, String name) throws IOException {
        if (this.shares == null) {
            return false;
        }
        Optional<SharedFiles.SharedFile> renamed = this.shares.rename(hash, name);
        renamed.ifPresent(f -> this.userLog.log(LogLine.Level.INFO, "Renamed to " + f.path()));
        return renamed.isPresent();
    }

    List<SharedFileInfo> sharedFiles() {
        List<SharedFileInfo> all = new ArrayList<>();
        if (this.shares != null) {
            for (SharedFiles.SharedFile f : this.shares.all()) {
                all.add(new SharedFileInfo(f.hash(), f.name(), f.size(), f.path(), this.shares.priority(f.hash())));
            }
        }
        return all;
    }

    // The incoming folder of the preferences and those of the categories: their files are shared.
    private List<Path> incomingDirs() {
        List<Path> dirs = new ArrayList<>();
        dirs.add(this.incomingDir.get());
        for (Category category : this.categories) {
            category.incoming().ifPresent(dirs::add);
        }
        return dirs;
    }

    List<Category> categories() {
        return this.categories;
    }

    /** Replaces the categories, kept in categories.properties; the ids must differ. */
    void setCategories(List<Category> categories) throws IOException {
        Set<Integer> ids = new HashSet<>();
        for (Category category : categories) {
            if (!ids.add(category.id())) {
                throw new IllegalArgumentException("two categories with the id " + category.id());
            }
        }
        List<Category> sorted = new ArrayList<>(categories);
        sorted.sort(Comparator.comparingInt(Category::id));
        CategoriesCodec.save(this.configDir.resolve(CategoriesCodec.FILE_NAME), sorted);
        this.categories = List.copyOf(sorted);
        this.downloads.categoriesChanged();
        if (this.shares != null) {
            this.shares.scan();
        }
    }

    /**
     * Replaces the IP filter with {@code ranges}, kept as ipfilter.dat; returns how many ranges it has.
     *
     * @throws IOException when there are none: a filter is not replaced by nothing
     */
    int setIpFilter(List<IpFilterDat.Range> ranges) throws IOException {
        if (ranges.isEmpty()) {
            throw new IOException("the list holds no IP range");
        }
        AtomicFiles.writeBytes(this.configDir.resolve(IPFILTER_FILE), IpFilterDat.write(ranges));
        Preferences now = this.preferences.get();
        this.ipFilter = IpFilter.of(ranges, now.ipFilterLevel()).allowing(IpWhitelist.ranges(now.ipFilterWhitelist()));
        this.userLog.log(LogLine.Level.INFO, this.ipFilter.rangeCount() + " IP filter ranges");
        this.filtersChanged();
        return this.ipFilter.rangeCount();
    }

    // A filter applies at once to what is open too: the server, sources, clients and Kad contacts it now refuses go.
    private void filtersChanged() {
        // A server known by its name has no address while the name resolves; it is checked once it has one.
        Optional<ServerMet.Server> server = this.session.server();
        int serverAt = server.map(ServerMet.Server::ip).orElse(0);
        if (serverAt != 0 && (this.ipFilter.blocks(serverAt) || this.isFilteredLan(serverAt))) {
            this.userLog.log(
                    LogLine.Level.INFO,
                    "Leaving the server " + ServerList.addressOf(server.get()) + ": it is now filtered");
            this.session.disconnect();
        }
        int sources = this.downloads.dropBlockedSources();
        // Clients connect to us whatever the LAN filter says, as the connection guard lets them.
        int clients = this.uploads.dropClients(address -> this.ipFilter.blocks(Ipv4.fromBytes(address.getAddress())));
        int contacts = this.kad == null ? 0 : this.kad.filtersChanged();
        if (sources + clients + contacts > 0) {
            this.userLog.log(
                    LogLine.Level.INFO,
                    "Now filtered: " + sources + " sources, " + clients + " clients, " + contacts
                            + " Kad contacts dropped");
        }
    }

    boolean setDownloadCategory(Hash16 hash, int category) {
        return this.downloads.setCategory(hash, category);
    }

    boolean setUploadPriority(Hash16 hash, UploadPriority priority) {
        return this.shares != null && this.shares.setPriority(hash, priority);
    }

    void shareDirectory(Path directory, boolean subdirectories) throws IOException {
        if (this.shares == null) {
            throw new IllegalStateException("the network is not started");
        }
        this.shares.addDirectory(directory, subdirectories);
    }

    void unshareDirectory(Path directory) throws IOException {
        if (this.shares == null) {
            throw new IllegalStateException("the network is not started");
        }
        this.shares.removeDirectory(directory);
    }

    List<SharedDirectory> sharedDirectories() {
        List<SharedDirectory> all = new ArrayList<>();
        if (this.shares != null) {
            this.shares.directories().forEach(d -> all.add(new SharedDirectory(d, false)));
            this.shares.trees().forEach(d -> all.add(new SharedDirectory(d, true)));
        }
        return all;
    }

    /** Adds Kad contacts to the running node, or to {@code nodes.dat}; returns how many were new. */
    int addKadContacts(NodesDat.Contents contents) throws IOException {
        if (this.kad != null) {
            int added = this.kad.addContacts(contents);
            this.kad.saveNodes();
            this.kad.lookForContacts();
            return added;
        }
        Path file = this.configDir.resolve(KadNode.NODES_FILE);
        Map<UInt128, NodesDat.Contact> merged = new LinkedHashMap<>();
        if (Files.exists(file)) {
            NodesDat.Contents ours = NodesDat.read(Files.readAllBytes(file));
            if (!ours.bootstrap()) {
                ours.contacts().forEach(c -> merged.put(c.id(), c));
            }
        }
        int before = merged.size();
        if (!contents.bootstrap()) {
            contents.contacts().forEach(c -> merged.putIfAbsent(c.id(), c));
        }
        int added = merged.size() - before;
        if (added > 0) {
            AtomicFiles.writeBytes(file, NodesDat.write(List.copyOf(merged.values())));
        }
        return added;
    }

    int addKnownFiles(List<KnownMet.KnownFile> files, List<Known2Met.Entry> trees) {
        if (this.shares == null) {
            throw new IllegalStateException("the network is not started");
        }
        return this.shares.addKnown(files, trees);
    }

    /** Where another client's download is copied before {@link #adoptDownload}. */
    Path tempDir() {
        return this.downloads.tempDir();
    }

    DownloadInfo adoptDownload(Path met, Path data) throws IOException {
        DownloadInfo info = this.downloads.adopt(met, data);
        this.userLog.log(LogLine.Level.INFO, "Downloading " + info.name() + ", taken from eMule");
        return info;
    }

    /**
     * Any thread: says hello to a client and runs the identification; the answer comes when its outcome is known or
     * after {@link #IDENTIFY_WAIT}. The connection stays open until idle.
     */
    CompletableFuture<PeerInfo> hello(InetSocketAddress peer) {
        CompletableFuture<PeerInfo> answer = new CompletableFuture<>();
        Thread.ofVirtual().name("njmule-hello-" + peer).start(() -> {
            try {
                TcpConnection connection =
                        TcpConnector.connectToClient(peer, Optional.empty(), PeerGreetings.HELLO_TIMEOUT, this.random);
                if (!this.loop.postOutcome(() -> this.greet(connection, answer))) {
                    connection.close();
                }
            } catch (IOException e) {
                answer.completeExceptionally(e);
            }
        });
        return answer.orTimeout(PeerGreetings.HELLO_TIMEOUT.plus(IDENTIFY_WAIT).toSeconds() * 2, TimeUnit.SECONDS);
    }

    private void greet(TcpConnection connection, CompletableFuture<PeerInfo> answer) {
        Greeting greeting = new Greeting(connection, answer);
        connection.start(greeting, frame -> Packing.unpack(frame, Packing.MAX_CLIENT_UNPACKED));
        connection.send(this.ourHello().encode(false));
        this.loop.schedule("hello identification", PeerGreetings.HELLO_TIMEOUT.plus(IDENTIFY_WAIT), greeting::settle);
    }

    /** A hello of ours: the answer, then the identification. Engine thread, except callbacks. */
    private final class Greeting implements TcpConnection.Handler {
        private final TcpConnection connection;
        private final CompletableFuture<PeerInfo> answer;
        private Hello peer;
        private Identities.Session identity;

        Greeting(TcpConnection connection, CompletableFuture<PeerInfo> answer) {
            this.connection = connection;
            this.answer = answer;
        }

        @Override
        public void onPacket(TcpConnection c, Packet packet) {
            if (packet.protocol() == Protocol.EDONKEY && packet.opcode() == Opcodes.Client.HELLOANSWER.code()) {
                c.setFrameLimit(FrameLimits.CLIENT);
            }
            if (!Network.this.loop.post(() -> this.received(packet))) {
                c.close();
            }
        }

        @Override
        public void onClosed(TcpConnection c, Optional<IOException> cause) {
            Network.this.loop.postOutcome(() -> {
                if (this.peer == null) {
                    this.answer.completeExceptionally(
                            cause.orElseGet(() -> new IOException("closed before the hello")));
                } else {
                    this.settle();
                }
            });
        }

        private void received(Packet packet) {
            try {
                if (this.peer == null) {
                    if (packet.protocol() == Protocol.EDONKEY && packet.opcode() == Opcodes.Client.HELLOANSWER.code()) {
                        this.peer = Hello.decode(packet);
                        // Before anything that fails on a closed connection: settling needs both.
                        this.identity = Network.this.identities.start(
                                this.peer, this.connection.remoteAddress().getAddress(), this.connection::send);
                        this.connection.setReadTimeout(PeerGreetings.IDLE_TIMEOUT);
                    }
                } else if (!this.identity.received(packet)
                        && packet.protocol() == Protocol.EDONKEY
                        && packet.opcode() == Opcodes.Client.HELLO.code()) {
                    this.connection.send(Network.this.ourHello().encode(true));
                }
                if (this.identity != null && this.identity.isSettled()) {
                    this.settle();
                }
            } catch (IOException e) {
                logger.debug("Bad packet from {}: {}", this.connection.remoteAddress(), e.getMessage());
                this.connection.close();
            }
        }

        private void settle() {
            if (this.peer == null || this.answer.isDone()) {
                return;
            }
            PeerInfo info = PeerGreetings.info(this.connection, this.peer);
            PeerInfo.Identification outcome;
            if (!this.identity.isSupported()) {
                outcome = PeerInfo.Identification.NOT_SUPPORTED;
            } else {
                outcome = switch (this.identity.trust()) {
                    case IDENTIFIED -> PeerInfo.Identification.VERIFIED;
                    case FAILED -> PeerInfo.Identification.FAILED;
                    default -> PeerInfo.Identification.PENDING;
                };
            }
            this.answer.complete(new PeerInfo(
                    info.address(), info.userHash(), info.nick(), info.software(), info.obfuscated(), outcome));
        }
    }

    // Other clients reach us on this port; a port that cannot be had leaves us unreachable, with a LowID.
    private void listen(int port) {
        try {
            this.listener = TcpListener.open(new InetSocketAddress(port), this.guard, this.greetings::accepted);
        } catch (IOException e) {
            this.listener = null;
            this.userLog.log(
                    LogLine.Level.WARNING,
                    "Cannot listen on TCP port "
                            + port
                            + " ("
                            + e.getMessage()
                            + "): other clients cannot connect, servers will give a LowID");
        }
    }

    /**
     * Applies changed preferences at once: new ports are listened on and announced again (to the server, to Kad and
     * through the router), and the networks start or stop as turned on or off.
     */
    void reconfigure(Preferences was, Preferences now) {
        if (this.closed || this.guard == null) {
            return;
        }
        boolean tcp = was.tcpPort() != now.tcpPort();
        boolean udp = was.udpPort() != now.udpPort();
        if (tcp) {
            this.closeListener();
            this.listen(now.tcpPort());
            this.userLog.log(LogLine.Level.INFO, "Listening on TCP port " + this.listenPort());
            // The server learnt our port at login and checks it to give a HighID.
            Optional<ServerMet.Server> server = this.session.server();
            if (server.isPresent() && now.ed2kEnabled()) {
                this.session.connect(server);
            }
        }
        // Only turning Kad off stops it: a Kad connected by hand stays while other settings change.
        if (was.kadEnabled() && !now.kadEnabled()) {
            this.stopKad();
        } else if (this.kad != null) {
            if (tcp || udp) {
                // Kad tells other nodes our TCP port and listens on the UDP one.
                this.stopKad();
                this.startKad();
            }
        } else if (now.kadEnabled() && (!was.kadEnabled() || ((tcp || udp) && !this.kadStoppedByHand))) {
            // Turned on, or new ports for a Kad that could not start; never against a stop by hand.
            this.kadStoppedByHand = false;
            this.startKad();
        }
        if (was.ed2kEnabled() && !now.ed2kEnabled()) {
            this.session.disconnect();
        }
        if (tcp || udp || was.upnp() != now.upnp()) {
            this.restartForwarding();
        }
        boolean level = was.ipFilterLevel() != now.ipFilterLevel();
        boolean whitelist = !was.ipFilterWhitelist().equals(now.ipFilterWhitelist());
        if (level) {
            // The file keeps every range: read again at the new level.
            this.ipFilter = loadIpFilter(this.configDir.resolve(IPFILTER_FILE), now);
            this.userLog.log(
                    LogLine.Level.INFO,
                    this.ipFilter.rangeCount() + " IP filter ranges at level " + now.ipFilterLevel());
        } else if (whitelist) {
            List<IpFilterDat.Range> allowed = IpWhitelist.ranges(now.ipFilterWhitelist());
            this.ipFilter = this.ipFilter.allowing(allowed);
            this.userLog.log(LogLine.Level.INFO, allowed.size() + " addresses or ranges always allowed");
        }
        if (level || whitelist || was.filterLanAddresses() != now.filterLanAddresses()) {
            this.filtersChanged();
        }
        if (was.rememberCancelledFiles() && !now.rememberCancelledFiles()) {
            this.forgetCancelled();
        }
        if (this.shares != null) {
            this.shares.rememberDownloaded(now.rememberDownloadedFiles());
        }
    }

    // The mappings follow the ports.
    private void restartForwarding() {
        boolean wanted = this.preferences.get().upnp() && this.listener != null && this.upnp.isPresent();
        if (!wanted && !this.forwardingWanted) {
            return;
        }
        this.forwardingWanted = wanted;
        int tcp = this.listenPort();
        OptionalInt udp = this.kadSocket == null ? OptionalInt.empty() : OptionalInt.of(this.kadSocket.port());
        this.changeMappings(() -> {
            this.unmap();
            if (wanted && !this.closed) {
                this.forwarding = PortForwarding.start(
                        this.upnp.orElseThrow(),
                        tcp,
                        udp,
                        (fine, line) -> this.loop.post(
                                () -> this.userLog.log(fine ? LogLine.Level.SUCCESS : LogLine.Level.WARNING, line)));
            }
        });
    }

    private void changeMappings(Runnable change) {
        this.upnpChanges = this.upnpChanges.thenRunAsync(
                () -> {
                    try {
                        change.run();
                    } catch (RuntimeException e) {
                        logger.warn("UPnP failed", e);
                    }
                },
                task -> Thread.ofVirtual().name("njmule-upnp").start(task));
    }

    // Steps of upnpChanges only.
    private void unmap() {
        if (this.forwarding != null) {
            this.forwarding.close();
            this.forwarding = null;
        }
    }

    /** Starts Kad, or looks for contacts at once when it runs: the Kad screen's Connect. */
    void connectKad() {
        this.kadStoppedByHand = false;
        if (this.kad == null) {
            this.startKad();
        } else {
            this.kad.lookForContacts();
        }
        if (this.kad != null) {
            this.restartForwarding();
        }
    }

    /** Stops Kad until it is connected again or njMule starts again. */
    void disconnectKad() {
        this.kadStoppedByHand = true;
        if (this.kad != null) {
            this.stopKad();
            this.userLog.log(LogLine.Level.INFO, "Kad stopped");
        }
    }

    private void stopKad() {
        if (this.kad == null) {
            return;
        }
        if (this.kadTicking != null) {
            this.kadTicking.cancel(false);
            this.kadTicking = null;
        }
        this.buddyLink.release();
        this.kad.close();
        this.kadSocket.close();
        this.kad = null;
        this.kadSocket = null;
        this.kadPublisher = null;
        this.kadNotes.kadStopped();
    }

    private void closeListener() {
        if (this.listener != null) {
            try {
                this.listener.close();
            } catch (IOException e) {
                logger.debug("Cannot close the listening socket", e);
            }
            this.listener = null;
        }
    }

    /** Saves and closes everything; the router's mappings go last, off the engine thread: done when they are gone. */
    @CanIgnoreReturnValue
    CompletableFuture<Void> close() {
        this.closed = true;
        this.saveTotals();
        this.messaging.close();
        this.buddyLink.release();
        if (this.kad != null) {
            this.kad.close();
            this.kadSocket.close();
        }
        this.kadNotes.kadStopped();
        this.uploads.close();
        this.downloads.close();
        if (this.shares != null) {
            this.shares.close();
        }
        this.session.disconnect();
        this.greetings.close();
        if (this.listener != null) {
            try {
                this.listener.close();
            } catch (IOException e) {
                logger.debug("Closing the listener failed", e);
            }
        }
        if (this.udp != null) {
            this.udp.close();
        }
        this.saveServers();
        this.changeMappings(this::unmap);
        return this.upnpChanges;
    }

    private int listenPort() {
        TcpListener l = this.listener;
        return l != null ? l.port() : this.preferences.get().tcpPort();
    }

    // Called from connection threads: reads only volatile state and the preferences.
    // The id peers take us for: our address when Kad finds our port open, else the server's id, else
    // 1, a LowID, while Kad is connected but firewalled. Zero would read as a HighID at our address.
    private static long ourClientId(ServerContext server, KadNode node) {
        boolean kad = node != null && node.isConnected();
        if (kad && !node.isFirewalled() && node.publicIp() != 0) {
            return Ipv4.toEd2k(node.publicIp());
        }
        if (server.clientId() != 0) {
            return server.clientId();
        }
        return kad ? 1 : 0;
    }

    private Hello ourHello() {
        Preferences prefs = this.preferences.get();
        ServerContext server = this.context;
        // Kad listens on the client UDP port; announced while Kad is connected, as a NAT shows it once proven usable.
        KadSocket socket = this.kadSocket;
        KadNode node = this.kad;
        boolean connected = socket != null && node != null && node.isConnected();
        int kadPort = connected ? node.announcedUdpPort() : 0;
        boolean directCallback = connected && node.supportsDirectCallback();
        Hello hello = Hello.of(
                this.userHash,
                ourClientId(server, node),
                this.listenPort(),
                prefs.nick(),
                kadPort,
                kadPort,
                1 << Hello.MISC1_AICH_SHIFT
                        | Hello.MISC1_UNICODE
                        // Comments, version 1.
                        | 1 << 4
                        | Hello.MISC1_DATA_COMPRESSION
                        | IdentityHandshake.SUPPORTED << 16
                        // Clients do not ask for files that nobody may see.
                        | (prefs.sharedFilesVisibility() == SharedFilesVisibility.NOBODY
                                ? Hello.MISC1_NO_VIEW_SHARED_FILES
                                : 0),
                Hello.MISC2_LARGE_FILES
                        | Hello.MISC2_SOURCE_EXCHANGE2
                        | Hello.MISC2_SUPPORTS_CAPTCHA
                        | cryptOptions(prefs.obfuscation())
                        | (directCallback ? Hello.MISC2_DIRECT_UDP_CALLBACK : 0)
                        | (kadPort != 0 ? KadMessages.VERSION : 0),
                server.serverIp(),
                server.serverPort());
        // Firewalled with a buddy: clients that know us reach us through it.
        boolean firewalled = server.clientId() < ServerMessages.LOW_ID_LIMIT;
        int buddyIp = this.buddyLink.buddyIp();
        if (connected && firewalled && node.isFirewalled() && buddyIp != 0) {
            return hello.withBuddy(buddyIp, this.buddyLink.buddyUdpPort());
        }
        return hello;
    }

    /** Checks again whether Kad nodes can reach us; {@code false} when Kad does not run. */
    boolean recheckKadFirewall() {
        if (this.kad == null) {
            return false;
        }
        this.kad.recheckFirewall();
        this.userLog.log(LogLine.Level.INFO, "Kad: checking the firewall again");
        return true;
    }

    /** Asks the Kad node at {@code ip:port} for contacts. */
    void kadBootstrap(int ip, int port) {
        if (this.kad == null) {
            throw new IllegalStateException("Kad is not running");
        }
        this.kad.bootstrap(ip, port);
    }

    /** The state of Kad, empty when it does not run. */
    Optional<FileComment> fileComment(Hash16 file) {
        return this.comments.get(file);
    }

    void setFileComment(Hash16 file, FileComment comment) throws IOException {
        this.comments.set(file, comment);
    }

    List<PeerComment> peerComments(Hash16 download) {
        return this.downloads.comments(download);
    }

    List<KadNote> kadNotes(Hash16 file) {
        return this.kadNotes.notes(file);
    }

    void searchKadNotes(Hash16 file, CompletableFuture<List<KadNote>> done) {
        Optional<Long> size = this.downloads
                .download(file)
                .map(DownloadInfo::size)
                .or(() -> Optional.ofNullable(this.shares)
                        .flatMap(s -> s.get(file))
                        .map(SharedFiles.SharedFile::size));
        if (size.isEmpty()) {
            done.completeExceptionally(new IllegalArgumentException("Neither downloaded nor shared: " + file));
            return;
        }
        this.kadNotes.search(file, size.get(), done);
    }

    // The downloads Kad is asked once a run what others said of.
    private List<KadNotes.Download> runningDownloads() {
        List<KadNotes.Download> running = new ArrayList<>();
        for (DownloadInfo d : this.downloads.downloads()) {
            if (d.status() == DownloadInfo.Status.DOWNLOADING) {
                running.add(new KadNotes.Download(d.hash(), d.size()));
            }
        }
        return running;
    }

    Optional<Hash16> friendSlot() {
        return this.friendSlot;
    }

    /** Gives the friend slot to {@code friend}, or to nobody; {@code false} for one who is not a friend. */
    boolean setFriendSlot(Optional<Hash16> friend) {
        if (friend.isPresent() && !this.friends.contains(friend.get())) {
            return false;
        }
        this.friendSlot = friend;
        return true;
    }

    /** The files the client listening at {@code to} shares, when it shows them. */
    CompletableFuture<List<SearchResult>> browse(InetSocketAddress to) {
        return this.browser.browse(to);
    }

    List<KadContactInfo> kadContacts() {
        return this.kad == null ? List.of() : this.kad.contactInfos();
    }

    List<KadSearchInfo> kadSearches() {
        return this.kad == null ? List.of() : this.kad.lookupInfos();
    }

    Optional<KadInfo> kadInfo() {
        KadNode node = this.kad;
        if (node == null) {
            return Optional.empty();
        }
        return Optional.of(new KadInfo(
                node.isConnected(),
                node.contacts(),
                node.verifiedContacts(),
                node.isFirewalled(),
                node.id().toHex(),
                node.publicIp() == 0 ? "" : Ipv4.format(node.publicIp()),
                this.kadSocket.port(),
                node.isFirewalledUdp(),
                node.isUdpVerified(),
                switch (node.buddyState()) {
                    case NONE -> KadInfo.Buddy.NONE;
                    case CONNECTING -> KadInfo.Buddy.CONNECTING;
                    case CONNECTED -> KadInfo.Buddy.CONNECTED;
                    case SERVING -> KadInfo.Buddy.SERVING;
                }));
    }

    private void startKad() {
        Preferences prefs = this.preferences.get();
        try {
            KadIdentity identity = KadIdentity.loadOrCreate(this.configDir, this.random);
            this.kadSocket = KadSocket.open(
                    prefs.udpPort(), this.loop, this.userHash, identity, ip -> this.ipFilter.blocks(ip), this.random);
            this.kad = new KadNode(
                    identity,
                    this.kadSocket,
                    new KadHost(),
                    new KadNode.Settings(
                            this.listenPort(),
                            this.userHash,
                            cryptOptions(prefs.obfuscation()) >>> 7,
                            ip -> Ipv4.isLan(ip) && !this.preferences.get().filterLanAddresses(),
                            ip -> this.ipFilter.blocks(ip),
                            this.kadSocket.port()),
                    this.configDir,
                    this.random,
                    Ticker.systemTicker());
            this.kad.useBuddies(this.buddyLink);
            this.kadSocket.deliverTo(this.kad);
            this.kadSocket.deliverClientPacketsTo(this::clientDatagram);
            this.kad.loadNodes();
            this.kad.loadIndex();
            this.kadPublisher = new KadPublisher(this.kad, this::kadShared, this.comments::get, Ticker.systemTicker());
            this.kadTicking = this.loop.scheduleRepeating("Kad", Duration.ofSeconds(1), Duration.ofSeconds(1), () -> {
                this.kad.tick();
                this.buddyLink.tick();
                this.kadPublisher.tick();
                this.kadNotes.tick();
            });
            logger.info("Kad id {} on UDP port {}", identity.id(), this.kadSocket.port());
        } catch (IOException e) {
            this.userLog.log(
                    LogLine.Level.WARNING, "Cannot start Kad on UDP port " + prefs.udpPort() + ": " + e.getMessage());
            if (this.kadSocket != null) {
                this.kadSocket.close();
            }
            this.kad = null;
            this.kadSocket = null;
        }
    }

    /** What the Kad node needs of the rest of the client. */
    private final class KadHost implements KadNode.Host {
        @Override
        public void checkReachable(int ip, int port, Hash16 userHash, int connectOptions) {
            Network.this.checkReachable(ip, port, userHash, connectOptions);
        }

        @Override
        public void checkUdp(int ip, int tcpPort, int internalPort, int externalPort, int senderKey) {
            Network.this.checkUdp(
                    ip, tcpPort, new LowIdMessages.UdpCheckRequest(internalPort, externalPort, senderKey));
        }

        @Override
        public boolean isKnownClient(int ip) {
            InetAddress address = InetAddresses.fromInteger(ip);
            return Network.this.downloads.hasSourceAt(address)
                    || Network.this.uploads.knows(address)
                    || Network.this
                            .buddyLink
                            .served()
                            .map(a -> a.getAddress().equals(address))
                            .orElse(false);
        }

        @Override
        public boolean hasHighId() {
            return Network.this.context.clientId() >= ServerMessages.LOW_ID_LIMIT;
        }
    }

    // A UDP test helper: after the hellos it is asked to send its datagram; the link stays open while we wait for it.
    private void checkUdp(int ip, int port, LowIdMessages.UdpCheckRequest request) {
        KadNode node = this.kad;
        InetSocketAddress address = new InetSocketAddress(Ipv4.format(ip), port);
        Thread.ofVirtual().name("njmule-udp-check-" + address).start(() -> {
            try {
                TcpConnection connection = TcpConnector.connectToClient(
                        address, Optional.empty(), PeerGreetings.HELLO_TIMEOUT, this.random);
                UdpCheck check = new UdpCheck(node, ip, request);
                connection.start(check, frame -> Packing.unpack(frame, Packing.MAX_CLIENT_UNPACKED));
                connection.send(this.ourHello().encode(false));
            } catch (IOException e) {
                logger.debug("UDP test helper {} unreachable: {}", address, e.getMessage());
                this.loop.post(() -> this.udpCheckEnded(node, ip, false));
            }
        });
    }

    private void udpCheckEnded(KadNode node, int ip, boolean asked) {
        if (this.kad == node && node != null) {
            node.udpCheckEnded(ip, asked);
        }
    }

    /** Asks a UDP test helper, once it greeted us as a Kad node able to help, then waits for its datagram. */
    private final class UdpCheck implements TcpConnection.Handler {
        private final KadNode node;
        private final int ip;
        private final LowIdMessages.UdpCheckRequest request;
        private volatile boolean asked;

        UdpCheck(KadNode node, int ip, LowIdMessages.UdpCheckRequest request) {
            this.node = node;
            this.ip = ip;
            this.request = request;
        }

        @Override
        public void onPacket(TcpConnection c, Packet packet) {
            if (this.asked
                    || packet.protocol() != Protocol.EDONKEY
                    || packet.opcode() != Opcodes.Client.HELLOANSWER.code()) {
                return;
            }
            c.setFrameLimit(FrameLimits.CLIENT);
            try {
                Hello peer = Hello.decode(packet);
                // Kad nodes before version 6 do not know the test.
                if ((peer.miscOptions2() & 0x0F) < 6 || peer.kadPort() == 0) {
                    c.close();
                    return;
                }
                this.asked = c.send(this.request.encode());
                c.setReadTimeout(UDP_CHECK_WAIT);
            } catch (IOException e) {
                c.close();
            }
        }

        @Override
        public void onClosed(TcpConnection c, Optional<IOException> cause) {
            boolean sent = this.asked;
            Network.this.loop.postOutcome(() -> Network.this.udpCheckEnded(this.node, this.ip, sent));
        }
    }

    /**
     * Connects to a client that asked to be called back, so that it can download from us: a few at a time, each address
     * once a minute; a client the filters refuse is not called.
     */
    private void callBack(InetSocketAddress address, Optional<Hash16> obfuscateFor, String why) {
        int ip = Ipv4.fromBytes(address.getAddress().getAddress());
        // Nobody must have us probe our LAN or call a client we banned.
        if (!Ipv4.isUnicastPublicCandidate(ip)
                || address.getPort() == 0
                || this.ipFilter.blocks(ip)
                || this.isFilteredLan(ip)
                || this.uploads.isBanned(address.getAddress())) {
            logger.debug("Ignoring a callback request to {} ({})", address, why);
            return;
        }
        long now = System.nanoTime();
        this.callbacks.values().removeIf(at -> now - at >= CALLBACK_REPEAT.toNanos());
        if (this.calling.size() >= MAX_CALLBACKS_OPEN || this.callbacks.containsKey(address)) {
            logger.debug("Not calling {} back now", address);
            return;
        }
        this.callbacks.put(address, now);
        this.calling.add(address);
        Thread.ofVirtual().name("njmule-callback-" + address).start(() -> {
            try {
                TcpConnection connection =
                        TcpConnector.connectToClient(address, obfuscateFor, PeerGreetings.HELLO_TIMEOUT, this.random);
                if (!this.loop.postOutcome(() -> this.calledBack(address, connection, why))) {
                    connection.close();
                }
            } catch (IOException e) {
                logger.debug("Callback to {} ({}) failed: {}", address, why, e.getMessage());
                this.loop.postOutcome(() -> this.calling.remove(address));
            }
        });
    }

    // The client asks for what it wants over the link: our upload side answers it.
    private void calledBack(InetSocketAddress address, TcpConnection connection, String why) {
        PeerLink link = this.links.outgoing(connection, new PeerLinks.Opener() {
            @Override
            public void greeted(PeerLink greeted) {
                Network.this.calling.remove(address);
                logger.debug("Called {} back ({})", connection.remoteAddress(), why);
                try {
                    connection.setReadTimeout(PeerGreetings.IDLE_TIMEOUT);
                } catch (IOException e) {
                    connection.close();
                }
            }

            @Override
            public void failed(String reason) {
                Network.this.calling.remove(address);
                logger.debug("Callback to {} failed: {}", connection.remoteAddress(), reason);
            }
        });
        connection.start(link, frame -> Packing.unpack(frame, Packing.MAX_CLIENT_UNPACKED));
        connection.send(this.ourHello().encode(false));
    }

    // Our buddy relays a client's callback request: for a file we share or download, we connect to it.
    private void relayedCallback(LowIdMessages.Callback callback) {
        KadNode node = this.kad;
        Hash16 file = callback.file().toHash16();
        if (node == null || !node.isOurBuddyId(callback.buddyId()) || !this.offersFile(file)) {
            logger.debug("Ignoring a callback relayed for {}", file);
            return;
        }
        this.callBack(
                new InetSocketAddress(Ipv4.format(callback.requesterIp()), callback.requesterPort()),
                Optional.empty(),
                "through our buddy");
    }

    private boolean offersFile(Hash16 file) {
        return (this.shares != null && this.shares.get(file).isPresent())
                || this.downloads.download(file).isPresent();
    }

    // Client packets on the Kad socket: a client asks us for a direct callback, which only a firewalled Kad node takes.
    private void clientDatagram(int ip, int port, Packet packet) {
        KadNode node = this.kad;
        if (packet.opcode() != Opcodes.ClientUdp.DIRECTCALLBACKREQ.code() || node == null || !node.isFirewalled()) {
            return;
        }
        long now = System.nanoTime();
        this.directCallers.values().removeIf(at -> now - at >= DIRECT_CALLBACK_REPEAT.toNanos());
        if (this.directCallers.containsKey(ip) || this.directCallers.size() >= MAX_DIRECT_CALLERS) {
            logger.debug("Ignoring a direct callback request from {}", Ipv4.format(ip));
            return;
        }
        this.directCallers.put(ip, now);
        try {
            LowIdMessages.DirectCallbackRequest request = LowIdMessages.DirectCallbackRequest.decode(packet);
            boolean obfuscate = (request.connectOptions() & CRYPT_REQUESTED_OR_REQUIRED) != 0
                    && this.preferences.get().obfuscation() != ObfuscationMode.DISABLED;
            this.callBack(
                    new InetSocketAddress(Ipv4.format(ip), request.tcpPort()),
                    obfuscate ? Optional.of(request.userHash()) : Optional.empty(),
                    "direct callback");
        } catch (DecodeException e) {
            logger.debug("Bad direct callback request from {}: {}", Ipv4.format(ip), e.getMessage());
        }
    }

    // A node asked us to prove it reachable: we connect back and say so after the hellos.
    private void checkReachable(int ip, int port, Hash16 peer, int connectOptions) {
        InetSocketAddress address = new InetSocketAddress(Ipv4.format(ip), port);
        Optional<Hash16> obfuscateFor =
                (connectOptions & 0x01) != 0 && this.preferences.get().obfuscation() != ObfuscationMode.DISABLED
                        ? Optional.of(peer)
                        : Optional.empty();
        Thread.ofVirtual().name("njmule-kad-check-" + address).start(() -> {
            try {
                TcpConnection connection =
                        TcpConnector.connectToClient(address, obfuscateFor, PeerGreetings.HELLO_TIMEOUT, this.random);
                connection.start(new ReachabilityCheck(), frame -> Packing.unpack(frame, Packing.MAX_CLIENT_UNPACKED));
                connection.send(this.ourHello().encode(false));
                this.loop.schedule("Kad check close", PeerGreetings.HELLO_TIMEOUT, connection::close);
            } catch (IOException e) {
                logger.debug("Kad check of {} failed: {}", address, e.getMessage());
            }
        });
    }

    /** After the peer's hello answer, tells it that we could connect. */
    private static final class ReachabilityCheck implements TcpConnection.Handler {
        @Override
        public void onPacket(TcpConnection c, Packet packet) {
            if (packet.protocol() == Protocol.EDONKEY && packet.opcode() == Opcodes.Client.HELLOANSWER.code()) {
                c.setFrameLimit(FrameLimits.CLIENT);
                c.send(Packet.empty(Protocol.EMULE, Opcodes.Extended.KAD_FWTCPCHECK_ACK.code()));
            }
        }

        @Override
        public void onClosed(TcpConnection c, Optional<IOException> cause) {
            // Nothing to clean up.
        }
    }

    private SearchManager.KadSearches kadSearches(KadNode node) {
        return (keyword, expression, results) -> node.searchKeyword(keyword, Optional.of(expression), found -> {
            List<SearchResult> converted = new ArrayList<>();
            for (KadNode.FileResult r : found) {
                converted.add(new SearchResult(r.hash(), r.name(), r.size(), r.sources(), 0, r.type(), r.media()));
            }
            results.accept(converted);
        });
    }

    private DownloadManager.KadSources kadSources(KadNode node) {
        return new DownloadManager.KadSources() {
            @Override
            public boolean find(Hash16 file, long size, Consumer<List<DownloadManager.KadSource>> sources) {
                return node.findSources(file, size, found -> {
                    // A firewalled source can only connect to us: useless while we are firewalled too.
                    boolean callable =
                            !node.isFirewalled() || Network.this.context.clientId() >= ServerMessages.LOW_ID_LIMIT;
                    List<DownloadManager.KadSource> converted = new ArrayList<>();
                    for (KadNode.Source s : found) {
                        if (!s.isFirewalled() || callable) {
                            converted.add(kadSource(s));
                        }
                    }
                    sources.accept(converted);
                });
            }

            @Override
            public boolean requestCallback(DownloadManager.KadSource source, Hash16 file) {
                return Network.this.kad == node && Network.this.requestKadCallback(node, source, file);
            }

            // Firewalled, our address and port may be those of another client behind the same router.
            @Override
            public int publicIp() {
                return node.isFirewalled() ? 0 : node.publicIp();
            }
        };
    }

    private static DownloadManager.KadSource kadSource(KadNode.Source s) {
        InetSocketAddress address = new InetSocketAddress(Ipv4.format(s.ip()), s.tcpPort());
        Optional<DownloadManager.KadCallback> callback = Optional.empty();
        if (s.buddy().isPresent()) {
            KadNode.Buddy buddy = s.buddy().get();
            callback = Optional.of(new DownloadManager.KadCallback(
                    buddy.ip(), buddy.udpPort(), Optional.of(buddy.id().toHash16()), s.udpPort()));
        } else if (s.directCallback()) {
            callback = Optional.of(new DownloadManager.KadCallback(0, 0, Optional.empty(), s.udpPort()));
        }
        return new DownloadManager.KadSource(address, Optional.of(s.userHash()), s.connectOptions(), callback);
    }

    // Through the buddy of the source, plain; or straight to its Kad port, obfuscated when either side asks for it.
    private boolean requestKadCallback(KadNode node, DownloadManager.KadSource source, Hash16 file) {
        DownloadManager.KadCallback route = source.callback().orElseThrow();
        if (route.buddyId().isPresent()) {
            KadNode.Buddy buddy = new KadNode.Buddy(
                    route.buddyIp(),
                    route.buddyPort(),
                    UInt128.of(route.buddyId().get()));
            return !this.uploads.isBanned(InetAddresses.fromInteger(route.buddyIp()))
                    && node.requestCallback(buddy, file);
        }
        int ip = Ipv4.fromBytes(source.address().getAddress().getAddress());
        if (route.udpPort() == 0
                || source.userHash().isEmpty()
                || !Ipv4.isUnicastPublicCandidate(ip)
                || this.ipFilter.blocks(ip)
                || this.isFilteredLan(ip)) {
            return false;
        }
        ObfuscationMode mode = this.preferences.get().obfuscation();
        boolean obfuscate = (source.cryptOptions() & CRYPT_SUPPORTED) != 0
                && mode != ObfuscationMode.DISABLED
                && ((source.cryptOptions() & CRYPT_REQUESTED) != 0 || mode != ObfuscationMode.SUPPORTED);
        Packet request = new LowIdMessages.DirectCallbackRequest(
                        this.listenPort(), this.userHash, cryptOptions(mode) >>> 7)
                .encode();
        this.kadSocket.sendClient(
                ip, route.udpPort(), request, obfuscate ? source.userHash() : Optional.empty(), node.publicIp());
        return true;
    }

    private List<KadPublisher.Shared> kadShared() {
        List<KadPublisher.Shared> files = new ArrayList<>();
        if (this.shares != null) {
            for (SharedFiles.SharedFile f : this.shares.all()) {
                files.add(new KadPublisher.Shared(f.hash(), f.name(), f.size(), true));
            }
        }
        for (DownloadInfo d : this.downloads.downloads()) {
            boolean shared = this.downloads
                    .find(d.hash())
                    .map(x -> !x.verifiedParts().isEmpty())
                    .orElse(false);
            if (shared) {
                files.add(new KadPublisher.Shared(d.hash(), d.name(), d.size(), false));
            }
        }
        return files;
    }

    // Complete shared files, then downloads with checked parts.
    private List<FilePublisher.Offer> offers() {
        List<FilePublisher.Offer> offers = new ArrayList<>();
        if (this.shares != null) {
            for (SharedFiles.SharedFile f : this.shares.all()) {
                offers.add(new FilePublisher.Offer(f.hash(), f.name(), f.size(), true));
            }
        }
        for (DownloadInfo d : this.downloads.downloads()) {
            boolean shared = this.downloads
                    .find(d.hash())
                    .map(x -> !x.verifiedParts().isEmpty())
                    .orElse(false);
            if (shared) {
                offers.add(new FilePublisher.Offer(d.hash(), d.name(), d.size(), false));
            }
        }
        return offers;
    }

    private Optional<Inet4Address> ourPublicAddress() {
        KadNode node = this.kad;
        return publicAddress(this.session.id(), node == null ? 0 : node.publicIp());
    }

    // Our public address, which signatures of the secure identification are bound to: a HighID, else the address the
    // server reports with a LowID, else the one Kad nodes see.
    static Optional<Inet4Address> publicAddress(Optional<ServerMessages.IdChange> id, int kadIp) {
        int ip = id.map(ServerMessages.IdChange::publicIp).filter(a -> a != 0).orElse(kadIp);
        if (ip == 0) {
            return Optional.empty();
        }
        try {
            return Optional.of((Inet4Address) InetAddress.getByAddress(Ipv4.toBytes(ip)));
        } catch (UnknownHostException e) {
            return Optional.empty();
        }
    }

    void datagram(InetSocketAddress from, Packet packet) {
        // The socket takes IPv6 too, but servers are IPv4 only.
        if (!(from.getAddress() instanceof Inet4Address)) {
            return;
        }
        if (packet.opcode() == Opcodes.ServerUdp.GLOBSERVSTATRES.code()) {
            this.pinger.received(from, packet);
            return;
        }
        if (packet.opcode() != Opcodes.ServerUdp.GLOBSEARCHRES.code()) {
            return;
        }
        try {
            this.searches.datagramResults(from, ServerDatagrams.decodeSearchResults(packet));
        } catch (DecodeException e) {
            logger.debug("Malformed search answer from {}", from, e);
        }
    }

    private void serverUpdated(ServerMet.Server server) {
        this.publish.accept(new EngineEvent.ServerUpdated(this.info(server)));
        this.saveServersSoon();
    }

    private void saveServersSoon() {
        if (this.pendingSave == null || this.pendingSave.isDone()) {
            this.pendingSave = this.loop.schedule("save server.met", SAVE_DELAY, this::saveServers);
        }
    }

    // A server that failed as many times in a row as the preferences allow leaves the list;
    // static servers and the one we are on stay.
    private void deadServer(ServerMet.Server server) {
        this.removeDead(List.of(ServerList.addressOf(server)));
    }

    private void removeDeadServers() {
        this.removeDead(this.servers.dead(this.preferences.get().deadServerRetries()));
    }

    private void removeDead(List<ServerList.Address> dead) {
        Optional<ServerList.Address> current = this.session.server().map(ServerList::addressOf);
        List<ServerList.Address> gone =
                dead.stream().filter(a -> !current.equals(Optional.of(a))).toList();
        int removed = gone.isEmpty() ? 0 : this.removeServers(gone);
        if (removed > 0) {
            this.userLog.log(
                    LogLine.Level.INFO, removed == 1 ? "1 dead server removed" : removed + " dead servers removed");
            this.publish.accept(new EngineEvent.ServersChanged(this.servers.size()));
        }
    }

    // Servers learnt from the server we are on or from the clients that greet us: at low priority, named by
    // their address, when the preferences ask for them.
    private void serversLearnt(List<ServerMessages.Endpoint> learnt, String from) {
        int added = 0;
        for (ServerMessages.Endpoint endpoint : learnt) {
            String name = Ipv4.format(endpoint.ip()) + ":" + endpoint.port();
            ServerMet.Server server = new ServerMet.Server(
                    endpoint.ip(),
                    endpoint.port(),
                    List.of(
                            new Tag.StringTag(TagName.id(ServerTag.SERVERNAME), name),
                            new Tag.IntTag(TagName.id(ServerTag.PREFERENCE), ServerList.PRIORITY_LOW)));
            if (this.servers.add(server)) {
                added++;
            }
        }
        if (added > 0) {
            this.saveServersSoon();
            this.userLog.log(
                    LogLine.Level.INFO, (added == 1 ? "1 new server" : added + " new servers") + " from " + from);
            this.publish.accept(new EngineEvent.ServersChanged(this.servers.size()));
        }
    }

    private void serverOf(Hello hello) {
        if (this.preferences.get().addServersFromClients() && hello.serverIp() != 0 && hello.serverPort() != 0) {
            this.serversLearnt(List.of(new ServerMessages.Endpoint(hello.serverIp(), hello.serverPort())), "a client");
        }
    }

    private void saveServers() {
        try {
            this.servers.save(this.serverMet);
        } catch (IOException e) {
            logger.warn("Cannot save {}", this.serverMet, e);
        }
    }

    private static FileComments loadComments(Path file) {
        try {
            return FileComments.load(file);
        } catch (IOException e) {
            logger.warn("Cannot read {}, no file comments", file, e);
            return FileComments.empty(file);
        }
    }

    private static CancelledFiles loadCancelled(Path file, SecureRandom random) {
        try {
            return CancelledFiles.load(file, random);
        } catch (IOException e) {
            logger.warn("Cannot read {}, no cancelled files remembered", file, e);
            return CancelledFiles.empty(file, random);
        }
    }

    private ChatFilter chatFilter() {
        Preferences p = this.preferences.get();
        return new ChatFilter(p.messagesFromFriendsOnly(), ChatFilter.words(p.messageFilter()), p.advancedSpamFilter());
    }

    // What a client browsing our files may see, as the preferences say: nothing when they do not let it look; a
    // friend counts with its identity proven.
    private Optional<List<SharedFiles.SharedFile>> visibleSharedFiles(Hash16 userHash, boolean proven) {
        boolean visible = switch (this.preferences.get().sharedFilesVisibility()) {
            case EVERYBODY -> true;
            case FRIENDS -> proven && this.friends.contains(userHash);
            case NOBODY -> false;
        };
        if (!visible) {
            return Optional.empty();
        }
        return Optional.of(this.shares == null ? List.of() : this.shares.all());
    }

    // Servers and clients at LAN addresses are left out unless the preferences let them in.
    private boolean isFilteredLan(int ip) {
        return this.preferences.get().filterLanAddresses() && Ipv4.isLan(ip);
    }

    private static SpamFilter loadSpam(Path file) {
        try {
            return SpamFilter.load(file);
        } catch (IOException e) {
            logger.warn("Cannot read {}, no spam markers", file, e);
            return new SpamFilter();
        }
    }

    private void saveStatics() {
        try {
            this.servers.saveStatics(this.staticServers);
        } catch (IOException e) {
            logger.warn("Cannot save {}", this.staticServers, e);
        }
    }

    private boolean isOurServer(int ip) {
        return ip != 0 && ip == this.serverIp;
    }

    // Any thread: a Kad node we asked to connect to our TCP port.
    private boolean kadAwaits(int ip) {
        KadNode node = this.kad;
        return node != null && node.awaitsConnectionFrom(ip);
    }

    private static IpFilter loadIpFilter(Path file, Preferences preferences) {
        List<IpFilterDat.Range> ranges = List.of();
        if (Files.exists(file)) {
            try {
                ranges = IpFilterDat.read(Files.readAllBytes(file), IPFILTER_FILE);
            } catch (IOException e) {
                logger.warn("Cannot read {}, no IP filter", file, e);
            }
        }
        return IpFilter.of(ranges, preferences.ipFilterLevel())
                .allowing(IpWhitelist.ranges(preferences.ipFilterWhitelist()));
    }

    private static int cryptOptions(ObfuscationMode mode) {
        return switch (mode) {
            case DISABLED -> 0;
            case SUPPORTED -> Hello.MISC2_SUPPORTS_CRYPT;
            case REQUESTED -> Hello.MISC2_SUPPORTS_CRYPT | Hello.MISC2_REQUESTS_CRYPT;
            case REQUIRED -> Hello.MISC2_SUPPORTS_CRYPT | Hello.MISC2_REQUESTS_CRYPT | Hello.MISC2_REQUIRES_CRYPT;
        };
    }

    ServerInfo info(ServerMet.Server server) {
        ServerList.Address at = ServerList.addressOf(server);
        String address = at.toString();
        return new ServerInfo(
                address,
                server.name().orElse(address),
                server.description().orElse(""),
                server.users().orElse(0),
                server.files().orElse(0),
                server.maxUsers().orElse(0),
                (int) server.failedCount().orElse(0),
                ServerList.priority(server),
                (int) Math.min(Integer.MAX_VALUE, server.ping().orElse(0)),
                server.softFiles().orElse(0),
                server.hardFiles().orElse(0),
                server.lowIdUsers().orElse(0),
                server.version().orElse(""),
                ServerList.supportsObfuscation(server),
                this.servers.isStatic(at));
    }

    private final class SessionEvents implements ServerSession.Listener {
        @Override
        public void connecting(ServerMet.Server server) {
            Network.this.serverIp = server.ip();
            ServerInfo info = Network.this.info(server);
            Network.this.userLog.log(LogLine.Level.INFO, "Connecting to " + describe(info));
            Network.this.publish.accept(new EngineEvent.ServerConnecting(info));
        }

        @Override
        public void connected(ServerMet.Server server, ServerMessages.IdChange id) {
            Network.this.context = new ServerContext(id.clientId(), server.ip(), server.port());
            ServerInfo info = Network.this.info(server);
            boolean obfuscated = Network.this.session.isObfuscated();
            Network.this.userLog.log(
                    LogLine.Level.SUCCESS,
                    "Connected to "
                            + describe(info)
                            + " with "
                            + (id.isLowId() ? "LowID " : "HighID ")
                            + id.clientId()
                            + (obfuscated ? ", obfuscated" : ""));
            Network.this.publish.accept(new EngineEvent.ServerConnected(info, id.clientId(), id.isLowId(), obfuscated));
            Network.this.saveServers();
            Network.this.pinger.start();
            Network.this.downloads.serverConnected();
            Network.this.publisher.connected();
            Network.this.removeDeadServers();
            if (Network.this.preferences.get().addServersFromServer()) {
                Network.this.session.send(ServerMessages.getServerList());
            }
        }

        // Our hello, the sources and Kad tell the id the server gave us last.
        @Override
        public void idChanged(ServerMet.Server server, ServerMessages.IdChange id) {
            Network.this.context = new ServerContext(id.clientId(), server.ip(), server.port());
            Network.this.userLog.log(
                    LogLine.Level.INFO,
                    "The server gave us a new id: " + (id.isLowId() ? "LowID " : "HighID ") + id.clientId());
        }

        @Override
        public void serverList(ServerMet.Server server, List<ServerMessages.Endpoint> servers) {
            if (Network.this.preferences.get().addServersFromServer()) {
                Network.this.serversLearnt(servers, describe(Network.this.info(server)));
            }
        }

        @Override
        public void disconnected(Optional<ServerMet.Server> server, String reason) {
            Network.this.serverIp = 0;
            Network.this.context = ServerContext.NONE;
            Network.this.publisher.disconnected();
            Network.this.searches.serverLost();
            Network.this.pinger.stop();
            Optional<ServerInfo> info = server.map(Network.this::info);
            Network.this.userLog.log(
                    LogLine.Level.INFO,
                    info.map(i -> "Disconnected from " + describe(i)).orElse("Not connected") + ": " + reason);
            Network.this.publish.accept(new EngineEvent.ServerDisconnected(info, reason));
        }

        @Override
        public void message(ServerMet.Server server, String line) {
            // Warnings and errors go to the event log too; the rest belongs to the server's information.
            if (line.startsWith("ERROR")) {
                Network.this.userLog.log(LogLine.Level.ERROR, line);
            } else if (line.startsWith("WARNING")) {
                Network.this.userLog.log(LogLine.Level.WARNING, line);
            } else {
                logger.info("{}: {}", describe(Network.this.info(server)), line);
            }
            Network.this.publish.accept(new EngineEvent.ServerMessage(Network.this.info(server), line));
        }

        @Override
        public void updated(ServerMet.Server server) {
            Network.this.serverUpdated(server);
        }

        @Override
        public void searchResults(ServerMet.Server server, ServerMessages.SearchResults results) {
            Network.this.searches.serverResults(server, results);
        }

        @Override
        public void callbackRequested(ServerMessages.CallbackRequested request) {
            int ip = request.endpoint().ip();
            // The client may require obfuscation, which needs its user hash.
            boolean obfuscate = request.userHash().isPresent()
                    && (request.cryptOptions() & CRYPT_REQUESTED_OR_REQUIRED) != 0
                    && Network.this.preferences.get().obfuscation() != ObfuscationMode.DISABLED;
            // A server could otherwise have us open any number of connections, or the same one again and again.
            Network.this.callBack(
                    new InetSocketAddress(Ipv4.format(ip), request.endpoint().port()),
                    obfuscate ? request.userHash() : Optional.empty(),
                    "asked by the server");
        }

        @Override
        public void foundSources(ServerMessages.FoundSources sources) {
            Network.this.downloads.foundSources(sources);
        }

        private static String describe(ServerInfo info) {
            return info.name().equals(info.address()) ? info.address() : info.name() + " (" + info.address() + ")";
        }
    }

    private final class ServerLinkImpl implements DownloadManager.ServerLink {
        @Override
        public boolean askSources(Hash16 file, long size) {
            Optional<ServerMessages.IdChange> id = Network.this.session.id();
            if (!Network.this.session.isConnected() || id.isEmpty()) {
                return false;
            }
            int flags = id.get().serverFlags();
            if (size > 0xFFFF_FFFFL && (flags & ServerMessages.TCP_LARGEFILES) == 0) {
                return false;
            }
            boolean obfuscated = (flags & ServerMessages.TCP_OBFUSCATION) != 0
                    && Network.this.preferences.get().obfuscation() != ObfuscationMode.DISABLED;
            return Network.this.session.send(ServerMessages.getSources(file, size, obfuscated));
        }

        @Override
        public long clientId() {
            return Network.this.context.clientId();
        }

        @Override
        public int serverIp() {
            return Network.this.context.serverIp();
        }

        @Override
        public boolean requestCallback(long lowId) {
            return Network.this.session.send(ServerMessages.callbackRequest(lowId));
        }
    }

    private final class DownloadEvents implements DownloadManager.Listener {
        @Override
        public void diskSpace(boolean enough) {
            if (enough) {
                Network.this.userLog.log(
                        LogLine.Level.INFO, "There is free space on the temp disk again: downloads go on");
            } else {
                Network.this.userLog.log(
                        LogLine.Level.WARNING,
                        "Not enough free space on the temp disk: downloads wait until there is room");
            }
            Network.this.publish.accept(new EngineEvent.DiskSpace(Network.this.tempDir(), enough));
        }

        @Override
        public void added(DownloadInfo download) {
            Network.this.publish.accept(new EngineEvent.DownloadAdded(download));
        }

        @Override
        public void progress(DownloadInfo download) {
            Network.this.publish.accept(new EngineEvent.DownloadProgress(download));
        }

        @Override
        public void completed(DownloadInfo download, Path file, PartFile part) {
            Network.this.userLog.log(LogLine.Level.SUCCESS, "Finished downloading " + download.name());
            if (Network.this.shares != null) {
                Network.this.shares.finished(file, part);
            }
            Network.this.publisher.changed(Optional.of(download.hash()));
            Network.this.publish.accept(new EngineEvent.DownloadCompleted(download, file));
            Network.this.startNext(download.category());
        }

        @Override
        public void failed(DownloadInfo download, String reason) {
            Network.this.userLog.log(LogLine.Level.ERROR, "Download " + download.name() + " stopped: " + reason);
            Network.this.publish.accept(new EngineEvent.DownloadFailed(download, reason));
        }

        @Override
        public void removed(Hash16 hash) {
            Network.this.publish.accept(new EngineEvent.DownloadRemoved(hash));
        }
    }

    private final class ShareEvents implements SharedFiles.Listener {
        @Override
        public void shared(SharedFiles.SharedFile file) {
            logger.debug("Shared {}", file.name());
            Network.this.publisher.changed(Optional.empty());
        }

        @Override
        public void unshared(SharedFiles.SharedFile file) {
            logger.debug("No longer shared: {}", file.name());
        }
    }

    private final class SearchEvents implements SearchManager.Listener {
        @Override
        public void results(SearchId search, List<SearchResult> changed) {
            Network.this.publish.accept(new EngineEvent.SearchResults(search, changed));
        }

        @Override
        public void ended(SearchId search, int results) {
            Network.this.publish.accept(new EngineEvent.SearchEnded(search, results));
        }
    }
}
