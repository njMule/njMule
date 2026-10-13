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
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
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

    /**
     * Adds the servers of a {@code server.met} (e.g. eMule's); completes with how many were new.
     */
    CompletableFuture<Integer> addServers(Path serverMet);

    /**
     * Connects to {@code server} (added to the list when new), or tries the known servers in turn
     * when empty. The future completes once the attempt has started; events tell the outcome.
     */
    CompletableFuture<Void> connect(Optional<ServerAddress> server);

    CompletableFuture<Void> disconnect();

    /** Starts a search; fails for an invalid expression or without a server connection. */
    CompletableFuture<SearchId> search(SearchRequest request);

    /** Asks the server for more results; completes with {@code false} when it has none. */
    CompletableFuture<Boolean> moreResults(SearchId search);

    CompletableFuture<Void> stopSearch(SearchId search);

    /** Says hello to the client at {@code peer} and completes with its answer. */
    CompletableFuture<PeerInfo> hello(ServerAddress peer);

    /**
     * Starts downloading the file of {@code link} into the temp directory; a file already being
     * downloaded gets the link's sources.
     */
    CompletableFuture<DownloadInfo> addDownload(Ed2kLink.FileLink link);

    CompletableFuture<List<DownloadInfo>> downloads();

    /** Completes with {@code false} for an unknown download. */
    CompletableFuture<Boolean> pauseDownload(Hash16 hash);

    CompletableFuture<Boolean> resumeDownload(Hash16 hash);

    /** Stops the download and deletes its data. */
    CompletableFuture<Boolean> cancelDownload(Hash16 hash);

    boolean isRunning();

    /** Stops the engine and saves its state; idempotent. */
    @Override
    void close();
}
