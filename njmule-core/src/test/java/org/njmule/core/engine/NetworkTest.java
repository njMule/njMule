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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.njmule.core.api.Category;
import org.njmule.core.api.DownloadInfo;
import org.njmule.core.api.DownloadPriority;
import org.njmule.core.api.Ed2kLink;
import org.njmule.core.api.EngineEvent;
import org.njmule.core.api.Friend;
import org.njmule.core.api.Hash16;
import org.njmule.core.api.LogLine;
import org.njmule.core.api.PeerInfo;
import org.njmule.core.api.SearchRequest;
import org.njmule.core.api.SearchResult;
import org.njmule.core.api.ServerInfo;
import org.njmule.core.api.SpamMarks;
import org.njmule.core.api.UploadPriority;
import org.njmule.core.config.AppDirectories;
import org.njmule.core.config.ObfuscationMode;
import org.njmule.core.config.Preferences;
import org.njmule.core.files.CancelledFiles;
import org.njmule.core.kad.KadNode;
import org.njmule.core.kad.UInt128;
import org.njmule.core.persist.format.IpFilterDat;
import org.njmule.core.persist.format.NodesDat;
import org.njmule.core.persist.format.ServerMet;
import org.njmule.core.protocol.ed2k.Hello;
import org.njmule.core.protocol.ed2k.ServerMessages;
import org.njmule.core.protocol.io.PacketWriter;
import org.njmule.core.protocol.packet.FrameLimit;
import org.njmule.core.protocol.packet.Opcodes;
import org.njmule.core.protocol.packet.Packet;
import org.njmule.core.protocol.packet.Protocol;
import org.njmule.core.protocol.packet.TcpFraming;
import org.njmule.core.protocol.tag.ServerTag;
import org.njmule.core.protocol.tag.Tag;
import org.njmule.core.protocol.tag.TagName;
import org.njmule.core.search.SpamFilter;
import org.njmule.core.search.StoredSearches;
import org.njmule.core.server.ScriptedServer;
import org.njmule.core.server.ServerList;
import org.njmule.core.util.Ipv4;

/** The network side of the engine on its own, with the events and the log it reports recorded. */
@Timeout(60)
class NetworkTest {

    private static final Hash16 USER = Hash16.parseHex("0a".repeat(16));
    private static final long HIGH_ID = Ipv4.toEd2k(Ipv4.parse("1.2.3.4"));
    private static final long OTHER_HIGH_ID = Ipv4.toEd2k(Ipv4.parse("5.6.7.8"));
    private static final int BLOCKED = Ipv4.of(203, 0, 113, 7);
    private static final int CRYPT =
            Hello.MISC2_SUPPORTS_CRYPT | Hello.MISC2_REQUESTS_CRYPT | Hello.MISC2_REQUIRES_CRYPT;
    private static final SearchResult SPAM =
            new SearchResult(Hash16.parseHex("b1".repeat(16)), "free movie installer.exe", 100, 1, 0, Optional.empty());
    private static final Category FILMS =
            new Category(1, "Films", Optional.empty(), "", 1, DownloadPriority.AUTO, "", false);
    private static final Category MUSIC =
            new Category(2, "Music", Optional.empty(), "", 2, DownloadPriority.AUTO, "", false);
    // Documentation addresses: the network is not started, nothing is sent to them.
    private static final ServerList.Address KNOWN_SERVER = new ServerList.Address(Ipv4.of(198, 51, 100, 7), 4661);
    private static final ServerList.Address UNKNOWN_SERVER = new ServerList.Address(Ipv4.of(198, 51, 100, 8), 4661);
    private static final NodesDat.Contact FIRST_CONTACT = new NodesDat.Contact(
            UInt128.parseHex("c1".repeat(16)), Ipv4.of(198, 51, 100, 11), 4672, 4662, 8, 0, NodesDat.UdpKey.NONE, true);
    private static final NodesDat.Contact SECOND_CONTACT = new NodesDat.Contact(
            UInt128.parseHex("c2".repeat(16)), Ipv4.of(198, 51, 100, 12), 4672, 4662, 8, 0, NodesDat.UdpKey.NONE, true);
    // A LAN address the preferences filter: a running node never sends anything beyond this machine.
    private static final NodesDat.Contact LAN_CONTACT = new NodesDat.Contact(
            UInt128.parseHex("c3".repeat(16)), Ipv4.of(10, 0, 0, 7), 4672, 4662, 8, 0, NodesDat.UdpKey.NONE, true);
    private static final Ed2kLink.FileLink FILM =
            Ed2kLink.FileLink.of("film.mkv", 5000, Hash16.parseHex("f1".repeat(16)));
    private static final Ed2kLink.FileLink OTHER_FILM =
            Ed2kLink.FileLink.of("film.mkv", 5000, Hash16.parseHex("f2".repeat(16)));
    private static final Ed2kLink.FileLink CANCELLED_FILM =
            Ed2kLink.FileLink.of("film.mkv", 5000, Hash16.parseHex("f4".repeat(16)));
    private static final Ed2kLink.FileLink CATEGORISED_FILM =
            Ed2kLink.FileLink.of("film.mkv", 5000, Hash16.parseHex("a3".repeat(16)));
    private static final Ed2kLink.FileLink CATEGORISED_SONG =
            Ed2kLink.FileLink.of("song.mp3", 5000, Hash16.parseHex("a4".repeat(16)));
    private static final Ed2kLink.FileLink LARGE_FILE =
            Ed2kLink.FileLink.of("large.iso", 5_000_000_000L, Hash16.parseHex("d1".repeat(16)));
    private static final Ed2kLink.FileLink SMALL_FILE =
            Ed2kLink.FileLink.of("small.iso", 5000, Hash16.parseHex("d2".repeat(16)));
    private static final Hash16 PAL = Hash16.parseHex("e1".repeat(16));
    private static final Hash16 PEER_HASH = Hash16.parseHex("21".repeat(16));
    private static final Hello PEER_HELLO = Hello.of(PEER_HASH, 0, 4999, "peer", 0, 0, 0, 0, 0, 0);

    /** A line of the front ends' log. */
    private record Logged(LogLine.Level level, String text) {}

    @TempDir
    Path dir;

    private final BlockingQueue<EngineEvent> events = new LinkedBlockingQueue<>();
    private final List<Logged> log = new CopyOnWriteArrayList<>();
    private volatile Preferences preferences;
    private AppDirectories dirs;
    private EngineLoop loop;
    private Network network;
    private ScriptedServer scripted;
    private int serverSocketPort;
    private ServerSocket takenTcpPort;
    private DatagramSocket takenUdpPort;
    private ServerSocket peerSocket;
    private Socket peerConnection;
    private CompletableFuture<PeerInfo> answer;
    private PeerInfo info;
    private Packet packet;
    private SpamMarks marks;
    private Object reply;
    private Throwable exception;
    private int kadPort;
    private int newTcpPort;
    private int newUdpPort;
    private byte[] remembered;
    private EngineEvent.ServerConnected connected;
    private EngineEvent.ServerDisconnected disconnected;

    @BeforeEach
    void open() throws IOException {
        this.dirs = AppDirectories.underRoot(this.dir.resolve("njmule"), false);
        Files.createDirectories(this.dirs.config());
        this.preferences = Preferences.defaults().toBuilder()
                .tcpPort(freeTcpPort())
                .udpPort(freeUdpPort())
                .kadEnabled(false)
                .build();
        this.loop = EngineLoop.start();
    }

    @AfterEach
    void close() throws Exception {
        if (this.network != null) {
            onLoop(() -> {
                this.network.close();
                return null;
            });
        }
        this.loop.close();
        if (this.scripted != null) {
            this.scripted.close();
        }
        if (this.peerConnection != null) {
            this.peerConnection.close();
        }
        if (this.peerSocket != null) {
            this.peerSocket.close();
        }
        if (this.takenTcpPort != null) {
            this.takenTcpPort.close();
        }
        if (this.takenUdpPort != null) {
            this.takenUdpPort.close();
        }
    }

    @Test
    void shouldKeepSpamMarksThatCannotBeSaved() throws Exception {
        givenANetwork();
        givenTheResultIsMarkedAsSpam();
        givenTheSpamMarksCannotBeSaved();

        whenTheResultIsMarkedAsNotSpam();

        thenNothingIsSpam();
        thenTheResultIsNotSpam();
        thenTheNetworkKeepsTheseMarks();
    }

    @Test
    void shouldRefuseToShareAFolderBeforeTheStart() throws Exception {
        givenANetwork();
        givenTheFolder("films");

        whenTheFolderIsShared("films");

        thenExceptionOccurredWithTheCause(IllegalStateException.class);
    }

    @Test
    void shouldRefuseToUnshareAFolderBeforeTheStart() throws Exception {
        givenANetwork();
        givenTheFolder("films");

        whenTheFolderIsUnshared("films");

        thenExceptionOccurredWithTheCause(IllegalStateException.class);
    }

    @Test
    void shouldRefuseToAddKnownFilesBeforeTheStart() throws Exception {
        givenANetwork();

        whenNoKnownFilesAreAdded();

        thenExceptionOccurredWithTheCause(IllegalStateException.class);
    }

    @Test
    void shouldRenameNoSharedFileBeforeTheStart() throws Exception {
        givenANetwork();

        whenTheSharedFileIsRenamed(USER, "other.iso");

        thenTheNetworkAnswers(false);
    }

    @Test
    void shouldPrioritiseNoSharedFileBeforeTheStart() throws Exception {
        givenANetwork();

        whenTheUploadPriorityIsSet(USER, UploadPriority.HIGH);

        thenTheNetworkAnswers(false);
    }

    @Test
    void shouldShareNothingBeforeTheStart() throws Exception {
        whenTheNetworkIsCreated();

        thenThereAreNoSharedFiles();
        thenThereAreNoSharedFolders();
        thenTheStatusCountsNoSharedFile();
        thenTheHistoryHasNoDownloadOf(USER);
    }

    @Test
    void shouldShareAndUnshareFoldersOnceStarted() throws Exception {
        givenAStartedNetwork();
        givenTheFolderIsShared("films", false);
        givenTheFolderIsShared("music", true);
        givenTheSharedFoldersAre("films", "music/**");

        whenTheFolderIsUnshared("films");

        thenTheSharedFolderCountIs(1);
        thenTheNetworkDescribesItsDownloadsAndUploads();
    }

    @Test
    void shouldNeverReplaceTheIpFilterByNothing() throws Exception {
        givenANetwork();

        whenTheIpFilterIsReplacedByNoRange();

        thenExceptionOccurredWithTheCause(IOException.class);
    }

    @Test
    void shouldReplaceTheIpFilterByAList() throws Exception {
        givenANetwork();

        whenTheIpFilterIsReplacedByTheTestRanges();

        thenTheNetworkAnswers(2);
        thenTheConfigurationHasTheFile(Network.IPFILTER_FILE);
        thenTheLogHas(LogLine.Level.INFO, "2 IP filter ranges");
    }

    @Test
    void shouldRefuseToConnectToAServerTheIpFilterBlocks() throws Exception {
        givenANetwork();
        givenTheIpFilterIsReplacedByTheTestRanges();

        whenTheNetworkConnectsTo(BLOCKED, 4661);

        thenExceptionOccurredWithTheCause(IllegalArgumentException.class, "IP filter");
    }

    @Test
    void shouldRefuseCategoriesThatShareAnId() throws Exception {
        givenANetwork();

        whenTheCategoriesAre(FILMS, FILMS);

        thenExceptionOccurredWithTheCause(IllegalArgumentException.class);
        thenThereAreNoCategories();
    }

    @Test
    void shouldTakeUnreadableFilesAsEmpty() throws Exception {
        givenAnUnreadableFile(StoredSearches.FILE_NAME);
        givenAnUnreadableFile(CancelledFiles.FILE_NAME);

        whenTheNetworkIsCreated();

        thenThereAreNoSavedSearches();
        thenTheFileIsNotCancelled(USER);
    }

    @Test
    void shouldChangeServersEvenWhenTheirFilesCannotBeSaved() throws Exception {
        givenManualServersOfHighPriority();
        givenANetwork();
        givenTheServerFilesCannotBeSaved();
        givenTheServerIsAdded(KNOWN_SERVER);
        givenTheServerIsNotAddedAgain(KNOWN_SERVER);
        givenTheOnlyServerHasThePriority(ServerInfo.Priority.HIGH);
        givenTheServerIsMadeStatic(KNOWN_SERVER);
        givenTheServerPriorityIsSet(KNOWN_SERVER, ServerInfo.Priority.LOW);
        givenAnUnknownServerIsNeitherMadeStaticNorPrioritised(UNKNOWN_SERVER);
        givenTheServerUpdateTellsItIsStatic();

        whenTheServerIsRemoved(KNOWN_SERVER);

        thenTheNetworkAnswers(true);
        thenThereAreNoServers();
    }

    @Test
    void shouldDropADatagramThatIsNoAnswer() throws Exception {
        givenANetwork();

        whenADatagramArrives(Opcodes.ServerUdp.GLOBSEARCHREQ, 4);

        thenNoEventIsReported();
    }

    @Test
    void shouldDropADatagramThatCannotBeRead() throws Exception {
        givenANetwork();

        whenADatagramArrives(Opcodes.ServerUdp.GLOBSEARCHRES, 3);

        thenNoEventIsReported();
    }

    @Test
    void shouldIgnoreSettingsChangedBeforeTheStart() throws Exception {
        givenANetwork();

        whenTheSettingsMoveTheTcpPort();

        thenNothingIsLogged();
    }

    @Test
    void shouldIgnoreSettingsChangedAfterTheEnd() throws Exception {
        givenANetwork();
        givenTheSettingsMovedTheTcpPort();
        givenTheNetworkIsClosed();

        whenTheSettingsMoveTheTcpPort();

        thenNothingIsLogged();
    }

    @Test
    void shouldLeaveUsUnreachableWhenAnotherProgramHoldsOurPort() throws Exception {
        givenTheTcpPortIsTakenByAnotherProgram();

        whenTheNetworkStarts();

        thenTheLogTellsItCannotListenOnTheTakenPort();
        thenTheStatusTellsTheTakenPort();
    }

    @Test
    void shouldNotRecheckTheKadFirewallNorListAnythingWhileKadDoesNotRun() throws Exception {
        givenAStartedNetwork();

        whenTheKadFirewallIsRechecked();

        thenTheNetworkAnswers(false);
        thenThereAreNoKadContacts();
        thenThereAreNoKadSearches();
    }

    @Test
    void shouldRefuseAKadBootstrapWhileKadDoesNotRun() throws Exception {
        givenAStartedNetwork();

        whenKadIsBootstrappedFrom(Ipv4.of(127, 0, 0, 1), 9);

        thenExceptionOccurredWithTheCause(IllegalStateException.class);
    }

    @Test
    void shouldRecheckTheKadFirewallWhileKadRuns() throws Exception {
        givenAStartedNetwork();
        givenKadIsConnectedTwice();

        whenTheKadFirewallIsRechecked();

        thenTheNetworkAnswers(true);
        thenTheLogHas(LogLine.Level.INFO, "Kad: checking the firewall again");
    }

    @Test
    void shouldTakeNoLanContactWhileKadRuns() throws Exception {
        givenAStartedNetwork();
        givenKadIsConnectedTwice();
        givenKadIsBootstrappedFromAFreePortOfThisMachine();

        whenAContactListIsAdded(LAN_CONTACT);

        thenTheNetworkAnswers(0);
        thenThereAreNoKadContacts();
        thenThereAreNoKadSearches();
    }

    @Test
    void shouldStopKadOnceWhenDisconnectedTwice() throws Exception {
        givenAStartedNetwork();
        givenKadIsConnectedTwice();

        whenKadIsDisconnectedTwice();

        thenKadDoesNotRun();
        thenTheLogHasOnce("Kad stopped");
    }

    @Test
    void shouldRefuseAKadSearchWhileKadDoesNotRun() throws Exception {
        givenAStartedNetwork();

        whenKadIsSearchedFor("ubuntu");

        thenExceptionOccurredWithTheCause(IllegalStateException.class, "not running");
    }

    @Test
    void shouldRefuseAKadSearchWithoutAContactToAsk() throws Exception {
        givenAStartedNetwork();
        givenKadIsConnected();

        whenKadIsSearchedFor("ubuntu");

        thenExceptionOccurredWithTheCause(IllegalStateException.class, "now");
        thenThereAreNoKadSearches();
    }

    @ParameterizedTest
    @MethodSource("obfuscationWanted")
    void shouldTellInOurHelloHowMuchObfuscationWeWant(ObfuscationMode mode, int wanted) throws Exception {
        givenANetwork();
        givenTheObfuscation(mode);
        givenAClientListeningWithABacklogOf(4);

        whenWeSayHelloToTheClientWhichHangsUp();

        thenOurHelloTellsTheCryptOptions(wanted);
        thenTheHelloFailsWith(IOException.class);
    }

    @Test
    void shouldReportKadThatCannotTakeItsPort() throws Exception {
        givenTheUdpPortIsTakenByAnotherProgram();
        givenKadIsEnabled();

        whenTheNetworkStarts();

        thenTheLogTellsKadCannotStartOnTheTakenPort();
    }

    @Test
    void shouldNotStartKadByHandWithoutItsPort() throws Exception {
        givenTheUdpPortIsTakenByAnotherProgram();
        givenKadIsEnabled();
        givenAStartedNetwork();

        whenKadIsConnected();

        thenKadHasStillNoPort();
    }

    @Test
    void shouldTurnOffQuietlyKadThatCouldNotTakeItsPort() throws Exception {
        givenTheUdpPortIsTakenByAnotherProgram();
        givenKadIsEnabled();
        givenAStartedNetwork();
        givenKadIsConnected();

        whenKadIsTurnedOffInTheSettings();

        thenKadDoesNotRun();
        thenTheLogNeverHas("Kad stopped");
    }

    @Test
    void shouldLeaveOnDiskTheCancelledFilesThatCannotBeForgotten() throws Exception {
        givenANetwork();
        givenTheDownloadIsCancelled(CANCELLED_FILM);
        givenTheNetworkIsClosed();
        givenTheCancelledFilesAreOnDisk();
        givenTheCancelledFilesCannotBeSaved();
        givenCancelledFilesAreNotRemembered();

        whenTheNetworkIsCreated();

        thenTheFileIsNotCancelled(CANCELLED_FILM.hash());
        thenTheCancelledFilesOnDiskAreLeftAsTheyWere();
    }

    @Test
    void shouldKeepNothingOfABootstrapList() throws Exception {
        givenANetwork();
        givenNodesDatHolds(FIRST_CONTACT);

        whenABootstrapListIsAdded(SECOND_CONTACT);

        thenTheNetworkAnswers(0);
    }

    @Test
    void shouldAddTheNewKadContactsToNodesDatWhileKadIsOff() throws Exception {
        givenANetwork();
        givenNodesDatHolds(FIRST_CONTACT);

        whenAContactListIsAdded(FIRST_CONTACT, SECOND_CONTACT);

        thenTheNetworkAnswers(1);
        thenNodesDatHolds(FIRST_CONTACT, SECOND_CONTACT);
    }

    @Test
    void shouldLogInAgainAndMoveKadWhenThePortsChange() throws Exception {
        givenKadIsEnabled();
        givenALoginTo(ScriptedServer.start(HIGH_ID, 0));
        givenTheServerGotALogin();
        givenKadRunsOnItsPort();

        whenThePortsAreMovedToFreeOnes();

        thenTheServerGetsALoginWithTheNewTcpPort();
        thenKadMovedToTheNewUdpPort();
        thenTheNetworkConnectsAgain();
    }

    @Test
    void shouldNotTakeAServerListItDidNotAskFor() throws Exception {
        givenKadIsEnabled();
        givenALoginTo(ScriptedServer.start(HIGH_ID, 0));
        givenTheServerGotALogin();
        givenThePortsWereMovedToFreeOnes();
        givenTheNetworkLoggedInAgainWithTheNewPorts();

        whenTheServerSendsAListNotAskedFor();

        thenThereAreNoServers();
    }

    @Test
    void shouldDisconnectWhenEd2kIsTurnedOff() throws Exception {
        givenKadIsEnabled();
        givenALoginTo(ScriptedServer.start(HIGH_ID, 0));
        givenTheServerGotALogin();
        givenThePortsWereMovedToFreeOnes();
        givenTheNetworkLoggedInAgainWithTheNewPorts();

        whenEd2kAndTheMemoryOfCancelledFilesAreTurnedOff();

        thenTheNetworkIsDisconnected();
        thenTheStatusHasNoServer();
    }

    @Test
    void shouldLogInAndAskForTheServersTheServerKnows() throws Exception {
        givenServersAreLearntFromServersAndLanAddressesFiltered();
        givenAnIpFilterBlocking(BLOCKED);
        givenAStartedNetwork();
        givenDeadServersAreKnown();

        whenTheNetworkConnectsTo(ScriptedServer.start(HIGH_ID, ServerMessages.TCP_RELATED_SEARCH));

        thenTheClientIdIs(HIGH_ID);
        thenRelatedSearchIsOn();
        thenTheServerIsAskedForItsList();
    }

    @Test
    void shouldLogWhatTheServerSaysAndLearnTheServersItKnows() throws Exception {
        givenServersAreLearntFromServersAndLanAddressesFiltered();
        givenAnIpFilterBlocking(BLOCKED);
        givenAStartedNetwork();
        givenDeadServersAreKnown();
        givenTheNetworkConnectedTo(ScriptedServer.start(HIGH_ID, ServerMessages.TCP_RELATED_SEARCH));
        givenTheServerWasAskedForItsList();

        whenTheServerSendsMessagesServersCallbacksAndItsStatus();

        thenTheServerUpdateTellsTheUsers(500);
        thenTheLogHas(LogLine.Level.ERROR, "ERROR: no more room");
        thenTheLogHas(LogLine.Level.WARNING, "WARNING: low id");
        thenTheLogHas(LogLine.Level.INFO, "2 dead servers removed");
        thenTheLogHasTheNewServersFromTheServer(2);
        thenTheServerCountIs(2);
    }

    @Test
    void shouldTakeTheNameTheServerGivesItself() throws Exception {
        givenAStartedNetwork();
        givenTheNetworkConnectedTo(ScriptedServer.start(HIGH_ID, ServerMessages.TCP_RELATED_SEARCH));

        whenTheServerTellsItsName("Scripted");

        thenTheServerUpdateTellsTheName("Scripted");
    }

    @Test
    void shouldTellWhenTheServerHangsUp() throws Exception {
        givenAStartedNetwork();
        givenTheNetworkConnectedTo(ScriptedServer.start(HIGH_ID, ServerMessages.TCP_RELATED_SEARCH));
        givenTheServerToldItsName("Scripted");

        whenTheServerHangsUp();

        thenTheDisconnectionNamesTheServer();
        thenOurClientIdIs(0);
        thenTheLogTellsTheDisconnectionFrom("Scripted");
    }

    @Test
    void shouldLeaveAPausedDownloadAsItIsWhenAddedAgain() throws Exception {
        givenDownloadsAreAddedPaused();
        givenAStartedNetwork();
        givenTheDownloadIsAddedPaused(LARGE_FILE);

        whenTheDownloadIsAddedAgain(LARGE_FILE);

        thenTheDownloadIsLeftAsItIs(DownloadInfo.Status.PAUSED);
    }

    @Test
    void shouldAskForLargeFilesOnlyFromServersThatTakeThem() throws Exception {
        givenDownloadsAreAddedPaused();
        givenAStartedNetwork();
        givenTheDownloadIsAddedPaused(LARGE_FILE);
        givenTheDownload(SMALL_FILE);
        givenTheNetworkConnectedTo(ScriptedServer.start(HIGH_ID, ServerMessages.TCP_OBFUSCATION));

        whenTheDownloadsAreResumed(LARGE_FILE, SMALL_FILE);

        thenTheServerIsAskedForTheSourcesOf(SMALL_FILE);
        thenTheServerIsNeverAskedForTheSourcesOfTheLargeFile();
    }

    @Test
    void shouldGiveTheFriendSlotOnlyToAFriend() throws Exception {
        givenANetwork();
        givenTheFriend(PAL, "pal");

        whenTheFriendSlotIsGivenTo(USER);

        thenTheNetworkAnswers(false);
    }

    @Test
    void shouldTakeTheFriendSlotAwayWithTheFriend() throws Exception {
        givenANetwork();
        givenTheFriend(PAL, "pal");
        givenTheFriendSlotIsGivenTo(PAL);

        whenTheFriendIsRemoved(PAL);

        thenTheNetworkAnswers(true);
        thenThereIsNoFriendSlot();
        thenThereAreNoFriends();
    }

    @Test
    void shouldStopPrioritiseAndPreviewADownload() throws Exception {
        givenCancelledFilesAreNotRemembered();
        givenANetwork();
        givenTheDownload(FILM);
        givenTheDownloadPriorityIsSet(FILM, DownloadPriority.HIGH);
        givenThePreviewIsTurnedOn(FILM);
        givenThereIsNothingToPreviewYet(FILM);

        whenTheDownloadIsStopped(FILM);

        thenTheNetworkAnswers(true);
        thenTheOnlyDownloadIs(DownloadInfo.Status.STOPPED, DownloadPriority.HIGH);
    }

    @Test
    void shouldNotRememberACancelledDownloadWhenThePreferencesSaySo() throws Exception {
        givenCancelledFilesAreNotRemembered();
        givenANetwork();
        givenTheDownload(FILM);

        whenTheDownloadIsCancelled(FILM);

        thenTheNetworkAnswers(true);
        thenTheFileIsNotCancelled(FILM.hash());
    }

    @Test
    void shouldCancelADownloadThatCannotBeRemembered() throws Exception {
        givenANetwork();
        givenTheDownload(OTHER_FILM);
        givenTheCancelledFilesCannotBeSaved();

        whenTheDownloadIsCancelled(OTHER_FILM);

        thenTheNetworkAnswers(true);
        thenThereAreNoDownloads();
    }

    @Test
    void shouldLeaveDownloadsOfOtherCategoriesAndRunningOnesAloneByASchedulerRule() throws Exception {
        givenANetwork();
        givenTheCategories(FILMS, MUSIC);
        givenTheDownloadInCategory(CATEGORISED_FILM, 1);
        givenTheDownloadInCategory(CATEGORISED_SONG, 2);

        whenTheSchedulerResumesThenStops(2, 3);

        thenEveryDownloadIs(DownloadInfo.Status.DOWNLOADING);
        thenTheLogHas(LogLine.Level.INFO, "Scheduler: downloads of category 2 resumed");
        thenTheLogHas(LogLine.Level.INFO, "Scheduler: downloads of category 3 stopped");
    }

    @Test
    void shouldFailAHelloToNobody() throws Exception {
        givenANetwork();

        whenWeSayHelloToNobody();

        thenTheHelloFailsWith(IOException.class);
    }

    @Test
    void shouldFailAHelloToAClientThatHangsUp() throws Exception {
        givenANetwork();
        givenAClientListeningWithABacklogOf(1);

        whenWeSayHelloToTheClientWhichHangsUp();

        thenTheClientGets(Opcodes.Client.HELLO);
        thenTheHelloFailsWith(IOException.class);
    }

    @Test
    void shouldFailAHelloAnsweredUnreadably() throws Exception {
        givenANetwork();
        givenAClientListeningWithABacklogOf(1);

        whenTheClientAnswersOurHelloUnreadably();

        thenTheHelloFailsWith(IOException.class);
    }

    @Test
    void shouldTellAClientWithoutTheIdentification() throws Exception {
        givenANetwork();
        givenAClientListeningWithABacklogOf(1);
        givenWeSaidHelloToTheClient();

        whenTheClientSendsAHelloThenItsAnswerWithoutTheIdentification();

        thenTheAnswerComesFrom(PEER_HASH, "peer");
        thenTheIdentificationIs(PeerInfo.Identification.NOT_SUPPORTED);
    }

    @Test
    void shouldIgnoreOtherPacketsButAnswerAHelloOfTheClientAfterwards() throws Exception {
        givenANetwork();
        givenAClientListeningWithABacklogOf(1);
        givenWeSaidHelloToTheClient();
        givenTheClientAnsweredWithoutTheIdentification();

        whenTheClientSendsOtherPacketsThenAHello();

        thenTheClientGets(Opcodes.Client.HELLOANSWER);
    }

    @Test
    void shouldLearnTheServerOfAClientWhenThePreferencesAskForIt() throws Exception {
        givenServersAreLearntFromClients();
        givenAStartedNetwork();

        whenAClientSaysHelloWithItsServer();

        thenTheServerCountChangesTo(1);
        thenTheLogHas(LogLine.Level.INFO, "1 new server from a client");
    }

    @Test
    void aNewIdFromTheServerIsTheIdOurHelloTells() throws Exception {
        givenALoginTo(ScriptedServer.start(HIGH_ID, 0));

        whenTheServerGivesUsTheId(OTHER_HIGH_ID);

        thenOurHelloTellsTheId(OTHER_HIGH_ID);
    }

    private void givenALoginTo(ScriptedServer server) throws Exception {
        this.scripted = server;
        Network network = started();
        connect(network, server);
        next(EngineEvent.ServerConnected.class);
    }

    private void whenTheServerGivesUsTheId(long id) throws Exception {
        this.scripted.send(new ServerMessages.IdChange(id, 0, 0).encode());
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (onLoop(this.network::status).clientId() != id) {
            assertThat(System.nanoTime()).as("the new id taken").isLessThan(deadline);
            Thread.sleep(20);
        }
    }

    private void thenOurHelloTellsTheId(long id) throws Exception {
        try (ServerSocket peer = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            CompletableFuture<PeerInfo> answer = this.network.hello(local(peer.getLocalPort()));
            try (Socket socket = peer.accept()) {
                assertThat(Hello.decode(read(socket)).clientId()).isEqualTo(id);
            }
            assertThatThrownBy(() -> answer.get(20, TimeUnit.SECONDS)).hasCauseInstanceOf(IOException.class);
        }
    }

    @Test
    void kadGetsItsPortEvenWhenTheServerSocketHoldsIt() throws Exception {
        givenARunningNetworkWithoutKad();

        whenKadIsTurnedOnAtThePortOfTheServerSocket();

        thenKadListensOnThatPort();
        thenTheServerSocketListensElsewhere();
    }

    private void givenARunningNetworkWithoutKad() throws Exception {
        started();
        this.serverSocketPort = onLoop(this.network::serverUdpPort).orElseThrow();
    }

    private void whenKadIsTurnedOnAtThePortOfTheServerSocket() throws Exception {
        reconfigure(this.network, p -> p.udpPort(this.serverSocketPort).kadEnabled(true));
    }

    private void thenKadListensOnThatPort() throws Exception {
        assertThat(onLoop(this.network::kadInfo))
                .as("Kad started")
                .hasValueSatisfying(kad -> assertThat(kad.udpPort()).isEqualTo(this.serverSocketPort));
    }

    private void thenTheServerSocketListensElsewhere() throws Exception {
        OptionalInt port = onLoop(this.network::serverUdpPort);
        assertThat(port).isPresent();
        assertThat(port.getAsInt()).isNotEqualTo(this.serverSocketPort);
    }

    @Test
    void aRunForTheUserConnectsAtStartWhenThePreferencesSaySo() throws Exception {
        givenTheServerListHolds(ScriptedServer.startAt(ScriptedServer.lanAddress(), HIGH_ID, 0));
        givenConnectingAtStart(true);

        whenTheNetworkStartsForTheUser();

        thenItConnectsToThatServer();
    }

    @Test
    void aRunForTheUserStaysDisconnectedWhenThePreferencesSaySo() throws Exception {
        givenTheServerListHolds(ScriptedServer.startAt(ScriptedServer.lanAddress(), HIGH_ID, 0));
        givenConnectingAtStart(false);

        whenTheNetworkStartsForTheUser();

        thenItStaysDisconnected();
    }

    private void givenTheServerListHolds(ScriptedServer server) throws IOException {
        this.scripted = server;
        // Known to be plain: no obfuscation is tried first.
        ServerMet.Server listed = new ServerMet.Server(server.ip(), server.port(), List.of())
                .with(new Tag.IntTag(TagName.id(ServerTag.UDPFLAGS), 0));
        Files.write(this.dirs.config().resolve(ServerList.FILE_NAME), ServerMet.write(List.of(listed)));
    }

    private void givenConnectingAtStart(boolean connect) {
        this.preferences = this.preferences.toBuilder()
                .autoConnect(connect)
                .filterLanAddresses(false)
                .build();
    }

    private void whenTheNetworkStartsForTheUser() throws Exception {
        Network created = create(true);
        onLoop(() -> {
            created.start();
            return null;
        });
    }

    private void thenItConnectsToThatServer() throws Exception {
        EngineEvent.ServerConnected connected = next(EngineEvent.ServerConnected.class);
        assertThat(connected.server().address()).endsWith(":" + this.scripted.port());
    }

    private void thenItStaysDisconnected() throws Exception {
        Thread.sleep(2000);
        assertThat(this.events).noneMatch(e -> e instanceof EngineEvent.ServerConnecting);
        assertThat(this.scripted.connections()).isZero();
    }

    private void givenANetwork() throws Exception {
        create();
    }

    private void givenAStartedNetwork() throws Exception {
        started();
    }

    private void givenTheNetworkIsClosed() throws Exception {
        onLoop(() -> {
            this.network.close();
            return null;
        });
    }

    private void givenTheResultIsMarkedAsSpam() throws Exception {
        this.marks = onLoop(() -> this.network.markSpam(List.of(SPAM), true));
        assertThat(this.marks.spam()).containsExactly(SPAM.hash());
        assertThat(this.dirs.config().resolve(SpamFilter.FILE_NAME)).exists();
    }

    private void givenTheSpamMarksCannotBeSaved() throws IOException {
        blockWrites(this.dirs.config().resolve(SpamFilter.FILE_NAME));
    }

    private void givenTheFolder(String name) throws IOException {
        Files.createDirectories(this.dir.resolve(name));
    }

    private void givenTheFolderIsShared(String name, boolean subdirectories) throws Exception {
        Path folder = Files.createDirectories(this.dir.resolve(name));
        onLoop(() -> {
            this.network.shareDirectory(folder, subdirectories);
            return null;
        });
    }

    private void givenTheSharedFoldersAre(String... folders) throws Exception {
        assertThat(onLoop(this.network::sharedDirectories))
                .extracting(d -> d.path().getFileName().toString() + (d.subdirectories() ? "/**" : ""))
                .containsExactlyInAnyOrder(folders);
    }

    private void givenTheIpFilterIsReplacedByTheTestRanges() throws Exception {
        whenTheIpFilterIsReplacedByTheTestRanges();
        assertThat(this.reply).isEqualTo(2);
    }

    private void givenAnUnreadableFile(String name) throws IOException {
        Files.write(this.dirs.config().resolve(name), new byte[] {9, 9, 9});
    }

    private void givenManualServersOfHighPriority() {
        this.preferences =
                this.preferences.toBuilder().manualServersHighPriority(true).build();
    }

    private void givenTheServerFilesCannotBeSaved() throws IOException {
        blockWrites(this.dirs.config().resolve(ServerList.FILE_NAME));
        blockWrites(this.dirs.config().resolve(ServerList.STATIC_FILE_NAME));
    }

    private void givenTheServerIsAdded(ServerList.Address server) throws Exception {
        assertThat(onLoop(() -> this.network.addServer(server))).isTrue();
    }

    private void givenTheServerIsNotAddedAgain(ServerList.Address server) throws Exception {
        assertThat(onLoop(() -> this.network.addServer(server))).as("known").isFalse();
    }

    private void givenTheOnlyServerHasThePriority(ServerInfo.Priority priority) throws Exception {
        assertThat(onLoop(this.network::servers))
                .singleElement()
                .satisfies(s -> assertThat(s.priority()).isEqualTo(priority));
    }

    private void givenTheServerIsMadeStatic(ServerList.Address server) throws Exception {
        assertThat(onLoop(() -> this.network.setStaticServer(server, true))).isTrue();
    }

    private void givenTheServerPriorityIsSet(ServerList.Address server, ServerInfo.Priority priority) throws Exception {
        assertThat(onLoop(() -> this.network.setServerPriority(server, priority)))
                .isTrue();
    }

    private void givenAnUnknownServerIsNeitherMadeStaticNorPrioritised(ServerList.Address server) throws Exception {
        assertThat(onLoop(() -> this.network.setStaticServer(server, true))).isFalse();
        assertThat(onLoop(() -> this.network.setServerPriority(server, ServerInfo.Priority.LOW)))
                .isFalse();
    }

    private void givenTheServerUpdateTellsItIsStatic() throws Exception {
        assertThat(next(EngineEvent.ServerUpdated.class).server().isStatic()).isTrue();
    }

    private void givenTheSettingsMovedTheTcpPort() throws Exception {
        whenTheSettingsMoveTheTcpPort();
    }

    private void givenTheTcpPortIsTakenByAnotherProgram() throws IOException {
        this.takenTcpPort = new ServerSocket(this.preferences.tcpPort());
    }

    private void givenTheUdpPortIsTakenByAnotherProgram() throws IOException {
        this.takenUdpPort = new DatagramSocket(this.preferences.udpPort());
    }

    private void givenKadIsEnabled() {
        this.preferences = this.preferences.toBuilder().kadEnabled(true).build();
    }

    private void givenKadIsConnected() throws Exception {
        whenKadIsConnected();
    }

    private void givenKadIsConnectedTwice() throws Exception {
        onLoop(() -> {
            this.network.connectKad();
            this.network.connectKad();
            return null;
        });
        assertThat(onLoop(this.network::kadInfo)).isPresent();
    }

    private void givenKadIsBootstrappedFromAFreePortOfThisMachine() throws Exception {
        int port = freeUdpPort();
        onLoop(() -> {
            this.network.kadBootstrap(Ipv4.of(127, 0, 0, 1), port);
            return null;
        });
    }

    private void givenTheDownloadIsCancelled(Ed2kLink.FileLink link) throws Exception {
        onLoop(() -> this.network.addDownload(link) != null && this.network.cancelDownload(link.hash()));
        assertThat(onLoop(this.network::history).cancelled(link.hash())).isTrue();
    }

    private void givenTheCancelledFilesAreOnDisk() throws IOException {
        this.remembered = Files.readAllBytes(this.dirs.config().resolve(CancelledFiles.FILE_NAME));
    }

    private void givenTheCancelledFilesCannotBeSaved() throws IOException {
        blockWrites(this.dirs.config().resolve(CancelledFiles.FILE_NAME));
    }

    private void givenCancelledFilesAreNotRemembered() {
        this.preferences =
                this.preferences.toBuilder().rememberCancelledFiles(false).build();
    }

    private void givenNodesDatHolds(NodesDat.Contact... contacts) throws IOException {
        Files.write(this.dirs.config().resolve(KadNode.NODES_FILE), NodesDat.write(List.of(contacts)));
    }

    private void givenTheServerGotALogin() throws InterruptedException {
        assertThat(this.scripted.next(Opcodes.Server.LOGINREQUEST.code(), Duration.ofSeconds(10)))
                .isPresent();
    }

    private void givenKadRunsOnItsPort() throws Exception {
        this.kadPort = onLoop(this.network::kadInfo).orElseThrow().udpPort();
    }

    private void givenThePortsWereMovedToFreeOnes() throws Exception {
        whenThePortsAreMovedToFreeOnes();
    }

    private void givenTheNetworkLoggedInAgainWithTheNewPorts() throws Exception {
        thenTheServerGetsALoginWithTheNewTcpPort();
        thenTheNetworkConnectsAgain();
    }

    private void givenServersAreLearntFromServersAndLanAddressesFiltered() {
        this.preferences = this.preferences.toBuilder()
                .addServersFromServer(true)
                .filterLanAddresses(true)
                .build();
    }

    private void givenAnIpFilterBlocking(int ip) throws IOException {
        Files.write(
                this.dirs.config().resolve(Network.IPFILTER_FILE),
                IpFilterDat.write(List.of(new IpFilterDat.Range(ip, ip, 0, "test"))));
    }

    // Failed too often: gone once logged in, before any could be pinged.
    private void givenDeadServersAreKnown() throws Exception {
        List<ServerMet.Server> dead = List.of(
                new ServerMet.Server(
                        Ipv4.of(198, 51, 100, 21), 65_534, List.of(new Tag.IntTag(TagName.id(ServerTag.FAIL), 99))),
                new ServerMet.Server(
                        Ipv4.of(198, 51, 100, 22), 65_534, List.of(new Tag.IntTag(TagName.id(ServerTag.FAIL), 99))));
        assertThat(onLoop(() -> this.network.addServers(dead))).isEqualTo(2);
    }

    private void givenTheNetworkConnectedTo(ScriptedServer server) throws Exception {
        whenTheNetworkConnectsTo(server);
    }

    private void givenTheServerWasAskedForItsList() throws InterruptedException {
        thenTheServerIsAskedForItsList();
    }

    private void givenTheServerToldItsName(String name) throws Exception {
        whenTheServerTellsItsName(name);
        thenTheServerUpdateTellsTheName(name);
    }

    private void givenDownloadsAreAddedPaused() {
        this.preferences = this.preferences.toBuilder().addDownloadsPaused(true).build();
    }

    private void givenTheDownloadIsAddedPaused(Ed2kLink.FileLink link) throws Exception {
        assertThat(onLoop(() -> this.network.addDownload(link)).status()).isEqualTo(DownloadInfo.Status.PAUSED);
    }

    private void givenTheDownload(Ed2kLink.FileLink link) throws Exception {
        onLoop(() -> this.network.addDownload(link));
    }

    private void givenTheFriend(Hash16 hash, String nick) throws Exception {
        onLoop(() -> {
            this.network.addFriend(new Friend(hash, nick, Optional.empty()));
            return null;
        });
    }

    private void givenTheFriendSlotIsGivenTo(Hash16 friend) throws Exception {
        whenTheFriendSlotIsGivenTo(friend);
        assertThat(this.reply).isEqualTo(true);
    }

    private void givenTheDownloadPriorityIsSet(Ed2kLink.FileLink link, DownloadPriority priority) throws Exception {
        assertThat(onLoop(() -> this.network.setDownloadPriority(link.hash(), priority)))
                .isTrue();
    }

    private void givenThePreviewIsTurnedOn(Ed2kLink.FileLink link) throws Exception {
        assertThat(onLoop(() -> this.network.setDownloadPreview(link.hash(), true)))
                .isTrue();
    }

    private void givenThereIsNothingToPreviewYet(Ed2kLink.FileLink link) throws Exception {
        assertThat(onLoop(() -> this.network.downloadData(link.hash())))
                .as("nothing to preview yet")
                .isEmpty();
    }

    private void givenTheCategories(Category... categories) throws Exception {
        onLoop(() -> {
            this.network.setCategories(List.of(categories));
            return null;
        });
    }

    private void givenTheDownloadInCategory(Ed2kLink.FileLink link, int category) throws Exception {
        onLoop(() -> {
            this.network.addDownload(link);
            this.network.setDownloadCategory(link.hash(), category);
            return null;
        });
    }

    private void givenAClientListeningWithABacklogOf(int backlog) throws IOException {
        this.peerSocket = new ServerSocket(0, backlog, InetAddress.getLoopbackAddress());
    }

    private void givenTheObfuscation(ObfuscationMode mode) {
        this.preferences = this.preferences.toBuilder().obfuscation(mode).build();
    }

    private void givenWeSaidHelloToTheClient() throws IOException {
        this.answer = this.network.hello(local(this.peerSocket.getLocalPort()));
        this.peerConnection = this.peerSocket.accept();
        assertThat(Hello.decode(read(this.peerConnection)).userHash()).isEqualTo(USER);
    }

    private void givenTheClientAnsweredWithoutTheIdentification() throws Exception {
        whenTheClientSendsAHelloThenItsAnswerWithoutTheIdentification();
        thenTheIdentificationIs(PeerInfo.Identification.NOT_SUPPORTED);
    }

    private void givenServersAreLearntFromClients() {
        this.preferences =
                this.preferences.toBuilder().addServersFromClients(true).build();
    }

    private void whenTheResultIsMarkedAsNotSpam() throws Exception {
        this.marks = onLoop(() -> this.network.markSpam(List.of(SPAM), false));
    }

    private void whenTheFolderIsShared(String name) {
        Path folder = this.dir.resolve(name);
        this.exception = catchThrowable(() -> onLoop(() -> {
            this.network.shareDirectory(folder, false);
            return null;
        }));
    }

    private void whenTheFolderIsUnshared(String name) {
        Path folder = this.dir.resolve(name);
        this.exception = catchThrowable(() -> onLoop(() -> {
            this.network.unshareDirectory(folder);
            return null;
        }));
    }

    private void whenNoKnownFilesAreAdded() {
        this.exception = catchThrowable(() -> onLoop(() -> this.network.addKnownFiles(List.of(), List.of())));
    }

    private void whenTheSharedFileIsRenamed(Hash16 hash, String name) throws Exception {
        this.reply = onLoop(() -> this.network.renameSharedFile(hash, name));
    }

    private void whenTheUploadPriorityIsSet(Hash16 hash, UploadPriority priority) throws Exception {
        this.reply = onLoop(() -> this.network.setUploadPriority(hash, priority));
    }

    private void whenTheNetworkIsCreated() throws Exception {
        create();
    }

    private void whenTheIpFilterIsReplacedByNoRange() {
        this.exception = catchThrowable(() -> onLoop(() -> this.network.setIpFilter(List.of())));
    }

    private void whenTheIpFilterIsReplacedByTheTestRanges() throws Exception {
        this.reply = onLoop(() -> this.network.setIpFilter(
                List.of(new IpFilterDat.Range(BLOCKED, BLOCKED, 0, "test"), new IpFilterDat.Range(1, 2, 0, "low"))));
    }

    private void whenTheNetworkConnectsTo(int ip, int port) {
        this.exception = catchThrowable(() -> onLoop(() -> {
            this.network.connect(Optional.of(new ServerList.Address(ip, port)));
            return null;
        }));
    }

    private void whenTheCategoriesAre(Category... categories) {
        this.exception = catchThrowable(() -> onLoop(() -> {
            this.network.setCategories(List.of(categories));
            return null;
        }));
    }

    private void whenTheServerIsRemoved(ServerList.Address server) throws Exception {
        this.reply = onLoop(() -> this.network.removeServer(server));
    }

    private void whenADatagramArrives(Opcodes.ServerUdp opcode, int length) throws Exception {
        InetSocketAddress from = new InetSocketAddress(InetAddress.getLoopbackAddress(), 4665);
        onLoop(() -> {
            this.network.datagram(from, Packet.of(Protocol.EDONKEY, opcode.code(), new byte[length]));
            return null;
        });
    }

    // Straight to the network: the preferences it reads stay as they were.
    private void whenTheSettingsMoveTheTcpPort() throws Exception {
        Preferences was = this.preferences;
        Preferences now = was.toBuilder().tcpPort(freeTcpPort()).build();
        onLoop(() -> {
            this.network.reconfigure(was, now);
            return null;
        });
    }

    private void whenTheNetworkStarts() throws Exception {
        started();
    }

    private void whenTheKadFirewallIsRechecked() throws Exception {
        this.reply = onLoop(this.network::recheckKadFirewall);
    }

    private void whenKadIsBootstrappedFrom(int ip, int port) {
        this.exception = catchThrowable(() -> onLoop(() -> {
            this.network.kadBootstrap(ip, port);
            return null;
        }));
    }

    // A bootstrap list keeps nothing of its own.
    private void whenABootstrapListIsAdded(NodesDat.Contact... contacts) throws Exception {
        this.reply = onLoop(() -> this.network.addKadContacts(new NodesDat.Contents(2, true, List.of(contacts))));
    }

    private void whenAContactListIsAdded(NodesDat.Contact... contacts) throws Exception {
        this.reply = onLoop(() -> this.network.addKadContacts(new NodesDat.Contents(2, false, List.of(contacts))));
    }

    private void whenKadIsConnected() throws Exception {
        onLoop(() -> {
            this.network.connectKad();
            return null;
        });
    }

    private void whenKadIsDisconnectedTwice() throws Exception {
        onLoop(() -> {
            this.network.disconnectKad();
            this.network.disconnectKad();
            return null;
        });
    }

    private void whenKadIsSearchedFor(String text) {
        SearchRequest kad = new SearchRequest(text, SearchRequest.Scope.KAD);
        this.exception = catchThrowable(() -> onLoop(() -> this.network.search(kad)));
    }

    private void whenWeSayHelloToTheClientWhichHangsUp() throws IOException {
        this.answer = this.network.hello(local(this.peerSocket.getLocalPort()));
        try (Socket socket = this.peerSocket.accept()) {
            this.packet = read(socket);
        }
    }

    private void whenKadIsTurnedOffInTheSettings() throws Exception {
        reconfigure(this.network, p -> p.kadEnabled(false));
    }

    private void whenThePortsAreMovedToFreeOnes() throws Exception {
        this.newTcpPort = freeTcpPort();
        this.newUdpPort = freeUdpPort();
        reconfigure(this.network, p -> p.tcpPort(this.newTcpPort).udpPort(this.newUdpPort));
    }

    // Not asked for: not taken.
    private void whenTheServerSendsAListNotAskedFor() throws Exception {
        this.scripted.send(
                ServerMessages.serverList(List.of(new ServerMessages.Endpoint(Ipv4.of(198, 51, 100, 31), 65_534))));
        this.scripted.send(ServerMessages.message("after the list"));
        next(EngineEvent.ServerMessage.class);
    }

    private void whenEd2kAndTheMemoryOfCancelledFilesAreTurnedOff() throws Exception {
        reconfigure(this.network, p -> p.ed2kEnabled(false).rememberCancelledFiles(false));
    }

    private void whenTheNetworkConnectsTo(ScriptedServer server) throws Exception {
        this.scripted = server;
        connect(this.network, server);
        this.connected = next(EngineEvent.ServerConnected.class);
    }

    private void whenTheServerSendsMessagesServersCallbacksAndItsStatus() throws Exception {
        this.scripted.send(
                ServerMessages.message("ERROR: no more room\nWARNING: low id\nWelcome"),
                ServerMessages.serverList(List.of(
                        // No UDP port above these: they are never pinged.
                        new ServerMessages.Endpoint(Ipv4.of(198, 51, 100, 1), 65_534),
                        new ServerMessages.Endpoint(Ipv4.of(198, 51, 100, 2), 65_533))),
                callback(Ipv4.of(10, 0, 0, 5)),
                callback(BLOCKED),
                callback(Ipv4.of(127, 0, 0, 9)),
                Packet.empty(Protocol.EDONKEY, Opcodes.Server.REJECT.code()),
                Packet.of(Protocol.EDONKEY, Opcodes.Server.CALLBACKREQUESTED.code(), new byte[2]),
                Packet.of(Protocol.EDONKEY, 0xEE, new byte[2]),
                new ServerMessages.ServerStatus(500, 600).encode());
    }

    private void whenTheServerTellsItsName(String name) throws Exception {
        this.scripted.send(new ServerMessages.ServerIdent(
                        Hash16.parseHex("33".repeat(16)),
                        this.scripted.ip(),
                        this.scripted.port(),
                        Optional.of(name),
                        Optional.empty())
                .encode());
    }

    private void whenTheServerHangsUp() throws Exception {
        this.scripted.hangUp();
        this.disconnected = next(EngineEvent.ServerDisconnected.class);
    }

    private void whenTheDownloadIsAddedAgain(Ed2kLink.FileLink link) throws Exception {
        this.reply = onLoop(() -> this.network.addDownload(link)).status();
    }

    private void whenTheDownloadsAreResumed(Ed2kLink.FileLink... links) throws Exception {
        onLoop(() -> Arrays.stream(links).allMatch(link -> this.network.resumeDownload(link.hash())));
    }

    private void whenTheFriendSlotIsGivenTo(Hash16 friend) throws Exception {
        this.reply = onLoop(() -> this.network.setFriendSlot(Optional.of(friend)));
    }

    private void whenTheFriendIsRemoved(Hash16 friend) throws Exception {
        this.reply = onLoop(() -> this.network.removeFriend(friend));
    }

    private void whenTheDownloadIsStopped(Ed2kLink.FileLink link) throws Exception {
        this.reply = onLoop(() -> this.network.stopDownload(link.hash()));
    }

    private void whenTheDownloadIsCancelled(Ed2kLink.FileLink link) throws Exception {
        this.reply = onLoop(() -> this.network.cancelDownload(link.hash()));
    }

    private void whenTheSchedulerResumesThenStops(int resume, int stop) throws Exception {
        onLoop(() -> {
            this.network.scheduleCategories(OptionalInt.empty(), OptionalInt.of(resume));
            this.network.scheduleCategories(OptionalInt.of(stop), OptionalInt.empty());
            return null;
        });
    }

    private void whenWeSayHelloToNobody() throws IOException {
        this.answer = this.network.hello(local(freeTcpPort()));
    }

    private void whenTheClientAnswersOurHelloUnreadably() throws IOException {
        this.answer = this.network.hello(local(this.peerSocket.getLocalPort()));
        this.peerConnection = this.peerSocket.accept();
        read(this.peerConnection);
        this.peerConnection
                .getOutputStream()
                .write(TcpFraming.encode(Packet.of(Protocol.EDONKEY, Opcodes.Client.HELLOANSWER.code(), new byte[3])));
    }

    // A hello before the answer is not taken for it.
    private void whenTheClientSendsAHelloThenItsAnswerWithoutTheIdentification() throws Exception {
        this.peerConnection.getOutputStream().write(TcpFraming.encode(PEER_HELLO.encode(false)));
        this.peerConnection.getOutputStream().write(TcpFraming.encode(PEER_HELLO.encode(true)));
        this.info = this.answer.get(20, TimeUnit.SECONDS);
    }

    // Other packets afterwards are ignored; a hello of its own is answered.
    private void whenTheClientSendsOtherPacketsThenAHello() throws IOException {
        this.peerConnection.getOutputStream().write(TcpFraming.encode(emule()));
        this.peerConnection
                .getOutputStream()
                .write(TcpFraming.encode(Packet.empty(Protocol.EDONKEY, Opcodes.Client.HELLOANSWER.code())));
        this.peerConnection.getOutputStream().write(TcpFraming.encode(PEER_HELLO.encode(false)));
        this.packet = read(this.peerConnection);
    }

    // A documentation address with no UDP port above it: never pinged, never connected to.
    private void whenAClientSaysHelloWithItsServer() throws IOException {
        Hello hello = Hello.of(
                Hash16.parseHex("23".repeat(16)), 0, 4999, "peer", 0, 0, 0, 0, Ipv4.of(198, 51, 100, 3), 65_534);
        this.peerConnection = new Socket(InetAddress.getLoopbackAddress(), this.preferences.tcpPort());
        this.peerConnection.setSoTimeout(10_000);
        this.peerConnection.getOutputStream().write(TcpFraming.encode(hello.encode(false)));
    }

    private void thenNothingIsSpam() {
        assertThat(this.marks.spam()).isEmpty();
    }

    private void thenTheResultIsNotSpam() {
        assertThat(this.marks.notSpam()).containsExactly(SPAM.hash());
    }

    private void thenTheNetworkKeepsTheseMarks() throws Exception {
        assertThat(onLoop(this.network::spamMarks)).isEqualTo(this.marks);
    }

    private void thenExceptionOccurredWithTheCause(Class<? extends Throwable> cause) {
        assertThat(this.exception).hasCauseInstanceOf(cause);
    }

    private void thenExceptionOccurredWithTheCause(Class<? extends Throwable> cause, String messagePart) {
        assertThat(this.exception).hasCauseInstanceOf(cause).hasMessageContaining(messagePart);
    }

    private void thenTheNetworkAnswers(Object expected) {
        assertThat(this.reply).isEqualTo(expected);
    }

    private void thenThereAreNoSharedFiles() throws Exception {
        assertThat(onLoop(this.network::sharedFiles)).isEmpty();
    }

    private void thenThereAreNoSharedFolders() throws Exception {
        assertThat(onLoop(this.network::sharedDirectories)).isEmpty();
    }

    private void thenTheStatusCountsNoSharedFile() throws Exception {
        assertThat(onLoop(this.network::status).sharedFiles()).isZero();
    }

    private void thenTheHistoryHasNoDownloadOf(Hash16 hash) throws Exception {
        assertThat(onLoop(this.network::history).downloaded(hash)).isFalse();
    }

    private void thenTheSharedFolderCountIs(int count) throws Exception {
        assertThat(onLoop(this.network::sharedDirectories)).hasSize(count);
    }

    private void thenTheNetworkDescribesItsDownloadsAndUploads() throws Exception {
        assertThat(onLoop(this.network::describe)).startsWith("downloads:").contains("uploads: ");
    }

    private void thenTheConfigurationHasTheFile(String name) {
        assertThat(this.dirs.config().resolve(name)).exists();
    }

    private void thenTheLogHas(LogLine.Level level, String text) {
        assertThat(this.log).contains(new Logged(level, text));
    }

    private void thenTheLogHasOnce(String text) {
        assertThat(this.log).filteredOn(l -> l.text().equals(text)).hasSize(1);
    }

    private void thenTheLogNeverHas(String text) {
        assertThat(this.log).noneMatch(l -> l.text().equals(text));
    }

    private void thenNothingIsLogged() {
        assertThat(this.log).isEmpty();
    }

    private void thenThereAreNoCategories() throws Exception {
        assertThat(onLoop(this.network::categories)).isEmpty();
    }

    private void thenThereAreNoSavedSearches() throws Exception {
        assertThat(onLoop(this.network::savedSearches)).isEmpty();
    }

    private void thenTheFileIsNotCancelled(Hash16 hash) throws Exception {
        assertThat(onLoop(this.network::history).cancelled(hash)).isFalse();
    }

    private void thenThereAreNoServers() throws Exception {
        assertThat(onLoop(this.network::servers)).isEmpty();
    }

    private void thenNoEventIsReported() {
        assertThat(this.events).isEmpty();
    }

    private void thenTheLogTellsItCannotListenOnTheTakenPort() {
        int port = this.takenTcpPort.getLocalPort();
        assertThat(this.log).anySatisfy(l -> assertThat(l.text()).startsWith("Cannot listen on TCP port " + port));
    }

    private void thenTheStatusTellsTheTakenPort() throws Exception {
        assertThat(onLoop(this.network::status).tcpPort()).isEqualTo(this.takenTcpPort.getLocalPort());
    }

    private void thenThereAreNoKadContacts() throws Exception {
        assertThat(onLoop(this.network::kadContacts)).isEmpty();
    }

    private void thenThereAreNoKadSearches() throws Exception {
        assertThat(onLoop(this.network::kadSearches)).isEmpty();
    }

    private void thenKadDoesNotRun() throws Exception {
        assertThat(onLoop(this.network::kadInfo)).isEmpty();
    }

    private void thenKadHasStillNoPort() throws Exception {
        assertThat(onLoop(this.network::kadInfo)).as("still no port").isEmpty();
    }

    private void thenTheLogTellsKadCannotStartOnTheTakenPort() {
        int port = this.takenUdpPort.getLocalPort();
        assertThat(this.log).anySatisfy(l -> assertThat(l.text()).startsWith("Cannot start Kad on UDP port " + port));
    }

    private void thenOurHelloTellsTheCryptOptions(int wanted) throws IOException {
        assertThat(Hello.decode(this.packet).miscOptions2() & CRYPT)
                .as(this.preferences.obfuscation().name())
                .isEqualTo(wanted);
    }

    private void thenTheHelloFailsWith(Class<? extends Throwable> cause) {
        assertThatThrownBy(() -> this.answer.get(20, TimeUnit.SECONDS)).hasCauseInstanceOf(cause);
    }

    private void thenTheCancelledFilesOnDiskAreLeftAsTheyWere() throws IOException {
        assertThat(Files.readAllBytes(this.dirs.config().resolve(CancelledFiles.FILE_NAME)))
                .as("left as it was")
                .isEqualTo(this.remembered);
    }

    private void thenNodesDatHolds(NodesDat.Contact... contacts) throws IOException {
        assertThat(NodesDat.read(Files.readAllBytes(this.dirs.config().resolve(KadNode.NODES_FILE)))
                        .contacts())
                .extracting(NodesDat.Contact::id)
                .containsExactly(
                        Arrays.stream(contacts).map(NodesDat.Contact::id).toArray(UInt128[]::new));
    }

    private void thenTheServerGetsALoginWithTheNewTcpPort() throws IOException, InterruptedException {
        Packet login = this.scripted
                .next(Opcodes.Server.LOGINREQUEST.code(), Duration.ofSeconds(10))
                .orElseThrow();
        assertThat(ServerMessages.Login.decode(login).tcpPort())
                .as("logged in again with the new port")
                .isEqualTo(this.newTcpPort);
    }

    private void thenKadMovedToTheNewUdpPort() throws Exception {
        assertThat(onLoop(this.network::kadInfo).orElseThrow().udpPort())
                .as("Kad moved too")
                .isEqualTo(this.newUdpPort)
                .isNotEqualTo(this.kadPort);
    }

    private void thenTheNetworkConnectsAgain() throws InterruptedException {
        next(EngineEvent.ServerConnected.class);
    }

    private void thenTheNetworkIsDisconnected() throws InterruptedException {
        next(EngineEvent.ServerDisconnected.class);
    }

    private void thenTheStatusHasNoServer() throws Exception {
        assertThat(onLoop(this.network::status).server()).isEmpty();
    }

    private void thenTheClientIdIs(long id) {
        assertThat(this.connected.clientId()).isEqualTo(id);
    }

    private void thenRelatedSearchIsOn() throws Exception {
        assertThat(onLoop(this.network::status).relatedSearch()).isTrue();
    }

    private void thenTheServerIsAskedForItsList() throws InterruptedException {
        assertThat(this.scripted.next(Opcodes.Server.GETSERVERLIST.code(), Duration.ofSeconds(10)))
                .isPresent();
    }

    private void thenTheServerUpdateTellsTheUsers(int users) throws InterruptedException {
        assertThat(next(EngineEvent.ServerUpdated.class).server().users()).isEqualTo(users);
    }

    private void thenTheLogHasTheNewServersFromTheServer(int count) {
        thenTheLogHas(LogLine.Level.INFO, count + " new servers from 127.0.0.1:" + this.scripted.port());
    }

    private void thenTheServerCountIs(int count) throws Exception {
        assertThat(onLoop(this.network::servers)).hasSize(count);
    }

    private void thenTheServerUpdateTellsTheName(String name) throws InterruptedException {
        assertThat(next(EngineEvent.ServerUpdated.class).server().name()).isEqualTo(name);
    }

    private void thenTheDisconnectionNamesTheServer() {
        assertThat(this.disconnected.server()).isPresent();
    }

    private void thenOurClientIdIs(long id) throws Exception {
        assertThat(onLoop(this.network::status).clientId()).isEqualTo(id);
    }

    private void thenTheLogTellsTheDisconnectionFrom(String name) {
        String start = "Disconnected from " + name + " (127.0.0.1:" + this.scripted.port() + ")";
        assertThat(this.log).anySatisfy(l -> assertThat(l.text()).startsWith(start));
    }

    private void thenTheDownloadIsLeftAsItIs(DownloadInfo.Status status) {
        assertThat(this.reply).as("added again: left as it is").isEqualTo(status);
    }

    private void thenTheServerIsAskedForTheSourcesOf(Ed2kLink.FileLink link) throws IOException, InterruptedException {
        Packet asked = this.scripted
                .next(Opcodes.Server.GETSOURCES_OBFU.code(), Duration.ofSeconds(10))
                .orElseThrow();
        assertThat(asked.reader().hash16()).isEqualTo(link.hash());
    }

    private void thenTheServerIsNeverAskedForTheSourcesOfTheLargeFile() throws InterruptedException {
        assertThat(this.scripted.next(Opcodes.Server.GETSOURCES_OBFU.code(), Duration.ofSeconds(3)))
                .as("never for the large one")
                .isEmpty();
    }

    private void thenThereIsNoFriendSlot() throws Exception {
        assertThat(onLoop(this.network::friendSlot)).isEmpty();
    }

    private void thenThereAreNoFriends() throws Exception {
        assertThat(onLoop(this.network::friends)).isEmpty();
    }

    private void thenTheOnlyDownloadIs(DownloadInfo.Status status, DownloadPriority priority) throws Exception {
        assertThat(onLoop(this.network::downloads)).singleElement().satisfies(d -> {
            assertThat(d.status()).isEqualTo(status);
            assertThat(d.priority()).isEqualTo(priority);
        });
    }

    private void thenThereAreNoDownloads() throws Exception {
        assertThat(onLoop(this.network::downloads)).isEmpty();
    }

    private void thenEveryDownloadIs(DownloadInfo.Status status) throws Exception {
        assertThat(onLoop(this.network::downloads))
                .extracting(DownloadInfo::status)
                .containsOnly(status);
    }

    private void thenTheClientGets(Opcodes.Client opcode) {
        assertThat(this.packet.opcode()).isEqualTo(opcode.code());
    }

    private void thenTheAnswerComesFrom(Hash16 userHash, String nick) {
        assertThat(this.info.userHash()).isEqualTo(userHash);
        assertThat(this.info.nick()).contains(nick);
    }

    private void thenTheIdentificationIs(PeerInfo.Identification identification) {
        assertThat(this.info.identification()).isEqualTo(identification);
    }

    private void thenTheServerCountChangesTo(int count) throws InterruptedException {
        assertThat(next(EngineEvent.ServersChanged.class).count()).isEqualTo(count);
    }

    private static Stream<Arguments> obfuscationWanted() {
        return Stream.of(
                Arguments.of(ObfuscationMode.DISABLED, 0),
                Arguments.of(ObfuscationMode.SUPPORTED, Hello.MISC2_SUPPORTS_CRYPT),
                Arguments.of(ObfuscationMode.REQUESTED, Hello.MISC2_SUPPORTS_CRYPT | Hello.MISC2_REQUESTS_CRYPT),
                Arguments.of(ObfuscationMode.REQUIRED, CRYPT));
    }

    private Network create() throws Exception {
        return create(false);
    }

    private Network create(boolean automaticConnections) throws Exception {
        this.network = onLoop(() -> new Network(
                this.loop,
                this.dirs.config(),
                () -> this.preferences,
                this.events::add,
                (level, text) -> this.log.add(new Logged(level, text)),
                USER,
                this.dirs.data().resolve("temp"),
                () -> this.dirs.data().resolve("incoming"),
                Optional.empty(),
                automaticConnections));
        return this.network;
    }

    private Network started() throws Exception {
        Network created = create();
        onLoop(() -> {
            created.start();
            return null;
        });
        return created;
    }

    private void connect(Network network, ScriptedServer server) throws Exception {
        onLoop(() -> {
            network.connect(Optional.of(new ServerList.Address(server.ip(), server.port())));
            return null;
        });
    }

    private void reconfigure(Network network, UnaryOperator<Preferences.Builder> change) throws Exception {
        Preferences was = this.preferences;
        Preferences now = change.apply(was.toBuilder()).build();
        this.preferences = now;
        onLoop(() -> {
            network.reconfigure(was, now);
            return null;
        });
    }

    private <T> T onLoop(Callable<T> task) throws Exception {
        return this.loop.submit(task).get(20, TimeUnit.SECONDS);
    }

    private <E extends EngineEvent> E next(Class<E> type) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            EngineEvent event = this.events.poll(100, TimeUnit.MILLISECONDS);
            if (type.isInstance(event)) {
                return type.cast(event);
            }
        }
        throw new AssertionError("no " + type.getSimpleName());
    }

    private static InetSocketAddress local(int port) {
        return new InetSocketAddress(InetAddress.getLoopbackAddress(), port);
    }

    private static Packet read(Socket socket) throws IOException {
        socket.setSoTimeout(10_000);
        return TcpFraming.read(new BufferedInputStream(socket.getInputStream()), FrameLimit.fixed(1 << 20));
    }

    // A packet of the extended protocol that has nothing to do with the hello.
    private static Packet emule() {
        return Packet.empty(Protocol.EMULE, Opcodes.Extended.KAD_FWTCPCHECK_ACK.code());
    }

    // A request of the server that we call a client back.
    private static Packet callback(int ip) {
        return Packet.of(
                Protocol.EDONKEY,
                Opcodes.Server.CALLBACKREQUESTED.code(),
                new PacketWriter().u32(Ipv4.toEd2k(ip)).u16(4662));
    }

    // A directory where the file's next version is written first: every save of the file fails.
    private static void blockWrites(Path file) throws IOException {
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.createDirectories(tmp);
        Files.writeString(tmp.resolve("keep"), "x");
    }

    private static int freeTcpPort() throws IOException {
        try (ServerSocket probe = new ServerSocket(0)) {
            return probe.getLocalPort();
        }
    }

    private static int freeUdpPort() throws IOException {
        try (DatagramSocket probe = new DatagramSocket(0)) {
            return probe.getLocalPort();
        }
    }
}
