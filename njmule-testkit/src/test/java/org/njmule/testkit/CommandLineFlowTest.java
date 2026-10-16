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

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.app.Main;
import org.njmule.core.api.Hash16;
import org.njmule.core.protocol.ed2k.FileEntry;
import org.njmule.core.util.Ipv4;

@Timeout(60)
class CommandLineFlowTest {

    @TempDir
    Path dir;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();
    private FakeServer server;
    private FakePeer peer;
    private byte[] data;
    private Path config;
    private Path emule;
    private int exit;

    @AfterEach
    void stop() throws IOException {
        if (this.server != null) {
            this.server.close();
        }
        if (this.peer != null) {
            this.peer.close();
        }
    }

    @Test
    void shouldConnectToTheGivenServerAndPrintIdMessagesAndResults() throws Exception {
        givenAFakeServerSaying(
                "Welcome to the fake server",
                FakeServer.file("ubuntu-24.04.iso", 6_000_000_000L, 50),
                FakeServer.file("ubuntu.txt", 10, 2));

        whenTheCommandLineConnectsToTheServerAndSearches("ubuntu iso");

        thenItExitsWithoutError();
        thenItPrints(
                "  > Welcome to the fake server",
                "ubuntu-24.04.iso",
                "5.6 GiB",
                "ed2k://|file|ubuntu.txt|10|",
                "2 results");
        thenItPrintsTheConnectionToTheServerWith("HighID");
        thenTheServerWasSearched(1);
    }

    @Test
    void shouldDownloadALinkAndExitWhenTheFileIsComplete() throws Exception {
        givenAFakePeerSharingRandomBytes("cli.bin", 3_000_000, 4);

        whenTheCommandLineDownloadsTheLinkOfThePeer();

        thenItExitsWithoutError();
        thenItPrints("Downloading cli.bin (2.9 MiB, 1 source)", "Finished cli.bin: ");
        thenTheDownloadedFileHoldsTheBytesOfThePeer("cli.bin");
    }

    @Test
    void shouldFailWithoutAnyServerToTry() throws Exception {
        whenTheCommandLineSearches("x");

        thenTheExitCodeIs(Main.EXIT_FAILURE);
        thenItPrintsTheError("no known server");
    }

    @Test
    void shouldReportWhatAnImportWouldTake() throws Exception {
        givenAnEmuleProfileWithThePreferences("[eMule]\r\nNick=Someone\r\n");

        whenTheCommandLineImportsTheProfileOnTrial();

        thenTheExitCodeIs(Main.EXIT_OK);
        thenItPrintsWhatWouldBeImportedFromTheProfile();
        thenItPrintsLinesMatching("would import +preferences\\.ini", "missing +server\\.met");
        thenTheProfileHoldsFiles(1);
    }

    private void givenAFakeServerSaying(String welcome, FileEntry... results) throws IOException {
        this.server = FakeServer.start(
                InetAddress.getLoopbackAddress(),
                new FakeServer.Config(Ipv4.toEd2k(Ipv4.parse("1.2.3.4")), welcome, List.of(results)));
    }

    private void givenAFakePeerSharingRandomBytes(String name, int size, long seed) throws IOException {
        this.data = new byte[size];
        new Random(seed).nextBytes(this.data);
        Path shared = Files.createDirectories(this.dir.resolve("shared")).resolve(name);
        Files.write(shared, this.data);
        this.peer = FakePeer.start(
                InetAddress.getLoopbackAddress(), shared, FakePeer.Config.simple(Hash16.parseHex("cd".repeat(16))));
    }

    private void givenAnEmuleProfileWithThePreferences(String preferences) throws IOException {
        this.emule = Files.createDirectories(this.dir.resolve("emule/config"));
        Files.writeString(this.emule.resolve("preferences.ini"), preferences);
    }

    private void whenTheCommandLineConnectsToTheServerAndSearches(String expression) throws Exception {
        run(
                "--headless",
                "--config-dir",
                configDir().toString(),
                "--connect-server",
                "127.0.0.1:" + this.server.port(),
                "--search",
                expression);
    }

    private void whenTheCommandLineDownloadsTheLinkOfThePeer() throws Exception {
        run(
                "--config-dir",
                configDir().toString(),
                "--download",
                this.peer.link().toUri());
    }

    private void whenTheCommandLineSearches(String expression) throws Exception {
        run("--config-dir", configDir().toString(), "--search", expression);
    }

    private void whenTheCommandLineImportsTheProfileOnTrial() throws Exception {
        run("--config-dir", configDir().toString(), "--import-emule", this.emule.toString(), "--dry-run");
    }

    private void thenItExitsWithoutError() {
        assertThat(this.exit).as(this.err.toString(UTF_8)).isZero();
    }

    private void thenTheExitCodeIs(int code) {
        assertThat(this.exit).isEqualTo(code);
    }

    private void thenItPrints(String... parts) {
        assertThat(this.out.toString(UTF_8)).contains(parts);
    }

    private void thenItPrintsTheConnectionToTheServerWith(String id) {
        thenItPrints("Connected to 127.0.0.1:" + this.server.port() + " with " + id);
    }

    private void thenItPrintsTheError(String part) {
        assertThat(this.err.toString(UTF_8)).contains(part);
    }

    private void thenItPrintsWhatWouldBeImportedFromTheProfile() {
        thenItPrints("What would be imported from " + this.emule);
    }

    private void thenItPrintsLinesMatching(String... patterns) {
        for (String pattern : patterns) {
            assertThat(this.out.toString(UTF_8)).containsPattern(pattern);
        }
    }

    private void thenTheServerWasSearched(int times) {
        assertThat(this.server.searches()).hasSize(times);
    }

    private void thenTheDownloadedFileHoldsTheBytesOfThePeer(String name) throws IOException {
        try (Stream<Path> incoming = Files.walk(this.config)) {
            Path done = incoming.filter(p -> p.getFileName().toString().equals(name))
                    .findFirst()
                    .orElseThrow();
            assertThat(Files.readAllBytes(done)).isEqualTo(this.data);
        }
    }

    private void thenTheProfileHoldsFiles(int count) throws IOException {
        try (Stream<Path> files = Files.list(this.emule)) {
            assertThat(files.count()).isEqualTo(count);
        }
    }

    private void run(String... arguments) {
        this.exit = Main.run(
                arguments,
                new PrintStream(this.out, true, UTF_8),
                new PrintStream(this.err, true, UTF_8),
                new CompletableFuture<>());
    }

    private Path configDir() throws IOException {
        Path root = this.dir.resolve("njmule");
        Files.createDirectories(root.resolve("config"));
        int port = FreePorts.tcp();
        Files.writeString(root.resolve("config/njmule.properties"), "net.tcpPort=" + port + "\n");
        this.config = root;
        return root;
    }
}
