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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
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

    @TempDir
    Path dir;

    private final FakeEngine fake = new FakeEngine();
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();
    private KadInfo kadState;
    private String kadLine;

    @AfterEach
    void stop() {
        this.fake.events.close();
    }

    @Test
    void addsServersSharesAndJoinsKadWithoutWaiting() throws Exception {
        this.fake.answerWith("addServers", 3);
        this.fake.answerWith("shareDirectory", null);
        this.fake.answerWith("kadBootstrap", null);
        Path music = this.dir.resolve("music");
        Path films = this.dir.resolve("films");

        assertThat(run(
                        "--add-servers",
                        "server.met",
                        "--share",
                        music.toString(),
                        films.toString(),
                        "--kad-bootstrap",
                        "10.0.0.9:4672"))
                .isEqualTo(Main.EXIT_OK);

        assertThat(out())
                .contains(
                        "Added 3 servers from server.met",
                        "Sharing " + music,
                        "Sharing " + films,
                        "Joining Kad through 10.0.0.9:4672");
        assertThat(this.fake.calls).noneMatch(call -> call.startsWith("connect"));
    }

    @Test
    void aServerAloneIsOnlyConnectedTo() throws Exception {
        this.fake.answerWith("connect", null);

        assertThat(run("--connect-server", SERVER)).isEqualTo(Main.EXIT_OK);

        assertThat(this.fake.calls).containsExactly("connect[Optional[" + SERVER + "]]");
        assertThat(out()).isEmpty();
    }

    @Test
    void aFailedCommandEndsTheRunWithItsReason() throws Exception {
        this.fake.answer("addServers", args -> CompletableFuture.failedFuture(new IOException("no such file")));
        assertThat(run("--add-servers", "missing.met")).isEqualTo(Main.EXIT_FAILURE);
        assertThat(err()).contains("Failed: no such file");

        this.fake.answer("addServers", args -> CompletableFuture.failedFuture(new IllegalStateException()));
        assertThat(run("--add-servers", "missing.met")).isEqualTo(Main.EXIT_FAILURE);
        assertThat(err())
                .as("a failure without a message is named by its kind")
                .contains("Failed: IllegalStateException");
    }

    @Test
    void reportsWhatAnImportTookFileByFile() throws Exception {
        this.fake.answerWith(
                "importFromEmule",
                new ImportReport(
                        false,
                        List.of(
                                new ImportReport.Item("server.met", ImportReport.Outcome.IMPORTED, "12 servers"),
                                new ImportReport.Item("cryptkey.dat", ImportReport.Outcome.SKIPPED, "not asked"))));
        Path emule = this.dir.resolve("emule");

        assertThat(run("--import-emule", emule.toString())).isEqualTo(Main.EXIT_OK);

        assertThat(out())
                .contains("Importing from " + emule)
                .containsPattern("  imported +server\\.met +12 servers")
                .containsPattern("  skipped +cryptkey\\.dat +not asked");
    }

    @Test
    void greetsAPeerLogsInAndPrintsTheResultsByTheirSources() throws Exception {
        this.fake.answerWith(
                "hello",
                new PeerInfo(
                        "10.0.0.2:4662", USER, Optional.of("peer"), "eMule", true, PeerInfo.Identification.VERIFIED));
        this.fake.answer("connect", args -> {
            this.fake.events.submit(new EngineEvent.ServersChanged(1));
            this.fake.events.submit(new EngineEvent.ServerConnecting(server(SERVER, SERVER)));
            this.fake.events.submit(new EngineEvent.ServerMessage(server(SERVER, "Fake"), "welcome"));
            this.fake.events.submit(new EngineEvent.ServerConnected(server(SERVER, "Fake"), 1234, true, true));
            return CompletableFuture.completedFuture(null);
        });
        this.fake.answer("search", args -> {
            SearchId id = new SearchId(7);
            this.fake.events.submit(new EngineEvent.SearchResults(id, List.of(result("few.iso", 2))));
            this.fake.events.submit(
                    new EngineEvent.SearchResults(new SearchId(8), List.of(result("someone else's.iso", 99))));
            this.fake.events.submit(new EngineEvent.ServerMessage(server(SERVER, "Fake"), "busy"));
            this.fake.events.submit(new EngineEvent.SearchResults(id, List.of(result("many.iso", 50))));
            this.fake.events.submit(new EngineEvent.SearchEnded(new SearchId(8), 1));
            this.fake.events.submit(new EngineEvent.SearchEnded(id, 2));
            return CompletableFuture.completedFuture(id);
        });
        this.fake.answerWith("stopSearch", null);

        assertThat(run("--hello", "10.0.0.2:4662", "--search", "iso")).isEqualTo(Main.EXIT_OK);

        String printed = out();
        assertThat(printed)
                .contains(
                        "Hello answered by 10.0.0.2:4662: eMule, \"peer\", obfuscated, user hash "
                                + USER.toHex()
                                + ", identity verified",
                        "Connecting to " + SERVER + "\n",
                        "  > welcome",
                        "Connected to Fake (" + SERVER + ") with LowID 1234, obfuscated",
                        "Searching \"iso\" on the server",
                        "  > busy",
                        "ed2k://|file|many.iso|1000|",
                        "2 results")
                .doesNotContain("someone else's.iso", "Keeping the connection open");
        assertThat(printed.indexOf("many.iso")).isLessThan(printed.indexOf("few.iso"));
        assertThat(this.fake.calls).contains("connect[Optional.empty]", "stopSearch[SearchId[value=7]]");
    }

    @Test
    void tellsHowThePeerProvedItsIdentity() throws Exception {
        this.fake.answer("connect", args -> CompletableFuture.failedFuture(new IOException("no server")));
        for (PeerInfo.Identification outcome : List.of(
                PeerInfo.Identification.FAILED,
                PeerInfo.Identification.NOT_SUPPORTED,
                PeerInfo.Identification.PENDING)) {
            this.fake.answerWith(
                    "hello", new PeerInfo("10.0.0.2:4662", USER, Optional.empty(), "aMule", false, outcome));

            assertThat(run("--hello", "10.0.0.2:4662", "--search", "iso")).isEqualTo(Main.EXIT_FAILURE);
        }

        assertThat(out())
                .contains(
                        "Hello answered by 10.0.0.2:4662: aMule, user hash " + USER.toHex() + ", identity NOT verified",
                        "identity not offered",
                        "identity not proven in time")
                .doesNotContain("\"", "obfuscated");
        assertThat(err()).contains("Failed: no server");
    }

    @Test
    void aHelloAloneKeepsTheConnectionOpenForAWhile() throws Exception {
        this.fake.answerWith(
                "hello",
                new PeerInfo(
                        "10.0.0.2:4662", USER, Optional.empty(), "aMule", false, PeerInfo.Identification.VERIFIED));
        NetworkCommands commands = commands();
        LaunchOptions.NetworkTasks tasks =
                LaunchOptions.parse("--hello", "10.0.0.2:4662").network();
        AtomicReference<Throwable> ended = new AtomicReference<>();
        Thread run = Thread.ofVirtual().start(() -> {
            try {
                commands.run(tasks);
            } catch (InterruptedException | RuntimeException e) {
                ended.set(e);
            }
        });

        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (!out().contains("Keeping the connection open") && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        run.interrupt();
        run.join(Duration.ofSeconds(10));

        assertThat(out()).contains("Keeping the connection open for " + NetworkCommands.HELLO_HOLD.toSeconds());
        assertThat(ended.get()).as("waiting for the peer").isInstanceOf(InterruptedException.class);
    }

    @Test
    void aRefusedLoginEndsTheRun() throws Exception {
        this.fake.answer("connect", args -> {
            this.fake.events.submit(new EngineEvent.ServerConnecting(server(SERVER, "Fake")));
            this.fake.events.submit(new EngineEvent.ServerDisconnected(Optional.empty(), "refused"));
            return CompletableFuture.completedFuture(null);
        });

        assertThat(run("--connect-server", SERVER, "--search", "iso")).isEqualTo(Main.EXIT_FAILURE);

        assertThat(out()).contains("Connecting to Fake (" + SERVER + ")");
        assertThat(err()).contains("Not connected: refused");
        assertThat(this.fake.calls).noneMatch(call -> call.startsWith("search"));
    }

    @Test
    void aGlobalSearchEndsWhenTheServerGoes() throws Exception {
        connectsWithHighId();
        this.fake.answer("search", args -> {
            SearchId id = new SearchId(3);
            this.fake.events.submit(new EngineEvent.SearchResults(id, List.of(result("kept.iso", 4))));
            this.fake.events.submit(new EngineEvent.ServersChanged(2));
            this.fake.events.submit(new EngineEvent.ServerDisconnected(Optional.empty(), "connection lost"));
            return CompletableFuture.completedFuture(id);
        });
        this.fake.answerWith("stopSearch", null);

        assertThat(run("--connect-server", SERVER, "--search", "iso", "--global"))
                .isEqualTo(Main.EXIT_OK);

        assertThat(out())
                .contains(
                        "Connected to " + SERVER + " with HighID 99\n",
                        "on every known server",
                        "kept.iso",
                        "1 results");
        assertThat(err()).contains("Disconnected: connection lost");
        assertThat(this.fake.calls).anyMatch(call -> call.contains("scope=GLOBAL"));
    }

    @Test
    void aServerSearchEndsOnceItsResultsStop() throws Exception {
        connectsWithHighId();
        this.fake.answer("search", args -> {
            this.fake.events.submit(new EngineEvent.SearchResults(new SearchId(4), List.of(result("only.iso", 1))));
            return CompletableFuture.completedFuture(new SearchId(4));
        });
        this.fake.answerWith("stopSearch", null);
        long start = System.nanoTime();

        assertThat(run("--connect-server", SERVER, "--search", "iso")).isEqualTo(Main.EXIT_OK);

        assertThat(Duration.ofNanos(System.nanoTime() - start))
                .isGreaterThanOrEqualTo(NetworkCommands.SEARCH_QUIET)
                .isLessThan(NetworkCommands.SEARCH_WAIT);
        assertThat(out()).contains("only.iso", "1 results");
    }

    @Test
    void aKadSearchNeedsKadRunning() throws Exception {
        this.fake.answerWith("kad", Optional.empty());

        assertThat(run("--search", "iso", "--kad")).isEqualTo(Main.EXIT_FAILURE);

        assertThat(err()).contains("Kad is not running");
        assertThat(this.fake.calls).noneMatch(call -> call.startsWith("search"));
    }

    @Test
    void aKadSearchWaitsForEnoughContactsThenDownloads() throws Exception {
        Queue<KadInfo> states = new ArrayDeque<>(List.of(
                new KadInfo(false, 3, 0, true, "", "", 4672),
                new KadInfo(true, 10, 2, true, "", "", 4672),
                new KadInfo(true, 40, NetworkCommands.KAD_CONTACTS, false, "", "", 4672)));
        this.fake.answer(
                "kad",
                args -> CompletableFuture.completedFuture(
                        Optional.of(states.size() > 1 ? states.poll() : states.peek())));
        this.fake.answer("search", args -> {
            this.fake.events.submit(new EngineEvent.SearchEnded(new SearchId(5), 0));
            return CompletableFuture.completedFuture(new SearchId(5));
        });
        this.fake.answerWith("stopSearch", null);
        Ed2kLink.FileLink link = Ed2kLink.FileLink.of("found.iso", 4096, Hash16.parseHex("AB".repeat(16)));
        completesDownloads();

        assertThat(run("--search", "iso", "--kad", "--download", link.toUri())).isEqualTo(Main.EXIT_OK);

        assertThat(out())
                .contains(
                        "Kad: 3 contacts, 0 verified, firewalled",
                        "Kad: 40 contacts, " + NetworkCommands.KAD_CONTACTS + " verified, connected, reachable",
                        "Searching \"iso\" on Kad",
                        "0 results",
                        "Downloading found.iso (4.0 KiB, 1 source)",
                        "Finished found.iso")
                .as("the state is printed now and then, not on every check")
                .doesNotContain("Kad: 10 contacts");
    }

    @Test
    void aKadSearchAloneEndsWithItsResults() throws Exception {
        this.fake.answerWith(
                "kad", Optional.of(new KadInfo(true, 50, NetworkCommands.KAD_CONTACTS + 1, false, "", "", 4672)));
        this.fake.answer("search", args -> {
            this.fake.events.submit(new EngineEvent.SearchResults(new SearchId(9), List.of(result("kad.iso", 7))));
            this.fake.events.submit(new EngineEvent.SearchEnded(new SearchId(9), 1));
            return CompletableFuture.completedFuture(new SearchId(9));
        });
        this.fake.answerWith("stopSearch", null);

        assertThat(run("--search", "iso", "--kad")).isEqualTo(Main.EXIT_OK);

        assertThat(out()).contains("Searching \"iso\" on Kad", "kad.iso", "1 results");
        assertThat(this.fake.calls)
                .anyMatch(call -> call.contains("scope=KAD"))
                .noneMatch(call -> call.startsWith("connect"));
    }

    @Test
    void aServerAndDownloadsWaitForTheLoginOnly() throws Exception {
        connectsWithHighId();
        completesDownloads();
        Ed2kLink.FileLink link = Ed2kLink.FileLink.of("direct.iso", 2048, Hash16.parseHex("FA".repeat(16)));

        assertThat(run("--connect-server", SERVER, "--download", link.toUri())).isEqualTo(Main.EXIT_OK);

        assertThat(out()).contains("Connected to " + SERVER, "Finished direct.iso");
        assertThat(this.fake.calls).noneMatch(call -> call.startsWith("search"));
    }

    @Test
    void downloadsReportEachOutcomeAndFailWhenOneFails() throws Exception {
        Ed2kLink.FileLink good = Ed2kLink.FileLink.of("good.bin", 1000, Hash16.parseHex("AA".repeat(16)));
        Ed2kLink.FileLink bad = Ed2kLink.FileLink.of("bad.bin", 3L << 20, Hash16.parseHex("BB".repeat(16)));
        DownloadInfo other = download(Ed2kLink.FileLink.of("other.bin", 1, Hash16.parseHex("CC".repeat(16))), 1);
        this.fake.answer("addDownload", args -> {
            Ed2kLink.FileLink link = (Ed2kLink.FileLink) args[0];
            if (link.equals(bad)) {
                this.fake.events.submit(new EngineEvent.DownloadProgress(other));
                this.fake.events.submit(new EngineEvent.DownloadCompleted(other, Path.of("other.bin")));
                this.fake.events.submit(new EngineEvent.DownloadFailed(other, "not ours"));
                this.fake.events.submit(new EngineEvent.DownloadProgress(download(good, 1)));
                this.fake.events.submit(new EngineEvent.ServerConnected(server(SERVER, SERVER), 5, true, false));
                this.fake.events.submit(new EngineEvent.ServerDisconnected(Optional.empty(), "lost"));
                this.fake.events.submit(new EngineEvent.DownloadCompleted(download(good, 1), Path.of("good.bin")));
                this.fake.events.submit(new EngineEvent.DownloadFailed(download(bad, 3), "disk full"));
            }
            return CompletableFuture.completedFuture(download(link, link.equals(bad) ? 3 : 1));
        });

        assertThat(run("--download", good.toUri(), bad.toUri())).isEqualTo(Main.EXIT_FAILURE);

        assertThat(out())
                .contains(
                        "Downloading good.bin (1000 B, 1 source)",
                        "Downloading bad.bin (3.0 MiB, 3 sources)",
                        "Connected to " + SERVER,
                        "Finished good.bin: good.bin")
                .doesNotContain("other.bin");
        assertThat(err())
                .contains("Failed bad.bin: disk full", "Disconnected from the server: lost")
                .doesNotContain("not ours");
    }

    @Test
    void searchesThenDownloadsWhatWasAsked() throws Exception {
        connectsWithHighId();
        this.fake.answer("search", args -> {
            this.fake.events.submit(new EngineEvent.SearchEnded(new SearchId(6), 0));
            return CompletableFuture.completedFuture(new SearchId(6));
        });
        this.fake.answerWith("stopSearch", null);
        completesDownloads();
        Ed2kLink.FileLink link = Ed2kLink.FileLink.of("wanted.iso", 2048, Hash16.parseHex("DD".repeat(16)));

        assertThat(run("--connect-server", SERVER, "--search", "iso", "--download", link.toUri()))
                .isEqualTo(Main.EXIT_OK);

        assertThat(out()).contains("0 results", "Finished wanted.iso");
    }

    @Test
    void downloadsAloneNeedNoServer() throws Exception {
        completesDownloads();
        Ed2kLink.FileLink link = Ed2kLink.FileLink.of("alone.iso", 2048, Hash16.parseHex("EE".repeat(16)));

        assertThat(run("--download", link.toUri())).isEqualTo(Main.EXIT_OK);

        assertThat(out()).contains("Finished alone.iso");
        assertThat(this.fake.calls).noneMatch(call -> call.startsWith("connect"));
    }

    @Test
    void formatsSizesProgressAndKadState() {
        assertThat(NetworkCommands.size(0)).isEqualTo("0 B");
        assertThat(NetworkCommands.size(1023)).isEqualTo("1023 B");
        assertThat(NetworkCommands.size(1536)).isEqualTo("1.5 KiB");
        assertThat(NetworkCommands.size(3L << 40)).isEqualTo("3.0 TiB");
        assertThat(NetworkCommands.size(2048L << 40)).as("no unit past TiB").isEqualTo("2048.0 TiB");

        DownloadInfo empty = new DownloadInfo(
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
                PartMap.EMPTY);
        assertThat(NetworkCommands.progress(empty)).isEqualTo(" 100.0%       2.0 KiB/s  1/2 sources  empty.txt");
        DownloadInfo quarter = new DownloadInfo(
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
                PartMap.EMPTY);
        assertThat(NetworkCommands.progress(quarter)).startsWith("  25.0%").endsWith("0/0 sources  a.iso");

        assertThat(NetworkCommands.kadLine(new KadInfo(true, 5, 4, true, "", "", 4672)))
                .isEqualTo("Kad: 5 contacts, 4 verified, connected, firewalled");
    }

    @Test
    void theKadLineTellsTheTestedUdpStateAndTheBuddy() {
        givenKadFirewalledOnBothPortsWithABuddy();

        whenTheKadLineIsWritten();

        thenTheKadLineIs("Kad: 5 contacts, 4 verified, connected, firewalled, UDP firewalled, with a buddy");
    }

    private void givenKadFirewalledOnBothPortsWithABuddy() {
        this.kadState = new KadInfo(true, 5, 4, true, "", "", 4672, true, true, KadInfo.Buddy.CONNECTED);
    }

    private void whenTheKadLineIsWritten() {
        this.kadLine = NetworkCommands.kadLine(this.kadState);
    }

    private void thenTheKadLineIs(String expected) {
        assertThat(this.kadLine).isEqualTo(expected);
    }

    @Test
    void ignoresTheEndOfTheEventStream() {
        NetworkCommands commands = commands();
        commands.onError(new IOException("closed"));
        commands.onComplete();
        assertThat(out()).isEmpty();
        assertThat(err()).isEmpty();
    }

    // The login of every search test: a server that takes us with a HighID.
    private void connectsWithHighId() {
        this.fake.answer("connect", args -> {
            this.fake.events.submit(new EngineEvent.ServerConnected(server(SERVER, SERVER), 99, false, false));
            return CompletableFuture.completedFuture(null);
        });
    }

    // Each added download completes at once.
    private void completesDownloads() {
        this.fake.answer("addDownload", args -> {
            DownloadInfo info = download((Ed2kLink.FileLink) args[0], 1);
            this.fake.events.submit(new EngineEvent.DownloadCompleted(info, Path.of(info.name())));
            return CompletableFuture.completedFuture(info);
        });
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
