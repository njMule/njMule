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
package org.njmule.app;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.njmule.core.api.DownloadInfo;
import org.njmule.core.api.DownloadPriority;
import org.njmule.core.api.Ed2kLink;
import org.njmule.core.api.EngineEvent;
import org.njmule.core.api.Hash16;
import org.njmule.core.api.ImportReport;
import org.njmule.core.api.KadInfo;
import org.njmule.core.api.PartMap;
import org.njmule.core.api.PeerInfo;
import org.njmule.core.api.SearchId;
import org.njmule.core.api.SearchResult;
import org.njmule.core.api.ServerInfo;

/** The command-line network work against an engine the test controls: what it asks, prints and returns. */
@Timeout(30)
class NetworkCommandsTest {

    private static final String SERVER = "10.0.0.1:4661";
    private static final Hash16 USER = Hash16.parseHex("0F".repeat(16));
    private static final Ed2kLink.FileLink GOOD =
            Ed2kLink.FileLink.of("good.bin", 1000, Hash16.parseHex("AA".repeat(16)));
    private static final Ed2kLink.FileLink BAD =
            Ed2kLink.FileLink.of("bad.bin", 3L << 20, Hash16.parseHex("BB".repeat(16)));

    @TempDir
    Path dir;

    private final FakeEngine fake = new FakeEngine();
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();
    private KadInfo kadState;
    private String kadLine;
    private int exitCode;
    private Duration took;
    private Thread run;
    private final AtomicReference<Throwable> ended = new AtomicReference<>();
    private DownloadInfo download;
    private String text;

    @AfterEach
    void stop() {
        this.fake.events.close();
    }

    @Test
    void shouldAddServersShareAndJoinKadWithoutWaiting() throws Exception {
        givenTheEngineAnswers("addServers", 3);
        givenTheEngineAnswers("shareDirectory", null);
        givenTheEngineAnswers("kadBootstrap", null);

        whenTheCommandsRunWith(
                "--add-servers",
                "server.met",
                "--share",
                folder("music"),
                folder("films"),
                "--kad-bootstrap",
                "10.0.0.9:4672");

        thenTheExitCodeIs(Main.EXIT_OK);
        thenTheOutputContains(
                "Added 3 servers from server.met",
                "Sharing " + folder("music"),
                "Sharing " + folder("films"),
                "Joining Kad through 10.0.0.9:4672");
        thenTheEngineIsNeverAskedTo("connect");
    }

    @Test
    void shouldOnlyConnectToAServerAlone() throws Exception {
        givenTheEngineAnswers("connect", null);

        whenTheCommandsRunWith("--connect-server", SERVER);

        thenTheExitCodeIs(Main.EXIT_OK);
        thenTheCallsAre("connect[Optional[" + SERVER + "]]");
        thenThereIsNoOutput();
    }

    @Test
    void shouldEndTheRunWithTheReasonOfAFailedCommand() throws Exception {
        givenTheEngineFails("addServers", new IOException("no such file"));

        whenTheCommandsRunWith("--add-servers", "missing.met");

        thenTheExitCodeIs(Main.EXIT_FAILURE);
        thenTheErrorsContain("Failed: no such file");
    }

    @Test
    void shouldNameAFailureWithoutAMessageByItsKind() throws Exception {
        givenTheEngineFails("addServers", new IllegalStateException());

        whenTheCommandsRunWith("--add-servers", "missing.met");

        thenTheExitCodeIs(Main.EXIT_FAILURE);
        thenTheErrorsContain("Failed: IllegalStateException");
    }

    @Test
    void shouldReportWhatAnImportTookFileByFile() throws Exception {
        givenAnImportThatTook(
                new ImportReport.Item("server.met", ImportReport.Outcome.IMPORTED, "12 servers"),
                new ImportReport.Item("cryptkey.dat", ImportReport.Outcome.SKIPPED, "not asked"));

        whenTheCommandsRunWith("--import-emule", folder("emule"));

        thenTheExitCodeIs(Main.EXIT_OK);
        thenTheOutputContains("Importing from " + folder("emule"));
        thenTheOutputMatches("  imported +server\\.met +12 servers");
        thenTheOutputMatches("  skipped +cryptkey\\.dat +not asked");
    }

    @Test
    void shouldGreetAPeerLogInAndPrintTheResultsByTheirSources() throws Exception {
        givenAPeerThatAnswersTheHello(new PeerInfo(
                "10.0.0.2:4662", USER, Optional.of("peer"), "eMule", true, PeerInfo.Identification.VERIFIED));
        givenAConnectionThatSends(
                new EngineEvent.ServersChanged(1),
                new EngineEvent.ServerConnecting(server(SERVER, SERVER)),
                new EngineEvent.ServerMessage(server(SERVER, "Fake"), "welcome"),
                new EngineEvent.ServerConnected(server(SERVER, "Fake"), 1234, true, true));
        givenASearchThatSends(
                new SearchId(7),
                new EngineEvent.SearchResults(new SearchId(7), List.of(result("few.iso", 2))),
                new EngineEvent.SearchResults(new SearchId(8), List.of(result("someone else's.iso", 99))),
                new EngineEvent.ServerMessage(server(SERVER, "Fake"), "busy"),
                new EngineEvent.SearchResults(new SearchId(7), List.of(result("many.iso", 50))),
                new EngineEvent.SearchEnded(new SearchId(8), 1),
                new EngineEvent.SearchEnded(new SearchId(7), 2));

        whenTheCommandsRunWith("--hello", "10.0.0.2:4662", "--search", "iso");

        thenTheExitCodeIs(Main.EXIT_OK);
        thenTheOutputContains(
                "Hello answered by 10.0.0.2:4662: eMule, \"peer\", obfuscated, user hash "
                        + USER.toHex()
                        + ", identity verified",
                "Connecting to " + SERVER + "\n",
                "  > welcome",
                "Connected to Fake (" + SERVER + ") with LowID 1234, obfuscated",
                "Searching \"iso\" on the server",
                "  > busy",
                "ed2k://|file|many.iso|1000|",
                "2 results");
        thenTheOutputDoesNotContain("someone else's.iso", "Keeping the connection open");
        thenTheOutputShowsBefore("many.iso", "few.iso");
        thenTheCallsInclude("connect[Optional.empty]", "stopSearch[SearchId[value=7]]");
    }

    @ParameterizedTest
    @CsvSource({"FAILED, NOT verified", "NOT_SUPPORTED, not offered", "PENDING, not proven in time"})
    void shouldTellHowThePeerProvedItsIdentity(PeerInfo.Identification outcome, String identity) throws Exception {
        givenTheEngineFails("connect", new IOException("no server"));
        givenAPeerThatAnswersTheHello(new PeerInfo("10.0.0.2:4662", USER, Optional.empty(), "aMule", false, outcome));

        whenTheCommandsRunWith("--hello", "10.0.0.2:4662", "--search", "iso");

        thenTheExitCodeIs(Main.EXIT_FAILURE);
        thenTheOutputContains(
                "Hello answered by 10.0.0.2:4662: aMule, user hash " + USER.toHex() + ", identity " + identity);
        thenTheOutputDoesNotContain("\"", "obfuscated");
        thenTheErrorsContain("Failed: no server");
    }

    @Test
    void shouldKeepTheConnectionOfAHelloAloneOpenForAWhile() throws Exception {
        givenAPeerThatAnswersTheHello(new PeerInfo(
                "10.0.0.2:4662", USER, Optional.empty(), "aMule", false, PeerInfo.Identification.VERIFIED));
        givenTheCommandsRunningWith("--hello", "10.0.0.2:4662");
        givenTheOutputShowsWithin("Keeping the connection open", Duration.ofSeconds(10));

        whenTheRunIsInterrupted();

        thenTheOutputContains("Keeping the connection open for " + NetworkCommands.HELLO_HOLD.toSeconds());
        thenTheRunEndedWaitingForThePeer();
    }

    @Test
    void shouldEndTheRunOnARefusedLogin() throws Exception {
        givenAConnectionThatSends(
                new EngineEvent.ServerConnecting(server(SERVER, "Fake")),
                new EngineEvent.ServerDisconnected(Optional.empty(), "refused"));

        whenTheCommandsRunWith("--connect-server", SERVER, "--search", "iso");

        thenTheExitCodeIs(Main.EXIT_FAILURE);
        thenTheOutputContains("Connecting to Fake (" + SERVER + ")");
        thenTheErrorsContain("Not connected: refused");
        thenTheEngineIsNeverAskedTo("search");
    }

    @Test
    void shouldEndAGlobalSearchWhenTheServerGoes() throws Exception {
        givenAServerThatTakesUsWithAHighId();
        givenASearchThatSends(
                new SearchId(3),
                new EngineEvent.SearchResults(new SearchId(3), List.of(result("kept.iso", 4))),
                new EngineEvent.ServersChanged(2),
                new EngineEvent.ServerDisconnected(Optional.empty(), "connection lost"));

        whenTheCommandsRunWith("--connect-server", SERVER, "--search", "iso", "--global");

        thenTheExitCodeIs(Main.EXIT_OK);
        thenTheOutputContains(
                "Connected to " + SERVER + " with HighID 99\n", "on every known server", "kept.iso", "1 results");
        thenTheErrorsContain("Disconnected: connection lost");
        thenACallContains("scope=GLOBAL");
    }

    @Test
    void shouldEndAServerSearchOnceItsResultsStop() throws Exception {
        givenAServerThatTakesUsWithAHighId();
        givenASearchThatSends(
                new SearchId(4), new EngineEvent.SearchResults(new SearchId(4), List.of(result("only.iso", 1))));

        whenTheCommandsRunWith("--connect-server", SERVER, "--search", "iso");

        thenTheExitCodeIs(Main.EXIT_OK);
        thenTheRunTookAtLeastButLessThan(NetworkCommands.SEARCH_QUIET, NetworkCommands.SEARCH_WAIT);
        thenTheOutputContains("only.iso", "1 results");
    }

    @Test
    void shouldNeedKadRunningForAKadSearch() throws Exception {
        givenTheEngineAnswers("kad", Optional.empty());

        whenTheCommandsRunWith("--search", "iso", "--kad");

        thenTheExitCodeIs(Main.EXIT_FAILURE);
        thenTheErrorsContain("Kad is not running");
        thenTheEngineIsNeverAskedTo("search");
    }

    @Test
    void shouldWaitForEnoughKadContactsThenSearchAndDownload() throws Exception {
        givenKadGoesThrough(
                new KadInfo(false, 3, 0, true, "", "", 4672),
                new KadInfo(true, 10, 2, true, "", "", 4672),
                new KadInfo(true, 40, NetworkCommands.KAD_CONTACTS, false, "", "", 4672));
        givenASearchThatSends(new SearchId(5), new EngineEvent.SearchEnded(new SearchId(5), 0));
        givenEachDownloadCompletesAtOnce();

        whenTheCommandsRunWith("--search", "iso", "--kad", "--download", fileLink("found.iso", 4096, "AB"));

        thenTheExitCodeIs(Main.EXIT_OK);
        thenTheOutputContains(
                "Kad: 3 contacts, 0 verified, firewalled",
                "Kad: 40 contacts, " + NetworkCommands.KAD_CONTACTS + " verified, connected, reachable",
                "Searching \"iso\" on Kad",
                "0 results",
                "Downloading found.iso (4.0 KiB, 1 source)",
                "Finished found.iso");
        thenTheKadStateIsNotPrintedOnEveryCheck("Kad: 10 contacts");
    }

    @Test
    void shouldEndAKadSearchAloneWithItsResults() throws Exception {
        givenTheEngineAnswers(
                "kad", Optional.of(new KadInfo(true, 50, NetworkCommands.KAD_CONTACTS + 1, false, "", "", 4672)));
        givenASearchThatSends(
                new SearchId(9),
                new EngineEvent.SearchResults(new SearchId(9), List.of(result("kad.iso", 7))),
                new EngineEvent.SearchEnded(new SearchId(9), 1));

        whenTheCommandsRunWith("--search", "iso", "--kad");

        thenTheExitCodeIs(Main.EXIT_OK);
        thenTheOutputContains("Searching \"iso\" on Kad", "kad.iso", "1 results");
        thenACallContains("scope=KAD");
        thenTheEngineIsNeverAskedTo("connect");
    }

    @Test
    void shouldWaitOnlyForTheLoginWithAServerAndDownloads() throws Exception {
        givenAServerThatTakesUsWithAHighId();
        givenEachDownloadCompletesAtOnce();

        whenTheCommandsRunWith("--connect-server", SERVER, "--download", fileLink("direct.iso", 2048, "FA"));

        thenTheExitCodeIs(Main.EXIT_OK);
        thenTheOutputContains("Connected to " + SERVER, "Finished direct.iso");
        thenTheEngineIsNeverAskedTo("search");
    }

    @Test
    void shouldReportEachDownloadOutcomeAndFailWhenOneFails() throws Exception {
        givenDownloadsWhereTheBadOneFailsAmidOtherEvents();

        whenTheCommandsRunWith("--download", GOOD.toUri(), BAD.toUri());

        thenTheExitCodeIs(Main.EXIT_FAILURE);
        thenTheOutputContains(
                "Downloading good.bin (1000 B, 1 source)",
                "Downloading bad.bin (3.0 MiB, 3 sources)",
                "Connected to " + SERVER,
                "Finished good.bin: good.bin");
        thenTheOutputDoesNotContain("other.bin");
        thenTheErrorsContain("Failed bad.bin: disk full", "Disconnected from the server: lost");
        thenTheErrorsDoNotContain("not ours");
    }

    @Test
    void shouldSearchThenDownloadWhatWasAsked() throws Exception {
        givenAServerThatTakesUsWithAHighId();
        givenASearchThatSends(new SearchId(6), new EngineEvent.SearchEnded(new SearchId(6), 0));
        givenEachDownloadCompletesAtOnce();

        whenTheCommandsRunWith(
                "--connect-server", SERVER, "--search", "iso", "--download", fileLink("wanted.iso", 2048, "DD"));

        thenTheExitCodeIs(Main.EXIT_OK);
        thenTheOutputContains("0 results", "Finished wanted.iso");
    }

    @Test
    void shouldNeedNoServerForDownloadsAlone() throws Exception {
        givenEachDownloadCompletesAtOnce();

        whenTheCommandsRunWith("--download", fileLink("alone.iso", 2048, "EE"));

        thenTheExitCodeIs(Main.EXIT_OK);
        thenTheOutputContains("Finished alone.iso");
        thenTheEngineIsNeverAskedTo("connect");
    }

    @ParameterizedTest
    @MethodSource("sizes")
    void shouldWriteSizesInBinaryUnits(long bytes, String expected) {
        whenTheSizeIsWritten(bytes);

        thenTheTextIs(expected);
    }

    @Test
    void shouldWriteTheProgressOfAnEmptyDownloadAsComplete() {
        givenTheDownload(new DownloadInfo(
                Hash16.parseHex("AB".repeat(16)),
                "empty.txt",
                0,
                0,
                DownloadInfo.Status.DOWNLOADING,
                DownloadPriority.NORMAL,
                0,
                2,
                1,
                2048,
                PartMap.EMPTY));

        whenTheProgressIsWritten();

        thenTheTextIs(" 100.0%       2.0 KiB/s  1/2 sources  empty.txt");
    }

    @Test
    void shouldWriteTheProgressOfADownloadAQuarterDone() {
        givenTheDownload(new DownloadInfo(
                Hash16.parseHex("AB".repeat(16)),
                "a.iso",
                4000,
                1000,
                DownloadInfo.Status.DOWNLOADING,
                DownloadPriority.NORMAL,
                0,
                0,
                0,
                0,
                PartMap.EMPTY));

        whenTheProgressIsWritten();

        thenTheTextStartsWith("  25.0%");
        thenTheTextEndsWith("0/0 sources  a.iso");
    }

    @Test
    void shouldWriteTheKadState() {
        givenTheKadState(new KadInfo(true, 5, 4, true, "", "", 4672));

        whenTheKadLineIsWritten();

        thenTheKadLineIs("Kad: 5 contacts, 4 verified, connected, firewalled");
    }

    @Test
    void theKadLineTellsTheTestedUdpStateAndTheBuddy() {
        givenKadFirewalledOnBothPortsWithABuddy();

        whenTheKadLineIsWritten();

        thenTheKadLineIs("Kad: 5 contacts, 4 verified, connected, firewalled, UDP firewalled, with a buddy");
    }

    @Test
    void shouldIgnoreTheEndOfTheEventStream() {
        whenTheEventStreamFailsAndCompletes();

        thenThereIsNoOutput();
        thenThereAreNoErrors();
    }

    static Stream<Arguments> sizes() {
        return Stream.of(
                arguments(0L, "0 B"),
                arguments(1023L, "1023 B"),
                arguments(1536L, "1.5 KiB"),
                arguments(3L << 40, "3.0 TiB"),
                // No unit past TiB.
                arguments(2048L << 40, "2048.0 TiB"));
    }

    private void givenTheEngineAnswers(String method, Object value) {
        this.fake.answerWith(method, value);
    }

    private void givenTheEngineFails(String method, Throwable failure) {
        this.fake.answer(method, args -> CompletableFuture.failedFuture(failure));
    }

    private void givenAnImportThatTook(ImportReport.Item... items) {
        this.fake.answerWith("importFromEmule", new ImportReport(false, List.of(items)));
    }

    private void givenAPeerThatAnswersTheHello(PeerInfo peer) {
        this.fake.answerWith("hello", peer);
    }

    private void givenAConnectionThatSends(EngineEvent... events) {
        this.fake.answer("connect", args -> {
            submit(events);
            return CompletableFuture.completedFuture(null);
        });
    }

    // The login of every search test: a server that takes us with a HighID.
    private void givenAServerThatTakesUsWithAHighId() {
        givenAConnectionThatSends(new EngineEvent.ServerConnected(server(SERVER, SERVER), 99, false, false));
    }

    // The commands stop each search they started.
    private void givenASearchThatSends(SearchId id, EngineEvent... events) {
        this.fake.answer("search", args -> {
            submit(events);
            return CompletableFuture.completedFuture(id);
        });
        this.fake.answerWith("stopSearch", null);
    }

    // Each state answers one check, and the last one stays.
    private void givenKadGoesThrough(KadInfo... states) {
        Queue<KadInfo> left = new ArrayDeque<>(List.of(states));
        this.fake.answer(
                "kad",
                args -> CompletableFuture.completedFuture(Optional.of(left.size() > 1 ? left.poll() : left.peek())));
    }

    private void givenEachDownloadCompletesAtOnce() {
        this.fake.answer("addDownload", args -> {
            DownloadInfo info = download((Ed2kLink.FileLink) args[0], 1);
            this.fake.events.submit(new EngineEvent.DownloadCompleted(info, Path.of(info.name())));
            return CompletableFuture.completedFuture(info);
        });
    }

    private void givenDownloadsWhereTheBadOneFailsAmidOtherEvents() {
        DownloadInfo other = download(Ed2kLink.FileLink.of("other.bin", 1, Hash16.parseHex("CC".repeat(16))), 1);
        this.fake.answer("addDownload", args -> {
            Ed2kLink.FileLink link = (Ed2kLink.FileLink) args[0];
            if (link.equals(BAD)) {
                submit(
                        new EngineEvent.DownloadProgress(other),
                        new EngineEvent.DownloadCompleted(other, Path.of("other.bin")),
                        new EngineEvent.DownloadFailed(other, "not ours"),
                        new EngineEvent.DownloadProgress(download(GOOD, 1)),
                        new EngineEvent.ServerConnected(server(SERVER, SERVER), 5, true, false),
                        new EngineEvent.ServerDisconnected(Optional.empty(), "lost"),
                        new EngineEvent.DownloadCompleted(download(GOOD, 1), Path.of("good.bin")),
                        new EngineEvent.DownloadFailed(download(BAD, 3), "disk full"));
            }
            return CompletableFuture.completedFuture(download(link, link.equals(BAD) ? 3 : 1));
        });
    }

    private void givenTheCommandsRunningWith(String... args) throws LaunchOptions.UsageException {
        NetworkCommands commands = commands();
        LaunchOptions.NetworkTasks tasks = LaunchOptions.parse(args).network();
        this.run = Thread.ofVirtual().start(() -> {
            try {
                commands.run(tasks);
            } catch (InterruptedException | RuntimeException e) {
                this.ended.set(e);
            }
        });
    }

    private void givenTheOutputShowsWithin(String text, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!out().contains(text) && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
    }

    private void givenTheDownload(DownloadInfo info) {
        this.download = info;
    }

    private void givenTheKadState(KadInfo state) {
        this.kadState = state;
    }

    private void givenKadFirewalledOnBothPortsWithABuddy() {
        this.kadState = new KadInfo(true, 5, 4, true, "", "", 4672, true, true, KadInfo.Buddy.CONNECTED);
    }

    private void whenTheCommandsRunWith(String... args) throws Exception {
        long start = System.nanoTime();
        this.exitCode = run(args);
        this.took = Duration.ofNanos(System.nanoTime() - start);
    }

    private void whenTheRunIsInterrupted() throws InterruptedException {
        this.run.interrupt();
        this.run.join(Duration.ofSeconds(10));
    }

    private void whenTheSizeIsWritten(long bytes) {
        this.text = NetworkCommands.size(bytes);
    }

    private void whenTheProgressIsWritten() {
        this.text = NetworkCommands.progress(this.download);
    }

    private void whenTheKadLineIsWritten() {
        this.kadLine = NetworkCommands.kadLine(this.kadState);
    }

    private void whenTheEventStreamFailsAndCompletes() {
        NetworkCommands commands = commands();
        commands.onError(new IOException("closed"));
        commands.onComplete();
    }

    private void thenTheExitCodeIs(int expected) {
        assertThat(this.exitCode).isEqualTo(expected);
    }

    private void thenTheOutputContains(String... texts) {
        assertThat(out()).contains(texts);
    }

    private void thenTheOutputDoesNotContain(String... texts) {
        assertThat(out()).doesNotContain(texts);
    }

    private void thenTheOutputMatches(String pattern) {
        assertThat(out()).containsPattern(pattern);
    }

    private void thenTheOutputShowsBefore(String first, String second) {
        String printed = out();
        assertThat(printed.indexOf(first)).isLessThan(printed.indexOf(second));
    }

    private void thenThereIsNoOutput() {
        assertThat(out()).isEmpty();
    }

    private void thenTheErrorsContain(String... texts) {
        assertThat(err()).contains(texts);
    }

    private void thenTheErrorsDoNotContain(String text) {
        assertThat(err()).doesNotContain(text);
    }

    private void thenThereAreNoErrors() {
        assertThat(err()).isEmpty();
    }

    private void thenTheCallsAre(String... calls) {
        assertThat(this.fake.calls).containsExactly(calls);
    }

    private void thenTheCallsInclude(String... calls) {
        assertThat(this.fake.calls).contains(calls);
    }

    private void thenACallContains(String part) {
        assertThat(this.fake.calls).anyMatch(call -> call.contains(part));
    }

    private void thenTheEngineIsNeverAskedTo(String method) {
        assertThat(this.fake.calls).noneMatch(call -> call.startsWith(method));
    }

    private void thenTheKadStateIsNotPrintedOnEveryCheck(String skippedState) {
        assertThat(out())
                .as("the state is printed now and then, not on every check")
                .doesNotContain(skippedState);
    }

    private void thenTheRunTookAtLeastButLessThan(Duration least, Duration limit) {
        assertThat(this.took).isGreaterThanOrEqualTo(least).isLessThan(limit);
    }

    private void thenTheRunEndedWaitingForThePeer() {
        assertThat(this.ended.get()).as("waiting for the peer").isInstanceOf(InterruptedException.class);
    }

    private void thenTheTextIs(String expected) {
        assertThat(this.text).isEqualTo(expected);
    }

    private void thenTheTextStartsWith(String start) {
        assertThat(this.text).startsWith(start);
    }

    private void thenTheTextEndsWith(String end) {
        assertThat(this.text).endsWith(end);
    }

    private void thenTheKadLineIs(String expected) {
        assertThat(this.kadLine).isEqualTo(expected);
    }

    private void submit(EngineEvent... events) {
        for (EngineEvent event : events) {
            this.fake.events.submit(event);
        }
    }

    private String folder(String name) {
        return this.dir.resolve(name).toString();
    }

    private int run(String... args) throws Exception {
        return commands().run(LaunchOptions.parse(args).network());
    }

    private NetworkCommands commands() {
        return new NetworkCommands(
                this.fake.engine(), new PrintStream(this.out, true, UTF_8), new PrintStream(this.err, true, UTF_8));
    }

    private String out() {
        return this.out.toString(UTF_8).replace(System.lineSeparator(), "\n");
    }

    private String err() {
        return this.err.toString(UTF_8).replace(System.lineSeparator(), "\n");
    }

    private static String fileLink(String name, long size, String hashByte) {
        return Ed2kLink.FileLink.of(name, size, Hash16.parseHex(hashByte.repeat(16)))
                .toUri();
    }

    private static ServerInfo server(String address, String name) {
        return new ServerInfo(address, name, "", 0, 0, 0, 0, ServerInfo.Priority.NORMAL, 0, 0, 0, 0, "", false, false);
    }

    private static SearchResult result(String name, int sources) {
        String hex = String.format(Locale.ROOT, "%08X", name.hashCode()).repeat(4);
        return new SearchResult(Hash16.parseHex(hex), name, 1000, sources, 0, Optional.empty());
    }

    private static DownloadInfo download(Ed2kLink.FileLink link, int sources) {
        return new DownloadInfo(
                link.hash(),
                link.name(),
                link.size(),
                0,
                DownloadInfo.Status.DOWNLOADING,
                DownloadPriority.NORMAL,
                0,
                sources,
                0,
                0,
                PartMap.EMPTY);
    }
}
