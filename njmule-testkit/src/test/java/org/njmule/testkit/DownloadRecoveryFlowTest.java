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
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Flow;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.core.api.DownloadInfo;
import org.njmule.core.api.Ed2kLink;
import org.njmule.core.api.EngineConfig;
import org.njmule.core.api.EngineEvent;
import org.njmule.core.api.Hash16;
import org.njmule.core.api.Hash20;
import org.njmule.core.api.NjMuleEngine;
import org.njmule.core.api.ServerAddress;
import org.njmule.core.config.AppDirectories;
import org.njmule.core.persist.format.IpFilterDat;
import org.njmule.core.util.Ipv4;

/** Downloads that must recover from corrupt parts and from sources that leave. */
@Timeout(120)
class DownloadRecoveryFlowTest {

    private static final int PART = 9_728_000;
    private static final int BLOCK = 184_320;
    // Well below the 30 s an idle slot is kept: a source left idle would close first.
    private static final Duration SOON = Duration.ofSeconds(20);
    private static final Duration WAIT = Duration.ofSeconds(90);

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
    void aSourceWithASlotTakesTheBlocksOfOneThatLeft() throws Exception {
        byte[] data = content(5 * BLOCK - 1000, 21);
        Path shared = this.share(data);
        FakePeer silent = this.peer(shared, new FakePeer.Config(hash(21), false, FakePeer.Slot.NOW, 0, Set.of()));
        silent.stall();
        NjMuleEngine engine = this.engine();
        Events events = new Events(engine);
        DownloadInfo added = engine.addDownload(silent.link()).get(10, TimeUnit.SECONDS);
        await(() -> silent.received().contains("REQUESTPARTS"), "the first source was asked for blocks");

        // The second source gets the two blocks left, then has nothing to ask for.
        FakePeer second = this.peer(shared, new FakePeer.Config(hash(22), false, FakePeer.Slot.NOW, 0, Set.of()));
        assertThat(engine.addSource(added.hash(), new ServerAddress("127.0.0.1", second.port()))
                        .get(10, TimeUnit.SECONDS))
                .isTrue();
        await(() -> second.bytesSent() == data.length - 3L * BLOCK, "the second source sent the blocks left");
        Thread.sleep(500);
        silent.dropDownloaders();

        Path file = events.completed(added.hash(), SOON).file();
        assertThat(Files.readAllBytes(file)).isEqualTo(data);
    }

    @Test
    void aSourceWithASlotTakesTheBlocksOfOneTheIpFilterNowRefuses() throws Exception {
        byte[] data = content(5 * BLOCK - 1000, 28);
        Path shared = this.share(data);
        FakePeer filtered = FakePeer.start(
                InetAddress.getByName("127.0.0.2"),
                shared,
                new FakePeer.Config(hash(28), false, FakePeer.Slot.NOW, 0, Set.of()));
        this.resources.add(filtered);
        filtered.stall();
        // The range blocks only once the filter level is raised above its own.
        NjMuleEngine engine =
                this.engine(List.of(new IpFilterDat.Range(Ipv4.of(127, 0, 0, 2), Ipv4.of(127, 0, 0, 2), 150, "test")));
        Events events = new Events(engine);
        DownloadInfo added = engine.addDownload(filtered.link()).get(10, TimeUnit.SECONDS);
        await(() -> filtered.received().contains("REQUESTPARTS"), "the first source was asked for blocks");
        FakePeer second = this.peer(shared, new FakePeer.Config(hash(29), false, FakePeer.Slot.NOW, 0, Set.of()));
        assertThat(engine.addSource(added.hash(), new ServerAddress("127.0.0.1", second.port()))
                        .get(10, TimeUnit.SECONDS))
                .isTrue();
        await(() -> second.bytesSent() == data.length - 3L * BLOCK, "the second source sent the blocks left");
        Thread.sleep(500);

        engine.updatePreferences(p -> p.toBuilder().ipFilterLevel(200).build()).get(10, TimeUnit.SECONDS);

        Path file = events.completed(added.hash(), SOON).file();
        assertThat(Files.readAllBytes(file)).isEqualTo(data);
    }

    @Test
    void aPartWithoutRecoveryDataIsDownloadedAgainAtOnce() throws Exception {
        byte[] data = content(2 * PART + 1000, 23);
        FakePeer peer =
                this.peer(this.share(data), new FakePeer.Config(hash(23), false, FakePeer.Slot.NOW, 0, Set.of(1)));
        NjMuleEngine engine = this.engine();
        Events events = new Events(engine);
        // A master hash the source does not know: it answers that it has no recovery data.
        Ed2kLink.FileLink link = peer.link();
        Ed2kLink.FileLink unknownAich = new Ed2kLink.FileLink(
                link.name(),
                link.size(),
                link.hash(),
                Optional.of(Hash20.parseBase32("Q".repeat(32))),
                link.partHashes(),
                link.sources(),
                link.httpSources());

        DownloadInfo added = engine.addDownload(unknownAich).get(10, TimeUnit.SECONDS);

        Path file = events.completed(added.hash(), SOON).file();
        assertThat(Files.readAllBytes(file)).isEqualTo(data);
        assertThat(peer.received()).contains("AICHREQUEST");
    }

    @Test
    void theRestOfABlockWhoseCompressedStreamEndsEarlyIsAskedForAtOnce() throws Exception {
        byte[] data = content(3 * BLOCK, 26);
        FakePeer peer = this.peer(this.share(data), FakePeer.Config.simple(hash(26)));
        peer.truncateNextBlock();
        NjMuleEngine engine = this.engine();
        Events events = new Events(engine);

        DownloadInfo added = engine.addDownload(peer.link()).get(10, TimeUnit.SECONDS);

        Path file = events.completed(added.hash(), SOON).file();
        assertThat(Files.readAllBytes(file)).isEqualTo(data);
    }

    @Test
    void aFileOfOnePartRecoversItsCorruptBlock() throws Exception {
        byte[] data = content(PART - 1000, 27);
        FakePeer peer =
                this.peer(this.share(data), new FakePeer.Config(hash(27), false, FakePeer.Slot.NOW, 0, Set.of(0)));
        NjMuleEngine engine = this.engine();
        Events events = new Events(engine);

        DownloadInfo added = engine.addDownload(peer.link()).get(10, TimeUnit.SECONDS);

        Path file = events.completed(added.hash(), WAIT).file();
        assertThat(Files.readAllBytes(file)).isEqualTo(data);
        assertThat(peer.received()).contains("AICHREQUEST");
        assertThat(peer.bytesSent()).as("only the bad block comes again").isLessThan(data.length + PART / 10);
    }

    @Test
    void aFileDownloadedAgainRecoversItsCorruptPartsAgain() throws Exception {
        byte[] data = content(3 * PART + 1000, 24);
        Path shared = this.share(data);
        NjMuleEngine engine = this.engine();
        Events events = new Events(engine);
        FakePeer first = this.peer(shared, new FakePeer.Config(hash(24), false, FakePeer.Slot.NOW, 0, Set.of(1)));
        DownloadInfo once = engine.addDownload(first.link()).get(10, TimeUnit.SECONDS);
        events.completed(once.hash(), WAIT);
        assertThat(first.received()).contains("AICHREQUEST");

        FakePeer again = this.peer(shared, new FakePeer.Config(hash(25), false, FakePeer.Slot.NOW, 0, Set.of(1)));
        DownloadInfo twice = engine.addDownload(again.link()).get(10, TimeUnit.SECONDS);

        Path file = events.completed(twice.hash(), WAIT).file();
        assertThat(Files.readAllBytes(file)).isEqualTo(data);
        assertThat(again.received())
                .as("the part tried before is recovered again")
                .contains("AICHREQUEST");
    }

    private Path share(byte[] data) throws IOException {
        Path shared = Files.createDirectories(this.dir.resolve("shared")).resolve("shared.bin");
        Files.write(shared, data);
        return shared;
    }

    private FakePeer peer(Path shared, FakePeer.Config config) throws IOException {
        FakePeer peer = FakePeer.start(InetAddress.getLoopbackAddress(), shared, config);
        this.resources.add(peer);
        return peer;
    }

    private NjMuleEngine engine() throws IOException {
        return this.engine(List.of());
    }

    private NjMuleEngine engine(List<IpFilterDat.Range> ipFilter) throws IOException {
        AppDirectories dirs = AppDirectories.underRoot(this.dir.resolve("njmule"), true);
        Files.createDirectories(dirs.config());
        if (!ipFilter.isEmpty()) {
            Files.write(dirs.config().resolve("ipfilter.dat"), IpFilterDat.write(ipFilter));
        }
        int port;
        try (ServerSocket probe = new ServerSocket(0)) {
            port = probe.getLocalPort();
        }
        Files.writeString(dirs.config().resolve("njmule.properties"), "net.tcpPort=" + port + "\n");
        NjMuleEngine engine = NjMuleEngine.start(new EngineConfig(dirs));
        this.resources.add(engine);
        return engine;
    }

    private interface Condition {
        boolean holds() throws Exception;
    }

    private static void await(Condition condition, String what) throws Exception {
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (!condition.holds()) {
            assertThat(System.nanoTime()).as(what).isLessThan(deadline);
            Thread.sleep(50);
        }
    }

    // Random bytes with runs of zeros.
    private static byte[] content(int size, long seed) {
        byte[] data = new byte[size];
        new Random(seed).nextBytes(data);
        for (int at = 0; at < size; at += 400_000) {
            Arrays.fill(data, at, Math.min(size, at + 150_000), (byte) 0);
        }
        return data;
    }

    private static Hash16 hash(int seed) {
        byte[] bytes = new byte[16];
        new Random(seed).nextBytes(bytes);
        return Hash16.of(bytes, 0);
    }

    /** Collects the engine's events. */
    private static final class Events implements Flow.Subscriber<EngineEvent> {
        private final BlockingQueue<EngineEvent> queue = new LinkedBlockingQueue<>();

        Events(NjMuleEngine engine) {
            engine.events().subscribe(this);
        }

        EngineEvent.DownloadCompleted completed(Hash16 hash, Duration within) throws InterruptedException {
            long deadline = System.nanoTime() + within.toNanos();
            while (System.nanoTime() < deadline) {
                EngineEvent event = this.queue.poll(100, TimeUnit.MILLISECONDS);
                if (event instanceof EngineEvent.DownloadCompleted done
                        && done.download().hash().equals(hash)) {
                    return done;
                }
                if (event instanceof EngineEvent.DownloadFailed failed) {
                    throw new AssertionError("download failed: " + failed.reason());
                }
            }
            throw new AssertionError("no download completed within " + within);
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
