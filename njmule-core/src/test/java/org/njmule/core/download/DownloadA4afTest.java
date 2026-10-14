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
package org.njmule.core.download;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.common.base.Ticker;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.core.api.DownloadInfo;
import org.njmule.core.api.DownloadPriority;
import org.njmule.core.api.Hash16;
import org.njmule.core.api.SourceInfo;
import org.njmule.core.config.ObfuscationMode;
import org.njmule.core.engine.EngineLoop;
import org.njmule.core.files.PartFile;
import org.njmule.core.peer.Identities;
import org.njmule.core.peer.IdentityHandshake;
import org.njmule.core.peer.PeerLinks;
import org.njmule.core.protocol.ed2k.ClientMessages;
import org.njmule.core.protocol.ed2k.Hello;
import org.njmule.core.protocol.io.PacketWriter;
import org.njmule.core.protocol.packet.Opcodes;
import org.njmule.core.protocol.packet.Packet;
import org.njmule.core.protocol.packet.Protocol;
import org.njmule.core.util.Ipv4;

/** A client that is a source of two downloads, asked for one at a time; the other lists it as A4AF. */
@Timeout(90)
class DownloadA4afTest {

    private static final int PORT = 4662;
    private static final int OUR_IP = Ipv4.of(198, 51, 100, 7);
    private static final Hash16 PEER = Hash16.parseHex("5d".repeat(16));
    private static final Hash16 FIRST = Hash16.parseHex("61".repeat(16));
    private static final Hash16 SECOND = Hash16.parseHex("62".repeat(16));
    private static final Hash16 UNKNOWN = Hash16.parseHex("63".repeat(16));
    private static final String FIRST_NAME = "first.bin";
    private static final String SECOND_NAME = "second.bin";
    private static final int SIZE = 5000;
    private static final Duration WAIT = ScriptedSource.WAIT;
    // Long enough for a few ticks of the downloads.
    private static final Duration QUIET = Duration.ofSeconds(3);

    @TempDir
    Path dir;

    private final EngineLoop loop = EngineLoop.start(1024, "test-engine");
    private final AtomicLong now = new AtomicLong(1_000_000_000L);
    private final Ticker ticker = new Ticker() {
        @Override
        public long read() {
            return DownloadA4afTest.this.now.get();
        }
    };
    private final List<AutoCloseable> resources = new ArrayList<>();
    private DownloadManager manager;
    private ScriptedSource client;
    private ScriptedSource.Link link;
    private Hash16 askedFor;
    private Packet lastUploadRequest;
    private int swapped;
    private boolean done;
    private List<Object> answers;

    @AfterEach
    void stop() throws Exception {
        if (this.manager != null) {
            this.onLoop(() -> {
                this.manager.close();
                return null;
            });
        }
        this.loop.close();
        Collections.reverse(this.resources);
        for (AutoCloseable resource : this.resources) {
            resource.close();
        }
    }

    @Test
    void shouldAskAClientThatIsASourceOfTwoDownloadsForOneAtATime() throws Exception {
        this.givenAClientThatIsASourceOfTwoDownloads(DownloadPriority.NORMAL, DownloadPriority.NORMAL);

        this.whenTheClientIsAsked();

        this.thenItIsAskedFor(FIRST);
        this.thenTheSourceIsA4afFor(SECOND, FIRST, FIRST_NAME);
        this.thenTheSourceIs(FIRST, SourceInfo.State.ASKING);
    }

    @Test
    void shouldAskForTheDownloadOfHigherPriorityFirst() throws Exception {
        this.givenAClientThatIsASourceOfTwoDownloads(DownloadPriority.NORMAL, DownloadPriority.HIGH);

        this.whenTheClientIsAsked();

        this.thenItIsAskedFor(SECOND);
        this.thenTheSourceIsA4afFor(FIRST, SECOND, SECOND_NAME);
    }

    @Test
    void shouldAskForAnotherDownloadOnTheSameConnectionWhenTheClientHasNoPartWeNeed() throws Exception {
        this.givenAClientThatIsASourceOfTwoDownloads(DownloadPriority.NORMAL, DownloadPriority.NORMAL);
        this.givenTheClientIsAskedFor(FIRST);

        this.whenTheClientHasNoPartWeNeedOf(FIRST);

        this.thenItIsAskedOnTheSameConnectionFor(SECOND);
        this.thenTheClientQueuesUsFor(SECOND, 2);
        this.thenTheSourceIsA4afFor(FIRST, SECOND, SECOND_NAME);
    }

    @Test
    void shouldForgetTheSourceOfADownloadItLacksAndAskForAnotherOnTheSameConnection() throws Exception {
        this.givenAClientThatIsASourceOfTwoDownloads(DownloadPriority.NORMAL, DownloadPriority.NORMAL);
        this.givenTheClientIsAskedFor(FIRST);

        this.whenTheClientLacks(FIRST);

        this.thenItIsAskedOnTheSameConnectionFor(SECOND);
        this.thenTheDownloadHasSources(FIRST, 0);
        this.thenTheDownloadHasSources(SECOND, 1);
    }

    @Test
    void shouldAskAgainForTheDownloadWhereTheClientKeepsUsInAGoodPlace() throws Exception {
        this.givenAClientThatIsASourceOfTwoDownloads(DownloadPriority.NORMAL, DownloadPriority.NORMAL);
        this.givenTheClientIsAskedFor(FIRST);
        this.givenTheClientQueuesUsAt(3);
        this.givenTheConnectionCloses();
        this.givenTheDownloadHasPriority(SECOND, DownloadPriority.HIGH);

        this.whenTheClientIsAskedAgainAtItsTime();

        this.thenItIsAskedFor(FIRST);
        this.thenTheSourceIsA4afFor(SECOND, FIRST, FIRST_NAME);
    }

    @Test
    void shouldAskForABetterDownloadWhenTheClientKeepsUsInNoPlace() throws Exception {
        this.givenAClientThatIsASourceOfTwoDownloads(DownloadPriority.NORMAL, DownloadPriority.NORMAL);
        this.givenTheClientIsAskedFor(FIRST);
        this.givenTheClientIsSilentAfterTheUploadRequest();
        this.givenTheConnectionCloses();
        this.givenTheDownloadHasPriority(SECOND, DownloadPriority.HIGH);

        this.whenTheClientIsAskedAgainAtItsTime();

        this.thenItIsAskedFor(SECOND);
    }

    @Test
    void shouldAskForAnotherDownloadOnceTheOneAskedForIsPaused() throws Exception {
        this.givenAClientThatIsASourceOfTwoDownloads(DownloadPriority.NORMAL, DownloadPriority.NORMAL);
        this.givenTheClientIsAskedFor(FIRST);
        this.givenTheClientQueuesUsAt(3);

        this.whenTheDownloadIsPaused(FIRST);

        this.thenTheConnectionCloses();
        this.thenTheSourceIsA4afFor(FIRST, SECOND, SECOND_NAME);
        this.thenTheSourceIs(SECOND, SourceInfo.State.WAITING);
        this.thenTheClientIsAskedAgainOnceItAllowsFor(SECOND);
    }

    @Test
    void shouldWaitAMinuteMoreThanSourcesAllowBeforeAskingForAnotherDownload() throws Exception {
        this.givenAClientThatIsASourceOfTwoDownloads(DownloadPriority.NORMAL, DownloadPriority.NORMAL);
        this.givenTheClientIsAskedFor(FIRST);
        this.givenTheClientQueuesUsAt(3);

        this.whenTheDownloadIsPaused(FIRST);

        this.thenTheConnectionCloses();
        this.thenTheClientIsNotAskedAgainWithin(DownloadManager.MIN_REASK);
        this.thenTheClientIsAskedAgainOnceItAllowsFor(SECOND);
    }

    @Test
    void shouldAskForAnotherDownloadOnceTheOneAskedForIsCancelled() throws Exception {
        this.givenAClientThatIsASourceOfTwoDownloads(DownloadPriority.NORMAL, DownloadPriority.NORMAL);
        this.givenTheClientIsAskedFor(FIRST);
        this.givenTheClientQueuesUsAt(3);

        this.whenTheDownloadIsCancelled(FIRST);

        this.thenTheConnectionCloses();
        this.thenTheSourceIs(SECOND, SourceInfo.State.WAITING);
        this.thenTheClientIsAskedAgainOnceItAllowsFor(SECOND);
    }

    @Test
    void shouldAskTheSourcesSwappedByHandForTheirNewDownloadDespiteAGoodPlace() throws Exception {
        this.givenAClientThatIsASourceOfTwoDownloads(DownloadPriority.NORMAL, DownloadPriority.NORMAL);
        this.givenTheClientIsAskedFor(FIRST);
        this.givenTheClientQueuesUsAt(3);
        this.givenTheConnectionCloses();

        this.whenTheA4afSourcesAreSwappedTo(SECOND);

        this.thenSwappedSources(1);
        this.thenTheSourceIsA4afFor(FIRST, SECOND, SECOND_NAME);
        this.thenTheClientIsAskedAgainOnceItAllowsFor(SECOND);
    }

    @Test
    void shouldSwapOneSourceWaitingInTheQueueByHand() throws Exception {
        this.givenAClientThatIsASourceOfTwoDownloads(DownloadPriority.NORMAL, DownloadPriority.NORMAL);
        this.givenTheClientIsAskedFor(FIRST);
        this.givenTheClientQueuesUsAt(3);

        this.whenTheSourceIsSwappedTo(SECOND);

        this.thenTheSwapIsDone(true);
        this.thenTheConnectionCloses();
        this.thenTheSourceIsA4afFor(FIRST, SECOND, SECOND_NAME);
        this.thenTheSourceCannotBeSwappedAgainTo(SECOND);
    }

    @Test
    void shouldSwapTheSourcesOfADownloadAwayToOtherDownloads() throws Exception {
        this.givenAClientThatIsASourceOfTwoDownloads(DownloadPriority.NORMAL, DownloadPriority.NORMAL);
        this.givenTheClientIsAskedFor(FIRST);
        this.givenTheClientQueuesUsAt(3);
        this.givenTheConnectionCloses();

        this.whenTheSourcesAreSwappedAwayFrom(FIRST);

        this.thenSwappedSources(1);
        this.thenTheSourceIsA4afFor(FIRST, SECOND, SECOND_NAME);
        this.thenTheClientIsAskedAgainOnceItAllowsFor(SECOND);
    }

    @Test
    void shouldTakeTheFreeA4afSourcesWhenADownloadTakesThemByItself() throws Exception {
        this.givenAClientThatIsASourceOfTwoDownloads(DownloadPriority.NORMAL, DownloadPriority.NORMAL);
        this.givenTheClientIsAskedFor(FIRST);
        this.givenTheClientIsSilentAfterTheUploadRequest();
        this.givenTheConnectionCloses();

        this.whenTheDownloadTakesA4afSourcesByItself(SECOND);

        this.thenTheSourceIsA4afFor(FIRST, SECOND, SECOND_NAME);
        this.thenTheDownloadTakesA4afSourcesByItself(SECOND, true);
        this.thenTheClientIsAskedAgainAtItsTimeFor(SECOND);
    }

    @Test
    void shouldLeaveAClientThatKeepsUsInAGoodPlaceWhenADownloadTakesA4afSourcesByItself() throws Exception {
        this.givenAClientThatIsASourceOfTwoDownloads(DownloadPriority.NORMAL, DownloadPriority.NORMAL);
        this.givenTheClientIsAskedFor(FIRST);
        this.givenTheClientQueuesUsAt(3);
        this.givenTheConnectionCloses();

        this.whenTheDownloadTakesA4afSourcesByItself(SECOND);

        this.thenTheSourceIs(FIRST, SourceInfo.State.WAITING);
        this.thenTheSourceIsA4afFor(SECOND, FIRST, FIRST_NAME);
    }

    @Test
    void shouldRememberThatADownloadTakesA4afSourcesByItself() throws Exception {
        this.givenAClientThatIsASourceOfTwoDownloads(DownloadPriority.NORMAL, DownloadPriority.NORMAL);
        this.givenTheDownloadTakesA4afSourcesByItself(SECOND);

        this.whenTheDownloadsAreLoadedAgain();

        this.thenTheDownloadTakesA4afSourcesByItself(SECOND, true);
        this.thenTheDownloadTakesA4afSourcesByItself(FIRST, false);
    }

    @Test
    void shouldChangeNothingForAnUnknownDownload() throws Exception {
        this.givenAClientThatIsASourceOfTwoDownloads(DownloadPriority.NORMAL, DownloadPriority.NORMAL);

        this.whenEverySwapIsAskedFor(UNKNOWN);

        this.thenEveryAnswerIs(0, 0, false, false);
    }

    private void givenAClientThatIsASourceOfTwoDownloads(DownloadPriority first, DownloadPriority second)
            throws Exception {
        this.client = new ScriptedSource(PEER, 0, 0);
        this.resources.add(this.client);
        this.manager = this.open();
        // In one go, so that no ask comes between.
        this.onLoop(() -> {
            this.manager.add(this.client.link(FIRST_NAME, SIZE, FIRST));
            this.manager.add(this.client.link(SECOND_NAME, SIZE, SECOND));
            this.manager.setPriority(FIRST, first);
            return this.manager.setPriority(SECOND, second);
        });
    }

    private void givenTheClientIsAskedFor(Hash16 file) throws Exception {
        this.whenTheClientIsAsked();
        this.thenItIsAskedFor(file);
    }

    private void givenTheClientQueuesUsAt(int rank) throws Exception {
        this.link.send(new ClientMessages.FileStatus(this.askedFor, 0, new BitSet()).encode());
        this.lastUploadRequest = this.link.until(Opcodes.Client.STARTUPLOADREQ).getLast();
        this.link.send(Packet.of(
                Protocol.EMULE,
                Opcodes.Extended.QUEUERANKING.code(),
                new PacketWriter().u16(rank).bytes(new byte[10])));
        this.awaitSource(this.askedFor, s -> s.state() == SourceInfo.State.QUEUED && s.queueRank() == rank);
    }

    private void givenTheClientIsSilentAfterTheUploadRequest() throws Exception {
        this.link.send(new ClientMessages.FileStatus(this.askedFor, 0, new BitSet()).encode());
        this.lastUploadRequest = this.link.until(Opcodes.Client.STARTUPLOADREQ).getLast();
        this.awaitSource(this.askedFor, s -> s.state() == SourceInfo.State.QUEUED);
    }

    private void givenTheConnectionCloses() throws Exception {
        this.link.close();
        this.awaitSource(this.askedFor, s -> s.state() == SourceInfo.State.WAITING);
    }

    private void givenTheDownloadHasPriority(Hash16 file, DownloadPriority priority) throws Exception {
        this.onLoop(() -> this.manager.setPriority(file, priority));
    }

    private void givenTheDownloadTakesA4afSourcesByItself(Hash16 file) throws Exception {
        this.whenTheDownloadTakesA4afSourcesByItself(file);
    }

    private void whenTheClientIsAsked() throws Exception {
        this.link = this.client.accept();
        this.askedFor = this.requestedFile();
    }

    private void whenTheClientHasNoPartWeNeedOf(Hash16 file) throws Exception {
        this.link.send(new ClientMessages.FileStatus(file, 1, new BitSet()).encode());
    }

    private void whenTheClientLacks(Hash16 file) throws Exception {
        this.link.send(ClientMessages.forFile(Opcodes.Client.FILEREQANSNOFIL, file));
    }

    private void whenTheClientIsAskedAgainAtItsTime() throws Exception {
        this.now.addAndGet(DownloadManager.REASK_INTERVAL.toNanos());
        this.whenTheClientIsAsked();
    }

    private void whenTheDownloadIsPaused(Hash16 file) throws Exception {
        this.onLoop(() -> this.manager.pause(file));
    }

    private void whenTheDownloadIsCancelled(Hash16 file) throws Exception {
        this.onLoop(() -> this.manager.cancel(file));
    }

    private void whenTheA4afSourcesAreSwappedTo(Hash16 file) throws Exception {
        this.swapped = this.onLoop(() -> this.manager.swapSourcesHere(file));
    }

    private void whenTheSourcesAreSwappedAwayFrom(Hash16 file) throws Exception {
        this.swapped = this.onLoop(() -> this.manager.swapSourcesAway(file));
    }

    private void whenTheSourceIsSwappedTo(Hash16 file) throws Exception {
        this.done = this.onLoop(() -> this.manager.swapSource(file, this.client.address()));
    }

    private void whenTheDownloadTakesA4afSourcesByItself(Hash16 file) throws Exception {
        this.onLoop(() -> this.manager.setAutoSwap(file, true));
    }

    private void whenTheDownloadsAreLoadedAgain() throws Exception {
        this.onLoop(() -> {
            this.manager.close();
            return null;
        });
        this.manager = this.open();
    }

    private void whenEverySwapIsAskedFor(Hash16 file) throws Exception {
        this.answers = this.onLoop(() -> List.of(
                this.manager.swapSourcesHere(file),
                this.manager.swapSourcesAway(file),
                this.manager.swapSource(file, this.client.address()),
                this.manager.setAutoSwap(file, true)));
    }

    private void thenItIsAskedFor(Hash16 file) {
        assertThat(this.askedFor).isEqualTo(file);
    }

    // Not before the shortest wait sources allow between two asks, with a margin.
    private void thenTheClientIsAskedAgainOnceItAllowsFor(Hash16 file) throws Exception {
        this.now.addAndGet(DownloadManager.EARLIEST_REASK.toNanos());
        this.whenTheClientIsAsked();
        this.thenItIsAskedFor(file);
    }

    private void thenTheClientIsNotAskedAgainWithin(Duration wait) throws Exception {
        this.now.addAndGet(wait.toNanos());
        assertThat(this.client.isConnectedToWithin(QUIET)).isFalse();
    }

    private void thenTheClientIsAskedAgainAtItsTimeFor(Hash16 file) throws Exception {
        this.whenTheClientIsAskedAgainAtItsTime();
        this.thenItIsAskedFor(file);
    }

    private void thenTheSourceCannotBeSwappedAgainTo(Hash16 file) throws Exception {
        this.whenTheSourceIsSwappedTo(file);
        this.thenTheSwapIsDone(false);
    }

    private void thenItIsAskedOnTheSameConnectionFor(Hash16 file) throws Exception {
        this.askedFor = this.requestedFile();
        assertThat(this.askedFor).isEqualTo(file);
    }

    private void thenTheClientQueuesUsFor(Hash16 file, int rank) throws Exception {
        this.givenTheClientQueuesUsAt(rank);
        assertThat(ClientMessages.fileOf(this.lastUploadRequest)).isEqualTo(file);
    }

    private void thenTheSourceIsA4afFor(Hash16 file, Hash16 other, String otherName) throws Exception {
        SourceInfo source = this.awaitSource(file, s -> s.state() == SourceInfo.State.A4AF);
        assertThat(source.askedFor()).contains(other);
        assertThat(source.askedForName()).isEqualTo(otherName);
        assertThat(source.queueRank()).isZero();
    }

    private void thenTheSourceIs(Hash16 file, SourceInfo.State state) throws Exception {
        SourceInfo source = this.awaitSource(file, s -> s.state() == state);
        assertThat(source.askedFor()).isEmpty();
    }

    private void thenTheConnectionCloses() throws Exception {
        assertThat(this.link.closesWithin(WAIT)).isTrue();
    }

    private void thenTheDownloadHasSources(Hash16 file, int count) throws Exception {
        this.await(
                count + " sources",
                () -> this.manager.download(file).orElseThrow().sources() == count);
    }

    private void thenSwappedSources(int count) {
        assertThat(this.swapped).isEqualTo(count);
    }

    private void thenTheSwapIsDone(boolean expected) {
        assertThat(this.done).isEqualTo(expected);
    }

    private void thenTheDownloadTakesA4afSourcesByItself(Hash16 file, boolean expected) throws Exception {
        assertThat(this.onLoop(() -> this.manager.download(file).map(DownloadInfo::autoSwap)))
                .contains(expected);
    }

    private void thenEveryAnswerIs(Object... expected) {
        assertThat(this.answers).containsExactly(expected);
    }

    // The file of the next file request, read with what comes before it.
    private Hash16 requestedFile() throws Exception {
        List<Packet> request = this.link.until(Opcodes.Client.SETREQFILEID);
        return ClientMessages.fileOf(request.getLast());
    }

    private DownloadManager open() throws Exception {
        SecureRandom random = new SecureRandom();
        Identities identities = new Identities(
                IdentityHandshake.Identity.of(Identities.loadOrCreateKey(this.dir, random)),
                hash -> Optional.empty(),
                (hash, key) -> {},
                Optional::empty,
                random);
        Hello hello = Hello.of(Hash16.parseHex("ab".repeat(16)), Ipv4.toEd2k(OUR_IP), PORT, "test", 0, 0, 0, 0, 0, 0);
        return this.onLoop(() -> DownloadManager.open(
                this.loop,
                this.dir.resolve("temp"),
                () -> this.dir.resolve("incoming"),
                () -> hello,
                () -> new DownloadManager.Settings(ObfuscationMode.SUPPORTED, 100, PORT, 0, true),
                new NoServer(),
                ip -> false,
                random,
                this.ticker,
                new PeerLinks(this.loop, identities, link -> Optional.empty()),
                (hash, bytes) -> {},
                Optional::empty,
                List::of,
                new Quiet()));
    }

    private SourceInfo awaitSource(Hash16 file, Predicate<SourceInfo> wanted) throws Exception {
        SourceInfo[] seen = new SourceInfo[1];
        this.await("the source of " + file + " as wanted", () -> {
            for (SourceInfo info : this.manager.sources()) {
                if (info.file().equals(file) && wanted.test(info)) {
                    seen[0] = info;
                    return true;
                }
            }
            return false;
        });
        return seen[0];
    }

    private void await(String what, Callable<Boolean> condition) throws Exception {
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (System.nanoTime() < deadline) {
            if (this.onLoop(condition)) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("waited in vain for " + what);
    }

    private <T> T onLoop(Callable<T> task) throws Exception {
        return this.loop.submit(task).get(30, TimeUnit.SECONDS);
    }

    private static final class NoServer implements DownloadManager.ServerLink {
        @Override
        public boolean askSources(Hash16 file, long size) {
            return false;
        }

        @Override
        public long clientId() {
            return Ipv4.toEd2k(OUR_IP);
        }

        @Override
        public int serverIp() {
            return 0;
        }

        @Override
        public boolean requestCallback(long lowId) {
            return false;
        }
    }

    private static final class Quiet implements DownloadManager.Listener {
        @Override
        public void added(DownloadInfo download) {}

        @Override
        public void progress(DownloadInfo download) {}

        @Override
        public void completed(DownloadInfo download, Path file, PartFile part) {}

        @Override
        public void failed(DownloadInfo download, String reason) {}

        @Override
        public void removed(Hash16 hash) {}
    }
}
