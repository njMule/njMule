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
package org.njmule.core.utp;

import com.google.common.base.Ticker;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Predicate;
import org.njmule.core.protocol.io.DecodeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * uTP (BEP 29) over datagrams that the caller carries: the connections to and from other hosts, told apart by address
 * and connection id. No known Java library speaks it and is kept up and fit for a native image, so it is our own: the
 * reliable stream of BEP 29 with selective acknowledgements and LEDBAT's delay-based congestion control. Incoming
 * connections are taken only from the hosts the acceptor expects. Thread-safe.
 */
public final class UtpSocket implements AutoCloseable {

    /** Carries a uTP packet to an address; the framing around it and the socket are the caller's. */
    @FunctionalInterface
    public interface Sender {
        void send(InetSocketAddress to, byte[] packet);
    }

    private static final Logger logger = LoggerFactory.getLogger(UtpSocket.class);
    private static final Duration TICK = Duration.ofMillis(50);

    private record Key(InetSocketAddress remote, int receiveId) {}

    private final Sender sender;
    private final Ticker ticker;
    private final SecureRandom random;
    private final Map<Key, UtpConnection> connections = new ConcurrentHashMap<>();
    private volatile Predicate<InetSocketAddress> expected = remote -> false;
    private volatile Consumer<UtpConnection> accepted = connection -> connection.close();
    private volatile boolean closed;
    private final Thread ticking;

    public UtpSocket(Sender sender, Ticker ticker, SecureRandom random) {
        this.sender = Objects.requireNonNull(sender, "sender");
        this.ticker = Objects.requireNonNull(ticker, "ticker");
        this.random = Objects.requireNonNull(random, "random");
        this.ticking = Thread.ofVirtual().name("njmule-utp").start(this::tickLoop);
    }

    /** Connects to {@code remote}; the future completes once it answered, or fails when it does not. */
    public CompletableFuture<UtpConnection> connect(InetSocketAddress remote) {
        Objects.requireNonNull(remote, "remote");
        if (this.closed) {
            return CompletableFuture.failedFuture(new IOException("uTP closed"));
        }
        // An id that no connection to the host uses, either way.
        int receiveId;
        do {
            receiveId = this.random.nextInt(1 << 16);
        } while (this.connections.containsKey(new Key(remote, receiveId))
                || this.connections.containsKey(new Key(remote, (receiveId + 1) & 0xFFFF)));
        UtpConnection connection = UtpConnection.outgoing(this, remote, receiveId);
        this.connections.put(new Key(remote, receiveId), connection);
        connection.start();
        return connection.connected();
    }

    /**
     * Takes the connections that hosts {@code expected} open, handing each to {@code accepted}; the others are ignored.
     */
    public void accept(Predicate<InetSocketAddress> expected, Consumer<UtpConnection> accepted) {
        this.expected = Objects.requireNonNull(expected, "expected");
        this.accepted = Objects.requireNonNull(accepted, "accepted");
    }

    /** A datagram's uTP packet from {@code from}; one that is not uTP is ignored. */
    public void received(InetSocketAddress from, byte[] data, int offset, int length) {
        UtpPacket packet;
        try {
            packet = UtpPacket.decode(data, offset, length);
        } catch (DecodeException e) {
            logger.debug("Not uTP from {}: {}", from, e.getMessage());
            return;
        }
        if (packet.type() == UtpPacket.Type.SYN) {
            synReceived(from, packet);
            return;
        }
        UtpConnection connection = this.connections.get(new Key(from, packet.connectionId()));
        if (connection != null) {
            connection.received(packet);
        }
    }

    // A new connection from an expected host; a SYN sent again finds the connection it made.
    private void synReceived(InetSocketAddress from, UtpPacket syn) {
        Key key = new Key(from, (syn.connectionId() + 1) & 0xFFFF);
        UtpConnection known = this.connections.get(key);
        if (known != null) {
            known.received(syn);
            return;
        }
        if (this.closed || !this.expected.test(from)) {
            logger.debug("uTP: a connection from {} nobody expects", from);
            return;
        }
        UtpConnection connection = UtpConnection.incoming(this, from, syn);
        if (this.connections.putIfAbsent(key, connection) != null) {
            return;
        }
        connection.start();
        this.accepted.accept(connection);
    }

    /** The connections open now. */
    public List<UtpConnection> connections() {
        return List.copyOf(this.connections.values());
    }

    void send(InetSocketAddress to, UtpPacket packet) {
        if (!this.closed) {
            this.sender.send(to, packet.encode());
        }
    }

    void forget(UtpConnection connection) {
        this.connections.remove(new Key(connection.remoteAddress(), connection.receiveId()), connection);
    }

    long nanos() {
        return this.ticker.read();
    }

    int random16() {
        return this.random.nextInt(1 << 16);
    }

    Optional<UtpConnection> find(InetSocketAddress remote, int receiveId) {
        return Optional.ofNullable(this.connections.get(new Key(remote, receiveId)));
    }

    private void tickLoop() {
        while (!this.closed) {
            try {
                Thread.sleep(TICK);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            for (UtpConnection connection : this.connections.values()) {
                try {
                    connection.tick();
                } catch (RuntimeException e) {
                    logger.warn("uTP: a connection to {} failed", connection.remoteAddress(), e);
                    connection.abort("failed: " + e);
                }
            }
        }
    }

    /** Resets every connection and stops. */
    @Override
    public void close() {
        this.ticking.interrupt();
        for (UtpConnection connection : this.connections.values()) {
            connection.abort("uTP closed");
        }
        this.closed = true;
        this.connections.clear();
    }
}
