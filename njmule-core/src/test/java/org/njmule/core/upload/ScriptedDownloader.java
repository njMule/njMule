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
package org.njmule.core.upload;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.njmule.core.protocol.ed2k.Hello;
import org.njmule.core.protocol.packet.FrameLimit;
import org.njmule.core.protocol.packet.Opcodes;
import org.njmule.core.protocol.packet.Packet;
import org.njmule.core.protocol.packet.Packing;
import org.njmule.core.protocol.packet.Protocol;
import org.njmule.core.protocol.packet.TcpFraming;

/** A client on the loopback address that says hello to us and then says what the test makes it say. */
final class ScriptedDownloader implements AutoCloseable {

    static final Duration WAIT = Duration.ofSeconds(20);

    private static final Set<Integer> IDENTIFICATION = Set.of(
            Opcodes.Extended.SECIDENTSTATE.code(),
            Opcodes.Extended.PUBLICKEY.code(),
            Opcodes.Extended.SIGNATURE.code());

    private final Hello hello;
    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;

    private ScriptedDownloader(Hello hello, Socket socket) throws IOException {
        this.hello = hello;
        this.socket = socket;
        this.in = new BufferedInputStream(socket.getInputStream());
        this.out = socket.getOutputStream();
    }

    /** Connects to us at {@code port} and greets us with {@code hello}. */
    static ScriptedDownloader connect(int port, Hello hello) throws IOException {
        Socket socket = new Socket(InetAddress.getLoopbackAddress(), port);
        socket.setSoTimeout((int) WAIT.toMillis());
        ScriptedDownloader client = new ScriptedDownloader(hello, socket);
        client.send(hello.encode(false));
        assertThat(client.next().opcode()).isEqualTo(Opcodes.Client.HELLOANSWER.code());
        return client;
    }

    /** Takes the connection we open to {@code listening}, answering our hello with {@code hello}. */
    static ScriptedDownloader acceptCall(ServerSocket listening, Hello hello) throws IOException {
        listening.setSoTimeout((int) WAIT.toMillis());
        Socket socket = listening.accept();
        socket.setSoTimeout((int) WAIT.toMillis());
        ScriptedDownloader client = new ScriptedDownloader(hello, socket);
        assertThat(client.next().opcode()).isEqualTo(Opcodes.Client.HELLO.code());
        client.send(hello.encode(true));
        return client;
    }

    Hello hello() {
        return this.hello;
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

    /** The next packet, if one comes within {@code wait}. */
    Optional<Packet> poll(Duration wait) throws IOException {
        this.socket.setSoTimeout((int) wait.toMillis());
        try {
            return Optional.of(next());
        } catch (SocketTimeoutException e) {
            return Optional.empty();
        } finally {
            this.socket.setSoTimeout((int) WAIT.toMillis());
        }
    }

    /** The packets read up to and with the first of {@code opcode} in {@code protocol}. */
    List<Packet> until(int protocol, int opcode) throws IOException {
        List<Packet> read = new ArrayList<>();
        while (true) {
            Packet packet = next();
            read.add(packet);
            if (packet.protocol() == protocol && packet.opcode() == opcode) {
                return read;
            }
        }
    }

    Packet until(Opcodes.Client opcode) throws IOException {
        return until(Protocol.EDONKEY, opcode.code()).getLast();
    }

    Packet until(Opcodes.Extended opcode) throws IOException {
        return until(Protocol.EMULE, opcode.code()).getLast();
    }

    /** Whether we close the connection within {@code wait}, whatever we send before. */
    boolean closedWithin(Duration wait) throws IOException {
        this.socket.setSoTimeout((int) wait.toMillis());
        try {
            while (true) {
                next();
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
