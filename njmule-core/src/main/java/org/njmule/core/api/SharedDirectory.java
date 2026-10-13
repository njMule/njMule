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

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** A directory whose files we share, with its subdirectories or alone. */
public record SharedDirectory(Path path, boolean subdirectories) {

    private static final List<String> SYSTEM = List.of(
            "/bin",
            "/boot",
            "/dev",
            "/etc",
            "/lib",
            "/lib64",
            "/proc",
            "/sbin",
            "/sys",
            "/usr",
            "/var",
            "/System",
            "/Library",
            "/Applications",
            "/private");
    private static final List<String> WINDOWS =
            List.of("SystemRoot", "ProgramFiles", "ProgramFiles(x86)", "ProgramData", "APPDATA", "LOCALAPPDATA");
    private static final List<String> PERSONAL = List.of("Documents", "Desktop", "Pictures");

    public SharedDirectory {
        Objects.requireNonNull(path, "path");
    }

    /**
     * Whether sharing {@code dir} deserves a second thought: a disk's root, a system directory, or the user's
     * documents, desktop or pictures, where personal files are.
     */
    public static boolean isSensitive(Path dir) {
        Path d = dir.toAbsolutePath().normalize();
        if (d.getParent() == null) {
            return true;
        }
        for (Path s : sensitive()) {
            if (d.startsWith(s)) {
                return true;
            }
        }
        return false;
    }

    private static List<Path> sensitive() {
        List<Path> dirs = new ArrayList<>();
        boolean windows =
                System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
        if (windows) {
            for (String variable : WINDOWS) {
                String value = System.getenv(variable);
                if (value != null && !value.isBlank()) {
                    dirs.add(Path.of(value).toAbsolutePath().normalize());
                }
            }
        } else {
            SYSTEM.forEach(s -> dirs.add(Path.of(s)));
        }
        String home = System.getProperty("user.home");
        if (home != null) {
            PERSONAL.forEach(p -> dirs.add(Path.of(home, p).toAbsolutePath().normalize()));
        }
        return dirs;
    }
}
