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

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.njmule.core.api.Ed2kLink;
import org.njmule.core.api.ImportOptions;
import org.njmule.core.api.ServerAddress;
import org.njmule.core.config.Preferences;
import org.njmule.http.WebUiSettings;
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
        List<Ed2kLink> links,
        Optional<Remote> remote,
        Optional<Path> applyUpdate,
        List<Long> waitFor,
        Optional<ServiceTask> service,
        boolean confirmed) {

    public LaunchOptions {
        files = List.copyOf(files);
        links = List.copyOf(links);
        waitFor = List.copyOf(waitFor);
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
     * @param api whether the remote-control API answers too
     * @param passwordFile where the password is read, instead of asking for it; set at each start when given without
     *     {@code --webui-password}
     * @param hosts the host names the interface answers to besides localhost, as {@link WebUiSettings#hostName} keeps
     *     them
     * @param proxies the reverse proxies trusted to name their client, as {@link WebUiSettings#proxy} keeps them
     * @param keepPassword the password stays as it is: no password option, which only {@code --service=install} allows
     * @param guestPasswordFile where the password of a guest, who may only look, is read; the guest stays as saved when
     *     empty
     * @param noGuest whether no guest may log in any more
     */
    public record WebUiSetup(
            Optional<Integer> port,
            boolean lan,
            boolean api,
            Optional<Path> passwordFile,
            List<String> hosts,
            List<String> proxies,
            boolean keepPassword,
            Optional<Path> guestPasswordFile,
            boolean noGuest) {
        public WebUiSetup {
            hosts = List.copyOf(hosts);
            proxies = List.copyOf(proxies);
        }

        /** A setup that leaves the guest as it is. */
        public WebUiSetup(
                Optional<Integer> port,
                boolean lan,
                boolean api,
                Optional<Path> passwordFile,
                List<String> hosts,
                List<String> proxies,
                boolean keepPassword) {
            this(port, lan, api, passwordFile, hosts, proxies, keepPassword, Optional.empty(), false);
        }

        /** A setup with a new password. */
        public WebUiSetup(
                Optional<Integer> port,
                boolean lan,
                boolean api,
                Optional<Path> passwordFile,
                List<String> hosts,
                List<String> proxies) {
            this(port, lan, api, passwordFile, hosts, proxies, false);
        }

        /** A setup that trusts no proxy. */
        public WebUiSetup(
                Optional<Integer> port, boolean lan, boolean api, Optional<Path> passwordFile, List<String> hosts) {
            this(port, lan, api, passwordFile, hosts, List.of());
        }
    }

    /**
     * A command for a running njMule, through its remote-control API.
     *
     * @param url the API of another computer, as {@code http://host:4711/api/v1}; this computer's when empty
     */
    public record Remote(List<String> command, Optional<URI> url) {
        public Remote {
            command = List.copyOf(command);
        }
    }

    /** An import of the eMule profile in {@code dir}. */
    public record EmuleImport(Path dir, ImportOptions options) {}

    /** What {@code --service} does with the Windows service of njMule. */
    public enum ServiceAction {
        INSTALL,
        UNINSTALL,
        START,
        STOP,
        STATUS
    }

    /** A {@code --service} command; {@code output} receives its messages too, for the command that asked for it. */
    public record ServiceTask(ServiceAction action, Optional<Path> output) {}

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
        EXIT,
        REMOTE,
        API_TOKEN,
        NEW_API_TOKEN,
        RESTORE_LINKS,
        CHECK_UPDATE,
        UPDATE,
        APPLY_UPDATE,
        SERVICE,
        REMOVE_PROFILE
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
    private static final String WEBUI_PASSWORD_FILE_OPTION = "--webui-password-file";
    private static final String EXIT_OPTION = "--exit";
    private static final String RESTORE_LINKS_OPTION = "--restore-links";
    private static final String WEBUI_PORT_OPTION = "--webui-port";
    private static final String WEBUI_LAN_OPTION = "--webui-lan";
    private static final String WEBUI_API_OPTION = "--webui-api";
    private static final String WEBUI_HOST_OPTION = "--webui-host";
    private static final String WEBUI_PROXY_OPTION = "--webui-proxy";
    private static final String WEBUI_GUEST_PASSWORD_FILE_OPTION = "--webui-guest-password-file";
    private static final String WEBUI_NO_GUEST_OPTION = "--webui-no-guest";
    private static final String REMOTE_OPTION = "--remote";
    private static final String REMOTE_URL_OPTION = "--remote-url";
    private static final String API_TOKEN_OPTION = "--api-token";
    private static final String NEW_API_TOKEN_OPTION = "--new-api-token";
    private static final String CHECK_UPDATE_OPTION = "--check-update";
    private static final String UPDATE_OPTION = "--update";
    private static final String APPLY_UPDATE_OPTION = "--apply-update";
    private static final String SERVICE_OPTION = "--service";
    private static final String REMOVE_PROFILE_OPTION = "--remove-profile";
    private static final String YES_OPTION = "--yes";
    private static final String SERVICE_OUTPUT_OPTION = "--service-output";
    private static final String WAIT_OPTION = "--wait";

    // What --webui-port, --webui-lan, --webui-api, --webui-host, --webui-proxy and the guest's options apply to.
    private static final String WEBUI_SETUP =
            WEBUI_PASSWORD_OPTION + ", " + WEBUI_PASSWORD_FILE_OPTION + " or " + SERVICE_OPTION + "=install";

    // What --exit and --restore-links may come with: where and how to run, nothing to do.
    private static final Set<String> ALONG =
            Set.of(CONFIG_DIR_OPTION, LOG_LEVEL_OPTION, HEADLESS_OPTION, WAIT_OPTION, YES_OPTION);

    private static final Set<String> SERVICE_ALONG = Set.of(SERVICE_OPTION, SERVICE_OUTPUT_OPTION, LOG_LEVEL_OPTION);
    private static final Set<String> SERVICE_INSTALL_ALONG = Set.of(
            SERVICE_OPTION,
            SERVICE_OUTPUT_OPTION,
            LOG_LEVEL_OPTION,
            WEBUI_PASSWORD_OPTION,
            WEBUI_PASSWORD_FILE_OPTION,
            WEBUI_PORT_OPTION,
            WEBUI_LAN_OPTION,
            WEBUI_API_OPTION,
            WEBUI_HOST_OPTION,
            WEBUI_PROXY_OPTION,
            WEBUI_GUEST_PASSWORD_FILE_OPTION,
            WEBUI_NO_GUEST_OPTION);

    public static LaunchOptions parse(String... args) throws UsageException {
        ParseResult result;
        try {
            result = parser().parseArgs(args);
        } catch (ParameterException e) {
            throw new UsageException(e.getMessage());
        }
        for (String alone : List.of(
                EXIT_OPTION,
                RESTORE_LINKS_OPTION,
                CHECK_UPDATE_OPTION,
                UPDATE_OPTION,
                APPLY_UPDATE_OPTION,
                REMOVE_PROFILE_OPTION)) {
            if (result.hasMatchedOption(alone) && !result.isUsageHelpRequested() && !result.isVersionHelpRequested()) {
                alone(result, alone);
            }
        }
        Optional<ServiceTask> service = service(result);
        if (result.hasMatchedOption(YES_OPTION) && !result.hasMatchedOption(REMOVE_PROFILE_OPTION)) {
            throw new UsageException(YES_OPTION + " applies to " + REMOVE_PROFILE_OPTION);
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
                webUi(result, service),
                links(result.matchedPositionalValue(0, List.<String>of())),
                remote(result),
                applyUpdate(result),
                waits(result),
                service,
                result.hasMatchedOption(YES_OPTION));
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
        if (result.hasMatchedOption(SERVICE_OPTION)) {
            return Action.SERVICE;
        }
        if (result.hasMatchedOption(WEBUI_PASSWORD_OPTION)) {
            return Action.WEBUI_PASSWORD;
        }
        if (result.hasMatchedOption(EXIT_OPTION)) {
            return Action.EXIT;
        }
        if (result.hasMatchedOption(REMOTE_OPTION)) {
            return Action.REMOTE;
        }
        if (result.hasMatchedOption(NEW_API_TOKEN_OPTION)) {
            return Action.NEW_API_TOKEN;
        }
        if (result.hasMatchedOption(API_TOKEN_OPTION)) {
            return Action.API_TOKEN;
        }
        if (result.hasMatchedOption(RESTORE_LINKS_OPTION)) {
            return Action.RESTORE_LINKS;
        }
        if (result.hasMatchedOption(REMOVE_PROFILE_OPTION)) {
            return Action.REMOVE_PROFILE;
        }
        if (result.hasMatchedOption(CHECK_UPDATE_OPTION)) {
            return Action.CHECK_UPDATE;
        }
        if (result.hasMatchedOption(UPDATE_OPTION)) {
            return Action.UPDATE;
        }
        if (result.hasMatchedOption(APPLY_UPDATE_OPTION)) {
            return Action.APPLY_UPDATE;
        }
        return result.hasMatchedOption(HASH_OPTION) ? Action.HASH : Action.RUN;
    }

    private static CommandLine parser() {
        CommandSpec spec = CommandSpec.create().name("njmule");
        spec.addOption(valued(CONFIG_DIR_OPTION, "<dir>", "keep all data below <dir> instead of the system locations"));
        spec.addOption(
                valued(LOG_LEVEL_OPTION, "<level>", "ERROR, WARN, INFO, DEBUG or TRACE (overrides the preferences)"));
        spec.addOption(flag(HEADLESS_OPTION, "accepted for older start commands: njmule never opens a window"));
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
                WEBUI_PASSWORD_FILE_OPTION,
                "<file>",
                "the web interface password is the first line of <file> (a Docker or systemd secret, for example):"
                        + " set at each start, turning the interface on and keeping its other options as saved unless"
                        + " given too, or with "
                        + WEBUI_PASSWORD_OPTION
                        + " set without asking"));
        spec.addOption(valued(WEBUI_PORT_OPTION, "<port>", "with " + WEBUI_SETUP + ": the port of the web interface"));
        spec.addOption(flag(
                EXIT_OPTION,
                "ask the njMule running on this configuration to save and exit, and wait for it (30 s at most)"));
        spec.addOption(flag(
                RESTORE_LINKS_OPTION,
                "give ed2k links back to what opened them before the desktop app took them, if it still has them,"
                        + " stop it from starting at login, and exit (the uninstallers run it)"));
        spec.addOption(flag(
                WEBUI_LAN_OPTION, "with " + WEBUI_SETUP + ": let other computers connect (otherwise only this one)"));
        spec.addOption(flag(
                WEBUI_API_OPTION,
                "with " + WEBUI_SETUP + ": let the remote-control API answer too, to holders of its token"));
        spec.addOption(OptionSpec.builder(WEBUI_HOST_OPTION)
                .paramLabel("<name>")
                .type(List.class)
                .auxiliaryTypes(String.class)
                .splitRegex(",")
                .description("with "
                        + WEBUI_SETUP
                        + ": a host name the web interface answers to, such as a LAN name or a reverse proxy's"
                        + " (repeatable); otherwise only localhost and, with "
                        + WEBUI_LAN_OPTION
                        + ", IP addresses")
                .build());
        spec.addOption(OptionSpec.builder(WEBUI_PROXY_OPTION)
                .paramLabel("<address>")
                .type(List.class)
                .auxiliaryTypes(String.class)
                .splitRegex(",")
                .description("with "
                        + WEBUI_SETUP
                        + ": a reverse proxy trusted to name its client in X-Forwarded-For, an address, a CIDR block or"
                        + " a range (repeatable); otherwise every request is its own client")
                .build());
        spec.addOption(valued(
                WEBUI_GUEST_PASSWORD_FILE_OPTION,
                "<file>",
                "with " + WEBUI_SETUP + ": a guest, who may only look, logs in with the first line of <file>, which"
                        + " is not the web interface password"));
        spec.addOption(flag(WEBUI_NO_GUEST_OPTION, "with " + WEBUI_SETUP + ": let no guest log in any more"));
        spec.addOption(OptionSpec.builder(REMOTE_OPTION)
                .paramLabel("<command>")
                .type(List.class)
                .auxiliaryTypes(String.class)
                .arity("1..*")
                .description("run a command in the njMule running on this configuration (or at "
                        + REMOTE_URL_OPTION
                        + "), through its remote-control API: "
                        + REMOTE_OPTION
                        + " help lists them")
                .build());
        spec.addOption(valued(
                REMOTE_URL_OPTION,
                "<url>",
                "the API of another njMule, as http://host:4711/api/v1, where the command of " + REMOTE_OPTION
                        + " runs; the token comes from the NJMULE_API_TOKEN environment variable (njmule-gui"
                        + " --remote-url <url> shows that njMule in the desktop app)"));
        spec.addOption(flag(
                CHECK_UPDATE_OPTION,
                "look for a new version of njMule on GitHub, print it and exit: 0 when this one is the newest,"
                        + " 100 when a newer one is out, 1 when the check failed"));
        spec.addOption(flag(
                UPDATE_OPTION,
                "look for a new version of njMule, download it, check it against its signed information and install"
                        + " it where njMule may replace itself (the Windows installer and zip, the macOS app, the Linux"
                        + " archives; elsewhere it tells how), then exit; stop njMule first (njmule --exit)"));
        spec.addOption(valued(
                SERVICE_OPTION,
                "<action>",
                "install, uninstall, start, stop or status of njMule as a Windows service, which runs it headless"
                        + " with its web interface on the profile in %%ProgramData%%\\njMule as the virtual account"
                        + " NT SERVICE\\njMule (Windows asks for the administrator's rights); install takes the web"
                        + " interface options, asks for its password when none is set, and restarts a service that"
                        + " runs"));
        spec.addOption(flag(
                REMOVE_PROFILE_OPTION,
                "delete this njMule's settings, lists, identity, unfinished downloads, caches and logs, after asking,"
                        + " and exit; the completed downloads stay (stop njMule first)"));
        spec.addOption(flag(YES_OPTION, "with " + REMOVE_PROFILE_OPTION + ": do not ask"));
        spec.addOption(OptionSpec.builder(SERVICE_OUTPUT_OPTION)
                .paramLabel("<file>")
                .type(String.class)
                .hidden(true)
                .description("also write the messages of " + SERVICE_OPTION + " to <file>")
                .build());
        spec.addOption(OptionSpec.builder(APPLY_UPDATE_OPTION)
                .paramLabel("<plan>")
                .type(String.class)
                .hidden(true)
                .description("install the new version that a stopping njMule handed over in <plan>")
                .build());
        spec.addOption(OptionSpec.builder(WAIT_OPTION)
                .paramLabel("<pid>")
                .type(List.class)
                .auxiliaryTypes(String.class)
                .hidden(true)
                .description("wait (60 s at most) for the process <pid> to end first, as after an update")
                .build());
        spec.addOption(flag(API_TOKEN_OPTION, "print the token of the remote-control API, making one if needed"));
        spec.addOption(
                flag(NEW_API_TOKEN_OPTION, "replace the token of the remote-control API, print the new one and exit"));
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

    // Another action, a task or links beside the option would be dropped without a word.
    // The service has its own profile: --service comes alone, but for the web interface's options of install.
    private static Optional<ServiceTask> service(ParseResult result) throws UsageException {
        if (!result.hasMatchedOption(SERVICE_OPTION)) {
            if (result.hasMatchedOption(SERVICE_OUTPUT_OPTION)) {
                throw new UsageException(SERVICE_OUTPUT_OPTION + " applies to " + SERVICE_OPTION);
            }
            return Optional.empty();
        }
        String value = result.matchedOptionValue(SERVICE_OPTION, "");
        ServiceAction action;
        try {
            action = ServiceAction.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new UsageException(
                    "Invalid " + SERVICE_OPTION + ": " + value + " (install, uninstall, start, stop or status)");
        }
        Set<String> along = action == ServiceAction.INSTALL ? SERVICE_INSTALL_ALONG : SERVICE_ALONG;
        for (OptionSpec option : result.matchedOptions()) {
            if (!along.contains(option.longestName())) {
                throw new UsageException(
                        SERVICE_OPTION + "=" + value + " cannot be combined with " + option.longestName());
            }
        }
        if (!result.matchedPositionalValue(0, List.<String>of()).isEmpty()) {
            throw new UsageException(SERVICE_OPTION + " cannot be combined with links");
        }
        Optional<Path> output = Optional.empty();
        if (result.hasMatchedOption(SERVICE_OUTPUT_OPTION)) {
            output = Optional.of(toFile(result.matchedOptionValue(SERVICE_OUTPUT_OPTION, "")));
        }
        return Optional.of(new ServiceTask(action, output));
    }

    private static void alone(ParseResult result, String name) throws UsageException {
        for (OptionSpec option : result.matchedOptions()) {
            if (!option.longestName().equals(name) && !ALONG.contains(option.longestName())) {
                throw new UsageException(name + " cannot be combined with " + option.longestName());
            }
        }
        if (!result.matchedPositionalValue(0, List.<String>of()).isEmpty()) {
            throw new UsageException(name + " cannot be combined with links");
        }
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

    // The service keeps the password it has when install comes with the other options only.
    private static Optional<WebUiSetup> webUi(ParseResult result, Optional<ServiceTask> service) throws UsageException {
        boolean port = result.hasMatchedOption(WEBUI_PORT_OPTION);
        boolean lan = result.hasMatchedOption(WEBUI_LAN_OPTION);
        boolean api = result.hasMatchedOption(WEBUI_API_OPTION);
        boolean host = result.hasMatchedOption(WEBUI_HOST_OPTION);
        boolean proxy = result.hasMatchedOption(WEBUI_PROXY_OPTION);
        boolean noGuest = result.hasMatchedOption(WEBUI_NO_GUEST_OPTION);
        Optional<Path> passwordFile = Optional.empty();
        if (result.hasMatchedOption(WEBUI_PASSWORD_FILE_OPTION)) {
            passwordFile = Optional.of(toFile(result.matchedOptionValue(WEBUI_PASSWORD_FILE_OPTION, "")));
        }
        Optional<Path> guestFile = Optional.empty();
        if (result.hasMatchedOption(WEBUI_GUEST_PASSWORD_FILE_OPTION)) {
            guestFile = Optional.of(toFile(result.matchedOptionValue(WEBUI_GUEST_PASSWORD_FILE_OPTION, "")));
        }
        if (noGuest && guestFile.isPresent()) {
            throw new UsageException(
                    WEBUI_GUEST_PASSWORD_FILE_OPTION + " cannot be combined with " + WEBUI_NO_GUEST_OPTION);
        }
        boolean keepPassword = !result.hasMatchedOption(WEBUI_PASSWORD_OPTION) && passwordFile.isEmpty();
        if (keepPassword && !(port || lan || api || host || proxy || noGuest || guestFile.isPresent())) {
            return Optional.empty();
        }
        if (keepPassword
                && service.map(task -> task.action() != ServiceAction.INSTALL).orElse(true)) {
            throw new UsageException(WEBUI_PORT_OPTION + ", " + WEBUI_LAN_OPTION + ", " + WEBUI_API_OPTION + ", "
                    + WEBUI_HOST_OPTION + ", " + WEBUI_PROXY_OPTION + ", " + WEBUI_GUEST_PASSWORD_FILE_OPTION + " and "
                    + WEBUI_NO_GUEST_OPTION + " apply to " + WEBUI_SETUP);
        }
        List<String> hosts = new ArrayList<>();
        for (String name : result.matchedOptionValue(WEBUI_HOST_OPTION, List.<String>of())) {
            try {
                hosts.add(WebUiSettings.hostName(name));
            } catch (IllegalArgumentException e) {
                throw new UsageException("Invalid host name for " + WEBUI_HOST_OPTION + ": " + name);
            }
        }
        List<String> proxies = new ArrayList<>();
        for (String address : result.matchedOptionValue(WEBUI_PROXY_OPTION, List.<String>of())) {
            try {
                proxies.add(WebUiSettings.proxy(address));
            } catch (IllegalArgumentException e) {
                throw new UsageException("Invalid proxy for " + WEBUI_PROXY_OPTION + ": " + address);
            }
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
        return Optional.of(
                new WebUiSetup(number, lan, api, passwordFile, hosts, proxies, keepPassword, guestFile, noGuest));
    }

    private static Optional<Path> applyUpdate(ParseResult result) throws UsageException {
        if (!result.hasMatchedOption(APPLY_UPDATE_OPTION)) {
            return Optional.empty();
        }
        return Optional.of(toFile(result.matchedOptionValue(APPLY_UPDATE_OPTION, "")));
    }

    private static List<Long> waits(ParseResult result) throws UsageException {
        List<Long> pids = new ArrayList<>();
        for (String value : result.matchedOptionValue(WAIT_OPTION, List.<String>of())) {
            try {
                long pid = Long.parseLong(value.strip());
                if (pid <= 0) {
                    throw new NumberFormatException(value);
                }
                pids.add(pid);
            } catch (NumberFormatException e) {
                throw new UsageException("Invalid process for " + WAIT_OPTION + ": " + value);
            }
        }
        return pids;
    }

    // Without --remote, an address is the njMule the desktop app shows: a remote with no command.
    private static Optional<Remote> remote(ParseResult result) throws UsageException {
        if (!result.hasMatchedOption(REMOTE_OPTION) && !result.hasMatchedOption(REMOTE_URL_OPTION)) {
            return Optional.empty();
        }
        Optional<URI> url = Optional.empty();
        if (result.hasMatchedOption(REMOTE_URL_OPTION)) {
            String value = result.matchedOptionValue(REMOTE_URL_OPTION, "");
            try {
                URI uri = new URI(value.strip());
                if (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme())) {
                    throw new URISyntaxException(value, "not http or https");
                }
                url = Optional.of(uri);
            } catch (URISyntaxException e) {
                throw new UsageException("Invalid address for " + REMOTE_URL_OPTION + ": " + value);
            }
        }
        return Optional.of(new Remote(result.matchedOptionValue(REMOTE_OPTION, List.<String>of()), url));
    }

    /** The njMule whose API the desktop app shows instead of an engine of its own, if any. */
    public Optional<URI> remoteDesktop() {
        return this.remote.filter(r -> r.command().isEmpty()).flatMap(Remote::url);
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
