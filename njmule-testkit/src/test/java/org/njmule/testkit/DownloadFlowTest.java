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
import static org.assertj.core.api.Assertions.tuple;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;
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
import org.njmule.core.persist.format.ServerMet;
import org.njmule.core.protocol.ed2k.ServerDatagrams;
import org.njmule.core.protocol.tag.ServerTag;
import org.njmule.core.protocol.tag.Tag;
import org.njmule.core.protocol.tag.TagName;
import org.njmule.core.util.Ipv4;

@Timeout(120)
class DownloadFlowTest {

    private static final Duration WAIT = Duration.ofSeconds(90);
    private static final int PART = 9_728_000;

    @TempDir
    Path dir;

    private final List<AutoCloseable> resources = new ArrayList<>();
    private byte[] data;
    private FakePeer peer;
    private FakeServer server;
    private FakeServer other;
    private NjMuleEngine engine;
    private Events events;
    private DownloadInfo added;
    private EngineEvent.DownloadCompleted completion;
    private int closedPort;
    private boolean cancelled;
    private boolean clearedOne;
    private int cleared;

    @AfterEach
    void closeEverything() throws Exception {
        Collections.reverse(this.resources);
        for (AutoCloseable resource : this.resources) {
            resource.close();
        }
    }

    @Test
    void shouldDownloadAFileSentInCompressedBlocks() throws Exception {
        givenAPeerSharing(2 * PART + 12_345, 1);
        givenNjMuleRunning();

        whenTheLinkOfThePeerIsDownloaded();

        thenTheDownloadStartedDownloadingFromSources(1);
        thenTheDownloadIsComplete();
        thenTheFileHoldsTheData();
        thenTheFileIsNamed("shared.bin");
        thenThePeerReceivedInOrder("HELLO", "SETREQFILEID", "HASHSETREQUEST", "STARTUPLOADREQ");
        thenThePeerReceived("REQUESTPARTS");
        thenThePeerReceivedTimes("STARTUPLOADREQ", 1);
    }

    @Test
    void shouldKeepACompletedDownloadListedUntilTheCompletedAreCleared() throws Exception {
        givenAPeerSharing(12_345, 2);
        givenNjMuleRunning();
        givenTheLinkOfThePeerDownloaded();

        whenTheCompletedDownloadsAreCleared();

        thenTheClearedDownloadsAre(1);
        thenTheDownloadIsGoneFromTheList();
        thenTheFileHoldsTheData();
    }

    @Test
    void shouldTakeOneCompletedDownloadOffTheListAndKeepItsFile() throws Exception {
        givenAPeerSharing(12_345, 2);
        givenNjMuleRunning();
        givenTheLinkOfThePeerDownloaded();

        whenTheCompletedDownloadIsCleared();

        thenTheCompletedDownloadWasCleared();
        thenTheDownloadIsGoneFromTheList();
        thenTheFileHoldsTheData();
    }

    @Test
    void shouldLetASourceDownloadFromUsOverTheConnectionWeDownloadOn() throws Exception {
        givenOurIncomingFolderHolds("ours.bin", 300_000, 9);
        givenAPeerSharing(PART / 2, 10);
        givenNjMuleRunning();
        givenThePeerAlsoAsksForOurSharedFile();

        whenTheLinkOfThePeerIsDownloaded();

        thenThePeerSoonGetsASlot();
        thenThePeerReceived("FILESTATUS");
    }

    @Test
    void shouldWaitInTheQueueAndGetASlotAgainAfterTheSessionEnds() throws Exception {
        givenAPeerSharing(PART + 500_000, 2, false, FakePeer.Slot.AFTER_QUEUE, 1_000_000, Set.of());
        givenNjMuleRunning();

        whenTheLinkOfThePeerIsDownloaded();

        thenTheDownloadStartedDownloadingFromSources(1);
        thenTheDownloadIsComplete();
        thenTheFileHoldsTheData();
        thenThePeerReceivedTimes("STARTUPLOADREQ", 1);
    }

    @Test
    void shouldDownloadACorruptPartAgain() throws Exception {
        givenAPeerSharing(2 * PART + 1000, 3, true, FakePeer.Slot.NOW, 0, Set.of(1));
        givenNjMuleRunning();

        whenTheLinkOfThePeerIsDownloaded();

        thenTheDownloadStartedDownloadingFromSources(1);
        thenTheDownloadIsComplete();
        thenTheFileHoldsTheData();
        thenThePeerSentMoreThanTheFile();
    }

    @Test
    void shouldSaveTheGoodBlocksOfACorruptPartWithAichRecoveryData() throws Exception {
        givenAPeerSharing(3 * PART + 1000, 6, false, FakePeer.Slot.NOW, 0, Set.of(1));
        givenNjMuleRunning();

        whenTheLinkOfThePeerIsDownloaded();

        thenTheDownloadStartedDownloadingFromSources(1);
        thenTheDownloadIsComplete();
        thenTheFileHoldsTheData();
        thenThePeerReceived("AICHREQUEST");
        thenThePeerSentTheBadBlockAgainOnly();
    }

    @Test
    void shouldGetTheSlotWhenTheSourceCallsBack() throws Exception {
        givenAPeerSharing(PART / 2, 4, true, FakePeer.Slot.CALL_BACK, 0, Set.of());
        givenNjMuleRunning();

        whenTheLinkOfThePeerIsDownloaded();

        thenTheDownloadStartedDownloadingFromSources(1);
        thenTheDownloadIsComplete();
        thenTheFileHoldsTheData();
        thenThePeerReceivedInOrder("STARTUPLOADREQ", "HELLOANSWER");
    }

    @Test
    void shouldDownloadFromTheSourcesTheServerKnows() throws Exception {
        givenAPeerOnTheLanSharing("shared", "served.bin", 1_000_000, 5);
        givenAServerOnTheLan();
        givenTheServerKnowsThePeerAsSource();
        givenNjMuleRunning();
        givenNjMuleConnectedToTheServer();

        whenTheFileIsDownloadedWithoutSources("served.bin");

        thenTheDownloadStartedWithSources(0);
        thenTheFileHoldsTheData();
    }

    @Test
    void shouldDownloadFromTheSourcesAnotherServerKnows() throws Exception {
        givenAPeerOnTheLanSharing("elsewhere", "elsewhere.bin", 700_000, 7);
        givenAServerOnTheLan();
        givenAnotherServerOnTheLanKnowingThePeerAsSource();
        givenNjMuleRunning();
        givenNjMuleKnowsTheOtherServer();
        givenNjMuleConnectedToTheServer();

        whenTheFileIsDownloadedWithoutSources("elsewhere.bin");

        thenTheFileHoldsTheData();
        thenTheOtherServerWasAskedForTheFileOverUdp();
    }

    @Test
    void shouldAskAFirewalledSourceTheServerKnowsToCallUs() throws Exception {
        givenAPeerOnTheLanSharing("lowid", "firewalled.bin", 800_000, 6);
        givenAServerOnTheLan();
        givenTheServerKnowsThePeerAsLowIdSource(4242);
        givenNjMuleRunning();
        givenNjMuleConnectedToTheServer();

        whenTheFileIsDownloadedWithoutSources("firewalled.bin");

        thenTheFileHoldsTheData();
        thenTheServerWasAskedToHaveCalledBack(4242);
        thenThePeerReceivedInOrder("SETREQFILEID", "STARTUPLOADREQ");
    }

    @Test
    void shouldPauseResumeAndCancelADownload() throws Exception {
        givenNjMuleRunning();
        givenADownloadWithASourceOnAClosedPort("nowhere.bin");
        givenItsSourceIsListed("nowhere.bin");
        givenTheDownloadPaused();
        givenTheDownloadResumed();

        whenTheDownloadIsCancelled();

        thenTheDownloadIsRemoved();
        thenTheDownloadIsRememberedAsCancelled();
        thenTheDownloadCanNoLongerBePaused();
        thenNoTemporaryFileIsLeftOnceTheCancelCanNoLongerBeUndone();
    }

    private void givenAPeerSharing(int size, int seed) throws IOException {
        startPeer(
                InetAddress.getLoopbackAddress(),
                "shared",
                "shared.bin",
                size,
                seed,
                FakePeer.Config.simple(hash(seed)));
    }

    private void givenAPeerSharing(
            int size, int seed, boolean compress, FakePeer.Slot slot, long sessionLimit, Set<Integer> corruptParts)
            throws IOException {
        startPeer(
                InetAddress.getLoopbackAddress(),
                "shared",
                "shared.bin",
                size,
                seed,
                new FakePeer.Config(hash(seed), compress, slot, sessionLimit, corruptParts));
    }

    private void givenAPeerOnTheLanSharing(String folder, String name, int size, int seed) throws IOException {
        startPeer(ServerFlowTest.lanAddress(), folder, name, size, seed, FakePeer.Config.simple(hash(seed)));
    }

    private void givenOurIncomingFolderHolds(String name, int size, long seed) throws IOException {
        Path incoming = Files.createDirectories(this.dir.resolve("njmule").resolve("incoming"));
        Files.write(incoming.resolve(name), content(size, seed));
    }

    private void givenNjMuleRunning() throws IOException {
        AppDirectories dirs = AppDirectories.underRoot(this.dir.resolve("njmule"), true);
        Files.createDirectories(dirs.config());
        int port = FreePorts.tcp();
        // The fake servers and peers listen on this machine's LAN address, which must be let in.
        Files.writeString(
                dirs.config().resolve("njmule.properties"), "net.tcpPort=" + port + "\nsecurity.filterLan=false\n");
        this.engine = NjMuleEngine.start(new EngineConfig(dirs));
        this.resources.add(this.engine);
        this.events = new Events(this.engine);
    }

    private void givenThePeerAlsoAsksForOurSharedFile() throws Exception {
        this.peer.alsoAskFor(awaitShared(this.engine).hash());
    }

    private void givenAServerOnTheLan() throws IOException {
        InetAddress lan = ServerFlowTest.lanAddress();
        this.server = FakeServer.start(
                lan, new FakeServer.Config(Ipv4.toEd2k(Ipv4.fromBytes(lan.getAddress())), "hi", List.of()));
        this.resources.add(this.server);
    }

    private void givenTheServerKnowsThePeerAsSource() throws SocketException {
        this.server.addSource(
                this.peer.link().hash(), new InetSocketAddress(ServerFlowTest.lanAddress(), this.peer.port()));
    }

    private void givenAnotherServerOnTheLanKnowingThePeerAsSource() throws IOException {
        InetAddress lan = ServerFlowTest.lanAddress();
        this.other = FakeServer.start(lan, new FakeServer.Config(1, "", List.of()));
        this.resources.add(this.other);
        this.other.addSource(this.peer.link().hash(), new InetSocketAddress(lan, this.peer.port()));
    }

    private void givenNjMuleKnowsTheOtherServer() throws Exception {
        Tag flags = new Tag.IntTag(
                TagName.id(ServerTag.UDPFLAGS),
                ServerDatagrams.UDP_EXT_GETSOURCES | ServerDatagrams.UDP_EXT_GETSOURCES2);
        Path serverMet = Files.createTempFile(this.dir, "other", ".met");
        Files.write(
                serverMet,
                ServerMet.write(List.of(new ServerMet.Server(
                        Ipv4.fromBytes(ServerFlowTest.lanAddress().getAddress()), this.other.port(), List.of(flags)))));
        assertThat(this.engine.addServers(serverMet).get(10, TimeUnit.SECONDS)).isEqualTo(1);
    }

    private void givenTheServerKnowsThePeerAsLowIdSource(long lowId) {
        this.server.addLowIdSource(this.peer.link().hash(), lowId, this.peer);
    }

    private void givenNjMuleConnectedToTheServer() throws Exception {
        this.engine
                .connect(Optional.of(
                        new ServerAddress(ServerFlowTest.lanAddress().getHostAddress(), this.server.port())))
                .get();
    }

    private void givenADownloadWithASourceOnAClosedPort(String name) throws Exception {
        this.closedPort = FreePorts.tcp();
        Ed2kLink.FileLink link = (Ed2kLink.FileLink) Ed2kLink.parse(
                "ed2k://|file|" + name + "|5000|" + "12".repeat(16) + "|/|sources,127.0.0.1:" + this.closedPort + "|/");
        this.added = this.engine.addDownload(link).get(10, TimeUnit.SECONDS);
        assertThat(this.events.next(EngineEvent.DownloadAdded.class).download().name())
                .isEqualTo(name);
    }

    private void givenItsSourceIsListed(String name) throws Exception {
        assertThat(this.engine.sources().get(10, TimeUnit.SECONDS))
                .singleElement()
                .satisfies(s -> {
                    assertThat(s.file()).isEqualTo(this.added.hash());
                    assertThat(s.fileName()).isEqualTo(name);
                    assertThat(s.address()).isEqualTo("127.0.0.1:" + this.closedPort);
                    assertThat(s.received()).isZero();
                    assertThat(s.partCount()).isEqualTo(1);
                });
    }

    private void givenTheDownloadPaused() throws Exception {
        assertThat(this.engine.pauseDownload(this.added.hash()).get()).isTrue();
        assertThat(this.engine.downloads().get().getFirst().status()).isEqualTo(DownloadInfo.Status.PAUSED);
    }

    private void givenTheDownloadResumed() throws Exception {
        assertThat(this.engine.resumeDownload(this.added.hash()).get()).isTrue();
        assertThat(this.engine.downloads().get().getFirst().status()).isEqualTo(DownloadInfo.Status.DOWNLOADING);
    }

    private void givenTheLinkOfThePeerDownloaded() throws Exception {
        whenTheLinkOfThePeerIsDownloaded();
    }

    private void whenTheCompletedDownloadsAreCleared() throws Exception {
        this.cleared = this.engine.clearCompletedDownloads().get(10, TimeUnit.SECONDS);
    }

    private void whenTheCompletedDownloadIsCleared() throws Exception {
        this.clearedOne = this.engine.clearCompletedDownload(this.added.hash()).get(10, TimeUnit.SECONDS);
    }

    private void whenTheLinkOfThePeerIsDownloaded() throws Exception {
        download(this.peer.link());
    }

    private void whenTheFileIsDownloadedWithoutSources(String name) throws Exception {
        download(Ed2kLink.FileLink.of(name, this.data.length, this.peer.link().hash()));
    }

    private void whenTheDownloadIsCancelled() throws Exception {
        this.cancelled = this.engine.cancelDownload(this.added.hash()).get();
    }

    private void thenTheDownloadStartedDownloadingFromSources(int sources) {
        assertThat(this.added.status()).isEqualTo(DownloadInfo.Status.DOWNLOADING);
        assertThat(this.added.sources()).isEqualTo(sources);
    }

    private void thenTheDownloadStartedWithSources(int sources) {
        assertThat(this.added.sources()).isEqualTo(sources);
    }

    // It stays listed as complete until the completed downloads are cleared.
    private void thenTheDownloadIsComplete() throws Exception {
        assertThat(this.completion.download().completed())
                .isEqualTo(this.completion.download().size());
        assertThat(this.engine.downloads().get(10, TimeUnit.SECONDS))
                .extracting(DownloadInfo::hash, DownloadInfo::status)
                .containsExactly(tuple(this.added.hash(), DownloadInfo.Status.COMPLETE));
    }

    private void thenTheCompletedDownloadWasCleared() {
        assertThat(this.clearedOne).isTrue();
    }

    private void thenTheClearedDownloadsAre(int count) {
        assertThat(this.cleared).isEqualTo(count);
    }

    private void thenTheDownloadIsGoneFromTheList() throws Exception {
        assertThat(this.events.next(EngineEvent.DownloadRemoved.class).hash()).isEqualTo(this.added.hash());
        assertThat(this.engine.downloads().get(10, TimeUnit.SECONDS)).isEmpty();
    }

    private void thenTheFileHoldsTheData() throws IOException {
        assertThat(Files.readAllBytes(this.completion.file())).isEqualTo(this.data);
    }

    private void thenTheFileIsNamed(String name) {
        assertThat(this.completion.file().getFileName().toString()).isEqualTo(name);
    }

    private void thenThePeerReceived(String message) {
        assertThat(this.peer.received()).contains(message);
    }

    private void thenThePeerReceivedInOrder(String... messages) {
        assertThat(this.peer.received()).containsSubsequence(messages);
    }

    private void thenThePeerReceivedTimes(String message, int times) {
        assertThat(Collections.frequency(this.peer.received(), message)).isEqualTo(times);
    }

    private void thenThePeerSentMoreThanTheFile() {
        assertThat(this.peer.bytesSent()).isGreaterThan(this.data.length);
    }

    // The part waits for the recovery data: only the bad block comes again.
    private void thenThePeerSentTheBadBlockAgainOnly() {
        assertThat(this.peer.bytesSent()).as("one bad block costs one block").isLessThan(this.data.length + PART / 10);
    }

    // The peer asked for our file on our connection and got a slot there.
    private void thenThePeerSoonGetsASlot() throws InterruptedException {
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (!this.peer.received().contains("ACCEPTUPLOADREQ")) {
            assertThat(System.nanoTime()).as("no slot for the peer").isLessThan(deadline);
            Thread.sleep(50);
        }
    }

    private void thenTheOtherServerWasAskedForTheFileOverUdp() {
        assertThat(this.other.udpSourceRequests()).contains(this.peer.link().hash());
    }

    private void thenTheServerWasAskedToHaveCalledBack(long lowId) {
        assertThat(this.server.callbackRequests()).containsExactly(lowId);
    }

    private void thenTheDownloadIsRemoved() throws Exception {
        assertThat(this.cancelled).isTrue();
        assertThat(this.events.next(EngineEvent.DownloadRemoved.class).hash()).isEqualTo(this.added.hash());
        assertThat(this.engine.downloads().get()).isEmpty();
    }

    private void thenTheDownloadIsRememberedAsCancelled() throws Exception {
        assertThat(this.engine.fileHistory().get().cancelled(this.added.hash()))
                .as("remembered as cancelled")
                .isTrue();
    }

    private void thenTheDownloadCanNoLongerBePaused() throws Exception {
        assertThat(this.engine.pauseDownload(this.added.hash()).get()).isFalse();
    }

    private void thenNoTemporaryFileIsLeftOnceTheCancelCanNoLongerBeUndone() throws Exception {
        long deadline = System.nanoTime()
                + NjMuleEngine.CANCEL_UNDO_TIME.plusSeconds(15).toNanos();
        while (System.nanoTime() < deadline && !temporaryFiles().isEmpty()) {
            Thread.sleep(100);
        }
        assertThat(temporaryFiles()).isEmpty();
    }

    private List<Path> temporaryFiles() throws IOException {
        try (Stream<Path> left = Files.list(this.engine.directories().temp())) {
            return left.toList();
        }
    }

    private void download(Ed2kLink.FileLink link) throws Exception {
        this.added = this.engine.addDownload(link).get(10, TimeUnit.SECONDS);
        this.completion = this.events.completed(this.added.hash());
    }

    private void startPeer(InetAddress address, String folder, String name, int size, long seed, FakePeer.Config config)
            throws IOException {
        this.data = content(size, seed);
        Path shared = Files.createDirectories(this.dir.resolve(folder)).resolve(name);
        Files.write(shared, this.data);
        this.peer = FakePeer.start(address, shared, config);
        this.resources.add(this.peer);
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
