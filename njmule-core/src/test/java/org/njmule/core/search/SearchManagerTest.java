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
package org.njmule.core.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.njmule.core.api.Hash16;
import org.njmule.core.api.Hash20;
import org.njmule.core.api.SearchId;
import org.njmule.core.api.SearchRequest;
import org.njmule.core.api.SearchResult;
import org.njmule.core.api.ServerInfo;
import org.njmule.core.config.ObfuscationMode;
import org.njmule.core.config.Preferences;
import org.njmule.core.engine.EngineLoop;
import org.njmule.core.persist.format.ServerMet;
import org.njmule.core.protocol.ed2k.FileEntry;
import org.njmule.core.protocol.ed2k.ServerDatagrams;
import org.njmule.core.protocol.ed2k.ServerMessages;
import org.njmule.core.protocol.packet.FrameLimits;
import org.njmule.core.protocol.packet.Opcodes;
import org.njmule.core.protocol.packet.Packet;
import org.njmule.core.protocol.packet.TcpFraming;
import org.njmule.core.protocol.tag.FileTag;
import org.njmule.core.protocol.tag.ServerTag;
import org.njmule.core.protocol.tag.Tag;
import org.njmule.core.protocol.tag.TagName;
import org.njmule.core.server.ServerList;
import org.njmule.core.server.ServerRoute;
import org.njmule.core.server.ServerSession;
import org.njmule.core.server.ServerUdp;
import org.njmule.core.util.Ipv4;

/** Searches on a scripted server on the loopback address and on a Kad the test plays. */
@Timeout(60)
class SearchManagerTest {

    private static final int LOOPBACK = Ipv4.parse("127.0.0.1");
    private static final long HIGH_ID = Ipv4.toEd2k(Ipv4.parse("198.51.100.7"));
    private static final Hash20 ROOT_A = Hash20.of(filled(20, 0x0A));
    private static final Hash20 ROOT_B = Hash20.of(filled(20, 0x0B));
    private static final List<SearchResult> FIRST_KAD_ANSWER = List.of(
            new SearchResult(hash(1), "distro.iso", 1000, 5, 1, Optional.empty()),
            new SearchResult(hash(2), " ", 1000, 1, 1, Optional.empty()),
            new SearchResult(hash(3), "empty.iso", 0, 1, 1, Optional.empty()));
    private static final List<SearchResult> SECOND_KAD_ANSWER = List.of(
            new SearchResult(hash(1), "distro.iso", 1000, 3, 4, Optional.of("Iso")),
            new SearchResult(hash(1), "distro.iso", 2000, 9, 9, Optional.empty()));
    private static final List<FileEntry> FIRST_SERVER_ANSWER = List.of(
            entry(1, "linux.iso", 1000, 2),
            new FileEntry(hash(2), 0, 0, List.of()),
            new FileEntry(hash(4), 0, 0, List.of(new Tag.StringTag(TagName.id(FileTag.FILENAME), "nosize.iso"))),
            entry(1, "linux.iso", 2000, 7));

    private final EngineLoop loop = EngineLoop.start(1024, "test-engine");
    private final ServerList servers = new ServerList();
    private final BlockingQueue<String> events = new LinkedBlockingQueue<>();
    private final List<SearchResult> reported = new CopyOnWriteArrayList<>();
    private Consumer<List<SearchResult>> kadAnswers;
    private SearchExpr kadExpression;
    private final List<AutoCloseable> resources = new ArrayList<>();
    private volatile Optional<ServerUdp> udp = Optional.empty();
    private volatile Optional<SearchManager.KadSearches> kad = Optional.empty();
    private final ServerSession session = new ServerSession(
            this.loop,
            this.servers,
            Preferences::defaults,
            Hash16.parseHex("01".repeat(16)),
            () -> 4662,
            new Session(),
            () -> false,
            new SecureRandom());
    // Where the UDP queries go: here, not to the servers' own addresses.
    private final DatagramSocket udpServer = openUdpServer();
    private final SearchManager manager = new SearchManager(
            this.loop,
            this.session,
            this.servers,
            () -> this.udp,
            () -> this.kad,
            new Events(),
            s -> ServerRoute.of(s, 0, ObfuscationMode.DISABLED)
                    .map(r -> new ServerRoute(
                            new InetSocketAddress(InetAddress.getLoopbackAddress(), this.udpServer.getLocalPort()),
                            r.key())),
            new SecureRandom());
    private final List<String> answered = new CopyOnWriteArrayList<>();
    private final List<String> keywords = new ArrayList<>();
    private final List<String> stopped = new ArrayList<>();
    private SearchId id;
    private ScriptedServer server;
    private String chosenKeyword;
    private Throwable exception;

    @AfterEach
    void stop() throws Exception {
        this.loop.close();
        this.udpServer.close();
        for (AutoCloseable resource : this.resources) {
            resource.close();
        }
    }

    @Test
    void shouldRefuseAKadSearchWhileKadIsNotRunning() {
        whenASearchIsStarted(kad("linux"));

        thenExceptionOccurredWithTheRootCause(IllegalStateException.class, "Kad is not running");
    }

    @Test
    void shouldRefuseAKadSearchForAKeywordKadCannotLookUp() {
        givenARunningKadThatCannotLookUp("never");

        whenASearchIsStarted(kad("never"));

        thenExceptionOccurredWithTheRootCause(IllegalStateException.class);
    }

    @Test
    void shouldLookUpTheLongestWordOnKad() {
        givenARunningKadThatCannotLookUp("never");

        whenASearchIsStarted(kad("rare linux distro"));

        thenNoExceptionOccurred();
        thenKadLooksUp("distro");
    }

    @Test
    void shouldReportTheFilesOfAKadAnswer() throws Exception {
        givenARunningKadThatCannotLookUp("never");
        givenASearchIsStarted(kad("rare linux distro"));

        whenKadSendsTheResults(FIRST_KAD_ANSWER);

        thenTheNextEventReports("distro.iso 5/1");
    }

    @Test
    void shouldRateAKadResultByItsNameWithoutTheWordsSearchedFor() throws Exception {
        givenARaterThatTakesForSpamNamesWith("pirate");
        givenARunningKadThatCannotLookUp("never");
        givenASearchIsStarted(kad("rare linux distro"));

        whenKadSendsTheResults(List.of(result(1, "Rare-Linux_Distro (pirate).iso", 1000, 5)));

        thenTheReportedResultCameFrom(hash(1), new SearchResult.Origin(Set.of(), true, Set.of(), "pirate iso"));
        thenTheReportedResultIsRated(hash(1), 70);
    }

    @Test
    void shouldRateTheUdpAnswersOfAServerAndCountItsSpam() throws Exception {
        givenAServerToAskOverUdp(Ipv4.of(192, 0, 2, 1));
        givenAnOpenUdpSocket();
        givenARaterThatTakesForSpamNamesWith("pirate");
        givenAConnectedServer();
        givenASearchIsStarted(global("linux"));
        givenTheServerReceivedTheQuery();
        givenTheServerAnswered(List.of(), false);
        givenTheQueryWentOverUdp();

        whenTheServerAnswersOverUdp(
                Ipv4.of(192, 0, 2, 1),
                entryFrom(5, "Linux (pirate).iso", Ipv4.of(203, 0, 113, 5)),
                entry(6, "linux.iso", 1000, 2));

        thenTheServerIsCounted("answered 192.0.2.1 2 1");
        thenTheReportedResultCameFrom(
                hash(5),
                new SearchResult.Origin(
                        Set.of(Ipv4.of(192, 0, 2, 1)), false, Set.of(Ipv4.of(203, 0, 113, 5)), "pirate iso"));
        thenTheReportedResultIsRated(hash(5), 70);
    }

    @Test
    void shouldAskNoOtherServerOnceTheConnectedOneGaveMoreThanAHundredResults() throws Exception {
        givenAServerToAskOverUdp(Ipv4.of(192, 0, 2, 1));
        givenAnOpenUdpSocket();
        givenAConnectedServer();
        givenASearchIsStarted(global("linux"));
        givenTheServerReceivedTheQuery();

        whenTheServerAnswersWith(SearchManager.GLOBAL_RESULTS + 1);

        thenNoServerIsAskedOverUdp();
    }

    @Test
    void shouldAskTheOtherServersWhileTheConnectedOneGaveAHundredResults() throws Exception {
        givenAServerToAskOverUdp(Ipv4.of(192, 0, 2, 1));
        givenAnOpenUdpSocket();
        givenAConnectedServer();
        givenASearchIsStarted(global("linux"));
        givenTheServerReceivedTheQuery();

        whenTheServerAnswersWith(SearchManager.GLOBAL_RESULTS);

        thenAServerIsAskedOverUdp();
    }

    @Test
    void shouldGiveTheLinkOfAResultTheAichRootKadTold() throws Exception {
        givenARunningKadThatCannotLookUp("never");
        givenASearchIsStarted(kad("rare linux distro"));

        whenKadSendsTheResults(List.of(distroWithAich(ROOT_A)));

        thenTheLinkOfTheResultHasTheAichRoot(hash(1), Optional.of(ROOT_A));
    }

    @Test
    void shouldKeepNoAichRootOfAFileGivenTwoEvenWhenTheFirstComesAgain() throws Exception {
        givenARunningKadThatCannotLookUp("never");
        givenASearchIsStarted(kad("rare linux distro"));
        givenKadAnsweredWith(List.of(distroWithAich(ROOT_A)));
        givenKadAnsweredWith(List.of(distroWithAich(ROOT_B)));

        whenKadSendsTheResults(List.of(distroWithAich(ROOT_A)));

        thenTheLinkOfTheResultHasTheAichRoot(hash(1), Optional.empty());
    }

    @Test
    void shouldKeepTheLargestCountsKadNodesReport() throws Exception {
        givenARunningKadThatCannotLookUp("never");
        givenASearchIsStarted(kad("rare linux distro"));
        givenKadAnsweredWith(FIRST_KAD_ANSWER);

        whenKadSendsTheResults(SECOND_KAD_ANSWER);

        thenTheNextEventReports("distro.iso 5/4", "Kad nodes report the same sources: the largest count is kept");
    }

    @Test
    void shouldTakeKadResultsUpToTheLimit() throws Exception {
        givenARunningKadThatCannotLookUp("never");
        givenASearchIsStarted(kad("rare linux distro"));
        givenKadAnsweredWith(FIRST_KAD_ANSWER);
        givenKadAnsweredWith(SECOND_KAD_ANSWER);

        whenKadSendsTheResults(moreFilesThanTheLimit());

        thenTheNextEventReportsFirst("file0.bin");
    }

    @Test
    void shouldLetAKadSearchOutliveTheServerAndTakeNothingMoreOnceStopped() throws Exception {
        givenARunningKadThatCannotLookUp("never");
        givenASearchIsStarted(kad("rare linux distro"));
        givenKadAnsweredWith(FIRST_KAD_ANSWER);
        givenKadAnsweredWith(SECOND_KAD_ANSWER);
        givenKadAnsweredWith(moreFilesThanTheLimit());
        givenTheServerIsLost();
        givenTheSearchIsStopped();
        givenTheSearchIsStopped();

        whenKadSendsTheResults(List.of(new SearchResult(hash(5), "late.iso", 10, 1, 0, Optional.empty())));

        thenTheNextEventIsTheEndWith(
                SearchManager.MAX_RESULTS, "a Kad search outlives the server; once stopped it takes nothing more");
        thenKadStoppedTheLookUpsOf("distro");
        thenNoEventComesWithin(200);
    }

    @Test
    void shouldFillTheMediaDetailsAFirstAnswerLackedFromLaterOnes() throws Exception {
        givenAKadSearchFor("opening song");
        givenKadAnswered(song(new SearchResult.Media("Some Band", "", "", 0, 192, "")));

        whenKadAnswers(song(new SearchResult.Media("Other Band", "First Album", "", 205, 128, "mp3")));

        thenTheLastResultHasTheMedia(new SearchResult.Media("Some Band", "First Album", "", 205, 192, "mp3"));
    }

    @Test
    void shouldShowTheNameMostSourcesGiveAndKeepTheOthers() throws Exception {
        givenAKadSearchFor("holiday clip");
        givenKadAnswered(new SearchResult(hash(13), "holiday clip.avi", 1000, 2, 0, Optional.empty()));

        whenKadAnswers(new SearchResult(hash(13), "Holiday Clip HD.avi", 1000, 5, 0, Optional.empty()));

        thenTheResultsReportedAre("holiday clip.avi", "Holiday Clip HD.avi");
        thenTheLastResultHasTheNames(
                new SearchResult.Name("Holiday Clip HD.avi", 5, 0), new SearchResult.Name("holiday clip.avi", 2, 0));
    }

    @Test
    void shouldTakeTheTypeFromTheFileNameRatherThanFromThePublisher() throws Exception {
        givenAKadSearchFor("holiday clip");

        whenKadAnswers(new SearchResult(hash(11), "holiday clip.exe", 1000, 1, 0, Optional.of("Video")));

        thenTheLastResultHasTheType(Optional.of("Pro"));
    }

    @Test
    void shouldKeepTheReportedTypeOfANameThatDoesNotTellIt() throws Exception {
        givenAKadSearchFor("readme");

        whenKadAnswers(new SearchResult(hash(12), "readme", 10, 1, 0, Optional.of("Doc")));

        thenTheLastResultHasTheType(Optional.of("Doc"));
    }

    @Test
    void shouldDropAReportedTypeThatIsNoFileType() throws Exception {
        givenAKadSearchFor("notes");

        whenKadAnswers(new SearchResult(hash(13), "notes", 10, 1, 0, Optional.of("\u001b[2JFree")));

        thenTheLastResultHasTheType(Optional.empty());
    }

    @Test
    void shouldKeepControlAndDirectionCharactersOutOfResultNames() throws Exception {
        givenAKadSearchFor("song");

        whenKadAnswers(new SearchResult(hash(16), "\u001b[2Jsong\u202Egpj.exe\r\n", 10, 1, 0, Optional.empty()));

        thenTheResultsReportedAre("[2Jsonggpj.exe");
    }

    @Test
    void shouldAskKadForArchivesAsProgramsAndKeepOnlyTheArchives() throws Exception {
        givenAKadSearchFor("linux @type=archive");

        whenKadAnswers(
                new SearchResult(hash(14), "linux setup.exe", 1000, 1, 0, Optional.of("Pro")),
                new SearchResult(hash(15), "linux sources.tar.gz", 1000, 1, 0, Optional.of("Pro")));

        thenKadIsAskedFor(new SearchExpr.And(
                new SearchExpr.Keyword("linux"), new SearchExpr.StringAttr(FileTag.FILETYPE, "Pro")));
        thenTheResultsReportedAre("linux sources.tar.gz");
    }

    @ParameterizedTest
    @CsvSource({"'@type=video linux', linux", "'ubuntu -windows', ubuntu"})
    void shouldLookUpOnKadTheLongestWordEveryMatchHas(String expression, String keyword) {
        whenTheKadKeywordIsChosenFor(expression);

        thenTheKadKeywordIs(keyword);
    }

    @Test
    void shouldLookUpOnKadAWordInQuotesWhenThereAreNoOthers() {
        whenTheKadKeywordIsChosenFor("\"free software\" go");

        thenTheKadKeywordIs("software", "words in quotes when there are no others");
    }

    @Test
    void shouldNotLookUpAnyWordOnKadForAlternatives() {
        whenTheKadKeywordIsChosenFor("debian OR ubuntu");

        thenExceptionOccurredWithAMessageContaining("OR");
    }

    @Test
    void shouldNotLookUpOnKadAWordShorterThanThreeLetters() {
        whenTheKadKeywordIsChosenFor("go to");

        thenExceptionOccurredWithAMessageContaining("three letters");
    }

    @Test
    void shouldRefuseAServerSearchWithoutAServer() {
        whenASearchIsStarted(server("linux"));

        thenExceptionOccurredWithTheRootCause(IllegalStateException.class);
    }

    @Test
    void shouldIgnoreAnswersNoSearchAskedFor() throws Exception {
        whenAnswersComeThatNoSearchAskedFor();

        thenNoEventComesWithin(200, "answers no search asked for");
    }

    @Test
    void shouldTakeMoreResultsFromTheServerOneRequestAtATimeUntilItsLastAnswer() throws Exception {
        givenAConnectedServer();
        givenASearchIsStarted(server("linux"));
        givenTheServerReceivedASearchRequest();
        givenTheServerAnswered(FIRST_SERVER_ANSWER, true);
        givenTheSearchReported("linux.iso 2/2");
        givenMoreResultsWereAskedFor();
        givenAskingForMoreAgainWasRefused();

        whenTheServerAnswers(List.of(entry(1, "linux.iso", 1000, 3)), false);

        thenTheNextEventReports("linux.iso 5/5", "servers count their own sources: they add up");
        thenTheNextEventIsTheEndWith(1);
        thenNoMoreResultsCanBeAskedFor();
    }

    @Test
    void shouldEndAServerSearchWhenTheServerIsLost() throws Exception {
        givenAConnectedServer();
        givenASearchIsStarted(server("lost"));
        givenTheServerReceivedTheQuery();

        whenTheServerIsLost();

        thenTheNextEventIsTheEndWith(0);
    }

    @Test
    void shouldIgnoreTheLateAnswerOfALostServer() throws Exception {
        givenAConnectedServer();
        givenASearchIsStarted(server("lost"));
        givenTheServerReceivedTheQuery();
        givenTheServerIsLost();
        givenTheSearchEndedWith(0);

        whenTheServerAnswers(List.of(entry(3, "late.iso", 10, 1)), false);

        thenNoEventComesWithin(500);
    }

    @Test
    void shouldEndAGlobalSearchWithTheServerAnswerWithoutTheUdpSocket() throws Exception {
        givenServersWithLargeFilesWithoutThemAndWithoutAUdpPort();
        givenAConnectedServer();
        givenASearchIsStarted(global("alone"));
        givenTheServerReceivedTheQuery();

        whenTheServerAnswers(List.of(), false);

        thenTheNextEventIsTheEndWith(0);
    }

    @Test
    void shouldEndAGlobalSearchNoServerCanBeAskedOverUdpOnceTheTimeForAnswersIsOver() throws Exception {
        // A query that cannot be sent, and one that cannot be made for a server without 64-bit sizes: the search
        // still ends, once the time for answers is over.
        givenServersWithLargeFilesWithoutThemAndWithoutAUdpPort();
        givenAConnectedServer();
        givenAClosedUdpSocket();
        givenASearchIsStarted(global("@size>5g linux"));
        givenTheServerReceivedTheQuery();

        whenTheServerAnswers(List.of(), false);

        thenTheSearchEndsOnceTheTimeForAnswersIsOverWith(0);
    }

    private void givenARunningKadThatCannotLookUp(String refused) {
        this.kad = Optional.of((keyword, expression, results) -> {
            this.keywords.add(keyword);
            if (keyword.equals(refused)) {
                return Optional.empty();
            }
            this.kadAnswers = results;
            return Optional.of(() -> this.stopped.add(keyword));
        });
    }

    private void givenAKadSearchFor(String words) throws Exception {
        this.kad = Optional.of((keyword, expression, results) -> {
            this.kadExpression = expression;
            this.kadAnswers = results;
            return Optional.of(() -> {});
        });
        onLoop(() -> this.manager.start(kad(words)));
    }

    private void givenASearchIsStarted(SearchRequest request) throws Exception {
        this.id = onLoop(() -> this.manager.start(request));
    }

    private void givenKadAnswered(SearchResult result) throws Exception {
        whenKadAnswers(result);
    }

    private void givenKadAnsweredWith(List<SearchResult> results) throws Exception {
        whenKadSendsTheResults(results);
        next();
    }

    private void givenTheServerIsLost() throws Exception {
        onLoop(() -> {
            this.manager.serverLost();
            return null;
        });
    }

    private void givenTheSearchIsStopped() throws Exception {
        onLoop(() -> {
            this.manager.stop(this.id);
            return null;
        });
    }

    private void givenAConnectedServer() throws Exception {
        this.server = connect();
    }

    private void givenTheServerReceivedASearchRequest() throws IOException {
        assertThat(this.server.next().opcode()).isEqualTo(Opcodes.Server.SEARCHREQUEST.code());
    }

    private void givenTheServerReceivedTheQuery() throws IOException {
        this.server.next();
    }

    private void givenTheServerAnswered(List<FileEntry> files, boolean more) throws IOException {
        this.server.send(new ServerMessages.SearchResults(files, more).encode());
    }

    private void givenTheSearchReported(String result) throws InterruptedException {
        assertThat(next()).isEqualTo("results " + this.id.value() + " " + result);
    }

    private void givenMoreResultsWereAskedFor() throws Exception {
        assertThat(onLoop(() -> this.manager.more(this.id))).isTrue();
        assertThat(this.server.next().opcode()).isEqualTo(Opcodes.Server.QUERY_MORE_RESULT.code());
    }

    private void givenAskingForMoreAgainWasRefused() throws Exception {
        assertThat(onLoop(() -> this.manager.more(this.id)))
                .as("one request at a time")
                .isFalse();
    }

    private void givenTheSearchEndedWith(int results) throws InterruptedException {
        assertThat(next()).isEqualTo("ended " + this.id.value() + " " + results);
    }

    private void givenServersWithLargeFilesWithoutThemAndWithoutAUdpPort() throws Exception {
        ServerMet.Server wide = new ServerMet.Server(Ipv4.of(192, 0, 2, 1), 4661, List.of())
                .with(intTag(ServerTag.UDPFLAGS, ServerDatagrams.UDP_LARGEFILES | ServerDatagrams.UDP_EXT_GETFILES));
        ServerMet.Server narrow = new ServerMet.Server(Ipv4.of(192, 0, 2, 2), 4661, List.of());
        ServerMet.Server noUdpPort = new ServerMet.Server(Ipv4.of(192, 0, 2, 3), 65_533, List.of());
        onLoop(() -> {
            this.servers.add(wide);
            this.servers.add(narrow);
            this.servers.add(noUdpPort);
            this.servers.setPriority(ServerList.addressOf(wide), ServerInfo.Priority.HIGH);
            return null;
        });
    }

    private void givenARaterThatTakesForSpamNamesWith(String word) {
        this.manager.rateSpamWith(new SearchManager.SpamRater() {
            @Override
            public int rate(SearchResult result) {
                return result.origin().bareName().contains(word) ? 70 : 0;
            }

            @Override
            public void answered(int serverIp, int results, int spamResults) {
                SearchManagerTest.this.answered.add(
                        "answered " + Ipv4.format(serverIp) + " " + results + " " + spamResults);
            }
        });
    }

    private void givenAServerToAskOverUdp(int ip) throws Exception {
        onLoop(() -> {
            this.servers.add(new ServerMet.Server(ip, 4661, List.of()));
            return null;
        });
    }

    private void givenAnOpenUdpSocket() throws IOException {
        ServerUdp open = ServerUdp.open(0, (from, packet) -> {});
        this.resources.add(open);
        this.udp = Optional.of(open);
    }

    private void givenTheQueryWentOverUdp() throws IOException {
        this.udpServer.setSoTimeout(20_000);
        this.udpServer.receive(new DatagramPacket(new byte[2048], 2048));
    }

    private void whenTheServerAnswersWith(int results) throws IOException {
        List<FileEntry> files = new ArrayList<>();
        for (int i = 0; i < results; i++) {
            files.add(entry(2000 + i, "linux " + i + ".iso", 1000, 1));
        }
        this.server.send(new ServerMessages.SearchResults(files, false).encode());
    }

    private void thenAServerIsAskedOverUdp() throws IOException {
        givenTheQueryWentOverUdp();
    }

    private void thenNoServerIsAskedOverUdp() throws IOException {
        this.udpServer.setSoTimeout(2_000);
        assertThatThrownBy(() -> this.udpServer.receive(new DatagramPacket(new byte[2048], 2048)))
                .as("no query over UDP")
                .isInstanceOf(SocketTimeoutException.class);
    }

    private void whenTheServerAnswersOverUdp(int ip, FileEntry... files) throws Exception {
        onLoop(() -> {
            this.manager.datagramResults(new InetSocketAddress(Ipv4.format(ip), 4665), List.of(files));
            return null;
        });
    }

    private void thenTheServerIsCounted(String counted) {
        assertThat(this.answered).containsExactly(counted);
    }

    private void thenTheReportedResultCameFrom(Hash16 file, SearchResult.Origin origin) {
        assertThat(lastReported(file).origin()).isEqualTo(origin);
    }

    private void thenTheReportedResultIsRated(Hash16 file, int rating) {
        assertThat(lastReported(file).spamRating()).isEqualTo(rating);
    }

    private void thenTheLinkOfTheResultHasTheAichRoot(Hash16 file, Optional<Hash20> root) {
        assertThat(lastReported(file).link().aichHash()).isEqualTo(root);
    }

    private static SearchResult distroWithAich(Hash20 root) {
        return new SearchResult(hash(1), "distro.iso", 1000, 5, SearchResult.UNKNOWN, Optional.empty())
                .withAichHash(Optional.of(root));
    }

    private SearchResult lastReported(Hash16 file) {
        return this.reported.reversed().stream()
                .filter(r -> r.hash().equals(file))
                .findFirst()
                .orElseThrow();
    }

    private static DatagramSocket openUdpServer() {
        try {
            return new DatagramSocket(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        } catch (SocketException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void givenAClosedUdpSocket() throws IOException {
        ServerUdp closed = ServerUdp.open(0, (from, packet) -> {});
        closed.close();
        this.udp = Optional.of(closed);
    }

    private void whenASearchIsStarted(SearchRequest request) {
        this.exception = catchThrowable(() -> this.id = onLoop(() -> this.manager.start(request)));
    }

    private void whenKadAnswers(SearchResult... results) throws Exception {
        onLoop(() -> {
            this.kadAnswers.accept(List.of(results));
            return null;
        });
        next();
    }

    private void whenKadSendsTheResults(List<SearchResult> results) throws Exception {
        onLoop(() -> {
            this.kadAnswers.accept(results);
            return null;
        });
    }

    private void whenTheKadKeywordIsChosenFor(String expression) {
        this.exception =
                catchThrowable(() -> this.chosenKeyword = SearchManager.kadKeyword(SearchParser.parse(expression)));
    }

    private void whenAnswersComeThatNoSearchAskedFor() throws Exception {
        onLoop(() -> {
            this.manager.serverResults(
                    new ServerMet.Server(LOOPBACK, 4661, List.of()),
                    new ServerMessages.SearchResults(List.of(), false));
            this.manager.datagramResults(
                    new InetSocketAddress(InetAddress.getLoopbackAddress(), 4665), List.of(entry(1, "x.iso", 10, 1)));
            return null;
        });
    }

    private void whenTheServerAnswers(List<FileEntry> files, boolean more) throws IOException {
        this.server.send(new ServerMessages.SearchResults(files, more).encode());
    }

    private void whenTheServerIsLost() throws Exception {
        onLoop(() -> {
            this.manager.serverLost();
            return null;
        });
    }

    private void thenNoExceptionOccurred() {
        assertThat(this.exception).isNull();
    }

    private void thenExceptionOccurredWithTheRootCause(Class<? extends Exception> type) {
        assertThat(this.exception).hasRootCauseInstanceOf(type);
    }

    private void thenExceptionOccurredWithTheRootCause(Class<? extends Exception> type, String message) {
        assertThat(this.exception).hasRootCauseInstanceOf(type).hasRootCauseMessage(message);
    }

    private void thenExceptionOccurredWithAMessageContaining(String messagePart) {
        assertThat(this.exception).hasMessageContaining(messagePart);
    }

    private void thenKadLooksUp(String expected) {
        assertThat(this.keywords).last().as("the longest word").isEqualTo(expected);
    }

    private void thenKadStoppedTheLookUpsOf(String... expected) {
        assertThat(this.stopped).containsExactly(expected);
    }

    private void thenKadIsAskedFor(SearchExpr expected) {
        assertThat(this.kadExpression).isEqualTo(expected);
    }

    private void thenTheKadKeywordIs(String expected) {
        assertThat(this.exception).isNull();
        assertThat(this.chosenKeyword).isEqualTo(expected);
    }

    private void thenTheKadKeywordIs(String expected, String description) {
        assertThat(this.exception).isNull();
        assertThat(this.chosenKeyword).as(description).isEqualTo(expected);
    }

    private void thenTheNextEventReports(String result) throws InterruptedException {
        assertThat(next()).isEqualTo("results " + this.id.value() + " " + result);
    }

    private void thenTheNextEventReports(String result, String description) throws InterruptedException {
        assertThat(next()).as(description).isEqualTo("results " + this.id.value() + " " + result);
    }

    private void thenTheNextEventReportsFirst(String name) throws InterruptedException {
        assertThat(next()).startsWith("results " + this.id.value() + " " + name);
    }

    private void thenTheNextEventIsTheEndWith(int results) throws InterruptedException {
        assertThat(next()).isEqualTo("ended " + this.id.value() + " " + results);
    }

    private void thenTheNextEventIsTheEndWith(int results, String description) throws InterruptedException {
        assertThat(next()).as(description).isEqualTo("ended " + this.id.value() + " " + results);
    }

    private void thenTheSearchEndsOnceTheTimeForAnswersIsOverWith(int results) throws InterruptedException {
        assertThat(next(SearchManager.ANSWER_GRACE.plusSeconds(10).toMillis()))
                .isEqualTo("ended " + this.id.value() + " " + results);
    }

    private void thenNoEventComesWithin(long millis) throws InterruptedException {
        assertThat(this.events.poll(millis, TimeUnit.MILLISECONDS)).isNull();
    }

    private void thenNoEventComesWithin(long millis, String description) throws InterruptedException {
        assertThat(this.events.poll(millis, TimeUnit.MILLISECONDS))
                .as(description)
                .isNull();
    }

    private void thenNoMoreResultsCanBeAskedFor() throws Exception {
        assertThat(onLoop(() -> this.manager.more(this.id))).isFalse();
    }

    private void thenTheResultsReportedAre(String... names) {
        assertThat(this.reported).extracting(SearchResult::name).containsExactly(names);
    }

    private void thenTheLastResultHasTheMedia(SearchResult.Media expected) {
        assertThat(this.reported.getLast().media()).isEqualTo(expected);
    }

    private void thenTheLastResultHasTheNames(SearchResult.Name... names) {
        assertThat(this.reported.getLast().names()).containsExactly(names);
    }

    private void thenTheLastResultHasTheType(Optional<String> expected) {
        assertThat(this.reported.getLast().type()).isEqualTo(expected);
    }

    private static SearchResult song(SearchResult.Media media) {
        return new SearchResult(hash(9), "opening song.mp3", 4_000_000, 1, 0, Optional.of("Audio"), media);
    }

    private static List<SearchResult> moreFilesThanTheLimit() {
        List<SearchResult> many = new ArrayList<>();
        for (int i = 0; i < SearchManager.MAX_RESULTS + 1; i++) {
            many.add(new SearchResult(hash(1000 + i), "file" + i + ".bin", 10, 1, 0, Optional.empty()));
        }
        return many;
    }

    private ScriptedServer connect() throws Exception {
        ServerSocket listening = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        this.resources.add(listening);
        onLoop(() -> {
            // Pinged before and known to be plain: no obfuscation is tried first.
            this.session.connect(Optional.of(new ServerMet.Server(LOOPBACK, listening.getLocalPort(), List.of())
                    .with(intTag(ServerTag.UDPFLAGS, 0))));
            return null;
        });
        listening.setSoTimeout(10_000);
        ScriptedServer server = new ScriptedServer(listening.accept());
        this.resources.add(server);
        assertThat(server.next().opcode()).isEqualTo(Opcodes.Server.LOGINREQUEST.code());
        server.send(new ServerMessages.IdChange(HIGH_ID, ServerMessages.TCP_LARGEFILES, 0).encode());
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!onLoop(this.session::isConnected)) {
            assertThat(System.nanoTime()).as("connected in time").isLessThan(deadline);
            Thread.sleep(20);
        }
        return server;
    }

    private static SearchRequest kad(String expression) {
        return new SearchRequest(expression, SearchRequest.Scope.KAD);
    }

    private static SearchRequest server(String expression) {
        return new SearchRequest(expression, SearchRequest.Scope.SERVER);
    }

    private static SearchRequest global(String expression) {
        return new SearchRequest(expression, SearchRequest.Scope.GLOBAL);
    }

    private static FileEntry entry(int hash, String name, long size, int sources) {
        return new FileEntry(
                hash(hash),
                0,
                0,
                List.of(
                        new Tag.StringTag(TagName.id(FileTag.FILENAME), name),
                        new Tag.IntTag(TagName.id(FileTag.FILESIZE), size),
                        new Tag.IntTag(TagName.id(FileTag.SOURCES), sources),
                        new Tag.IntTag(TagName.id(FileTag.COMPLETE_SOURCES), sources)));
    }

    private static Tag intTag(int id, long value) {
        return new Tag.IntTag(TagName.id(id), value);
    }

    private static SearchResult result(int hash, String name, long size, int sources) {
        return new SearchResult(hash(hash), name, size, sources, 0, Optional.empty());
    }

    // An entry naming a HighID source at sourceIp.
    private static FileEntry entryFrom(int hash, String name, int sourceIp) {
        return new FileEntry(
                hash(hash),
                Ipv4.toEd2k(sourceIp),
                4662,
                List.of(
                        new Tag.StringTag(TagName.id(FileTag.FILENAME), name),
                        new Tag.IntTag(TagName.id(FileTag.FILESIZE), 1000),
                        new Tag.IntTag(TagName.id(FileTag.SOURCES), 3)));
    }

    private static byte[] filled(int length, int value) {
        byte[] bytes = new byte[length];
        Arrays.fill(bytes, (byte) value);
        return bytes;
    }

    private static Hash16 hash(int seed) {
        return Hash16.parseHex(String.format("%032x", seed));
    }

    private String next() throws InterruptedException {
        return next(10_000);
    }

    private String next(long millis) throws InterruptedException {
        String event = this.events.poll(millis, TimeUnit.MILLISECONDS);
        assertThat(event).as("an event").isNotNull();
        return event;
    }

    private <T> T onLoop(Callable<T> task) throws Exception {
        return this.loop.submit(task).get(10, TimeUnit.SECONDS);
    }

    /** The server's side of the connection. */
    private static final class ScriptedServer implements AutoCloseable {
        private final Socket socket;
        private final InputStream in;
        private final OutputStream out;

        ScriptedServer(Socket socket) throws IOException {
            this.socket = socket;
            this.socket.setSoTimeout(10_000);
            this.in = new BufferedInputStream(socket.getInputStream());
            this.out = socket.getOutputStream();
        }

        Packet next() throws IOException {
            return TcpFraming.read(this.in, FrameLimits.SERVER);
        }

        void send(Packet packet) throws IOException {
            this.out.write(TcpFraming.encode(packet));
            this.out.flush();
        }

        @Override
        public void close() throws IOException {
            this.socket.close();
        }
    }

    private final class Events implements SearchManager.Listener {
        @Override
        public void results(SearchId search, List<SearchResult> changed) {
            SearchManagerTest.this.reported.addAll(changed);
            SearchResult first = changed.getFirst();
            SearchManagerTest.this.events.add("results " + search.value() + " " + first.name() + " " + first.sources()
                    + "/" + first.completeSources());
        }

        @Override
        public void ended(SearchId search, int results) {
            SearchManagerTest.this.events.add("ended " + search.value() + " " + results);
        }
    }

    private final class Session implements ServerSession.Listener {
        @Override
        public void connecting(ServerMet.Server server) {}

        @Override
        public void connected(ServerMet.Server server, ServerMessages.IdChange id) {}

        @Override
        public void disconnected(Optional<ServerMet.Server> server, String reason) {}

        @Override
        public void message(ServerMet.Server server, String line) {}

        @Override
        public void updated(ServerMet.Server server) {}

        @Override
        public void searchResults(ServerMet.Server server, ServerMessages.SearchResults results) {
            SearchManagerTest.this.manager.serverResults(server, results);
        }

        @Override
        public void callbackRequested(ServerMessages.CallbackRequested request) {}

        @Override
        public void foundSources(ServerMessages.FoundSources sources) {}

        @Override
        public void serverList(ServerMet.Server server, List<ServerMessages.Endpoint> servers) {}
    }
}
