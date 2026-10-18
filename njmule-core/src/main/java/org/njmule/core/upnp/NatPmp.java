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
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.PortUnreachableException;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import org.njmule.core.util.Bytes;

/**
 * A router that answers NAT-PMP (RFC 6886) or PCP (RFC 6887), its successor, on port 5351 of the default gateway. Asked
 * in NAT-PMP, a router that speaks PCP only answers in PCP, and is spoken to in it from then on. A mapping counts only
 * with our own port outside: the eD2K and Kad networks are told that one. At the IPv6 gateway, a PCP MAP from our IPv6
 * address, the same outside, opens the router's IPv6 firewall to it.
 */
public final class NatPmp implements Gateway {

    /** The port routers answer on. */
    public static final int PORT = 5351;

    // RFC 6886 §3.1: the first try waits 250 ms, each other twice as long as the one before.
    private static final Duration FIRST_WAIT = Duration.ofMillis(250);
    private static final int TRIES = 4;

    private static final byte NAT_PMP_VERSION = 0;
    private static final byte PCP_VERSION = 2;
    private static final int RESPONSE = 0x80;
    private static final int EXTERNAL_ADDRESS = 0;
    private static final int MAP_UDP = 1;
    private static final int MAP_TCP = 2;
    private static final int PCP_MAP = 1;
    private static final int PCP_TCP = 6;
    private static final int PCP_UDP = 17;
    private static final int NONCE = 12;
    private static final int PCP_MAP_LENGTH = 60;
    private static final int NAT_PMP_MAP_ANSWER = 16;

    // NAT-PMP results that mean the router will not map: not authorized, out of resources, unsupported opcode.
    private static final String[] NAT_PMP_RESULTS = {
        "success", "unsupported version", "not authorized", "network failure", "out of resources", "unsupported opcode"
    };
    private static final String[] PCP_RESULTS = {
        "success",
        "unsupported version",
        "not authorized",
        "malformed request",
        "unsupported opcode",
        "unsupported option",
        "malformed option",
        "network failure",
        "no resources",
        "unsupported protocol",
        "user over quota",
        "cannot provide the external port",
        "address mismatch",
        "excessive remote peers"
    };

    private final InetSocketAddress server;
    private final boolean pcp;
    private final SecureRandom random;
    // PCP: the nonce of each mapping ("TCP 4662"), which renewing or removing it repeats.
    private final Map<String, byte[]> nonces = new ConcurrentHashMap<>();
    // PCP at the IPv6 gateway: the pinholes opened, by id.
    private final Map<Integer, Pinhole> pinholes = new ConcurrentHashMap<>();
    private final AtomicInteger pinholeIds = new AtomicInteger(1);

    private record Pinhole(String protocol, Inet6Address address, int port, Bytes nonce) {}

    private NatPmp(InetSocketAddress server, boolean pcp, SecureRandom random) {
        this.server = server;
        this.pcp = pcp;
        this.random = random;
    }

    /** The router at {@code server}, if it answers NAT-PMP or PCP within about {@code wait}. */
    public static Optional<NatPmp> discover(InetSocketAddress server, Duration wait) throws IOException {
        Objects.requireNonNull(server, "server");
        byte[] request = {NAT_PMP_VERSION, EXTERNAL_ADDRESS};
        Optional<byte[]> answer = exchange(
                server,
                Optional.empty(),
                local -> request,
                a -> a.length >= 2 && (a[0] == PCP_VERSION || (a[0] == NAT_PMP_VERSION && a[1] == (byte) RESPONSE)),
                triesWithin(wait));
        if (answer.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new NatPmp(server, answer.get()[0] == PCP_VERSION, new SecureRandom()));
    }

    // As many tries as fit in wait, at least one.
    private static int triesWithin(Duration wait) {
        int tries = 1;
        long total = FIRST_WAIT.toMillis();
        while (tries < TRIES && total + (FIRST_WAIT.toMillis() << tries) <= wait.toMillis()) {
            total += FIRST_WAIT.toMillis() << tries;
            tries++;
        }
        return tries;
    }

    @Override
    public String protocol() {
        return this.pcp ? "PCP" : "NAT-PMP";
    }

    @Override
    public String host() {
        return this.server.getAddress().getHostAddress();
    }

    @Override
    public long addMapping(String protocol, int port, String description, long lease) throws IOException {
        return this.pcp ? pcpMap(protocol, port, lease) : natPmpMap(protocol, port, lease, port);
    }

    @Override
    public void deleteMapping(String protocol, int port) throws IOException {
        if (this.pcp) {
            pcpMap(protocol, port, 0);
            this.nonces.remove(protocol + " " + port);
        } else {
            natPmpMap(protocol, port, 0, 0);
        }
    }

    /** As {@link Gateway#openPinhole}: a router at an IPv6 gateway that speaks PCP opens its IPv6 firewall. */
    @Override
    public OptionalInt openPinhole(String protocol, Inet6Address address, int port, long lease) throws IOException {
        if (!this.pcp || !(this.server.getAddress() instanceof Inet6Address)) {
            return OptionalInt.empty();
        }
        byte[] nonce = new byte[NONCE];
        this.random.nextBytes(nonce);
        Pinhole pinhole = new Pinhole(protocol, address, port, Bytes.of(nonce));
        pcpPinhole(pinhole, lease);
        int id = this.pinholeIds.getAndIncrement();
        this.pinholes.put(id, pinhole);
        return OptionalInt.of(id);
    }

    @Override
    public void renewPinhole(int id, long lease) throws IOException {
        Pinhole pinhole = this.pinholes.get(id);
        if (pinhole == null) {
            throw new IOException("no pinhole " + id);
        }
        pcpPinhole(pinhole, lease);
    }

    @Override
    public void closePinhole(int id) throws IOException {
        Pinhole pinhole = this.pinholes.remove(id);
        if (pinhole != null) {
            pcpPinhole(pinhole, 0);
        }
    }

    // The same address and port outside as inside: only the firewall opens.
    private void pcpPinhole(Pinhole pinhole, long lease) throws IOException {
        int transport = "TCP".equals(pinhole.protocol()) ? PCP_TCP : PCP_UDP;
        byte[] ours = pinhole.address().getAddress();
        byte[] nonce = pinhole.nonce().toByteArray();
        ByteBuffer answer = ByteBuffer.wrap(exchange(
                        this.server,
                        Optional.of(pinhole.address()),
                        local -> ByteBuffer.allocate(PCP_MAP_LENGTH)
                                .put(PCP_VERSION)
                                .put((byte) PCP_MAP)
                                .putShort((short) 0)
                                .putInt((int) lease)
                                .put(ours)
                                .put(nonce)
                                .put((byte) transport)
                                .put(new byte[3])
                                .putShort((short) pinhole.port())
                                .putShort((short) pinhole.port())
                                .put(ours)
                                .array(),
                        a -> a.length >= PCP_MAP_LENGTH
                                && a[0] == PCP_VERSION
                                && (a[1] & 0xFF) == (RESPONSE | PCP_MAP)
                                && Arrays.equals(a, 24, 24 + NONCE, nonce, 0, NONCE),
                        TRIES)
                .orElseThrow(() -> new SocketTimeoutException("PCP: the router at " + host() + " did not answer")));
        int result = answer.get(3) & 0xFF;
        if (result != 0) {
            String reason = result < PCP_RESULTS.length ? PCP_RESULTS[result] : "result " + result;
            throw result == 2 || (result >= 8 && result <= 11)
                    ? new Refused(reason)
                    : new IOException("PCP: " + reason);
        }
    }

    @Override
    public void close() {
        // Nothing held between requests.
    }

    private long natPmpMap(String protocol, int port, long lease, int externalPort) throws IOException {
        int opcode = "TCP".equals(protocol) ? MAP_TCP : MAP_UDP;
        byte[] request = ByteBuffer.allocate(12)
                .put(NAT_PMP_VERSION)
                .put((byte) opcode)
                .putShort((short) 0)
                .putShort((short) port)
                .putShort((short) externalPort)
                .putInt((int) lease)
                .array();
        ByteBuffer answer = ByteBuffer.wrap(answer(
                local -> request,
                a -> a.length >= NAT_PMP_MAP_ANSWER
                        && a[0] == NAT_PMP_VERSION
                        && (a[1] & 0xFF) == (RESPONSE | opcode)
                        && ((a[8] & 0xFF) << 8 | (a[9] & 0xFF)) == port));
        int result = answer.getShort(2) & 0xFFFF;
        if (result != 0) {
            String reason = result < NAT_PMP_RESULTS.length ? NAT_PMP_RESULTS[result] : "result " + result;
            throw result == 2 || result == 4 || result == 5
                    ? new Refused(reason)
                    : new IOException("NAT-PMP: " + reason);
        }
        int mapped = answer.getShort(10) & 0xFFFF;
        long granted = Integer.toUnsignedLong(answer.getInt(12));
        if (lease > 0 && mapped != port) {
            natPmpMap(protocol, port, 0, 0);
            throw new Refused("the router gave outside port " + mapped + " for " + port);
        }
        return granted;
    }

    private long pcpMap(String protocol, int port, long lease) throws IOException {
        byte[] nonce = this.nonces.computeIfAbsent(protocol + " " + port, key -> {
            byte[] fresh = new byte[NONCE];
            this.random.nextBytes(fresh);
            return fresh;
        });
        int transport = "TCP".equals(protocol) ? PCP_TCP : PCP_UDP;
        ByteBuffer answer = ByteBuffer.wrap(answer(
                local -> ByteBuffer.allocate(PCP_MAP_LENGTH)
                        .put(PCP_VERSION)
                        .put((byte) PCP_MAP)
                        .putShort((short) 0)
                        .putInt((int) lease)
                        .put(mapped(local))
                        .put(nonce)
                        .put((byte) transport)
                        .put(new byte[3])
                        .putShort((short) port)
                        .putShort((short) port)
                        // Any outside address: ::ffff:0.0.0.0.
                        .put(mapped(null))
                        .array(),
                a -> a.length >= PCP_MAP_LENGTH
                        && a[0] == PCP_VERSION
                        && (a[1] & 0xFF) == (RESPONSE | PCP_MAP)
                        && Arrays.equals(a, 24, 24 + NONCE, nonce, 0, NONCE)));
        int result = answer.get(3) & 0xFF;
        if (result != 0) {
            String reason = result < PCP_RESULTS.length ? PCP_RESULTS[result] : "result " + result;
            throw result == 2 || (result >= 8 && result <= 11)
                    ? new Refused(reason)
                    : new IOException("PCP: " + reason);
        }
        long granted = Integer.toUnsignedLong(answer.getInt(4));
        int mapped = answer.getShort(42) & 0xFFFF;
        if (lease > 0 && mapped != port) {
            pcpMap(protocol, port, 0);
            this.nonces.remove(protocol + " " + port);
            throw new Refused("the router gave outside port " + mapped + " for " + port);
        }
        return granted;
    }

    // An IPv4 address as PCP writes it, IPv4-mapped; null for 0.0.0.0.
    private static byte[] mapped(InetAddress v4) {
        byte[] address = new byte[16];
        address[10] = (byte) 0xFF;
        address[11] = (byte) 0xFF;
        if (v4 != null) {
            System.arraycopy(v4.getAddress(), 0, address, 12, 4);
        }
        return address;
    }

    private byte[] answer(Request request, Predicate<byte[]> fits) throws IOException {
        return exchange(this.server, Optional.empty(), request, fits, TRIES)
                .orElseThrow(
                        () -> new SocketTimeoutException(protocol() + ": the router at " + host() + " did not answer"));
    }

    /** A request built for our address towards the router, which PCP writes in it. */
    @FunctionalInterface
    private interface Request {
        byte[] bytes(InetAddress local);
    }

    // Sends the request again after 250 ms, 500 ms, ...; only an answer from the router that fits counts. A request
    // about an address of ours goes from it, which PCP checks.
    private static Optional<byte[]> exchange(
            InetSocketAddress server, Optional<InetAddress> from, Request request, Predicate<byte[]> fits, int tries)
            throws IOException {
        try (DatagramSocket socket =
                from.isPresent() ? new DatagramSocket(new InetSocketAddress(from.get(), 0)) : new DatagramSocket()) {
            socket.connect(server);
            InetAddress local = socket.getLocalAddress();
            if (from.isEmpty() && !(local instanceof Inet4Address) && !(server.getAddress() instanceof Inet6Address)) {
                throw new IOException("no IPv4 address towards " + server);
            }
            byte[] bytes = request.bytes(local);
            byte[] buffer = new byte[1100];
            long wait = FIRST_WAIT.toMillis();
            for (int i = 0; i < tries; i++, wait *= 2) {
                socket.send(new DatagramPacket(bytes, bytes.length));
                long deadline = System.nanoTime() + wait * 1_000_000;
                while (true) {
                    long left = (deadline - System.nanoTime()) / 1_000_000;
                    if (left <= 0) {
                        break;
                    }
                    socket.setSoTimeout((int) left);
                    DatagramPacket in = new DatagramPacket(buffer, buffer.length);
                    try {
                        socket.receive(in);
                    } catch (SocketTimeoutException e) {
                        break;
                    } catch (PortUnreachableException e) {
                        // Nothing listens there.
                        return Optional.empty();
                    }
                    byte[] got = Arrays.copyOf(buffer, in.getLength());
                    if (fits.test(got)) {
                        return Optional.of(got);
                    }
                }
            }
            return Optional.empty();
        }
    }
}
