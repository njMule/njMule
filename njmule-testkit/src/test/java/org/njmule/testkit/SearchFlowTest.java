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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
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
import org.njmule.core.api.SearchId;
import org.njmule.core.api.SearchRequest;
import org.njmule.core.api.ServerAddress;
import org.njmule.core.config.AppDirectories;
import org.njmule.core.persist.format.ServerMet;
import org.njmule.core.protocol.tag.ServerTag;
import org.njmule.core.protocol.tag.Tag;
import org.njmule.core.protocol.tag.TagName;
import org.njmule.core.util.Ipv4;

/** Searches on the servers, end to end through the engine, with fake servers on this machine's LAN address. */
@Timeout(60)
class SearchFlowTest {

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
    void aNewGlobalSearchEndsTheOneBeforeSoUdpAnswersReachTheNewOne() throws Exception {
        InetAddress local = ServerFlowTest.lanAddress();
        FakeServer connected = this.server(local, new FakeServer.Config(highId(local), "", List.of()));
        connected.answerSearches(expression -> List.of(
                FakeServer.file(expression.toString().contains("first") ? "first.bin" : "second.bin", 1000, 1)));
        FakeServer other =
                this.server(local, new FakeServer.Config(1, "", List.of(FakeServer.file("udp.bin", 2000, 1))));
        NjMuleEngine engine = this.engine();
        Events events = new Events(engine);
        this.knows(engine, local, other.port(), List.of());
        this.connect(engine, events, local, connected);

        SearchId first = engine.search(new SearchRequest("first", SearchRequest.Scope.GLOBAL))
                .get();
        this.waitFor(() -> other.udpSearches().size() == 1);
        SearchId second = engine.search(new SearchRequest("second", SearchRequest.Scope.GLOBAL))
                .get();

        Set<String> secondResults = new HashSet<>();
        boolean firstEnded = false;
        while (true) {
            EngineEvent event = events.next(
                    EngineEvent.class,
                    e -> e instanceof EngineEvent.SearchResults || e instanceof EngineEvent.SearchEnded);
            if (event instanceof EngineEvent.SearchEnded ended && ended.search().equals(first)) {
                firstEnded = true;
            } else if (event instanceof EngineEvent.SearchResults r
                    && r.search().equals(second)) {
                r.results().forEach(result -> secondResults.add(result.name()));
            } else if (event instanceof EngineEvent.SearchEnded ended
                    && ended.search().equals(second)) {
                break;
            }
        }
        assertThat(firstEnded).as("the first search ended before the second").isTrue();
        assertThat(secondResults).containsExactlyInAnyOrder("second.bin", "udp.bin");
        assertThat(other.udpSearches()).hasSize(2);
    }

    @Test
    void moreResultsAreAskedOnlyForTheLatestServerSearch() throws Exception {
        InetAddress local = ServerFlowTest.lanAddress();
        FakeServer server = this.server(local, new FakeServer.Config(highId(local), "", List.of()));
        server.answerSearches(expression -> List.of(FakeServer.file(expression + ".bin", 1000, 1)));
        server.announceMoreResults(true);
        NjMuleEngine engine = this.engine();
        Events events = new Events(engine);
        this.connect(engine, events, local, server);

        SearchId first = engine.search(new SearchRequest("first", SearchRequest.Scope.SERVER))
                .get();
        events.next(EngineEvent.SearchResults.class, r -> r.search().equals(first));
        SearchId second = engine.search(new SearchRequest("second", SearchRequest.Scope.SERVER))
                .get();
        events.next(EngineEvent.SearchResults.class, r -> r.search().equals(second));

        assertThat(engine.moreResults(first).get()).isFalse();
        assertThat(engine.moreResults(second).get()).isTrue();
        this.waitFor(() -> server.moreRequests().size() == 1);
        assertThat(server.moreRequests().getFirst().toString()).contains("second");
    }

    @Test
    void aGlobalSearchSkipsServersWithoutAUdpPort() throws Exception {
        InetAddress local = ServerFlowTest.lanAddress();
        FakeServer connected = this.server(local, new FakeServer.Config(highId(local), "", List.of()));
        FakeServer other =
                this.server(local, new FakeServer.Config(1, "", List.of(FakeServer.file("udp.bin", 2000, 1))));
        NjMuleEngine engine = this.engine();
        Events events = new Events(engine);
        // Asked first for its high priority; its UDP port, TCP port + 4, does not exist.
        this.knows(engine, local, 65_533, List.of(new Tag.IntTag(TagName.id(ServerTag.PREFERENCE), 1)));
        this.knows(engine, local, other.port(), List.of());
        this.connect(engine, events, local, connected);

        // The engine survives a failing timer job and only logs it, so the log (on the console) is what tells.
        ByteArrayOutputStream log = new ByteArrayOutputStream();
        PrintStream console = System.out;
        System.setOut(new PrintStream(
                new OutputStream() {
                    @Override
                    public void write(int b) {
                        console.write(b);
                        log.write(b);
                    }
                },
                true,
                StandardCharsets.UTF_8));
        EngineEvent.SearchEnded ended;
        try {
            SearchId search = engine.search(new SearchRequest("anything", SearchRequest.Scope.GLOBAL))
                    .get();
            ended = events.next(EngineEvent.SearchEnded.class, e -> e.search().equals(search));
        } finally {
            System.setOut(console);
        }

        assertThat(ended.results()).isEqualTo(1);
        assertThat(other.udpSearches()).hasSize(1);
        assertThat(log.toString(StandardCharsets.UTF_8)).doesNotContain("global search failed");
    }

    private void knows(NjMuleEngine engine, InetAddress address, int port, List<Tag> tags) throws Exception {
        Path serverMet = Files.createTempFile(this.dir, "known", ".met");
        Files.write(
                serverMet,
                ServerMet.write(List.of(new ServerMet.Server(Ipv4.fromBytes(address.getAddress()), port, tags))));
        assertThat(engine.addServers(serverMet).get()).isEqualTo(1);
    }

    private void connect(NjMuleEngine engine, Events events, InetAddress address, FakeServer server) throws Exception {
        engine.connect(Optional.of(new ServerAddress(address.getHostAddress(), server.port())))
                .get();
        events.next(EngineEvent.ServerConnected.class, e -> true);
    }

    private void waitFor(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (!condition.getAsBoolean()) {
            assertThat(System.nanoTime()).isLessThan(deadline);
            Thread.sleep(20);
        }
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
        Files.writeString(
                dirs.config().resolve("njmule.properties"), "net.tcpPort=" + port + "\nsecurity.filterLan=false\n");
        NjMuleEngine engine = NjMuleEngine.start(new EngineConfig(dirs));
        this.resources.add(engine);
        return engine;
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
