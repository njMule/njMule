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

import java.net.InetSocketAddress;
import java.net.SocketException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import org.njmule.core.api.FileComment;
import org.njmule.core.api.Hash16;
import org.njmule.core.api.Hash20;
import org.njmule.core.files.FileTypes;
import org.njmule.core.files.SharedFiles;
import org.njmule.core.files.hash.Ed2k;
import org.njmule.core.net.PeerChannel;
import org.njmule.core.peer.Identities;
import org.njmule.core.peer.PeerLink;
import org.njmule.core.protocol.ed2k.ClientMessages;
import org.njmule.core.protocol.ed2k.FileDescription;
import org.njmule.core.protocol.ed2k.FileEntry;
import org.njmule.core.protocol.ed2k.Hello;
import org.njmule.core.protocol.ed2k.SourceExchange;
import org.njmule.core.protocol.io.DecodeException;
import org.njmule.core.protocol.io.PacketReader;
import org.njmule.core.protocol.io.PacketWriter;
import org.njmule.core.protocol.packet.Opcodes;
import org.njmule.core.protocol.packet.Packet;
import org.njmule.core.protocol.packet.Protocol;
import org.njmule.core.protocol.tag.FileTag;
import org.njmule.core.protocol.tag.Tag;
import org.njmule.core.protocol.tag.TagName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A client that may download from us, over the upload side of its link: it asks for a file, its parts and hash set and
 * a place in our queue; with a slot it asks for blocks. Engine thread only.
 */
final class UploadSession implements PeerLink.Role {

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

        /** The AICH master hash of the shared {@code file}, when we have its tree. */
        Optional<Hash20> aichMaster(Hash16 file);

        /** The AICH recovery data of {@code part} of {@code file}, when we have its tree with {@code master}. */
        Optional<byte[]> aichRecovery(Hash16 file, int part, Hash20 master);

        /**
         * The shared files the client with {@code userHash} may see, {@code proven} when its identity is; none when the
         * preferences say so.
         */
        List<SharedFiles.SharedFile> visibleTo(Hash16 userHash, boolean proven);

        /** Whether {@code userHash} is one of a friend's. */
        boolean isFriend(Hash16 userHash);

        /** Our comment on {@code file}, if we made one. */
        Optional<FileComment> comment(Hash16 file);
    }

    /** The answer to a client browsing our files stays below this size, which every client takes. */
    static final int MAX_SHARED_LIST = 1_000_000;

    /** One requested block of {@code file}, which the client chose for it. */
    record Block(Uploadable file, long start, long end) {}

    /** Blocks a client may have waiting at once. */
    static final int MAX_PENDING_BLOCKS = 32;

    // Larger files need the large file support of the client.
    private static final long MAX_SMALL_FILE = 0xFFFF_FFFFL;

    /** The largest block a client may ask for. */
    static final long MAX_BLOCK = 3L * Ed2k.EMBLOCKSIZE;

    // Requests held while a friend's identity is being proven, at most.
    private static final int MAX_HELD = 16;

    private static final Logger logger = LoggerFactory.getLogger(UploadSession.class);

    private PeerChannel connection;
    private final Supplier<Hello> ourHello;
    private final Queue queue;
    private final Deque<Block> pending = new ArrayDeque<>();
    // Blocks sent in this slot are not sent again.
    private final Set<Block> sent = new HashSet<>();
    private Hello peer;
    private final Set<Hash16> commented = new HashSet<>();
    private Optional<Uploadable> file = Optional.empty();
    private Optional<Identities.Session> identity = Optional.empty();
    private final List<Packet> held = new ArrayList<>();
    private boolean slot;
    private boolean closed;

    private UploadSession(Hello peer, Supplier<Hello> ourHello, Queue queue) {
        this.peer = Objects.requireNonNull(peer, "peer");
        this.ourHello = Objects.requireNonNull(ourHello, "ourHello");
        this.queue = Objects.requireNonNull(queue, "queue");
    }

    /** Takes the upload side of {@code link}, whose client greeted us. */
    static UploadSession on(PeerLink link, Supplier<Hello> ourHello, Queue queue) {
        UploadSession session = new UploadSession(link.peer().orElseThrow(), ourHello, queue);
        session.connection = link.attach(PeerLink.Side.UPLOAD, session);
        session.identity = link.identity();
        return session;
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

    /** How far the client's user hash is trusted, for its credits. */
    Identities.Trust trust() {
        return this.identity.map(Identities.Session::trust).orElse(Identities.Trust.NEEDED);
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

    /** Done with the client; a download from it over the same connection goes on. */
    void close() {
        if (!this.closed) {
            this.closed = true;
            this.connection.close();
        }
    }

    /** Closes the connection, also for a download from the client. */
    void disconnect() {
        this.closed = true;
        this.connection.disconnect();
    }

    @Override
    public void received(Packet packet) {
        if (this.closed) {
            return;
        }
        if (this.waitsForProof(packet)) {
            if (this.held.size() < MAX_HELD) {
                this.held.add(packet);
            }
            return;
        }
        try {
            if (packet.protocol() == Protocol.EDONKEY) {
                this.basic(packet);
            } else if (packet.protocol() == Protocol.EMULE) {
                this.extended(packet);
            }
        } catch (DecodeException | IllegalArgumentException e) {
            logger.debug("Bad packet from {}: {}", this.address(), e.getMessage());
            this.disconnect();
        } catch (RuntimeException e) {
            logger.warn("Failed handling a packet from {}", this.address(), e);
            this.close();
        }
    }

    @Override
    public void identitySettled() {
        List<Packet> waiting = List.copyOf(this.held);
        this.held.clear();
        waiting.forEach(this::received);
    }

    // What a friend may do that others may not waits until its key is proven, as user hashes travel in clear; clients
    // ask for our files or write before their signature comes.
    private boolean waitsForProof(Packet packet) {
        int opcode = packet.opcode();
        boolean forFriends = packet.protocol() == Protocol.EDONKEY
                ? opcode == Opcodes.Client.ASKSHAREDFILES.code()
                        || opcode == Opcodes.Client.ASKSHAREDDIRS.code()
                        || opcode == Opcodes.Client.ASKSHAREDFILESDIR.code()
                        || opcode == Opcodes.Client.MESSAGE.code()
                : packet.protocol() == Protocol.EMULE
                        && (opcode == Opcodes.Extended.CHATCAPTCHAREQ.code()
                                || opcode == Opcodes.Extended.CHATCAPTCHARES.code());
        return forFriends && this.trust() == Identities.Trust.NEEDED && this.queue.isFriend(this.userHash());
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
                    this.disconnect();
                    return;
                }
                this.peer = again;
                this.send(this.ourHello.get().encode(true));
            }
            case REQUESTFILENAME -> {
                Hash16 hash = ClientMessages.fileOf(packet);
                this.lookup(hash).ifPresent(f -> {
                    this.send(new ClientMessages.FileName(hash, f.name()).encode());
                    this.tellComment(hash);
                });
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
            case ASKSHAREDFILES -> this.sharedFilesAsked();
            // Shared directories are not listed: the files are, to those that may see them.
            case ASKSHAREDDIRS, ASKSHAREDFILESDIR ->
                this.send(Packet.empty(Protocol.EDONKEY, Opcodes.Client.ASKSHAREDDENIEDANS.code()));
            default -> {
                // Nothing else needs an answer from an uploader.
            }
        }
    }

    // The files the preferences let this client see, an empty list when they do not; large files only to those that
    // take them.
    private void sharedFilesAsked() {
        boolean large = (this.peer.miscOptions2() & Hello.MISC2_LARGE_FILES) != 0;
        PacketWriter entries = new PacketWriter();
        int count = 0;
        for (SharedFiles.SharedFile f :
                this.queue.visibleTo(this.peer.userHash(), this.trust().credits())) {
            if ((f.size() > MAX_SMALL_FILE && !large) || entries.size() > MAX_SHARED_LIST) {
                continue;
            }
            List<Tag> tags = new ArrayList<>();
            tags.add(new Tag.StringTag(TagName.id(FileTag.FILENAME), f.name()));
            tags.add(new Tag.IntTag(TagName.id(FileTag.FILESIZE), f.size()));
            FileTypes.of(f.name()).ifPresent(t -> tags.add(new Tag.StringTag(TagName.id(FileTag.FILETYPE), t)));
            new FileEntry(f.hash(), 0, 0, tags).write(entries);
            count++;
        }
        logger.debug("{} of our files shown to {}", count, this.peer.nick().orElse("?"));
        PacketWriter out = new PacketWriter().u32(count).bytes(entries.toByteArray());
        this.send(Packet.of(Protocol.EDONKEY, Opcodes.Client.ASKSHAREDFILESANSWER.code(), out));
    }

    // A client that takes comments hears ours on a file it asks for, once a connection.
    private void tellComment(Hash16 file) {
        if (FileDescription.accepted(this.peer) && this.commented.add(file)) {
            this.queue.comment(file).ifPresent(c -> this.send(FileDescription.encode(c)));
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
            case AICHREQUEST -> this.aichRequested(packet);
            case AICHFILEHASHREQ -> {
                Hash16 file = ClientMessages.fileOf(packet);
                this.queue
                        .aichMaster(file)
                        .ifPresent(master -> this.send(Packet.of(
                                Protocol.EMULE,
                                Opcodes.Extended.AICHFILEHASHANS.code(),
                                new PacketWriter().hash16(file).hash20(master))));
            }
            default -> {
                // Nothing else needs an answer from an uploader.
            }
        }
    }

    // A downloader repairs a corrupt part with the hashes of its blocks; without them, the answer is the file hash
    // alone.
    private void aichRequested(Packet packet) throws DecodeException {
        PacketReader in = packet.reader();
        Hash16 file = in.hash16();
        int part = in.u16();
        Hash20 master = Hash20.of(in.bytes(Hash20.LENGTH));
        Optional<byte[]> recovery = this.queue.aichRecovery(file, part, master);
        PacketWriter answer = new PacketWriter().hash16(file);
        recovery.ifPresent(data -> answer.u16(part).hash20(master).bytes(data));
        this.send(Packet.of(Protocol.EMULE, Opcodes.Extended.AICHANSWER.code(), answer));
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

    @Override
    public void closed(Optional<String> cause) {
        this.gone();
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
