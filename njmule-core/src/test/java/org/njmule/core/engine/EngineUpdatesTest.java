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

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.core.FreePorts;
import org.njmule.core.api.EngineConfig;
import org.njmule.core.api.InstallKind;
import org.njmule.core.api.NjMuleEngine;
import org.njmule.core.api.NjMuleInfo;
import org.njmule.core.api.UpdateStatus;
import org.njmule.core.config.AppDirectories;

/** The engine's side of the updates, with the checks off as in every test: nothing reaches GitHub. */
@Timeout(30)
class EngineUpdatesTest {

    @TempDir
    Path dir;

    private AppDirectories dirs;
    private UpdateStatus status;

    @Test
    void shouldTellTheVersionRunningAndThatItKnowsOfNoNewer() throws Exception {
        givenAProfileWithoutNetworks();

        whenTheStatusIsRead();

        thenTheVersionRunningIs(NjMuleInfo.version());
        thenTheInstallationIs(InstallKind.UNKNOWN);
        thenNoNewerVersionIsKnown();
    }

    @Test
    void shouldAnswerACheckWithTheReasonItCannotLook() throws Exception {
        givenAProfileWithoutNetworks();

        whenAskedToCheck();

        thenTheCheckFailedWith("turned off");
    }

    @Test
    void shouldRememberASkippedVersionInTheNextRun() throws Exception {
        givenAProfileWithoutNetworks();
        givenTheVersionSkipped("0.2.0");

        whenTheStatusIsRead();

        thenTheSkippedVersionIs("0.2.0");
    }

    private void givenAProfileWithoutNetworks() throws Exception {
        this.dirs = AppDirectories.underRoot(this.dir.resolve("njmule"), false);
        Files.createDirectories(this.dirs.config());
        Files.writeString(
                this.dirs.config().resolve("njmule.properties"),
                "net.tcpPort=" + FreePorts.tcp() + "\nnet.ed2k.enabled=false\nnet.kad.enabled=false\n");
    }

    private void givenTheVersionSkipped(String version) throws Exception {
        try (NjMuleEngine engine = NjMuleEngine.start(new EngineConfig(this.dirs))) {
            engine.skipUpdate(version).get(5, TimeUnit.SECONDS);
        }
    }

    private void whenTheStatusIsRead() throws Exception {
        try (NjMuleEngine engine = NjMuleEngine.start(new EngineConfig(this.dirs))) {
            this.status = engine.updateStatus();
        }
    }

    private void whenAskedToCheck() throws Exception {
        try (NjMuleEngine engine = NjMuleEngine.start(new EngineConfig(this.dirs))) {
            this.status = engine.checkForUpdate().get(5, TimeUnit.SECONDS);
        }
    }

    private void thenTheVersionRunningIs(String version) {
        assertThat(this.status.current()).isEqualTo(version);
    }

    private void thenTheInstallationIs(InstallKind kind) {
        assertThat(this.status.installation()).isEqualTo(kind);
    }

    private void thenNoNewerVersionIsKnown() {
        assertThat(this.status.available()).isEmpty();
    }

    private void thenTheCheckFailedWith(String reason) {
        assertThat(this.status.failure()).hasValueSatisfying(f -> assertThat(f).contains(reason));
    }

    private void thenTheSkippedVersionIs(String version) {
        assertThat(this.status.skipped()).contains(version);
    }
}
