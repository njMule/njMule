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

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.CipherInputStream;
import javax.crypto.CipherOutputStream;
import org.njmule.core.protocol.crypt.StreamObfuscation;
import org.njmule.core.protocol.ed2k.ServerMessages;
import org.njmule.core.protocol.packet.FrameLimit;
import org.njmule.core.protocol.packet.Opcodes;
import org.njmule.core.protocol.packet.Packet;
import org.njmule.core.protocol.packet.Packing;
import org.njmule.core.protocol.packet.Protocol;
import org.njmule.core.protocol.packet.TcpFraming;
import org.njmule.core.util.Ipv4;

/**
 * An eD2K server on loopback that a test scripts: it answers each login with the id it was given, sends what the test
 * asks to the last client, and records what it receives. A connection that opens with an obfuscation handshake is
 * closed at once, as by a server that cannot obfuscate, unless the server was started to take them.
 */
public final class ScriptedServer implements AutoCloseable {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final ServerSocket socket;
    private final BlockingQueue<Packet> received = new LinkedBlockingQueue<>();
    private final List<Socket> clients = new CopyOnWriteArrayList<>();
    private final List<OutputStream> outputs = new CopyOnWriteArrayList<>();
    private final AtomicInteger connections = new AtomicInteger();
    private final AtomicInteger obfuscatedLogins = new AtomicInteger();
    private final long clientId;
    private final int flags;
    private final boolean obfuscates;

    private ScriptedServer(InetAddress address, long clientId, int flags, boolean obfuscates) throws IOException {
        this.clientId = clientId;
        this.flags = flags;
        this.obfuscates = obfuscates;
        this.socket = new ServerSocket(0, 8, address);
        Thread.ofVirtual().name("scripted-server").start(this::accept);
    }

    /** A server that logs clients in with {@code clientId} and announces {@code flags}; 0 for no answer. */
    public static ScriptedServer start(long clientId, int flags) throws IOException {
        return new ScriptedServer(InetAddress.getLoopbackAddress(), clientId, flags, false);
    }

    /** As {@link #start}, taking obfuscated connections too. */
    public static ScriptedServer startObfuscating(long clientId, int flags) throws IOException {
        return new ScriptedServer(InetAddress.getLoopbackAddress(), clientId, flags, true);
    }

    /** As {@link #start}, on another address of this computer: one the server list takes, unlike loopback. */
    public static ScriptedServer startAt(InetAddress address, long clientId, int flags) throws IOException {
        return new ScriptedServer(address, clientId, flags, false);
    }

    /** This computer's LAN address, which the server list takes, unlike loopback; the test is skipped without one. */
    public static InetAddress lanAddress() throws SocketException {
        for (NetworkInterface nic : Collections.list(NetworkInterface.getNetworkInterfaces())) {
            if (!nic.isUp() || nic.isLoopback() || nic.isVirtual()) {
                continue;
            }
            for (InetAddress address : Collections.list(nic.getInetAddresses())) {
                if (address instanceof Inet4Address && !address.isLinkLocalAddress()) {
                    return address;
                }
            }
        }
        assumeTrue(false, "no LAN address");
        throw new IllegalStateException();
    }

    public int ip() {
        return Ipv4.fromBytes(this.socket.getInetAddress().getAddress());
    }

    public int port() {
        return this.socket.getLocalPort();
    }

    /** Connections accepted so far, obfuscated or not. */
    public int connections() {
        return this.connections.get();
    }

    /** Logins received over obfuscated connections. */
    public int obfuscatedLogins() {
        return this.obfuscatedLogins.get();
    }

    /** Sends {@code packets} to the client that connected last, once one did. */
    public void send(Packet... packets) throws IOException, InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (this.outputs.isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        OutputStream out = this.outputs.getLast();
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
        this.outputs.clear();
    }

    @Override
    public void close() throws IOException {
        this.socket.close();
        hangUp();
    }

    private void accept() {
        while (!this.socket.isClosed()) {
            try {
                Socket client = this.socket.accept();
                this.connections.incrementAndGet();
                Thread.ofVirtual().name("scripted-server-client").start(() -> serve(client));
            } catch (IOException e) {
                return;
            }
        }
    }

    private void serve(Socket client) {
        try (client) {
            InputStream in = new BufferedInputStream(client.getInputStream());
            OutputStream out = new BufferedOutputStream(client.getOutputStream());
            in.mark(1);
            int first = in.read();
            boolean obfuscated = first >= 0 && !Protocol.isTcp(first);
            if (first < 0 || (obfuscated && !this.obfuscates)) {
                return;
            }
            if (obfuscated) {
                StreamObfuscation.Ciphers ciphers = StreamObfuscation.acceptAsServer(in, out, RANDOM);
                in = new CipherInputStream(in, ciphers.receive().cipher());
                out = new CipherOutputStream(out, ciphers.send().cipher());
            } else {
                in.reset();
            }
            this.clients.add(client);
            this.outputs.add(out);
            while (true) {
                Packet packet = Packing.unpack(TcpFraming.read(in, FrameLimit.fixed(1 << 20)), 1 << 22);
                this.received.add(packet);
                if (packet.opcode() == Opcodes.Server.LOGINREQUEST.code()) {
                    if (obfuscated) {
                        this.obfuscatedLogins.incrementAndGet();
                    }
                    if (this.clientId != 0) {
                        synchronized (this) {
                            out.write(TcpFraming.encode(
                                    new ServerMessages.IdChange(this.clientId, this.flags, 0).encode()));
                            out.flush();
                        }
                    }
                }
            }
        } catch (IOException e) {
            // The client or the test closed the connection.
        }
    }
}
