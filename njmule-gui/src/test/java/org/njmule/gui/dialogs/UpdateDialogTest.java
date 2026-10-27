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
package org.njmule.gui.dialogs;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Labeled;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TextInputControl;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.core.api.InstallKind;
import org.njmule.core.api.NewVersion;
import org.njmule.core.api.UpdateStatus;
import org.njmule.gui.Engines;
import org.njmule.gui.FxTesting;
import org.njmule.gui.TestGui;

@Timeout(60)
class UpdateDialogTest {

    private static final Instant CHECKED = Instant.parse("2026-11-03T08:00:00Z");
    private static final String RELEASE = "https://github.com/njMule/njMule/releases/";

    @TempDir
    Path dir;

    private final List<String> skipped = new CopyOnWriteArrayList<>();
    private final List<String> asked = new CopyOnWriteArrayList<>();
    private TestGui test;
    private UpdateDialog dialog;
    private UpdateStatus found;

    @AfterEach
    void close() throws Exception {
        if (this.dialog != null) {
            FxTesting.onFx(this.dialog.stage()::close);
        }
        if (this.test != null) {
            this.test.close();
        }
    }

    @Test
    void shouldShowANewVersionWithWhatChangedAndHowToInstallIt() throws Exception {
        givenTheCheckFinds(available(InstallKind.DEB, false));

        whenTheUserChecksForUpdates();

        thenTheDialogShows(
                "njMule 0.2.0 is available",
                "Added\n\n• Updates.",
                "Install the download with your package manager: sudo apt install ./njmule_0.2.0_amd64.deb");
        thenTheButtonsAre("update-download", "update-skip");
    }

    @Test
    void shouldTellADockerContainerToPullTheNewImage() throws Exception {
        givenTheCheckFinds(available(InstallKind.DOCKER, false));

        whenTheUserChecksForUpdates();

        thenTheDialogShows("Pull the new image with docker pull ghcr.io/njmule/njmule and create the container again");
        thenTheButtonsAre("update-skip");
    }

    @Test
    void shouldSendASynologyNasToThePackageCenterWithoutDownloadingThere() throws Exception {
        givenTheCheckFinds(available(InstallKind.SYNOLOGY, false));

        whenTheUserChecksForUpdates();

        thenTheDialogShows("install it in the Package Center with Manual Install");
        thenTheButtonsAre("update-skip");
    }

    @Test
    void shouldUrgeAVersionThatFixesSecurityProblemsWithoutLettingItBeSkipped() throws Exception {
        givenTheCheckFinds(available(InstallKind.MSI, true));

        whenTheUserChecksForUpdates();

        thenTheDialogShows(
                "It fixes security problems: install it soon.",
                "njMule downloads it, checks it against its signed release information and installs it");
        thenTheButtonsAre("update-download");
    }

    @Test
    void shouldTellThatWindowsAsksForRightsForAnInstallationForAllUsers() throws Exception {
        givenTheCheckFinds(withInstaller(available(InstallKind.MSI, false), UpdateStatus.Installer.ELEVATED));

        whenTheUserChecksForUpdates();

        thenTheDialogShows("Windows then asks for administrator rights");
    }

    @Test
    void shouldDownloadTheNewVersionWhenAsked() throws Exception {
        givenTheCheckFinds(available(InstallKind.MSI, false));
        givenTheUserCheckedForUpdates();

        whenTheUserPresses("update-download");

        thenTheEngineWasAskedTo("download");
    }

    @Test
    void shouldShowHowFarTheDownloadIs() throws Exception {
        givenTheCheckFinds(downloading(available(InstallKind.MSI, false), 1_048_576, 4_194_304));

        whenTheUserChecksForUpdates();

        thenTheDialogShows("of", "downloaded");
        thenTheProgressIs(0.25);
        thenTheButtonsAre("update-skip");
    }

    @Test
    void shouldOfferToRestartOnceTheVersionIsReady() throws Exception {
        givenTheCheckFinds(ready(available(InstallKind.MSI, false), UpdateStatus.Install.ASKED));

        whenTheUserChecksForUpdates();

        thenTheDialogShows("njMule 0.2.0 is ready to install");
        thenTheButtonsAre("update-restart", "update-at-exit", "update-skip");
    }

    @Test
    void shouldRestartToInstallWhenAsked() throws Exception {
        givenTheCheckFinds(ready(available(InstallKind.MSI, false), UpdateStatus.Install.ASKED));
        givenTheUserCheckedForUpdates();

        whenTheUserPresses("update-restart");

        thenTheEngineWasAskedTo("install now");
    }

    @Test
    void shouldInstallAtExitWhenAsked() throws Exception {
        givenTheCheckFinds(ready(available(InstallKind.MSI, false), UpdateStatus.Install.ASKED));
        givenTheUserCheckedForUpdates();

        whenTheUserPresses("update-at-exit");

        thenTheEngineWasAskedTo("install at exit");
    }

    @Test
    void shouldTellThatTheVersionReadyInstallsAtExit() throws Exception {
        givenTheCheckFinds(ready(available(InstallKind.MSI, false), UpdateStatus.Install.AT_EXIT));

        whenTheUserChecksForUpdates();

        thenTheDialogShows("It is installed when you close njMule.");
        thenTheButtonsAre("update-restart", "update-skip");
    }

    @Test
    void shouldShowThePackageItCannotInstallItself() throws Exception {
        givenTheCheckFinds(ready(available(InstallKind.DEB, false), UpdateStatus.Install.ASKED));

        whenTheUserChecksForUpdates();

        thenTheDialogShows("Downloaded and checked:", "sudo apt install ./njmule_0.2.0_amd64.deb");
        thenTheButtonsAre("update-folder", "update-skip");
    }

    @Test
    void shouldTellThatNjMuleRestartsToInstall() throws Exception {
        givenTheCheckFinds(ready(available(InstallKind.MSI, false), UpdateStatus.Install.RESTART));

        whenTheUserChecksForUpdates();

        thenTheDialogShows("njMule is installing the new version and starts again in a moment.");
    }

    @Test
    void shouldSkipAVersionAndClose() throws Exception {
        givenTheCheckFinds(available(InstallKind.MSI, false));
        givenTheUserCheckedForUpdates();

        whenTheUserSkipsTheVersion();

        thenTheEngineWasToldToSkip("0.2.0");
        thenTheDialogIsClosed();
    }

    @Test
    void shouldKeepTheNoteReadableAndTheButtonsInsideTheWindowWhenThereAreSeveral() throws Exception {
        givenTheCheckFinds(available(InstallKind.MSI, false));

        whenTheUserChecksForUpdates();

        thenTheNoteIsAtLeastWide(300);
        thenTheButtonsAreInsideTheWindow();
    }

    @Test
    void shouldTellThatThisIsTheNewestVersion() throws Exception {
        givenTheCheckFinds(status(Optional.empty(), Optional.empty()));

        whenTheUserChecksForUpdates();

        thenTheDialogShows("njMule 0.1.0 is the newest version.", "Last check:");
        thenTheButtonsAre("update-check");
    }

    @Test
    void shouldTellWhyTheCheckFailed() throws Exception {
        givenTheCheckFinds(status(Optional.empty(), Optional.of("HTTP 503 from github.com")));

        whenTheUserChecksForUpdates();

        thenTheDialogShows("The check failed: HTTP 503 from github.com");
        thenTheButtonsAre("update-check");
    }

    private void givenTheCheckFinds(UpdateStatus found) throws Exception {
        this.found = found;
        this.test = TestGui.start(
                this.dir.resolve("njmule"),
                engine -> Engines.overriding(
                        engine,
                        Map.of(
                                "checkForUpdate",
                                args -> CompletableFuture.completedFuture(found),
                                "skipUpdate",
                                args -> {
                                    this.skipped.add((String) args[0]);
                                    return CompletableFuture.completedFuture(found);
                                },
                                "downloadUpdate",
                                args -> {
                                    this.asked.add("download");
                                    return CompletableFuture.completedFuture(found);
                                },
                                "installUpdate",
                                args -> {
                                    this.asked.add((Boolean) args[0] ? "install now" : "install at exit");
                                    return CompletableFuture.completedFuture(found);
                                })));
    }

    private void givenTheUserCheckedForUpdates() throws Exception {
        whenTheUserChecksForUpdates();
    }

    private void whenTheUserChecksForUpdates() throws Exception {
        FxTesting.onFx(() -> this.dialog = UpdateDialog.checkNow(this.test.gui, new Stage()));
        FxTesting.await(
                "the outcome of the check",
                () -> this.found.equals(this.test.model.updateProperty().get()));
        FxTesting.onFx(() -> {});
    }

    private void whenTheUserSkipsTheVersion() throws Exception {
        FxTesting.onFx(() -> button("update-skip").fire());
    }

    private void whenTheUserPresses(String id) throws Exception {
        FxTesting.onFx(() -> button(id).fire());
    }

    private void thenTheDialogShows(String... parts) throws Exception {
        String shown = String.join("\n", FxTesting.onFx(this::texts));
        assertThat(shown).contains(parts);
    }

    private void thenTheButtonsAre(String... ids) throws Exception {
        List<String> shown = FxTesting.onFx(() -> {
            List<String> found = new ArrayList<>();
            collect(this.dialog.stage().getScene().getRoot(), n -> {
                if (n instanceof Button b && b.getId() != null) {
                    found.add(b.getId());
                }
            });
            return found;
        });
        // In the order of the platform's dialogs.
        assertThat(shown).containsExactlyInAnyOrder(ids);
    }

    private void thenTheNoteIsAtLeastWide(double width) throws Exception {
        assertThat(FxTesting.onFx(
                        () -> ((Labeled) this.dialog.stage().getScene().lookup(".dialog-hint")).getWidth()))
                .isGreaterThanOrEqualTo(width);
    }

    private void thenTheButtonsAreInsideTheWindow() throws Exception {
        double window = FxTesting.onFx(() -> this.dialog.stage().getScene().getWidth());
        List<Double> rights = FxTesting.onFx(() -> {
            List<Double> found = new ArrayList<>();
            collect(this.dialog.stage().getScene().getRoot(), n -> {
                if (n instanceof Button b && b.getId() != null) {
                    found.add(b.localToScene(b.getBoundsInLocal()).getMaxX());
                }
            });
            return found;
        });
        assertThat(rights).isNotEmpty().allSatisfy(right -> assertThat(right).isLessThanOrEqualTo(window));
    }

    private void thenTheEngineWasToldToSkip(String version) throws Exception {
        FxTesting.assertEventually("the skip", () -> this.skipped.contains(version));
    }

    private void thenTheEngineWasAskedTo(String what) throws Exception {
        FxTesting.assertEventually(what, () -> this.asked.contains(what));
    }

    private void thenTheProgressIs(double expected) throws Exception {
        assertThat(FxTesting.onFx(
                        () -> ((ProgressBar) this.dialog.stage().getScene().lookup("#update-progress")).getProgress()))
                .isEqualTo(expected);
    }

    private void thenTheDialogIsClosed() throws Exception {
        assertThat(FxTesting.onFx(() -> this.dialog.stage().isShowing())).isFalse();
    }

    private List<String> texts() {
        List<String> found = new ArrayList<>();
        if (this.dialog != null) {
            collect(this.dialog.stage().getScene().getRoot(), n -> {
                if (n instanceof Labeled l && l.getText() != null) {
                    found.add(l.getText());
                } else if (n instanceof TextInputControl t) {
                    found.add(t.getText());
                }
            });
        }
        return found;
    }

    private Button button(String id) {
        return (Button) this.dialog.stage().getScene().lookup("#" + id);
    }

    private static void collect(Node node, Consumer<Node> each) {
        each.accept(node);
        if (node instanceof Parent parent) {
            parent.getChildrenUnmodifiable().forEach(child -> collect(child, each));
        }
    }

    private static UpdateStatus available(InstallKind installation, boolean critical) {
        String file = installation == InstallKind.DEB ? "njmule_0.2.0_amd64.deb" : "njmule-0.2.0-windows-x64.msi";
        // A release has no package for a Docker container, which pulls its image.
        Optional<NewVersion.Download> download = installation == InstallKind.DOCKER
                ? Optional.empty()
                : Optional.of(
                        new NewVersion.Download(URI.create(RELEASE + "download/v0.2.0/" + file), 1, "0".repeat(64)));
        NewVersion v = new NewVersion(
                "0.2.0",
                Instant.parse("2026-11-02T10:00:00Z"),
                critical,
                "### Added\n\n- Updates.",
                URI.create(RELEASE + "tag/v0.2.0"),
                download);
        boolean managed = Set.of(InstallKind.DEB, InstallKind.DOCKER, InstallKind.SYNOLOGY)
                .contains(installation);
        return new UpdateStatus(
                "0.1.0",
                installation,
                managed ? UpdateStatus.Installer.NONE : UpdateStatus.Installer.SELF,
                false,
                Optional.of(CHECKED),
                Optional.empty(),
                Optional.of(v),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                UpdateStatus.Install.ASKED);
    }

    private static UpdateStatus withInstaller(UpdateStatus s, UpdateStatus.Installer installer) {
        return new UpdateStatus(
                s.current(),
                s.installation(),
                installer,
                s.checking(),
                s.lastCheck(),
                s.failure(),
                s.available(),
                s.skipped(),
                s.download(),
                s.ready(),
                s.install());
    }

    private static UpdateStatus downloading(UpdateStatus s, long received, long size) {
        return new UpdateStatus(
                s.current(),
                s.installation(),
                s.installer(),
                s.checking(),
                s.lastCheck(),
                s.failure(),
                s.available(),
                s.skipped(),
                Optional.of(new UpdateStatus.Progress(received, size)),
                Optional.empty(),
                s.install());
    }

    private static UpdateStatus ready(UpdateStatus s, UpdateStatus.Install install) {
        return new UpdateStatus(
                s.current(),
                s.installation(),
                s.installer(),
                s.checking(),
                s.lastCheck(),
                s.failure(),
                s.available(),
                s.skipped(),
                Optional.empty(),
                Optional.of(Path.of("cache", "update", "0.2.0", "package")),
                install);
    }

    private static UpdateStatus status(Optional<NewVersion> available, Optional<String> failure) {
        return new UpdateStatus(
                "0.1.0",
                InstallKind.MSI,
                UpdateStatus.Installer.SELF,
                false,
                Optional.of(CHECKED),
                failure,
                available,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                UpdateStatus.Install.ASKED);
    }
}
