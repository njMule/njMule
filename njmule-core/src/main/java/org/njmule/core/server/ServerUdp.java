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
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.util.Objects;
import org.njmule.core.protocol.io.DecodeException;
import org.njmule.core.protocol.packet.Packet;
import org.njmule.core.protocol.packet.Protocol;
import org.njmule.core.protocol.packet.UdpFraming;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The UDP socket for server queries, on a random port. A virtual thread receives; datagrams too
 * short for a header or not eDonkey ones are dropped before the receiver sees them.
 */
public final class ServerUdp implements AutoCloseable {

    /** Gets each plausible datagram, on the receiving thread. */
    @FunctionalInterface
    public interface Receiver {
        void received(InetSocketAddress from, Packet packet);
    }

    /** UDP port of a server: its TCP port + 4. */
    public static final int PORT_OFFSET = 4;

    private static final Logger LOG = LoggerFactory.getLogger(ServerUdp.class);
    private static final int MAX_DATAGRAM = 8 * 1024;

    private final DatagramSocket socket;
    private final Receiver receiver;

    private ServerUdp(DatagramSocket socket, Receiver receiver) {
        this.socket = socket;
        this.receiver = receiver;
    }

    /** Binds {@code port} (0 picks one) on every interface and starts receiving. */
    public static ServerUdp open(int port, Receiver receiver) throws SocketException {
        ServerUdp udp =
                new ServerUdp(
                        new DatagramSocket(port), Objects.requireNonNull(receiver, "receiver"));
        Thread.ofVirtual().name("njmule-server-udp").start(udp::run);
        return udp;
    }

    public void send(InetSocketAddress to, Packet packet) throws IOException {
        byte[] datagram = UdpFraming.encode(packet);
        this.socket.send(new DatagramPacket(datagram, datagram.length, to));
    }

    public int port() {
        return this.socket.getLocalPort();
    }

    @Override
    public void close() {
        this.socket.close();
    }

    private void run() {
        byte[] buffer = new byte[MAX_DATAGRAM];
        DatagramPacket datagram = new DatagramPacket(buffer, buffer.length);
        while (!this.socket.isClosed()) {
            try {
                datagram.setLength(buffer.length);
                this.socket.receive(datagram);
                Packet packet = UdpFraming.decode(buffer, 0, datagram.getLength());
                if (packet.protocol() == Protocol.EDONKEY) {
                    this.receiver.received((InetSocketAddress) datagram.getSocketAddress(), packet);
                }
            } catch (DecodeException e) {
                LOG.debug("Dropping a malformed datagram from {}", datagram.getSocketAddress());
            } catch (IOException e) {
                if (!this.socket.isClosed()) {
                    LOG.warn("Server UDP receive failed", e);
                }
            }
        }
    }
}
