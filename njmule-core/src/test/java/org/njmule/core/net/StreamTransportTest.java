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
package org.njmule.core.net;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.njmule.core.protocol.packet.FrameLimits;
import org.njmule.core.protocol.packet.Packet;
import org.njmule.core.protocol.packet.Protocol;

class StreamTransportTest {

    private TcpConnection near;
    private TcpConnection far;
    private final CompletableFuture<Packet> heard = new CompletableFuture<>();

    @AfterEach
    void closeBoth() {
        for (TcpConnection c : new TcpConnection[] {this.near, this.far}) {
            if (c != null) {
                c.close();
            }
        }
    }

    @Test
    void shouldCarryPacketsOverAStreamOtherThanASocket() throws Exception {
        givenTwoConnectionsOverAPipe();

        whenTheNearOneSends(Packet.of(Protocol.EMULE, 0x60, new byte[] {1, 2, 3}));

        thenTheFarOneHears(Packet.of(Protocol.EMULE, 0x60, new byte[] {1, 2, 3}));
    }

    private void givenTwoConnectionsOverAPipe() throws IOException {
        PipedInputStream toFar = new PipedInputStream(1 << 16);
        PipedInputStream toNear = new PipedInputStream(1 << 16);
        PipedOutputStream fromNear = new PipedOutputStream(toFar);
        PipedOutputStream fromFar = new PipedOutputStream(toNear);
        this.near = TcpConnection.over(new Pipe(toNear, fromNear, 1), FrameLimits.CLIENT);
        this.far = TcpConnection.over(new Pipe(toFar, fromFar, 2), FrameLimits.CLIENT);
        this.near.start(new Ignoring(), frame -> frame);
        this.far.start(
                new TcpConnection.Handler() {
                    @Override
                    public void onPacket(TcpConnection connection, Packet packet) {
                        StreamTransportTest.this.heard.complete(packet);
                    }

                    @Override
                    public void onClosed(TcpConnection connection, Optional<IOException> cause) {
                        // The test closes it.
                    }
                },
                frame -> frame);
    }

    private void whenTheNearOneSends(Packet packet) {
        this.near.send(packet);
    }

    private void thenTheFarOneHears(Packet expected) throws Exception {
        assertThat(this.heard.get(10, TimeUnit.SECONDS)).isEqualTo(expected);
    }

    private static final class Ignoring implements TcpConnection.Handler {
        @Override
        public void onPacket(TcpConnection connection, Packet packet) {
            // Nothing comes back.
        }

        @Override
        public void onClosed(TcpConnection connection, Optional<IOException> cause) {
            // The test closes it.
        }
    }

    // One end of a pipe, at a made-up loopback port.
    private record Pipe(InputStream in, OutputStream out, int port) implements StreamTransport {
        @Override
        public InputStream input() {
            return this.in;
        }

        @Override
        public OutputStream output() {
            return this.out;
        }

        @Override
        public InetSocketAddress remoteAddress() {
            return new InetSocketAddress(InetAddress.getLoopbackAddress(), this.port);
        }

        @Override
        public Optional<InetAddress> localAddress() {
            return Optional.of(InetAddress.getLoopbackAddress());
        }

        @Override
        public void setReadTimeout(Duration timeout) {
            // Pipes wait forever.
        }

        @Override
        public void close() {
            try {
                this.in.close();
                this.out.close();
            } catch (IOException e) {
                // Closing anyway.
            }
        }
    }
}
