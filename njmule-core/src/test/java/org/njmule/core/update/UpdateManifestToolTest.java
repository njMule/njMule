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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** The release writes the update information with tools/UpdateManifest.java: what it writes, njMule reads. */
@Timeout(120)
class UpdateManifestToolTest {

    private static final Path TOOL = Path.of("..", "tools", "UpdateManifest.java");

    @TempDir
    Path dir;

    private Path dist;
    private Path notes;
    private UpdateManifest manifest;

    @Test
    void shouldDescribeEveryPackageOfTheReleaseForTheUpdateCheck() throws Exception {
        givenTheReleasePackages(
                "njmule-0.2.0-windows-x64.msi",
                "njmule-0.2.0-windows-x64.zip",
                "njmule_0.2.0_amd64.deb",
                "njmule_0.2.0_arm64.deb",
                "njmule-0.2.0-1.x86_64.rpm",
                "njmule-0.2.0-1.aarch64.rpm",
                "njmule-0.2.0-linux-x64.tar.xz",
                "njmule-0.2.0-linux-arm64.tar.xz",
                "njmule-0.2.0-linux-x64-static.tar.xz",
                "njmule-0.2.0-macos-arm64.dmg",
                "njmule-0.2.0-synology-x86_64.spk",
                "njmule-0.2.0-sbom.cdx.json");
        givenTheNotes("### Added\n\n- Updates with \"quotes\" and a \\ backslash.\n");

        whenTheToolDescribesThem("0.2.0");

        thenTheVersionIs("0.2.0");
        thenThePackagesAre(
                "windows-x64 msi",
                "windows-x64 zip",
                "linux-x64 deb",
                "linux-arm64 deb",
                "linux-x64 rpm",
                "linux-arm64 rpm",
                "linux-x64 tar",
                "linux-arm64 tar",
                "linux-x64 static-tar",
                "macos-arm64 dmg",
                "linux-x64 spk");
        thenTheNotesAre("### Added\n\n- Updates with \"quotes\" and a \\ backslash.");
        thenItIsCritical(false);
    }

    @Test
    void shouldMarkCriticalAReleaseWithSecurityFixes() throws Exception {
        givenTheReleasePackages("njmule-0.2.1-windows-x64.msi");
        givenTheNotes("### Security\n\n- A fix.\n");

        whenTheToolDescribesThem("0.2.1");

        thenItIsCritical(true);
    }

    private void givenTheReleasePackages(String... names) throws IOException {
        this.dist = Files.createDirectories(this.dir.resolve("dist"));
        for (String name : names) {
            Files.writeString(this.dist.resolve(name), name);
        }
    }

    private void givenTheNotes(String text) throws IOException {
        this.notes = Files.writeString(this.dir.resolve("notes.md"), text);
    }

    private void whenTheToolDescribesThem(String version) throws Exception {
        Path out = this.dir.resolve(UpdateManifest.FILE_NAME);
        String java = ProcessHandle.current().info().command().orElse("java");
        Process tool = new ProcessBuilder(java, TOOL.toString(), this.dist.toString(), version, this.notes.toString())
                .redirectOutput(out.toFile())
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start();
        assertThat(tool.waitFor(90, TimeUnit.SECONDS)).isTrue();
        assertThat(tool.exitValue()).isZero();
        this.manifest = UpdateManifest.parse(
                Files.readString(out, StandardCharsets.UTF_8).getBytes(StandardCharsets.UTF_8));
    }

    private void thenTheVersionIs(String version) {
        assertThat(this.manifest.version().getVersion()).isEqualTo(version);
    }

    private void thenThePackagesAre(String... platformsAndKinds) {
        List<String> found = this.manifest.assets().stream()
                .map(a -> a.platform() + " " + a.kind())
                .toList();
        assertThat(found).containsExactlyInAnyOrder(platformsAndKinds);
        assertThat(this.manifest.assets())
                .allSatisfy(a -> assertThat(a.size()).isEqualTo(a.name().length()));
    }

    private void thenTheNotesAre(String text) {
        assertThat(this.manifest.notes()).isEqualTo(text);
    }

    private void thenItIsCritical(boolean expected) {
        assertThat(this.manifest.critical()).isEqualTo(expected);
    }
}
