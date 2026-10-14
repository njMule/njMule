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
import com.google.common.net.InetAddresses;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.core.api.Category;
import org.njmule.core.api.DownloadInfo;
import org.njmule.core.api.DownloadPriority;
import org.njmule.core.api.Ed2kLink;
import org.njmule.core.api.FileComment;
import org.njmule.core.api.FileHashing;
import org.njmule.core.api.Hash16;
import org.njmule.core.api.Hash20;
import org.njmule.core.api.SourceInfo;
import org.njmule.core.config.ObfuscationMode;
import org.njmule.core.engine.EngineLoop;
import org.njmule.core.files.PartFile;
import org.njmule.core.files.hash.AichRecovery;
import org.njmule.core.files.hash.AichTree;
import org.njmule.core.files.hash.Ed2k;
import org.njmule.core.files.hash.FileHasher;
import org.njmule.core.peer.Identities;
import org.njmule.core.peer.IdentityHandshake;
import org.njmule.core.peer.PeerLinks;
import org.njmule.core.protocol.ed2k.ClientMessages;
import org.njmule.core.protocol.ed2k.FileDescription;
import org.njmule.core.protocol.ed2k.Hello;
import org.njmule.core.protocol.ed2k.ServerMessages;
import org.njmule.core.protocol.ed2k.SourceExchange;
import org.njmule.core.protocol.io.PacketWriter;
import org.njmule.core.protocol.packet.Opcodes;
import org.njmule.core.protocol.packet.Packet;
import org.njmule.core.protocol.packet.Protocol;
import org.njmule.core.security.ConnectionGuard;
import org.njmule.core.security.IpFilter;
import org.njmule.core.util.Ipv4;

/** Downloads talking with scripted sources on the loopback address, on a clock the test moves. */
@Timeout(90)
class DownloadSourcesTest {

    private static final int PORT = 4662;
    private static final int OUR_IP = Ipv4.of(198, 51, 100, 7);
    private static final int BLOCKED_IP = Ipv4.of(203, 0, 113, 9);
    private static final Hash16 PEER = Hash16.parseHex("5e".repeat(16));
    private static final Duration WAIT = ScriptedSource.WAIT;

    @TempDir
    Path dir;

    private final EngineLoop loop = EngineLoop.start(1024, "test-engine");
    private final AtomicLong now = new AtomicLong(1_000_000_000L);
    private final Ticker ticker = new Ticker() {
        @Override
        public long read() {
            return DownloadSourcesTest.this.now.get();
        }
    };
    private final BlockingQueue<String> events = new LinkedBlockingQueue<>();
    private final List<AutoCloseable> resources = new ArrayList<>();
    private final Server server = new Server();
    private volatile DownloadManager.Settings settings = settings(ObfuscationMode.SUPPORTED, 100, 0);
    private volatile Path incoming;
    private volatile Optional<DownloadManager.KadSources> kad = Optional.empty();
    private DownloadManager.Listener listener = new Events();
    private DownloadManager manager;
    private ConnectionGuard guard;
    private ConnectionGuard.Permit held;
    private ScriptedSource waitingSource;

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
    void aSourceTellsItsCommentAndOtherSourcesAndKeepsUsInItsQueue() throws Exception {
        ScriptedSource source = this.source(1 << Hello.MISC1_AICH_SHIFT, Hello.MISC2_SOURCE_EXCHANGE2);
        DownloadManager manager = this.manager();
        Hash16 hash = Hash16.parseHex("31".repeat(16));
        this.onLoop(() -> manager.add(source.link("talk.bin", 5000, hash)));

        ScriptedSource.Link link = source.accept();
        assertThat(names(link.until(Opcodes.Extended.REQUESTSOURCES2)))
                .as("a source able to is asked for the AICH hash and for other sources")
                .containsExactly("REQUESTFILENAME", "SETREQFILEID", "AICHFILEHASHREQ", "REQUESTSOURCES2");
        SourceInfo asking = this.awaitSource(source, s -> s.state() == SourceInfo.State.ASKING);
        assertThat(asking.nick()).contains("scripted");
        assertThat(this.onLoop(manager::describe)).contains("talk.bin missing 5000:", "REQUESTING");

        // From now on only sources known to take obfuscation are asked: none of those it tells of, nor this one.
        this.settings = settings(ObfuscationMode.REQUIRED, 100, 0);
        link.send(FileDescription.encode(FileComment.of("a fine copy", 4)));
        link.send(FileDescription.encode(FileComment.of("free SPAM inside", 1)));
        link.send(FileDescription.encode(FileComment.NONE));
        link.send(aichHash(Hash16.parseHex("32".repeat(16)), Hash20.parseBase32("A".repeat(32))));
        link.send(aichHash(hash, Hash20.parseBase32("A".repeat(32))));
        link.send(new SourceExchange.Answer(
                        4,
                        hash,
                        List.of(
                                new SourceExchange.Source(0, 1234, PORT, 0, 0, Optional.empty(), 0),
                                exchanged(BLOCKED_IP),
                                exchanged(Ipv4.of(10, 1, 2, 3)),
                                exchanged(Ipv4.of(192, 0, 2, 50)),
                                exchanged(Ipv4.of(192, 0, 2, 51))))
                .encode());
        link.send(new SourceExchange.Answer(4, hash, List.of(exchanged(Ipv4.of(192, 0, 2, 52)))).encode());
        link.send(new ClientMessages.FileStatus(Hash16.parseHex("32".repeat(16)), 0, new BitSet()).encode());
        link.send(new ClientMessages.FileStatus(hash, 0, new BitSet()).encode());
        link.until(Opcodes.Client.STARTUPLOADREQ);
        link.send(Packet.of(Protocol.EDONKEY, Opcodes.Client.QUEUERANK.code(), new PacketWriter().u32(3)));

        // Queued from the upload request on; the rank comes after.
        SourceInfo queued = this.awaitSource(source, s -> s.state() == SourceInfo.State.QUEUED && s.queueRank() != 0);
        assertThat(queued.queueRank()).isEqualTo(3);
        assertThat(queued.parts()).isEqualTo(1);
        assertThat(this.onLoop(() -> manager.download(hash).orElseThrow().sources()))
                .as("the public sources it told of, not the LowID, the blocked or the LAN one, nor those of an answer"
                        + " not asked for")
                .isEqualTo(3);
        assertThat(this.onLoop(() -> manager.comments(hash))).singleElement().satisfies(c -> {
            assertThat(c.user()).isEqualTo("scripted");
            assertThat(c.comment()).isEqualTo(FileComment.of("a fine copy", 4));
        });
        assertThat(this.onLoop(() -> manager.find(hash).orElseThrow().file().aichHash()))
                .as("one source alone is no reason to trust a hash")
                .isEmpty();
        assertThat(this.onLoop(() -> manager.exchangeable(hash)))
                .as("a source counts once it said which parts it has")
                .isEmpty();

        // The connection closes while queued: the place in the queue stays, and the source can be passed on.
        link.close();
        this.awaitSource(source, s -> s.state() == SourceInfo.State.WAITING);
        assertThat(this.onLoop(() -> manager.exchangeable(hash)))
                .singleElement()
                .satisfies(s -> {
                    assertThat(s.port()).isEqualTo(source.port());
                    assertThat(s.userHash()).contains(PEER);
                });

        // Asked again in time, plain once allowed: not for more sources, asked for less than 40 minutes ago.
        this.settings = settings(ObfuscationMode.SUPPORTED, 100, 0);
        this.advance(DownloadManager.REASK_INTERVAL);
        ScriptedSource.Link again = source.accept();
        List<Packet> request = new ArrayList<>(again.until(Opcodes.Client.SETREQFILEID));
        again.send(new ClientMessages.FileStatus(hash, 0, new BitSet()).encode());
        request.addAll(again.until(Opcodes.Client.STARTUPLOADREQ));
        assertThat(names(request)).doesNotContain("REQUESTSOURCES2");
        this.onLoop(() -> manager.pause(hash));
        assertThat(again.closesWithin(WAIT)).isTrue();
    }

    @Test
    void aSourceThatEndsOurSlotIsAskedForTheSameBlocksAndAFileThatCannotBeMovedFailsUntilResumed() throws Exception {
        byte[] data = random(5000, 3);
        Hash16 hash = Ed2k.md4(data, 0, data.length);
        Path blocker = Files.writeString(this.dir.resolve("blocker"), "a file where the directory should be");
        this.incoming = blocker;
        ScriptedSource source = this.source(0, 0);
        DownloadManager manager = this.manager();
        this.onLoop(() -> manager.add(source.link("data.bin", data.length, hash)));

        ScriptedSource.Link link = source.accept();
        link.until(Opcodes.Client.SETREQFILEID);
        link.send(new ClientMessages.FileStatus(hash, 0, new BitSet()).encode());
        link.until(Opcodes.Client.STARTUPLOADREQ);
        link.send(Packet.empty(Protocol.EDONKEY, Opcodes.Client.ACCEPTUPLOADREQ.code()));
        assertThat(requested(link)).containsExactly(new ClientMessages.Range(0, data.length));
        this.awaitSource(source, s -> s.state() == SourceInfo.State.DOWNLOADING);
        assertThat(this.onLoop(() -> manager.download(hash).orElseThrow().transferring()))
                .isEqualTo(1);
        assertThat(this.onLoop(manager::describe)).contains("DOWNLOADING [0-5000 missing");

        link.send(Packet.empty(Protocol.EDONKEY, Opcodes.Client.OUTOFPARTREQS.code()));
        this.awaitSource(source, s -> s.state() == SourceInfo.State.QUEUED);
        link.send(Packet.empty(Protocol.EDONKEY, Opcodes.Client.ACCEPTUPLOADREQ.code()));
        assertThat(requested(link))
                .as("the blocks let go are asked for again")
                .containsExactly(new ClientMessages.Range(0, data.length));

        link.send(new ClientMessages.SendingPart(Hash16.parseHex("33".repeat(16)), 0, 10, new byte[10]).encode(false));
        link.send(new ClientMessages.SendingPart(hash, 4990, 5010, new byte[20]).encode(false));
        link.send(new ClientMessages.SendingPart(hash, 0, data.length, data).encode(true));

        assertThat(this.await("failed")).contains("cannot move");
        assertThat(this.onLoop(() -> manager.download(hash).orElseThrow().status()))
                .isEqualTo(DownloadInfo.Status.ERROR);
        assertThat(link.closesWithin(WAIT)).as("its sessions end").isTrue();

        Files.delete(blocker);
        assertThat(this.onLoop(() -> manager.resume(hash))).isTrue();
        Path done = Path.of(this.await("completed ").substring("completed ".length()));
        assertThat(Files.readAllBytes(done)).isEqualTo(data);
        assertThat(this.onLoop(() -> manager.download(hash))).isEmpty();
    }

    @Test
    void aSourceIgnoresWhatDoesNotFitUntilItBreaksTheProtocol() throws Exception {
        byte[] data = random(5000, 4);
        Hash16 hash = Ed2k.md4(data, 0, data.length);
        ScriptedSource source = this.source(0, 0);
        DownloadManager manager = this.manager();
        this.onLoop(() -> manager.add(source.link("odd.bin", data.length, hash)));
        ScriptedSource.Link link = source.accept();
        link.until(Opcodes.Client.SETREQFILEID);

        // Nothing of this changes anything: a rank before the queue, an answer not asked for, a hash set not needed.
        link.send(Packet.of(
                Protocol.EMULE,
                Opcodes.Extended.QUEUERANKING.code(),
                new PacketWriter().u16(9).bytes(new byte[10])));
        link.send(Packet.of(
                Protocol.EMULE,
                Opcodes.Extended.AICHANSWER.code(),
                new PacketWriter().hash16(hash).u16(0).hash20(Hash20.parseBase32("B".repeat(32)))));
        link.send(new ClientMessages.HashSet(hash, List.of(Hash16.parseHex("36".repeat(16)))).encode());
        link.send(new ClientMessages.FileStatus(hash, 0, new BitSet()).encode());
        link.send(new ClientMessages.FileStatus(hash, 0, new BitSet()).encode());
        link.until(Opcodes.Client.STARTUPLOADREQ);
        assertThat(this.awaitSource(source, s -> s.state() == SourceInfo.State.QUEUED)
                        .queueRank())
                .isZero();
        assertThat(this.onLoop(() -> manager.find(hash).orElseThrow().file().partHashes()))
                .isEmpty();

        // Neither does the end of a slot not given yet, nor data of no block asked for.
        link.send(Packet.empty(Protocol.EDONKEY, Opcodes.Client.OUTOFPARTREQS.code()));
        link.send(Packet.empty(Protocol.EDONKEY, Opcodes.Client.ACCEPTUPLOADREQ.code()));
        assertThat(requested(link)).containsExactly(new ClientMessages.Range(0, data.length));
        link.send(new ClientMessages.CompressedPart(hash, 7, 10, new byte[10]).encode(false));
        link.send(
                new ClientMessages.CompressedPart(Hash16.parseHex("37".repeat(16)), 0, 10, new byte[10]).encode(false));
        link.send(new ClientMessages.SendingPart(hash, 0, 0, new byte[0]).encode(false));
        link.send(new ClientMessages.SendingPart(hash, 0, 1000, Arrays.copyOf(data, 1000)).encode(false));
        this.await(
                "the first bytes written",
                () -> manager.download(hash).orElseThrow().completed() == 1000);

        // A status that does not decode ends it for good.
        link.send(Packet.of(
                Protocol.EDONKEY,
                Opcodes.Client.FILESTATUS.code(),
                new PacketWriter().hash16(hash).u16(5)));
        assertThat(link.closesWithin(WAIT)).isTrue();
        this.awaitSources(hash, 0);
        assertThat(this.onLoop(() -> manager.download(hash).orElseThrow().completed()))
                .isEqualTo(1000);
    }

    @Test
    void aCorruptBlockIsRecoveredWithTheAichDataOfASourceThatComesLater() throws Exception {
        Recovery recovery = this.recovery(5);
        ScriptedSource.Link second = recovery.helper.accept();
        second.until(Opcodes.Client.SETREQFILEID);
        second.send(new ClientMessages.FileStatus(recovery.link.hash(), 0, new BitSet()).encode());
        Packet request = second.until(Opcodes.Extended.AICHREQUEST).getLast();
        assertThat(request.reader().hash16()).isEqualTo(recovery.link.hash());
        second.send(aichHash(recovery.link.hash(), Hash20.parseBase32("F".repeat(32))));

        second.send(
                aichAnswer(recovery.link.hash(), recovery.tree.masterHash(), AichRecovery.create(recovery.tree, 0)));

        assertThat(requested(recovery.first))
                .as("only the corrupt block is downloaded again")
                .containsExactly(new ClientMessages.Range(2L * Ed2k.EMBLOCKSIZE, recovery.data.length));
        sendBlocks(recovery.first, recovery.link.hash(), recovery.data, 2L * Ed2k.EMBLOCKSIZE, recovery.data.length);
        Path done = Path.of(this.await("completed ").substring("completed ".length()));
        assertThat(Files.readAllBytes(done)).isEqualTo(recovery.data);
        assertThat(this.onLoop(() -> this.manager.find(recovery.link.hash()))).isEmpty();
    }

    @Test
    void recoveryDataThatProvesNothingHasThePartDownloadedAgainWhole() throws Exception {
        Recovery recovery = this.recovery(6);
        ScriptedSource.Link second = recovery.helper.accept();
        second.until(Opcodes.Client.SETREQFILEID);
        second.send(new ClientMessages.FileStatus(recovery.link.hash(), 0, new BitSet()).encode());
        second.until(Opcodes.Extended.AICHREQUEST);

        second.send(aichAnswer(recovery.link.hash(), recovery.tree.masterHash(), random(200, 7)));

        List<ClientMessages.Range> whole = List.of(
                new ClientMessages.Range(0, Ed2k.EMBLOCKSIZE),
                new ClientMessages.Range(Ed2k.EMBLOCKSIZE, 2L * Ed2k.EMBLOCKSIZE),
                new ClientMessages.Range(2L * Ed2k.EMBLOCKSIZE, recovery.data.length));
        assertThat(requested(recovery.first)).containsExactlyElementsOf(whole);

        // Corrupt once more: its recovery was tried, so it comes again whole at once.
        byte[] corrupt = recovery.data.clone();
        corrupt[0] ^= 0x55;
        sendBlocks(recovery.first, recovery.link.hash(), corrupt, 0, recovery.data.length);
        assertThat(requested(recovery.first)).containsExactlyElementsOf(whole);
        this.onLoop(() -> this.manager.pause(recovery.link.hash()));
    }

    /**
     * A download of three blocks with a trusted AICH hash whose first source, unable to send recovery data, sends the
     * last block corrupt; a second source, able to, is then added by hand.
     */
    private Recovery recovery(long seed) throws Exception {
        byte[] data = random(2 * Ed2k.EMBLOCKSIZE + 30_000, seed);
        Path file = Files.write(this.dir.resolve("original.bin"), data);
        Ed2kLink.FileLink hashed = FileHashing.link(file, bytes -> {});
        AichTree tree = FileHasher.hash(file).aich().orElseThrow();
        ScriptedSource first = this.source(0, 0);
        ScriptedSource helper = this.source(1 << Hello.MISC1_AICH_SHIFT, 0);
        Ed2kLink.FileLink link = new Ed2kLink.FileLink(
                "recover.bin",
                data.length,
                hashed.hash(),
                Optional.of(tree.masterHash()),
                hashed.partHashes(),
                List.of(new Ed2kLink.Source("127.0.0.1", first.port())),
                List.of());
        DownloadManager manager = this.manager();
        this.onLoop(() -> manager.add(link));
        ScriptedSource.Link served = first.accept();
        served.until(Opcodes.Client.SETREQFILEID);
        served.send(new ClientMessages.FileStatus(link.hash(), 0, new BitSet()).encode());
        served.until(Opcodes.Client.STARTUPLOADREQ);
        served.send(Packet.empty(Protocol.EDONKEY, Opcodes.Client.ACCEPTUPLOADREQ.code()));
        assertThat(requested(served)).hasSize(3);
        byte[] corrupt = data.clone();
        corrupt[data.length - 1] ^= 0x55;
        sendBlocks(served, link.hash(), corrupt, 0, data.length);
        this.await(
                "the corrupt part held for its recovery data",
                () -> manager.find(link.hash()).orElseThrow().file().isHeld(0));
        assertThat(this.onLoop(() -> manager.addSourceByHand(link.hash(), helper.address())))
                .isTrue();
        return new Recovery(data, link, tree, served, helper);
    }

    /** The data of the download, its link and AICH tree, the first source's link and the second source. */
    private static final class Recovery {
        private final byte[] data;
        private final Ed2kLink.FileLink link;
        private final AichTree tree;
        private final ScriptedSource.Link first;
        private final ScriptedSource helper;

        Recovery(byte[] data, Ed2kLink.FileLink link, AichTree tree, ScriptedSource.Link first, ScriptedSource helper) {
            this.data = data;
            this.link = link;
            this.tree = tree;
            this.first = first;
            this.helper = helper;
        }
    }

    private static Packet aichAnswer(Hash16 file, Hash20 master, byte[] data) {
        return Packet.of(
                Protocol.EMULE,
                Opcodes.Extended.AICHANSWER.code(),
                new PacketWriter().hash16(file).u16(0).hash20(master).bytes(data));
    }

    // In packets of 10 KB, as clients send them.
    private static void sendBlocks(ScriptedSource.Link link, Hash16 hash, byte[] data, long start, long end)
            throws Exception {
        for (long at = start; at < end; at += 10_240) {
            int to = (int) Math.min(end, at + 10_240);
            link.send(
                    new ClientMessages.SendingPart(hash, at, to, Arrays.copyOfRange(data, (int) at, to)).encode(false));
        }
    }

    @Test
    void sourcesWithoutTheFileOrOutOfReachAreDropped() throws Exception {
        ScriptedSource missing = this.source(0, 0);
        ScriptedSource odd = this.source(0, 0);
        ScriptedSource useless = this.source(0, 0);
        ScriptedSource silent = this.source(0, 0);
        int closed;
        try (ServerSocket probe = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            closed = probe.getLocalPort();
        }
        DownloadManager manager = this.manager();
        Hash16 hash = Hash16.parseHex("34".repeat(16));
        Ed2kLink.FileLink link = new Ed2kLink.FileLink(
                "gone.bin",
                5000,
                hash,
                Optional.empty(),
                List.of(),
                List.of(
                        new Ed2kLink.Source("127.0.0.1", missing.port()),
                        new Ed2kLink.Source("127.0.0.1", odd.port()),
                        new Ed2kLink.Source("127.0.0.1", useless.port()),
                        new Ed2kLink.Source("127.0.0.1", silent.port()),
                        new Ed2kLink.Source("127.0.0.1", closed)),
                List.of());
        this.onLoop(() -> manager.add(link));

        ScriptedSource.Link noFile = missing.accept();
        noFile.until(Opcodes.Client.SETREQFILEID);
        noFile.send(ClientMessages.forFile(Opcodes.Client.FILEREQANSNOFIL, Hash16.parseHex("35".repeat(16))));
        noFile.send(ClientMessages.forFile(Opcodes.Client.FILEREQANSNOFIL, hash));
        ScriptedSource.Link tooMany = odd.accept();
        tooMany.until(Opcodes.Client.SETREQFILEID);
        BitSet five = new BitSet();
        five.set(0, 5);
        tooMany.send(new ClientMessages.FileStatus(hash, 5, five).encode());
        ScriptedSource.Link nothing = useless.accept();
        nothing.until(Opcodes.Client.SETREQFILEID);
        nothing.send(new ClientMessages.FileStatus(hash, 1, new BitSet()).encode());
        silent.refuseNext();

        assertThat(noFile.closesWithin(WAIT)).isTrue();
        assertThat(tooMany.closesWithin(WAIT)).isTrue();
        assertThat(nothing.closesWithin(WAIT)).as("it has no part we need").isTrue();
        this.awaitSources(hash, 3);
        assertThat(this.onLoop(() ->
                        manager.sources().stream().map(SourceInfo::address).toList()))
                .containsExactlyInAnyOrder(
                        "127.0.0.1:" + useless.port(), "127.0.0.1:" + silent.port(), "127.0.0.1:" + closed);

        // Three failures in a row drop a source; the one without parts we need waits its time. Each round waits for
        // both failures: a refused connection takes Windows a couple of seconds.
        this.awaitFailures(manager, 1, silent.port(), closed);
        this.advance(DownloadManager.MIN_REASK);
        silent.refuseNext();
        this.awaitFailures(manager, 2, silent.port(), closed);
        this.advance(DownloadManager.MIN_REASK);
        silent.refuseNext();
        this.awaitSources(hash, 1);
        assertThat(this.onLoop(() -> manager.sources().getFirst().address())).isEqualTo("127.0.0.1:" + useless.port());
        this.onLoop(() -> manager.pause(hash));
    }

    @Test
    void firewalledSourcesAreAskedThroughTheServerOneAtATime() throws Exception {
        DownloadManager manager = this.manager();
        Hash16 hash = Hash16.parseHex("36".repeat(16));
        this.onLoop(() -> manager.add(Ed2kLink.FileLink.of("lowid.bin", 5000, hash)));
        this.server.refused.add(6000L);

        this.onLoop(() -> {
            manager.foundSources(new ServerMessages.FoundSources(
                    hash,
                    List.of(
                            new ServerMessages.Source(5000, PORT, 0, Optional.of(PEER)),
                            new ServerMessages.Source(5000, PORT, 0, Optional.of(PEER)),
                            new ServerMessages.Source(6000, PORT, 0, Optional.empty()),
                            new ServerMessages.Source(0, PORT, 0, Optional.empty()),
                            new ServerMessages.Source(Ipv4.toEd2k(BLOCKED_IP), PORT, 0, Optional.empty()))));
            manager.foundSources(new ServerMessages.FoundSources(
                    Hash16.parseHex("37".repeat(16)),
                    List.of(new ServerMessages.Source(7000, PORT, 0, Optional.empty()))));
            return null;
        });

        this.await("both asked for", () -> this.server.callbacks.size() >= 2);
        assertThat(this.server.callbacks).as("one a tick, each once").containsExactly(5000L, 6000L);
        // One the server could not ask is forgotten, and may be asked for again.
        this.onLoop(() -> {
            manager.foundSources(new ServerMessages.FoundSources(
                    hash, List.of(new ServerMessages.Source(6000, PORT, 0, Optional.empty()))));
            return null;
        });
        this.await("asked again", () -> this.server.callbacks.size() >= 3);
        // One that does not call back in time is forgotten too: the server names it again, and it is asked again.
        this.advance(DownloadManager.CALLBACK_WAIT);
        this.await("asked once more", () -> {
            manager.foundSources(new ServerMessages.FoundSources(
                    hash, List.of(new ServerMessages.Source(5000, PORT, 0, Optional.of(PEER)))));
            return this.server.callbacks.lastIndexOf(5000L) > 0;
        });

        this.onLoop(() -> manager.pause(hash));
        int asked = this.server.callbacks.size();
        this.onLoop(() -> {
            manager.foundSources(new ServerMessages.FoundSources(
                    hash, List.of(new ServerMessages.Source(8000, PORT, 0, Optional.empty()))));
            return null;
        });
        Thread.sleep(1500);
        assertThat(this.server.callbacks).as("not for a paused download").hasSize(asked);
    }

    @Test
    void aFullSourceListTakesNoMoreFirewalledSources() throws Exception {
        this.settings = settings(ObfuscationMode.SUPPORTED, 1, 0);
        ScriptedSource source = this.source(0, Hello.MISC2_SOURCE_EXCHANGE2);
        DownloadManager manager = this.manager();
        Hash16 hash = Hash16.parseHex("38".repeat(16));
        this.onLoop(() -> manager.add(source.link("full.bin", 5000, hash)));
        ScriptedSource.Link link = source.accept();
        List<Packet> asked = new ArrayList<>(link.until(Opcodes.Client.SETREQFILEID));
        link.send(new ClientMessages.FileStatus(hash, 0, new BitSet()).encode());
        asked.addAll(link.until(Opcodes.Client.STARTUPLOADREQ));
        assertThat(names(asked)).as("a full list wants no more sources").doesNotContain("REQUESTSOURCES2");

        this.onLoop(() -> {
            manager.foundSources(new ServerMessages.FoundSources(
                    hash, List.of(new ServerMessages.Source(5000, PORT, 0, Optional.empty()))));
            return null;
        });
        Thread.sleep(1500);

        assertThat(this.server.callbacks).isEmpty();
        assertThat(this.onLoop(() -> manager.addSourceByHand(
                        hash, new InetSocketAddress(InetAddresses.forString("127.0.0.2"), PORT))))
                .isFalse();
        link.close();
    }

    @Test
    void aSourceNamedByItsHostIsLookedUp() throws Exception {
        ScriptedSource source = this.source(0, 0);
        DownloadManager manager = this.manager();
        Hash16 hash = Hash16.parseHex("3d".repeat(16));
        Ed2kLink.FileLink link = new Ed2kLink.FileLink(
                "named.bin",
                5000,
                hash,
                Optional.empty(),
                List.of(),
                List.of(new Ed2kLink.Source("localhost", source.port())),
                List.of());

        this.onLoop(() -> manager.add(link));

        assertThat(source.accept()).isNotNull();
        assertThat(this.onLoop(() -> manager.sources().getFirst().address())).isEqualTo("127.0.0.1:" + source.port());
        this.onLoop(() -> manager.pause(hash));
    }

    @Test
    void aDiskShortOfSpaceFromTheStartHoldsEveryDownload() throws Exception {
        this.settings = settings(ObfuscationMode.SUPPORTED, 100, Long.MAX_VALUE);
        // A listener that does not care about the disk.
        this.listener = new DownloadManager.Listener() {
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
        };
        DownloadManager manager = this.manager();
        Hash16 hash = Hash16.parseHex("3e".repeat(16));

        DownloadInfo info = this.onLoop(() -> manager.add(Ed2kLink.FileLink.of("held.bin", 5000, hash)));

        assertThat(info.status()).isEqualTo(DownloadInfo.Status.NO_SPACE);
        assertThat(DownloadManager.passesOn(Ipv4.of(10, 0, 0, 1), InetAddresses.forString("::1")))
                .as("a LAN source goes to no client without an IPv4 address")
                .isFalse();
    }

    @Test
    void kadSourcesOfAStoppedDownloadAreLeftOut() throws Exception {
        AtomicReference<Consumer<List<DownloadManager.KadSource>>> found = new AtomicReference<>();
        this.kad = Optional.of((file, size, sources) -> {
            found.compareAndSet(null, sources);
            return true;
        });
        DownloadManager manager = this.manager();
        Hash16 hash = Hash16.parseHex("39".repeat(16));
        this.onLoop(() -> manager.add(Ed2kLink.FileLink.of("kad.bin", 5000, hash)));
        this.await("Kad searched", () -> found.get() != null);

        int sources = this.onLoop(() -> {
            manager.stop(hash);
            found.get()
                    .accept(List.of(DownloadManager.KadSource.direct(
                            new InetSocketAddress(Ipv4.format(Ipv4.of(192, 0, 2, 60)), PORT))));
            return manager.download(hash).orElseThrow().sources();
        });

        assertThat(sources).isZero();
    }

    @Test
    void unknownDownloadsChangeNothing() throws Exception {
        DownloadManager manager = this.manager();
        Hash16 unknown = Hash16.parseHex("3a".repeat(16));
        Hash16 hash = Hash16.parseHex("3b".repeat(16));
        this.onLoop(() -> {
            manager.add(Ed2kLink.FileLink.of("known.bin", 5000, hash));
            return manager.pause(hash);
        });

        assertThat(this.onLoop(() -> List.of(
                        manager.stopBySchedule(unknown),
                        manager.setPriority(unknown, DownloadPriority.HIGH),
                        manager.cancel(unknown),
                        manager.resume(unknown),
                        manager.setPreviewFirst(unknown, true),
                        manager.setCategory(unknown, Category.NONE),
                        manager.addSourceByHand(
                                unknown, new InetSocketAddress(InetAddresses.forString("127.0.0.1"), PORT)),
                        manager.addSourceByHand(hash, new InetSocketAddress(InetAddresses.forString("::1"), PORT)),
                        manager.addSourceByHand(hash, new InetSocketAddress(InetAddresses.forString("127.0.0.1"), 0)))))
                .containsOnly(false);
        assertThat(this.onLoop(() -> manager.comments(unknown))).isEmpty();
        assertThat(this.onLoop(() -> manager.exchangeable(unknown))).isEmpty();
        assertThat(this.onLoop(() -> manager.exchangeable(hash))).as("paused").isEmpty();

        assertThat(this.onLoop(() -> manager.setPreviewFirst(hash, true))).isTrue();
        assertThat(this.onLoop(() -> manager.download(hash).orElseThrow().previewFirst()))
                .isTrue();
        assertThat(this.onLoop(() -> manager.stopBySchedule(hash))).isTrue();
        assertThat(this.onLoop(() -> manager.stopBySchedule(hash)))
                .as("stopped already")
                .isTrue();
        assertThat(this.onLoop(() -> manager.download(hash).orElseThrow().status()))
                .isEqualTo(DownloadInfo.Status.STOPPED);
        assertThat(this.onLoop(() -> manager.setCategory(hash, Category.NONE))).isTrue();
    }

    @Test
    void downloadsWaitWhileTheDiskIsShortOfSpace() throws Exception {
        ScriptedSource source = this.source(0, 0);
        DownloadManager manager = this.manager();
        Hash16 hash = Hash16.parseHex("3c".repeat(16));
        this.onLoop(() -> manager.add(source.link("space.bin", 5000, hash)));
        ScriptedSource.Link link = source.accept();
        link.until(Opcodes.Client.SETREQFILEID);
        link.send(new ClientMessages.FileStatus(hash, 0, new BitSet()).encode());
        link.until(Opcodes.Client.STARTUPLOADREQ);

        this.settings = settings(ObfuscationMode.SUPPORTED, 100, Long.MAX_VALUE);
        assertThat(this.await("space ")).isEqualTo("space short");
        assertThat(link.closesWithin(WAIT)).as("the sessions end").isTrue();
        assertThat(this.onLoop(() -> manager.download(hash).orElseThrow().status()))
                .isEqualTo(DownloadInfo.Status.NO_SPACE);

        this.settings = settings(ObfuscationMode.SUPPORTED, 100, 0);
        assertThat(this.await("space ")).isEqualTo("space enough");
        // With room again, the source is asked as soon as it allows, not after the whole wait of a queued one.
        this.advance(DownloadManager.EARLIEST_REASK);
        ScriptedSource.Link again = source.accept();
        again.until(Opcodes.Client.SETREQFILEID);
        this.onLoop(() -> manager.pause(hash));
    }

    private static SourceExchange.Source exchanged(int ip) {
        return new SourceExchange.Source(ip, 0, PORT, 0, 0, Optional.empty(), 0);
    }

    private static Packet aichHash(Hash16 file, Hash20 master) {
        return Packet.of(
                Protocol.EMULE,
                Opcodes.Extended.AICHFILEHASHANS.code(),
                new PacketWriter().hash16(file).hash20(master));
    }

    private static List<ClientMessages.Range> requested(ScriptedSource.Link link) throws Exception {
        List<Packet> read = link.until(Opcodes.Client.REQUESTPARTS);
        return ClientMessages.RequestParts.decode(read.getLast()).ranges().stream()
                .filter(r -> !r.isEmpty())
                .toList();
    }

    private static List<String> names(List<Packet> packets) {
        List<String> names = new ArrayList<>();
        for (Packet packet : packets) {
            names.add(
                    packet.protocol() == Protocol.EMULE
                            ? Opcodes.Extended.of(packet.opcode())
                                    .map(Enum::name)
                                    .orElse("?")
                            : Opcodes.Client.of(packet.opcode()).map(Enum::name).orElse("?"));
        }
        return names;
    }

    @Test
    void aSourceWaitsWhileOurConnectionsAreAtTheirLimit() throws Exception {
        this.givenOurConnectionsAtTheirLimit();
        this.givenADownloadWhoseSourceIsNotCalled();

        this.whenAConnectionOfOursCloses();

        this.thenTheSourceIsCalled();
    }

    private void givenOurConnectionsAtTheirLimit() {
        this.guard = new ConnectionGuard(
                IpFilter::empty, new ConnectionGuard.Limits(1, 5, 20, Duration.ofSeconds(5)), this.ticker);
        this.held = this.guard.outgoing();
    }

    private void givenADownloadWhoseSourceIsNotCalled() throws Exception {
        this.waitingSource = this.source(0, 0);
        DownloadManager downloads = this.manager();
        this.onLoop(() -> downloads.add(this.waitingSource.link("waits.bin", 5000, Hash16.parseHex("36".repeat(16)))));
        assertThat(this.waitingSource.calledWithin(Duration.ofSeconds(2))).isFalse();
    }

    private void whenAConnectionOfOursCloses() {
        this.held.close();
    }

    private void thenTheSourceIsCalled() throws Exception {
        assertThat(this.waitingSource.calledWithin(WAIT)).isTrue();
        this.waitingSource.accept().until(Opcodes.Client.SETREQFILEID);
        assertThat(this.guard.openConnections()).isEqualTo(1);
    }

    private static DownloadManager.Settings settings(ObfuscationMode obfuscation, int maxSources, long minFree) {
        return new DownloadManager.Settings(obfuscation, maxSources, PORT, minFree, true, false, "spam|fake", 0);
    }

    private ScriptedSource source(int misc1, int misc2) throws Exception {
        ScriptedSource source = new ScriptedSource(PEER, misc1, misc2);
        this.resources.add(source);
        return source;
    }

    private DownloadManager manager() throws Exception {
        SecureRandom random = new SecureRandom();
        Identities identities = new Identities(
                IdentityHandshake.Identity.of(Identities.loadOrCreateKey(this.dir, random)),
                hash -> Optional.empty(),
                (hash, key) -> {},
                Optional::empty,
                random);
        Hello hello = Hello.of(Hash16.parseHex("ab".repeat(16)), Ipv4.toEd2k(OUR_IP), PORT, "test", 0, 0, 0, 0, 0, 0);
        this.incoming = this.incoming == null ? this.dir.resolve("incoming") : this.incoming;
        this.manager = this.onLoop(() -> DownloadManager.open(
                this.loop,
                this.dir.resolve("temp"),
                () -> this.incoming,
                () -> hello,
                () -> this.settings,
                this.server,
                ip -> ip == BLOCKED_IP,
                random,
                this.ticker,
                this.links(identities),
                (hash, bytes) -> {},
                () -> this.kad,
                List::of,
                this.listener));
        return this.manager;
    }

    private PeerLinks links(Identities identities) {
        PeerLinks links = new PeerLinks(this.loop, identities, link -> Optional.empty());
        if (this.guard != null) {
            links.countIn(this.guard);
        }
        return links;
    }

    private void advance(Duration duration) {
        this.now.addAndGet(duration.toNanos());
    }

    private SourceInfo awaitSource(ScriptedSource source, Predicate<SourceInfo> wanted) throws Exception {
        String address = "127.0.0.1:" + source.port();
        AtomicReference<SourceInfo> seen = new AtomicReference<>();
        this.await("source " + address, () -> {
            for (SourceInfo info : this.manager.sources()) {
                if (info.address().equals(address) && wanted.test(info)) {
                    seen.set(info);
                    return true;
                }
            }
            return false;
        });
        return seen.get();
    }

    private void awaitFailures(DownloadManager manager, int failures, int... ports) throws Exception {
        this.await(failures + " failures of " + Arrays.toString(ports), () -> {
            String now = manager.describe();
            return Arrays.stream(ports).allMatch(p -> now.contains("127.0.0.1:" + p + " (failed " + failures + ")"));
        });
    }

    private void awaitSources(Hash16 hash, int count) throws Exception {
        this.await(
                count + " sources",
                () -> this.manager.download(hash).orElseThrow().sources() == count);
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

    private String await(String prefix) throws InterruptedException {
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (System.nanoTime() < deadline) {
            String event = this.events.poll(100, TimeUnit.MILLISECONDS);
            if (event != null && event.startsWith(prefix)) {
                return event;
            }
        }
        throw new AssertionError("no event " + prefix);
    }

    private <T> T onLoop(Callable<T> task) throws Exception {
        return this.loop.submit(task).get(30, TimeUnit.SECONDS);
    }

    private static byte[] random(int size, long seed) {
        byte[] data = new byte[size];
        new Random(seed).nextBytes(data);
        return data;
    }

    // A HighID connection whose server asks firewalled sources to call us, refusing some.
    private static final class Server implements DownloadManager.ServerLink {
        private final List<Long> callbacks = new CopyOnWriteArrayList<>();
        private final List<Long> refused = new CopyOnWriteArrayList<>();

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
            this.callbacks.add(lowId);
            return !this.refused.contains(lowId);
        }
    }

    private final class Events implements DownloadManager.Listener {
        @Override
        public void added(DownloadInfo download) {
            DownloadSourcesTest.this.events.add("added " + download.name());
        }

        @Override
        public void progress(DownloadInfo download) {}

        @Override
        public void completed(DownloadInfo download, Path file, PartFile part) {
            DownloadSourcesTest.this.events.add("completed " + file);
        }

        @Override
        public void failed(DownloadInfo download, String reason) {
            DownloadSourcesTest.this.events.add("failed " + reason);
        }

        @Override
        public void removed(Hash16 hash) {
            DownloadSourcesTest.this.events.add("removed");
        }

        @Override
        public void diskSpace(boolean enough) {
            DownloadSourcesTest.this.events.add(enough ? "space enough" : "space short");
        }
    }
}
