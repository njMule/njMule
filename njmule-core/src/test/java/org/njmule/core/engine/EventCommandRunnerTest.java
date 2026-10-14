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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.core.api.DownloadInfo;
import org.njmule.core.api.DownloadPriority;
import org.njmule.core.api.EngineEvent;
import org.njmule.core.api.Hash16;
import org.njmule.core.api.PartMap;
import org.njmule.core.config.EventCommands;

class EventCommandRunnerTest {

    private static final Hash16 HASH = Hash16.parseHex("0123456789abcdef0123456789abcdef");

    @TempDir
    Path dir;

    private EventCommands commands = EventCommands.NONE;
    private boolean windows;
    private boolean launcherFails;
    // The commands handed to the executor, not run until a step runs them.
    private final ArrayDeque<Runnable> waiting = new ArrayDeque<>();
    private final List<List<String>> launched = new ArrayList<>();
    private EventCommandRunner runner;

    @Test
    void shouldFillTheDownloadInsideItsArgumentsWhenADownloadCompletes() {
        this.givenTheCommand(
                EventCommands.Event.DOWNLOAD_COMPLETED, "notify --file=%FILE \"%NAME (%SIZE bytes)\" %HASH");
        this.givenTheRunner();

        this.whenTheEngineSays(this.completed("My Holiday.avi"));
        this.whenTheWaitingCommandsRun();

        this.thenTheCommandLaunchedIs(List.of(
                "notify",
                "--file=" + this.dir.resolve("My Holiday.avi").toAbsolutePath(),
                "My Holiday.avi (1234 bytes)",
                HASH.toHex()));
    }

    @Test
    void shouldRunNothingForACommandThatIsOff() {
        this.givenTheCommand(EventCommands.Event.DOWNLOAD_COMPLETED, "notify %NAME");
        this.givenTheCommandIsTurnedOff(EventCommands.Event.DOWNLOAD_COMPLETED);
        this.givenTheRunner();

        this.whenTheEngineSays(this.completed("a.iso"));
        this.whenTheWaitingCommandsRun();

        this.thenNothingWasLaunched();
    }

    @Test
    void shouldTellTheReasonWhenADownloadFails() {
        this.givenTheCommand(EventCommands.Event.DOWNLOAD_FAILED, "alert %NAME %MESSAGE %FILE");
        this.givenTheRunner();

        this.whenTheEngineSays(new EngineEvent.DownloadFailed(this.download("b.iso"), "disk full"));
        this.whenTheWaitingCommandsRun();

        this.thenTheCommandLaunchedIs(List.of("alert", "b.iso", "disk full", ""));
    }

    @Test
    void shouldNameTheSenderOrItsAddressForAChatMessage() {
        this.givenTheCommand(EventCommands.Event.CHAT_MESSAGE, "say %SENDER: %MESSAGE");
        this.givenTheRunner();

        this.whenTheEngineSays(this.message(Optional.of("ann"), "hello there"));
        this.whenTheEngineSays(this.message(Optional.empty(), "hi"));
        this.whenTheWaitingCommandsRun();

        this.thenTheCommandsLaunchedAre(
                List.of(List.of("say", "ann:", "hello there"), List.of("say", "192.0.2.7:4662:", "hi")));
    }

    @Test
    void shouldKeepAQuoteInAMessageFromSplittingTheWindowsCommandLine() {
        this.givenTheCommand(EventCommands.Event.CHAT_MESSAGE, "say %MESSAGE");
        this.givenTheSystemIsWindows();
        this.givenTheRunner();

        this.whenTheEngineSays(this.message(Optional.of("eve"), "x\" --delete-all \"y"));
        this.whenTheWaitingCommandsRun();

        this.thenTheCommandLaunchedIs(List.of("say", "x' --delete-all 'y"));
    }

    @Test
    void shouldRunTheDiskCommandOnlyWhenSpaceRunsShort() {
        this.givenTheCommand(EventCommands.Event.DISK_SPACE_SHORT, "warn %FILE");
        this.givenTheRunner();

        this.whenTheEngineSays(new EngineEvent.DiskSpace(this.dir, true));
        this.whenTheEngineSays(new EngineEvent.DiskSpace(this.dir, false));
        this.whenTheWaitingCommandsRun();

        this.thenTheCommandLaunchedIs(List.of("warn", this.dir.toAbsolutePath().toString()));
    }

    @Test
    void shouldRunNothingForACommandWithAQuoteLeftOpen() {
        this.givenTheCommand(EventCommands.Event.DOWNLOAD_COMPLETED, "notify \"%NAME");
        this.givenTheRunner();

        this.whenTheEngineSays(this.completed("c.iso"));
        this.whenTheWaitingCommandsRun();

        this.thenNothingWasLaunched();
    }

    @Test
    void shouldRunOnlyAFewCommandsAtOnce() {
        this.givenTheCommand(EventCommands.Event.DOWNLOAD_COMPLETED, "notify %NAME");
        this.givenTheRunner();

        this.whenDownloadsComplete(EventCommandRunner.MAX_RUNNING + 2);

        this.thenCommandsAreWaitingToRun(EventCommandRunner.MAX_RUNNING);
    }

    @Test
    void shouldRunAgainOnceACommandHasEnded() {
        this.givenTheCommand(EventCommands.Event.DOWNLOAD_COMPLETED, "notify %NAME");
        this.givenTheRunner();
        this.givenDownloadsCompleted(EventCommandRunner.MAX_RUNNING);

        this.whenTheWaitingCommandsRun();
        this.whenDownloadsComplete(1);

        this.thenCommandsAreWaitingToRun(1);
    }

    @Test
    void shouldFreeTheSlotOfACommandThatCannotStart() {
        this.givenTheCommand(EventCommands.Event.DOWNLOAD_COMPLETED, "missing-program %NAME");
        this.givenTheLauncherFails();
        this.givenTheRunner();
        this.givenDownloadsCompleted(EventCommandRunner.MAX_RUNNING);

        this.whenTheWaitingCommandsRun();
        this.whenDownloadsComplete(EventCommandRunner.MAX_RUNNING);

        this.thenCommandsAreWaitingToRun(EventCommandRunner.MAX_RUNNING);
    }

    private void givenTheCommand(EventCommands.Event event, String commandLine) {
        this.commands = this.commands.with(event, new EventCommands.Command(true, commandLine));
    }

    private void givenTheCommandIsTurnedOff(EventCommands.Event event) {
        this.commands = this.commands.with(
                event, new EventCommands.Command(false, this.commands.get(event).commandLine()));
    }

    private void givenTheSystemIsWindows() {
        this.windows = true;
    }

    private void givenTheLauncherFails() {
        this.launcherFails = true;
    }

    private void givenTheRunner() {
        this.runner = new EventCommandRunner(
                () -> this.commands,
                command -> {
                    if (this.launcherFails) {
                        throw new IOException("no such program");
                    }
                    this.launched.add(command);
                },
                this.waiting::add,
                this.windows);
    }

    private void givenDownloadsCompleted(int count) {
        this.whenDownloadsComplete(count);
    }

    private void whenTheEngineSays(EngineEvent event) {
        this.runner.handle(event);
    }

    private void whenDownloadsComplete(int count) {
        for (int i = 0; i < count; i++) {
            this.runner.handle(this.completed("file" + i));
        }
    }

    private void whenTheWaitingCommandsRun() {
        while (!this.waiting.isEmpty()) {
            this.waiting.removeFirst().run();
        }
    }

    private void thenTheCommandLaunchedIs(List<String> expected) {
        assertThat(this.launched).isEqualTo(List.of(expected));
    }

    private void thenTheCommandsLaunchedAre(List<List<String>> expected) {
        assertThat(this.launched).isEqualTo(expected);
    }

    private void thenNothingWasLaunched() {
        assertThat(this.launched).isEmpty();
    }

    private void thenCommandsAreWaitingToRun(int count) {
        assertThat(this.waiting).hasSize(count);
    }

    private EngineEvent completed(String name) {
        return new EngineEvent.DownloadCompleted(this.download(name), this.dir.resolve(name));
    }

    private DownloadInfo download(String name) {
        return new DownloadInfo(
                HASH,
                name,
                1234,
                1234,
                DownloadInfo.Status.COMPLETE,
                DownloadPriority.NORMAL,
                0,
                0,
                0,
                0,
                PartMap.EMPTY);
    }

    private EngineEvent message(Optional<String> nick, String text) {
        return new EngineEvent.MessageReceived("192.0.2.7:4662", HASH, nick, text);
    }
}
