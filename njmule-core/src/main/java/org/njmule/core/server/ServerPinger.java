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

import com.google.common.base.CharMatcher;
import java.io.IOException;
import java.net.Inet4Address;
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
import org.njmule.core.protocol.packet.Opcodes;
import org.njmule.core.protocol.packet.Packet;
import org.njmule.core.protocol.tag.ServerTag;
import org.njmule.core.protocol.tag.Tag;
import org.njmule.core.protocol.tag.TagName;
import org.njmule.core.util.Ipv4;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Asks the known servers for their status over UDP: one server every {@link #INTERVAL}, each at most every
 * {@link #REPING_AFTER}. An answer counts only from the pinged server's UDP ports with the challenge that was sent; it
 * updates the server's load, limits, UDP capabilities and obfuscation key and ports, and the server is then asked for
 * its name, description and version, under the same conditions. Engine thread only.
 */
public final class ServerPinger {

    public static final Duration INTERVAL = Duration.ofSeconds(5);
    public static final Duration REPING_AFTER = Duration.ofMinutes(270);

    private static final Logger logger = LoggerFactory.getLogger(ServerPinger.class);
    private static final Duration ANSWER_TIMEOUT = Duration.ofSeconds(30);
    private static final long CHALLENGE_BASE = 0x55AA_0000L;
    // Servers that announce obfuscation without ports use these defaults.
    private static final int OBFUSCATED_UDP_OFFSET = 12;
    private static final CharMatcher CONTROL = CharMatcher.javaIsoControl();
    private static final int MAX_VERSION = 64;

    private record Pending(long challenge, long sentAt, long sentMillis) {}

    private record Asked(ServerList.Address address, ServerMet.Server server) {}

    /** Where the pings go: the server UDP socket. */
    @FunctionalInterface
    public interface Sender {
        void send(InetSocketAddress to, Packet packet) throws IOException;
    }

    private final EngineLoop loop;
    private final ServerList servers;
    private final Supplier<Optional<Sender>> udp;
    private final IntSupplier publicIp;
    private final Consumer<ServerMet.Server> updated;
    private final IntSupplier deadRetries;
    private final Consumer<ServerMet.Server> dead;
    private final SecureRandom random;
    private final Clock clock;
    private final Map<ServerList.Address, Pending> pending = new HashMap<>();
    private final Map<ServerList.Address, Pending> descriptions = new HashMap<>();
    private ScheduledFuture<?> timer;
    private int cursor;

    /**
     * @param publicIp our address as the connected server sees it; the UDP key is bound to it
     * @param updated told about each server an answer updated
     * @param deadRetries the failures in a row after which a server is dead
     * @param dead told about a dead server instead of pinging it again
     */
    public ServerPinger(
            EngineLoop loop,
            ServerList servers,
            Supplier<Optional<Sender>> udp,
            IntSupplier publicIp,
            Consumer<ServerMet.Server> updated,
            IntSupplier deadRetries,
            Consumer<ServerMet.Server> dead,
            SecureRandom random,
            Clock clock) {
        this.loop = Objects.requireNonNull(loop, "loop");
        this.servers = Objects.requireNonNull(servers, "servers");
        this.udp = Objects.requireNonNull(udp, "udp");
        this.publicIp = Objects.requireNonNull(publicIp, "publicIp");
        this.updated = Objects.requireNonNull(updated, "updated");
        this.deadRetries = Objects.requireNonNull(deadRetries, "deadRetries");
        this.dead = Objects.requireNonNull(dead, "dead");
        this.random = Objects.requireNonNull(random, "random");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public void start() {
        if (this.timer == null) {
            this.timer = this.loop.scheduleRepeating("server status pings", INTERVAL, INTERVAL, this::pingNext);
        }
    }

    public void stop() {
        if (this.timer != null) {
            this.timer.cancel(false);
            this.timer = null;
        }
        this.pending.clear();
        this.descriptions.clear();
    }

    /** A status or description answer received from {@code from}. */
    public void received(InetSocketAddress from, Packet packet) {
        // The socket takes IPv6 too; no server is known by an IPv6 address.
        if (!(from.getAddress() instanceof Inet4Address)) {
            logger.debug("Ignoring a status from {}", from);
            return;
        }
        if (packet.opcode() == Opcodes.ServerUdp.SERVER_DESC_RES.code()) {
            described(from, packet);
            return;
        }
        ServerDatagrams.Status status;
        try {
            status = ServerDatagrams.Status.decode(packet);
        } catch (DecodeException e) {
            logger.debug("Malformed status from {}", from, e);
            return;
        }
        Optional<Asked> asked = asked(this.pending, from, status.challenge());
        if (asked.isEmpty()) {
            logger.debug("Unrequested status from {}", from);
            return;
        }
        ServerList.Address address = asked.get().address();
        Pending sent = this.pending.remove(address);
        long ping = Math.max(0, this.clock.millis() - sent.sentMillis());
        this.servers.update(
                address, s -> apply(s, status, this.publicIp.getAsInt()).with(intTag(ServerTag.PING, ping)));
        this.servers.get(address).ifPresent(this.updated);
        askDescription(asked.get().server(), address);
    }

    // The server asked from where the answer came with its challenge: only the few waiting ones are looked at.
    private Optional<Asked> asked(Map<ServerList.Address, Pending> waiting, InetSocketAddress from, long challenge) {
        int ip = Ipv4.fromBytes(from.getAddress().getAddress());
        if (!this.servers.accepts(ip)) {
            return Optional.empty();
        }
        for (Map.Entry<ServerList.Address, Pending> e : waiting.entrySet()) {
            if (e.getValue().challenge() != challenge) {
                continue;
            }
            Optional<ServerMet.Server> server = this.servers.get(e.getKey());
            if (server.isPresent() && server.get().ip() == ip && isUdpPortOf(server.get(), from.getPort())) {
                return Optional.of(new Asked(e.getKey(), server.get()));
            }
        }
        return Optional.empty();
    }

    private void askDescription(ServerMet.Server server, ServerList.Address address) {
        Optional<Sender> socket = this.udp.get();
        if (socket.isEmpty()) {
            return;
        }
        long challenge = ServerDatagrams.descriptionChallenge(this.random.nextInt());
        InetSocketAddress target = Ipv4.socketAddress(server.ip(), server.port() + ServerUdp.PORT_OFFSET);
        try {
            socket.get().send(target, ServerDatagrams.descriptionRequest(challenge));
            this.descriptions.put(
                    address, new Pending(challenge, this.clock.instant().getEpochSecond(), this.clock.millis()));
        } catch (IOException e) {
            logger.debug("Cannot ask {} for its description", target, e);
        }
    }

    private void described(InetSocketAddress from, Packet packet) {
        ServerDatagrams.Description description;
        try {
            description = ServerDatagrams.Description.decode(packet);
        } catch (DecodeException e) {
            logger.debug("Malformed description from {}", from, e);
            return;
        }
        Optional<Asked> asked = asked(this.descriptions, from, description.challenge());
        if (asked.isEmpty()) {
            logger.debug("Unrequested description from {}", from);
            return;
        }
        ServerList.Address address = asked.get().address();
        this.descriptions.remove(address);
        this.servers.update(address, s -> describe(s, description.tags()));
        this.servers.get(address).ifPresent(this.updated);
    }

    void pingNext() {
        long now = this.clock.instant().getEpochSecond();
        this.pending.values().removeIf(p -> now - p.sentAt() > ANSWER_TIMEOUT.toSeconds());
        this.descriptions.values().removeIf(p -> now - p.sentAt() > ANSWER_TIMEOUT.toSeconds());
        Optional<Sender> socket = this.udp.get();
        List<ServerMet.Server> candidates = this.servers.candidates();
        if (socket.isEmpty() || candidates.isEmpty()) {
            return;
        }
        for (int i = 0; i < candidates.size(); i++) {
            int index = (this.cursor + i) % candidates.size();
            ServerMet.Server server = candidates.get(index);
            ServerList.Address address = ServerList.addressOf(server);
            // A last ping in the future (a clock set back, a list from elsewhere) is no recent ping.
            long since = now - server.lastPing().orElse(0);
            // No address yet, or no UDP port above the TCP one: nothing to ask.
            if (server.ip() == 0
                    || server.port() + ServerUdp.PORT_OFFSET > 0xFFFF
                    || (since >= 0 && since < REPING_AFTER.toSeconds())
                    || this.pending.containsKey(address)) {
                continue;
            }
            this.cursor = index + 1;
            if (server.failedCount().orElse(0) >= this.deadRetries.getAsInt() && !this.servers.isStatic(address)) {
                this.dead.accept(server);
                return;
            }
            long challenge = CHALLENGE_BASE | (this.random.nextInt() & 0xFFFF);
            InetSocketAddress target = Ipv4.socketAddress(server.ip(), server.port() + ServerUdp.PORT_OFFSET);
            try {
                socket.get().send(target, ServerDatagrams.statusRequest(challenge));
                this.pending.put(address, new Pending(challenge, now, this.clock.millis()));
                // Recorded on sending, so a silent server is not asked again at every start; the answer
                // takes the failure back.
                this.servers.update(
                        address,
                        s -> s.with(intTag(ServerTag.LASTPING, now))
                                .with(intTag(ServerTag.FAIL, s.failedCount().orElse(0) + 1)));
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

    static ServerMet.Server apply(ServerMet.Server server, ServerDatagrams.Status status, int ourIp) {
        int tcpPort = status.obfuscatedTcpPort();
        int udpPort = status.obfuscatedUdpPort();
        if (status.has(ServerDatagrams.UDP_TCP_OBFUSCATION) && tcpPort == 0) {
            tcpPort = server.port();
        }
        if (status.has(ServerDatagrams.UDP_OBFUSCATION) && udpPort == 0) {
            udpPort = server.port() + OBFUSCATED_UDP_OFFSET;
        }
        ServerMet.Server updated = server.with(intTag(ServerTag.USERS, status.users()))
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
            updated = updated.with(intTag(ServerTag.UDPKEY, status.udpKey()))
                    .with(intTag(ServerTag.UDPKEYIP, Ipv4.toEd2k(ourIp)));
        }
        return updated;
    }

    // Names and descriptions are shown and printed: without control characters; blank ones keep what was known.
    static ServerMet.Server describe(ServerMet.Server server, List<Tag> tags) {
        ServerMet.Server described = server;
        for (Tag tag : tags) {
            int id = tag.name().isId() ? tag.name().id() : -1;
            String text = switch (tag) {
                case Tag.StringTag t
                when id == ServerTag.SERVERNAME || id == ServerTag.DESCRIPTION ->
                    CONTROL.removeFrom(t.value()).strip();
                case Tag.StringTag t
                when id == ServerTag.VERSION -> {
                    String version = CONTROL.removeFrom(t.value()).strip();
                    yield version.substring(0, Math.min(MAX_VERSION, version.length()));
                }
                case Tag.IntTag t
                when id == ServerTag.VERSION -> ServerMet.Server.version(t.value() >>> 16, t.value() & 0xFFFF);
                default -> "";
            };
            if (!text.isEmpty()) {
                described = described.with(new Tag.StringTag(TagName.id(id), text));
            }
        }
        return described;
    }

    private static Tag intTag(int id, long value) {
        return new Tag.IntTag(TagName.id(id), value);
    }

    private static Tag intTag(String name, long value) {
        return new Tag.IntTag(TagName.of(name), value);
    }
}
