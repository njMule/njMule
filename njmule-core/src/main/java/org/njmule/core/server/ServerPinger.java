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

import java.io.IOException;
import java.net.InetSocketAddress;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ScheduledFuture;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import org.njmule.core.engine.EngineLoop;
import org.njmule.core.persist.format.ServerMet;
import org.njmule.core.protocol.ed2k.ServerDatagrams;
import org.njmule.core.protocol.io.DecodeException;
import org.njmule.core.protocol.packet.Packet;
import org.njmule.core.protocol.tag.ServerTag;
import org.njmule.core.protocol.tag.Tag;
import org.njmule.core.protocol.tag.TagName;
import org.njmule.core.util.Ipv4;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Asks the known servers for their status over UDP: one server every {@link #INTERVAL}, each at
 * most every {@link #REPING_AFTER}. An answer counts only from the pinged server's UDP ports with
 * the challenge that was sent; it updates the server's load, limits, UDP capabilities and
 * obfuscation key and ports. Engine thread only.
 */
public final class ServerPinger {

    public static final Duration INTERVAL = Duration.ofSeconds(5);
    public static final Duration REPING_AFTER = Duration.ofMinutes(270);

    private static final Logger logger = LoggerFactory.getLogger(ServerPinger.class);
    private static final Duration ANSWER_TIMEOUT = Duration.ofSeconds(30);
    private static final long CHALLENGE_BASE = 0x55AA_0000L;
    // Servers that announce obfuscation without ports use these defaults.
    private static final int OBFUSCATED_UDP_OFFSET = 12;

    private record Pending(long challenge, long sentAt) {}

    private final EngineLoop loop;
    private final ServerList servers;
    private final Supplier<Optional<ServerUdp>> udp;
    private final IntSupplier publicIp;
    private final Consumer<ServerMet.Server> updated;
    private final SecureRandom random;
    private final Clock clock;
    private final Map<ServerList.Address, Pending> pending = new HashMap<>();
    private ScheduledFuture<?> timer;
    private int cursor;

    /**
     * @param publicIp our address as the connected server sees it; the UDP key is bound to it
     * @param updated told about each server an answer updated
     */
    public ServerPinger(
            EngineLoop loop,
            ServerList servers,
            Supplier<Optional<ServerUdp>> udp,
            IntSupplier publicIp,
            Consumer<ServerMet.Server> updated,
            SecureRandom random,
            Clock clock) {
        this.loop = Objects.requireNonNull(loop, "loop");
        this.servers = Objects.requireNonNull(servers, "servers");
        this.udp = Objects.requireNonNull(udp, "udp");
        this.publicIp = Objects.requireNonNull(publicIp, "publicIp");
        this.updated = Objects.requireNonNull(updated, "updated");
        this.random = Objects.requireNonNull(random, "random");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public void start() {
        if (this.timer == null) {
            this.timer =
                    this.loop.scheduleRepeating(
                            "server status pings", INTERVAL, INTERVAL, this::pingNext);
        }
    }

    public void stop() {
        if (this.timer != null) {
            this.timer.cancel(false);
            this.timer = null;
        }
        this.pending.clear();
    }

    /** A status answer received from {@code from}. */
    public void received(InetSocketAddress from, Packet packet) {
        ServerDatagrams.Status status;
        try {
            status = ServerDatagrams.Status.decode(packet);
        } catch (DecodeException e) {
            logger.debug("Malformed status from {}", from, e);
            return;
        }
        int ip = Ipv4.fromBytes(from.getAddress().getAddress());
        for (ServerMet.Server server : this.servers.candidates()) {
            ServerList.Address address = ServerList.addressOf(server);
            Pending sent = this.pending.get(address);
            if (sent != null
                    && server.ip() == ip
                    && isUdpPortOf(server, from.getPort())
                    && sent.challenge() == status.challenge()) {
                this.pending.remove(address);
                this.servers.update(address, s -> apply(s, status, this.publicIp.getAsInt()));
                this.servers.get(address).ifPresent(this.updated);
                return;
            }
        }
        logger.debug("Unrequested status from {}", from);
    }

    private void pingNext() {
        long now = this.clock.instant().getEpochSecond();
        this.pending.values().removeIf(p -> now - p.sentAt() > ANSWER_TIMEOUT.toSeconds());
        Optional<ServerUdp> socket = this.udp.get();
        List<ServerMet.Server> candidates = this.servers.candidates();
        if (socket.isEmpty() || candidates.isEmpty()) {
            return;
        }
        for (int i = 0; i < candidates.size(); i++) {
            int index = (this.cursor + i) % candidates.size();
            ServerMet.Server server = candidates.get(index);
            ServerList.Address address = ServerList.addressOf(server);
            long last = server.lastPing().orElse(0);
            if (now - last < REPING_AFTER.toSeconds() || this.pending.containsKey(address)) {
                continue;
            }
            this.cursor = index + 1;
            long challenge = CHALLENGE_BASE | (this.random.nextInt() & 0xFFFF);
            InetSocketAddress target =
                    new InetSocketAddress(
                            Ipv4.format(server.ip()), server.port() + ServerUdp.PORT_OFFSET);
            try {
                socket.get().send(target, ServerDatagrams.statusRequest(challenge));
                this.pending.put(address, new Pending(challenge, now));
                // Recorded on sending, so a silent server is not asked again at every start.
                this.servers.update(address, s -> s.with(intTag(ServerTag.LASTPING, now)));
            } catch (IOException e) {
                logger.debug("Cannot ping {}", target, e);
            }
            return;
        }
    }

    private static boolean isUdpPortOf(ServerMet.Server server, int port) {
        return port == server.port() + ServerUdp.PORT_OFFSET
                || port == server.port() + OBFUSCATED_UDP_OFFSET
                || port == server.obfuscatedUdpPort().orElse(-1);
    }

    static ServerMet.Server apply(
            ServerMet.Server server, ServerDatagrams.Status status, int ourIp) {
        int tcpPort = status.obfuscatedTcpPort();
        int udpPort = status.obfuscatedUdpPort();
        if (status.has(ServerDatagrams.UDP_TCP_OBFUSCATION) && tcpPort == 0) {
            tcpPort = server.port();
        }
        if (status.has(ServerDatagrams.UDP_OBFUSCATION) && udpPort == 0) {
            udpPort = server.port() + OBFUSCATED_UDP_OFFSET;
        }
        ServerMet.Server updated =
                server.with(intTag(ServerTag.USERS, status.users()))
                        .with(intTag(ServerTag.FILES, status.files()))
                        .with(intTag(ServerTag.MAXUSERS, status.maxUsers()))
                        .with(intTag(ServerTag.SOFTFILES, status.softFiles()))
                        .with(intTag(ServerTag.HARDFILES, status.hardFiles()))
                        .with(intTag(ServerTag.UDPFLAGS, Integer.toUnsignedLong(status.udpFlags())))
                        .with(intTag(ServerTag.LOWIDUSERS, status.lowIdUsers()))
                        .with(intTag(ServerTag.TCPPORTOBFUSCATION, tcpPort))
                        .with(intTag(ServerTag.UDPPORTOBFUSCATION, udpPort))
                        .with(intTag(ServerTag.FAIL, 0));
        if (status.udpKey() != 0) {
            updated =
                    updated.with(intTag(ServerTag.UDPKEY, status.udpKey()))
                            .with(intTag(ServerTag.UDPKEYIP, Ipv4.toEd2k(ourIp)));
        }
        return updated;
    }

    private static Tag intTag(int id, long value) {
        return new Tag.IntTag(TagName.id(id), value);
    }

    private static Tag intTag(String name, long value) {
        return new Tag.IntTag(TagName.of(name), value);
    }
}
