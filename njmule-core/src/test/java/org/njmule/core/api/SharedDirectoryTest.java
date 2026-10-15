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
package org.njmule.core.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SharedDirectoryTest {

    @TempDir
    Path dir;

    private List<Path> sensitiveDirectories;
    private boolean sensitive;

    @Test
    void shouldTakeTheRootForSensitive() {
        whenTheRootOfTheHomeIsChecked();

        thenItIsSensitive();
    }

    @Test
    void shouldTakeThePersonalDocumentsForSensitive() {
        whenTheFolderOfTheHomeIsChecked("Documents");

        thenItIsSensitive();
    }

    @Test
    void shouldTakeAFolderInsideTheDesktopForSensitive() {
        whenTheFolderOfTheHomeIsChecked("Desktop", "photos");

        thenItIsSensitive();
    }

    @Test
    void shouldNotTakeThePersonalMusicForSensitive() {
        whenTheFolderOfTheHomeIsChecked("Music");

        thenItIsNotSensitive();
    }

    @Test
    void shouldTakeASystemDirectoryForSensitive() {
        whenTheSystemDirectoryIsChecked();

        thenItIsSensitive();
    }

    @Test
    void shouldTakeTheHomeForSensitive() {
        whenTheHomeIsChecked();

        thenItIsSensitive();
    }

    @Test
    void shouldTakeTheDirectoryOfAllHomesForSensitive() {
        whenTheDirectoryOfAllHomesIsChecked();

        thenItIsSensitive();
    }

    @Test
    void shouldTakeTheSshKeysForSensitive() {
        whenTheFolderOfTheHomeIsChecked(".ssh");

        thenItIsSensitive();
    }

    @Test
    void shouldTakeAFolderInsideTheGnupgKeysForSensitive() {
        whenTheFolderOfTheHomeIsChecked(".gnupg", "private-keys-v1.d");

        thenItIsSensitive();
    }

    @ParameterizedTest
    @ValueSource(strings = {"home", ""})
    void shouldTakeADirectoryContainingASensitiveOneForSensitive(String directory) throws IOException {
        givenTheDirectories("home/Documents", "home/Music");
        givenTheSensitiveDirectories("home/Documents");

        whenTheDirectoryIsChecked(directory);

        thenItIsSensitive();
    }

    @Test
    void shouldTakeADirectoryInsideASensitiveOneForSensitive() throws IOException {
        givenTheDirectories("home/Documents", "home/Music");
        givenTheSensitiveDirectories("home/Documents");

        whenTheDirectoryIsChecked("home/Documents/taxes");

        thenItIsSensitive();
    }

    @Test
    void shouldNotTakeADirectoryBesideASensitiveOneForSensitive() throws IOException {
        givenTheDirectories("home/Documents", "home/Music");
        givenTheSensitiveDirectories("home/Documents");

        whenTheDirectoryIsChecked("home/Music");

        thenItIsNotSensitive();
    }

    @Test
    void shouldNotTakeASiblingWithTheSamePrefixForSensitive() throws IOException {
        givenTheDirectories("home/Documents", "home/Music");
        givenTheSensitiveDirectories("home/Documents");

        whenTheDirectoryIsChecked("home2");

        thenItIsNotSensitive("a sibling with the same prefix");
    }

    @Test
    void shouldJudgeALinkByWhereItLeads() throws IOException {
        givenTheDirectories("home/Documents", "share");
        givenALinkTo("share/docs", "home/Documents");
        givenTheSensitiveDirectories("home/Documents");

        whenTheDirectoryIsChecked("share/docs");

        thenItIsSensitive();
    }

    @Test
    void shouldNotTakeTheDirectoryBesideASensitiveLinkForSensitive() throws IOException {
        givenTheDirectories("home/Documents", "share");
        givenALinkTo("share/docs", "home/Documents");
        givenTheSensitiveDirectories("share/docs");

        whenTheDirectoryIsChecked("share/other");

        thenItIsNotSensitive();
    }

    @Test
    void shouldTakeWhereASensitiveLinkLeadsForSensitive() throws IOException {
        givenTheDirectories("home/Documents", "share");
        givenALinkTo("share/docs", "home/Documents");
        givenTheSensitiveDirectories("share/docs");

        whenTheDirectoryIsChecked("home/Documents");

        thenItIsSensitive();
    }

    private void givenTheDirectories(String... relatives) throws IOException {
        for (String relative : relatives) {
            Files.createDirectories(this.dir.resolve(relative));
        }
    }

    private void givenALinkTo(String link, String target) throws IOException {
        try {
            Files.createSymbolicLink(this.dir.resolve(link), this.dir.resolve(target));
        } catch (IOException | UnsupportedOperationException e) {
            assumeTrue(false, "symbolic links are not available: " + e);
        }
    }

    private void givenTheSensitiveDirectories(String... relatives) {
        this.sensitiveDirectories =
                Arrays.stream(relatives).map(this.dir::resolve).toList();
    }

    private void whenTheRootOfTheHomeIsChecked() {
        this.sensitive = SharedDirectory.isSensitive(home().getRoot());
    }

    private void whenTheHomeIsChecked() {
        this.sensitive = SharedDirectory.isSensitive(home());
    }

    private void whenTheDirectoryOfAllHomesIsChecked() {
        this.sensitive = SharedDirectory.isSensitive(home().getParent());
    }

    private void whenTheFolderOfTheHomeIsChecked(String... names) {
        Path folder = home();
        for (String name : names) {
            folder = folder.resolve(name);
        }
        this.sensitive = SharedDirectory.isSensitive(folder);
    }

    private void whenTheSystemDirectoryIsChecked() {
        String windows = System.getenv("SystemRoot");
        Path system = windows != null ? Path.of(windows, "System32") : Path.of("/etc");
        this.sensitive = SharedDirectory.isSensitive(system);
    }

    private void whenTheDirectoryIsChecked(String relative) {
        this.sensitive = SharedDirectory.isSensitive(this.dir.resolve(relative), this.sensitiveDirectories);
    }

    private void thenItIsSensitive() {
        assertThat(this.sensitive).isTrue();
    }

    private void thenItIsNotSensitive() {
        assertThat(this.sensitive).isFalse();
    }

    private void thenItIsNotSensitive(String description) {
        assertThat(this.sensitive).as(description).isFalse();
    }

    private static Path home() {
        return Path.of(System.getProperty("user.home"));
    }
}
