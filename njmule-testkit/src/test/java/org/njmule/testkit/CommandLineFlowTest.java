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
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.app.Main;
import org.njmule.core.api.Hash16;
import org.njmule.core.util.Ipv4;

@Timeout(60)
class CommandLineFlowTest {

    @TempDir Path dir;

    @Test
    void connectsToTheGivenServerAndPrintsIdMessagesAndResults() throws Exception {
        InetAddress loopback = InetAddress.getLoopbackAddress();
        try (FakeServer server =
                FakeServer.start(
                        loopback,
                        new FakeServer.Config(
                                Ipv4.toEd2k(Ipv4.parse("1.2.3.4")),
                                "Welcome to the fake server",
                                List.of(
                                        FakeServer.file("ubuntu-24.04.iso", 6_000_000_000L, 50),
                                        FakeServer.file("ubuntu.txt", 10, 2))))) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ByteArrayOutputStream err = new ByteArrayOutputStream();

            int exit =
                    Main.run(
                            new String[] {
                                "--headless",
                                "--config-dir",
                                this.configDir().toString(),
                                "--connect-server",
                                "127.0.0.1:" + server.port(),
                                "--search",
                                "ubuntu iso"
                            },
                            new PrintStream(out, true, UTF_8),
                            new PrintStream(err, true, UTF_8),
                            new CompletableFuture<>());

            String printed = out.toString(UTF_8);
            assertThat(exit).as(err.toString(UTF_8)).isZero();
            assertThat(printed)
                    .contains(
                            "Connected to 127.0.0.1:" + server.port() + " with HighID",
                            "  > Welcome to the fake server",
                            "ubuntu-24.04.iso",
                            "5.6 GiB",
                            "ed2k://|file|ubuntu.txt|10|",
                            "2 results");
            assertThat(server.searches()).hasSize(1);
        }
    }

    @Test
    void downloadsALinkAndExitsWhenTheFileIsComplete() throws Exception {
        byte[] data = new byte[3_000_000];
        new Random(4).nextBytes(data);
        Path shared = Files.createDirectories(this.dir.resolve("shared")).resolve("cli.bin");
        Files.write(shared, data);
        try (FakePeer peer =
                FakePeer.start(
                        InetAddress.getLoopbackAddress(),
                        shared,
                        FakePeer.Config.simple(Hash16.parseHex("cd".repeat(16))))) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ByteArrayOutputStream err = new ByteArrayOutputStream();
            Path config = this.configDir();

            int exit =
                    Main.run(
                            new String[] {
                                "--config-dir", config.toString(), "--download", peer.link().toUri()
                            },
                            new PrintStream(out, true, UTF_8),
                            new PrintStream(err, true, UTF_8),
                            new CompletableFuture<>());

            assertThat(exit).as(err.toString(UTF_8)).isZero();
            assertThat(out.toString(UTF_8))
                    .contains("Downloading cli.bin (2.9 MiB, 1 source)", "Finished cli.bin: ");
            try (Stream<Path> incoming = Files.walk(config)) {
                Path done =
                        incoming.filter(p -> p.getFileName().toString().equals("cli.bin"))
                                .findFirst()
                                .orElseThrow();
                assertThat(Files.readAllBytes(done)).isEqualTo(data);
            }
        }
    }

    @Test
    void failsWithoutAnyServerToTry() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        int exit =
                Main.run(
                        new String[] {"--config-dir", this.configDir().toString(), "--search", "x"},
                        new PrintStream(out, true, UTF_8),
                        new PrintStream(err, true, UTF_8),
                        new CompletableFuture<>());

        assertThat(exit).isEqualTo(Main.EXIT_FAILURE);
        assertThat(err.toString(UTF_8)).contains("no known server");
    }

    private Path configDir() throws Exception {
        Path root = this.dir.resolve("njmule");
        Files.createDirectories(root.resolve("config"));
        int port;
        try (ServerSocket probe = new ServerSocket(0)) {
            port = probe.getLocalPort();
        }
        Files.writeString(root.resolve("config/njmule.properties"), "net.tcpPort=" + port + "\n");
        return root;
    }
}
