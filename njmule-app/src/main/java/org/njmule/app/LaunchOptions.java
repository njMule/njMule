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
import picocli.CommandLine;
import picocli.CommandLine.Help;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Model.OptionSpec;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.ParseResult;

/**
 * Parsed command-line options shared by the headless launcher and the GUI. Parsing uses picocli's
 * programmatic model, which needs no reflection in the native image.
 */
public record LaunchOptions(
        Action action, Optional<Path> configDir, Optional<String> logLevel, List<Path> files) {

    public LaunchOptions {
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

    /** Thrown for invalid arguments; the message is meant for the user. */
    public static final class UsageException extends Exception {
        private static final long serialVersionUID = 1L;

        public UsageException(String message) {
            super(message);
        }
    }

    private static final String CONFIG_DIR = "--config-dir";
    private static final String LOG_LEVEL = "--log-level";
    private static final String HASH = "--hash";

    public static LaunchOptions parse(String... args) throws UsageException {
        ParseResult result;
        try {
            result = parser().parseArgs(args);
        } catch (ParameterException e) {
            throw new UsageException(e.getMessage());
        }
        Optional<Path> configDir = Optional.empty();
        if (result.hasMatchedOption(CONFIG_DIR)) {
            configDir = Optional.of(toDirectory(result.matchedOptionValue(CONFIG_DIR, "")));
        }
        Optional<String> logLevel = Optional.empty();
        if (result.hasMatchedOption(LOG_LEVEL)) {
            logLevel = Optional.of(toLevel(result.matchedOptionValue(LOG_LEVEL, "")));
        }
        List<Path> files = new ArrayList<>();
        for (String file : result.matchedOptionValue(HASH, List.<String>of())) {
            files.add(toFile(file));
        }
        return new LaunchOptions(action(result), configDir, logLevel, files);
    }

    /** The help text, without colours. */
    public static String usage() {
        return parser().getUsageMessage(Help.Ansi.OFF);
    }

    private static Action action(ParseResult result) {
        if (result.isUsageHelpRequested()) {
            return Action.HELP;
        }
        if (result.isVersionHelpRequested()) {
            return Action.VERSION;
        }
        if (result.hasMatchedOption("--license")) {
            return Action.LICENSE;
        }
        if (result.hasMatchedOption("--warranty")) {
            return Action.WARRANTY;
        }
        if (result.hasMatchedOption("--notice")) {
            return Action.NOTICE;
        }
        return result.hasMatchedOption(HASH) ? Action.HASH : Action.RUN;
    }

    private static CommandLine parser() {
        CommandSpec spec = CommandSpec.create().name("njmule");
        spec.addOption(
                valued(
                        CONFIG_DIR,
                        "<dir>",
                        "keep all data below <dir> instead of the system locations"));
        spec.addOption(
                valued(
                        LOG_LEVEL,
                        "<level>",
                        "ERROR, WARN, INFO, DEBUG or TRACE (overrides the preferences)"));
        spec.addOption(
                OptionSpec.builder("-V", "--version")
                        .versionHelp(true)
                        .description("print the version and exit")
                        .build());
        spec.addOption(flag("--license", "print the GNU General Public License and exit"));
        spec.addOption(flag("--warranty", "print the warranty disclaimer and exit"));
        spec.addOption(flag("--notice", "print copyright, attribution and third-party notices"));
        spec.addOption(
                OptionSpec.builder(HASH)
                        .paramLabel("<file>")
                        .type(List.class)
                        .auxiliaryTypes(String.class)
                        .arity("1..*")
                        .description("print the ed2k link (with AICH hash) of each file and exit")
                        .build());
        spec.addOption(
                OptionSpec.builder("-h", "--help")
                        .usageHelp(true)
                        .description("print this help and exit")
                        .build());
        return new CommandLine(spec);
    }

    private static OptionSpec valued(String name, String label, String description) {
        return OptionSpec.builder(name)
                .paramLabel(label)
                .type(String.class)
                .description(description)
                .build();
    }

    private static OptionSpec flag(String name, String description) {
        return OptionSpec.builder(name)
                .arity("0")
                .type(boolean.class)
                .description(description)
                .build();
    }

    private static Path toDirectory(String value) throws UsageException {
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
