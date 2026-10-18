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

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.SocketException;
import java.util.Arrays;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The datagram socket QUIC runs on: what it sends leaves the client UDP port in frames, and what arrives in frames for
 * it is handed in here. It binds no port of its own.
 */
final class FrameSocket extends DatagramSocket {

    // Datagrams waiting for QUIC to read them; more are dropped, as a full socket buffer would.
    private static final int WAITING = 1024;

    private static final DatagramPacket CLOSED = new DatagramPacket(new byte[0], 0);

    private static final Logger logger = LoggerFactory.getLogger(FrameSocket.class);

    private final QuicLink.Sender sender;
    private final Consumer<FrameSocket> onClose;
    private final BlockingQueue<DatagramPacket> arrived = new LinkedBlockingQueue<>(WAITING);
    private final AtomicBoolean shut = new AtomicBoolean();

    /** @param onClose runs once, when QUIC closes the socket */
    FrameSocket(QuicLink.Sender sender, Consumer<FrameSocket> onClose) throws SocketException {
        // An unbound socket, which only the close below releases.
        super((SocketAddress) null);
        this.sender = sender;
        this.onClose = onClose;
    }

    /** A datagram that came in a frame from {@code from}. */
    void deliver(InetSocketAddress from, byte[] data) {
        if (!this.shut.get() && !this.arrived.offer(new DatagramPacket(data, data.length, from))) {
            logger.trace("A QUIC datagram from {} dropped: too many wait", from);
        }
    }

    @Override
    public void send(DatagramPacket packet) throws IOException {
        if (this.shut.get()) {
            throw new SocketException("closed");
        }
        byte[] data = Arrays.copyOfRange(packet.getData(), packet.getOffset(), packet.getOffset() + packet.getLength());
        this.sender.send((InetSocketAddress) packet.getSocketAddress(), data);
    }

    @Override
    public void receive(DatagramPacket packet) throws IOException {
        DatagramPacket d;
        try {
            d = this.arrived.take();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("interrupted");
        }
        if (d == CLOSED) {
            // Wakes the next reader too; the one taken left room.
            this.arrived.add(CLOSED);
            throw new SocketException("closed");
        }
        int length = Math.min(d.getLength(), packet.getData().length - packet.getOffset());
        System.arraycopy(d.getData(), 0, packet.getData(), packet.getOffset(), length);
        packet.setLength(length);
        packet.setSocketAddress(d.getSocketAddress());
    }

    @Override
    public void close() {
        if (!this.shut.compareAndSet(false, true)) {
            return;
        }
        this.arrived.clear();
        this.arrived.add(CLOSED);
        super.close();
        this.onClose.accept(this);
    }

    @Override
    public boolean isClosed() {
        return this.shut.get();
    }

    @Override
    public int getLocalPort() {
        return 0;
    }

    @Override
    public SocketAddress getLocalSocketAddress() {
        return new InetSocketAddress((InetAddress) null, 0);
    }

    @Override
    public int getReceiveBufferSize() {
        return WAITING * 1500;
    }
}
