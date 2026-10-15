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
    private byte[] data;
    private Path shared;
    private FakePeer source;
    private FakePeer second;
    private NjMuleEngine engine;
    private Events events;
    private DownloadInfo added;

    @AfterEach
    void closeEverything() throws Exception {
        Collections.reverse(this.resources);
        for (AutoCloseable resource : this.resources) {
            resource.close();
        }
    }

    @Test
    void shouldLetASourceWithASlotTakeTheBlocksOfOneThatLeft() throws Exception {
        givenTheFileShared(5 * BLOCK - 1000, 21);
        givenASourceThatStalls(21);
        givenNjMuleRunning();
        givenTheDownloadOfTheSourceAdded();
        givenTheSourceWasAskedForBlocks();
        givenASecondSourceAdded(22);
        givenTheSecondSourceSentTheBlocksLeft();

        whenTheFirstSourceDropsItsDownloaders();

        thenTheDownloadCompletesWithTheDataWithin(SOON);
    }

    @Test
    void shouldLetASourceWithASlotTakeTheBlocksOfOneTheIpFilterNowRefuses() throws Exception {
        givenTheFileShared(5 * BLOCK - 1000, 28);
        givenASourceThatStallsAt("127.0.0.2", 28);
        givenNjMuleRunningWithTheIpFilterRange("127.0.0.2", 150);
        givenTheDownloadOfTheSourceAdded();
        givenTheSourceWasAskedForBlocks();
        givenASecondSourceAdded(29);
        givenTheSecondSourceSentTheBlocksLeft();

        whenTheIpFilterLevelIsRaisedTo(200);

        thenTheDownloadCompletesWithTheDataWithin(SOON);
    }

    @Test
    void shouldDownloadAPartWithoutRecoveryDataAgainAtOnce() throws Exception {
        givenTheFileShared(2 * PART + 1000, 23);
        givenASourceSendingCorruptParts(23, Set.of(1));
        givenNjMuleRunning();

        whenTheFileIsDownloadedWithAMasterHashTheSourceDoesNotKnow();

        thenTheDownloadCompletesWithTheDataWithin(SOON);
        thenTheSourceReceived("AICHREQUEST");
    }

    @Test
    void shouldAskAtOnceForTheRestOfABlockWhoseCompressedStreamEndsEarly() throws Exception {
        givenTheFileShared(3 * BLOCK, 26);
        givenASourceCuttingTheNextBlockShort(26);
        givenNjMuleRunning();

        whenTheFileOfTheSourceIsDownloaded();

        thenTheDownloadCompletesWithTheDataWithin(SOON);
    }

    @Test
    void shouldRecoverTheCorruptBlockOfAFileOfOnePart() throws Exception {
        givenTheFileShared(PART - 1000, 27);
        givenASourceSendingCorruptParts(27, Set.of(0));
        givenNjMuleRunning();

        whenTheFileOfTheSourceIsDownloaded();

        thenTheDownloadCompletesWithTheDataWithin(WAIT);
        thenTheSourceReceived("AICHREQUEST");
        thenTheSourceSentTheBadBlockAgainOnly();
    }

    @Test
    void shouldRecoverTheCorruptPartsOfAFileDownloadedAgain() throws Exception {
        givenTheFileShared(3 * PART + 1000, 24);
        givenNjMuleRunning();
        givenASourceSendingCorruptParts(24, Set.of(1));
        givenTheFileOfTheSourceDownloadedWithin(WAIT);
        givenTheSourceReceived("AICHREQUEST");
        givenASourceSendingCorruptParts(25, Set.of(1));

        whenTheFileOfTheSourceIsDownloaded();

        thenTheDownloadCompletesWithTheDataWithin(WAIT);
        thenTheSourceReceived("AICHREQUEST", "the part tried before is recovered again");
    }

    private void givenTheFileShared(int size, long seed) throws IOException {
        this.data = content(size, seed);
        this.shared = Files.createDirectories(this.dir.resolve("shared")).resolve("shared.bin");
        Files.write(this.shared, this.data);
    }

    private void givenASourceThatStalls(int seed) throws IOException {
        this.source = peer(InetAddress.getLoopbackAddress(), config(seed, Set.of()));
        this.source.stall();
    }

    private void givenASourceThatStallsAt(String address, int seed) throws IOException {
        this.source = peer(InetAddress.getByName(address), config(seed, Set.of()));
        this.source.stall();
    }

    private void givenASourceSendingCorruptParts(int seed, Set<Integer> parts) throws IOException {
        this.source = peer(InetAddress.getLoopbackAddress(), config(seed, parts));
    }

    private void givenASourceCuttingTheNextBlockShort(int seed) throws IOException {
        this.source = peer(InetAddress.getLoopbackAddress(), FakePeer.Config.simple(hash(seed)));
        this.source.truncateNextBlock();
    }

    private void givenNjMuleRunning() throws IOException {
        startNjMule(List.of());
    }

    // The range blocks only once the filter level is raised above its own.
    private void givenNjMuleRunningWithTheIpFilterRange(String address, int level) throws IOException {
        int ip = Ipv4.parse(address);
        startNjMule(List.of(new IpFilterDat.Range(ip, ip, level, "test")));
    }

    private void givenTheDownloadOfTheSourceAdded() throws Exception {
        this.added = this.engine.addDownload(this.source.link()).get(10, TimeUnit.SECONDS);
    }

    private void givenTheSourceWasAskedForBlocks() throws Exception {
        await(() -> this.source.received().contains("REQUESTPARTS"), "the first source was asked for blocks");
    }

    // The second source gets the two blocks left, then has nothing to ask for.
    private void givenASecondSourceAdded(int seed) throws Exception {
        this.second = peer(InetAddress.getLoopbackAddress(), config(seed, Set.of()));
        assertThat(this.engine
                        .addSource(this.added.hash(), new ServerAddress("127.0.0.1", this.second.port()))
                        .get(10, TimeUnit.SECONDS))
                .isTrue();
    }

    private void givenTheSecondSourceSentTheBlocksLeft() throws Exception {
        await(() -> this.second.bytesSent() == this.data.length - 3L * BLOCK, "the second source sent the blocks left");
        Thread.sleep(500);
    }

    private void givenTheFileOfTheSourceDownloadedWithin(Duration within) throws Exception {
        whenTheFileOfTheSourceIsDownloaded();
        this.events.completed(this.added.hash(), within);
    }

    private void givenTheSourceReceived(String message) {
        assertThat(this.source.received()).contains(message);
    }

    private void whenTheFirstSourceDropsItsDownloaders() throws IOException {
        this.source.dropDownloaders();
    }

    private void whenTheIpFilterLevelIsRaisedTo(int level) throws Exception {
        this.engine
                .updatePreferences(p -> p.toBuilder().ipFilterLevel(level).build())
                .get(10, TimeUnit.SECONDS);
    }

    private void whenTheFileOfTheSourceIsDownloaded() throws Exception {
        this.added = this.engine.addDownload(this.source.link()).get(10, TimeUnit.SECONDS);
    }

    // A master hash the source does not know: it answers that it has no recovery data.
    private void whenTheFileIsDownloadedWithAMasterHashTheSourceDoesNotKnow() throws Exception {
        Ed2kLink.FileLink link = this.source.link();
        Ed2kLink.FileLink unknownAich = new Ed2kLink.FileLink(
                link.name(),
                link.size(),
                link.hash(),
                Optional.of(Hash20.parseBase32("Q".repeat(32))),
                link.partHashes(),
                link.sources(),
                link.httpSources());
        this.added = this.engine.addDownload(unknownAich).get(10, TimeUnit.SECONDS);
    }

    private void thenTheDownloadCompletesWithTheDataWithin(Duration within) throws Exception {
        Path file = this.events.completed(this.added.hash(), within).file();
        assertThat(Files.readAllBytes(file)).isEqualTo(this.data);
    }

    private void thenTheSourceReceived(String message) {
        assertThat(this.source.received()).contains(message);
    }

    private void thenTheSourceReceived(String message, String why) {
        assertThat(this.source.received()).as(why).contains(message);
    }

    private void thenTheSourceSentTheBadBlockAgainOnly() {
        assertThat(this.source.bytesSent())
                .as("only the bad block comes again")
                .isLessThan(this.data.length + PART / 10);
    }

    private FakePeer.Config config(int seed, Set<Integer> corruptParts) {
        return new FakePeer.Config(hash(seed), false, FakePeer.Slot.NOW, 0, corruptParts);
    }

    private FakePeer peer(InetAddress address, FakePeer.Config config) throws IOException {
        FakePeer peer = FakePeer.start(address, this.shared, config);
        this.resources.add(peer);
        return peer;
    }

    private void startNjMule(List<IpFilterDat.Range> ipFilter) throws IOException {
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
        this.engine = NjMuleEngine.start(new EngineConfig(dirs));
        this.resources.add(this.engine);
        this.events = new Events(this.engine);
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
