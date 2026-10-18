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
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.core.api.InstallKind;

@Timeout(30)
class UpdateHelperTest {

    private static final Instant NOW = Instant.parse("2026-11-03T08:00:00Z");

    @TempDir
    Path dir;

    private final List<List<String>> started = new CopyOnWriteArrayList<>();
    private Path target;
    private Path staged;
    private boolean restart = true;
    private String answer = "njMule 0.2.0 (GPL-3.0-or-later)";
    private int answerCode;
    private Behaviour newVersion = Behaviour.PROVES_ITSELF;
    private int code;

    /** What the new version does once started. */
    private enum Behaviour {
        PROVES_ITSELF,
        FAILS,
        CLOSED_AT_ONCE
    }

    @Test
    void shouldInstallTheNewVersionAndStartIt() throws IOException {
        givenTheInstalledVersion("0.1.0");
        givenTheNewVersionUnpacked("0.2.0");

        whenTheHelperRuns();

        thenItEndedWith(0);
        thenTheInstalledVersionIs("0.2.0");
        thenTheOldVersionIsKept("0.1.0");
        thenThePlanSays(ApplyPlan.State.INSTALLED);
        thenItStarted(List.of(program(), "--config-dir", "/data"));
    }

    @Test
    void shouldGoBackWhenTheNewVersionStopsWithAnError() throws IOException {
        givenTheInstalledVersion("0.1.0");
        givenTheNewVersionUnpacked("0.2.0");
        givenTheNewVersion(Behaviour.FAILS);

        whenTheHelperRuns();

        thenItEndedWith(1);
        thenTheInstalledVersionIs("0.1.0");
        thenThePlanSays(ApplyPlan.State.ROLLED_BACK);
        thenThePlanTellsWhy("stopped with code 3");
        thenItStartedTimes(2);
    }

    @Test
    void shouldKeepTheOldVersionWhenTheNewOneDoesNotAnswer() throws IOException {
        givenTheInstalledVersion("0.1.0");
        givenTheNewVersionUnpacked("0.2.0");
        givenTheNewProgramAnswers(1, "cannot load glass.dll");

        whenTheHelperRuns();

        thenItEndedWith(1);
        thenTheInstalledVersionIs("0.1.0");
        thenThePlanSays(ApplyPlan.State.FAILED);
        thenThePlanTellsWhy("does not start");
        thenItStarted(List.of(program(), "--config-dir", "/data"));
    }

    @Test
    void shouldRefuseANewProgramThatNamesAnotherVersion() throws IOException {
        givenTheInstalledVersion("0.1.0");
        givenTheNewVersionUnpacked("0.2.0");
        givenTheNewProgramAnswers(0, "njMule 0.1.0 (GPL-3.0-or-later)");

        whenTheHelperRuns();

        thenTheInstalledVersionIs("0.1.0");
        thenThePlanSays(ApplyPlan.State.FAILED);
    }

    @Test
    void shouldOnlyInstallWhenNjMuleExits() throws IOException {
        givenTheInstalledVersion("0.1.0");
        givenTheNewVersionUnpacked("0.2.0");
        givenNoRestart();

        whenTheHelperRuns();

        thenItEndedWith(0);
        thenTheInstalledVersionIs("0.2.0");
        thenTheOldVersionIsKept("0.1.0");
        thenNothingStarted();
    }

    @Test
    void shouldKeepTheNewVersionWhenTheUserClosesItAtOnce() throws IOException {
        givenTheInstalledVersion("0.1.0");
        givenTheNewVersionUnpacked("0.2.0");
        givenTheNewVersion(Behaviour.CLOSED_AT_ONCE);

        whenTheHelperRuns();

        thenItEndedWith(0);
        thenTheInstalledVersionIs("0.2.0");
        thenThePlanSays(ApplyPlan.State.INSTALLED);
    }

    @Test
    void shouldDoNothingWithoutAPlan() {
        whenTheHelperRuns();

        thenItEndedWith(1);
        thenNothingStarted();
    }

    private void givenTheInstalledVersion(String version) throws IOException {
        this.target = this.dir.resolve("installed");
        write(this.target.resolve("njmule.exe"), version);
    }

    private void givenTheNewVersionUnpacked(String version) throws IOException {
        this.staged = files().unpacked(version);
        write(this.staged.resolve("njmule.exe"), version);
    }

    private void givenTheNewVersion(Behaviour behaviour) {
        this.newVersion = behaviour;
    }

    private void givenTheNewProgramAnswers(int exitCode, String text) {
        this.answerCode = exitCode;
        this.answer = text;
    }

    private void givenNoRestart() {
        this.restart = false;
    }

    private void whenTheHelperRuns() {
        UpdateFiles where = files();
        try {
            if (this.staged != null) {
                plan().save(where.plan());
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        UpdateHelper helper = new UpdateHelper(
                where.plan(),
                (command, timeout) -> new Commands.Result(this.answerCode, this.answer),
                (command, log) -> start(command),
                InstantSource.system(),
                Duration.ofSeconds(1),
                Duration.ofSeconds(10),
                Duration.ofMillis(10));
        this.code = helper.run(List.of());
    }

    private Process start(List<String> command) throws IOException {
        this.started.add(command);
        if (this.started.size() > 1) {
            return FakeProcess.ended(0);
        }
        return switch (this.newVersion) {
            case PROVES_ITSELF -> {
                Files.createFile(files().started("0.2.0"));
                yield new FakeProcess();
            }
            case FAILS -> FakeProcess.ended(3);
            case CLOSED_AT_ONCE -> FakeProcess.ended(0);
        };
    }

    private void thenItEndedWith(int expected) {
        assertThat(this.code).isEqualTo(expected);
    }

    private void thenTheInstalledVersionIs(String version) throws IOException {
        assertThat(Files.readString(this.target.resolve("njmule.exe"))).isEqualTo(version);
    }

    private void thenTheOldVersionIsKept(String version) throws IOException {
        assertThat(Files.readString(this.target.resolve("njmule.exe.old"))).isEqualTo(version);
    }

    private void thenThePlanSays(ApplyPlan.State state) throws IOException {
        assertThat(ApplyPlan.load(files().plan()))
                .hasValueSatisfying(p -> assertThat(p.state()).isEqualTo(state));
    }

    private void thenThePlanTellsWhy(String words) throws IOException {
        assertThat(ApplyPlan.load(files().plan()).orElseThrow().reason())
                .hasValueSatisfying(r -> assertThat(r).contains(words));
    }

    private void thenItStarted(List<String> command) {
        assertThat(this.started).containsExactly(command);
    }

    private void thenItStartedTimes(int times) {
        assertThat(this.started).hasSize(times);
    }

    private void thenNothingStarted() {
        assertThat(this.started).isEmpty();
    }

    private ApplyPlan plan() {
        return new ApplyPlan(
                "0.2.0",
                "0.1.0",
                InstallKind.WINDOWS_ZIP,
                this.dir.resolve("njmule-0.2.0-windows-x64.zip"),
                Optional.of(this.staged),
                Optional.of(this.target),
                Optional.of("njmule.exe"),
                this.restart,
                false,
                this.restart ? Optional.of(Path.of(program())) : Optional.empty(),
                this.restart ? List.of("--config-dir", "/data") : List.of(),
                ApplyPlan.State.HANDED_OVER,
                Optional.empty(),
                OptionalLong.empty(),
                NOW);
    }

    private String program() {
        return this.dir.resolve("installed").resolve("njmule-gui.exe").toString();
    }

    private UpdateFiles files() {
        return new UpdateFiles(this.dir.resolve("cache").resolve("update"));
    }

    private static void write(Path file, String text) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }
}
