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
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.core.api.InstallKind;
import org.njmule.core.api.NewVersion;
import org.njmule.core.api.UpdateStatus;
import org.njmule.core.config.UpdateChannel;

@Timeout(30)
class UpdatesTest {

    private static final Instant START = Instant.parse("2026-11-03T08:00:00Z");
    private static final String MSI = "njmule-0.2.0-windows-x64.msi";

    @TempDir
    Path dir;

    private final FakeWeb web = new FakeWeb();
    private final TestSigning signer = new TestSigning();
    private final AtomicReference<Instant> now = new AtomicReference<>(START);
    private final List<UpdateStatus> changes = new CopyOnWriteArrayList<>();
    private final List<NewVersion> found = new CopyOnWriteArrayList<>();
    private Installation installation = new Installation(InstallKind.MSI, "windows-x64");
    private boolean enabled = true;
    private Updates updates;
    private UpdateStatus status;
    private CompletableFuture<UpdateStatus> first;
    private CompletableFuture<UpdateStatus> second;
    private boolean due;

    @AfterEach
    void stop() {
        this.web.release();
        if (this.updates != null) {
            this.updates.close();
        }
    }

    @Test
    void shouldOfferANewerVersionWithThePackageOfThisInstallation() throws Exception {
        givenTheNewestReleaseIs("0.2.0", false);

        whenCheckedByHand(UpdateChannel.STABLE);

        thenTheVersionAvailableIs("0.2.0");
        thenItsPackageIsAt("https://github.com/njMule/njMule/releases/download/v0.2.0/" + MSI);
        thenItsPageIsAt("https://github.com/njMule/njMule/releases/tag/v0.2.0");
        thenTheCheckSucceededAt(START);
    }

    @Test
    void shouldTellOfANewVersionItFoundOnItsOwnOnceARun() throws Exception {
        givenTheNewestReleaseIs("0.2.0", false);
        givenCheckedOnItsOwn();

        whenCheckedOnItsOwn();

        thenItToldOf("0.2.0");
    }

    @Test
    void shouldNotTellOfAVersionTheUserLookedFor() throws Exception {
        givenTheNewestReleaseIs("0.2.0", false);
        givenCheckedByHand();

        whenCheckedOnItsOwn();

        thenItToldOfNothing();
        thenTheVersionAvailableIs("0.2.0");
    }

    @Test
    void shouldFindNothingNewerThanTheVersionRunning() throws Exception {
        givenTheNewestReleaseIs("0.1.0", false);

        whenCheckedOnItsOwn();

        thenNoVersionIsAvailable();
        thenTheCheckDidNotFail();
    }

    @Test
    void shouldNotOfferATestVersionToTheStableChannel() throws Exception {
        givenTheNewestReleaseIs("0.2.0-beta.1", false);

        whenCheckedOnItsOwn();

        thenNoVersionIsAvailable();
    }

    @Test
    void shouldOfferATestVersionToTheTestChannel() throws Exception {
        givenTheNewestReleaseIs("0.1.0", false);
        givenATestVersion("0.2.0-beta.1");

        whenCheckedByHand(UpdateChannel.PRERELEASE);

        thenTheVersionAvailableIs("0.2.0-beta.1");
    }

    @Test
    void shouldRefuseInformationNotSignedByNjMule() throws Exception {
        givenTheNewestReleaseSignedBy(new TestSigning(), "0.2.0");

        whenCheckedOnItsOwn();

        thenTheCheckFailedWith("not signed by njMule");
        thenNoVersionIsAvailable();
        thenItToldOfNothing();
    }

    @Test
    void shouldRefuseAnOlderVersionThanOneOfferedBefore() throws Exception {
        givenTheKeptState("highest.stable=0.3.0\n");
        givenTheNewestReleaseIs("0.2.0", false);

        whenCheckedOnItsOwn();

        thenTheCheckFailedWith("older than the 0.3.0 it offered before");
        thenNoVersionIsAvailable();
    }

    @Test
    void shouldKeepQuietAboutASkippedVersion() throws Exception {
        givenTheNewestReleaseIs("0.2.0", false);
        givenSkipped("0.2.0");

        whenCheckedOnItsOwn();

        thenItToldOfNothing();
        thenTheSkippedVersionIs("0.2.0");
    }

    @Test
    void shouldTellOfASkippedVersionThatFixesASecurityProblem() throws Exception {
        givenTheNewestReleaseIs("0.2.0", true);
        givenSkipped("0.2.0");

        whenCheckedOnItsOwn();

        thenItToldOf("0.2.0");
    }

    @Test
    void shouldRememberTheSkippedVersionAndTheLastCheckInTheNextRun() throws Exception {
        givenTheNewestReleaseIs("0.2.0", false);
        givenCheckedByHand();
        givenSkipped("0.2.0");

        whenNjMuleStartsAgain();

        thenTheSkippedVersionIs("0.2.0");
        thenTheCheckSucceededAt(START);
    }

    @Test
    void shouldOfferNoPackageToACopyBuiltFromTheSources() throws Exception {
        givenTheInstallation(new Installation(InstallKind.UNKNOWN, "linux-x64"));
        givenTheNewestReleaseIs("0.2.0", false);

        whenCheckedByHand(UpdateChannel.STABLE);

        thenTheVersionAvailableHasNoPackage();
    }

    @Test
    void shouldLookAtTheFirstChanceOfARun() {
        givenUpdates();

        whenAskedWhetherACheckIsDue();

        thenItIsDue(true);
    }

    @Test
    void shouldNotLookAgainBeforeADay() throws Exception {
        givenTheNewestReleaseIs("0.1.0", false);
        givenCheckedOnItsOwn();
        givenTimePassed(Duration.ofHours(23));

        whenAskedWhetherACheckIsDue();

        thenItIsDue(false);
    }

    @Test
    void shouldLookAgainADayLater() throws Exception {
        givenTheNewestReleaseIs("0.1.0", false);
        givenCheckedOnItsOwn();
        givenTimePassed(Duration.ofDays(1));

        whenAskedWhetherACheckIsDue();

        thenItIsDue(true);
    }

    @Test
    void shouldLookAgainSixHoursAfterAFailure() throws Exception {
        givenCheckedOnItsOwn();
        givenTimePassed(Duration.ofHours(6));

        whenAskedWhetherACheckIsDue();

        thenItIsDue(true);
    }

    @Test
    void shouldJoinTheCheckRunning() throws Exception {
        givenTheNewestReleaseIs("0.2.0", false);
        givenTheWebSlow();
        givenACheckRunning();

        whenCheckedAgainMeanwhile();

        thenTheCheckIsReported(true);
        thenBothEndWithTheSameStatusOnceTheWebAnswers();
        thenTheReleaseWasFetchedOnce();
    }

    @Test
    void shouldNeverLookWhenTurnedOff() throws Exception {
        givenTurnedOff();

        whenCheckedByHand(UpdateChannel.STABLE);

        thenTheCheckFailedWith("turned off");
        thenTheWebWasNotAsked();
    }

    @Test
    void shouldTellWhenACheckStartsAndEnds() throws Exception {
        givenTheNewestReleaseIs("0.1.0", false);

        whenCheckedOnItsOwn();

        thenTheChangesWereCheckingThenDone();
    }

    private void givenTheNewestReleaseIs(String version, boolean critical) {
        String json = UpdateManifestTest.manifest(
                version,
                critical,
                UpdateManifestTest.msi(MSI),
                UpdateManifestTest.asset("linux-x64", "deb", "njmule_0.2.0_amd64.deb"));
        this.web.serveRelease(FakeWeb.LATEST, json, this.signer);
    }

    private void givenTheNewestReleaseSignedBy(TestSigning other, String version) {
        this.web.serveRelease(FakeWeb.LATEST, UpdateManifestTest.manifest(version, false), other);
    }

    private void givenATestVersion(String version) {
        String tag = "v" + version;
        this.web.serve(
                FakeWeb.LISTING,
                "[{\"tag_name\": \"" + tag + "\", \"assets\": [{\"name\": \"njmule-update.json\"},"
                        + " {\"name\": \"njmule-update.json.minisig\"}]}]");
        this.web.serveRelease(
                FakeWeb.REPOSITORY + "/releases/download/" + tag + "/",
                UpdateManifestTest.manifest(version, false),
                this.signer);
    }

    private void givenTheKeptState(String text) throws IOException {
        Files.writeString(this.dir.resolve(UpdateState.FILE_NAME), text);
    }

    private void givenTheInstallation(Installation chosen) {
        this.installation = chosen;
    }

    private void givenTurnedOff() {
        this.enabled = false;
    }

    private void givenUpdates() {
        updates();
    }

    private void givenSkipped(String version) {
        updates().skip(version);
    }

    private void givenCheckedOnItsOwn() throws Exception {
        whenCheckedOnItsOwn();
    }

    private void givenCheckedByHand() throws Exception {
        whenCheckedByHand(UpdateChannel.STABLE);
    }

    private void givenTimePassed(Duration time) {
        this.now.set(this.now.get().plus(time));
    }

    private void givenTheWebSlow() {
        this.web.holdFetches();
    }

    private void givenACheckRunning() {
        this.first = updates().check(UpdateChannel.STABLE, true);
    }

    private void whenCheckedOnItsOwn() throws Exception {
        this.status = updates().check(UpdateChannel.STABLE, true).get(10, TimeUnit.SECONDS);
    }

    private void whenCheckedByHand(UpdateChannel channel) throws Exception {
        this.status = updates().check(channel, false).get(10, TimeUnit.SECONDS);
    }

    private void whenCheckedAgainMeanwhile() {
        this.second = updates().check(UpdateChannel.STABLE, false);
    }

    private void whenAskedWhetherACheckIsDue() {
        this.due = updates().isDue();
    }

    private void whenNjMuleStartsAgain() {
        this.updates.close();
        this.updates = null;
        this.status = updates().status();
    }

    private void thenTheVersionAvailableIs(String version) {
        assertThat(this.status.available()).map(NewVersion::version).contains(version);
    }

    private void thenItsPackageIsAt(String uri) {
        NewVersion.Download download =
                this.status.available().orElseThrow().download().orElseThrow();
        assertThat(download.uri()).hasToString(uri);
        assertThat(download.size()).isEqualTo(31_719_424);
        assertThat(download.sha256()).isEqualTo(UpdateManifestTest.SHA256);
    }

    private void thenItsPageIsAt(String uri) {
        assertThat(this.status.available().orElseThrow().page()).hasToString(uri);
    }

    private void thenTheVersionAvailableHasNoPackage() {
        assertThat(this.status.available().orElseThrow().download()).isEmpty();
    }

    private void thenNoVersionIsAvailable() {
        assertThat(this.status.available()).isEmpty();
    }

    private void thenTheCheckSucceededAt(Instant when) {
        assertThat(this.status.lastCheck()).contains(when);
        assertThat(this.status.failure()).isEmpty();
    }

    private void thenTheCheckDidNotFail() {
        assertThat(this.status.failure()).isEmpty();
    }

    private void thenTheCheckFailedWith(String reason) {
        assertThat(this.status.failure()).hasValueSatisfying(f -> assertThat(f).contains(reason));
    }

    private void thenItToldOf(String version) {
        assertThat(this.found).extracting(NewVersion::version).containsExactly(version);
    }

    private void thenItToldOfNothing() {
        assertThat(this.found).isEmpty();
    }

    private void thenTheSkippedVersionIs(String version) {
        assertThat(this.status.skipped()).contains(version);
    }

    private void thenItIsDue(boolean expected) {
        assertThat(this.due).isEqualTo(expected);
    }

    private void thenBothEndWithTheSameStatusOnceTheWebAnswers() throws Exception {
        this.web.release();
        assertThat(this.second.get(10, TimeUnit.SECONDS)).isEqualTo(this.first.get(10, TimeUnit.SECONDS));
    }

    private void thenTheReleaseWasFetchedOnce() {
        assertThat(this.web.asked()).hasSize(2);
    }

    private void thenTheCheckIsReported(boolean checking) {
        assertThat(updates().status().checking()).isEqualTo(checking);
    }

    private void thenTheWebWasNotAsked() {
        assertThat(this.web.asked()).isEmpty();
    }

    private void thenTheChangesWereCheckingThenDone() {
        assertThat(this.changes).extracting(UpdateStatus::checking).containsExactly(true, false);
    }

    private Updates updates() {
        if (this.updates == null) {
            this.updates = new Updates(
                    this.web.releases(),
                    this.signer.keys(),
                    this.installation,
                    "0.1.0",
                    this.dir.resolve(UpdateState.FILE_NAME),
                    this.now::get,
                    Duration.ZERO,
                    this.enabled,
                    new Updates.Listener() {
                        @Override
                        public void changed(UpdateStatus changed) {
                            UpdatesTest.this.changes.add(changed);
                        }

                        @Override
                        public void found(NewVersion version) {
                            UpdatesTest.this.found.add(version);
                        }
                    });
        }
        return this.updates;
    }
}
