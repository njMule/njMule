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

import com.google.common.base.Ticker;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.njmule.core.api.Hash16;
import org.njmule.core.engine.EngineLoop;
import org.njmule.core.persist.format.ServerMet;
import org.njmule.core.protocol.ed2k.ServerDatagrams;
import org.njmule.core.protocol.ed2k.ServerMessages;
import org.njmule.core.protocol.packet.Opcodes;
import org.njmule.core.protocol.packet.Protocol;
import org.njmule.core.protocol.tag.ServerTag;
import org.njmule.core.protocol.tag.Tag;
import org.njmule.core.protocol.tag.TagName;
import org.njmule.core.util.Ipv4;

/** The downloads' sources asked of the servers we are not on, over UDP. */
class GlobalSourcesTest {

    private static final int DEAD_RETRIES = 5;
    private static final int FLAGS = ServerDatagrams.UDP_EXT_GETSOURCES | ServerDatagrams.UDP_EXT_GETSOURCES2;
    private static final Hash16 FILE = file(1);
    private static final Hash16 OTHER = file(2);
    private static final long HIGH_ID = Ipv4.toEd2k(Ipv4.parse("1.2.3.4"));
    private static final long LOW_ID = 0x1234;

    private final EngineLoop loop = EngineLoop.start(1024, "test-engine");
    private final ServerList servers = new ServerList();
    private final ManualTicker ticker = new ManualTicker();
    private final Map<String, DatagramSocket> sockets = new HashMap<>();
    // The list takes no loopback server: each one is at a documentation address, and its datagrams go to its socket.
    private final Map<ServerList.Address, DatagramSocket> routes = new HashMap<>();
    private final List<ServerMessages.FoundSources> found = new CopyOnWriteArrayList<>();
    private final ServerUdp udp;
    private final GlobalSources sources;
    private List<ServerDatagrams.SourceQuery> wanting = List.of();
    private Optional<ServerMet.Server> connected = Optional.empty();
    private boolean allowed = true;

    GlobalSourcesTest() throws IOException {
        this.udp = ServerUdp.open(0, (from, packet) -> {});
        this.sources = new GlobalSources(
                this.loop,
                this.servers,
                () -> Optional.of(this.udp),
                s -> Optional.ofNullable(this.routes.get(ServerList.addressOf(s)))
                        .map(socket -> new ServerRoute(local(socket), 0)),
                () -> this.connected,
                () -> DEAD_RETRIES,
                () -> this.allowed,
                new GlobalSources.Downloads() {
                    @Override
                    public List<ServerDatagrams.SourceQuery> wanting() {
                        return GlobalSourcesTest.this.wanting;
                    }

                    @Override
                    public void found(ServerMessages.FoundSources sources) {
                        GlobalSourcesTest.this.found.add(sources);
                    }
                },
                new SecureRandom(),
                this.ticker);
    }

    @AfterEach
    void stop() {
        this.udp.close();
        this.sockets.values().forEach(DatagramSocket::close);
        this.loop.close();
    }

    @Test
    void shouldAskAnotherServerForTheWantedDownloadsButNotTheOneWeAreOn() throws Exception {
        givenTheServerWeAreOn("A");
        givenAnotherServer("B");
        givenTheWantedDownloads(FILE);

        whenTheNextRequestGoesOut();

        thenServerIsAskedFor("B", FILE);
        thenServerIsNotAsked("A");
    }

    @Test
    void shouldLeaveOutAServerThatFailedTooOften() throws Exception {
        givenADeadServer("C");
        givenAnotherServer("B");
        givenTheWantedDownloads(FILE);

        whenTheNextRequestsGoOut(2);

        thenServerIsAskedFor("B", FILE);
        thenServerIsNotAsked("C");
    }

    @Test
    void shouldAskNothingWhenEveryConnectionMustBeObfuscated() throws Exception {
        givenAnotherServer("B");
        givenTheWantedDownloads(FILE);
        givenEveryConnectionMustBeObfuscated();

        whenTheNextRequestGoesOut();

        thenServerIsNotAsked("B");
    }

    @Test
    void shouldAskTheNextServerForTheFilesThePreviousOneWasNotAskedFor() throws Exception {
        givenAnotherServer("B");
        givenAnotherServer("D");
        givenManyWantedDownloads(40);

        whenTheNextRequestsGoOut(3);

        thenServerIsAskedFirstFor("D", file(35));
    }

    @Test
    void shouldTakeOnlyTheHighIdSourcesOfTheFilesTheServerWasAskedFor() throws Exception {
        givenAnotherServer("B");
        givenTheWantedDownloads(FILE);
        givenServerWasAsked("B");

        whenServerAnswers("B", sources(FILE, HIGH_ID, LOW_ID), sources(OTHER, HIGH_ID));

        thenTheSourcesFoundAre(sources(FILE, HIGH_ID));
    }

    @Test
    void shouldIgnoreSourcesFromAnAddressThatWasNotAsked() throws Exception {
        givenAnotherServer("B");
        givenAnotherServer("D");
        givenTheWantedDownloads(FILE);
        givenServerWasAsked("B");

        whenServerAnswers("D", sources(FILE, HIGH_ID));

        thenNoSourcesAreFound();
    }

    @Test
    void shouldNotAskAgainBeforeHalfAnHourAfterAPass() throws Exception {
        givenAnotherServer("B");
        givenTheWantedDownloads(FILE);
        givenServerWasAsked("B");
        givenThePassEnded();

        whenTheNextRequestGoesOutAfter(GlobalSources.PASS_PAUSE.minusMinutes(1));

        thenServerIsNotAsked("B");
    }

    @Test
    void shouldAskAgainHalfAnHourAfterAPass() throws Exception {
        givenAnotherServer("B");
        givenTheWantedDownloads(FILE);
        givenServerWasAsked("B");
        givenThePassEnded();

        whenTheNextRequestGoesOutAfter(GlobalSources.PASS_PAUSE);

        thenServerIsAskedFor("B", FILE);
    }

    private void givenTheServerWeAreOn(String name) throws IOException {
        this.connected = Optional.of(givenAServer(name, List.of()));
    }

    private void givenAnotherServer(String name) throws IOException {
        givenAServer(name, List.of());
    }

    private void givenADeadServer(String name) throws IOException {
        givenAServer(name, List.of(new Tag.IntTag(TagName.id(ServerTag.FAIL), DEAD_RETRIES)));
    }

    private ServerMet.Server givenAServer(String name, List<Tag> tags) throws IOException {
        DatagramSocket socket = new DatagramSocket(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        this.sockets.put(name, socket);
        List<Tag> all = new ArrayList<>(tags);
        all.add(new Tag.IntTag(TagName.id(ServerTag.UDPFLAGS), FLAGS));
        ServerMet.Server server = new ServerMet.Server(Ipv4.of(192, 0, 2, this.sockets.size()), 4661, all);
        this.servers.add(server);
        this.routes.put(ServerList.addressOf(server), socket);
        return server;
    }

    private void givenTheWantedDownloads(Hash16... files) {
        this.wanting = Arrays.stream(files)
                .map(f -> new ServerDatagrams.SourceQuery(f, 1000))
                .toList();
    }

    private void givenManyWantedDownloads(int count) {
        this.wanting = IntStream.range(0, count)
                .mapToObj(i -> new ServerDatagrams.SourceQuery(file(i), 1000))
                .toList();
    }

    private void givenEveryConnectionMustBeObfuscated() {
        this.allowed = false;
    }

    private void givenServerWasAsked(String name) throws IOException {
        this.sources.tick();
        receive(name, 2000);
    }

    private void givenThePassEnded() {
        this.sources.tick();
    }

    private void whenTheNextRequestGoesOut() {
        this.sources.tick();
    }

    private void whenTheNextRequestsGoOut(int count) {
        for (int i = 0; i < count; i++) {
            this.sources.tick();
        }
    }

    private void whenTheNextRequestGoesOutAfter(Duration wait) {
        this.ticker.advance(wait);
        this.sources.tick();
    }

    private void whenServerAnswers(String name, ServerMessages.FoundSources... files) {
        this.sources.received(local(this.sockets.get(name)), ServerDatagrams.foundSources(List.of(files)));
    }

    private void thenServerIsAskedFor(String name, Hash16... files) throws IOException {
        assertThat(filesAsked(receive(name, 2000).orElseThrow())).containsExactly(files);
    }

    private void thenServerIsAskedFirstFor(String name, Hash16 first) throws IOException {
        assertThat(filesAsked(receive(name, 2000).orElseThrow())).first().isEqualTo(first);
    }

    private void thenServerIsNotAsked(String name) throws IOException {
        assertThat(receive(name, 300)).isEmpty();
    }

    private void thenTheSourcesFoundAre(ServerMessages.FoundSources... files) {
        assertThat(this.found).containsExactly(files);
    }

    private void thenNoSourcesAreFound() {
        assertThat(this.found).isEmpty();
    }

    private Optional<byte[]> receive(String name, int millis) throws IOException {
        DatagramSocket socket = this.sockets.get(name);
        socket.setSoTimeout(millis);
        DatagramPacket datagram = new DatagramPacket(new byte[1024], 1024);
        try {
            socket.receive(datagram);
        } catch (SocketTimeoutException e) {
            return Optional.empty();
        }
        return Optional.of(Arrays.copyOf(datagram.getData(), datagram.getLength()));
    }

    // A sized request: each file id is followed by its 32-bit size.
    private static List<Hash16> filesAsked(byte[] datagram) {
        assertThat(datagram[0] & 0xFF).isEqualTo(Protocol.EDONKEY);
        assertThat(datagram[1] & 0xFF).isEqualTo(Opcodes.ServerUdp.GLOBGETSOURCES2.code());
        List<Hash16> files = new ArrayList<>();
        for (int at = 2; at + Hash16.LENGTH + 4 <= datagram.length; at += Hash16.LENGTH + 4) {
            files.add(Hash16.of(datagram, at));
        }
        return files;
    }

    private static InetSocketAddress local(DatagramSocket socket) {
        return new InetSocketAddress(InetAddress.getLoopbackAddress(), socket.getLocalPort());
    }

    private static Hash16 file(int number) {
        return Hash16.parseHex("%032x".formatted(number));
    }

    private static ServerMessages.FoundSources sources(Hash16 file, long... clientIds) {
        return new ServerMessages.FoundSources(
                file,
                Arrays.stream(clientIds)
                        .mapToObj(id -> new ServerMessages.Source(id, 4662, 0, Optional.empty()))
                        .toList());
    }

    private static final class ManualTicker extends Ticker {
        private long nanos;

        @Override
        public long read() {
            return this.nanos;
        }

        void advance(Duration duration) {
            this.nanos += duration.toNanos();
        }
    }
}
