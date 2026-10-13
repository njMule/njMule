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

import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import org.njmule.core.engine.EngineLoop;
import org.njmule.core.net.TcpConnection;
import org.njmule.core.protocol.ed2k.Hello;

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

    private volatile Consumer<Hello> greeted = hello -> {};

    public PeerLinks(EngineLoop loop, Identities identities, Uploads uploads) {
        this.loop = Objects.requireNonNull(loop, "loop");
        this.identities = Objects.requireNonNull(identities, "identities");
        this.uploads = Objects.requireNonNull(uploads, "uploads");
    }

    /** A link over a connection of ours, to start before the hellos; {@code opener} hears how they go. */
    public PeerLink outgoing(TcpConnection connection, Opener opener) {
        return new PeerLink(this.loop, connection, this, opener);
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

    /** Hears every hello of a client we are linked with, on the thread that read it. */
    public void onGreeted(Consumer<Hello> observer) {
        this.greeted = Objects.requireNonNull(observer, "observer");
    }

    void register(PeerLink link) {
        link.peerAddress().ifPresent(address -> this.open.put(address, link));
        link.peer().ifPresent(this.greeted);
    }

    void unregister(PeerLink link) {
        link.peerAddress().ifPresent(address -> this.open.remove(address, link));
    }
}
