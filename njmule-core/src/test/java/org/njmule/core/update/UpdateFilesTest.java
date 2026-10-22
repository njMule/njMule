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
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class UpdateFilesTest {

    @TempDir
    Path dir;

    private UpdateFiles files;
    private String running = "0.1.0";
    private Exception failure;

    @Test
    void shouldKeepOnlyTheVersionsAskedForAndThePlan() throws IOException {
        givenTheFolder("0.1.5/njmule.zip");
        givenTheFolder("0.2.0/njmule.zip");
        givenTheFolder("0.3.0-beta.1/files/njmule.exe");
        givenTheFile("started-0.1.5");
        givenTheFile("started-0.2.0");
        givenTheFile(UpdateFiles.PLAN_FILE);

        whenKeepingOnly("0.2.0");

        thenWhatIsLeftIs("0.2.0", "started-0.2.0", UpdateFiles.PLAN_FILE);
    }

    @Test
    void shouldKeepTheMarkOfTheVersionRunning() throws IOException {
        givenTheFolder("0.2.0/njmule.zip");
        givenTheFile("started-0.1.5");
        givenTheRunningVersion("0.1.5");

        whenKeepingOnly();

        thenWhatIsLeftIs("started-0.1.5");
    }

    @ParameterizedTest
    @ValueSource(strings = {"..", "../0.2.0", "0.2.0/../..", "", ".hidden", "0.2.0\\..\\x"})
    void shouldRefuseAVersionThatIsNotOne(String version) {
        whenTheFolderOfIsAsked(version);

        thenItWasRefused();
    }

    private void givenTheFolder(String file) throws IOException {
        Path path = files().root().resolve(file);
        Files.createDirectories(path.getParent());
        Files.writeString(path, "x");
    }

    private void givenTheFile(String name) throws IOException {
        Files.createDirectories(files().root());
        Files.writeString(files().root().resolve(name), "x");
    }

    private void givenTheRunningVersion(String version) {
        this.running = version;
    }

    private void whenKeepingOnly(String... versions) {
        files().keepOnly(List.of(versions), this.running);
    }

    private void whenTheFolderOfIsAsked(String version) {
        try {
            files().version(version);
        } catch (IllegalArgumentException e) {
            this.failure = e;
        }
    }

    private void thenWhatIsLeftIs(String... names) throws IOException {
        try (var left = Files.list(files().root())) {
            assertThat(left.map(p -> p.getFileName().toString())).containsExactlyInAnyOrder(names);
        }
    }

    private void thenItWasRefused() {
        assertThat(this.failure).isInstanceOf(IllegalArgumentException.class);
    }

    private UpdateFiles files() {
        if (this.files == null) {
            this.files = UpdateFiles.in(this.dir);
        }
        return this.files;
    }
}
