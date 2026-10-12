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

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.njmule.app.logging.LoggingSetup;
import org.njmule.core.api.EngineConfig;
import org.njmule.core.api.NjMuleEngine;
import org.njmule.core.api.NjMuleInfo;
import org.njmule.core.config.AppDirectories;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Headless entry point: runs the engine until the process is asked to stop. */
public final class Main {

    public static final int EXIT_OK = 0;
    public static final int EXIT_USAGE = 2;
    public static final int EXIT_FAILURE = 1;

    private static final Logger LOG = LoggerFactory.getLogger(Main.class);

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
     * @param stop completed by tests to end a {@code RUN}; in production only the shutdown hook
     *     (Ctrl+C, SIGTERM, logoff) stops the engine
     * @return the process exit code
     */
    public static int run(
            String[] args, PrintStream out, PrintStream err, CompletableFuture<Void> stop) {
        CommandLine cli;
        try {
            cli = CommandLine.parse(args);
        } catch (CommandLine.UsageException e) {
            err.println(e.getMessage());
            err.print(CommandLine.USAGE);
            return EXIT_USAGE;
        }
        switch (cli.action()) {
            case HELP -> out.print(CommandLine.USAGE);
            case VERSION ->
                    out.println(
                            NjMuleInfo.NAME
                                    + " "
                                    + NjMuleInfo.version()
                                    + " ("
                                    + NjMuleInfo.LICENSE_ID
                                    + ")");
            case LICENSE -> out.print(NjMuleInfo.licenseText());
            case WARRANTY -> out.println(NjMuleInfo.warrantyText());
            case NOTICE -> out.print(NjMuleInfo.noticeText());
            case RUN -> {
                return runEngine(cli, out, err, stop);
            }
        }
        return EXIT_OK;
    }

    private static int runEngine(
            CommandLine cli, PrintStream out, PrintStream err, CompletableFuture<Void> stop) {
        out.println(NjMuleInfo.startupNotice("njmule --warranty", "njmule --license"));
        AppDirectories dirs = AppDirectories.resolve(cli.configDir(), executableDir());
        NjMuleEngine engine;
        try {
            dirs.createAll();
            LoggingSetup.enableFileLog(dirs.logs());
            engine = NjMuleEngine.start(new EngineConfig(dirs));
        } catch (IOException | RuntimeException e) {
            LOG.error("Cannot start {}", NjMuleInfo.NAME, e);
            err.println("Cannot start " + NjMuleInfo.NAME + ": " + e.getMessage());
            return EXIT_FAILURE;
        }
        LoggingSetup.setLevel(cli.logLevel().orElse(engine.preferences().logLevel()));
        LOG.info("Configuration in {}, logs in {}", dirs.config(), dirs.logs());

        Thread hook =
                Thread.ofPlatform()
                        .name("njmule-shutdown")
                        .unstarted(
                                () -> {
                                    engine.close();
                                    stop.complete(null);
                                });
        Runtime.getRuntime().addShutdownHook(hook);
        try {
            stop.join();
            engine.close();
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
