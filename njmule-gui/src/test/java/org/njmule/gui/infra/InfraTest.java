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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InfraTest {

    @TempDir Path dir;

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
        assertThat(again.window())
                .hasValueSatisfying(b -> assertThat(b).containsExactly(10, 20, 800, 600));
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
    void everyFamilyHasLightAndDarkThemes() {
        for (Themes.Family family : Themes.Family.values()) {
            assertThat(Themes.theme(family, true).isDarkMode()).isTrue();
            assertThat(Themes.theme(family, false).getUserAgentStylesheet()).isNotBlank();
        }
        assertThat(Themes.theme(Themes.Family.NORD, false).isDarkMode()).isFalse();
    }
}
