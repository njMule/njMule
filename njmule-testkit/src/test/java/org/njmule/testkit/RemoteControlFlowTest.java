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
import java.net.ServerSocket;
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
import org.njmule.webui.Passwords;
import org.njmule.webui.WebUi;
import org.njmule.webui.WebUiSettings;

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
        this.givenAServerWithFiles();
        this.givenNjMuleRunningWithTheApi();

        this.whenRunRemotely("connect", "127.0.0.1:" + this.server.port());
        this.thenItPrints("Connecting.");
        this.thenTheStatusSoonShows("HighID");

        this.whenRunRemotely("search", "linux");
        String hash = this.thenAResultIsListed();
        String search = this.thenTheSearchIsNamed();

        this.whenRunRemotely("get", search, hash);
        this.thenItPrints("Downloading linux web.iso");

        this.whenRunRemotely("downloads");
        this.thenItPrints(hash, "linux web.iso");

        this.whenRunRemotely("pause", hash);
        this.thenItPrints("linux web.iso: paused");

        this.whenRunRemotely("limits", "50", "0");
        this.thenItPrints("Upload:   50 KiB/s", "Download: unlimited");

        this.whenRunRemotely("shutdown");
        this.thenItPrints("saving and exiting");
        this.thenNjMuleWasAskedToExit();
    }

    @Test
    void shouldSayWhenThereAreNoServers() throws Exception {
        this.givenNjMuleRunningWithTheApi();

        this.whenRunRemotely("servers");

        this.thenItPrints("No servers.");
    }

    @Test
    void shouldSayWhenTheApiIsOff() throws Exception {
        this.whenRunRemotely("status");

        this.thenItFailsSaying("remote-control API", "is off");
    }

    @Test
    void shouldListItsCommands() throws Exception {
        this.whenRunRemotely("help");

        this.thenItPrints("search [server|global|kad] <expression>", "shutdown");
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
                "net.tcpPort=" + freePort() + "\nnet.kad.enabled=false\nsecurity.filterLan=false\n");
        NjMuleEngine engine = NjMuleEngine.start(new EngineConfig(dirs));
        this.running.add(engine);
        WebUiSettings web = new WebUiSettings(
                true, freePort(), false, Passwords.hash("flow password".toCharArray(), new SecureRandom()), true);
        web.save(dirs.config());
        this.running.add(WebUi.start(engine, web, this.exited::countDown));
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

    private void thenTheStatusSoonShows(String text) throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        do {
            this.whenRunRemotely("status");
            if (this.printed.contains(text)) {
                break;
            }
            Thread.sleep(100);
        } while (System.nanoTime() < end);
        this.thenItPrints(text);
    }

    private String thenAResultIsListed() {
        this.thenItPrints("linux.txt");
        Matcher m = RESULT.matcher(this.printed);
        assertThat(m.find()).as(this.printed).isTrue();
        return m.group(1);
    }

    private String thenTheSearchIsNamed() {
        Matcher m = SEARCH.matcher(this.printed);
        assertThat(m.find()).as(this.printed).isTrue();
        return m.group(1);
    }

    private void thenNjMuleWasAskedToExit() throws InterruptedException {
        assertThat(this.exited.await(10, TimeUnit.SECONDS)).isTrue();
    }

    private static int freePort() throws IOException {
        try (ServerSocket probe = new ServerSocket(0)) {
            return probe.getLocalPort();
        }
    }
}
