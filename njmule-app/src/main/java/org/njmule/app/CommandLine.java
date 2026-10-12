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

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.njmule.core.config.Preferences;

/** Parsed command-line options shared by the headless launcher and the GUI. */
public record CommandLine(
        Action action, Optional<Path> configDir, Optional<String> logLevel, List<Path> files) {

    public CommandLine {
        files = List.copyOf(files);
    }

    /** What the program should do. */
    public enum Action {
        RUN,
        HELP,
        VERSION,
        LICENSE,
        WARRANTY,
        NOTICE,
        HASH
    }

    public static final String USAGE =
            """
            Usage: njmule [options]

              --config-dir <dir>   keep all data below <dir> instead of the system locations
              --log-level <level>  ERROR, WARN, INFO, DEBUG or TRACE (overrides the preferences)
              --version, -V        print the version and exit
              --license            print the GNU General Public License and exit
              --warranty           print the warranty disclaimer and exit
              --notice             print copyright, attribution and third-party notices
              --hash <file>...     print the ed2k link (with AICH hash) of each file and exit
              --help, -h           print this help and exit
            """;

    /** Thrown for invalid arguments; the message is meant for the user. */
    public static final class UsageException extends Exception {
        private static final long serialVersionUID = 1L;

        public UsageException(String message) {
            super(message);
        }
    }

    public static CommandLine parse(String... args) throws UsageException {
        Action action = Action.RUN;
        Optional<Path> configDir = Optional.empty();
        Optional<String> logLevel = Optional.empty();
        List<Path> files = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            switch (arg) {
                case "--help", "-h" -> action = Action.HELP;
                case "--version", "-V" -> action = Action.VERSION;
                case "--license" -> action = Action.LICENSE;
                case "--warranty" -> action = Action.WARRANTY;
                case "--notice" -> action = Action.NOTICE;
                case "--config-dir" -> configDir = Optional.of(toPath(value(args, ++i, arg)));
                case "--log-level" -> logLevel = Optional.of(toLevel(value(args, ++i, arg)));
                case "--hash" -> {
                    action = Action.HASH;
                    files.add(toFile(value(args, ++i, arg)));
                    while (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                        files.add(toFile(args[++i]));
                    }
                }
                default -> throw new UsageException("Unknown option: " + arg);
            }
        }
        return new CommandLine(action, configDir, logLevel, files);
    }

    private static String value(String[] args, int index, String option) throws UsageException {
        if (index >= args.length || args[index].startsWith("--")) {
            throw new UsageException("Missing value for " + option);
        }
        return args[index];
    }

    private static Path toPath(String value) throws UsageException {
        try {
            return Path.of(value).toAbsolutePath().normalize();
        } catch (InvalidPathException e) {
            throw new UsageException("Invalid directory: " + value);
        }
    }

    private static Path toFile(String value) throws UsageException {
        try {
            return Path.of(value);
        } catch (InvalidPathException e) {
            throw new UsageException("Invalid file: " + value);
        }
    }

    private static String toLevel(String value) throws UsageException {
        String level = value.toUpperCase(Locale.ROOT);
        if (!Preferences.LOG_LEVELS.contains(level)) {
            throw new UsageException("Invalid log level: " + value);
        }
        return level;
    }
}
