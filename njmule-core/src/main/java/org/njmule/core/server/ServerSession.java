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
package org.njmule.core.server;

import com.google.common.base.CharMatcher;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ScheduledFuture;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.njmule.core.api.Hash16;
import org.njmule.core.config.ObfuscationMode;
import org.njmule.core.config.Preferences;
import org.njmule.core.engine.EngineLoop;
import org.njmule.core.net.TcpConnection;
import org.njmule.core.net.TcpConnector;
import org.njmule.core.persist.format.ServerMet;
import org.njmule.core.protocol.ed2k.ServerMessages;
import org.njmule.core.protocol.ed2k.ServerMessages.IdChange;
import org.njmule.core.protocol.io.DecodeException;
import org.njmule.core.protocol.packet.Opcodes;
import org.njmule.core.protocol.packet.Packet;
import org.njmule.core.protocol.packet.Packing;
import org.njmule.core.protocol.packet.Protocol;
import org.njmule.core.protocol.tag.ServerTag;
import org.njmule.core.protocol.tag.Tag;
import org.njmule.core.protocol.tag.TagName;
import org.njmule.core.util.Ipv4;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The connection to one eD2K server at a time: connect (obfuscated when both sides support it), log in, then serve the
 * session. Used on the engine thread only; network callbacks are posted to it, and those of an abandoned attempt are
 * ignored.
 */
public final class ServerSession {

    /** What the session reports, on the engine thread. */
    public interface Listener {
        void connecting(ServerMet.Server server);

        void connected(ServerMet.Server server, IdChange id);

        /** {@code server} is the last server tried, if any. */
        void disconnected(Optional<ServerMet.Server> server, String reason);

        void message(ServerMet.Server server, String line);

        void updated(ServerMet.Server server);

        void searchResults(ServerMet.Server server, ServerMessages.SearchResults results);

        /** A firewalled client asks, through the server, that we connect to it. */
        void callbackRequested(ServerMessages.CallbackRequested request);

        /** The server's answer to a request for the sources of a file. */
        void foundSources(ServerMessages.FoundSources sources);

        /** The servers the server knows, sent when asked. */
        void serverList(ServerMet.Server server, List<ServerMessages.Endpoint> servers);
    }

    /** From the start of an attempt to the id: connection, handshake and login. */
    public static final Duration LOGIN_TIMEOUT = Duration.ofSeconds(25);

    private static final Logger logger = LoggerFactory.getLogger(ServerSession.class);
    private static final String VERSION_LINE = "server version";
    private static final Pattern VERSION_NUMBER = Pattern.compile("(\\d{1,5})\\.(\\d{1,5})");
    private static final CharMatcher CONTROL = CharMatcher.javaIsoControl();

    private enum State {
        IDLE,
        CONNECTING,
        LOGGING_IN,
        CONNECTED
    }

    private final EngineLoop loop;
    private final ServerList servers;
    private final Supplier<Preferences> preferences;
    private final Hash16 userHash;
    private final IntSupplier listenPort;
    private final Listener listener;
    private final SecureRandom random;
    private final Deque<ServerMet.Server> pending = new ArrayDeque<>();

    private State state = State.IDLE;
    private long attempt;
    private Optional<ServerMet.Server> server = Optional.empty();
    private TcpConnection connection;
    private ScheduledFuture<?> timeout;
    private Optional<IdChange> id = Optional.empty();
    private long lastClientId;

    public ServerSession(
            EngineLoop loop,
            ServerList servers,
            Supplier<Preferences> preferences,
            Hash16 userHash,
            IntSupplier listenPort,
            Listener listener,
            SecureRandom random) {
        this.loop = Objects.requireNonNull(loop, "loop");
        this.servers = Objects.requireNonNull(servers, "servers");
        this.preferences = Objects.requireNonNull(preferences, "preferences");
        this.userHash = Objects.requireNonNull(userHash, "userHash");
        this.listenPort = Objects.requireNonNull(listenPort, "listenPort");
        this.listener = Objects.requireNonNull(listener, "listener");
        this.random = Objects.requireNonNull(random, "random");
    }

    /**
     * Connects to {@code target}, or to the known servers one after the other until one accepts us. An open session or
     * attempt is dropped first.
     */
    public void connect(Optional<ServerMet.Server> target) {
        this.disconnect("reconnecting");
        this.pending.clear();
        if (target.isPresent()) {
            this.pending.add(target.get());
        } else {
            this.pending.addAll(this.candidates());
        }
        this.tryNext();
    }

    /** Ends the session or the attempt; no further server is tried. */
    public void disconnect() {
        this.pending.clear();
        this.disconnect("disconnected");
    }

    /** Whether the current session is obfuscated. */
    public boolean isObfuscated() {
        return this.isConnected() && this.connection.isObfuscated();
    }

    public boolean isConnected() {
        return this.state == State.CONNECTED;
    }

    /** The id of the current session. */
    public Optional<IdChange> id() {
        return this.isConnected() ? this.id : Optional.empty();
    }

    /** The server connected to or being tried. */
    public Optional<ServerMet.Server> server() {
        return this.server;
    }

    /** Sends a packet to the connected server; {@code false} when there is no session. */
    public boolean send(Packet packet) {
        return this.isConnected() && this.connection.send(packet);
    }

    private void tryNext() {
        while (!this.pending.isEmpty()) {
            ServerMet.Server next = this.pending.poll();
            if (next.port() < 1 || next.port() > 0xFFFF) {
                logger.debug("Skipping {}: no such port", ServerList.addressOf(next));
            } else if (this.obfuscationFor(next).isEmpty()) {
                logger.debug("Skipping {}: it cannot obfuscate as required", ServerList.addressOf(next));
            } else {
                this.start(next);
                return;
            }
        }
        Optional<ServerMet.Server> last = this.server;
        this.state = State.IDLE;
        this.server = Optional.empty();
        this.listener.disconnected(last, "no server accepted the connection");
    }

    private void start(ServerMet.Server target) {
        long current = ++this.attempt;
        this.state = State.CONNECTING;
        this.server = Optional.of(target);
        this.id = Optional.empty();
        this.timeout = this.loop.schedule(
                "server login timeout",
                LOGIN_TIMEOUT,
                () -> this.fail(current, "no answer within " + LOGIN_TIMEOUT.toSeconds() + " s"));
        Optional<String> host = target.dynamicIp();
        if (host.isEmpty()) {
            if (target.ip() >>> 24 == 0) {
                this.fail(current, "no address to connect to");
            } else {
                this.dial(current, target);
            }
            return;
        }
        // A host name is looked up for each connection, off the engine thread; the address may change.
        Thread.ofVirtual().name("njmule-server-resolve").start(() -> {
            try {
                int ip = resolve(host.get());
                this.loop.postOutcome(() -> this.resolved(current, ip));
            } catch (IOException e) {
                this.loop.postOutcome(() -> this.fail(current, describe(e)));
            }
        });
    }

    private static int resolve(String host) throws UnknownHostException {
        for (InetAddress address : InetAddress.getAllByName(host)) {
            if (address instanceof Inet4Address v4) {
                return Ipv4.fromBytes(v4.getAddress());
            }
        }
        throw new UnknownHostException("no IPv4 address for " + host);
    }

    private void resolved(long current, int ip) {
        if (current != this.attempt || this.state != State.CONNECTING || this.server.isEmpty()) {
            return;
        }
        if (!this.servers.accepts(ip)) {
            this.fail(current, Ipv4.format(ip) + " is not an address to connect to");
            return;
        }
        ServerMet.Server target = this.server.get();
        this.servers.update(ServerList.addressOf(target), s -> new ServerMet.Server(ip, s.port(), s.tags()));
        ServerMet.Server at = new ServerMet.Server(ip, target.port(), target.tags());
        this.server = Optional.of(at);
        this.dial(current, at);
    }

    private void dial(long current, ServerMet.Server target) {
        boolean obfuscate = this.obfuscationFor(target).orElse(false);
        int port = obfuscate ? (int) target.obfuscatedTcpPort().getAsLong() : target.port();
        InetSocketAddress address = new InetSocketAddress(Ipv4.format(target.ip()), port);
        this.listener.connecting(target);
        Thread.ofVirtual().name("njmule-server-connect").start(() -> {
            try {
                TcpConnection opened = TcpConnector.connectToServer(address, obfuscate, LOGIN_TIMEOUT, this.random);
                if (!this.loop.postOutcome(() -> this.opened(current, opened))) {
                    opened.close();
                }
            } catch (IOException e) {
                this.loop.postOutcome(() -> this.fail(current, describe(e)));
            }
        });
    }

    private void opened(long current, TcpConnection opened) {
        if (current != this.attempt || this.state != State.CONNECTING) {
            opened.close();
            return;
        }
        this.connection = opened;
        opened.start(
                new TcpConnection.Handler() {
                    @Override
                    public void onPacket(TcpConnection c, Packet packet) {
                        if (!ServerSession.this.loop.post(() -> ServerSession.this.received(current, packet))) {
                            c.close();
                        }
                    }

                    @Override
                    public void onClosed(TcpConnection c, Optional<IOException> cause) {
                        ServerSession.this.loop.postOutcome(() -> ServerSession.this.closed(
                                current, cause.map(ServerSession::describe).orElse("closed by the server")));
                    }
                },
                ServerSession::unpack);
        Preferences prefs = this.preferences.get();
        opened.send(new ServerMessages.Login(
                        this.userHash,
                        this.lastClientId,
                        this.listenPort.getAsInt(),
                        prefs.nick(),
                        capabilities(prefs.obfuscation()))
                .encode());
        this.state = State.LOGGING_IN;
    }

    private void received(long current, Packet packet) {
        if (current != this.attempt || this.server.isEmpty()) {
            return;
        }
        ServerMet.Server target = this.server.get();
        if (packet.protocol() != Protocol.EDONKEY) {
            logger.debug(
                    "Dropping a packet of protocol 0x{} from {}",
                    Integer.toHexString(packet.protocol()),
                    ServerList.addressOf(target));
            return;
        }
        Optional<Opcodes.Server> opcode = Opcodes.Server.of(packet.opcode());
        if (opcode.isEmpty()) {
            logger.debug("Ignoring server opcode 0x{}", Integer.toHexString(packet.opcode()));
            return;
        }
        try {
            switch (opcode.get()) {
                case IDCHANGE -> this.idChanged(target, IdChange.decode(packet));
                case SERVERMESSAGE -> {
                    // Shown and logged: no terminal escapes, bells or other control characters.
                    for (String raw : ServerMessages.decodeMessage(packet)) {
                        String line = CONTROL.removeFrom(raw).strip();
                        if (line.isEmpty()) {
                            continue;
                        }
                        version(line).ifPresent(v -> this.update(target, stringTag(ServerTag.VERSION, v)));
                        this.listener.message(target, line);
                    }
                }
                case SERVERSTATUS ->
                    ServerMessages.ServerStatus.decode(packet)
                            .ifPresent(status -> this.update(
                                    target,
                                    intTag(ServerTag.USERS, status.users()),
                                    intTag(ServerTag.FILES, status.files())));
                case SERVERIDENT -> {
                    ServerMessages.ServerIdent ident = ServerMessages.ServerIdent.decode(packet);
                    this.update(
                            target,
                            ident.name()
                                    .map(n -> stringTag(ServerTag.SERVERNAME, CONTROL.removeFrom(n)))
                                    .orElse(null),
                            ident.description()
                                    .map(d -> stringTag(ServerTag.DESCRIPTION, CONTROL.removeFrom(d)))
                                    .orElse(null));
                }
                case CALLBACKREQUESTED ->
                    this.listener.callbackRequested(ServerMessages.CallbackRequested.decode(packet));
                case SEARCHRESULT -> this.listener.searchResults(target, searchResults(packet, target));
                case FOUNDSOURCES, FOUNDSOURCES_OBFU ->
                    this.listener.foundSources(ServerMessages.FoundSources.decode(packet));
                case SERVERLIST -> this.listener.serverList(target, ServerMessages.decodeServerList(packet));
                case REJECT -> logger.info("{} rejected our last request", ServerList.addressOf(target));
                default -> logger.debug("Ignoring server message {}", opcode.get());
            }
        } catch (DecodeException e) {
            logger.warn(
                    "Malformed packet 0x{} from {}",
                    Integer.toHexString(packet.opcode()),
                    ServerList.addressOf(target),
                    e);
        }
    }

    // A packet that does not inflate is one bad packet, not a broken connection: it stays packed and is dropped.
    private static Packet unpack(Packet frame) {
        try {
            return Packing.unpackFromServer(frame);
        } catch (DecodeException e) {
            logger.debug("Cannot inflate a server packet: {}", e.getMessage());
            return frame;
        }
    }

    // Answers carry no search id, so one that cannot be read still answers the oldest search, as an empty one.
    private static ServerMessages.SearchResults searchResults(Packet packet, ServerMet.Server from) {
        try {
            return ServerMessages.SearchResults.decode(packet);
        } catch (DecodeException e) {
            logger.warn("Malformed search answer from {}: {}", ServerList.addressOf(from), e.getMessage());
            return new ServerMessages.SearchResults(List.of(), false);
        }
    }

    private void idChanged(ServerMet.Server target, IdChange change) {
        if (change.clientId() == 0) {
            // The server refuses us without saying so; the login timeout moves on.
            logger.debug("Id 0 from {}", ServerList.addressOf(target));
            return;
        }
        this.id = Optional.of(change);
        this.lastClientId = change.clientId();
        if (this.state == State.LOGGING_IN) {
            this.state = State.CONNECTED;
            this.timeout.cancel(false);
            this.pending.clear();
            this.servers.update(ServerList.addressOf(target), s -> s.with(intTag(ServerTag.FAIL, 0)));
            this.listener.connected(target, change);
        }
    }

    // Only the static servers when the preferences say so.
    private List<ServerMet.Server> candidates() {
        List<ServerMet.Server> all = this.servers.connectable();
        if (!this.preferences.get().staticServersOnly()) {
            return all;
        }
        return all.stream()
                .filter(s -> this.servers.isStatic(ServerList.addressOf(s)))
                .toList();
    }

    // Servers name their software in a message line: "server version 17.15 (lugdunum)".
    static Optional<String> version(String line) {
        if (!line.regionMatches(true, 0, VERSION_LINE, 0, VERSION_LINE.length())) {
            return Optional.empty();
        }
        String version = line.substring(VERSION_LINE.length()).strip();
        Matcher number = VERSION_NUMBER.matcher(version);
        if (number.matches()) {
            version = ServerMet.Server.version(Long.parseLong(number.group(1)), Long.parseLong(number.group(2)));
        }
        return version.isEmpty() ? Optional.empty() : Optional.of(version.substring(0, Math.min(64, version.length())));
    }

    // A server not in the list (one given by hand that the list refuses) still shows what it said.
    private void update(ServerMet.Server target, Tag... tags) {
        UnaryOperator<ServerMet.Server> change = s -> {
            ServerMet.Server updated = s;
            for (Tag tag : tags) {
                if (tag != null) {
                    updated = updated.with(tag);
                }
            }
            return updated;
        };
        ServerList.Address address = ServerList.addressOf(target);
        this.servers.update(address, change);
        ServerMet.Server current = this.servers.get(address).orElseGet(() -> change.apply(target));
        this.server = Optional.of(current);
        this.listener.updated(current);
    }

    private void fail(long current, String reason) {
        if (current != this.attempt || this.state == State.IDLE || this.state == State.CONNECTED) {
            return;
        }
        ServerMet.Server target = this.server.orElseThrow();
        logger.info("Cannot connect to {}: {}", ServerList.addressOf(target), reason);
        this.servers.update(
                ServerList.addressOf(target),
                s -> s.with(intTag(ServerTag.FAIL, s.failedCount().orElse(0) + 1)));
        this.drop();
        if (this.pending.isEmpty()) {
            this.state = State.IDLE;
            this.server = Optional.empty();
            this.listener.disconnected(Optional.of(target), reason);
        } else {
            this.tryNext();
        }
    }

    private void closed(long current, String reason) {
        if (current != this.attempt || this.state == State.IDLE) {
            return;
        }
        if (this.state == State.CONNECTED) {
            ServerMet.Server target = this.server.orElseThrow();
            this.drop();
            this.state = State.IDLE;
            this.server = Optional.empty();
            this.listener.disconnected(Optional.of(target), reason);
        } else {
            this.fail(current, reason);
        }
    }

    private void disconnect(String reason) {
        if (this.state == State.IDLE) {
            return;
        }
        Optional<ServerMet.Server> last = this.server;
        this.attempt++;
        this.drop();
        this.state = State.IDLE;
        this.server = Optional.empty();
        this.listener.disconnected(last, reason);
    }

    private void drop() {
        if (this.timeout != null) {
            this.timeout.cancel(false);
        }
        if (this.connection != null) {
            this.connection.close();
            this.connection = null;
        }
        this.id = Optional.empty();
    }

    /**
     * Whether to obfuscate with {@code target}: empty when the preferences require obfuscation and the server cannot do
     * it.
     */
    private Optional<Boolean> obfuscationFor(ServerMet.Server target) {
        ObfuscationMode mode = this.preferences.get().obfuscation();
        boolean serverCan = ServerList.supportsObfuscation(target);
        if (mode == ObfuscationMode.REQUIRED) {
            return serverCan ? Optional.of(true) : Optional.empty();
        }
        return Optional.of(serverCan && mode == ObfuscationMode.REQUESTED);
    }

    static int capabilities(ObfuscationMode mode) {
        int caps = ServerMessages.CAP_ZLIB
                | ServerMessages.CAP_NEWTAGS
                | ServerMessages.CAP_UNICODE
                | ServerMessages.CAP_LARGEFILES;
        return switch (mode) {
            case DISABLED -> caps;
            case SUPPORTED -> caps | ServerMessages.CAP_SUPPORTS_CRYPT;
            case REQUESTED -> caps | ServerMessages.CAP_SUPPORTS_CRYPT | ServerMessages.CAP_REQUESTS_CRYPT;
            case REQUIRED ->
                caps
                        | ServerMessages.CAP_SUPPORTS_CRYPT
                        | ServerMessages.CAP_REQUESTS_CRYPT
                        | ServerMessages.CAP_REQUIRES_CRYPT;
        };
    }

    private static Tag intTag(int id, long value) {
        return new Tag.IntTag(TagName.id(id), value);
    }

    private static Tag intTag(String name, long value) {
        return new Tag.IntTag(TagName.of(name), value);
    }

    private static Tag stringTag(int id, String value) {
        return new Tag.StringTag(TagName.id(id), value);
    }

    private static String describe(IOException e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }
}
