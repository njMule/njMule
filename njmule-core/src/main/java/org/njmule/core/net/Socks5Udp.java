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
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import org.njmule.core.config.ProxySettings;

/**
 * A UDP association through a SOCKS5 proxy (RFC 1928 §7, logging in as RFC 1929 says): datagrams go to the proxy's
 * relay behind a header that names their destination and come back behind one that names their sender. It lasts as long
 * as the TCP connection that asked for it. Our own code, since the JDK and Jetty carry only TCP through SOCKS.
 */
public final class Socks5Udp implements AutoCloseable {

    /** The proxy cannot relay our UDP: it speaks no SOCKS5, wants another login, or refuses the command. */
    public static final class Unsupported extends IOException {
        private static final long serialVersionUID = 1L;

        Unsupported(String message) {
            super(message);
        }
    }

    /** The header of a datagram to or from an IPv4 address. */
    static final int IPV4_HEADER = 10;

    private static final int VERSION = 5;
    private static final int NO_LOGIN = 0;
    private static final int USER_AND_PASSWORD = 2;
    private static final int LOGIN_VERSION = 1;
    private static final int UDP_ASSOCIATE = 3;
    private static final int IPV4 = 1;
    private static final int DOMAIN = 3;
    private static final int IPV6 = 4;
    private static final int MAX_LOGIN_PART = 255;
    // The replies that say the proxy will never relay UDP for us: not allowed by its rules, command not supported.
    private static final int NOT_ALLOWED = 2;
    private static final int COMMAND_NOT_SUPPORTED = 7;

    private final Socket control;
    private final InetSocketAddress relay;
    private final CountDownLatch ended = new CountDownLatch(1);

    private Socks5Udp(Socket control, InetSocketAddress relay) {
        this.control = control;
        this.relay = relay;
    }

    /**
     * Asks the SOCKS proxy of {@code proxy} for a UDP association, from {@code local} (null: any address), waiting
     * {@code timeout} at most for each answer.
     *
     * @throws Unsupported when the proxy will not relay UDP for us; other {@link IOException}s may pass
     */
    public static Socks5Udp associate(ProxySettings proxy, InetAddress local, Duration timeout) throws IOException {
        if (proxy.type() != ProxySettings.Type.SOCKS) {
            throw new Unsupported("not a SOCKS proxy");
        }
        InetSocketAddress at = new InetSocketAddress(proxy.host(), proxy.port());
        if (at.isUnresolved()) {
            throw new UnknownHostException(proxy.host());
        }
        int millis = Math.toIntExact(timeout.toMillis());
        Socket socket = new Socket(Proxy.NO_PROXY);
        try {
            if (local != null) {
                socket.bind(new InetSocketAddress(local, 0));
            }
            socket.connect(at, millis);
            socket.setSoTimeout(millis);
            DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            OutputStream out = socket.getOutputStream();
            logIn(in, out, proxy);
            // 0.0.0.0:0: the address our datagrams come from is not known before the first one.
            out.write(new byte[] {VERSION, UDP_ASSOCIATE, 0, IPV4, 0, 0, 0, 0, 0, 0});
            out.flush();
            InetSocketAddress bound = reply(in);
            // A relay on the proxy's every address is reached where the proxy was.
            InetSocketAddress relay =
                    bound.getAddress().isAnyLocalAddress() || !(bound.getAddress() instanceof Inet4Address)
                            ? new InetSocketAddress(socket.getInetAddress(), bound.getPort())
                            : bound;
            socket.setSoTimeout(0);
            Socks5Udp association = new Socks5Udp(socket, relay);
            Thread.ofVirtual().name("njmule-socks-udp").start(association::watch);
            return association;
        } catch (IOException | RuntimeException e) {
            socket.close();
            throw e;
        }
    }

    private static void logIn(DataInputStream in, OutputStream out, ProxySettings proxy) throws IOException {
        boolean login = !proxy.user().isEmpty();
        out.write(login ? new byte[] {VERSION, 2, NO_LOGIN, USER_AND_PASSWORD} : new byte[] {VERSION, 1, NO_LOGIN});
        out.flush();
        int version = in.readUnsignedByte();
        int method = in.readUnsignedByte();
        if (version != VERSION) {
            throw new Unsupported("the proxy speaks no SOCKS5");
        }
        if (method == NO_LOGIN) {
            return;
        }
        if (method != USER_AND_PASSWORD || !login) {
            throw new Unsupported(
                    method == USER_AND_PASSWORD
                            ? "the proxy wants a user name and password"
                            : "the proxy takes none of our ways to log in");
        }
        byte[] user = proxy.user().getBytes(StandardCharsets.UTF_8);
        byte[] password = proxy.password().getBytes(StandardCharsets.UTF_8);
        if (user.length > MAX_LOGIN_PART || password.length > MAX_LOGIN_PART) {
            throw new Unsupported("the user name or password is too long for SOCKS5");
        }
        out.write(LOGIN_VERSION);
        out.write(user.length);
        out.write(user);
        out.write(password.length);
        out.write(password);
        out.flush();
        in.readUnsignedByte();
        if (in.readUnsignedByte() != 0) {
            throw new Unsupported("the proxy refused the user name or password");
        }
    }

    // The address the proxy binds for us, after a reply that grants the command.
    private static InetSocketAddress reply(DataInputStream in) throws IOException {
        if (in.readUnsignedByte() != VERSION) {
            throw new Unsupported("the proxy speaks no SOCKS5");
        }
        int status = in.readUnsignedByte();
        in.readUnsignedByte();
        int type = in.readUnsignedByte();
        if (status == NOT_ALLOWED || status == COMMAND_NOT_SUPPORTED) {
            throw new Unsupported("the proxy relays no UDP (SOCKS5 reply " + status + ")");
        }
        if (status != 0) {
            throw new IOException("the proxy refused the UDP relay (SOCKS5 reply " + status + ")");
        }
        InetAddress address = switch (type) {
            case IPV4 -> InetAddress.getByAddress(readBytes(in, 4));
            case IPV6 -> InetAddress.getByAddress(readBytes(in, 16));
            case DOMAIN ->
                InetAddress.getByName(new String(readBytes(in, in.readUnsignedByte()), StandardCharsets.US_ASCII));
            default -> throw new IOException("unknown address type " + type + " in a SOCKS5 reply");
        };
        return new InetSocketAddress(address, in.readUnsignedShort());
    }

    private static byte[] readBytes(DataInputStream in, int count) throws IOException {
        byte[] bytes = new byte[count];
        in.readFully(bytes);
        return bytes;
    }

    // The proxy ends the association by closing its connection; nothing else comes on it.
    private void watch() {
        try (InputStream in = this.control.getInputStream()) {
            while (in.read() >= 0) {
                // Ignored.
            }
        } catch (IOException e) {
            // Closed, here or by the proxy.
        } finally {
            this.ended.countDown();
        }
    }

    /** Where the datagrams go, and come from. */
    public InetSocketAddress relay() {
        return this.relay;
    }

    public boolean isOpen() {
        return this.ended.getCount() > 0;
    }

    /** Waits for the association to end, closed by the proxy or by {@link #close}. */
    public void awaitEnd() throws InterruptedException {
        this.ended.await();
    }

    @Override
    public void close() {
        try {
            this.control.close();
        } catch (IOException e) {
            // Nothing more to release.
        }
    }

    /** {@code data} for an IPv4 address, behind the header the relay reads. */
    public static byte[] wrap(InetSocketAddress to, byte[] data) {
        if (!(to.getAddress() instanceof Inet4Address v4)) {
            throw new IllegalArgumentException("not an IPv4 address: " + to);
        }
        byte[] datagram = new byte[IPV4_HEADER + data.length];
        datagram[3] = IPV4;
        System.arraycopy(v4.getAddress(), 0, datagram, 4, 4);
        datagram[8] = (byte) (to.getPort() >>> 8);
        datagram[9] = (byte) to.getPort();
        System.arraycopy(data, 0, datagram, IPV4_HEADER, data.length);
        return datagram;
    }

    /**
     * The sender and payload of a datagram from the relay; empty for a fragment, a sender that is not an IPv4 address
     * (eD2K and Kad know no other) or a datagram too short for its header.
     */
    public static Optional<UdpRoute.Received> unwrap(byte[] data, int offset, int length) {
        // RSV (2), FRAG, ATYP: only whole datagrams from IPv4 senders.
        if (length < IPV4_HEADER || data[offset + 2] != 0 || data[offset + 3] != IPV4) {
            return Optional.empty();
        }
        try {
            InetAddress sender = InetAddress.getByAddress(Arrays.copyOfRange(data, offset + 4, offset + 8));
            int port = (data[offset + 8] & 0xFF) << 8 | (data[offset + 9] & 0xFF);
            return Optional.of(new UdpRoute.Received(
                    new InetSocketAddress(sender, port), offset + IPV4_HEADER, length - IPV4_HEADER));
        } catch (UnknownHostException e) {
            return Optional.empty();
        }
    }
}
