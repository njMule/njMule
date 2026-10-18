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

import java.net.Inet6Address;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import org.njmule.core.api.Hash16;
import org.njmule.core.engine.EngineLoop;
import org.njmule.core.net.TcpConnection;
import org.njmule.core.protocol.ed2k.Hello;
import org.njmule.core.security.ConnectionGuard;

/** Makes the links to clients and keeps the open ones by where the clients listen. Engine thread only. */
public final class PeerLinks {

    /** Starts our upload side on a link when the client asks for something of ours; empty to refuse. */
    @FunctionalInterface
    public interface Uploads {
        Optional<PeerLink.Role> attach(PeerLink link);
    }

    /** The one who opened a connection of ours, told how the hellos went. */
    public interface Opener {
        void greeted(PeerLink link);

        /** The connection closed before the client answered our hello. */
        void failed(String reason);
    }

    private static final Opener NONE = new Opener() {
        @Override
        public void greeted(PeerLink link) {
            // A link the client opened is greeted from the start.
        }

        @Override
        public void failed(String reason) {
            // Nobody waits for it.
        }
    };

    private final EngineLoop loop;
    private final Identities identities;
    private final Uploads uploads;
    private final Map<InetSocketAddress, PeerLink> open = new HashMap<>();
    private final Map<PeerLink, ConnectionGuard.Permit> ours = new HashMap<>();

    private volatile Consumer<PeerLink> greeted = link -> {};
    private volatile BiConsumer<Hash16, Inet6Address> moved = (hash, address) -> {};
    private volatile ConnectionGuard guard;

    public PeerLinks(EngineLoop loop, Identities identities, Uploads uploads) {
        this.loop = Objects.requireNonNull(loop, "loop");
        this.identities = Objects.requireNonNull(identities, "identities");
        this.uploads = Objects.requireNonNull(uploads, "uploads");
    }

    /** Our connections count in the total of {@code guard}, which the incoming ones share. */
    public void countIn(ConnectionGuard guard) {
        this.guard = Objects.requireNonNull(guard, "guard");
    }

    /** Whether a new connection of ours fits in what the preferences allow. */
    public boolean mayConnect() {
        ConnectionGuard g = this.guard;
        return g == null || g.hasRoom();
    }

    /** A link over a connection of ours, to start before the hellos; {@code opener} hears how they go. */
    public PeerLink outgoing(TcpConnection connection, Opener opener) {
        PeerLink link = new PeerLink(this.loop, connection, this, opener);
        ConnectionGuard g = this.guard;
        if (g != null) {
            this.ours.put(link, g.outgoing());
        }
        return link;
    }

    /** A link over a connection the client opened, whose hello we answered. */
    public PeerLink incoming(TcpConnection connection, Hello peer) {
        PeerLink link = new PeerLink(this.loop, connection, this, NONE);
        link.greet(peer, this.identities);
        return link;
    }

    /** The open link to the client listening at {@code address}, if any. */
    public Optional<PeerLink> find(InetSocketAddress address) {
        return Optional.ofNullable(this.open.get(address)).filter(PeerLink::isOpen);
    }

    Identities identities() {
        return this.identities;
    }

    Uploads uploads() {
        return this.uploads;
    }

    /** Hears every link whose client said hello, on the thread that read it. */
    public void onGreeted(Consumer<PeerLink> observer) {
        this.greeted = Objects.requireNonNull(observer, "observer");
    }

    /** Hears the clients, by user hash, that tell their new IPv6 address; on the engine thread. */
    public void onMoved(BiConsumer<Hash16, Inet6Address> observer) {
        this.moved = Objects.requireNonNull(observer, "observer");
    }

    /** The links open now. */
    public List<PeerLink> open() {
        return this.open.values().stream().filter(PeerLink::isOpen).distinct().toList();
    }

    void moved(Hash16 userHash, Inet6Address address) {
        this.moved.accept(userHash, address);
    }

    void register(PeerLink link) {
        link.peerAddress().ifPresent(address -> this.open.put(address, link));
        if (link.peer().isPresent()) {
            this.greeted.accept(link);
        }
    }

    // The link closed.
    void unregister(PeerLink link) {
        link.peerAddress().ifPresent(address -> this.open.remove(address, link));
        ConnectionGuard.Permit permit = this.ours.remove(link);
        if (permit != null) {
            permit.close();
        }
    }
}
