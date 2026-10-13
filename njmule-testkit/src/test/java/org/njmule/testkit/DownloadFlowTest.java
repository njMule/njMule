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
import java.net.InetSocketAddress;
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
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.core.api.DownloadInfo;
import org.njmule.core.api.Ed2kLink;
import org.njmule.core.api.EngineConfig;
import org.njmule.core.api.EngineEvent;
import org.njmule.core.api.Hash16;
import org.njmule.core.api.NjMuleEngine;
import org.njmule.core.api.ServerAddress;
import org.njmule.core.api.SharedFileInfo;
import org.njmule.core.config.AppDirectories;
import org.njmule.core.util.Ipv4;

@Timeout(120)
class DownloadFlowTest {

    private static final Duration WAIT = Duration.ofSeconds(90);
    private static final int PART = 9_728_000;

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
    void downloadsAFileSentInCompressedBlocks() throws Exception {
        byte[] data = content(2 * PART + 12_345, 1);
        FakePeer peer = this.peer(data, FakePeer.Config.simple(hash(1)));

        Path file = this.download(peer);

        assertThat(Files.readAllBytes(file)).isEqualTo(data);
        assertThat(file.getFileName().toString()).isEqualTo("shared.bin");
        assertThat(peer.received())
                .containsSubsequence("HELLO", "SETREQFILEID", "HASHSETREQUEST", "STARTUPLOADREQ")
                .contains("REQUESTPARTS");
        assertThat(Collections.frequency(peer.received(), "STARTUPLOADREQ")).isEqualTo(1);
    }

    @Test
    void aSourceDownloadsFromUsOverTheConnectionWeDownloadOn() throws Exception {
        Path incoming = Files.createDirectories(this.dir.resolve("njmule").resolve("incoming"));
        Files.write(incoming.resolve("ours.bin"), content(300_000, 9));
        byte[] data = content(PART / 2, 10);
        FakePeer peer = this.peer(data, FakePeer.Config.simple(hash(10)));
        NjMuleEngine engine = this.engine();
        peer.alsoAskFor(awaitShared(engine).hash());
        Events events = new Events(engine);

        DownloadInfo added = engine.addDownload(peer.link()).get(10, TimeUnit.SECONDS);
        events.completed(added.hash());

        // The peer asked for our file on our connection and got a slot there.
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (!peer.received().contains("ACCEPTUPLOADREQ")) {
            assertThat(System.nanoTime()).as("no slot for the peer").isLessThan(deadline);
            Thread.sleep(50);
        }
        assertThat(peer.received()).contains("FILESTATUS");
    }

    private static SharedFileInfo awaitShared(NjMuleEngine engine) throws Exception {
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (true) {
            List<SharedFileInfo> files = engine.sharedFiles().get(10, TimeUnit.SECONDS);
            if (!files.isEmpty()) {
                return files.getFirst();
            }
            assertThat(System.nanoTime()).as("nothing shared").isLessThan(deadline);
            Thread.sleep(50);
        }
    }

    @Test
    void waitsInTheQueueAndGetsASlotAgainAfterTheSessionEnds() throws Exception {
        byte[] data = content(PART + 500_000, 2);
        FakePeer peer =
                this.peer(data, new FakePeer.Config(hash(2), false, FakePeer.Slot.AFTER_QUEUE, 1_000_000, Set.of()));

        Path file = this.download(peer);

        assertThat(Files.readAllBytes(file)).isEqualTo(data);
        assertThat(Collections.frequency(peer.received(), "STARTUPLOADREQ")).isEqualTo(1);
    }

    @Test
    void downloadsACorruptPartAgain() throws Exception {
        byte[] data = content(2 * PART + 1000, 3);
        FakePeer peer = this.peer(data, new FakePeer.Config(hash(3), true, FakePeer.Slot.NOW, 0, Set.of(1)));

        Path file = this.download(peer);

        assertThat(Files.readAllBytes(file)).isEqualTo(data);
        assertThat(peer.bytesSent()).isGreaterThan(data.length);
    }

    @Test
    void getsTheSlotWhenTheSourceCallsBack() throws Exception {
        byte[] data = content(PART / 2, 4);
        FakePeer peer = this.peer(data, new FakePeer.Config(hash(4), true, FakePeer.Slot.CALL_BACK, 0, Set.of()));

        Path file = this.download(peer);

        assertThat(Files.readAllBytes(file)).isEqualTo(data);
        assertThat(peer.received()).containsSubsequence("STARTUPLOADREQ", "HELLOANSWER");
    }

    @Test
    void downloadsFromTheSourcesTheServerKnows() throws Exception {
        InetAddress lan = ServerFlowTest.lanAddress();
        byte[] data = content(1_000_000, 5);
        Path shared = Files.createDirectories(this.dir.resolve("shared")).resolve("served.bin");
        Files.write(shared, data);
        FakePeer peer = FakePeer.start(lan, shared, FakePeer.Config.simple(hash(5)));
        this.resources.add(peer);
        FakeServer server = FakeServer.start(
                lan, new FakeServer.Config(Ipv4.toEd2k(Ipv4.fromBytes(lan.getAddress())), "hi", List.of()));
        this.resources.add(server);
        server.addSource(peer.link().hash(), new InetSocketAddress(lan, peer.port()));
        NjMuleEngine engine = this.engine();
        Events events = new Events(engine);
        engine.connect(Optional.of(new ServerAddress(lan.getHostAddress(), server.port())))
                .get();

        Ed2kLink.FileLink bare =
                Ed2kLink.FileLink.of("served.bin", data.length, peer.link().hash());
        DownloadInfo added = engine.addDownload(bare).get(10, TimeUnit.SECONDS);
        assertThat(added.sources()).isZero();

        Path file = events.completed(added.hash()).file();
        assertThat(Files.readAllBytes(file)).isEqualTo(data);
    }

    @Test
    void aFirewalledSourceTheServerKnowsIsAskedToCallUs() throws Exception {
        InetAddress lan = ServerFlowTest.lanAddress();
        byte[] data = content(800_000, 6);
        Path shared = Files.createDirectories(this.dir.resolve("lowid")).resolve("firewalled.bin");
        Files.write(shared, data);
        FakePeer peer = FakePeer.start(lan, shared, FakePeer.Config.simple(hash(6)));
        this.resources.add(peer);
        FakeServer server = FakeServer.start(
                lan, new FakeServer.Config(Ipv4.toEd2k(Ipv4.fromBytes(lan.getAddress())), "hi", List.of()));
        this.resources.add(server);
        long lowId = 4242;
        server.addLowIdSource(peer.link().hash(), lowId, peer);
        NjMuleEngine engine = this.engine();
        Events events = new Events(engine);
        engine.connect(Optional.of(new ServerAddress(lan.getHostAddress(), server.port())))
                .get();

        DownloadInfo added = engine.addDownload(Ed2kLink.FileLink.of(
                        "firewalled.bin", data.length, peer.link().hash()))
                .get(10, TimeUnit.SECONDS);

        Path file = events.completed(added.hash()).file();
        assertThat(Files.readAllBytes(file)).isEqualTo(data);
        assertThat(server.callbackRequests()).containsExactly(lowId);
        assertThat(peer.received()).containsSubsequence("SETREQFILEID", "STARTUPLOADREQ");
    }

    @Test
    void pausesResumesAndCancelsDownloads() throws Exception {
        NjMuleEngine engine = this.engine();
        Events events = new Events(engine);
        int closed;
        try (ServerSocket probe = new ServerSocket(0)) {
            closed = probe.getLocalPort();
        }
        Ed2kLink.FileLink link = (Ed2kLink.FileLink) Ed2kLink.parse(
                "ed2k://|file|nowhere.bin|5000|" + "12".repeat(16) + "|/|sources,127.0.0.1:" + closed + "|/");
        DownloadInfo added = engine.addDownload(link).get(10, TimeUnit.SECONDS);
        assertThat(events.next(EngineEvent.DownloadAdded.class).download().name())
                .isEqualTo("nowhere.bin");
        assertThat(engine.sources().get(10, TimeUnit.SECONDS)).singleElement().satisfies(s -> {
            assertThat(s.file()).isEqualTo(added.hash());
            assertThat(s.fileName()).isEqualTo("nowhere.bin");
            assertThat(s.address()).isEqualTo("127.0.0.1:" + closed);
            assertThat(s.received()).isZero();
            assertThat(s.partCount()).isEqualTo(1);
        });

        assertThat(engine.pauseDownload(added.hash()).get()).isTrue();
        assertThat(engine.downloads().get().getFirst().status()).isEqualTo(DownloadInfo.Status.PAUSED);
        assertThat(engine.resumeDownload(added.hash()).get()).isTrue();
        assertThat(engine.downloads().get().getFirst().status()).isEqualTo(DownloadInfo.Status.DOWNLOADING);

        assertThat(engine.cancelDownload(added.hash()).get()).isTrue();
        assertThat(events.next(EngineEvent.DownloadRemoved.class).hash()).isEqualTo(added.hash());
        assertThat(engine.downloads().get()).isEmpty();
        assertThat(engine.pauseDownload(added.hash()).get()).isFalse();
        try (Stream<Path> left = Files.list(engine.directories().temp())) {
            assertThat(left).isEmpty();
        }
    }

    private Path download(FakePeer peer) throws Exception {
        NjMuleEngine engine = this.engine();
        Events events = new Events(engine);
        DownloadInfo added = engine.addDownload(peer.link()).get(10, TimeUnit.SECONDS);
        assertThat(added.status()).isEqualTo(DownloadInfo.Status.DOWNLOADING);
        assertThat(added.sources()).isEqualTo(1);

        EngineEvent.DownloadCompleted done = events.completed(added.hash());
        assertThat(done.download().completed()).isEqualTo(done.download().size());
        assertThat(engine.downloads().get(10, TimeUnit.SECONDS)).isEmpty();
        return done.file();
    }

    private FakePeer peer(byte[] data, FakePeer.Config config) throws IOException {
        Path shared = Files.createDirectories(this.dir.resolve("shared")).resolve("shared.bin");
        Files.write(shared, data);
        FakePeer peer = FakePeer.start(InetAddress.getLoopbackAddress(), shared, config);
        this.resources.add(peer);
        return peer;
    }

    private NjMuleEngine engine() throws IOException {
        AppDirectories dirs = AppDirectories.underRoot(this.dir.resolve("njmule"), true);
        Files.createDirectories(dirs.config());
        int port;
        try (ServerSocket probe = new ServerSocket(0)) {
            port = probe.getLocalPort();
        }
        Files.writeString(dirs.config().resolve("njmule.properties"), "net.tcpPort=" + port + "\n");
        NjMuleEngine engine = NjMuleEngine.start(new EngineConfig(dirs));
        this.resources.add(engine);
        return engine;
    }

    // Random bytes with runs of zeros, so compression has something to do.
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

        EngineEvent.DownloadCompleted completed(Hash16 hash) throws InterruptedException {
            long deadline = System.nanoTime() + WAIT.toNanos();
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
            throw new AssertionError("no download completed within " + WAIT);
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
