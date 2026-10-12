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
package org.njmule.core.api;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.function.UnaryOperator;
import org.njmule.core.config.AppDirectories;
import org.njmule.core.config.Preferences;
import org.njmule.core.engine.Engine;

/**
 * Facade of the engine for every front end. Methods are thread-safe; commands run on the engine
 * thread and complete their futures there, events are delivered on a dedicated thread.
 */
public interface NjMuleEngine extends AutoCloseable {

    /** Creates the directories, loads the preferences and starts the engine. */
    static NjMuleEngine start(EngineConfig config) throws IOException {
        return Engine.start(config);
    }

    AppDirectories directories();

    /** Latest committed preferences; never blocks. */
    Preferences preferences();

    /** Applies, validates and persists a change; the future fails if the result is invalid. */
    CompletableFuture<Preferences> updatePreferences(UnaryOperator<Preferences> change);

    Flow.Publisher<EngineEvent> events();

    boolean isRunning();

    /** Stops the engine and saves its state; idempotent. */
    @Override
    void close();
}
