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
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.core.api.InstallKind;
import org.njmule.core.api.NewVersion;
import org.njmule.core.api.UpdateHandOver;
import org.njmule.core.api.UpdateStatus;
import org.njmule.core.api.UpdateStatus.Install;
import org.njmule.core.api.UpdateStatus.Installer;
import org.njmule.core.config.NightHours;
import org.njmule.core.config.Preferences;
import org.njmule.core.config.UpdateChannel;
import org.njmule.core.config.UpdateInstallTime;
import org.njmule.core.config.UpdateMode;
import org.njmule.core.update.TestPackages.Entry;

@Timeout(30)
class UpdateInstallTest {

    private static final Instant NOW = Instant.parse("2026-11-03T08:00:00Z");
    private static final String ZIP = "njmule-0.2.0-windows-x64.zip";
    private static final String MSI = "njmule-0.2.0-windows-x64.msi";

    @TempDir
    Path dir;

    private final FakeWeb web = new FakeWeb();
    private final TestSigning signer = new TestSigning();
    private final List<UpdateStatus> changes = new CopyOnWriteArrayList<>();
    private final List<NewVersion> ready = new CopyOnWriteArrayList<>();
    private final List<String> restarts = new CopyOnWriteArrayList<>();
    private InstallKind kind = InstallKind.WINDOWS_ZIP;
    private Installer installer = Installer.SELF;
    private String running = "0.1.0";
    private Preferences preferences = Preferences.defaults().toBuilder()
            .updateMode(UpdateMode.NOTIFY)
            .updateChannel(UpdateChannel.STABLE)
            .build();
    private Updates updates;
    private UpdateStatus status;
    private Throwable failure;
    private Optional<UpdateHandOver> handOver;
    private boolean restarted;
    private Optional<String> told;

    @AfterEach
    void stop() {
        if (this.updates != null) {
            this.updates.close();
        }
    }

    @Test
    void shouldDownloadThePackageCheckItAndUnpackIt() throws Exception {
        givenANewReleaseWithItsPackage();
        givenCheckedByHand();

        whenDownloaded();

        thenNoFailureOccurred();
        thenThePhaseIs(UpdateStatus.Phase.READY);
        thenTheVersionIsToldReady("0.2.0");
        thenTheUnpackedFilesHold("njmule.exe", "0.2.0");
        thenTheProgressWasTold();
    }

    @Test
    void shouldTellWhyADownloadFailed() throws Exception {
        givenANewReleaseWhosePackageWasChanged();
        givenCheckedByHand();

        whenDownloaded();

        thenThePhaseIs(UpdateStatus.Phase.FAILED);
        thenTheFailureSays("does not match its signed hash");
        thenNothingIsReady();
    }

    @Test
    void shouldRefuseToDownloadBeforeANewVersionIsKnown() {
        whenDownloaded();

        thenItWasRefused("no new version is known");
    }

    @Test
    void shouldDownloadOnItsOwnInTheDownloadMode() throws Exception {
        givenANewReleaseWithItsPackage();
        givenTheMode(UpdateMode.DOWNLOAD);

        whenCheckedOnItsOwn();

        thenAVersionBecomesReady();
        thenItInstallsWhen(Install.ASKED);
    }

    @Test
    void shouldInstallAtExitInTheInstallMode() throws Exception {
        givenANewReleaseWithItsPackage();
        givenTheMode(UpdateMode.INSTALL);
        givenCheckedByHand();
        givenDownloaded();

        whenNjMuleStops();

        thenItInstallsWhen(Install.AT_EXIT);
        thenAnInstallationIsHandedOver(false);
    }

    @Test
    void shouldNotInstallOnItsOwnWhatNeedsAdministratorRights() throws Exception {
        givenTheInstallation(InstallKind.MSI, Installer.ELEVATED);
        givenANewReleaseWithItsPackage();
        givenTheMode(UpdateMode.INSTALL);
        givenCheckedByHand();
        givenDownloaded();

        whenNjMuleStops();

        thenItInstallsWhen(Install.ASKED);
        thenNothingIsHandedOver();
    }

    @Test
    void shouldRestartToInstallWhenTheUserAsks() throws Exception {
        givenANewReleaseWithItsPackage();
        givenCheckedByHand();
        givenDownloaded();

        whenInstalled(true);

        thenNoFailureOccurred();
        thenThePhaseIs(UpdateStatus.Phase.INSTALLING);
        thenARestartWasAskedFor("0.2.0");
    }

    @Test
    void shouldHandOverTheRestartOnceNjMuleStopped() throws Exception {
        givenANewReleaseWithItsPackage();
        givenCheckedByHand();
        givenDownloaded();
        givenInstalled(true);

        whenNjMuleStops();

        thenAnInstallationIsHandedOver(true);
    }

    @Test
    void shouldInstallAtExitWhenTheUserAsks() throws Exception {
        givenANewReleaseWithItsPackage();
        givenCheckedByHand();
        givenDownloaded();

        whenInstalled(false);

        thenItInstallsWhen(Install.AT_EXIT);
        thenNoRestartWasAskedFor();
    }

    @Test
    void shouldHandOverAnInstallationAskedForTheExit() throws Exception {
        givenANewReleaseWithItsPackage();
        givenCheckedByHand();
        givenDownloaded();
        givenInstalled(false);

        whenNjMuleStops();

        thenAnInstallationIsHandedOver(false);
    }

    @Test
    void shouldRefuseToInstallWhatIsNotDownloaded() throws Exception {
        givenANewReleaseWithItsPackage();
        givenCheckedByHand();

        whenInstalled(true);

        thenItWasRefused("no new version is downloaded");
    }

    @Test
    void shouldRefuseToInstallWhereAPackageManagerOwnsTheFiles() throws Exception {
        givenTheInstallation(InstallKind.DEB, Installer.NONE);
        givenANewReleaseWithItsPackage();
        givenCheckedByHand();
        givenDownloaded();

        whenInstalled(true);

        thenItWasRefused("cannot replace itself here");
    }

    @Test
    void shouldRestartInTheNightHoursWhenAsked() throws Exception {
        givenANewReleaseWithItsPackage();
        givenTheMode(UpdateMode.INSTALL);
        givenTheNightHours(UpdateInstallTime.EXIT_OR_NIGHT, "03:00-05:00");
        givenCheckedByHand();
        givenDownloaded();

        whenTheClockSays(LocalTime.of(4, 0));

        thenItRestarted(true);
        thenARestartWasAskedFor("0.2.0");
    }

    @Test
    void shouldWaitForTheNightHours() throws Exception {
        givenANewReleaseWithItsPackage();
        givenTheMode(UpdateMode.INSTALL);
        givenTheNightHours(UpdateInstallTime.EXIT_OR_NIGHT, "03:00-05:00");
        givenCheckedByHand();
        givenDownloaded();

        whenTheClockSays(LocalTime.of(12, 0));

        thenItRestarted(false);
        thenNoRestartWasAskedFor();
    }

    @Test
    void shouldNotRestartAtNightWhenOnlyTheExitInstalls() throws Exception {
        givenANewReleaseWithItsPackage();
        givenTheMode(UpdateMode.INSTALL);
        givenCheckedByHand();
        givenDownloaded();

        whenTheClockSays(LocalTime.of(4, 0));

        thenItRestarted(false);
    }

    @Test
    void shouldTellOfAFailedInstallationAtTheNextStart() throws Exception {
        givenTheLastInstallationEnded(ApplyPlan.State.FAILED, "the new version does not start");
        givenTheMode(UpdateMode.INSTALL);

        whenNjMuleStarts();

        thenTheFailureSays("the installation of njMule 0.2.0 failed: the new version does not start");
        thenNoPlanIsLeft();
    }

    @Test
    void shouldNotInstallAgainOnItsOwnAVersionWhoseInstallationFailed() throws Exception {
        givenTheLastInstallationEnded(ApplyPlan.State.FAILED, "the new version does not start");
        givenANewReleaseWithItsPackage();
        givenTheMode(UpdateMode.INSTALL);
        givenCheckedByHand();
        givenDownloaded();

        whenNjMuleStops();

        thenItInstallsWhen(Install.ASKED);
    }

    @Test
    void shouldTellWhatWindowsInstallerSaid() throws Exception {
        givenTheInstallation(InstallKind.MSI, Installer.SELF);
        givenTheLastInstallerEndedWith("1603");

        whenNjMuleStarts();

        thenTheFailureSays("Windows Installer ended with code 1603");
    }

    @Test
    void shouldMakeAnInstallationFinalOnceTheNewVersionRanLongEnough() throws Exception {
        givenTheNewVersionInstalledOverTheOld();

        whenTheNewVersionRanLongEnough();

        thenItToldOf("njMule was updated from 0.1.0 to 0.2.0");
        thenTheStartIsMarked("0.2.0");
        thenTheOldFilesAreRemovedSoon();
        thenNoPlanIsLeftSoon();
    }

    @Test
    void shouldUndoAnInstallationStoppedHalfWay() throws Exception {
        givenAnInstallationStoppedHalfWay();

        whenNjMuleStarts();

        thenTheInstalledFileHolds("njmule.exe", "0.1.0");
        thenTheFailureSays("did not finish");
    }

    private void givenTheInstallation(InstallKind chosen, Installer by) {
        this.kind = chosen;
        this.installer = by;
    }

    private void givenTheMode(UpdateMode mode) {
        this.preferences = this.preferences.toBuilder().updateMode(mode).build();
    }

    private void givenTheNightHours(UpdateInstallTime when, String hours) {
        this.preferences = this.preferences.toBuilder()
                .updateInstallTime(when)
                .nightHours(NightHours.parse(hours))
                .build();
    }

    private void givenANewReleaseWithItsPackage() throws Exception {
        byte[] body = packageBytes();
        serveRelease(body, body);
    }

    private void givenANewReleaseWhosePackageWasChanged() throws Exception {
        byte[] sealed = packageBytes();
        byte[] changed = sealed.clone();
        changed[changed.length / 2] ^= 1;
        serveRelease(sealed, changed);
    }

    private void givenCheckedByHand() throws Exception {
        updates().check(UpdateChannel.STABLE, false).get(10, TimeUnit.SECONDS);
    }

    private void givenDownloaded() throws Exception {
        updates().download().get(10, TimeUnit.SECONDS);
    }

    private void givenInstalled(boolean restart) throws Exception {
        updates().install(restart).get(10, TimeUnit.SECONDS);
    }

    private void givenTheLastInstallationEnded(ApplyPlan.State state, String reason) throws IOException {
        plan(state, Optional.of(reason), Optional.empty(), Optional.empty()).save(files().plan());
    }

    private void givenTheLastInstallerEndedWith(String code) throws IOException {
        plan(ApplyPlan.State.HANDED_OVER, Optional.empty(), Optional.empty(), Optional.empty())
                .save(files().plan());
        Files.createDirectories(files().version("0.2.0"));
        Files.writeString(files().version("0.2.0").resolve(HandOver.INSTALLER_RESULT), code + "\r\n");
    }

    private void givenTheNewVersionInstalledOverTheOld() throws IOException {
        write(installed().resolve("njmule.exe"), "0.1.0");
        write(files().unpacked("0.2.0").resolve("njmule.exe"), "0.2.0");
        ApplyPlan plan = plan(
                ApplyPlan.State.INSTALLED,
                Optional.empty(),
                Optional.of(files().unpacked("0.2.0")),
                Optional.of(installed()));
        UpdateHelper.swap(plan, files(), Commands.SYSTEM).apply();
        plan.save(files().plan());
        this.running = "0.2.0";
    }

    private void givenAnInstallationStoppedHalfWay() throws IOException {
        givenTheNewVersionInstalledOverTheOld();
        ApplyPlan.load(files().plan())
                .orElseThrow()
                .with(ApplyPlan.State.WAITING, Optional.empty(), NOW)
                .save(files().plan());
        this.running = "0.1.0";
    }

    private void whenDownloaded() {
        try {
            this.status = updates().download().get(10, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            this.failure = e.getCause();
        } catch (Exception e) {
            this.failure = e;
        }
    }

    private void whenCheckedOnItsOwn() throws Exception {
        this.status = updates().check(UpdateChannel.STABLE, true).get(10, TimeUnit.SECONDS);
    }

    private void whenNjMuleStops() {
        updates().close();
        this.status = updates().status();
        this.handOver = updates().handOver();
    }

    private void whenInstalled(boolean restart) {
        try {
            this.status = updates().install(restart).get(10, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            this.failure = e.getCause();
        } catch (Exception e) {
            this.failure = e;
        }
    }

    private void whenTheClockSays(LocalTime time) {
        this.restarted = updates().installIfNight(time);
    }

    private void whenNjMuleStarts() {
        this.status = updates().status();
    }

    private void whenTheNewVersionRanLongEnough() {
        this.told = updates().ranLongEnough();
    }

    private void thenNoFailureOccurred() {
        assertThat(this.failure).isNull();
    }

    private void thenItWasRefused(String words) {
        assertThat(this.failure).isInstanceOf(IllegalStateException.class).hasMessageContaining(words);
    }

    private void thenThePhaseIs(UpdateStatus.Phase phase) {
        assertThat(this.status.phase()).isEqualTo(phase);
    }

    private void thenTheFailureSays(String words) {
        assertThat(this.status.failure()).hasValueSatisfying(f -> assertThat(f).contains(words));
    }

    private void thenTheVersionIsToldReady(String version) {
        assertThat(this.ready).extracting(NewVersion::version).containsExactly(version);
    }

    private void thenTheUnpackedFilesHold(String name, String text) throws IOException {
        assertThat(Files.readString(files().unpacked("0.2.0").resolve(name))).isEqualTo(text);
    }

    private void thenTheProgressWasTold() {
        assertThat(this.changes).anySatisfy(s -> assertThat(s.download()).isPresent());
    }

    private void thenNothingIsReady() {
        assertThat(this.status.ready()).isEmpty();
    }

    private void thenAVersionBecomesReady() throws InterruptedException {
        long end = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (updates().status().ready().isEmpty() && System.nanoTime() < end) {
            Thread.sleep(20);
        }
        assertThat(updates().status().ready()).isPresent();
    }

    private void thenItInstallsWhen(Install expected) {
        assertThat(updates().status().install()).isEqualTo(expected);
    }

    private void thenAnInstallationIsHandedOver(boolean restart) {
        assertThat(this.handOver).hasValueSatisfying(h -> {
            assertThat(h.version()).isEqualTo("0.2.0");
            assertThat(h.restart()).isEqualTo(restart);
        });
    }

    private void thenNothingIsHandedOver() {
        assertThat(this.handOver).isEmpty();
    }

    private void thenARestartWasAskedFor(String version) {
        assertThat(this.restarts).containsExactly(version);
    }

    private void thenNoRestartWasAskedFor() {
        assertThat(this.restarts).isEmpty();
    }

    private void thenItRestarted(boolean expected) {
        assertThat(this.restarted).isEqualTo(expected);
    }

    private void thenNoPlanIsLeft() {
        assertThat(files().plan()).doesNotExist();
    }

    private void thenItToldOf(String message) {
        assertThat(this.told).contains(message);
    }

    private void thenTheStartIsMarked(String version) {
        assertThat(files().started(version)).exists();
    }

    private void thenTheOldFilesAreRemovedSoon() throws InterruptedException {
        awaitGone(installed().resolve("njmule.exe.old"));
    }

    private void thenNoPlanIsLeftSoon() throws InterruptedException {
        awaitGone(files().plan());
    }

    private void thenTheInstalledFileHolds(String name, String text) throws IOException {
        assertThat(Files.readString(installed().resolve(name))).isEqualTo(text);
    }

    private static void awaitGone(Path file) throws InterruptedException {
        long end = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (Files.exists(file) && System.nanoTime() < end) {
            Thread.sleep(20);
        }
        assertThat(file).doesNotExist();
    }

    private void serveRelease(byte[] sealed, byte[] served) throws Exception {
        String name =
                this.kind == InstallKind.MSI ? MSI : this.kind == InstallKind.DEB ? "njmule_0.2.0_amd64.deb" : ZIP;
        String platform = this.kind == InstallKind.DEB ? "linux-x64" : "windows-x64";
        String sha256 =
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(sealed));
        String asset = """
                {"platform": "%s", "kind": "%s", "name": "%s", "size": %d, "sha256": "%s"}
                """.formatted(platform, this.kind.id(), name, sealed.length, sha256);
        this.web.serveRelease(FakeWeb.LATEST, UpdateManifestTest.manifest("0.2.0", false, asset), this.signer);
        this.web.serve(FakeWeb.REPOSITORY + "/releases/download/v0.2.0/" + name, served);
    }

    private byte[] packageBytes() throws IOException {
        Path zip = TestPackages.zip(
                this.dir.resolve("release").resolve(ZIP),
                Map.of("njmule-0.2.0-windows-x64/njmule.exe", Entry.program("0.2.0")));
        return Files.readAllBytes(zip);
    }

    private ApplyPlan plan(
            ApplyPlan.State state, Optional<String> reason, Optional<Path> staged, Optional<Path> target) {
        return new ApplyPlan(
                "0.2.0",
                "0.1.0",
                this.kind,
                files().packageFile("0.2.0", this.kind == InstallKind.MSI ? MSI : ZIP),
                staged,
                target,
                staged.isPresent() ? Optional.of("njmule.exe") : Optional.empty(),
                false,
                true,
                Optional.empty(),
                List.of(),
                state,
                reason,
                OptionalLong.empty(),
                NOW);
    }

    private Path installed() {
        return this.dir.resolve("installed");
    }

    private UpdateFiles files() {
        return new UpdateFiles(this.dir.resolve("cache").resolve("update"));
    }

    private Updates updates() {
        if (this.updates == null) {
            Installation installation = new Installation(
                    this.kind, "windows-x64", Optional.of(installed().resolve("njmule.exe")), this.installer);
            if (this.kind == InstallKind.DEB) {
                installation = new Installation(this.kind, "linux-x64", Optional.empty(), this.installer);
            }
            this.updates = new Updates(
                    this.web.releases(),
                    this.web,
                    this.signer.keys(),
                    installation,
                    this.running,
                    this.dir.resolve(UpdateState.FILE_NAME),
                    files(),
                    (command, timeout) -> new Commands.Result(0, ""),
                    (command, log) -> {
                        throw new IOException("no process in this test");
                    },
                    () -> NOW,
                    Duration.ZERO,
                    true,
                    false,
                    new Updates.Listener() {
                        @Override
                        public void changed(UpdateStatus changed) {
                            UpdateInstallTest.this.changes.add(changed);
                        }

                        @Override
                        public void found(NewVersion version) {
                            // Told by the status too.
                        }

                        @Override
                        public void ready(NewVersion version) {
                            UpdateInstallTest.this.ready.add(version);
                        }

                        @Override
                        public void restart(String version) {
                            UpdateInstallTest.this.restarts.add(version);
                        }
                    });
            this.updates.preferences(this.preferences);
        }
        return this.updates;
    }

    private static void write(Path file, String text) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }
}
