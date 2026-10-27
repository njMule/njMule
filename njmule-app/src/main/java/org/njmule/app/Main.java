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

import java.io.BufferedReader;
import java.io.Console;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import org.njmule.app.logging.LoggingSetup;
import org.njmule.core.api.DownloadInfo;
import org.njmule.core.api.Ed2kLink;
import org.njmule.core.api.EngineConfig;
import org.njmule.core.api.FileHashing;
import org.njmule.core.api.NjMuleEngine;
import org.njmule.core.api.NjMuleInfo;
import org.njmule.core.api.ServerAddress;
import org.njmule.core.api.UpdateHandOver;
import org.njmule.core.api.UpdateStatus;
import org.njmule.core.config.AppDirectories;
import org.njmule.core.remote.ApiToken;
import org.njmule.webui.ApiClient;
import org.njmule.webui.Passwords;
import org.njmule.webui.UpdateHelp;
import org.njmule.webui.WebUi;
import org.njmule.webui.WebUiSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Headless entry point: runs the engine until the process is asked to stop. */
public final class Main {

    public static final int EXIT_OK = 0;
    public static final int EXIT_USAGE = 2;
    public static final int EXIT_FAILURE = 1;
    /** What {@code --check-update} returns when a newer version is out, as package managers do. */
    public static final int EXIT_UPDATE_AVAILABLE = 100;

    private static final Logger logger = LoggerFactory.getLogger(Main.class);

    /** How long --exit waits for the running instance to stop. */
    private static final Duration EXIT_WAIT = Duration.ofSeconds(30);

    private static final Duration REMOTE_SEARCH_WAIT = Duration.ofSeconds(10);

    /** How long --check-update waits for GitHub: both fetches, each with its own time limit. */
    private static final Duration CHECK_UPDATE_WAIT = Duration.ofMinutes(4);

    /** How long --update waits for the package of a new version, on a slow line too. */
    private static final Duration DOWNLOAD_WAIT = Duration.ofHours(2);

    private static final Duration PROGRESS_EVERY = Duration.ofSeconds(1);

    /** Where {@code --remote-url} finds the token of the other computer's API. */
    static final String API_TOKEN_VARIABLE = "NJMULE_API_TOKEN";

    /** Where a password comes from: the terminal, or a line of standard input. */
    @FunctionalInterface
    interface PasswordSource {
        /** The password typed after {@code prompt}, or null at the end of the input. */
        char[] read(String prompt) throws IOException;
    }

    private Main() {}

    public static void main(String[] args) {
        int code = run(args, System.out, System.err, new CompletableFuture<>());
        if (code != EXIT_OK) {
            System.exit(code);
        }
    }

    /**
     * Executes the command line.
     *
     * @param stop completed by tests to end a {@code RUN}; in production only the shutdown hook (Ctrl+C, SIGTERM,
     *     logoff) stops the engine
     * @return the process exit code
     */
    public static int run(String[] args, PrintStream out, PrintStream err, CompletableFuture<Void> stop) {
        return run(args, out, err, stop, terminal(out));
    }

    static int run(
            String[] args, PrintStream out, PrintStream err, CompletableFuture<Void> stop, PasswordSource passwords) {
        LaunchOptions cli;
        try {
            cli = LaunchOptions.parse(args);
        } catch (LaunchOptions.UsageException e) {
            err.println(e.getMessage());
            err.print(LaunchOptions.usage());
            return EXIT_USAGE;
        }
        switch (cli.action()) {
            case HELP -> out.print(LaunchOptions.usage());
            case VERSION ->
                out.println(NjMuleInfo.NAME + " " + NjMuleInfo.version() + " (" + NjMuleInfo.LICENSE_ID + ")");
            case LICENSE -> out.println(NjMuleInfo.licenseText().stripTrailing());
            case WARRANTY -> out.println(NjMuleInfo.warrantyText());
            case NOTICE -> out.println(NjMuleInfo.noticeText().stripTrailing());
            case HASH -> {
                return hash(cli, out, err);
            }
            case WEBUI_PASSWORD -> {
                return webUiPassword(cli, out, err, passwords);
            }
            case EXIT -> {
                Path config = AppDirectories.resolve(cli.configDir()).config();
                if (!LinkHandoff.deliver(config, List.of(LinkHandoff.EXIT))) {
                    err.println("No njMule runs on " + config);
                    return EXIT_FAILURE;
                }
                // Scripts go on once it has saved and stopped, not as soon as it was asked.
                if (!LinkHandoff.awaitGone(config, EXIT_WAIT)) {
                    err.println(
                            NjMuleInfo.NAME + " on " + config + " still runs after " + EXIT_WAIT.toSeconds() + " s");
                    return EXIT_FAILURE;
                }
                out.println(NjMuleInfo.NAME + " on " + config + " has exited.");
                return EXIT_OK;
            }
            case RESTORE_LINKS -> {
                return restoreLinks(
                        AppDirectories.resolve(cli.configDir()).config(),
                        LinkAssociation::forThisSystem,
                        Main::autostart,
                        out,
                        err);
            }
            case RUN -> {
                if (cli.remoteDesktop().isPresent()) {
                    err.println("--remote-url without --remote opens the desktop app on that njMule.");
                    return EXIT_USAGE;
                }
                Updating.awaitEnd(cli.waitFor());
                return runEngine(cli, List.of(args), out, err, stop);
            }
            case REMOTE -> {
                return remote(cli, out, err);
            }
            case API_TOKEN, NEW_API_TOKEN -> {
                return apiToken(cli, out, err);
            }
            case CHECK_UPDATE -> {
                return checkUpdate(cli, out, err);
            }
            case UPDATE -> {
                return update(cli, out, err);
            }
            case SERVICE -> {
                return WindowsService.run(cli, out, err, passwords);
            }
            case REMOVE_PROFILE -> {
                return ProfileRemoval.run(cli, out, err, prompt -> answer(passwords, prompt));
            }
            case APPLY_UPDATE -> {
                LoggingSetup.setLevel(cli.logLevel().orElse("INFO"));
                return UpdateHandOver.install(cli.applyUpdate().orElseThrow(), cli.waitFor());
            }
        }
        return EXIT_OK;
    }

    // A command for a running njMule, through its remote-control API.
    private static int remote(LaunchOptions cli, PrintStream out, PrintStream err) {
        LaunchOptions.Remote remote = cli.remote().orElseThrow();
        if (remote.command().equals(List.of("help"))) {
            out.print(RemoteControl.COMMANDS);
            return EXIT_OK;
        }
        Path config = AppDirectories.resolve(cli.configDir()).config();
        Optional<ApiClient> api;
        try {
            if (remote.url().isPresent()) {
                String token = System.getenv(API_TOKEN_VARIABLE);
                if (token == null || token.isBlank()) {
                    err.println("Set " + API_TOKEN_VARIABLE
                            + " to the API token of that njMule (njmule --api-token there).");
                    return EXIT_USAGE;
                }
                api = Optional.of(new ApiClient(remote.url().get(), token.strip()));
            } else {
                api = ApiClient.local(config);
            }
        } catch (IOException e) {
            err.println("Cannot read the settings of the web interface: " + e.getMessage());
            return EXIT_FAILURE;
        }
        if (api.isEmpty()) {
            err.println("The remote-control API of " + config + " is off: turn the web interface on with its API"
                    + " (njmule --webui-password --webui-api, or in the settings) and start njMule.");
            return EXIT_FAILURE;
        }
        try {
            new RemoteControl(api.get(), out, REMOTE_SEARCH_WAIT).run(remote.command());
            return EXIT_OK;
        } catch (RemoteControl.Usage e) {
            err.println(e.getMessage());
            err.print(RemoteControl.COMMANDS);
            return EXIT_USAGE;
        } catch (ApiClient.Failure e) {
            err.println(e.getMessage());
            return EXIT_FAILURE;
        } catch (IOException e) {
            err.println("No njMule answers there: " + e.getMessage());
            return EXIT_FAILURE;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return EXIT_FAILURE;
        }
    }

    // An engine that connects to nothing on its own, so that the check follows the proxy and the network interface
    // of the preferences as every other request does.
    private static int checkUpdate(LaunchOptions cli, PrintStream out, PrintStream err) {
        AppDirectories dirs = AppDirectories.resolve(cli.configDir());
        LoggingSetup.setLevel(cli.logLevel().orElse("ERROR"));
        UpdateStatus status;
        try (NjMuleEngine engine = NjMuleEngine.start(new EngineConfig(dirs, false))) {
            status = engine.checkForUpdate().get(CHECK_UPDATE_WAIT.toSeconds(), TimeUnit.SECONDS);
        } catch (IOException | RuntimeException e) {
            err.println("Cannot start " + NjMuleInfo.NAME + " to check: " + e.getMessage());
            err.println("When it runs, ask it: njmule --remote update check");
            return EXIT_FAILURE;
        } catch (ExecutionException | TimeoutException e) {
            err.println("Cannot check for a new version: " + e);
            return EXIT_FAILURE;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return EXIT_FAILURE;
        }
        UpdateReport report = UpdateReport.of(status);
        report.print(out, err);
        if (report.isAvailable()) {
            return EXIT_UPDATE_AVAILABLE;
        }
        return status.failure().isPresent() ? EXIT_FAILURE : EXIT_OK;
    }

    // As --check-update, then the package downloaded and checked, and installed from this process once its engine has
    // stopped: the files of an archive or a bundle are swapped now, an installer package runs once this command ends.
    private static int update(LaunchOptions cli, PrintStream out, PrintStream err) {
        AppDirectories dirs = AppDirectories.resolve(cli.configDir());
        LoggingSetup.setLevel(cli.logLevel().orElse("ERROR"));
        NjMuleEngine engine;
        try {
            engine = NjMuleEngine.start(new EngineConfig(dirs, false));
        } catch (IOException | RuntimeException e) {
            err.println("Cannot start " + NjMuleInfo.NAME + " to update it: " + e.getMessage());
            err.println("When it runs, ask it: njmule --remote update install, or stop it first: njmule --exit");
            return EXIT_FAILURE;
        }
        UpdateStatus status;
        try (engine) {
            status = engine.checkForUpdate().get(CHECK_UPDATE_WAIT.toSeconds(), TimeUnit.SECONDS);
            UpdateReport report = UpdateReport.of(status);
            if (!report.isAvailable()
                    || status.available().orElseThrow().download().isEmpty()) {
                report.print(out, err);
                return status.failure().isPresent() ? EXIT_FAILURE : EXIT_OK;
            }
            status = download(engine, out);
            if (status.failure().isPresent() || status.ready().isEmpty()) {
                err.println(
                        "Cannot download the new version: " + status.failure().orElse("it did not come"));
                return EXIT_FAILURE;
            }
            if (!status.canInstall()) {
                out.println("Downloaded and checked: " + status.ready().get());
                out.println(UpdateHelp.howToInstallFile(
                        status.installation(), status.ready().get()));
                return EXIT_OK;
            }
            engine.installUpdate(false).get(CHECK_UPDATE_WAIT.toSeconds(), TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            err.println("Cannot update: " + e.getCause().getMessage());
            return EXIT_FAILURE;
        } catch (TimeoutException e) {
            err.println("Cannot update: " + NjMuleInfo.NAME + " did not answer in time");
            return EXIT_FAILURE;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return EXIT_FAILURE;
        }
        UpdateHandOver install = engine.updateHandOver().orElseThrow();
        try {
            if (install.installHere()) {
                out.println(NjMuleInfo.NAME + " " + install.version() + " is installed. Start it again to use it.");
            } else {
                out.println("The installer of " + NjMuleInfo.NAME + " " + install.version()
                        + " runs once this command has ended.");
            }
            return EXIT_OK;
        } catch (IOException e) {
            err.println("Cannot install " + NjMuleInfo.NAME + " " + install.version() + ": " + e.getMessage());
            return EXIT_FAILURE;
        }
    }

    // The download, its progress printed every tenth.
    private static UpdateStatus download(NjMuleEngine engine, PrintStream out)
            throws InterruptedException, ExecutionException, TimeoutException {
        CompletableFuture<UpdateStatus> download = engine.downloadUpdate();
        long end = System.nanoTime() + DOWNLOAD_WAIT.toNanos();
        int told = -1;
        while (!download.isDone()) {
            if (System.nanoTime() > end) {
                throw new TimeoutException();
            }
            Optional<UpdateStatus.Progress> progress = engine.updateStatus().download();
            if (progress.isPresent()) {
                int tenth = (int) (progress.get().fraction() * 10);
                if (tenth > told) {
                    told = tenth;
                    out.printf(Locale.ROOT, "Downloading %d%%%n", tenth * 10);
                }
            }
            try {
                download.get(PROGRESS_EVERY.toMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                // Still coming.
            }
        }
        return download.get();
    }

    private static int apiToken(LaunchOptions cli, PrintStream out, PrintStream err) {
        Path config = AppDirectories.resolve(cli.configDir()).config();
        try {
            out.println(
                    cli.action() == LaunchOptions.Action.NEW_API_TOKEN
                            ? ApiToken.renew(config, new SecureRandom())
                            : ApiToken.loadOrCreate(config, new SecureRandom()));
            return EXIT_OK;
        } catch (IOException e) {
            err.println("Cannot keep the API token: " + e.getMessage());
            return EXIT_FAILURE;
        }
    }

    // A temporary directory only this user may enter. Elsewhere the temporary folder is shared, so the directory gets
    // owner-only permissions; on Windows it lies in the user's own temporary folder, which others cannot enter.
    @SuppressWarnings("java:S5443")
    private static Path privateTempDirectory(String prefix) throws IOException {
        if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            return Files.createTempDirectory(
                    prefix, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        }
        return Files.createTempDirectory(prefix);
    }

    /** The start at login of this system, whatever its arguments: here only to be turned off. */
    static Optional<Autostart> autostart(Path configDir) {
        return Autostart.forThisSystem(configDir, List.of());
    }

    /**
     * Undoes what the desktop app set up on this system, as an uninstaller asks: gives the ed2k links back when it
     * still has them, and stops it from starting at login. Without a configuration directory there is no saved handler:
     * ours only goes, from a scratch directory that goes too.
     */
    static int restoreLinks(
            Path config,
            Function<Path, Optional<LinkAssociation>> associations,
            Function<Path, Optional<Autostart>> autostarts,
            PrintStream out,
            PrintStream err) {
        Path scratch = null;
        try {
            Path dir = config;
            if (!Files.isDirectory(config)) {
                scratch = privateTempDirectory("njmule-links-");
                dir = scratch;
            }
            int code = giveLinksBack(associations.apply(dir), out, err);
            try {
                Optional<Autostart> start = autostarts.apply(dir);
                if (start.isPresent() && start.get().isOn()) {
                    start.get().turnOff();
                    out.println(NjMuleInfo.NAME + " no longer starts when you log in.");
                }
            } catch (IOException e) {
                err.println("Cannot stop " + NjMuleInfo.NAME + " from starting at login: " + e.getMessage());
                code = EXIT_FAILURE;
            }
            return code;
        } catch (IOException e) {
            err.println("Cannot give the ed2k links back: " + e.getMessage());
            return EXIT_FAILURE;
        } finally {
            if (scratch != null) {
                try {
                    Files.deleteIfExists(scratch);
                } catch (IOException e) {
                    logger.debug("Cannot delete {}: {}", scratch, e.toString());
                }
            }
        }
    }

    private static int giveLinksBack(Optional<LinkAssociation> association, PrintStream out, PrintStream err) {
        if (association.isEmpty()) {
            out.println("This program does not open ed2k links here: nothing to give back.");
            return EXIT_OK;
        }
        try {
            if (!association.get().isOurs()) {
                out.println("ed2k links open in another program: left as they are.");
                return EXIT_OK;
            }
            association.get().giveBack();
            out.println("ed2k links given back.");
            return EXIT_OK;
        } catch (IOException e) {
            err.println("Cannot give the ed2k links back: " + e.getMessage());
            return EXIT_FAILURE;
        }
    }

    // Prints the link of each file (without part hashes) and the hashing speed.
    private static int hash(LaunchOptions cli, PrintStream out, PrintStream err) {
        int code = EXIT_OK;
        for (Path file : cli.files()) {
            long start = System.nanoTime();
            try {
                Ed2kLink.FileLink link = FileHashing.link(file, bytes -> {});
                double seconds = Math.max(1e-9, (System.nanoTime() - start) / 1e9);
                Ed2kLink.FileLink shown = new Ed2kLink.FileLink(
                        link.name(), link.size(), link.hash(), link.aichHash(), List.of(), List.of(), List.of());
                out.println(shown.toUri());
                out.printf(
                        Locale.ROOT,
                        "  %d bytes, %d part hashes, %.1f MB/s%n",
                        link.size(),
                        link.partHashes().size(),
                        link.size() / seconds / 1e6);
            } catch (IOException e) {
                err.println("Cannot hash " + file + ": " + e.getMessage());
                code = EXIT_FAILURE;
            }
        }
        return code;
    }

    private static int webUiPassword(LaunchOptions cli, PrintStream out, PrintStream err, PasswordSource passwords) {
        Path config = AppDirectories.resolve(cli.configDir()).config();
        try {
            WebUiSettings settings = WebUiPassword.setFromFile(cli, config)
                    ? WebUiSettings.load(config)
                    : setTypedWebUiPassword(cli.webUi().orElseThrow(), config, passwords);
            out.println("The web interface starts with "
                    + NjMuleInfo.NAME
                    + " on port "
                    + settings.port()
                    + (settings.lan() ? ", open to other computers." : ": http://localhost:" + settings.port() + "/"));
            return EXIT_OK;
        } catch (LaunchOptions.UsageException e) {
            err.println(e.getMessage());
            return EXIT_USAGE;
        } catch (IOException e) {
            err.println("Cannot set the web interface password: " + e.getMessage());
            return EXIT_FAILURE;
        }
    }

    static WebUiSettings setTypedWebUiPassword(LaunchOptions.WebUiSetup setup, Path config, PasswordSource passwords)
            throws LaunchOptions.UsageException, IOException {
        char[] first = null;
        char[] second = null;
        try {
            first = passwords.read("Web interface password: ");
            second = passwords.read("The same password again: ");
            if (first == null || second == null || first.length < Passwords.MIN_LENGTH) {
                throw new LaunchOptions.UsageException(
                        "The password needs at least " + Passwords.MIN_LENGTH + " characters.");
            }
            if (!Arrays.equals(first, second)) {
                throw new LaunchOptions.UsageException("The two passwords differ.");
            }
            return WebUiPassword.save(config, setup, first);
        } finally {
            if (first != null) {
                Passwords.wipe(first);
            }
            if (second != null) {
                Passwords.wipe(second);
            }
        }
    }

    // A short answer typed in reply; null at the end of the input.
    private static String answer(PasswordSource input, String prompt) {
        try {
            char[] typed = input.read(prompt);
            return typed == null ? null : new String(typed);
        } catch (IOException e) {
            return null;
        }
    }

    // Reads passwords without echo on a terminal, otherwise one per line of standard input.
    private static PasswordSource terminal(PrintStream out) {
        // Since JDK 22 there is a console even without a terminal; isTerminal() tells them apart.
        Optional<Console> console = Optional.ofNullable(System.console()).filter(Console::isTerminal);
        if (console.isPresent()) {
            Console terminal = console.get();
            return prompt -> terminal.readPassword("%s", prompt);
        }
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        return prompt -> {
            out.print(prompt);
            out.flush();
            String line = in.readLine();
            return line == null ? null : line.toCharArray();
        };
    }

    private static Optional<WebUi> startWebUi(
            NjMuleEngine engine, AppDirectories dirs, PrintStream out, CompletableFuture<Void> stop) {
        WebUiSettings settings = WebUiSettings.load(dirs.config());
        if (!settings.usable()) {
            return Optional.empty();
        }
        try {
            WebUi ui = WebUi.start(engine, settings, () -> stop.complete(null));
            out.println("Web interface on port " + ui.port() + (settings.api() ? ", with the remote-control API" : ""));
            return Optional.of(ui);
        } catch (IOException e) {
            logger.warn("Cannot start the web interface on port {}: {}", settings.port(), e.toString());
            return Optional.empty();
        }
    }

    private static int runEngine(
            LaunchOptions cli, List<String> args, PrintStream out, PrintStream err, CompletableFuture<Void> stop) {
        AppDirectories dirs = AppDirectories.resolve(cli.configDir());
        // Links for the njMule already running go to it, instead of failing on its lock.
        if (!cli.links().isEmpty()
                && !cli.network().isOneShot()
                && LinkHandoff.deliver(
                        dirs.config(), cli.links().stream().map(Ed2kLink::toUri).toList())) {
            out.println("The links went to the " + NjMuleInfo.NAME + " already running.");
            return EXIT_OK;
        }
        out.println(NjMuleInfo.startupNotice("njmule --warranty", "njmule --license"));
        // A password file (a Docker secret, say) is the password of every start.
        try {
            WebUiPassword.setFromFile(cli, dirs.config());
        } catch (LaunchOptions.UsageException e) {
            err.println(e.getMessage());
            return EXIT_USAGE;
        } catch (IOException e) {
            err.println("Cannot set the web interface password: " + e.getMessage());
            return EXIT_FAILURE;
        }
        NjMuleEngine engine;
        try {
            dirs.createAll();
            LoggingSetup.enableFileLog(dirs.logs());
            // A one-shot command connects as it asks, and once.
            engine = NjMuleEngine.start(new EngineConfig(dirs, !cli.network().isOneShot()));
        } catch (IOException | RuntimeException e) {
            logger.error("Cannot start {}", NjMuleInfo.NAME, e);
            err.println("Cannot start " + NjMuleInfo.NAME + ": " + e.getMessage());
            return EXIT_FAILURE;
        }
        LoggingSetup.setLevel(cli.logLevel().orElse(engine.preferences().logLevel()));
        logger.info("Configuration in {}, logs in {}", dirs.config(), dirs.logs());

        LaunchOptions.NetworkTasks tasks = cli.network();
        if (tasks.isOneShot()) {
            // The commands print their own progress, server notices included; the console log
            // keeps only errors.
            if (cli.logLevel().isEmpty()) {
                LoggingSetup.setLevel("ERROR");
            }
            // Interrupting a download still saves what arrived.
            Thread hook = Thread.ofPlatform().name("njmule-shutdown").unstarted(engine::close);
            Runtime.getRuntime().addShutdownHook(hook);
            try {
                return new NetworkCommands(engine, out, err).run(tasks);
            } catch (InterruptedException e) {
                // Closed before the flag is set again: an interrupted close would not wait for the saves and the
                // configuration would stay locked.
                engine.close();
                Thread.currentThread().interrupt();
                return EXIT_FAILURE;
            } finally {
                engine.close();
                try {
                    Runtime.getRuntime().removeShutdownHook(hook);
                } catch (IllegalStateException e) {
                    // Already shutting down: the hook closes the engine too.
                }
                LoggingSetup.disableFileLog();
            }
        }
        try {
            int code = new NetworkCommands(engine, out, err).run(tasks);
            if (code != EXIT_OK) {
                engine.close();
                LoggingSetup.disableFileLog();
                return code;
            }
        } catch (InterruptedException e) {
            // As for a one-shot command: closed before the flag is set again, so that the close waits for the saves.
            engine.close();
            LoggingSetup.disableFileLog();
            Thread.currentThread().interrupt();
            return EXIT_FAILURE;
        }

        // A new version installed now: everything is saved and the engine stops, as on exit.
        Updating.onRestart(engine, () -> stop.complete(null));
        Optional<WebUi> web = startWebUi(engine, dirs, out, stop);
        for (Ed2kLink link : cli.links()) {
            follow(engine, link, err);
        }
        Optional<LinkHandoff> handoff = listen(engine, dirs, err, stop);
        Runnable shutdown = () -> {
            handoff.ifPresent(LinkHandoff::close);
            web.ifPresent(WebUi::close);
            engine.close();
        };
        Thread hook = Thread.ofPlatform().name("njmule-shutdown").unstarted(() -> {
            shutdown.run();
            stop.complete(null);
        });
        Runtime.getRuntime().addShutdownHook(hook);
        // Interrupting the thread that runs njMule stops it too; the flag is set again only after the close, which
        // waits for the saves.
        boolean interrupted = false;
        try {
            try {
                stop.get();
            } catch (InterruptedException e) {
                interrupted = true;
            } catch (ExecutionException e) {
                logger.warn("Stopping: {}", e.getCause().toString());
            }
            shutdown.run();
            try {
                Runtime.getRuntime().removeShutdownHook(hook);
            } catch (IllegalStateException e) {
                // Already shutting down: the hook itself stopped the engine.
            }
            return Updating.handOver(engine, args, System.getenv());
        } finally {
            LoggingSetup.disableFileLog();
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // Later starts hand their links to this one, or ask it to exit.
    private static Optional<LinkHandoff> listen(
            NjMuleEngine engine, AppDirectories dirs, PrintStream err, CompletableFuture<Void> stop) {
        try {
            return Optional.of(LinkHandoff.listen(dirs.config(), links -> {
                if (links.contains(LinkHandoff.EXIT)) {
                    stop.complete(null);
                    return;
                }
                for (String text : links) {
                    Ed2kLink.tryParse(text).ifPresent(link -> follow(engine, link, err));
                }
            }));
        } catch (IOException e) {
            logger.warn("Later starts cannot hand their links over: {}", e.toString());
            return Optional.empty();
        }
    }

    // A file link downloads, a server link adds the server, a server list or nodes.dat link takes the servers or the
    // Kad contacts at its address.
    @SuppressWarnings("FutureReturnValueIgnored") // the follow-ups report their own outcome
    private static void follow(NjMuleEngine engine, Ed2kLink link, PrintStream err) {
        try {
            switch (link) {
                case Ed2kLink.FileLink file -> added(engine.addDownload(file), err);
                case Ed2kLink.ServerLink server ->
                    engine.addServer(new ServerAddress(server.host(), server.port()))
                            .whenComplete((added, failure) -> {
                                if (failure != null) {
                                    err.println("Cannot add the server: " + reason(failure));
                                } else {
                                    logger.info("Server {}:{} added: {}", server.host(), server.port(), added);
                                }
                            });
                case Ed2kLink.ServerListLink list ->
                    engine.addServers(URI.create(list.url())).whenComplete((n, failure) -> {
                        if (failure != null) {
                            err.println("Cannot take the server list: " + reason(failure));
                        } else {
                            logger.info("{} new servers from {}", n, list.url());
                        }
                    });
                case Ed2kLink.NodesListLink list ->
                    engine.addKadContacts(URI.create(list.url())).whenComplete((n, failure) -> {
                        if (failure != null) {
                            err.println("Cannot take the Kad contacts: " + reason(failure));
                        } else {
                            logger.info("{} new Kad contacts from {}", n, list.url());
                        }
                    });
                default -> logger.info("Not acting on {}", link.toUri());
            }
        } catch (IllegalArgumentException e) {
            // An address the link format allows but a URI does not: the other links still count.
            err.println("Cannot follow " + link.toUri() + ": " + e.getMessage());
        }
    }

    // What went wrong, without the wrapping of the future.
    private static String reason(Throwable failure) {
        Throwable cause =
                failure instanceof CompletionException && failure.getCause() != null ? failure.getCause() : failure;
        return String.valueOf(cause.getMessage());
    }

    @SuppressWarnings("FutureReturnValueIgnored") // the follow-up reports its own outcome
    private static void added(CompletableFuture<DownloadInfo> download, PrintStream err) {
        download.whenComplete((info, failure) -> {
            if (failure != null) {
                err.println("Cannot add the download: " + failure.getMessage());
            } else {
                logger.info("Download added: {}", info.name());
            }
        });
    }
}
