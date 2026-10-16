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
package org.njmule.core.kad;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.common.base.Ticker;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.njmule.core.api.Hash16;
import org.njmule.core.api.KadContactInfo;
import org.njmule.core.api.KadSearchInfo;
import org.njmule.core.api.SearchResult;
import org.njmule.core.persist.format.NodesDat;
import org.njmule.core.protocol.io.PacketWriter;
import org.njmule.core.protocol.kad.KadMessages;
import org.njmule.core.protocol.packet.Opcodes;
import org.njmule.core.protocol.packet.Packet;
import org.njmule.core.protocol.packet.Protocol;
import org.njmule.core.protocol.tag.FileTag;
import org.njmule.core.protocol.tag.Tag;
import org.njmule.core.protocol.tag.TagName;
import org.njmule.core.protocol.tag.Tags;
import org.njmule.core.search.SearchExpr;
import org.njmule.core.search.SearchTreeCodec;
import org.njmule.core.util.Ipv4;

/** The Kad messages a node answers and takes, with a manual clock and a transport that only records. */
class KadNodeMessagesTest {

    private static final int PORT = 4672;
    private static final int PEER = Ipv4.of(20, 1, 1, 1);
    private static final long SECOND = 1_000_000_000L;
    private static final long MINUTE = 60 * SECOND;
    private static final Hash16 USER = Hash16.parseHex("0102030405060708090a0b0c0d0e0f10");
    // Recent nodes that greet us and so are asked to check our port.
    private static final List<Integer> HELPERS =
            List.of(Ipv4.of(21, 1, 1, 1), Ipv4.of(22, 1, 1, 1), Ipv4.of(23, 1, 1, 1), Ipv4.of(24, 1, 1, 1));

    /** A packet the node sent. */
    private record Sent(int ip, int port, Packet packet, Optional<UInt128> receiverId, int receiverKey) {}

    /** A node the host was asked to connect to. */
    private record Check(int ip, int port, Hash16 userHash, int options) {}

    @TempDir
    Path dir;

    private final SecureRandom random = new SecureRandom();
    private final AtomicLong time = new AtomicLong(1_000 * SECOND);
    private final Ticker ticker = new Ticker() {
        @Override
        public long read() {
            return KadNodeMessagesTest.this.time.get();
        }
    };
    private final List<Sent> sent = new CopyOnWriteArrayList<>();
    private final List<Check> checks = new ArrayList<>();
    private final Set<Integer> blocked = new HashSet<>();
    private final UInt128 asker = UInt128.random(this.random);
    private final List<KadNode.Source> sources = new ArrayList<>();
    private final List<KadNode.FileResult> fileResults = new ArrayList<>();
    private KadIdentity identity;
    private KadNode node;
    private UInt128 peerId;
    private UInt128 contactId;
    private UInt128 target;
    private Hash16 fileHash;
    private KadMessages.Entry publishedFile;
    private UInt128 publishedSource;
    private List<KadMessages.Entry> toPublish;
    private List<KadMessages.Entry> answered;
    private SearchExpr expression;
    private boolean started;
    private Optional<Runnable> search;
    private int pongs;
    private UInt128 direct;
    private UInt128 large;
    private UInt128 good;

    @BeforeEach
    void create() throws IOException {
        this.identity = KadIdentity.loadOrCreate(this.dir, this.random);
        this.node = new KadNode(
                this.identity,
                (ip, port, packet, receiverId, receiverKey, senderKey) ->
                        this.sent.add(new Sent(ip, port, packet, receiverId, receiverKey)),
                (ip, port, userHash, options) -> this.checks.add(new Check(ip, port, userHash, options)),
                new KadNode.Settings(4662, USER, 3, ip -> false, this.blocked::contains),
                this.dir,
                this.random,
                this.ticker);
    }

    // Loaded contacts we never heard from may be gone: the asking node would take them as verified.
    @Test
    void shouldAnswerABootstrapRequestWithTheContactsWeHeardFrom() throws Exception {
        givenContactsAt(Ipv4.of(70, 1, 1, 1), Ipv4.of(71, 1, 1, 1));
        givenANodeSaidHelloWithVersion(Ipv4.of(72, 1, 1, 1), 8);
        givenANodeSaidHelloWithVersion(Ipv4.of(73, 1, 1, 1), 8);

        whenANodeAsksForABootstrap(PEER);

        thenOurBootstrapAnswerToListsTheContactsAt(PEER, Ipv4.of(72, 1, 1, 1), Ipv4.of(73, 1, 1, 1));
    }

    @Test
    void shouldLeaveADeadContactOutOfABootstrapAnswer() throws Exception {
        givenANodeSaidHelloWithVersion(Ipv4.of(72, 1, 1, 1), 8);
        givenTheContactLeftItsCheckUnanswered(Ipv4.of(72, 1, 1, 1));

        whenANodeAsksForABootstrap(PEER);

        thenOurBootstrapAnswerToListsTheContactsAt(PEER);
    }

    @Test
    void shouldDropABootstrapAnswerNobodyAskedFor() {
        givenContactsAt(Ipv4.of(70, 1, 1, 1), Ipv4.of(71, 1, 1, 1));

        whenAHelperAnswersABootstrap(Ipv4.of(25, 1, 1, 1));

        thenTheContactsAre(2, "an answer nobody asked for");
    }

    @Test
    void shouldTakeOnlyTheBootstrapAnswerToItsOwnRequest() {
        givenContactsAt(Ipv4.of(70, 1, 1, 1), Ipv4.of(71, 1, 1, 1));
        givenABootstrapRequestWentTo(Ipv4.of(25, 1, 1, 1));

        whenAHelperAnswersABootstrap(Ipv4.of(25, 1, 1, 1));

        thenTheContactsAre(4, "the helper and its one usable contact, not a LAN address or an old version");
        thenTheVerifiedContactsAre(3, "the helper proved itself by answering");
    }

    @Test
    void shouldAnswerAHelloObfuscatedAndAskForAnAcknowledgement() throws Exception {
        whenANodeSaysHello(PEER, 77);

        thenTheContactsAre(1);
        thenTheVerifiedContactsAre(0);
        thenOurHelloAnswerIsObfuscatedWithItsIdAndKey(PEER, 77);
        thenOurHelloAnswerAsksForAnAcknowledgement(PEER);
        thenTheNodeIsAskedToCheckOurPort(PEER, "a recent node is asked to check our port");
    }

    @Test
    void shouldVerifyANodeThatAcknowledgesWithOurKey() {
        givenANodeSaidHello(PEER, 77);
        givenTheNodeAcknowledgedWithoutOurKey(PEER);

        whenTheNodeAcknowledgesWithOurKey(PEER);

        thenTheVerifiedContactsAre(1);
    }

    @Test
    void shouldAnswerAnOldNodePlainAndTakeIt() throws Exception {
        whenANodeSaysHelloWithVersion(Ipv4.of(21, 1, 1, 1), 5);

        thenOurHelloAnswerIsPlain(Ipv4.of(21, 1, 1, 1));
        thenTheContactsAre(1);
        thenTheNodeIsNotAskedToCheckOurPort(Ipv4.of(21, 1, 1, 1), "too old to check our port");
    }

    @Test
    void shouldAnswerANodeBehindAFirewallButNotTakeIt() {
        givenANodeSaidHelloWithVersion(Ipv4.of(21, 1, 1, 1), 5);

        whenANodeBehindAFirewallSaysHello(Ipv4.of(22, 1, 1, 1));

        thenOurHelloAnswerWentTo(Ipv4.of(22, 1, 1, 1));
        thenTheContactsAre(1, "a node others cannot reach is no contact");
    }

    @Test
    void shouldCheckTheOldestContactAfterAMinute() {
        givenContactsAt(Ipv4.of(70, 1, 1, 1));

        whenMinutesPass(1);

        thenTheContactIsAskedWhetherItIsStillThere(Ipv4.of(70, 1, 1, 1));
    }

    @Test
    void shouldDropASilentContactOnceItsCheckExpired() {
        givenContactsAt(Ipv4.of(70, 1, 1, 1));
        givenTheContactWasAskedWhetherItIsStillThere(Ipv4.of(70, 1, 1, 1));
        givenAMinutePassedWhileTheContactWasWaitedFor();

        whenMinutesPass(1);

        thenTheContactsAre(0, "gone once its check expired");
    }

    @Test
    void shouldNotAcknowledgeAHelloAnswerNotAskedFor() {
        givenAKnownContactAt(Ipv4.of(70, 1, 1, 1));

        whenTheContactSendsItsHelloAnswer(Ipv4.of(70, 1, 1, 1), 99);

        thenNoAcknowledgementWentTo(Ipv4.of(70, 1, 1, 1));
    }

    @Test
    void shouldAcknowledgeTheAnswerToItsCheckWhenTheContactAsks() throws Exception {
        givenAKnownContactAt(Ipv4.of(70, 1, 1, 1));
        givenItsHelloAnswerCameUnasked(Ipv4.of(70, 1, 1, 1), 99);
        givenTheContactWasAskedWhetherItIsStillThere(Ipv4.of(70, 1, 1, 1));

        whenTheContactSendsItsHelloAnswer(Ipv4.of(70, 1, 1, 1), 99);

        thenOurAcknowledgementWentTo(Ipv4.of(70, 1, 1, 1), 99);
    }

    @Test
    void shouldKeepAContactThatAnswersItsCheck() throws Exception {
        givenAKnownContactAt(Ipv4.of(70, 1, 1, 1));
        givenItsHelloAnswerCameUnasked(Ipv4.of(70, 1, 1, 1), 99);
        givenTheContactWasAskedWhetherItIsStillThere(Ipv4.of(70, 1, 1, 1));
        givenTheContactAnsweredItsCheck(Ipv4.of(70, 1, 1, 1), 99);

        whenMinutesPassOneByOne(3);

        thenTheContactsAre(1, "it answered: no longer checked to death");
    }

    @Test
    void shouldAnswerALookupMeantForUs() throws Exception {
        givenContactsAt(Ipv4.of(70, 1, 1, 1));

        whenALookupMeantForUsComes();

        thenTheLookupIsAnswered();
    }

    @Test
    void shouldNotAnswerALookupMeantForAnotherNode() {
        givenContactsAt(Ipv4.of(70, 1, 1, 1));

        whenALookupMeantForAnotherNodeComes();

        thenTheLookupIsNotAnswered();
    }

    @Test
    void shouldStoreAKeywordPublishedToUs() throws Exception {
        whenAFileIsPublishedToUsUnderOurId("ubuntu desktop.iso", 4000);

        thenThePublishIsAnsweredForOurId(PEER);
        thenTheKeywordsIndexedAre(1);
    }

    @Test
    void shouldFindAKeywordPublishedToUsWithoutAnExpression() throws Exception {
        givenAFilePublishedToUsUnderOurId("ubuntu desktop.iso", 4000);

        whenANodeSearchesOurId(Ipv4.of(23, 1, 1, 1));

        thenTheSearchAnswerListsThePublishedFile(Ipv4.of(23, 1, 1, 1));
    }

    @Test
    void shouldFindAKeywordPublishedToUsWithAnExpressionThatMatches() {
        givenAFilePublishedToUsUnderOurId("ubuntu desktop.iso", 4000);

        whenANodeSearchesOurIdFor(Ipv4.of(24, 1, 1, 1), "ubuntu");

        thenASearchAnswerWentTo(Ipv4.of(24, 1, 1, 1), "the expression matches");
    }

    @Test
    void shouldSendNothingWhenAnExpressionFindsNothing() {
        givenAFilePublishedToUsUnderOurId("ubuntu desktop.iso", 4000);

        whenANodeSearchesOurIdFor(Ipv4.of(26, 1, 1, 1), "windows");

        thenNoSearchAnswerWentTo(Ipv4.of(26, 1, 1, 1), "nothing found, nothing sent");
    }

    @Test
    void shouldNotStoreAKeywordPublishedFarFromUs() {
        whenAKeywordFarFromUsIsPublishedBy(PEER);

        thenNoPublishAnswerWentTo(PEER);
        thenNothingIsIndexed();
    }

    @Test
    void shouldNotStoreASourcePublishedFarFromUs() {
        whenASourceFarFromUsIsPublishedBy(Ipv4.of(27, 1, 1, 1));

        thenNoPublishAnswerWentTo(Ipv4.of(27, 1, 1, 1));
        thenNothingIsIndexed();
    }

    @Test
    void shouldStoreASourcePublishedToUs() throws Exception {
        whenASourceIsPublishedToUsBy(PEER);

        thenThePublishIsAnsweredForOurId(PEER);
    }

    @Test
    void shouldNotStoreASourceOfNoType() {
        whenASourceOfNoTypeIsPublishedToUsBy(Ipv4.of(28, 1, 1, 1));

        thenNoPublishAnswerWentTo(Ipv4.of(28, 1, 1, 1), "a source of no type");
    }

    @Test
    void shouldFindASourcePublishedToUsAtTheAddressItCameFrom() throws Exception {
        givenASourcePublishedToUsBy(PEER);
        givenASourceOfNoTypePublishedToUsBy(Ipv4.of(28, 1, 1, 1));

        whenANodeSearchesSourcesOfOurId(Ipv4.of(23, 1, 1, 1));

        thenTheSearchAnswerListsThePublishedSourceAt(Ipv4.of(23, 1, 1, 1), PEER);
    }

    @Test
    void shouldAnswerAFirewallCheckAndConnectToTheAsker() throws Exception {
        whenAFirewallCheckComesFrom(PEER, 5000);

        thenOurFirewallAnswerToSaysItsAddress(PEER);
        thenTheHostIsAskedToConnectTo(PEER, 5000);
    }

    @Test
    void shouldNotAnswerAFirewallCheckFromALanAddress() {
        givenAFirewallCheckCameFrom(PEER, 5000);

        whenAFirewallCheckComesFrom(Ipv4.of(192, 168, 1, 9), 5000);

        thenNoFirewallAnswerWentTo(Ipv4.of(192, 168, 1, 9));
        thenTheHostIsAskedForChecks(1);
    }

    @Test
    void shouldNotAnswerAFirewallCheckWithoutAPort() {
        givenAFirewallCheckCameFrom(PEER, 5000);

        whenAFirewallCheckComesFrom(Ipv4.of(29, 1, 1, 1), 0);

        thenNoFirewallAnswerWentTo(Ipv4.of(29, 1, 1, 1));
        thenTheHostIsAskedForChecks(1);
    }

    @Test
    void shouldIgnoreOurAddressFromANodeNeverAsked() {
        givenTheHelpersSaidHello();

        whenANodeReportsOurAddress(Ipv4.of(25, 1, 1, 1), Ipv4.of(80, 1, 1, 1));

        thenOurAddressIs(0);
    }

    @Test
    void shouldTakeTheFirstAddressReported() {
        givenTheHelpersSaidHello();

        whenANodeReportsOurAddress(Ipv4.of(21, 1, 1, 1), Ipv4.of(80, 1, 1, 1));

        thenOurAddressIs(Ipv4.of(80, 1, 1, 1));
    }

    @Test
    void shouldKeepOurAddressWhenOnlyOneNodeReportsAnother() {
        givenTheHelpersSaidHello();
        givenANodeReportedOurAddress(Ipv4.of(21, 1, 1, 1), Ipv4.of(80, 1, 1, 1));

        whenANodeReportsOurAddress(Ipv4.of(22, 1, 1, 1), Ipv4.of(81, 1, 1, 1));

        thenOurAddressIs(Ipv4.of(80, 1, 1, 1), "one report is not enough");
    }

    @Test
    void shouldChangeOurAddressWhenTwoNodesAgreeOnTheNewOne() {
        givenTheHelpersSaidHello();
        givenANodeReportedOurAddress(Ipv4.of(21, 1, 1, 1), Ipv4.of(80, 1, 1, 1));
        givenANodeReportedOurAddress(Ipv4.of(22, 1, 1, 1), Ipv4.of(81, 1, 1, 1));

        whenANodeReportsOurAddress(Ipv4.of(23, 1, 1, 1), Ipv4.of(81, 1, 1, 1));

        thenOurAddressIs(Ipv4.of(81, 1, 1, 1));
    }

    @Test
    void shouldKeepTheNewAddressWhenAnotherNodeAgrees() {
        givenTheHelpersSaidHello();
        givenANodeReportedOurAddress(Ipv4.of(21, 1, 1, 1), Ipv4.of(80, 1, 1, 1));
        givenANodeReportedOurAddress(Ipv4.of(22, 1, 1, 1), Ipv4.of(81, 1, 1, 1));
        givenANodeReportedOurAddress(Ipv4.of(23, 1, 1, 1), Ipv4.of(81, 1, 1, 1));

        whenANodeReportsOurAddress(Ipv4.of(24, 1, 1, 1), Ipv4.of(81, 1, 1, 1));

        thenOurAddressIs(Ipv4.of(81, 1, 1, 1));
    }

    // Four reports: no further node is asked until the next check.
    @Test
    void shouldAskNoFurtherNodeToCheckOurPortAfterFourReports() {
        givenTheHelpersSaidHello();
        givenANodeReportedOurAddress(Ipv4.of(21, 1, 1, 1), Ipv4.of(80, 1, 1, 1));
        givenANodeReportedOurAddress(Ipv4.of(22, 1, 1, 1), Ipv4.of(81, 1, 1, 1));
        givenANodeReportedOurAddress(Ipv4.of(23, 1, 1, 1), Ipv4.of(81, 1, 1, 1));
        givenANodeReportedOurAddress(Ipv4.of(24, 1, 1, 1), Ipv4.of(81, 1, 1, 1));

        whenANodeSaysHelloWithVersion(Ipv4.of(26, 1, 1, 1), 8);

        thenTheNodeIsNotAskedToCheckOurPort(Ipv4.of(26, 1, 1, 1));
        thenWeAreFirewalled("no node connected to us");
    }

    @Test
    void shouldStayFirewalledWhenACheckEndsWithoutTwoConnections() {
        givenTheHelpersReportedOurAddress(Ipv4.of(80, 1, 1, 1));
        givenConnectionsConfirmedFrom(Ipv4.of(21, 1, 1, 1), Ipv4.of(99, 1, 1, 1));

        whenTheFirewallIsRechecked();

        thenWeAreFirewalled();
        thenAConnectionIsAwaitedFrom(Ipv4.of(22, 1, 1, 1), "still awaited");
    }

    @Test
    void shouldForgetANodeAwaitedTooLong() {
        givenTheHelpersReportedOurAddress(Ipv4.of(80, 1, 1, 1));
        givenConnectionsConfirmedFrom(Ipv4.of(21, 1, 1, 1), Ipv4.of(99, 1, 1, 1));
        givenTheFirewallWasRecheckedAndAConnectionIsAwaitedFrom(Ipv4.of(22, 1, 1, 1));

        whenMinutesPass(13);

        thenNoConnectionIsAwaitedFrom(Ipv4.of(22, 1, 1, 1), "forgotten after three minutes");
    }

    @Test
    void shouldAnswerAPingWithThePortItCameFrom() throws Exception {
        whenANodePingsFromPort(PEER, 5555);

        thenOurPongToSaysThePort(PEER, 5555);
    }

    @Test
    void shouldAnswerOnlyAFewPingsOfAFlood() {
        givenANodePingedFromPort(PEER, 5555);
        givenAPongNobodyWaitsFor(PEER);

        whenANodePings(PEER, 20);

        thenFewerPongsWentTo(PEER, 5);
    }

    @Test
    void shouldBanAFloodingNodeForEverything() {
        givenANodeFloodedUsWithPings(PEER);

        whenANodeAsksForABootstrap(PEER);

        thenNoBootstrapAnswerWentTo(PEER, "banned for everything");
    }

    @Test
    void shouldEndTheBanOfAFloodingNodeAfterTwoHours() {
        givenANodeFloodedUsWithPings(PEER);
        givenTheBannedNodeGotNoBootstrapAnswer(PEER);

        whenTheNodePingsAgainAfterMinutes(PEER, 121);

        thenOneMorePongWentTo(PEER, "the ban ends");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("droppedPackets")
    void shouldDropUnknownBuddyAndMalformedPackets(String kind, Packet packet) {
        whenANodeSends(PEER, packet);

        thenNothingIsSent();
        thenTheContactsAre(0);
    }

    @Test
    void shouldNotStartASourceSearchAlreadyRunning() {
        givenAFile();
        givenAContactAtTheFile();
        givenASourceSearchStarted(1000);

        whenTheSourceSearchStarts(1000);

        thenItDidNotStart("already running");
    }

    @Test
    void shouldAskTheContactForSourcesOfAFileOfThatSize() throws Exception {
        givenAFile();
        givenAContactAtTheFile();
        givenASourceSearchStarted(1000);

        whenTheLookupReachesTheContact();

        thenTheSourceSearchAsksForTheSize(1000);
    }

    @Test
    void shouldKeepOnlySourcesThatCanBeReachedDirectly() {
        givenAFile();
        givenAContactAtTheFile();
        givenASourceSearchStarted(1000);
        givenTheLookupReachedTheContact();

        whenTheContactAnswersWithSourcesOfEveryKind();

        thenOnlyTheSourcesThatCanBeReachedDirectlyAreFound();
    }

    @Test
    void shouldSendTheExpressionAlongAKeywordSearch() throws Exception {
        givenAContactAtTheKeyword("ubuntu");
        givenAKeywordSearchWithAnExpressionStarted("ubuntu desktop");

        whenTheLookupReachesTheContact();

        thenTheExpressionGoesAlong();
    }

    @Test
    void shouldKeepOnlyFilesThatMatchAllTheWordsOfAKeywordSearch() {
        givenAContactAtTheKeyword("ubuntu");
        givenAKeywordSearchWithAnExpressionStarted("ubuntu desktop");
        givenTheLookupReachedTheContact();

        whenTheContactAnswersWithFilesOfEveryKind();

        thenOnlyTheFileThatMatchesAllTheWordsIsFound();
    }

    @Test
    void shouldDropResultsForASearchNobodyRuns() {
        givenAContactAtTheKeyword("ubuntu");
        givenAKeywordSearchWithAnExpressionStarted("ubuntu desktop");
        givenTheLookupReachedTheContact();
        givenTheContactAnsweredWithFilesOfEveryKind();

        whenTheSameFilesComeForTheKeyword("debian");

        thenTheFilesFoundAre(1);
    }

    @Test
    void shouldNotSearchWithoutAWordLongEnough() {
        whenAKeywordSearchStarts("a b");

        thenTheSearchDidNotStart("no word long enough");
        thenNoLookupRuns();
    }

    @Test
    void shouldNotSearchAKeywordWithNobodyToAsk() {
        whenAKeywordSearchStarts("ubuntu");

        thenTheSearchDidNotStart("nobody to ask");
        thenNoLookupRuns();
    }

    @Test
    void shouldNotSearchSourcesWithNobodyToAsk() {
        givenAFile();

        whenTheSourceSearchStarts(10);

        thenItDidNotStart();
        thenNoLookupRuns();
    }

    @Test
    void shouldNotPublishASourceWithNobodyToAsk() {
        givenAFile();

        whenWeArePublishedAsASource(10);

        thenItDidNotStart();
        thenNoLookupRuns();
    }

    @Test
    void shouldNotPublishAKeywordWithNobodyToAsk() {
        whenTheKeywordIsPublishedWithAFile("ubuntu", "ubuntu.iso", 1);

        thenItDidNotStart();
        thenNoLookupRuns();
    }

    @Test
    void shouldPublishNoKeywordWithoutFiles() {
        givenAContactAtTheKeyword("ubuntu");

        whenTheKeywordIsPublishedWithoutFiles("ubuntu");

        thenItDidNotStart("nothing to publish");
    }

    @Test
    void shouldStartOneKeywordPublish() {
        givenAContactAtTheKeyword("ubuntu");
        givenFilesToPublish(KadMessages.MAX_PUBLISHED_FILES + 10);

        whenTheKeywordIsPublishedWithTheFiles("ubuntu");

        thenItStarted();
        thenTheKeywordPublishesRunningAre(1);
        thenTheSourcePublishesRunningAre(0);
    }

    @Test
    void shouldNotStartAKeywordPublishAlreadyRunning() {
        givenAContactAtTheKeyword("ubuntu");
        givenFilesToPublish(KadMessages.MAX_PUBLISHED_FILES + 10);
        givenTheKeywordPublishStarted("ubuntu");

        whenTheKeywordIsPublishedWithTheFiles("ubuntu");

        thenItDidNotStart("already running");
        thenTheKeywordPublishesRunningAre(1);
        thenTheSourcePublishesRunningAre(0);
    }

    @Test
    void shouldPublishTheFilesOfAKeywordInBatches() throws Exception {
        givenAContactAtTheKeyword("ubuntu");
        givenFilesToPublish(KadMessages.MAX_PUBLISHED_FILES + 10);
        givenTheKeywordPublishStarted("ubuntu");

        whenTheLookupReachesTheContact();

        thenKeywordPublishesWentToTheContact(2);
        thenTheLastKeywordPublishCarriesFiles(10);
    }

    @Test
    void shouldCountTheAnswersToAKeywordPublish() {
        givenAContactAtTheKeyword("ubuntu");
        givenFilesToPublish(KadMessages.MAX_PUBLISHED_FILES + 10);
        givenTheKeywordPublishStarted("ubuntu");
        givenTheLookupReachedTheContact();

        whenANodeAnswersThePublish(PEER);

        thenTheOnlyLookupIs(KadSearchInfo.Kind.STORE_KEYWORD, 1);
    }

    @Test
    void shouldNotCountAnAnswerToNoRequest() {
        givenAContactAtTheKeyword("ubuntu");
        givenFilesToPublish(KadMessages.MAX_PUBLISHED_FILES + 10);
        givenTheKeywordPublishStarted("ubuntu");
        givenTheLookupReachedTheContact();
        givenANodeAnsweredThePublish(PEER);

        whenANodeAnswersThePublish(Ipv4.of(70, 1, 1, 1));

        thenTheFirstLookupHasAnswers(1);
    }

    @Test
    void shouldStartOneSourcePublish() {
        givenAFile();
        givenAContactAtTheFile();
        givenTheNodeIsReachableThroughTcp();

        whenWeArePublishedAsASource(5_000_000_000L);

        thenItStarted();
        thenTheSourcePublishesRunningAre(1);
        thenTheOnlyLookupIs(KadSearchInfo.Kind.STORE_SOURCE);
    }

    @Test
    void shouldNotStartASourcePublishAlreadyRunning() {
        givenAFile();
        givenAContactAtTheFile();
        givenTheNodeIsReachableThroughTcp();
        givenASourcePublishStarted(5_000_000_000L);

        whenWeArePublishedAsASource(5_000_000_000L);

        thenItDidNotStart("already running");
        thenTheSourcePublishesRunningAre(1);
        thenTheOnlyLookupIs(KadSearchInfo.Kind.STORE_SOURCE);
    }

    @Test
    void shouldPublishUsAsASourceWithOurPortAndTheKindOfSize() throws Exception {
        givenAFile();
        givenAContactAtTheFile();
        givenTheNodeIsReachableThroughTcp();
        givenASourcePublishStarted(5_000_000_000L);

        whenTheLookupReachesTheContact();

        thenOurSourcePublishNamesTheFileAndUs();
        thenOurSourcePublishCarries(4, 4662, 5_000_000_000L, 3);
    }

    @Test
    void shouldShowWhatTheLookupsLookFor() {
        givenAFile();
        givenAnAliveContactAt(PEER);
        givenASourceSearchStarted(10);
        givenAKeywordSearchStarted("ubuntu");

        whenTheNodeLooksForContacts();

        thenTheLookupsInclude(KadSearchInfo.Kind.SOURCES, KadSearchInfo.Kind.KEYWORD, KadSearchInfo.Kind.NODE);
        thenEveryRunningLookupIsShown();
    }

    @Test
    void shouldLookUpOurOwnIdBeforeBeingReady() throws Exception {
        givenAnAliveContactAt(PEER);
        givenTheNodeIsNotReady();

        whenMinutesPass(3);

        thenOurOwnIdIsLookedUp();
        thenTheNodeIsNotReady();
    }

    @Test
    void shouldBeReadyOnceTheFirstLookupOfOurOwnIdIsDone() throws Exception {
        givenAnAliveContactAt(PEER);
        givenTheNodeIsNotReady();
        givenOurOwnIdIsLookedUpAfterMinutes(3);

        whenMinutesPass(1);

        thenTheNodeIsReady();
    }

    @Test
    void shouldLookForContactsAgainWhenQuiet() throws Exception {
        givenAnAliveContactAt(PEER);
        givenTheFirstLookupsWereSent();

        whenMinutesPass(16);

        thenAZoneIsLookedUpAgain();
        thenTheNodeIsConnected("heard from within twenty minutes");
    }

    @Test
    void shouldNoLongerBeConnectedWithoutNewsForTwentyMinutes() throws Exception {
        givenAnAliveContactAt(PEER);
        givenTheFirstLookupsWereSent();
        givenAQuietNodeLookedForContactsAgainAfterMinutes(16);

        whenMinutesPassWithoutATick(5);

        thenTheNodeIsNotConnected();
    }

    @Test
    void shouldLoadNoContactsFromAnUnreadableNodesDat() throws Exception {
        givenAnUnreadableNodesDat();

        whenTheContactsAreLoaded();

        thenTheContactsAre(0);
    }

    @Test
    void shouldSaveNoNodesDatWithoutContacts() throws Exception {
        givenAnUnreadableNodesDat();
        givenTheContactsWereLoaded();
        givenTheNodesDatIsDeleted();

        whenTheNodeCloses();

        thenThereIsNoNodesDat("no contacts, no file");
    }

    @Test
    void shouldKeepContactsThatCannotBeSavedInTheTable() throws Exception {
        givenContactsAt(PEER);
        givenTheTemporaryNodesFileCannotBeWritten();

        whenTheContactsAreSaved();

        thenThereIsNoNodesDat();
        thenTheContactsAre(1);
    }

    @Test
    void shouldDropALookupAnswerNobodyAskedFor() {
        whenAStrangerAnswersALookup(Ipv4.of(40, 1, 1, 1));

        thenTheContactsAre(0);
        thenNothingIsSent();
    }

    @Test
    void shouldDropASearchAnswerNobodyAskedFor() {
        whenAStrangerAnswersASearch(Ipv4.of(40, 1, 1, 1));

        thenTheContactsAre(0);
        thenNothingIsSent();
    }

    @Test
    void shouldLeaveOutContactsAnAnswerCannotVouchFor() {
        givenAContactAtTheKeyword("ubuntu");
        givenTheKeywordPublishWithAFileStarted("ubuntu", "ubuntu.iso", 10);

        whenTheContactAnswersWithContactsOfEveryKind();

        thenTheContactAddressesAre(Ipv4.format(PEER) + ":" + PORT, "43.1.1.1:" + PORT);
    }

    @Test
    void shouldAnswerANodeOnThisMachineButNotTakeIt() throws Exception {
        whenANodeSaysHelloWithVersion(Ipv4.of(127, 0, 0, 1), 8);

        thenOurHelloAnswerWentTo(Ipv4.of(127, 0, 0, 1));
        thenTheContactsAre(0);
        thenTheNodeIsNotAskedToCheckOurPort(Ipv4.of(127, 0, 0, 1), "it could not check our port");
    }

    @Test
    void shouldNotLookUpOurOwnIdTwiceAtOnce() throws Exception {
        givenAnAliveContactAt(PEER);
        givenASourceSearchForOurOwnIdStarted();

        whenTheNodeLooksForContacts();

        thenOurOwnIdIsLookedUpOnce();
        thenTheNodeIsNotReady();
    }

    static Stream<Arguments> droppedPackets() {
        return Stream.of(
                Arguments.of("unknown opcode", Packet.of(Protocol.KAD, 0xEE, new byte[0])),
                Arguments.of(
                        "buddy request without buddies",
                        Packet.of(Protocol.KAD, Opcodes.Kad.FINDBUDDY_REQ.code(), new byte[20])),
                Arguments.of("malformed hello", Packet.of(Protocol.KAD, Opcodes.Kad.HELLO_REQ.code(), new byte[3])),
                Arguments.of(
                        "malformed firewall answer",
                        Packet.of(Protocol.KAD, Opcodes.Kad.FIREWALLED_RES.code(), new byte[3])));
    }

    // Steps.

    private void givenContactsAt(int... ips) {
        for (int ip : ips) {
            this.node.addContacts(contents(record(ip)));
        }
    }

    private void givenAKnownContactAt(int ip) {
        this.contactId = UInt128.random(this.random);
        this.node.addContacts(
                contents(new NodesDat.Contact(this.contactId, ip, PORT, 4662, 8, 0, NodesDat.UdpKey.NONE, true)));
    }

    // A contact that greeted us: alive for an hour, so lookups take it.
    private void givenAnAliveContactAt(int ip) {
        UInt128 id = UInt128.random(this.random);
        this.node.addContacts(contents(new NodesDat.Contact(id, ip, PORT, 4662, 8, 0, NodesDat.UdpKey.NONE, true)));
        receive(ip, new KadMessages.Hello(new KadMessages.Self(id, 4662, 8), List.of()).encode(false));
    }

    private void givenAFile() {
        this.fileHash = hash();
        this.target = UInt128.of(this.fileHash);
    }

    private void givenAContactAtTheFile() {
        contactAt(this.target);
    }

    private void givenAContactAtTheKeyword(String word) {
        this.target = Keywords.id(word);
        contactAt(this.target);
    }

    private void givenABootstrapRequestWentTo(int helper) {
        this.node.bootstrap(helper, PORT);
        assertThat(last(helper, Opcodes.Kad.BOOTSTRAP_REQ)).isNotNull();
    }

    private void givenANodeSaidHello(int ip, int senderKey) {
        whenANodeSaysHello(ip, senderKey);
    }

    // An acknowledgement without our key proves nothing.
    private void givenTheNodeAcknowledgedWithoutOurKey(int ip) {
        receive(ip, KadMessages.helloAck(this.peerId), 0, 0);
        thenTheVerifiedContactsAre(0);
    }

    private void givenANodeSaidHelloWithVersion(int ip, int version) {
        whenANodeSaysHelloWithVersion(ip, version);
    }

    private void givenTheContactWasAskedWhetherItIsStillThere(int ip) {
        whenMinutesPass(1);
        thenTheContactIsAskedWhetherItIsStillThere(ip);
    }

    // A new contact is checked within a minute: unanswered, it counts as dead until it is removed.
    private void givenTheContactLeftItsCheckUnanswered(int ip) {
        givenTheContactWasAskedWhetherItIsStillThere(ip);
        assertThat(this.node.contactInfos())
                .singleElement()
                .satisfies(c -> assertThat(c.type()).isEqualTo(KadContact.DEAD));
    }

    private void givenAMinutePassedWhileTheContactWasWaitedFor() {
        whenMinutesPass(1);
        thenTheContactsAre(1, "still waited for");
    }

    private void givenItsHelloAnswerCameUnasked(int ip, int senderKey) {
        whenTheContactSendsItsHelloAnswer(ip, senderKey);
        thenNoAcknowledgementWentTo(ip);
    }

    private void givenTheContactAnsweredItsCheck(int ip, int senderKey) throws Exception {
        whenTheContactSendsItsHelloAnswer(ip, senderKey);
        thenOurAcknowledgementWentTo(ip, senderKey);
    }

    private void givenAFilePublishedToUsUnderOurId(String name, long size) {
        whenAFileIsPublishedToUsUnderOurId(name, size);
    }

    private void givenASourcePublishedToUsBy(int ip) {
        whenASourceIsPublishedToUsBy(ip);
    }

    private void givenASourceOfNoTypePublishedToUsBy(int ip) {
        whenASourceOfNoTypeIsPublishedToUsBy(ip);
    }

    private void givenAFirewallCheckCameFrom(int ip, int port) {
        whenAFirewallCheckComesFrom(ip, port);
    }

    private void givenTheHelpersSaidHello() {
        for (int helper : HELPERS) {
            hello(helper, 8);
        }
    }

    private void givenANodeReportedOurAddress(int ip, int address) {
        whenANodeReportsOurAddress(ip, address);
    }

    private void givenTheHelpersReportedOurAddress(int address) {
        for (int helper : HELPERS) {
            hello(helper, 8);
            receive(helper, KadMessages.firewallAnswer(address));
        }
    }

    private void givenConnectionsConfirmedFrom(int... ips) {
        for (int ip : ips) {
            this.node.reachableConfirmed(ip);
        }
    }

    private void givenTheFirewallWasRecheckedAndAConnectionIsAwaitedFrom(int ip) {
        whenTheFirewallIsRechecked();
        thenWeAreFirewalled();
        thenAConnectionIsAwaitedFrom(ip, "still awaited");
    }

    private void givenANodePingedFromPort(int ip, int port) {
        whenANodePingsFromPort(ip, port);
    }

    // A pong nobody waits for changes nothing.
    private void givenAPongNobodyWaitsFor(int ip) {
        receive(ip, KadMessages.pong(PORT));
    }

    private void givenANodeFloodedUsWithPings(int ip) {
        givenANodePingedFromPort(ip, 5555);
        givenAPongNobodyWaitsFor(ip);
        whenANodePings(ip, 20);
        thenFewerPongsWentTo(ip, 5);
        this.pongs = count(ip, Opcodes.Kad.PONG);
    }

    private void givenTheBannedNodeGotNoBootstrapAnswer(int ip) {
        whenANodeAsksForABootstrap(ip);
        thenNoBootstrapAnswerWentTo(ip, "banned for everything");
    }

    private void givenASourceSearchStarted(long size) {
        whenTheSourceSearchStarts(size);
        thenItStarted();
    }

    private void givenASourceSearchForOurOwnIdStarted() {
        this.fileHash = this.node.id().toHash16();
        givenASourceSearchStarted(10);
    }

    private void givenTheLookupReachedTheContact() {
        whenTheLookupReachesTheContact();
    }

    private void givenAKeywordSearchStarted(String text) {
        whenAKeywordSearchStarts(text);
        assertThat(this.search).isPresent();
    }

    private void givenAKeywordSearchWithAnExpressionStarted(String text) {
        this.expression = new SearchExpr.Keyword(text);
        this.search = this.node.searchKeyword(text, Optional.of(this.expression), this.fileResults::addAll);
        assertThat(this.search).isPresent();
    }

    private void givenTheContactAnsweredWithFilesOfEveryKind() {
        whenTheContactAnswersWithFilesOfEveryKind();
    }

    private void givenFilesToPublish(int count) {
        this.toPublish = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            this.toPublish.add(entry(UInt128.random(this.random), "ubuntu " + i + ".iso", 1000 + i));
        }
    }

    private void givenTheKeywordPublishStarted(String word) {
        whenTheKeywordIsPublishedWithTheFiles(word);
        thenItStarted();
    }

    private void givenTheKeywordPublishWithAFileStarted(String word, String name, long size) {
        whenTheKeywordIsPublishedWithAFile(word, name, size);
        thenItStarted();
    }

    private void givenANodeAnsweredThePublish(int ip) {
        whenANodeAnswersThePublish(ip);
    }

    // Two nodes connected to our TCP port and said so: the node is open.
    private void givenTheNodeIsReachableThroughTcp() {
        for (int ip : new int[] {Ipv4.of(26, 1, 1, 1), Ipv4.of(27, 1, 1, 1)}) {
            hello(ip, 8);
            this.node.reachableConfirmed(ip);
        }
    }

    private void givenASourcePublishStarted(long size) {
        whenWeArePublishedAsASource(size);
        thenItStarted();
    }

    private void givenTheNodeIsNotReady() {
        thenTheNodeIsNotReady();
    }

    private void givenOurOwnIdIsLookedUpAfterMinutes(int minutes) throws Exception {
        whenMinutesPass(minutes);
        thenOurOwnIdIsLookedUp();
        thenTheNodeIsNotReady();
    }

    private void givenTheFirstLookupsWereSent() {
        this.time.addAndGet(11 * SECOND);
        this.node.tick();
        this.time.addAndGet(10 * SECOND);
        this.node.tick();
        this.sent.clear();
    }

    private void givenAQuietNodeLookedForContactsAgainAfterMinutes(int minutes) throws Exception {
        whenMinutesPass(minutes);
        thenAZoneIsLookedUpAgain();
        thenTheNodeIsConnected("heard from within twenty minutes");
    }

    private void givenAnUnreadableNodesDat() throws IOException {
        Files.write(nodesFile(), new byte[] {1, 2, 3});
    }

    private void givenTheContactsWereLoaded() {
        whenTheContactsAreLoaded();
        thenTheContactsAre(0);
    }

    private void givenTheNodesDatIsDeleted() throws IOException {
        Files.delete(nodesFile());
    }

    private void givenTheTemporaryNodesFileCannotBeWritten() throws IOException {
        Path tmp = this.dir.resolve(KadNode.NODES_FILE + ".tmp");
        Files.createDirectories(tmp);
        Files.writeString(tmp.resolve("keep"), "x");
    }

    private void whenANodeAsksForABootstrap(int ip) {
        receive(ip, KadMessages.bootstrapRequest());
    }

    private void whenAHelperAnswersABootstrap(int helper) {
        receive(
                helper,
                new KadMessages.BootstrapAnswer(
                                new KadMessages.Self(UInt128.random(this.random), 4662, 8),
                                List.of(
                                        contact(Ipv4.of(30, 1, 1, 1)),
                                        contact(Ipv4.of(192, 168, 1, 9)),
                                        new KadMessages.Contact(
                                                UInt128.random(this.random), Ipv4.of(31, 1, 1, 1), PORT, 0, 1)))
                        .encode());
    }

    private void whenANodeSaysHello(int ip, int senderKey) {
        this.peerId = UInt128.random(this.random);
        receive(
                ip,
                new KadMessages.Hello(new KadMessages.Self(this.peerId, 4662, 8), List.of()).encode(false),
                0,
                senderKey);
    }

    private void whenTheNodeAcknowledgesWithOurKey(int ip) {
        receive(ip, KadMessages.helloAck(this.peerId), this.identity.verifyKey(ip), 0);
    }

    private void whenANodeSaysHelloWithVersion(int ip, int version) {
        hello(ip, version);
    }

    private void whenANodeBehindAFirewallSaysHello(int ip) {
        Tag udpFirewalled = new Tag.IntTag(TagName.id(FileTag.KADMISCOPTIONS), 0x01);
        receive(
                ip,
                new KadMessages.Hello(
                                new KadMessages.Self(UInt128.random(this.random), 4662, 8), List.of(udpFirewalled))
                        .encode(false));
    }

    private void whenMinutesPass(int minutes) {
        this.time.addAndGet(minutes * MINUTE);
        this.node.tick();
    }

    private void whenMinutesPassOneByOne(int minutes) {
        for (int i = 0; i < minutes; i++) {
            whenMinutesPass(1);
        }
    }

    private void whenMinutesPassWithoutATick(int minutes) {
        this.time.addAndGet(minutes * MINUTE);
    }

    // A hello answer that asks for an acknowledgement.
    private void whenTheContactSendsItsHelloAnswer(int ip, int senderKey) {
        Packet answer = new KadMessages.Hello(
                        new KadMessages.Self(this.contactId, 4662, 8),
                        List.of(new Tag.IntTag(TagName.id(FileTag.KADMISCOPTIONS), 0x04)))
                .encode(true);
        receive(ip, answer, 0, senderKey);
    }

    private void whenALookupMeantForUsComes() {
        this.target = UInt128.random(this.random);
        receive(PEER, new KadMessages.Request(2, this.target, this.node.id()).encode());
    }

    private void whenALookupMeantForAnotherNodeComes() {
        this.target = UInt128.random(this.random);
        receive(PEER, new KadMessages.Request(2, this.target, UInt128.random(this.random)).encode());
    }

    private void whenAFileIsPublishedToUsUnderOurId(String name, long size) {
        this.publishedFile = entry(UInt128.random(this.random), name, size);
        receive(PEER, new KadMessages.KeywordPublish(this.node.id(), List.of(this.publishedFile)).encode());
    }

    private void whenANodeSearchesOurId(int ip) {
        receive(ip, new KadMessages.KeywordSearch(this.node.id(), 0, new byte[0]).encode());
    }

    private void whenANodeSearchesOurIdFor(int ip, String word) {
        receive(ip, new KadMessages.KeywordSearch(this.node.id(), 0, tree(new SearchExpr.Keyword(word))).encode());
    }

    private void whenAKeywordFarFromUsIsPublishedBy(int ip) {
        UInt128 far = farFrom(this.node.id());
        receive(ip, new KadMessages.KeywordPublish(far, List.of(entry(far, "far away.iso", 10))).encode());
    }

    private void whenASourceFarFromUsIsPublishedBy(int ip) {
        UInt128 far = farFrom(this.node.id());
        receive(ip, new KadMessages.SourcePublish(far, UInt128.random(this.random), sourceTags(1)).encode());
    }

    private void whenASourceIsPublishedToUsBy(int ip) {
        this.publishedSource = UInt128.random(this.random);
        receive(ip, new KadMessages.SourcePublish(this.node.id(), this.publishedSource, sourceTags(1)).encode());
    }

    private void whenASourceOfNoTypeIsPublishedToUsBy(int ip) {
        receive(ip, new KadMessages.SourcePublish(this.node.id(), UInt128.random(this.random), List.of()).encode());
    }

    private void whenANodeSearchesSourcesOfOurId(int ip) {
        receive(ip, new KadMessages.SourceSearch(this.node.id(), 0, 0).encode());
    }

    private void whenAFirewallCheckComesFrom(int ip, int port) {
        receive(ip, new KadMessages.FirewallCheck(port, this.asker, 1).encode());
    }

    private void whenANodeReportsOurAddress(int ip, int address) {
        receive(ip, KadMessages.firewallAnswer(address));
    }

    private void whenTheFirewallIsRechecked() {
        this.node.recheckFirewall();
        this.node.tick();
    }

    private void whenANodePingsFromPort(int ip, int port) {
        this.node.received(ip, port, KadMessages.ping(), false, 0, 0);
    }

    private void whenANodePings(int ip, int times) {
        for (int i = 0; i < times; i++) {
            receive(ip, KadMessages.ping());
        }
    }

    private void whenTheNodePingsAgainAfterMinutes(int ip, int minutes) {
        this.time.addAndGet(minutes * MINUTE);
        receive(ip, KadMessages.ping());
    }

    private void whenANodeSends(int ip, Packet packet) {
        receive(ip, packet);
    }

    private void whenTheSourceSearchStarts(long size) {
        this.started = this.node.findSources(this.fileHash, size, this.sources::addAll);
    }

    // Lets the lookup towards the target ask the contact at that very id, which then gets the request.
    private void whenTheLookupReachesTheContact() {
        receive(PEER, new KadMessages.Answer(this.target, List.of()).encode());
        this.time.addAndGet(4 * SECOND);
        this.node.tick();
    }

    private void whenTheContactAnswersWithSourcesOfEveryKind() {
        this.direct = UInt128.random(this.random);
        this.large = UInt128.random(this.random);
        List<KadMessages.Entry> entries = List.of(
                new KadMessages.Entry(this.direct, source(1, Ipv4.of(90, 1, 1, 1), 4662, 0)),
                new KadMessages.Entry(this.large, source(4, Ipv4.of(91, 1, 1, 1), 4663, 3)),
                new KadMessages.Entry(UInt128.random(this.random), source(3, Ipv4.of(92, 1, 1, 1), 4662, 0)),
                new KadMessages.Entry(UInt128.random(this.random), source(1, 0, 4662, 0)),
                new KadMessages.Entry(UInt128.random(this.random), source(1, Ipv4.of(93, 1, 1, 1), 0, 0)));
        receive(PEER, new KadMessages.SearchAnswer(this.target, this.target, entries).encode());
    }

    private void whenTheContactAnswersWithFilesOfEveryKind() {
        this.good = UInt128.random(this.random);
        this.answered = List.of(
                new KadMessages.Entry(
                        this.good,
                        List.of(
                                new Tag.StringTag(TagName.id(FileTag.FILENAME), "Ubuntu Desktop 24.iso"),
                                new Tag.IntTag(TagName.id(FileTag.FILESIZE), 5000),
                                new Tag.IntTag(TagName.id(FileTag.SOURCES), 7),
                                new Tag.StringTag(TagName.id(FileTag.FILETYPE), "Iso"),
                                new Tag.StringTag(TagName.id(FileTag.MEDIA_TITLE), "Noble Numbat"),
                                new Tag.IntTag(TagName.id(FileTag.MEDIA_LENGTH), 95))),
                entry(UInt128.random(this.random), "ubuntu server.iso", 5000),
                entry(UInt128.random(this.random), "ubuntu desktop.iso", 0),
                new KadMessages.Entry(
                        UInt128.random(this.random), List.of(new Tag.IntTag(TagName.id(FileTag.FILESIZE), 5))));
        receive(PEER, new KadMessages.SearchAnswer(this.target, this.target, this.answered).encode());
    }

    private void whenTheSameFilesComeForTheKeyword(String word) {
        UInt128 other = Keywords.id(word);
        receive(PEER, new KadMessages.SearchAnswer(other, other, this.answered).encode());
    }

    private void whenAKeywordSearchStarts(String text) {
        this.search = this.node.searchKeyword(text, Optional.empty(), this.fileResults::addAll);
    }

    private void whenWeArePublishedAsASource(long size) {
        this.started = this.node.publishSource(this.fileHash, size);
    }

    private void whenTheKeywordIsPublishedWithAFile(String word, String name, long size) {
        this.started = this.node.publishKeyword(word, List.of(entry(UInt128.random(this.random), name, size)));
    }

    private void whenTheKeywordIsPublishedWithoutFiles(String word) {
        this.started = this.node.publishKeyword(word, List.of());
    }

    private void whenTheKeywordIsPublishedWithTheFiles(String word) {
        this.started = this.node.publishKeyword(word, this.toPublish);
    }

    private void whenANodeAnswersThePublish(int ip) {
        receive(ip, new KadMessages.PublishAnswer(this.target, 3).encode());
    }

    private void whenTheNodeLooksForContacts() {
        this.node.lookForContacts();
        this.node.tick();
    }

    private void whenTheContactsAreLoaded() {
        this.node.loadNodes();
    }

    private void whenTheNodeCloses() {
        this.node.close();
    }

    private void whenTheContactsAreSaved() {
        this.node.saveNodes();
    }

    private void whenAStrangerAnswersALookup(int ip) {
        UInt128 keyword = Keywords.id("ubuntu");
        receive(ip, new KadMessages.Answer(keyword, List.of(contact(Ipv4.of(41, 1, 1, 1)))).encode());
    }

    private void whenAStrangerAnswersASearch(int ip) {
        UInt128 keyword = Keywords.id("ubuntu");
        receive(ip, new KadMessages.SearchAnswer(keyword, keyword, List.of(entry(keyword, "ubuntu.iso", 10))).encode());
    }

    private void whenTheContactAnswersWithContactsOfEveryKind() {
        receive(
                PEER,
                new KadMessages.Answer(
                                this.target,
                                List.of(
                                        new KadMessages.Contact(
                                                UInt128.random(this.random), Ipv4.of(41, 1, 1, 1), PORT, 0, 1),
                                        contact(Ipv4.of(192, 168, 7, 7)),
                                        new KadMessages.Contact(
                                                UInt128.random(this.random), Ipv4.of(42, 1, 1, 1), 53, 0, 5),
                                        contact(Ipv4.of(43, 1, 1, 1))))
                        .encode());
    }

    private void thenOurBootstrapAnswerToListsTheContactsAt(int ip, Integer... addresses) throws Exception {
        KadMessages.BootstrapAnswer ours = KadMessages.BootstrapAnswer.decode(last(ip, Opcodes.Kad.BOOTSTRAP_RES));
        assertThat(ours.sender().id()).isEqualTo(this.node.id());
        assertThat(ours.sender().tcpPort()).isEqualTo(4662);
        assertThat(ours.contacts()).extracting(KadMessages.Contact::ip).containsExactlyInAnyOrder(addresses);
    }

    private void thenTheContactsAre(int count) {
        assertThat(this.node.contacts()).isEqualTo(count);
    }

    private void thenTheContactsAre(int count, String because) {
        assertThat(this.node.contacts()).as(because).isEqualTo(count);
    }

    private void thenTheVerifiedContactsAre(int count) {
        assertThat(this.node.verifiedContacts()).isEqualTo(count);
    }

    private void thenTheVerifiedContactsAre(int count, String because) {
        assertThat(this.node.verifiedContacts()).as(because).isEqualTo(count);
    }

    private void thenOurHelloAnswerIsObfuscatedWithItsIdAndKey(int ip, int key) {
        Sent reply = sentWith(ip, Opcodes.Kad.HELLO_RES);
        assertThat(reply.receiverId()).as("obfuscated with its id").contains(this.peerId);
        assertThat(reply.receiverKey()).as("and the key it sent").isEqualTo(key);
    }

    private void thenOurHelloAnswerAsksForAnAcknowledgement(int ip) throws Exception {
        KadMessages.Hello hello =
                KadMessages.Hello.decode(sentWith(ip, Opcodes.Kad.HELLO_RES).packet());
        assertThat(hello.sender().id()).isEqualTo(this.node.id());
        assertThat(Tags.intValue(hello.tags(), FileTag.KADMISCOPTIONS))
                .as("TCP-firewalled, UDP not known to be firewalled yet, and asking for an acknowledgement")
                .hasValue(0x02 | 0x04);
    }

    private void thenTheNodeIsAskedToCheckOurPort(int ip, String because) {
        assertThat(last(ip, Opcodes.Kad.FIREWALLED2_REQ)).as(because).isNotNull();
    }

    private void thenTheNodeIsNotAskedToCheckOurPort(int ip) {
        assertThat(last(ip, Opcodes.Kad.FIREWALLED2_REQ)).isNull();
    }

    private void thenTheNodeIsNotAskedToCheckOurPort(int ip, String because) {
        assertThat(last(ip, Opcodes.Kad.FIREWALLED2_REQ)).as(because).isNull();
    }

    private void thenOurHelloAnswerIsPlain(int ip) throws Exception {
        Sent plain = sentWith(ip, Opcodes.Kad.HELLO_RES);
        assertThat(plain.receiverId()).as("too old for obfuscation").isEmpty();
        assertThat(KadMessages.Hello.decode(plain.packet()).tags()).isEmpty();
    }

    private void thenOurHelloAnswerWentTo(int ip) {
        assertThat(sentWith(ip, Opcodes.Kad.HELLO_RES)).isNotNull();
    }

    private void thenTheContactIsAskedWhetherItIsStillThere(int ip) {
        assertThat(last(ip, Opcodes.Kad.HELLO_REQ))
                .as("asked whether it is still there")
                .isNotNull();
    }

    private void thenNoAcknowledgementWentTo(int ip) {
        assertThat(last(ip, Opcodes.Kad.HELLO_RES_ACK)).as("not asked for").isNull();
    }

    private void thenOurAcknowledgementWentTo(int ip, int key) throws Exception {
        Sent ack = sentWith(ip, Opcodes.Kad.HELLO_RES_ACK);
        assertThat(KadMessages.decodeHelloAck(ack.packet())).isEqualTo(this.node.id());
        assertThat(ack.receiverKey()).isEqualTo(key);
    }

    private void thenTheLookupIsAnswered() throws Exception {
        assertThat(KadMessages.Answer.decode(last(PEER, Opcodes.Kad.RES)).target())
                .isEqualTo(this.target);
    }

    private void thenTheLookupIsNotAnswered() {
        assertThat(last(PEER, Opcodes.Kad.RES)).isNull();
    }

    private void thenThePublishIsAnsweredForOurId(int ip) throws Exception {
        KadMessages.PublishAnswer load = KadMessages.PublishAnswer.decode(last(ip, Opcodes.Kad.PUBLISH_RES));
        assertThat(load.target()).isEqualTo(this.node.id());
    }

    private void thenTheKeywordsIndexedAre(int count) {
        assertThat(this.node.indexed()[0]).isEqualTo(count);
    }

    private void thenTheSearchAnswerListsThePublishedFile(int ip) throws Exception {
        assertThat(KadMessages.SearchAnswer.decode(last(ip, Opcodes.Kad.SEARCH_RES))
                        .results())
                .extracting(KadMessages.Entry::id)
                .containsExactly(this.publishedFile.id());
    }

    private void thenASearchAnswerWentTo(int ip, String because) {
        assertThat(last(ip, Opcodes.Kad.SEARCH_RES)).as(because).isNotNull();
    }

    private void thenNoSearchAnswerWentTo(int ip, String because) {
        assertThat(last(ip, Opcodes.Kad.SEARCH_RES)).as(because).isNull();
    }

    private void thenNoPublishAnswerWentTo(int ip) {
        assertThat(last(ip, Opcodes.Kad.PUBLISH_RES)).isNull();
    }

    private void thenNoPublishAnswerWentTo(int ip, String because) {
        assertThat(last(ip, Opcodes.Kad.PUBLISH_RES)).as(because).isNull();
    }

    private void thenNothingIsIndexed() {
        assertThat(this.node.indexed()).containsExactly(0, 0);
    }

    private void thenTheSearchAnswerListsThePublishedSourceAt(int searcher, int ip) throws Exception {
        List<KadMessages.Entry> found = KadMessages.SearchAnswer.decode(last(searcher, Opcodes.Kad.SEARCH_RES))
                .results();
        assertThat(found).extracting(KadMessages.Entry::id).containsExactly(this.publishedSource);
        assertThat(Tags.intValue(found.getFirst().tags(), FileTag.SOURCEIP)).hasValue(Integer.toUnsignedLong(ip));
    }

    private void thenOurFirewallAnswerToSaysItsAddress(int ip) throws Exception {
        assertThat(KadMessages.decodeFirewallAnswer(last(ip, Opcodes.Kad.FIREWALLED_RES)))
                .isEqualTo(ip);
    }

    private void thenTheHostIsAskedToConnectTo(int ip, int port) {
        assertThat(this.checks).containsExactly(new Check(ip, port, this.asker.toHash16(), 1));
    }

    private void thenNoFirewallAnswerWentTo(int ip) {
        assertThat(last(ip, Opcodes.Kad.FIREWALLED_RES)).isNull();
    }

    private void thenTheHostIsAskedForChecks(int count) {
        assertThat(this.checks).hasSize(count);
    }

    private void thenOurAddressIs(int address) {
        assertThat(this.node.publicIp()).isEqualTo(address);
    }

    private void thenOurAddressIs(int address, String because) {
        assertThat(this.node.publicIp()).as(because).isEqualTo(address);
    }

    private void thenWeAreFirewalled() {
        assertThat(this.node.isFirewalled()).isTrue();
    }

    private void thenWeAreFirewalled(String because) {
        assertThat(this.node.isFirewalled()).as(because).isTrue();
    }

    private void thenAConnectionIsAwaitedFrom(int ip, String because) {
        assertThat(this.node.awaitsConnectionFrom(ip)).as(because).isTrue();
    }

    private void thenNoConnectionIsAwaitedFrom(int ip, String because) {
        assertThat(this.node.awaitsConnectionFrom(ip)).as(because).isFalse();
    }

    private void thenOurPongToSaysThePort(int ip, int port) throws Exception {
        assertThat(KadMessages.decodePong(last(ip, Opcodes.Kad.PONG))).isEqualTo(port);
    }

    private void thenFewerPongsWentTo(int ip, int limit) {
        assertThat(count(ip, Opcodes.Kad.PONG)).as("only a few are answered").isLessThan(limit);
    }

    private void thenNoBootstrapAnswerWentTo(int ip, String because) {
        assertThat(last(ip, Opcodes.Kad.BOOTSTRAP_RES)).as(because).isNull();
    }

    private void thenOneMorePongWentTo(int ip, String because) {
        assertThat(count(ip, Opcodes.Kad.PONG)).as(because).isEqualTo(this.pongs + 1);
    }

    private void thenNothingIsSent() {
        assertThat(this.sent).isEmpty();
    }

    private void thenItStarted() {
        assertThat(this.started).isTrue();
    }

    private void thenItDidNotStart() {
        assertThat(this.started).isFalse();
    }

    private void thenItDidNotStart(String because) {
        assertThat(this.started).as(because).isFalse();
    }

    private void thenTheSourceSearchAsksForTheSize(long size) throws Exception {
        KadMessages.SourceSearch request = KadMessages.SourceSearch.decode(last(PEER, Opcodes.Kad.SEARCH_SOURCE_REQ));
        assertThat(request.size()).isEqualTo(size);
    }

    private void thenOnlyTheSourcesThatCanBeReachedDirectlyAreFound() {
        assertThat(this.sources)
                .containsExactly(
                        new KadNode.Source(this.direct.toHash16(), Ipv4.of(90, 1, 1, 1), 4662, 0),
                        new KadNode.Source(this.large.toHash16(), Ipv4.of(91, 1, 1, 1), 4663, 3));
    }

    private void thenTheExpressionGoesAlong() throws Exception {
        KadMessages.KeywordSearch request = KadMessages.KeywordSearch.decode(last(PEER, Opcodes.Kad.SEARCH_KEY_REQ));
        assertThat(request.expression()).as("the expression goes along").isEqualTo(tree(this.expression));
    }

    private void thenOnlyTheFileThatMatchesAllTheWordsIsFound() {
        assertThat(this.fileResults)
                .containsExactly(new KadNode.FileResult(
                        this.good.toHash16(),
                        "Ubuntu Desktop 24.iso",
                        5000,
                        7,
                        Optional.of("Iso"),
                        new SearchResult.Media("", "", "Noble Numbat", 95, 0, ""),
                        Optional.empty()));
    }

    private void thenTheFilesFoundAre(int count) {
        assertThat(this.fileResults).hasSize(count);
    }

    private void thenTheSearchDidNotStart(String because) {
        assertThat(this.search).as(because).isEmpty();
    }

    private void thenNoLookupRuns() {
        assertThat(this.node.runningLookups()).isZero();
    }

    private void thenTheKeywordPublishesRunningAre(int count) {
        assertThat(this.node.runningKeywordPublishes()).isEqualTo(count);
    }

    private void thenTheSourcePublishesRunningAre(int count) {
        assertThat(this.node.runningSourcePublishes()).isEqualTo(count);
    }

    private void thenKeywordPublishesWentToTheContact(int count) {
        assertThat(count(PEER, Opcodes.Kad.PUBLISH_KEY_REQ)).isEqualTo(count);
    }

    private void thenTheLastKeywordPublishCarriesFiles(int count) throws Exception {
        assertThat(KadMessages.KeywordPublish.decode(last(PEER, Opcodes.Kad.PUBLISH_KEY_REQ))
                        .files())
                .hasSize(count);
    }

    private void thenTheOnlyLookupIs(KadSearchInfo.Kind kind, int answers) {
        assertThat(this.node.lookupInfos()).singleElement().satisfies(l -> {
            assertThat(l.kind()).isEqualTo(kind);
            assertThat(l.answers()).isEqualTo(answers);
        });
    }

    private void thenTheOnlyLookupIs(KadSearchInfo.Kind kind) {
        assertThat(this.node.lookupInfos())
                .singleElement()
                .satisfies(l -> assertThat(l.kind()).isEqualTo(kind));
    }

    private void thenTheFirstLookupHasAnswers(int answers) {
        assertThat(this.node.lookupInfos().getFirst().answers()).isEqualTo(answers);
    }

    private void thenOurSourcePublishNamesTheFileAndUs() throws Exception {
        KadMessages.SourcePublish publish = sourcePublish();
        assertThat(publish.file()).isEqualTo(this.target);
        assertThat(publish.source()).isEqualTo(UInt128.of(USER));
    }

    private void thenOurSourcePublishCarries(int type, int port, long size, int encryption) throws Exception {
        List<Tag> tags = sourcePublish().tags();
        assertThat(Tags.intValue(tags, FileTag.SOURCETYPE)).as("a large file").hasValue(type);
        assertThat(Tags.intValue(tags, FileTag.SOURCEPORT)).hasValue(port);
        assertThat(Tags.intValue(tags, FileTag.FILESIZE)).hasValue(size);
        assertThat(Tags.intValue(tags, FileTag.ENCRYPTION)).hasValue(encryption);
    }

    private void thenTheLookupsInclude(KadSearchInfo.Kind... kinds) {
        assertThat(this.node.lookupInfos()).extracting(KadSearchInfo::kind).contains(kinds);
    }

    private void thenEveryRunningLookupIsShown() {
        assertThat(this.node.runningLookups()).isEqualTo(this.node.lookupInfos().size());
    }

    private void thenTheNodeIsReady() {
        assertThat(this.node.isReady()).isTrue();
    }

    private void thenTheNodeIsNotReady() {
        assertThat(this.node.isReady()).isFalse();
    }

    private void thenOurOwnIdIsLookedUp() throws Exception {
        assertThat(requestedTargets()).as("our own id is looked up").contains(this.node.id());
    }

    private void thenOurOwnIdIsLookedUpOnce() throws Exception {
        assertThat(requestedTargets()).filteredOn(this.node.id()::equals).hasSize(1);
    }

    private void thenAZoneIsLookedUpAgain() throws Exception {
        assertThat(requestedTargets())
                .as("a zone is looked up again, besides our own id")
                .anyMatch(t -> !t.equals(this.node.id()));
    }

    private void thenTheNodeIsConnected(String because) {
        assertThat(this.node.isConnected()).as(because).isTrue();
    }

    private void thenTheNodeIsNotConnected() {
        assertThat(this.node.isConnected()).isFalse();
    }

    private void thenThereIsNoNodesDat() {
        assertThat(nodesFile()).doesNotExist();
    }

    private void thenThereIsNoNodesDat(String because) {
        assertThat(nodesFile()).as(because).doesNotExist();
    }

    private void thenTheContactAddressesAre(String... addresses) {
        assertThat(this.node.contactInfos()).extracting(KadContactInfo::address).containsExactlyInAnyOrder(addresses);
    }

    // Helpers.

    private Path nodesFile() {
        return this.dir.resolve(KadNode.NODES_FILE);
    }

    private KadMessages.SourcePublish sourcePublish() throws Exception {
        return KadMessages.SourcePublish.decode(last(PEER, Opcodes.Kad.PUBLISH_SOURCE_REQ));
    }

    private List<UInt128> requestedTargets() throws Exception {
        List<UInt128> targets = new ArrayList<>();
        for (Sent s : this.sent) {
            if (s.packet().opcode() == Opcodes.Kad.REQ.code()) {
                targets.add(KadMessages.Request.decode(s.packet()).target());
            }
        }
        return targets;
    }

    private void contactAt(UInt128 id) {
        this.node.addContacts(contents(new NodesDat.Contact(id, PEER, PORT, 4662, 8, 0, NodesDat.UdpKey.NONE, true)));
    }

    private void hello(int ip, int version) {
        receive(
                ip,
                new KadMessages.Hello(new KadMessages.Self(UInt128.random(this.random), 4662, version), List.of())
                        .encode(false));
    }

    private void receive(int ip, Packet packet) {
        receive(ip, packet, 0, 0);
    }

    private void receive(int ip, Packet packet, int receiverKey, int senderKey) {
        this.node.received(ip, PORT, packet, receiverKey != 0, receiverKey, senderKey);
    }

    private Packet last(int ip, Opcodes.Kad opcode) {
        Sent s = lastSent(ip, opcode);
        return s == null ? null : s.packet();
    }

    private Sent sentWith(int ip, Opcodes.Kad opcode) {
        Sent s = lastSent(ip, opcode);
        assertThat(s).as("%s sent to %s", opcode, Ipv4.format(ip)).isNotNull();
        return s;
    }

    private Sent lastSent(int ip, Opcodes.Kad opcode) {
        for (int i = this.sent.size() - 1; i >= 0; i--) {
            Sent s = this.sent.get(i);
            if (s.ip() == ip && s.packet().opcode() == opcode.code()) {
                return s;
            }
        }
        return null;
    }

    private int count(int ip, Opcodes.Kad opcode) {
        return (int) this.sent.stream()
                .filter(s -> s.ip() == ip && s.packet().opcode() == opcode.code())
                .count();
    }

    private Hash16 hash() {
        byte[] bytes = new byte[16];
        this.random.nextBytes(bytes);
        return Hash16.of(bytes);
    }

    private KadMessages.Contact contact(int ip) {
        return new KadMessages.Contact(UInt128.random(this.random), ip, PORT, 4662, 8);
    }

    private NodesDat.Contact record(int ip) {
        return new NodesDat.Contact(UInt128.random(this.random), ip, PORT, 4662, 8, 0, NodesDat.UdpKey.NONE, true);
    }

    private static NodesDat.Contents contents(NodesDat.Contact... contacts) {
        return new NodesDat.Contents(2, false, List.of(contacts));
    }

    private static KadMessages.Entry entry(UInt128 id, String name, long size) {
        return new KadMessages.Entry(
                id,
                List.of(
                        new Tag.StringTag(TagName.id(FileTag.FILENAME), name),
                        new Tag.IntTag(TagName.id(FileTag.FILESIZE), size)));
    }

    private static List<Tag> sourceTags(int type) {
        return List.of(
                new Tag.IntTag(TagName.id(FileTag.SOURCETYPE), type),
                new Tag.IntTag(TagName.id(FileTag.SOURCEPORT), 4662));
    }

    private static List<Tag> source(int type, int ip, int port, int encryption) {
        return List.of(
                new Tag.IntTag(TagName.id(FileTag.SOURCETYPE), type),
                new Tag.IntTag(TagName.id(FileTag.SOURCEIP), Integer.toUnsignedLong(ip)),
                new Tag.IntTag(TagName.id(FileTag.SOURCEPORT), port),
                new Tag.IntTag(TagName.id(FileTag.ENCRYPTION), encryption));
    }

    private static byte[] tree(SearchExpr expression) {
        PacketWriter out = new PacketWriter();
        SearchTreeCodec.write(out, expression, true);
        return out.toByteArray();
    }

    // An id whose top bits differ from ours: far beyond the tolerance of a publish.
    private static UInt128 farFrom(UInt128 id) {
        return new UInt128(~id.high(), id.low());
    }
}
