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
import java.net.Inet4Address;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Objects;
import org.njmule.core.security.ConnectionGuard;
import org.njmule.core.security.ConnectionGuard.Admission;
import org.njmule.core.util.Ipv4;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Accepts incoming TCP connections on a virtual thread. Each one first passes the {@link ConnectionGuard}; admitted
 * sockets go to the acceptor, each on its own virtual thread.
 */
public final class TcpListener implements AutoCloseable {

    /** Handles an admitted socket; it closes the permit when the connection ends. */
    @FunctionalInterface
    public interface Acceptor {
        void accepted(Socket socket, ConnectionGuard.Permit permit);
    }

    private static final Logger logger = LoggerFactory.getLogger(TcpListener.class);
    private static final int BACKLOG = 128;

    private final ServerSocket server;
    private final ConnectionGuard guard;
    private final Acceptor acceptor;

    private TcpListener(ServerSocket server, ConnectionGuard guard, Acceptor acceptor) {
        this.server = server;
        this.guard = guard;
        this.acceptor = acceptor;
    }

    /** Binds {@code address} (port 0 picks a free one) and starts accepting. */
    public static TcpListener open(InetSocketAddress address, ConnectionGuard guard, Acceptor acceptor)
            throws IOException {
        Objects.requireNonNull(guard, "guard");
        Objects.requireNonNull(acceptor, "acceptor");
        ServerSocket server = new ServerSocket();
        try {
            server.bind(address, BACKLOG);
        } catch (IOException e) {
            server.close();
            throw e;
        }
        TcpListener listener = new TcpListener(server, guard, acceptor);
        Thread.ofVirtual().name("njmule-tcp-listener-" + listener.port()).start(listener::run);
        return listener;
    }

    public int port() {
        return this.server.getLocalPort();
    }

    @Override
    public void close() throws IOException {
        this.server.close();
    }

    private void run() {
        while (!this.server.isClosed()) {
            Socket socket;
            try {
                socket = this.server.accept();
            } catch (IOException e) {
                if (!this.server.isClosed()) {
                    logger.warn("Accept failed on port {}", port(), e);
                }
                continue;
            }
            admit(socket);
        }
    }

    private void admit(Socket socket) {
        // The eD2K network is IPv4 only.
        if (!(socket.getInetAddress() instanceof Inet4Address address)) {
            closeQuietly(socket);
            return;
        }
        switch (this.guard.admit(Ipv4.fromBytes(address.getAddress()))) {
            case Admission.Admitted(ConnectionGuard.Permit permit) ->
                Thread.ofVirtual()
                        .name("njmule-tcp-accept-" + socket.getRemoteSocketAddress())
                        .start(() -> this.acceptor.accepted(socket, permit));
            case Admission.Refused(ConnectionGuard.Refusal reason) -> {
                logger.debug("Refused {}: {}", socket.getRemoteSocketAddress(), reason);
                closeQuietly(socket);
            }
        }
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException e) {
            // Refusing anyway.
        }
    }
}
