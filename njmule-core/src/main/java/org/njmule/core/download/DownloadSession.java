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

import com.google.common.base.Ticker;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.function.Supplier;
import org.njmule.core.api.FileComment;
import org.njmule.core.api.Hash16;
import org.njmule.core.api.Hash20;
import org.njmule.core.engine.EngineLoop;
import org.njmule.core.files.PartFile;
import org.njmule.core.net.PeerChannel;
import org.njmule.core.net.TcpConnection;
import org.njmule.core.net.TcpConnector;
import org.njmule.core.peer.Identities;
import org.njmule.core.peer.PeerLink;
import org.njmule.core.peer.PeerLinks;
import org.njmule.core.protocol.ed2k.ClientMessages;
import org.njmule.core.protocol.ed2k.FileDescription;
import org.njmule.core.protocol.ed2k.Hello;
import org.njmule.core.protocol.ed2k.SourceExchange;
import org.njmule.core.protocol.io.DecodeException;
import org.njmule.core.protocol.io.PacketReader;
import org.njmule.core.protocol.io.PacketWriter;
import org.njmule.core.protocol.packet.Opcodes;
import org.njmule.core.protocol.packet.Packet;
import org.njmule.core.protocol.packet.Packing;
import org.njmule.core.protocol.packet.Protocol;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One source of one download at a time: the connection, the file request, the place in the source's upload queue and
 * the transfer of blocks. A source with nothing for the download may be asked for another one on the same connection.
 * Engine thread only; connection callbacks are posted to it.
 */
final class DownloadSession implements PeerLink.Role {

    /** What a session reports, on the engine thread. */
    interface Listener {
        /**
         * The source sent us data that reached the download: {@code bytes} of the file, carried in {@code transferred}
         * bytes (compressed or not).
         */
        void received(DownloadSession session, int bytes, int transferred);

        /** The session is over. */
        void ended(DownloadSession session, Outcome outcome, String reason);

        /** Whether the source may be asked for more sources now. */
        boolean maySourcesBeAsked(DownloadSession session);

        /** The source told us of other sources. */
        void sourcesFound(DownloadSession session, SourceExchange.Answer answer);

        /** The source sent AICH recovery data for {@code part}, which we asked for. */
        default void aichAnswered(DownloadSession session, int part, Hash20 master, byte[] data) {}

        /** The source has no AICH recovery data for {@code part}, which we asked for. */
        default void aichUnavailable(DownloadSession session, int part) {}

        /** The session let blocks go that other sources may now download. */
        default void released(DownloadSession session) {}

        /** How many of {@code blocks} more blocks the session may ask for now; with none, it waits to be woken. */
        default int allowance(DownloadSession session, int blocks) {
            return blocks;
        }

        /** The session asked the source for {@code bytes} more. */
        default void requested(DownloadSession session, long bytes) {}

        /** The source told which parts it has. */
        default void partsKnown(DownloadSession session) {}

        /** The source announced the AICH master hash of the file. */
        default void aichAnnounced(DownloadSession session, Hash20 master) {}

        /** The source told its comment on the file. */
        default void commented(DownloadSession session, FileComment comment) {}

        /** The source told the name it gives {@code file}, made fit to show. */
        default void named(DownloadSession session, Hash16 file, String name) {}

        /**
         * The source has nothing for the download asked for: no part we need, or not the file at all when
         * {@code hasFile} is false. Returns another download it is a source of, to ask it for on this connection
         * instead.
         */
        default Optional<DownloadQueue.Download> another(DownloadSession session, boolean hasFile) {
            return Optional.empty();
        }
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

    /**
     * A slot whose blocks bring no data for this long is left, whatever else the source sends; a silent connection
     * closes after it too.
     */
    static final Duration DATA_TIMEOUT = Duration.ofSeconds(100);

    /**
     * A slot is kept this long with nothing to ask, while the data in flight is written and checked: a corrupt part is
     * asked again.
     */
    static final Duration IDLE_SLOT = Duration.ofSeconds(30);

    /** Data of no block we asked for, this many times each within {@link #UNEXPECTED_GAP}, ends a slot. */
    static final int MAX_UNEXPECTED = 3;

    static final Duration UNEXPECTED_GAP = Duration.ofSeconds(10);

    /** Compressed blocks that do not inflate, in one session, that end it: the source is broken. */
    static final int MAX_CORRUPT_STREAMS = 3;

    private static final Logger logger = LoggerFactory.getLogger(DownloadSession.class);

    /** The longest name of a file a source gives that is kept, in characters. */
    static final int MAX_NAME = 255;

    /** One requested block and the bytes of it still to come. */
    private static final class Pending {
        private final PartFile.Block block;
        private final RangeSet<Long> missing = TreeRangeSet.create();
        private BlockInflater inflater;
        // Compressed bytes of the block's stream received so far.
        private long compressed;

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
    private final Ticker ticker;
    private final DownloadQueue queue;
    private DownloadQueue.Download download;
    // The downloads asked for on this connection, whose answers may still come after a swap to another.
    private final Map<Hash16, DownloadQueue.Download> asked = new LinkedHashMap<>();
    private final InetSocketAddress address;
    private final Supplier<Hello> ourHello;
    private final PeerLinks links;
    private final Listener listener;
    private final Map<Long, Pending> pending = new LinkedHashMap<>();

    private State state = State.CONNECTING;
    private PeerChannel connection;
    private Optional<Hash16> peerHash = Optional.empty();
    private BitSet sourceParts;
    private boolean uploadRequested;
    private boolean accepted;
    private int queueRank;
    private ScheduledFuture<?> idle;
    private Optional<Identities.Session> identity = Optional.empty();
    private PeerLink link;
    private Optional<Hello> peer = Optional.empty();
    // The files the source was asked for other sources of, whose answers we take.
    private final Set<Hash16> sourcesAsked = new HashSet<>();
    // The part whose AICH recovery data we asked for, or -1.
    private int aichAsked = -1;
    // When the slot last made progress: blocks asked for, or data of them received.
    private long progressAt;
    // Data of no block asked for, in a row, and when the last came.
    private int unexpected;
    private long unexpectedAt;
    private int corruptStreams;
    // The compressed bytes still to come of streams that failed, by block start: dropped as they arrive.
    private final Map<Long, Long> broken = new HashMap<>();

    DownloadSession(
            EngineLoop loop,
            Ticker ticker,
            DownloadQueue queue,
            DownloadQueue.Download download,
            InetSocketAddress address,
            Supplier<Hello> ourHello,
            PeerLinks links,
            Listener listener) {
        this.loop = Objects.requireNonNull(loop, "loop");
        this.ticker = Objects.requireNonNull(ticker, "ticker");
        this.queue = Objects.requireNonNull(queue, "queue");
        this.download = Objects.requireNonNull(download, "download");
        this.address = Objects.requireNonNull(address, "address");
        this.ourHello = Objects.requireNonNull(ourHello, "ourHello");
        this.links = Objects.requireNonNull(links, "links");
        this.listener = Objects.requireNonNull(listener, "listener");
    }

    /** The source's hello, once it greeted us. */
    Optional<Hello> peer() {
        return this.peer;
    }

    /** Whether the source can send AICH recovery data of {@code part}: it greeted us as able to and has the part. */
    boolean mayRecover(int part) {
        return this.connection != null
                && this.peer.map(Hello::supportsAich).orElse(false)
                && this.sourceParts != null
                && this.sourceParts.get(part);
    }

    /** Asks the source for the AICH recovery data of {@code part}, under the trusted {@code master} hash. */
    void requestAich(int part, Hash20 master) {
        this.aichAsked = part;
        this.connection.send(Packet.of(
                Protocol.EMULE,
                Opcodes.Extended.AICHREQUEST.code(),
                new PacketWriter().hash16(this.download.hash()).u16(part).hash20(master)));
    }

    // An answer of 16 bytes says the source has no recovery data; only an answer we asked for counts.
    private void aichAnswer(Packet packet) throws DecodeException {
        PacketReader in = packet.reader();
        Hash16 file = in.hash16();
        int asked = this.aichAsked;
        if (!file.equals(this.download.hash()) || asked < 0) {
            this.aichAsked = -1;
            return;
        }
        if (in.remaining() == 0) {
            this.aichAsked = -1;
            this.listener.aichUnavailable(this, asked);
            return;
        }
        int part = in.u16();
        Hash20 master = Hash20.of(in.bytes(Hash20.LENGTH));
        if (part == this.aichAsked) {
            this.aichAsked = -1;
            this.listener.aichAnswered(this, part, master, in.bytes(in.remaining()));
        }
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

    /** Whether the source was asked for an upload, or offered us one: its answer tells our place in its queue. */
    boolean uploadAsked() {
        return this.uploadRequested;
    }

    /** Connects to the source, obfuscated with its user hash when known, and says hello. */
    void connect(InetSocketAddress via, Optional<Hash16> obfuscateFor, SecureRandom random) {
        Thread.ofVirtual().name("njmule-download-" + via).start(() -> {
            try {
                TcpConnection opened = TcpConnector.connectToClient(via, obfuscateFor, HANDSHAKE_TIMEOUT, random);
                if (!this.loop.postOutcome(() -> opened(opened))) {
                    opened.close();
                }
            } catch (IOException e) {
                this.loop.postOutcome(() -> end(Outcome.UNREACHABLE, "cannot connect: " + e.getMessage()));
            }
        });
    }

    /**
     * Takes the download side of a link whose client greeted us. {@code slotOffered} tells a source that connected to
     * give us a slot from one that still has to be asked for an upload: a firewalled source we asked through the server
     * to connect, or a client already connected for another reason.
     */
    void adopt(PeerLink link, Optional<BitSet> knownParts, boolean slotOffered) {
        Hello hello = link.peer().orElseThrow();
        this.link = link;
        this.connection = link.attach(PeerLink.Side.DOWNLOAD, this);
        this.peer = Optional.of(hello);
        this.peerHash = Optional.of(hello.userHash());
        this.identity = link.identity();
        this.state = State.QUEUED;
        this.uploadRequested = slotOffered;
        knownParts.ifPresentOrElse(this::setParts, this::requestFile);
    }

    /** Ends the session; a source that gave us a slot is told we leave it. */
    void close(String reason) {
        if (this.state == State.DOWNLOADING && this.connection != null) {
            this.connection.send(Packet.empty(Protocol.EDONKEY, Opcodes.Client.CANCELTRANSFER.code()));
        }
        end(Outcome.LATER, reason);
    }

    /** Leaves a slot whose blocks brought no data for {@link #DATA_TIMEOUT}, telling the source. */
    void checkProgress(long now) {
        if (this.state == State.DOWNLOADING
                && !this.pending.isEmpty()
                && now - this.progressAt >= DATA_TIMEOUT.toNanos()) {
            close("no data for " + DATA_TIMEOUT.toSeconds() + " s");
        }
    }

    /** The download changed (data written, a part found corrupt): ask for blocks if idle. */
    void wake() {
        if (this.state == State.DOWNLOADING && this.pending.isEmpty()) {
            requestBlocks();
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
        this.link = this.links.outgoing(opened, new PeerLinks.Opener() {
            @Override
            public void greeted(PeerLink link) {
                DownloadSession.this.greeted(link);
            }

            @Override
            public void failed(String reason) {
                // The download side hears it as the connection closing.
            }
        });
        this.connection = this.link.attach(PeerLink.Side.DOWNLOAD, this);
        this.state = State.GREETING;
        opened.start(this.link, DownloadSession::unpack);
        opened.send(this.ourHello
                .get()
                .toPeerAt(opened.remoteAddress().getAddress())
                .encode(false));
    }

    private void greeted(PeerLink greeting) {
        if (greeting != this.link || this.state != State.GREETING) {
            return;
        }
        Hello hello = greeting.peer().orElseThrow();
        this.peer = Optional.of(hello);
        this.peerHash = Optional.of(hello.userHash());
        this.identity = greeting.identity();
        if (hello.userHash().equals(this.ourHello.get().userHash())) {
            end(Outcome.GONE, "the source is ourselves");
            return;
        }
        this.state = State.REQUESTING;
        requestFile();
    }

    @Override
    public void received(Packet packet) {
        if (this.state == State.ENDED) {
            return;
        }
        try {
            if (packet.protocol() == Protocol.EMULE) {
                receivedExtended(packet);
            } else if (packet.protocol() == Protocol.EDONKEY) {
                receivedBasic(packet);
            }
        } catch (DecodeException | IllegalArgumentException e) {
            logger.debug("Bad packet from {}: {}", this.address, e.getMessage());
            end(Outcome.GONE, "protocol error: " + e.getMessage());
        } catch (RuntimeException e) {
            // A lost packet would stall the session: end it, the source is asked again later.
            logger.warn("Failed handling a packet from {}", this.address, e);
            end(Outcome.LATER, "internal error: " + e);
        }
    }

    private void receivedBasic(Packet packet) throws DecodeException {
        Optional<Opcodes.Client> opcode = Opcodes.Client.of(packet.opcode());
        if (opcode.isEmpty()) {
            return;
        }
        Hash16 file = this.download.hash();
        switch (opcode.get()) {
            case FILESTATUS -> {
                ClientMessages.FileStatus status = ClientMessages.FileStatus.decode(packet);
                long size = this.download.file().size();
                if (!status.file().equals(file)) {
                    return;
                }
                if (status.fits(size)) {
                    setParts(status.dataParts(size));
                } else {
                    end(Outcome.GONE, "the source sent " + status.partCount() + " parts for the file");
                }
            }
            case FILEREQANSNOFIL -> {
                if (ClientMessages.fileOf(packet).equals(file) && !askForAnother(false)) {
                    end(Outcome.GONE, "the source does not have the file");
                }
            }
            case HASHSETANSWER -> hashSet(ClientMessages.HashSet.decode(packet));
            case ACCEPTUPLOADREQ -> {
                this.accepted = true;
                startDownloading();
            }
            case QUEUERANK -> queued((int) ClientMessages.decodeQueueRank(packet));
            case SENDINGPART -> data(ClientMessages.SendingPart.decode(packet));
            case OUTOFPARTREQS -> backToQueue("the source ended our upload session");
            case CANCELTRANSFER -> backToQueue("the source cancelled the transfer");
            case REQFILENAMEANSWER -> named(ClientMessages.FileName.decode(packet));
            default -> {
                // Other messages need nothing from us.
            }
        }
    }

    private void receivedExtended(Packet packet) throws DecodeException {
        Optional<Opcodes.Extended> opcode = Opcodes.Extended.of(packet.opcode());
        if (opcode.isEmpty()) {
            return;
        }
        switch (opcode.get()) {
            case QUEUERANKING -> queued((int) ClientMessages.decodeQueueRank(packet));
            case SENDINGPART_I64 -> data(ClientMessages.SendingPart.decode(packet));
            case COMPRESSEDPART, COMPRESSEDPART_I64 -> compressed(ClientMessages.CompressedPart.decode(packet));
            case AICHANSWER -> aichAnswer(packet);
            case FILEDESC -> this.listener.commented(this, FileDescription.decode(packet));
            case AICHFILEHASHANS -> {
                PacketReader in = packet.reader();
                if (in.hash16().equals(this.download.hash())) {
                    this.listener.aichAnnounced(this, Hash20.of(in.bytes(Hash20.LENGTH)));
                }
            }
            case ANSWERSOURCES2 -> {
                SourceExchange.Answer answer =
                        extendedExchange() && packet.reader().u8() == SourceExchange.EXTENDED_VERSION
                                ? SourceExchange.Answer.decodeExtended(packet)
                                : SourceExchange.Answer.decode(packet);
                // Only answers we asked for, about a file we asked for.
                if (this.sourcesAsked.remove(answer.file())) {
                    this.listener.sourcesFound(this, answer);
                }
            }
            default -> {
                // Nothing else is expected from an uploader.
            }
        }
    }

    // Names of the files asked for on this connection only, as the source writes them.
    private void named(ClientMessages.FileName answer) {
        if (this.asked.containsKey(answer.file())) {
            shown(answer.name()).ifPresent(name -> this.listener.named(this, answer.file(), name));
        }
    }

    // A name from the network as text to show: control characters made spaces, at most MAX_NAME characters.
    static Optional<String> shown(String name) {
        StringBuilder out = new StringBuilder(Math.min(name.length(), MAX_NAME));
        name.strip().codePoints().forEach(c -> {
            if (out.length() + Character.charCount(c) <= MAX_NAME) {
                out.appendCodePoint(Character.isISOControl(c) ? ' ' : c);
            }
        });
        String shown = out.toString().strip();
        return shown.isEmpty() ? Optional.empty() : Optional.of(shown);
    }

    private void requestFile() {
        Hash16 file = this.download.hash();
        this.asked.put(file, this.download);
        this.connection.send(ClientMessages.forFile(Opcodes.Client.REQUESTFILENAME, file));
        this.connection.send(ClientMessages.forFile(Opcodes.Client.SETREQFILEID, file));
        // Without a trusted AICH hash, the sources' announcements may make one.
        if (this.download.file().aichHash().isEmpty()
                && this.peer.map(Hello::supportsAich).orElse(false)) {
            this.connection.send(Packet.of(
                    Protocol.EMULE, Opcodes.Extended.AICHFILEHASHREQ.code(), new PacketWriter().hash16(file)));
        }
        if (!this.download.file().hasHashSet()) {
            this.connection.send(ClientMessages.forFile(Opcodes.Client.HASHSETREQUEST, file));
        }
        boolean exchanges = this.peer
                .map(p -> (p.miscOptions2() & Hello.MISC2_SOURCE_EXCHANGE2) != 0)
                .orElse(false);
        if (exchanges && this.listener.maySourcesBeAsked(this)) {
            this.sourcesAsked.add(file);
            this.connection.send(SourceExchange.request(
                    file, extendedExchange() ? SourceExchange.EXTENDED_VERSION : SourceExchange.VERSION));
        }
    }

    // Both announce the extended source exchange: the source answers in it, whatever version is asked for.
    private boolean extendedExchange() {
        return this.peer.map(Hello::supportsExtendedSourceExchange).orElse(false)
                && this.ourHello.get().supportsExtendedSourceExchange();
    }

    // One file request at a time: the next goes out once the source answered the last, and before any upload request.
    private boolean askForAnother(boolean hasFile) {
        if (this.uploadRequested || this.state == State.ENDED) {
            return false;
        }
        Optional<DownloadQueue.Download> next = this.listener.another(this, hasFile);
        if (next.isEmpty()) {
            return false;
        }
        this.sourceParts = null;
        logger.debug(
                "Asking {} for {} instead of {}",
                this.address,
                next.get().file().name(),
                this.download.file().name());
        this.download = next.get();
        requestFile();
        return true;
    }

    private void setParts(BitSet parts) {
        this.sourceParts = parts;
        this.listener.partsKnown(this);
        if (!hasNeededParts()) {
            if (!askForAnother(true)) {
                close("the source has no part we need");
            }
            return;
        }
        if (!this.uploadRequested) {
            this.uploadRequested = true;
            this.state = State.QUEUED;
            setReadTimeout(QUEUED_TIMEOUT);
            this.connection.send(ClientMessages.forFile(Opcodes.Client.STARTUPLOADREQ, this.download.hash()));
        }
        startDownloading();
    }

    // The hash set of a download asked for before a swap to another still counts, while the download is ours.
    private void hashSet(ClientMessages.HashSet set) {
        DownloadQueue.Download target = this.asked.get(set.file());
        if (target != null
                && this.queue.get(set.file()).orElse(null) == target
                && !target.file().hasHashSet()) {
            target.file().setHashSet(set.parts());
            this.queue.hashSetKnown(target);
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
        setReadTimeout(DATA_TIMEOUT);
        logger.debug("Downloading {} from {}", this.download.file().name(), this.address);
        requestBlocks();
    }

    private void requestBlocks() {
        if (this.state != State.DOWNLOADING) {
            return;
        }
        if (!this.download.isActive()) {
            close("the download is not active");
            return;
        }
        int wanted = ClientMessages.BLOCKS_PER_REQUEST - this.pending.size();
        int allowed = wanted > 0 ? this.listener.allowance(this, wanted) : 0;
        if (wanted > 0 && allowed == 0) {
            // Over the download limit: woken once there is budget again, and not idle meanwhile.
            stopIdle();
            return;
        }
        List<PartFile.Block> next =
                allowed > 0 ? this.download.file().nextBlocks(this.sourceParts, allowed) : List.of();
        if (next.isEmpty() && allowed > 0) {
            // Every missing block is asked of a source already: near the end, some of theirs are asked of this one too.
            List<PartFile.Block> own =
                    this.pending.values().stream().map(p -> p.block).toList();
            next = this.download.file().endgameBlocks(this.sourceParts, allowed, own);
        }
        if (next.isEmpty()) {
            if (this.pending.isEmpty() && this.idle == null) {
                this.idle = this.loop.schedule("idle download slot", IDLE_SLOT, () -> {
                    this.idle = null;
                    if (this.state != State.DOWNLOADING || !this.pending.isEmpty()) {
                        return;
                    }
                    if (hasHeldPart()) {
                        // The slot stays while a part it has waits for its recovery data.
                        requestBlocks();
                    } else {
                        close("nothing more to download from this source");
                    }
                });
            }
            return;
        }
        stopIdle();
        List<ClientMessages.Range> ranges = new ArrayList<>(ClientMessages.BLOCKS_PER_REQUEST);
        boolean wide = false;
        long bytes = 0;
        for (PartFile.Block block : next) {
            this.pending.put(block.start(), new Pending(block));
            ranges.add(new ClientMessages.Range(block.start(), block.end()));
            wide |= block.end() > 0xFFFF_FFFFL;
            bytes += block.length();
        }
        this.listener.requested(this, bytes);
        this.progressAt = this.ticker.read();
        while (ranges.size() < ClientMessages.BLOCKS_PER_REQUEST) {
            ranges.add(new ClientMessages.Range(0, 0));
        }
        this.connection.send(new ClientMessages.RequestParts(this.download.hash(), ranges).encode(wide));
    }

    private void data(ClientMessages.SendingPart part) {
        if (!part.file().equals(this.download.hash())) {
            unexpected(part.start());
            return;
        }
        byte[] data = part.data();
        accept(part.start(), data, data.length);
    }

    private void compressed(ClientMessages.CompressedPart part) throws DecodeException {
        if (this.state != State.DOWNLOADING) {
            return;
        }
        byte[] chunk = part.chunk();
        boolean ours = part.file().equals(this.download.hash());
        if (ours && dropsBroken(part.blockStart(), chunk.length)) {
            return;
        }
        Pending block = this.pending.get(part.blockStart());
        if (block == null || !ours) {
            unexpected(part.blockStart());
            return;
        }
        if (block.inflater == null) {
            block.inflater = new BlockInflater(block.block.start(), Math.toIntExact(block.block.length()));
        }
        // zlib may hold the output of a chunk back: the chunk alone is progress.
        this.progressAt = this.ticker.read();
        block.compressed += chunk.length;
        long position = block.inflater.position();
        byte[] data;
        try {
            data = block.inflater.inflate(chunk);
        } catch (DecodeException e) {
            corrupt(block, part.compressedSize(), e);
            return;
        }
        boolean ended = block.inflater.finished();
        accept(position, data, chunk.length);
        // A stream that ended before its block did brings nothing more: the rest is asked for again now.
        if (ended && this.state == State.DOWNLOADING && this.pending.get(block.block.start()) == block) {
            this.pending.remove(block.block.start());
            block.dispose();
            for (Range<Long> rest : block.missing.asRanges()) {
                this.download.file().release(rest.lowerEndpoint(), rest.upperEndpoint());
            }
            requestBlocks();
        }
    }

    // A stream that does not inflate costs its block, asked for again; the next streams may be fine, but a source that
    // keeps sending broken ones is broken itself.
    private void corrupt(Pending block, long compressedSize, DecodeException cause) throws DecodeException {
        if (++this.corruptStreams >= MAX_CORRUPT_STREAMS) {
            throw cause;
        }
        logger.debug("Compressed block {} from {}: {}", block.block.start(), this.address, cause.getMessage());
        this.pending.remove(block.block.start());
        block.dispose();
        for (Range<Long> rest : block.missing.asRanges()) {
            this.download.file().release(rest.lowerEndpoint(), rest.upperEndpoint());
        }
        long left = compressedSize - block.compressed;
        if (left > 0) {
            this.broken.put(block.block.start(), left);
        }
        requestBlocks();
    }

    // Whether the chunk belongs to the rest of a stream that failed.
    private boolean dropsBroken(long blockStart, int length) {
        Long left = this.broken.get(blockStart);
        if (left == null) {
            return false;
        }
        if (left > length) {
            this.broken.put(blockStart, left - length);
        } else {
            this.broken.remove(blockStart);
        }
        return true;
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
            unexpected(start);
            return;
        }
        this.progressAt = this.ticker.read();
        this.queue.received(this.download, start, data, this.address.getAddress());
        this.listener.received(this, data.length, transferred);
        target.missing.remove(Range.closedOpen(start, end));
        if (target.missing.isEmpty()) {
            target.dispose();
            this.pending.remove(target.block.start());
            requestBlocks();
        }
    }

    // A few packets of data not asked for may still be on the way; more, close together, mean the source is off track.
    private void unexpected(long start) {
        if (this.state != State.DOWNLOADING) {
            return;
        }
        logger.debug("Data at {} from {} was not requested", start, this.address);
        long now = this.ticker.read();
        if (now - this.unexpectedAt > UNEXPECTED_GAP.toNanos()) {
            this.unexpected = 0;
        }
        this.unexpectedAt = now;
        if (++this.unexpected >= MAX_UNEXPECTED) {
            close("the source sends data we did not ask for");
        }
    }

    private void backToQueue(String reason) {
        if (this.state != State.DOWNLOADING) {
            return;
        }
        logger.debug("{}: {}", this.address, reason);
        boolean hadBlocks = !this.pending.isEmpty();
        releaseBlocks();
        stopIdle();
        this.accepted = false;
        this.state = State.QUEUED;
        setReadTimeout(QUEUED_TIMEOUT);
        if (hadBlocks) {
            this.listener.released(this);
        }
    }

    @Override
    public void closed(Optional<String> cause) {
        if (this.state == State.ENDED) {
            return;
        }
        // A queued source keeps our place when the idle connection closes; a lost slot is gone.
        Outcome outcome = switch (this.state) {
            case QUEUED -> Outcome.QUEUED;
            case DOWNLOADING -> Outcome.LATER;
            default -> Outcome.UNREACHABLE;
        };
        end(outcome, "the connection closed" + cause.map(c -> " (" + c + ")").orElse(""));
    }

    private void end(Outcome outcome, String reason) {
        if (this.state == State.ENDED) {
            return;
        }
        this.state = State.ENDED;
        stopIdle();
        releaseBlocks();
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

    private boolean hasHeldPart() {
        PartFile file = this.download.file();
        for (int part = this.sourceParts.nextSetBit(0);
                part >= 0 && part < file.partCount();
                part = this.sourceParts.nextSetBit(part + 1)) {
            if (file.isHeld(part) && !file.isComplete(part)) {
                return true;
            }
        }
        return false;
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
