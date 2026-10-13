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
package org.njmule.core.download;

import com.google.common.collect.Range;
import com.google.common.collect.RangeSet;
import com.google.common.collect.TreeRangeSet;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ScheduledFuture;
import java.util.function.Supplier;
import org.njmule.core.api.Hash16;
import org.njmule.core.engine.EngineLoop;
import org.njmule.core.files.PartFile;
import org.njmule.core.net.TcpConnection;
import org.njmule.core.net.TcpConnector;
import org.njmule.core.peer.Identities;
import org.njmule.core.protocol.ed2k.ClientMessages;
import org.njmule.core.protocol.ed2k.Hello;
import org.njmule.core.protocol.ed2k.SourceExchange;
import org.njmule.core.protocol.io.DecodeException;
import org.njmule.core.protocol.packet.FrameLimits;
import org.njmule.core.protocol.packet.Opcodes;
import org.njmule.core.protocol.packet.Packet;
import org.njmule.core.protocol.packet.Packing;
import org.njmule.core.protocol.packet.Protocol;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One source of one download: the connection, the file request, the place in the source's upload
 * queue and the transfer of blocks. Engine thread only; connection callbacks are posted to it.
 */
final class DownloadSession {

    /** What a session reports, on the engine thread. */
    interface Listener {
        /**
         * The source sent us data that reached the download: {@code bytes} of the file, carried in
         * {@code transferred} bytes (compressed or not).
         */
        void received(DownloadSession session, int bytes, int transferred);

        /** The session is over. */
        void ended(DownloadSession session, Outcome outcome, String reason);

        /** Whether the source may be asked for more sources now. */
        boolean maySourcesBeAsked(DownloadSession session);

        /** The source told us of other sources. */
        void sourcesFound(DownloadSession session, SourceExchange.Answer answer);
    }

    /** How a session ended, which decides when the source is asked again. */
    enum Outcome {
        /** The source keeps us in its upload queue. */
        QUEUED,
        /** Nothing to do with the source now; it may have more later. */
        LATER,
        /** The source could not be reached or greeted. */
        UNREACHABLE,
        /** The source lacks the file or broke the protocol. */
        GONE
    }

    enum State {
        CONNECTING,
        GREETING,
        REQUESTING,
        QUEUED,
        DOWNLOADING,
        ENDED
    }

    /** Time to connect, greet and learn the parts of the source. */
    static final Duration HANDSHAKE_TIMEOUT = Duration.ofSeconds(30);

    /** A queued connection that stays silent this long is closed; our place in the queue stays. */
    static final Duration QUEUED_TIMEOUT = Duration.ofSeconds(60);

    /** A source that gave us a slot but sends nothing for this long is dropped. */
    static final Duration DATA_TIMEOUT = Duration.ofSeconds(100);

    /**
     * A slot is kept this long with nothing to ask, while the data in flight is written and
     * checked: a corrupt part is asked again.
     */
    static final Duration IDLE_SLOT = Duration.ofSeconds(30);

    private static final Logger logger = LoggerFactory.getLogger(DownloadSession.class);

    /** One requested block and the bytes of it still to come. */
    private static final class Pending {
        private final PartFile.Block block;
        private final RangeSet<Long> missing = TreeRangeSet.create();
        private BlockInflater inflater;

        Pending(PartFile.Block block) {
            this.block = block;
            this.missing.add(Range.closedOpen(block.start(), block.end()));
        }

        boolean contains(long start, long end) {
            return start >= this.block.start() && end <= this.block.end();
        }

        void dispose() {
            if (this.inflater != null) {
                this.inflater.close();
                this.inflater = null;
            }
        }
    }

    private final EngineLoop loop;
    private final DownloadQueue queue;
    private final DownloadQueue.Download download;
    private final InetSocketAddress address;
    private final Supplier<Hello> ourHello;
    private final Identities identities;
    private final Listener listener;
    private final Map<Long, Pending> pending = new LinkedHashMap<>();

    private State state = State.CONNECTING;
    private TcpConnection connection;
    private Optional<Hash16> peerHash = Optional.empty();
    private BitSet sourceParts;
    private boolean uploadRequested;
    private boolean accepted;
    private int queueRank;
    private ScheduledFuture<?> idle;
    private Optional<Identities.Session> identity = Optional.empty();
    private Optional<Hello> peer = Optional.empty();
    private boolean sourcesAsked;

    DownloadSession(
            EngineLoop loop,
            DownloadQueue queue,
            DownloadQueue.Download download,
            InetSocketAddress address,
            Supplier<Hello> ourHello,
            Identities identities,
            Listener listener) {
        this.loop = Objects.requireNonNull(loop, "loop");
        this.queue = Objects.requireNonNull(queue, "queue");
        this.download = Objects.requireNonNull(download, "download");
        this.address = Objects.requireNonNull(address, "address");
        this.ourHello = Objects.requireNonNull(ourHello, "ourHello");
        this.identities = Objects.requireNonNull(identities, "identities");
        this.listener = Objects.requireNonNull(listener, "listener");
    }

    State state() {
        return this.state;
    }

    /** State and outstanding blocks, for diagnostics. */
    String describe() {
        StringBuilder text = new StringBuilder(this.address + " " + this.state);
        for (Pending p : this.pending.values()) {
            text.append(" [")
                    .append(p.block.start())
                    .append('-')
                    .append(p.block.end())
                    .append(" missing ")
                    .append(p.missing)
                    .append(p.inflater != null ? " inflating" : "")
                    .append(']');
        }
        return text.toString();
    }

    InetSocketAddress address() {
        return this.address;
    }

    DownloadQueue.Download download() {
        return this.download;
    }

    Optional<Hash16> peerHash() {
        return this.peerHash;
    }

    /** The connect options the source announced: supports, requests, requires obfuscation. */
    int peerCryptOptions() {
        return this.peer.map(p -> p.miscOptions2() >>> 7 & 0x07).orElse(0);
    }

    /** Whether the source's user hash may be credited with what it sends. */
    boolean trusted() {
        return this.identity.map(i -> i.trust().credits()).orElse(false);
    }

    /** Our place in the source's queue, once it told us. */
    int queueRank() {
        return this.queueRank;
    }

    /** Connects to the source, obfuscated with its user hash when known, and says hello. */
    void connect(Optional<Hash16> obfuscateFor, SecureRandom random) {
        Thread.ofVirtual()
                .name("njmule-download-" + this.address)
                .start(
                        () -> {
                            try {
                                TcpConnection opened =
                                        TcpConnector.connectToClient(
                                                this.address,
                                                obfuscateFor,
                                                HANDSHAKE_TIMEOUT,
                                                random);
                                if (!this.loop.post(() -> this.opened(opened))) {
                                    opened.close();
                                }
                            } catch (IOException e) {
                                this.loop.post(
                                        () ->
                                                this.end(
                                                        Outcome.UNREACHABLE,
                                                        "cannot connect: " + e.getMessage()));
                            }
                        });
    }

    /**
     * Takes over a connection the source opened after we greeted each other, as a source does when
     * it has a slot for us; returns the handler for the rest of the connection.
     */
    TcpConnection.Handler adopt(TcpConnection incoming, Hello peer, Optional<BitSet> knownParts) {
        return this.adopt(incoming, peer, knownParts, true);
    }

    /**
     * As above; {@code slotOffered} is false for a firewalled source that connected because we
     * asked it to through the server, which still has to be asked for an upload.
     */
    TcpConnection.Handler adopt(
            TcpConnection incoming, Hello peer, Optional<BitSet> knownParts, boolean slotOffered) {
        this.connection = incoming;
        this.peer = Optional.of(peer);
        this.peerHash = Optional.of(peer.userHash());
        this.identity =
                Optional.of(
                        this.identities.start(
                                peer, incoming.remoteAddress().getAddress(), incoming::send));
        this.state = State.QUEUED;
        this.uploadRequested = slotOffered;
        knownParts.ifPresentOrElse(this::setParts, this::requestFile);
        return this.handler(incoming);
    }

    /** Ends the session; a source that gave us a slot is told we leave it. */
    void close(String reason) {
        if (this.state == State.DOWNLOADING && this.connection != null) {
            this.connection.send(
                    Packet.empty(Protocol.EDONKEY, Opcodes.Client.CANCELTRANSFER.code()));
        }
        this.end(Outcome.LATER, reason);
    }

    /** The download changed (data written, a part found corrupt): ask for blocks if idle. */
    void wake() {
        if (this.state == State.DOWNLOADING && this.pending.isEmpty()) {
            this.requestBlocks();
        }
    }

    /** The parts the source reported, if it did. */
    Optional<BitSet> sourceParts() {
        return Optional.ofNullable(this.sourceParts).map(parts -> (BitSet) parts.clone());
    }

    private void opened(TcpConnection opened) {
        if (this.state != State.CONNECTING) {
            opened.close();
            return;
        }
        this.connection = opened;
        this.state = State.GREETING;
        opened.start(this.handler(opened), DownloadSession::unpack);
        opened.send(this.ourHello.get().encode(false));
    }

    private TcpConnection.Handler handler(TcpConnection mine) {
        return new TcpConnection.Handler() {
            @Override
            public void onPacket(TcpConnection c, Packet packet) {
                // The next frame is read right after this returns: lift the handshake limit here.
                if (packet.protocol() == Protocol.EDONKEY
                        && packet.opcode() == Opcodes.Client.HELLOANSWER.code()) {
                    c.setFrameLimit(FrameLimits.CLIENT);
                }
                if (!DownloadSession.this.loop.post(
                        () -> DownloadSession.this.received(mine, packet))) {
                    c.close();
                }
            }

            @Override
            public void onClosed(TcpConnection c, Optional<IOException> cause) {
                DownloadSession.this.loop.post(
                        () ->
                                DownloadSession.this.closed(
                                        mine, cause.map(IOException::getMessage)));
            }
        };
    }

    private void received(TcpConnection from, Packet packet) {
        if (from != this.connection || this.state == State.ENDED) {
            return;
        }
        try {
            if (this.identity.isPresent() && this.identity.get().received(packet)) {
                return;
            }
            if (packet.protocol() == Protocol.EMULE) {
                this.receivedExtended(packet);
            } else if (packet.protocol() == Protocol.EDONKEY) {
                this.receivedBasic(packet);
            }
        } catch (DecodeException | IllegalArgumentException e) {
            logger.debug("Bad packet from {}: {}", this.address, e.getMessage());
            this.end(Outcome.GONE, "protocol error: " + e.getMessage());
        } catch (RuntimeException e) {
            // A lost packet would stall the session: end it, the source is asked again later.
            logger.warn("Failed handling a packet from {}", this.address, e);
            this.end(Outcome.LATER, "internal error: " + e);
        }
    }

    private void receivedBasic(Packet packet) throws DecodeException {
        Optional<Opcodes.Client> opcode = Opcodes.Client.of(packet.opcode());
        if (opcode.isEmpty()) {
            return;
        }
        Hash16 file = this.download.hash();
        switch (opcode.get()) {
            case HELLOANSWER -> {
                if (this.state == State.GREETING) {
                    Hello peer = Hello.decode(packet);
                    this.peer = Optional.of(peer);
                    this.peerHash = Optional.of(peer.userHash());
                    // As the side that connected, we ask for the identification first.
                    this.identity =
                            Optional.of(
                                    this.identities.start(
                                            peer,
                                            this.connection.remoteAddress().getAddress(),
                                            this.connection::send));
                    this.state = State.REQUESTING;
                    this.requestFile();
                }
            }
            case HELLO -> this.connection.send(this.ourHello.get().encode(true));
            case FILESTATUS -> {
                ClientMessages.FileStatus status = ClientMessages.FileStatus.decode(packet);
                if (status.file().equals(file)) {
                    this.setParts(status.dataParts(this.download.file().size()));
                }
            }
            case FILEREQANSNOFIL -> {
                if (ClientMessages.fileOf(packet).equals(file)) {
                    this.end(Outcome.GONE, "the source does not have the file");
                }
            }
            case HASHSETANSWER -> this.hashSet(ClientMessages.HashSet.decode(packet));
            case ACCEPTUPLOADREQ -> {
                this.accepted = true;
                this.startDownloading();
            }
            case QUEUERANK -> this.queued((int) ClientMessages.decodeQueueRank(packet));
            case SENDINGPART -> this.data(ClientMessages.SendingPart.decode(packet));
            case OUTOFPARTREQS -> this.backToQueue("the source ended our upload session");
            case CANCELTRANSFER -> this.backToQueue("the source cancelled the transfer");
            default -> {
                // File names and other messages need nothing from us.
            }
        }
    }

    private void receivedExtended(Packet packet) throws DecodeException {
        Optional<Opcodes.Extended> opcode = Opcodes.Extended.of(packet.opcode());
        if (opcode.isEmpty()) {
            return;
        }
        switch (opcode.get()) {
            case QUEUERANKING -> this.queued((int) ClientMessages.decodeQueueRank(packet));
            case SENDINGPART_I64 -> this.data(ClientMessages.SendingPart.decode(packet));
            case COMPRESSEDPART, COMPRESSEDPART_I64 ->
                    this.compressed(ClientMessages.CompressedPart.decode(packet));
            case ANSWERSOURCES2 -> {
                SourceExchange.Answer answer = SourceExchange.Answer.decode(packet);
                // Only answers we asked for, about this file.
                if (this.sourcesAsked && answer.file().equals(this.download.hash())) {
                    this.sourcesAsked = false;
                    this.listener.sourcesFound(this, answer);
                }
            }
            default -> {
                // Nothing else is expected from an uploader.
            }
        }
    }

    private void requestFile() {
        Hash16 file = this.download.hash();
        this.connection.send(ClientMessages.forFile(Opcodes.Client.REQUESTFILENAME, file));
        this.connection.send(ClientMessages.forFile(Opcodes.Client.SETREQFILEID, file));
        if (!this.download.file().hasHashSet()) {
            this.connection.send(ClientMessages.forFile(Opcodes.Client.HASHSETREQUEST, file));
        }
        boolean exchanges =
                this.peer
                        .map(p -> (p.miscOptions2() & Hello.MISC2_SOURCE_EXCHANGE2) != 0)
                        .orElse(false);
        if (exchanges && this.listener.maySourcesBeAsked(this)) {
            this.sourcesAsked = true;
            this.connection.send(SourceExchange.request(file));
        }
    }

    private void setParts(BitSet parts) {
        PartFile file = this.download.file();
        if (this.sourceParts != null) {
            file.addAvailability(this.sourceParts, -1);
        }
        this.sourceParts = parts;
        file.addAvailability(parts, 1);
        if (!this.hasNeededParts()) {
            this.close("the source has no part we need");
            return;
        }
        if (!this.uploadRequested) {
            this.uploadRequested = true;
            this.state = State.QUEUED;
            this.setReadTimeout(QUEUED_TIMEOUT);
            this.connection.send(
                    ClientMessages.forFile(Opcodes.Client.STARTUPLOADREQ, this.download.hash()));
        }
        this.startDownloading();
    }

    private void hashSet(ClientMessages.HashSet set) {
        if (set.file().equals(this.download.hash()) && !this.download.file().hasHashSet()) {
            this.download.file().setHashSet(set.parts());
            this.queue.hashSetKnown(this.download);
        }
    }

    private void queued(int rank) {
        if (this.state == State.QUEUED) {
            this.queueRank = rank;
            logger.debug("Queued at {} on {}", rank, this.address);
        }
    }

    private void startDownloading() {
        if (this.state != State.QUEUED || !this.accepted || this.sourceParts == null) {
            return;
        }
        this.state = State.DOWNLOADING;
        this.queueRank = 0;
        this.setReadTimeout(DATA_TIMEOUT);
        logger.debug("Downloading {} from {}", this.download.file().name(), this.address);
        this.requestBlocks();
    }

    private void requestBlocks() {
        if (this.state != State.DOWNLOADING) {
            return;
        }
        if (!this.download.isActive()) {
            this.close("the download is not active");
            return;
        }
        int wanted = ClientMessages.BLOCKS_PER_REQUEST - this.pending.size();
        List<PartFile.Block> next =
                wanted > 0 ? this.download.file().nextBlocks(this.sourceParts, wanted) : List.of();
        if (next.isEmpty()) {
            if (this.pending.isEmpty() && this.idle == null) {
                this.idle =
                        this.loop.schedule(
                                "idle download slot",
                                IDLE_SLOT,
                                () -> {
                                    this.idle = null;
                                    if (this.state == State.DOWNLOADING && this.pending.isEmpty()) {
                                        this.close("nothing more to download from this source");
                                    }
                                });
            }
            return;
        }
        this.stopIdle();
        List<ClientMessages.Range> ranges = new ArrayList<>(ClientMessages.BLOCKS_PER_REQUEST);
        boolean wide = false;
        for (PartFile.Block block : next) {
            this.pending.put(block.start(), new Pending(block));
            ranges.add(new ClientMessages.Range(block.start(), block.end()));
            wide |= block.end() > 0xFFFF_FFFFL;
        }
        while (ranges.size() < ClientMessages.BLOCKS_PER_REQUEST) {
            ranges.add(new ClientMessages.Range(0, 0));
        }
        this.connection.send(
                new ClientMessages.RequestParts(this.download.hash(), ranges).encode(wide));
    }

    private void data(ClientMessages.SendingPart part) {
        if (part.file().equals(this.download.hash())) {
            byte[] data = part.data();
            this.accept(part.start(), data, data.length);
        }
    }

    private void compressed(ClientMessages.CompressedPart part) throws DecodeException {
        Pending block = this.pending.get(part.blockStart());
        if (this.state != State.DOWNLOADING
                || block == null
                || !part.file().equals(this.download.hash())) {
            return;
        }
        if (block.inflater == null) {
            block.inflater =
                    new BlockInflater(block.block.start(), Math.toIntExact(block.block.length()));
        }
        long position = block.inflater.position();
        byte[] chunk = part.chunk();
        byte[] data = block.inflater.inflate(chunk);
        this.accept(position, data, chunk.length);
    }

    // Data counts only inside a block we asked for and still wait for.
    private void accept(long start, byte[] data, int transferred) {
        if (this.state != State.DOWNLOADING || data.length == 0) {
            return;
        }
        long end = start + data.length;
        Pending target = null;
        for (Pending candidate : this.pending.values()) {
            if (candidate.contains(start, end)) {
                target = candidate;
                break;
            }
        }
        if (target == null) {
            logger.debug("Data {}-{} from {} was not requested", start, end, this.address);
            return;
        }
        this.queue.received(this.download, start, data);
        this.listener.received(this, data.length, transferred);
        target.missing.remove(Range.closedOpen(start, end));
        if (target.missing.isEmpty()) {
            target.dispose();
            this.pending.remove(target.block.start());
            this.requestBlocks();
        }
    }

    private void backToQueue(String reason) {
        if (this.state != State.DOWNLOADING) {
            return;
        }
        logger.debug("{}: {}", this.address, reason);
        this.releaseBlocks();
        this.stopIdle();
        this.accepted = false;
        this.state = State.QUEUED;
        this.setReadTimeout(QUEUED_TIMEOUT);
    }

    private void closed(TcpConnection which, Optional<String> cause) {
        if (which != this.connection || this.state == State.ENDED) {
            return;
        }
        // A queued source keeps our place when the idle connection closes; a lost slot is gone.
        Outcome outcome =
                switch (this.state) {
                    case QUEUED -> Outcome.QUEUED;
                    case DOWNLOADING -> Outcome.LATER;
                    default -> Outcome.UNREACHABLE;
                };
        this.end(outcome, "the connection closed" + cause.map(c -> " (" + c + ")").orElse(""));
    }

    private void end(Outcome outcome, String reason) {
        if (this.state == State.ENDED) {
            return;
        }
        this.state = State.ENDED;
        this.stopIdle();
        this.releaseBlocks();
        if (this.sourceParts != null) {
            this.download.file().addAvailability(this.sourceParts, -1);
        }
        if (this.connection != null) {
            this.connection.close();
        }
        this.listener.ended(this, outcome, reason);
    }

    private void stopIdle() {
        if (this.idle != null) {
            this.idle.cancel(false);
            this.idle = null;
        }
    }

    private void releaseBlocks() {
        for (Pending block : this.pending.values()) {
            block.dispose();
            this.download.file().release(block.block);
        }
        this.pending.clear();
    }

    private boolean hasNeededParts() {
        PartFile file = this.download.file();
        for (int part = this.sourceParts.nextSetBit(0);
                part >= 0 && part < file.partCount();
                part = this.sourceParts.nextSetBit(part + 1)) {
            if (!file.isComplete(part)) {
                return true;
            }
        }
        return false;
    }

    private void setReadTimeout(Duration timeout) {
        try {
            this.connection.setReadTimeout(timeout);
        } catch (SocketException e) {
            this.connection.close();
        }
    }

    /** Clients pack eMule packets: those inflate with the client cap. */
    private static Packet unpack(Packet frame) throws DecodeException {
        return Packing.unpack(frame, Packing.MAX_CLIENT_UNPACKED);
    }
}
