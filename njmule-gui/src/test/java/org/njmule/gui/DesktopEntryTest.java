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
package org.njmule.gui;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class DesktopEntryTest {

    // JavaFX names the windows' class after the application class; the desktop entry must match it, or
    // Linux desktops show the running app without its icon and name.
    @Test
    void theLinuxDesktopEntryMatchesTheWindowClass() throws IOException {
        Path entry = Path.of("..", "packaging", "linux", "org.njmule.njMule.desktop");

        assertThat(Files.readAllLines(entry)).contains("StartupWMClass=" + NjMuleFxApp.class.getName());
    }
}
