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
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import org.njmule.core.api.Hash16;
import org.njmule.core.net.StreamTransport;
import org.njmule.core.protocol.ed2k.NatTraversalMessages;
import org.njmule.core.protocol.ed2k.NatTraversalMessages.Caps;
import org.njmule.core.protocol.ed2k.NatTraversalMessages.Rendezvous;
import org.njmule.core.protocol.io.DecodeException;
import org.njmule.core.protocol.packet.Packet;
import org.njmule.core.protocol.packet.Protocol;
import org.njmule.core.quic.QuicLink;
import org.njmule.core.quic.QuicTransport;
import org.njmule.core.util.Ipv4;
import org.njmule.core.utp.UtpSocket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Connections between firewalled clients through their routers, over uTP or QUIC on the client UDP port. As requester
 * we ask the target's Kad buddy for a rendezvous, punch holes towards the target and open the connection once its holes
 * come, over QUIC when the target has it; as target we hear the rendezvous from our buddy, punch holes back and take
 * the connection from the address the requester was seen at, or from near it. A client whose UDP port is reachable
 * takes connections from anyone too. Thread-safe.
 */
public final class NatTraversal implements AutoCloseable {

    /** How plain datagrams leave the client UDP port. */
    @FunctionalInterface
    public interface Datagrams {
        void send(InetSocketAddress to, Packet packet);
    }

    /** Where a connection through the routers goes, and whether this side speaks first. */
    @FunctionalInterface
    public interface Connections {
        void opened(StreamTransport connection, boolean helloFirst);
    }

    /** How long a rendezvous partner may take to open the connection. */
    static final Duration EXPECTED_FOR = Duration.ofSeconds(30);

    /** How long a requester waits for the connection. */
    static final Duration ATTEMPT = Duration.ofSeconds(25);

    /** How long a host QUIC failed with is met over uTP instead. */
    static final Duration QUIC_COOLDOWN = Duration.ofMinutes(2);

    // The ports around a punched one that a router may have chosen instead.
    private static final int SWEEP = 16;
    private static final int PORT_WINDOW = 512;
    private static final int TARGET_PUNCHES = 12;
    private static final int REQUESTER_PUNCHES = 6;
    // A requester opens the connection once the target's holes came, or after this.
    private static final Duration CONNECT_AFTER = Duration.ofMillis(500);

    private static final Logger logger = LoggerFactory.getLogger(NatTraversal.class);

    // A host whose connection we wait for, near a port; with the requester's future and transport, when we asked for
    // it.
    private record Expected(
            InetSocketAddress at,
            long until,
            Hash16 peer,
            Optional<CompletableFuture<StreamTransport>> waiting,
            boolean overQuic) {}

    private final Datagrams datagrams;
    private final Connections connections;
    private final Ticker ticker;
    private final Supplier<Hash16> ourHash;
    private final IntSupplier ourOptions;
    private final BooleanSupplier reachable;
    private final UtpSocket utp;
    private final QuicLink quic;
    private final Cache<InetSocketAddress, Boolean> quicFailed;
    private final List<Expected> expected = new CopyOnWriteArrayList<>();
    // One connection at a time is opened.
    private final Object connecting = new Object();

    /**
     * @param ourOptions our connect options, which a capability answer tells
     * @param reachable whether our UDP port takes datagrams from anyone, so that connections from hosts not expected
     *     are taken too
     */
    public NatTraversal(
            Datagrams datagrams,
            Connections connections,
            Ticker ticker,
            SecureRandom random,
            Supplier<Hash16> ourHash,
            IntSupplier ourOptions,
            BooleanSupplier reachable) {
        this.datagrams = Objects.requireNonNull(datagrams, "datagrams");
        this.connections = Objects.requireNonNull(connections, "connections");
        this.ticker = Objects.requireNonNull(ticker, "ticker");
        this.ourHash = Objects.requireNonNull(ourHash, "ourHash");
        this.ourOptions = Objects.requireNonNull(ourOptions, "ourOptions");
        this.reachable = Objects.requireNonNull(reachable, "reachable");
        this.utp = new UtpSocket(
                (to, packet) -> this.datagrams.send(to, NatTraversalMessages.utpFrame(packet)), ticker, random);
        this.utp.accept(this::mayConnect, this::accepted);
        this.quic = new QuicLink(
                (to, datagram) -> this.datagrams.send(to, NatTraversalMessages.quicFrame(datagram)),
                ourHash,
                new QuicPeers(),
                random);
        this.quicFailed = CacheBuilder.newBuilder()
                .ticker(ticker)
                .expireAfterWrite(QUIC_COOLDOWN)
                .build();
    }

    /** A frame of protocol 0xB2 from {@code from}. */
    public void frame(InetSocketAddress from, Packet packet) {
        if (packet.protocol() != Protocol.UDP_RESERVED_2) {
            return;
        }
        switch (packet.opcode()) {
            case NatTraversalMessages.FRAME_UTP -> {
                byte[] utp = packet.payload();
                this.utp.received(from, utp, 0, utp.length);
            }
            case NatTraversalMessages.FRAME_QUIC -> this.quic.received(from, packet.payload());
            case NatTraversalMessages.FRAME_CAPS -> answerCaps(from, packet);
            default -> {
                // Capability answers and keys: nothing we need.
            }
        }
    }

    /** A hole punch from {@code from}: a requester connects there now if it waited for the target's holes. */
    public void holePunched(InetSocketAddress from) {
        for (Expected e : live()) {
            if (e.waiting().isPresent()
                    && e.at().getAddress().equals(from.getAddress())
                    && !e.waiting().get().isDone()) {
                connectTo(from, e);
            }
        }
    }

    /**
     * As target: our buddy passed on a rendezvous from the requester it saw at {@code observed}. Its connection is
     * awaited from there or from the address it gave, and holes are punched towards both.
     */
    public void rendezvousForwarded(InetSocketAddress observed, Rendezvous rendezvous) {
        if ((rendezvous.options() & (NatTraversalMessages.OPTION_UTP | NatTraversalMessages.OPTION_QUIC)) == 0) {
            logger.debug("A rendezvous from {} without uTP or QUIC", observed);
            return;
        }
        List<InetSocketAddress> ats = new ArrayList<>();
        if (!observed.getAddress().isAnyLocalAddress() && observed.getPort() != 0) {
            ats.add(observed);
        }
        if (rendezvous.requesterIp() != 0 && rendezvous.requesterPort() != 0) {
            InetSocketAddress told = Ipv4.socketAddress(rendezvous.requesterIp(), rendezvous.requesterPort());
            if (!ats.contains(told)) {
                ats.add(told);
            }
        }
        long until = this.ticker.read() + EXPECTED_FOR.toNanos();
        for (InetSocketAddress at : ats) {
            this.expected.add(new Expected(at, until, rendezvous.requester(), Optional.empty(), false));
            punch(at, TARGET_PUNCHES);
        }
        logger.debug("Rendezvous with {} at {}", rendezvous.requester(), ats);
    }

    /**
     * As requester: asks {@code target}'s buddy at {@code buddy} for a rendezvous about {@code file}, telling our
     * public address when we know it ({@code ourIp} in {@code Ipv4} form, 0 otherwise); the connection is QUIC when the
     * target {@code hasQuic} and QUIC did not fail with it lately, else uTP. The future completes with the connection,
     * or fails when none opens in time.
     */
    public CompletableFuture<StreamTransport> rendezvous(
            InetSocketAddress target,
            Hash16 targetHash,
            InetSocketAddress buddy,
            Hash16 buddyId,
            Hash16 file,
            int ourIp,
            int ourPort,
            boolean hasQuic) {
        CompletableFuture<StreamTransport> result = new CompletableFuture<>();
        boolean overQuic = hasQuic && this.quicFailed.getIfPresent(target) == null;
        Expected awaited = new Expected(
                target, this.ticker.read() + EXPECTED_FOR.toNanos(), targetHash, Optional.of(result), overQuic);
        this.expected.add(awaited);
        // Our router lets the target's holes and connection in once ours went out towards it.
        punch(target, REQUESTER_PUNCHES);
        Rendezvous asked = new Rendezvous(
                this.ourHash.get(),
                ourTraversalOptions(),
                file,
                ourIp,
                ourPort,
                overQuic ? NatTraversalMessages.TRANSPORT_QUIC : NatTraversalMessages.TRANSPORT_UTP);
        this.datagrams.send(buddy, asked.encodeThroughBuddy(buddyId));
        this.datagrams.send(target, NatTraversalMessages.holePunch());
        CompletableFuture.delayedExecutor(CONNECT_AFTER.toMillis(), TimeUnit.MILLISECONDS)
                .execute(() -> connectTo(target, awaited));
        return result.orTimeout(ATTEMPT.toSeconds(), TimeUnit.SECONDS).whenComplete((c, failure) -> forget(result));
    }

    // Connects once over the transport chosen; a connection the target opened first wins. The connections' own
    // futures are handled here.
    @SuppressWarnings("FutureReturnValueIgnored")
    private void connectTo(InetSocketAddress at, Expected awaited) {
        CompletableFuture<StreamTransport> result = awaited.waiting().orElseThrow();
        synchronized (this.connecting) {
            if (result.isDone()) {
                return;
            }
            if (awaited.overQuic()) {
                if (!this.quic.connecting(at)) {
                    this.quic.connect(at, Optional.of(awaited.peer())).whenComplete((connection, failure) -> {
                        if (connection == null) {
                            logger.debug("No QUIC connection with {}: {}", at, String.valueOf(failure));
                            this.quicFailed.put(awaited.at(), Boolean.TRUE);
                        } else {
                            opened(connection, result);
                        }
                    });
                }
            } else if (this.utp.connections().stream()
                    .noneMatch(c -> c.remoteAddress().equals(at))) {
                this.utp.connect(at).whenComplete((connection, failure) -> {
                    if (connection != null) {
                        opened(connection, result);
                    }
                });
            }
        }
    }

    private void opened(StreamTransport connection, CompletableFuture<StreamTransport> result) {
        if (result.complete(connection)) {
            this.connections.opened(connection, false);
        } else {
            connection.close();
        }
    }

    private int ourTraversalOptions() {
        return this.ourOptions.getAsInt() | NatTraversalMessages.OPTION_UTP | NatTraversalMessages.OPTION_QUIC;
    }

    // A connection from an expected host, near the port awaited, or from anyone when our port is reachable.
    private boolean mayConnect(InetSocketAddress from) {
        return find(from).isPresent() || this.reachable.getAsBoolean();
    }

    // The side that takes a connection says hello first; the side that opened it answers.
    private void accepted(StreamTransport connection) {
        Optional<Expected> by = find(connection.remoteAddress());
        if (by.isPresent()
                && by.get().waiting().isPresent()
                && !by.get().waiting().get().complete(connection)) {
            // The one we opened won.
            connection.close();
            return;
        }
        if (by.isPresent() && by.get().waiting().isEmpty()) {
            this.expected.remove(by.get());
        }
        this.connections.opened(connection, true);
    }

    private Optional<Expected> find(InetSocketAddress from) {
        Expected near = null;
        int nearest = Integer.MAX_VALUE;
        for (Expected e : live()) {
            InetAddress ip = e.at().getAddress();
            int distance = Math.abs(e.at().getPort() - from.getPort());
            if (ip.equals(from.getAddress()) && distance <= PORT_WINDOW && distance < nearest) {
                near = e;
                nearest = distance;
            }
        }
        return Optional.ofNullable(near);
    }

    private List<Expected> live() {
        long now = this.ticker.read();
        this.expected.removeIf(e -> now - e.until() > 0);
        return this.expected;
    }

    private void forget(CompletableFuture<StreamTransport> result) {
        this.expected.removeIf(e -> e.waiting().filter(result::equals).isPresent());
    }

    // A capability frame from a host we wait for, or that knows us, gets our answer: uTP and QUIC.
    private void answerCaps(InetSocketAddress from, Packet packet) {
        Caps caps;
        try {
            caps = Caps.decode(packet);
        } catch (DecodeException e) {
            logger.debug("Bad capabilities from {}: {}", from, e.getMessage());
            return;
        }
        boolean forUs = caps.expected().equals(this.ourHash.get());
        if (!forUs && (!caps.expected().isZero() || find(from).isEmpty())) {
            return;
        }
        Caps answer = new Caps(true, ourTraversalOptions(), this.ourHash.get(), caps.sender(), caps.file());
        this.datagrams.send(from, answer.encode());
    }

    // A burst towards the port, then one punch at each port near it, as the router may have chosen one of those.
    private void punch(InetSocketAddress at, int count) {
        Packet punch = NatTraversalMessages.holePunch();
        for (int i = 0; i < count; i++) {
            this.datagrams.send(at, punch);
        }
        for (int d = 1; d <= SWEEP; d++) {
            for (int port : new int[] {at.getPort() - d, at.getPort() + d}) {
                if (port >= 1 && port <= 0xFFFF) {
                    this.datagrams.send(new InetSocketAddress(at.getAddress(), port), punch);
                }
            }
        }
    }

    /** The connections open now through routers. */
    public List<StreamTransport> connections() {
        List<StreamTransport> all = new ArrayList<>(this.utp.connections());
        all.addAll(this.quic.connections());
        return all;
    }

    @Override
    public void close() {
        this.utp.close();
        this.quic.close();
        this.expected.clear();
    }

    // QUIC takes the connections uTP would.
    private final class QuicPeers implements QuicLink.Peers {
        @Override
        public boolean mayConnect(InetSocketAddress from) {
            return NatTraversal.this.mayConnect(from);
        }

        @Override
        public Optional<Hash16> expected(InetSocketAddress from) {
            return find(from).map(Expected::peer);
        }

        @Override
        public void accepted(QuicTransport connection) {
            NatTraversal.this.accepted(connection);
        }
    }
}
