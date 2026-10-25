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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Keeps the computer from sleeping while files are transferred, when the preferences ask for it, through a small
 * program of the system that runs only as long as it is needed and ends with njMule anyway: the Windows launcher of the
 * desktop app ({@code --keep-awake}), {@code caffeinate} on macOS, {@code systemd-inhibit} on Linux.
 */
final class KeepAwake implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(KeepAwake.class);

    /** How long the computer stays awake after the last transfer, so that a pause between two does not let it go. */
    static final Duration LINGER = Duration.ofMinutes(2);

    /** Starts the program that keeps the computer awake. */
    @FunctionalInterface
    interface Starter {
        Process start(List<String> command) throws IOException;
    }

    private final Optional<List<String>> command;
    private final Starter starter;
    private final Object lock = new Object();
    private Process helper;
    private Instant lastTransfer;
    private boolean failed;

    KeepAwake(Optional<List<String>> command, Starter starter) {
        this.command = command;
        this.starter = starter;
    }

    static KeepAwake forThisSystem() {
        Optional<Path> program = ProcessHandle.current().info().command().map(Path::of);
        return new KeepAwake(
                command(
                        System.getProperty("os.name", ""),
                        ProcessHandle.current().pid(),
                        program,
                        Files::isRegularFile),
                c -> new ProcessBuilder(c)
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .redirectError(ProcessBuilder.Redirect.DISCARD)
                        .start());
    }

    /**
     * The program that keeps the computer awake for the njMule running as {@code pid}; empty where there is none, as on
     * Windows without the launcher beside {@code program} (a development build).
     */
    static Optional<List<String>> command(String osName, long pid, Optional<Path> program, Predicate<Path> isFile) {
        String os = osName.toLowerCase(Locale.ROOT);
        String id = String.valueOf(pid);
        if (os.startsWith("windows")) {
            return program.map(p -> p.resolveSibling("njmule-gui.exe"))
                    .filter(isFile)
                    .map(launcher -> List.of(launcher.toString(), "--keep-awake", id));
        }
        if (os.startsWith("mac")) {
            return Optional.of(List.of("caffeinate", "-i", "-w", id));
        }
        // tail ends with njMule, and the lock with it.
        return Optional.of(List.of(
                "systemd-inhibit",
                "--what=sleep:idle",
                "--who=njMule",
                "--why=Transfers in progress",
                "--mode=block",
                "tail",
                "--pid=" + id,
                "-f",
                "/dev/null"));
    }

    /** Holds the computer awake while {@code wanted} and files are transferred, and lets it go a while after. */
    void update(boolean wanted, boolean transferring, Instant now) {
        synchronized (this.lock) {
            change(wanted, transferring, now);
        }
    }

    private void change(boolean wanted, boolean transferring, Instant now) {
        if (transferring) {
            this.lastTransfer = now;
        }
        boolean keep = wanted
                && this.command.isPresent()
                && !this.failed
                && this.lastTransfer != null
                && Duration.between(this.lastTransfer, now).compareTo(LINGER) < 0;
        if (this.helper != null && !this.helper.isAlive()) {
            // Ended by itself: the system has no such program, or refuses it; not started again.
            logger.info(
                    "Cannot keep the computer awake: {} ended",
                    this.command.get().getFirst());
            this.helper = null;
            this.failed = true;
            return;
        }
        if (keep && this.helper == null) {
            try {
                this.helper = this.starter.start(this.command.get());
                logger.debug("Keeping the computer awake while files are transferred");
            } catch (IOException e) {
                logger.info("Cannot keep the computer awake: {}", e.toString());
                this.failed = true;
            }
        } else if (!keep && this.helper != null) {
            stop();
        }
    }

    boolean awake() {
        synchronized (this.lock) {
            return this.helper != null && this.helper.isAlive();
        }
    }

    @Override
    public void close() {
        synchronized (this.lock) {
            stop();
        }
    }

    private void stop() {
        if (this.helper != null) {
            this.helper.destroy();
            this.helper = null;
        }
    }
}
