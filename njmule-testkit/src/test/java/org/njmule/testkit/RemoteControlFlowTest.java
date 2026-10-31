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
package org.njmule.testkit;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.app.Main;
import org.njmule.core.api.EngineConfig;
import org.njmule.core.api.NjMuleEngine;
import org.njmule.core.config.AppDirectories;
import org.njmule.core.util.Ipv4;
import org.njmule.http.Passwords;
import org.njmule.http.WebUi;
import org.njmule.http.WebUiSettings;

/** The command line driving a running engine through the remote-control API, against a fake server. */
@Timeout(120)
class RemoteControlFlowTest {

    private static final Pattern RESULT = Pattern.compile("([0-9A-F]{32}) +\\d+ +\\S+ \\S+ +linux web\\.iso");
    private static final Pattern SEARCH = Pattern.compile("Search (\\d+):");

    @TempDir
    Path dir;

    private final List<AutoCloseable> running = new ArrayList<>();
    private final CountDownLatch exited = new CountDownLatch(1);
    private FakeServer server;
    private String hash;
    private String search;
    private int code;
    private String printed;
    private String errors;

    @AfterEach
    void stop() throws Exception {
        for (AutoCloseable c : this.running.reversed()) {
            c.close();
        }
    }

    @Test
    void shouldConnectSearchDownloadAndStopARunningNjMuleFromTheCommandLine() throws Exception {
        givenAServerWithFiles();
        givenNjMuleRunningWithTheApi();
        givenToldRemotelyToConnectToTheServer();
        givenItPrinted("Connecting.");
        givenTheStatusSoonShows("HighID");
        givenRunRemotely("search", "linux");
        givenAResultAndTheSearchListed();
        givenToldRemotelyToGetTheResult();
        givenItPrinted("Downloading linux web.iso");
        givenRunRemotely("downloads");
        givenItPrintedTheHashOfTheResultAnd("linux web.iso");
        givenToldRemotelyToPauseTheResult();
        givenItPrinted("linux web.iso: paused");
        givenRunRemotely("limits", "50", "0");
        givenItPrinted("Upload:   50 KiB/s", "Download: unlimited");

        whenRunRemotely("shutdown");

        thenItPrints("saving and exiting");
        thenNjMuleWasAskedToExit();
    }

    @Test
    void shouldSayWhenThereAreNoServers() throws Exception {
        givenNjMuleRunningWithTheApi();

        whenRunRemotely("servers");

        thenItPrints("No servers.");
    }

    @Test
    void shouldTellWhatIsKnownOfNewVersions() throws Exception {
        givenNjMuleRunningWithTheApi();

        whenRunRemotely("update");

        thenItPrints("has not looked for a new version yet.");
    }

    @Test
    void shouldTellWhyItCannotLookForANewVersion() throws Exception {
        givenNjMuleRunningWithTheApi();

        whenRunRemotely("update", "check");

        thenItPrints("Cannot check for a new version: update checks are turned off");
    }

    @Test
    void shouldRefuseToDownloadBeforeANewVersionIsKnown() throws Exception {
        givenNjMuleRunningWithTheApi();

        whenRunRemotely("update", "download");

        thenItFailsSaying("no new version is known");
    }

    @Test
    void shouldRefuseToInstallWhatIsNotDownloaded() throws Exception {
        givenNjMuleRunningWithTheApi();

        whenRunRemotely("update", "install");

        thenItFailsSaying("no new version is downloaded");
    }

    @Test
    void shouldSayWhenTheApiIsOff() throws Exception {
        whenRunRemotely("status");

        thenItFailsSaying("remote-control API", "is off");
    }

    @Test
    void shouldListItsCommands() throws Exception {
        whenRunRemotely("help");

        thenItPrints("search [server|global|kad] <expression>", "shutdown");
    }

    // Given

    private void givenAServerWithFiles() throws IOException {
        this.server = FakeServer.start(
                InetAddress.getLoopbackAddress(),
                new FakeServer.Config(
                        Ipv4.toEd2k(Ipv4.parse("1.2.3.4")),
                        "welcome",
                        List.of(
                                FakeServer.file("linux web.iso", 700_000_000L, 40),
                                FakeServer.file("linux.txt", 10, 2))));
        this.running.add(this.server);
    }

    private void givenNjMuleRunningWithTheApi() throws IOException {
        AppDirectories dirs = AppDirectories.underRoot(this.dir, false);
        Files.createDirectories(dirs.config());
        Files.writeString(
                dirs.config().resolve("njmule.properties"),
                "net.tcpPort=" + FreePorts.tcp() + "\nnet.kad.enabled=false\nsecurity.filterLan=false\n");
        NjMuleEngine engine = NjMuleEngine.start(new EngineConfig(dirs));
        this.running.add(engine);
        WebUiSettings web = new WebUiSettings(
                true, FreePorts.tcp(), false, Passwords.hash("flow password".toCharArray(), new SecureRandom()), true);
        web.save(dirs.config());
        this.running.add(WebUi.start(engine, web, this.exited::countDown));
    }

    private void givenRunRemotely(String... command) {
        whenRunRemotely(command);
    }

    private void givenToldRemotelyToConnectToTheServer() {
        whenRunRemotely("connect", "127.0.0.1:" + this.server.port());
    }

    private void givenToldRemotelyToGetTheResult() {
        whenRunRemotely("get", this.search, this.hash);
    }

    private void givenToldRemotelyToPauseTheResult() {
        whenRunRemotely("pause", this.hash);
    }

    private void givenItPrinted(String... parts) {
        thenItPrints(parts);
    }

    private void givenItPrintedTheHashOfTheResultAnd(String name) {
        thenItPrints(this.hash, name);
    }

    private void givenTheStatusSoonShows(String text) throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        do {
            whenRunRemotely("status");
            if (this.printed.contains(text)) {
                break;
            }
            Thread.sleep(100);
        } while (System.nanoTime() < end);
        thenItPrints(text);
    }

    private void givenAResultAndTheSearchListed() {
        thenItPrints("linux.txt");
        Matcher result = RESULT.matcher(this.printed);
        assertThat(result.find()).as(this.printed).isTrue();
        this.hash = result.group(1);
        Matcher named = SEARCH.matcher(this.printed);
        assertThat(named.find()).as(this.printed).isTrue();
        this.search = named.group(1);
    }

    // When

    private void whenRunRemotely(String... command) {
        List<String> args = new ArrayList<>(List.of("--config-dir", this.dir.toString(), "--remote"));
        args.addAll(List.of(command));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        this.code = Main.run(
                args.toArray(String[]::new),
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8),
                new CompletableFuture<>());
        this.printed = out.toString(StandardCharsets.UTF_8);
        this.errors = err.toString(StandardCharsets.UTF_8);
    }

    // Then

    private void thenItPrints(String... parts) {
        assertThat(this.code).as(this.errors).isZero();
        assertThat(this.printed).contains(parts);
    }

    private void thenItFailsSaying(String... parts) {
        assertThat(this.code).isNotZero();
        assertThat(this.errors).contains(parts);
    }

    private void thenNjMuleWasAskedToExit() throws InterruptedException {
        assertThat(this.exited.await(10, TimeUnit.SECONDS)).isTrue();
    }
}
