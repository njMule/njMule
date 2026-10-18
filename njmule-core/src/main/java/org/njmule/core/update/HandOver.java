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
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import org.njmule.core.api.InstallKind;
import org.njmule.core.api.NjMuleInfo;
import org.njmule.core.api.UpdateHandOver;
import org.njmule.core.api.UpdateStatus.Installer;
import org.njmule.core.config.OsFamily;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The hand-over of a new version from a stopped engine: it writes the plan and starts the installer, which waits for
 * this process to end. An MSI goes to the Windows launcher, copied out of the folder Windows Installer replaces, which
 * runs {@code msiexec} and starts njMule again; an unpacked archive or bundle goes to {@link UpdateHelper}, run by this
 * version's program.
 */
final class HandOver implements UpdateHandOver {

    static final String LAUNCHER = "njmule-gui.exe";
    static final String INSTALLER_LOG = "install.log";
    static final String INSTALLER_RESULT = "msiexec.txt";
    static final String HELPER_LOG = "helper.log";

    private static final Logger logger = LoggerFactory.getLogger(HandOver.class);
    // How long the installer has to say that it runs.
    private static final Duration SIGNAL = Duration.ofSeconds(15);
    private static final Duration POLL = Duration.ofMillis(100);

    private final ApplyPlan plan;
    private final Installation installation;
    private final UpdateFiles files;
    private final Commands commands;
    private final Launcher launcher;
    private final InstantSource clock;
    private final boolean desktop;

    /**
     * @param plan the installation, its program and arguments still to come
     * @param desktop whether this is the Windows desktop app, which starts again through its launcher
     */
    HandOver(
            ApplyPlan plan,
            Installation installation,
            UpdateFiles files,
            Commands commands,
            Launcher launcher,
            InstantSource clock,
            boolean desktop) {
        this.plan = Objects.requireNonNull(plan, "plan");
        this.installation = Objects.requireNonNull(installation, "installation");
        this.files = Objects.requireNonNull(files, "files");
        this.commands = Objects.requireNonNull(commands, "commands");
        this.launcher = Objects.requireNonNull(launcher, "launcher");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.desktop = desktop;
    }

    /** Whether this process is the Windows desktop app, started by its launcher. */
    static boolean desktop() {
        return OsFamily.current() == OsFamily.WINDOWS && "1".equals(System.getenv(Launcher.DESKTOP_VARIABLE));
    }

    @Override
    public String version() {
        return this.plan.version();
    }

    @Override
    public boolean restart() {
        return this.plan.restart();
    }

    @Override
    public void start(List<String> arguments) throws IOException {
        ApplyPlan full = planned(this.plan.restart(), arguments);
        Files.deleteIfExists(this.files.started(full.version()));
        full.save(this.files.plan());
        logger.info("Handing over the installation of {} {}", NjMuleInfo.NAME, full.version());
        if (full.kind() == InstallKind.MSI) {
            startInstaller(full);
        } else {
            startHelper(full);
        }
    }

    @Override
    public boolean installHere() throws IOException {
        if (this.plan.kind() == InstallKind.MSI) {
            ApplyPlan alone = planned(false, List.of());
            alone.save(this.files.plan());
            startInstaller(alone);
            return false;
        }
        ApplyPlan here =
                planned(false, List.of()).withHelper(ProcessHandle.current().pid(), this.clock.instant());
        here.save(this.files.plan());
        Optional<String> failure = UpdateHelper.place(here, this.files, this.commands);
        if (failure.isPresent()) {
            here.with(ApplyPlan.State.FAILED, failure, this.clock.instant()).save(this.files.plan());
            throw new IOException(failure.get());
        }
        here.with(ApplyPlan.State.INSTALLED, Optional.empty(), this.clock.instant())
                .save(this.files.plan());
        return true;
    }

    private ApplyPlan planned(boolean restart, List<String> arguments) {
        Optional<Path> program = restart ? program() : Optional.empty();
        return new ApplyPlan(
                this.plan.version(),
                this.plan.from(),
                this.plan.kind(),
                this.plan.packageFile(),
                this.plan.staged(),
                this.plan.target(),
                this.plan.probe(),
                restart && program.isPresent(),
                this.plan.unattended(),
                program,
                program.isPresent() ? arguments : List.of(),
                ApplyPlan.State.HANDED_OVER,
                Optional.empty(),
                OptionalLong.empty(),
                this.clock.instant());
    }

    // The installed program that starts njMule as the user does: on Windows the desktop app's launcher.
    private Optional<Path> program() {
        Optional<Path> executable = this.installation.executable();
        if (executable.isEmpty()) {
            return Optional.empty();
        }
        if (this.desktop) {
            Path launcher = executable.get().resolveSibling(LAUNCHER);
            if (Files.isRegularFile(launcher)) {
                return Optional.of(launcher);
            }
        }
        return executable;
    }

    // The launcher runs msiexec once this process and its own parent launcher have ended, then starts njMule again.
    private void startInstaller(ApplyPlan full) throws IOException {
        Path installed = this.installation.executable().orElseThrow().resolveSibling(LAUNCHER);
        Path folder = this.files.version(full.version());
        Path copy = folder.resolve(LAUNCHER);
        Files.createDirectories(folder);
        Files.copy(installed, copy, StandardCopyOption.REPLACE_EXISTING);
        Files.deleteIfExists(folder.resolve(INSTALLER_RESULT));
        List<String> command = new ArrayList<>(
                List.of(copy.toString(), "--apply-update", full.packageFile().toString()));
        waits().forEach(pid -> command.addAll(List.of("--wait", Long.toString(pid))));
        command.addAll(List.of("--ui", full.unattended() ? "quiet" : "passive"));
        if (this.installation.installer() == Installer.ELEVATED) {
            command.add("--all-users");
        }
        command.addAll(List.of(
                "--log",
                folder.resolve(INSTALLER_LOG).toString(),
                "--result",
                folder.resolve(INSTALLER_RESULT).toString()));
        if (full.restart()) {
            command.add("--relaunch");
            command.addAll(UpdateHelper.command(full));
        }
        this.launcher.start(command, Optional.empty());
    }

    // This program, run again as the installer; it says that it runs by taking the plan over.
    private void startHelper(ApplyPlan full) throws IOException {
        Path self = this.installation.executable().orElseThrow();
        List<String> command = new ArrayList<>(
                List.of(self.toString(), "--apply-update", this.files.plan().toString()));
        waits().forEach(pid -> command.addAll(List.of("--wait", Long.toString(pid))));
        Process helper = this.launcher.start(
                command, Optional.of(this.files.version(full.version()).resolve(HELPER_LOG)));
        Instant end = this.clock.instant().plus(SIGNAL);
        while (this.clock.instant().isBefore(end)) {
            Optional<ApplyPlan> now = ApplyPlan.load(this.files.plan());
            if (now.isPresent() && now.get().state() != ApplyPlan.State.HANDED_OVER) {
                return;
            }
            if (!helper.isAlive()) {
                break;
            }
            try {
                Thread.sleep(POLL);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        String why = helper.isAlive()
                ? "the installer did not start in time"
                : "the installer stopped at once with code " + helper.exitValue();
        logger.error("Cannot install {} {}: {}", NjMuleInfo.NAME, full.version(), why);
        helper.destroy();
        full.with(ApplyPlan.State.FAILED, Optional.of(why), this.clock.instant())
                .save(this.files.plan());
        if (full.restart()) {
            // This version again, once this process has ended.
            List<String> again = new ArrayList<>(UpdateHelper.command(full));
            again.addAll(List.of("--wait", Long.toString(ProcessHandle.current().pid())));
            this.launcher.start(again, Optional.empty());
        }
        throw new IOException(why);
    }

    // This process, and the Windows launcher that waits for it, whose file the installer replaces too.
    private List<Long> waits() {
        List<Long> pids = new ArrayList<>(List.of(ProcessHandle.current().pid()));
        Optional<Path> folder = this.installation.executable().map(Path::getParent);
        ProcessHandle.current()
                .parent()
                .filter(parent -> parent.info()
                        .command()
                        .map(Path::of)
                        .filter(c -> String.valueOf(c.getFileName())
                                .toLowerCase(Locale.ROOT)
                                .equals(LAUNCHER))
                        .filter(c -> folder.isPresent() && folder.get().equals(c.getParent()))
                        .isPresent())
                .ifPresent(parent -> pids.add(parent.pid()));
        return pids;
    }
}
