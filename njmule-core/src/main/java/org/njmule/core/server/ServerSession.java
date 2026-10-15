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
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.function.BooleanSupplier;
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
import org.njmule.core.protocol.ed2k.ServerDatagrams;
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
 * The connection to one eD2K server at a time: connect, log in, then serve the session. Looking for any server, two are
 * tried at once. When the preferences ask for obfuscation, the servers that take it, and once those never pinged, are
 * tried obfuscated first, then every server plainly unless obfuscation is required. When asked to keep connected, every
 * server is tried again {@link #RETRY_AFTER} after all failed, and a lost session is replaced. Used on the engine
 * thread only; network callbacks are posted to it, and those of an abandoned attempt are ignored.
 */
public final class ServerSession {

    /** What the session reports, on the engine thread. */
    public interface Listener {
        void connecting(ServerMet.Server server);

        void connected(ServerMet.Server server, IdChange id);

        /** The server we are logged in to gave us another id. */
        default void idChanged(ServerMet.Server server, IdChange id) {}

        /** {@code server} is the last server tried, if any. */
        void disconnected(Optional<ServerMet.Server> server, String reason);

        /** An attempt to log in to {@code server} failed; others may go on. */
        default void attemptFailed(ServerMet.Server server, String reason) {}

        /** No server could be logged in to: they are tried again after {@code wait}. */
        default void retrying(Duration wait) {}

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

    /** After an id of 0, which refuses the login, the wait for a real id before the next server is tried. */
    public static final Duration REFUSAL_GRACE = Duration.ofSeconds(3);

    /**
     * A session with nothing sent for this long gets an empty file offer, which changes nothing on the server but keeps
     * routers from dropping the idle connection.
     */
    public static final Duration KEEP_ALIVE = Duration.ofMinutes(15);

    /** Servers tried at once when any will do: a dead or silent one does not hold up the next. */
    public static final int ATTEMPTS_AT_ONCE = 2;

    /** Wait before trying every server again, and before a reconnect that follows another too soon. */
    public static final Duration RETRY_AFTER = Duration.ofSeconds(30);

    private static final Logger logger = LoggerFactory.getLogger(ServerSession.class);
    private static final Duration KEEP_ALIVE_CHECK = Duration.ofMinutes(1);
    private static final String VERSION_LINE = "server version";
    private static final Pattern VERSION_NUMBER = Pattern.compile("(\\d{1,5})\\.(\\d{1,5})");
    private static final CharMatcher CONTROL = CharMatcher.javaIsoControl();

    // One connection attempt, from the address lookup to the id; the session once logged in.
    private static final class Attempt {
        ServerMet.Server server;
        final boolean obfuscated;
        // Obfuscation tried on a server not known to take it: its failure is no failure of the server.
        final boolean probe;
        TcpConnection connection;
        ScheduledFuture<?> timeout;
        ScheduledFuture<?> keepingAlive;
        long lastSent;
        Optional<IdChange> id = Optional.empty();
        boolean refused;
        boolean over;

        Attempt(ServerMet.Server server, boolean obfuscated, boolean probe) {
            this.server = server;
            this.obfuscated = obfuscated;
            this.probe = probe;
        }
    }

    private final EngineLoop loop;
    private final ServerList servers;
    private final Supplier<Preferences> preferences;
    private final Hash16 userHash;
    private final IntSupplier listenPort;
    private final Listener listener;
    private final BooleanSupplier keepConnected;
    private final SecureRandom random;
    // The servers left to try in this pass.
    private final Deque<ServerMet.Server> pending = new ArrayDeque<>();
    private final List<Attempt> attempts = new ArrayList<>();
    // Servers not known to take obfuscation that were tried obfuscated once; not again until they say they take it.
    private final Set<ServerList.Address> probed = new HashSet<>();

    private Attempt session;
    // The server asked for, or empty for any.
    private Optional<ServerMet.Server> target = Optional.empty();
    private boolean looking;
    // A first pass asks for obfuscation where it may be taken; a second pass, unless it is required, goes plain.
    private boolean obfuscatedPass;
    private boolean attempted;
    private Optional<ServerMet.Server> last = Optional.empty();
    private String lastFailure = "";
    private long lastClientId;
    // A new round of attempts, once every server failed or after a reconnect that followed another too soon.
    private ScheduledFuture<?> retry;
    // A reconnect less than RETRY_AFTER ago: a server that takes us and drops us at once is not hammered.
    private boolean reconnectedLately;

    public ServerSession(
            EngineLoop loop,
            ServerList servers,
            Supplier<Preferences> preferences,
            Hash16 userHash,
            IntSupplier listenPort,
            Listener listener,
            BooleanSupplier keepConnected,
            SecureRandom random) {
        this.loop = Objects.requireNonNull(loop, "loop");
        this.servers = Objects.requireNonNull(servers, "servers");
        this.preferences = Objects.requireNonNull(preferences, "preferences");
        this.userHash = Objects.requireNonNull(userHash, "userHash");
        this.listenPort = Objects.requireNonNull(listenPort, "listenPort");
        this.listener = Objects.requireNonNull(listener, "listener");
        this.keepConnected = Objects.requireNonNull(keepConnected, "keepConnected");
        this.random = Objects.requireNonNull(random, "random");
    }

    /**
     * Connects to {@code target}, or to the known servers one after the other until one accepts us. An open session or
     * attempt is dropped first.
     */
    public void connect(Optional<ServerMet.Server> target) {
        stop("reconnecting");
        look(Objects.requireNonNull(target, "target"));
    }

    private void look(Optional<ServerMet.Server> target) {
        this.target = target;
        this.looking = true;
        this.attempted = false;
        this.lastFailure = "";
        this.obfuscatedPass = this.preferences.get().obfuscation().compareTo(ObfuscationMode.REQUESTED) >= 0;
        fillPass();
        next();
    }

    /** Ends the session or the attempt; no further server is tried. */
    public void disconnect() {
        stop("disconnected");
    }

    /** Whether the current session is obfuscated. */
    public boolean isObfuscated() {
        return this.session != null && this.session.connection.isObfuscated();
    }

    public boolean isConnected() {
        return this.session != null;
    }

    /** The id of the current session. */
    public Optional<IdChange> id() {
        return this.session == null ? Optional.empty() : this.session.id;
    }

    /** The server connected to or being tried. */
    public Optional<ServerMet.Server> server() {
        if (this.session != null) {
            return Optional.of(this.session.server);
        }
        return this.attempts.isEmpty() ? Optional.empty() : Optional.of(this.attempts.getFirst().server);
    }

    /** The addresses of the servers logged in to or being tried; 0 for a name not looked up yet is left out. */
    public Set<Integer> addresses() {
        Set<Integer> addresses = new HashSet<>();
        if (this.session != null) {
            addresses.add(this.session.server.ip());
        }
        for (Attempt attempt : this.attempts) {
            addresses.add(attempt.server.ip());
        }
        addresses.remove(0);
        return Set.copyOf(addresses);
    }

    /** Sends a packet to the connected server; {@code false} when there is no session. */
    public boolean send(Packet packet) {
        Attempt current = this.session;
        if (current == null || !current.connection.send(packet)) {
            return false;
        }
        current.lastSent = System.nanoTime();
        return true;
    }

    void keepAliveIfIdle(long now) {
        Attempt current = this.session;
        if (current != null && now - current.lastSent >= KEEP_ALIVE.toNanos()) {
            send(ServerMessages.keepAlive());
        }
    }

    private void fillPass() {
        this.pending.clear();
        if (this.target.isPresent()) {
            this.pending.add(this.target.get());
        } else {
            this.pending.addAll(candidates());
        }
    }

    // Starts attempts while there is room; once the pass is over, the plain pass or the end.
    private void next() {
        int room = this.target.isPresent() ? 1 : ATTEMPTS_AT_ONCE;
        while (this.attempts.size() < room && !this.pending.isEmpty()) {
            ServerMet.Server next = this.pending.poll();
            if (next.port() < 1 || next.port() > 0xFFFF) {
                logger.debug("Skipping {}: no such port", ServerList.addressOf(next));
                continue;
            }
            Optional<Boolean> obfuscated = obfuscationFor(next);
            if (obfuscated.isEmpty()) {
                logger.debug("Skipping {} in this pass: obfuscation", ServerList.addressOf(next));
                continue;
            }
            boolean probe = obfuscated.get() && !ServerList.supportsObfuscation(next);
            if (probe) {
                this.probed.add(ServerList.addressOf(next));
            }
            start(new Attempt(next, obfuscated.get(), probe));
        }
        if (!this.attempts.isEmpty() || !this.pending.isEmpty()) {
            return;
        }
        if (this.obfuscatedPass && this.preferences.get().obfuscation() != ObfuscationMode.REQUIRED) {
            this.obfuscatedPass = false;
            fillPass();
            next();
            return;
        }
        // Without a server tried, waiting would change nothing.
        if (this.target.isEmpty() && this.attempted && this.keepConnected.getAsBoolean()) {
            retryLater();
            return;
        }
        Optional<ServerMet.Server> tried = this.last;
        this.looking = false;
        this.last = Optional.empty();
        // A server asked for by name tells why it failed; for any, each failure was logged.
        this.listener.disconnected(
                tried,
                this.target.isPresent() && this.attempted ? this.lastFailure : "no server accepted the connection");
    }

    private void start(Attempt attempt) {
        ServerMet.Server server = attempt.server;
        this.attempts.add(attempt);
        this.attempted = true;
        this.last = Optional.of(server);
        attempt.timeout = this.loop.schedule(
                "server login timeout",
                LOGIN_TIMEOUT,
                () -> fail(attempt, "no answer within " + LOGIN_TIMEOUT.toSeconds() + " s"));
        Optional<String> host = server.dynamicIp();
        if (host.isEmpty()) {
            if (server.ip() >>> 24 == 0) {
                // Later, as every other outcome: the attempts are being started.
                this.loop.post(() -> fail(attempt, "no address to connect to"));
            } else {
                dial(attempt);
            }
            return;
        }
        // A host name is looked up for each connection, off the engine thread; the address may change.
        Thread.ofVirtual().name("njmule-server-resolve").start(() -> {
            try {
                int ip = resolve(host.get());
                this.loop.postOutcome(() -> resolved(attempt, ip));
            } catch (IOException e) {
                this.loop.postOutcome(() -> fail(attempt, describe(e)));
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

    private void resolved(Attempt attempt, int ip) {
        if (attempt.over) {
            return;
        }
        if (!this.servers.accepts(ip)) {
            fail(attempt, Ipv4.format(ip) + " is not an address to connect to");
            return;
        }
        ServerMet.Server named = attempt.server;
        this.servers.update(ServerList.addressOf(named), s -> new ServerMet.Server(ip, s.port(), s.tags()));
        attempt.server = new ServerMet.Server(ip, named.port(), named.tags());
        dial(attempt);
    }

    private void dial(Attempt attempt) {
        ServerMet.Server server = attempt.server;
        InetSocketAddress address = Ipv4.socketAddress(server.ip(), portFor(attempt));
        this.listener.connecting(server);
        Thread.ofVirtual().name("njmule-server-connect").start(() -> {
            try {
                TcpConnection opened =
                        TcpConnector.connectToServer(address, attempt.obfuscated, LOGIN_TIMEOUT, this.random);
                if (!this.loop.postOutcome(() -> opened(attempt, opened))) {
                    opened.close();
                }
            } catch (IOException e) {
                this.loop.postOutcome(() -> fail(attempt, describe(e)));
            }
        });
    }

    // A server not known to take obfuscation is tried on its main port, where servers take it too.
    private static int portFor(Attempt attempt) {
        return attempt.obfuscated && !attempt.probe
                ? (int) attempt.server.obfuscatedTcpPort().getAsLong()
                : attempt.server.port();
    }

    private void opened(Attempt attempt, TcpConnection opened) {
        if (attempt.over) {
            opened.close();
            return;
        }
        attempt.connection = opened;
        opened.start(
                new TcpConnection.Handler() {
                    @Override
                    public void onPacket(TcpConnection c, Packet packet) {
                        if (!ServerSession.this.loop.post(() -> ServerSession.this.received(attempt, packet))) {
                            c.close();
                        }
                    }

                    @Override
                    public void onClosed(TcpConnection c, Optional<IOException> cause) {
                        ServerSession.this.loop.postOutcome(() -> ServerSession.this.closed(
                                attempt, cause.map(ServerSession::describe).orElse("closed by the server")));
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
    }

    private void received(Attempt attempt, Packet packet) {
        if (attempt.over) {
            return;
        }
        ServerMet.Server target = attempt.server;
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
                case IDCHANGE -> idChanged(attempt, IdChange.decode(packet));
                case SERVERMESSAGE -> {
                    // Shown and logged: no terminal escapes, bells or other control characters.
                    for (String raw : ServerMessages.decodeMessage(packet)) {
                        String line = CONTROL.removeFrom(raw).strip();
                        if (line.isEmpty()) {
                            continue;
                        }
                        version(line).ifPresent(v -> update(attempt, stringTag(ServerTag.VERSION, v)));
                        this.listener.message(target, line);
                    }
                }
                case SERVERSTATUS ->
                    ServerMessages.ServerStatus.decode(packet)
                            .ifPresent(status -> update(
                                    attempt,
                                    intTag(ServerTag.USERS, status.users()),
                                    intTag(ServerTag.FILES, status.files())));
                case SERVERIDENT -> {
                    ServerMessages.ServerIdent ident = ServerMessages.ServerIdent.decode(packet);
                    update(
                            attempt,
                            ident.name()
                                    .map(n -> stringTag(ServerTag.SERVERNAME, CONTROL.removeFrom(n)))
                                    .orElse(null),
                            ident.description()
                                    .map(d -> stringTag(ServerTag.DESCRIPTION, CONTROL.removeFrom(d)))
                                    .orElse(null));
                }
                // What only the server we are logged in to may ask or answer.
                case CALLBACKREQUESTED -> {
                    if (attempt == this.session) {
                        this.listener.callbackRequested(ServerMessages.CallbackRequested.decode(packet));
                    }
                }
                case SEARCHRESULT -> {
                    if (attempt == this.session) {
                        this.listener.searchResults(target, searchResults(packet, target));
                    }
                }
                case FOUNDSOURCES, FOUNDSOURCES_OBFU -> {
                    if (attempt == this.session) {
                        this.listener.foundSources(ServerMessages.FoundSources.decode(packet));
                    }
                }
                case SERVERLIST -> {
                    if (attempt == this.session) {
                        this.listener.serverList(target, ServerMessages.decodeServerList(packet));
                    }
                }
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

    private void idChanged(Attempt attempt, IdChange change) {
        if (change.clientId() == 0) {
            logger.debug("Id 0 from {}", ServerList.addressOf(attempt.server));
            if (attempt != this.session && !attempt.refused) {
                // A refusal: the next server need not wait for the whole login timeout.
                attempt.refused = true;
                attempt.timeout.cancel(false);
                attempt.timeout = this.loop.schedule(
                        "server login refused", REFUSAL_GRACE, () -> fail(attempt, "the server answered with id 0"));
            }
            return;
        }
        boolean changed = attempt.id.map(IdChange::clientId).orElse(0L) != change.clientId();
        attempt.id = Optional.of(change);
        this.lastClientId = change.clientId();
        if (attempt != this.session) {
            loggedIn(attempt, change);
        } else if (changed) {
            this.listener.idChanged(attempt.server, change);
        }
    }

    private void loggedIn(Attempt attempt, IdChange id) {
        attempt.timeout.cancel(false);
        this.attempts.remove(attempt);
        for (Attempt other : this.attempts) {
            drop(other);
        }
        this.attempts.clear();
        this.pending.clear();
        this.looking = false;
        this.last = Optional.empty();
        this.session = attempt;
        attempt.lastSent = System.nanoTime();
        attempt.keepingAlive = this.loop.scheduleRepeating(
                "server keep-alive", KEEP_ALIVE_CHECK, KEEP_ALIVE_CHECK, () -> keepAliveIfIdle(System.nanoTime()));
        ServerList.Address address = ServerList.addressOf(attempt.server);
        boolean learnt = attempt.obfuscated && !ServerList.supportsObfuscation(attempt.server);
        int port = portFor(attempt);
        UnaryOperator<ServerMet.Server> change = s -> {
            ServerMet.Server logged = s.with(intTag(ServerTag.FAIL, 0));
            // It took the obfuscation it was not known to take: from now on it is tried so, on that port.
            return learnt
                    ? logged.with(intTag(
                                    ServerTag.UDPFLAGS, s.udpFlags().orElse(0) | ServerDatagrams.UDP_TCP_OBFUSCATION))
                            .with(intTag(ServerTag.TCPPORTOBFUSCATION, port))
                    : logged;
        };
        if (learnt) {
            this.probed.remove(address);
        }
        this.servers.update(address, change);
        ServerMet.Server server = attempt.server;
        attempt.server = this.servers.get(address).orElseGet(() -> change.apply(server));
        this.listener.connected(attempt.server, id);
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
    private void update(Attempt attempt, Tag... tags) {
        UnaryOperator<ServerMet.Server> change = s -> {
            ServerMet.Server updated = s;
            for (Tag tag : tags) {
                if (tag != null) {
                    updated = updated.with(tag);
                }
            }
            return updated;
        };
        ServerMet.Server server = attempt.server;
        ServerList.Address address = ServerList.addressOf(server);
        this.servers.update(address, change);
        ServerMet.Server current = this.servers.get(address).orElseGet(() -> change.apply(server));
        attempt.server = current;
        this.listener.updated(current);
    }

    private void fail(Attempt attempt, String reason) {
        if (attempt.over || attempt == this.session) {
            return;
        }
        ServerList.Address address = ServerList.addressOf(attempt.server);
        logger.info("Cannot connect to {}{}: {}", address, attempt.obfuscated ? " obfuscated" : "", reason);
        if (!attempt.probe) {
            this.servers.update(
                    address, s -> s.with(intTag(ServerTag.FAIL, s.failedCount().orElse(0) + 1)));
        }
        drop(attempt);
        this.attempts.remove(attempt);
        this.lastFailure = reason;
        this.listener.attemptFailed(attempt.server, reason);
        next();
    }

    private void closed(Attempt attempt, String reason) {
        if (attempt.over) {
            return;
        }
        if (attempt != this.session) {
            fail(attempt, reason);
            return;
        }
        drop(attempt);
        this.session = null;
        this.listener.disconnected(Optional.of(attempt.server), reason);
        if (this.session == null && !this.looking && this.keepConnected.getAsBoolean()) {
            reconnect();
        }
    }

    // Any server will do; at once, unless the last reconnect was too recent.
    private void reconnect() {
        if (this.reconnectedLately) {
            this.target = Optional.empty();
            this.looking = true;
            retryLater();
            return;
        }
        this.reconnectedLately = true;
        this.loop.schedule("server reconnect pause", RETRY_AFTER, () -> this.reconnectedLately = false);
        look(Optional.empty());
    }

    private void retryLater() {
        this.retry = this.loop.schedule("server retry", RETRY_AFTER, () -> {
            this.retry = null;
            if (this.looking && this.session == null && this.attempts.isEmpty()) {
                look(Optional.empty());
            }
        });
        this.listener.retrying(RETRY_AFTER);
    }

    // Drops the session and the attempts; tells it when there was one.
    private void stop(String reason) {
        Optional<ServerMet.Server> was = server().or(() -> this.last);
        boolean active = this.session != null || this.looking;
        if (this.retry != null) {
            this.retry.cancel(false);
            this.retry = null;
        }
        for (Attempt attempt : this.attempts) {
            drop(attempt);
        }
        this.attempts.clear();
        this.pending.clear();
        if (this.session != null) {
            drop(this.session);
            this.session = null;
        }
        this.looking = false;
        this.last = Optional.empty();
        if (active) {
            this.listener.disconnected(was, reason);
        }
    }

    private static void drop(Attempt attempt) {
        attempt.over = true;
        if (attempt.timeout != null) {
            attempt.timeout.cancel(false);
        }
        if (attempt.keepingAlive != null) {
            attempt.keepingAlive.cancel(false);
        }
        if (attempt.connection != null) {
            attempt.connection.close();
        }
    }

    /**
     * Whether to obfuscate with {@code server} in this pass; empty when it is left out of it. The obfuscated pass takes
     * the servers known to take obfuscation, and once those never pinged, whose support is unknown; the plain pass
     * takes every server, unless obfuscation is required.
     */
    private Optional<Boolean> obfuscationFor(ServerMet.Server server) {
        if (!this.obfuscatedPass) {
            return this.preferences.get().obfuscation() == ObfuscationMode.REQUIRED
                    ? Optional.empty()
                    : Optional.of(false);
        }
        boolean unknown = server.udpFlags().isEmpty() && !this.probed.contains(ServerList.addressOf(server));
        return ServerList.supportsObfuscation(server) || unknown ? Optional.of(true) : Optional.empty();
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
