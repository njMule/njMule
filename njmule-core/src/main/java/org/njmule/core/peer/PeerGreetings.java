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
import java.net.Inet4Address;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.IntPredicate;
import java.util.function.Supplier;
import org.njmule.core.api.Hash16;
import org.njmule.core.api.PeerInfo;
import org.njmule.core.net.TcpConnection;
import org.njmule.core.net.TcpConnector;
import org.njmule.core.protocol.ed2k.Hello;
import org.njmule.core.protocol.io.DecodeException;
import org.njmule.core.protocol.packet.FrameLimits;
import org.njmule.core.protocol.packet.Opcodes;
import org.njmule.core.protocol.packet.Packet;
import org.njmule.core.protocol.packet.Packing;
import org.njmule.core.protocol.packet.Protocol;
import org.njmule.core.security.ConnectionGuard;
import org.njmule.core.util.Ipv4;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The hello exchange with other clients, in both directions. Incoming connections get our answer (servers test our
 * reachability this way before giving a HighID); outgoing greetings report what the peer answered. An incoming
 * connection that a {@link Router} wants is handed over after the hello; the others stay open until they are idle.
 * Thread-safe.
 */
public final class PeerGreetings implements AutoCloseable {

    /** Time an incoming peer has to send its hello. */
    public static final Duration HELLO_TIMEOUT = Duration.ofSeconds(10);

    /** An idle connection closes after this time. */
    public static final Duration IDLE_TIMEOUT = Duration.ofSeconds(40);

    private static final Logger logger = LoggerFactory.getLogger(PeerGreetings.class);

    /** Takes over incoming connections from clients something waits for, after their hello. */
    @FunctionalInterface
    public interface Router {
        /** The handler for the rest of the connection, if it is wanted; on its reading thread. */
        Optional<TcpConnection.Handler> route(TcpConnection connection, Hello peer);
    }

    private final Hash16 userHash;
    private final Supplier<Hello> ourHello;
    private final Supplier<Boolean> plainAllowed;
    private final IntPredicate serverTesting;
    private final SecureRandom random;
    private final Set<TcpConnection> open = ConcurrentHashMap.newKeySet();
    private volatile Router router = (connection, peer) -> Optional.empty();

    /**
     * @param ourHello builds our greeting from the current state (name, ports, server)
     * @param plainAllowed whether plain incoming connections are accepted
     * @param serverTesting whether an address ({@code Ipv4} form) is the server we log in to, which connects to tell
     *     whether we are reachable: it may come plain and takes a short padding
     */
    public PeerGreetings(
            Hash16 userHash,
            Supplier<Hello> ourHello,
            Supplier<Boolean> plainAllowed,
            IntPredicate serverTesting,
            SecureRandom random) {
        this.userHash = Objects.requireNonNull(userHash, "userHash");
        this.ourHello = Objects.requireNonNull(ourHello, "ourHello");
        this.plainAllowed = Objects.requireNonNull(plainAllowed, "plainAllowed");
        this.serverTesting = Objects.requireNonNull(serverTesting, "serverTesting");
        this.random = Objects.requireNonNull(random, "random");
    }

    /** Sets who may take over incoming connections. */
    public void routeTo(Router incoming) {
        this.router = Objects.requireNonNull(incoming, "incoming");
    }

    /** Serves an accepted socket (from a listener thread) until the connection ends. */
    public void accepted(Socket socket, ConnectionGuard.Permit permit) {
        TcpConnection connection;
        boolean server = socket.getInetAddress() instanceof Inet4Address ip
                && this.serverTesting.test(Ipv4.fromBytes(ip.getAddress()));
        try {
            connection = TcpConnector.accept(
                    socket,
                    this.userHash,
                    server || this.plainAllowed.get(),
                    HELLO_TIMEOUT,
                    this.random,
                    server ? TcpConnector.MAX_SERVER_TEST_PADDING : TcpConnector.MAX_CLIENT_PADDING);
        } catch (IOException e) {
            logger.debug("Incoming connection from {} failed: {}", socket.getRemoteSocketAddress(), e.getMessage());
            permit.close();
            return;
        }
        this.open.add(connection);
        connection.start(
                new Handler(Optional.empty()) {
                    @Override
                    public void onClosed(TcpConnection c, Optional<IOException> cause) {
                        super.onClosed(c, cause);
                        permit.close();
                    }
                },
                PeerGreetings::unpack);
    }

    /**
     * Connects to a client and says hello; the future completes with its answer. The connection stays open until the
     * peer closes it or it is idle.
     */
    public CompletableFuture<PeerInfo> greet(InetSocketAddress address) {
        return greet(address, Optional.empty());
    }

    /** As {@link #greet(InetSocketAddress)}, obfuscated with the peer's user hash when known. */
    public CompletableFuture<PeerInfo> greet(InetSocketAddress address, Optional<Hash16> obfuscateFor) {
        CompletableFuture<PeerInfo> answer = new CompletableFuture<>();
        Thread.ofVirtual().name("njmule-greet-" + address).start(() -> {
            try {
                TcpConnection connection =
                        TcpConnector.connectToClient(address, obfuscateFor, HELLO_TIMEOUT, this.random);
                this.open.add(connection);
                connection.start(new Handler(Optional.of(answer)), PeerGreetings::unpack);
                connection.send(this.ourHello.get().encode(false));
            } catch (IOException e) {
                answer.completeExceptionally(e);
            }
        });
        return answer.orTimeout(HELLO_TIMEOUT.toSeconds() * 2, TimeUnit.SECONDS);
    }

    /** Closes every open connection. */
    @Override
    public void close() {
        for (TcpConnection connection : this.open) {
            connection.close();
        }
    }

    /** Clients pack eMule packets: those inflate with the client cap. */
    private static Packet unpack(Packet frame) throws DecodeException {
        return Packing.unpack(frame, Packing.MAX_CLIENT_UNPACKED);
    }

    private class Handler implements TcpConnection.Handler {
        private final Optional<CompletableFuture<PeerInfo>> answer;
        private boolean greeted;
        private TcpConnection.Handler next;

        Handler(Optional<CompletableFuture<PeerInfo>> answer) {
            this.answer = answer;
        }

        @Override
        public void onPacket(TcpConnection connection, Packet packet) {
            if (this.next != null) {
                this.next.onPacket(connection, packet);
                return;
            }
            if (packet.protocol() != Protocol.EDONKEY) {
                return;
            }
            boolean hello = packet.opcode() == Opcodes.Client.HELLO.code();
            if (!hello && packet.opcode() != Opcodes.Client.HELLOANSWER.code()) {
                return;
            }
            try {
                Hello peer = Hello.decode(packet);
                if (hello) {
                    connection.send(PeerGreetings.this.ourHello.get().encode(true));
                }
                if (!this.greeted) {
                    this.greeted = true;
                    connection.setFrameLimit(FrameLimits.CLIENT);
                    connection.setReadTimeout(IDLE_TIMEOUT);
                    logger.debug("Hello from {}: {}", connection.remoteAddress(), PeerSoftware.describe(peer));
                    this.answer.ifPresent(a -> a.complete(info(connection, peer)));
                    // Ourselves, or someone posing as us: it hears who we are, which ends a connection to ourselves.
                    if (this.answer.isEmpty() && peer.userHash().equals(PeerGreetings.this.userHash)) {
                        connection.close();
                        return;
                    }
                    if (this.answer.isEmpty()) {
                        this.next = PeerGreetings.this
                                .router
                                .route(connection, peer)
                                .orElse(null);
                    }
                }
            } catch (IOException e) {
                logger.debug("Bad hello from {}: {}", connection.remoteAddress(), e.getMessage());
                connection.close();
            }
        }

        @Override
        public void onClosed(TcpConnection connection, Optional<IOException> cause) {
            PeerGreetings.this.open.remove(connection);
            if (this.next != null) {
                this.next.onClosed(connection, cause);
            }
            this.answer.ifPresent(
                    a -> a.completeExceptionally(cause.orElseGet(() -> new IOException("closed before the hello"))));
        }
    }

    /** What {@code peer} said of itself over {@code connection}; identification pending. */
    public static PeerInfo info(TcpConnection connection, Hello peer) {
        return new PeerInfo(
                connection.remoteAddress().getAddress().getHostAddress()
                        + ":"
                        + connection.remoteAddress().getPort(),
                peer.userHash(),
                peer.nick(),
                PeerSoftware.describe(peer),
                connection.isObfuscated(),
                PeerInfo.Identification.PENDING);
    }
}
