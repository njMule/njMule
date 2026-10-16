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
import static org.assertj.core.api.Assertions.catchThrowable;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@link LinkAssociation#run} on real child processes: small Java programs of this test, never a system tool. */
class CommandRunTest {

    @TempDir
    Path dir;

    private Path pidFile;
    private List<String> command;
    private LinkAssociation.Result result;
    private Throwable exception;
    private Duration took;

    @AfterEach
    void stopWhatIsStillRunning() throws IOException {
        if (this.pidFile != null && Files.exists(this.pidFile)) {
            ProcessHandle.of(Long.parseLong(Files.readString(this.pidFile).strip()))
                    .ifPresent(ProcessHandle::destroyForcibly);
        }
    }

    @Test
    void shouldStopACommandThatOutlivesItsTimeout() throws Exception {
        givenAProgramThatPrintsThenSleeps(60);

        whenItRunsWithATimeoutOf(Duration.ofSeconds(5));

        thenItFailedAs("did not finish");
        thenItAnsweredWithin(Duration.ofSeconds(25));
        thenTheSleeperIsSoonGone();
    }

    @Test
    void shouldAnswerWhenACommandEndsButWhatItStartedKeepsItsOutputOpen() throws Exception {
        givenAProgramThatStartsASleeperOnItsOutputThenEnds(60);

        whenItRunsWithATimeoutOf(Duration.ofSeconds(5));

        thenItEndedWith(0, "started a sleeper");
        thenItAnsweredWithin(Duration.ofSeconds(25));
    }

    private void givenAProgramThatPrintsThenSleeps(int seconds) throws URISyntaxException {
        this.pidFile = this.dir.resolve("sleeper.pid");
        this.command = java(Sleeper.class, String.valueOf(seconds), this.pidFile.toString());
    }

    private void givenAProgramThatStartsASleeperOnItsOutputThenEnds(int seconds) throws URISyntaxException {
        this.pidFile = this.dir.resolve("sleeper.pid");
        this.command = java(Starter.class, String.valueOf(seconds), this.pidFile.toString());
    }

    private void whenItRunsWithATimeoutOf(Duration timeout) {
        long start = System.nanoTime();
        this.exception = catchThrowable(() -> this.result = LinkAssociation.run(this.command, timeout));
        this.took = Duration.ofNanos(System.nanoTime() - start);
    }

    private void thenItFailedAs(String message) {
        assertThat(this.exception).isInstanceOf(IOException.class).hasMessageContaining(message);
    }

    private void thenItEndedWith(int exit, String output) {
        assertThat(this.exception).isNull();
        assertThat(this.result.exit()).isEqualTo(exit);
        assertThat(this.result.output()).contains(output);
    }

    private void thenItAnsweredWithin(Duration limit) {
        assertThat(this.took).isLessThan(limit);
    }

    private void thenTheSleeperIsSoonGone() throws Exception {
        Optional<ProcessHandle> sleeper =
                ProcessHandle.of(Long.parseLong(Files.readString(this.pidFile).strip()));
        long end = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (sleeper.isPresent() && sleeper.get().isAlive() && System.nanoTime() < end) {
            Thread.sleep(50);
        }
        assertThat(sleeper.filter(ProcessHandle::isAlive)).isEmpty();
    }

    private static List<String> java(Class<?> main, String... args) throws URISyntaxException {
        List<String> command = new ArrayList<>();
        command.add(ProcessHandle.current().info().command().orElse("java"));
        command.add("-cp");
        command.add(Path.of(CommandRunTest.class
                        .getProtectionDomain()
                        .getCodeSource()
                        .getLocation()
                        .toURI())
                .toString());
        command.add(main.getName());
        command.addAll(List.of(args));
        return command;
    }

    /** Writes its process id to the file given, prints a line and sleeps for the seconds given. */
    public static final class Sleeper {

        private Sleeper() {}

        public static void main(String[] args) throws Exception {
            Files.writeString(
                    Path.of(args[1]), String.valueOf(ProcessHandle.current().pid()));
            System.out.println("sleeping");
            System.out.flush();
            Thread.sleep(Long.parseLong(args[0]) * 1000);
        }
    }

    /** Starts a sleeper that shares its output, says so and ends. */
    public static final class Starter {

        private Starter() {}

        public static void main(String[] args) throws Exception {
            new ProcessBuilder(java(Sleeper.class, args)).inheritIO().start();
            System.out.println("started a sleeper");
        }
    }
}
