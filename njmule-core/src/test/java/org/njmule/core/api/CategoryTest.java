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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CategoryTest {

    @Test
    void patternsMatchWholeNamesWithWildcardsAndPartsWithout() {
        Category films = category("*.mkv | film?.avi", false);

        assertThat(films.matches("Holiday.MKV")).isTrue();
        assertThat(films.matches("film1.avi")).isTrue();
        assertThat(films.matches("film12.avi")).isFalse();
        assertThat(films.matches("mkv.txt")).isFalse();
        assertThat(category("ubuntu|debian", false).matches("Ubuntu 24.04 desktop.iso"))
                .isTrue();
        assertThat(category("", false).matches("anything")).isFalse();
        assertThat(category("^\\d{4} .*\\.flac$", true).matches("2024 live.flac"))
                .isTrue();
    }

    @Test
    void refusesWhatCannotBeKept() {
        assertThatThrownBy(() -> new Category(0, "x", Optional.empty(), "", 0, DownloadPriority.AUTO, "", false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Category(1, " ", Optional.empty(), "", 0, DownloadPriority.AUTO, "", false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() ->
                        new Category(1, "x", Optional.of(Path.of("relative")), "", 0, DownloadPriority.AUTO, "", false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Category(1, "x", Optional.empty(), "", 9, DownloadPriority.AUTO, "", false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> category("(unclosed", true)).isInstanceOf(IllegalArgumentException.class);
    }

    private static Category category(String patterns, boolean regex) {
        return new Category(1, "Films", Optional.empty(), "", 0, DownloadPriority.AUTO, patterns, regex);
    }
}
