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
import static org.assertj.core.api.Assertions.catchThrowable;

import java.io.IOException;
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
import org.njmule.core.protocol.io.DecodeException;
import org.njmule.core.protocol.io.PacketReader;
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
    private final List<Packet> sent = new ArrayList<>();
    private final ServerPinger pinger;
    private boolean sending;
    private ServerDatagrams.Status status;
    private ServerMet.Server applied;
    private List<Tag> description = List.of();
    private Throwable exception;

    ServerPingerTest() {
        this.pinger = new ServerPinger(
                this.loop,
                this.servers,
                () -> Optional.<ServerPinger.Sender>of((to, packet) -> send(packet)),
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
    void shouldSkipAServerWhoseUdpPortWouldPassTheLastPort() {
        givenTheServer(server("192.0.2.1", 65_533));
        givenAPingWasTriedWithoutFailing();

        whenAPingIsTried();

        thenNoExceptionOccurred();
    }

    @Test
    void shouldNotPingAServerKnownByHostNameOnly() {
        givenTheServer(new ServerMet.Server(
                0, 4661, List.of(new Tag.StringTag(TagName.id(ServerTag.DYNIP), "server.example"))));

        whenAPingIsTried();

        thenNoExceptionOccurred();
        thenTheOnlyServerWasNeverPinged();
    }

    @Test
    void shouldIgnoreAnswersFromIpv6Addresses() {
        givenTheServer(server("192.0.2.1", 4661));
        givenAPacketArrivedWithoutFailingFrom(
                new InetSocketAddress(InetAddress.ofLiteral("::1"), 4665),
                status(1).encode());

        whenAPacketArrivesFrom(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 4665),
                status(1).encode());

        thenNoExceptionOccurred();
        thenNoServerIsUpdated();
    }

    @Test
    void shouldTakeTheDefaultObfuscationPortsAndBindTheKeyToOurAddress() {
        givenTheStatus(new ServerDatagrams.Status(
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
                0x1234_5678L));

        whenTheStatusIsAppliedFor(server("192.0.2.1", 4661), "1.2.3.4");

        thenTheObfuscatedTcpPortIs(4661);
        thenTheObfuscatedUdpPortIs(4661 + 12);
        thenTheUsersAre(10);
        thenTheServerSupportsObfuscation();
        thenTheKeyIsBoundTo("1.2.3.4");
    }

    @Test
    void shouldReportAServerThatFailedTooOftenDeadUnlessItIsStatic() {
        givenTheServer(server("192.0.2.1", 4661).with(failures(5)));
        givenTheStaticServer(server("192.0.2.2", 4661).with(failures(5)));
        givenTheNextServerWasPinged();

        whenTheNextServerIsPinged();

        thenTheServersReportedDeadAre(server("192.0.2.1", 4661).with(failures(5)));
        thenTheServerWasNeverPinged(
                server("192.0.2.2", 4661), "a static server is asked, though the closed socket sends nothing");
    }

    @Test
    void shouldLeaveAServerPingedRecentlyAlone() {
        givenAServerThatFailedOftenAndWasLastPingedIn(Duration.ZERO);

        whenTheNextServerIsPinged();

        thenNoServerIsReportedDead("not even checked");
    }

    @Test
    void shouldCheckAgainAServerLastPingedInTheFuture() {
        givenAServerThatFailedOftenAndWasLastPingedIn(Duration.ofDays(30));

        whenTheNextServerIsPinged();

        thenThatServerIsReportedDead();
    }

    @Test
    void shouldChangeNothingOnAMalformedStatus() {
        givenTheServer(server("192.0.2.1", 4661));

        whenAPacketArrivesFrom(
                new InetSocketAddress(InetAddress.ofLiteral("192.0.2.1"), 4665),
                Packet.of(Protocol.EDONKEY, Opcodes.ServerUdp.GLOBSERVSTATRES.code(), new byte[2]));

        thenNoExceptionOccurred();
        thenNoServerIsUpdated();
    }

    @Test
    void shouldChangeNothingOnAStatusThatWasNotAsked() {
        givenTheServer(server("192.0.2.1", 4661));

        whenAPacketArrivesFrom(
                new InetSocketAddress(InetAddress.ofLiteral("192.0.2.1"), 4665),
                status(0x55AA_0001L).encode());

        thenNoExceptionOccurred();
        thenNoServerIsUpdated();
    }

    @Test
    void shouldTakeTheStatusThePingedServerSendsFromItsUdpPortThenAskForItsDescription() throws DecodeException {
        givenTheServer(server("192.0.2.1", 4661));
        givenTheSocketSends();
        givenTheNextServerWasPinged();

        whenThePingedServerAnswersFrom("192.0.2.1", 4665);

        thenNoExceptionOccurred();
        thenTheUpdatedServerHasTheUsers(1);
        thenTheLastPacketSentIs(Opcodes.ServerUdp.SERVER_DESC_REQ.code());
    }

    @Test
    void shouldChangeNothingOnTheChallengeOfAPingComingFromAnotherAddress() throws DecodeException {
        givenTheServer(server("192.0.2.1", 4661));
        givenTheSocketSends();
        givenTheNextServerWasPinged();

        whenThePingedServerAnswersFrom("192.0.2.9", 4665);

        thenNoExceptionOccurred();
        thenNoServerIsUpdated();
    }

    @Test
    void shouldChangeNothingOnTheChallengeOfAPingComingFromAnotherPort() throws DecodeException {
        givenTheServer(server("192.0.2.1", 4661));
        givenTheSocketSends();
        givenTheNextServerWasPinged();

        whenThePingedServerAnswersFrom("192.0.2.1", 4666);

        thenNoExceptionOccurred();
        thenNoServerIsUpdated();
    }

    @Test
    void shouldChangeNothingOnADescriptionThatWasNotAsked() {
        givenTheServer(server("192.0.2.1", 4661));

        whenAPacketArrivesFrom(
                new InetSocketAddress(InetAddress.ofLiteral("192.0.2.1"), 4665),
                new ServerDatagrams.Description(
                                ServerDatagrams.descriptionChallenge(1),
                                List.of(new Tag.StringTag(TagName.id(ServerTag.SERVERNAME), "Spoofed")))
                        .encode());

        thenNoExceptionOccurred();
        thenNoServerIsUpdated();
    }

    @Test
    void shouldTakeTheNameDescriptionAndVersionOfADescriptionWithoutControlCharacters() {
        givenTheDescriptionTags(
                stringTag(ServerTag.SERVERNAME, "Evil\r\nServer"),
                stringTag(ServerTag.DESCRIPTION, "\u001b[31mred"),
                stringTag(ServerTag.VERSION, "17.15\u0007"));

        whenTheDescriptionIsAppliedTo(server("192.0.2.1", 4661));

        thenTheServerIsDescribedAs("EvilServer", "[31mred", "17.15");
    }

    @Test
    void shouldWriteANumericVersionAsMajorDotMinor() {
        givenTheDescriptionTags(new Tag.IntTag(TagName.id(ServerTag.VERSION), 17L << 16 | 9));

        whenTheDescriptionIsAppliedTo(server("192.0.2.1", 4661));

        thenTheVersionIs("17.09");
    }

    @Test
    void shouldKeepTheKnownNameWhenADescriptionGivesABlankOne() {
        givenTheDescriptionTags(stringTag(ServerTag.SERVERNAME, " \t "));

        whenTheDescriptionIsAppliedTo(server("192.0.2.1", 4661).with(stringTag(ServerTag.SERVERNAME, "Known")));

        thenTheNameIs("Known");
    }

    @Test
    void shouldStartAndStopTheTimerOnce() throws Exception {
        whenThePingerIsStartedTwiceAndStoppedTwice();

        thenNoServerIsReportedDead();
    }

    @Test
    void shouldKeepTheAnnouncedObfuscationPortsAndTakeNoKeyAsNoKey() {
        givenTheStatus(new ServerDatagrams.Status(
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
                0));

        whenTheStatusIsAppliedFor(server("192.0.2.1", 4661), "1.2.3.4");

        thenTheObfuscatedTcpPortIs(7001);
        thenTheObfuscatedUdpPortIs(7000);
        thenTheServerHasNoKey();
    }

    private void givenTheServer(ServerMet.Server server) {
        this.servers.add(server);
    }

    private void givenTheStaticServer(ServerMet.Server server) {
        this.servers.add(server);
        this.servers.setStatic(ServerList.addressOf(server), true);
    }

    private void givenAServerThatFailedOftenAndWasLastPingedIn(Duration fromNow) {
        long then = Clock.systemUTC().instant().plus(fromNow).getEpochSecond();
        this.servers.add(new ServerMet.Server(Ipv4.parse("192.0.2.1"), 4661, List.of())
                .with(new Tag.IntTag(TagName.id(ServerTag.LASTPING), then))
                .with(new Tag.IntTag(TagName.id(ServerTag.FAIL), 9)));
    }

    private void givenTheNextServerWasPinged() {
        this.pinger.pingNext();
    }

    private void givenTheSocketSends() {
        this.sending = true;
    }

    private void givenAPingWasTriedWithoutFailing() {
        assertThatCode(this.pinger::pingNext).doesNotThrowAnyException();
    }

    private void givenAPacketArrivedWithoutFailingFrom(InetSocketAddress from, Packet packet) {
        assertThatCode(() -> this.pinger.received(from, packet)).doesNotThrowAnyException();
    }

    private void givenTheDescriptionTags(Tag... tags) {
        this.description = List.of(tags);
    }

    private void givenTheStatus(ServerDatagrams.Status announced) {
        this.status = announced;
    }

    private void whenTheNextServerIsPinged() {
        this.pinger.pingNext();
    }

    private void whenAPingIsTried() {
        this.exception = catchThrowable(this.pinger::pingNext);
    }

    private void whenAPacketArrivesFrom(InetSocketAddress from, Packet packet) {
        this.exception = catchThrowable(() -> this.pinger.received(from, packet));
    }

    private void whenThePingedServerAnswersFrom(String address, int port) throws DecodeException {
        Packet answer = status(challengeOf(this.sent.getLast())).encode();
        this.exception = catchThrowable(
                () -> this.pinger.received(new InetSocketAddress(InetAddress.ofLiteral(address), port), answer));
    }

    private void whenTheStatusIsAppliedFor(ServerMet.Server server, String ourAddress) {
        this.applied = ServerPinger.apply(server, this.status, Ipv4.parse(ourAddress));
    }

    private void whenTheDescriptionIsAppliedTo(ServerMet.Server server) {
        this.applied = ServerPinger.describe(server, this.description);
    }

    private void whenThePingerIsStartedTwiceAndStoppedTwice() throws Exception {
        this.loop
                .submit(() -> {
                    this.pinger.start();
                    this.pinger.start();
                    this.pinger.stop();
                    this.pinger.stop();
                    return null;
                })
                .get(5, TimeUnit.SECONDS);
    }

    private void thenNoExceptionOccurred() {
        assertThat(this.exception).isNull();
    }

    private void thenTheOnlyServerWasNeverPinged() {
        assertThat(this.servers.all())
                .singleElement()
                .satisfies(s -> assertThat(s.lastPing()).isEmpty());
    }

    private void thenTheServerWasNeverPinged(ServerMet.Server server, String why) {
        assertThat(this.servers.get(ServerList.addressOf(server)).orElseThrow().lastPing())
                .as(why)
                .isEmpty();
    }

    private void thenNoServerIsUpdated() {
        assertThat(this.updated).isEmpty();
    }

    private void thenTheUpdatedServerHasTheUsers(long users) {
        assertThat(this.updated)
                .singleElement()
                .satisfies(s -> assertThat(s.users()).hasValue(users));
    }

    private void thenTheLastPacketSentIs(int opcode) {
        assertThat(this.sent.getLast().opcode()).isEqualTo(opcode);
    }

    private void thenThatServerIsReportedDead() {
        assertThat(this.dead).extracting(ServerMet.Server::ip).containsExactly(Ipv4.parse("192.0.2.1"));
    }

    private void thenTheServersReportedDeadAre(ServerMet.Server... expected) {
        assertThat(this.dead).containsExactly(expected);
    }

    private void thenNoServerIsReportedDead() {
        assertThat(this.dead).isEmpty();
    }

    private void thenNoServerIsReportedDead(String why) {
        assertThat(this.dead).as(why).isEmpty();
    }

    private void thenTheObfuscatedTcpPortIs(int port) {
        assertThat(this.applied.obfuscatedTcpPort()).hasValue(port);
    }

    private void thenTheObfuscatedUdpPortIs(int port) {
        assertThat(this.applied.obfuscatedUdpPort()).hasValue(port);
    }

    private void thenTheUsersAre(long users) {
        assertThat(this.applied.users()).hasValue(users);
    }

    private void thenTheServerSupportsObfuscation() {
        assertThat(ServerList.supportsObfuscation(this.applied)).isTrue();
    }

    private void thenTheKeyIsBoundTo(String address) {
        assertThat(intValue(this.applied, ServerTag.UDPKEYIP)).isEqualTo(Ipv4.toEd2k(Ipv4.parse(address)));
    }

    private void thenTheServerIsDescribedAs(String name, String description, String version) {
        assertThat(this.applied.name()).hasValue(name);
        assertThat(this.applied.description()).hasValue(description);
        thenTheVersionIs(version);
    }

    private void thenTheVersionIs(String version) {
        assertThat(this.applied.version()).hasValue(version);
    }

    private void thenTheNameIs(String name) {
        assertThat(this.applied.name()).hasValue(name);
    }

    private void thenTheServerHasNoKey() {
        assertThat(this.applied.tags()).noneMatch(t -> t.name().equals(TagName.id(ServerTag.UDPKEY)));
    }

    private static ServerMet.Server server(String ip, int port) {
        return new ServerMet.Server(Ipv4.parse(ip), port, List.of());
    }

    private static Tag stringTag(int id, String value) {
        return new Tag.StringTag(TagName.id(id), value);
    }

    private static Tag failures(int count) {
        return new Tag.IntTag(TagName.id(ServerTag.FAIL), count);
    }

    private static long intValue(ServerMet.Server server, int id) {
        return server.tags().stream()
                .filter(t -> t.name().equals(TagName.id(id)))
                .map(t -> ((Tag.IntTag) t).value())
                .findFirst()
                .orElseThrow();
    }

    // Without givenTheSocketSends() the socket fails as a closed one: nothing is ever sent.
    private void send(Packet packet) throws IOException {
        if (!this.sending) {
            throw new IOException("closed");
        }
        this.sent.add(packet);
    }

    private static long challengeOf(Packet request) throws DecodeException {
        return PacketReader.of(request.payload()).u32();
    }

    private static ServerDatagrams.Status status(long challenge) {
        return new ServerDatagrams.Status(challenge, 1, 2, 3, 4, 5, 0, 0, 0, 0, 0);
    }
}
