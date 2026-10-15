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
package org.njmule.core.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.core.api.BanInfo;
import org.njmule.core.api.Ed2kLink;
import org.njmule.core.api.EngineConfig;
import org.njmule.core.api.EngineStatus;
import org.njmule.core.api.Friend;
import org.njmule.core.api.Hash16;
import org.njmule.core.api.Hash20;
import org.njmule.core.api.NjMuleEngine;
import org.njmule.core.api.SharedFileInfo;
import org.njmule.core.api.UploadInfo;
import org.njmule.core.api.UploadPriority;
import org.njmule.core.config.AppDirectories;
import org.njmule.core.config.SharedFilesVisibility;
import org.njmule.core.protocol.ed2k.ClientMessages;
import org.njmule.core.protocol.ed2k.FileEntry;
import org.njmule.core.protocol.ed2k.Hello;
import org.njmule.core.protocol.ed2k.SourceExchange;
import org.njmule.core.protocol.io.PacketReader;
import org.njmule.core.protocol.io.PacketWriter;
import org.njmule.core.protocol.io.StringEncoding;
import org.njmule.core.protocol.packet.FrameLimit;
import org.njmule.core.protocol.packet.Opcodes;
import org.njmule.core.protocol.packet.Packet;
import org.njmule.core.protocol.packet.Protocol;
import org.njmule.core.protocol.packet.TcpFraming;

@Timeout(60)
class UploadQueueTest {

    private static final Duration WAIT = Duration.ofSeconds(20);
    private static final Hash16 PARTIAL = Hash16.parseHex("5A".repeat(16));
    private static final Hash20 AICH =
            Hash20.of(new byte[] {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20});
    private static final Hash16 UNKNOWN = Hash16.parseHex("77".repeat(16));

    @TempDir
    Path dir;

    private final List<AutoCloseable> resources = new ArrayList<>();
    private final Map<Integer, Client> clients = new HashMap<>();

    private NjMuleEngine engine;
    private List<SharedFileInfo> files;
    private int port;
    private ServerSocket listening;
    private Client called;
    private Packet answer;
    private Optional<Packet> polled;
    private boolean accepted;
    private int firstByte;
    private Throwable exception;

    @AfterEach
    void closeEverything() throws Exception {
        Collections.reverse(this.resources);
        for (AutoCloseable resource : this.resources) {
            resource.close();
        }
    }

    @Test
    void shouldGiveTheSlotsTheLimitAllowsAndQueueTheRest() throws Exception {
        givenAnEngineSharingAFileAtKiBps(1);
        givenClients(1, 2, 3);
        givenTheClientsAskForTheFile(1, 2, 3);
        givenTheClientGetsASlot(1);
        givenTheClientGetsASlot(2);

        whenTheClientAsksForAnUpload(3);

        thenTheAnswerIsTheQueueRank(1);
        // Front ends see the slots, then the queue.
        thenTheUploadStatesAre(UploadInfo.State.UPLOADING, UploadInfo.State.UPLOADING, UploadInfo.State.QUEUED);
    }

    @Test
    void shouldBanAndDropAClientThatAsksTooOften() throws Exception {
        givenAnEngineSharingAFileAtKiBps(1);
        givenClients(1, 2, 3);
        givenTheClientsAskForTheFile(1, 2, 3);
        givenTheClientGetsASlot(1);
        givenTheClientGetsASlot(2);
        givenTheClientIsQueuedAtRank(3, 1);
        // Asking again at once: three times are tolerated, the fourth bans the client and drops it.
        givenTheClientIsToleratedAskingAgainAtOnce(3, 3);

        whenTheClientAsksOnceMore(3);

        thenExceptionOccurred(IOException.class);
    }

    @Test
    void shouldSendASlotTheBlockItAsksForWithinTheLimitOnceTheBannedClientLeft() throws Exception {
        givenAnEngineSharingAFileAtKiBps(1);
        givenClients(1, 2, 3);
        givenTheClientsAskForTheFile(1, 2, 3);
        givenTheClientGetsASlot(1);
        givenTheClientGetsASlot(2);
        givenTheClientIsQueuedAtRank(3, 1);
        givenTheClientIsToleratedAskingAgainAtOnce(3, 3);
        givenTheClientIsBannedForAskingOnceMore(3);

        whenTheClientRequestsTheBytes(1, 0, 1000);

        thenTheClientIsSentAPartEndingAt(1000);
        // The banned client left the queue.
        thenTheUploadStatesAre(UploadInfo.State.UPLOADING, UploadInfo.State.UPLOADING);
        thenEveryUploadIsOfTheSharedFile();
        thenTheUploadsTransferredInAll(1000);
        thenTheStatusCountsUploads(2);
        thenTheStatusCountsNoQueuedClient();
        thenTheStatusCountsUploadedBytes(1000);
        thenTheStatusCountsSharedFiles(1);
        thenTheStatusShowsNoServer();
        thenTheBanShowsWithItsTimeLeft();
    }

    @Test
    void shouldLiftABan() throws Exception {
        givenAnEngineSharingAFileAtKiBps(1);
        givenClients(1, 2, 3);
        givenTheClientsAskForTheFile(1, 2, 3);
        givenTheClientGetsASlot(1);
        givenTheClientGetsASlot(2);
        givenTheClientIsQueuedAtRank(3, 1);
        givenTheClientIsToleratedAskingAgainAtOnce(3, 3);
        givenTheClientIsBannedForAskingOnceMore(3);

        whenTheBanIsLifted();

        thenTheBanIsLifted();
        thenThereAreNoBans();
    }

    @Test
    void shouldLiftNoBanOfAnAddressNeverBanned() throws Exception {
        givenAnEngineSharingAFileAtKiBps(1);

        whenTheBanOfTheAddressIsLifted("192.0.2.1");

        thenNoBanIsLifted();
    }

    @Test
    void shouldGoOnUploadingAfterALimitIsSetWhereThereWasNone() throws Exception {
        givenAnEngineSharingAFile();
        givenTheClientGetsASlot(15);
        givenTheUploadLimitIsSetToKiBps(10);

        whenTheClientRequestsTheBytesWaitingAtMost(15, 0, 1000, Duration.ofSeconds(5));

        thenTheClientIsAnswered(Opcodes.Client.SENDINGPART);
    }

    @Test
    void shouldSkipAnInvertedRangeInARequest() throws Exception {
        givenAnEngineSharingAFile();
        givenTheClientGetsASlot(21);

        // 5000-4000 is backwards.
        whenTheClientRequestsTheRanges(21, 5000, 4000, 0, 1000);

        thenTheClientIsSentTheBytes(0, 1000);
    }

    @Test
    void shouldKeepSendingAFileRenamedDuringItsUpload() throws Exception {
        givenAnEngineSharingAFile();
        givenTheClientGetsASlot(20);
        givenTheClientGetsTheBytes(20, 0, 1000);
        givenTheSharedFileIsRenamed("renamed.bin");

        whenTheClientRequestsTheBytes(20, 1000, 2000);

        thenTheClientIsSentTheBytesOfTheFileNamed("renamed.bin", 1000, 2000);
    }

    @Test
    void shouldRefuseTheFriendSlotToAClientThatIsNotAFriend() throws Exception {
        givenAnEngineSharingAFileAtKiBps(1);
        givenAFriend(4);

        whenTheFriendSlotIsGivenTo(9);

        thenTheFriendSlotIsRefusedAsNotAFriend();
    }

    @Test
    void shouldGiveTheFriendSlotToAFriend() throws Exception {
        givenAnEngineSharingAFileAtKiBps(1);
        givenAFriend(4);

        whenTheFriendSlotIsGivenTo(4);

        thenTheFriendSlotIsGiven();
        thenTheFriendSlotGoesTo(4);
    }

    @Test
    void shouldLetTheFriendWithTheFriendSlotGoBeforeTheQueue() throws Exception {
        givenAnEngineSharingAFileAtKiBps(1);
        givenAFriend(4);
        givenTheFriendSlotIsGivenTo(4);
        givenTheClientsAskForTheFile(1, 2, 3, 4);
        givenTheClientGetsASlot(1);
        givenTheClientGetsASlot(2);
        givenTheClientIsQueuedAtRank(3, 1);

        whenTheClientAsksForAnUpload(4);

        thenTheQueueRankIs(1, "the friend comes first");
    }

    @Test
    void shouldGiveTheFriendSlotOnlyOnceTheFriendProvesItsKey() throws Exception {
        givenAnEngineSharingAFileAtKiBps(1);
        givenAFriend(16);
        givenTheFriendSlotIsGivenTo(16);
        givenTheClientGetsASlot(1);
        givenTheClientGetsASlot(2);
        givenTheClientIsQueuedAtRank(3, 1);
        // Anyone can send the friend's user hash: it counts once the key behind it is proven.
        givenAnIdentifyingClient(16);
        givenTheClientIsQueuedAtRank(16, 2, "not proven yet");
        givenAnIdentifyingClient(16);
        givenTheClientProvesItsKey(16);

        whenTheClientAsksForAnUpload(16);

        thenTheQueueRankIs(1, "proven");
    }

    @Test
    void shouldShowFilesSharedWithFriendsToNoClientThatFailsToProveTheFriendsKey() throws Exception {
        givenAnEngineSharingAFileVisibleTo(SharedFilesVisibility.FRIENDS);
        givenAFriend(17);
        givenAnIdentifyingClient(17);
        givenTheClientAskedForOurSharedFiles(17);
        // A client claiming the friend's hash waits for the proof, and failing it sees nothing.
        givenTheClientIsToldNothingWithin(17, Duration.ofSeconds(1));

        whenTheClientFailsTheProof(17);

        thenTheAnswerCarries(0, 0, 0, 0);
    }

    @Test
    void shouldShowFilesSharedWithFriendsToTheFriendNotTakingPartInTheIdentification() throws Exception {
        givenAnEngineSharingAFileVisibleTo(SharedFilesVisibility.FRIENDS);
        givenAFriend(17);
        givenAClient(17);

        whenTheClientAsksForOurSharedFiles(17);

        thenTheAnswerStartsWith(1, 0, 0, 0);
    }

    @Test
    void shouldShowFilesSharedWithFriendsToTheFriendProvingItsKey() throws Exception {
        givenAnEngineSharingAFileVisibleTo(SharedFilesVisibility.FRIENDS);
        givenAFriend(17);
        givenAnIdentifyingClient(17);
        givenTheClientAskedForOurSharedFiles(17);

        whenTheClientProvesItsKey(17);

        thenTheAnswerIs(Opcodes.Client.ASKSHAREDFILESANSWER);
        thenTheAnswerStartsWith(1, 0, 0, 0);
    }

    @Test
    void shouldListOurFilesInOnePseudoDirectoryToClientsBrowsingByDirectory() throws Exception {
        givenAnEngineSharingAFileVisibleTo(SharedFilesVisibility.EVERYBODY);
        givenAClient(18);

        whenTheClientAsksForOurSharedDirectories(18);

        thenTheAnswerIs(Opcodes.Client.ASKSHAREDDIRSANS);
        thenTheDirectoriesAre("!Other");
    }

    @Test
    void shouldGiveOurFilesInThePseudoDirectoryToClientsBrowsingByDirectory() throws Exception {
        givenAnEngineSharingAFileVisibleTo(SharedFilesVisibility.EVERYBODY);
        givenAClient(18);
        givenTheClientAskedForOurSharedDirectories(18);

        whenTheClientAsksForTheFilesOfTheDirectory(18, "!Other");

        thenTheAnswerIs(Opcodes.Client.ASKSHAREDFILESDIRANS);
        thenTheAnswerListsTheSharedFileIn("!Other");
    }

    @Test
    void shouldGiveOurFilesOnceAConnection() throws Exception {
        givenAnEngineSharingAFileVisibleTo(SharedFilesVisibility.EVERYBODY);
        givenAClient(18);
        givenTheClientAskedForOurSharedDirectories(18);
        givenTheClientAskedForTheFilesOfTheDirectory(18, "!Other");

        // A list can take a megabyte: one a connection.
        whenTheClientAlsoAsksForAllOurSharedFilesWaitingAtMost(18, Duration.ofSeconds(1));

        thenTheClientIsToldNothing();
    }

    @Test
    void shouldTellTheAichHashOfADownloadToClientsThatAskForIt() throws Exception {
        givenAnEngine();
        givenADownloadWithTheAichHash("partial.bin", PARTIAL, AICH);
        givenAClient(19);

        whenTheClientAsksForTheAichHashOfWaitingAtMost(19, PARTIAL, Duration.ofSeconds(3));

        thenTheClientIsToldTheAichHash(PARTIAL, AICH);
    }

    @Test
    void shouldShareAFileWithTheAutomaticUploadPriority() throws Exception {
        givenAnEngine();

        whenTheFileIsShared();

        thenTheFileIsSharedWithTheUploadPriority(UploadPriority.AUTO);
    }

    @Test
    void shouldKeepTheUploadPriorityASharedFileIsGiven() throws Exception {
        givenAnEngineSharingAFile();

        whenTheUploadPriorityOfTheSharedFileIsSet(UploadPriority.HIGH);

        thenThePriorityIsSet();
        thenTheSharedFileHasTheUploadPriority(UploadPriority.HIGH);
    }

    @Test
    void shouldSetNoUploadPriorityForAFileNotShared() throws Exception {
        givenAnEngineSharingAFile();

        whenTheUploadPriorityOfTheFileIsSet(Hash16.parseHex("00".repeat(16)), UploadPriority.LOW);

        thenNoPriorityIsSet();
    }

    @Test
    void shouldTellADownloaderTheNameOfTheFile() throws Exception {
        givenAnEngineSharingAFile();
        givenAClient(4);

        whenTheClientAsksForTheNameOfTheFile(4);

        thenTheFileNameIs("queued.bin");
    }

    @Test
    void shouldTellADownloaderThatAFileIsNotHere() throws Exception {
        givenAnEngineSharingAFile();
        givenAClient(4);

        whenTheClientAsksForTheFile(4, UNKNOWN);

        thenTheAnswerIs(Opcodes.Client.FILEREQANSNOFIL);
    }

    @Test
    void shouldShowADownloaderNoSharedFilesByDefault() throws Exception {
        givenAnEngineSharingAFile();
        givenAClient(4);

        whenTheClientAsksForOurSharedFiles(4);

        thenTheAnswerIs(Opcodes.Client.ASKSHAREDFILESANSWER);
        thenTheAnswerCarries(0, 0, 0, 0);
    }

    @Test
    void shouldDenyADownloaderOurSharedDirectoriesByDefault() throws Exception {
        givenAnEngineSharingAFile();
        givenAClient(4);

        whenTheClientAsksForOurSharedDirectories(4);

        thenTheAnswerIs(Opcodes.Client.ASKSHAREDDENIEDANS);
    }

    @Test
    void shouldDenyADownloaderTheFilesOfADirectoryByDefault() throws Exception {
        givenAnEngineSharingAFile();
        givenAClient(4);

        whenTheClientAsksForTheFilesOfTheDirectory(4, "!Other");

        thenTheAnswerIs(Opcodes.Client.ASKSHAREDDENIEDANS);
    }

    @Test
    void shouldTellADownloaderItsPublicIp() throws Exception {
        givenAnEngineSharingAFile();
        givenAClient(4);

        whenTheClientAsksForItsPublicIp(4);

        thenTheAnswerIs(Opcodes.Extended.PUBLICIP_ANSWER);
        thenTheAnswerCarries(127, 0, 0, 1);
    }

    @Test
    void shouldAnswerTheAichRequestOfADownloader() throws Exception {
        givenAnEngineSharingAFile();
        givenAClient(4);

        whenTheClientAsksForTheAichDataOfPart(4, 0);

        thenTheAnswerIs(Opcodes.Extended.AICHANSWER);
        thenTheAnswerIsBytesLong(16);
    }

    @Test
    void shouldGiveADownloaderASlotAfterARequestForSourcesNobodyCanAnswer() throws Exception {
        givenAnEngineSharingAFile();
        givenAClient(4);

        // Nobody else has the file, so a request for sources gets no answer; a slot still comes.
        whenTheClientAsksForSourcesThenForAnUpload(4);

        thenTheAnswerIs(Opcodes.Client.ACCEPTUPLOADREQ);
    }

    @Test
    void shouldCallAWaitingClientBackWhenASlotFrees() throws Exception {
        givenAnEngineSharingAFileAtKiBps(1);
        givenClients(5, 6);
        givenTheClientGetsASlot(5);
        givenTheClientGetsASlot(6);
        givenAQueuedClientThatHungUpListeningForACall(7);
        givenTheClientGivesItsSlotBack(5);
        givenTheEngineCallsTheQueuedClientBack();

        whenTheCalledClientSaysHelloAs(7);

        thenTheAnswerIs(Opcodes.Client.ACCEPTUPLOADREQ);
    }

    @Test
    void shouldGiveAnotherAddressClaimingAUserHashNeitherItsSlotNorItsPlace() throws Exception {
        givenAnEngineSharingAFileAtKiBps(1);
        givenTheClientsAskForTheFile(7);
        givenTheClientGetsASlot(7);
        givenAClientAtAnotherAddressClaimingTheUserHashIsIgnored("127.0.0.2", 7);

        // The slot still serves the client that holds it.
        whenTheClientRequestsTheBytes(7, 0, 1000);

        thenTheAnswerIs(Opcodes.Client.SENDINGPART);
        thenThereAreUploads(1);
    }

    @Test
    void shouldDropAndBanAClientChangingItsUserHash() throws Exception {
        givenAnEngine();
        givenAClient(8);
        givenTheClientIsDroppedForChangingItsUserHashTo(8, 9);

        // The banned address cannot even connect again.
        whenTheAddressConnectsAgain();

        thenTheConnectionIsClosedAtOnce();
        thenThereAreNoUploads();
    }

    @Test
    void shouldGiveASlotLeftIdleToTheNextClientInTheQueue() throws Exception {
        givenAnEngineSharingAFileAtKiBps(1);
        givenClients(11, 12);
        givenTheClientGetsASlot(11);
        givenTheClientGetsASlot(12);
        givenTheClientIsQueued(13);
        givenTheClientGetsTheBytes(11, 0, 1000);

        // The client that asks for nothing loses its slot to the one waiting.
        whenTheClientWaitsAtMost(12, Duration.ofSeconds(25));

        thenTheClientIsAnswered(Opcodes.Client.OUTOFPARTREQS);
        thenTheClientIsAnsweredWithin(13, Duration.ofSeconds(25), Opcodes.Client.ACCEPTUPLOADREQ);
    }

    @Test
    void shouldSendTheFileItsClientSwitchedToInASlot() throws Exception {
        givenAnEngineSharingTwoFiles();
        givenTheClientGetsASlot(14);
        givenTheClientSwitchesToTheSharedFile(14, 1);

        whenTheClientRequestsTheBytesOfTheSharedFile(14, 1, 0, 1000);

        thenTheClientIsSentAPartOfTheSharedFile(1);
        thenTheClientIsSentTheBytesOfTheSharedFile(1, 0, 1000);
    }

    private void givenAnEngine() throws IOException {
        startTheEngine("", 1);
    }

    private void givenAnEngineSharingAFile() throws Exception {
        startTheEngine("", 1);
        this.files = awaitShared(this.engine, 1);
    }

    private void givenAnEngineSharingAFileAtKiBps(int kibps) throws Exception {
        startTheEngine("limits.uploadKiBps=" + kibps + "\n", 1);
        this.files = awaitShared(this.engine, 1);
    }

    private void givenAnEngineSharingAFileVisibleTo(SharedFilesVisibility visibility) throws Exception {
        startTheEngine("share.visibleTo=" + visibility + "\n", 1);
        this.files = awaitShared(this.engine, 1);
    }

    private void givenAnEngineSharingTwoFiles() throws Exception {
        startTheEngine("", 2);
        this.files = awaitShared(this.engine, 2);
    }

    private void givenADownloadWithTheAichHash(String name, Hash16 hash, Hash20 aich) throws Exception {
        this.engine
                .addDownload(new Ed2kLink.FileLink(
                        name, 1_000_000, hash, Optional.of(aich), List.of(), List.of(), List.of()))
                .get(10, TimeUnit.SECONDS);
    }

    private void givenTheUploadLimitIsSetToKiBps(int kibps) throws Exception {
        Thread.sleep(300);
        this.engine
                .updatePreferences(p -> p.toBuilder().maxUploadKiBps(kibps).build())
                .get(10, TimeUnit.SECONDS);
        Thread.sleep(300);
    }

    private void givenTheSharedFileIsRenamed(String name) throws Exception {
        assertThat(this.engine.renameSharedFile(file().hash(), name).get(10, TimeUnit.SECONDS))
                .isTrue();
    }

    private void givenAFriend(int seed) throws Exception {
        this.engine
                .addFriend(new Friend(userHashOf(seed), "pal", Optional.empty()))
                .get(5, TimeUnit.SECONDS);
    }

    private void givenTheFriendSlotIsGivenTo(int seed) throws Exception {
        assertThat(this.engine.setFriendSlot(Optional.of(userHashOf(seed))).get(5, TimeUnit.SECONDS))
                .isTrue();
    }

    private void givenAClient(int seed) throws IOException {
        this.clients.put(seed, client(this.port, seed));
    }

    private void givenClients(int... seeds) throws IOException {
        for (int seed : seeds) {
            givenAClient(seed);
        }
    }

    private void givenAnIdentifyingClient(int seed) throws IOException {
        this.clients.put(seed, identifying(this.port, seed));
    }

    private void givenTheClientsAskForTheFile(int... seeds) throws IOException {
        for (int seed : seeds) {
            Client client = theClient(seed);
            client.send(ClientMessages.forFile(Opcodes.Client.SETREQFILEID, file().hash()));
            assertThat(client.next().opcode()).isEqualTo(Opcodes.Client.FILESTATUS.code());
        }
    }

    private void givenTheClientGetsASlot(int seed) throws IOException {
        assertThat(theClient(seed).ask(file().hash()).opcode()).isEqualTo(Opcodes.Client.ACCEPTUPLOADREQ.code());
    }

    private void givenTheClientIsQueued(int seed) throws IOException {
        assertThat(theClient(seed).ask(file().hash()).opcode()).isEqualTo(Opcodes.Extended.QUEUERANKING.code());
    }

    private void givenTheClientIsQueuedAtRank(int seed, int rank) throws IOException {
        assertThat(ClientMessages.decodeQueueRank(theClient(seed).ask(file().hash())))
                .isEqualTo(rank);
    }

    private void givenTheClientIsQueuedAtRank(int seed, int rank, String description) throws IOException {
        assertThat(ClientMessages.decodeQueueRank(theClient(seed).ask(file().hash())))
                .as(description)
                .isEqualTo(rank);
    }

    private void givenTheClientIsToleratedAskingAgainAtOnce(int seed, int times) throws IOException {
        for (int i = 0; i < times; i++) {
            givenTheClientIsQueued(seed);
        }
    }

    private void givenTheClientIsBannedForAskingOnceMore(int seed) throws IOException {
        Client client = theClient(seed);
        client.send(ClientMessages.forFile(Opcodes.Client.STARTUPLOADREQ, file().hash()));
        assertThat(catchThrowable(client::next)).isInstanceOf(IOException.class);
    }

    private void givenTheClientGetsTheBytes(int seed, long start, long end) throws IOException {
        Client client = theClient(seed);
        requestBytes(client, file().hash(), start, end);
        assertThat(client.next().opcode()).isEqualTo(Opcodes.Client.SENDINGPART.code());
    }

    private void givenTheClientSwitchesToTheSharedFile(int seed, int index) throws IOException {
        Client client = theClient(seed);
        client.send(ClientMessages.forFile(
                Opcodes.Client.SETREQFILEID, this.files.get(index).hash()));
        assertThat(client.next().opcode()).isEqualTo(Opcodes.Client.FILESTATUS.code());
    }

    private void givenTheClientProvesItsKey(int seed) throws IOException {
        theClient(seed).prove();
    }

    private void givenTheClientAskedForOurSharedFiles(int seed) throws IOException {
        theClient(seed).send(askSharedFiles());
    }

    private void givenTheClientIsToldNothingWithin(int seed, Duration wait) throws IOException {
        assertThat(theClient(seed).poll(wait)).isEmpty();
    }

    private void givenTheClientAskedForOurSharedDirectories(int seed) throws IOException {
        Client client = theClient(seed);
        client.send(askSharedDirectories());
        client.next();
    }

    private void givenTheClientAskedForTheFilesOfTheDirectory(int seed, String directory) throws IOException {
        Client client = theClient(seed);
        client.send(askFilesOf(directory));
        client.next();
    }

    private void givenAQueuedClientThatHungUpListeningForACall(int seed) throws IOException {
        this.listening = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        this.resources.add(this.listening);
        Client waiting = client(this.port, seed, this.listening.getLocalPort());
        assertThat(waiting.ask(file().hash()).opcode()).isEqualTo(Opcodes.Extended.QUEUERANKING.code());
        waiting.socket.close();
    }

    private void givenTheClientGivesItsSlotBack(int seed) throws IOException {
        theClient(seed).send(Packet.empty(Protocol.EDONKEY, Opcodes.Client.CANCELTRANSFER.code()));
    }

    private void givenTheEngineCallsTheQueuedClientBack() throws IOException {
        this.listening.setSoTimeout((int) WAIT.toMillis());
        Socket back = this.listening.accept();
        this.resources.add(back);
        back.setSoTimeout((int) WAIT.toMillis());
        this.called = new Client(back);
        assertThat(this.called.next().opcode()).isEqualTo(Opcodes.Client.HELLO.code());
    }

    private void givenAClientAtAnotherAddressClaimingTheUserHashIsIgnored(String address, int seed) throws IOException {
        Client impostor = clientFrom(InetAddress.getByName(address), this.port, seed);
        impostor.send(ClientMessages.forFile(Opcodes.Client.STARTUPLOADREQ, file().hash()));
        assertThat(impostor.poll(Duration.ofSeconds(1))).isEmpty();
    }

    private void givenTheClientIsDroppedForChangingItsUserHashTo(int seed, int otherSeed) throws IOException {
        Client client = theClient(seed);
        client.send(hello(otherSeed, 5000 + otherSeed).encode(false));
        assertThat(catchThrowable(client::next)).isInstanceOf(IOException.class);
    }

    private void whenTheClientAsksForAnUpload(int seed) throws IOException {
        this.answer = theClient(seed).ask(file().hash());
    }

    private void whenTheClientAsksOnceMore(int seed) throws IOException {
        Client client = theClient(seed);
        client.send(ClientMessages.forFile(Opcodes.Client.STARTUPLOADREQ, file().hash()));
        this.exception = catchThrowable(client::next);
    }

    private void whenTheClientRequestsTheBytes(int seed, long start, long end) throws IOException {
        whenTheClientRequestsTheBytesOfTheSharedFile(seed, 0, start, end);
    }

    private void whenTheClientRequestsTheBytesOfTheSharedFile(int seed, int index, long start, long end)
            throws IOException {
        Client client = theClient(seed);
        requestBytes(client, this.files.get(index).hash(), start, end);
        this.answer = client.next();
    }

    private void whenTheClientRequestsTheBytesWaitingAtMost(int seed, long start, long end, Duration wait)
            throws IOException {
        Client client = theClient(seed);
        requestBytes(client, file().hash(), start, end);
        this.polled = client.poll(wait);
    }

    // Ranges are three starts then three ends.
    private void whenTheClientRequestsTheRanges(int seed, long start1, long end1, long start2, long end2)
            throws IOException {
        Client client = theClient(seed);
        client.send(Packet.of(
                Protocol.EDONKEY,
                Opcodes.Client.REQUESTPARTS.code(),
                new PacketWriter()
                        .hash16(file().hash())
                        .u32(start1)
                        .u32(start2)
                        .u32(0)
                        .u32(end1)
                        .u32(end2)
                        .u32(0)));
        this.answer = client.next();
    }

    private void whenTheClientWaitsAtMost(int seed, Duration wait) throws IOException {
        this.polled = theClient(seed).poll(wait);
    }

    private void whenTheBanIsLifted() throws Exception {
        List<BanInfo> bans = this.engine.bans().get(5, TimeUnit.SECONDS);
        this.accepted = this.engine.unban(bans.getFirst().address()).get(5, TimeUnit.SECONDS);
    }

    private void whenTheBanOfTheAddressIsLifted(String address) throws Exception {
        this.accepted = this.engine.unban(address).get(5, TimeUnit.SECONDS);
    }

    private void whenTheFriendSlotIsGivenTo(int seed) throws Exception {
        this.accepted = this.engine.setFriendSlot(Optional.of(userHashOf(seed))).get(5, TimeUnit.SECONDS);
    }

    private void whenTheClientFailsTheProof(int seed) throws IOException {
        Client client = theClient(seed);
        IdentityProof.fake(client.out);
        this.answer = client.next();
    }

    private void whenTheClientProvesItsKey(int seed) throws IOException {
        Client client = theClient(seed);
        client.prove();
        this.answer = client.next();
    }

    private void whenTheClientAsksForOurSharedFiles(int seed) throws IOException {
        Client client = theClient(seed);
        client.send(askSharedFiles());
        this.answer = client.next();
    }

    private void whenTheClientAlsoAsksForAllOurSharedFilesWaitingAtMost(int seed, Duration wait) throws IOException {
        Client client = theClient(seed);
        client.send(askSharedFiles());
        this.polled = client.poll(wait);
    }

    private void whenTheClientAsksForOurSharedDirectories(int seed) throws IOException {
        Client client = theClient(seed);
        client.send(askSharedDirectories());
        this.answer = client.next();
    }

    private void whenTheClientAsksForTheFilesOfTheDirectory(int seed, String directory) throws IOException {
        Client client = theClient(seed);
        client.send(askFilesOf(directory));
        this.answer = client.next();
    }

    private void whenTheClientAsksForTheAichHashOfWaitingAtMost(int seed, Hash16 hash, Duration wait)
            throws IOException {
        Client client = theClient(seed);
        client.send(
                Packet.of(Protocol.EMULE, Opcodes.Extended.AICHFILEHASHREQ.code(), new PacketWriter().hash16(hash)));
        this.polled = client.poll(wait);
    }

    private void whenTheFileIsShared() throws Exception {
        this.files = awaitShared(this.engine, 1);
    }

    private void whenTheUploadPriorityOfTheSharedFileIsSet(UploadPriority priority) throws Exception {
        whenTheUploadPriorityOfTheFileIsSet(file().hash(), priority);
    }

    private void whenTheUploadPriorityOfTheFileIsSet(Hash16 hash, UploadPriority priority) throws Exception {
        this.accepted = this.engine.setUploadPriority(hash, priority).get(5, TimeUnit.SECONDS);
    }

    private void whenTheClientAsksForTheNameOfTheFile(int seed) throws IOException {
        Client client = theClient(seed);
        client.send(ClientMessages.forFile(Opcodes.Client.REQUESTFILENAME, file().hash()));
        this.answer = client.next();
    }

    private void whenTheClientAsksForTheFile(int seed, Hash16 hash) throws IOException {
        Client client = theClient(seed);
        client.send(ClientMessages.forFile(Opcodes.Client.SETREQFILEID, hash));
        this.answer = client.next();
    }

    private void whenTheClientAsksForItsPublicIp(int seed) throws IOException {
        Client client = theClient(seed);
        client.send(Packet.empty(Protocol.EMULE, Opcodes.Extended.PUBLICIP_REQ.code()));
        this.answer = client.next();
    }

    private void whenTheClientAsksForTheAichDataOfPart(int seed, int part) throws IOException {
        Client client = theClient(seed);
        client.send(Packet.of(
                Protocol.EMULE,
                Opcodes.Extended.AICHREQUEST.code(),
                new PacketWriter().hash16(file().hash()).u16(part).bytes(new byte[20])));
        this.answer = client.next();
    }

    private void whenTheClientAsksForSourcesThenForAnUpload(int seed) throws IOException {
        Client client = theClient(seed);
        client.send(SourceExchange.request(file().hash()));
        this.answer = client.ask(file().hash());
        client.send(Packet.empty(Protocol.EDONKEY, Opcodes.Client.CANCELTRANSFER.code()));
    }

    private void whenTheCalledClientSaysHelloAs(int seed) throws IOException {
        this.called.send(hello(seed, this.listening.getLocalPort()).encode(true));
        this.answer = this.called.next();
    }

    private void whenTheAddressConnectsAgain() throws IOException {
        Socket again = new Socket(InetAddress.getLoopbackAddress(), this.port);
        this.resources.add(again);
        again.setSoTimeout((int) WAIT.toMillis());
        this.firstByte = again.getInputStream().read();
    }

    private void thenTheAnswerIs(Opcodes.Opcode opcode) {
        assertThat(this.answer.opcode()).isEqualTo(opcode.code());
    }

    private void thenTheAnswerIsTheQueueRank(int rank) throws IOException {
        assertThat(this.answer.protocol()).isEqualTo(Protocol.EMULE);
        assertThat(this.answer.opcode()).isEqualTo(Opcodes.Extended.QUEUERANKING.code());
        assertThat(ClientMessages.decodeQueueRank(this.answer)).isEqualTo(rank);
    }

    private void thenTheQueueRankIs(int rank, String description) throws IOException {
        assertThat(ClientMessages.decodeQueueRank(this.answer)).as(description).isEqualTo(rank);
    }

    private void thenTheAnswerCarries(int... bytes) {
        assertThat(this.answer.payload()).containsExactly(bytes);
    }

    private void thenTheAnswerStartsWith(int... bytes) {
        assertThat(this.answer.payload()).startsWith(bytes);
    }

    private void thenTheAnswerIsBytesLong(int length) {
        assertThat(this.answer.length()).isEqualTo(length);
    }

    private void thenTheClientIsSentAPartEndingAt(long end) throws IOException {
        assertThat(this.answer.opcode()).isEqualTo(Opcodes.Client.SENDINGPART.code());
        assertThat(ClientMessages.SendingPart.decode(this.answer).end()).isEqualTo(end);
    }

    private void thenTheClientIsSentTheBytes(long start, long end) throws IOException {
        ClientMessages.SendingPart part = ClientMessages.SendingPart.decode(this.answer);
        assertThat(part.start()).isEqualTo(start);
        assertThat(part.end()).isEqualTo(end);
    }

    private void thenTheClientIsSentTheBytesOfTheFileNamed(String name, int start, int end) throws IOException {
        ClientMessages.SendingPart part = ClientMessages.SendingPart.decode(this.answer);
        byte[] expected = Files.readAllBytes(this.dir.resolve("njmule/incoming").resolve(name));
        assertThat(part.data()).isEqualTo(Arrays.copyOfRange(expected, start, end));
    }

    private void thenTheClientIsSentAPartOfTheSharedFile(int index) throws IOException {
        assertThat(ClientMessages.SendingPart.decode(this.answer).file())
                .isEqualTo(this.files.get(index).hash());
    }

    private void thenTheClientIsSentTheBytesOfTheSharedFile(int index, int start, int end) throws IOException {
        thenTheClientIsSentTheBytesOfTheFileNamed(this.files.get(index).name(), start, end);
    }

    private void thenTheClientIsAnswered(Opcodes.Opcode opcode) {
        assertThat(this.polled).hasValueSatisfying(p -> assertThat(p.opcode()).isEqualTo(opcode.code()));
    }

    private void thenTheClientIsAnsweredWithin(int seed, Duration wait, Opcodes.Opcode opcode) throws IOException {
        assertThat(theClient(seed).poll(wait))
                .hasValueSatisfying(p -> assertThat(p.opcode()).isEqualTo(opcode.code()));
    }

    private void thenTheClientIsToldNothing() {
        assertThat(this.polled).isEmpty();
    }

    private void thenTheFileNameIs(String name) throws IOException {
        assertThat(ClientMessages.FileName.decode(this.answer).name()).isEqualTo(name);
    }

    private void thenTheDirectoriesAre(String... names) throws IOException {
        PacketReader in = this.answer.reader();
        assertThat(in.u32()).isEqualTo(names.length);
        for (String name : names) {
            assertThat(in.string16()).isEqualTo(name);
        }
    }

    private void thenTheAnswerListsTheSharedFileIn(String directory) throws IOException {
        PacketReader list = this.answer.reader();
        assertThat(list.string16()).isEqualTo(directory);
        assertThat(list.u32()).isEqualTo(1);
        assertThat(FileEntry.read(list).hash()).isEqualTo(file().hash());
    }

    private void thenTheClientIsToldTheAichHash(Hash16 hash, Hash20 aich) throws IOException {
        assertThat(this.polled).isPresent();
        assertThat(this.polled.get().opcode()).isEqualTo(Opcodes.Extended.AICHFILEHASHANS.code());
        PacketReader in = this.polled.get().reader();
        assertThat(in.hash16()).isEqualTo(hash);
        assertThat(in.hash20()).isEqualTo(aich);
    }

    private void thenTheUploadStatesAre(UploadInfo.State... states) throws Exception {
        assertThat(this.engine.uploads().get(5, TimeUnit.SECONDS))
                .extracting(UploadInfo::state)
                .containsExactly(states);
    }

    private void thenEveryUploadIsOfTheSharedFile() throws Exception {
        assertThat(this.engine.uploads().get(5, TimeUnit.SECONDS))
                .allMatch(u -> u.file().equals(file().name()));
    }

    private void thenTheUploadsTransferredInAll(long bytes) throws Exception {
        assertThat(this.engine.uploads().get(5, TimeUnit.SECONDS).stream()
                        .mapToLong(UploadInfo::transferred)
                        .sum())
                .isEqualTo(bytes);
    }

    private void thenThereAreUploads(int count) throws Exception {
        assertThat(this.engine.uploads().get(5, TimeUnit.SECONDS)).hasSize(count);
    }

    private void thenThereAreNoUploads() throws Exception {
        assertThat(this.engine.uploads().get(5, TimeUnit.SECONDS)).isEmpty();
    }

    private void thenTheStatusCountsUploads(int uploads) throws Exception {
        assertThat(status().uploads()).isEqualTo(uploads);
    }

    private void thenTheStatusCountsNoQueuedClient() throws Exception {
        assertThat(status().queued()).isZero();
    }

    private void thenTheStatusCountsUploadedBytes(long bytes) throws Exception {
        assertThat(status().uploaded()).isEqualTo(bytes);
    }

    private void thenTheStatusCountsSharedFiles(int sharedFiles) throws Exception {
        assertThat(status().sharedFiles()).isEqualTo(sharedFiles);
    }

    private void thenTheStatusShowsNoServer() throws Exception {
        assertThat(status().server()).isEmpty();
    }

    private void thenTheBanShowsWithItsTimeLeft() throws Exception {
        assertThat(this.engine.bans().get(5, TimeUnit.SECONDS))
                .singleElement()
                .satisfies(b -> assertThat(b.secondsLeft()).isPositive());
    }

    private void thenTheBanIsLifted() {
        assertThat(this.accepted).isTrue();
    }

    private void thenNoBanIsLifted() {
        assertThat(this.accepted).isFalse();
    }

    private void thenThereAreNoBans() throws Exception {
        assertThat(this.engine.bans().get(5, TimeUnit.SECONDS)).isEmpty();
    }

    private void thenTheFriendSlotIsRefusedAsNotAFriend() {
        assertThat(this.accepted).as("not a friend").isFalse();
    }

    private void thenTheFriendSlotIsGiven() {
        assertThat(this.accepted).isTrue();
    }

    private void thenTheFriendSlotGoesTo(int seed) throws Exception {
        assertThat(this.engine.friendSlot().get(5, TimeUnit.SECONDS)).contains(userHashOf(seed));
    }

    private void thenThePriorityIsSet() {
        assertThat(this.accepted).isTrue();
    }

    private void thenNoPriorityIsSet() {
        assertThat(this.accepted).isFalse();
    }

    private void thenTheFileIsSharedWithTheUploadPriority(UploadPriority priority) {
        assertThat(file().priority()).isEqualTo(priority);
    }

    private void thenTheSharedFileHasTheUploadPriority(UploadPriority priority) throws Exception {
        assertThat(this.engine.sharedFiles().get(5, TimeUnit.SECONDS))
                .singleElement()
                .extracting(SharedFileInfo::priority)
                .isEqualTo(priority);
    }

    private void thenExceptionOccurred(Class<? extends Throwable> type) {
        assertThat(this.exception).isInstanceOf(type);
    }

    private void thenTheConnectionIsClosedAtOnce() {
        assertThat(this.firstByte).isEqualTo(-1);
    }

    private void startTheEngine(String extra, int sharedFiles) throws IOException {
        this.engine = engine(extra, sharedFiles);
        this.port = this.engine.preferences().tcpPort();
    }

    private SharedFileInfo file() {
        return this.files.getFirst();
    }

    private EngineStatus status() throws Exception {
        return this.engine.status().get(5, TimeUnit.SECONDS);
    }

    // The client of that seed, connected on first use.
    private Client theClient(int seed) throws IOException {
        Client client = this.clients.get(seed);
        if (client == null) {
            client = client(this.port, seed);
            this.clients.put(seed, client);
        }
        return client;
    }

    private static Hash16 userHashOf(int seed) {
        return hello(seed, 5000 + seed).userHash();
    }

    private static void requestBytes(Client client, Hash16 file, long start, long end) throws IOException {
        client.send(new ClientMessages.RequestParts(
                        file,
                        List.of(
                                new ClientMessages.Range(start, end),
                                new ClientMessages.Range(0, 0),
                                new ClientMessages.Range(0, 0)))
                .encode(false));
    }

    private static Packet askSharedFiles() {
        return Packet.empty(Protocol.EDONKEY, Opcodes.Client.ASKSHAREDFILES.code());
    }

    private static Packet askSharedDirectories() {
        return Packet.empty(Protocol.EDONKEY, Opcodes.Client.ASKSHAREDDIRS.code());
    }

    private static Packet askFilesOf(String directory) {
        return Packet.of(
                Protocol.EDONKEY,
                Opcodes.Client.ASKSHAREDFILESDIR.code(),
                new PacketWriter().string16(directory, StringEncoding.UTF8));
    }

    private Client clientFrom(InetAddress local, int port, int seed) throws IOException {
        Socket socket = new Socket(InetAddress.getLoopbackAddress(), port, local, 0);
        socket.setSoTimeout((int) WAIT.toMillis());
        Client client = new Client(socket);
        this.resources.add(socket);
        client.send(hello(seed, 5000 + seed).encode(false));
        assertThat(client.next().opcode()).isEqualTo(Opcodes.Client.HELLOANSWER.code());
        return client;
    }

    private Client client(int port, int seed) throws IOException {
        return client(port, seed, 5000 + seed);
    }

    private Client client(int port, int seed, int listenPort) throws IOException {
        return client(port, seed, listenPort, 0);
    }

    // A client taking part in the secure identification, which it leaves to the test.
    private Client identifying(int port, int seed) throws IOException {
        return client(port, seed, 5000 + seed, IdentityProof.MISC1);
    }

    private Client client(int port, int seed, int listenPort, int miscOptions1) throws IOException {
        Socket socket = new Socket(InetAddress.getLoopbackAddress(), port);
        socket.setSoTimeout((int) WAIT.toMillis());
        Client client = new Client(socket);
        this.resources.add(socket);
        client.send(hello(seed, listenPort, miscOptions1).encode(false));
        Packet answer = client.next();
        assertThat(answer.opcode()).isEqualTo(Opcodes.Client.HELLOANSWER.code());
        client.engine = Hello.decode(answer);
        return client;
    }

    private static Hello hello(int seed, int listenPort) {
        return hello(seed, listenPort, 0);
    }

    private static Hello hello(int seed, int listenPort, int miscOptions1) {
        byte[] hash = new byte[16];
        new Random(seed).nextBytes(hash);
        return Hello.of(Hash16.of(hash), 0, listenPort, "client " + seed, 0, 0, miscOptions1, 0, 0, 0);
    }

    private static List<SharedFileInfo> awaitShared(NjMuleEngine engine, int count) throws Exception {
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (System.nanoTime() < deadline) {
            List<SharedFileInfo> files = engine.sharedFiles().get(10, TimeUnit.SECONDS);
            if (files.size() >= count) {
                return files;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("not shared");
    }

    private NjMuleEngine engine(String extra, int files) throws IOException {
        Path root = this.dir.resolve("njmule");
        AppDirectories dirs = AppDirectories.underRoot(root, true);
        Files.createDirectories(dirs.config());
        Path incoming = Files.createDirectories(root.resolve("incoming"));
        for (int i = 0; i < files; i++) {
            byte[] data = new byte[300_000];
            new Random(5 + i).nextBytes(data);
            Files.write(incoming.resolve(i == 0 ? "queued.bin" : "other" + i + ".bin"), data);
        }
        int port;
        try (ServerSocket probe = new ServerSocket(0)) {
            port = probe.getLocalPort();
        }
        Files.writeString(dirs.config().resolve("njmule.properties"), "net.tcpPort=" + port + "\n" + extra);
        NjMuleEngine engine = NjMuleEngine.start(new EngineConfig(dirs));
        this.resources.add(engine);
        return engine;
    }

    /** A downloader speaking the protocol by hand. */
    private static final class Client {
        private final Socket socket;
        private final InputStream in;
        private final OutputStream out;
        private Hello engine;

        Client(Socket socket) throws IOException {
            this.socket = socket;
            this.in = new BufferedInputStream(socket.getInputStream());
            this.out = socket.getOutputStream();
        }

        void send(Packet packet) throws IOException {
            this.out.write(TcpFraming.encode(packet));
            this.out.flush();
        }

        Packet ask(Hash16 file) throws IOException {
            send(ClientMessages.forFile(Opcodes.Client.STARTUPLOADREQ, file));
            return next();
        }

        // The engine's side of the identification is left unanswered unless the test proves a key.
        Packet next() throws IOException {
            while (true) {
                Packet packet = TcpFraming.read(this.in, FrameLimit.fixed(1 << 20));
                if (!IdentityProof.isIdentification(packet)) {
                    return packet;
                }
            }
        }

        void prove() throws IOException {
            IdentityProof.prove(this.engine, this.in, this.out);
        }

        Optional<Packet> poll(Duration wait) throws IOException {
            int old = this.socket.getSoTimeout();
            this.socket.setSoTimeout((int) wait.toMillis());
            try {
                return Optional.of(next());
            } catch (SocketTimeoutException e) {
                return Optional.empty();
            } finally {
                this.socket.setSoTimeout(old);
            }
        }
    }
}
