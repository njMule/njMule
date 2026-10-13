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
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.njmule.core.api.DownloadInfo;
import org.njmule.core.api.Ed2kLink;
import org.njmule.core.api.EngineEvent;
import org.njmule.core.api.Hash16;
import org.njmule.core.api.LogLine;
import org.njmule.core.api.PeerInfo;
import org.njmule.core.api.SearchId;
import org.njmule.core.api.SearchRequest;
import org.njmule.core.api.SearchResult;
import org.njmule.core.api.ServerInfo;
import org.njmule.core.config.ObfuscationMode;
import org.njmule.core.config.Preferences;
import org.njmule.core.download.DownloadManager;
import org.njmule.core.net.TcpListener;
import org.njmule.core.peer.PeerGreetings;
import org.njmule.core.persist.AtomicFiles;
import org.njmule.core.persist.format.IpFilterDat;
import org.njmule.core.persist.format.ServerMet;
import org.njmule.core.protocol.ed2k.Hello;
import org.njmule.core.protocol.ed2k.ServerDatagrams;
import org.njmule.core.protocol.ed2k.ServerMessages;
import org.njmule.core.protocol.io.DecodeException;
import org.njmule.core.protocol.packet.Opcodes;
import org.njmule.core.protocol.packet.Packet;
import org.njmule.core.search.SearchManager;
import org.njmule.core.security.ConnectionGuard;
import org.njmule.core.security.IpFilter;
import org.njmule.core.server.ServerList;
import org.njmule.core.server.ServerPinger;
import org.njmule.core.server.ServerSession;
import org.njmule.core.server.ServerUdp;
import org.njmule.core.util.Ipv4;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The network side of the engine: the listening socket, the server session, server UDP queries,
 * searches, the hello exchange with peers and the downloads. Engine thread only, except the methods
 * marked otherwise.
 */
final class Network {

    static final String IPFILTER_FILE = "ipfilter.dat";

    // Server updates are saved together, at most this long after the first one.
    private static final Duration SAVE_DELAY = Duration.ofSeconds(30);

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
                        new DownloadEvents());
        this.greetings.routeTo(this.downloads.router());
    }

    /** Opens the listening socket and the server UDP socket; failures leave us reachable less. */
    void start() {
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
        LOG.info(
                "{} servers known, {} IP filter ranges, user hash {}",
                this.servers.size(),
                this.ipFilter.rangeCount(),
                this.userHash);
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

    /** Any thread: says hello to a client. */
    CompletableFuture<PeerInfo> hello(InetSocketAddress peer) {
        return this.greetings.greet(peer);
    }

    void close() {
        this.downloads.close();
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
        return Hello.of(
                this.userHash,
                server.clientId(),
                this.listenPort(),
                prefs.nick(),
                0,
                0,
                Hello.MISC1_UNICODE
                        | Hello.MISC1_DATA_COMPRESSION
                        | Hello.MISC1_NO_VIEW_SHARED_FILES,
                Hello.MISC2_LARGE_FILES | cryptOptions(prefs.obfuscation()),
                server.serverIp(),
                server.serverPort());
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
                server.files().orElse(0));
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
        }

        @Override
        public void disconnected(Optional<ServerMet.Server> server, String reason) {
            Network.this.context = ServerContext.NONE;
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
        public void completed(DownloadInfo download, Path file) {
            Network.this.userLog.log(
                    LogLine.Level.SUCCESS, "Finished downloading " + download.name());
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
