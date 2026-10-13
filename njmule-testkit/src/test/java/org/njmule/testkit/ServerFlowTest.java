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
package org.njmule.testkit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.core.api.EngineConfig;
import org.njmule.core.api.EngineEvent;
import org.njmule.core.api.NjMuleEngine;
import org.njmule.core.api.PeerInfo;
import org.njmule.core.api.SearchId;
import org.njmule.core.api.SearchRequest;
import org.njmule.core.api.SearchResult;
import org.njmule.core.api.ServerAddress;
import org.njmule.core.api.ServerInfo;
import org.njmule.core.config.AppDirectories;
import org.njmule.core.persist.format.ServerMet;
import org.njmule.core.protocol.ed2k.FileEntry;
import org.njmule.core.protocol.ed2k.ServerMessages;
import org.njmule.core.protocol.io.PacketWriter;
import org.njmule.core.protocol.packet.FrameLimit;
import org.njmule.core.protocol.packet.Packet;
import org.njmule.core.protocol.packet.Protocol;
import org.njmule.core.protocol.packet.TcpFraming;
import org.njmule.core.protocol.tag.ServerTag;
import org.njmule.core.protocol.tag.Tag;
import org.njmule.core.protocol.tag.TagName;
import org.njmule.core.util.Ipv4;

@Timeout(60)
class ServerFlowTest {

    private static final Duration WAIT = Duration.ofSeconds(30);

    @TempDir
    Path dir;

    private final List<AutoCloseable> resources = new ArrayList<>();

    @AfterEach
    void closeEverything() throws Exception {
        Collections.reverse(this.resources);
        for (AutoCloseable resource : this.resources) {
            resource.close();
        }
    }

    @Test
    void logsInAndSearchesTheServerThenTheOthersOverUdp() throws Exception {
        InetAddress local = lanAddress();
        FakeServer connected = this.server(
                local,
                new FakeServer.Config(
                        highId(local),
                        "server version 17.15\nWelcome to the fake server",
                        List.of(
                                FakeServer.file("ubuntu-24.04.iso", 6_000_000_000L, 50),
                                // Unusable results, which must not reach the front ends.
                                FakeServer.file("empty.bin", 0, 3),
                                FakeServer.file(" ", 100, 1))));
        FakeServer other = this.server(
                local,
                new FakeServer.Config(
                        1,
                        "",
                        List.of(
                                FakeServer.file("ubuntu-24.04.iso", 6_000_000_000L, 7),
                                FakeServer.file("ubuntu notes.txt", 10, 1))));
        NjMuleEngine engine = this.engine();
        Events events = new Events(engine);
        Path serverMet = this.dir.resolve("other-server.met");
        Files.write(
                serverMet,
                ServerMet.write(
                        List.of(new ServerMet.Server(Ipv4.fromBytes(local.getAddress()), other.port(), List.of()))));
        assertThat(engine.addServers(serverMet).get()).isEqualTo(1);

        engine.connect(Optional.of(new ServerAddress(local.getHostAddress(), connected.port())))
                .get();

        EngineEvent.ServerConnected login = events.next(EngineEvent.ServerConnected.class, e -> true);
        assertThat(login.lowId()).isFalse();
        assertThat(events.next(EngineEvent.ServerMessage.class, e -> e.text().startsWith("Welcome")))
                .isNotNull();
        assertThat(connected.logins()).hasSize(1);

        SearchId search = engine.search(new SearchRequest("ubuntu iso", SearchRequest.Scope.GLOBAL))
                .get();
        Map<String, SearchResult> results = new HashMap<>();
        while (true) {
            EngineEvent event = events.next(
                    EngineEvent.class,
                    e -> e instanceof EngineEvent.SearchResults || e instanceof EngineEvent.SearchEnded);
            if (event instanceof EngineEvent.SearchResults r && r.search().equals(search)) {
                r.results().forEach(result -> results.put(result.name(), result));
            } else if (event instanceof EngineEvent.SearchEnded ended
                    && ended.search().equals(search)) {
                assertThat(ended.results()).isEqualTo(2);
                break;
            }
        }
        assertThat(results.get("ubuntu-24.04.iso").sources()).isEqualTo(57);
        assertThat(results.get("ubuntu-24.04.iso").size()).isEqualTo(6_000_000_000L);
        assertThat(results).containsKey("ubuntu notes.txt");
        assertThat(connected.searches()).hasSize(1);
        assertThat(other.udpSearches()).hasSize(1);
    }

    @Test
    void anAnswerOfTheServerStaysWithTheSearchItAnswers() throws Exception {
        InetAddress local = lanAddress();
        FakeServer server = this.server(local, new FakeServer.Config(highId(local), "", List.of()));
        server.answerSearches(expression -> List.of(
                FakeServer.file(expression.toString().contains("first") ? "first.bin" : "second.bin", 1000, 1)));
        NjMuleEngine engine = this.engine();
        Events events = new Events(engine);
        engine.connect(Optional.of(new ServerAddress(local.getHostAddress(), server.port())))
                .get();
        events.next(EngineEvent.ServerConnected.class, e -> true);

        // The second search goes out before the answer to the first comes in.
        CompletableFuture<SearchId> first = engine.search(new SearchRequest("first", SearchRequest.Scope.SERVER));
        SearchId second = engine.search(new SearchRequest("second", SearchRequest.Scope.SERVER))
                .get();
        first.get();

        EngineEvent.SearchResults results =
                events.next(EngineEvent.SearchResults.class, r -> r.search().equals(second));
        assertThat(results.results()).extracting(SearchResult::name).containsExactly("second.bin");
    }

    @Test
    void offersTheSharedFilesToTheServer() throws Exception {
        InetAddress local = lanAddress();
        FakeServer server = this.server(local, new FakeServer.Config(highId(local), "hi", List.of()));
        Path incoming = Files.createDirectories(this.dir.resolve("njmule").resolve("incoming"));
        Files.write(incoming.resolve("song.mp3"), new byte[] {1, 2, 3, 4, 5});
        NjMuleEngine engine = this.engine();
        Events events = new Events(engine);
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (engine.sharedFiles().get().isEmpty()) {
            assertThat(System.nanoTime()).isLessThan(deadline);
            Thread.sleep(50);
        }

        engine.connect(Optional.of(new ServerAddress(local.getHostAddress(), server.port())))
                .get();
        events.next(EngineEvent.ServerConnected.class, e -> true);

        while (server.offers().isEmpty()) {
            assertThat(System.nanoTime()).isLessThan(deadline);
            Thread.sleep(50);
        }
        FileEntry offer = server.offers().getFirst();
        assertThat(offer.name()).contains("song.mp3");
        assertThat(offer.size()).hasValue(5);
        assertThat(offer.type()).contains("Audio");
        // A server with compression learns our address from the connection.
        assertThat(offer.clientId()).isEqualTo(0xFBFB_FBFBL);
        assertThat(offer.port()).isEqualTo(0xFBFB);
    }

    @Test
    void movesOnToTheNextServerAndReportsALowId() throws Exception {
        InetAddress local = lanAddress();
        int deadPort;
        try (ServerSocket probe = new ServerSocket(0, 1, local)) {
            deadPort = probe.getLocalPort();
        }
        FakeServer alive = this.server(local, new FakeServer.Config(1234, "low", List.of()));
        NjMuleEngine engine = this.engine();
        Events events = new Events(engine);
        Path serverMet = this.dir.resolve("two-servers.met");
        int ip = Ipv4.fromBytes(local.getAddress());
        Files.write(
                serverMet,
                ServerMet.write(List.of(
                        new ServerMet.Server(ip, deadPort, List.of()),
                        new ServerMet.Server(ip, alive.port(), List.of()))));
        engine.addServers(serverMet).get();

        engine.connect(Optional.empty()).get();

        EngineEvent.ServerConnected login = events.next(EngineEvent.ServerConnected.class, e -> true);
        assertThat(login.lowId()).isTrue();
        assertThat(login.clientId()).isEqualTo(1234);
        assertThat(login.server().address()).endsWith(":" + alive.port());
    }

    @Test
    void dropsDeadServersButStaticOnesAndTakesTheServersListWhenAsked() throws Exception {
        InetAddress local = lanAddress();
        int deadPort;
        try (ServerSocket probe = new ServerSocket(0, 1, local)) {
            deadPort = probe.getLocalPort();
        }
        FakeServer alive = this.server(local, new FakeServer.Config(highId(local), "hi", List.of()));
        alive.knowsServers(List.of(new ServerMessages.Endpoint(Ipv4.of(81, 2, 3, 9), 4661)));
        NjMuleEngine engine = this.engine();
        engine.updatePreferences(p -> p.toBuilder().addServersFromServer(true).build())
                .get();
        Events events = new Events(engine);
        Path serverMet = this.dir.resolve("dead.met");
        List<Tag> failedOnce = List.of(new Tag.IntTag(TagName.id(ServerTag.FAIL), 1));
        Files.write(
                serverMet,
                ServerMet.write(List.of(
                        new ServerMet.Server(Ipv4.fromBytes(local.getAddress()), deadPort, failedOnce),
                        new ServerMet.Server(Ipv4.of(81, 2, 3, 7), 4661, failedOnce))));
        engine.addServers(serverMet).get();
        assertThat(engine.setStaticServer(new ServerAddress("81.2.3.7", 4661), true)
                        .get())
                .isTrue();

        engine.connect(Optional.of(new ServerAddress(local.getHostAddress(), alive.port())))
                .get();
        events.next(EngineEvent.ServerConnected.class, e -> true);
        events.next(EngineEvent.ServersChanged.class, e -> true);
        events.next(EngineEvent.ServersChanged.class, e -> true);

        List<ServerInfo> servers = engine.servers().get();
        assertThat(servers)
                .extracting(ServerInfo::address)
                .containsExactlyInAnyOrder(
                        local.getHostAddress() + ":" + alive.port(), "81.2.3.7:4661", "81.2.3.9:4661");
        assertThat(servers)
                .filteredOn(s -> s.address().equals("81.2.3.9:4661"))
                .singleElement()
                .satisfies(s -> assertThat(s.priority()).isEqualTo(ServerInfo.Priority.LOW));
    }

    @Test
    void pingsTheKnownServersAndConnectsBackOnCallbackRequests() throws Exception {
        InetAddress local = lanAddress();
        FakeServer connected = this.server(local, new FakeServer.Config(highId(local), "hi", List.of()));
        FakeServer other = this.server(local, new FakeServer.Config(1, "", List.of()));
        NjMuleEngine engine = this.engine();
        Events events = new Events(engine);
        Path serverMet = this.dir.resolve("other.met");
        Files.write(
                serverMet,
                ServerMet.write(
                        List.of(new ServerMet.Server(Ipv4.fromBytes(local.getAddress()), other.port(), List.of()))));
        engine.addServers(serverMet).get();
        engine.connect(Optional.of(new ServerAddress(local.getHostAddress(), connected.port())))
                .get();
        events.next(EngineEvent.ServerConnected.class, e -> true);

        EngineEvent.ServerUpdated pinged = events.next(
                EngineEvent.ServerUpdated.class,
                e -> e.server().address().endsWith(":" + other.port())
                        && e.server().users() == 10);
        assertThat(pinged.server().files()).isEqualTo(20);

        try (ServerSocket firewalled = new ServerSocket(0, 4, local)) {
            Packet request = callbackRequested(Ipv4.fromBytes(local.getAddress()), firewalled.getLocalPort());
            connected.broadcast(request);
            // The same request again is not answered twice in a row.
            connected.broadcast(request);
            try (Socket peer = firewalled.accept()) {
                peer.setSoTimeout(10_000);
                Packet hello = TcpFraming.read(peer.getInputStream(), FrameLimit.fixed(4096));
                assertThat(hello.opcode()).isEqualTo(0x01);
            }
            firewalled.setSoTimeout(2_000);
            assertThatThrownBy(firewalled::accept).isInstanceOf(java.net.SocketTimeoutException.class);
        }
    }

    private static Packet callbackRequested(int ip, int port) {
        return Packet.of(
                Protocol.EDONKEY, 0x35, new PacketWriter().u32(Ipv4.toEd2k(ip)).u16(port));
    }

    @Test
    void answersHellosAndReportsTheAnswer() throws Exception {
        NjMuleEngine engine = this.engine();
        int port = engine.preferences().tcpPort();

        PeerInfo self = engine.hello(new ServerAddress("127.0.0.1", port)).get(10, TimeUnit.SECONDS);

        assertThat(self.software()).startsWith("njMule v");
        assertThat(self.nick()).contains(engine.preferences().nick());
        assertThat(self.obfuscated()).isFalse();
    }

    private FakeServer server(InetAddress address, FakeServer.Config config) throws IOException {
        FakeServer server = FakeServer.start(address, config);
        this.resources.add(server);
        return server;
    }

    private NjMuleEngine engine() throws IOException {
        AppDirectories dirs = AppDirectories.underRoot(this.dir.resolve("njmule"), true);
        Files.createDirectories(dirs.config());
        int port;
        try (ServerSocket probe = new ServerSocket(0)) {
            port = probe.getLocalPort();
        }
        // The fake servers and peers listen on this machine's LAN address, which must be let in.
        Files.writeString(
                dirs.config().resolve("njmule.properties"), "net.tcpPort=" + port + "\nsecurity.filterLan=false\n");
        NjMuleEngine engine = NjMuleEngine.start(new EngineConfig(dirs));
        this.resources.add(engine);
        return engine;
    }

    // Servers on loopback are not kept in the server list, so these tests need a LAN address.
    static InetAddress lanAddress() throws SocketException {
        for (NetworkInterface nic : Collections.list(NetworkInterface.getNetworkInterfaces())) {
            if (!nic.isUp() || nic.isLoopback() || nic.isVirtual()) {
                continue;
            }
            for (InetAddress address : Collections.list(nic.getInetAddresses())) {
                if (address instanceof Inet4Address && !address.isLinkLocalAddress()) {
                    return address;
                }
            }
        }
        assumeTrue(false, "no LAN address");
        throw new IllegalStateException();
    }

    private static long highId(InetAddress address) {
        return Ipv4.toEd2k(Ipv4.fromBytes(address.getAddress()));
    }

    /** Collects the engine's events for assertions. */
    private static final class Events implements Flow.Subscriber<EngineEvent> {
        private final BlockingQueue<EngineEvent> queue = new LinkedBlockingQueue<>();

        Events(NjMuleEngine engine) {
            engine.events().subscribe(this);
        }

        <T extends EngineEvent> T next(Class<T> type, Predicate<T> match) throws InterruptedException {
            long deadline = System.nanoTime() + WAIT.toNanos();
            while (System.nanoTime() < deadline) {
                EngineEvent event = this.queue.poll(100, TimeUnit.MILLISECONDS);
                if (type.isInstance(event) && match.test(type.cast(event))) {
                    return type.cast(event);
                }
            }
            throw new AssertionError("no " + type.getSimpleName() + " within " + WAIT);
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(EngineEvent item) {
            this.queue.add(item);
        }

        @Override
        public void onError(Throwable throwable) {}

        @Override
        public void onComplete() {}
    }
}
