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

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PushbackInputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import javax.crypto.CipherInputStream;
import javax.crypto.CipherOutputStream;
import org.njmule.core.api.Hash16;
import org.njmule.core.config.BindInterface;
import org.njmule.core.protocol.crypt.StreamObfuscation;
import org.njmule.core.protocol.packet.FrameLimits;
import org.njmule.core.protocol.packet.Protocol;

/**
 * Opens {@link TcpConnection}s: outgoing ones to servers and clients, plain or obfuscated, and incoming ones, whose
 * obfuscation is recognised from the first byte. Every method blocks until the handshake is done, so callers run them
 * on a virtual thread.
 */
public final class TcpConnector {

    /** Largest random padding added to an obfuscation handshake with a client. */
    public static final int MAX_CLIENT_PADDING = 128;

    /**
     * Largest padding in the answer to a server testing whether we are reachable: servers accept at most 16 bytes
     * there, and a longer padding leaves us with a LowID.
     */
    public static final int MAX_SERVER_TEST_PADDING = 15;

    private static final int BUFFER = 16 * 1024;

    // One engine per process: the network sets where outgoing connections go.
    private static volatile Supplier<Proxy> proxy = () -> Proxy.NO_PROXY;
    private static volatile Supplier<BindInterface.Binding> binding = BindInterface.Binding.Any::new;

    private TcpConnector() {}

    /** Outgoing connections go through the proxy {@code supplier} gives at each connection. */
    public static void useProxy(Supplier<Proxy> supplier) {
        proxy = Objects.requireNonNull(supplier, "supplier");
    }

    /** The proxy outgoing connections go through now. */
    static Proxy currentProxy() {
        return proxy.get();
    }

    /** Outgoing connections leave from the interface {@code supplier} gives at each connection, or not at all. */
    public static void useBinding(Supplier<BindInterface.Binding> supplier) {
        binding = Objects.requireNonNull(supplier, "supplier");
    }

    /** The interface outgoing connections leave from now. */
    static BindInterface.Binding currentBinding() {
        return binding.get();
    }

    /** The local address to bind a socket for the interface chosen now: null for any; none at all while it is gone. */
    static InetAddress localAddress() throws IOException {
        return switch (binding.get()) {
            case BindInterface.Binding.Any _ -> null;
            case BindInterface.Binding.Bound bound -> bound.address();
            case BindInterface.Binding.Missing missing ->
                throw new IOException("the network interface " + missing.choice() + " is not available");
        };
    }

    /** Connects to a server, obfuscated with a Diffie-Hellman key exchange if requested. */
    public static TcpConnection connectToServer(
            InetSocketAddress address, boolean obfuscate, Duration timeout, SecureRandom random) throws IOException {
        Socket socket = connect(address, timeout);
        try {
            InputStream in = new BufferedInputStream(socket.getInputStream(), BUFFER);
            OutputStream out = new BufferedOutputStream(socket.getOutputStream(), BUFFER);
            if (obfuscate) {
                StreamObfuscation.Ciphers ciphers = StreamObfuscation.connectToServer(in, out, random);
                in = new CipherInputStream(in, ciphers.receive().cipher());
                out = new CipherOutputStream(out, ciphers.send().cipher());
            }
            socket.setSoTimeout(0);
            return new TcpConnection(socket, in, out, obfuscate, FrameLimits.SERVER);
        } catch (IOException | RuntimeException e) {
            closeQuietly(socket);
            throw e;
        }
    }

    /**
     * Connects to a client; the connection is obfuscated when the client's user hash is known, since the keys derive
     * from it. Reads keep {@code timeout} until the caller changes it.
     */
    public static TcpConnection connectToClient(
            InetSocketAddress address, Optional<Hash16> obfuscateFor, Duration timeout, SecureRandom random)
            throws IOException {
        Socket socket = connect(address, timeout);
        try {
            InputStream in = new BufferedInputStream(socket.getInputStream(), BUFFER);
            OutputStream out = new BufferedOutputStream(socket.getOutputStream(), BUFFER);
            if (obfuscateFor.isPresent()) {
                StreamObfuscation.Ciphers ciphers =
                        StreamObfuscation.connectToClient(in, out, obfuscateFor.get(), random, MAX_CLIENT_PADDING);
                in = new CipherInputStream(in, ciphers.receive().cipher());
                out = new CipherOutputStream(out, ciphers.send().cipher());
            }
            return new TcpConnection(socket, in, out, obfuscateFor.isPresent(), FrameLimits.CLIENT_HANDSHAKE);
        } catch (IOException | RuntimeException e) {
            closeQuietly(socket);
            throw e;
        }
    }

    /**
     * Completes an accepted connection: a first byte that is not a protocol byte starts an obfuscation handshake keyed
     * on our user hash. Reads keep {@code timeout} (the time allowed for the hello) until the caller changes it.
     *
     * @param allowPlain {@code false} when obfuscation is required
     */
    public static TcpConnection accept(
            Socket socket, Hash16 ownUserHash, boolean allowPlain, Duration timeout, SecureRandom random)
            throws IOException {
        return accept(socket, ownUserHash, allowPlain, timeout, random, MAX_CLIENT_PADDING);
    }

    /** As above, with at most {@code maxPadding} bytes of handshake padding. */
    public static TcpConnection accept(
            Socket socket,
            Hash16 ownUserHash,
            boolean allowPlain,
            Duration timeout,
            SecureRandom random,
            int maxPadding)
            throws IOException {
        try {
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(Math.toIntExact(timeout.toMillis()));
            PushbackInputStream raw =
                    new PushbackInputStream(new BufferedInputStream(socket.getInputStream(), BUFFER), 1);
            OutputStream out = new BufferedOutputStream(socket.getOutputStream(), BUFFER);
            int first = raw.read();
            if (first < 0) {
                throw new EOFException("closed before the first byte");
            }
            if (Protocol.isTcp(first)) {
                if (!allowPlain) {
                    throw new IOException("plain connection refused, obfuscation required");
                }
                raw.unread(first);
                return new TcpConnection(socket, raw, out, false, FrameLimits.CLIENT_HANDSHAKE);
            }
            StreamObfuscation.Ciphers ciphers =
                    StreamObfuscation.acceptFromClient(raw, out, ownUserHash, random, maxPadding);
            return new TcpConnection(
                    socket,
                    new CipherInputStream(raw, ciphers.receive().cipher()),
                    new CipherOutputStream(out, ciphers.send().cipher()),
                    true,
                    FrameLimits.CLIENT_HANDSHAKE);
        } catch (IOException | RuntimeException e) {
            closeQuietly(socket);
            throw e;
        }
    }

    private static Socket connect(InetSocketAddress address, Duration timeout) throws IOException {
        int millis = Math.toIntExact(timeout.toMillis());
        InetAddress local = localAddress();
        Socket socket = new Socket(proxy.get());
        try {
            if (local != null) {
                socket.bind(new InetSocketAddress(local, 0));
            }
            socket.connect(address, millis);
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(millis);
            return socket;
        } catch (IOException | RuntimeException e) {
            closeQuietly(socket);
            throw e;
        }
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException e) {
            // The original failure is what matters.
        }
    }
}
