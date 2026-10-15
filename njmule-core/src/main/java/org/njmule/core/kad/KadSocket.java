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
package org.njmule.core.kad;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.function.IntPredicate;
import org.njmule.core.api.Hash16;
import org.njmule.core.engine.EngineLoop;
import org.njmule.core.protocol.crypt.DatagramObfuscation;
import org.njmule.core.protocol.io.DecodeException;
import org.njmule.core.protocol.packet.Packet;
import org.njmule.core.protocol.packet.Packing;
import org.njmule.core.protocol.packet.Protocol;
import org.njmule.core.protocol.packet.UdpFraming;
import org.njmule.core.util.Ipv4;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The client UDP socket, which Kad shares: datagrams are opened with any of the keys they may be obfuscated with,
 * unpacked, and Kad packets go to the node on the engine thread.
 */
public final class KadSocket implements KadNode.Transport, AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(KadSocket.class);
    private static final int MAX_DATAGRAM = 8 * 1024;
    private static final int MAX_UNPACKED = 250_000;
    private static final int PACK_ABOVE = 200;
    private static final Duration CLOSE_WAIT = Duration.ofSeconds(2);

    private final DatagramSocket socket;
    private Thread reader;
    private final EngineLoop loop;
    private final Hash16 userHash;
    private final KadIdentity identity;
    private final IntPredicate blocked;
    private final SecureRandom random;
    private volatile KadNode node;
    private volatile ClientPackets clientPackets = (ip, port, packet) -> {};

    /** Client packets that share the socket with Kad, such as callback requests; on the engine thread. */
    @FunctionalInterface
    public interface ClientPackets {
        void received(int ip, int port, Packet packet);
    }

    private KadSocket(
            DatagramSocket socket,
            EngineLoop loop,
            Hash16 userHash,
            KadIdentity identity,
            IntPredicate blocked,
            SecureRandom random) {
        this.socket = socket;
        this.loop = loop;
        this.userHash = userHash;
        this.identity = identity;
        this.blocked = blocked;
        this.random = random;
    }

    /**
     * Binds {@code port} on every interface; packets reach the node once {@link #deliverTo} is set.
     *
     * @param blocked addresses (host order) whose datagrams are dropped unread
     */
    public static KadSocket open(
            int port, EngineLoop loop, Hash16 userHash, KadIdentity identity, IntPredicate blocked, SecureRandom random)
            throws SocketException {
        KadSocket socket = new KadSocket(
                new DatagramSocket(port),
                Objects.requireNonNull(loop, "loop"),
                Objects.requireNonNull(userHash, "userHash"),
                Objects.requireNonNull(identity, "identity"),
                Objects.requireNonNull(blocked, "blocked"),
                Objects.requireNonNull(random, "random"));
        socket.reader = Thread.ofVirtual().name("njmule-kad-udp").start(socket::run);
        return socket;
    }

    public void deliverTo(KadNode node) {
        this.node = Objects.requireNonNull(node, "node");
    }

    /** Where the client packets of the extended protocol go. */
    public void deliverClientPacketsTo(ClientPackets sink) {
        this.clientPackets = Objects.requireNonNull(sink, "sink");
    }

    /**
     * Sends a client packet, obfuscated for {@code receiverUserHash} when given; {@code ourIp} ({@code Ipv4} form) is
     * part of that key.
     */
    public void sendClient(int ip, int port, Packet packet, Optional<Hash16> receiverUserHash, int ourIp) {
        byte[] datagram = UdpFraming.encode(packet);
        if (receiverUserHash.isPresent() && ourIp != 0) {
            datagram = DatagramObfuscation.encryptForClient(
                    datagram, receiverUserHash.get(), Ipv4.toBytes(ourIp), this.random);
        }
        transmit(ip, port, datagram);
    }

    public int port() {
        return this.socket.getLocalPort();
    }

    @Override
    public void send(int ip, int port, Packet packet, Optional<UInt128> receiverId, int receiverKey, int senderKey) {
        Packet framed = packet.length() > PACK_ABOVE ? Packing.pack(packet) : packet;
        byte[] datagram = UdpFraming.encode(framed);
        if (receiverId.isPresent() || receiverKey != 0) {
            datagram = DatagramObfuscation.encryptForKad(
                    datagram, receiverId.map(id -> Hash16.of(id.toWireBytes())), receiverKey, senderKey, this.random);
        }
        transmit(ip, port, datagram);
    }

    private void transmit(int ip, int port, byte[] datagram) {
        try {
            this.socket.send(
                    new DatagramPacket(datagram, datagram.length, new InetSocketAddress(Ipv4.format(ip), port)));
        } catch (IOException | IllegalArgumentException e) {
            // A bad address must not end the caller's work, a lookup or the table's upkeep.
            logger.debug("Cannot send to {}:{}: {}", Ipv4.format(ip), port, e.getMessage());
        }
    }

    @Override
    public void close() {
        this.socket.close();
        // The port is free only once the receiving thread has left the socket: until then binding it again fails,
        // as when Kad starts again at once on the same port.
        Thread reading = this.reader;
        if (reading != null && !reading.equals(Thread.currentThread())) {
            try {
                if (!reading.join(CLOSE_WAIT)) {
                    logger.debug("The UDP reader of port {} is still running", this.socket.getLocalPort());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void run() {
        byte[] buffer = new byte[MAX_DATAGRAM];
        DatagramPacket datagram = new DatagramPacket(buffer, buffer.length);
        while (!this.socket.isClosed()) {
            try {
                datagram.setLength(buffer.length);
                this.socket.receive(datagram);
                received(datagram);
            } catch (IOException e) {
                if (!this.socket.isClosed()) {
                    logger.debug("Kad UDP: {}", e.getMessage());
                }
            }
        }
    }

    private void received(DatagramPacket datagram) {
        KadNode target = this.node;
        if (target == null || !(datagram.getSocketAddress() instanceof InetSocketAddress from)) {
            return;
        }
        byte[] address = from.getAddress().getAddress();
        if (address.length != 4 || from.getPort() == 0) {
            return;
        }
        int ip = Ipv4.fromBytes(address);
        if (this.blocked.test(ip)) {
            return;
        }
        try {
            DatagramObfuscation.Received opened = DatagramObfuscation.decodeFromClient(
                    datagram.getData(),
                    datagram.getOffset(),
                    datagram.getLength(),
                    this.userHash,
                    address,
                    Optional.of(Hash16.of(this.identity.id().toWireBytes())),
                    this.identity.verifyKey(ip));
            Packet packet = opened.packet();
            if (packet.protocol() == Protocol.KAD_PACKED) {
                packet = Packing.unpack(packet, MAX_UNPACKED);
            }
            int port = from.getPort();
            if (packet.protocol() == Protocol.EMULE) {
                Packet client = packet;
                this.loop.post(() -> this.clientPackets.received(ip, port, client));
                return;
            }
            if (packet.protocol() != Protocol.KAD) {
                return;
            }
            Packet kad = packet;
            boolean obfuscated = opened.kind() != DatagramObfuscation.Kind.PLAIN;
            this.loop.post(() ->
                    target.received(ip, port, kad, obfuscated, opened.receiverVerifyKey(), opened.senderVerifyKey()));
        } catch (DecodeException e) {
            logger.debug("Bad datagram from {}: {}", from, e.getMessage());
        }
    }
}
