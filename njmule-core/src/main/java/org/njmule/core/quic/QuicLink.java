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
package org.njmule.core.quic;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.njmule.core.api.Hash16;
import org.njmule.core.protocol.ed2k.NatTraversalMessages.QuicProof;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tech.kwik.core.QuicClientConnection;
import tech.kwik.core.QuicConnection;
import tech.kwik.core.QuicStream;
import tech.kwik.core.server.ApplicationProtocolConnection;
import tech.kwik.core.server.ApplicationProtocolConnectionFactory;
import tech.kwik.core.server.ServerConnection;
import tech.kwik.core.server.ServerConnectionConfig;
import tech.kwik.core.server.ServerConnector;

/**
 * QUIC connections between clients through their routers, whose datagrams travel in frames on the client UDP port. We
 * open connections as TLS client and take them as TLS server; nobody checks certificates, as each side first proves on
 * its stream who it is and whom it expects. Thread-safe.
 */
public final class QuicLink implements AutoCloseable {

    /** How QUIC datagrams leave the client UDP port. */
    @FunctionalInterface
    public interface Sender {
        void send(InetSocketAddress to, byte[] datagram);
    }

    /** The clients whose connections are taken. */
    public interface Peers {
        boolean mayConnect(InetSocketAddress from);

        /** The user hash of the client awaited at {@code from}, when known. */
        Optional<Hash16> expected(InetSocketAddress from);

        /** A connection a client opened, once it proved who it is. */
        void accepted(QuicTransport connection);
    }

    static final String PROTOCOL = "ed2k-ai-natt-quic-v1";
    private static final String SERVER_NAME = "emuleai-natt";
    private static final long STREAM_WINDOW = 512 * 1024;
    private static final long CONNECTION_WINDOW = 4 * 1024 * 1024;
    // Kwik pings at half the idle time: every 25 s, which keeps the routers' holes open.
    private static final Duration IDLE = Duration.ofSeconds(50);
    private static final Duration HANDSHAKE = Duration.ofSeconds(20);
    private static final Duration PROOF_WAIT = Duration.ofSeconds(10);
    private static final int CONNECTION_ID_LENGTH = 8;
    // Kwik keeps a connection alive only for as long as asked: longer than any.
    private static final int KEEP_ALIVE_SECONDS = Integer.MAX_VALUE / 2;

    private static final Logger logger = LoggerFactory.getLogger(QuicLink.class);

    static {
        // Certificates go unchecked on purpose, as the proofs identify the clients: Kwik must not warn on stdout.
        System.setProperty("tech.kwik.core.no-security-warnings", "true");
    }

    private final Sender sender;
    private final Supplier<Hash16> ourHash;
    private final Peers peers;
    private final SecureRandom random;
    private final Map<InetSocketAddress, FrameSocket> clients = new ConcurrentHashMap<>();
    // The hosts whose connections our server took, while they talk.
    private final Cache<InetSocketAddress, Boolean> served =
            CacheBuilder.newBuilder().expireAfterAccess(IDLE.multipliedBy(2)).build();
    private final Set<QuicTransport> live = ConcurrentHashMap.newKeySet();
    private final Object serving = new Object();
    // Guarded by serving.
    private FrameSocket serverSocket;
    private ServerConnector server;
    private boolean stopped;

    public QuicLink(Sender sender, Supplier<Hash16> ourHash, Peers peers, SecureRandom random) {
        this.sender = Objects.requireNonNull(sender, "sender");
        this.ourHash = Objects.requireNonNull(ourHash, "ourHash");
        this.peers = Objects.requireNonNull(peers, "peers");
        this.random = Objects.requireNonNull(random, "random");
    }

    /** A QUIC datagram that came in a frame from {@code from}. */
    public void received(InetSocketAddress from, byte[] datagram) {
        if (datagram.length == 0) {
            return;
        }
        FrameSocket client = this.clients.get(from);
        if (client != null) {
            client.deliver(from, datagram);
            return;
        }
        // Only a host we take connections from may start one: its first packets have the long header.
        boolean talking = this.served.getIfPresent(from) != null;
        if (talking || ((datagram[0] & 0x80) != 0 && this.peers.mayConnect(from))) {
            listening().ifPresent(socket -> {
                this.served.put(from, Boolean.TRUE);
                socket.deliver(from, datagram);
            });
        }
    }

    /**
     * Opens a connection to {@code to}, whose client we expect to have the user hash {@code peer} when known; the
     * future fails when none opens.
     */
    public CompletableFuture<QuicTransport> connect(InetSocketAddress to, Optional<Hash16> peer) {
        CompletableFuture<QuicTransport> result = new CompletableFuture<>();
        Thread.ofVirtual().name("njmule-quic-" + to + "-connect").start(() -> {
            try {
                result.complete(open(to, peer));
            } catch (IOException | RuntimeException e) {
                result.completeExceptionally(e);
            }
        });
        return result;
    }

    /** Whether a connection to {@code to} is being opened, or is open. */
    public boolean connecting(InetSocketAddress to) {
        return this.clients.containsKey(to);
    }

    /** The connections open now. */
    public List<QuicTransport> connections() {
        return List.copyOf(this.live);
    }

    private QuicTransport open(InetSocketAddress to, Optional<Hash16> peer) throws IOException {
        FrameSocket socket = new FrameSocket(this.sender, s -> this.clients.remove(to, s));
        if (this.clients.putIfAbsent(to, socket) != null) {
            socket.close();
            throw new IOException("already connecting to " + to);
        }
        QuicClientConnection connection;
        try {
            connection = QuicClientConnection.newBuilder()
                    .host(SERVER_NAME)
                    .proxy(to.getAddress().getHostAddress())
                    .port(to.getPort())
                    .applicationProtocol(PROTOCOL)
                    .noServerCertificateCheck()
                    .socketFactory(destination -> socket)
                    .connectTimeout(HANDSHAKE)
                    .maxIdleTimeout(IDLE)
                    .maxOpenPeerInitiatedBidirectionalStreams(1)
                    .maxOpenPeerInitiatedUnidirectionalStreams(0)
                    .defaultStreamReceiveBufferSize(STREAM_WINDOW)
                    .connectionIdLength(CONNECTION_ID_LENGTH)
                    .logger(new KwikLog())
                    .build();
        } catch (IOException | RuntimeException e) {
            socket.close();
            throw e;
        }
        QuicTransport transport = track(new QuicTransport(connection, to, t -> {
            this.live.remove(t);
            socket.close();
        }));
        connection.setPeerInitiatedStreamCallback(transport::peerOpened);
        try {
            connection.connect();
            connection.keepAlive(KEEP_ALIVE_SECONDS);
            establish(transport, peer);
            return transport;
        } catch (IOException | RuntimeException e) {
            transport.close();
            throw e;
        }
    }

    // Both sides write their proof first and check the other's.
    private void establish(QuicTransport transport, Optional<Hash16> peer) throws IOException {
        Hash16 us = this.ourHash.get();
        transport.open(new QuicProof(us, peer.orElse(Hash16.ZERO)).encode());
        QuicProof theirs = QuicProof.decode(transport.readFirst(QuicProof.LENGTH, PROOF_WAIT));
        if (!theirs.receiver().isZero() && !theirs.receiver().equals(us)) {
            throw new IOException("a proof for another client");
        }
        if (peer.isPresent() && !theirs.sender().equals(peer.get())) {
            throw new IOException("a proof from another client");
        }
    }

    private QuicTransport track(QuicTransport transport) {
        this.live.add(transport);
        return transport;
    }

    private Optional<FrameSocket> listening() {
        synchronized (this.serving) {
            if (this.stopped) {
                return Optional.empty();
            }
            if (this.serverSocket == null) {
                try {
                    startServer();
                } catch (IOException | GeneralSecurityException | RuntimeException e) {
                    logger.warn("Connections through routers over QUIC cannot come in: {}", e.toString());
                    this.stopped = true;
                    return Optional.empty();
                }
            }
            return Optional.of(this.serverSocket);
        }
    }

    // Guarded by serving.
    private void startServer() throws IOException, GeneralSecurityException {
        FrameSocket socket = new FrameSocket(this.sender, s -> {});
        try {
            ServerConnector connector = TlsIdentity.selfSigned(this.random, Instant.now())
                    .serve(ServerConnector.builder())
                    .withSocket(socket)
                    .withSupportedVersion(QuicConnection.QuicVersion.V1)
                    .withConfiguration(ServerConnectionConfig.builder()
                            .maxIdleTimeout((int) IDLE.toMillis())
                            .maxOpenPeerInitiatedBidirectionalStreams(1)
                            .maxOpenPeerInitiatedUnidirectionalStreams(0)
                            .maxBidirectionalStreamBufferSize(STREAM_WINDOW)
                            .maxConnectionBufferSize(CONNECTION_WINDOW)
                            .connectionIdLength(CONNECTION_ID_LENGTH)
                            .retryRequired(false)
                            .build())
                    .withLogger(new KwikLog())
                    .build();
            connector.registerApplicationProtocol(PROTOCOL, new Accepting());
            connector.start();
            this.server = connector;
            this.serverSocket = socket;
        } catch (IOException | GeneralSecurityException | RuntimeException e) {
            socket.close();
            throw e;
        }
    }

    @Override
    public void close() {
        ServerConnector connector;
        synchronized (this.serving) {
            this.stopped = true;
            connector = this.server;
            this.server = null;
            this.serverSocket = null;
        }
        for (QuicTransport t : this.live) {
            t.close();
        }
        for (FrameSocket s : this.clients.values()) {
            s.close();
        }
        if (connector != null) {
            // It waits for its connections to close.
            Thread.ofVirtual().name("njmule-quic-server-close").start(connector::close);
        }
    }

    // The server's side of each connection: once the client proved who it is, it is handed on.
    private final class Accepting implements ApplicationProtocolConnectionFactory {
        @Override
        public ApplicationProtocolConnection createConnection(String protocol, QuicConnection connection) {
            if (!(connection instanceof ServerConnection theirs)) {
                connection.close();
                return new ApplicationProtocolConnection() {};
            }
            InetSocketAddress from = theirs.getInitialRemoteAddress();
            QuicTransport transport = track(new QuicTransport(connection, from, t -> {
                QuicLink.this.live.remove(t);
                QuicLink.this.served.invalidate(from);
            }));
            Thread.ofVirtual().name("njmule-quic-" + from + "-accept").start(() -> {
                try {
                    establish(transport, QuicLink.this.peers.expected(from));
                    QuicLink.this.peers.accepted(transport);
                } catch (IOException | RuntimeException e) {
                    logger.debug("No QUIC connection from {}: {}", from, e.toString());
                    transport.close();
                }
            });
            return new ApplicationProtocolConnection() {
                @Override
                public void acceptPeerInitiatedStream(QuicStream stream) {
                    transport.peerOpened(stream);
                }
            };
        }

        @Override
        public int maxConcurrentPeerInitiatedBidirectionalStreams() {
            return 1;
        }

        @Override
        public int maxConcurrentPeerInitiatedUnidirectionalStreams() {
            return 0;
        }
    }
}
