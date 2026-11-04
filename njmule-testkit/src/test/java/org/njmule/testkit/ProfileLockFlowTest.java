/*
 * njMule - New Jack Mule
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.app.LinkHandoff;
import org.njmule.app.Main;
import org.njmule.core.config.AppDirectories;

/**
 * One njMule per profile: a later start on it hands its links to the one running, or asks it to exit, and without
 * either fails on its lock. In this JVM both are {@link Main}; with {@code -Pnjmule.engine=<path>} both are the
 * executable, as a link clicked in a browser or an uninstaller starts it.
 */
class ProfileLockFlowTest {

    private static final Duration WAIT = Duration.ofSeconds(60);
    private static final String NAME = "handed-over.bin";
    private static final String LINK = "ed2k://|file|" + NAME + "|1000|0123456789ABCDEF0123456789ABCDEF|/";

    @TempDir
    Path dir;

    private AppDirectories dirs;
    private Path root;
    private Thread running;
    private CompletableFuture<Void> stopRunning;
    private Process runningProcess;
    private int exit;
    private String output;

    @AfterEach
    void stopTheRunningNjmule() throws InterruptedException {
        if (this.stopRunning != null) {
            this.stopRunning.complete(null);
            this.running.join(WAIT);
        }
        if (this.runningProcess != null) {
            this.runningProcess.destroy();
            if (!this.runningProcess.waitFor(WAIT.toSeconds(), TimeUnit.SECONDS)) {
                this.runningProcess.destroyForcibly();
            }
        }
    }

    @Test
    void shouldHandALinkToTheNjmuleRunningOnTheProfile() throws Exception {
        givenNjmuleRunningOnTheProfile();

        whenNjmuleStartsOnTheProfileWith(LINK);

        thenTheExitCodeIs(Main.EXIT_OK);
        thenItSaid("The links went to the njMule already running.");
        thenTheRunningNjmuleDownloadsTheLink();
    }

    @Test
    void shouldRefuseASecondNjmuleOnTheProfile() throws Exception {
        givenNjmuleRunningOnTheProfile();

        whenNjmuleStartsOnTheProfileWith();

        thenTheExitCodeIs(Main.EXIT_FAILURE);
        thenItSaid("njMule is already running with the configuration in");
    }

    @Test
    void shouldHaveTheNjmuleRunningOnTheProfileExit() throws Exception {
        givenNjmuleRunningOnTheProfile();

        whenNjmuleStartsOnTheProfileWith("--exit");

        thenTheExitCodeIs(Main.EXIT_OK);
        thenItSaid("has exited.");
        thenTheRunningNjmuleHasStopped();
    }

    private void givenNjmuleRunningOnTheProfile() throws IOException, InterruptedException {
        this.root = this.dir.resolve("njmule");
        this.dirs = AppDirectories.underRoot(this.root, false);
        Files.createDirectories(this.dirs.config());
        Files.writeString(this.dirs.config().resolve("njmule.properties"), "net.tcpPort=" + FreePorts.tcp() + "\n");
        if (Engines.inThisJvm()) {
            this.stopRunning = new CompletableFuture<>();
            PrintStream quiet = new PrintStream(new ByteArrayOutputStream(), true, UTF_8);
            String[] arguments = {"--config-dir", this.root.toString()};
            this.running = Thread.ofVirtual()
                    .name("njmule-running")
                    .start(() -> Main.run(arguments, quiet, quiet, this.stopRunning));
        } else {
            this.runningProcess = Engines.command("--config-dir", this.root.toString())
                    .redirectOutput(this.root.resolve("running.log").toFile())
                    .start();
        }
        awaitTrue(() -> Files.exists(this.dirs.config().resolve(LinkHandoff.FILE_NAME)), "the hand-over to open");
    }

    private void whenNjmuleStartsOnTheProfileWith(String... more) throws IOException, InterruptedException {
        List<String> arguments = new ArrayList<>(List.of("--config-dir", this.root.toString()));
        arguments.addAll(List.of(more));
        if (Engines.inThisJvm()) {
            ByteArrayOutputStream text = new ByteArrayOutputStream();
            PrintStream out = new PrintStream(text, true, UTF_8);
            CompletableFuture<Void> stop = new CompletableFuture<>();
            int[] code = {-1};
            Thread later = Thread.ofVirtual()
                    .name("njmule-later")
                    .start(() -> code[0] = Main.run(arguments.toArray(String[]::new), out, out, stop));
            // A later start that ran an engine after all would not end by itself.
            if (!later.join(WAIT)) {
                stop.complete(null);
                later.join(WAIT);
            }
            this.exit = code[0];
            this.output = text.toString(UTF_8);
        } else {
            Process later = Engines.command(arguments.toArray(String[]::new)).start();
            this.output = new String(later.getInputStream().readAllBytes(), UTF_8);
            if (!later.waitFor(WAIT.toSeconds(), TimeUnit.SECONDS)) {
                later.destroyForcibly();
            }
            this.exit = later.exitValue();
        }
    }

    private void thenTheExitCodeIs(int expected) {
        assertThat(this.exit).as(this.output).isEqualTo(expected);
    }

    private void thenItSaid(String text) {
        assertThat(this.output).contains(text);
    }

    private void thenTheRunningNjmuleDownloadsTheLink() throws InterruptedException {
        awaitTrue(() -> partFiles() > 0, "the handed link to be downloaded");
    }

    private void thenTheRunningNjmuleHasStopped() throws InterruptedException {
        if (this.running != null) {
            assertThat(this.running.join(WAIT)).isTrue();
        } else {
            assertThat(this.runningProcess.waitFor(WAIT.toSeconds(), TimeUnit.SECONDS))
                    .isTrue();
        }
    }

    private long partFiles() throws IOException {
        if (!Files.isDirectory(this.dirs.temp())) {
            return 0;
        }
        try (Stream<Path> files = Files.list(this.dirs.temp())) {
            return files.count();
        }
    }

    @FunctionalInterface
    private interface Check {
        boolean holds() throws IOException;
    }

    private static void awaitTrue(Check check, String what) throws InterruptedException {
        Instant end = Instant.now().plus(WAIT);
        while (Instant.now().isBefore(end)) {
            try {
                if (check.holds()) {
                    return;
                }
            } catch (IOException e) {
                // Not there yet.
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Waited " + WAIT.toSeconds() + " s for " + what);
    }
}
