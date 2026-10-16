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

import java.io.IOException;
import java.net.InetAddress;
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
import org.njmule.core.protocol.ed2k.ServerDatagrams;
import org.njmule.core.protocol.tag.ServerTag;
import org.njmule.core.protocol.tag.Tag;
import org.njmule.core.protocol.tag.TagName;
import org.njmule.core.util.Ipv4;

/** Global searches to a server that takes obfuscated datagrams, with the UDP key it gave for our address. */
@Timeout(60)
class ObfuscatedServerUdpFlowTest {

    private static final Duration WAIT = Duration.ofSeconds(30);
    private static final int KEY = 0x5EC2_E701;

    @TempDir
    Path dir;

    private final List<AutoCloseable> resources = new ArrayList<>();
    private final Set<String> results = new HashSet<>();
    private InetAddress local;
    private FakeServer other;
    private NjMuleEngine engine;

    @AfterEach
    void closeEverything() throws Exception {
        Collections.reverse(this.resources);
        for (AutoCloseable resource : this.resources) {
            resource.close();
        }
    }

    @Test
    void shouldSearchAServerObfuscatedAndReadItsObfuscatedAnswer() throws Exception {
        givenNjMuleOnAServer();
        givenAnotherServerTakingObfuscatedDatagrams();
        givenNjMuleKnowsItWithTheKeyGivenFor(ourAddress());
        whenAGlobalSearchRuns();
        thenTheOtherServerGotTheSearchObfuscatedOnly();
        thenItsAnswerReachedTheSearch();
    }

    @Test
    void shouldSearchPlainWithAKeyGivenForAnotherAddressOfOurs() throws Exception {
        givenNjMuleOnAServer();
        givenAnotherServerTakingObfuscatedDatagrams();
        givenNjMuleKnowsItWithTheKeyGivenFor(Ipv4.of(93, 40, 9, 9));
        whenAGlobalSearchRuns();
        thenTheOtherServerGotTheSearchPlainOnly();
    }

    private void givenNjMuleOnAServer() throws Exception {
        this.local = ServerFlowTest.lanAddress();
        FakeServer connected = server(new FakeServer.Config(Ipv4.toEd2k(ourAddress()), "", List.of()));
        this.engine = engine();
        Events events = new Events(this.engine);
        this.engine
                .connect(Optional.of(new ServerAddress(this.local.getHostAddress(), connected.port())))
                .get();
        events.next(EngineEvent.ServerConnected.class);
    }

    private void givenAnotherServerTakingObfuscatedDatagrams() throws IOException {
        this.other = server(new FakeServer.Config(1, "", List.of(FakeServer.file("udp.bin", 2000, 1))));
        this.other.takeObfuscatedUdp(KEY);
    }

    private void givenNjMuleKnowsItWithTheKeyGivenFor(int ourIp) throws Exception {
        List<Tag> tags = List.of(
                intTag(ServerTag.UDPFLAGS, ServerDatagrams.UDP_OBFUSCATION),
                intTag(ServerTag.UDPPORTOBFUSCATION, this.other.obfuscatedUdpPort()),
                intTag(ServerTag.UDPKEY, Integer.toUnsignedLong(KEY)),
                intTag(ServerTag.UDPKEYIP, Ipv4.toEd2k(ourIp)));
        Path serverMet = Files.createTempFile(this.dir, "known", ".met");
        Files.write(serverMet, ServerMet.write(List.of(new ServerMet.Server(ourAddress(), this.other.port(), tags))));
        assertThat(this.engine.addServers(serverMet).get()).isEqualTo(1);
    }

    private void whenAGlobalSearchRuns() throws Exception {
        Events events = new Events(this.engine);
        SearchId search = this.engine
                .search(new SearchRequest("anything", SearchRequest.Scope.GLOBAL))
                .get();
        while (true) {
            EngineEvent event = events.next(EngineEvent.class);
            if (event instanceof EngineEvent.SearchResults r && r.search().equals(search)) {
                r.results().forEach(result -> this.results.add(result.name()));
            } else if (event instanceof EngineEvent.SearchEnded ended
                    && ended.search().equals(search)) {
                return;
            }
        }
    }

    private void thenTheOtherServerGotTheSearchObfuscatedOnly() {
        assertThat(this.other.obfuscatedUdpSearches()).hasSize(1);
        assertThat(this.other.udpSearches()).isEmpty();
    }

    private void thenTheOtherServerGotTheSearchPlainOnly() {
        assertThat(this.other.udpSearches()).hasSize(1);
        assertThat(this.other.obfuscatedUdpSearches()).isEmpty();
    }

    private void thenItsAnswerReachedTheSearch() {
        assertThat(this.results).contains("udp.bin");
    }

    // The test servers and njMule share this computer's LAN address.
    private int ourAddress() {
        return Ipv4.fromBytes(this.local.getAddress());
    }

    private FakeServer server(FakeServer.Config config) throws IOException {
        FakeServer server = FakeServer.start(this.local, config);
        this.resources.add(server);
        return server;
    }

    private NjMuleEngine engine() throws IOException {
        AppDirectories dirs = AppDirectories.underRoot(this.dir.resolve("njmule"), true);
        Files.createDirectories(dirs.config());
        int port = FreePorts.tcp();
        Files.writeString(
                dirs.config().resolve("njmule.properties"),
                "net.tcpPort=" + port + "\nnet.kad.enabled=false\nsecurity.filterLan=false\n");
        NjMuleEngine started = NjMuleEngine.start(new EngineConfig(dirs));
        this.resources.add(started);
        return started;
    }

    private static Tag intTag(int id, long value) {
        return new Tag.IntTag(TagName.id(id), value);
    }

    /** The engine's events, in order. */
    private static final class Events implements Flow.Subscriber<EngineEvent> {
        private final BlockingQueue<EngineEvent> queue = new LinkedBlockingQueue<>();

        Events(NjMuleEngine engine) {
            engine.events().subscribe(this);
        }

        <T extends EngineEvent> T next(Class<T> type) throws InterruptedException {
            long deadline = System.nanoTime() + WAIT.toNanos();
            while (System.nanoTime() < deadline) {
                EngineEvent event = this.queue.poll(100, TimeUnit.MILLISECONDS);
                if (type.isInstance(event)) {
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
