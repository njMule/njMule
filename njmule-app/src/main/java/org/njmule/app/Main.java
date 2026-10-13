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
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.njmule.app.logging.LoggingSetup;
import org.njmule.core.api.Ed2kLink;
import org.njmule.core.api.EngineConfig;
import org.njmule.core.api.FileHashing;
import org.njmule.core.api.NjMuleEngine;
import org.njmule.core.api.NjMuleInfo;
import org.njmule.core.config.AppDirectories;
import org.njmule.webui.Passwords;
import org.njmule.webui.WebUi;
import org.njmule.webui.WebUiSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Headless entry point: runs the engine until the process is asked to stop. */
public final class Main {

    public static final int EXIT_OK = 0;
    public static final int EXIT_USAGE = 2;
    public static final int EXIT_FAILURE = 1;

    private static final Logger logger = LoggerFactory.getLogger(Main.class);

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
            case RUN -> {
                return runEngine(cli, out, err, stop);
            }
        }
        return EXIT_OK;
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
        LaunchOptions.WebUiSetup setup = cli.webUi().orElseThrow();
        AppDirectories dirs = AppDirectories.resolve(cli.configDir(), executableDir());
        char[] first = null;
        char[] second = null;
        try {
            first = passwords.read("Web interface password: ");
            second = passwords.read("The same password again: ");
            if (first == null || second == null || first.length < Passwords.MIN_LENGTH) {
                err.println("The password needs at least " + Passwords.MIN_LENGTH + " characters.");
                return EXIT_USAGE;
            }
            if (!Arrays.equals(first, second)) {
                err.println("The two passwords differ.");
                return EXIT_USAGE;
            }
            WebUiSettings old = WebUiSettings.load(dirs.config());
            WebUiSettings settings = new WebUiSettings(
                    true, setup.port().orElse(old.port()), setup.lan(), Passwords.hash(first, new SecureRandom()));
            settings.save(dirs.config());
            out.println("The web interface starts with "
                    + NjMuleInfo.NAME
                    + " on port "
                    + settings.port()
                    + (settings.lan() ? ", open to other computers." : ": http://localhost:" + settings.port() + "/"));
            return EXIT_OK;
        } catch (IOException e) {
            err.println("Cannot set the web interface password: " + e.getMessage());
            return EXIT_FAILURE;
        } finally {
            if (first != null) {
                Passwords.wipe(first);
            }
            if (second != null) {
                Passwords.wipe(second);
            }
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

    private static Optional<WebUi> startWebUi(NjMuleEngine engine, AppDirectories dirs, PrintStream out) {
        WebUiSettings settings = WebUiSettings.load(dirs.config());
        if (!settings.usable()) {
            return Optional.empty();
        }
        try {
            WebUi ui = WebUi.start(engine, settings);
            out.println("Web interface on port " + ui.port());
            return Optional.of(ui);
        } catch (IOException e) {
            logger.warn("Cannot start the web interface on port {}: {}", settings.port(), e.toString());
            return Optional.empty();
        }
    }

    private static int runEngine(LaunchOptions cli, PrintStream out, PrintStream err, CompletableFuture<Void> stop) {
        out.println(NjMuleInfo.startupNotice("njmule --warranty", "njmule --license"));
        AppDirectories dirs = AppDirectories.resolve(cli.configDir(), executableDir());
        NjMuleEngine engine;
        try {
            dirs.createAll();
            LoggingSetup.enableFileLog(dirs.logs());
            engine = NjMuleEngine.start(new EngineConfig(dirs));
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
            Thread.currentThread().interrupt();
        }

        Optional<WebUi> web = startWebUi(engine, dirs, out);
        Runnable shutdown = () -> {
            web.ifPresent(WebUi::close);
            engine.close();
        };
        Thread hook = Thread.ofPlatform().name("njmule-shutdown").unstarted(() -> {
            shutdown.run();
            stop.complete(null);
        });
        Runtime.getRuntime().addShutdownHook(hook);
        try {
            stop.join();
            shutdown.run();
            try {
                Runtime.getRuntime().removeShutdownHook(hook);
            } catch (IllegalStateException e) {
                // Already shutting down: the hook itself stopped the engine.
            }
        } finally {
            LoggingSetup.disableFileLog();
        }
        return EXIT_OK;
    }

    // Only a native executable has a meaningful directory for the portable-mode marker; on the
    // JVM the command is the java launcher.
    private static Optional<Path> executableDir() {
        if (!"runtime".equals(System.getProperty("org.graalvm.nativeimage.imagecode"))) {
            return Optional.empty();
        }
        return ProcessHandle.current().info().command().map(Path::of).map(Path::getParent);
    }
}
