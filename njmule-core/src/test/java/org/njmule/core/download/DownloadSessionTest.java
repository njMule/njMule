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
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.core.api.Ed2kLink;
import org.njmule.core.api.FileComment;
import org.njmule.core.api.Hash16;
import org.njmule.core.api.Hash20;
import org.njmule.core.engine.EngineLoop;
import org.njmule.core.files.PartFile;
import org.njmule.core.peer.Identities;
import org.njmule.core.peer.IdentityHandshake;
import org.njmule.core.peer.PeerLinks;
import org.njmule.core.protocol.ed2k.ClientMessages;
import org.njmule.core.protocol.ed2k.FileDescription;
import org.njmule.core.protocol.ed2k.Hello;
import org.njmule.core.protocol.ed2k.SourceExchange;
import org.njmule.core.protocol.io.PacketWriter;
import org.njmule.core.protocol.packet.Opcodes;
import org.njmule.core.protocol.packet.Packet;
import org.njmule.core.protocol.packet.Protocol;

/** One session with a scripted source, reporting to a listener that only hears what it must. */
@Timeout(60)
class DownloadSessionTest {

    private static final Hash16 PEER = Hash16.parseHex("6a".repeat(16));
    private static final Hash20 MASTER = Hash20.parseBase32("E".repeat(32));

    @TempDir
    Path dir;

    private final EngineLoop loop = EngineLoop.start(1024, "test-engine");
    private final BlockingQueue<String> events = new LinkedBlockingQueue<>();
    private final List<AutoCloseable> resources = new ArrayList<>();
    private Optional<String> shownName = Optional.empty();
    private DownloadQueue queue;
    private DownloadQueue.Download download;
    private ScriptedSource source;
    private ScriptedSource.Link link;
    private DownloadSession session;
    private Hash16 hash;
    private int closedPort;

    @AfterEach
    void stop() throws Exception {
        if (this.queue != null) {
            onLoop(() -> {
                this.queue.close();
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
    void shouldCutTheNameASourceGivesAtItsLongest() {
        whenASourceGivesTheName("a".repeat(DownloadSession.MAX_NAME + 40));

        thenTheNameShownIs("a".repeat(DownloadSession.MAX_NAME));
    }

    @Test
    void shouldShowNoNameForOneOfSpacesAndControlCharacters() {
        whenASourceGivesTheName(" \u0000\t ");

        thenNoNameIsShown();
    }

    @Test
    void shouldMissNothingWithAListenerThatHearsOnlyTheEssentials() throws Exception {
        givenASource(1 << Hello.MISC1_AICH_SHIFT, Hello.MISC2_SOURCE_EXCHANGE2);
        givenASessionForTheFile(Hash16.parseHex("6b".repeat(16)));
        givenTheSessionConnected();
        givenTheSourceDescribedTheFile();
        givenTheSourceTookBackTheSlotItGaveUs();
        givenTheSourceHadNoRecoveryDataAtFirst();
        givenTheSourceSentRecoveryDataWhenAskedAgain();
        givenTheSourceRankedUs(4);
        givenTheDownloadPaused();

        whenTheSourceGivesUsTheSlot();

        thenTheEventIs("ended LATER the download is not active");
        thenTheConnectionCloses();
    }

    @Test
    void shouldCloseTheConnectionOfASessionEndedBeforeItOpens() throws Exception {
        givenASource(0, 0);
        givenASessionForTheFile(Hash16.parseHex("6c".repeat(16)));

        whenTheSessionConnectsAndEnds("changed our mind");

        thenTheEventIs("ended LATER changed our mind");
        thenTheSourceIsHungUpOnWithoutAHello();
        thenNoOtherEventComesWithin(Duration.ofMillis(300));
    }

    @Test
    void shouldChangeNothingWhenAConnectionFailsAfterTheSessionEnded() throws Exception {
        givenAClosedPort();
        givenASessionToTheClosedPortForTheFile(Hash16.parseHex("6d".repeat(16)));

        whenTheSessionConnectsAndEnds("given up");

        thenTheEventIs("ended LATER given up");
        thenTheFailureComesTooLate();
    }

    @Test
    void shouldEndTheSessionToBeAskedAgainLaterOnAFaultInTheQueue() throws Exception {
        givenASource(0, 0);
        givenASessionForTheFile(Hash16.parseHex("6e".repeat(16)));
        givenTheSessionConnected();
        givenTheSourceGaveUsASlot();
        givenTheDownloadCancelledBehindTheSessionsBack();

        whenTheSourceSendsData(0, 100);

        thenTheEventStartsWith("ended LATER internal error");
        thenTheConnectionCloses();
    }

    private void givenASource(int misc1, int misc2) throws Exception {
        this.source = source(misc1, misc2);
    }

    private void givenASessionForTheFile(Hash16 file) throws Exception {
        this.hash = file;
        this.session = session(this.source, file);
    }

    private void givenAClosedPort() throws IOException {
        try (ServerSocket probe = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            this.closedPort = probe.getLocalPort();
        }
    }

    private void givenASessionToTheClosedPortForTheFile(Hash16 file) throws Exception {
        this.hash = file;
        queue();
        this.session = session(new InetSocketAddress(InetAddress.getLoopbackAddress(), this.closedPort), file);
    }

    private void givenTheSessionConnected() throws Exception {
        onLoop(() -> {
            this.session.connect(this.session.address(), Optional.empty(), new SecureRandom());
            return null;
        });
        this.link = this.source.accept();
    }

    private void givenTheSourceDescribedTheFile() throws Exception {
        this.link.until(Opcodes.Extended.REQUESTSOURCES2);
        this.link.send(FileDescription.encode(FileComment.of("fine", 3)));
        this.link.send(Packet.of(
                Protocol.EMULE,
                Opcodes.Extended.AICHFILEHASHANS.code(),
                new PacketWriter().hash16(this.hash).hash20(MASTER)));
        this.link.send(new ClientMessages.FileStatus(this.hash, 0, new BitSet()).encode());
    }

    private void givenTheSourceTookBackTheSlotItGaveUs() throws Exception {
        this.link.until(Opcodes.Client.STARTUPLOADREQ);
        this.link.send(Packet.empty(Protocol.EDONKEY, Opcodes.Client.ACCEPTUPLOADREQ.code()));
        this.link.until(Opcodes.Client.REQUESTPARTS);
        this.link.send(Packet.empty(Protocol.EDONKEY, Opcodes.Client.OUTOFPARTREQS.code()));
        await("back in the queue", () -> this.session.state() == DownloadSession.State.QUEUED);
    }

    // Asked for recovery data, it has none; asked again, it sends some.
    private void givenTheSourceHadNoRecoveryDataAtFirst() throws Exception {
        assertThat(onLoop(() -> this.session.mayRecover(0))).isTrue();
        onLoop(() -> {
            this.session.requestAich(0, MASTER);
            return null;
        });
        assertThat(this.link
                        .until(Opcodes.Extended.AICHREQUEST)
                        .getLast()
                        .reader()
                        .hash16())
                .isEqualTo(this.hash);
        this.link.send(
                Packet.of(Protocol.EMULE, Opcodes.Extended.AICHANSWER.code(), new PacketWriter().hash16(this.hash)));
    }

    private void givenTheSourceSentRecoveryDataWhenAskedAgain() throws Exception {
        onLoop(() -> {
            this.session.requestAich(0, MASTER);
            return null;
        });
        this.link.until(Opcodes.Extended.AICHREQUEST);
        this.link.send(Packet.of(
                Protocol.EMULE,
                Opcodes.Extended.AICHANSWER.code(),
                new PacketWriter().hash16(this.hash).u16(0).hash20(MASTER).bytes(new byte[40])));
    }

    private void givenTheSourceRankedUs(int rank) throws Exception {
        this.link.send(Packet.of(Protocol.EDONKEY, Opcodes.Client.QUEUERANK.code(), new PacketWriter().u32(rank)));
        await("the rank", () -> this.session.queueRank() == rank);
    }

    // The download pauses under it: given the slot, it leaves.
    private void givenTheDownloadPaused() throws Exception {
        onLoop(() -> {
            this.queue.pause(this.download);
            return null;
        });
    }

    private void givenTheSourceGaveUsASlot() throws Exception {
        this.link.until(Opcodes.Client.SETREQFILEID);
        this.link.send(new ClientMessages.FileStatus(this.hash, 0, new BitSet()).encode());
        this.link.until(Opcodes.Client.STARTUPLOADREQ);
        this.link.send(Packet.empty(Protocol.EDONKEY, Opcodes.Client.ACCEPTUPLOADREQ.code()));
        this.link.until(Opcodes.Client.REQUESTPARTS);
    }

    // Cancelled behind the session's back: its data file takes no more writes.
    private void givenTheDownloadCancelledBehindTheSessionsBack() throws Exception {
        onLoop(() -> this.queue.cancel(this.download));
    }

    private void whenTheSourceGivesUsTheSlot() throws Exception {
        this.link.send(Packet.empty(Protocol.EDONKEY, Opcodes.Client.ACCEPTUPLOADREQ.code()));
    }

    private void whenTheSessionConnectsAndEnds(String reason) throws Exception {
        onLoop(() -> {
            this.session.connect(this.session.address(), Optional.empty(), new SecureRandom());
            this.session.close(reason);
            return null;
        });
    }

    private void whenTheSourceSendsData(int start, int end) throws Exception {
        this.link.send(new ClientMessages.SendingPart(this.hash, start, end, new byte[end - start]).encode(false));
    }

    private void thenTheEventIs(String event) throws InterruptedException {
        assertThat(this.events.poll(10, TimeUnit.SECONDS)).isEqualTo(event);
    }

    private void thenTheEventStartsWith(String event) throws InterruptedException {
        assertThat(this.events.poll(10, TimeUnit.SECONDS)).startsWith(event);
    }

    private void thenTheConnectionCloses() throws IOException {
        assertThat(this.link.closesWithin(ScriptedSource.WAIT)).isTrue();
    }

    private void thenTheSourceIsHungUpOnWithoutAHello() throws Exception {
        try (Socket socket = this.source.acceptSilently();
                InputStream in = socket.getInputStream()) {
            assertThat(in.read()).as("closed without a hello").isEqualTo(-1);
        }
    }

    private void thenNoOtherEventComesWithin(Duration wait) throws InterruptedException {
        assertThat(this.events.poll(wait.toMillis(), TimeUnit.MILLISECONDS)).isNull();
    }

    private void thenTheFailureComesTooLate() throws InterruptedException {
        assertThat(this.events.poll(5, TimeUnit.SECONDS))
                .as("the failure comes too late")
                .isNull();
    }

    private ScriptedSource source(int misc1, int misc2) throws Exception {
        ScriptedSource source = new ScriptedSource(PEER, misc1, misc2);
        this.resources.add(source);
        return source;
    }

    private DownloadSession session(ScriptedSource source, Hash16 hash) throws Exception {
        queue();
        this.download = onLoop(() -> this.queue.add(Ed2kLink.FileLink.of("session.bin", 5000, hash)));
        return session(source.address(), hash);
    }

    private DownloadSession session(InetSocketAddress address, Hash16 hash) throws Exception {
        if (this.download == null) {
            this.download = onLoop(() -> this.queue.add(Ed2kLink.FileLink.of("session.bin", 5000, hash)));
        }
        SecureRandom random = new SecureRandom();
        Identities identities = new Identities(
                IdentityHandshake.Identity.of(Identities.loadOrCreateKey(this.dir, random)),
                h -> Optional.empty(),
                (h, key) -> {},
                Optional::empty,
                random);
        Hello hello = Hello.of(Hash16.parseHex("ab".repeat(16)), 0, 4662, "test", 0, 0, 0, 0, 0, 0);
        return new DownloadSession(
                this.loop,
                Ticker.systemTicker(),
                this.queue,
                this.download,
                address,
                () -> hello,
                new PeerLinks(this.loop, identities, link -> Optional.empty()),
                new Essentials());
    }

    private void queue() throws Exception {
        if (this.queue == null) {
            this.queue = onLoop(() -> DownloadQueue.load(
                    this.loop,
                    this.dir.resolve("temp"),
                    (PartFile file) -> this.dir.resolve("incoming"),
                    () -> 0,
                    new DownloadQueue.Listener() {
                        @Override
                        public void added(DownloadQueue.Download d) {}

                        @Override
                        public void progress(DownloadQueue.Download d) {}

                        @Override
                        public void completed(DownloadQueue.Download d, Path file) {}

                        @Override
                        public void failed(DownloadQueue.Download d, String reason) {}
                    }));
        }
    }

    private void await(String what, Callable<Boolean> condition) throws Exception {
        long deadline = System.nanoTime() + ScriptedSource.WAIT.toNanos();
        while (System.nanoTime() < deadline) {
            if (onLoop(condition)) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("waited in vain for " + what);
    }

    private <T> T onLoop(Callable<T> task) throws Exception {
        return this.loop.submit(task).get(30, TimeUnit.SECONDS);
    }

    /** Hears what every listener must: data, the end, and the sources it may ask for. */
    private final class Essentials implements DownloadSession.Listener {
        @Override
        public void received(DownloadSession session, int bytes, int transferred) {
            DownloadSessionTest.this.events.add("received " + bytes);
        }

        @Override
        public void ended(DownloadSession session, DownloadSession.Outcome outcome, String reason) {
            DownloadSessionTest.this.events.add("ended " + outcome + " " + reason);
        }

        @Override
        public boolean maySourcesBeAsked(DownloadSession session) {
            return true;
        }

        @Override
        public void sourcesFound(DownloadSession session, SourceExchange.Answer answer) {
            DownloadSessionTest.this.events.add("sources " + answer.sources().size());
        }
    }

    private void whenASourceGivesTheName(String name) {
        this.shownName = DownloadSession.shown(name);
    }

    private void thenTheNameShownIs(String name) {
        assertThat(this.shownName).contains(name);
    }

    private void thenNoNameIsShown() {
        assertThat(this.shownName).isEmpty();
    }
}
