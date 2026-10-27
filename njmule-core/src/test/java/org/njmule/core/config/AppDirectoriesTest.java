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
package org.njmule.core.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class AppDirectoriesTest {

    @TempDir
    Path home;

    private OsFamily os;
    private final Map<String, String> env = new HashMap<>();
    private Optional<Path> overrideRoot = Optional.empty();
    private Optional<Path> executableDir = Optional.empty();
    private Optional<Path> windowsDownloads = Optional.empty();
    private AppDirectories directories;
    private Optional<Path> download;
    private OsFamily detected;
    private Throwable exception;

    @Test
    void shouldUseRoamingForConfigAndLocalForBulkDataOnWindows() {
        givenTheOperatingSystem(OsFamily.WINDOWS);
        givenTheVariableInTheHome("APPDATA", "AppData/Roaming");
        givenTheVariableInTheHome("LOCALAPPDATA", "AppData/Local");
        givenTheVariableInTheHome("USERPROFILE", "");

        whenTheDirectoriesAreResolved();

        thenTheConfigDirectoryIs("AppData/Roaming/njMule/config");
        thenTheGuiDirectoryIs("AppData/Roaming/njMule/gui");
        thenTheDataDirectoryIs("AppData/Local/njMule/data");
        thenTheTempDirectoryIs("AppData/Local/njMule/temp");
        thenTheCacheDirectoryIs("AppData/Local/njMule/cache");
        thenTheLogsDirectoryIs("AppData/Local/njMule/logs");
        thenTheDownloadsDirectoryIs("Downloads/njMule");
        thenTheDirectoriesArePortable(false);
    }

    @Test
    void shouldFollowTheDownloadsFolderTheUserMovedOnWindows() {
        givenTheOperatingSystem(OsFamily.WINDOWS);
        givenTheDownloadsFolderMovedInTheHome("Data/Downloads");

        whenTheDirectoriesAreResolved();

        thenTheDownloadsDirectoryIs("Data/Downloads/njMule");
    }

    @Test
    void shouldFallBackToTheProfileOnWindowsWhenVariablesAreMissing() {
        givenTheOperatingSystem(OsFamily.WINDOWS);

        whenTheDirectoriesAreResolved();

        thenTheConfigDirectoryIs("AppData/Roaming/njMule/config");
    }

    @Test
    void shouldUseTheLibraryOnMacos() {
        givenTheOperatingSystem(OsFamily.MACOS);

        whenTheDirectoriesAreResolved();

        thenTheConfigDirectoryIs("Library/Application Support/njMule/config");
        thenTheDataDirectoryIs("Library/Application Support/njMule/data");
        thenTheCacheDirectoryIs("Library/Caches/org.njmule.njMule");
        thenTheLogsDirectoryIs("Library/Logs/njMule");
        thenTheDownloadsDirectoryIs("Downloads/njMule");
    }

    @Test
    void shouldFollowXdgDefaultsOnLinux() {
        givenTheOperatingSystem(OsFamily.LINUX);

        whenTheDirectoriesAreResolved();

        thenTheConfigDirectoryIs(".config/njmule");
        thenTheDataDirectoryIs(".local/share/njmule");
        thenTheTempDirectoryIs(".local/share/njmule/temp");
        thenTheCacheDirectoryIs(".cache/njmule");
        thenTheLogsDirectoryIs(".local/state/njmule/logs");
        thenTheDownloadsDirectoryIs("Downloads/njMule");
    }

    @Test
    void shouldHonourAbsoluteXdgVariablesAndIgnoreRelativeOnesOnLinux() {
        givenTheOperatingSystem(OsFamily.LINUX);
        givenTheVariableInTheHome("XDG_CONFIG_HOME", "cfg");
        givenTheVariable("XDG_CACHE_HOME", "rel");

        whenTheDirectoriesAreResolved();

        thenTheConfigDirectoryIs("cfg/njmule");
        thenTheCacheDirectoryIs(".cache/njmule");
    }

    @Test
    void shouldReadTheDownloadDirectoryFromUserDirsOnLinux() throws IOException {
        givenTheOperatingSystem(OsFamily.LINUX);
        givenTheFileInTheHome(".config/user-dirs.dirs", """
                # comment
                XDG_DESKTOP_DIR="$HOME/Desktop"
                XDG_DOWNLOAD_DIR="$HOME/Scaricati"
                """);

        whenTheDirectoriesAreResolved();

        thenTheDownloadsDirectoryIs("Scaricati/njMule");
    }

    @Test
    void shouldReadAnAbsoluteDownloadDirectoryFromUserDirs() throws IOException {
        givenUserDirsWithTheAbsoluteDownloadDirectoryInTheHome("abs");

        whenTheDownloadDirectoryIsReadFromUserDirs();

        thenTheDownloadDirectoryReadIs("abs");
    }

    @Test
    void shouldIgnoreARelativeDownloadDirectoryInUserDirs() throws IOException {
        givenTheFileInTheHome("user-dirs.dirs", "XDG_DOWNLOAD_DIR=relative\n");

        whenTheDownloadDirectoryIsReadFromUserDirs();

        thenNoDownloadDirectoryIsRead();
    }

    @Test
    void shouldReadTheHomeAsDownloadDirectoryFromUserDirs() throws IOException {
        givenTheFileInTheHome("user-dirs.dirs", "XDG_DOWNLOAD_DIR=\"$HOME\"\n");

        whenTheDownloadDirectoryIsReadFromUserDirs();

        thenTheDownloadDirectoryReadIs("");
    }

    @Test
    void shouldLetTheLastDownloadDirectoryLineWinAsInTheShell() throws IOException {
        givenTheFileInTheHome("user-dirs.dirs", """
                XDG_DOWNLOAD_DIR="$HOME/Old"
                # moved
                XDG_DOWNLOAD_DIR="$HOME/New"
                """);

        whenTheDownloadDirectoryIsReadFromUserDirs();

        thenTheDownloadDirectoryReadIs("New");
    }

    @Test
    void shouldReadNoDownloadDirectoryWhenTheLastLineIsRelative() throws IOException {
        givenTheFileInTheHome("user-dirs.dirs", "XDG_DOWNLOAD_DIR=\"$HOME/Old\"\nXDG_DOWNLOAD_DIR=relative\n");

        whenTheDownloadDirectoryIsReadFromUserDirs();

        thenNoDownloadDirectoryIsRead();
    }

    @Test
    void shouldPutEverythingBelowAnOverrideRoot() {
        givenTheOperatingSystem(OsFamily.WINDOWS);
        givenTheOverrideRootInTheHome("custom");

        whenTheDirectoriesAreResolved();

        thenTheConfigDirectoryIs("custom/config");
        thenTheDownloadsDirectoryIs("custom/incoming");
        thenTheDirectoriesArePortable(false);
    }

    @Test
    void shouldEnablePortableModeWithAMarkerNextToTheExecutable() throws IOException {
        givenTheOperatingSystem(OsFamily.LINUX);
        givenThePortableMarkerNextToTheExecutableIn("app");

        whenTheDirectoriesAreResolved();

        thenTheDirectoriesArePortable(true);
        thenTheConfigDirectoryIs("app/data/config");
    }

    @Test
    void shouldCreateTheDirectoriesOfTheProfileButNotThoseOfTheDownloads() throws IOException {
        givenTheDirectoriesBelowTheRoot("r");

        whenAllDirectoriesAreCreated();

        thenTheLogsDirectoryExists();
        thenTheDirectoriesOfTheDownloadsExist(false);
    }

    @Test
    void shouldRejectRelativeDirectories() {
        whenDirectoriesAreMadeWithTheRelativeConfig("rel");

        thenExceptionOccurred(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @CsvSource({"Windows 11, WINDOWS", "Mac OS X, MACOS", "Linux, LINUX", "FreeBSD, LINUX", ", LINUX"})
    void shouldDetectTheOsFamily(String osName, OsFamily expected) {
        whenTheOsFamilyIsDetectedFrom(osName);

        thenTheDetectedOsFamilyIs(expected);
    }

    @Test
    void shouldDetectTheCurrentOsFamily() {
        whenTheCurrentOsFamilyIsDetected();

        thenAnOsFamilyIsDetected();
    }

    private void givenTheOperatingSystem(OsFamily family) {
        this.os = family;
    }

    private void givenTheVariable(String name, String value) {
        this.env.put(name, value);
    }

    private void givenTheVariableInTheHome(String name, String pathInTheHome) {
        this.env.put(name, this.home.resolve(pathInTheHome).toString());
    }

    private void givenTheFileInTheHome(String pathInTheHome, String content) throws IOException {
        Path file = this.home.resolve(pathInTheHome);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private void givenUserDirsWithTheAbsoluteDownloadDirectoryInTheHome(String pathInTheHome) throws IOException {
        Files.writeString(
                this.home.resolve("user-dirs.dirs"), "XDG_DOWNLOAD_DIR=\"" + this.home.resolve(pathInTheHome) + "\"\n");
    }

    private void givenTheOverrideRootInTheHome(String pathInTheHome) {
        this.overrideRoot = Optional.of(this.home.resolve(pathInTheHome));
    }

    private void givenThePortableMarkerNextToTheExecutableIn(String pathInTheHome) throws IOException {
        Path exeDir = Files.createDirectories(this.home.resolve(pathInTheHome));
        Files.createFile(exeDir.resolve(AppDirectories.PORTABLE_MARKER));
        this.executableDir = Optional.of(exeDir);
    }

    private void givenTheDownloadsFolderMovedInTheHome(String pathInTheHome) {
        this.windowsDownloads = Optional.of(this.home.resolve(pathInTheHome));
    }

    private void givenTheDirectoriesBelowTheRoot(String pathInTheHome) {
        this.directories = AppDirectories.underRoot(this.home.resolve(pathInTheHome), false);
    }

    private void whenTheDirectoriesAreResolved() {
        this.directories = AppDirectories.resolve(
                this.os,
                this.env,
                this.home,
                this.overrideRoot,
                this.executableDir,
                variables -> this.windowsDownloads);
    }

    private void whenTheDownloadDirectoryIsReadFromUserDirs() {
        this.download = AppDirectories.userDirsDownload(this.home.resolve("user-dirs.dirs"), this.home);
    }

    private void whenAllDirectoriesAreCreated() throws IOException {
        this.directories.createAll();
    }

    private void whenDirectoriesAreMadeWithTheRelativeConfig(String config) {
        Path rel = Path.of(config);
        Path abs = this.home;
        this.exception = catchThrowable(() -> new AppDirectories(rel, abs, abs, abs, abs, abs, abs, false));
    }

    private void whenTheOsFamilyIsDetectedFrom(String osName) {
        this.detected = OsFamily.fromOsName(osName);
    }

    private void whenTheCurrentOsFamilyIsDetected() {
        this.detected = OsFamily.current();
    }

    private void thenTheConfigDirectoryIs(String pathInTheHome) {
        assertThat(this.directories.config()).isEqualTo(this.home.resolve(pathInTheHome));
    }

    private void thenTheGuiDirectoryIs(String pathInTheHome) {
        assertThat(this.directories.gui()).isEqualTo(this.home.resolve(pathInTheHome));
    }

    private void thenTheDataDirectoryIs(String pathInTheHome) {
        assertThat(this.directories.data()).isEqualTo(this.home.resolve(pathInTheHome));
    }

    private void thenTheTempDirectoryIs(String pathInTheHome) {
        assertThat(this.directories.temp()).isEqualTo(this.home.resolve(pathInTheHome));
    }

    private void thenTheCacheDirectoryIs(String pathInTheHome) {
        assertThat(this.directories.cache()).isEqualTo(this.home.resolve(pathInTheHome));
    }

    private void thenTheLogsDirectoryIs(String pathInTheHome) {
        assertThat(this.directories.logs()).isEqualTo(this.home.resolve(pathInTheHome));
    }

    private void thenTheDownloadsDirectoryIs(String pathInTheHome) {
        assertThat(this.directories.downloads()).isEqualTo(this.home.resolve(pathInTheHome));
    }

    private void thenTheDirectoriesArePortable(boolean expected) {
        assertThat(this.directories.portable()).isEqualTo(expected);
    }

    private void thenTheLogsDirectoryExists() {
        assertThat(this.directories.logs()).isDirectory();
    }

    // The engine creates those the preferences name, which may be others.
    private void thenTheDirectoriesOfTheDownloadsExist(boolean exist) {
        assertThat(Files.isDirectory(this.directories.downloads())).isEqualTo(exist);
        assertThat(Files.isDirectory(this.directories.temp())).isEqualTo(exist);
    }

    private void thenTheDownloadDirectoryReadIs(String pathInTheHome) {
        assertThat(this.download).contains(this.home.resolve(pathInTheHome));
    }

    private void thenNoDownloadDirectoryIsRead() {
        assertThat(this.download).isEmpty();
    }

    private void thenTheDetectedOsFamilyIs(OsFamily expected) {
        assertThat(this.detected).isEqualTo(expected);
    }

    private void thenAnOsFamilyIsDetected() {
        assertThat(this.detected).isNotNull();
    }

    private void thenExceptionOccurred(Class<? extends Throwable> type) {
        assertThat(this.exception).isInstanceOf(type);
    }
}
