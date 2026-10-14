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
package org.njmule.core.download;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.njmule.core.api.Ed2kLink;
import org.njmule.core.api.Hash16;
import org.njmule.core.protocol.ed2k.Hello;
import org.njmule.core.protocol.packet.FrameLimit;
import org.njmule.core.protocol.packet.Opcodes;
import org.njmule.core.protocol.packet.Packet;
import org.njmule.core.protocol.packet.Packing;
import org.njmule.core.protocol.packet.Protocol;
import org.njmule.core.protocol.packet.TcpFraming;

/** A source on the loopback address that answers the hello of each downloader and leaves the rest to the test. */
final class ScriptedSource implements AutoCloseable {

    static final Duration WAIT = Duration.ofSeconds(20);

    private static final Set<Integer> IDENTIFICATION = Set.of(
            Opcodes.Extended.SECIDENTSTATE.code(),
            Opcodes.Extended.PUBLICKEY.code(),
            Opcodes.Extended.SIGNATURE.code());

    private final ServerSocket server;
    private final Hash16 userHash;
    private final int misc1;
    private final int misc2;
    private final BlockingQueue<Socket> accepted = new LinkedBlockingQueue<>();

    /** A source with {@code userHash} announcing the options {@code misc1} and {@code misc2} in its hello. */
    ScriptedSource(Hash16 userHash, int misc1, int misc2) throws IOException {
        this.userHash = userHash;
        this.misc1 = misc1;
        this.misc2 = misc2;
        this.server = new ServerSocket(0, 16, InetAddress.getLoopbackAddress());
        Thread.ofVirtual().name("scripted-source").start(this::acceptLoop);
    }

    int port() {
        return this.server.getLocalPort();
    }

    InetSocketAddress address() {
        return new InetSocketAddress(this.server.getInetAddress(), this.port());
    }

    /** A link of {@code size} bytes named {@code name}, with this source. */
    Ed2kLink.FileLink link(String name, long size, Hash16 hash) {
        return new Ed2kLink.FileLink(
                name,
                size,
                hash,
                Optional.empty(),
                List.of(),
                List.of(new Ed2kLink.Source("127.0.0.1", this.port())),
                List.of());
    }

    Hello hello() {
        return this.hello(this.userHash, 0, 0);
    }

    /** Our hello with another user hash, client id and server, as a firewalled client tells them. */
    Hello hello(Hash16 hash, long clientId, int serverIp) {
        return Hello.of(hash, clientId, this.port(), "scripted", 0, 0, this.misc1, this.misc2, serverIp, 4661);
    }

    /** Connects to the downloader listening at {@code port} with {@code hello}, as a source calling back does. */
    static Link call(int port, Hello hello) throws IOException {
        Socket socket = new Socket(InetAddress.getLoopbackAddress(), port);
        socket.setSoTimeout((int) WAIT.toMillis());
        Link link = new Link(socket);
        link.send(hello.encode(false));
        assertThat(link.next().opcode()).isEqualTo(Opcodes.Client.HELLOANSWER.code());
        return link;
    }

    /** Waits for the next downloader and leaves its connection unanswered. */
    Socket acceptSilently() throws Exception {
        return this.nextSocket();
    }

    /** Waits for the next downloader and answers its hello. */
    Link accept() throws Exception {
        Link link = new Link(this.nextSocket());
        Packet hello = link.next();
        assertThat(hello.opcode()).isEqualTo(Opcodes.Client.HELLO.code());
        link.send(this.hello().encode(true));
        return link;
    }

    /** Waits for the next downloader and closes its connection without a word. */
    void refuseNext() throws Exception {
        this.nextSocket().close();
    }

    /** Whether a downloader connects within {@code wait}; its connection is closed without a word. */
    boolean isConnectedToWithin(Duration wait) throws Exception {
        Socket socket = this.accepted.poll(wait.toMillis(), TimeUnit.MILLISECONDS);
        if (socket == null) {
            return false;
        }
        socket.close();
        return true;
    }

    private Socket nextSocket() throws InterruptedException, SocketException {
        Socket socket = this.accepted.poll(WAIT.toSeconds(), TimeUnit.SECONDS);
        assertThat(socket).as("a downloader connects").isNotNull();
        socket.setSoTimeout((int) WAIT.toMillis());
        return socket;
    }

    @Override
    public void close() throws IOException {
        this.server.close();
        for (Socket socket : this.accepted) {
            socket.close();
        }
    }

    private void acceptLoop() {
        while (!this.server.isClosed()) {
            try {
                this.accepted.add(this.server.accept());
            } catch (IOException e) {
                return;
            }
        }
    }

    /** One downloader's connection. */
    static final class Link implements AutoCloseable {
        private final Socket socket;
        private final InputStream in;
        private final OutputStream out;

        Link(Socket socket) throws IOException {
            this.socket = socket;
            this.in = new BufferedInputStream(socket.getInputStream());
            this.out = socket.getOutputStream();
        }

        void send(Packet packet) throws IOException {
            this.out.write(TcpFraming.encode(packet));
            this.out.flush();
        }

        /** The next packet, unpacked; the secure identification is left out. */
        Packet next() throws IOException {
            while (true) {
                Packet packet = Packing.unpack(TcpFraming.read(this.in, FrameLimit.fixed(1 << 22)), 1 << 22);
                if (packet.protocol() != Protocol.EMULE || !IDENTIFICATION.contains(packet.opcode())) {
                    return packet;
                }
            }
        }

        /** The packets read up to and with the first of {@code opcode} in {@code protocol}. */
        List<Packet> until(int protocol, int opcode) throws IOException {
            List<Packet> read = new ArrayList<>();
            while (true) {
                Packet packet = this.next();
                read.add(packet);
                if (packet.protocol() == protocol && packet.opcode() == opcode) {
                    return read;
                }
            }
        }

        List<Packet> until(Opcodes.Client opcode) throws IOException {
            return this.until(Protocol.EDONKEY, opcode.code());
        }

        List<Packet> until(Opcodes.Extended opcode) throws IOException {
            return this.until(Protocol.EMULE, opcode.code());
        }

        /** Whether the downloader closes the connection within {@code wait}, whatever it sends before. */
        boolean closesWithin(Duration wait) throws IOException {
            this.socket.setSoTimeout((int) wait.toMillis());
            try {
                while (true) {
                    this.next();
                }
            } catch (EOFException | SocketException e) {
                return true;
            } catch (SocketTimeoutException e) {
                return false;
            }
        }

        @Override
        public void close() throws IOException {
            this.socket.close();
        }
    }
}
