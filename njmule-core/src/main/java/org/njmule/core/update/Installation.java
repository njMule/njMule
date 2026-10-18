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
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import org.njmule.core.api.InstallKind;
import org.njmule.core.api.UpdateStatus.Installer;
import org.njmule.core.config.OsFamily;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * How this copy of njMule was installed and what it runs on, which choose its package among a release's and who puts a
 * new version in place. Each package says what it is in {@value #FILE_NAME} beside the executable
 * ({@code Contents/Resources} in a macOS bundle); {@value #KIND_VARIABLE} says it for a container, and wins.
 *
 * @param kind how it was installed
 * @param platform the system and processor, as "windows-x64"
 * @param executable the native executable running, as its package placed it; empty on the JVM
 * @param installer who puts a new version in place
 */
record Installation(InstallKind kind, String platform, Optional<Path> executable, Installer installer) {

    static final String FILE_NAME = "install.properties";
    static final String KIND_VARIABLE = "NJMULE_INSTALL_KIND";

    private static final Logger logger = LoggerFactory.getLogger(Installation.class);
    private static final SecureRandom PROBE_NAMES = new SecureRandom();

    Installation {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(platform, "platform");
        Objects.requireNonNull(executable, "executable");
        Objects.requireNonNull(installer, "installer");
    }

    /** An installation njMule does not update itself. */
    Installation(InstallKind kind, String platform) {
        this(kind, platform, Optional.empty(), Installer.NONE);
    }

    /** This copy of njMule. */
    static Installation current() {
        return resolve(
                Optional.ofNullable(System.getenv(KIND_VARIABLE)),
                runningExecutable(),
                OsFamily.current(),
                System.getProperty("os.arch", ""));
    }

    /** The installation that {@code variable} or the file beside {@code executable} describe. */
    static Installation resolve(Optional<String> variable, Optional<Path> executable, OsFamily os, String arch) {
        String platform = system(os) + "-" + processor(arch);
        Optional<String> declared = variable.map(String::strip).filter(v -> !v.isEmpty());
        boolean byPackage = declared.isEmpty();
        Optional<Path> dir = executable.map(Path::getParent);
        if (byPackage && dir.isPresent()) {
            declared = declared(dir.get(), os);
        }
        InstallKind kind = declared.flatMap(InstallKind::of).orElse(InstallKind.UNKNOWN);
        // A container says what it is: its files are the image's, never ours to replace.
        Optional<Path> placed = byPackage ? executable : Optional.empty();
        Installation found = new Installation(kind, platform, placed, Installer.NONE);
        return new Installation(kind, platform, placed, installer(found));
    }

    /**
     * What a new version replaces: the folder of the executable, or for the macOS app its bundle; empty when njMule
     * does not replace itself.
     */
    Optional<Path> root() {
        if (this.executable.isEmpty()) {
            return Optional.empty();
        }
        Path dir = this.executable.get().getParent();
        if (this.kind == InstallKind.DMG) {
            // njMule.app/Contents/MacOS/njmule-gui
            Path contents = dir == null ? null : dir.getParent();
            Path bundle = contents == null ? null : contents.getParent();
            return Optional.ofNullable(bundle)
                    .filter(b -> String.valueOf(b.getFileName()).endsWith(".app"));
        }
        return Optional.ofNullable(dir);
    }

    // njMule replaces the files of the packages it unpacks itself, where it may write; the MSI's through Windows
    // Installer, which asks for administrator rights when the program is installed for all users.
    private static Installer installer(Installation found) {
        Optional<Path> root = found.root();
        if (root.isEmpty()) {
            return Installer.NONE;
        }
        return switch (found.kind()) {
            case MSI -> writable(root.get()) ? Installer.SELF : Installer.ELEVATED;
            case WINDOWS_ZIP, TAR, STATIC_TAR -> writable(root.get()) ? Installer.SELF : Installer.NONE;
            case DMG -> writable(UpdateFiles.parent(root.get())) ? Installer.SELF : Installer.NONE;
            default -> Installer.NONE;
        };
    }

    // Asked by doing: the permissions alone do not tell a folder only an administrator may write to on Windows.
    static boolean writable(Path dir) {
        byte[] random = new byte[6];
        PROBE_NAMES.nextBytes(random);
        Path probe = dir.resolve(".njmule-probe-" + HexFormat.of().formatHex(random));
        try {
            Files.createFile(probe);
            Files.delete(probe);
            return true;
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            return false;
        }
    }

    // Only a native executable lives where its package put it; on the JVM the command is the java launcher.
    private static Optional<Path> runningExecutable() {
        if (!"runtime".equals(System.getProperty("org.graalvm.nativeimage.imagecode"))) {
            return Optional.empty();
        }
        return ProcessHandle.current().info().command().map(Path::of);
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
