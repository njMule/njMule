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

import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Path;
import java.security.SecureRandom;
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
    private DownloadQueue queue;
    private DownloadQueue.Download download;

    @AfterEach
    void stop() throws Exception {
        if (this.queue != null) {
            this.onLoop(() -> {
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
    void aListenerThatHearsOnlyTheEssentialsMissesNothingElse() throws Exception {
        ScriptedSource source = this.source(1 << Hello.MISC1_AICH_SHIFT, Hello.MISC2_SOURCE_EXCHANGE2);
        Hash16 hash = Hash16.parseHex("6b".repeat(16));
        DownloadSession session = this.session(source, hash);
        this.onLoop(() -> {
            session.connect(Optional.empty(), new SecureRandom());
            return null;
        });

        ScriptedSource.Link link = source.accept();
        link.until(Opcodes.Extended.REQUESTSOURCES2);
        link.send(FileDescription.encode(FileComment.of("fine", 3)));
        link.send(Packet.of(
                Protocol.EMULE,
                Opcodes.Extended.AICHFILEHASHANS.code(),
                new PacketWriter().hash16(hash).hash20(MASTER)));
        link.send(new ClientMessages.FileStatus(hash, 0, new BitSet()).encode());
        link.until(Opcodes.Client.STARTUPLOADREQ);
        link.send(Packet.empty(Protocol.EDONKEY, Opcodes.Client.ACCEPTUPLOADREQ.code()));
        link.until(Opcodes.Client.REQUESTPARTS);
        link.send(Packet.empty(Protocol.EDONKEY, Opcodes.Client.OUTOFPARTREQS.code()));
        this.await("back in the queue", () -> session.state() == DownloadSession.State.QUEUED);

        // Asked for recovery data, it has none; asked again, it sends some.
        assertThat(this.onLoop(() -> session.mayRecover(0))).isTrue();
        this.onLoop(() -> {
            session.requestAich(0, MASTER);
            return null;
        });
        assertThat(link.until(Opcodes.Extended.AICHREQUEST).getLast().reader().hash16())
                .isEqualTo(hash);
        link.send(Packet.of(Protocol.EMULE, Opcodes.Extended.AICHANSWER.code(), new PacketWriter().hash16(hash)));
        this.onLoop(() -> {
            session.requestAich(0, MASTER);
            return null;
        });
        link.until(Opcodes.Extended.AICHREQUEST);
        link.send(Packet.of(
                Protocol.EMULE,
                Opcodes.Extended.AICHANSWER.code(),
                new PacketWriter().hash16(hash).u16(0).hash20(MASTER).bytes(new byte[40])));
        link.send(Packet.of(Protocol.EDONKEY, Opcodes.Client.QUEUERANK.code(), new PacketWriter().u32(4)));
        this.await("the rank", () -> session.queueRank() == 4);

        // The download pauses under it: given the slot, it leaves.
        this.onLoop(() -> {
            this.queue.pause(this.download);
            return null;
        });
        link.send(Packet.empty(Protocol.EDONKEY, Opcodes.Client.ACCEPTUPLOADREQ.code()));
        assertThat(this.events.poll(10, TimeUnit.SECONDS)).isEqualTo("ended LATER the download is not active");
        assertThat(link.closesWithin(ScriptedSource.WAIT)).isTrue();
    }

    @Test
    void aSessionEndedBeforeItsConnectionOpensClosesIt() throws Exception {
        ScriptedSource source = this.source(0, 0);
        DownloadSession session = this.session(source, Hash16.parseHex("6c".repeat(16)));

        this.onLoop(() -> {
            session.connect(Optional.empty(), new SecureRandom());
            session.close("changed our mind");
            return null;
        });

        assertThat(this.events.poll(10, TimeUnit.SECONDS)).isEqualTo("ended LATER changed our mind");
        try (Socket socket = source.acceptSilently();
                InputStream in = socket.getInputStream()) {
            assertThat(in.read()).as("closed without a hello").isEqualTo(-1);
        }
        assertThat(this.events.poll(300, TimeUnit.MILLISECONDS)).isNull();
    }

    @Test
    void aConnectionThatFailsAfterTheSessionEndedChangesNothing() throws Exception {
        int closed;
        try (ServerSocket probe = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            closed = probe.getLocalPort();
        }
        this.queue();
        DownloadSession session = this.session(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), closed), Hash16.parseHex("6d".repeat(16)));

        this.onLoop(() -> {
            session.connect(Optional.empty(), new SecureRandom());
            session.close("given up");
            return null;
        });

        assertThat(this.events.poll(10, TimeUnit.SECONDS)).isEqualTo("ended LATER given up");
        assertThat(this.events.poll(5, TimeUnit.SECONDS))
                .as("the failure comes too late")
                .isNull();
    }

    @Test
    void aFaultInTheQueueEndsTheSessionToBeAskedAgainLater() throws Exception {
        ScriptedSource source = this.source(0, 0);
        Hash16 hash = Hash16.parseHex("6e".repeat(16));
        DownloadSession session = this.session(source, hash);
        this.onLoop(() -> {
            session.connect(Optional.empty(), new SecureRandom());
            return null;
        });
        ScriptedSource.Link link = source.accept();
        link.until(Opcodes.Client.SETREQFILEID);
        link.send(new ClientMessages.FileStatus(hash, 0, new BitSet()).encode());
        link.until(Opcodes.Client.STARTUPLOADREQ);
        link.send(Packet.empty(Protocol.EDONKEY, Opcodes.Client.ACCEPTUPLOADREQ.code()));
        link.until(Opcodes.Client.REQUESTPARTS);

        // Cancelled behind the session's back: its data file takes no more writes.
        this.onLoop(() -> this.queue.cancel(this.download));
        link.send(new ClientMessages.SendingPart(hash, 0, 100, new byte[100]).encode(false));

        assertThat(this.events.poll(10, TimeUnit.SECONDS)).startsWith("ended LATER internal error");
        assertThat(link.closesWithin(ScriptedSource.WAIT)).isTrue();
    }

    private ScriptedSource source(int misc1, int misc2) throws Exception {
        ScriptedSource source = new ScriptedSource(PEER, misc1, misc2);
        this.resources.add(source);
        return source;
    }

    private DownloadSession session(ScriptedSource source, Hash16 hash) throws Exception {
        this.queue();
        this.download = this.onLoop(() -> this.queue.add(Ed2kLink.FileLink.of("session.bin", 5000, hash)));
        return this.session(source.address(), hash);
    }

    private DownloadSession session(InetSocketAddress address, Hash16 hash) throws Exception {
        if (this.download == null) {
            this.download = this.onLoop(() -> this.queue.add(Ed2kLink.FileLink.of("session.bin", 5000, hash)));
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
                this.queue,
                this.download,
                address,
                () -> hello,
                new PeerLinks(this.loop, identities, link -> Optional.empty()),
                new Essentials());
    }

    private void queue() throws Exception {
        if (this.queue == null) {
            this.queue = this.onLoop(() -> DownloadQueue.load(
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
}
