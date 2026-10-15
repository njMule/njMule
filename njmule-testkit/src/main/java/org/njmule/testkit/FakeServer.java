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
import java.util.function.Function;
import org.njmule.core.api.Hash16;
import org.njmule.core.protocol.crypt.DatagramObfuscation;
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
 * An in-process eD2K server for tests: logs clients in with a chosen id, greets them, answers searches with fixed
 * results over TCP and, on the TCP port + 4, over UDP, and knows the sources of files it is given. It records what it
 * receives. Plain connections only.
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
    public static final int FLAGS = ServerMessages.TCP_COMPRESSION
            | ServerMessages.TCP_NEWTAGS
            | ServerMessages.TCP_UNICODE
            | ServerMessages.TCP_LARGEFILES;

    private static final Logger logger = LoggerFactory.getLogger(FakeServer.class);
    private static final int UDP_OFFSET = 4;
    private static final SecureRandom PORTS = new SecureRandom();
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Config config;
    private final ServerSocket tcp;
    private final DatagramSocket udp;
    private final List<ServerMessages.Login> logins = new CopyOnWriteArrayList<>();
    private final Map<Long, FakePeer> lowIds = new ConcurrentHashMap<>();
    private final List<Long> callbackRequests = new CopyOnWriteArrayList<>();
    private volatile List<ServerMessages.Endpoint> knownServers = List.of();
    private final List<SearchExpr> searches = new CopyOnWriteArrayList<>();
    private final List<SearchExpr> udpSearches = new CopyOnWriteArrayList<>();
    private final List<SearchExpr> obfuscatedUdpSearches = new CopyOnWriteArrayList<>();
    private final List<Hash16> udpSourceRequests = new CopyOnWriteArrayList<>();
    // Set once obfuscated datagrams are taken: their socket and the key the status gives for them.
    private volatile DatagramSocket obfuscatedUdp;
    private volatile int udpKey;
    private final List<FileEntry> offers = new CopyOnWriteArrayList<>();
    private final List<OutputStream> clients = new CopyOnWriteArrayList<>();
    private final Map<Hash16, List<ServerMessages.Source>> sources = new ConcurrentHashMap<>();
    private volatile Function<SearchExpr, List<FileEntry>> answers;
    private volatile boolean hasMore;
    private final List<SearchExpr> moreRequests = new CopyOnWriteArrayList<>();

    // The UDP socket listens on the TCP port + 4 of the same address.
    private FakeServer(Config config, ServerSocket tcp) throws SocketException {
        this.config = config;
        this.answers = expression -> config.results();
        this.tcp = tcp;
        this.udp = new DatagramSocket(new InetSocketAddress(tcp.getInetAddress(), tcp.getLocalPort() + UDP_OFFSET));
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
     * A server that answers every search with the files {@code names}, each of {@code size} bytes; for callers that see
     * only the public API.
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
        List<Tag> tags = List.of(
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

    /** Answers TCP searches with what {@code answers} finds for each, instead of the fixed results. */
    public void answerSearches(Function<SearchExpr, List<FileEntry>> answers) {
        this.answers = Objects.requireNonNull(answers, "answers");
    }

    /** Whether TCP search answers say that more results can be asked for. */
    public void announceMoreResults(boolean more) {
        this.hasMore = more;
    }

    /** The searches whose more results clients asked for: always the last one asked, as a server keeps no other. */
    public List<SearchExpr> moreRequests() {
        return List.copyOf(this.moreRequests);
    }

    public List<SearchExpr> udpSearches() {
        return List.copyOf(this.udpSearches);
    }

    /** The files whose sources were asked for over UDP, in order. */
    public List<Hash16> udpSourceRequests() {
        return List.copyOf(this.udpSourceRequests);
    }

    /** Searches that came obfuscated, on the obfuscated UDP port. */
    public List<SearchExpr> obfuscatedUdpSearches() {
        return List.copyOf(this.obfuscatedUdpSearches);
    }

    /**
     * From now on obfuscated datagrams are taken with {@code key} on a UDP port of their own, which the status answer
     * gives with the key; answers to them go back obfuscated.
     */
    public void takeObfuscatedUdp(int key) throws SocketException {
        DatagramSocket socket = new DatagramSocket(new InetSocketAddress(this.tcp.getInetAddress(), 0));
        this.udpKey = key;
        this.obfuscatedUdp = socket;
        Thread.ofVirtual().name("fake-server-obfuscated-udp").start(() -> obfuscatedUdpLoop(socket));
    }

    /** The UDP port obfuscated datagrams are taken on, 0 when they are not. */
    public int obfuscatedUdpPort() {
        DatagramSocket socket = this.obfuscatedUdp;
        return socket == null ? 0 : socket.getLocalPort();
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
                .add(new ServerMessages.Source(Ipv4.toEd2k(ip), peer.getPort(), 0, Optional.empty()));
    }

    /**
     * From now on, answers requests for the sources of {@code file} with {@code peer} as a firewalled client with the
     * LowID {@code lowId}, which calls back the downloader that asks for it through a callback request.
     */
    public void addLowIdSource(Hash16 file, long lowId, FakePeer peer) {
        this.lowIds.put(lowId, peer);
        this.sources
                .computeIfAbsent(file, f -> new CopyOnWriteArrayList<>())
                .add(new ServerMessages.Source(lowId, peer.port(), 0, Optional.empty()));
    }

    /** The servers sent to clients that ask for the server list. */
    public void knowsServers(List<ServerMessages.Endpoint> servers) {
        this.knownServers = List.copyOf(servers);
    }

    /** The LowIDs clients asked us to have call them back. */
    public List<Long> callbackRequests() {
        return List.copyOf(this.callbackRequests);
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
        DatagramSocket obfuscated = this.obfuscatedUdp;
        if (obfuscated != null) {
            obfuscated.close();
        }
        this.tcp.close();
    }

    private void acceptLoop() {
        while (!this.tcp.isClosed()) {
            try {
                Socket client = this.tcp.accept();
                Thread.ofVirtual().name("fake-server-client").start(() -> serve(client));
            } catch (IOException e) {
                if (!this.tcp.isClosed()) {
                    logger.warn("Fake server accept failed", e);
                }
            }
        }
    }

    private void serve(Socket client) {
        OutputStream out = null;
        try (client) {
            InputStream in = new BufferedInputStream(client.getInputStream());
            out = new BufferedOutputStream(client.getOutputStream());
            this.clients.add(out);
            while (true) {
                Packet request = Packing.unpack(TcpFraming.read(in, FrameLimit.fixed(1 << 20)), 1 << 22);
                synchronized (out) {
                    for (Packet answer : answer(request, client)) {
                        out.write(TcpFraming.encode(answer));
                    }
                    out.flush();
                }
            }
        } catch (IOException e) {
            logger.debug("Fake server client gone: {}", e.getMessage());
        } finally {
            // A client gone, or a connection closed at once as an obfuscated one is, gets no broadcast.
            if (out != null) {
                this.clients.remove(out);
            }
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
        if (request.opcode() == Opcodes.Server.GETSERVERLIST.code()) {
            return List.of(ServerMessages.serverList(this.knownServers));
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
            return known.isEmpty() ? List.of() : List.of(new ServerMessages.FoundSources(file, known).encode());
        }
        if (request.opcode() == Opcodes.Server.CALLBACKREQUEST.code()) {
            long lowId = request.reader().u32();
            this.callbackRequests.add(lowId);
            FakePeer peer = this.lowIds.get(lowId);
            int port = this.logins.isEmpty() ? 0 : this.logins.getLast().tcpPort();
            if (peer != null && port != 0) {
                peer.callAsLowId(
                        new InetSocketAddress(client.getInetAddress(), port),
                        lowId,
                        Ipv4.fromBytes(address().getAddress()),
                        port());
            }
            return List.of();
        }
        if (request.opcode() == Opcodes.Server.SEARCHREQUEST.code()) {
            SearchExpr expression = SearchTreeCodec.read(request.reader());
            this.searches.add(expression);
            return List.of(new ServerMessages.SearchResults(this.answers.apply(expression), this.hasMore).encode());
        }
        if (request.opcode() == Opcodes.Server.QUERY_MORE_RESULT.code() && !this.searches.isEmpty()) {
            SearchExpr last = this.searches.getLast();
            this.moreRequests.add(last);
            return List.of(new ServerMessages.SearchResults(this.answers.apply(last), false).encode());
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
                Packet answer = answerUdp(request, this.udpSearches);
                if (answer != null) {
                    byte[] bytes = UdpFraming.encode(answer);
                    this.udp.send(new DatagramPacket(bytes, bytes.length, datagram.getSocketAddress()));
                }
            } catch (IOException e) {
                if (!this.udp.isClosed()) {
                    logger.debug("Fake server UDP: {}", e.getMessage());
                }
            }
        }
    }

    private void obfuscatedUdpLoop(DatagramSocket socket) {
        byte[] buffer = new byte[8192];
        DatagramPacket datagram = new DatagramPacket(buffer, buffer.length);
        while (!socket.isClosed()) {
            try {
                datagram.setLength(buffer.length);
                socket.receive(datagram);
                DatagramObfuscation.Received received =
                        DatagramObfuscation.decodeAtServer(buffer, 0, datagram.getLength(), this.udpKey);
                if (received.kind() != DatagramObfuscation.Kind.SERVER) {
                    continue;
                }
                Packet answer = answerUdp(received.packet(), this.obfuscatedUdpSearches);
                if (answer != null) {
                    byte[] bytes =
                            DatagramObfuscation.encryptFromServer(UdpFraming.encode(answer), this.udpKey, RANDOM);
                    socket.send(new DatagramPacket(bytes, bytes.length, datagram.getSocketAddress()));
                }
            } catch (IOException e) {
                if (!socket.isClosed()) {
                    logger.debug("Fake server obfuscated UDP: {}", e.getMessage());
                }
            }
        }
    }

    private Packet answerUdp(Packet request, List<SearchExpr> searches) throws DecodeException {
        int opcode = request.opcode();
        if (opcode == Opcodes.ServerUdp.GLOBSERVSTATREQ.code()) {
            long challenge = request.reader().u32();
            int key = this.udpKey;
            return new ServerDatagrams.Status(
                            challenge,
                            10,
                            20,
                            100,
                            1000,
                            2000,
                            ServerDatagrams.UDP_EXT_GETSOURCES
                                    | ServerDatagrams.UDP_EXT_GETSOURCES2
                                    | (key == 0 ? 0 : ServerDatagrams.UDP_OBFUSCATION),
                            0,
                            obfuscatedUdpPort(),
                            0,
                            Integer.toUnsignedLong(key))
                    .encode();
        }
        PacketReader in = request.reader();
        if (opcode == Opcodes.ServerUdp.GLOBGETSOURCES.code() || opcode == Opcodes.ServerUdp.GLOBGETSOURCES2.code()) {
            return answerSources(in, opcode == Opcodes.ServerUdp.GLOBGETSOURCES2.code());
        }
        if (opcode == Opcodes.ServerUdp.GLOBSEARCHREQ3.code()) {
            TagCodec.readEd2kList(in, in.u32(), 16);
        } else if (opcode != Opcodes.ServerUdp.GLOBSEARCHREQ.code()
                && opcode != Opcodes.ServerUdp.GLOBSEARCHREQ2.code()) {
            return null;
        }
        searches.add(SearchTreeCodec.read(in));
        return this.config.results().isEmpty() ? null : ServerDatagrams.searchResults(this.config.results());
    }

    // The HighID sources of the files asked for that have some, in one answer.
    private Packet answerSources(PacketReader in, boolean sized) throws DecodeException {
        List<ServerMessages.FoundSources> found = new ArrayList<>();
        while (in.remaining() > 0) {
            Hash16 file = in.hash16();
            if (sized && in.u32() == 0) {
                in.u64();
            }
            this.udpSourceRequests.add(file);
            List<ServerMessages.Source> known = this.sources.getOrDefault(file, List.of()).stream()
                    .filter(source -> !source.isLowId())
                    .toList();
            if (!known.isEmpty()) {
                found.add(new ServerMessages.FoundSources(file, known));
            }
        }
        return found.isEmpty() ? null : ServerDatagrams.foundSources(found);
    }

    private static Hash16 hashOf(String name) {
        try {
            return Hash16.of(MessageDigest.getInstance("SHA-256").digest(name.getBytes(StandardCharsets.UTF_8)), 0);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
