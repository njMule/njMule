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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AppDirectoriesTest {

    @TempDir
    Path home;

    private AppDirectories resolve(OsFamily os, Map<String, String> env) {
        return AppDirectories.resolve(os, env, this.home, Optional.empty(), Optional.empty());
    }

    @Test
    void windowsUsesRoamingForConfigAndLocalForBulkData() {
        Path roaming = this.home.resolve("AppData/Roaming");
        Path local = this.home.resolve("AppData/Local");
        AppDirectories d = resolve(
                OsFamily.WINDOWS,
                Map.of(
                        "APPDATA", roaming.toString(),
                        "LOCALAPPDATA", local.toString(),
                        "USERPROFILE", this.home.toString()));

        assertThat(d.config()).isEqualTo(roaming.resolve("njMule/config"));
        assertThat(d.gui()).isEqualTo(roaming.resolve("njMule/gui"));
        assertThat(d.data()).isEqualTo(local.resolve("njMule/data"));
        assertThat(d.temp()).isEqualTo(local.resolve("njMule/temp"));
        assertThat(d.cache()).isEqualTo(local.resolve("njMule/cache"));
        assertThat(d.logs()).isEqualTo(local.resolve("njMule/logs"));
        assertThat(d.downloads()).isEqualTo(this.home.resolve("Downloads/njMule"));
        assertThat(d.portable()).isFalse();
    }

    @Test
    void windowsFallsBackToTheProfileWhenVariablesAreMissing() {
        AppDirectories d = resolve(OsFamily.WINDOWS, Map.of());
        assertThat(d.config()).isEqualTo(this.home.resolve("AppData/Roaming/njMule/config"));
    }

    @Test
    void macosUsesTheLibrary() {
        AppDirectories d = resolve(OsFamily.MACOS, Map.of());

        Path support = this.home.resolve("Library/Application Support/njMule");
        assertThat(d.config()).isEqualTo(support.resolve("config"));
        assertThat(d.data()).isEqualTo(support.resolve("data"));
        assertThat(d.cache()).isEqualTo(this.home.resolve("Library/Caches/org.njmule.njMule"));
        assertThat(d.logs()).isEqualTo(this.home.resolve("Library/Logs/njMule"));
        assertThat(d.downloads()).isEqualTo(this.home.resolve("Downloads/njMule"));
    }

    @Test
    void linuxFollowsXdgDefaults() {
        AppDirectories d = resolve(OsFamily.LINUX, Map.of());

        assertThat(d.config()).isEqualTo(this.home.resolve(".config/njmule"));
        assertThat(d.data()).isEqualTo(this.home.resolve(".local/share/njmule"));
        assertThat(d.temp()).isEqualTo(this.home.resolve(".local/share/njmule/temp"));
        assertThat(d.cache()).isEqualTo(this.home.resolve(".cache/njmule"));
        assertThat(d.logs()).isEqualTo(this.home.resolve(".local/state/njmule/logs"));
        assertThat(d.downloads()).isEqualTo(this.home.resolve("Downloads/njMule"));
    }

    @Test
    void linuxHonoursAbsoluteXdgVariablesAndIgnoresRelativeOnes() {
        Path cfg = this.home.resolve("cfg");
        AppDirectories d = resolve(OsFamily.LINUX, Map.of("XDG_CONFIG_HOME", cfg.toString(), "XDG_CACHE_HOME", "rel"));

        assertThat(d.config()).isEqualTo(cfg.resolve("njmule"));
        assertThat(d.cache()).isEqualTo(this.home.resolve(".cache/njmule"));
    }

    @Test
    void linuxReadsTheDownloadDirectoryFromUserDirs() throws IOException {
        Path config = Files.createDirectories(this.home.resolve(".config"));
        Files.writeString(config.resolve("user-dirs.dirs"), """
                # comment
                XDG_DESKTOP_DIR="$HOME/Desktop"
                XDG_DOWNLOAD_DIR="$HOME/Scaricati"
                """);

        AppDirectories d = resolve(OsFamily.LINUX, Map.of());
        assertThat(d.downloads()).isEqualTo(this.home.resolve("Scaricati/njMule"));
    }

    @Test
    void userDirsWithAbsoluteOrRelativeValues() throws IOException {
        Path file = this.home.resolve("user-dirs.dirs");
        Path abs = this.home.resolve("abs");
        Files.writeString(file, "XDG_DOWNLOAD_DIR=\"" + abs + "\"\n");
        assertThat(AppDirectories.userDirsDownload(file, this.home)).contains(abs);

        Files.writeString(file, "XDG_DOWNLOAD_DIR=relative\n");
        assertThat(AppDirectories.userDirsDownload(file, this.home)).isEmpty();

        Files.writeString(file, "XDG_DOWNLOAD_DIR=\"$HOME\"\n");
        assertThat(AppDirectories.userDirsDownload(file, this.home)).contains(this.home);
    }

    @Test
    void theLastDownloadDirectoryLineWinsAsInTheShell() throws IOException {
        Path file = this.home.resolve("user-dirs.dirs");
        Files.writeString(file, """
                XDG_DOWNLOAD_DIR="$HOME/Old"
                # moved
                XDG_DOWNLOAD_DIR="$HOME/New"
                """);
        assertThat(AppDirectories.userDirsDownload(file, this.home)).contains(this.home.resolve("New"));

        Files.writeString(file, "XDG_DOWNLOAD_DIR=\"$HOME/Old\"\nXDG_DOWNLOAD_DIR=relative\n");
        assertThat(AppDirectories.userDirsDownload(file, this.home)).isEmpty();
    }

    @Test
    void overrideRootPutsEverythingBelowIt() {
        Path root = this.home.resolve("custom");
        AppDirectories d =
                AppDirectories.resolve(OsFamily.WINDOWS, Map.of(), this.home, Optional.of(root), Optional.empty());

        assertThat(d.config()).isEqualTo(root.resolve("config"));
        assertThat(d.downloads()).isEqualTo(root.resolve("incoming"));
        assertThat(d.portable()).isFalse();
    }

    @Test
    void portableMarkerNextToTheExecutableEnablesPortableMode() throws IOException {
        Path exeDir = Files.createDirectories(this.home.resolve("app"));
        Files.createFile(exeDir.resolve(AppDirectories.PORTABLE_MARKER));

        AppDirectories d =
                AppDirectories.resolve(OsFamily.LINUX, Map.of(), this.home, Optional.empty(), Optional.of(exeDir));

        assertThat(d.portable()).isTrue();
        assertThat(d.config()).isEqualTo(exeDir.resolve("data/config"));
    }

    @Test
    void createAllCreatesEveryDirectory() throws IOException {
        AppDirectories d = AppDirectories.underRoot(this.home.resolve("r"), false);
        d.createAll();
        assertThat(d.logs()).isDirectory();
        assertThat(d.downloads()).isDirectory();
    }

    @Test
    void rejectsRelativeDirectories() {
        Path rel = Path.of("rel");
        Path abs = this.home;
        assertThatThrownBy(() -> new AppDirectories(rel, abs, abs, abs, abs, abs, abs, false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void detectsTheOsFamily() {
        assertThat(OsFamily.fromOsName("Windows 11")).isEqualTo(OsFamily.WINDOWS);
        assertThat(OsFamily.fromOsName("Mac OS X")).isEqualTo(OsFamily.MACOS);
        assertThat(OsFamily.fromOsName("Linux")).isEqualTo(OsFamily.LINUX);
        assertThat(OsFamily.fromOsName("FreeBSD")).isEqualTo(OsFamily.LINUX);
        assertThat(OsFamily.fromOsName(null)).isEqualTo(OsFamily.LINUX);
        assertThat(OsFamily.current()).isNotNull();
    }
}
