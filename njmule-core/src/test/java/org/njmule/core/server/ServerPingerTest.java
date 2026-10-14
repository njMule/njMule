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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.njmule.core.engine.EngineLoop;
import org.njmule.core.persist.format.ServerMet;
import org.njmule.core.protocol.ed2k.ServerDatagrams;
import org.njmule.core.protocol.packet.Opcodes;
import org.njmule.core.protocol.packet.Packet;
import org.njmule.core.protocol.packet.Protocol;
import org.njmule.core.protocol.tag.ServerTag;
import org.njmule.core.protocol.tag.Tag;
import org.njmule.core.protocol.tag.TagName;
import org.njmule.core.util.Ipv4;

class ServerPingerTest {

    private final EngineLoop loop = EngineLoop.start(1024, "test-engine");
    private final ServerList servers = new ServerList();
    private final List<ServerMet.Server> updated = new ArrayList<>();
    private final List<ServerMet.Server> dead = new ArrayList<>();
    private final ServerUdp udp;
    private final ServerPinger pinger;

    ServerPingerTest() throws Exception {
        // Closed: whatever the pinger tries to send never leaves this machine.
        this.udp = ServerUdp.open(0, (from, packet) -> {});
        this.udp.close();
        this.pinger = new ServerPinger(
                this.loop,
                this.servers,
                () -> Optional.of(this.udp),
                () -> Ipv4.parse("1.2.3.4"),
                this.updated::add,
                () -> 5,
                this.dead::add,
                new SecureRandom(),
                Clock.systemUTC());
    }

    @AfterEach
    void stop() {
        this.loop.close();
    }

    @Test
    void aServerWhoseUdpPortWouldPassTheLastPortIsSkipped() {
        this.servers.add(new ServerMet.Server(Ipv4.parse("192.0.2.1"), 65_533, List.of()));

        assertThatCode(this.pinger::pingNext).doesNotThrowAnyException();
        assertThatCode(this.pinger::pingNext).doesNotThrowAnyException();
    }

    @Test
    void aServerKnownByHostNameOnlyIsNotPinged() {
        this.servers.add(new ServerMet.Server(
                0, 4661, List.of(new Tag.StringTag(TagName.id(ServerTag.DYNIP), "server.example"))));

        assertThatCode(this.pinger::pingNext).doesNotThrowAnyException();
        assertThat(this.servers.all())
                .singleElement()
                .satisfies(s -> assertThat(s.lastPing()).isEmpty());
    }

    @Test
    void answersFromIpv6AddressesAreIgnored() {
        this.servers.add(new ServerMet.Server(Ipv4.parse("192.0.2.1"), 4661, List.of()));
        InetSocketAddress from = new InetSocketAddress(InetAddress.getLoopbackAddress(), 4665);
        InetSocketAddress v6 = new InetSocketAddress(InetAddress.ofLiteral("::1"), 4665);

        assertThatCode(() -> this.pinger.received(v6, status(1).encode())).doesNotThrowAnyException();
        assertThatCode(() -> this.pinger.received(from, status(1).encode())).doesNotThrowAnyException();
        assertThat(this.updated).isEmpty();
    }

    @Test
    void obfuscationWithoutPortsTakesTheDefaultsAndTheKeyIsBoundToOurAddress() {
        ServerMet.Server server = new ServerMet.Server(Ipv4.parse("192.0.2.1"), 4661, List.of());
        ServerDatagrams.Status status = new ServerDatagrams.Status(
                1,
                10,
                20,
                30,
                40,
                50,
                ServerDatagrams.UDP_OBFUSCATION | ServerDatagrams.UDP_TCP_OBFUSCATION,
                5,
                0,
                0,
                0x1234_5678L);

        ServerMet.Server applied = ServerPinger.apply(server, status, Ipv4.parse("1.2.3.4"));

        assertThat(applied.obfuscatedTcpPort()).hasValue(4661);
        assertThat(applied.obfuscatedUdpPort()).hasValue(4661 + 12);
        assertThat(applied.users()).hasValue(10);
        assertThat(ServerList.supportsObfuscation(applied)).isTrue();
        assertThat(intValue(applied, ServerTag.UDPKEYIP)).isEqualTo(Ipv4.toEd2k(Ipv4.parse("1.2.3.4")));
    }

    @Test
    void aServerThatFailedTooOftenIsReportedDeadUnlessItIsStatic() {
        ServerMet.Server failing = new ServerMet.Server(Ipv4.parse("192.0.2.1"), 4661, List.of())
                .with(new Tag.IntTag(TagName.id(ServerTag.FAIL), 5));
        ServerMet.Server kept = new ServerMet.Server(Ipv4.parse("192.0.2.2"), 4661, List.of())
                .with(new Tag.IntTag(TagName.id(ServerTag.FAIL), 5));
        this.servers.add(failing);
        this.servers.add(kept);
        this.servers.setStatic(ServerList.addressOf(kept), true);

        this.pinger.pingNext();
        this.pinger.pingNext();

        assertThat(this.dead).containsExactly(failing);
        assertThat(this.servers.get(ServerList.addressOf(kept)).orElseThrow().lastPing())
                .as("a static server is asked, though the closed socket sends nothing")
                .isEmpty();
    }

    @Test
    void aServerPingedRecentlyIsLeftAlone() {
        long now = Clock.systemUTC().instant().getEpochSecond();
        this.servers.add(new ServerMet.Server(Ipv4.parse("192.0.2.1"), 4661, List.of())
                .with(new Tag.IntTag(TagName.id(ServerTag.LASTPING), now))
                .with(new Tag.IntTag(TagName.id(ServerTag.FAIL), 9)));

        this.pinger.pingNext();

        assertThat(this.dead).as("not even checked").isEmpty();
    }

    @Test
    void aServerLastPingedInTheFutureIsCheckedAgain() {
        this.givenAServerThatFailedOftenAndWasLastPingedIn(Duration.ofDays(30));

        this.whenTheNextServerIsPinged();

        this.thenThatServerIsReportedDead();
    }

    private void givenAServerThatFailedOftenAndWasLastPingedIn(Duration fromNow) {
        long then = Clock.systemUTC().instant().plus(fromNow).getEpochSecond();
        this.servers.add(new ServerMet.Server(Ipv4.parse("192.0.2.1"), 4661, List.of())
                .with(new Tag.IntTag(TagName.id(ServerTag.LASTPING), then))
                .with(new Tag.IntTag(TagName.id(ServerTag.FAIL), 9)));
    }

    private void whenTheNextServerIsPinged() {
        this.pinger.pingNext();
    }

    private void thenThatServerIsReportedDead() {
        assertThat(this.dead).extracting(ServerMet.Server::ip).containsExactly(Ipv4.parse("192.0.2.1"));
    }

    @Test
    void malformedOrUnaskedStatusesChangeNothing() {
        this.servers.add(new ServerMet.Server(Ipv4.parse("192.0.2.1"), 4661, List.of()));
        InetSocketAddress server = new InetSocketAddress(InetAddress.ofLiteral("192.0.2.1"), 4665);

        this.pinger.received(
                server, Packet.of(Protocol.EDONKEY, Opcodes.ServerUdp.GLOBSERVSTATRES.code(), new byte[2]));
        this.pinger.received(server, status(0x55AA_0001L).encode());

        assertThat(this.updated).isEmpty();
    }

    @Test
    void theTimerStartsAndStopsOnce() throws Exception {
        this.loop
                .submit(() -> {
                    this.pinger.start();
                    this.pinger.start();
                    this.pinger.stop();
                    this.pinger.stop();
                    return null;
                })
                .get(5, TimeUnit.SECONDS);

        assertThat(this.dead).isEmpty();
    }

    @Test
    void obfuscationPortsAnnouncedAreKeptAndNoKeyIsNoKey() {
        ServerMet.Server server = new ServerMet.Server(Ipv4.parse("192.0.2.1"), 4661, List.of());
        ServerDatagrams.Status status = new ServerDatagrams.Status(
                1,
                10,
                20,
                30,
                40,
                50,
                ServerDatagrams.UDP_OBFUSCATION | ServerDatagrams.UDP_TCP_OBFUSCATION,
                5,
                7000,
                7001,
                0);

        ServerMet.Server applied = ServerPinger.apply(server, status, Ipv4.parse("1.2.3.4"));

        assertThat(applied.obfuscatedTcpPort()).hasValue(7001);
        assertThat(applied.obfuscatedUdpPort()).hasValue(7000);
        assertThat(applied.tags()).noneMatch(t -> t.name().equals(TagName.id(ServerTag.UDPKEY)));
    }

    private static long intValue(ServerMet.Server server, int id) {
        return server.tags().stream()
                .filter(t -> t.name().equals(TagName.id(id)))
                .map(t -> ((Tag.IntTag) t).value())
                .findFirst()
                .orElseThrow();
    }

    private static ServerDatagrams.Status status(long challenge) {
        return new ServerDatagrams.Status(challenge, 1, 2, 3, 4, 5, 0, 0, 0, 0, 0);
    }
}
