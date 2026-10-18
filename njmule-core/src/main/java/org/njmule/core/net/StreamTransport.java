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

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.time.Duration;
import java.util.Optional;

/**
 * The byte stream a connection runs over: a TCP socket, or a reliable stream over UDP that reaches a firewalled client.
 * A read that waits longer than the read timeout fails with a {@link java.net.SocketTimeoutException}.
 */
public interface StreamTransport extends AutoCloseable {

    InputStream input() throws IOException;

    OutputStream output() throws IOException;

    InetSocketAddress remoteAddress();

    /** Our address on it, while it is open. */
    Optional<InetAddress> localAddress();

    /** Fails reads that wait longer than {@code timeout}; zero waits forever. */
    void setReadTimeout(Duration timeout) throws SocketException;

    /** Closes it; a blocked read or write fails. */
    @Override
    void close();

    /** A TCP socket's. */
    static StreamTransport of(Socket socket) {
        return new SocketTransport(socket);
    }

    /** A connected TCP socket. */
    record SocketTransport(Socket socket) implements StreamTransport {
        @Override
        public InputStream input() throws IOException {
            return this.socket.getInputStream();
        }

        @Override
        public OutputStream output() throws IOException {
            return this.socket.getOutputStream();
        }

        @Override
        public InetSocketAddress remoteAddress() {
            return (InetSocketAddress) this.socket.getRemoteSocketAddress();
        }

        @Override
        public Optional<InetAddress> localAddress() {
            return Optional.ofNullable(this.socket.getLocalAddress()).filter(a -> !a.isAnyLocalAddress());
        }

        @Override
        public void setReadTimeout(Duration timeout) throws SocketException {
            this.socket.setSoTimeout(Math.toIntExact(timeout.toMillis()));
        }

        @Override
        public void close() {
            try {
                this.socket.close();
            } catch (IOException e) {
                // Closing anyway; nothing else to release.
            }
        }
    }
}
