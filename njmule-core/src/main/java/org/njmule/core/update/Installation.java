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
package org.njmule.core.update;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import org.njmule.core.api.InstallKind;
import org.njmule.core.config.OsFamily;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * How this copy of njMule was installed and what it runs on, which choose its package among a release's. Each package
 * says what it is in {@value #FILE_NAME} beside the executable ({@code Contents/Resources} in a macOS bundle);
 * {@value #KIND_VARIABLE} says it for a container, and wins.
 *
 * @param kind how it was installed
 * @param platform the system and processor, as "windows-x64"
 */
record Installation(InstallKind kind, String platform) {

    static final String FILE_NAME = "install.properties";
    static final String KIND_VARIABLE = "NJMULE_INSTALL_KIND";

    private static final Logger logger = LoggerFactory.getLogger(Installation.class);

    Installation {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(platform, "platform");
    }

    /** This copy of njMule. */
    static Installation current() {
        return resolve(
                Optional.ofNullable(System.getenv(KIND_VARIABLE)),
                executableDir(),
                OsFamily.current(),
                System.getProperty("os.arch", ""));
    }

    /** The installation that {@code variable} or the file in {@code executableDir} describe. */
    static Installation resolve(Optional<String> variable, Optional<Path> executableDir, OsFamily os, String arch) {
        String platform = system(os) + "-" + processor(arch);
        Optional<String> declared = variable.map(String::strip).filter(v -> !v.isEmpty());
        if (declared.isEmpty() && executableDir.isPresent()) {
            declared = declared(executableDir.get(), os);
        }
        InstallKind kind = declared.flatMap(InstallKind::of).orElse(InstallKind.UNKNOWN);
        return new Installation(kind, platform);
    }

    // Only a native executable lives where its package put it; on the JVM the command is the java launcher.
    private static Optional<Path> executableDir() {
        if (!"runtime".equals(System.getProperty("org.graalvm.nativeimage.imagecode"))) {
            return Optional.empty();
        }
        return ProcessHandle.current().info().command().map(Path::of).map(Path::getParent);
    }

    private static Optional<String> declared(Path dir, OsFamily os) {
        List<Path> places = os == OsFamily.MACOS
                ? List.of(dir.resolveSibling("Resources").resolve(FILE_NAME), dir.resolve(FILE_NAME))
                : List.of(dir.resolve(FILE_NAME));
        for (Path file : places) {
            if (Files.isRegularFile(file)) {
                try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                    Properties properties = new Properties();
                    properties.load(in);
                    return Optional.ofNullable(properties.getProperty("kind")).map(String::strip);
                } catch (IOException | IllegalArgumentException e) {
                    logger.debug("Cannot read {}: {}", file, e.toString());
                }
            }
        }
        return Optional.empty();
    }

    private static String system(OsFamily os) {
        return switch (os) {
            case WINDOWS -> "windows";
            case MACOS -> "macos";
            case LINUX -> "linux";
        };
    }

    // As the release names them: x64 and arm64.
    private static String processor(String arch) {
        String name = arch.toLowerCase(Locale.ROOT);
        return switch (name) {
            case "amd64", "x86_64", "x64" -> "x64";
            case "aarch64", "arm64" -> "arm64";
            default -> name.replaceAll("[^a-z0-9_]", "");
        };
    }
}
