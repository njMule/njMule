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
import com.google.common.net.InetAddresses;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiConsumer;
import java.util.function.IntPredicate;
import java.util.function.Supplier;
import org.njmule.core.api.DownloadInfo;
import org.njmule.core.api.Ed2kLink;
import org.njmule.core.api.Hash16;
import org.njmule.core.config.ObfuscationMode;
import org.njmule.core.engine.EngineLoop;
import org.njmule.core.files.PartFile;
import org.njmule.core.net.TcpConnection;
import org.njmule.core.peer.Identities;
import org.njmule.core.peer.PeerGreetings;
import org.njmule.core.protocol.ed2k.Hello;
import org.njmule.core.protocol.ed2k.ServerMessages;
import org.njmule.core.util.Ipv4;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The downloads and their sources: sources come from links and from the server, each one is asked
 * for a place in its upload queue and asked again while it keeps us there, and data flows from
 * those that give us a slot. Engine thread only, except {@link #router()}.
 */
public final class DownloadManager implements AutoCloseable {

    /** What the downloads report, on the engine thread. */
    public interface Listener {
        void added(DownloadInfo download);

        /** At most once a second per download. */
        void progress(DownloadInfo download);

        /** {@code part} has the hashes of the finished file, now at {@code file}. */
        void completed(DownloadInfo download, Path file, PartFile part);

        void failed(DownloadInfo download, String reason);

        void removed(Hash16 hash);
    }

    /** The server connection, as far as the downloads use it. */
    public interface ServerLink {
        /** Asks the connected server for the sources of a file; {@code false} without one. */
        boolean askSources(Hash16 file, long size);

        /** Our client id on the server, zero when not connected. */
        long clientId();
    }

    /** What the downloads need from the preferences. */
    public record Settings(ObfuscationMode obfuscation, int maxSourcesPerFile, int listenPort) {}

    /** A source that keeps us in its queue is asked again this often. */
    public static final Duration REASK_INTERVAL = Duration.ofMinutes(29);

    /** Sources ban clients that ask more often than this. */
    public static final Duration MIN_REASK = Duration.ofMinutes(10);

    /** The server is asked for the sources of each download this often. */
    public static final Duration SERVER_SOURCES_INTERVAL = Duration.ofMinutes(20);

    private static final Duration TICK = Duration.ofSeconds(1);
    private static final Duration ROUTE_WAIT = Duration.ofSeconds(5);
    private static final int MAX_STARTING = 8;
    private static final int MAX_FAILURES = 3;
    private static final int RATE_WINDOW = 5;

    private static final Logger LOG = LoggerFactory.getLogger(DownloadManager.class);

    private static final class Source {
        private final InetSocketAddress address;
        private Optional<Hash16> userHash = Optional.empty();
        private long nextAsk;
        private long lastAsk;
        private boolean asked;
        private int failures;
        private BitSet parts;
        private DownloadSession session;

        Source(InetSocketAddress address, long now) {
            this.address = address;
            this.nextAsk = now;
        }
    }

    private static final class Entry {
        private final DownloadQueue.Download download;
        private final Map<InetSocketAddress, Source> sources = new LinkedHashMap<>();
        private final long[] window = new long[RATE_WINDOW];
        private int slot;
        private long received;
        private boolean changed;
        private long nextServerAsk;

        Entry(DownloadQueue.Download download, long now) {
            this.download = download;
            this.nextServerAsk = now;
        }

        long rate() {
            long sum = 0;
            for (long bytes : this.window) {
                sum += bytes;
            }
            return sum / RATE_WINDOW;
        }
    }

    private final EngineLoop loop;
    private final Supplier<Hello> ourHello;
    private final Supplier<Settings> settings;
    private final ServerLink server;
    private final IntPredicate blocked;
    private final SecureRandom random;
    private final Ticker ticker;
    private final Identities identities;
    private final BiConsumer<Hash16, Long> credit;
    private final Listener listener;
    private final Map<Hash16, Entry> entries = new LinkedHashMap<>();
    private final SessionEvents sessionEvents = new SessionEvents();
    private DownloadQueue queue;
    private ScheduledFuture<?> ticking;

    private DownloadManager(
            EngineLoop loop,
            Supplier<Hello> ourHello,
            Supplier<Settings> settings,
            ServerLink server,
            IntPredicate blocked,
            SecureRandom random,
            Ticker ticker,
            Identities identities,
            BiConsumer<Hash16, Long> credit,
            Listener listener) {
        this.loop = Objects.requireNonNull(loop, "loop");
        this.ourHello = Objects.requireNonNull(ourHello, "ourHello");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.server = Objects.requireNonNull(server, "server");
        this.blocked = Objects.requireNonNull(blocked, "blocked");
        this.random = Objects.requireNonNull(random, "random");
        this.ticker = Objects.requireNonNull(ticker, "ticker");
        this.identities = Objects.requireNonNull(identities, "identities");
        this.credit = Objects.requireNonNull(credit, "credit");
        this.listener = Objects.requireNonNull(listener, "listener");
    }

    /**
     * Loads the downloads of {@code tempDir} and starts asking for sources.
     *
     * @param blocked IP addresses (host order) that the IP filter refuses
     * @param credit credits a trusted source's user hash with the bytes it sent us
     */
    public static DownloadManager open(
            EngineLoop loop,
            Path tempDir,
            Supplier<Path> incomingDir,
            Supplier<Hello> ourHello,
            Supplier<Settings> settings,
            ServerLink server,
            IntPredicate blocked,
            SecureRandom random,
            Ticker ticker,
            Identities identities,
            BiConsumer<Hash16, Long> credit,
            Listener listener)
            throws IOException {
        DownloadManager manager =
                new DownloadManager(
                        loop,
                        ourHello,
                        settings,
                        server,
                        blocked,
                        random,
                        ticker,
                        identities,
                        credit,
                        listener);
        manager.queue = DownloadQueue.load(loop, tempDir, incomingDir, manager.new QueueEvents());
        long now = ticker.read();
        for (DownloadQueue.Download download : manager.queue.all()) {
            manager.entries.put(download.hash(), new Entry(download, now));
        }
        manager.ticking = loop.scheduleRepeating("downloads", TICK, TICK, manager::tick);
        return manager;
    }

    /** Adds a download, or the link's sources to the existing one. */
    public DownloadInfo add(Ed2kLink.FileLink link) throws IOException {
        DownloadQueue.Download download = this.queue.add(link);
        Entry entry =
                this.entries.computeIfAbsent(
                        download.hash(), hash -> new Entry(download, this.ticker.read()));
        for (Ed2kLink.Source source : link.sources()) {
            this.addLinkSource(entry, source);
        }
        return this.info(entry);
    }

    public List<DownloadInfo> downloads() {
        List<DownloadInfo> all = new ArrayList<>(this.entries.size());
        for (Entry entry : this.entries.values()) {
            all.add(this.info(entry));
        }
        return all;
    }

    /** The download of {@code hash}, whose checked parts other clients may download. */
    public Optional<DownloadQueue.Download> find(Hash16 hash) {
        return Optional.ofNullable(this.entries.get(hash)).map(e -> e.download);
    }

    public Optional<DownloadInfo> download(Hash16 hash) {
        return Optional.ofNullable(this.entries.get(hash)).map(this::info);
    }

    public boolean pause(Hash16 hash) {
        Entry entry = this.entries.get(hash);
        if (entry == null) {
            return false;
        }
        this.queue.pause(entry.download);
        this.closeSessions(entry, "the download is paused");
        this.listener.progress(this.info(entry));
        return true;
    }

    public boolean resume(Hash16 hash) {
        Entry entry = this.entries.get(hash);
        if (entry == null) {
            return false;
        }
        this.queue.resume(entry.download);
        long now = this.ticker.read();
        for (Source source : entry.sources.values()) {
            source.nextAsk =
                    source.asked ? Math.max(now, source.lastAsk + MIN_REASK.toNanos()) : now;
        }
        entry.nextServerAsk = now;
        this.listener.progress(this.info(entry));
        return true;
    }

    /** Stops the download and deletes its data. */
    public boolean cancel(Hash16 hash) {
        Entry entry = this.entries.remove(hash);
        if (entry == null) {
            return false;
        }
        this.closeSessions(entry, "the download is cancelled");
        this.queue.cancel(entry.download);
        this.listener.removed(hash);
        return true;
    }

    /** A server connection was made: it is asked for the sources of every download. */
    public void serverConnected() {
        long now = this.ticker.read();
        for (Entry entry : this.entries.values()) {
            entry.nextServerAsk = now;
        }
    }

    /** Sources the server found. LowID sources need a callback through the server: not yet. */
    public void foundSources(ServerMessages.FoundSources found) {
        Entry entry = this.entries.get(found.file());
        if (entry == null || !entry.download.isActive()) {
            return;
        }
        long ourId = this.server.clientId();
        int ourPort = this.settings.get().listenPort();
        int added = 0;
        for (ServerMessages.Source source : found.sources()) {
            if (source.isLowId() || source.clientId() == ourId && source.port() == ourPort) {
                continue;
            }
            int ip = Ipv4.fromEd2k(source.clientId());
            if (!Ipv4.isUnicastPublicCandidate(ip) || this.blocked.test(ip) || source.port() == 0) {
                continue;
            }
            Source fresh =
                    this.addSource(entry, new InetSocketAddress(Ipv4.format(ip), source.port()));
            if (fresh != null) {
                fresh.userHash = source.userHash();
                added++;
            }
        }
        LOG.debug(
                "{} of {} sources from the server are new for {}",
                added,
                found.sources().size(),
                entry.download.file().name());
    }

    /**
     * Hands an incoming connection to the download whose source opened it, as a source does when it
     * has a slot for us. Called on the connection's thread; waits for the engine thread.
     */
    public PeerGreetings.Router router() {
        return (connection, peer) -> {
            try {
                return this.loop
                        .submit(() -> this.adopt(connection, peer))
                        .get(ROUTE_WAIT.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Optional.empty();
            } catch (ExecutionException | TimeoutException | RuntimeException e) {
                LOG.debug("Cannot route the connection from {}", connection.remoteAddress(), e);
                return Optional.empty();
            }
        };
    }

    /** Ends every session and saves the downloads. */
    @Override
    public void close() {
        if (this.ticking != null) {
            this.ticking.cancel(false);
        }
        for (Entry entry : this.entries.values()) {
            this.closeSessions(entry, "shutting down");
        }
        this.queue.close();
    }

    private Optional<TcpConnection.Handler> adopt(TcpConnection connection, Hello peer) {
        InetAddress from = connection.remoteAddress().getAddress();
        for (Entry entry : this.entries.values()) {
            if (!entry.download.isActive()) {
                continue;
            }
            for (Source source : entry.sources.values()) {
                boolean same =
                        source.userHash.map(peer.userHash()::equals).orElse(false)
                                || source.address.getAddress().equals(from)
                                        && source.address.getPort() == peer.tcpPort();
                if (!same || !source.asked) {
                    continue;
                }
                DownloadSession old = source.session;
                if (old != null && old.state() != DownloadSession.State.QUEUED) {
                    return Optional.empty();
                }
                if (old != null) {
                    source.session = null;
                    old.close("the source called back");
                }
                DownloadSession session =
                        new DownloadSession(
                                this.loop,
                                this.queue,
                                entry.download,
                                source.address,
                                this.ourHello,
                                this.identities,
                                this.sessionEvents);
                source.session = session;
                source.userHash = Optional.of(peer.userHash());
                LOG.debug("{} called back for {}", source.address, entry.download.file().name());
                return Optional.of(
                        session.adopt(connection, peer, Optional.ofNullable(source.parts)));
            }
        }
        return Optional.empty();
    }

    private void tick() {
        long now = this.ticker.read();
        int starting = 0;
        for (Entry entry : this.entries.values()) {
            for (Source source : entry.sources.values()) {
                if (source.session != null && isStarting(source.session)) {
                    starting++;
                }
            }
        }
        // One source request per tick, so the server is never flooded.
        boolean askedServer = false;
        for (Entry entry : this.entries.values()) {
            entry.window[entry.slot] = entry.received;
            entry.slot = (entry.slot + 1) % RATE_WINDOW;
            if (entry.received > 0) {
                entry.changed = true;
            }
            entry.received = 0;
            if (entry.download.isActive()) {
                if (!askedServer
                        && now - entry.nextServerAsk >= 0
                        && this.server.askSources(
                                entry.download.hash(), entry.download.file().size())) {
                    askedServer = true;
                    entry.nextServerAsk = now + SERVER_SOURCES_INTERVAL.toNanos();
                }
                starting += this.startSessions(entry, now, MAX_STARTING - starting);
            }
            if (entry.changed) {
                entry.changed = false;
                this.listener.progress(this.info(entry));
            }
        }
    }

    private int startSessions(Entry entry, long now, int budget) {
        int started = 0;
        boolean required = this.settings.get().obfuscation() == ObfuscationMode.REQUIRED;
        boolean disabled = this.settings.get().obfuscation() == ObfuscationMode.DISABLED;
        for (Source source : entry.sources.values()) {
            if (started >= budget) {
                break;
            }
            if (source.session != null || now - source.nextAsk < 0) {
                continue;
            }
            if (required && source.userHash.isEmpty()) {
                continue;
            }
            source.asked = true;
            source.lastAsk = now;
            source.nextAsk = now + REASK_INTERVAL.toNanos();
            source.session =
                    new DownloadSession(
                            this.loop,
                            this.queue,
                            entry.download,
                            source.address,
                            this.ourHello,
                            this.identities,
                            this.sessionEvents);
            source.session.connect(disabled ? Optional.empty() : source.userHash, this.random);
            started++;
        }
        return started;
    }

    private static boolean isStarting(DownloadSession session) {
        return switch (session.state()) {
            case CONNECTING, GREETING, REQUESTING -> true;
            default -> false;
        };
    }

    private void addLinkSource(Entry entry, Ed2kLink.Source source) {
        if (InetAddresses.isInetAddress(source.host())) {
            InetAddress address = InetAddresses.forString(source.host());
            if (address instanceof Inet4Address) {
                this.addSource(entry, new InetSocketAddress(address, source.port()));
            }
            return;
        }
        Hash16 hash = entry.download.hash();
        Thread.ofVirtual()
                .name("njmule-resolve-source")
                .start(
                        () -> {
                            try {
                                for (InetAddress address :
                                        InetAddress.getAllByName(source.host())) {
                                    if (address instanceof Inet4Address) {
                                        InetSocketAddress resolved =
                                                new InetSocketAddress(address, source.port());
                                        this.loop.post(
                                                () ->
                                                        Optional.ofNullable(this.entries.get(hash))
                                                                .ifPresent(
                                                                        e ->
                                                                                this.addSource(
                                                                                        e,
                                                                                        resolved)));
                                        return;
                                    }
                                }
                            } catch (UnknownHostException e) {
                                LOG.debug("Cannot resolve the source {}", source.host());
                            }
                        });
    }

    // Returns the new source, or null if it was known or the list is full.
    private Source addSource(Entry entry, InetSocketAddress address) {
        if (entry.sources.containsKey(address)
                || entry.sources.size() >= this.settings.get().maxSourcesPerFile()) {
            return null;
        }
        Source source = new Source(address, this.ticker.read());
        entry.sources.put(address, source);
        entry.changed = true;
        return source;
    }

    private void closeSessions(Entry entry, String reason) {
        for (Source source : List.copyOf(entry.sources.values())) {
            DownloadSession session = source.session;
            if (session != null) {
                source.session = null;
                session.close(reason);
            }
        }
    }

    private DownloadInfo info(Entry entry) {
        PartFile file = entry.download.file();
        int transferring = 0;
        for (Source source : entry.sources.values()) {
            if (source.session != null
                    && source.session.state() == DownloadSession.State.DOWNLOADING) {
                transferring++;
            }
        }
        return new DownloadInfo(
                file.hash(),
                file.name(),
                file.size(),
                file.completedBytes(),
                status(file.status()),
                entry.sources.size(),
                transferring,
                entry.rate());
    }

    private static DownloadInfo.Status status(PartFile.Status status) {
        return switch (status) {
            case DOWNLOADING -> DownloadInfo.Status.DOWNLOADING;
            case PAUSED -> DownloadInfo.Status.PAUSED;
            case ERROR -> DownloadInfo.Status.ERROR;
            case COMPLETE -> DownloadInfo.Status.COMPLETE;
        };
    }

    private final class SessionEvents implements DownloadSession.Listener {
        @Override
        public void received(DownloadSession session, int bytes, int transferred) {
            Entry entry = DownloadManager.this.entries.get(session.download().hash());
            if (entry != null) {
                entry.received += bytes;
            }
            if (session.trusted() && session.peerHash().isPresent()) {
                DownloadManager.this.credit.accept(session.peerHash().get(), (long) transferred);
            }
        }

        @Override
        public void ended(DownloadSession session, DownloadSession.Outcome outcome, String reason) {
            Entry entry = DownloadManager.this.entries.get(session.download().hash());
            Source source = entry == null ? null : entry.sources.get(session.address());
            if (source == null || source.session != session) {
                return;
            }
            source.session = null;
            session.sourceParts().ifPresent(parts -> source.parts = parts);
            session.peerHash().ifPresent(hash -> source.userHash = Optional.of(hash));
            LOG.debug("{} ({}): {}", source.address, outcome, reason);
            long now = DownloadManager.this.ticker.read();
            switch (outcome) {
                case QUEUED, LATER -> {
                    source.failures = 0;
                    source.nextAsk = source.lastAsk + REASK_INTERVAL.toNanos();
                }
                case UNREACHABLE -> {
                    source.failures++;
                    source.nextAsk = now + MIN_REASK.toNanos();
                    if (source.failures >= MAX_FAILURES) {
                        entry.sources.remove(source.address);
                    }
                }
                case GONE -> entry.sources.remove(source.address);
            }
            entry.changed = true;
        }
    }

    private final class QueueEvents implements DownloadQueue.Listener {
        @Override
        public void added(DownloadQueue.Download download) {
            Entry entry = new Entry(download, DownloadManager.this.ticker.read());
            DownloadManager.this.entries.put(download.hash(), entry);
            DownloadManager.this.listener.added(DownloadManager.this.info(entry));
        }

        @Override
        public void progress(DownloadQueue.Download download) {
            Entry entry = DownloadManager.this.entries.get(download.hash());
            if (entry == null) {
                return;
            }
            entry.changed = true;
            for (Source source : entry.sources.values()) {
                if (source.session != null) {
                    source.session.wake();
                }
            }
        }

        @Override
        public void completed(DownloadQueue.Download download, Path file) {
            Entry entry = DownloadManager.this.entries.remove(download.hash());
            if (entry == null) {
                return;
            }
            DownloadManager.this.closeSessions(entry, "the download is complete");
            DownloadManager.this.listener.completed(
                    DownloadManager.this.info(entry), file, download.file());
        }

        @Override
        public void failed(DownloadQueue.Download download, String reason) {
            Entry entry = DownloadManager.this.entries.get(download.hash());
            if (entry == null) {
                return;
            }
            DownloadManager.this.closeSessions(entry, reason);
            DownloadManager.this.listener.failed(DownloadManager.this.info(entry), reason);
        }
    }
}
