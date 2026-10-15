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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(30)
class LinkHandoffTest {

    private static final String LINK = "ed2k://|file|a.iso|10|" + "ab".repeat(16) + "|/";

    @TempDir
    Path dir;

    private final BlockingQueue<List<String>> received = new LinkedBlockingQueue<>();

    @Test
    void aLaterStartHandsItsLinksToTheRunningOne() throws Exception {
        try (LinkHandoff _ = LinkHandoff.listen(this.dir, this.received::add)) {
            assertThat(LinkHandoff.deliver(this.dir, List.of(LINK, "http://example.org/not-a-link")))
                    .isTrue();
            assertThat(this.received.poll(5, TimeUnit.SECONDS)).containsExactly(LINK);

            assertThat(LinkHandoff.deliver(this.dir, List.of()))
                    .as("a start that only wants the window")
                    .isTrue();
            assertThat(this.received.poll(5, TimeUnit.SECONDS)).isEmpty();
        }
        assertThat(this.dir.resolve(LinkHandoff.FILE_NAME)).doesNotExist();
        assertThat(LinkHandoff.deliver(this.dir, List.of(LINK)))
                .as("nobody listens")
                .isFalse();
    }

    @Test
    void onlyWhoKnowsTheSecretHandsOverLinks() throws Exception {
        Path file = this.dir.resolve(LinkHandoff.FILE_NAME);
        try (LinkHandoff _ = LinkHandoff.listen(this.dir, this.received::add)) {
            if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
                assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(file)))
                        .isEqualTo("rw-------");
            }
            String port = Files.readString(file, StandardCharsets.US_ASCII).split(" ", -1)[0];
            Files.writeString(file, port + " " + "00".repeat(32) + "\n", StandardCharsets.US_ASCII);

            assertThat(LinkHandoff.deliver(this.dir, List.of(LINK))).isFalse();
            assertThat(this.received.poll(500, TimeUnit.MILLISECONDS)).isNull();
        }
        assertThat(file).as("the file another instance wrote stays").exists();
    }

    @Test
    void aReceiverThatFailsStillTakesTheLinks() throws Exception {
        try (LinkHandoff _ = LinkHandoff.listen(this.dir, links -> {
            this.received.add(links);
            throw new IllegalArgumentException("cannot follow");
        })) {
            assertThat(LinkHandoff.deliver(this.dir, List.of(LINK)))
                    .as("the later start must not try to run beside it")
                    .isTrue();
            assertThat(this.received.poll(5, TimeUnit.SECONDS)).containsExactly(LINK);
        }
    }

    @Test
    void aLinkCannotSmuggleInAnotherLine() throws Exception {
        try (LinkHandoff _ = LinkHandoff.listen(this.dir, this.received::add)) {
            assertThat(LinkHandoff.deliver(
                            this.dir, List.of("ed2k://|server|x|1|/\n" + LinkHandoff.EXIT, "", "a\rb", LINK)))
                    .isTrue();
            assertThat(this.received.poll(5, TimeUnit.SECONDS))
                    .as("no exit request, and an empty link does not end the list")
                    .containsExactly(LINK);

            String[] fields = Files.readString(this.dir.resolve(LinkHandoff.FILE_NAME), StandardCharsets.US_ASCII)
                    .strip()
                    .split(" ", -1);
            try (Socket client = new Socket(InetAddress.getLoopbackAddress(), Integer.parseInt(fields[0]))) {
                client.getOutputStream()
                        .write((fields[1] + "\n" + LINK + "\n" + LinkHandoff.EXIT + "\n\n")
                                .getBytes(StandardCharsets.UTF_8));
                assertThat(new String(client.getInputStream().readAllBytes(), StandardCharsets.UTF_8))
                        .startsWith("OK");
            }
            assertThat(this.received.poll(5, TimeUnit.SECONDS))
                    .as("the exit request counts only alone")
                    .containsExactly(LINK);
        }
    }

    @Test
    void aSlowLocalProgramHoldsNobodyUp() throws Exception {
        try (LinkHandoff _ = LinkHandoff.listen(this.dir, this.received::add)) {
            String port = Files.readString(this.dir.resolve(LinkHandoff.FILE_NAME), StandardCharsets.US_ASCII)
                    .split(" ", -1)[0];
            try (Socket slow = new Socket(InetAddress.getLoopbackAddress(), Integer.parseInt(port))) {
                // A byte now and then, never a whole line.
                Thread trickle = Thread.ofVirtual().start(() -> {
                    try {
                        while (true) {
                            slow.getOutputStream().write('a');
                            Thread.sleep(200);
                        }
                    } catch (IOException | InterruptedException e) {
                        // Closed.
                    }
                });
                long start = System.nanoTime();
                assertThat(LinkHandoff.deliver(this.dir, List.of(LINK))).isTrue();
                assertThat(this.received.poll(5, TimeUnit.SECONDS)).containsExactly(LINK);

                slow.setSoTimeout(
                        Math.toIntExact(LinkHandoff.DEADLINE.plusSeconds(5).toMillis()));
                assertThat(slow.getInputStream().read()).as("dropped").isEqualTo(-1);
                assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(LinkHandoff.DEADLINE.plusSeconds(5));
                trickle.interrupt();
            }
        }
    }

    @Test
    void aFileLeftByACrashIsNoRunningInstance() throws Exception {
        Files.writeString(this.dir.resolve(LinkHandoff.FILE_NAME), "1 " + "ab".repeat(32) + "\n");

        assertThat(LinkHandoff.deliver(this.dir, List.of(LINK))).isFalse();
    }

    @Test
    void aDamagedFileIsNoRunningInstance() throws Exception {
        for (String port : new String[] {"70000", "65536", "-1", "0"}) {
            Files.writeString(this.dir.resolve(LinkHandoff.FILE_NAME), port + " " + "ab".repeat(32) + "\n");

            assertThat(LinkHandoff.deliver(this.dir, List.of(LINK))).as(port).isFalse();
        }
        for (String content : new String[] {"4711", "port " + "ab".repeat(32), "1 2 3"}) {
            Files.writeString(this.dir.resolve(LinkHandoff.FILE_NAME), content + "\n");

            assertThat(LinkHandoff.deliver(this.dir, List.of(LINK))).as(content).isFalse();
        }
    }

    @Test
    void anUnreadableFileIsNoRunningInstance() throws Exception {
        Files.createDirectories(this.dir.resolve(LinkHandoff.FILE_NAME));

        assertThat(LinkHandoff.deliver(this.dir, List.of(LINK))).isFalse();
    }

    @Test
    void listeningNeedsAPlaceForTheFile() throws Exception {
        Path notADirectory = Files.writeString(this.dir.resolve("config"), "a file");

        assertThatThrownBy(() -> LinkHandoff.listen(notADirectory, this.received::add))
                .isInstanceOf(IOException.class);
    }

    @Test
    void closingCopesWithAFileAlreadyGone() throws Exception {
        LinkHandoff handoff = LinkHandoff.listen(this.dir, this.received::add);
        Files.delete(this.dir.resolve(LinkHandoff.FILE_NAME));

        handoff.close();

        assertThat(LinkHandoff.deliver(this.dir, List.of(LINK))).isFalse();
    }

    @Test
    void aProgramWithoutTheSecretGetsNoAnswer() throws Exception {
        try (LinkHandoff _ = LinkHandoff.listen(this.dir, this.received::add)) {
            int port = port();
            // Only the first line, which ends the exchange: nothing is left unread to reset the connection.
            for (String sent : new String[] {"", "not hex\n", "ab\n"}) {
                try (Socket client = new Socket(InetAddress.getLoopbackAddress(), port)) {
                    client.getOutputStream().write(sent.getBytes(StandardCharsets.UTF_8));
                    client.shutdownOutput();
                    assertThat(client.getInputStream().readAllBytes()).as(sent).isEmpty();
                }
            }
            assertThat(this.received.poll(300, TimeUnit.MILLISECONDS)).isNull();
        }
    }

    @Test
    void takesAtMostSoManyLinksAndTheLastOneWithoutItsLineEnd() throws Exception {
        try (LinkHandoff _ = LinkHandoff.listen(this.dir, this.received::add)) {
            StringBuilder sent = new StringBuilder(secret()).append('\n');
            for (int i = 0; i < LinkHandoff.MAX_LINKS + 10; i++) {
                sent.append("ed2k://|file|")
                        .append(i)
                        .append(".iso|10|")
                        .append("ab".repeat(16))
                        .append("|/\n");
            }
            assertThat(exchange(sent.toString())).startsWith("OK");
            List<String> links = this.received.poll(5, TimeUnit.SECONDS);
            assertThat(links).hasSize(LinkHandoff.MAX_LINKS);
            assertThat(links.getFirst()).contains("|0.iso|");

            assertThat(exchange(secret() + "\n" + LINK)).startsWith("OK");
            assertThat(this.received.poll(5, TimeUnit.SECONDS)).containsExactly(LINK);
        }
    }

    @Test
    void aLineTooLongEndsTheExchange() throws Exception {
        try (LinkHandoff _ = LinkHandoff.listen(this.dir, this.received::add)) {
            // One character too many, and no more: nothing is left unread to reset the connection.
            String tooLong = "ed2k://" + "a".repeat(LinkHandoff.MAX_LINE - 6);

            assertThat(exchange(secret() + "\n" + tooLong)).isEmpty();
            assertThat(this.received.poll(300, TimeUnit.MILLISECONDS)).isNull();
            assertThat(LinkHandoff.deliver(this.dir, List.of(LINK)))
                    .as("still listening")
                    .isTrue();
        }
    }

    @Test
    void anInstanceHoldingItsLockHasNotGone() throws Exception {
        Path lock = this.dir.resolve("njmule.lock");
        try (FileChannel channel = FileChannel.open(lock, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                FileLock _ = channel.lock()) {
            Thread.currentThread().interrupt();
            try {
                assertThat(LinkHandoff.awaitGone(this.dir, Duration.ofSeconds(5)))
                        .as("an interrupted wait")
                        .isFalse();
                assertThat(Thread.currentThread().isInterrupted()).isTrue();
            } finally {
                Thread.interrupted();
            }
        }
        assertThat(LinkHandoff.awaitGone(this.dir, Duration.ofSeconds(5)))
                .as("a lock nobody holds")
                .isTrue();
    }

    @Test
    void aLockThatCannotBeOpenedGoesByTheHandoffFile() throws Exception {
        Files.createDirectories(this.dir.resolve("njmule.lock"));
        assertThat(LinkHandoff.awaitGone(this.dir, Duration.ofMillis(300))).isTrue();

        Files.writeString(this.dir.resolve(LinkHandoff.FILE_NAME), "1 " + "ab".repeat(32) + "\n");
        assertThat(LinkHandoff.awaitGone(this.dir, Duration.ofMillis(300))).isFalse();
    }

    private int port() throws IOException {
        return Integer.parseInt(Files.readString(this.dir.resolve(LinkHandoff.FILE_NAME), StandardCharsets.US_ASCII)
                .split(" ", -1)[0]);
    }

    private String secret() throws IOException {
        return Files.readString(this.dir.resolve(LinkHandoff.FILE_NAME), StandardCharsets.US_ASCII)
                .strip()
                .split(" ", -1)[1];
    }

    // Sends text as a later start would, and returns the answer.
    private String exchange(String text) throws IOException {
        try (Socket client = new Socket(InetAddress.getLoopbackAddress(), port())) {
            client.getOutputStream().write(text.getBytes(StandardCharsets.UTF_8));
            client.shutdownOutput();
            return new String(client.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
