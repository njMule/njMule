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
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import org.njmule.core.config.ProxySettings;

/**
 * A UDP association through a SOCKS5 proxy (RFC 1928 §7): datagrams go to the proxy's relay behind a header that names
 * their destination and come back behind one that names their sender. It lasts as long as the TCP connection that asked
 * for it. Our own code, since the JDK and Jetty carry only TCP through SOCKS.
 */
public final class Socks5Udp implements AutoCloseable {

    /** The header of a datagram to or from an IPv4 address. */
    static final int IPV4_HEADER = 10;

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
     * @throws Socks5.Unsupported when the proxy will not relay UDP for us; other {@link IOException}s may pass
     */
    public static Socks5Udp associate(ProxySettings proxy, InetAddress local, Duration timeout) throws IOException {
        Socket socket = Socks5.open(proxy, local, Math.toIntExact(timeout.toMillis()));
        try {
            // 0.0.0.0:0: the address our datagrams come from is not known before the first one.
            InetSocketAddress bound = Socks5.request(
                    socket, Socks5.UDP_ASSOCIATE, new InetSocketAddress(InetAddress.getByAddress(new byte[4]), 0));
            // A relay on the proxy's every address, or one it names, is reached where the proxy was.
            InetSocketAddress relay =
                    Socks5.usable(bound) ? bound : new InetSocketAddress(socket.getInetAddress(), bound.getPort());
            socket.setSoTimeout(0);
            Socks5Udp association = new Socks5Udp(socket, relay);
            Thread.ofVirtual().name("njmule-socks-udp").start(association::watch);
            return association;
        } catch (IOException | RuntimeException e) {
            socket.close();
            throw e;
        }
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
        datagram[3] = Socks5.IPV4;
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
        if (length < IPV4_HEADER || data[offset + 2] != 0 || data[offset + 3] != Socks5.IPV4) {
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
