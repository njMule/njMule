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

import java.io.IOException;
import java.net.InetSocketAddress;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ScheduledFuture;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
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
import org.njmule.core.protocol.tag.ServerTag;
import org.njmule.core.protocol.tag.Tag;
import org.njmule.core.protocol.tag.TagName;
import org.njmule.core.util.Ipv4;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The connection to one eD2K server at a time: connect (obfuscated when both sides support it), log
 * in, then serve the session. Used on the engine thread only; network callbacks are posted to it,
 * and those of an abandoned attempt are ignored.
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
    }

    /** From the start of an attempt to the id: connection, handshake and login. */
    public static final Duration LOGIN_TIMEOUT = Duration.ofSeconds(25);

    private static final Logger LOG = LoggerFactory.getLogger(ServerSession.class);

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
     * Connects to {@code target}, or to the known servers one after the other until one accepts us.
     * An open session or attempt is dropped first.
     */
    public void connect(Optional<ServerMet.Server> target) {
        this.disconnect("reconnecting");
        this.pending.clear();
        if (target.isPresent()) {
            this.pending.add(target.get());
        } else {
            this.pending.addAll(this.servers.candidates());
        }
        this.tryNext();
    }

    /** Ends the session or the attempt; no further server is tried. */
    public void disconnect() {
        this.pending.clear();
        this.disconnect("disconnected");
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
            if (this.obfuscationFor(next).isPresent()) {
                this.start(next);
                return;
            }
            LOG.debug("Skipping {}: it cannot obfuscate as required", ServerList.addressOf(next));
        }
        Optional<ServerMet.Server> last = this.server;
        this.state = State.IDLE;
        this.server = Optional.empty();
        this.listener.disconnected(last, "no server accepted the connection");
    }

    private void start(ServerMet.Server target) {
        long current = ++this.attempt;
        boolean obfuscate = this.obfuscationFor(target).orElse(false);
        int port =
                obfuscate && target.obfuscatedTcpPort().orElse(0) > 0
                        ? (int) target.obfuscatedTcpPort().getAsLong()
                        : target.port();
        InetSocketAddress address = new InetSocketAddress(Ipv4.format(target.ip()), port);
        this.state = State.CONNECTING;
        this.server = Optional.of(target);
        this.id = Optional.empty();
        this.timeout =
                this.loop.schedule(
                        "server login timeout",
                        LOGIN_TIMEOUT,
                        () ->
                                this.fail(
                                        current,
                                        "no answer within " + LOGIN_TIMEOUT.toSeconds() + " s"));
        this.listener.connecting(target);
        Thread.ofVirtual()
                .name("njmule-server-connect")
                .start(
                        () -> {
                            try {
                                TcpConnection opened =
                                        TcpConnector.connectToServer(
                                                address, obfuscate, LOGIN_TIMEOUT, this.random);
                                if (!this.loop.post(() -> this.opened(current, opened))) {
                                    opened.close();
                                }
                            } catch (IOException e) {
                                this.loop.post(() -> this.fail(current, describe(e)));
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
                        if (!ServerSession.this.loop.post(
                                () -> ServerSession.this.received(current, packet))) {
                            c.close();
                        }
                    }

                    @Override
                    public void onClosed(TcpConnection c, Optional<IOException> cause) {
                        ServerSession.this.loop.post(
                                () ->
                                        ServerSession.this.closed(
                                                current,
                                                cause.map(ServerSession::describe)
                                                        .orElse("closed by the server")));
                    }
                },
                Packing::unpackFromServer);
        Preferences prefs = this.preferences.get();
        opened.send(
                new ServerMessages.Login(
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
        Optional<Opcodes.Server> opcode = Opcodes.Server.of(packet.opcode());
        if (opcode.isEmpty()) {
            LOG.debug("Ignoring server opcode 0x{}", Integer.toHexString(packet.opcode()));
            return;
        }
        try {
            switch (opcode.get()) {
                case IDCHANGE -> this.idChanged(target, IdChange.decode(packet));
                case SERVERMESSAGE -> {
                    for (String line : ServerMessages.decodeMessage(packet)) {
                        this.listener.message(target, line);
                    }
                }
                case SERVERSTATUS ->
                        ServerMessages.ServerStatus.decode(packet)
                                .ifPresent(
                                        status ->
                                                this.update(
                                                        target,
                                                        intTag(ServerTag.USERS, status.users()),
                                                        intTag(ServerTag.FILES, status.files())));
                case SERVERIDENT -> {
                    ServerMessages.ServerIdent ident = ServerMessages.ServerIdent.decode(packet);
                    this.update(
                            target,
                            ident.name().map(n -> stringTag(ServerTag.SERVERNAME, n)).orElse(null),
                            ident.description()
                                    .map(d -> stringTag(ServerTag.DESCRIPTION, d))
                                    .orElse(null));
                }
                case CALLBACKREQUESTED ->
                        this.listener.callbackRequested(
                                ServerMessages.CallbackRequested.decode(packet));
                case SEARCHRESULT ->
                        this.listener.searchResults(
                                target, ServerMessages.SearchResults.decode(packet));
                case REJECT ->
                        LOG.info("{} rejected our last request", ServerList.addressOf(target));
                default -> LOG.debug("Ignoring server message {}", opcode.get());
            }
        } catch (DecodeException e) {
            LOG.warn(
                    "Malformed packet 0x{} from {}",
                    Integer.toHexString(packet.opcode()),
                    ServerList.addressOf(target),
                    e);
        }
    }

    private void idChanged(ServerMet.Server target, IdChange change) {
        if (change.clientId() == 0) {
            // The server refuses us without saying so; the login timeout moves on.
            LOG.debug("Id 0 from {}", ServerList.addressOf(target));
            return;
        }
        this.id = Optional.of(change);
        this.lastClientId = change.clientId();
        if (this.state == State.LOGGING_IN) {
            this.state = State.CONNECTED;
            this.timeout.cancel(false);
            this.pending.clear();
            this.servers.update(
                    ServerList.addressOf(target), s -> s.with(intTag(ServerTag.FAIL, 0)));
            this.listener.connected(target, change);
        }
    }

    private void update(ServerMet.Server target, Tag... tags) {
        ServerList.Address address = ServerList.addressOf(target);
        this.servers.update(
                address,
                s -> {
                    ServerMet.Server updated = s;
                    for (Tag tag : tags) {
                        if (tag != null) {
                            updated = updated.with(tag);
                        }
                    }
                    return updated;
                });
        ServerMet.Server current = this.servers.get(address).orElse(target);
        this.server = Optional.of(current);
        this.listener.updated(current);
    }

    private void fail(long current, String reason) {
        if (current != this.attempt || this.state == State.IDLE || this.state == State.CONNECTED) {
            return;
        }
        ServerMet.Server target = this.server.orElseThrow();
        LOG.info("Cannot connect to {}: {}", ServerList.addressOf(target), reason);
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
     * Whether to obfuscate with {@code target}: empty when the preferences require obfuscation and
     * the server cannot do it.
     */
    private Optional<Boolean> obfuscationFor(ServerMet.Server target) {
        ObfuscationMode mode = this.preferences.get().obfuscation();
        long flags = target.udpFlags().orElse(0);
        boolean serverCan =
                target.obfuscatedTcpPort().orElse(0) > 0
                        && (flags
                                        & (ServerDatagrams.UDP_OBFUSCATION
                                                | ServerDatagrams.UDP_TCP_OBFUSCATION))
                                != 0;
        if (mode == ObfuscationMode.REQUIRED) {
            return serverCan ? Optional.of(true) : Optional.empty();
        }
        return Optional.of(serverCan && mode == ObfuscationMode.REQUESTED);
    }

    static int capabilities(ObfuscationMode mode) {
        int caps =
                ServerMessages.CAP_ZLIB
                        | ServerMessages.CAP_NEWTAGS
                        | ServerMessages.CAP_UNICODE
                        | ServerMessages.CAP_LARGEFILES;
        return switch (mode) {
            case DISABLED -> caps;
            case SUPPORTED -> caps | ServerMessages.CAP_SUPPORTS_CRYPT;
            case REQUESTED ->
                    caps | ServerMessages.CAP_SUPPORTS_CRYPT | ServerMessages.CAP_REQUESTS_CRYPT;
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
