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

import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
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
    }
}
