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
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
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
import org.njmule.core.api.NjMuleInfo;
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
    private final Map<String, SearchResult> results = new HashMap<>();
    private InetAddress local;
    private FakeServer server;
    private FakeServer other;
    private int deadPort;
    private NjMuleEngine engine;
    private Events events;
    private EngineEvent.ServerConnected login;
    private EngineEvent.SearchEnded ended;
    private CompletableFuture<SearchId> pending;
    private SearchId search;
    private long deadline;
    private ServerSocket firewalled;
    private PeerInfo answer;
    private Throwable exception;

    @AfterEach
    void closeEverything() throws Exception {
        Collections.reverse(this.resources);
        for (AutoCloseable resource : this.resources) {
            resource.close();
        }
    }

    @Test
    void shouldLogInAndSearchTheServerThenTheOthersOverUdp() throws Exception {
        givenAServerOnTheLan(
                "server version 17.15\nWelcome to the fake server",
                FakeServer.file("ubuntu-24.04.iso", 6_000_000_000L, 50),
                // Unusable results, which must not reach the front ends.
                FakeServer.file("empty.bin", 0, 3),
                FakeServer.file(" ", 100, 1));
        givenAnotherServerFinding(
                FakeServer.file("ubuntu-24.04.iso", 6_000_000_000L, 7), FakeServer.file("ubuntu notes.txt", 10, 1));
        givenNjMuleRunning();
        givenNjMuleKnowsTheOtherServer();
        givenNjMuleConnectedToTheServer();
        givenTheLoginGotAHighIdAndTheMessageStartingWith("Welcome");

        whenASearchRuns("ubuntu iso", SearchRequest.Scope.GLOBAL);

        thenTheSearchEndedWithResults(2);
        thenTheResultHas("ubuntu-24.04.iso", 57, 6_000_000_000L);
        thenTheResultsHold("ubuntu notes.txt");
        thenTheServerWasSearchedTimes(1);
        thenTheOtherServerWasSearchedOverUdpTimes(1);
    }

    @Test
    void shouldKeepAnAnswerOfTheServerWithTheSearchItAnswers() throws Exception {
        givenAServerOnTheLan("");
        givenTheServerFindsAFileNamedAfterTheFirstOrSecondSearch();
        givenNjMuleRunning();
        givenNjMuleConnectedToTheServer();
        givenAServerSearchSentFor("first");

        whenAnotherServerSearchIsSentBeforeTheAnswerFor("second");

        thenTheResultsOfTheLatestSearchAre("second.bin");
    }

    @Test
    void shouldOfferTheSharedFilesToTheServer() throws Exception {
        givenAServerOnTheLan("hi");
        givenOurIncomingFolderHolds("song.mp3", new byte[] {1, 2, 3, 4, 5});
        givenNjMuleRunning();
        givenTheFileShared();

        whenNjMuleConnectsToTheServer();

        thenTheServerIsOffered("song.mp3", 5, "Audio");
        thenTheOfferLeavesOurAddressToTheServer();
    }

    @Test
    void shouldMoveOnToTheNextServerAndReportALowId() throws Exception {
        givenADeadPortOnTheLan();
        givenAServerOnTheLanGivingTheId(1234, "low");
        givenNjMuleRunning();
        givenNjMuleKnowsTheDeadServerThenTheAliveOne();

        whenNjMuleConnectsToAnyServer();

        thenTheLoginGotTheLowId(1234);
        thenTheLoginIsOnTheAliveServer();
    }

    @Test
    void shouldDropDeadServersButStaticOnesAndTakeTheServerListWhenAsked() throws Exception {
        givenADeadPortOnTheLan();
        givenAServerOnTheLan("hi");
        givenTheServerKnowsTheServer("81.2.3.9", 4661);
        givenNjMuleRunningAndTakingTheServerListsOfServers();
        givenNjMuleKnowsTheDeadServerAndAnotherThatFailedOnce("81.2.3.7", 4661);
        givenTheServerMadeStatic("81.2.3.7", 4661);

        whenNjMuleConnectsToTheServer();

        thenTheServerListChangesTimes(2);
        thenNjMuleKnowsTheServerAnd("81.2.3.7:4661", "81.2.3.9:4661");
        thenTheServerHasThePriority("81.2.3.9:4661", ServerInfo.Priority.LOW);
    }

    @Test
    void shouldPingTheKnownServersAndConnectBackOnCallbackRequests() throws Exception {
        givenAServerOnTheLan("hi");
        givenAnotherServerFinding();
        givenNjMuleRunning();
        givenNjMuleKnowsTheOtherServer();
        givenNjMuleConnectedToTheServer();
        givenTheOtherServerPingedWithUsersAndFiles(10, 20);
        givenAFirewalledPeerListeningOnTheLan();
        givenTheServerAskedTwiceToHaveThePeerCalled();
        givenThePeerWasCalledWithAHello();

        whenThePeerWaitsForAnotherCallForMillis(2_000);

        thenExceptionOccurred(SocketTimeoutException.class);
    }

    @Test
    void shouldNameAKnownServerAfterTheDescriptionItGivesOverUdp() throws Exception {
        givenAServerOnTheLan("hi");
        givenAnotherServerFinding();
        givenTheOtherServerDescribesItselfAs("Other", "Second server", "17.15");
        givenNjMuleRunning();
        givenNjMuleKnowsTheOtherServer();

        whenNjMuleConnectsToTheServer();

        thenTheOtherServerIsSoonDescribedAs("Other", "Second server", "17.15");
    }

    @Test
    void shouldAnswerHellosAndReportTheAnswer() throws Exception {
        givenNjMuleRunning();

        whenNjMuleSaysHelloToItself();

        thenTheAnswerComesFromSoftwareStartingWith("njMule " + NjMuleInfo.version());
        thenTheAnswerHasOurNick();
        thenTheAnswerIsNotObfuscated();
    }

    private void givenAServerOnTheLan(String welcome, FileEntry... files) throws IOException {
        this.local = lanAddress();
        this.server = server(new FakeServer.Config(highId(this.local), welcome, List.of(files)));
    }

    private void givenAServerOnTheLanGivingTheId(long clientId, String welcome) throws IOException {
        this.local = lanAddress();
        this.server = server(new FakeServer.Config(clientId, welcome, List.of()));
    }

    private void givenAnotherServerFinding(FileEntry... files) throws IOException {
        this.other = server(new FakeServer.Config(1, "", List.of(files)));
    }

    private void givenTheOtherServerDescribesItselfAs(String name, String description, String version) {
        this.other.describeAs(name, description, version);
    }

    private void givenTheServerFindsAFileNamedAfterTheFirstOrSecondSearch() {
        this.server.answerSearches(expression -> List.of(
                FakeServer.file(expression.toString().contains("first") ? "first.bin" : "second.bin", 1000, 1)));
    }

    private void givenTheServerKnowsTheServer(String ip, int port) {
        this.server.knowsServers(List.of(new ServerMessages.Endpoint(Ipv4.parse(ip), port)));
    }

    private void givenADeadPortOnTheLan() throws IOException {
        this.local = lanAddress();
        try (ServerSocket probe = new ServerSocket(0, 1, this.local)) {
            this.deadPort = probe.getLocalPort();
        }
    }

    private void givenOurIncomingFolderHolds(String name, byte[] content) throws IOException {
        Path incoming = Files.createDirectories(this.dir.resolve("njmule").resolve("incoming"));
        Files.write(incoming.resolve(name), content);
    }

    private void givenNjMuleRunning() throws IOException {
        startNjMule();
        this.events = new Events(this.engine);
    }

    private void givenNjMuleRunningAndTakingTheServerListsOfServers() throws Exception {
        startNjMule();
        this.engine
                .updatePreferences(p -> p.toBuilder().addServersFromServer(true).build())
                .get();
        this.events = new Events(this.engine);
    }

    private void givenNjMuleKnowsTheOtherServer() throws Exception {
        Path serverMet = this.dir.resolve("other-server.met");
        Files.write(
                serverMet,
                ServerMet.write(List.of(
                        new ServerMet.Server(Ipv4.fromBytes(this.local.getAddress()), this.other.port(), List.of()))));
        assertThat(this.engine.addServers(serverMet).get()).isEqualTo(1);
    }

    private void givenNjMuleKnowsTheDeadServerThenTheAliveOne() throws Exception {
        Path serverMet = this.dir.resolve("two-servers.met");
        int ip = Ipv4.fromBytes(this.local.getAddress());
        Files.write(
                serverMet,
                ServerMet.write(List.of(
                        new ServerMet.Server(ip, this.deadPort, List.of()),
                        new ServerMet.Server(ip, this.server.port(), List.of()))));
        this.engine.addServers(serverMet).get();
    }

    private void givenNjMuleKnowsTheDeadServerAndAnotherThatFailedOnce(String ip, int port) throws Exception {
        Path serverMet = this.dir.resolve("dead.met");
        List<Tag> failedOnce = List.of(new Tag.IntTag(TagName.id(ServerTag.FAIL), 1));
        Files.write(
                serverMet,
                ServerMet.write(List.of(
                        new ServerMet.Server(Ipv4.fromBytes(this.local.getAddress()), this.deadPort, failedOnce),
                        new ServerMet.Server(Ipv4.parse(ip), port, failedOnce))));
        this.engine.addServers(serverMet).get();
    }

    private void givenTheServerMadeStatic(String host, int port) throws Exception {
        assertThat(this.engine
                        .setStaticServer(new ServerAddress(host, port), true)
                        .get())
                .isTrue();
    }

    private void givenNjMuleConnectedToTheServer() throws Exception {
        whenNjMuleConnectsToTheServer();
    }

    private void givenTheLoginGotAHighIdAndTheMessageStartingWith(String start) throws InterruptedException {
        assertThat(this.login.lowId()).isFalse();
        assertThat(this.events.next(
                        EngineEvent.ServerMessage.class, e -> e.text().startsWith(start)))
                .isNotNull();
        assertThat(this.server.logins()).hasSize(1);
    }

    // The second search goes out before the answer to the first comes in.
    private void givenAServerSearchSentFor(String expression) {
        this.pending = this.engine.search(new SearchRequest(expression, SearchRequest.Scope.SERVER));
    }

    private void givenTheFileShared() throws Exception {
        this.deadline = System.nanoTime() + WAIT.toNanos();
        while (this.engine.sharedFiles().get().isEmpty()) {
            assertThat(System.nanoTime()).isLessThan(this.deadline);
            Thread.sleep(50);
        }
    }

    private void givenTheOtherServerPingedWithUsersAndFiles(int users, int files) throws InterruptedException {
        EngineEvent.ServerUpdated pinged = this.events.next(
                EngineEvent.ServerUpdated.class,
                e -> e.server().address().endsWith(":" + this.other.port())
                        && e.server().users() == users);
        assertThat(pinged.server().files()).isEqualTo(files);
    }

    private void givenAFirewalledPeerListeningOnTheLan() throws IOException {
        this.firewalled = new ServerSocket(0, 4, this.local);
        this.resources.add(this.firewalled);
    }

    // The same request again is not answered twice in a row.
    private void givenTheServerAskedTwiceToHaveThePeerCalled() throws IOException {
        Packet request = callbackRequested(Ipv4.fromBytes(this.local.getAddress()), this.firewalled.getLocalPort());
        this.server.broadcast(request);
        this.server.broadcast(request);
    }

    private void givenThePeerWasCalledWithAHello() throws IOException {
        try (Socket peer = this.firewalled.accept()) {
            peer.setSoTimeout(10_000);
            Packet hello = TcpFraming.read(peer.getInputStream(), FrameLimit.fixed(4096));
            assertThat(hello.opcode()).isEqualTo(0x01);
        }
    }

    private void whenASearchRuns(String expression, SearchRequest.Scope scope) throws Exception {
        SearchId id = this.engine.search(new SearchRequest(expression, scope)).get();
        while (true) {
            EngineEvent event = this.events.next(
                    EngineEvent.class,
                    e -> e instanceof EngineEvent.SearchResults || e instanceof EngineEvent.SearchEnded);
            if (event instanceof EngineEvent.SearchResults r && r.search().equals(id)) {
                r.results().forEach(result -> this.results.put(result.name(), result));
            } else if (event instanceof EngineEvent.SearchEnded end
                    && end.search().equals(id)) {
                this.ended = end;
                break;
            }
        }
    }

    private void whenAnotherServerSearchIsSentBeforeTheAnswerFor(String expression) throws Exception {
        this.search = this.engine
                .search(new SearchRequest(expression, SearchRequest.Scope.SERVER))
                .get();
        this.pending.get();
    }

    private void whenNjMuleConnectsToTheServer() throws Exception {
        this.engine
                .connect(Optional.of(new ServerAddress(this.local.getHostAddress(), this.server.port())))
                .get();
        this.login = this.events.next(EngineEvent.ServerConnected.class, e -> true);
    }

    private void whenNjMuleConnectsToAnyServer() throws Exception {
        this.engine.connect(Optional.empty()).get();
        this.login = this.events.next(EngineEvent.ServerConnected.class, e -> true);
    }

    private void whenThePeerWaitsForAnotherCallForMillis(int millis) throws IOException {
        this.firewalled.setSoTimeout(millis);
        this.exception = catchThrowable(this.firewalled::accept);
    }

    private void whenNjMuleSaysHelloToItself() throws Exception {
        int port = this.engine.preferences().tcpPort();
        this.answer = this.engine.hello(new ServerAddress("127.0.0.1", port)).get(10, TimeUnit.SECONDS);
    }

    private void thenTheOtherServerIsSoonDescribedAs(String name, String description, String version)
            throws InterruptedException {
        EngineEvent.ServerUpdated described = this.events.next(
                EngineEvent.ServerUpdated.class,
                e -> e.server().address().endsWith(":" + this.other.port())
                        && e.server().name().equals(name));
        assertThat(described.server().description()).isEqualTo(description);
        assertThat(described.server().version()).isEqualTo(version);
    }

    private void thenTheSearchEndedWithResults(int count) {
        assertThat(this.ended.results()).isEqualTo(count);
    }

    private void thenTheResultHas(String name, int sources, long size) {
        assertThat(this.results.get(name).sources()).isEqualTo(sources);
        assertThat(this.results.get(name).size()).isEqualTo(size);
    }

    private void thenTheResultsHold(String name) {
        assertThat(this.results).containsKey(name);
    }

    private void thenTheServerWasSearchedTimes(int times) {
        assertThat(this.server.searches()).hasSize(times);
    }

    private void thenTheOtherServerWasSearchedOverUdpTimes(int times) {
        assertThat(this.other.udpSearches()).hasSize(times);
    }

    private void thenTheResultsOfTheLatestSearchAre(String... names) throws InterruptedException {
        EngineEvent.SearchResults found = this.events.next(
                EngineEvent.SearchResults.class, r -> r.search().equals(this.search));
        assertThat(found.results()).extracting(SearchResult::name).containsExactly(names);
    }

    private void thenTheServerIsOffered(String name, long size, String type) throws InterruptedException {
        while (this.server.offers().isEmpty()) {
            assertThat(System.nanoTime()).isLessThan(this.deadline);
            Thread.sleep(50);
        }
        FileEntry offer = this.server.offers().getFirst();
        assertThat(offer.name()).contains(name);
        assertThat(offer.size()).hasValue(size);
        assertThat(offer.type()).contains(type);
    }

    // A server with compression learns our address from the connection.
    private void thenTheOfferLeavesOurAddressToTheServer() {
        FileEntry offer = this.server.offers().getFirst();
        assertThat(offer.clientId()).isEqualTo(0xFBFB_FBFBL);
        assertThat(offer.port()).isEqualTo(0xFBFB);
    }

    private void thenTheLoginGotTheLowId(long clientId) {
        assertThat(this.login.lowId()).isTrue();
        assertThat(this.login.clientId()).isEqualTo(clientId);
    }

    private void thenTheLoginIsOnTheAliveServer() {
        assertThat(this.login.server().address()).endsWith(":" + this.server.port());
    }

    private void thenTheServerListChangesTimes(int times) throws InterruptedException {
        for (int i = 0; i < times; i++) {
            this.events.next(EngineEvent.ServersChanged.class, e -> true);
        }
    }

    private void thenNjMuleKnowsTheServerAnd(String... addresses) throws Exception {
        List<String> expected = new ArrayList<>(List.of(addresses));
        expected.add(this.local.getHostAddress() + ":" + this.server.port());
        assertThat(this.engine.servers().get())
                .extracting(ServerInfo::address)
                .containsExactlyInAnyOrderElementsOf(expected);
    }

    private void thenTheServerHasThePriority(String address, ServerInfo.Priority priority) throws Exception {
        assertThat(this.engine.servers().get())
                .filteredOn(s -> s.address().equals(address))
                .singleElement()
                .satisfies(s -> assertThat(s.priority()).isEqualTo(priority));
    }

    private void thenExceptionOccurred(Class<? extends Throwable> type) {
        assertThat(this.exception).isInstanceOf(type);
    }

    private void thenTheAnswerComesFromSoftwareStartingWith(String start) {
        assertThat(this.answer.software()).startsWith(start);
    }

    private void thenTheAnswerHasOurNick() {
        assertThat(this.answer.nick()).contains(this.engine.preferences().nick());
    }

    private void thenTheAnswerIsNotObfuscated() {
        assertThat(this.answer.obfuscated()).isFalse();
    }

    private static Packet callbackRequested(int ip, int port) {
        return Packet.of(
                Protocol.EDONKEY, 0x35, new PacketWriter().u32(Ipv4.toEd2k(ip)).u16(port));
    }

    private FakeServer server(FakeServer.Config config) throws IOException {
        FakeServer started = FakeServer.start(this.local, config);
        this.resources.add(started);
        return started;
    }

    private void startNjMule() throws IOException {
        AppDirectories dirs = AppDirectories.underRoot(this.dir.resolve("njmule"), true);
        Files.createDirectories(dirs.config());
        int port;
        try (ServerSocket probe = new ServerSocket(0)) {
            port = probe.getLocalPort();
        }
        // The fake servers and peers listen on this machine's LAN address, which must be let in.
        Files.writeString(
                dirs.config().resolve("njmule.properties"), "net.tcpPort=" + port + "\nsecurity.filterLan=false\n");
        this.engine = NjMuleEngine.start(new EngineConfig(dirs));
        this.resources.add(this.engine);
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
