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
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.njmule.core.api.EngineEvent;
import org.njmule.core.api.NjMuleEngine;
import org.njmule.core.api.NjMuleInfo;
import org.njmule.core.api.UpdateHandOver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** What the front ends share around the installation of a new version: waiting, restarting, handing it over. */
public final class Updating {

    /**
     * The exit code that asks a service's supervisor to start njMule again once a new version is in place, as
     * {@code Restart=on-failure} of systemd does.
     */
    public static final int EXIT_RESTART = 75;

    /** How long a start waits for the njMule it replaces. */
    public static final Duration EXIT_WAIT = Duration.ofSeconds(60);

    private static final Logger logger = LoggerFactory.getLogger(Updating.class);
    private static final String WAIT_OPTION = "--wait";

    private Updating() {}

    /**
     * Waits until the processes {@code pids} have ended, {@link #EXIT_WAIT} at most in all: a start after an update
     * finds the files and the configuration of the njMule it replaces free only then.
     */
    public static void awaitEnd(List<Long> pids) {
        long end = System.nanoTime() + EXIT_WAIT.toNanos();
        for (long pid : pids) {
            Optional<ProcessHandle> process = ProcessHandle.of(pid);
            if (process.isEmpty()) {
                continue;
            }
            try {
                process.get().onExit().get(Math.max(0, end - System.nanoTime()), TimeUnit.NANOSECONDS);
            } catch (TimeoutException | ExecutionException e) {
                logger.warn("Process {} still runs after {} s: starting anyway", pid, EXIT_WAIT.toSeconds());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** Runs {@code restart} when the engine asks to restart for a new version; any thread. */
    public static void onRestart(NjMuleEngine engine, Runnable restart) {
        engine.events().subscribe(new Flow.Subscriber<EngineEvent>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(EngineEvent event) {
                if (event instanceof EngineEvent.UpdateRestart) {
                    restart.run();
                }
            }

            @Override
            public void onError(Throwable failure) {
                // The engine stopped.
            }

            @Override
            public void onComplete() {
                // The engine stopped.
            }
        });
    }

    /** The arguments that start njMule again as {@code args} did, without what waited for an earlier process. */
    public static List<String> again(List<String> args) {
        List<String> kept = new ArrayList<>();
        for (int i = 0; i < args.size(); i++) {
            String arg = args.get(i);
            if (arg.equals(WAIT_OPTION)) {
                i++;
            } else if (!arg.startsWith(WAIT_OPTION + "=")) {
                kept.add(arg);
            }
        }
        return kept;
    }

    /** Whether this process is a service of systemd, which starts it again after {@link #EXIT_RESTART}. */
    public static boolean underSystemd(Map<String, String> environment) {
        return environment.containsKey("INVOCATION_ID") && environment.containsKey("JOURNAL_STREAM");
    }

    /**
     * Once the engine of a long run has stopped: hands over the installation of a new version, if one is due; returns
     * the exit code of the process. A service of systemd installs in place, and exits for its supervisor to restart it
     * when asked; any other run hands over to an installer process that starts njMule again with {@code args} when
     * asked.
     */
    public static int handOver(NjMuleEngine engine, List<String> args, Map<String, String> environment) {
        Optional<UpdateHandOver> handOver = engine.updateHandOver();
        if (handOver.isEmpty()) {
            return Main.EXIT_OK;
        }
        UpdateHandOver install = handOver.get();
        try {
            // A service's supervisor stops what it started with it: the files are swapped now.
            if (underSystemd(environment)) {
                install.installHere();
                logger.info("{} {} is in place", NjMuleInfo.NAME, install.version());
                return install.restart() ? EXIT_RESTART : Main.EXIT_OK;
            }
            install.start(again(args));
            logger.info("Installing {} {} once this process has ended", NjMuleInfo.NAME, install.version());
        } catch (IOException | RuntimeException e) {
            logger.error("Cannot install {} {}: {}", NjMuleInfo.NAME, install.version(), e.getMessage());
        }
        return Main.EXIT_OK;
    }
}
