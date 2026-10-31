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

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.core.api.InstallKind;
import org.njmule.core.api.UpdateStatus.Installer;

@Timeout(30)
class HandOverTest {

    private static final Instant NOW = Instant.parse("2026-11-03T08:00:00Z");
    private static final String PID = Long.toString(ProcessHandle.current().pid());

    @TempDir
    Path dir;

    private final List<List<String>> started = new CopyOnWriteArrayList<>();
    private InstallKind kind = InstallKind.WINDOWS_ZIP;
    private Installer installer = Installer.SELF;
    private boolean restart = true;
    private boolean unattended;
    private boolean desktop;
    private boolean helperTakesOver = true;
    private Exception failure;
    private boolean inPlace;

    @Test
    void shouldHandAnArchiveToThisProgramRunAgainAsTheInstaller() throws IOException {
        givenAnInstallation();

        whenHandedOver();

        thenNoFailureOccurred();
        thenTheFirstStartWas(List.of(
                executable().toString(), "--apply-update", files().plan().toString(), "--wait", PID));
        thenThePlanStartsAgainWith(executable(), List.of("--config-dir", "/data"));
    }

    @Test
    void shouldStartAgainThroughTheDesktopWindow() throws IOException {
        givenAnInstallation();
        givenTheDesktopWindow();

        whenHandedOver();

        thenThePlanStartsAgainWith(launcher(), List.of("--config-dir", "/data"));
    }

    @Test
    void shouldStartTheOldVersionAgainWhenTheInstallerDoesNotRun() throws IOException {
        givenAnInstallation();
        givenAnInstallerThatStopsAtOnce();

        whenHandedOver();

        thenItFailedWith("stopped at once");
        thenThePlanSays(ApplyPlan.State.FAILED);
        thenTheLastStartWas(List.of(executable().toString(), "--config-dir", "/data", "--wait", PID));
    }

    @Test
    void shouldHandAnInstallerPackageToACopyOfTheLauncher() throws IOException {
        givenAnInstallation();
        givenTheKind(InstallKind.MSI);
        givenTheDesktopWindow();

        whenHandedOver();

        thenNoFailureOccurred();
        thenTheLauncherWasCopied();
        thenTheFirstStartWas(List.of(
                copiedLauncher().toString(),
                "--apply-update",
                packageFile().toString(),
                "--wait",
                PID,
                "--ui",
                "passive",
                "--log",
                version().resolve(HandOver.INSTALLER_LOG).toString(),
                "--result",
                version().resolve(HandOver.INSTALLER_RESULT).toString(),
                "--relaunch",
                launcher().toString(),
                "--config-dir",
                "/data"));
    }

    @Test
    void shouldInstallQuietlyWhenNobodyWatches() throws IOException {
        givenAnInstallation();
        givenTheKind(InstallKind.MSI);
        givenNobodyWatches();

        whenHandedOver();

        thenTheFirstStartHas("--ui", "quiet");
    }

    @Test
    void shouldLetWindowsAskForAdministratorRights() throws IOException {
        givenAnInstallation();
        givenTheKind(InstallKind.MSI);
        givenTheInstaller(Installer.ELEVATED);

        whenHandedOver();

        thenTheFirstStartHas("--all-users");
    }

    @Test
    void shouldSwapTheFilesFromTheCommandLine() throws IOException {
        givenAnInstallation();
        givenNoRestart();

        whenInstalledHere();

        thenNoFailureOccurred();
        thenItIsInPlace(true);
        thenTheInstalledVersionIs("0.2.0");
        thenThePlanSays(ApplyPlan.State.INSTALLED);
    }

    @Test
    void shouldLeaveAnInstallerPackageToItsInstallerFromTheCommandLine() throws IOException {
        givenAnInstallation();
        givenTheKind(InstallKind.MSI);
        givenNoRestart();

        whenInstalledHere();

        thenItIsInPlace(false);
        thenTheFirstStartHas("--apply-update", packageFile().toString());
        thenTheFirstStartHasNo("--relaunch");
    }

    private void givenAnInstallation() throws IOException {
        write(executable(), "0.1.0");
        write(launcher(), "launcher 0.1.0");
        write(files().unpacked("0.2.0").resolve("njmule.exe"), "0.2.0");
        write(packageFile(), "package");
    }

    private void givenTheKind(InstallKind chosen) {
        this.kind = chosen;
    }

    private void givenTheInstaller(Installer chosen) {
        this.installer = chosen;
    }

    private void givenTheDesktopWindow() {
        this.desktop = true;
    }

    private void givenNobodyWatches() {
        this.unattended = true;
    }

    private void givenNoRestart() {
        this.restart = false;
    }

    private void givenAnInstallerThatStopsAtOnce() {
        this.helperTakesOver = false;
    }

    private void whenHandedOver() {
        try {
            handOver().start(List.of("--config-dir", "/data"));
        } catch (IOException e) {
            this.failure = e;
        }
    }

    private void whenInstalledHere() {
        try {
            this.inPlace = handOver().installHere();
        } catch (IOException e) {
            this.failure = e;
        }
    }

    private void thenNoFailureOccurred() {
        assertThat(this.failure).isNull();
    }

    private void thenItFailedWith(String words) {
        assertThat(this.failure).isInstanceOf(IOException.class).hasMessageContaining(words);
    }

    private void thenTheFirstStartWas(List<String> command) {
        assertThat(this.started).first().isEqualTo(command);
    }

    private void thenTheLastStartWas(List<String> command) {
        assertThat(this.started).last().isEqualTo(command);
    }

    private void thenTheFirstStartHas(String... words) {
        assertThat(this.started.getFirst()).containsSubsequence(words);
    }

    private void thenTheFirstStartHasNo(String word) {
        assertThat(this.started.getFirst()).doesNotContain(word);
    }

    private void thenThePlanStartsAgainWith(Path program, List<String> arguments) throws IOException {
        ApplyPlan plan = ApplyPlan.load(files().plan()).orElseThrow();
        assertThat(plan.program()).contains(program);
        assertThat(plan.arguments()).isEqualTo(arguments);
        assertThat(plan.restart()).isTrue();
    }

    private void thenThePlanSays(ApplyPlan.State state) throws IOException {
        assertThat(ApplyPlan.load(files().plan()).orElseThrow().state()).isEqualTo(state);
    }

    private void thenTheLauncherWasCopied() throws IOException {
        assertThat(Files.readString(copiedLauncher())).isEqualTo("launcher 0.1.0");
    }

    private void thenItIsInPlace(boolean expected) {
        assertThat(this.inPlace).isEqualTo(expected);
    }

    private void thenTheInstalledVersionIs(String version) throws IOException {
        assertThat(Files.readString(executable())).isEqualTo(version);
    }

    private HandOver handOver() {
        boolean swapped = this.kind != InstallKind.MSI;
        Path root = executable().getParent();
        ApplyPlan plan = new ApplyPlan(
                "0.2.0",
                "0.1.0",
                this.kind,
                packageFile(),
                swapped ? Optional.of(files().unpacked("0.2.0")) : Optional.empty(),
                swapped ? Optional.of(root) : Optional.empty(),
                swapped ? Optional.of("njmule.exe") : Optional.empty(),
                this.restart,
                this.unattended,
                Optional.empty(),
                List.of(),
                ApplyPlan.State.HANDED_OVER,
                Optional.empty(),
                OptionalLong.empty(),
                NOW);
        Installation installation =
                new Installation(this.kind, "windows-x64", Optional.of(executable()), this.installer);
        return new HandOver(
                plan,
                installation,
                files(),
                (command, timeout) -> new Commands.Result(0, "njMule 0.2.0 (GPL-3.0-or-later)"),
                (command, log) -> start(command),
                () -> NOW,
                this.desktop);
    }

    // The installer of an archive says that it runs by taking the plan over.
    private Process start(List<String> command) throws IOException {
        this.started.add(new ArrayList<>(command));
        if (command.contains("--apply-update") && this.kind != InstallKind.MSI) {
            if (!this.helperTakesOver) {
                return FakeProcess.ended(1);
            }
            ApplyPlan plan = ApplyPlan.load(files().plan()).orElseThrow();
            plan.withHelper(4242, NOW).save(files().plan());
        }
        return new FakeProcess();
    }

    private Path executable() {
        return this.dir.resolve("installed").resolve("njmule.exe");
    }

    private Path launcher() {
        return this.dir.resolve("installed").resolve(HandOver.LAUNCHER_FILE);
    }

    private Path copiedLauncher() {
        return version().resolve(HandOver.LAUNCHER_FILE);
    }

    private Path packageFile() {
        return version().resolve("njmule-0.2.0-windows-x64." + (this.kind == InstallKind.MSI ? "msi" : "zip"));
    }

    private Path version() {
        return files().version("0.2.0");
    }

    private UpdateFiles files() {
        return new UpdateFiles(this.dir.resolve("cache").resolve("update"));
    }

    private static void write(Path file, String text) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }
}
