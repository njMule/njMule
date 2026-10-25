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
package org.njmule.core.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

class KeepAwakeTest {

    private static final Instant NOW = Instant.parse("2026-10-25T12:00:00Z");
    private static final List<String> COMMAND = List.of("caffeinate", "-i", "-w", "42");

    private final List<FakeProcess> started = new ArrayList<>();
    private boolean refused;
    private boolean launcher = true;
    private KeepAwake keepAwake;
    private Optional<List<String>> command;

    @Test
    void shouldKeepTheComputerAwakeWhileFilesAreTransferred() {
        givenTheHelper(COMMAND);

        whenUpdated(true, true, NOW);

        thenItIsAwake(true);
        thenTheHelpersStartedAre(1);
    }

    @Test
    void shouldLetItSleepWhenNotAsked() {
        givenTheHelper(COMMAND);

        whenUpdated(false, true, NOW);

        thenItIsAwake(false);
        thenTheHelpersStartedAre(0);
    }

    @Test
    void shouldKeepItAwakeThroughAShortPause() {
        givenTheHelper(COMMAND);
        givenUpdated(true, true, NOW);

        whenUpdated(true, false, NOW.plus(KeepAwake.LINGER).minusSeconds(1));

        thenItIsAwake(true);
        thenTheHelpersStartedAre(1);
    }

    @Test
    void shouldLetItSleepAWhileAfterTheLastTransfer() {
        givenTheHelper(COMMAND);
        givenUpdated(true, true, NOW);

        whenUpdated(true, false, NOW.plus(KeepAwake.LINGER));

        thenItIsAwake(false);
    }

    @Test
    void shouldNotTryAgainWhenTheSystemRefuses() {
        givenTheHelper(COMMAND);
        givenTheSystemRefuses();
        givenUpdated(true, true, NOW);

        whenUpdated(true, true, NOW.plusSeconds(30));

        thenItIsAwake(false);
        thenTheHelpersStartedAre(0);
    }

    @Test
    void shouldUseTheLauncherOnWindows() {
        whenTheCommandIsFound("Windows 11", Optional.of(Path.of("C:", "njMule", "njmule.exe")));

        thenTheCommandIs(Path.of("C:", "njMule", "njmule-gui.exe").toString(), "--keep-awake", "42");
    }

    @Test
    void shouldHaveNoneOnWindowsWithoutTheLauncher() {
        givenNoLauncher();

        whenTheCommandIsFound("Windows 11", Optional.of(Path.of("C:", "jdk", "java.exe")));

        thenThereIsNoCommand();
    }

    @Test
    void shouldInhibitSleepThroughSystemdOnLinux() {
        whenTheCommandIsFound("Linux", Optional.empty());

        thenTheCommandStartsWith("systemd-inhibit", "--what=sleep:idle");
    }

    private void givenTheHelper(List<String> helper) {
        this.keepAwake = new KeepAwake(Optional.of(helper), c -> {
            if (this.refused) {
                throw new IOException("no such program");
            }
            FakeProcess process = new FakeProcess();
            this.started.add(process);
            return process;
        });
    }

    private void givenNoLauncher() {
        this.launcher = false;
    }

    private void givenTheSystemRefuses() {
        this.refused = true;
    }

    private void givenUpdated(boolean wanted, boolean transferring, Instant at) {
        this.keepAwake.update(wanted, transferring, at);
    }

    private void whenUpdated(boolean wanted, boolean transferring, Instant at) {
        this.keepAwake.update(wanted, transferring, at);
    }

    private void whenTheCommandIsFound(String os, Optional<Path> program) {
        this.command = KeepAwake.command(os, 42, program, p -> this.launcher);
    }

    private void thenItIsAwake(boolean expected) {
        assertThat(this.keepAwake.awake()).isEqualTo(expected);
    }

    private void thenTheHelpersStartedAre(int count) {
        assertThat(this.started).hasSize(count);
    }

    private void thenTheCommandIs(String... expected) {
        assertThat(this.command).hasValue(List.of(expected));
    }

    private void thenThereIsNoCommand() {
        assertThat(this.command).isEmpty();
    }

    private void thenTheCommandStartsWith(String... expected) {
        assertThat(this.command).hasValueSatisfying(c -> assertThat(c).startsWith(expected));
    }

    /** A helper that runs until destroyed. */
    private static final class FakeProcess extends Process {
        private boolean alive = true;

        @Override
        public java.io.OutputStream getOutputStream() {
            return java.io.OutputStream.nullOutputStream();
        }

        @Override
        public java.io.InputStream getInputStream() {
            return java.io.InputStream.nullInputStream();
        }

        @Override
        public java.io.InputStream getErrorStream() {
            return java.io.InputStream.nullInputStream();
        }

        @Override
        public int waitFor() {
            return 0;
        }

        @Override
        public int exitValue() {
            return 0;
        }

        @Override
        public boolean isAlive() {
            return this.alive;
        }

        @Override
        public void destroy() {
            this.alive = false;
        }

        @Override
        public CompletableFuture<Process> onExit() {
            return CompletableFuture.completedFuture(this);
        }
    }
}
