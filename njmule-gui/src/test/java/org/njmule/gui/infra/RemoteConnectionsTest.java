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
package org.njmule.gui.infra;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RemoteConnectionsTest {

    private static final URI NAS = URI.create("http://nas:4711/api/v1");

    @TempDir
    Path dir;

    private Map<String, String> environment = Map.of();
    private Optional<String> token;
    private Optional<String> desktop;

    @Test
    void shouldGiveTheTokenItRemembers() throws IOException {
        givenRemembered(NAS, "secret-token");

        whenTheTokenIsLookedFor(NAS);

        thenTheTokenIs("secret-token");
        thenTheAddressesRememberedAre(NAS);
        thenOnlyItsOwnerReadsTheFile();
    }

    @Test
    void shouldTakeTheTokenOfTheEnvironmentFirst() throws IOException {
        givenRemembered(NAS, "secret-token");
        givenTheEnvironmentToken("other-token");

        whenTheTokenIsLookedFor(NAS);

        thenTheTokenIs("other-token");
    }

    @Test
    void shouldHaveNoTokenForAnAddressNeverUsed() throws IOException {
        whenTheTokenIsLookedFor(NAS);

        thenThereIsNoToken();
    }

    @Test
    void shouldStartTheLauncherOfTheWindowsDesktopApp() throws IOException {
        Path program = givenThePrograms("njmule.exe", "njmule-gui.exe");

        whenTheDesktopProgramIsFound(program);

        thenTheDesktopProgramIs(program.resolveSibling("njmule-gui.exe").toString());
    }

    @Test
    void shouldStartNothingFromADevelopmentBuild() throws IOException {
        Path program = givenThePrograms("java.exe");

        whenTheDesktopProgramIsFound(program);

        thenThereIsNoDesktopProgram();
    }

    private void givenRemembered(URI api, String secret) throws IOException {
        RemoteConnections.remember(this.dir, api, secret);
    }

    private void givenTheEnvironmentToken(String secret) {
        this.environment = Map.of(RemoteConnections.TOKEN_VARIABLE, secret);
    }

    private Path givenThePrograms(String... names) throws IOException {
        for (String name : names) {
            Files.createFile(this.dir.resolve(name));
        }
        return this.dir.resolve(names[0]);
    }

    private void whenTheTokenIsLookedFor(URI api) throws IOException {
        this.token = RemoteConnections.token(this.dir, api, this.environment);
    }

    private void whenTheDesktopProgramIsFound(Path program) {
        this.desktop = RemoteConnections.desktop(program.toString());
    }

    private void thenTheTokenIs(String expected) {
        assertThat(this.token).hasValue(expected);
    }

    private void thenThereIsNoToken() {
        assertThat(this.token).isEmpty();
    }

    private void thenTheAddressesRememberedAre(URI... expected) throws IOException {
        assertThat(RemoteConnections.remembered(this.dir)).isEqualTo(List.of(expected));
    }

    private void thenOnlyItsOwnerReadsTheFile() throws IOException {
        Path file = this.dir.resolve(RemoteConnections.FILE_NAME);
        if (file.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(file)))
                    .isEqualTo("rw-------");
        }
    }

    private void thenTheDesktopProgramIs(String expected) {
        assertThat(this.desktop).hasValue(expected);
    }

    private void thenThereIsNoDesktopProgram() {
        assertThat(this.desktop).isEmpty();
    }
}
