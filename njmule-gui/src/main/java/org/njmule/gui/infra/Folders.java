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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/** Opens a folder in the system's file manager. */
public final class Folders {

    private Folders() {}

    /** Opens {@code folder}, creating it first; the file manager runs on its own. */
    public static void open(Path folder) throws IOException {
        Path dir = Files.createDirectories(folder).toAbsolutePath();
        new ProcessBuilder(command(System.getProperty("os.name", ""), dir)).start();
    }

    // The desktop's own opener; the folder is one argument, never parsed by a shell.
    static List<String> command(String os, Path dir) {
        String name = os.toLowerCase(Locale.ROOT);
        if (name.startsWith("windows")) {
            return List.of("explorer.exe", dir.toString());
        }
        if (name.startsWith("mac")) {
            return List.of("open", dir.toString());
        }
        return List.of("xdg-open", dir.toString());
    }
}
