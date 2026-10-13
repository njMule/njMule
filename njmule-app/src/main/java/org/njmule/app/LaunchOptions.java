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
import org.njmule.core.api.ImportOptions;
import org.njmule.core.api.ServerAddress;
import org.njmule.core.config.Preferences;
import picocli.CommandLine;
import picocli.CommandLine.Help;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Model.OptionSpec;
import picocli.CommandLine.Model.PositionalParamSpec;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.ParseResult;

/**
 * Parsed command-line options shared by the headless launcher and the GUI. Parsing uses picocli's programmatic model,
 * which needs no reflection in the native image.
 */
public record LaunchOptions(
        Action action,
        Optional<Path> configDir,
        Optional<String> logLevel,
        List<Path> files,
        boolean headless,
        NetworkTasks network,
        Optional<WebUiSetup> webUi,
        List<Ed2kLink> links) {

    public LaunchOptions {
        files = List.copyOf(files);
        links = List.copyOf(links);
    }

    /**
     * Network work for a run: servers to add, a server to connect to, a search, a hello or downloads. With a search, a
     * hello or downloads the run ends once they are done.
     */
    public record NetworkTasks(
            Optional<Path> serverMet,
            Optional<ServerAddress> server,
            Optional<String> search,
            boolean global,
            Optional<ServerAddress> hello,
            List<Ed2kLink.FileLink> downloads,
            List<Path> shares,
            Optional<ServerAddress> kadBootstrap,
            boolean kad,
            Optional<EmuleImport> emuleImport) {

        static final NetworkTasks NONE = new NetworkTasks(
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                false,
                Optional.empty(),
                List.of(),
                List.of(),
                Optional.empty(),
                false,
                Optional.empty());

        public NetworkTasks {
            downloads = List.copyOf(downloads);
            shares = List.copyOf(shares);
        }

        public boolean isOneShot() {
            return this.search.isPresent()
                    || this.hello.isPresent()
                    || !this.downloads.isEmpty()
                    || this.emuleImport.isPresent();
        }
    }

    /**
     * A new password for the web interface, which it also turns on.
     *
     * @param port the port to listen on, the current one when empty
     * @param lan whether other computers may connect, instead of this one only
     */
    public record WebUiSetup(Optional<Integer> port, boolean lan) {}

    /** An import of the eMule profile in {@code dir}. */
    public record EmuleImport(Path dir, ImportOptions options) {}

    /** What the program should do. */
    public enum Action {
        RUN,
        HELP,
        VERSION,
        LICENSE,
        WARRANTY,
        NOTICE,
        HASH,
        WEBUI_PASSWORD,
        EXIT
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
    private static final String SHARE_OPTION = "--share";
    private static final String KAD_BOOTSTRAP_OPTION = "--kad-bootstrap";
    private static final String KAD_OPTION = "--kad";
    private static final String IMPORT_OPTION = "--import-emule";
    private static final String DRY_RUN_OPTION = "--dry-run";
    private static final String IDENTITY_OPTION = "--adopt-emule-identity";
    private static final String IMPORT_DOWNLOADS_OPTION = "--import-downloads";
    private static final String WEBUI_PASSWORD_OPTION = "--webui-password";
    private static final String EXIT_OPTION = "--exit";
    private static final String WEBUI_PORT_OPTION = "--webui-port";
    private static final String WEBUI_LAN_OPTION = "--webui-lan";

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
        NetworkTasks network = new NetworkTasks(
                serverMet,
                address(result, CONNECT_SERVER_OPTION),
                Optional.ofNullable(result.<String>matchedOptionValue(SEARCH_OPTION, null)),
                result.hasMatchedOption(GLOBAL_OPTION),
                address(result, HELLO_OPTION),
                fileLinks(result.matchedOptionValue(DOWNLOAD_OPTION, List.<String>of())),
                directories(result.matchedOptionValue(SHARE_OPTION, List.<String>of())),
                address(result, KAD_BOOTSTRAP_OPTION),
                result.hasMatchedOption(KAD_OPTION),
                emuleImport(result));
        if (network.global() && network.search().isEmpty()) {
            throw new UsageException("--global applies to --search");
        }
        if (network.kad() && (network.search().isEmpty() || network.global())) {
            throw new UsageException("--kad applies to --search, without --global");
        }
        if (network.emuleImport().isEmpty()
                && (result.hasMatchedOption(DRY_RUN_OPTION)
                        || result.hasMatchedOption(IDENTITY_OPTION)
                        || result.hasMatchedOption(IMPORT_DOWNLOADS_OPTION))) {
            throw new UsageException(DRY_RUN_OPTION
                    + ", "
                    + IDENTITY_OPTION
                    + " and "
                    + IMPORT_DOWNLOADS_OPTION
                    + " apply to "
                    + IMPORT_OPTION);
        }
        return new LaunchOptions(
                action(result),
                configDir,
                logLevel,
                files,
                result.hasMatchedOption(HEADLESS_OPTION),
                network,
                webUi(result),
                links(result.matchedPositionalValue(0, List.<String>of())));
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
        if (result.hasMatchedOption(WEBUI_PASSWORD_OPTION)) {
            return Action.WEBUI_PASSWORD;
        }
        if (result.hasMatchedOption(EXIT_OPTION)) {
            return Action.EXIT;
        }
        return result.hasMatchedOption(HASH_OPTION) ? Action.HASH : Action.RUN;
    }

    private static CommandLine parser() {
        CommandSpec spec = CommandSpec.create().name("njmule");
        spec.addOption(valued(CONFIG_DIR_OPTION, "<dir>", "keep all data below <dir> instead of the system locations"));
        spec.addOption(
                valued(LOG_LEVEL_OPTION, "<level>", "ERROR, WARN, INFO, DEBUG or TRACE (overrides the preferences)"));
        spec.addOption(flag(HEADLESS_OPTION, "run without a window (the launcher of the desktop app)"));
        spec.addOption(valued(ADD_SERVERS_OPTION, "<server.met>", "add the servers of another client's server.met"));
        spec.addOption(valued(
                CONNECT_SERVER_OPTION,
                "<host:port>",
                "connect to this server (any known one when a" + " search needs a server)"));
        spec.addOption(valued(SEARCH_OPTION, "<expression>", "search, print the results and exit"));
        spec.addOption(flag(GLOBAL_OPTION, "search the other known servers too, over UDP"));
        spec.addOption(flag(KAD_OPTION, "search on Kad instead of the servers"));
        spec.addOption(valued(
                KAD_BOOTSTRAP_OPTION,
                "<host:port>",
                "join Kad through this node (its UDP port), e.g. a running eMule"));
        spec.addOption(valued(HELLO_OPTION, "<host:port>", "say hello to a client, print its answer and exit"));
        spec.addOption(OptionSpec.builder(DOWNLOAD_OPTION)
                .paramLabel("<ed2k link>")
                .type(List.class)
                .auxiliaryTypes(String.class)
                .arity("1..*")
                .description("download the files of these links (from their sources and,"
                        + " with --connect-server, the server's), then exit")
                .build());
        spec.addOption(OptionSpec.builder(SHARE_OPTION)
                .paramLabel("<dir>")
                .type(List.class)
                .auxiliaryTypes(String.class)
                .arity("1..*")
                .description("share the files of these directories too (remembered), besides" + " the incoming one")
                .build());
        spec.addOption(valued(
                IMPORT_OPTION,
                "<dir>",
                "take servers, Kad contacts, known files, shared directories and"
                        + " preferences from eMule's config directory (only read), then"
                        + " exit"));
        spec.addOption(flag(DRY_RUN_OPTION, "with " + IMPORT_OPTION + ": only report"));
        spec.addOption(flag(
                IDENTITY_OPTION,
                "with "
                        + IMPORT_OPTION
                        + ": also take eMule's user hash, key and credits, from the"
                        + " next start (never run both with one identity)"));
        spec.addOption(flag(
                IMPORT_DOWNLOADS_OPTION,
                "with " + IMPORT_OPTION + ": also copy eMule's downloads (paused ones while it runs)"));
        spec.addOption(flag(
                WEBUI_PASSWORD_OPTION,
                "ask for a password for the web interface, turn the interface on (from" + " the next start) and exit"));
        spec.addOption(valued(
                WEBUI_PORT_OPTION, "<port>", "with " + WEBUI_PASSWORD_OPTION + ": the port of the web interface"));
        spec.addOption(flag(EXIT_OPTION, "ask the njMule running on this configuration to save and exit"));
        spec.addOption(flag(
                WEBUI_LAN_OPTION,
                "with " + WEBUI_PASSWORD_OPTION + ": let other computers connect (otherwise only this one)"));
        spec.addOption(OptionSpec.builder("-V", "--version")
                .versionHelp(true)
                .description("print the version and exit")
                .build());
        spec.addOption(flag("--license", "print the GNU General Public License and exit"));
        spec.addOption(flag("--warranty", "print the warranty disclaimer and exit"));
        spec.addOption(flag("--notice", "print copyright, attribution and third-party notices"));
        spec.addOption(OptionSpec.builder(HASH_OPTION)
                .paramLabel("<file>")
                .type(List.class)
                .auxiliaryTypes(String.class)
                .arity("1..*")
                .description("print the ed2k link (with AICH hash) of each file and exit")
                .build());
        spec.addOption(OptionSpec.builder("-h", "--help")
                .usageHelp(true)
                .description("print this help and exit")
                .build());
        spec.addPositional(PositionalParamSpec.builder()
                .paramLabel("<ed2k link>")
                .type(List.class)
                .auxiliaryTypes(String.class)
                .arity("0..*")
                .description("add these downloads, handing them to the njMule already running with the same"
                        + " configuration, if any (as a browser does with a clicked link)")
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

    private static Optional<ServerAddress> address(ParseResult result, String option) throws UsageException {
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

    private static Optional<WebUiSetup> webUi(ParseResult result) throws UsageException {
        boolean port = result.hasMatchedOption(WEBUI_PORT_OPTION);
        boolean lan = result.hasMatchedOption(WEBUI_LAN_OPTION);
        if (!result.hasMatchedOption(WEBUI_PASSWORD_OPTION)) {
            if (port || lan) {
                throw new UsageException(
                        WEBUI_PORT_OPTION + " and " + WEBUI_LAN_OPTION + " apply to " + WEBUI_PASSWORD_OPTION);
            }
            return Optional.empty();
        }
        Optional<Integer> number = Optional.empty();
        if (port) {
            String value = result.matchedOptionValue(WEBUI_PORT_OPTION, "");
            try {
                number = Optional.of(Integer.parseInt(value.strip()));
            } catch (NumberFormatException e) {
                number = Optional.of(-1);
            }
            if (number.get() < 1 || number.get() > 0xFFFF) {
                throw new UsageException("Invalid port for " + WEBUI_PORT_OPTION + ": " + value);
            }
        }
        return Optional.of(new WebUiSetup(number, lan));
    }

    private static Optional<EmuleImport> emuleImport(ParseResult result) throws UsageException {
        if (!result.hasMatchedOption(IMPORT_OPTION)) {
            return Optional.empty();
        }
        return Optional.of(new EmuleImport(
                toDirectory(result.matchedOptionValue(IMPORT_OPTION, "")),
                new ImportOptions(
                        result.hasMatchedOption(DRY_RUN_OPTION),
                        result.hasMatchedOption(IDENTITY_OPTION),
                        result.hasMatchedOption(IMPORT_DOWNLOADS_OPTION))));
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

    private static List<Ed2kLink> links(List<String> values) throws UsageException {
        List<Ed2kLink> links = new ArrayList<>(values.size());
        for (String value : values) {
            links.add(Ed2kLink.tryParse(value).orElseThrow(() -> new UsageException("Not an ed2k link: " + value)));
        }
        return links;
    }

    private static List<Path> directories(List<String> values) throws UsageException {
        List<Path> dirs = new ArrayList<>(values.size());
        for (String value : values) {
            dirs.add(toDirectory(value));
        }
        return dirs;
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
