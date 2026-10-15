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
package org.njmule.core.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Flow;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.core.api.Ed2kLink;
import org.njmule.core.api.EngineConfig;
import org.njmule.core.api.EngineEvent;
import org.njmule.core.api.Hash16;
import org.njmule.core.api.NjMuleEngine;
import org.njmule.core.api.ServerAddress;
import org.njmule.core.config.AppDirectories;
import org.njmule.core.persist.format.IpFilterDat;
import org.njmule.core.util.Ipv4;

@Timeout(30)
class FilterChangeTest {

    private static final Ed2kLink.FileLink FILM =
            Ed2kLink.FileLink.of("film.mkv", 5000, Hash16.parseHex("a1".repeat(16)));

    @TempDir
    Path dir;

    private final BlockingQueue<EngineEvent> events = new LinkedBlockingQueue<>();
    private AppDirectories dirs;
    private ServerSocket server;
    private NjMuleEngine engine;

    @AfterEach
    void close() throws IOException {
        if (this.engine != null) {
            this.engine.close();
        }
        if (this.server != null) {
            this.server.close();
        }
    }

    @Test
    void shouldDropTheServerAndTheSourcesThatAStricterFilterNowRefuses() throws Exception {
        givenAProfileFilteringAtLevel(40);
        givenAnIpFilterThatRatesTheLoopbackAt(50);
        givenAServerOnTheLoopback();
        givenARunningEngine();
        givenADownloadWithASourceAt("127.0.0.3");
        givenTheEngineConnectsToTheServer();

        whenTheFilterLevelIsRaisedTo(100);

        thenTheServerIsLeft();
        thenTheSourceIsDropped();
    }

    private void givenAProfileFilteringAtLevel(int level) throws IOException {
        this.dirs = AppDirectories.underRoot(this.dir.resolve("njmule"), false);
        Files.createDirectories(this.dirs.config());
        int tcpPort;
        try (ServerSocket probe = new ServerSocket(0)) {
            tcpPort = probe.getLocalPort();
        }
        Files.writeString(
                this.dirs.config().resolve("njmule.properties"),
                "net.tcpPort=" + tcpPort + "\nnet.kad.enabled=false\nsecurity.ipFilterLevel=" + level + "\n");
    }

    // Level 50 is let through at 40 and blocked at 100.
    private void givenAnIpFilterThatRatesTheLoopbackAt(int level) throws IOException {
        Files.write(
                this.dirs.config().resolve(Network.IPFILTER_FILE),
                IpFilterDat.write(List.of(
                        new IpFilterDat.Range(Ipv4.of(127, 0, 0, 1), Ipv4.of(127, 0, 0, 3), level, "loopback"))));
    }

    private void givenAServerOnTheLoopback() throws IOException {
        this.server = new ServerSocket(0, 5, InetAddress.getLoopbackAddress());
    }

    private void givenARunningEngine() throws IOException {
        this.engine = NjMuleEngine.start(new EngineConfig(this.dirs));
        BlockingQueue<EngineEvent> recorded = this.events;
        this.engine.events().subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(EngineEvent item) {
                recorded.add(item);
            }

            @Override
            public void onError(Throwable throwable) {}

            @Override
            public void onComplete() {}
        });
    }

    private void givenADownloadWithASourceAt(String ip) throws Exception {
        this.engine.addDownload(FILM).get(5, TimeUnit.SECONDS);
        assertThat(this.engine
                        .addSource(FILM.hash(), new ServerAddress(ip, 4662))
                        .get(5, TimeUnit.SECONDS))
                .isTrue();
    }

    private void givenTheEngineConnectsToTheServer() throws Exception {
        this.engine
                .connect(Optional.of(new ServerAddress("127.0.0.1", this.server.getLocalPort())))
                .get(5, TimeUnit.SECONDS);
        assertThat(next(this.events, EngineEvent.ServerConnecting.class)).isTrue();
    }

    private void whenTheFilterLevelIsRaisedTo(int level) throws Exception {
        this.engine
                .updatePreferences(p -> p.toBuilder().ipFilterLevel(level).build())
                .get(5, TimeUnit.SECONDS);
    }

    private void thenTheServerIsLeft() throws InterruptedException {
        assertThat(next(this.events, EngineEvent.ServerDisconnected.class))
                .as("the server is left")
                .isTrue();
    }

    private void thenTheSourceIsDropped() throws Exception {
        assertThat(this.engine.sources().get(5, TimeUnit.SECONDS))
                .as("the source is dropped")
                .isEmpty();
    }

    private static boolean next(BlockingQueue<EngineEvent> events, Class<? extends EngineEvent> type)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (type.isInstance(events.poll(100, TimeUnit.MILLISECONDS))) {
                return true;
            }
        }
        return false;
    }
}
