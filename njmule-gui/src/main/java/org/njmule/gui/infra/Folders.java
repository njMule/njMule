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

/** Opens a folder in the system's file manager, or a file with the program the system gives its kind. */
public final class Folders {

    private Folders() {}

    /** Opens {@code folder}, creating it first; the file manager runs on its own. */
    public static void open(Path folder) throws IOException {
        Path dir = Files.createDirectories(folder).toAbsolutePath();
        process(command(System.getProperty("os.name", ""), dir)).start();
    }

    /** Opens {@code file} with the program the system opens its kind with; the program runs on its own. */
    public static void openFile(Path file) throws IOException {
        process(command(System.getProperty("os.name", ""), file.toAbsolutePath()))
                .start();
    }

    /** Shows the folder of {@code file} with the file selected, where the file manager can. */
    public static void reveal(Path file) throws IOException {
        process(revealCommand(System.getProperty("os.name", ""), file.toAbsolutePath()))
                .start();
    }

    // Nobody reads what the file manager writes: a full pipe would stop it.
    static ProcessBuilder process(List<String> command) {
        return new ProcessBuilder(command)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD);
    }

    static List<String> revealCommand(String os, Path file) {
        String name = os.toLowerCase(Locale.ROOT);
        if (name.startsWith("windows")) {
            return List.of("explorer.exe", "/select,", file.toString());
        }
        if (name.startsWith("mac")) {
            return List.of("open", "-R", file.toString());
        }
        return List.of("xdg-open", String.valueOf(file.getParent()));
    }

    // The desktop's own opener, for a folder or a file; the path is one argument, never parsed by a shell.
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
