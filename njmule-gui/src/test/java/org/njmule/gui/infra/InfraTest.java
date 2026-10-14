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

import java.lang.ProcessBuilder.Redirect;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InfraTest {

    @Test
    void datesAndTimesFollowTheSystemWithAFullYear() {
        Instant time = Instant.parse("2026-10-13T19:07:43Z");

        String italian = Formats.dateTime(Locale.ITALY, ZoneOffset.ofHours(2)).format(time);
        String american = Formats.dateTime(Locale.US, ZoneOffset.ofHours(2)).format(time);

        assertThat(italian).startsWith("13/10/2026").contains("21:07:43");
        assertThat(american).startsWith("10/13/2026").contains("9:07:43");
    }

    @Test
    void exitPromptAndStartMinimisedAreRemembered() throws Exception {
        GuiSettings settings = GuiSettings.load(this.dir);
        assertThat(settings.confirmExit()).as("on by default").isTrue();
        assertThat(settings.startMinimized()).isFalse();
        settings.setConfirmExit(false);
        settings.setStartMinimized(true);
        settings.save();

        GuiSettings loaded = GuiSettings.load(this.dir);
        assertThat(loaded.confirmExit()).isFalse();
        assertThat(loaded.startMinimized()).isTrue();
    }

    @Test
    void historiesKeepTheLatestEntriesFirstOnce() throws Exception {
        GuiSettings settings = GuiSettings.load(this.dir);
        for (int i = 1; i <= GuiSettings.MAX_HISTORY + 2; i++) {
            settings.remember("serverMet", "https://example.org/" + i);
        }
        settings.remember("serverMet", "https://example.org/5");
        settings.save();

        List<String> history = GuiSettings.load(this.dir).history("serverMet");
        assertThat(history).hasSize(GuiSettings.MAX_HISTORY);
        assertThat(history).first().isEqualTo("https://example.org/5");
        assertThat(history).containsOnlyOnce("https://example.org/5");
        assertThat(history).doesNotContain("https://example.org/1", "https://example.org/2");
        assertThat(settings.history("nodesDat")).isEmpty();
    }

    @TempDir
    Path dir;

    @Test
    void sizesRatesDurationsAndPercentages() {
        Formats en = new Formats(Locale.ENGLISH);
        Formats it = new Formats(Locale.ITALIAN);

        assertThat(en.size(-5)).isEqualTo("0 B");
        assertThat(en.size(999)).isEqualTo("999 B");
        assertThat(en.size(1024)).isEqualTo("1.00 KB");
        assertThat(en.size(150 * 1024)).isEqualTo("150.0 KB");
        assertThat(en.size(5L * 1024 * 1024 * 1024)).isEqualTo("5.00 GB");
        assertThat(en.size(1023L * 1024 * 1024 + 1_000_000)).isEqualTo("1,024 MB");
        assertThat(it.size(1536)).isEqualTo("1,50 KB");
        assertThat(en.rate(2560)).isEqualTo("2.5 KB/s");
        assertThat(en.rate(-1)).isEqualTo("0.0 KB/s");
        assertThat(en.count(1234567)).isEqualTo("1,234,567");
        assertThat(it.count(1234567)).isEqualTo("1.234.567");
        assertThat(en.percent(1, 3)).isEqualTo("33.3 %");
        assertThat(en.percent(5, 0)).isEqualTo("0.0 %");
        assertThat(en.duration(9)).isEqualTo("9s");
        assertThat(en.duration(247)).isEqualTo("4m 07s");
        assertThat(en.duration(3900)).isEqualTo("1h 05m");
        assertThat(en.duration(2 * 86400 + 3 * 3600)).isEqualTo("2d 3h");
    }

    @Test
    void sizesRoundedUpToTheNextFormKeepItsDigits() {
        Formats en = new Formats(Locale.ENGLISH);

        assertThat(en.size(102_394)).isEqualTo("99.99 KB");
        assertThat(en.size(102_395)).as("99.995 KB").isEqualTo("100.0 KB");
        assertThat(en.size(1_023_938)).isEqualTo("999.9 KB");
        assertThat(en.size(1_023_949)).as("999.95 KB").isEqualTo("1,000 KB");
        assertThat(en.size(104_852_358L)).as("99.995 MB").isEqualTo("100.0 MB");
    }

    @Test
    void settingsHaveDefaultsAndSurviveASave() throws Exception {
        GuiSettings fresh = GuiSettings.load(this.dir);
        assertThat(fresh.theme()).isEqualTo(Themes.Family.NORD);
        assertThat(fresh.mode()).isEqualTo(GuiSettings.Mode.SYSTEM);
        assertThat(fresh.language()).isEmpty();
        assertThat(fresh.screen()).isEqualTo("transfers");
        assertThat(fresh.window()).isEmpty();
        assertThat(fresh.maximized()).isFalse();

        fresh.setTheme(Themes.Family.DRACULA);
        fresh.setMode(GuiSettings.Mode.DARK);
        fresh.setLanguage(Optional.of(Locale.ITALIAN));
        fresh.setScreen("search");
        fresh.setWindow(10, 20, 800, 600);
        fresh.setMaximized(true);
        fresh.save();

        assertThat(fresh.existed()).isFalse();
        GuiSettings again = GuiSettings.load(this.dir);
        assertThat(again.existed()).isTrue();
        assertThat(again.theme()).isEqualTo(Themes.Family.DRACULA);
        assertThat(again.mode()).isEqualTo(GuiSettings.Mode.DARK);
        assertThat(again.language()).contains(Locale.ITALIAN);
        assertThat(again.screen()).isEqualTo("search");
        assertThat(again.window()).hasValueSatisfying(b -> assertThat(b).containsExactly(10, 20, 800, 600));
        assertThat(again.maximized()).isTrue();
    }

    @Test
    void brokenSettingsFallBackToTheDefaults() throws Exception {
        Files.writeString(
                this.dir.resolve(GuiSettings.FILE_NAME),
                "theme=pink\nmode=\nwindow.x=a\nwindow.y=1\nwindow.width=50\nwindow.height=50\n");

        GuiSettings settings = GuiSettings.load(this.dir);

        assertThat(settings.theme()).isEqualTo(Themes.Family.NORD);
        assertThat(settings.mode()).isEqualTo(GuiSettings.Mode.SYSTEM);
        assertThat(settings.window()).isEmpty();
    }

    @Test
    void windowBoundsThatAreNoNumbersAreLeft() throws Exception {
        for (String broken : List.of(
                "window.x=NaN\nwindow.y=0\nwindow.width=800\nwindow.height=600\n",
                "window.x=0\nwindow.y=-Infinity\nwindow.width=800\nwindow.height=600\n",
                "window.x=0\nwindow.y=0\nwindow.width=Infinity\nwindow.height=600\n")) {
            Files.writeString(this.dir.resolve(GuiSettings.FILE_NAME), broken);
            assertThat(GuiSettings.load(this.dir).window()).as(broken).isEmpty();
        }
    }

    @Test
    void everyFamilyHasLightAndDarkThemes() {
        for (Themes.Family family : Themes.Family.values()) {
            assertThat(Themes.theme(family, true).isDarkMode()).isTrue();
            assertThat(Themes.theme(family, false).getUserAgentStylesheet()).isNotBlank();
        }
        assertThat(Themes.theme(Themes.Family.NORD, false).isDarkMode()).isFalse();
    }

    @Test
    void foldersOpenInTheFileManagerOfEachSystem() {
        Path dir = Path.of("downloads");
        assertThat(Folders.command("Windows 11", dir)).containsExactly("explorer.exe", dir.toString());
        assertThat(Folders.command("Mac OS X", dir)).containsExactly("open", dir.toString());
        assertThat(Folders.command("Linux", dir)).containsExactly("xdg-open", dir.toString());
        Path file = dir.resolve("film.mkv");
        assertThat(Preview.command("C:\\VLC\\vlc.exe", " --fullscreen  --no-audio ", file))
                .as("each argument apart, the file last")
                .containsExactly(
                        "C:\\VLC\\vlc.exe",
                        "--fullscreen",
                        "--no-audio",
                        file.toAbsolutePath().toString());
        assertThat(Preview.command("mpv", "", file))
                .containsExactly("mpv", file.toAbsolutePath().toString());
        assertThat(Folders.revealCommand("Windows 11", file))
                .as("the file selected")
                .containsExactly("explorer.exe", "/select,", file.toString());
        assertThat(Folders.revealCommand("Mac OS X", file)).containsExactly("open", "-R", file.toString());
        assertThat(Folders.revealCommand("Linux", file)).containsExactly("xdg-open", dir.toString());
    }

    @Test
    void thePlayerAndTheFileManagerHaveTheirOutputDropped() {
        Path file = Path.of("downloads", "film.mkv");
        // Never started here: only how they would start.
        for (ProcessBuilder process :
                List.of(Preview.process("vlc", "-v", file), Folders.process(Folders.command("Linux", file)))) {
            assertThat(process.redirectOutput())
                    .as("a verbose program never fills a pipe")
                    .isEqualTo(Redirect.DISCARD);
            assertThat(process.redirectError()).isEqualTo(Redirect.DISCARD);
        }
    }

    @Test
    void aPlayerPathCopiedWithItsQuotesWorks() {
        Path file = Path.of("downloads", "film.mkv");
        assertThat(Preview.command(" \"C:\\Program Files\\VLC\\vlc.exe\" ", "--fullscreen", file))
                .containsExactly(
                        "C:\\Program Files\\VLC\\vlc.exe",
                        "--fullscreen",
                        file.toAbsolutePath().toString());
        assertThat(Preview.command("'/opt/my player/mpv'", "", file))
                .containsExactly("/opt/my player/mpv", file.toAbsolutePath().toString());
        assertThat(Preview.isSet("mpv")).isTrue();
        assertThat(Preview.isSet("   ")).as("blank").isFalse();
        assertThat(Preview.isSet(" \"\" ")).as("only quotes").isFalse();
    }
}
