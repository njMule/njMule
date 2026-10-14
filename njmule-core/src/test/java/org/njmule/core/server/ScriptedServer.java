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

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.njmule.core.protocol.ed2k.ServerMessages;
import org.njmule.core.protocol.packet.FrameLimit;
import org.njmule.core.protocol.packet.Opcodes;
import org.njmule.core.protocol.packet.Packet;
import org.njmule.core.protocol.packet.Packing;
import org.njmule.core.protocol.packet.TcpFraming;
import org.njmule.core.util.Ipv4;

/**
 * An eD2K server on loopback that a test scripts, plain connections only: it answers each login with the id it was
 * given, sends what the test asks to the last client, and records what it receives.
 */
public final class ScriptedServer implements AutoCloseable {

    private final ServerSocket socket;
    private final BlockingQueue<Packet> received = new LinkedBlockingQueue<>();
    private final List<Socket> clients = new CopyOnWriteArrayList<>();
    private final long clientId;
    private final int flags;

    private ScriptedServer(long clientId, int flags) throws IOException {
        this.clientId = clientId;
        this.flags = flags;
        this.socket = new ServerSocket(0, 8, InetAddress.getLoopbackAddress());
        Thread.ofVirtual().name("scripted-server").start(this::accept);
    }

    /** A server that logs clients in with {@code clientId} and announces {@code flags}; 0 for no answer. */
    public static ScriptedServer start(long clientId, int flags) throws IOException {
        return new ScriptedServer(clientId, flags);
    }

    public int ip() {
        return Ipv4.parse("127.0.0.1");
    }

    public int port() {
        return this.socket.getLocalPort();
    }

    /** Sends {@code packets} to the client that connected last, once one did. */
    public void send(Packet... packets) throws IOException, InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (this.clients.isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        OutputStream out = this.clients.getLast().getOutputStream();
        synchronized (this) {
            for (Packet packet : packets) {
                out.write(TcpFraming.encode(packet));
            }
            out.flush();
        }
    }

    /** The next packet received with {@code opcode}, the others before it dropped; empty after {@code wait}. */
    public Optional<Packet> next(int opcode, Duration wait) throws InterruptedException {
        long deadline = System.nanoTime() + wait.toNanos();
        while (true) {
            long left = deadline - System.nanoTime();
            if (left <= 0) {
                return Optional.empty();
            }
            Packet packet = this.received.poll(left, TimeUnit.NANOSECONDS);
            if (packet != null && packet.opcode() == opcode) {
                return Optional.of(packet);
            }
        }
    }

    /** Closes the connections of the clients, as a server that goes away. */
    public void hangUp() throws IOException {
        for (Socket client : this.clients) {
            client.close();
        }
        this.clients.clear();
    }

    @Override
    public void close() throws IOException {
        this.socket.close();
        this.hangUp();
    }

    private void accept() {
        while (!this.socket.isClosed()) {
            try {
                Socket client = this.socket.accept();
                this.clients.add(client);
                Thread.ofVirtual().name("scripted-server-client").start(() -> this.serve(client));
            } catch (IOException e) {
                return;
            }
        }
    }

    private void serve(Socket client) {
        try {
            InputStream in = new BufferedInputStream(client.getInputStream());
            while (true) {
                Packet packet = Packing.unpack(TcpFraming.read(in, FrameLimit.fixed(1 << 20)), 1 << 22);
                this.received.add(packet);
                if (packet.opcode() == Opcodes.Server.LOGINREQUEST.code() && this.clientId != 0) {
                    OutputStream out = client.getOutputStream();
                    synchronized (this) {
                        out.write(
                                TcpFraming.encode(new ServerMessages.IdChange(this.clientId, this.flags, 0).encode()));
                        out.flush();
                    }
                }
            }
        } catch (IOException e) {
            // The client or the test closed the connection.
        }
    }
}
