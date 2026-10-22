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
package org.njmule.core.update;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.njmule.core.api.InstallKind;
import org.njmule.core.api.NjMuleInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The installer of a new version unpacked by njMule, run by the old version's program in a process of its own once the
 * old njMule has handed over: it waits for that njMule to end, swaps the files, asks the new program its version and,
 * when njMule is to start again, starts the new version and watches it until it has run its engine 30 s. A new version
 * that does not answer, or stops with an error before, gives its place back to the old one, which starts again. How far
 * it got goes into the plan, which the next start of njMule reads.
 */
public final class UpdateHelper {

    static final String JOURNAL = "swap.journal";
    static final Duration DEFAULT_WAIT = Duration.ofSeconds(60);
    static final Duration DEFAULT_WATCH = Duration.ofMinutes(10);

    private static final Logger logger = LoggerFactory.getLogger(UpdateHelper.class);
    private static final Duration PROBE_TIME = Duration.ofSeconds(60);
    private static final Duration DEFAULT_POLL = Duration.ofMillis(500);

    private final Path planFile;
    private final UpdateFiles files;
    private final Commands commands;
    private final Launcher launcher;
    private final InstantSource clock;
    private final Duration wait;
    private final Duration watch;
    private final Duration poll;

    UpdateHelper(
            Path planFile,
            Commands commands,
            Launcher launcher,
            InstantSource clock,
            Duration wait,
            Duration watch,
            Duration poll) {
        this.planFile = Objects.requireNonNull(planFile, "planFile");
        this.files = new UpdateFiles(UpdateFiles.parent(planFile));
        this.commands = Objects.requireNonNull(commands, "commands");
        this.launcher = Objects.requireNonNull(launcher, "launcher");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.wait = Objects.requireNonNull(wait, "wait");
        this.watch = Objects.requireNonNull(watch, "watch");
        this.poll = Objects.requireNonNull(poll, "poll");
    }

    /** The installer of the plan in {@code planFile}, with the real programs and processes of this computer. */
    static UpdateHelper of(Path planFile) {
        return new UpdateHelper(
                planFile,
                Commands.SYSTEM,
                Launcher.SYSTEM,
                InstantSource.system(),
                DEFAULT_WAIT,
                DEFAULT_WATCH,
                DEFAULT_POLL);
    }

    /** As {@link org.njmule.core.api.UpdateHandOver#install}. */
    public static int install(Path planFile, List<Long> waitFor) {
        return of(planFile).run(waitFor);
    }

    /** Installs once the processes {@code waitFor} have ended; returns the exit code of this process. */
    int run(List<Long> waitFor) {
        ApplyPlan plan;
        try {
            plan = ApplyPlan.load(this.planFile).orElse(null);
        } catch (IOException e) {
            logger.error("Cannot read the installation plan: {}", e.toString());
            return 1;
        }
        if (plan == null || plan.staged().isEmpty() || plan.target().isEmpty()) {
            logger.error("No installation to do in {}", this.planFile);
            return 1;
        }
        plan = plan.withHelper(ProcessHandle.current().pid(), this.clock.instant());
        save(plan);
        for (long pid : waitFor) {
            if (!awaitEnd(pid)) {
                save(plan.with(
                        ApplyPlan.State.FAILED,
                        Optional.of(NjMuleInfo.NAME + " did not stop within " + this.wait.toSeconds() + " s"),
                        this.clock.instant()));
                return 1;
            }
        }
        logger.info(
                "Installing {} {} in {}",
                NjMuleInfo.NAME,
                plan.version(),
                plan.target().get());
        Optional<String> failure = place(plan, this.files, this.commands);
        if (failure.isPresent()) {
            logger.error("The installation failed: {}", failure.get());
            save(plan.with(ApplyPlan.State.FAILED, failure, this.clock.instant()));
            restartOld(plan);
            return 1;
        }
        plan = plan.with(ApplyPlan.State.INSTALLED, Optional.empty(), this.clock.instant());
        save(plan);
        if (!plan.restart() || plan.program().isEmpty()) {
            return 0;
        }
        Process started;
        try {
            started = this.launcher.start(command(plan), Optional.empty());
        } catch (IOException e) {
            return rollBack(plan, "the new version does not start: " + e.getMessage());
        }
        return watch(plan, started);
    }

    /**
     * Swaps the files of {@code plan} and asks the new program its version; puts the old files back when it does not
     * answer. Returns why it failed.
     */
    static Optional<String> place(ApplyPlan plan, UpdateFiles files, Commands commands) {
        Swap swap = swap(plan, files, commands);
        try {
            swap.apply();
        } catch (IOException | RuntimeException e) {
            return Optional.of("cannot put the new files in place: " + e.getMessage());
        }
        Optional<String> wrong = probe(plan, commands);
        if (wrong.isPresent()) {
            try {
                swap.rollBack();
            } catch (IOException e) {
                return Optional.of(wrong.get() + "; the old files could not all be put back: " + e.getMessage());
            }
        }
        return wrong;
    }

    static Swap swap(ApplyPlan plan, UpdateFiles files, Commands commands) {
        Path journal = files.version(plan.version()).resolve(JOURNAL);
        Path staged = plan.staged().orElseThrow();
        Path target = plan.target().orElseThrow();
        return plan.kind() == InstallKind.DMG
                ? Swap.bundle(staged, target, journal, Swap.ditto(commands))
                : Swap.folder(staged, target, journal);
    }

    // The new program, asked as the user would: it must name the version installed.
    private static Optional<String> probe(ApplyPlan plan, Commands commands) {
        if (plan.probe().isEmpty()) {
            return Optional.empty();
        }
        Path program = plan.target().orElseThrow().resolve(plan.probe().get());
        try {
            Commands.Result answer = commands.run(List.of(program.toString(), "--version"), PROBE_TIME);
            if (answer.succeeded() && answer.output().contains(NjMuleInfo.NAME + " " + plan.version())) {
                return Optional.empty();
            }
            return Optional.of("the new version does not start: "
                    + (answer.succeeded() ? answer.output().strip() : "it ended with code " + answer.exitCode()));
        } catch (IOException e) {
            return Optional.of("the new version does not start: " + e.getMessage());
        }
    }

    // Until the new version leaves its mark; an error before puts the old version back.
    private int watch(ApplyPlan plan, Process started) {
        Path mark = this.files.started(plan.version());
        Instant end = this.clock.instant().plus(this.watch);
        while (this.clock.instant().isBefore(end)) {
            if (Files.exists(mark)) {
                return 0;
            }
            if (!started.isAlive()) {
                int code = started.exitValue();
                // Closed early by the user: the old files stay until the new version has run long enough.
                if (code == 0 || Files.exists(mark)) {
                    return 0;
                }
                return rollBack(plan, "the new version stopped with code " + code + " before it had run 30 s");
            }
            try {
                Thread.sleep(this.poll);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return 0;
            }
        }
        logger.warn("{} {} did not report within {} min", NjMuleInfo.NAME, plan.version(), this.watch.toMinutes());
        return 0;
    }

    private int rollBack(ApplyPlan plan, String why) {
        logger.error("Going back to {} {}: {}", NjMuleInfo.NAME, plan.from(), why);
        String reason = why;
        try {
            swap(plan, this.files, this.commands).rollBack();
        } catch (IOException | RuntimeException e) {
            reason = why + "; the old files could not all be put back: " + e.getMessage();
        }
        save(plan.with(ApplyPlan.State.ROLLED_BACK, Optional.of(reason), this.clock.instant()));
        restartOld(plan);
        return 1;
    }

    private void restartOld(ApplyPlan plan) {
        if (!plan.restart() || plan.program().isEmpty()) {
            return;
        }
        try {
            this.launcher.start(command(plan), Optional.empty());
        } catch (IOException e) {
            logger.error("Cannot start {} again: {}", NjMuleInfo.NAME, e.toString());
        }
    }

    static List<String> command(ApplyPlan plan) {
        List<String> command = new ArrayList<>();
        command.add(plan.program().orElseThrow().toString());
        command.addAll(plan.arguments());
        return command;
    }

    private boolean awaitEnd(long pid) {
        Optional<ProcessHandle> process = ProcessHandle.of(pid);
        if (process.isEmpty()) {
            return true;
        }
        try {
            process.get().onExit().get(this.wait.toMillis(), TimeUnit.MILLISECONDS);
            return true;
        } catch (TimeoutException e) {
            return false;
        } catch (ExecutionException e) {
            return !process.get().isAlive();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private void save(ApplyPlan plan) {
        try {
            plan.save(this.planFile);
        } catch (IOException e) {
            logger.warn("Cannot keep the installation plan: {}", e.toString());
        }
    }
}
