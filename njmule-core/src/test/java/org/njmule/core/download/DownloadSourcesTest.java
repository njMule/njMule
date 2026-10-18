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
import java.io.IOException;
import java.net.Inet6Address;
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
import org.njmule.core.protocol.ed2k.ClientReasks;
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
    private static final Hash16 UNKNOWN = Hash16.parseHex("3a".repeat(16));
    private static final int SOURCE_UDP_PORT = 46_720;

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
    private final Udp udp = new Udp();
    private volatile DownloadManager.Settings settings = settings(ObfuscationMode.SUPPORTED, 100, 0);
    private volatile Path incoming;
    private volatile Optional<DownloadManager.KadSources> kad = Optional.empty();
    private DownloadManager.Listener listener = new Events();
    private DownloadManager manager;
    private ConnectionGuard guard;
    private ConnectionGuard.Permit held;
    private ScriptedSource waitingSource;
    private final AtomicReference<Consumer<List<DownloadManager.KadSource>>> found = new AtomicReference<>();
    private byte[] data;
    private Hash16 hash;
    private ScriptedSource source;
    private Optional<Inet6Address> ourIpv6 = Optional.empty();
    private ScriptedSource.Link link;
    private Recovery recovery;
    private ScriptedSource.Link second;
    private Packet request;
    private ScriptedSource missing;
    private ScriptedSource odd;
    private ScriptedSource useless;
    private ScriptedSource silent;
    private int closedPort;
    private ScriptedSource.Link noFile;
    private ScriptedSource.Link tooMany;
    private ScriptedSource.Link nothing;
    private DownloadInfo info;
    private int callbacksBefore;
    private int sourceCount;
    private List<Boolean> results;
    private boolean accepted;
    private boolean passedOn;

    @AfterEach
    void stop() throws Exception {
        if (this.manager != null) {
            onLoop(() -> {
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
    void shouldTakeTheIpv6SourcesOfAnExtendedAnswerWhenWeHaveIpv6() throws Exception {
        givenWeHaveTheIpv6Address("2001:db8::99");
        givenASourceAnnouncingTheIpv6Address(Hello.MISC2_SOURCE_EXCHANGE2, "2001:db8::50");
        givenTheManager();
        givenADownloadFromTheSource("six.bin", Hash16.parseHex("4e".repeat(16)));
        givenWeAskedTheSourceForOtherSourcesTheExtendedWay();

        whenTheSourceAnswersTheExtendedWayWithAHighIdALowIdAndASourceAtAnIpv6AddressAlone();

        thenTheDownloadHasSources(4);
        thenASourceIsShownAt("[2001:db8::61]:" + PORT);
    }

    @Test
    void shouldHearTheCommentAndOtherSourcesOfASourceThatKeepsUsInItsQueue() throws Exception {
        givenASource(1 << Hello.MISC1_AICH_SHIFT, Hello.MISC2_SOURCE_EXCHANGE2);
        givenTheManager();
        givenADownloadFromTheSource("talk.bin", Hash16.parseHex("31".repeat(16)));
        givenWeAskedTheSourceForTheAichHashAndOtherSources();
        givenTheSourceShownAsAsked();
        givenOnlySourcesKnownToTakeObfuscationAsked();
        givenTheSourceToldItsCommentsAndOtherSourcesAndQueuedUsAt(3);
        givenWeWereQueuedAt(3);
        givenTheGoodCommentAndThePublicSourcesItToldOfTaken();
        givenTheAichHashOfOneSourceNotTrusted();
        givenTheSourceNotPassedOnYet();
        givenTheConnectionClosedWhileQueued();
        givenTheSourceCanBePassedOn();
        givenPlainConnectionsAllowedAgain();
        givenTimePassed(DownloadManager.REASK_INTERVAL);
        givenTheSourceAskedAgainButNotForMoreSources();

        whenTheDownloadIsPaused();

        thenTheConnectionCloses();
    }

    @Test
    void shouldAskAQueuedSourceAgainOverUdpShortlyBeforeItsTime() throws Exception {
        givenASourceTakingUdpReasksOnItsPort(SOURCE_UDP_PORT);
        givenTheManagerAskingAgainOverUdp();
        givenADownloadFromTheSource("udp.bin", Hash16.parseHex("41".repeat(16)));
        givenTheSourceQueuedUsAt(5);
        givenTheConnectionClosedWhileQueued();

        whenTimePasses(DownloadManager.REASK_INTERVAL.minusMinutes(1));

        thenTheSourceIsAskedAgainOverUdpForTheFile(SOURCE_UDP_PORT);
    }

    @Test
    void shouldTakeTheRankASourceGivesOverUdp() throws Exception {
        givenAQueuedSourceAskedAgainOverUdp();

        whenTheSourceAnswersOverUdp(new ClientReasks.Ack(Optional.of(ClientMessages.PartStatus.COMPLETE), 2)
                .encode(ClientReasks.UDP_VERSION));

        thenTheSourceShowsTheRank(2);
    }

    @Test
    void shouldNotConnectWhenItsTimeComesToASourceThatAnsweredOverUdp() throws Exception {
        givenAQueuedSourceAskedAgainOverUdp();
        givenTheSourceAnsweredOverUdp(new ClientReasks.Ack(Optional.of(ClientMessages.PartStatus.COMPLETE), 2)
                .encode(ClientReasks.UDP_VERSION));

        whenTimePasses(DownloadManager.UDP_REASK_LEAD);

        thenTheSourceIsNotConnectedToWithin(Duration.ofSeconds(2));
    }

    @Test
    void shouldConnectWhenItsTimeComesToASourceThatDidNotAnswerOverUdp() throws Exception {
        givenAQueuedSourceAskedAgainOverUdp();

        whenTimePasses(DownloadManager.UDP_REASK_LEAD);

        thenTheSourceIsConnectedTo();
    }

    @Test
    void shouldDropASourceThatSaysOverUdpItNoLongerHasTheFile() throws Exception {
        givenAQueuedSourceAskedAgainOverUdp();

        whenTheSourceAnswersOverUdp(ClientReasks.fileNotFound());

        thenTheDownloadHasSources(0);
        thenEachPartIsKnownAtSources(0);
    }

    @Test
    void shouldShowTheNameASourceGivesTheFileWithoutControlCharacters() throws Exception {
        givenASource(0, 0);
        givenTheManager();
        givenADownloadFromTheSource("sintel.mkv", Hash16.parseHex("44".repeat(16)));
        givenTheSourceAskedForTheFile();

        whenTheSourceNamesTheFile("Sintel\t2010.mkv\u0007");

        thenTheSourceGivesTheName("Sintel 2010.mkv");
    }

    @Test
    void shouldTakeNoNameASourceGivesAFileNotAskedFor() throws Exception {
        givenASource(0, 0);
        givenTheManager();
        givenADownloadFromTheSource("sintel.mkv", Hash16.parseHex("45".repeat(16)));
        givenTheSourceAskedForTheFile();
        givenTheSourceNamedTheFile("Sintel.mkv");

        whenTheSourceNamesAnotherFileBeforeTellingItsParts("Other.mkv");

        thenTheSourceGivesTheName("Sintel.mkv");
    }

    @Test
    void shouldCountThePartsOfASourceThatKeepsUsQueuedInTheirRarity() throws Exception {
        givenASource(0, 0);
        givenTheManager();
        givenADownloadFromTheSource("rare.bin", Hash16.parseHex("43".repeat(16)));
        givenTheSourceQueuedUsAt(5);

        whenTheConnectionClosesWhileQueued();

        thenEachPartIsKnownAtSources(1);
    }

    @Test
    void shouldAskASourceThatEndsOurSlotForTheSameBlocksAndFailAFileThatCannotBeMovedUntilResumed() throws Exception {
        givenTheData(random(5000, 3));
        givenAFileWhereTheIncomingDirectoryShouldBe();
        givenASource(0, 0);
        givenTheManager();
        givenADownloadOfTheDataFromTheSource("data.bin");
        givenTheSourceGaveUsASlot();
        givenTheWholeFileAskedFor();
        givenTheSourceShownAsDownloading();
        givenTheSourceEndedOurSlot();
        givenTheSourceGaveUsTheSlotAgain();
        givenTheBlocksLetGoAskedForAgain();
        givenTheSourceSentTheFileAmongDataThatDoesNotFit();
        givenTheDownloadFailedWith("cannot move");
        givenTheDownloadInError();
        givenItsSessionsEnded();
        givenTheIncomingDirectoryFreed();

        whenTheDownloadIsResumed();

        thenItIsAccepted();
        thenTheDownloadCompletesWithTheData();
        thenTheDownloadIsGone();
    }

    @Test
    void shouldIgnoreWhatDoesNotFitFromASourceUntilItBreaksTheProtocol() throws Exception {
        givenTheData(random(5000, 4));
        givenASource(0, 0);
        givenTheManager();
        givenADownloadOfTheDataFromTheSource("odd.bin");
        givenWeAskedTheSourceForTheFile();
        givenTheSourceSentWhatDoesNotFitBeforeQueuingUs();
        givenWeWereQueuedWithNothingOfItTaken();
        givenTheSourceEndedASlotNotGivenYetThenGaveUsOne();
        givenTheSourceSentDataOfNoBlockAskedForThenTheFirstBytes();

        whenTheSourceSendsAStatusThatDoesNotDecode();

        thenTheConnectionCloses();
        thenTheDownloadHasSources(0);
        thenTheDownloadHasCompleted(1000);
    }

    @Test
    void shouldRecoverACorruptBlockWithTheAichDataOfASourceThatComesLater() throws Exception {
        givenADownloadWithACorruptPartHeldForItsRecoveryData(5);
        givenTheSecondSourceAskedForRecoveryData();
        givenTheRecoveryDataAskedForTheFile();
        givenTheSecondSourceToldAnotherAichHash();
        givenTheSecondSourceSentTheRecoveryDataOfThePart();
        givenOnlyTheCorruptBlockAskedForAgain();

        whenTheFirstSourceSendsTheBlockAgain();

        thenTheDownloadCompletesWithTheData();
        thenTheFileIsNoLongerFound();
    }

    @Test
    void shouldDownloadThePartAgainWholeWithRecoveryDataThatProvesNothing() throws Exception {
        givenADownloadWithACorruptPartHeldForItsRecoveryData(6);
        givenTheSecondSourceAskedForRecoveryData();
        givenTheSecondSourceSentRecoveryDataThatProvesNothing();
        givenThePartAskedForAgainWhole();

        whenTheFirstSourceSendsThePartCorruptAgain();

        thenThePartIsAskedForAgainWhole();
        thenTheDownloadCanBePaused();
    }

    @Test
    void shouldDropSourcesWithoutTheFileOrOutOfReach() throws Exception {
        givenSourcesWithoutTheFileAndOutOfReach();
        givenTheManager();
        givenADownloadFromAllOfThem(Hash16.parseHex("34".repeat(16)));
        givenTheSourcesAnsweredWithoutAFileWeCanUse();
        givenTheSilentSourceRefusedUs();
        givenTheSourcesWithoutAFileWeCanUseHungUpOn();
        givenTheUselessAndTheUnreachableSourcesKept();
        givenTheUnreachableSourcesFailedTwice();

        whenTheUnreachableSourcesAreAskedAgain();

        thenOnlyTheUselessSourceIsLeft();
        thenTheDownloadCanBePaused();
    }

    @Test
    void shouldAskFirewalledSourcesThroughTheServerOneAtATimeAndNotForAPausedDownload() throws Exception {
        givenTheManager();
        givenADownload("lowid.bin", Hash16.parseHex("36".repeat(16)));
        givenAFirewalledSourceTheServerCannotAsk(6000);
        givenTheServerFoundFirewalledSources();
        givenEachAskedForOnceOneATick();
        givenOneTheServerCouldNotAskAskedAgainWhenNamedAgain();
        givenOneThatDidNotCallBackInTimeAskedAgainWhenNamedAgain();
        givenTheDownloadPaused();

        whenTheServerFinds(new ServerMessages.Source(8000, PORT, 0, Optional.empty()));

        thenNoMoreCallbacksAreAskedFor();
    }

    @Test
    void shouldTakeNoMoreFirewalledSourcesIntoAFullSourceList() throws Exception {
        givenAMaximumOfSources(1);
        givenASource(0, Hello.MISC2_SOURCE_EXCHANGE2);
        givenTheManager();
        givenADownloadFromTheSource("full.bin", Hash16.parseHex("38".repeat(16)));
        givenTheSourceAskedForNoMoreSources();

        whenTheServerFinds(new ServerMessages.Source(5000, PORT, 0, Optional.empty()));

        thenNoCallbackIsAskedFor();
    }

    @Test
    void shouldTakeAgainAfterARestartTheSourceThatQueuedUs() throws Exception {
        givenASource(0, 0);
        givenTheManager();
        givenADownloadFromTheSource("kept.bin", Hash16.parseHex("3a".repeat(16)));
        givenTheSourceQueuedUsAt(3);

        whenTheManagerStartsAgain();

        thenTheSourceIsKnownAt("127.0.0.1");
    }

    @Test
    void shouldKeepNoSourceOfAStoppedDownloadForTheNextRun() throws Exception {
        givenASource(0, 0);
        givenTheManager();
        givenADownloadFromTheSource("stopped.bin", Hash16.parseHex("3b".repeat(16)));
        givenTheSourceQueuedUsAt(3);
        givenTheDownloadStopped();

        whenTheManagerStartsAgain();

        thenTheDownloadHasSources(0);
    }

    @Test
    void shouldTakeNoSourceByHandIntoAFullSourceList() throws Exception {
        givenAMaximumOfSources(1);
        givenASource(0, Hello.MISC2_SOURCE_EXCHANGE2);
        givenTheManager();
        givenADownloadFromTheSource("full.bin", Hash16.parseHex("38".repeat(16)));
        givenTheSourceAskedForNoMoreSources();

        whenSourcesAreAddedByHand(new InetSocketAddress(InetAddresses.forString("127.0.0.2"), PORT));

        thenEveryOneIsRefused();
    }

    @Test
    void shouldLookUpASourceNamedByItsHost() throws Exception {
        givenASource(0, 0);
        givenTheManager();

        whenADownloadNamesTheSourceBy("localhost");

        thenTheSourceIsConnectedTo();
        thenTheSourceIsKnownAt("127.0.0.1");
        thenTheDownloadCanBePaused();
    }

    @Test
    void shouldHoldEveryDownloadOnADiskShortOfSpaceFromTheStart() throws Exception {
        givenAMinimumFreeSpaceOf(Long.MAX_VALUE);
        givenAListenerThatDoesNotCareAboutTheDisk();
        givenTheManager();

        whenADownloadIsAdded("held.bin", Hash16.parseHex("3e".repeat(16)));

        thenTheDownloadIsAddedAs(DownloadInfo.Status.NO_SPACE);
    }

    @Test
    void shouldPassALanSourceOnToNoClientWithoutAnIpv4Address() {
        whenASourceIsOfferedToAClient(Ipv4.of(10, 0, 0, 1), InetAddresses.forString("::1"));

        thenItIsNotPassedOn();
    }

    @Test
    void shouldLeaveOutTheKadSourcesOfAStoppedDownload() throws Exception {
        givenKadThatLooksForSources();
        givenTheManager();
        givenADownload("kad.bin", Hash16.parseHex("39".repeat(16)));
        givenKadLookedForItsSources();

        whenKadFindsASourceOnceTheDownloadIsStopped();

        thenTheDownloadGotNoSource();
    }

    @Test
    void shouldChangeNothingForAnUnknownDownload() throws Exception {
        givenTheManager();
        givenAPausedDownload("known.bin", Hash16.parseHex("3b".repeat(16)));

        whenEveryChangeIsAskedForTheUnknownDownload();

        thenEveryOneIsRefused();
        thenTheUnknownDownloadHasNoComments();
        thenTheUnknownDownloadHasNoSourceToPassOn();
    }

    @Test
    void shouldRefuseSourcesByHandWithoutAPortOrAtAnAddressNoHostHas() throws Exception {
        givenTheManager();
        givenAPausedDownload("known.bin", Hash16.parseHex("3b".repeat(16)));

        whenSourcesAreAddedByHand(
                new InetSocketAddress(InetAddresses.forString("ff02::1"), PORT),
                new InetSocketAddress(InetAddresses.forString("::"), PORT),
                new InetSocketAddress(InetAddresses.forString("127.0.0.1"), 0));

        thenEveryOneIsRefused();
        thenThePausedDownloadHasNoSourceToPassOn();
    }

    @Test
    void shouldTakeAnIpv6SourceByHandAndShowItInBrackets() throws Exception {
        givenTheManager();
        givenAPausedDownload("six.bin", Hash16.parseHex("4f".repeat(16)));

        whenSourcesAreAddedByHand(new InetSocketAddress(InetAddresses.forString("2001:db8::5"), PORT));

        thenEveryOneIsTaken();
        thenTheFirstSourceIsShownAt("[2001:db8::5]:" + PORT);
    }

    @Test
    void shouldSetAPausedDownloadToPreviewFirst() throws Exception {
        givenTheManager();
        givenAPausedDownload("known.bin", Hash16.parseHex("3b".repeat(16)));

        whenTheDownloadIsSetToPreviewFirst();

        thenItIsAccepted();
        thenTheDownloadIsPreviewedFirst();
    }

    @Test
    void shouldAcceptAStopByScheduleOfADownloadStoppedAlready() throws Exception {
        givenTheManager();
        givenAPausedDownload("known.bin", Hash16.parseHex("3b".repeat(16)));
        givenTheDownloadStoppedBySchedule();

        whenTheDownloadIsStoppedByScheduleAgain();

        thenTheStopIsAcceptedAsStoppedAlready();
        thenTheStatusOfTheDownloadIs(DownloadInfo.Status.STOPPED);
    }

    @Test
    void shouldSetTheCategoryOfAStoppedDownload() throws Exception {
        givenTheManager();
        givenAPausedDownload("known.bin", Hash16.parseHex("3b".repeat(16)));
        givenTheDownloadStoppedBySchedule();

        whenTheCategoryIsSet(Category.NONE);

        thenItIsAccepted();
    }

    @Test
    void shouldLetDownloadsWaitWhileTheDiskIsShortOfSpace() throws Exception {
        givenASource(0, 0);
        givenTheManager();
        givenADownloadFromTheSource("space.bin", Hash16.parseHex("3c".repeat(16)));
        givenWeAskedTheSourceForAnUploadSlot();
        givenAMinimumFreeSpaceOf(Long.MAX_VALUE);
        givenTheDownloadsWaitForSpace();
        givenAMinimumFreeSpaceOf(0);
        givenTheDiskHasRoomAgain();

        whenTimePasses(DownloadManager.EARLIEST_REASK);

        thenTheSourceIsAskedAgain();
        thenTheDownloadCanBePaused();
    }

    @Test
    void shouldLetASourceWaitWhileOurConnectionsAreAtTheirLimit() throws Exception {
        givenOurConnectionsAtTheirLimit();
        givenADownloadWhoseSourceIsNotCalled();

        whenAConnectionOfOursCloses();

        thenTheSourceIsCalled();
    }

    private void givenTheData(byte[] bytes) {
        this.data = bytes;
    }

    private void givenAFileWhereTheIncomingDirectoryShouldBe() throws IOException {
        this.incoming = Files.writeString(this.dir.resolve("blocker"), "a file where the directory should be");
    }

    private void givenASource(int misc1, int misc2) throws Exception {
        this.source = source(misc1, misc2);
    }

    private void givenASourceAnnouncingTheIpv6Address(int misc2, String address) throws Exception {
        this.source = source(0, misc2);
        this.source.announceIpv6((Inet6Address) InetAddresses.forString(address));
    }

    private void givenWeHaveTheIpv6Address(String address) {
        this.ourIpv6 = Optional.of((Inet6Address) InetAddresses.forString(address));
    }

    private void givenTheManager() throws Exception {
        manager();
    }

    private void givenASourceTakingUdpReasksOnItsPort(int udpPort) throws Exception {
        this.source = new ScriptedSource(PEER, ClientReasks.UDP_VERSION << Hello.MISC1_UDP_VERSION_SHIFT, 0, udpPort);
        this.resources.add(this.source);
    }

    private void givenTheManagerAskingAgainOverUdp() throws Exception {
        manager();
        onLoop(() -> {
            this.manager.reaskOverUdp(this.udp);
            return null;
        });
    }

    private void givenTheSourceQueuedUsAt(int rank) throws Exception {
        this.link = accept(this.source);
        this.link.until(Opcodes.Client.SETREQFILEID);
        this.link.send(new ClientMessages.FileStatus(this.hash, 0, new BitSet()).encode());
        this.link.until(Opcodes.Client.STARTUPLOADREQ);
        this.link.send(Packet.of(
                Protocol.EMULE,
                Opcodes.Extended.QUEUERANKING.code(),
                new PacketWriter().u16(rank).bytes(new byte[10])));
        awaitSource(this.source, s -> s.state() == SourceInfo.State.QUEUED && s.queueRank() == rank);
    }

    // Queued at 5 and closed, then a minute before its time.
    private void givenAQueuedSourceAskedAgainOverUdp() throws Exception {
        givenASourceTakingUdpReasksOnItsPort(SOURCE_UDP_PORT);
        givenTheManagerAskingAgainOverUdp();
        givenADownloadFromTheSource("udp.bin", Hash16.parseHex("41".repeat(16)));
        givenTheSourceQueuedUsAt(5);
        givenTheConnectionClosedWhileQueued();
        advance(DownloadManager.REASK_INTERVAL.minusMinutes(1));
        thenTheSourceIsAskedAgainOverUdpForTheFile(SOURCE_UDP_PORT);
    }

    private void givenTheSourceAskedForTheFile() throws Exception {
        this.link = accept(this.source);
        this.link.until(Opcodes.Client.SETREQFILEID);
    }

    private void givenTheSourceNamedTheFile(String name) throws Exception {
        whenTheSourceNamesTheFile(name);
        thenTheSourceGivesTheName(name);
    }

    private void whenTheSourceNamesTheFile(String name) throws Exception {
        this.link.send(new ClientMessages.FileName(this.hash, name).encode());
    }

    // The parts come after the name: once they are known, the name was read.
    private void whenTheSourceNamesAnotherFileBeforeTellingItsParts(String name) throws Exception {
        this.link.send(new ClientMessages.FileName(Hash16.parseHex("46".repeat(16)), name).encode());
        this.link.send(new ClientMessages.FileStatus(this.hash, 0, new BitSet()).encode());
        awaitSource(this.source, s -> s.parts() > 0);
    }

    private void thenTheSourceGivesTheName(String name) throws Exception {
        awaitSource(this.source, s -> s.reportedName().isPresent());
        assertThat(awaitSource(this.source, s -> true).reportedName()).contains(name);
    }

    private void givenTheSourceAnsweredOverUdp(Packet answer) throws Exception {
        whenTheSourceAnswersOverUdp(answer);
        thenTheSourceShowsTheRank(2);
    }

    private void whenTheSourceAnswersOverUdp(Packet answer) throws Exception {
        onLoop(() -> {
            this.manager.reaskAnswered(Ipv4.parse("127.0.0.1"), SOURCE_UDP_PORT, answer);
            return null;
        });
    }

    private void whenTheConnectionClosesWhileQueued() throws Exception {
        givenTheConnectionClosedWhileQueued();
    }

    private void thenEachPartIsKnownAtSources(int sources) throws Exception {
        assertThat(onLoop(() ->
                        this.manager.download(this.hash).orElseThrow().parts().sources()))
                .containsOnly(sources);
    }

    private void thenTheSourceIsAskedAgainOverUdpForTheFile(int udpPort) throws Exception {
        Udp.Sent sent = this.udp.sent.poll(WAIT.toSeconds(), TimeUnit.SECONDS);
        assertThat(sent).as("a re-ask over UDP").isNotNull();
        assertThat(sent.to()).isEqualTo(new InetSocketAddress(InetAddress.getLoopbackAddress(), udpPort));
        assertThat(sent.packet().opcode()).isEqualTo(Opcodes.ClientUdp.REASKFILEPING.code());
        assertThat(ClientReasks.Ping.file(sent.packet())).isEqualTo(this.hash);
    }

    private void thenTheSourceShowsTheRank(int rank) throws Exception {
        awaitSource(this.source, s -> s.queueRank() == rank);
    }

    private void thenTheSourceIsNotConnectedToWithin(Duration wait) throws Exception {
        assertThat(this.source.isConnectedToWithin(wait)).isFalse();
    }

    private void givenADownloadFromTheSource(String name, Hash16 file) throws Exception {
        this.hash = file;
        onLoop(() -> this.manager.add(this.source.link(name, 5000, file)));
    }

    private void givenADownloadOfTheDataFromTheSource(String name) throws Exception {
        this.hash = Ed2k.md4(this.data, 0, this.data.length);
        onLoop(() -> this.manager.add(this.source.link(name, this.data.length, this.hash)));
    }

    private void givenADownload(String name, Hash16 file) throws Exception {
        this.hash = file;
        onLoop(() -> this.manager.add(Ed2kLink.FileLink.of(name, 5000, file)));
    }

    private void givenAPausedDownload(String name, Hash16 file) throws Exception {
        this.hash = file;
        onLoop(() -> {
            this.manager.add(Ed2kLink.FileLink.of(name, 5000, file));
            return this.manager.pause(file);
        });
    }

    private void givenWeAskedTheSourceForOtherSourcesTheExtendedWay() throws Exception {
        this.link = accept(this.source);
        List<Packet> asked = this.link.until(Opcodes.Extended.REQUESTSOURCES2);
        assertThat(asked.getLast().payload()[0])
                .as("version 1 asks for the extended form")
                .isEqualTo((byte) SourceExchange.EXTENDED_VERSION);
    }

    private void givenWeAskedTheSourceForTheAichHashAndOtherSources() throws Exception {
        this.link = accept(this.source);
        assertThat(names(this.link.until(Opcodes.Extended.REQUESTSOURCES2)))
                .as("a source able to is asked for the AICH hash and for other sources")
                .containsExactly("REQUESTFILENAME", "SETREQFILEID", "AICHFILEHASHREQ", "REQUESTSOURCES2");
    }

    private void givenTheSourceShownAsAsked() throws Exception {
        SourceInfo asking = awaitSource(this.source, s -> s.state() == SourceInfo.State.ASKING);
        assertThat(asking.nick()).contains("scripted");
        assertThat(onLoop(this.manager::describe)).contains("talk.bin missing 5000:", "REQUESTING");
    }

    // From now on only sources known to take obfuscation are asked: none of those it tells of, nor this one.
    private void givenOnlySourcesKnownToTakeObfuscationAsked() {
        this.settings = settings(ObfuscationMode.REQUIRED, 100, 0);
    }

    private void givenTheSourceToldItsCommentsAndOtherSourcesAndQueuedUsAt(int rank) throws Exception {
        this.link.send(FileDescription.encode(FileComment.of("a fine copy", 4)));
        this.link.send(FileDescription.encode(FileComment.of("free SPAM inside", 1)));
        this.link.send(FileDescription.encode(FileComment.NONE));
        this.link.send(aichHash(Hash16.parseHex("32".repeat(16)), Hash20.parseBase32("A".repeat(32))));
        this.link.send(aichHash(this.hash, Hash20.parseBase32("A".repeat(32))));
        this.link.send(new SourceExchange.Answer(
                        4,
                        this.hash,
                        List.of(
                                new SourceExchange.Source(0, 1234, PORT, 0, 0, Optional.empty(), 0),
                                exchanged(BLOCKED_IP),
                                exchanged(Ipv4.of(10, 1, 2, 3)),
                                exchanged(Ipv4.of(192, 0, 2, 50)),
                                exchanged(Ipv4.of(192, 0, 2, 51))))
                .encode());
        this.link.send(new SourceExchange.Answer(4, this.hash, List.of(exchanged(Ipv4.of(192, 0, 2, 52)))).encode());
        this.link.send(new ClientMessages.FileStatus(Hash16.parseHex("32".repeat(16)), 0, new BitSet()).encode());
        this.link.send(new ClientMessages.FileStatus(this.hash, 0, new BitSet()).encode());
        this.link.until(Opcodes.Client.STARTUPLOADREQ);
        this.link.send(Packet.of(Protocol.EDONKEY, Opcodes.Client.QUEUERANK.code(), new PacketWriter().u32(rank)));
    }

    private void whenTheSourceAnswersTheExtendedWayWithAHighIdALowIdAndASourceAtAnIpv6AddressAlone() throws Exception {
        this.link.send(new SourceExchange.Answer(
                        SourceExchange.EXTENDED_VERSION,
                        this.hash,
                        List.of(
                                new SourceExchange.Source(
                                        Ipv4.of(192, 0, 2, 60),
                                        0,
                                        PORT,
                                        0,
                                        0,
                                        Optional.empty(),
                                        0,
                                        ipv6("2001:db8::60")),
                                SourceExchange.Source.ipv6Only(
                                        (Inet6Address) InetAddresses.forString("2001:db8::61"), PORT, Optional.empty()),
                                new SourceExchange.Source(
                                        0, 1234, PORT, 0, 0, Optional.empty(), 0, ipv6("2001:db8::62")),
                                // Ourselves, at our own IPv6 address.
                                SourceExchange.Source.ipv6Only(
                                        (Inet6Address) InetAddresses.forString("2001:db8::99"),
                                        PORT,
                                        Optional.empty())))
                .encodeExtended());
    }

    // Queued from the upload request on; the rank comes after.
    private void givenWeWereQueuedAt(int rank) throws Exception {
        SourceInfo queued = awaitSource(this.source, s -> s.state() == SourceInfo.State.QUEUED && s.queueRank() != 0);
        assertThat(queued.queueRank()).isEqualTo(rank);
        assertThat(queued.parts()).isEqualTo(1);
    }

    private void givenTheGoodCommentAndThePublicSourcesItToldOfTaken() throws Exception {
        assertThat(onLoop(() -> this.manager.download(this.hash).orElseThrow().sources()))
                .as("the public sources it told of, not the LowID, the blocked or the LAN one, nor those of an answer"
                        + " not asked for")
                .isEqualTo(3);
        assertThat(onLoop(() -> this.manager.comments(this.hash)))
                .singleElement()
                .satisfies(c -> {
                    assertThat(c.user()).isEqualTo("scripted");
                    assertThat(c.comment()).isEqualTo(FileComment.of("a fine copy", 4));
                });
    }

    private void givenTheAichHashOfOneSourceNotTrusted() throws Exception {
        assertThat(onLoop(
                        () -> this.manager.find(this.hash).orElseThrow().file().aichHash()))
                .as("one source alone is no reason to trust a hash")
                .isEmpty();
    }

    private void givenTheSourceNotPassedOnYet() throws Exception {
        assertThat(onLoop(() -> this.manager.exchangeable(this.hash)))
                .as("a source counts once it said which parts it has")
                .isEmpty();
    }

    // The connection closes while queued: the place in the queue stays, and the source can be passed on.
    private void givenTheConnectionClosedWhileQueued() throws Exception {
        this.link.close();
        awaitSource(this.source, s -> s.state() == SourceInfo.State.WAITING);
    }

    private void givenTheSourceCanBePassedOn() throws Exception {
        assertThat(onLoop(() -> this.manager.exchangeable(this.hash)))
                .singleElement()
                .satisfies(s -> {
                    assertThat(s.port()).isEqualTo(this.source.port());
                    assertThat(s.userHash()).contains(PEER);
                });
    }

    // Asked again in time, plain once allowed: not for more sources, asked for less than 40 minutes ago.
    private void givenPlainConnectionsAllowedAgain() {
        this.settings = settings(ObfuscationMode.SUPPORTED, 100, 0);
    }

    private void givenTimePassed(Duration duration) {
        advance(duration);
    }

    private void givenTheSourceAskedAgainButNotForMoreSources() throws Exception {
        assertThat(names(askForAnUploadSlot())).doesNotContain("REQUESTSOURCES2");
    }

    private void givenTheSourceGaveUsASlot() throws Exception {
        askForAnUploadSlot();
        this.link.send(Packet.empty(Protocol.EDONKEY, Opcodes.Client.ACCEPTUPLOADREQ.code()));
    }

    private void givenTheWholeFileAskedFor() throws Exception {
        assertThat(requested(this.link)).containsExactly(new ClientMessages.Range(0, this.data.length));
    }

    private void givenTheSourceShownAsDownloading() throws Exception {
        awaitSource(this.source, s -> s.state() == SourceInfo.State.DOWNLOADING);
        assertThat(onLoop(() -> this.manager.download(this.hash).orElseThrow().transferring()))
                .isEqualTo(1);
        assertThat(onLoop(this.manager::describe)).contains("DOWNLOADING [0-5000 missing");
    }

    private void givenTheSourceEndedOurSlot() throws Exception {
        this.link.send(Packet.empty(Protocol.EDONKEY, Opcodes.Client.OUTOFPARTREQS.code()));
        awaitSource(this.source, s -> s.state() == SourceInfo.State.QUEUED);
    }

    private void givenTheSourceGaveUsTheSlotAgain() throws Exception {
        this.link.send(Packet.empty(Protocol.EDONKEY, Opcodes.Client.ACCEPTUPLOADREQ.code()));
    }

    private void givenTheBlocksLetGoAskedForAgain() throws Exception {
        assertThat(requested(this.link))
                .as("the blocks let go are asked for again")
                .containsExactly(new ClientMessages.Range(0, this.data.length));
    }

    private void givenTheSourceSentTheFileAmongDataThatDoesNotFit() throws Exception {
        this.link.send(
                new ClientMessages.SendingPart(Hash16.parseHex("33".repeat(16)), 0, 10, new byte[10]).encode(false));
        this.link.send(new ClientMessages.SendingPart(this.hash, 4990, 5010, new byte[20]).encode(false));
        this.link.send(new ClientMessages.SendingPart(this.hash, 0, this.data.length, this.data).encode(true));
    }

    private void givenTheDownloadFailedWith(String reason) throws InterruptedException {
        assertThat(await("failed")).contains(reason);
    }

    private void givenTheDownloadInError() throws Exception {
        assertThat(onLoop(() -> this.manager.download(this.hash).orElseThrow().status()))
                .isEqualTo(DownloadInfo.Status.ERROR);
    }

    private void givenItsSessionsEnded() throws Exception {
        assertThat(this.link.closesWithin(WAIT)).as("its sessions end").isTrue();
    }

    private void givenTheIncomingDirectoryFreed() throws IOException {
        Files.delete(this.incoming);
    }

    private void givenWeAskedTheSourceForTheFile() throws Exception {
        this.link = accept(this.source);
        this.link.until(Opcodes.Client.SETREQFILEID);
    }

    // Nothing of this changes anything: a rank before the queue, an answer not asked for, a hash set not needed.
    private void givenTheSourceSentWhatDoesNotFitBeforeQueuingUs() throws Exception {
        this.link.send(Packet.of(
                Protocol.EMULE,
                Opcodes.Extended.QUEUERANKING.code(),
                new PacketWriter().u16(9).bytes(new byte[10])));
        this.link.send(Packet.of(
                Protocol.EMULE,
                Opcodes.Extended.AICHANSWER.code(),
                new PacketWriter().hash16(this.hash).u16(0).hash20(Hash20.parseBase32("B".repeat(32)))));
        this.link.send(new ClientMessages.HashSet(this.hash, List.of(Hash16.parseHex("36".repeat(16)))).encode());
        this.link.send(new ClientMessages.FileStatus(this.hash, 0, new BitSet()).encode());
        this.link.send(new ClientMessages.FileStatus(this.hash, 0, new BitSet()).encode());
        this.link.until(Opcodes.Client.STARTUPLOADREQ);
    }

    private void givenWeWereQueuedWithNothingOfItTaken() throws Exception {
        assertThat(awaitSource(this.source, s -> s.state() == SourceInfo.State.QUEUED)
                        .queueRank())
                .isZero();
        assertThat(onLoop(
                        () -> this.manager.find(this.hash).orElseThrow().file().partHashes()))
                .isEmpty();
    }

    // Neither does the end of a slot not given yet, nor data of no block asked for.
    private void givenTheSourceEndedASlotNotGivenYetThenGaveUsOne() throws Exception {
        this.link.send(Packet.empty(Protocol.EDONKEY, Opcodes.Client.OUTOFPARTREQS.code()));
        this.link.send(Packet.empty(Protocol.EDONKEY, Opcodes.Client.ACCEPTUPLOADREQ.code()));
        assertThat(requested(this.link)).containsExactly(new ClientMessages.Range(0, this.data.length));
    }

    private void givenTheSourceSentDataOfNoBlockAskedForThenTheFirstBytes() throws Exception {
        this.link.send(new ClientMessages.CompressedPart(this.hash, 7, 10, new byte[10]).encode(false));
        this.link.send(
                new ClientMessages.CompressedPart(Hash16.parseHex("37".repeat(16)), 0, 10, new byte[10]).encode(false));
        this.link.send(new ClientMessages.SendingPart(this.hash, 0, 0, new byte[0]).encode(false));
        this.link.send(
                new ClientMessages.SendingPart(this.hash, 0, 1000, Arrays.copyOf(this.data, 1000)).encode(false));
        await(
                "the first bytes written",
                () -> this.manager.download(this.hash).orElseThrow().completed() == 1000);
    }

    private void givenADownloadWithACorruptPartHeldForItsRecoveryData(long seed) throws Exception {
        this.recovery = recovery(seed);
        this.hash = this.recovery.link.hash();
        this.data = this.recovery.data;
    }

    private void givenTheSecondSourceAskedForRecoveryData() throws Exception {
        this.second = accept(this.recovery.helper);
        this.second.until(Opcodes.Client.SETREQFILEID);
        this.second.send(new ClientMessages.FileStatus(this.hash, 0, new BitSet()).encode());
        this.request = this.second.until(Opcodes.Extended.AICHREQUEST).getLast();
    }

    private void givenTheRecoveryDataAskedForTheFile() throws Exception {
        assertThat(this.request.reader().hash16()).isEqualTo(this.hash);
    }

    private void givenTheSecondSourceToldAnotherAichHash() throws Exception {
        this.second.send(aichHash(this.hash, Hash20.parseBase32("F".repeat(32))));
    }

    private void givenTheSecondSourceSentTheRecoveryDataOfThePart() throws Exception {
        this.second.send(
                aichAnswer(this.hash, this.recovery.tree.masterHash(), AichRecovery.create(this.recovery.tree, 0)));
    }

    private void givenOnlyTheCorruptBlockAskedForAgain() throws Exception {
        assertThat(requested(this.recovery.first))
                .as("only the corrupt block is downloaded again")
                .containsExactly(new ClientMessages.Range(2L * Ed2k.EMBLOCKSIZE, this.data.length));
    }

    private void givenTheSecondSourceSentRecoveryDataThatProvesNothing() throws Exception {
        this.second.send(aichAnswer(this.hash, this.recovery.tree.masterHash(), random(200, 7)));
    }

    private void givenThePartAskedForAgainWhole() throws Exception {
        assertThat(requested(this.recovery.first)).containsExactlyElementsOf(wholePart());
    }

    private void givenSourcesWithoutTheFileAndOutOfReach() throws Exception {
        this.missing = source(0, 0);
        this.odd = source(0, 0);
        this.useless = source(0, 0);
        this.silent = source(0, 0);
        try (ServerSocket probe = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            this.closedPort = probe.getLocalPort();
        }
    }

    private void givenADownloadFromAllOfThem(Hash16 file) throws Exception {
        this.hash = file;
        Ed2kLink.FileLink gone = new Ed2kLink.FileLink(
                "gone.bin",
                5000,
                file,
                Optional.empty(),
                List.of(),
                List.of(
                        new Ed2kLink.Source("127.0.0.1", this.missing.port()),
                        new Ed2kLink.Source("127.0.0.1", this.odd.port()),
                        new Ed2kLink.Source("127.0.0.1", this.useless.port()),
                        new Ed2kLink.Source("127.0.0.1", this.silent.port()),
                        new Ed2kLink.Source("127.0.0.1", this.closedPort)),
                List.of());
        onLoop(() -> this.manager.add(gone));
    }

    private void givenTheSourcesAnsweredWithoutAFileWeCanUse() throws Exception {
        this.noFile = accept(this.missing);
        this.noFile.until(Opcodes.Client.SETREQFILEID);
        this.noFile.send(ClientMessages.forFile(Opcodes.Client.FILEREQANSNOFIL, Hash16.parseHex("35".repeat(16))));
        this.noFile.send(ClientMessages.forFile(Opcodes.Client.FILEREQANSNOFIL, this.hash));
        this.tooMany = accept(this.odd);
        this.tooMany.until(Opcodes.Client.SETREQFILEID);
        BitSet five = new BitSet();
        five.set(0, 5);
        this.tooMany.send(new ClientMessages.FileStatus(this.hash, 5, five).encode());
        this.nothing = accept(this.useless);
        this.nothing.until(Opcodes.Client.SETREQFILEID);
        this.nothing.send(new ClientMessages.FileStatus(this.hash, 1, new BitSet()).encode());
    }

    private void givenTheSilentSourceRefusedUs() throws Exception {
        this.silent.refuseNext();
    }

    private void givenTheSourcesWithoutAFileWeCanUseHungUpOn() throws Exception {
        assertThat(this.noFile.closesWithin(WAIT)).isTrue();
        assertThat(this.tooMany.closesWithin(WAIT)).isTrue();
        assertThat(this.nothing.closesWithin(WAIT)).as("it has no part we need").isTrue();
    }

    private void givenTheUselessAndTheUnreachableSourcesKept() throws Exception {
        awaitSources(this.hash, 3);
        assertThat(onLoop(() ->
                        this.manager.sources().stream().map(SourceInfo::address).toList()))
                .containsExactlyInAnyOrder(
                        "127.0.0.1:" + this.useless.port(),
                        "127.0.0.1:" + this.silent.port(),
                        "127.0.0.1:" + this.closedPort);
    }

    // Three failures in a row drop a source; the one without parts we need waits its time. Each round waits for
    // both failures: a refused connection takes Windows a couple of seconds.
    private void givenTheUnreachableSourcesFailedTwice() throws Exception {
        awaitFailures(this.manager, 1, this.silent.port(), this.closedPort);
        advance(DownloadManager.MIN_REASK);
        this.silent.refuseNext();
        awaitFailures(this.manager, 2, this.silent.port(), this.closedPort);
    }

    private void givenAFirewalledSourceTheServerCannotAsk(long lowId) {
        this.server.refused.add(lowId);
    }

    private void givenTheServerFoundFirewalledSources() throws Exception {
        onLoop(() -> {
            this.manager.foundSources(new ServerMessages.FoundSources(
                    this.hash,
                    List.of(
                            new ServerMessages.Source(5000, PORT, 0, Optional.of(PEER)),
                            new ServerMessages.Source(5000, PORT, 0, Optional.of(PEER)),
                            new ServerMessages.Source(6000, PORT, 0, Optional.empty()),
                            new ServerMessages.Source(0, PORT, 0, Optional.empty()),
                            new ServerMessages.Source(Ipv4.toEd2k(BLOCKED_IP), PORT, 0, Optional.empty()))));
            this.manager.foundSources(new ServerMessages.FoundSources(
                    Hash16.parseHex("37".repeat(16)),
                    List.of(new ServerMessages.Source(7000, PORT, 0, Optional.empty()))));
            return null;
        });
    }

    private void givenEachAskedForOnceOneATick() throws Exception {
        await("both asked for", () -> this.server.callbacks.size() >= 2);
        assertThat(this.server.callbacks).as("one a tick, each once").containsExactly(5000L, 6000L);
    }

    // One the server could not ask is forgotten, and may be asked for again.
    private void givenOneTheServerCouldNotAskAskedAgainWhenNamedAgain() throws Exception {
        onLoop(() -> {
            this.manager.foundSources(new ServerMessages.FoundSources(
                    this.hash, List.of(new ServerMessages.Source(6000, PORT, 0, Optional.empty()))));
            return null;
        });
        await("asked again", () -> this.server.callbacks.size() >= 3);
    }

    // One that does not call back in time is forgotten too: the server names it again, and it is asked again.
    private void givenOneThatDidNotCallBackInTimeAskedAgainWhenNamedAgain() throws Exception {
        advance(DownloadManager.CALLBACK_WAIT);
        await("asked once more", () -> {
            this.manager.foundSources(new ServerMessages.FoundSources(
                    this.hash, List.of(new ServerMessages.Source(5000, PORT, 0, Optional.of(PEER)))));
            return this.server.callbacks.lastIndexOf(5000L) > 0;
        });
    }

    private void givenTheDownloadPaused() throws Exception {
        onLoop(() -> this.manager.pause(this.hash));
    }

    private void givenAMaximumOfSources(int maxSources) {
        this.settings = settings(ObfuscationMode.SUPPORTED, maxSources, 0);
    }

    private void givenTheSourceAskedForNoMoreSources() throws Exception {
        assertThat(names(askForAnUploadSlot()))
                .as("a full list wants no more sources")
                .doesNotContain("REQUESTSOURCES2");
    }

    private void givenAMinimumFreeSpaceOf(long minFree) {
        this.settings = settings(ObfuscationMode.SUPPORTED, 100, minFree);
    }

    // A listener that does not care about the disk.
    private void givenAListenerThatDoesNotCareAboutTheDisk() {
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
    }

    private void givenKadThatLooksForSources() {
        this.kad = Optional.of((file, size, sources) -> {
            this.found.compareAndSet(null, sources);
            return true;
        });
    }

    private void givenKadLookedForItsSources() throws Exception {
        await("Kad searched", () -> this.found.get() != null);
    }

    private void givenTheDownloadStoppedBySchedule() throws Exception {
        assertThat(onLoop(() -> this.manager.stopBySchedule(this.hash))).isTrue();
    }

    private void givenWeAskedTheSourceForAnUploadSlot() throws Exception {
        askForAnUploadSlot();
    }

    private void givenTheDownloadsWaitForSpace() throws Exception {
        assertThat(await("space ")).isEqualTo("space short");
        assertThat(this.link.closesWithin(WAIT)).as("the sessions end").isTrue();
        assertThat(onLoop(() -> this.manager.download(this.hash).orElseThrow().status()))
                .isEqualTo(DownloadInfo.Status.NO_SPACE);
    }

    private void givenTheDiskHasRoomAgain() throws InterruptedException {
        assertThat(await("space ")).isEqualTo("space enough");
    }

    private void givenOurConnectionsAtTheirLimit() {
        this.guard = new ConnectionGuard(
                IpFilter::empty, new ConnectionGuard.Limits(1, 5, 20, Duration.ofSeconds(5)), this.ticker);
        this.held = this.guard.outgoing();
    }

    private void givenADownloadWhoseSourceIsNotCalled() throws Exception {
        this.waitingSource = source(0, 0);
        DownloadManager downloads = manager();
        onLoop(() -> downloads.add(this.waitingSource.link("waits.bin", 5000, Hash16.parseHex("36".repeat(16)))));
        assertThat(this.waitingSource.calledWithin(Duration.ofSeconds(2))).isFalse();
    }

    private void whenTheDownloadIsPaused() throws Exception {
        onLoop(() -> this.manager.pause(this.hash));
    }

    private void whenTheDownloadIsResumed() throws Exception {
        this.accepted = onLoop(() -> this.manager.resume(this.hash));
    }

    // A status that does not decode ends it for good.
    private void whenTheSourceSendsAStatusThatDoesNotDecode() throws Exception {
        this.link.send(Packet.of(
                Protocol.EDONKEY,
                Opcodes.Client.FILESTATUS.code(),
                new PacketWriter().hash16(this.hash).u16(5)));
    }

    private void whenTheFirstSourceSendsTheBlockAgain() throws Exception {
        sendBlocks(this.recovery.first, this.hash, this.data, 2L * Ed2k.EMBLOCKSIZE, this.data.length);
    }

    // Corrupt once more: its recovery was tried, so it comes again whole at once.
    private void whenTheFirstSourceSendsThePartCorruptAgain() throws Exception {
        byte[] corrupt = this.data.clone();
        corrupt[0] ^= 0x55;
        sendBlocks(this.recovery.first, this.hash, corrupt, 0, this.data.length);
    }

    private void whenTheUnreachableSourcesAreAskedAgain() throws Exception {
        advance(DownloadManager.MIN_REASK);
        this.silent.refuseNext();
    }

    private void whenTheServerFinds(ServerMessages.Source firewalled) throws Exception {
        this.callbacksBefore = this.server.callbacks.size();
        onLoop(() -> {
            this.manager.foundSources(new ServerMessages.FoundSources(this.hash, List.of(firewalled)));
            return null;
        });
    }

    private void whenSourcesAreAddedByHand(InetSocketAddress... addresses) throws Exception {
        this.results = onLoop(() -> {
            List<Boolean> added = new ArrayList<>();
            for (InetSocketAddress address : addresses) {
                added.add(this.manager.addSourceByHand(this.hash, address));
            }
            return added;
        });
    }

    private void whenADownloadNamesTheSourceBy(String host) throws Exception {
        this.hash = Hash16.parseHex("3d".repeat(16));
        Ed2kLink.FileLink named = new Ed2kLink.FileLink(
                "named.bin",
                5000,
                this.hash,
                Optional.empty(),
                List.of(),
                List.of(new Ed2kLink.Source(host, this.source.port())),
                List.of());
        onLoop(() -> this.manager.add(named));
    }

    private void whenADownloadIsAdded(String name, Hash16 file) throws Exception {
        this.hash = file;
        this.info = onLoop(() -> this.manager.add(Ed2kLink.FileLink.of(name, 5000, file)));
    }

    private void whenASourceIsOfferedToAClient(int sourceIp, InetAddress client) {
        this.passedOn = DownloadManager.passesOn(sourceIp, client);
    }

    private void whenKadFindsASourceOnceTheDownloadIsStopped() throws Exception {
        this.sourceCount = onLoop(() -> {
            this.manager.stop(this.hash);
            this.found
                    .get()
                    .accept(List.of(DownloadManager.KadSource.direct(
                            new InetSocketAddress(Ipv4.format(Ipv4.of(192, 0, 2, 60)), PORT))));
            return this.manager.download(this.hash).orElseThrow().sources();
        });
    }

    private void whenEveryChangeIsAskedForTheUnknownDownload() throws Exception {
        this.results = onLoop(() -> List.of(
                this.manager.stopBySchedule(UNKNOWN),
                this.manager.setPriority(UNKNOWN, DownloadPriority.HIGH),
                this.manager.cancel(UNKNOWN),
                this.manager.resume(UNKNOWN),
                this.manager.setPreviewFirst(UNKNOWN, true),
                this.manager.setCategory(UNKNOWN, Category.NONE),
                this.manager.addSourceByHand(
                        UNKNOWN, new InetSocketAddress(InetAddresses.forString("127.0.0.1"), PORT))));
    }

    private void whenTheDownloadIsSetToPreviewFirst() throws Exception {
        this.accepted = onLoop(() -> this.manager.setPreviewFirst(this.hash, true));
    }

    private void whenTheDownloadIsStoppedByScheduleAgain() throws Exception {
        this.accepted = onLoop(() -> this.manager.stopBySchedule(this.hash));
    }

    private void whenTheCategoryIsSet(int category) throws Exception {
        this.accepted = onLoop(() -> this.manager.setCategory(this.hash, category));
    }

    private void whenTimePasses(Duration duration) {
        advance(duration);
    }

    private void whenAConnectionOfOursCloses() {
        this.held.close();
    }

    private void thenTheConnectionCloses() throws Exception {
        assertThat(this.link.closesWithin(WAIT)).isTrue();
    }

    private void thenItIsAccepted() {
        assertThat(this.accepted).isTrue();
    }

    private void thenTheDownloadCompletesWithTheData() throws Exception {
        Path done = Path.of(await("completed ").substring("completed ".length()));
        assertThat(Files.readAllBytes(done)).isEqualTo(this.data);
    }

    private void thenTheDownloadIsGone() throws Exception {
        assertThat(onLoop(() -> this.manager.download(this.hash))).isEmpty();
    }

    private void thenTheFileIsNoLongerFound() throws Exception {
        assertThat(onLoop(() -> this.manager.find(this.hash))).isEmpty();
    }

    private void thenTheDownloadHasSources(int count) throws Exception {
        awaitSources(this.hash, count);
    }

    private void thenASourceIsShownAt(String address) throws Exception {
        assertThat(onLoop(() ->
                        this.manager.sources().stream().map(SourceInfo::address).toList()))
                .contains(address);
    }

    private void thenTheDownloadHasCompleted(long bytes) throws Exception {
        assertThat(onLoop(() -> this.manager.download(this.hash).orElseThrow().completed()))
                .isEqualTo(bytes);
    }

    private void thenThePartIsAskedForAgainWhole() throws Exception {
        assertThat(requested(this.recovery.first)).containsExactlyElementsOf(wholePart());
    }

    // Ends its sessions before the manager closes.
    private void thenTheDownloadCanBePaused() throws Exception {
        assertThat(onLoop(() -> this.manager.pause(this.hash))).isTrue();
    }

    private void thenOnlyTheUselessSourceIsLeft() throws Exception {
        awaitSources(this.hash, 1);
        assertThat(onLoop(() -> this.manager.sources().getFirst().address()))
                .isEqualTo("127.0.0.1:" + this.useless.port());
    }

    private void thenNoMoreCallbacksAreAskedFor() throws InterruptedException {
        Thread.sleep(1500);
        assertThat(this.server.callbacks).as("not for a paused download").hasSize(this.callbacksBefore);
    }

    private void thenNoCallbackIsAskedFor() throws InterruptedException {
        Thread.sleep(1500);
        assertThat(this.server.callbacks).isEmpty();
    }

    private void thenEveryOneIsRefused() {
        assertThat(this.results).containsOnly(false);
    }

    private void thenEveryOneIsTaken() {
        assertThat(this.results).containsOnly(true);
    }

    private void thenTheFirstSourceIsShownAt(String address) throws Exception {
        assertThat(onLoop(() -> this.manager.sources().getFirst().address())).isEqualTo(address);
    }

    private void thenTheSourceIsConnectedTo() throws Exception {
        assertThat(this.source.accept()).isNotNull();
    }

    private void givenTheDownloadStopped() throws Exception {
        onLoop(() -> this.manager.stop(this.hash));
    }

    private void whenTheManagerStartsAgain() throws Exception {
        onLoop(() -> {
            this.manager.close();
            return null;
        });
        manager();
    }

    private void thenTheSourceIsKnownAt(String ip) throws Exception {
        assertThat(onLoop(() -> this.manager.sources().getFirst().address())).isEqualTo(ip + ":" + this.source.port());
    }

    private void thenTheDownloadIsAddedAs(DownloadInfo.Status status) {
        assertThat(this.info.status()).isEqualTo(status);
    }

    private void thenItIsNotPassedOn() {
        assertThat(this.passedOn)
                .as("a LAN source goes to no client without an IPv4 address")
                .isFalse();
    }

    private void thenTheDownloadGotNoSource() {
        assertThat(this.sourceCount).isZero();
    }

    private void thenTheUnknownDownloadHasNoComments() throws Exception {
        assertThat(onLoop(() -> this.manager.comments(UNKNOWN))).isEmpty();
    }

    private void thenTheUnknownDownloadHasNoSourceToPassOn() throws Exception {
        assertThat(onLoop(() -> this.manager.exchangeable(UNKNOWN))).isEmpty();
    }

    private void thenThePausedDownloadHasNoSourceToPassOn() throws Exception {
        assertThat(onLoop(() -> this.manager.exchangeable(this.hash)))
                .as("paused")
                .isEmpty();
    }

    private void thenTheDownloadIsPreviewedFirst() throws Exception {
        assertThat(onLoop(() -> this.manager.download(this.hash).orElseThrow().previewFirst()))
                .isTrue();
    }

    private void thenTheStopIsAcceptedAsStoppedAlready() {
        assertThat(this.accepted).as("stopped already").isTrue();
    }

    private void thenTheStatusOfTheDownloadIs(DownloadInfo.Status status) throws Exception {
        assertThat(onLoop(() -> this.manager.download(this.hash).orElseThrow().status()))
                .isEqualTo(status);
    }

    private void thenTheSourceIsAskedAgain() throws Exception {
        accept(this.source).until(Opcodes.Client.SETREQFILEID);
    }

    private void thenTheSourceIsCalled() throws Exception {
        assertThat(this.waitingSource.calledWithin(WAIT)).isTrue();
        this.waitingSource.accept().until(Opcodes.Client.SETREQFILEID);
        assertThat(this.guard.openConnections()).isEqualTo(1);
    }

    // The next connection of the source, closed with the test.
    private ScriptedSource.Link accept(ScriptedSource from) throws Exception {
        ScriptedSource.Link next = from.accept();
        this.resources.add(next);
        return next;
    }

    // Asks for the file and for an upload slot; the packets read.
    private List<Packet> askForAnUploadSlot() throws Exception {
        this.link = accept(this.source);
        List<Packet> asked = new ArrayList<>(this.link.until(Opcodes.Client.SETREQFILEID));
        this.link.send(new ClientMessages.FileStatus(this.hash, 0, new BitSet()).encode());
        asked.addAll(this.link.until(Opcodes.Client.STARTUPLOADREQ));
        return asked;
    }

    private List<ClientMessages.Range> wholePart() {
        return List.of(
                new ClientMessages.Range(0, Ed2k.EMBLOCKSIZE),
                new ClientMessages.Range(Ed2k.EMBLOCKSIZE, 2L * Ed2k.EMBLOCKSIZE),
                new ClientMessages.Range(2L * Ed2k.EMBLOCKSIZE, this.data.length));
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
        ScriptedSource first = source(0, 0);
        ScriptedSource helper = source(1 << Hello.MISC1_AICH_SHIFT, 0);
        Ed2kLink.FileLink link = new Ed2kLink.FileLink(
                "recover.bin",
                data.length,
                hashed.hash(),
                Optional.of(tree.masterHash()),
                hashed.partHashes(),
                List.of(new Ed2kLink.Source("127.0.0.1", first.port())),
                List.of());
        DownloadManager manager = manager();
        onLoop(() -> manager.add(link));
        ScriptedSource.Link served = first.accept();
        served.until(Opcodes.Client.SETREQFILEID);
        served.send(new ClientMessages.FileStatus(link.hash(), 0, new BitSet()).encode());
        served.until(Opcodes.Client.STARTUPLOADREQ);
        served.send(Packet.empty(Protocol.EDONKEY, Opcodes.Client.ACCEPTUPLOADREQ.code()));
        assertThat(requested(served)).hasSize(3);
        byte[] corrupt = data.clone();
        corrupt[data.length - 1] ^= 0x55;
        sendBlocks(served, link.hash(), corrupt, 0, data.length);
        await(
                "the corrupt part held for its recovery data",
                () -> manager.find(link.hash()).orElseThrow().file().isHeld(0));
        assertThat(onLoop(() -> manager.addSourceByHand(link.hash(), helper.address())))
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

    private static Optional<Inet6Address> ipv6(String address) {
        return Optional.of((Inet6Address) InetAddresses.forString(address));
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

    private static DownloadManager.Settings settings(ObfuscationMode obfuscation, int maxSources, long minFree) {
        return new DownloadManager.Settings(
                obfuscation, maxSources, PORT, minFree, true, false, "spam|fake", 0, false, "");
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
        Hello plain = Hello.of(Hash16.parseHex("ab".repeat(16)), Ipv4.toEd2k(OUR_IP), PORT, "test", 0, 0, 0, 0, 0, 0);
        Hello hello = this.ourIpv6.map(plain::withIpv6).orElse(plain);
        this.incoming = this.incoming == null ? this.dir.resolve("incoming") : this.incoming;
        this.manager = onLoop(() -> DownloadManager.open(
                this.loop,
                this.dir.resolve("temp"),
                () -> this.incoming,
                () -> hello,
                () -> this.settings,
                this.server,
                ip -> ip == BLOCKED_IP,
                random,
                this.ticker,
                links(identities),
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
        await("source " + address, () -> {
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
        await(failures + " failures of " + Arrays.toString(ports), () -> {
            String now = manager.describe();
            return Arrays.stream(ports).allMatch(p -> now.contains("127.0.0.1:" + p + " (failed " + failures + ")"));
        });
    }

    private void awaitSources(Hash16 hash, int count) throws Exception {
        await(
                count + " sources",
                () -> this.manager.download(hash).orElseThrow().sources() == count);
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

    // The client UDP port, always open: what goes out is kept.
    private static final class Udp implements DownloadManager.ClientUdp {
        record Sent(InetSocketAddress to, Packet packet, Optional<Hash16> userHash) {}

        private final BlockingQueue<Sent> sent = new LinkedBlockingQueue<>();

        @Override
        public boolean available() {
            return true;
        }

        @Override
        public void send(InetSocketAddress to, Packet packet, Optional<Hash16> userHash) {
            this.sent.add(new Sent(to, packet, userHash));
        }
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
