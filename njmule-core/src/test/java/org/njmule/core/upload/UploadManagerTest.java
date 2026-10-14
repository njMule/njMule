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
package org.njmule.core.upload;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.common.base.Ticker;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.core.api.BanInfo;
import org.njmule.core.api.Ed2kLink;
import org.njmule.core.api.Hash16;
import org.njmule.core.api.UploadInfo;
import org.njmule.core.api.UploadPriority;
import org.njmule.core.config.ObfuscationMode;
import org.njmule.core.download.DownloadQueue;
import org.njmule.core.engine.EngineLoop;
import org.njmule.core.files.SharedFiles;
import org.njmule.core.files.hash.Ed2k;
import org.njmule.core.net.TcpListener;
import org.njmule.core.peer.Identities;
import org.njmule.core.peer.IdentityHandshake;
import org.njmule.core.peer.PeerGreetings;
import org.njmule.core.peer.PeerLink;
import org.njmule.core.peer.PeerLinks;
import org.njmule.core.protocol.ed2k.ClientMessages;
import org.njmule.core.protocol.ed2k.Hello;
import org.njmule.core.protocol.ed2k.LowIdMessages;
import org.njmule.core.protocol.ed2k.SourceExchange;
import org.njmule.core.protocol.io.PacketReader;
import org.njmule.core.protocol.io.PacketWriter;
import org.njmule.core.protocol.packet.Opcodes;
import org.njmule.core.protocol.packet.Packet;
import org.njmule.core.protocol.packet.Protocol;
import org.njmule.core.security.ConnectionGuard;
import org.njmule.core.security.IpFilter;
import org.njmule.core.util.Ipv4;

/** Our upload queue serving scripted clients on the loopback address, on a clock the test moves. */
@Timeout(90)
class UploadManagerTest {

    private static final Hash16 OURS = Hash16.parseHex("ab".repeat(16));
    private static final Duration WAIT = ScriptedDownloader.WAIT;
    private static final Duration SILENCE = Duration.ofMillis(500);
    private static final long LOW_ID = 1234;
    private static final Hash16 FAULTY = Hash16.parseHex("fa".repeat(16));

    @TempDir
    Path dir;

    private final EngineLoop loop = EngineLoop.start(1024, "test-engine");
    private final AtomicLong now = new AtomicLong(1_000_000_000L);
    private final Ticker ticker = new Ticker() {
        @Override
        public long read() {
            return UploadManagerTest.this.now.get();
        }
    };
    private final Map<Hash16, SharedFiles.SharedFile> shared = new ConcurrentHashMap<>();
    private final Map<Hash16, UploadPriority> priorities = new ConcurrentHashMap<>();
    private final List<Integer> reachable = new CopyOnWriteArrayList<>();
    private final List<AutoCloseable> resources = new ArrayList<>();
    private volatile List<SourceExchange.Source> downloadSources = List.of();
    private volatile UploadManager.Settings settings = new UploadManager.Settings(0, ObfuscationMode.SUPPORTED, 100);
    private volatile DownloadQueue downloads;
    private UploadManager manager;
    private int port;
    private final List<String> udpChecks = new CopyOnWriteArrayList<>();
    private ScriptedDownloader client;

    @AfterEach
    void stop() throws Exception {
        if (this.manager != null) {
            this.onLoop(() -> {
                this.manager.close();
                return null;
            });
        }
        if (this.downloads != null) {
            this.onLoop(() -> {
                this.downloads.close();
                return null;
            });
        }
        Collections.reverse(this.resources);
        for (AutoCloseable resource : this.resources) {
            resource.close();
        }
        this.loop.close();
    }

    @Test
    void aSlotGoesBackToTheQueueAfterAboutAPartWhileOthersWait() throws Exception {
        this.settings = new UploadManager.Settings(1, ObfuscationMode.SUPPORTED, 100, Optional.empty());
        int blocks = 18;
        byte[] data = random(blocks * (int) UploadSession.MAX_BLOCK, 1);
        SharedFiles.SharedFile file = this.share("big.bin", data);
        this.start();
        ScriptedDownloader first = this.client(1, 0, 0);
        ScriptedDownloader second = this.client(2, 0, 0);
        ScriptedDownloader firewalled = this.client(3, LOW_ID, 0);
        assertThat(this.ask(first, file.hash()).opcode()).isEqualTo(Opcodes.Client.ACCEPTUPLOADREQ.code());
        assertThat(this.ask(second, file.hash()).opcode()).isEqualTo(Opcodes.Client.ACCEPTUPLOADREQ.code());
        assertThat(rank(this.ask(firewalled, file.hash()))).isEqualTo(1);
        assertThat(this.onLoop(this.manager::describe)).startsWith("waiting 1").contains("slot");

        // The firewalled client leaves the queue's connection: we cannot call it, so it waits however many slots
        // a limit lifted gives.
        firewalled.close();
        this.settings = new UploadManager.Settings(0, ObfuscationMode.SUPPORTED, 100, Optional.empty());
        for (int at = 0; at < blocks; at += 3) {
            List<ClientMessages.Range> ranges = new ArrayList<>();
            for (int b = at; b < at + 3; b++) {
                ranges.add(new ClientMessages.Range(b * UploadSession.MAX_BLOCK, (b + 1) * UploadSession.MAX_BLOCK));
            }
            first.send(new ClientMessages.RequestParts(file.hash(), ranges).encode(false));
        }
        long received = 0;
        while (received < data.length) {
            Packet packet = first.next();
            if (packet.opcode() == Opcodes.Client.SENDINGPART.code()) {
                received += ClientMessages.SendingPart.decode(packet).data().length;
            }
        }

        assertThat(names(first.until(Protocol.EDONKEY, Opcodes.Client.ACCEPTUPLOADREQ.code())))
                .as("about a part sent, the slot goes to the queue and comes back, as no one else can take it")
                .containsSubsequence("OUTOFPARTREQS", "QUEUERANKING", "ACCEPTUPLOADREQ");
        assertThat(this.onLoop(this.manager::uploaded)).isEqualTo(data.length);
        assertThat(this.onLoop(this.manager::waitingCount)).isEqualTo(1);
    }

    @Test
    void theFriendWithTheFriendSlotPassesAFullQueue() throws Exception {
        Hash16 friend = hashOf(9);
        this.settings = new UploadManager.Settings(1, ObfuscationMode.SUPPORTED, 1, Optional.of(friend));
        SharedFiles.SharedFile file = this.share("wanted.txt", random(5000, 2));
        this.start();
        ScriptedDownloader first = this.client(1, 0, 0);
        ScriptedDownloader second = this.client(2, 0, 0);
        ScriptedDownloader waiting = this.client(3, 0, 0);
        ScriptedDownloader late = this.client(4, 0, 0);
        ScriptedDownloader best = this.client(9, 0, 0);
        this.ask(first, file.hash());
        this.ask(second, file.hash());
        assertThat(rank(this.ask(waiting, file.hash()))).isEqualTo(1);

        late.send(ClientMessages.forFile(Opcodes.Client.STARTUPLOADREQ, file.hash()));
        assertThat(late.poll(SILENCE)).as("the queue is full").isEmpty();
        assertThat(rank(this.ask(best, file.hash()))).as("a friend's place").isEqualTo(1);

        // A slot holder asks who else has the file: the other clients of it, not itself.
        first.send(SourceExchange.request(file.hash()));
        SourceExchange.Answer answer = SourceExchange.Answer.decode(first.until(Opcodes.Extended.ANSWERSOURCES2));
        assertThat(answer.sources())
                .extracting(SourceExchange.Source::port)
                .containsExactlyInAnyOrder(
                        second.hello().tcpPort(),
                        waiting.hello().tcpPort(),
                        best.hello().tcpPort());

        first.close();
        assertThat(best.until(Opcodes.Client.ACCEPTUPLOADREQ)).isNotNull();
        assertThat(this.onLoop(this.manager::uploads))
                .extracting(UploadInfo::state)
                .containsExactly(UploadInfo.State.UPLOADING, UploadInfo.State.UPLOADING, UploadInfo.State.QUEUED);
    }

    @Test
    void waitingClientsAreRankedByTheirWaitAndTheirFilesPriority() throws Exception {
        this.settings = new UploadManager.Settings(1, ObfuscationMode.SUPPORTED, 100, Optional.empty());
        SharedFiles.SharedFile slotted = this.share("slotted.txt", random((int) UploadSession.MAX_BLOCK, 3));
        SharedFiles.SharedFile veryLow = this.share("very-low.txt", random(3000, 4));
        SharedFiles.SharedFile normal = this.share("normal.txt", random(3000, 5));
        SharedFiles.SharedFile release = this.share("release.txt", random(3000, 6));
        SharedFiles.SharedFile low = this.share("low.txt", random(3000, 7));
        SharedFiles.SharedFile high = this.share("high.txt", random(3000, 8));
        this.priorities.put(veryLow.hash(), UploadPriority.VERY_LOW);
        this.priorities.put(normal.hash(), UploadPriority.NORMAL);
        this.priorities.put(release.hash(), UploadPriority.RELEASE);
        this.priorities.put(low.hash(), UploadPriority.LOW);
        this.priorities.put(high.hash(), UploadPriority.HIGH);
        this.start();
        // Slots kept busy by the limit, so they are never idle while the clock moves.
        for (int seed = 1; seed <= 2; seed++) {
            ScriptedDownloader slot = this.client(seed, 0, 0);
            this.ask(slot, slotted.hash());
            slot.send(blocks(slotted.hash(), 0, UploadSession.MAX_BLOCK));
        }
        ScriptedDownloader c = this.client(3, 0, 0);
        ScriptedDownloader d = this.client(4, 0, 0);
        ScriptedDownloader e = this.client(5, 0, 0);

        this.ask(c, veryLow.hash());
        this.advance(Duration.ofSeconds(60));
        this.ask(d, normal.hash());
        this.advance(Duration.ofSeconds(60));
        assertThat(rank(this.ask(e, release.hash())))
                .as("it has waited for nothing yet")
                .isEqualTo(3);
        this.advance(Duration.ofMinutes(2));
        // Waited 240 s at 2, 180 s at 7 and 120 s at 18.
        assertThat(this.waitingFiles()).containsExactly("release.txt", "normal.txt", "very-low.txt");

        this.advance(UploadManager.MIN_REQUEST_TIME);
        assertThat(rank(this.ask(e, low.hash())))
                .as("720 s at 6 against 780 s at 7")
                .isEqualTo(2);
        assertThat(rank(this.ask(d, high.hash()))).isEqualTo(1);
        assertThat(this.waitingFiles()).containsExactly("high.txt", "low.txt", "very-low.txt");
    }

    @Test
    void clientsThatStopAskingAreForgottenAndBansRunOut() throws Exception {
        this.settings = new UploadManager.Settings(1, ObfuscationMode.SUPPORTED, 100, Optional.empty());
        SharedFiles.SharedFile file = this.share("kept.txt", random(3000, 9));
        this.start();
        ScriptedDownloader first = this.client(1, 0, 0);
        ScriptedDownloader second = this.client(2, 0, 0);
        this.ask(first, file.hash());
        this.ask(second, file.hash());
        this.ask(this.client(3, 0, 0), file.hash());
        assertThat(this.onLoop(this.manager::waitingCount)).isEqualTo(1);

        this.advance(UploadManager.PURGE_AFTER.plusSeconds(1));
        this.await("the silent client forgotten", () -> this.manager.waitingCount() == 0);

        // A hello again is answered; one with another user hash is an attack, and the address is banned.
        first.send(first.hello().encode(false));
        assertThat(first.until(Opcodes.Client.HELLOANSWER)).isNotNull();
        first.send(this.hello(8, 0, 0).encode(false));
        assertThat(first.closedWithin(WAIT)).isTrue();
        List<BanInfo> bans = this.onLoop(this.manager::bans);
        assertThat(bans).singleElement().satisfies(b -> assertThat(b.address()).isEqualTo("127.0.0.1"));
        assertThat(ScriptedDownloader.connect(this.port, this.hello(10, 0, 0)).closedWithin(WAIT))
                .as("a banned address is turned away")
                .isTrue();
        second.send(ClientMessages.forFile(Opcodes.Client.STARTUPLOADREQ, file.hash()));
        assertThat(second.poll(SILENCE))
                .as("its slot runs to its end, but it asks in vain")
                .isEmpty();

        this.advance(UploadManager.BAN_TIME);
        this.await(
                "the ban over",
                () -> this.manager.bans().isEmpty() && !this.manager.isBanned(InetAddress.getLoopbackAddress()));
        ScriptedDownloader back = this.client(11, 0, 0);
        back.send(ClientMessages.forFile(Opcodes.Client.SETREQFILEID, file.hash()));
        assertThat(back.until(Opcodes.Client.FILESTATUS)).isNotNull();
    }

    @Test
    void aClientThatLeftTheQueueIsCalledForItsSlot() throws Exception {
        this.settings = new UploadManager.Settings(1, ObfuscationMode.SUPPORTED, 100, Optional.empty());
        SharedFiles.SharedFile file = this.share("called.txt", random(3000, 10));
        this.start();
        ScriptedDownloader first = this.client(1, 0, 0);
        ScriptedDownloader second = this.client(2, 0, 0);
        this.ask(first, file.hash());
        this.ask(second, file.hash());

        try (ServerSocket listening = new ServerSocket(0, 4, InetAddress.getLoopbackAddress())) {
            Hello hello = this.hello(3, 0, listening.getLocalPort());
            ScriptedDownloader waiting = this.client(hello);
            assertThat(rank(this.ask(waiting, file.hash()))).isEqualTo(1);
            waiting.close();
            first.close();

            // Another client answers at its address first, and slowly: the call is tried again.
            Thread.sleep(300);
            ScriptedDownloader impostor = ScriptedDownloader.acceptCall(listening, this.hello(7, 0, 0));
            assertThat(impostor.closedWithin(WAIT)).isTrue();
            ScriptedDownloader called = ScriptedDownloader.acceptCall(listening, hello);
            this.resources.add(called);
            assertThat(called.until(Opcodes.Client.ACCEPTUPLOADREQ)).isNotNull();
        }

        // One whose port is closed is dropped from the queue.
        int closed;
        try (ServerSocket probe = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            closed = probe.getLocalPort();
        }
        ScriptedDownloader unreachable = this.client(this.hello(4, 0, closed));
        assertThat(rank(this.ask(unreachable, file.hash()))).isEqualTo(1);
        unreachable.close();
        second.close();
        this.await("the unreachable client dropped", () -> this.manager.waitingCount() == 0);

        // So is one that hangs up before its hello.
        ScriptedDownloader third = this.client(5, 0, 0);
        this.ask(third, file.hash());
        try (ServerSocket listening = new ServerSocket(0, 4, InetAddress.getLoopbackAddress())) {
            ScriptedDownloader rude = this.client(this.hello(6, 0, listening.getLocalPort()));
            assertThat(rank(this.ask(rude, file.hash()))).isEqualTo(1);
            rude.close();
            third.close();
            listening.setSoTimeout((int) WAIT.toMillis());
            listening.accept().close();
            this.await("the client that hung up dropped", () -> this.manager.waitingCount() == 0);
        }
    }

    @Test
    void aDownloadSharesItsCheckedPartsAndItsSources() throws Exception {
        byte[] last = random(1000, 11);
        Hash16 first = Hash16.parseHex("c1".repeat(16));
        List<Hash16> parts = List.of(first, Ed2k.md4(last, 0, last.length));
        Ed2kLink.FileLink link = new Ed2kLink.FileLink(
                "partial.bin",
                Ed2k.PARTSIZE + last.length,
                Ed2k.fileHash(parts),
                Optional.empty(),
                parts,
                List.of(),
                List.of());
        DownloadQueue.Download download = this.downloadLastPart(link, last);
        List<SourceExchange.Source> sources = new ArrayList<>();
        for (int i = 1; i <= 20; i++) {
            sources.add(new SourceExchange.Source(
                    Ipv4.of(198, 51, 100, i), 0, 4662, 0, 0, Optional.of(hashOf(100 + i)), 0));
        }
        this.downloadSources = sources;
        this.start();
        ScriptedDownloader client = this.client(1, 0, 0);

        client.send(ClientMessages.forFile(Opcodes.Client.SETREQFILEID, download.hash()));
        ClientMessages.FileStatus status = ClientMessages.FileStatus.decode(client.until(Opcodes.Client.FILESTATUS));
        assertThat(status.partCount()).isEqualTo(2);
        assertThat(status.has(0)).isFalse();
        assertThat(status.has(1)).isTrue();
        client.send(blocks(download.hash(), Ed2k.PARTSIZE, Ed2k.PARTSIZE + 10));
        client.send(ClientMessages.forFile(Opcodes.Client.STARTUPLOADREQ, download.hash()));
        assertThat(names(client.until(Protocol.EDONKEY, Opcodes.Client.ACCEPTUPLOADREQ.code())))
                .as("no blocks before the slot")
                .doesNotContain("SENDINGPART");
        client.send(blocks(hashOf(78), 0, 10));
        client.send(ClientMessages.forFile(Opcodes.Client.STARTUPLOADREQ, download.hash()));
        assertThat(names(client.until(Protocol.EDONKEY, Opcodes.Client.ACCEPTUPLOADREQ.code())))
                .as("asked again, the slot is given again; nothing of another file")
                .doesNotContain("SENDINGPART");
        client.send(new ClientMessages.RequestParts(
                        download.hash(),
                        List.of(
                                new ClientMessages.Range(0, 1000),
                                new ClientMessages.Range(Ed2k.PARTSIZE, Ed2k.PARTSIZE + 500),
                                new ClientMessages.Range(0, 0)))
                .encode(true));
        ClientMessages.SendingPart sent = ClientMessages.SendingPart.decode(client.until(Opcodes.Client.SENDINGPART));
        assertThat(sent.start()).as("only the checked part").isEqualTo(Ed2k.PARTSIZE);
        assertThat(sent.data()).isEqualTo(Arrays.copyOf(last, 500));

        client.send(SourceExchange.request(download.hash()));
        assertThat(SourceExchange.Answer.decode(client.until(Opcodes.Extended.ANSWERSOURCES2))
                        .sources())
                .as("the download's sources, packed")
                .hasSize(20);
        client.send(SourceExchange.request(download.hash()));
        client.send(Packet.of(
                Protocol.EMULE,
                Opcodes.Extended.REQUESTSOURCES2.code(),
                new PacketWriter().u8(0).u16(0).hash16(download.hash())));
        client.send(SourceExchange.request(hashOf(77)));
        client.send(Packet.empty(Protocol.EMULE, Opcodes.Extended.KAD_FWTCPCHECK_ACK.code()));
        client.send(Packet.empty(Protocol.EMULE, 0x7F));
        client.send(Packet.empty(Protocol.EDONKEY, 0x7F));
        client.send(Packet.of(
                Protocol.EDONKEY,
                Opcodes.Client.ASKSHAREDFILESDIR.code(),
                new PacketWriter().u16(5).bytes("Films".getBytes(StandardCharsets.US_ASCII))));
        Packet listing = client.until(Opcodes.Client.ASKSHAREDFILESDIRANS);
        PacketReader in = listing.reader();
        in.bytes(in.u16());
        assertThat(in.u32()).as("no files in another directory").isZero();
        assertThat(this.reachable).containsExactly(Ipv4.of(127, 0, 0, 1));

        // Asked again at once, or by an old client, or for a file we do not have: no answer.
        client.send(ClientMessages.forFile(Opcodes.Client.REQUESTFILENAME, download.hash()));
        assertThat(names(client.until(Protocol.EDONKEY, Opcodes.Client.REQFILENAMEANSWER.code())))
                .doesNotContain("ANSWERSOURCES2");

        // A request that does not decode ends the connection.
        client.send(Packet.of(Protocol.EDONKEY, Opcodes.Client.REQUESTPARTS.code(), new PacketWriter().u8(1)));
        assertThat(client.closedWithin(WAIT)).isTrue();
        this.advance(Duration.ofMinutes(13));
        this.await("the credits saved", () -> Files.exists(this.dir.resolve(Credits.FILE_NAME)));
    }

    @Test
    void aBlockIsReadWhereItsFileWentOrTheSlotEnds() throws Exception {
        byte[] data = random(4000, 12);
        SharedFiles.SharedFile file = this.share("moved.txt", data);
        Path gone = this.dir.resolve("gone.txt");
        this.shared.put(file.hash(), withPath(file, gone));
        this.start();
        ScriptedDownloader client = this.client(1, 0, 0);
        assertThat(this.ask(client, file.hash()).opcode()).isEqualTo(Opcodes.Client.ACCEPTUPLOADREQ.code());

        this.shared.put(file.hash(), file);
        client.send(blocks(file.hash(), 0, 1000));
        assertThat(ClientMessages.SendingPart.decode(client.until(Opcodes.Client.SENDINGPART))
                        .data())
                .isEqualTo(Arrays.copyOf(data, 1000));

        this.shared.remove(file.hash());
        client.send(blocks(file.hash(), 1000, 2000));
        assertThat(client.closedWithin(WAIT)).isTrue();
        this.await("the slot gone", () -> this.manager.slotCount() == 0);
    }

    @Test
    void aFaultLookingAFileUpEndsOnlyThatClientsSession() throws Exception {
        SharedFiles.SharedFile file = this.share("fine.txt", random(3000, 14));
        this.start();
        ScriptedDownloader unlucky = this.client(1, 0, 0);
        ScriptedDownloader other = this.client(2, 0, 0);

        unlucky.send(ClientMessages.forFile(Opcodes.Client.SETREQFILEID, FAULTY));

        assertThat(unlucky.closedWithin(WAIT)).isTrue();
        assertThat(this.ask(other, file.hash()).opcode()).isEqualTo(Opcodes.Client.ACCEPTUPLOADREQ.code());
    }

    @Test
    void aDownloadOfWholePartsTellsItsEmptyLastPartToo() throws Exception {
        byte[] second = random((int) Ed2k.PARTSIZE, 15);
        List<Hash16> parts =
                List.of(Hash16.parseHex("c2".repeat(16)), Ed2k.md4(second, 0, second.length), Ed2k.EMPTY_PART_HASH);
        Ed2kLink.FileLink link = new Ed2kLink.FileLink(
                "whole.bin", 2 * Ed2k.PARTSIZE, Ed2k.fileHash(parts), Optional.empty(), parts, List.of(), List.of());
        DownloadQueue.Download download = this.downloadLastPart(link, second);
        this.start();
        ScriptedDownloader client = this.client(1, 0, 0);

        client.send(ClientMessages.forFile(Opcodes.Client.SETREQFILEID, download.hash()));

        ClientMessages.FileStatus status = ClientMessages.FileStatus.decode(client.until(Opcodes.Client.FILESTATUS));
        assertThat(status.partCount()).isEqualTo(3);
        assertThat(status.has(0)).isFalse();
        assertThat(status.has(1)).isTrue();
        assertThat(status.has(2))
                .as("the empty part a whole number of parts ends with")
                .isTrue();
    }

    @Test
    void clientsTheIpFilterRefusesAreDropped() throws Exception {
        this.settings = new UploadManager.Settings(1, ObfuscationMode.SUPPORTED, 100, Optional.empty());
        SharedFiles.SharedFile file = this.share("filtered.txt", random(3000, 13));
        this.start();
        ScriptedDownloader first = this.client(1, 0, 0);
        ScriptedDownloader second = this.client(2, 0, 0);
        ScriptedDownloader third = this.client(3, 0, 0);
        this.ask(first, file.hash());
        this.ask(second, file.hash());
        this.ask(third, file.hash());

        assertThat(this.onLoop(() -> this.manager.dropClients(ip -> false))).isZero();
        assertThat(this.onLoop(() -> this.manager.dropClients(InetAddress::isLoopbackAddress)))
                .isEqualTo(3);

        assertThat(first.closedWithin(WAIT)).isTrue();
        assertThat(third.closedWithin(WAIT)).isTrue();
        assertThat(this.onLoop(this.manager::uploads)).isEmpty();
    }

    private DownloadQueue.Download downloadLastPart(Ed2kLink.FileLink link, byte[] last) throws Exception {
        this.downloads = this.onLoop(() -> DownloadQueue.load(
                this.loop,
                this.dir.resolve("temp"),
                part -> this.dir.resolve("incoming"),
                () -> 0,
                new DownloadQueue.Listener() {
                    @Override
                    public void added(DownloadQueue.Download download) {}

                    @Override
                    public void progress(DownloadQueue.Download download) {}

                    @Override
                    public void completed(DownloadQueue.Download download, Path file) {}

                    @Override
                    public void failed(DownloadQueue.Download download, String reason) {}
                }));
        DownloadQueue.Download download = this.onLoop(() -> this.downloads.add(link));
        this.onLoop(() -> {
            this.downloads.received(download, Ed2k.PARTSIZE, last);
            return null;
        });
        this.await("the last part checked", () -> download.verifiedParts().get(1));
        return download;
    }

    private static SharedFiles.SharedFile withPath(SharedFiles.SharedFile file, Path path) {
        return new SharedFiles.SharedFile(
                path, file.name(), file.size(), file.hash(), file.partHashes(), file.aichHash(), file.mtime());
    }

    private static Packet blocks(Hash16 file, long start, long end) {
        return new ClientMessages.RequestParts(
                        file,
                        List.of(
                                new ClientMessages.Range(start, end),
                                new ClientMessages.Range(0, 0),
                                new ClientMessages.Range(0, 0)))
                .encode(false);
    }

    private List<String> waitingFiles() throws Exception {
        return this.onLoop(() -> this.manager.uploads().stream()
                .filter(u -> u.state() == UploadInfo.State.QUEUED)
                .map(UploadInfo::file)
                .toList());
    }

    private SharedFiles.SharedFile share(String name, byte[] data) throws IOException {
        Path path =
                Files.write(Files.createDirectories(this.dir.resolve("shared")).resolve(name), data);
        Hash16 hash = data.length < Ed2k.PARTSIZE ? Ed2k.md4(data, 0, data.length) : hashOf(name.length());
        SharedFiles.SharedFile file =
                new SharedFiles.SharedFile(path, name, data.length, hash, List.of(), Optional.empty(), 0);
        this.shared.put(hash, file);
        return file;
    }

    /** Asks for an upload of {@code file}: the answer is a slot or a place in the queue. */
    private Packet ask(ScriptedDownloader client, Hash16 file) throws Exception {
        client.send(ClientMessages.forFile(Opcodes.Client.SETREQFILEID, file));
        client.until(Opcodes.Client.FILESTATUS);
        client.send(ClientMessages.forFile(Opcodes.Client.STARTUPLOADREQ, file));
        while (true) {
            Packet packet = client.next();
            boolean slot =
                    packet.protocol() == Protocol.EDONKEY && packet.opcode() == Opcodes.Client.ACCEPTUPLOADREQ.code();
            boolean rank =
                    packet.protocol() == Protocol.EMULE && packet.opcode() == Opcodes.Extended.QUEUERANKING.code();
            if (slot || rank) {
                return packet;
            }
        }
    }

    private static int rank(Packet packet) throws Exception {
        assertThat(packet.opcode()).isEqualTo(Opcodes.Extended.QUEUERANKING.code());
        return (int) ClientMessages.decodeQueueRank(packet);
    }

    @Test
    void aClientsUdpTestRequestGoesToKadWithItsAddress() throws Exception {
        this.givenTheUploadsTellKadOfUdpTests();
        this.givenAClientConnected();

        this.whenItAsksForItsUdpTest(new LowIdMessages.UdpCheckRequest(4673, 5000, 99));

        this.thenKadHearsOfTheUdpTest("127.0.0.1 4673 5000 99 false");
    }

    private void givenTheUploadsTellKadOfUdpTests() throws Exception {
        this.start();
        this.onLoop(() -> {
            this.manager.onUdpCheck((ip, request, known) -> this.udpChecks.add(Ipv4.format(ip) + " "
                    + request.internalPort() + " " + request.externalPort() + " " + request.senderKey() + " "
                    + known));
            return null;
        });
    }

    private void givenAClientConnected() throws IOException {
        this.client = this.client(21, 0, 0);
    }

    private void whenItAsksForItsUdpTest(LowIdMessages.UdpCheckRequest request) throws IOException {
        this.client.send(request.encode());
    }

    private void thenKadHearsOfTheUdpTest(String request) throws Exception {
        this.await("the UDP test request", () -> !this.udpChecks.isEmpty());
        assertThat(this.udpChecks).containsExactly(request);
    }

    private ScriptedDownloader client(int seed, long clientId, int listenPort) throws IOException {
        return this.client(this.hello(seed, clientId, listenPort));
    }

    private ScriptedDownloader client(Hello hello) throws IOException {
        ScriptedDownloader client = ScriptedDownloader.connect(this.port, hello);
        this.resources.add(client);
        return client;
    }

    private Hello hello(int seed, long clientId, int listenPort) {
        return Hello.of(
                hashOf(seed), clientId, listenPort == 0 ? 5000 + seed : listenPort, "client " + seed, 0, 0, 0, 0, 0, 0);
    }

    private static Hash16 hashOf(int seed) {
        return Hash16.parseHex(String.format("%02x", seed & 0xFF).repeat(16));
    }

    private void start() throws Exception {
        SecureRandom random = new SecureRandom();
        Identities identities = new Identities(
                IdentityHandshake.Identity.of(Identities.loadOrCreateKey(this.dir, random)),
                hash -> Optional.empty(),
                (hash, key) -> {},
                Optional::empty,
                random);
        Hello ours = Hello.of(OURS, 0, 4662, "test", 0, 0, 0, 0, 0, 0);
        PeerLinks links = new PeerLinks(this.loop, identities, link -> this.manager.attach(link));
        DownloadQueue queue = this.downloads;
        this.manager = new UploadManager(
                this.loop,
                hash -> {
                    if (hash.equals(FAULTY)) {
                        throw new IllegalStateException("cannot look " + hash + " up");
                    }
                    return Optional.ofNullable(this.shared.get(hash));
                },
                hash -> Optional.empty(),
                (hash, proven) -> Optional.of(List.copyOf(this.shared.values())),
                hash -> false,
                hash -> Optional.empty(),
                hash -> queue == null ? Optional.empty() : queue.get(hash),
                hash -> this.downloadSources,
                () -> ours,
                () -> this.settings,
                links,
                Credits.load(this.dir, Clock.systemUTC()),
                this.reachable::add,
                random,
                this.ticker);
        this.manager.priorities(hash -> this.priorities.getOrDefault(hash, UploadPriority.AUTO));
        this.onLoop(() -> {
            this.manager.start();
            return null;
        });
        PeerGreetings greetings = new PeerGreetings(OURS, () -> ours, () -> true, ip -> false, random);
        greetings.routeTo((connection, peer) -> {
            try {
                return Optional.of(this.loop
                        .submit(() -> {
                            PeerLink link = links.incoming(connection, peer);
                            if (this.manager.attach(link).isEmpty()) {
                                link.disconnect();
                            }
                            return link;
                        })
                        .get(10, TimeUnit.SECONDS));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Optional.empty();
            } catch (ExecutionException | TimeoutException e) {
                return Optional.empty();
            }
        });
        TcpListener listener = TcpListener.open(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0),
                new ConnectionGuard(
                        IpFilter::empty,
                        new ConnectionGuard.Limits(500, 100, 1000, Duration.ofSeconds(5)),
                        Ticker.systemTicker()),
                greetings::accepted);
        this.resources.add(greetings);
        this.resources.add(listener);
        this.port = listener.port();
    }

    private void advance(Duration duration) {
        this.now.addAndGet(duration.toNanos());
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

    private static byte[] random(int size, long seed) {
        byte[] data = new byte[size];
        new Random(seed).nextBytes(data);
        return data;
    }
}
