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

import com.google.common.base.Ticker;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.IntPredicate;
import java.util.function.Supplier;
import org.njmule.core.api.Hash16;
import org.njmule.core.engine.EngineLoop;
import org.njmule.core.kad.KadNode;
import org.njmule.core.kad.UInt128;
import org.njmule.core.net.PeerChannel;
import org.njmule.core.net.TcpConnection;
import org.njmule.core.net.TcpConnector;
import org.njmule.core.protocol.ed2k.Hello;
import org.njmule.core.protocol.ed2k.LowIdMessages;
import org.njmule.core.protocol.io.DecodeException;
import org.njmule.core.protocol.packet.Opcodes;
import org.njmule.core.protocol.packet.Packet;
import org.njmule.core.protocol.packet.Packing;
import org.njmule.core.protocol.packet.Protocol;
import org.njmule.core.util.Ipv4;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The TCP link between Kad buddies. Firewalled, we connect to the nodes that offered to be our buddy, one at a time,
 * keep the first link that stands and ping it now and then. Open, we serve one firewalled node that asked us: we take
 * its connection and relay to it the callback requests of other clients. Engine thread only.
 */
public final class BuddyLink implements KadNode.Buddies {

    /** The firewalled side pings this often; the buddy answers a ping this long after its last answer at most. */
    static final Duration PING_EVERY = Duration.ofMinutes(10);

    static final Duration PONG_GAP = Duration.ofMinutes(13);

    /**
     * A buddy link waits this long for a packet: pings come every ten minutes, and a buddy that answers every other one
     * sends a pong every twenty.
     */
    static final Duration LINK_TIMEOUT = Duration.ofMinutes(25);

    /** A firewalled node that asked us waits this long for its connection to be taken. */
    static final Duration EXPECTED_FOR = Duration.ofMinutes(10);

    // Offers and requests kept at once: the first link to stand wins, the rest are dropped.
    private static final int MAX_OFFERS = 10;
    private static final int MAX_EXPECTED = 4;

    private static final Logger logger = LoggerFactory.getLogger(BuddyLink.class);

    /** A node that offered to be our buddy. */
    private static final class Offer {
        private final int ip;
        private final int tcpPort;
        private final int udpPort;
        private final Hash16 userHash;
        private final int connectOptions;

        Offer(int ip, int tcpPort, int udpPort, Hash16 userHash, int connectOptions) {
            this.ip = ip;
            this.tcpPort = tcpPort;
            this.udpPort = udpPort;
            this.userHash = userHash;
            this.connectOptions = connectOptions;
        }

        int ip() {
            return this.ip;
        }

        int tcpPort() {
            return this.tcpPort;
        }

        int udpPort() {
            return this.udpPort;
        }

        Hash16 userHash() {
            return this.userHash;
        }

        int connectOptions() {
            return this.connectOptions;
        }
    }

    /** A firewalled node that asked us to be its buddy and will connect. */
    private record Expected(int ip, Hash16 userHash, UInt128 buddyId, long since) {}

    /** Where our buddy takes callback requests. */
    private record Address(int ip, int udpPort) {}

    private static final Address NO_BUDDY = new Address(0, 0);

    private final EngineLoop loop;
    private final PeerLinks links;
    private final Supplier<Hello> ourHello;
    private final IntPredicate obfuscate;
    private final Consumer<LowIdMessages.Callback> callbacks;
    private final SecureRandom random;
    private final Ticker ticker;
    private final Deque<Offer> offers = new ArrayDeque<>();
    private final List<Expected> expected = new ArrayList<>();
    // Our buddy's address while we have one, read by connection threads too for our hello.
    private final AtomicReference<Address> buddy = new AtomicReference<>(NO_BUDDY);
    private State state = State.NONE;
    private Offer connecting;
    private PeerChannel channel;
    private Role role;
    // The id the node we serve was found by; callback requests must name it.
    private UInt128 servedId;
    // When our buddy is pinged next, and when the node we serve may get its next pong.
    private final AtomicLong nextPing = new AtomicLong();
    private final AtomicLong nextPong = new AtomicLong();

    /**
     * @param obfuscate whether a connection to a buddy announcing these connect options is obfuscated
     * @param callbacks the callback requests our buddy relays to us
     */
    public BuddyLink(
            EngineLoop loop,
            PeerLinks links,
            Supplier<Hello> ourHello,
            IntPredicate obfuscate,
            Consumer<LowIdMessages.Callback> callbacks,
            SecureRandom random,
            Ticker ticker) {
        this.loop = Objects.requireNonNull(loop, "loop");
        this.links = Objects.requireNonNull(links, "links");
        this.ourHello = Objects.requireNonNull(ourHello, "ourHello");
        this.obfuscate = Objects.requireNonNull(obfuscate, "obfuscate");
        this.callbacks = Objects.requireNonNull(callbacks, "callbacks");
        this.random = Objects.requireNonNull(random, "random");
        this.ticker = Objects.requireNonNull(ticker, "ticker");
    }

    @Override
    public State state() {
        return this.state;
    }

    /** Any thread. */
    @Override
    public int buddyIp() {
        return this.buddy.get().ip();
    }

    /** Any thread. */
    @Override
    public int buddyUdpPort() {
        return this.buddy.get().udpPort();
    }

    /** The address of the firewalled node we serve, while we serve one. */
    public Optional<InetSocketAddress> served() {
        return this.state == State.SERVING && this.channel != null
                ? Optional.of(this.channel.remoteAddress())
                : Optional.empty();
    }

    @Override
    public void offered(int ip, int tcpPort, int udpPort, Hash16 userHash, int connectOptions) {
        if (this.state == State.CONNECTED || this.state == State.SERVING || this.offers.size() >= MAX_OFFERS) {
            return;
        }
        for (Offer o : this.offers) {
            if (o.ip() == ip) {
                return;
            }
        }
        this.offers.addLast(new Offer(ip, tcpPort, udpPort, userHash, connectOptions));
        this.connectNext();
    }

    @Override
    public boolean asked(int ip, int tcpPort, int udpPort, Hash16 userHash, UInt128 buddyId) {
        long now = this.ticker.read();
        this.expected.removeIf(e -> now - e.since() >= EXPECTED_FOR.toNanos());
        InetSocketAddress address = new InetSocketAddress(Ipv4.format(ip), tcpPort);
        // A client we deal with already would mix up both uses of the link.
        if (this.state != State.NONE
                || this.expected.size() >= MAX_EXPECTED
                || this.links.find(address).isPresent()
                || this.expected.stream().anyMatch(e -> e.ip() == ip)) {
            return false;
        }
        this.expected.add(new Expected(ip, userHash, buddyId, now));
        return true;
    }

    /** Takes a connection a client opened when it is the firewalled node we agreed to serve. */
    public boolean adopt(PeerLink link) {
        Hello peer = link.peer().orElseThrow();
        if (this.state != State.NONE || !(link.remoteAddress().getAddress() instanceof Inet4Address from)) {
            return false;
        }
        int at = Ipv4.fromBytes(from.getAddress());
        for (Expected e : this.expected) {
            if (e.ip() == at && e.userHash().equals(peer.userHash())) {
                this.expected.clear();
                this.offers.clear();
                this.attach(link);
                this.state = State.SERVING;
                this.servedId = e.buddyId();
                // The first ping is answered once the node has waited as long as between two pongs.
                this.nextPong.set(e.since() + PONG_GAP.toNanos());
                logger.info("Kad: we are now the buddy of {}", link.remoteAddress());
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean relay(UInt128 buddyId, UInt128 file, int requesterIp, int requesterPort) {
        if (this.state != State.SERVING || !buddyId.equals(this.servedId)) {
            return false;
        }
        return this.channel.send(new LowIdMessages.Callback(buddyId, file, requesterIp, requesterPort).encode());
    }

    @Override
    public void release() {
        this.offers.clear();
        this.expected.clear();
        this.connecting = null;
        PeerChannel open = this.channel;
        this.drop();
        if (open != null) {
            logger.info("Kad: buddy link to {} released", open.remoteAddress());
            open.close();
        }
    }

    /** Runs every second: pings our buddy, forgets stale requests, and connects to the next offer. */
    public void tick() {
        long now = this.ticker.read();
        this.expected.removeIf(e -> now - e.since() >= EXPECTED_FOR.toNanos());
        if (this.state == State.CONNECTED && now - this.nextPing.get() >= 0) {
            this.nextPing.set(now + PING_EVERY.toNanos());
            this.channel.send(LowIdMessages.buddyPing());
        }
        this.connectNext();
    }

    private void connectNext() {
        if (this.state != State.NONE || this.connecting != null || this.offers.isEmpty()) {
            return;
        }
        Offer offer = this.offers.removeFirst();
        this.connecting = offer;
        this.state = State.CONNECTING;
        InetSocketAddress address = new InetSocketAddress(Ipv4.format(offer.ip()), offer.tcpPort());
        Optional<Hash16> obfuscateFor =
                this.obfuscate.test(offer.connectOptions()) ? Optional.of(offer.userHash()) : Optional.empty();
        dial(this.loop, address, obfuscateFor, this.random, c -> this.opened(offer, c), why -> this.failed(offer, why));
    }

    // Connects off the engine thread; the outcome comes back on it.
    private static void dial(
            EngineLoop loop,
            InetSocketAddress address,
            Optional<Hash16> obfuscateFor,
            SecureRandom random,
            Consumer<TcpConnection> opened,
            Consumer<String> failed) {
        Thread.ofVirtual().name("njmule-buddy-" + address).start(() -> {
            try {
                TcpConnection connection =
                        TcpConnector.connectToClient(address, obfuscateFor, PeerGreetings.HELLO_TIMEOUT, random);
                if (!loop.postOutcome(() -> opened.accept(connection))) {
                    connection.close();
                }
            } catch (IOException e) {
                loop.postOutcome(() -> failed.accept(e.getMessage()));
            }
        });
    }

    private void opened(Offer offer, TcpConnection connection) {
        if (this.connecting != offer) {
            connection.close();
            return;
        }
        PeerLink link = this.links.outgoing(connection, new PeerLinks.Opener() {
            @Override
            public void greeted(PeerLink greeted) {
                BuddyLink.this.greeted(offer, greeted);
            }

            @Override
            public void failed(String reason) {
                BuddyLink.this.failed(offer, reason);
            }
        });
        connection.start(link, frame -> Packing.unpack(frame, Packing.MAX_CLIENT_UNPACKED));
        connection.send(this.ourHello.get().encode(false));
    }

    // Only the node that offered, by its user hash, becomes our buddy.
    private void greeted(Offer offer, PeerLink link) {
        Hello peer = link.peer().orElseThrow();
        if (this.connecting != offer || !peer.userHash().equals(offer.userHash())) {
            link.disconnect();
            if (this.connecting == offer) {
                this.failed(offer, "another client answered");
            }
            return;
        }
        this.connecting = null;
        this.offers.clear();
        this.attach(link);
        this.buddy.set(new Address(offer.ip(), peer.udpPort() != 0 ? peer.udpPort() : offer.udpPort()));
        this.state = State.CONNECTED;
        this.nextPing.set(this.ticker.read() + PING_EVERY.toNanos());
        logger.info("Kad: {} is our buddy", link.remoteAddress());
    }

    private void failed(Offer offer, String reason) {
        if (this.connecting != offer) {
            return;
        }
        logger.debug("Kad: no buddy link to {}: {}", Ipv4.format(offer.ip()), reason);
        this.connecting = null;
        this.state = State.NONE;
        this.connectNext();
    }

    private void attach(PeerLink link) {
        Role role = new Role();
        this.channel = link.attach(PeerLink.Side.BUDDY, role);
        this.role = role;
        role.channel = this.channel;
        try {
            this.channel.setReadTimeout(LINK_TIMEOUT);
        } catch (SocketException e) {
            link.disconnect();
        }
    }

    private void drop() {
        this.channel = null;
        this.role = null;
        this.state = State.NONE;
        this.buddy.set(NO_BUDDY);
        this.servedId = null;
    }

    /** The buddy side of the link. */
    private final class Role implements PeerLink.Role {
        private PeerChannel channel;

        @Override
        public void received(Packet packet) {
            BuddyLink buddies = BuddyLink.this;
            if (buddies.role != this || packet.protocol() != Protocol.EMULE) {
                return;
            }
            long now = buddies.ticker.read();
            Optional<Opcodes.Extended> opcode = Opcodes.Extended.of(packet.opcode());
            if (opcode.isEmpty()) {
                return;
            }
            switch (opcode.get()) {
                case BUDDYPING -> {
                    if (buddies.state == State.SERVING && now - buddies.nextPong.get() >= 0) {
                        buddies.nextPong.set(now + PONG_GAP.toNanos());
                        this.channel.send(LowIdMessages.buddyPong());
                    }
                }
                case BUDDYPONG -> {
                    if (buddies.state == State.CONNECTED) {
                        buddies.nextPing.set(now + PING_EVERY.toNanos());
                    }
                }
                case CALLBACK -> {
                    if (buddies.state == State.CONNECTED) {
                        this.callback(packet);
                    }
                }
                default -> {
                    // Re-asks through the buddy need UDP re-asks, which we do not use.
                }
            }
        }

        private void callback(Packet packet) {
            try {
                BuddyLink.this.callbacks.accept(LowIdMessages.Callback.decode(packet));
            } catch (DecodeException e) {
                logger.debug("Bad callback from our buddy: {}", e.getMessage());
            }
        }

        @Override
        public void closed(Optional<String> cause) {
            if (BuddyLink.this.role == this) {
                logger.info("Kad: buddy link closed{}", cause.map(c -> ": " + c).orElse(""));
                BuddyLink.this.drop();
            }
        }
    }
}
