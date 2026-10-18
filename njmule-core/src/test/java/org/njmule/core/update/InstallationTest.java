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
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.njmule.core.api.InstallKind;
import org.njmule.core.config.OsFamily;

class InstallationTest {

    @TempDir
    Path dir;

    private Optional<String> variable = Optional.empty();
    private Optional<Path> executableDir = Optional.empty();
    private Installation installation;

    @Test
    void shouldTakeTheKindThePackageWroteBesideTheExecutable() throws IOException {
        givenThePackageWrote(this.dir, "kind=msi\n");

        whenResolvedOn(OsFamily.WINDOWS, "amd64");

        thenTheKindIs(InstallKind.MSI);
        thenThePlatformIs("windows-x64");
    }

    @Test
    void shouldTakeTheKindOfAMacBundleFromItsResources() throws IOException {
        givenABundleWhosePackageWrote("kind=dmg\n");

        whenResolvedOn(OsFamily.MACOS, "aarch64");

        thenTheKindIs(InstallKind.DMG);
        thenThePlatformIs("macos-arm64");
    }

    @Test
    void shouldLetTheEnvironmentOfAContainerWin() throws IOException {
        givenThePackageWrote(this.dir, "kind=tar\n");
        givenTheVariable("docker");

        whenResolvedOn(OsFamily.LINUX, "amd64");

        thenTheKindIs(InstallKind.DOCKER);
    }

    @Test
    void shouldNotKnowACopyBuiltFromTheSources() {
        whenResolvedOn(OsFamily.LINUX, "amd64");

        thenTheKindIs(InstallKind.UNKNOWN);
    }

    @Test
    void shouldNotKnowAKindItHasNoNameFor() throws IOException {
        givenThePackageWrote(this.dir, "kind=snap\n");

        whenResolvedOn(OsFamily.LINUX, "amd64");

        thenTheKindIs(InstallKind.UNKNOWN);
    }

    @ParameterizedTest
    @CsvSource({"amd64, linux-x64", "x86_64, linux-x64", "aarch64, linux-arm64", "arm64, linux-arm64"})
    void shouldNameTheProcessorAsTheReleasesDo(String arch, String platform) {
        whenResolvedOn(OsFamily.LINUX, arch);

        thenThePlatformIs(platform);
    }

    private void givenThePackageWrote(Path where, String text) throws IOException {
        Files.writeString(where.resolve(Installation.FILE_NAME), text);
        if (this.executableDir.isEmpty()) {
            this.executableDir = Optional.of(where);
        }
    }

    private void givenABundleWhosePackageWrote(String text) throws IOException {
        Path contents = this.dir.resolve("njMule.app").resolve("Contents");
        Files.writeString(Files.createDirectories(contents.resolve("Resources")).resolve(Installation.FILE_NAME), text);
        this.executableDir = Optional.of(Files.createDirectories(contents.resolve("MacOS")));
    }

    private void givenTheVariable(String value) {
        this.variable = Optional.of(value);
    }

    private void whenResolvedOn(OsFamily os, String arch) {
        this.installation = Installation.resolve(this.variable, this.executableDir, os, arch);
    }

    private void thenTheKindIs(InstallKind expected) {
        assertThat(this.installation.kind()).isEqualTo(expected);
    }

    private void thenThePlatformIs(String expected) {
        assertThat(this.installation.platform()).isEqualTo(expected);
    }
}
