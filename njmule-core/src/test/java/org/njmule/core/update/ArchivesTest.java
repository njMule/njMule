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
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.njmule.core.update.TestPackages.Entry;

class ArchivesTest {

    @TempDir
    Path dir;

    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private Path archive;
    private Path target;
    private Exception failure;

    @Test
    void shouldUnpackAZipWithoutItsTopFolder() throws IOException {
        givenTheFile("njmule-0.2.0-windows-x64/njmule.exe", Entry.program("exe"));
        givenTheFile("njmule-0.2.0-windows-x64/lib/glass.dll", Entry.file("dll"));
        givenAZip("njmule-0.2.0-windows-x64.zip");

        whenUnpacked();

        thenNoFailureOccurred();
        thenTheTargetHolds("njmule.exe", "exe");
        thenTheTargetHolds("lib/glass.dll", "dll");
        thenTheTargetHasNo("njmule-0.2.0-windows-x64");
    }

    @Test
    void shouldReadTheBackslashesOfAZipWrittenOnWindows() throws IOException {
        givenTheFile("njmule-0.2.0-windows-x64\\njmule.exe", Entry.program("exe"));
        givenAZip("njmule-0.2.0-windows-x64.zip");

        whenUnpacked();

        thenTheTargetHolds("njmule.exe", "exe");
    }

    @Test
    void shouldUnpackATarXzWithoutItsTopFolder() throws IOException {
        givenTheFile("njmule-0.2.0-linux-x64/njmule-gui", Entry.program("gui"));
        givenTheFile("njmule-0.2.0-linux-x64/share/njmule-icon.svg", Entry.file("svg"));
        givenATarXz("njmule-0.2.0-linux-x64.tar.xz");

        whenUnpacked();

        thenNoFailureOccurred();
        thenTheTargetHolds("njmule-gui", "gui");
        thenTheTargetHolds("share/njmule-icon.svg", "svg");
    }

    @Test
    void shouldKeepAProgramOfATarXzRunnable() throws IOException {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
        givenTheFile("njmule-0.2.0-linux-x64/njmule-gui", Entry.program("gui"));
        givenATarXz("njmule-0.2.0-linux-x64.tar.xz");

        whenUnpacked();

        thenItRuns("njmule-gui");
    }

    @Test
    void shouldKeepAFileNamedAsTheTopFolder() throws IOException {
        givenTheFile("njmule/njmule", Entry.program("cli"));
        givenATarXz("njmule.tar.xz");

        whenUnpacked();

        thenTheTargetHolds("njmule", "cli");
    }

    @Test
    void shouldKeepTheNamesOfAPackageWithoutATopFolder() throws IOException {
        givenTheFile("njmule.exe", Entry.program("exe"));
        givenTheFile("LICENSE", Entry.file("GPL"));
        givenAZip("loose.zip");

        whenUnpacked();

        thenTheTargetHolds("njmule.exe", "exe");
        thenTheTargetHolds("LICENSE", "GPL");
    }

    @ParameterizedTest
    @ValueSource(strings = {"../evil.exe", "top/../../evil.exe", "/etc/evil", "C:/evil.exe", "top/./x"})
    void shouldRefuseANameThatLeavesTheTarget(String name) throws IOException {
        givenTheFile(name, Entry.file("evil"));
        givenAZip("evil.zip");

        whenUnpacked();

        thenItFailedWith("may not hold");
        thenNothingLeftTheTarget();
    }

    @Test
    void shouldRefuseALinkInATarXz() throws IOException {
        givenTheFile("njmule/njmule", Entry.program("cli"));
        givenTheFile("njmule/passwd", Entry.linkTo("/etc/passwd"));
        givenATarXz("link.tar.xz");

        whenUnpacked();

        thenItFailedWith("link");
    }

    @Test
    void shouldRefuseAnEmptyPackage() throws IOException {
        givenAZip("empty.zip");

        whenUnpacked();

        thenItFailedWith("empty");
    }

    @Test
    void shouldRefuseAPackageOfAnotherFormat() throws IOException {
        givenTheFile("njmule.exe", Entry.program("exe"));
        givenAZip("njmule.7z");

        whenUnpacked();

        thenItFailedWith("not a zip or tar.xz");
    }

    private void givenTheFile(String name, Entry entry) {
        this.entries.put(name, entry);
    }

    private void givenAZip(String name) throws IOException {
        this.archive = TestPackages.zip(this.dir.resolve("download").resolve(name), this.entries);
    }

    private void givenATarXz(String name) throws IOException {
        this.archive = TestPackages.tarXz(this.dir.resolve("download").resolve(name), this.entries);
    }

    private void whenUnpacked() {
        this.target = this.dir.resolve("staged").resolve("files");
        try {
            Archives.unpack(this.archive, this.target);
        } catch (IOException e) {
            this.failure = e;
        }
    }

    private void thenNoFailureOccurred() {
        assertThat(this.failure).isNull();
    }

    private void thenTheTargetHolds(String name, String text) throws IOException {
        assertThat(Files.readString(this.target.resolve(name))).isEqualTo(text);
    }

    private void thenTheTargetHasNo(String name) {
        assertThat(this.target.resolve(name)).doesNotExist();
    }

    private void thenItRuns(String name) throws IOException {
        assertThat(Files.getPosixFilePermissions(this.target.resolve(name)))
                .contains(PosixFilePermission.OWNER_EXECUTE);
    }

    private void thenItFailedWith(String words) {
        assertThat(this.failure).isInstanceOf(IOException.class).hasMessageContaining(words);
    }

    private void thenNothingLeftTheTarget() throws IOException {
        try (var left = Files.list(this.dir)) {
            assertThat(left.map(p -> p.getFileName().toString())).containsExactlyInAnyOrder("download", "staged");
        }
        assertThat(this.dir.resolve("evil.exe")).doesNotExist();
    }
}
