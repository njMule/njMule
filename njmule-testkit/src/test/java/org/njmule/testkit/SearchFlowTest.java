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
    private final ByteArrayOutputStream log = new ByteArrayOutputStream();
    private InetAddress local;
    private FakeServer server;
    private FakeServer other;
    private NjMuleEngine engine;
    private Events events;
    private SearchId earlier;
    private SearchId latest;
    private EngineEvent.SearchEnded ended;
    private boolean more;
    private PrintStream console;

    @AfterEach
    void closeEverything() throws Exception {
        if (this.console != null) {
            System.setOut(this.console);
        }
        Collections.reverse(this.resources);
        for (AutoCloseable resource : this.resources) {
            resource.close();
        }
    }

    @Test
    void shouldEndTheGlobalSearchBeforeANewOneSoUdpAnswersReachTheNewOne() throws Exception {
        givenAServerOnTheLan();
        givenTheServerFindsAFileNamedAfterTheFirstOrSecondSearch();
        givenAnotherServerFinding("udp.bin", 2000);
        givenNjMuleRunning();
        givenNjMuleKnowsTheOtherServer();
        givenNjMuleConnectedToTheServer();
        givenASearchStarted("first", SearchRequest.Scope.GLOBAL);
        givenTheOtherServerSearchedOverUdpTimes(1);

        whenASearchStarts("second", SearchRequest.Scope.GLOBAL);

        thenTheEarlierSearchEndsBeforeTheLatestFinds("second.bin", "udp.bin");
        thenTheOtherServerWasSearchedOverUdpTimes(2);
    }

    @Test
    void shouldAskMoreResultsOnlyForTheLatestServerSearch() throws Exception {
        givenAServerOnTheLan();
        givenTheServerFindsAFileNamedAfterTheSearchAndHasMore();
        givenNjMuleRunning();
        givenNjMuleConnectedToTheServer();
        givenAServerSearchAnswered("first");
        givenAServerSearchAnswered("second");
        givenNoMoreResultsAreAskedForTheEarlierSearch();

        whenMoreResultsAreAskedForTheLatestSearch();

        thenMoreResultsAreAsked();
        thenTheServerIsAskedForMoreResultsOnlyOf("second");
    }

    @Test
    void shouldSkipServersWithoutAUdpPortInAGlobalSearch() throws Exception {
        givenAServerOnTheLan();
        givenAnotherServerFinding("udp.bin", 2000);
        givenNjMuleRunning();
        givenNjMuleKnowsAPreferredServerWithoutUdpPortAt(65_533);
        givenNjMuleKnowsTheOtherServer();
        givenNjMuleConnectedToTheServer();
        givenTheConsoleRecorded();

        whenASearchRuns("anything", SearchRequest.Scope.GLOBAL);

        thenTheSearchEndedWithResults(1);
        thenTheOtherServerWasSearchedOverUdpTimes(1);
        thenTheConsoleDoesNotShow("global search failed");
    }

    private void givenAServerOnTheLan() throws IOException {
        this.local = ServerFlowTest.lanAddress();
        this.server = server(new FakeServer.Config(highId(this.local), "", List.of()));
    }

    private void givenTheServerFindsAFileNamedAfterTheFirstOrSecondSearch() {
        this.server.answerSearches(expression -> List.of(
                FakeServer.file(expression.toString().contains("first") ? "first.bin" : "second.bin", 1000, 1)));
    }

    private void givenTheServerFindsAFileNamedAfterTheSearchAndHasMore() {
        this.server.answerSearches(expression -> List.of(FakeServer.file(expression + ".bin", 1000, 1)));
        this.server.announceMoreResults(true);
    }

    private void givenAnotherServerFinding(String name, long size) throws IOException {
        this.other = server(new FakeServer.Config(1, "", List.of(FakeServer.file(name, size, 1))));
    }

    private void givenNjMuleRunning() throws IOException {
        AppDirectories dirs = AppDirectories.underRoot(this.dir.resolve("njmule"), true);
        Files.createDirectories(dirs.config());
        int port = FreePorts.tcp();
        Files.writeString(
                dirs.config().resolve("njmule.properties"), "net.tcpPort=" + port + "\nsecurity.filterLan=false\n");
        this.engine = NjMuleEngine.start(new EngineConfig(dirs));
        this.resources.add(this.engine);
        this.events = new Events(this.engine);
    }

    private void givenNjMuleKnowsTheOtherServer() throws Exception {
        knows(this.other.port(), List.of());
    }

    // Asked first for its high priority; its UDP port, TCP port + 4, does not exist.
    private void givenNjMuleKnowsAPreferredServerWithoutUdpPortAt(int port) throws Exception {
        knows(port, List.of(new Tag.IntTag(TagName.id(ServerTag.PREFERENCE), 1)));
    }

    private void givenNjMuleConnectedToTheServer() throws Exception {
        this.engine
                .connect(Optional.of(new ServerAddress(this.local.getHostAddress(), this.server.port())))
                .get();
        this.events.next(EngineEvent.ServerConnected.class, e -> true);
    }

    private void givenASearchStarted(String expression, SearchRequest.Scope scope) throws Exception {
        whenASearchStarts(expression, scope);
    }

    private void givenAServerSearchAnswered(String expression) throws Exception {
        whenASearchStarts(expression, SearchRequest.Scope.SERVER);
        SearchId search = this.latest;
        this.events.next(EngineEvent.SearchResults.class, r -> r.search().equals(search));
    }

    private void givenTheOtherServerSearchedOverUdpTimes(int times) throws InterruptedException {
        waitFor(() -> this.other.udpSearches().size() == times);
    }

    private void givenNoMoreResultsAreAskedForTheEarlierSearch() throws Exception {
        assertThat(this.engine.moreResults(this.earlier).get()).isFalse();
    }

    // The engine survives a failing timer job and only logs it, so the log (on the console) is what tells.
    private void givenTheConsoleRecorded() {
        PrintStream original = System.out;
        this.console = original;
        System.setOut(new PrintStream(
                new OutputStream() {
                    @Override
                    public void write(int b) {
                        original.write(b);
                        SearchFlowTest.this.log.write(b);
                    }
                },
                true,
                StandardCharsets.UTF_8));
    }

    private void whenASearchStarts(String expression, SearchRequest.Scope scope) throws Exception {
        this.earlier = this.latest;
        this.latest = this.engine.search(new SearchRequest(expression, scope)).get();
    }

    private void whenASearchRuns(String expression, SearchRequest.Scope scope) throws Exception {
        whenASearchStarts(expression, scope);
        SearchId search = this.latest;
        this.ended =
                this.events.next(EngineEvent.SearchEnded.class, e -> e.search().equals(search));
    }

    private void whenMoreResultsAreAskedForTheLatestSearch() throws Exception {
        this.more = this.engine.moreResults(this.latest).get();
    }

    private void thenTheEarlierSearchEndsBeforeTheLatestFinds(String... names) throws InterruptedException {
        Set<String> latestResults = new HashSet<>();
        boolean earlierEnded = false;
        while (true) {
            EngineEvent event = this.events.next(
                    EngineEvent.class,
                    e -> e instanceof EngineEvent.SearchEnded || e instanceof EngineEvent.SearchResults);
            if (event instanceof EngineEvent.SearchEnded end && end.search().equals(this.earlier)) {
                earlierEnded = true;
            } else if (event instanceof EngineEvent.SearchResults r
                    && r.search().equals(this.latest)) {
                r.results().forEach(result -> latestResults.add(result.name()));
            } else if (event instanceof EngineEvent.SearchEnded end
                    && end.search().equals(this.latest)) {
                break;
            }
        }
        assertThat(earlierEnded).as("the first search ended before the second").isTrue();
        assertThat(latestResults).containsExactlyInAnyOrder(names);
    }

    private void thenTheOtherServerWasSearchedOverUdpTimes(int times) {
        assertThat(this.other.udpSearches()).hasSize(times);
    }

    private void thenMoreResultsAreAsked() {
        assertThat(this.more).isTrue();
    }

    private void thenTheServerIsAskedForMoreResultsOnlyOf(String expression) throws InterruptedException {
        waitFor(() -> this.server.moreRequests().size() == 1);
        assertThat(this.server.moreRequests().getFirst().toString()).contains(expression);
    }

    private void thenTheSearchEndedWithResults(int results) {
        assertThat(this.ended.results()).isEqualTo(results);
    }

    private void thenTheConsoleDoesNotShow(String text) {
        assertThat(this.log.toString(StandardCharsets.UTF_8)).doesNotContain(text);
    }

    private void knows(int port, List<Tag> tags) throws Exception {
        Path serverMet = Files.createTempFile(this.dir, "known", ".met");
        Files.write(
                serverMet,
                ServerMet.write(List.of(new ServerMet.Server(Ipv4.fromBytes(this.local.getAddress()), port, tags))));
        assertThat(this.engine.addServers(serverMet).get()).isEqualTo(1);
    }

    private void waitFor(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (!condition.getAsBoolean()) {
            assertThat(System.nanoTime()).isLessThan(deadline);
            Thread.sleep(20);
        }
    }

    private FakeServer server(FakeServer.Config config) throws IOException {
        FakeServer started = FakeServer.start(this.local, config);
        this.resources.add(started);
        return started;
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
