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

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.security.SecureRandom;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.njmule.core.api.Hash16;
import org.njmule.core.protocol.crypt.DatagramObfuscation;
import org.njmule.core.protocol.io.DecodeException;
import org.njmule.core.protocol.kad.KadMessages;
import org.njmule.core.protocol.packet.Opcodes;
import org.njmule.core.protocol.packet.Packet;
import org.njmule.core.protocol.packet.Packing;
import org.njmule.core.protocol.packet.Protocol;
import org.njmule.core.protocol.packet.UdpFraming;

/**
 * Talks Kad with an eMule on this computer, without changing anything in it: bootstrap, hello, lookup and ping. Its UDP
 * port comes from {@code -Dnjmule.interop.kadPort} (default 14897).
 */
@Tag("interop")
@Timeout(60)
class KadInteropTest {

    private static final int PORT = Integer.getInteger("njmule.interop.kadPort", 14897);

    private final UInt128 own = UInt128.random(new SecureRandom());
    private DatagramSocket socket;
    private UInt128 emule;
    private int pongPort;

    @AfterEach
    void closeTheSocket() {
        if (this.socket != null) {
            this.socket.close();
        }
    }

    @Test
    void shouldBeAnsweredByAnEmuleToBootstrapHelloLookupAndPing() throws Exception {
        givenASocket();
        givenAnEmuleThatAnswersTheBootstrap();
        givenTheEmuleAnswersOurHello();
        givenTheEmuleAnswersALookupForOurId();

        whenTheEmuleIsPinged();

        thenThePongGivesOurPort();
    }

    private void givenASocket() throws IOException {
        this.socket = new DatagramSocket();
        this.socket.setSoTimeout(5_000);
    }

    private void givenAnEmuleThatAnswersTheBootstrap() throws IOException, DecodeException {
        Optional<Packet> bootstrap = ask(this.socket, KadMessages.bootstrapRequest(), Opcodes.Kad.BOOTSTRAP_RES);
        assumeTrue(bootstrap.isPresent(), "no eMule Kad answering on UDP port " + PORT);
        KadMessages.BootstrapAnswer answer = KadMessages.BootstrapAnswer.decode(bootstrap.get());
        assertThat(answer.sender().version()).isGreaterThanOrEqualTo(8);
        assertThat(answer.contacts()).hasSizeLessThanOrEqualTo(KadMessages.MAX_BOOTSTRAP_CONTACTS);
        for (KadMessages.Contact c : answer.contacts()) {
            assertThat(c.ip() >>> 24).as("no loopback contacts").isNotEqualTo(127);
        }
        this.emule = answer.sender().id();
    }

    private void givenTheEmuleAnswersOurHello() throws IOException, DecodeException {
        KadMessages.Hello hello =
                new KadMessages.Hello(new KadMessages.Self(this.own, 4662, KadMessages.VERSION), List.of());
        KadMessages.Hello reply = KadMessages.Hello.decode(
                ask(this.socket, hello.encode(false), Opcodes.Kad.HELLO_RES).orElseThrow());
        assertThat(reply.sender().id()).isEqualTo(this.emule);
        assertThat(reply.sender().version()).isEqualTo(KadMessages.VERSION);
    }

    private void givenTheEmuleAnswersALookupForOurId() throws IOException, DecodeException {
        KadMessages.Request request = new KadMessages.Request(11, this.own, this.emule);
        KadMessages.Answer contacts = KadMessages.Answer.decode(
                ask(this.socket, request.encode(), Opcodes.Kad.RES).orElseThrow());
        assertThat(contacts.target()).isEqualTo(this.own);
        assertThat(contacts.contacts()).hasSizeLessThanOrEqualTo(11);
    }

    private void whenTheEmuleIsPinged() throws IOException, DecodeException {
        this.pongPort = KadMessages.decodePong(
                ask(this.socket, KadMessages.ping(), Opcodes.Kad.PONG).orElseThrow());
    }

    private void thenThePongGivesOurPort() {
        assertThat(this.pongPort).isEqualTo(this.socket.getLocalPort());
    }

    // Sends a plain datagram and waits for the answer with the given opcode.
    private Optional<Packet> ask(DatagramSocket socket, Packet request, Opcodes.Kad answer)
            throws IOException, DecodeException {
        byte[] out = UdpFraming.encode(request);
        socket.send(new DatagramPacket(out, out.length, InetAddress.getLoopbackAddress(), PORT));
        byte[] buffer = new byte[8192];
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (System.nanoTime() < deadline) {
            DatagramPacket in = new DatagramPacket(buffer, buffer.length);
            try {
                socket.receive(in);
            } catch (SocketTimeoutException e) {
                return Optional.empty();
            }
            // Answers to a hello come obfuscated with our id.
            Packet packet = DatagramObfuscation.decodeFromClient(
                            buffer,
                            0,
                            in.getLength(),
                            Hash16.ZERO,
                            in.getAddress().getAddress(),
                            Optional.of(Hash16.of(this.own.toWireBytes())),
                            0)
                    .packet();
            if (packet.protocol() == Protocol.KAD_PACKED) {
                packet = Packing.unpack(packet, 250_000);
            }
            if (packet.protocol() == Protocol.KAD && packet.opcode() == answer.code()) {
                return Optional.of(packet);
            }
        }
        return Optional.empty();
    }
}
