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
import java.util.HashMap;
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
import org.njmule.core.protocol.ed2k.ClientReasks;
import org.njmule.core.protocol.ed2k.Hello;
import org.njmule.core.protocol.ed2k.LowIdMessages;
import org.njmule.core.protocol.ed2k.SourceExchange;
import org.njmule.core.protocol.io.DecodeException;
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
    private static final int MANY_BLOCKS = 16;

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
    private final List<ScriptedDownloader> slotHolders = new ArrayList<>();
    private final List<SharedFiles.SharedFile> others = new ArrayList<>();
    private ScriptedDownloader client;
    private SharedFiles.SharedFile file;
    private SharedFiles.SharedFile other;
    private Hello eager;
    private Optional<UploadManager.ReaskAnswer> reaskAnswer = Optional.empty();
    private Packet answer;
    private long queued;
    private long dataBeforeAnswer;
    private final Map<Integer, ScriptedDownloader> clients = new HashMap<>();
    private final Map<String, SharedFiles.SharedFile> files = new HashMap<>();
    private byte[] data;
    private byte[] lastPart;
    private DownloadQueue.Download download;
    private ServerSocket listening;
    private ClientMessages.FileStatus status;
    private ClientMessages.SendingPart block;
    private List<SourceExchange.Source> toldSources;
    private int dropped;

    @AfterEach
    void stop() throws Exception {
        if (this.manager != null) {
            onLoop(() -> {
                this.manager.close();
                return null;
            });
        }
        if (this.downloads != null) {
            onLoop(() -> {
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
    void shouldGiveASlotBackToTheQueueAfterAboutAPartWhileOthersWait() throws Exception {
        givenTheSettings(new UploadManager.Settings(1, ObfuscationMode.SUPPORTED, 100, Optional.empty()));
        givenTheSharedFile("big.bin", 18 * (int) UploadSession.MAX_BLOCK, 1);
        givenTheUploadsStarted();
        givenTheClientsConnected(1, 2);
        givenAFirewalledClientConnected(3);
        givenTheClientGotASlot(1);
        givenTheClientGotASlot(2);
        givenTheClientWaitsAt(3, 1);
        givenTheQueueDescribedAs("waiting 1", "slot");
        // The firewalled client leaves the queue's connection: we cannot call it, so it waits however many slots
        // a limit lifted gives.
        givenTheClientLeft(3);
        givenTheSettings(new UploadManager.Settings(0, ObfuscationMode.SUPPORTED, 100, Optional.empty()));

        whenTheClientDownloadsTheWholeFile(1);

        thenTheClientIsToldInTurn(
                1,
                "about a part sent, the slot goes to the queue and comes back, as no one else can take it",
                "OUTOFPARTREQS",
                "QUEUERANKING",
                "ACCEPTUPLOADREQ");
        thenTheBytesUploadedAre(18 * UploadSession.MAX_BLOCK);
        thenTheClientsWaitingAre(1);
    }

    @Test
    void shouldLetTheFriendWithTheFriendSlotPassAFullQueue() throws Exception {
        givenTheSettings(new UploadManager.Settings(1, ObfuscationMode.SUPPORTED, 1, Optional.of(hashOf(9))));
        givenTheSharedFile("wanted.txt", 5000, 2);
        givenTheUploadsStarted();
        givenTheClientsConnected(1, 2, 3, 4, 9);
        givenTheClientAsked(1);
        givenTheClientAsked(2);
        givenTheClientWaitsAt(3, 1);
        givenTheClientTurnedAwayByTheFullQueue(4);
        givenTheClientWaitsAt(9, 1, "a friend's place");
        givenASlotHolderToldOfTheOtherClientsOfTheFile(1, 2, 3, 9);

        whenTheClientLeaves(1);

        thenTheClientGetsASlot(9);
        thenTheUploadStatesAre(UploadInfo.State.UPLOADING, UploadInfo.State.UPLOADING, UploadInfo.State.QUEUED);
    }

    @Test
    void shouldRankWaitingClientsByTheirWaitAndTheirFilesPriority() throws Exception {
        givenTheSettings(new UploadManager.Settings(1, ObfuscationMode.SUPPORTED, 100, Optional.empty()));
        givenTheSharedFile("slotted.txt", (int) UploadSession.MAX_BLOCK, 3);
        givenTheSharedFile("very-low.txt", 3000, 4);
        givenTheSharedFile("normal.txt", 3000, 5);
        givenTheSharedFile("release.txt", 3000, 6);
        givenTheSharedFile("low.txt", 3000, 7);
        givenTheSharedFile("high.txt", 3000, 8);
        givenThePriorityOf("very-low.txt", UploadPriority.VERY_LOW);
        givenThePriorityOf("normal.txt", UploadPriority.NORMAL);
        givenThePriorityOf("release.txt", UploadPriority.RELEASE);
        givenThePriorityOf("low.txt", UploadPriority.LOW);
        givenThePriorityOf("high.txt", UploadPriority.HIGH);
        givenTheUploadsStarted();
        givenAClientKeepingASlotBusyWith(1, "slotted.txt");
        givenAClientKeepingASlotBusyWith(2, "slotted.txt");
        givenTheClientsConnected(3, 4, 5);
        givenTheClientAskedFor(3, "very-low.txt");
        givenTimePassed(Duration.ofSeconds(60));
        givenTheClientAskedFor(4, "normal.txt");
        givenTimePassed(Duration.ofSeconds(60));
        givenTheClientWaitsForAt(5, "release.txt", 3, "it has waited for nothing yet");
        givenTimePassed(Duration.ofMinutes(2));
        // Waited 240 s at 2, 180 s at 7 and 120 s at 18.
        givenTheFilesWaitedForAre("release.txt", "normal.txt", "very-low.txt");
        givenTimePassed(UploadManager.MIN_REQUEST_TIME);
        givenTheClientWaitsForAt(5, "low.txt", 2, "720 s at 6 against 780 s at 7");

        whenTheClientAsksFor(4, "high.txt");

        thenItWaitsAt(1);
        thenTheFilesWaitedForAre("high.txt", "low.txt", "very-low.txt");
    }

    @Test
    void shouldForgetClientsThatStopAsking() throws Exception {
        givenTheSettings(new UploadManager.Settings(1, ObfuscationMode.SUPPORTED, 100, Optional.empty()));
        givenTheSharedFile("kept.txt", 3000, 9);
        givenTheUploadsStarted();
        givenTheClientsConnected(1, 2);
        givenTheClientAsked(1);
        givenTheClientAsked(2);
        givenTheClientAsked(3);
        givenTheClientsWaitingAre(1);

        whenTimePasses(UploadManager.PURGE_AFTER.plusSeconds(1));

        thenTheQueueEmpties("the silent client forgotten");
    }

    @Test
    void shouldLetBansRunOut() throws Exception {
        givenTheSettings(new UploadManager.Settings(1, ObfuscationMode.SUPPORTED, 100, Optional.empty()));
        givenTheSharedFile("kept.txt", 3000, 9);
        givenTheUploadsStarted();
        givenTheClientsConnected(1, 2);
        givenTheClientAsked(1);
        givenTheClientAsked(2);
        givenTheClientAsked(3);
        givenTheClientsWaitingAre(1);
        givenTimePassed(UploadManager.PURGE_AFTER.plusSeconds(1));
        givenTheQueueEmptied("the silent client forgotten");
        // A hello again is answered; one with another user hash is an attack, and the address is banned.
        givenTheClientAnsweredOnAHelloAgain(1);
        givenTheClientCutOffOnAHelloWithTheUserHashOf(1, 8);
        givenTheOnlyBanIsOf("127.0.0.1");
        givenANewClientFromTheBannedAddressTurnedAway(10);
        givenTheClientAskingInVain(2);

        whenTimePasses(UploadManager.BAN_TIME);

        thenTheBanIsOver();
        thenANewClientIsToldTheFileStatus(11);
    }

    @Test
    void shouldCallAClientThatLeftTheQueueForItsSlot() throws Exception {
        givenTheSettings(new UploadManager.Settings(1, ObfuscationMode.SUPPORTED, 100, Optional.empty()));
        givenTheSharedFile("called.txt", 3000, 10);
        givenTheUploadsStarted();
        givenTheClientsConnected(1, 2);
        givenTheClientAsked(1);
        givenTheClientAsked(2);
        givenAClientListeningForCallsWaitingAt(3, 1);
        givenTheClientLeft(3);

        whenTheClientLeaves(1);

        thenAnImpostorAnsweringTheCallIsCutOff(7);
        thenTheClientAnsweringTheCallGetsASlot(3);
    }

    @Test
    void shouldDropFromTheQueueAClientWhosePortIsClosed() throws Exception {
        givenTheSettings(new UploadManager.Settings(1, ObfuscationMode.SUPPORTED, 100, Optional.empty()));
        givenTheSharedFile("called.txt", 3000, 10);
        givenTheUploadsStarted();
        givenTheClientsConnected(1, 2);
        givenTheClientAsked(1);
        givenTheClientAsked(2);
        givenAClientListeningForCallsWaitingAt(3, 1);
        givenTheClientLeft(3);
        givenTheClientLeft(1);
        givenAnImpostorAnsweringTheCallCutOff(7);
        givenTheClientAnsweringTheCallGotASlot(3);
        givenAClientThatCannotBeCalledWaitingAt(4, 1);
        givenTheClientLeft(4);

        whenTheClientLeaves(2);

        thenTheQueueEmpties("the unreachable client dropped");
    }

    @Test
    void shouldDropFromTheQueueAClientThatHangsUpBeforeItsHello() throws Exception {
        givenTheSettings(new UploadManager.Settings(1, ObfuscationMode.SUPPORTED, 100, Optional.empty()));
        givenTheSharedFile("called.txt", 3000, 10);
        givenTheUploadsStarted();
        givenTheClientsConnected(1, 2);
        givenTheClientAsked(1);
        givenTheClientAsked(2);
        givenAClientListeningForCallsWaitingAt(3, 1);
        givenTheClientLeft(3);
        givenTheClientLeft(1);
        givenAnImpostorAnsweringTheCallCutOff(7);
        givenTheClientAnsweringTheCallGotASlot(3);
        givenAClientThatCannotBeCalledWaitingAt(4, 1);
        givenTheClientLeft(4);
        givenTheClientLeft(2);
        givenTheQueueEmptied("the unreachable client dropped");
        givenTheClientAsked(5);
        givenAClientListeningForCallsWaitingAt(6, 1);
        givenTheClientLeft(6);
        givenTheClientLeft(5);

        whenTheCalledClientHangsUpBeforeItsHello();

        thenTheQueueEmpties("the client that hung up dropped");
    }

    @Test
    void shouldShareOnlyTheCheckedPartsOfADownload() throws Exception {
        givenADownloadWithOnlyItsLastPartChecked("partial.bin", 1000, 11);
        givenTheDownloadHasSources(20);
        givenTheUploadsStarted();
        givenAClientConnected(1);
        givenTheClientToldTheDownloadHasOnlyItsLastPart();
        givenNoBlocksSentBeforeTheSlot();
        givenTheSlotGivenAgainWithNothingOfAnotherFile();

        whenTheClientAsksForBlocksOfBothParts();

        thenTheBlockSentStartsAt(Ed2k.PARTSIZE, "only the checked part");
        thenTheBlockSentIsTheStartOfTheLastPart(500);
    }

    @Test
    void shouldShareTheSourcesOfADownload() throws Exception {
        givenADownloadWithOnlyItsLastPartChecked("partial.bin", 1000, 11);
        givenTheDownloadHasSources(20);
        givenTheUploadsStarted();
        givenAClientConnected(1);
        givenTheClientToldTheDownloadHasOnlyItsLastPart();
        givenNoBlocksSentBeforeTheSlot();
        givenTheSlotGivenAgainWithNothingOfAnotherFile();
        givenOnlyTheCheckedPartSent();

        whenTheClientAsksForTheSourcesOfTheDownload();

        thenTheSourcesToldAre(20, "the download's sources, packed");
    }

    @Test
    void shouldEndTheConnectionOnARequestThatDoesNotDecodeAndSaveTheCredits() throws Exception {
        givenADownloadWithOnlyItsLastPartChecked("partial.bin", 1000, 11);
        givenTheDownloadHasSources(20);
        givenTheUploadsStarted();
        givenAClientConnected(1);
        givenTheClientToldTheDownloadHasOnlyItsLastPart();
        givenNoBlocksSentBeforeTheSlot();
        givenTheSlotGivenAgainWithNothingOfAnotherFile();
        givenOnlyTheCheckedPartSent();
        givenTheSourcesOfTheDownloadTold(20);
        givenTheSourcesAskedAgainAtOnceByAnOldClientAndForAFileWeDoNotHave();
        givenTheClientConfirmedItReachedUs();
        givenPacketsOfAnUnknownOpcode(0x7F);
        givenTheSharedDirectoryListedEmpty("Films");
        givenKadToldOfTheAddressThatReachedUs("127.0.0.1");
        // Asked again at once, or by an old client, or for a file we do not have: no answer.
        givenNoSourcesToldWhenTheClientAsksForTheFileName();

        whenTheClientSendsARequestThatDoesNotDecode();

        thenTheConnectionEnds();
        thenTheCreditsAreSavedWithin(Duration.ofMinutes(13));
    }

    @Test
    void shouldReadABlockWhereItsFileWent() throws Exception {
        givenTheSharedFile("moved.txt", 4000, 12);
        givenTheFileListedAt("gone.txt");
        givenTheUploadsStarted();
        givenAClientConnected(1);
        givenTheClientGotASlot(1);
        givenTheFileFoundWhereItWent();

        whenTheClientAsksForTheBlock(0, 1000);

        thenTheClientReceivesTheBlock(0, 1000);
    }

    @Test
    void shouldEndTheSlotOnceItsFileIsGone() throws Exception {
        givenTheSharedFile("moved.txt", 4000, 12);
        givenTheFileListedAt("gone.txt");
        givenTheUploadsStarted();
        givenAClientConnected(1);
        givenTheClientGotASlot(1);
        givenTheFileFoundWhereItWent();
        givenTheClientReceivedTheBlock(0, 1000);
        givenTheFileNoLongerShared();

        whenTheClientAsksForTheBlock(1000, 2000);

        thenTheConnectionEnds();
        thenTheSlotIsGone();
    }

    @Test
    void shouldEndOnlyTheSessionOfTheClientWhoseFileLookUpFaults() throws Exception {
        givenTheSharedFile("fine.txt", 3000, 14);
        givenTheUploadsStarted();
        givenTheClientsConnected(1, 2);

        whenTheClientAsksForAFileWhoseLookUpFaults(1);

        thenTheClientIsCutOff(1);
        thenTheClientStillGetsASlot(2);
    }

    @Test
    void shouldTellTheEmptyLastPartOfADownloadOfWholePartsToo() throws Exception {
        givenADownloadOfWholePartsWithOnlyItsSecondChecked("whole.bin", 15);
        givenTheUploadsStarted();
        givenAClientConnected(1);

        whenTheClientAsksForTheDownload();

        thenTheStatusTellsParts(3);
        thenTheStatusLacksPart(0);
        thenTheStatusHasPart(1);
        thenTheStatusHasPart(2, "the empty part a whole number of parts ends with");
    }

    @Test
    void shouldDropTheClientsTheIpFilterRefuses() throws Exception {
        givenTheSettings(new UploadManager.Settings(1, ObfuscationMode.SUPPORTED, 100, Optional.empty()));
        givenTheSharedFile("filtered.txt", 3000, 13);
        givenTheUploadsStarted();
        givenTheClientsConnected(1, 2, 3);
        givenTheClientAsked(1);
        givenTheClientAsked(2);
        givenTheClientAsked(3);
        givenNoClientDroppedByAFilterThatRefusesNone();

        whenTheClientsAtTheLoopbackAddressAreDropped();

        thenTheClientsDroppedAre(3);
        thenTheClientIsCutOff(1);
        thenTheClientIsCutOff(3);
        thenNoUploadIsLeft();
    }

    private void givenTheSettings(UploadManager.Settings chosen) {
        this.settings = chosen;
    }

    private void givenTheSharedFile(String name, int size, long seed) throws IOException {
        this.data = random(size, seed);
        this.file = share(name, this.data);
        this.files.put(name, this.file);
    }

    private void givenThePriorityOf(String name, UploadPriority priority) {
        this.priorities.put(this.files.get(name).hash(), priority);
    }

    private void givenTheFileListedAt(String name) {
        this.shared.put(this.file.hash(), withPath(this.file, this.dir.resolve(name)));
    }

    private void givenTheFileFoundWhereItWent() {
        this.shared.put(this.file.hash(), this.file);
    }

    private void givenTheFileNoLongerShared() {
        this.shared.remove(this.file.hash());
    }

    private void givenADownloadWithOnlyItsLastPartChecked(String name, int lastSize, long seed) throws Exception {
        this.lastPart = random(lastSize, seed);
        List<Hash16> parts =
                List.of(Hash16.parseHex("c1".repeat(16)), Ed2k.md4(this.lastPart, 0, this.lastPart.length));
        Ed2kLink.FileLink link = new Ed2kLink.FileLink(
                name,
                Ed2k.PARTSIZE + this.lastPart.length,
                Ed2k.fileHash(parts),
                Optional.empty(),
                parts,
                List.of(),
                List.of());
        this.download = downloadLastPart(link, this.lastPart);
    }

    private void givenADownloadOfWholePartsWithOnlyItsSecondChecked(String name, long seed) throws Exception {
        byte[] second = random((int) Ed2k.PARTSIZE, seed);
        List<Hash16> parts =
                List.of(Hash16.parseHex("c2".repeat(16)), Ed2k.md4(second, 0, second.length), Ed2k.EMPTY_PART_HASH);
        Ed2kLink.FileLink link = new Ed2kLink.FileLink(
                name, 2 * Ed2k.PARTSIZE, Ed2k.fileHash(parts), Optional.empty(), parts, List.of(), List.of());
        this.download = downloadLastPart(link, second);
    }

    private void givenTheDownloadHasSources(int count) {
        List<SourceExchange.Source> sources = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            sources.add(new SourceExchange.Source(
                    Ipv4.of(198, 51, 100, i), 0, 4662, 0, 0, Optional.of(hashOf(100 + i)), 0));
        }
        this.downloadSources = sources;
    }

    private void givenTheUploadsStarted() throws Exception {
        start();
    }

    private void givenTheClientsConnected(int... seeds) throws IOException {
        for (int seed : seeds) {
            clientOf(seed);
        }
    }

    private void givenAClientConnected(int seed) throws IOException {
        this.client = clientOf(seed);
    }

    private void givenAFirewalledClientConnected(int seed) throws IOException {
        this.clients.put(seed, client(hello(seed, LOW_ID, 0)));
    }

    private void givenAClientListeningForCallsWaitingAt(int seed, int place) throws Exception {
        this.listening = new ServerSocket(0, 4, InetAddress.getLoopbackAddress());
        this.resources.add(this.listening);
        this.clients.put(seed, client(hello(seed, 0, this.listening.getLocalPort())));
        givenTheClientWaitsAt(seed, place);
    }

    private void givenAClientThatCannotBeCalledWaitingAt(int seed, int place) throws Exception {
        int closed;
        try (ServerSocket probe = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            closed = probe.getLocalPort();
        }
        this.clients.put(seed, client(hello(seed, 0, closed)));
        givenTheClientWaitsAt(seed, place);
    }

    private void givenTheClientAsked(int seed) throws Exception {
        ask(clientOf(seed), this.file.hash());
    }

    private void givenTheClientAskedFor(int seed, String name) throws Exception {
        ask(clientOf(seed), this.files.get(name).hash());
    }

    private void givenTheClientGotASlot(int seed) throws Exception {
        assertThat(ask(clientOf(seed), this.file.hash()).opcode()).isEqualTo(Opcodes.Client.ACCEPTUPLOADREQ.code());
    }

    private void givenTheClientWaitsAt(int seed, int place) throws Exception {
        assertThat(rank(ask(clientOf(seed), this.file.hash()))).isEqualTo(place);
    }

    private void givenTheClientWaitsAt(int seed, int place, String why) throws Exception {
        assertThat(rank(ask(clientOf(seed), this.file.hash()))).as(why).isEqualTo(place);
    }

    private void givenTheClientWaitsForAt(int seed, String name, int place, String why) throws Exception {
        assertThat(rank(ask(clientOf(seed), this.files.get(name).hash())))
                .as(why)
                .isEqualTo(place);
    }

    // Slots kept busy by the limit, so they are never idle while the clock moves.
    private void givenAClientKeepingASlotBusyWith(int seed, String name) throws Exception {
        Hash16 hash = this.files.get(name).hash();
        ScriptedDownloader holder = clientOf(seed);
        ask(holder, hash);
        holder.send(blocks(hash, 0, UploadSession.MAX_BLOCK));
    }

    private void givenTheClientTurnedAwayByTheFullQueue(int seed) throws IOException {
        ScriptedDownloader late = clientOf(seed);
        late.send(ClientMessages.forFile(Opcodes.Client.STARTUPLOADREQ, this.file.hash()));
        assertThat(late.poll(SILENCE)).as("the queue is full").isEmpty();
    }

    // A slot holder asks who else has the file: the other clients of it, not itself.
    private void givenASlotHolderToldOfTheOtherClientsOfTheFile(int holder, int... others) throws Exception {
        ScriptedDownloader asking = clientOf(holder);
        asking.send(SourceExchange.request(this.file.hash()));
        SourceExchange.Answer told = SourceExchange.Answer.decode(asking.until(Opcodes.Extended.ANSWERSOURCES2));
        List<Integer> ports = new ArrayList<>();
        for (int seed : others) {
            ports.add(this.clients.get(seed).hello().tcpPort());
        }
        assertThat(told.sources()).extracting(SourceExchange.Source::port).containsExactlyInAnyOrderElementsOf(ports);
    }

    private void givenTheQueueDescribedAs(String start, String part) throws Exception {
        assertThat(onLoop(this.manager::describe)).startsWith(start).contains(part);
    }

    private void givenTheClientsWaitingAre(int count) throws Exception {
        assertThat(onLoop(this.manager::waitingCount)).isEqualTo(count);
    }

    private void givenTheFilesWaitedForAre(String... names) throws Exception {
        assertThat(waitingFiles()).containsExactly(names);
    }

    private void givenTheClientLeft(int seed) throws IOException {
        this.clients.get(seed).close();
    }

    private void givenTimePassed(Duration duration) {
        advance(duration);
    }

    private void givenTheQueueEmptied(String what) throws Exception {
        thenTheQueueEmpties(what);
    }

    private void givenTheClientAnsweredOnAHelloAgain(int seed) throws IOException {
        ScriptedDownloader known = this.clients.get(seed);
        known.send(known.hello().encode(false));
        assertThat(known.until(Opcodes.Client.HELLOANSWER)).isNotNull();
    }

    private void givenTheClientCutOffOnAHelloWithTheUserHashOf(int seed, int other) throws IOException {
        ScriptedDownloader known = this.clients.get(seed);
        known.send(hello(other, 0, 0).encode(false));
        assertThat(known.closedWithin(WAIT)).isTrue();
    }

    private void givenTheOnlyBanIsOf(String address) throws Exception {
        List<BanInfo> bans = onLoop(this.manager::bans);
        assertThat(bans).singleElement().satisfies(b -> assertThat(b.address()).isEqualTo(address));
    }

    private void givenANewClientFromTheBannedAddressTurnedAway(int seed) throws IOException {
        assertThat(client(hello(seed, 0, 0)).closedWithin(WAIT))
                .as("a banned address is turned away")
                .isTrue();
    }

    private void givenTheClientAskingInVain(int seed) throws IOException {
        ScriptedDownloader holder = this.clients.get(seed);
        holder.send(ClientMessages.forFile(Opcodes.Client.STARTUPLOADREQ, this.file.hash()));
        assertThat(holder.poll(SILENCE))
                .as("its slot runs to its end, but it asks in vain")
                .isEmpty();
    }

    private void givenAnImpostorAnsweringTheCallCutOff(int seed) throws Exception {
        thenAnImpostorAnsweringTheCallIsCutOff(seed);
    }

    private void givenTheClientAnsweringTheCallGotASlot(int seed) throws IOException {
        thenTheClientAnsweringTheCallGetsASlot(seed);
    }

    private void givenTheClientToldTheDownloadHasOnlyItsLastPart() throws Exception {
        whenTheClientAsksForTheDownload();
        thenTheStatusTellsParts(2);
        thenTheStatusLacksPart(0);
        thenTheStatusHasPart(1);
    }

    private void givenNoBlocksSentBeforeTheSlot() throws IOException {
        this.client.send(blocks(this.download.hash(), Ed2k.PARTSIZE, Ed2k.PARTSIZE + 10));
        this.client.send(ClientMessages.forFile(Opcodes.Client.STARTUPLOADREQ, this.download.hash()));
        assertThat(names(this.client.until(Protocol.EDONKEY, Opcodes.Client.ACCEPTUPLOADREQ.code())))
                .as("no blocks before the slot")
                .doesNotContain("SENDINGPART");
    }

    private void givenTheSlotGivenAgainWithNothingOfAnotherFile() throws IOException {
        this.client.send(blocks(hashOf(78), 0, 10));
        this.client.send(ClientMessages.forFile(Opcodes.Client.STARTUPLOADREQ, this.download.hash()));
        assertThat(names(this.client.until(Protocol.EDONKEY, Opcodes.Client.ACCEPTUPLOADREQ.code())))
                .as("asked again, the slot is given again; nothing of another file")
                .doesNotContain("SENDINGPART");
    }

    private void givenOnlyTheCheckedPartSent() throws Exception {
        whenTheClientAsksForBlocksOfBothParts();
        thenTheBlockSentStartsAt(Ed2k.PARTSIZE, "only the checked part");
        thenTheBlockSentIsTheStartOfTheLastPart(500);
    }

    private void givenTheSourcesOfTheDownloadTold(int count) throws Exception {
        whenTheClientAsksForTheSourcesOfTheDownload();
        thenTheSourcesToldAre(count, "the download's sources, packed");
    }

    private void givenTheSourcesAskedAgainAtOnceByAnOldClientAndForAFileWeDoNotHave() throws IOException {
        this.client.send(SourceExchange.request(this.download.hash()));
        this.client.send(Packet.of(
                Protocol.EMULE,
                Opcodes.Extended.REQUESTSOURCES2.code(),
                new PacketWriter().u8(0).u16(0).hash16(this.download.hash())));
        this.client.send(SourceExchange.request(hashOf(77)));
    }

    private void givenTheClientConfirmedItReachedUs() throws IOException {
        this.client.send(Packet.empty(Protocol.EMULE, Opcodes.Extended.KAD_FWTCPCHECK_ACK.code()));
    }

    private void givenPacketsOfAnUnknownOpcode(int opcode) throws IOException {
        this.client.send(Packet.empty(Protocol.EMULE, opcode));
        this.client.send(Packet.empty(Protocol.EDONKEY, opcode));
    }

    private void givenTheSharedDirectoryListedEmpty(String directory) throws Exception {
        this.client.send(Packet.of(
                Protocol.EDONKEY,
                Opcodes.Client.ASKSHAREDFILESDIR.code(),
                new PacketWriter().u16(directory.length()).bytes(directory.getBytes(StandardCharsets.US_ASCII))));
        Packet listing = this.client.until(Opcodes.Client.ASKSHAREDFILESDIRANS);
        PacketReader in = listing.reader();
        in.bytes(in.u16());
        assertThat(in.u32()).as("no files in another directory").isZero();
    }

    private void givenKadToldOfTheAddressThatReachedUs(String address) {
        assertThat(this.reachable).containsExactly(Ipv4.parse(address));
    }

    private void givenNoSourcesToldWhenTheClientAsksForTheFileName() throws IOException {
        this.client.send(ClientMessages.forFile(Opcodes.Client.REQUESTFILENAME, this.download.hash()));
        assertThat(names(this.client.until(Protocol.EDONKEY, Opcodes.Client.REQFILENAMEANSWER.code())))
                .doesNotContain("ANSWERSOURCES2");
    }

    private void givenTheClientReceivedTheBlock(int start, int end) throws Exception {
        whenTheClientAsksForTheBlock(start, end);
        thenTheClientReceivesTheBlock(start, end);
    }

    private void givenNoClientDroppedByAFilterThatRefusesNone() throws Exception {
        assertThat(onLoop(() -> this.manager.dropClients(ip -> false))).isZero();
    }

    private void whenTheClientDownloadsTheWholeFile(int seed) throws Exception {
        ScriptedDownloader downloader = this.clients.get(seed);
        long count = this.file.size() / UploadSession.MAX_BLOCK;
        for (long at = 0; at < count; at += 3) {
            List<ClientMessages.Range> ranges = new ArrayList<>();
            for (long b = at; b < at + 3; b++) {
                ranges.add(new ClientMessages.Range(b * UploadSession.MAX_BLOCK, (b + 1) * UploadSession.MAX_BLOCK));
            }
            downloader.send(new ClientMessages.RequestParts(this.file.hash(), ranges).encode(false));
        }
        long received = 0;
        while (received < this.file.size()) {
            Packet packet = downloader.next();
            if (packet.opcode() == Opcodes.Client.SENDINGPART.code()) {
                received += ClientMessages.SendingPart.decode(packet).data().length;
            }
        }
    }

    private void whenTheClientLeaves(int seed) throws IOException {
        this.clients.get(seed).close();
    }

    private void whenTheClientAsksFor(int seed, String name) throws Exception {
        this.answer = ask(clientOf(seed), this.files.get(name).hash());
    }

    private void whenTimePasses(Duration duration) {
        advance(duration);
    }

    private void whenTheCalledClientHangsUpBeforeItsHello() throws IOException {
        this.listening.setSoTimeout((int) WAIT.toMillis());
        this.listening.accept().close();
    }

    private void whenTheClientAsksForTheDownload() throws Exception {
        this.client.send(ClientMessages.forFile(Opcodes.Client.SETREQFILEID, this.download.hash()));
        this.status = ClientMessages.FileStatus.decode(this.client.until(Opcodes.Client.FILESTATUS));
    }

    private void whenTheClientAsksForBlocksOfBothParts() throws Exception {
        this.client.send(new ClientMessages.RequestParts(
                        this.download.hash(),
                        List.of(
                                new ClientMessages.Range(0, 1000),
                                new ClientMessages.Range(Ed2k.PARTSIZE, Ed2k.PARTSIZE + 500),
                                new ClientMessages.Range(0, 0)))
                .encode(true));
        this.block = ClientMessages.SendingPart.decode(this.client.until(Opcodes.Client.SENDINGPART));
    }

    private void whenTheClientAsksForTheSourcesOfTheDownload() throws Exception {
        this.client.send(SourceExchange.request(this.download.hash()));
        this.toldSources = SourceExchange.Answer.decode(this.client.until(Opcodes.Extended.ANSWERSOURCES2))
                .sources();
    }

    private void whenTheClientSendsARequestThatDoesNotDecode() throws IOException {
        this.client.send(Packet.of(Protocol.EDONKEY, Opcodes.Client.REQUESTPARTS.code(), new PacketWriter().u8(1)));
    }

    private void whenTheClientAsksForTheBlock(int start, int end) throws IOException {
        this.client.send(blocks(this.file.hash(), start, end));
    }

    private void whenTheClientAsksForAFileWhoseLookUpFaults(int seed) throws IOException {
        this.clients.get(seed).send(ClientMessages.forFile(Opcodes.Client.SETREQFILEID, FAULTY));
    }

    private void whenTheClientsAtTheLoopbackAddressAreDropped() throws Exception {
        this.dropped = onLoop(() -> this.manager.dropClients(InetAddress::isLoopbackAddress));
    }

    private void thenTheClientIsToldInTurn(int seed, String why, String... told) throws IOException {
        assertThat(names(this.clients.get(seed).until(Protocol.EDONKEY, Opcodes.Client.ACCEPTUPLOADREQ.code())))
                .as(why)
                .containsSubsequence(told);
    }

    private void thenTheBytesUploadedAre(long expected) throws Exception {
        assertThat(onLoop(this.manager::uploaded)).isEqualTo(expected);
    }

    private void thenTheClientsWaitingAre(int count) throws Exception {
        assertThat(onLoop(this.manager::waitingCount)).isEqualTo(count);
    }

    private void thenTheClientGetsASlot(int seed) throws IOException {
        assertThat(this.clients.get(seed).until(Opcodes.Client.ACCEPTUPLOADREQ)).isNotNull();
    }

    private void thenTheUploadStatesAre(UploadInfo.State... states) throws Exception {
        assertThat(onLoop(this.manager::uploads)).extracting(UploadInfo::state).containsExactly(states);
    }

    private void thenItWaitsAt(int place) throws Exception {
        assertThat(rank(this.answer)).isEqualTo(place);
    }

    private void thenTheFilesWaitedForAre(String... names) throws Exception {
        assertThat(waitingFiles()).containsExactly(names);
    }

    private void thenTheQueueEmpties(String what) throws Exception {
        await(what, () -> this.manager.waitingCount() == 0);
    }

    private void thenTheBanIsOver() throws Exception {
        await(
                "the ban over",
                () -> this.manager.bans().isEmpty() && !this.manager.isBanned(InetAddress.getLoopbackAddress()));
    }

    private void thenANewClientIsToldTheFileStatus(int seed) throws IOException {
        ScriptedDownloader back = clientOf(seed);
        back.send(ClientMessages.forFile(Opcodes.Client.SETREQFILEID, this.file.hash()));
        assertThat(back.until(Opcodes.Client.FILESTATUS)).isNotNull();
    }

    // Another client answers at its address first, and slowly: the call is tried again.
    private void thenAnImpostorAnsweringTheCallIsCutOff(int seed) throws Exception {
        Thread.sleep(300);
        ScriptedDownloader impostor = ScriptedDownloader.acceptCall(this.listening, hello(seed, 0, 0));
        this.resources.add(impostor);
        assertThat(impostor.closedWithin(WAIT)).isTrue();
    }

    private void thenTheClientAnsweringTheCallGetsASlot(int seed) throws IOException {
        ScriptedDownloader called = ScriptedDownloader.acceptCall(
                this.listening, this.clients.get(seed).hello());
        this.resources.add(called);
        assertThat(called.until(Opcodes.Client.ACCEPTUPLOADREQ)).isNotNull();
    }

    private void thenTheStatusTellsParts(int count) {
        assertThat(this.status.partCount()).isEqualTo(count);
    }

    private void thenTheStatusHasPart(int part) {
        assertThat(this.status.has(part)).isTrue();
    }

    private void thenTheStatusHasPart(int part, String why) {
        assertThat(this.status.has(part)).as(why).isTrue();
    }

    private void thenTheStatusLacksPart(int part) {
        assertThat(this.status.has(part)).isFalse();
    }

    private void thenTheBlockSentStartsAt(long start, String why) {
        assertThat(this.block.start()).as(why).isEqualTo(start);
    }

    private void thenTheBlockSentIsTheStartOfTheLastPart(int length) {
        assertThat(this.block.data()).isEqualTo(Arrays.copyOf(this.lastPart, length));
    }

    private void thenTheSourcesToldAre(int count, String why) {
        assertThat(this.toldSources).as(why).hasSize(count);
    }

    private void thenTheConnectionEnds() throws IOException {
        assertThat(this.client.closedWithin(WAIT)).isTrue();
    }

    private void thenTheCreditsAreSavedWithin(Duration duration) throws Exception {
        advance(duration);
        await("the credits saved", () -> Files.exists(this.dir.resolve(Credits.FILE_NAME)));
    }

    private void thenTheClientReceivesTheBlock(int start, int end) throws Exception {
        assertThat(ClientMessages.SendingPart.decode(this.client.until(Opcodes.Client.SENDINGPART))
                        .data())
                .isEqualTo(Arrays.copyOfRange(this.data, start, end));
    }

    private void thenTheSlotIsGone() throws Exception {
        await("the slot gone", () -> this.manager.slotCount() == 0);
    }

    private void thenTheClientIsCutOff(int seed) throws IOException {
        assertThat(this.clients.get(seed).closedWithin(WAIT)).isTrue();
    }

    private void thenTheClientStillGetsASlot(int seed) throws Exception {
        assertThat(ask(this.clients.get(seed), this.file.hash()).opcode())
                .isEqualTo(Opcodes.Client.ACCEPTUPLOADREQ.code());
    }

    private void thenTheClientsDroppedAre(int count) {
        assertThat(this.dropped).isEqualTo(count);
    }

    private void thenNoUploadIsLeft() throws Exception {
        assertThat(onLoop(this.manager::uploads)).isEmpty();
    }

    /** The client of {@code seed}, connected on first use. */
    private ScriptedDownloader clientOf(int seed) throws IOException {
        ScriptedDownloader known = this.clients.get(seed);
        if (known == null) {
            known = client(seed, 0, 0);
            this.clients.put(seed, known);
        }
        return known;
    }

    private DownloadQueue.Download downloadLastPart(Ed2kLink.FileLink link, byte[] last) throws Exception {
        DownloadQueue.Download download = addDownload(link);
        onLoop(() -> {
            this.downloads.received(download, Ed2k.PARTSIZE, last);
            return null;
        });
        await("the last part checked", () -> download.verifiedParts().get(1));
        return download;
    }

    private DownloadQueue.Download addDownload(Ed2kLink.FileLink link) throws Exception {
        this.downloads = onLoop(() -> DownloadQueue.load(
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
        return onLoop(() -> this.downloads.add(link));
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
        return onLoop(() -> this.manager.uploads().stream()
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
    void shouldTellAClientWaitingForTheFileItsPlaceOverUdp() throws Exception {
        givenTwoSlotsBusyUnderALimit();
        givenAClientWaitingThatTakesUdpReasksOnPort(4672);

        whenAReaskForTheFileComesFrom(InetAddress.getLoopbackAddress(), 4672);

        thenTheAnswerTellsThePlace(1);
    }

    @Test
    void shouldAnswerNothingToAReaskFromAnotherUdpPortOfTheAddress() throws Exception {
        givenTwoSlotsBusyUnderALimit();
        givenAClientWaitingThatTakesUdpReasksOnPort(4672);

        whenAReaskForTheFileComesFrom(InetAddress.getLoopbackAddress(), 4673);

        thenThereIsNoAnswer();
    }

    @Test
    void shouldTellAnyoneOverUdpThatWeDoNotShareTheFile() throws Exception {
        givenTwoSlotsBusyUnderALimit();

        whenAReaskForTheFileComesFrom(InetAddress.getLoopbackAddress(), 4672, Hash16.parseHex("9d".repeat(16)));

        thenTheAnswerIsPlain(ClientReasks.fileNotFound());
    }

    @Test
    void shouldPassAClientsUdpTestRequestToKadWithItsAddress() throws Exception {
        givenTheUploadsTellKadOfUdpTests();
        givenAClientConnected();

        whenItAsksForItsUdpTest(new LowIdMessages.UdpCheckRequest(4673, 5000, 99));

        thenKadHearsOfTheUdpTest("127.0.0.1 4673 5000 99 false");
    }

    private void givenTheUploadsTellKadOfUdpTests() throws Exception {
        start();
        onLoop(() -> {
            this.manager.onUdpCheck((ip, request, known) -> this.udpChecks.add(Ipv4.format(ip) + " "
                    + request.internalPort() + " " + request.externalPort() + " " + request.senderKey() + " "
                    + known));
            return null;
        });
    }

    private void givenAClientConnected() throws IOException {
        this.client = client(21, 0, 0);
    }

    private void whenItAsksForItsUdpTest(LowIdMessages.UdpCheckRequest request) throws IOException {
        this.client.send(request.encode());
    }

    private void thenKadHearsOfTheUdpTest(String request) throws Exception {
        await("the UDP test request", () -> !this.udpChecks.isEmpty());
        assertThat(this.udpChecks).containsExactly(request);
    }

    @Test
    void shouldGiveASlotWhenItAsksAgainToAFirewalledClientWhoseTurnCameWhileItWasAway() throws Exception {
        givenTwoSlotsBusyUnderALimit();
        givenAFirewalledClientThatWaitedAndLeft();
        givenASlotFreedWhileItWasAway();

        whenTheFirewalledClientAsksAgain();

        thenItGetsASlot();
    }

    private void givenAClientWaitingThatTakesUdpReasksOnPort(int udpPort) throws Exception {
        this.client = client(Hello.of(
                hashOf(3),
                0,
                5003,
                "client 3",
                udpPort,
                udpPort,
                ClientReasks.UDP_VERSION << Hello.MISC1_UDP_VERSION_SHIFT,
                0,
                0,
                0));
        assertThat(rank(ask(this.client, this.file.hash()))).isEqualTo(1);
    }

    private void whenAReaskForTheFileComesFrom(InetAddress ip, int port) throws Exception {
        whenAReaskForTheFileComesFrom(ip, port, this.file.hash());
    }

    private void whenAReaskForTheFileComesFrom(InetAddress ip, int port, Hash16 file) throws Exception {
        Packet ping =
                new ClientReasks.Ping(file, ClientMessages.PartStatus.COMPLETE, 0).encode(ClientReasks.UDP_VERSION);
        this.reaskAnswer = onLoop(() -> this.manager.reasked(ip, port, ping));
    }

    private void thenTheAnswerTellsThePlace(int place) throws Exception {
        assertThat(this.reaskAnswer).isPresent();
        Packet answer = this.reaskAnswer.get().packet();
        assertThat(answer.opcode()).isEqualTo(Opcodes.ClientUdp.REASKACK.code());
        assertThat(ClientReasks.Ack.decode(answer, ClientReasks.UDP_VERSION))
                .isEqualTo(new ClientReasks.Ack(Optional.of(ClientMessages.PartStatus.COMPLETE), place));
    }

    private void thenThereIsNoAnswer() {
        assertThat(this.reaskAnswer).isEmpty();
    }

    private void thenTheAnswerIsPlain(Packet expected) {
        assertThat(this.reaskAnswer).contains(new UploadManager.ReaskAnswer(expected, Optional.empty()));
    }

    private void givenTwoSlotsBusyUnderALimit() throws Exception {
        this.settings = new UploadManager.Settings(1, ObfuscationMode.SUPPORTED, 100, Optional.empty());
        this.file = share("for-all.bin", random((int) UploadSession.MAX_BLOCK, 16));
        start();
        for (int seed = 1; seed <= 2; seed++) {
            ScriptedDownloader slot = client(seed, 0, 0);
            assertThat(ask(slot, this.file.hash()).opcode()).isEqualTo(Opcodes.Client.ACCEPTUPLOADREQ.code());
            slot.send(blocks(this.file.hash(), 0, UploadSession.MAX_BLOCK));
            this.slotHolders.add(slot);
        }
    }

    private void givenAFirewalledClientThatWaitedAndLeft() throws Exception {
        ScriptedDownloader firewalled = client(hello(3, LOW_ID, 0));
        assertThat(rank(ask(firewalled, this.file.hash()))).isEqualTo(1);
        firewalled.close();
        advance(UploadManager.MIN_REQUEST_TIME.plusMinutes(1));
    }

    private void givenASlotFreedWhileItWasAway() throws Exception {
        this.slotHolders.getFirst().close();
        await(
                "the firewalled client called on its next request",
                () -> this.manager.describe().startsWith("waiting 1, 1 called when they ask"));
    }

    private void whenTheFirewalledClientAsksAgain() throws Exception {
        this.answer = ask(client(hello(3, LOW_ID, 0)), this.file.hash());
    }

    private void thenItGetsASlot() {
        assertThat(this.answer.opcode()).isEqualTo(Opcodes.Client.ACCEPTUPLOADREQ.code());
    }

    @Test
    void shouldQueueLittleForAClientThatReadsNothingAndLetOurAnswersPassItsData() throws Exception {
        givenAClientWithASlotThatAsksForManyBlocksAndReadsNothing();

        whenItAsksForTheFileNameAndReads();

        thenLittleOfWhatItAskedForWasQueued();
        thenOurAnswerCameBeforeMostOfTheData();
    }

    private void givenAClientWithASlotThatAsksForManyBlocksAndReadsNothing() throws Exception {
        this.file = share("much.bin", random(MANY_BLOCKS * (int) UploadSession.MAX_BLOCK, 17));
        start();
        this.client = client(1, 0, 0);
        assertThat(ask(this.client, this.file.hash()).opcode()).isEqualTo(Opcodes.Client.ACCEPTUPLOADREQ.code());
        for (int at = 0; at < MANY_BLOCKS; at += 3) {
            List<ClientMessages.Range> ranges = new ArrayList<>();
            for (int b = at; b < at + 3; b++) {
                ranges.add(
                        b < MANY_BLOCKS
                                ? new ClientMessages.Range(
                                        b * UploadSession.MAX_BLOCK, (b + 1) * UploadSession.MAX_BLOCK)
                                : new ClientMessages.Range(0, 0));
            }
            this.client.send(new ClientMessages.RequestParts(this.file.hash(), ranges).encode(false));
        }
        // Whatever is not held back is handed over within a few ticks.
        long last = -1;
        long sent = sentToTheSlot();
        while (sent != last) {
            Thread.sleep(1_000);
            last = sent;
            sent = sentToTheSlot();
        }
        this.queued = sent;
    }

    private long sentToTheSlot() throws Exception {
        return onLoop(() -> this.manager.uploads().stream()
                .mapToLong(UploadInfo::transferred)
                .sum());
    }

    private void whenItAsksForTheFileNameAndReads() throws Exception {
        this.client.send(ClientMessages.forFile(Opcodes.Client.REQUESTFILENAME, this.file.hash()));
        Thread.sleep(500);
        for (Packet packet : this.client.until(Protocol.EDONKEY, Opcodes.Client.REQFILENAMEANSWER.code())) {
            if (packet.protocol() == Protocol.EDONKEY && packet.opcode() == Opcodes.Client.SENDINGPART.code()) {
                this.dataBeforeAnswer +=
                        ClientMessages.SendingPart.decode(packet).data().length;
            }
        }
    }

    private void thenLittleOfWhatItAskedForWasQueued() {
        assertThat(this.queued).isLessThan(this.file.size() / 2);
    }

    private void thenOurAnswerCameBeforeMostOfTheData() {
        assertThat(this.dataBeforeAnswer).isLessThan(this.file.size() / 2);
    }

    @Test
    void shouldTellSourcesOnlyForAFileTheClientAskedForOnItsConnection() throws Exception {
        givenAFileWithAWaitingClient();
        givenAClientThatAskedForAnotherFile();

        whenItAsksForTheSourcesOfTheFile();

        thenItHearsNoSources();
        thenItHearsTheSourcesOnceItAskedForTheFile();
    }

    @Test
    void shouldTellNoSourcesToAClientAtABannedAddress() throws Exception {
        givenTwoSlotsBusyUnderALimit();
        givenTheirAddressBannedForAClientThatAskedTooOften();

        whenASlotHolderAsksForTheSourcesOfItsFile();

        thenItHearsNoSources();
    }

    @Test
    void shouldForgetTheSourceAnswersGivenOnceTheyCanBeAskedAgain() throws Exception {
        givenAFileWithAWaitingClient();
        givenAClientToldItsSources();

        whenTheLongestWaitForSourcesPasses();

        thenNoSourceAnswerIsRemembered();
    }

    private void givenAFileWithAWaitingClient() throws Exception {
        this.settings = new UploadManager.Settings(1, ObfuscationMode.SUPPORTED, 100, Optional.empty());
        this.file = share("sought.bin", random(3000, 18));
        this.other = share("other.bin", random(3000, 19));
        start();
        for (int seed = 1; seed <= 3; seed++) {
            ask(client(seed, 0, 0), this.file.hash());
        }
    }

    private void givenAClientThatAskedForAnotherFile() throws Exception {
        this.client = client(4, 0, 0);
        this.client.send(ClientMessages.forFile(Opcodes.Client.REQUESTFILENAME, this.other.hash()));
        this.client.until(Opcodes.Client.REQFILENAMEANSWER);
    }

    private void givenAClientToldItsSources() throws Exception {
        givenAClientThatAskedForAnotherFile();
        thenItHearsTheSourcesOnceItAskedForTheFile();
        await("the answer remembered", () -> this.manager.describe().contains(", 1 source answers"));
    }

    private void givenTheirAddressBannedForAClientThatAskedTooOften() throws Exception {
        ScriptedDownloader eager = client(5, 0, 0);
        for (int i = 0; i < UploadManager.BAD_LIMIT; i++) {
            ask(eager, this.file.hash());
        }
        eager.send(ClientMessages.forFile(Opcodes.Client.STARTUPLOADREQ, this.file.hash()));
        assertThat(eager.closedWithin(WAIT)).isTrue();
        this.client = this.slotHolders.getFirst();
    }

    private void whenItAsksForTheSourcesOfTheFile() throws IOException {
        this.client.send(SourceExchange.request(this.file.hash()));
    }

    private void whenASlotHolderAsksForTheSourcesOfItsFile() throws IOException {
        whenItAsksForTheSourcesOfTheFile();
    }

    private void whenTheLongestWaitForSourcesPasses() {
        advance(Duration.ofMinutes(161));
    }

    private void thenItHearsNoSources() throws IOException {
        this.client.send(ClientMessages.forFile(Opcodes.Client.REQUESTFILENAME, this.file.hash()));
        assertThat(names(this.client.until(Protocol.EDONKEY, Opcodes.Client.REQFILENAMEANSWER.code())))
                .doesNotContain("ANSWERSOURCES2");
    }

    private void thenItHearsTheSourcesOnceItAskedForTheFile() throws IOException, DecodeException {
        this.client.send(ClientMessages.forFile(Opcodes.Client.REQUESTFILENAME, this.file.hash()));
        this.client.until(Opcodes.Client.REQFILENAMEANSWER);
        this.client.send(SourceExchange.request(this.file.hash()));
        assertThat(SourceExchange.Answer.decode(this.client.until(Opcodes.Extended.ANSWERSOURCES2))
                        .sources())
                .hasSize(3);
    }

    private void thenNoSourceAnswerIsRemembered() throws Exception {
        await("the answer forgotten", () -> this.manager.describe().contains(", 0 source answers"));
    }

    @Test
    void shouldNotCountAsksForOtherFilesAsAskingTooOften() throws Exception {
        givenTwoSlotsBusyUnderALimit();
        givenOtherFilesShared(UploadManager.BAD_LIMIT);
        givenAClientWaiting();

        whenItAsksForEachOtherFileRightAway();

        thenItIsStillTold(Opcodes.Extended.QUEUERANKING);
    }

    @Test
    void shouldNotCountTheEndOfASlotAsAnAskOfTheClient() throws Exception {
        givenTwoSlotsBusyUnderALimit();
        givenAClientWaiting();
        givenItAskedAgainRightAway(UploadManager.BAD_LIMIT - 1);
        givenItGotASlotThatEndedBecauseItAskedForNothing();

        whenItAsksAgainAsLongAfterItsLastAskAsAllowed();

        thenItIsStillTold(Opcodes.Extended.QUEUERANKING);
    }

    private void givenOtherFilesShared(int count) throws IOException {
        for (int i = 0; i < count; i++) {
            this.others.add(share("other-" + i + ".bin", random(3000, 30 + i)));
        }
    }

    private void givenAClientWaiting() throws Exception {
        this.client = client(6, 0, 0);
        assertThat(rank(ask(this.client, this.file.hash()))).isEqualTo(1);
    }

    private void givenItAskedAgainRightAway(int times) throws Exception {
        for (int i = 0; i < times; i++) {
            advance(Duration.ofSeconds(1));
            assertThat(rank(ask(this.client, this.file.hash()))).isEqualTo(1);
        }
    }

    private void givenItGotASlotThatEndedBecauseItAskedForNothing() throws Exception {
        ScriptedDownloader later = client(7, 0, 0);
        assertThat(rank(ask(later, this.file.hash()))).isEqualTo(2);
        this.slotHolders.getFirst().close();
        this.client.until(Opcodes.Client.ACCEPTUPLOADREQ);
        advance(UploadManager.FIRST_REQUEST_WAIT.plusSeconds(1));
        this.client.until(Opcodes.Client.OUTOFPARTREQS);
    }

    private void whenItAsksForEachOtherFileRightAway() throws Exception {
        for (SharedFiles.SharedFile wanted : this.others) {
            advance(Duration.ofSeconds(1));
            this.answer = ask(this.client, wanted.hash());
        }
    }

    // Ten minutes after its last ask, less the time its slot took.
    private void whenItAsksAgainAsLongAfterItsLastAskAsAllowed() throws Exception {
        advance(UploadManager.MIN_REQUEST_TIME.minus(UploadManager.FIRST_REQUEST_WAIT));
        this.answer = ask(this.client, this.file.hash());
    }

    private void thenItIsStillTold(Opcodes.Extended opcode) {
        assertThat(this.answer.protocol()).isEqualTo(Protocol.EMULE);
        assertThat(this.answer.opcode()).isEqualTo(opcode.code());
    }

    @Test
    void shouldStillCountAskingTooOftenAfterTheClientLeftTheQueue() throws Exception {
        givenTwoSlotsBusyUnderALimit();
        givenAClientThatAskedTooOftenAlmostToABanAndCannotBeCalled();
        givenItLeftTheQueueWhenItCouldNotBeCalled();

        whenItComesBackAndAsksRightAway();

        thenItIsBanned();
    }

    @Test
    void shouldStillCountAskingTooOftenWhenTheClientComesBackWithAnotherUserHash() throws Exception {
        givenTwoSlotsBusyUnderALimit();
        givenAClientThatAskedTooOftenAlmostToABanAndCannotBeCalled();
        givenItLeftTheQueueWhenItCouldNotBeCalled();

        whenItComesBackWithTheUserHashOfAndAsksRightAway(9);

        thenItIsBanned();
    }

    @Test
    void shouldBanAClientThatKeepsTakingSlotsWithoutAskingForData() throws Exception {
        givenASlotForAClientWhileAnotherWaits();

        whenItLeavesItsSlotIdle(UploadManager.IDLE_SLOTS_LIMIT);

        thenItIsBanned();
    }

    @Test
    void shouldForgiveTheIdleSlotsOnceASlotSentData() throws Exception {
        givenASlotForAClientWhileAnotherWaits();
        givenItLeftItsSlotIdle(UploadManager.IDLE_SLOTS_LIMIT - 1);
        givenItThenDownloadedFromItsSlot();

        whenItLeavesItsSlotIdle(1);

        thenItGetsItsSlotBack();
    }

    private void givenAClientThatAskedTooOftenAlmostToABanAndCannotBeCalled() throws Exception {
        int closed;
        try (ServerSocket probe = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            closed = probe.getLocalPort();
        }
        this.eager = hello(8, 0, closed);
        this.client = client(this.eager);
        assertThat(rank(ask(this.client, this.file.hash()))).isEqualTo(1);
        givenItAskedAgainRightAway(UploadManager.BAD_LIMIT - 1);
        this.client.close();
    }

    private void givenItLeftTheQueueWhenItCouldNotBeCalled() throws Exception {
        this.slotHolders.getFirst().close();
        await("the client left the queue", () -> this.manager.waitingCount() == 0);
    }

    private void givenASlotForAClientWhileAnotherWaits() throws Exception {
        // Two slots still, with budget enough for a small block while the other slot is busy.
        this.settings = new UploadManager.Settings(5, ObfuscationMode.SUPPORTED, 100, Optional.empty());
        SharedFiles.SharedFile big = share("busy.bin", random((int) UploadSession.MAX_BLOCK, 21));
        this.file = share("small.bin", random(3000, 22));
        start();
        ScriptedDownloader busy = client(1, 0, 0);
        assertThat(ask(busy, big.hash()).opcode()).isEqualTo(Opcodes.Client.ACCEPTUPLOADREQ.code());
        busy.send(blocks(big.hash(), 0, UploadSession.MAX_BLOCK));
        this.client = client(2, 0, 0);
        assertThat(ask(this.client, this.file.hash()).opcode()).isEqualTo(Opcodes.Client.ACCEPTUPLOADREQ.code());
        // A firewalled client keeps waiting, so the slot goes back to the queue when idle, and to the same client.
        ScriptedDownloader firewalled = client(hello(3, LOW_ID, 0));
        assertThat(rank(ask(firewalled, this.file.hash()))).isEqualTo(1);
        firewalled.close();
    }

    private void givenItLeftItsSlotIdle(int times) throws Exception {
        whenItLeavesItsSlotIdle(times);
        thenItGetsItsSlotBack();
    }

    private void givenItThenDownloadedFromItsSlot() throws Exception {
        this.client.send(blocks(this.file.hash(), 0, 3000));
        this.client.until(Opcodes.Client.SENDINGPART);
        advance(UploadManager.IDLE_SLOT.plusSeconds(1));
        this.client.until(Opcodes.Client.OUTOFPARTREQS);
        thenItGetsItsSlotBack();
    }

    private void whenItComesBackAndAsksRightAway() throws Exception {
        comeBackAndAskRightAway(this.eager);
    }

    private void whenItComesBackWithTheUserHashOfAndAsksRightAway(int seed) throws Exception {
        comeBackAndAskRightAway(hello(seed, 0, this.eager.tcpPort()));
    }

    private void comeBackAndAskRightAway(Hello hello) throws Exception {
        this.client = client(hello);
        this.client.send(ClientMessages.forFile(Opcodes.Client.SETREQFILEID, this.file.hash()));
        this.client.until(Opcodes.Client.FILESTATUS);
        this.client.send(ClientMessages.forFile(Opcodes.Client.STARTUPLOADREQ, this.file.hash()));
    }

    private void whenItLeavesItsSlotIdle(int times) throws Exception {
        for (int i = 0; i < times; i++) {
            if (i > 0) {
                thenItGetsItsSlotBack();
            }
            advance(UploadManager.FIRST_REQUEST_WAIT.plusSeconds(1));
            this.client.until(Opcodes.Client.OUTOFPARTREQS);
        }
    }

    private void thenItIsBanned() throws Exception {
        assertThat(this.client.closedWithin(WAIT)).isTrue();
        assertThat(onLoop(this.manager::bans)).isNotEmpty();
    }

    private void thenItGetsItsSlotBack() throws IOException {
        this.client.until(Opcodes.Client.ACCEPTUPLOADREQ);
    }

    @Test
    void shouldDropAClientAskingForManyFilesWeDoNotHaveAndBanItTheSecondTime() throws Exception {
        givenAClientThatWasDroppedForAskingForFilesWeDoNotHave();

        whenAnotherConnectionFromItsAddressAsksForThem(UploadSession.UNKNOWN_FILES_LIMIT);

        thenItIsBanned();
    }

    @Test
    void shouldNotCountFilesWeDownloadAsUnknown() throws Exception {
        givenADownloadWithNothingToShareYet();

        whenAClientAsksForItAndForUnknownFiles(UploadSession.UNKNOWN_FILES_LIMIT - 1);

        thenItStillGetsAnswers();
    }

    private void givenAClientThatWasDroppedForAskingForFilesWeDoNotHave() throws Exception {
        this.file = share("known.bin", random(3000, 23));
        start();
        whenAnotherConnectionFromItsAddressAsksForThem(UploadSession.UNKNOWN_FILES_LIMIT);
        assertThat(this.client.closedWithin(WAIT)).isTrue();
        assertThat(onLoop(this.manager::bans)).isEmpty();
    }

    private void givenADownloadWithNothingToShareYet() throws Exception {
        Ed2kLink.FileLink link = new Ed2kLink.FileLink(
                "pending.bin", 5000, hashOf(90), Optional.empty(), List.of(), List.of(), List.of());
        addDownload(link);
        this.file = share("known.bin", random(3000, 24));
        start();
    }

    private void whenAnotherConnectionFromItsAddressAsksForThem(int files) throws Exception {
        this.client = client(30 + this.slotHolders.size(), 0, 0);
        this.slotHolders.add(this.client);
        for (int i = 0; i < files; i++) {
            this.client.send(ClientMessages.forFile(Opcodes.Client.REQUESTFILENAME, hashOf(100 + i)));
        }
    }

    private void whenAClientAsksForItAndForUnknownFiles(int unknown) throws Exception {
        this.client = client(30, 0, 0);
        this.client.send(ClientMessages.forFile(Opcodes.Client.SETREQFILEID, hashOf(90)));
        this.client.until(Opcodes.Client.FILEREQANSNOFIL);
        for (int i = 0; i < unknown; i++) {
            this.client.send(ClientMessages.forFile(Opcodes.Client.SETREQFILEID, hashOf(100 + i)));
            this.client.until(Opcodes.Client.FILEREQANSNOFIL);
        }
    }

    private void thenItStillGetsAnswers() throws IOException {
        this.client.send(ClientMessages.forFile(Opcodes.Client.REQUESTFILENAME, this.file.hash()));
        assertThat(this.client.until(Opcodes.Client.REQFILENAMEANSWER)).isNotNull();
    }

    private ScriptedDownloader client(int seed, long clientId, int listenPort) throws IOException {
        return client(hello(seed, clientId, listenPort));
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
        onLoop(() -> {
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
