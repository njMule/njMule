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
package org.njmule.core.upnp;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A router on the loopback that answers NAT-PMP (RFC 6886) or PCP only (RFC 6887), for tests. It notes each mapping
 * asked for, as "map TCP 4662 3600" or "delete UDP 4672", with the nonce after it in PCP.
 */
final class FakeNatPmp implements AutoCloseable {

    /** What it speaks. */
    enum Speaks {
        NAT_PMP,
        PCP
    }

    private static final byte[] OUTSIDE = {(byte) 203, 0, 113, 5};

    private final DatagramSocket socket;
    private final Speaks speaks;
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private volatile long grants = 7200;
    private volatile int outsideOffset;
    private volatile int result;

    FakeNatPmp(Speaks speaks) throws IOException {
        this.speaks = speaks;
        this.socket = new DatagramSocket(0, InetAddress.getLoopbackAddress());
        Thread.ofVirtual().name("fake-nat-pmp").start(this::serve);
    }

    InetSocketAddress address() {
        return new InetSocketAddress(InetAddress.getLoopbackAddress(), this.socket.getLocalPort());
    }

    /** The longest lease it grants. */
    void grantsAtMost(long seconds) {
        this.grants = seconds;
    }

    /** Maps to the port that many above the one asked for. */
    void mapsOutsideTo(int offset) {
        this.outsideOffset = offset;
    }

    /** Answers mappings with {@code code}, 0 being success. */
    void answers(int code) {
        this.result = code;
    }

    List<String> requests() {
        return List.copyOf(this.requests);
    }

    @Override
    public void close() {
        this.socket.close();
    }

    private void serve() {
        byte[] buffer = new byte[1100];
        while (!this.socket.isClosed()) {
            try {
                DatagramPacket in = new DatagramPacket(buffer, buffer.length);
                this.socket.receive(in);
                byte[] request = Arrays.copyOf(buffer, in.getLength());
                byte[] answer = this.speaks == Speaks.NAT_PMP ? natPmp(request) : pcp(request);
                if (answer != null) {
                    send(answer, in.getSocketAddress());
                }
            } catch (IOException e) {
                // Closed.
            }
        }
    }

    private void send(byte[] answer, SocketAddress to) throws IOException {
        this.socket.send(new DatagramPacket(answer, answer.length, to));
    }

    private byte[] natPmp(byte[] request) {
        if (request.length < 2) {
            return null;
        }
        int opcode = request[1] & 0xFF;
        if (request[0] != 0) {
            // A newer version than ours: NAT-PMP's "unsupported version".
            return ByteBuffer.allocate(8)
                    .put((byte) 0)
                    .put((byte) (0x80 | opcode))
                    .putShort((short) 1)
                    .array();
        }
        if (opcode == 0) {
            return ByteBuffer.allocate(12)
                    .put((byte) 0)
                    .put((byte) 0x80)
                    .putShort((short) 0)
                    .putInt(1)
                    .put(OUTSIDE)
                    .array();
        }
        ByteBuffer in = ByteBuffer.wrap(request);
        int port = in.getShort(4) & 0xFFFF;
        long lease = Integer.toUnsignedLong(in.getInt(8));
        this.requests.add((lease == 0 ? "delete " : "map ")
                + (opcode == 2 ? "TCP " : "UDP ")
                + port
                + (lease == 0 ? "" : " " + lease));
        return ByteBuffer.allocate(16)
                .put((byte) 0)
                .put((byte) (0x80 | opcode))
                .putShort((short) this.result)
                .putInt(1)
                .putShort((short) port)
                .putShort((short) (lease == 0 ? 0 : port + this.outsideOffset))
                .putInt((int) Math.min(lease, this.grants))
                .array();
    }

    private byte[] pcp(byte[] request) {
        if (request.length < 2) {
            return null;
        }
        if (request[0] != 2) {
            // An older version than ours: PCP's "unsupported version", in PCP.
            return ByteBuffer.allocate(24)
                    .put((byte) 2)
                    .put((byte) (0x80 | (request[1] & 0x7F)))
                    .put((byte) 0)
                    .put((byte) 1)
                    .array();
        }
        if (request.length < 60) {
            return null;
        }
        ByteBuffer in = ByteBuffer.wrap(request);
        long lease = Integer.toUnsignedLong(in.getInt(4));
        byte[] nonce = Arrays.copyOfRange(request, 24, 36);
        int protocol = request[36] & 0xFF;
        int port = in.getShort(40) & 0xFFFF;
        this.requests.add((lease == 0 ? "delete " : "map ") + (protocol == 6 ? "TCP " : "UDP ") + port
                + (lease == 0 ? "" : " " + lease) + " " + HexFormat.of().formatHex(nonce));
        return ByteBuffer.allocate(60)
                .put((byte) 2)
                .put((byte) 0x81)
                .put((byte) 0)
                .put((byte) this.result)
                .putInt((int) Math.min(lease, this.grants))
                .putInt(1)
                .put(new byte[12])
                .put(nonce)
                .put((byte) protocol)
                .put(new byte[3])
                .putShort((short) port)
                .putShort((short) (lease == 0 ? 0 : port + this.outsideOffset))
                .put(new byte[10])
                .put((byte) 0xFF)
                .put((byte) 0xFF)
                .put(OUTSIDE)
                .array();
    }
}
