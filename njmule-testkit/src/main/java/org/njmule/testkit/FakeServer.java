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
package org.njmule.testkit;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.BindException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.njmule.core.api.Hash16;
import org.njmule.core.protocol.ed2k.FileEntry;
import org.njmule.core.protocol.ed2k.ServerDatagrams;
import org.njmule.core.protocol.ed2k.ServerMessages;
import org.njmule.core.protocol.io.DecodeException;
import org.njmule.core.protocol.io.PacketReader;
import org.njmule.core.protocol.packet.FrameLimit;
import org.njmule.core.protocol.packet.Opcodes;
import org.njmule.core.protocol.packet.Packet;
import org.njmule.core.protocol.packet.Packing;
import org.njmule.core.protocol.packet.TcpFraming;
import org.njmule.core.protocol.packet.UdpFraming;
import org.njmule.core.protocol.tag.FileTag;
import org.njmule.core.protocol.tag.Tag;
import org.njmule.core.protocol.tag.TagCodec;
import org.njmule.core.protocol.tag.TagName;
import org.njmule.core.search.SearchExpr;
import org.njmule.core.search.SearchTreeCodec;
import org.njmule.core.util.Ipv4;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * An in-process eD2K server for tests: logs clients in with a chosen id, greets them, answers
 * searches with fixed results over TCP and, on the TCP port + 4, over UDP, and knows the sources of
 * files it is given. It records what it receives. Plain connections only.
 */
public final class FakeServer implements AutoCloseable {

    /** What the server answers. */
    public record Config(long clientId, String welcome, List<FileEntry> results) {
        public Config {
            Objects.requireNonNull(welcome, "welcome");
            results = List.copyOf(results);
        }
    }

    /** Server flags announced with the id. */
    public static final int FLAGS =
            ServerMessages.TCP_COMPRESSION
                    | ServerMessages.TCP_NEWTAGS
                    | ServerMessages.TCP_UNICODE
                    | ServerMessages.TCP_LARGEFILES;

    private static final Logger LOG = LoggerFactory.getLogger(FakeServer.class);
    private static final int UDP_OFFSET = 4;
    private static final SecureRandom PORTS = new SecureRandom();

    private final Config config;
    private final ServerSocket tcp;
    private final DatagramSocket udp;
    private final List<ServerMessages.Login> logins = new CopyOnWriteArrayList<>();
    private final List<SearchExpr> searches = new CopyOnWriteArrayList<>();
    private final List<SearchExpr> udpSearches = new CopyOnWriteArrayList<>();
    private final List<FileEntry> offers = new CopyOnWriteArrayList<>();
    private final List<OutputStream> clients = new CopyOnWriteArrayList<>();
    private final Map<Hash16, List<ServerMessages.Source>> sources = new ConcurrentHashMap<>();

    // The UDP socket listens on the TCP port + 4 of the same address.
    private FakeServer(Config config, ServerSocket tcp) throws SocketException {
        this.config = config;
        this.tcp = tcp;
        this.udp =
                new DatagramSocket(
                        new InetSocketAddress(
                                tcp.getInetAddress(), tcp.getLocalPort() + UDP_OFFSET));
    }

    /** Listens on {@code address}, on a free TCP port whose + 4 UDP port is free too. */
    public static FakeServer start(InetAddress address, Config config) throws IOException {
        // Random ports below the ephemeral range: the system hands out ephemeral ports in a row,
        // and on Windows whole blocks of them may be reserved for UDP.
        for (int attempt = 0; attempt < 50; attempt++) {
            ServerSocket tcp;
            try {
                tcp = new ServerSocket(20_000 + PORTS.nextInt(20_000), 16, address);
            } catch (BindException e) {
                continue;
            }
            FakeServer server;
            try {
                server = new FakeServer(config, tcp);
            } catch (BindException e) {
                tcp.close();
                continue;
            }
            Thread.ofVirtual().name("fake-server-tcp").start(server::acceptLoop);
            Thread.ofVirtual().name("fake-server-udp").start(server::udpLoop);
            return server;
        }
        throw new BindException("no free TCP/UDP port pair");
    }

    /**
     * A server that answers every search with the files {@code names}, each of {@code size} bytes;
     * for callers that see only the public API.
     */
    public static Config finding(long clientId, String welcome, long size, String... names) {
        List<FileEntry> files = new ArrayList<>();
        for (String name : names) {
            files.add(file(name, size, 5));
        }
        return new Config(clientId, welcome, files);
    }

    /** A search result with a hash derived from the name. */
    public static FileEntry file(String name, long size, int sources) {
        List<Tag> tags =
                List.of(
                        new Tag.StringTag(TagName.id(FileTag.FILENAME), name),
                        new Tag.IntTag(TagName.id(FileTag.FILESIZE), size),
                        new Tag.IntTag(TagName.id(FileTag.SOURCES), sources),
                        new Tag.IntTag(TagName.id(FileTag.COMPLETE_SOURCES), sources));
        return new FileEntry(hashOf(name), 0, 0, tags);
    }

    public InetAddress address() {
        return this.tcp.getInetAddress();
    }

    public int port() {
        return this.tcp.getLocalPort();
    }

    public List<ServerMessages.Login> logins() {
        return List.copyOf(this.logins);
    }

    public List<SearchExpr> searches() {
        return List.copyOf(this.searches);
    }

    public List<SearchExpr> udpSearches() {
        return List.copyOf(this.udpSearches);
    }

    /** The files clients offered, in order. */
    public List<FileEntry> offers() {
        return List.copyOf(this.offers);
    }

    /** From now on, answers requests for the sources of {@code file} with {@code peer}. */
    public void addSource(Hash16 file, InetSocketAddress peer) {
        int ip = Ipv4.fromBytes(peer.getAddress().getAddress());
        this.sources
                .computeIfAbsent(file, f -> new CopyOnWriteArrayList<>())
                .add(
                        new ServerMessages.Source(
                                Ipv4.toEd2k(ip), peer.getPort(), 0, Optional.empty()));
    }

    /** Sends {@code packet} to every logged-in client. */
    public void broadcast(Packet packet) throws IOException {
        byte[] frame = TcpFraming.encode(packet);
        for (OutputStream client : this.clients) {
            synchronized (client) {
                client.write(frame);
                client.flush();
            }
        }
    }

    @Override
    public void close() throws IOException {
        this.udp.close();
        this.tcp.close();
    }

    private void acceptLoop() {
        while (!this.tcp.isClosed()) {
            try {
                Socket client = this.tcp.accept();
                Thread.ofVirtual().name("fake-server-client").start(() -> this.serve(client));
            } catch (IOException e) {
                if (!this.tcp.isClosed()) {
                    LOG.warn("Fake server accept failed", e);
                }
            }
        }
    }

    private void serve(Socket client) {
        try (client) {
            InputStream in = new BufferedInputStream(client.getInputStream());
            OutputStream out = new BufferedOutputStream(client.getOutputStream());
            this.clients.add(out);
            while (true) {
                Packet request =
                        Packing.unpack(TcpFraming.read(in, FrameLimit.fixed(1 << 20)), 1 << 22);
                synchronized (out) {
                    for (Packet answer : this.answer(request, client)) {
                        out.write(TcpFraming.encode(answer));
                    }
                    out.flush();
                }
            }
        } catch (IOException e) {
            LOG.debug("Fake server client gone: {}", e.getMessage());
        }
    }

    private List<Packet> answer(Packet request, Socket client) throws DecodeException {
        if (request.opcode() == Opcodes.Server.LOGINREQUEST.code()) {
            this.logins.add(ServerMessages.Login.decode(request));
            int seen = Ipv4.fromBytes(client.getInetAddress().getAddress());
            return List.of(
                    new ServerMessages.IdChange(this.config.clientId(), FLAGS, seen).encode(),
                    ServerMessages.message(this.config.welcome()),
                    new ServerMessages.ServerStatus(10, 20).encode());
        }
        if (request.opcode() == Opcodes.Server.OFFERFILES.code()) {
            PacketReader in = request.reader();
            long count = in.u32();
            for (long i = 0; i < count; i++) {
                this.offers.add(FileEntry.read(in));
            }
            return List.of();
        }
        if (request.opcode() == Opcodes.Server.GETSOURCES.code()
                || request.opcode() == Opcodes.Server.GETSOURCES_OBFU.code()) {
            Hash16 file = request.reader().hash16();
            List<ServerMessages.Source> known = this.sources.getOrDefault(file, List.of());
            return known.isEmpty()
                    ? List.of()
                    : List.of(new ServerMessages.FoundSources(file, known).encode());
        }
        if (request.opcode() == Opcodes.Server.SEARCHREQUEST.code()) {
            this.searches.add(SearchTreeCodec.read(request.reader()));
            return List.of(new ServerMessages.SearchResults(this.config.results(), false).encode());
        }
        return List.of();
    }

    private void udpLoop() {
        byte[] buffer = new byte[8192];
        DatagramPacket datagram = new DatagramPacket(buffer, buffer.length);
        while (!this.udp.isClosed()) {
            try {
                datagram.setLength(buffer.length);
                this.udp.receive(datagram);
                Packet request = UdpFraming.decode(buffer, 0, datagram.getLength());
                Packet answer = this.answerUdp(request);
                if (answer != null) {
                    byte[] bytes = UdpFraming.encode(answer);
                    this.udp.send(
                            new DatagramPacket(bytes, bytes.length, datagram.getSocketAddress()));
                }
            } catch (IOException e) {
                if (!this.udp.isClosed()) {
                    LOG.debug("Fake server UDP: {}", e.getMessage());
                }
            }
        }
    }

    private Packet answerUdp(Packet request) throws DecodeException {
        int opcode = request.opcode();
        if (opcode == Opcodes.ServerUdp.GLOBSERVSTATREQ.code()) {
            long challenge = request.reader().u32();
            return new ServerDatagrams.Status(challenge, 10, 20, 100, 1000, 2000, 0, 0, 0, 0, 0)
                    .encode();
        }
        PacketReader in = request.reader();
        if (opcode == Opcodes.ServerUdp.GLOBSEARCHREQ3.code()) {
            TagCodec.readEd2kList(in, in.u32(), 16);
        } else if (opcode != Opcodes.ServerUdp.GLOBSEARCHREQ.code()
                && opcode != Opcodes.ServerUdp.GLOBSEARCHREQ2.code()) {
            return null;
        }
        this.udpSearches.add(SearchTreeCodec.read(in));
        return this.config.results().isEmpty()
                ? null
                : ServerDatagrams.searchResults(this.config.results());
    }

    private static Hash16 hashOf(String name) {
        try {
            return Hash16.of(
                    MessageDigest.getInstance("SHA-256")
                            .digest(name.getBytes(StandardCharsets.UTF_8)),
                    0);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
