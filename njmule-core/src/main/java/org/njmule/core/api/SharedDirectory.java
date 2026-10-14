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

import java.io.IOException;
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
    private static final List<String> PERSONAL = List.of("Documents", "Desktop", "Pictures", ".ssh", ".gnupg");
    // Where Windows keeps the OneDrive folders, which can hold the redirected personal folders.
    private static final List<String> ONEDRIVE = List.of("OneDrive", "OneDriveConsumer", "OneDriveCommercial");

    public SharedDirectory {
        Objects.requireNonNull(path, "path");
    }

    /**
     * Whether sharing {@code dir} deserves a second thought: a disk's root, a system directory, the user's documents,
     * desktop, pictures or keys, where personal files are, or a directory holding one of them, such as the home. Links
     * and junctions are judged by where they lead.
     */
    public static boolean isSensitive(Path dir) {
        return isSensitive(dir, sensitive());
    }

    static boolean isSensitive(Path dir, List<Path> sensitive) {
        List<Path> forms = forms(dir);
        if (forms.stream().anyMatch(d -> d.getParent() == null)) {
            return true;
        }
        for (Path s : sensitive) {
            for (Path sensitiveForm : forms(s)) {
                for (Path d : forms) {
                    if (d.startsWith(sensitiveForm) || sensitiveForm.startsWith(d)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    // The path as given and, when it exists, where it really is.
    private static List<Path> forms(Path path) {
        Path absolute = path.toAbsolutePath().normalize();
        try {
            Path real = absolute.toRealPath();
            return real.equals(absolute) ? List.of(absolute) : List.of(absolute, real);
        } catch (IOException | SecurityException e) {
            return List.of(absolute);
        }
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
            for (String variable : ONEDRIVE) {
                String value = System.getenv(variable);
                if (value != null && !value.isBlank()) {
                    PERSONAL.forEach(
                            p -> dirs.add(Path.of(value, p).toAbsolutePath().normalize()));
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
