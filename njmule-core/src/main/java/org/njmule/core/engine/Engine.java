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
package org.njmule.core.engine;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.UnaryOperator;
import org.njmule.core.api.EngineConfig;
import org.njmule.core.api.EngineEvent;
import org.njmule.core.api.LogLine;
import org.njmule.core.api.NjMuleEngine;
import org.njmule.core.api.NjMuleInfo;
import org.njmule.core.config.AppDirectories;
import org.njmule.core.config.Preferences;
import org.njmule.core.persist.AtomicFiles;
import org.njmule.core.persist.PreferencesCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Engine implementation behind {@link NjMuleEngine}. */
public final class Engine implements NjMuleEngine {

    private static final Logger LOG = LoggerFactory.getLogger(Engine.class);
    private static final int EVENT_BUFFER = 1_024;

    private final AppDirectories directories;
    private final Path preferencesFile;
    private final EngineLoop loop;
    private final ExecutorService eventExecutor;
    private final SubmissionPublisher<EngineEvent> events;
    private final AtomicBoolean closed = new AtomicBoolean();
    // Written only on the engine thread, read from anywhere.
    private volatile Preferences preferences;

    private Engine(AppDirectories directories, Preferences preferences, EngineLoop loop) {
        this.directories = directories;
        this.preferencesFile = directories.config().resolve(PreferencesCodec.FILE_NAME);
        this.preferences = preferences;
        this.loop = loop;
        this.eventExecutor =
                Executors.newSingleThreadExecutor(
                        Thread.ofPlatform().name("njmule-events").daemon(true).factory());
        this.events = new SubmissionPublisher<>(this.eventExecutor, EVENT_BUFFER);
    }

    public static Engine start(EngineConfig config) throws IOException {
        Objects.requireNonNull(config, "config");
        AppDirectories dirs = config.directories();
        dirs.createAll();
        Path prefsFile = dirs.config().resolve(PreferencesCodec.FILE_NAME);
        AtomicFiles.cleanupInterruptedWrite(prefsFile);
        Preferences prefs = PreferencesCodec.load(prefsFile);
        PreferencesCodec.save(prefsFile, prefs);

        Engine engine = new Engine(dirs, prefs, EngineLoop.start());
        engine.publish(new EngineEvent.Started(NjMuleInfo.version()));
        engine.userLog(
                LogLine.Level.INFO, NjMuleInfo.NAME + " " + NjMuleInfo.version() + " started");
        return engine;
    }

    @Override
    public AppDirectories directories() {
        return this.directories;
    }

    @Override
    public Preferences preferences() {
        return this.preferences;
    }

    @Override
    public CompletableFuture<Preferences> updatePreferences(UnaryOperator<Preferences> change) {
        Objects.requireNonNull(change, "change");
        return this.loop.submit(
                () -> {
                    Preferences current = this.preferences;
                    Preferences next = Objects.requireNonNull(change.apply(current), "preferences");
                    if (!next.equals(current)) {
                        try {
                            PreferencesCodec.save(this.preferencesFile, next);
                        } catch (IOException e) {
                            throw new UncheckedIOException(
                                    "Cannot save " + this.preferencesFile, e);
                        }
                        this.preferences = next;
                        this.publish(new EngineEvent.PreferencesChanged(next));
                    }
                    return next;
                });
    }

    @Override
    public Flow.Publisher<EngineEvent> events() {
        return this.events;
    }

    @Override
    public boolean isRunning() {
        return !this.closed.get() && this.loop.isRunning();
    }

    @Override
    public void close() {
        if (!this.closed.compareAndSet(false, true)) {
            return;
        }
        this.publish(new EngineEvent.Stopping());
        LOG.info("Stopping {}", NjMuleInfo.NAME);
        this.loop.close();
        this.events.close();
        this.eventExecutor.shutdown();
        try {
            if (!this.eventExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                this.eventExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            this.eventExecutor.shutdownNow();
        }
    }

    /** Logs a message both to SLF4J and to the front ends' log pane. */
    void userLog(LogLine.Level level, String message) {
        switch (level) {
            case DEBUG -> LOG.debug("{}", message);
            case INFO, SUCCESS -> LOG.info("{}", message);
            case WARNING -> LOG.warn("{}", message);
            case ERROR -> LOG.error("{}", message);
        }
        this.publish(new EngineEvent.Log(new LogLine(Instant.now(), level, message)));
    }

    // Slow subscribers lose events instead of stalling the engine.
    private void publish(EngineEvent event) {
        if (!this.events.isClosed()) {
            this.events.offer(
                    event,
                    (subscriber, dropped) -> {
                        LOG.debug("Dropped {} for a slow subscriber", dropped);
                        return false;
                    });
        }
    }
}
