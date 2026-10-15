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

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.njmule.core.api.EngineEvent;
import org.njmule.core.config.EventCommands;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs the user's commands on engine events. The program is started directly, never through a shell, on a thread of its
 * own: the engine and the event thread never wait for it, and only a few run at once.
 */
final class EventCommandRunner implements Flow.Subscriber<EngineEvent> {

    /** Starts a program. */
    @FunctionalInterface
    interface Launcher {
        /** Runs {@code command}, the program first, until it ends; what it writes is discarded. */
        void run(List<String> command) throws IOException, InterruptedException;
    }

    /** Commands running at once at most; the events beyond them run nothing. */
    static final int MAX_RUNNING = 4;

    private static final Logger logger = LoggerFactory.getLogger(EventCommandRunner.class);
    private static final Pattern PLACEHOLDER = Pattern.compile("%(NAME|FILE|HASH|SIZE|SENDER|MESSAGE)");

    private final Supplier<EventCommands> commands;
    private final Launcher launcher;
    private final Executor executor;
    private final boolean windows;
    private final Semaphore running = new Semaphore(MAX_RUNNING);

    /**
     * @param commands the commands of the current preferences
     * @param executor runs each command, on a thread that may wait for it
     * @param windows whether the system takes a command line as one text, as Windows does
     */
    EventCommandRunner(Supplier<EventCommands> commands, Launcher launcher, Executor executor, boolean windows) {
        this.commands = Objects.requireNonNull(commands, "commands");
        this.launcher = Objects.requireNonNull(launcher, "launcher");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.windows = windows;
    }

    /** Starts {@code command} and waits for its end, with nothing to read on its input and its output discarded. */
    static void launch(List<String> command) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        process.getOutputStream().close();
        int exit = process.waitFor();
        if (exit != 0) {
            logger.info("{} ended with exit code {}", command.getFirst(), exit);
        }
    }

    @Override
    public void onSubscribe(Flow.Subscription subscription) {
        subscription.request(Long.MAX_VALUE);
    }

    @Override
    public void onNext(EngineEvent event) {
        handle(event);
    }

    @Override
    public void onError(Throwable throwable) {
        logger.debug("No more events for the event commands: {}", throwable.toString());
    }

    @Override
    public void onComplete() {
        // The engine stopped: commands still running go on by themselves.
    }

    /** Runs the command of {@code event}, if it has one and it is on. */
    void handle(EngineEvent event) {
        switch (event) {
            case EngineEvent.DownloadCompleted e ->
                run(
                        EventCommands.Event.DOWNLOAD_COMPLETED,
                        Map.of(
                                "NAME",
                                e.download().name(),
                                "FILE",
                                e.file().toAbsolutePath().toString(),
                                "HASH",
                                e.download().hash().toHex(),
                                "SIZE",
                                Long.toString(e.download().size())));
            case EngineEvent.DownloadFailed e ->
                run(
                        EventCommands.Event.DOWNLOAD_FAILED,
                        Map.of(
                                "NAME",
                                e.download().name(),
                                "HASH",
                                e.download().hash().toHex(),
                                "SIZE",
                                Long.toString(e.download().size()),
                                "MESSAGE",
                                e.reason()));
            case EngineEvent.MessageReceived e ->
                run(
                        EventCommands.Event.CHAT_MESSAGE,
                        Map.of("SENDER", e.nick().orElse(e.address()), "MESSAGE", e.text()));
            case EngineEvent.DiskSpace e
            when !e.enough() ->
                run(
                        EventCommands.Event.DISK_SPACE_SHORT,
                        Map.of("FILE", e.directory().toAbsolutePath().toString()));
            default -> {
                // No command runs on the other events.
            }
        }
    }

    private void run(EventCommands.Event event, Map<String, String> values) {
        EventCommands.Command command = this.commands.get().get(event);
        if (!command.enabled() || command.commandLine().isEmpty()) {
            return;
        }
        List<String> arguments;
        try {
            arguments = EventCommands.arguments(command.commandLine()).stream()
                    .map(argument -> fill(argument, values))
                    .toList();
        } catch (IllegalArgumentException e) {
            logger.warn("The command for {} cannot run: {}", event.id(), e.getMessage());
            return;
        }
        if (arguments.isEmpty() || arguments.getFirst().isEmpty()) {
            return;
        }
        if (!this.running.tryAcquire()) {
            logger.warn("{} event commands are still running: the one for {} does not run", MAX_RUNNING, event.id());
            return;
        }
        try {
            this.executor.execute(() -> {
                try {
                    logger.info("Running {} for {}", arguments.getFirst(), event.id());
                    this.launcher.run(arguments);
                } catch (IOException | RuntimeException e) {
                    logger.warn("Cannot run {} for {}: {}", arguments.getFirst(), event.id(), e.toString());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    this.running.release();
                }
            });
        } catch (RejectedExecutionException e) {
            this.running.release();
        }
    }

    // The values come from the network (names, messages): they only ever fill in text, never become a format.
    private String fill(String argument, Map<String, String> values) {
        return PLACEHOLDER
                .matcher(argument)
                .replaceAll(m -> Matcher.quoteReplacement(safe(values.getOrDefault(m.group(1), ""))));
    }

    // On Windows the arguments travel as one command line, where a quote in a name or a message would split it.
    private String safe(String value) {
        return this.windows ? value.replace('"', '\'') : value;
    }
}
