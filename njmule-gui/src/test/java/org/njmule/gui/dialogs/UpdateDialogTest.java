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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Labeled;
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
    void shouldUrgeAVersionThatFixesSecurityProblemsWithoutLettingItBeSkipped() throws Exception {
        givenTheCheckFinds(available(InstallKind.MSI, true));

        whenTheUserChecksForUpdates();

        thenTheDialogShows("It fixes security problems: install it soon.", "Open the download: it installs");
        thenTheButtonsAre("update-download");
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

    private void thenTheEngineWasToldToSkip(String version) throws Exception {
        FxTesting.assertEventually("the skip", () -> this.skipped.contains(version));
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
        NewVersion v = new NewVersion(
                "0.2.0",
                Instant.parse("2026-11-02T10:00:00Z"),
                critical,
                "### Added\n\n- Updates.",
                URI.create(RELEASE + "tag/v0.2.0"),
                Optional.of(
                        new NewVersion.Download(URI.create(RELEASE + "download/v0.2.0/" + file), 1, "0".repeat(64))));
        return new UpdateStatus(
                "0.1.0", installation, false, Optional.of(CHECKED), Optional.empty(), Optional.of(v), Optional.empty());
    }

    private static UpdateStatus status(Optional<NewVersion> available, Optional<String> failure) {
        return new UpdateStatus(
                "0.1.0", InstallKind.MSI, false, Optional.of(CHECKED), failure, available, Optional.empty());
    }
}
