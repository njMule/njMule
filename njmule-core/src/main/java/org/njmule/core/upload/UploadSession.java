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
package org.njmule.core.upload;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.BitSet;
import java.util.Deque;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import org.njmule.core.api.Hash16;
import org.njmule.core.engine.EngineLoop;
import org.njmule.core.files.hash.Ed2k;
import org.njmule.core.net.TcpConnection;
import org.njmule.core.peer.Identities;
import org.njmule.core.protocol.ed2k.ClientMessages;
import org.njmule.core.protocol.ed2k.Hello;
import org.njmule.core.protocol.ed2k.SourceExchange;
import org.njmule.core.protocol.io.DecodeException;
import org.njmule.core.protocol.io.PacketWriter;
import org.njmule.core.protocol.packet.FrameLimits;
import org.njmule.core.protocol.packet.Opcodes;
import org.njmule.core.protocol.packet.Packet;
import org.njmule.core.protocol.packet.Protocol;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A client that may download from us, over one connection: it asks for a file, its parts and hash set and a place in
 * our queue; with a slot it asks for blocks. Engine thread only; connection callbacks are posted to it.
 */
final class UploadSession {

    /** What the session needs from the queue, on the engine thread. */
    interface Queue {
        Optional<Uploadable> find(Hash16 file);

        /** The client asks for an upload of {@code file}. */
        void requested(UploadSession session, Uploadable file);

        /** The client asked for blocks; the queue sends them when the slot has bandwidth. */
        void blocksRequested(UploadSession session);

        /** The client does not want more data now. */
        void stopped(UploadSession session, String reason);

        void closed(UploadSession session);

        /** The client asks which other clients have {@code file}. */
        void sourcesRequested(UploadSession session, SourceExchange.Request request);

        /** A Kad node we asked to check our TCP port connected and says it could. */
        void reachabilityConfirmed(UploadSession session);

        /** A chat packet: a message or a CAPTCHA request or result. */
        void chat(UploadSession session, Packet packet);

        /** The client broke the rules in a way that only an attacker would; it is banned. */
        void misbehaved(UploadSession session, String reason);
    }

    /** One requested block of {@code file}, which the client chose for it. */
    record Block(Uploadable file, long start, long end) {}

    /** Blocks a client may have waiting at once. */
    static final int MAX_PENDING_BLOCKS = 32;

    /** The largest block a client may ask for. */
    static final long MAX_BLOCK = 3L * Ed2k.EMBLOCKSIZE;

    private static final Logger logger = LoggerFactory.getLogger(UploadSession.class);

    private final EngineLoop loop;
    private final TcpConnection connection;
    private final Supplier<Hello> ourHello;
    private final Queue queue;
    private final Deque<Block> pending = new ArrayDeque<>();
    // Blocks sent in this slot are not sent again.
    private final Set<Block> sent = new HashSet<>();
    private Hello peer;
    private Optional<Uploadable> file = Optional.empty();
    private Optional<Identities.Session> identity = Optional.empty();
    private boolean slot;
    private boolean closed;

    UploadSession(EngineLoop loop, TcpConnection connection, Hello peer, Supplier<Hello> ourHello, Queue queue) {
        this.loop = Objects.requireNonNull(loop, "loop");
        this.connection = Objects.requireNonNull(connection, "connection");
        this.peer = Objects.requireNonNull(peer, "peer");
        this.ourHello = Objects.requireNonNull(ourHello, "ourHello");
        this.queue = Objects.requireNonNull(queue, "queue");
    }

    Hello peer() {
        return this.peer;
    }

    Hash16 userHash() {
        return this.peer.userHash();
    }

    InetSocketAddress address() {
        return this.connection.remoteAddress();
    }

    Optional<Uploadable> file() {
        return this.file;
    }

    boolean isOpen() {
        return !this.closed && this.connection.isOpen();
    }

    boolean hasSlot() {
        return this.slot;
    }

    /** The next requested block, if any. */
    Optional<Block> nextBlock() {
        Block block = this.pending.pollFirst();
        if (block != null) {
            this.sent.add(block);
        }
        return Optional.ofNullable(block);
    }

    boolean hasPendingBlocks() {
        return !this.pending.isEmpty();
    }

    /** Requested and sent blocks, for diagnostics. */
    String describe() {
        return this.address()
                + (this.slot ? " slot" : "")
                + (this.isOpen() ? "" : " closed")
                + " pending "
                + this.pending
                + " sent "
                + this.sent.size();
    }

    /** Runs the secure identification over this connection. */
    void identify(Identities.Session session) {
        this.identity = Optional.of(session);
    }

    /** How far the client's user hash is trusted, for its credits. */
    Identities.Trust trust() {
        return this.identity.map(Identities.Session::trust).orElse(Identities.Trust.NEEDED);
    }

    /** The handler for the connection, which must already have exchanged hellos. */
    TcpConnection.Handler handler() {
        return new TcpConnection.Handler() {
            @Override
            public void onPacket(TcpConnection c, Packet packet) {
                if (packet.protocol() == Protocol.EDONKEY && packet.opcode() == Opcodes.Client.HELLOANSWER.code()) {
                    c.setFrameLimit(FrameLimits.CLIENT);
                }
                if (!UploadSession.this.loop.post(() -> UploadSession.this.received(packet))) {
                    c.close();
                }
            }

            @Override
            public void onClosed(TcpConnection c, Optional<IOException> cause) {
                UploadSession.this.loop.postOutcome(UploadSession.this::gone);
            }
        };
    }

    /** Gives the client its slot. */
    void accept() {
        this.slot = true;
        this.pending.clear();
        this.sent.clear();
        this.send(Packet.empty(Protocol.EDONKEY, Opcodes.Client.ACCEPTUPLOADREQ.code()));
    }

    /** Tells the client its place in our queue. */
    void sendRank(int rank) {
        this.send(Packet.of(
                Protocol.EMULE,
                Opcodes.Extended.QUEUERANKING.code(),
                new PacketWriter().u16(Math.min(rank, 0xFFFF)).bytes(new byte[10])));
    }

    /** Takes the slot away; the client goes back to waiting. */
    void endSlot() {
        if (this.slot) {
            this.slot = false;
            this.pending.clear();
            this.send(Packet.empty(Protocol.EDONKEY, Opcodes.Client.OUTOFPARTREQS.code()));
        }
    }

    boolean send(Packet packet) {
        return !this.closed && this.connection.send(packet);
    }

    void close() {
        if (!this.closed) {
            this.closed = true;
            this.connection.close();
        }
    }

    /** A packet from the client, on the engine thread. */
    void received(Packet packet) {
        if (this.closed) {
            return;
        }
        try {
            if (this.identity.isPresent() && this.identity.get().received(packet)) {
                return;
            }
            if (packet.protocol() == Protocol.EDONKEY) {
                this.basic(packet);
            } else if (packet.protocol() == Protocol.EMULE) {
                this.extended(packet);
            }
        } catch (DecodeException | IllegalArgumentException e) {
            logger.debug("Bad packet from {}: {}", this.address(), e.getMessage());
            this.close();
        } catch (RuntimeException e) {
            logger.warn("Failed handling a packet from {}", this.address(), e);
            this.close();
        }
    }

    private void basic(Packet packet) throws DecodeException {
        Optional<Opcodes.Client> opcode = Opcodes.Client.of(packet.opcode());
        if (opcode.isEmpty()) {
            return;
        }
        switch (opcode.get()) {
            case HELLO -> {
                Hello again = Hello.decode(packet);
                // Becoming another client on the same connection would take over that client's
                // credits, place in the queue and slot.
                if (!again.userHash().equals(this.peer.userHash())) {
                    this.queue.misbehaved(this, "changed its user hash");
                    this.close();
                    return;
                }
                this.peer = again;
                this.send(this.ourHello.get().encode(true));
            }
            case REQUESTFILENAME -> {
                Hash16 hash = ClientMessages.fileOf(packet);
                this.lookup(hash).ifPresent(f -> this.send(new ClientMessages.FileName(hash, f.name()).encode()));
            }
            case SETREQFILEID -> {
                Hash16 hash = ClientMessages.fileOf(packet);
                this.file = this.lookup(hash);
                if (this.file.isEmpty()) {
                    this.send(ClientMessages.forFile(Opcodes.Client.FILEREQANSNOFIL, hash));
                } else {
                    this.send(status(this.file.get()).encode());
                }
            }
            case HASHSETREQUEST -> {
                Hash16 hash = ClientMessages.fileOf(packet);
                this.lookup(hash)
                        .filter(f -> !f.partHashes().isEmpty())
                        .ifPresent(f -> this.send(new ClientMessages.HashSet(hash, f.partHashes()).encode()));
            }
            case STARTUPLOADREQ -> {
                if (packet.length() >= Hash16.LENGTH) {
                    this.file = this.lookup(ClientMessages.fileOf(packet)).or(() -> this.file);
                }
                this.file.ifPresent(f -> this.queue.requested(this, f));
            }
            case REQUESTPARTS -> this.blocks(ClientMessages.RequestParts.decode(packet));
            case MESSAGE -> this.chat(packet);
            case CANCELTRANSFER, END_OF_DOWNLOAD -> {
                this.slot = false;
                this.pending.clear();
                this.queue.stopped(this, "the client stopped downloading");
            }
            // Our shared files are not for browsing: an empty list, and no directories.
            case ASKSHAREDFILES ->
                this.send(Packet.of(
                        Protocol.EDONKEY, Opcodes.Client.ASKSHAREDFILESANSWER.code(), new PacketWriter().u32(0)));
            case ASKSHAREDDIRS, ASKSHAREDFILESDIR ->
                this.send(Packet.empty(Protocol.EDONKEY, Opcodes.Client.ASKSHAREDDENIEDANS.code()));
            default -> {
                // Nothing else needs an answer from an uploader.
            }
        }
    }

    private void extended(Packet packet) throws DecodeException {
        Optional<Opcodes.Extended> opcode = Opcodes.Extended.of(packet.opcode());
        if (opcode.isEmpty()) {
            return;
        }
        switch (opcode.get()) {
            case REQUESTPARTS_I64 -> this.blocks(ClientMessages.RequestParts.decode(packet));
            case KAD_FWTCPCHECK_ACK -> this.queue.reachabilityConfirmed(this);
            case CHATCAPTCHAREQ, CHATCAPTCHARES -> this.chat(packet);
            case REQUESTSOURCES2 -> this.queue.sourcesRequested(this, SourceExchange.Request.decode(packet));
            case PUBLICIP_REQ ->
                this.send(Packet.of(
                        Protocol.EMULE,
                        Opcodes.Extended.PUBLICIP_ANSWER.code(),
                        this.address().getAddress().getAddress()));
            // No AICH recovery data yet: the answer that says so is the file hash alone.
            case AICHREQUEST ->
                this.send(Packet.of(
                        Protocol.EMULE,
                        Opcodes.Extended.AICHANSWER.code(),
                        new PacketWriter().hash16(ClientMessages.fileOf(packet))));
            default -> {
                // Nothing else needs an answer from an uploader.
            }
        }
    }

    // Chat waits for the hello: we must know who writes.
    private void chat(Packet packet) {
        if (this.peer != null) {
            this.queue.chat(this, packet);
        }
    }

    // Only blocks of the file in upload, that we have, of at most one block size, are taken.
    private void blocks(ClientMessages.RequestParts request) {
        if (!this.slot
                || this.file.isEmpty()
                || !request.file().equals(this.file.get().hash())) {
            return;
        }
        Uploadable upload = this.file.get();
        for (ClientMessages.Range range : request.ranges()) {
            if (range.isEmpty()) {
                continue;
            }
            Block block = new Block(upload, range.start(), range.end());
            if (range.end() - range.start() > MAX_BLOCK || !upload.has(range.start(), range.end())) {
                logger.debug("Refusing block {}-{} to {}", range.start(), range.end(), this.address());
                continue;
            }
            if (!this.pending.contains(block)
                    && !this.sent.contains(block)
                    && this.pending.size() < MAX_PENDING_BLOCKS) {
                this.pending.addLast(block);
            }
        }
        if (!this.pending.isEmpty()) {
            this.queue.blocksRequested(this);
        }
    }

    /** The connection is closed, on the engine thread. */
    void gone() {
        if (!this.closed) {
            this.closed = true;
        }
        this.queue.closed(this);
    }

    // Files above 4 GB only for clients that can take them.
    private Optional<Uploadable> lookup(Hash16 hash) {
        boolean large = (this.peer.miscOptions2() & Hello.MISC2_LARGE_FILES) != 0;
        return this.queue.find(hash).filter(f -> large || f.size() <= Ed2k.OLD_MAX_FILE_SIZE);
    }

    /** Fails reads that wait longer than {@code timeout}. */
    void setReadTimeout(Duration timeout) {
        try {
            this.connection.setReadTimeout(timeout);
        } catch (SocketException e) {
            this.close();
        }
    }

    /** OP_FILESTATUS of {@code file}: no bits for a complete file, else the checked parts. */
    static ClientMessages.FileStatus status(Uploadable file) {
        Optional<BitSet> parts = file.parts();
        if (parts.isEmpty()) {
            return new ClientMessages.FileStatus(file.hash(), 0, new BitSet());
        }
        // Sizes that are exact multiples of the part size count one more, empty and so complete.
        int count = (int) (file.size() / Ed2k.PARTSIZE) + 1;
        BitSet bits = parts.get();
        if (file.size() % Ed2k.PARTSIZE == 0) {
            bits.set(count - 1);
        }
        return new ClientMessages.FileStatus(file.hash(), count, bits);
    }
}
