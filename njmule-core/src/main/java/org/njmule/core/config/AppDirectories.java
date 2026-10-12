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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Where njMule keeps its files, following each operating system's conventions (Known Folders,
 * {@code ~/Library}, XDG).
 *
 * <p>An explicit root ({@code --config-dir}) or the portable mode ({@value #PORTABLE_MARKER} next
 * to the executable) puts everything below one directory. The installation directory is never
 * written.
 *
 * @param config preferences and eMule-compatible {@code .met}/{@code .dat} files
 * @param data large persistent data (AICH hash sets, Kad indexes)
 * @param temp incomplete downloads
 * @param cache regenerable data
 * @param gui front-end state (window layout)
 * @param downloads completed downloads
 */
public record AppDirectories(
        Path config,
        Path data,
        Path temp,
        Path cache,
        Path logs,
        Path gui,
        Path downloads,
        boolean portable) {

    public static final String PORTABLE_MARKER = "portable.flag";

    private static final Logger LOG = LoggerFactory.getLogger(AppDirectories.class);
    private static final String APP_DIR = "njMule";
    private static final String XDG_DIR = "njmule";

    public AppDirectories {
        for (Path p : List.of(config, data, temp, cache, logs, gui, downloads)) {
            if (!Objects.requireNonNull(p, "directory").isAbsolute()) {
                throw new IllegalArgumentException("Directory must be absolute: " + p);
            }
        }
    }

    public static AppDirectories underRoot(Path root, boolean portable) {
        Path r = root.toAbsolutePath().normalize();
        return new AppDirectories(
                r.resolve("config"),
                r.resolve("data"),
                r.resolve("temp"),
                r.resolve("cache"),
                r.resolve("logs"),
                r.resolve("gui"),
                r.resolve("incoming"),
                portable);
    }

    /** Resolves the layout for the running system. */
    public static AppDirectories resolve(
            Optional<Path> overrideRoot, Optional<Path> executableDir) {
        return resolve(
                OsFamily.current(),
                System.getenv(),
                Path.of(System.getProperty("user.home")),
                overrideRoot,
                executableDir);
    }

    /** Resolves the layout from explicit inputs, without reading the real environment. */
    public static AppDirectories resolve(
            OsFamily os,
            Map<String, String> env,
            Path home,
            Optional<Path> overrideRoot,
            Optional<Path> executableDir) {
        if (overrideRoot.isPresent()) {
            return underRoot(overrideRoot.get(), false);
        }
        if (executableDir.isPresent()
                && Files.isRegularFile(executableDir.get().resolve(PORTABLE_MARKER))) {
            return underRoot(executableDir.get().resolve("data"), true);
        }
        return switch (os) {
            case WINDOWS -> windows(env, home);
            case MACOS -> macos(home);
            case LINUX -> linux(env, home);
        };
    }

    public void createAll() throws IOException {
        for (Path p :
                List.of(
                        this.config,
                        this.data,
                        this.temp,
                        this.cache,
                        this.logs,
                        this.gui,
                        this.downloads)) {
            Files.createDirectories(p);
        }
    }

    private static AppDirectories windows(Map<String, String> env, Path home) {
        Path roaming =
                absoluteEnv(env, "APPDATA")
                        .orElse(home.resolve("AppData").resolve("Roaming"))
                        .resolve(APP_DIR);
        Path local =
                absoluteEnv(env, "LOCALAPPDATA")
                        .orElse(home.resolve("AppData").resolve("Local"))
                        .resolve(APP_DIR);
        Path profile = absoluteEnv(env, "USERPROFILE").orElse(home);
        return new AppDirectories(
                roaming.resolve("config"),
                local.resolve("data"),
                local.resolve("temp"),
                local.resolve("cache"),
                local.resolve("logs"),
                roaming.resolve("gui"),
                // Default location; a relocated Downloads Known Folder is honoured once the desktop
                // integration reads it through SHGetKnownFolderPath.
                profile.resolve("Downloads").resolve(APP_DIR),
                false);
    }

    private static AppDirectories macos(Path home) {
        Path library = home.resolve("Library");
        Path support = library.resolve("Application Support").resolve(APP_DIR);
        return new AppDirectories(
                support.resolve("config"),
                support.resolve("data"),
                support.resolve("temp"),
                library.resolve("Caches").resolve("org.njmule.njMule"),
                library.resolve("Logs").resolve(APP_DIR),
                support.resolve("gui"),
                home.resolve("Downloads").resolve(APP_DIR),
                false);
    }

    private static AppDirectories linux(Map<String, String> env, Path home) {
        Path configHome = absoluteEnv(env, "XDG_CONFIG_HOME").orElse(home.resolve(".config"));
        Path dataHome =
                absoluteEnv(env, "XDG_DATA_HOME").orElse(home.resolve(".local").resolve("share"));
        Path cacheHome = absoluteEnv(env, "XDG_CACHE_HOME").orElse(home.resolve(".cache"));
        Path stateHome =
                absoluteEnv(env, "XDG_STATE_HOME").orElse(home.resolve(".local").resolve("state"));
        Path config = configHome.resolve(XDG_DIR);
        Path data = dataHome.resolve(XDG_DIR);
        Path downloads =
                absoluteEnv(env, "XDG_DOWNLOAD_DIR")
                        .or(() -> userDirsDownload(configHome.resolve("user-dirs.dirs"), home))
                        .orElse(home.resolve("Downloads"));
        return new AppDirectories(
                config,
                data,
                data.resolve("temp"),
                cacheHome.resolve(XDG_DIR),
                stateHome.resolve(XDG_DIR).resolve("logs"),
                config.resolve("gui"),
                downloads.resolve(APP_DIR),
                false);
    }

    /** Reads {@code XDG_DOWNLOAD_DIR} from xdg-user-dirs' {@code user-dirs.dirs}. */
    static Optional<Path> userDirsDownload(Path file, Path home) {
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String trimmed = line.strip();
                if (!trimmed.startsWith("XDG_DOWNLOAD_DIR=")) {
                    continue;
                }
                String value = trimmed.substring("XDG_DOWNLOAD_DIR=".length()).strip();
                if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                    value = value.substring(1, value.length() - 1);
                }
                if (value.equals("$HOME")) {
                    return Optional.of(home);
                }
                if (value.startsWith("$HOME/")) {
                    return Optional.of(home.resolve(value.substring("$HOME/".length())));
                }
                Path path = Path.of(value);
                return path.isAbsolute() ? Optional.of(path) : Optional.empty();
            }
        } catch (IOException | RuntimeException e) {
            LOG.debug("Cannot read {}", file, e);
        }
        return Optional.empty();
    }

    // The XDG specification requires ignoring relative paths in these variables.
    private static Optional<Path> absoluteEnv(Map<String, String> env, String name) {
        String value = env.get(name);
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            Path path = Path.of(value.strip());
            return path.isAbsolute() ? Optional.of(path) : Optional.empty();
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }
}
