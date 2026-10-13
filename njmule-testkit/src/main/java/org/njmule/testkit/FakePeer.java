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
import java.io.RandomAccessFile;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import org.njmule.core.api.Ed2kLink;
import org.njmule.core.api.FileHashing;
import org.njmule.core.api.Hash16;
import org.njmule.core.protocol.ed2k.ClientMessages;
import org.njmule.core.protocol.ed2k.Hello;
import org.njmule.core.protocol.io.PacketWriter;
import org.njmule.core.protocol.packet.FrameLimit;
import org.njmule.core.protocol.packet.Opcodes;
import org.njmule.core.protocol.packet.Packet;
import org.njmule.core.protocol.packet.Packing;
import org.njmule.core.protocol.packet.Protocol;
import org.njmule.core.protocol.packet.TcpFraming;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * An in-process client for tests that shares one file: it answers hellos and file requests, keeps downloaders queued or
 * gives them a slot (also by calling them back), and sends the blocks they ask for, plain or compressed. It can end
 * upload sessions early and send a corrupt copy of chosen parts once. Plain connections only.
 */
public final class FakePeer implements AutoCloseable {

    /** How a downloader gets its upload slot. */
    public enum Slot {
        /** At once. */
        NOW,
        /** After a short wait in the queue, on the same connection. */
        AFTER_QUEUE,
        /** The connection closes while queued; the peer connects back to give the slot. */
        CALL_BACK
    }

    /** How the peer behaves; a positive {@code sessionLimit} ends upload sessions early. */
    public record Config(Hash16 userHash, boolean compress, Slot slot, long sessionLimit, Set<Integer> corruptParts) {
        public Config {
            Objects.requireNonNull(userHash, "userHash");
            Objects.requireNonNull(slot, "slot");
            corruptParts = Set.copyOf(corruptParts);
        }

        /** Gives a slot at once and sends compressed blocks, as most peers do. */
        public static Config simple(Hash16 userHash) {
            return new Config(userHash, true, Slot.NOW, 0, Set.of());
        }
    }

    private static final Logger logger = LoggerFactory.getLogger(FakePeer.class);
    private static final int CHUNK = 10_240;
    private static final long SLOT_DELAY_MILLIS = 300;

    private final Path file;
    private final Ed2kLink.FileLink link;
    private final Config config;
    private final ServerSocket tcp;
    private volatile long lowId;
    private volatile int serverIp;
    private volatile int serverPort;
    private final List<String> received = new CopyOnWriteArrayList<>();
    private final Set<Integer> corrupted = ConcurrentHashMap.newKeySet();
    private final AtomicLong sent = new AtomicLong();

    private FakePeer(Path file, Ed2kLink.FileLink link, Config config, ServerSocket tcp) {
        this.file = file;
        this.link = link;
        this.config = config;
        this.tcp = tcp;
    }

    /** Hashes {@code file} and shares it on a free port of {@code address}. */
    public static FakePeer start(InetAddress address, Path file, Config config) throws IOException {
        Ed2kLink.FileLink link = FileHashing.link(file, bytes -> {});
        FakePeer peer = new FakePeer(file, link, config, new ServerSocket(0, 16, address));
        Thread.ofVirtual().name("fake-peer-accept").start(peer::acceptLoop);
        return peer;
    }

    public int port() {
        return this.tcp.getLocalPort();
    }

    /** The file's link, with this peer as its source. */
    public Ed2kLink.FileLink link() {
        return new Ed2kLink.FileLink(
                this.link.name(),
                this.link.size(),
                this.link.hash(),
                this.link.aichHash(),
                List.of(),
                List.of(new Ed2kLink.Source(this.tcp.getInetAddress().getHostAddress(), this.port())),
                List.of());
    }

    /** The names of the opcodes received, in order. */
    public List<String> received() {
        return List.copyOf(this.received);
    }

    /**
     * Connects to {@code downloader} as a firewalled client with the LowID {@code lowId} on the server at
     * {@code serverIp:serverPort} does when asked to call back; then serves as usual.
     */
    public void callAsLowId(InetSocketAddress downloader, long lowId, int serverIp, int serverPort) {
        this.lowId = lowId;
        this.serverIp = serverIp;
        this.serverPort = serverPort;
        Thread.ofVirtual().name("fake-peer-lowid").start(() -> {
            try {
                this.serve(new Socket(downloader.getAddress(), downloader.getPort()), Answer.HELLO_ONLY);
            } catch (IOException e) {
                logger.debug("Cannot call as a LowID: {}", e.getMessage());
            }
        });
    }

    /** File bytes sent, before compression. */
    public long bytesSent() {
        return this.sent.get();
    }

    @Override
    public void close() throws IOException {
        this.tcp.close();
    }

    private void acceptLoop() {
        while (!this.tcp.isClosed()) {
            try {
                Socket client = this.tcp.accept();
                Thread.ofVirtual().name("fake-peer-client").start(() -> this.serve(client));
            } catch (IOException e) {
                if (!this.tcp.isClosed()) {
                    logger.warn("Fake peer accept failed", e);
                }
            }
        }
    }

    private void serve(Socket client) {
        this.serve(client, Answer.NONE);
    }

    private void serve(Socket client, boolean calledBack) {
        this.serve(client, calledBack ? Answer.SLOT : Answer.NONE);
    }

    // What an outgoing connection says first: nothing (it was accepted), a hello and then a slot
    // (a call back with a slot), or a hello only (a LowID calling back on request).
    private enum Answer {
        NONE,
        SLOT,
        HELLO_ONLY
    }

    private void serve(Socket client, Answer answer) {
        boolean calledBack = answer == Answer.SLOT;
        try (client;
                RandomAccessFile data = new RandomAccessFile(this.file.toFile(), "r")) {
            InputStream in = new BufferedInputStream(client.getInputStream());
            Session session = new Session(client, data, calledBack);
            if (answer != Answer.NONE) {
                session.send(session.hello().encode(false));
            }
            while (!client.isClosed()) {
                session.handle(TcpFraming.read(in, FrameLimit.fixed(1 << 20)));
            }
        } catch (IOException e) {
            logger.debug("Fake peer client gone: {}", e.getMessage());
        }
    }

    // Connects to the downloader's listening port, as a peer with a free slot does.
    private void callBack(InetAddress address, int port) {
        Thread.ofVirtual().name("fake-peer-callback").start(() -> {
            try {
                Thread.sleep(SLOT_DELAY_MILLIS);
                this.serve(new Socket(address, port), true);
            } catch (IOException e) {
                logger.debug("Cannot call back: {}", e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    private static String name(Packet packet) {
        if (packet.protocol() == Protocol.EMULE) {
            return Opcodes.Extended.of(packet.opcode()).map(Enum::name).orElse("?");
        }
        return Opcodes.Client.of(packet.opcode()).map(Enum::name).orElse("?");
    }

    /** One downloader's connection. */
    private final class Session {
        private final Socket socket;
        private final OutputStream out;
        private final RandomAccessFile data;
        private final boolean calledBack;
        private int downloaderPort;
        private long sessionBytes;

        Session(Socket socket, RandomAccessFile data, boolean calledBack) throws IOException {
            this.socket = socket;
            this.out = new BufferedOutputStream(socket.getOutputStream());
            this.data = data;
            this.calledBack = calledBack;
        }

        void handle(Packet packet) throws IOException {
            FakePeer.this.received.add(name(packet));
            Hash16 hash = FakePeer.this.link.hash();
            if (packet.protocol() == Protocol.EMULE && packet.opcode() == Opcodes.Extended.REQUESTPARTS_I64.code()) {
                this.blocks(ClientMessages.RequestParts.decode(packet));
                return;
            }
            if (packet.protocol() != Protocol.EDONKEY) {
                return;
            }
            Opcodes.Client opcode = Opcodes.Client.of(packet.opcode()).orElse(null);
            if (opcode == null) {
                return;
            }
            switch (opcode) {
                case HELLO -> {
                    this.downloaderPort = Hello.decode(packet).tcpPort();
                    this.send(this.hello().encode(true));
                }
                case HELLOANSWER -> {
                    if (this.calledBack) {
                        this.send(Packet.empty(Protocol.EDONKEY, Opcodes.Client.ACCEPTUPLOADREQ.code()));
                    }
                }
                case REQUESTFILENAME ->
                    this.send(new ClientMessages.FileName(hash, FakePeer.this.link.name()).encode());
                case SETREQFILEID -> this.send(new ClientMessages.FileStatus(hash, 0, new BitSet()).encode());
                case HASHSETREQUEST ->
                    this.send(new ClientMessages.HashSet(hash, FakePeer.this.link.partHashes()).encode());
                case STARTUPLOADREQ -> {
                    Slot slot = this.calledBack ? Slot.NOW : FakePeer.this.config.slot();
                    if (slot != Slot.NOW) {
                        this.send(Packet.of(
                                Protocol.EMULE,
                                Opcodes.Extended.QUEUERANKING.code(),
                                new PacketWriter().u16(1).bytes(new byte[10])));
                    }
                    if (slot == Slot.AFTER_QUEUE) {
                        this.slotLater();
                    } else if (slot == Slot.CALL_BACK) {
                        FakePeer.this.callBack(this.socket.getInetAddress(), this.downloaderPort);
                        this.socket.close();
                    } else {
                        this.send(Packet.empty(Protocol.EDONKEY, Opcodes.Client.ACCEPTUPLOADREQ.code()));
                    }
                }
                case REQUESTPARTS -> this.blocks(ClientMessages.RequestParts.decode(packet));
                default -> {
                    // Cancels and the end of the download need no answer.
                }
            }
        }

        Hello hello() {
            return Hello.of(
                    FakePeer.this.config.userHash(),
                    FakePeer.this.lowId,
                    FakePeer.this.port(),
                    "fake peer",
                    0,
                    0,
                    Hello.MISC1_UNICODE,
                    Hello.MISC2_LARGE_FILES,
                    FakePeer.this.serverIp,
                    FakePeer.this.serverPort);
        }

        private void slotLater() {
            Thread.ofVirtual().start(() -> {
                try {
                    Thread.sleep(SLOT_DELAY_MILLIS);
                    this.send(Packet.empty(Protocol.EDONKEY, Opcodes.Client.ACCEPTUPLOADREQ.code()));
                } catch (IOException e) {
                    logger.debug("No slot given: {}", e.getMessage());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        private void blocks(ClientMessages.RequestParts request) throws IOException {
            for (ClientMessages.Range range : request.ranges()) {
                if (range.isEmpty()) {
                    continue;
                }
                byte[] block = new byte[Math.toIntExact(range.end() - range.start())];
                synchronized (this.data) {
                    this.data.seek(range.start());
                    this.data.readFully(block);
                }
                this.corrupt(range.start(), block);
                this.sendBlock(range.start(), block);
                FakePeer.this.sent.addAndGet(block.length);
                this.sessionBytes += block.length;
                long limit = FakePeer.this.config.sessionLimit();
                if (limit > 0 && this.sessionBytes >= limit) {
                    this.sessionBytes = 0;
                    this.send(Packet.empty(Protocol.EDONKEY, Opcodes.Client.OUTOFPARTREQS.code()));
                    this.slotLater();
                    return;
                }
            }
        }

        private void corrupt(long start, byte[] block) {
            int part = (int) (start / 9_728_000L);
            if (FakePeer.this.config.corruptParts().contains(part) && FakePeer.this.corrupted.add(part)) {
                block[0] ^= 0x55;
            }
        }

        private void sendBlock(long start, byte[] block) throws IOException {
            Hash16 hash = FakePeer.this.link.hash();
            List<Packet> packets = new ArrayList<>();
            if (FakePeer.this.config.compress()) {
                byte[] stream = Packing.deflate(block, 1);
                for (int at = 0; at < stream.length; at += CHUNK) {
                    byte[] chunk = Arrays.copyOfRange(stream, at, Math.min(stream.length, at + CHUNK));
                    packets.add(new ClientMessages.CompressedPart(hash, start, stream.length, chunk).encode(false));
                }
            } else {
                for (int at = 0; at < block.length; at += CHUNK) {
                    int end = Math.min(block.length, at + CHUNK);
                    packets.add(new ClientMessages.SendingPart(
                                    hash, start + at, start + end, Arrays.copyOfRange(block, at, end))
                            .encode(false));
                }
            }
            for (Packet packet : packets) {
                this.send(packet);
            }
        }

        void send(Packet packet) throws IOException {
            synchronized (this.out) {
                this.out.write(TcpFraming.encode(packet));
                this.out.flush();
            }
        }
    }
}
