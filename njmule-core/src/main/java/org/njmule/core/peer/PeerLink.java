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
package org.njmule.core.peer;

import java.io.IOException;
import java.net.Inet6Address;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.njmule.core.engine.EngineLoop;
import org.njmule.core.net.PeerChannel;
import org.njmule.core.net.TcpConnection;
import org.njmule.core.protocol.ed2k.ClientMessages;
import org.njmule.core.protocol.ed2k.Hello;
import org.njmule.core.protocol.io.DecodeException;
import org.njmule.core.protocol.packet.FrameLimits;
import org.njmule.core.protocol.packet.Opcodes;
import org.njmule.core.protocol.packet.Packet;
import org.njmule.core.protocol.packet.Protocol;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A connection to a client, shared by our download from it and our upload to it, as eD2K clients use one connection for
 * both: each packet goes to the side it belongs to, the upload side starts when the client first asks for something of
 * ours, and the connection closes once neither side needs it. Engine thread, except the handler callbacks.
 */
public final class PeerLink implements TcpConnection.Handler {

    /** Our download from the client, our upload to it, or the Kad buddy link with it. */
    public enum Side {
        DOWNLOAD,
        UPLOAD,
        BUDDY
    }

    /** One side of the link, on the engine thread. */
    public interface Role {
        void received(Packet packet);

        /** The connection closed; {@code cause} is empty after a clean close. */
        void closed(Optional<String> cause);

        /** The client's identity is settled: proven or failed. */
        default void identitySettled() {}
    }

    private static final Logger logger = LoggerFactory.getLogger(PeerLink.class);

    // What uploaders send to downloaders; anything else is for the upload side.
    private static final Set<Opcodes.Client> FROM_UPLOADERS = Set.of(
            Opcodes.Client.SENDINGPART,
            Opcodes.Client.FILEREQANSNOFIL,
            Opcodes.Client.FILESTATUS,
            Opcodes.Client.HASHSETANSWER,
            Opcodes.Client.ACCEPTUPLOADREQ,
            Opcodes.Client.OUTOFPARTREQS,
            Opcodes.Client.REQFILENAMEANSWER,
            Opcodes.Client.QUEUERANK,
            Opcodes.Client.ASKSHAREDFILESANSWER,
            Opcodes.Client.ASKSHAREDDIRSANS,
            Opcodes.Client.ASKSHAREDFILESDIRANS,
            Opcodes.Client.ASKSHAREDDENIEDANS);
    private static final Set<Opcodes.Extended> FROM_UPLOADERS_EXTENDED = Set.of(
            Opcodes.Extended.COMPRESSEDPART,
            Opcodes.Extended.COMPRESSEDPART_I64,
            Opcodes.Extended.SENDINGPART_I64,
            Opcodes.Extended.QUEUERANKING,
            Opcodes.Extended.ANSWERSOURCES,
            Opcodes.Extended.ANSWERSOURCES2,
            Opcodes.Extended.PREVIEWANSWER,
            Opcodes.Extended.MULTIPACKETANSWER,
            Opcodes.Extended.MULTIPACKETANSWER_EXT2,
            Opcodes.Extended.PUBLICIP_ANSWER,
            Opcodes.Extended.AICHANSWER,
            Opcodes.Extended.AICHFILEHASHANS,
            Opcodes.Extended.FILEDESC,
            Opcodes.Extended.HASHSETANSWER2);
    // What buddies send each other; taken only on a link that is a buddy link.
    private static final Set<Opcodes.Extended> BETWEEN_BUDDIES = Set.of(
            Opcodes.Extended.BUDDYPING,
            Opcodes.Extended.BUDDYPONG,
            Opcodes.Extended.CALLBACK,
            Opcodes.Extended.REASKCALLBACKTCP);

    private final EngineLoop loop;
    private final TcpConnection connection;
    private final PeerLinks links;
    private final PeerLinks.Opener opener;
    private final Map<Side, Channel> sides = new EnumMap<>(Side.class);
    private final Map<Side, Duration> timeouts = new EnumMap<>(Side.class);
    private Hello peer;
    private Identities.Session identity;
    private boolean closed;

    PeerLink(EngineLoop loop, TcpConnection connection, PeerLinks links, PeerLinks.Opener opener) {
        this.loop = Objects.requireNonNull(loop, "loop");
        this.connection = Objects.requireNonNull(connection, "connection");
        this.links = Objects.requireNonNull(links, "links");
        this.opener = Objects.requireNonNull(opener, "opener");
    }

    /** The hello of the client; empty on a connection of ours until it answers. */
    public Optional<Hello> peer() {
        return Optional.ofNullable(this.peer);
    }

    /** The secure identification of the client, once it greeted us. */
    public Optional<Identities.Session> identity() {
        return Optional.ofNullable(this.identity);
    }

    public InetSocketAddress remoteAddress() {
        return this.connection.remoteAddress();
    }

    /** Where the client listens: its address and the TCP port of its hello. */
    public Optional<InetSocketAddress> peerAddress() {
        return peer().map(p -> new InetSocketAddress(remoteAddress().getAddress(), p.tcpPort()));
    }

    public boolean isOpen() {
        return !this.closed && this.connection.isOpen();
    }

    public boolean has(Side side) {
        return this.sides.containsKey(side);
    }

    /** Takes {@code side} for {@code role}; the channel it gets serves until it closes it. */
    public PeerChannel attach(Side side, Role role) {
        Objects.requireNonNull(role, "role");
        if (this.sides.containsKey(side)) {
            throw new IllegalStateException(side + " is taken");
        }
        Channel channel = new Channel(side, role);
        this.sides.put(side, channel);
        return channel;
    }

    /** Sends {@code packet} outside the sides, as a message about the connection itself. */
    public boolean send(Packet packet) {
        return isOpen() && this.connection.send(packet);
    }

    /** Closes the connection for both sides. */
    public void disconnect() {
        this.connection.close();
    }

    // The hellos are over: the identification starts and the other side may join.
    void greet(Hello hello, Identities identities) {
        this.peer = hello;
        this.identity = identities.start(
                hello, remoteAddress().getAddress(), this.connection.localAddress(), this.connection::send);
        this.links.register(this);
    }

    @Override
    public void onPacket(TcpConnection c, Packet packet) {
        // The next frame is read right after this returns: lift the handshake limit here.
        if (packet.protocol() == Protocol.EDONKEY && packet.opcode() == Opcodes.Client.HELLOANSWER.code()) {
            c.setFrameLimit(FrameLimits.CLIENT);
        }
        if (!this.loop.post(() -> received(packet))) {
            c.close();
        }
    }

    @Override
    public void onClosed(TcpConnection c, Optional<IOException> cause) {
        this.loop.postOutcome(() -> closed(cause.map(IOException::getMessage)));
    }

    private void received(Packet packet) {
        if (this.closed) {
            return;
        }
        try {
            if (this.peer == null) {
                greeting(packet);
                return;
            }
            if (packet.protocol() == Protocol.EDONKEY && packet.opcode() == Opcodes.Client.CHANGE_CLIENT_IP.code()) {
                moved(packet);
                return;
            }
            boolean settled = this.identity.isSettled();
            if (this.identity.received(packet)) {
                if (!settled && this.identity.isSettled()) {
                    for (Channel channel : List.copyOf(this.sides.values())) {
                        channel.role.identitySettled();
                    }
                }
                return;
            }
        } catch (DecodeException e) {
            logger.debug("Bad packet from {}: {}", remoteAddress(), e.getMessage());
            this.connection.close();
            return;
        }
        Side side = sideOf(packet);
        Channel channel = this.sides.get(side);
        Role role = channel == null ? null : channel.role;
        if (role == null && side == Side.UPLOAD) {
            role = this.links.uploads().attach(this).orElse(null);
        }
        if (role == null) {
            logger.debug("Nothing on {} takes opcode {}", remoteAddress(), packet.opcode());
            return;
        }
        role.received(packet);
    }

    // A client tells its new IPv6 address: taken only from one that proved its identity, so that nobody can send our
    // connections for another client elsewhere.
    private void moved(Packet packet) throws DecodeException {
        Optional<Inet6Address> address = ClientMessages.changedIpv6(packet);
        if (address.isPresent() && this.identity.trust() == Identities.Trust.IDENTIFIED) {
            this.links.moved(this.peer.userHash(), address.get());
        }
    }

    // On a connection of ours the hello answer comes first; nothing else counts before it.
    private void greeting(Packet packet) throws DecodeException {
        if (packet.protocol() != Protocol.EDONKEY || packet.opcode() != Opcodes.Client.HELLOANSWER.code()) {
            return;
        }
        greet(Hello.decode(packet), this.links.identities());
        this.opener.greeted(this);
    }

    private void closed(Optional<String> cause) {
        if (this.closed) {
            return;
        }
        this.closed = true;
        this.links.unregister(this);
        if (this.peer == null) {
            this.opener.failed(cause.orElse("closed before the hello"));
        }
        List<Channel> gone = List.copyOf(this.sides.values());
        this.sides.clear();
        for (Channel channel : gone) {
            channel.role.closed(cause);
        }
    }

    private void release(Channel channel) {
        if (!channel.isCurrent()) {
            return;
        }
        this.sides.remove(channel.side);
        this.timeouts.remove(channel.side);
        if (this.sides.isEmpty()) {
            this.connection.close();
        } else {
            applyTimeout();
        }
    }

    // A side that waits longer than the other keeps the connection open for both.
    private void applyTimeout() {
        Duration longest = Duration.ZERO;
        for (Duration d : this.timeouts.values()) {
            if (d.compareTo(longest) > 0) {
                longest = d;
            }
        }
        if (longest.isZero()) {
            return;
        }
        try {
            this.connection.setReadTimeout(longest);
        } catch (SocketException e) {
            this.connection.close();
        }
    }

    static Side sideOf(Packet packet) {
        if (packet.protocol() == Protocol.EMULE
                && Opcodes.Extended.of(packet.opcode())
                        .filter(BETWEEN_BUDDIES::contains)
                        .isPresent()) {
            return Side.BUDDY;
        }
        boolean fromUploader = switch (packet.protocol()) {
            case Protocol.EDONKEY ->
                Opcodes.Client.of(packet.opcode())
                        .filter(FROM_UPLOADERS::contains)
                        .isPresent();
            case Protocol.EMULE ->
                Opcodes.Extended.of(packet.opcode())
                        .filter(FROM_UPLOADERS_EXTENDED::contains)
                        .isPresent();
            default -> false;
        };
        return fromUploader ? Side.DOWNLOAD : Side.UPLOAD;
    }

    /** The connection as one side sees it. */
    private final class Channel implements PeerChannel {
        private final Side side;
        private final Role role;

        Channel(Side side, Role role) {
            this.side = side;
            this.role = role;
        }

        private boolean isCurrent() {
            return PeerLink.this.sides.get(this.side) == this;
        }

        @Override
        public boolean send(Packet packet) {
            return isCurrent() && PeerLink.this.connection.send(packet);
        }

        @Override
        public boolean sendData(Packet packet) {
            return isCurrent() && PeerLink.this.connection.sendData(packet);
        }

        @Override
        public long queuedData() {
            return PeerLink.this.connection.queuedData();
        }

        @Override
        public InetSocketAddress remoteAddress() {
            return PeerLink.this.remoteAddress();
        }

        @Override
        public boolean isOpen() {
            return isCurrent() && PeerLink.this.isOpen();
        }

        @Override
        public void setReadTimeout(Duration timeout) {
            if (isCurrent()) {
                PeerLink.this.timeouts.put(this.side, timeout);
                PeerLink.this.applyTimeout();
            }
        }

        @Override
        public void close() {
            PeerLink.this.release(this);
        }

        @Override
        public void disconnect() {
            if (isCurrent()) {
                PeerLink.this.connection.close();
            }
        }
    }
}
