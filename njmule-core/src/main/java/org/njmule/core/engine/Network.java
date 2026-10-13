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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.njmule.core.api.DownloadInfo;
import org.njmule.core.api.Ed2kLink;
import org.njmule.core.api.EngineEvent;
import org.njmule.core.api.EngineStatus;
import org.njmule.core.api.Hash16;
import org.njmule.core.api.KadInfo;
import org.njmule.core.api.LogLine;
import org.njmule.core.api.PeerInfo;
import org.njmule.core.api.SearchId;
import org.njmule.core.api.SearchRequest;
import org.njmule.core.api.SearchResult;
import org.njmule.core.api.ServerInfo;
import org.njmule.core.api.SharedFileInfo;
import org.njmule.core.api.UploadInfo;
import org.njmule.core.config.ObfuscationMode;
import org.njmule.core.config.Preferences;
import org.njmule.core.download.DownloadManager;
import org.njmule.core.files.PartFile;
import org.njmule.core.files.SharedFiles;
import org.njmule.core.kad.KadIdentity;
import org.njmule.core.kad.KadNode;
import org.njmule.core.kad.KadPublisher;
import org.njmule.core.kad.KadSocket;
import org.njmule.core.kad.UInt128;
import org.njmule.core.net.ProxyRoute;
import org.njmule.core.net.TcpConnection;
import org.njmule.core.net.TcpConnector;
import org.njmule.core.net.TcpListener;
import org.njmule.core.peer.Identities;
import org.njmule.core.peer.IdentityHandshake;
import org.njmule.core.peer.PeerGreetings;
import org.njmule.core.persist.AtomicFiles;
import org.njmule.core.persist.format.IpFilterDat;
import org.njmule.core.persist.format.Known2Met;
import org.njmule.core.persist.format.KnownMet;
import org.njmule.core.persist.format.NodesDat;
import org.njmule.core.persist.format.ServerMet;
import org.njmule.core.protocol.ed2k.Hello;
import org.njmule.core.protocol.ed2k.ServerDatagrams;
import org.njmule.core.protocol.ed2k.ServerMessages;
import org.njmule.core.protocol.io.DecodeException;
import org.njmule.core.protocol.kad.KadMessages;
import org.njmule.core.protocol.packet.FrameLimits;
import org.njmule.core.protocol.packet.Opcodes;
import org.njmule.core.protocol.packet.Packet;
import org.njmule.core.protocol.packet.Packing;
import org.njmule.core.protocol.packet.Protocol;
import org.njmule.core.search.SearchManager;
import org.njmule.core.security.ConnectionGuard;
import org.njmule.core.security.IpFilter;
import org.njmule.core.server.FilePublisher;
import org.njmule.core.server.ServerList;
import org.njmule.core.server.ServerPinger;
import org.njmule.core.server.ServerSession;
import org.njmule.core.server.ServerUdp;
import org.njmule.core.upload.Credits;
import org.njmule.core.upload.UploadManager;
import org.njmule.core.upnp.Igd;
import org.njmule.core.upnp.PortForwarding;
import org.njmule.core.util.Bytes;
import org.njmule.core.util.Ipv4;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The network side of the engine: the listening socket, the server session, server UDP queries,
 * searches, the hello exchange with peers, the downloads, the shared files and the uploads. Engine
 * thread only, except the methods marked otherwise.
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

    private static final Logger LOG = LoggerFactory.getLogger(Network.class);

    /** What our hello says about the server we are on. */
    private record ServerContext(long clientId, int serverIp, int serverPort) {
        static final ServerContext NONE = new ServerContext(0, 0, 0);
    }

    private final EngineLoop loop;
    private final Supplier<Preferences> preferences;
    private final Consumer<EngineEvent> publish;
    private final UserLog userLog;
    private final Hash16 userHash;
    private final Path serverMet;
    private final ServerList servers;
    private final ServerSession session;
    private final SearchManager searches;
    private final ServerPinger pinger;
    private final PeerGreetings greetings;
    private final IpFilter ipFilter;
    private final DownloadManager downloads;
    private final Credits credits;
    private final UploadManager uploads;
    private final FilePublisher publisher;
    private KadNode kad;
    private KadSocket kadSocket;
    private PortForwarding forwarding;
    private final Totals totals;
    private final long started;
    private KadPublisher kadPublisher;
    private final Identities identities;
    private final SecureRandom random;
    private final Path configDir;
    private final Supplier<Path> incomingDir;
    private SharedFiles shares;
    private TcpListener listener;
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
            Supplier<Path> incomingDir)
            throws IOException {
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
        this.started = System.nanoTime();
        this.random = random;
        Identities identities =
                new Identities(
                        IdentityHandshake.Identity.of(
                                Identities.loadOrCreateKey(configDir, random)),
                        hash ->
                                this.credits
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
        this.ipFilter = loadIpFilter(configDir.resolve(IPFILTER_FILE));
        this.session =
                new ServerSession(
                        loop,
                        this.servers,
                        preferences,
                        userHash,
                        this::listenPort,
                        new SessionEvents(),
                        random);
        this.searches =
                new SearchManager(
                        loop,
                        this.session,
                        this.servers,
                        () -> Optional.ofNullable(this.udp),
                        () -> Optional.ofNullable(this.kad).map(k -> this.kadSearches(k)),
                        new SearchEvents());
        this.pinger =
                new ServerPinger(
                        loop,
                        this.servers,
                        () -> Optional.ofNullable(this.udp),
                        () -> this.session.id().map(ServerMessages.IdChange::publicIp).orElse(0),
                        this::serverUpdated,
                        random,
                        Clock.systemUTC());
        this.greetings =
                new PeerGreetings(
                        userHash,
                        this::ourHello,
                        () -> preferences.get().obfuscation() != ObfuscationMode.REQUIRED,
                        random);
        this.downloads =
                DownloadManager.open(
                        loop,
                        tempDir,
                        incomingDir,
                        this::ourHello,
                        () -> {
                            Preferences prefs = preferences.get();
                            return new DownloadManager.Settings(
                                    prefs.obfuscation(),
                                    prefs.maxSourcesPerFile(),
                                    this.listenPort());
                        },
                        new ServerLinkImpl(),
                        ip -> this.ipFilter.blocks(ip),
                        random,
                        Ticker.systemTicker(),
                        identities,
                        this.credits::downloaded,
                        () -> Optional.ofNullable(this.kad).map(k -> this.kadSources(k)),
                        new DownloadEvents());
        this.uploads =
                new UploadManager(
                        loop,
                        hash -> this.shares == null ? Optional.empty() : this.shares.get(hash),
                        this.downloads::find,
                        this.downloads::exchangeable,
                        this::ourHello,
                        () ->
                                new UploadManager.Settings(
                                        preferences.get().maxUploadKiBps(),
                                        preferences.get().obfuscation()),
                        identities,
                        this.credits,
                        ip -> {
                            if (this.kad != null) {
                                this.kad.reachableConfirmed(ip);
                            }
                        },
                        random,
                        Ticker.systemTicker());
        this.identities = identities;
        this.publisher =
                new FilePublisher(
                        this.session, this::offers, this::listenPort, Ticker.systemTicker());
        PeerGreetings.Router forDownloads = this.downloads.router();
        PeerGreetings.Router forUploads = this.uploads.router();
        // A source calling back with a slot first; any other client may want our files.
        this.greetings.routeTo(
                (connection, peer) -> {
                    Optional<TcpConnection.Handler> handler = forDownloads.route(connection, peer);
                    return handler.isPresent() ? handler : forUploads.route(connection, peer);
                });
    }

    /** Opens the listening socket and the server UDP socket; failures leave us reachable less. */
    void start() {
        ProxyRoute.install(() -> this.preferences.get().proxy());
        int port = this.preferences.get().tcpPort();
        ConnectionGuard guard =
                new ConnectionGuard(
                        () -> this.ipFilter, ConnectionGuard.Limits.DEFAULT, Ticker.systemTicker());
        try {
            this.listener =
                    TcpListener.open(new InetSocketAddress(port), guard, this.greetings::accepted);
        } catch (IOException e) {
            this.userLog.log(
                    LogLine.Level.WARNING,
                    "Cannot listen on TCP port "
                            + port
                            + " ("
                            + e.getMessage()
                            + "): other clients cannot connect, servers will give a LowID");
        }
        try {
            this.udp =
                    ServerUdp.open(
                            0, (from, packet) -> this.loop.post(() -> this.datagram(from, packet)));
        } catch (IOException e) {
            this.userLog.log(
                    LogLine.Level.WARNING, "No server UDP socket: global searches are off");
        }
        this.shares =
                SharedFiles.open(this.loop, this.configDir, this.incomingDir, new ShareEvents());
        this.uploads.start();
        if (this.preferences.get().kadEnabled()) {
            this.startKad();
        }
        if (this.preferences.get().upnp() && this.listener != null) {
            OptionalInt udp =
                    this.kadSocket == null
                            ? OptionalInt.empty()
                            : OptionalInt.of(this.kadSocket.port());
            this.forwarding =
                    PortForwarding.start(
                            Igd.SSDP,
                            this.listenPort(),
                            udp,
                            line ->
                                    this.loop.post(
                                            () -> this.userLog.log(LogLine.Level.INFO, line)));
        }
        this.loop.scheduleRepeating("save totals", TOTALS_SAVE, TOTALS_SAVE, this::saveTotals);
        this.loop.scheduleRepeating(
                "offer shared files",
                Duration.ofSeconds(1),
                Duration.ofSeconds(1),
                this.publisher::tick);
        LOG.info(
                "{} servers known, {} IP filter ranges, user hash {}",
                this.servers.size(),
                this.ipFilter.rangeCount(),
                this.userHash);
    }

    List<ServerInfo> servers() {
        List<ServerInfo> all = new ArrayList<>();
        for (ServerMet.Server server : this.servers.all()) {
            all.add(info(server));
        }
        return all;
    }

    boolean removeServer(ServerList.Address address) {
        boolean removed = this.servers.remove(address);
        if (removed) {
            this.saveServers();
        }
        return removed;
    }

    EngineStatus status() {
        Optional<ServerMessages.IdChange> id = this.session.id();
        return new EngineStatus(
                id.isPresent() ? this.session.server().map(Network::info) : Optional.empty(),
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
                this.totals.downloaded() + this.downloads.downloaded(),
                this.totals.uploaded() + this.uploads.uploaded(),
                this.totals.sessions() + 1,
                this.sessionSeconds(),
                this.totals.seconds() + this.sessionSeconds());
    }

    private long sessionSeconds() {
        return (System.nanoTime() - this.started) / 1_000_000_000L;
    }

    private void saveTotals() {
        this.totals.save(
                this.downloads.downloaded(), this.uploads.uploaded(), this.sessionSeconds());
    }

    List<UploadInfo> uploads() {
        return this.uploads.uploads();
    }

    /** Adds the servers of another client's server.met; returns how many were new. */
    int addServers(Path file) throws IOException {
        List<ServerMet.Server> read = ServerMet.read(Files.readAllBytes(file));
        int added = this.servers.addAll(read);
        if (added > 0) {
            this.servers.save(this.serverMet);
        }
        return added;
    }

    /** Connects to the server at {@code ip:port} (added to the list if new), or to any. */
    void connect(Optional<ServerList.Address> address) {
        if (address.isEmpty()) {
            if (this.servers.candidates().isEmpty()) {
                throw new IllegalStateException("no known server: add a server.met or give one");
            }
            this.session.connect(Optional.empty());
            return;
        }
        ServerMet.Server server =
                this.servers
                        .get(address.get())
                        .orElseGet(
                                () ->
                                        new ServerMet.Server(
                                                address.get().ip(),
                                                address.get().port(),
                                                List.of()));
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
        DownloadInfo info = this.downloads.add(link);
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

    boolean cancelDownload(Hash16 hash) {
        return this.downloads.cancel(hash);
    }

    /** Downloads and uploads in detail, for diagnostics. */
    String describe() {
        return "downloads:\n" + this.downloads.describe() + "uploads: " + this.uploads.describe();
    }

    List<SharedFileInfo> sharedFiles() {
        List<SharedFileInfo> all = new ArrayList<>();
        if (this.shares != null) {
            for (SharedFiles.SharedFile f : this.shares.all()) {
                all.add(new SharedFileInfo(f.hash(), f.name(), f.size(), f.path()));
            }
        }
        return all;
    }

    void shareDirectory(Path directory) throws IOException {
        if (this.shares == null) {
            throw new IllegalStateException("the network is not started");
        }
        this.shares.addDirectory(directory);
    }

    /**
     * Adds Kad contacts to the running node, or to {@code nodes.dat}; returns how many were new.
     */
    int addKadContacts(NodesDat.Contents contents) throws IOException {
        if (this.kad != null) {
            int added = this.kad.addContacts(contents);
            this.kad.saveNodes();
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
     * Any thread: says hello to a client and runs the identification; the answer comes when its
     * outcome is known or after {@link #IDENTIFY_WAIT}. The connection stays open until idle.
     */
    CompletableFuture<PeerInfo> hello(InetSocketAddress peer) {
        CompletableFuture<PeerInfo> answer = new CompletableFuture<>();
        Thread.ofVirtual()
                .name("njmule-hello-" + peer)
                .start(
                        () -> {
                            try {
                                TcpConnection connection =
                                        TcpConnector.connectToClient(
                                                peer,
                                                Optional.empty(),
                                                PeerGreetings.HELLO_TIMEOUT,
                                                this.random);
                                if (!this.loop.post(() -> this.greet(connection, answer))) {
                                    connection.close();
                                }
                            } catch (IOException e) {
                                answer.completeExceptionally(e);
                            }
                        });
        return answer.orTimeout(
                PeerGreetings.HELLO_TIMEOUT.plus(IDENTIFY_WAIT).toSeconds() * 2, TimeUnit.SECONDS);
    }

    private void greet(TcpConnection connection, CompletableFuture<PeerInfo> answer) {
        Greeting greeting = new Greeting(connection, answer);
        connection.start(greeting, frame -> Packing.unpack(frame, Packing.MAX_CLIENT_UNPACKED));
        connection.send(this.ourHello().encode(false));
        this.loop.schedule(
                "hello identification",
                PeerGreetings.HELLO_TIMEOUT.plus(IDENTIFY_WAIT),
                greeting::settle);
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
            if (packet.protocol() == Protocol.EDONKEY
                    && packet.opcode() == Opcodes.Client.HELLOANSWER.code()) {
                c.setFrameLimit(FrameLimits.CLIENT);
            }
            Network.this.loop.post(() -> this.received(packet));
        }

        @Override
        public void onClosed(TcpConnection c, Optional<IOException> cause) {
            Network.this.loop.post(
                    () -> {
                        if (this.peer == null) {
                            this.answer.completeExceptionally(
                                    cause.orElseGet(
                                            () -> new IOException("closed before the hello")));
                        } else {
                            this.settle();
                        }
                    });
        }

        private void received(Packet packet) {
            try {
                if (this.peer == null) {
                    if (packet.protocol() == Protocol.EDONKEY
                            && packet.opcode() == Opcodes.Client.HELLOANSWER.code()) {
                        this.peer = Hello.decode(packet);
                        this.connection.setReadTimeout(PeerGreetings.IDLE_TIMEOUT);
                        this.identity =
                                Network.this.identities.start(
                                        this.peer,
                                        this.connection.remoteAddress().getAddress(),
                                        this.connection::send);
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
                LOG.debug(
                        "Bad packet from {}: {}", this.connection.remoteAddress(), e.getMessage());
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
                outcome =
                        switch (this.identity.trust()) {
                            case IDENTIFIED -> PeerInfo.Identification.VERIFIED;
                            case FAILED -> PeerInfo.Identification.FAILED;
                            default -> PeerInfo.Identification.PENDING;
                        };
            }
            this.answer.complete(
                    new PeerInfo(
                            info.address(),
                            info.userHash(),
                            info.nick(),
                            info.software(),
                            info.obfuscated(),
                            outcome));
        }
    }

    void close() {
        this.saveTotals();
        if (this.forwarding != null) {
            this.forwarding.close();
        }
        if (this.kad != null) {
            this.kad.close();
            this.kadSocket.close();
        }
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
                LOG.debug("Closing the listener failed", e);
            }
        }
        if (this.udp != null) {
            this.udp.close();
        }
        this.saveServers();
    }

    private int listenPort() {
        TcpListener l = this.listener;
        return l != null ? l.port() : this.preferences.get().tcpPort();
    }

    // Called from connection threads: reads only volatile state and the preferences.
    private Hello ourHello() {
        Preferences prefs = this.preferences.get();
        ServerContext server = this.context;
        // Kad listens on the client UDP port; announced while Kad is connected.
        KadSocket socket = this.kadSocket;
        KadNode node = this.kad;
        int kadPort = socket != null && node != null && node.isConnected() ? socket.port() : 0;
        return Hello.of(
                this.userHash,
                server.clientId(),
                this.listenPort(),
                prefs.nick(),
                kadPort,
                kadPort,
                Hello.MISC1_UNICODE
                        | Hello.MISC1_DATA_COMPRESSION
                        | IdentityHandshake.SUPPORTED << 16
                        | Hello.MISC1_NO_VIEW_SHARED_FILES,
                Hello.MISC2_LARGE_FILES
                        | Hello.MISC2_SOURCE_EXCHANGE2
                        | cryptOptions(prefs.obfuscation())
                        | (kadPort != 0 ? KadMessages.VERSION : 0),
                server.serverIp(),
                server.serverPort());
    }

    /** Asks the Kad node at {@code ip:port} for contacts. */
    void kadBootstrap(int ip, int port) {
        if (this.kad == null) {
            throw new IllegalStateException("Kad is not running");
        }
        this.kad.bootstrap(ip, port);
    }

    /** The state of Kad, empty when it does not run. */
    Optional<KadInfo> kadInfo() {
        KadNode node = this.kad;
        if (node == null) {
            return Optional.empty();
        }
        return Optional.of(
                new KadInfo(
                        node.isConnected(),
                        node.contacts(),
                        node.verifiedContacts(),
                        node.isFirewalled(),
                        node.id().toHex()));
    }

    private void startKad() {
        Preferences prefs = this.preferences.get();
        try {
            KadIdentity identity = KadIdentity.loadOrCreate(this.configDir, this.random);
            this.kadSocket =
                    KadSocket.open(
                            prefs.udpPort(),
                            this.loop,
                            this.userHash,
                            identity,
                            ip -> this.ipFilter.blocks(ip),
                            this.random);
            this.kad =
                    new KadNode(
                            identity,
                            this.kadSocket,
                            this::checkReachable,
                            new KadNode.Settings(
                                    this.listenPort(),
                                    this.userHash,
                                    cryptOptions(prefs.obfuscation()) >>> 7,
                                    ip -> false),
                            this.configDir,
                            this.random,
                            Ticker.systemTicker());
            this.kadSocket.deliverTo(this.kad);
            this.kad.loadNodes();
            this.kadPublisher = new KadPublisher(this.kad, this::kadShared, Ticker.systemTicker());
            this.loop.scheduleRepeating(
                    "Kad",
                    Duration.ofSeconds(1),
                    Duration.ofSeconds(1),
                    () -> {
                        this.kad.tick();
                        this.kadPublisher.tick();
                    });
            LOG.info("Kad id {} on UDP port {}", identity.id(), this.kadSocket.port());
        } catch (IOException e) {
            this.userLog.log(
                    LogLine.Level.WARNING,
                    "Cannot start Kad on UDP port " + prefs.udpPort() + ": " + e.getMessage());
            if (this.kadSocket != null) {
                this.kadSocket.close();
            }
            this.kad = null;
            this.kadSocket = null;
        }
    }

    // A node asked us to prove it reachable: we connect back and say so after the hellos.
    private void checkReachable(int ip, int port, Hash16 peer, int connectOptions) {
        InetSocketAddress address = new InetSocketAddress(Ipv4.format(ip), port);
        Optional<Hash16> obfuscateFor =
                (connectOptions & 0x01) != 0
                                && this.preferences.get().obfuscation() != ObfuscationMode.DISABLED
                        ? Optional.of(peer)
                        : Optional.empty();
        Thread.ofVirtual()
                .name("njmule-kad-check-" + address)
                .start(
                        () -> {
                            try {
                                TcpConnection connection =
                                        TcpConnector.connectToClient(
                                                address,
                                                obfuscateFor,
                                                PeerGreetings.HELLO_TIMEOUT,
                                                this.random);
                                connection.start(
                                        new ReachabilityCheck(),
                                        frame ->
                                                Packing.unpack(frame, Packing.MAX_CLIENT_UNPACKED));
                                connection.send(this.ourHello().encode(false));
                                this.loop.schedule(
                                        "Kad check close",
                                        PeerGreetings.HELLO_TIMEOUT,
                                        connection::close);
                            } catch (IOException e) {
                                LOG.debug("Kad check of {} failed: {}", address, e.getMessage());
                            }
                        });
    }

    /** After the peer's hello answer, tells it that we could connect. */
    private static final class ReachabilityCheck implements TcpConnection.Handler {
        @Override
        public void onPacket(TcpConnection c, Packet packet) {
            if (packet.protocol() == Protocol.EDONKEY
                    && packet.opcode() == Opcodes.Client.HELLOANSWER.code()) {
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
        return (keyword, expression, results) ->
                node.searchKeyword(
                        keyword,
                        Optional.of(expression),
                        found -> {
                            List<SearchResult> converted = new ArrayList<>();
                            for (KadNode.FileResult r : found) {
                                converted.add(
                                        new SearchResult(
                                                r.hash(),
                                                r.name(),
                                                r.size(),
                                                r.sources(),
                                                0,
                                                r.type()));
                            }
                            results.accept(converted);
                        });
    }

    private DownloadManager.KadSources kadSources(KadNode node) {
        return (file, size, sources) ->
                node.findSources(
                        file,
                        size,
                        found -> {
                            List<InetSocketAddress> addresses = new ArrayList<>();
                            for (KadNode.Source s : found) {
                                addresses.add(
                                        new InetSocketAddress(Ipv4.format(s.ip()), s.tcpPort()));
                            }
                            sources.accept(addresses);
                        });
    }

    private List<KadPublisher.Shared> kadShared() {
        List<KadPublisher.Shared> files = new ArrayList<>();
        if (this.shares != null) {
            for (SharedFiles.SharedFile f : this.shares.all()) {
                files.add(new KadPublisher.Shared(f.hash(), f.name(), f.size(), true));
            }
        }
        for (DownloadInfo d : this.downloads.downloads()) {
            boolean shared =
                    this.downloads
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
            boolean shared =
                    this.downloads
                            .find(d.hash())
                            .map(x -> !x.verifiedParts().isEmpty())
                            .orElse(false);
            if (shared) {
                offers.add(new FilePublisher.Offer(d.hash(), d.name(), d.size(), false));
            }
        }
        return offers;
    }

    // Our public address, known when the server gave us a HighID.
    private Optional<Inet4Address> ourPublicAddress() {
        long id = this.context.clientId();
        if (id < ServerMessages.LOW_ID_LIMIT) {
            return Optional.empty();
        }
        try {
            return Optional.of(
                    (Inet4Address) InetAddress.getByAddress(Ipv4.toBytes(Ipv4.fromEd2k(id))));
        } catch (UnknownHostException e) {
            return Optional.empty();
        }
    }

    private void datagram(InetSocketAddress from, Packet packet) {
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
            LOG.debug("Malformed search answer from {}", from, e);
        }
    }

    private void serverUpdated(ServerMet.Server server) {
        this.publish.accept(new EngineEvent.ServerUpdated(info(server)));
        if (this.pendingSave == null || this.pendingSave.isDone()) {
            this.pendingSave = this.loop.schedule("save server.met", SAVE_DELAY, this::saveServers);
        }
    }

    private void saveServers() {
        try {
            this.servers.save(this.serverMet);
        } catch (IOException e) {
            LOG.warn("Cannot save {}", this.serverMet, e);
        }
    }

    private static IpFilter loadIpFilter(Path file) {
        if (!Files.exists(file)) {
            return IpFilter.empty();
        }
        try {
            return IpFilter.of(
                    IpFilterDat.read(Files.readAllBytes(file), IPFILTER_FILE),
                    IpFilter.DEFAULT_LEVEL);
        } catch (IOException e) {
            LOG.warn("Cannot read {}, no IP filter", file, e);
            return IpFilter.empty();
        }
    }

    private static int cryptOptions(ObfuscationMode mode) {
        return switch (mode) {
            case DISABLED -> 0;
            case SUPPORTED -> Hello.MISC2_SUPPORTS_CRYPT;
            case REQUESTED -> Hello.MISC2_SUPPORTS_CRYPT | Hello.MISC2_REQUESTS_CRYPT;
            case REQUIRED ->
                    Hello.MISC2_SUPPORTS_CRYPT
                            | Hello.MISC2_REQUESTS_CRYPT
                            | Hello.MISC2_REQUIRES_CRYPT;
        };
    }

    static ServerInfo info(ServerMet.Server server) {
        String address = ServerList.addressOf(server).toString();
        return new ServerInfo(
                address,
                server.name().orElse(address),
                server.description().orElse(""),
                server.users().orElse(0),
                server.files().orElse(0),
                server.maxUsers().orElse(0),
                (int) server.failedCount().orElse(0),
                ServerList.priority(server));
    }

    private final class SessionEvents implements ServerSession.Listener {
        @Override
        public void connecting(ServerMet.Server server) {
            ServerInfo info = info(server);
            Network.this.userLog.log(LogLine.Level.INFO, "Connecting to " + describe(info));
            Network.this.publish.accept(new EngineEvent.ServerConnecting(info));
        }

        @Override
        public void connected(ServerMet.Server server, ServerMessages.IdChange id) {
            Network.this.context = new ServerContext(id.clientId(), server.ip(), server.port());
            ServerInfo info = info(server);
            boolean obfuscated = Network.this.session.isObfuscated();
            Network.this.userLog.log(
                    LogLine.Level.SUCCESS,
                    "Connected to "
                            + describe(info)
                            + " with "
                            + (id.isLowId() ? "LowID " : "HighID ")
                            + id.clientId()
                            + (obfuscated ? ", obfuscated" : ""));
            Network.this.publish.accept(
                    new EngineEvent.ServerConnected(info, id.clientId(), id.isLowId(), obfuscated));
            Network.this.saveServers();
            Network.this.pinger.start();
            Network.this.downloads.serverConnected();
            Network.this.publisher.connected();
        }

        @Override
        public void disconnected(Optional<ServerMet.Server> server, String reason) {
            Network.this.context = ServerContext.NONE;
            Network.this.publisher.disconnected();
            Network.this.searches.stopAll();
            Network.this.pinger.stop();
            Optional<ServerInfo> info = server.map(Network::info);
            Network.this.userLog.log(
                    LogLine.Level.INFO,
                    info.map(i -> "Disconnected from " + describe(i)).orElse("Not connected")
                            + ": "
                            + reason);
            Network.this.publish.accept(new EngineEvent.ServerDisconnected(info, reason));
        }

        @Override
        public void message(ServerMet.Server server, String line) {
            LogLine.Level level =
                    line.startsWith("ERROR")
                            ? LogLine.Level.ERROR
                            : line.startsWith("WARNING")
                                    ? LogLine.Level.WARNING
                                    : LogLine.Level.INFO;
            Network.this.userLog.log(level, line);
            Network.this.publish.accept(new EngineEvent.ServerMessage(info(server), line));
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
            if (!Ipv4.isUnicastPublicCandidate(ip) || Network.this.ipFilter.blocks(ip)) {
                LOG.debug("Ignoring a callback request to {}", Ipv4.format(ip));
                return;
            }
            // The client may require obfuscation, which needs its user hash.
            boolean obfuscate =
                    request.userHash().isPresent()
                            && (request.cryptOptions() & CRYPT_REQUESTED_OR_REQUIRED) != 0
                            && Network.this.preferences.get().obfuscation()
                                    != ObfuscationMode.DISABLED;
            InetSocketAddress address =
                    new InetSocketAddress(Ipv4.format(ip), request.endpoint().port());
            Network.this
                    .greetings
                    .greet(address, obfuscate ? request.userHash() : Optional.empty())
                    .whenComplete(
                            (peer, failure) ->
                                    LOG.debug(
                                            "Callback to {}: {}",
                                            address,
                                            failure == null
                                                    ? peer.software()
                                                    : failure.getMessage()));
        }

        @Override
        public void foundSources(ServerMessages.FoundSources sources) {
            Network.this.downloads.foundSources(sources);
        }

        private static String describe(ServerInfo info) {
            return info.name().equals(info.address())
                    ? info.address()
                    : info.name() + " (" + info.address() + ")";
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
            boolean obfuscated =
                    (flags & ServerMessages.TCP_OBFUSCATION) != 0
                            && Network.this.preferences.get().obfuscation()
                                    != ObfuscationMode.DISABLED;
            return Network.this.session.send(ServerMessages.getSources(file, size, obfuscated));
        }

        @Override
        public long clientId() {
            return Network.this.context.clientId();
        }
    }

    private final class DownloadEvents implements DownloadManager.Listener {
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
            Network.this.userLog.log(
                    LogLine.Level.SUCCESS, "Finished downloading " + download.name());
            if (Network.this.shares != null) {
                Network.this.shares.finished(file, part);
            }
            Network.this.publisher.changed(Optional.of(download.hash()));
            Network.this.publish.accept(new EngineEvent.DownloadCompleted(download, file));
        }

        @Override
        public void failed(DownloadInfo download, String reason) {
            Network.this.userLog.log(
                    LogLine.Level.ERROR, "Download " + download.name() + " stopped: " + reason);
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
            LOG.debug("Shared {}", file.name());
            Network.this.publisher.changed(Optional.empty());
        }

        @Override
        public void unshared(SharedFiles.SharedFile file) {
            LOG.debug("No longer shared: {}", file.name());
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
