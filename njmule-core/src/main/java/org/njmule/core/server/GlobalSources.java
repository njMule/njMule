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
package org.njmule.core.server;

import com.google.common.base.Ticker;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetSocketAddress;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import org.njmule.core.api.Hash16;
import org.njmule.core.engine.EngineLoop;
import org.njmule.core.persist.format.ServerMet;
import org.njmule.core.protocol.ed2k.ServerDatagrams;
import org.njmule.core.protocol.ed2k.ServerMessages;
import org.njmule.core.protocol.io.DecodeException;
import org.njmule.core.protocol.packet.Packet;
import org.njmule.core.util.Ipv4;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Asks the servers other than the one we are on for the sources of the downloads over UDP: one request every
 * {@link #INTERVAL}, at most {@link #MAX_FILES_PER_SERVER} files per server in a pass over the list, and
 * {@link #PASS_PAUSE} between passes. An answer counts only from the address a server was asked at, for the files it
 * was asked for, and only its HighID sources: a LowID one could only be reached through that server. Engine thread
 * only.
 */
public final class GlobalSources {

    /** What the downloads want and take. */
    public interface Downloads {
        /** The downloads that want more sources, the most wanted first. */
        List<ServerDatagrams.SourceQuery> wanting();

        /** HighID sources a server found for a download. */
        void found(ServerMessages.FoundSources sources);
    }

    public static final Duration INTERVAL = Duration.ofSeconds(1);
    public static final Duration PASS_PAUSE = Duration.ofMinutes(30);
    public static final int MAX_FILES_PER_SERVER = 35;

    private static final Logger logger = LoggerFactory.getLogger(GlobalSources.class);
    private static final Duration ANSWER_TIMEOUT = Duration.ofSeconds(30);

    private record Request(ServerRoute route, Packet packet) {}

    private record Asked(Set<Hash16> files, long sentAt) {}

    private final EngineLoop loop;
    private final ServerList servers;
    private final Supplier<Optional<ServerUdp>> udp;
    private final Function<ServerMet.Server, Optional<ServerRoute>> route;
    private final Supplier<Optional<ServerMet.Server>> connected;
    private final IntSupplier deadRetries;
    private final BooleanSupplier allowed;
    private final Downloads downloads;
    private final SecureRandom random;
    private final Ticker ticker;
    // By the address and UDP port asked at.
    private final Map<Long, Asked> asked = new HashMap<>();
    private final Deque<ServerMet.Server> left = new ArrayDeque<>();
    private final Deque<Request> requests = new ArrayDeque<>();
    private ScheduledFuture<?> timer;
    private boolean passing;
    private long nextPass;
    // Where the next server's files start, so that each server is asked for other files when there are many.
    private int offset;

    /**
     * @param route how a server is asked, empty when it cannot be
     * @param connected the server we are on, which is not asked
     * @param deadRetries the failures in a row after which a server is not asked
     * @param allowed whether sources without a user hash can be used, so are worth asking for
     */
    public GlobalSources(
            EngineLoop loop,
            ServerList servers,
            Supplier<Optional<ServerUdp>> udp,
            Function<ServerMet.Server, Optional<ServerRoute>> route,
            Supplier<Optional<ServerMet.Server>> connected,
            IntSupplier deadRetries,
            BooleanSupplier allowed,
            Downloads downloads,
            SecureRandom random,
            Ticker ticker) {
        this.loop = Objects.requireNonNull(loop, "loop");
        this.servers = Objects.requireNonNull(servers, "servers");
        this.udp = Objects.requireNonNull(udp, "udp");
        this.route = Objects.requireNonNull(route, "route");
        this.connected = Objects.requireNonNull(connected, "connected");
        this.deadRetries = Objects.requireNonNull(deadRetries, "deadRetries");
        this.allowed = Objects.requireNonNull(allowed, "allowed");
        this.downloads = Objects.requireNonNull(downloads, "downloads");
        this.random = Objects.requireNonNull(random, "random");
        this.ticker = Objects.requireNonNull(ticker, "ticker");
        this.nextPass = ticker.read();
    }

    /** Starts asking, once connected to a server: the first pass starts at once unless one ended lately. */
    public void start() {
        if (this.timer == null) {
            this.timer = this.loop.scheduleRepeating("server source requests", INTERVAL, INTERVAL, this::tick);
        }
    }

    public void stop() {
        if (this.timer != null) {
            this.timer.cancel(false);
            this.timer = null;
        }
        this.left.clear();
        this.requests.clear();
        this.asked.clear();
        if (this.passing) {
            this.passing = false;
            this.nextPass = this.ticker.read() + PASS_PAUSE.toNanos();
        }
    }

    /** A source answer received from {@code from}. */
    public void received(InetSocketAddress from, Packet packet) {
        Asked sent = this.asked.get(slot(from));
        if (sent == null) {
            logger.debug("Unrequested sources from {}", from);
            return;
        }
        List<ServerMessages.FoundSources> found;
        try {
            found = ServerDatagrams.decodeFoundSources(packet);
        } catch (DecodeException e) {
            logger.debug("Malformed sources from {}", from, e);
            return;
        }
        for (ServerMessages.FoundSources file : found) {
            if (!sent.files().contains(file.file())) {
                continue;
            }
            List<ServerMessages.Source> reachable =
                    file.sources().stream().filter(s -> !s.isLowId()).toList();
            if (!reachable.isEmpty()) {
                this.downloads.found(new ServerMessages.FoundSources(file.file(), reachable));
            }
        }
    }

    void tick() {
        long now = this.ticker.read();
        this.asked.values().removeIf(a -> now - a.sentAt() > ANSWER_TIMEOUT.toNanos());
        Optional<ServerUdp> socket = this.udp.get();
        if (socket.isEmpty() || !this.allowed.getAsBoolean() || (this.requests.isEmpty() && !queueNextServer(now))) {
            return;
        }
        Request request = this.requests.poll();
        try {
            socket.get().send(request.route(), request.packet(), this.random);
        } catch (IOException e) {
            logger.debug("Cannot ask {} for sources", request.route().target(), e);
        }
    }

    // Queues the requests for the next server to ask; false when there is none now.
    private boolean queueNextServer(long now) {
        while (true) {
            if (this.left.isEmpty()) {
                if (this.passing) {
                    this.passing = false;
                    this.nextPass = now + PASS_PAUSE.toNanos();
                    return false;
                }
                if (now - this.nextPass < 0 || this.downloads.wanting().isEmpty()) {
                    return false;
                }
                this.left.addAll(this.servers.candidates());
                this.passing = !this.left.isEmpty();
                if (!this.passing) {
                    return false;
                }
            }
            ServerMet.Server server = this.left.poll();
            Optional<ServerRoute> way = askable(server) ? this.route.apply(server) : Optional.empty();
            if (way.isEmpty()) {
                continue;
            }
            List<ServerDatagrams.SourceQuery> files = nextFiles();
            if (files.isEmpty()) {
                // Nothing is wanted any more: the pass ends here.
                this.left.clear();
                continue;
            }
            List<Packet> packets = ServerDatagrams.sourceRequests(
                    files, (int) server.udpFlags().orElse(0));
            if (packets.isEmpty()) {
                continue;
            }
            Set<Hash16> hashes = new HashSet<>();
            files.forEach(f -> hashes.add(f.file()));
            this.asked.put(slot(way.get().target()), new Asked(hashes, now));
            packets.forEach(p -> this.requests.add(new Request(way.get(), p)));
            return true;
        }
    }

    private boolean askable(ServerMet.Server server) {
        Optional<ServerList.Address> current = this.connected.get().map(ServerList::addressOf);
        return server.ip() != 0
                && server.port() + ServerUdp.PORT_OFFSET <= 0xFFFF
                && server.failedCount().orElse(0) < this.deadRetries.getAsInt()
                && !current.equals(Optional.of(ServerList.addressOf(server)));
    }

    private List<ServerDatagrams.SourceQuery> nextFiles() {
        List<ServerDatagrams.SourceQuery> wanting = this.downloads.wanting();
        if (wanting.size() <= MAX_FILES_PER_SERVER) {
            return wanting;
        }
        List<ServerDatagrams.SourceQuery> files = new ArrayList<>(MAX_FILES_PER_SERVER);
        for (int i = 0; i < MAX_FILES_PER_SERVER; i++) {
            files.add(wanting.get((this.offset + i) % wanting.size()));
        }
        this.offset = (this.offset + MAX_FILES_PER_SERVER) % wanting.size();
        return files;
    }

    private static long slot(InetSocketAddress address) {
        return address.getAddress() instanceof Inet4Address v4
                ? Integer.toUnsignedLong(Ipv4.fromBytes(v4.getAddress())) << 16 | address.getPort()
                : -1;
    }
}
