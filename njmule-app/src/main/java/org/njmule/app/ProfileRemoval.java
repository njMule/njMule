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
package org.njmule.app;

import com.google.common.io.MoreFiles;
import com.google.common.io.RecursiveDeleteOption;
import java.io.IOException;
import java.io.PrintStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.function.Function;
import org.njmule.core.config.AppDirectories;

/**
 * {@code --remove-profile}: deletes a profile of njMule, as when it is uninstalled for good: its settings, lists,
 * identity, unfinished downloads, caches and logs. The completed downloads stay.
 */
final class ProfileRemoval {

    // The preferences of the engine and their key of the folder of the completed downloads.
    private static final String PREFERENCES = "njmule.properties";
    private static final String INCOMING = "dirs.incoming";

    private ProfileRemoval() {}

    /** Removes the profile of {@code cli}, after asking through {@code ask} (null: no answer) unless confirmed. */
    static int run(LaunchOptions cli, PrintStream out, PrintStream err, Function<String, String> ask) {
        AppDirectories dirs = AppDirectories.resolve(cli.configDir());
        if (!LinkHandoff.awaitGone(dirs.config(), Duration.ZERO)) {
            err.println("njMule runs on this profile: stop it first (njmule --exit).");
            return Main.EXIT_FAILURE;
        }
        Path incoming = incoming(dirs);
        List<Path> folders = folders(dirs, incoming);
        if (folders.isEmpty()) {
            out.println("There is no profile of njMule to delete.");
            return Main.EXIT_OK;
        }
        out.println("These folders of njMule will be deleted:");
        folders.forEach(f -> out.println("  " + f));
        out.println("The completed downloads in " + incoming + " stay.");
        if (!cli.confirmed()) {
            String answer = ask.apply("Delete them? [y/N] ");
            String said = answer == null ? "" : answer.strip().toLowerCase(Locale.ROOT);
            if (!said.equals("y") && !said.equals("yes")) {
                out.println("Nothing was deleted.");
                return Main.EXIT_OK;
            }
        }
        int code = Main.EXIT_OK;
        for (Path folder : folders) {
            try {
                MoreFiles.deleteRecursively(folder, RecursiveDeleteOption.ALLOW_INSECURE);
                removeEmptyParent(folder);
            } catch (IOException e) {
                err.println("Cannot delete " + folder + ": " + e.getMessage());
                code = Main.EXIT_FAILURE;
            }
        }
        if (code == Main.EXIT_OK) {
            out.println("njMule's profile is deleted.");
        }
        return code;
    }

    // The existing folders of the profile, a folder inside another one counted once; never one holding downloads.
    static List<Path> folders(AppDirectories dirs, Path incoming) {
        Path downloads = incoming.toAbsolutePath().normalize();
        List<Path> all = new ArrayList<>();
        for (Path p : List.of(dirs.config(), dirs.data(), dirs.temp(), dirs.cache(), dirs.logs(), dirs.gui())) {
            Path folder = p.toAbsolutePath().normalize();
            boolean inside = all.stream().anyMatch(folder::startsWith);
            if (Files.isDirectory(folder) && !inside && !downloads.startsWith(folder)) {
                all.removeIf(f -> f.startsWith(folder));
                all.add(folder);
            }
        }
        return List.copyOf(all);
    }

    /**
     * Where the completed downloads go: the folder the preferences name, else the default one. Read from the file
     * itself, as the engine does not run.
     */
    static Path incoming(AppDirectories dirs) {
        Properties preferences = new Properties();
        try (Reader in = Files.newBufferedReader(dirs.config().resolve(PREFERENCES), StandardCharsets.UTF_8)) {
            preferences.load(in);
            String chosen = preferences.getProperty(INCOMING, "").strip();
            return chosen.isEmpty() ? dirs.downloads() : Path.of(chosen);
        } catch (IOException | IllegalArgumentException e) {
            return dirs.downloads();
        }
    }

    // The folder named after njMule that held them goes too once empty, as %APPDATA%\njMule.
    private static void removeEmptyParent(Path folder) throws IOException {
        Path parent = folder.getParent();
        if (parent != null && String.valueOf(parent.getFileName()).equalsIgnoreCase("njmule")) {
            try {
                Files.deleteIfExists(parent);
            } catch (DirectoryNotEmptyException e) {
                // Other folders of the profile are still in it.
            }
        }
    }
}
