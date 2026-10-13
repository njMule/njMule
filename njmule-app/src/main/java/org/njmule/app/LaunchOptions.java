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
import org.njmule.core.api.Ed2kLink;
import org.njmule.core.api.ServerAddress;
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
        Action action,
        Optional<Path> configDir,
        Optional<String> logLevel,
        List<Path> files,
        boolean headless,
        NetworkTasks network) {

    public LaunchOptions {
        files = List.copyOf(files);
    }

    /**
     * Network work for a run: servers to add, a server to connect to, a search, a hello or
     * downloads. With a search, a hello or downloads the run ends once they are done.
     */
    public record NetworkTasks(
            Optional<Path> serverMet,
            Optional<ServerAddress> server,
            Optional<String> search,
            boolean global,
            Optional<ServerAddress> hello,
            List<Ed2kLink.FileLink> downloads) {

        static final NetworkTasks NONE =
                new NetworkTasks(
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        false,
                        Optional.empty(),
                        List.of());

        public NetworkTasks {
            downloads = List.copyOf(downloads);
        }

        public boolean isOneShot() {
            return this.search.isPresent() || this.hello.isPresent() || !this.downloads.isEmpty();
        }
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

    private static final String CONFIG_DIR_OPTION = "--config-dir";
    private static final String LOG_LEVEL_OPTION = "--log-level";
    private static final String HASH_OPTION = "--hash";
    private static final String HEADLESS_OPTION = "--headless";
    private static final String ADD_SERVERS_OPTION = "--add-servers";
    private static final String CONNECT_SERVER_OPTION = "--connect-server";
    private static final String SEARCH_OPTION = "--search";
    private static final String GLOBAL_OPTION = "--global";
    private static final String HELLO_OPTION = "--hello";
    private static final String DOWNLOAD_OPTION = "--download";

    public static LaunchOptions parse(String... args) throws UsageException {
        ParseResult result;
        try {
            result = parser().parseArgs(args);
        } catch (ParameterException e) {
            throw new UsageException(e.getMessage());
        }
        Optional<Path> configDir = Optional.empty();
        if (result.hasMatchedOption(CONFIG_DIR_OPTION)) {
            configDir = Optional.of(toDirectory(result.matchedOptionValue(CONFIG_DIR_OPTION, "")));
        }
        Optional<String> logLevel = Optional.empty();
        if (result.hasMatchedOption(LOG_LEVEL_OPTION)) {
            logLevel = Optional.of(toLevel(result.matchedOptionValue(LOG_LEVEL_OPTION, "")));
        }
        List<Path> files = new ArrayList<>();
        for (String file : result.matchedOptionValue(HASH_OPTION, List.<String>of())) {
            files.add(toFile(file));
        }
        Optional<Path> serverMet = Optional.empty();
        if (result.hasMatchedOption(ADD_SERVERS_OPTION)) {
            serverMet = Optional.of(toFile(result.matchedOptionValue(ADD_SERVERS_OPTION, "")));
        }
        NetworkTasks network =
                new NetworkTasks(
                        serverMet,
                        address(result, CONNECT_SERVER_OPTION),
                        Optional.ofNullable(result.<String>matchedOptionValue(SEARCH_OPTION, null)),
                        result.hasMatchedOption(GLOBAL_OPTION),
                        address(result, HELLO_OPTION),
                        fileLinks(result.matchedOptionValue(DOWNLOAD_OPTION, List.<String>of())));
        if (network.global() && network.search().isEmpty()) {
            throw new UsageException("--global applies to --search");
        }
        return new LaunchOptions(
                action(result),
                configDir,
                logLevel,
                files,
                result.hasMatchedOption(HEADLESS_OPTION),
                network);
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
        return result.hasMatchedOption(HASH_OPTION) ? Action.HASH : Action.RUN;
    }

    private static CommandLine parser() {
        CommandSpec spec = CommandSpec.create().name("njmule");
        spec.addOption(
                valued(
                        CONFIG_DIR_OPTION,
                        "<dir>",
                        "keep all data below <dir> instead of the system locations"));
        spec.addOption(
                valued(
                        LOG_LEVEL_OPTION,
                        "<level>",
                        "ERROR, WARN, INFO, DEBUG or TRACE (overrides the preferences)"));
        spec.addOption(
                flag(HEADLESS_OPTION, "run without a window (the launcher of the desktop app)"));
        spec.addOption(
                valued(
                        ADD_SERVERS_OPTION,
                        "<server.met>",
                        "add the servers of another client's server.met"));
        spec.addOption(
                valued(
                        CONNECT_SERVER_OPTION,
                        "<host:port>",
                        "connect to this server (any known one when a"
                                + " search needs a server)"));
        spec.addOption(valued(SEARCH_OPTION, "<expression>", "search, print the results and exit"));
        spec.addOption(flag(GLOBAL_OPTION, "search the other known servers too, over UDP"));
        spec.addOption(
                valued(
                        HELLO_OPTION,
                        "<host:port>",
                        "say hello to a client, print its answer and exit"));
        spec.addOption(
                OptionSpec.builder(DOWNLOAD_OPTION)
                        .paramLabel("<ed2k link>")
                        .type(List.class)
                        .auxiliaryTypes(String.class)
                        .arity("1..*")
                        .description(
                                "download the files of these links (from their sources and,"
                                        + " with --connect-server, the server's), then exit")
                        .build());
        spec.addOption(
                OptionSpec.builder("-V", "--version")
                        .versionHelp(true)
                        .description("print the version and exit")
                        .build());
        spec.addOption(flag("--license", "print the GNU General Public License and exit"));
        spec.addOption(flag("--warranty", "print the warranty disclaimer and exit"));
        spec.addOption(flag("--notice", "print copyright, attribution and third-party notices"));
        spec.addOption(
                OptionSpec.builder(HASH_OPTION)
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

    private static Optional<ServerAddress> address(ParseResult result, String option)
            throws UsageException {
        if (!result.hasMatchedOption(option)) {
            return Optional.empty();
        }
        String value = result.matchedOptionValue(option, "");
        try {
            return Optional.of(ServerAddress.parse(value));
        } catch (IllegalArgumentException e) {
            throw new UsageException("Invalid address for " + option + ": " + value);
        }
    }

    private static List<Ed2kLink.FileLink> fileLinks(List<String> values) throws UsageException {
        List<Ed2kLink.FileLink> links = new ArrayList<>(values.size());
        for (String value : values) {
            if (!(Ed2kLink.tryParse(value).orElse(null) instanceof Ed2kLink.FileLink link)) {
                throw new UsageException("Not an ed2k file link: " + value);
            }
            links.add(link);
        }
        return links;
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
