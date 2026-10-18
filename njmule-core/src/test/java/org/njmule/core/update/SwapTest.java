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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SwapTest {

    @TempDir
    Path dir;

    private Path staged;
    private Path target;
    private Swap swap;
    private Swap.Copier copier = Swap.FILES;
    private Exception failure;

    @Test
    void shouldPutTheNewFilesInPlaceAndKeepTheOldOnes() throws IOException {
        givenTheInstalledFile("njmule.exe", "0.1.0");
        givenTheNewFile("njmule.exe", "0.2.0");

        whenApplied();

        thenNoFailureOccurred();
        thenTheTargetHolds("njmule.exe", "0.2.0");
        thenTheTargetHolds("njmule.exe.old", "0.1.0");
    }

    @Test
    void shouldLeaveWhatThePackageDoesNotHold() throws IOException {
        givenTheInstalledFile("njmule.exe", "0.1.0");
        givenTheInstalledFile("portable.flag", "");
        givenTheInstalledFile("config/njmule.properties", "user.nick=me");
        givenTheNewFile("njmule.exe", "0.2.0");

        whenApplied();

        thenTheTargetHolds("portable.flag", "");
        thenTheTargetHolds("config/njmule.properties", "user.nick=me");
    }

    @Test
    void shouldAddTheFilesAndFoldersOnlyTheNewVersionHas() throws IOException {
        givenTheInstalledFile("njmule.exe", "0.1.0");
        givenTheNewFile("njmule.exe", "0.2.0");
        givenTheNewFile("lib/fx/glass.dll", "dll");

        whenApplied();

        thenTheTargetHolds("lib/fx/glass.dll", "dll");
    }

    @Test
    void shouldPutTheOldVersionBackWhenAskedLater() throws IOException {
        givenTheInstalledFile("njmule.exe", "0.1.0");
        givenTheNewFile("njmule.exe", "0.2.0");
        givenTheNewFile("lib/fx/glass.dll", "dll");
        givenApplied();

        whenRolledBack();

        thenNoFailureOccurred();
        thenTheTargetHolds("njmule.exe", "0.1.0");
        thenTheTargetHasNo("njmule.exe.old");
        thenTheTargetHasNo("lib");
    }

    @Test
    void shouldPutTheOldVersionBackWhenTheSwapFails() throws IOException {
        givenTheInstalledFile("a.dll", "old a");
        givenTheInstalledFile("b.dll", "old b");
        givenTheNewFile("a.dll", "new a");
        givenTheNewFile("b.dll", "new b");
        givenACopyThatFailsOn("b.dll");

        whenApplied();

        thenAFailureOccurred();
        thenTheTargetHolds("a.dll", "old a");
        thenTheTargetHolds("b.dll", "old b");
        thenTheTargetHasNo("a.dll.old");
    }

    @Test
    void shouldRemoveTheOldFilesOnceTheNewVersionStays() throws IOException {
        givenTheInstalledFile("njmule.exe", "0.1.0");
        givenTheNewFile("njmule.exe", "0.2.0");
        givenApplied();

        whenCleanedUp();

        thenNoFailureOccurred();
        thenTheTargetHolds("njmule.exe", "0.2.0");
        thenTheTargetHasNo("njmule.exe.old");
    }

    @Test
    void shouldReplaceOldFilesAnEarlierUpdateLeft() throws IOException {
        givenTheInstalledFile("njmule.exe", "0.1.5");
        givenTheInstalledFile("njmule.exe.old", "0.1.0");
        givenTheNewFile("njmule.exe", "0.2.0");

        whenApplied();

        thenTheTargetHolds("njmule.exe.old", "0.1.5");
    }

    @Test
    void shouldSwapAWholeBundle() throws IOException {
        givenAnInstalledBundle("0.1.0");
        givenANewBundle("0.2.0");

        whenApplied();

        thenNoFailureOccurred();
        thenTheBundleIs("0.2.0");
        thenTheOldBundleIs("0.1.0");
    }

    @Test
    void shouldPutTheOldBundleBack() throws IOException {
        givenAnInstalledBundle("0.1.0");
        givenANewBundle("0.2.0");
        givenApplied();

        whenRolledBack();

        thenTheBundleIs("0.1.0");
        thenThereIsNoOldBundle();
    }

    @Test
    void shouldRemoveTheOldBundle() throws IOException {
        givenAnInstalledBundle("0.1.0");
        givenANewBundle("0.2.0");
        givenApplied();

        whenCleanedUp();

        thenTheBundleIs("0.2.0");
        thenThereIsNoOldBundle();
    }

    private void givenTheInstalledFile(String name, String text) throws IOException {
        this.target = this.dir.resolve("installed");
        write(this.target.resolve(name), text);
    }

    private void givenTheNewFile(String name, String text) throws IOException {
        this.staged = this.dir.resolve("cache").resolve("files");
        write(this.staged.resolve(name), text);
    }

    private void givenAnInstalledBundle(String version) throws IOException {
        this.target = this.dir.resolve("Applications").resolve("njMule.app");
        write(this.target.resolve("Contents/Info.plist"), version);
    }

    private void givenANewBundle(String version) throws IOException {
        this.staged = this.dir.resolve("cache").resolve("files").resolve("njMule.app");
        write(this.staged.resolve("Contents/Info.plist"), version);
    }

    private void givenACopyThatFailsOn(String name) {
        this.copier = (from, to) -> {
            if (from.getFileName().toString().equals(name)) {
                throw new IOException("disk full");
            }
            Swap.FILES.copy(from, to);
        };
    }

    private void givenApplied() throws IOException {
        swap().apply();
    }

    private void whenApplied() {
        try {
            swap().apply();
        } catch (IOException e) {
            this.failure = e;
        }
    }

    private void whenRolledBack() {
        try {
            swap().rollBack();
        } catch (IOException e) {
            this.failure = e;
        }
    }

    private void whenCleanedUp() {
        try {
            swap().cleanUp();
        } catch (IOException e) {
            this.failure = e;
        }
    }

    private void thenNoFailureOccurred() {
        assertThat(this.failure).isNull();
    }

    private void thenAFailureOccurred() {
        assertThat(this.failure).isInstanceOf(IOException.class);
    }

    private void thenTheTargetHolds(String name, String text) throws IOException {
        assertThat(Files.readString(this.target.resolve(name))).isEqualTo(text);
    }

    private void thenTheTargetHasNo(String name) {
        assertThat(this.target.resolve(name)).doesNotExist();
    }

    private void thenTheBundleIs(String version) throws IOException {
        assertThat(Files.readString(this.target.resolve("Contents/Info.plist"))).isEqualTo(version);
    }

    private void thenTheOldBundleIs(String version) throws IOException {
        assertThat(Files.readString(this.target.resolveSibling("njMule.app.old").resolve("Contents/Info.plist")))
                .isEqualTo(version);
    }

    private void thenThereIsNoOldBundle() {
        assertThat(this.target.resolveSibling("njMule.app.old")).doesNotExist();
    }

    private Swap swap() {
        if (this.swap == null) {
            Path journal = this.dir.resolve("cache").resolve(UpdateHelper.JOURNAL);
            this.swap = this.target.getFileName().toString().endsWith(".app")
                    ? Swap.bundle(this.staged, this.target, journal, this.copier)
                    : Swap.folder(this.staged, this.target, journal, this.copier);
        }
        return this.swap;
    }

    private static void write(Path file, String text) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }
}
