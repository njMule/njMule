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

import java.util.List;
import java.util.Optional;
import org.njmule.core.config.Preferences;

/** Notifications published by the engine to front ends, in order, on a dedicated thread. */
public sealed interface EngineEvent {

    record Started(String version) implements EngineEvent {}

    record Stopping() implements EngineEvent {}

    record PreferencesChanged(Preferences preferences) implements EngineEvent {}

    record Log(LogLine line) implements EngineEvent {}

    /** A connection attempt to a server started. */
    record ServerConnecting(ServerInfo server) implements EngineEvent {}

    /**
     * Logged in: {@code clientId} is our id on the server, a LowID when firewalled; {@code
     * obfuscated} when the connection is.
     */
    record ServerConnected(ServerInfo server, long clientId, boolean lowId, boolean obfuscated)
            implements EngineEvent {}

    /**
     * The server connection ended or no attempt succeeded; {@code server} is the last one tried.
     */
    record ServerDisconnected(Optional<ServerInfo> server, String reason) implements EngineEvent {}

    /** A line of the connected server's welcome or notice text. */
    record ServerMessage(ServerInfo server, String text) implements EngineEvent {}

    /** New users, files, name or description of a server. */
    record ServerUpdated(ServerInfo server) implements EngineEvent {}

    /** Results new to a search, or updated with more sources. */
    record SearchResults(SearchId search, List<SearchResult> results) implements EngineEvent {
        public SearchResults {
            results = List.copyOf(results);
        }
    }

    /** A search ended; no more results will arrive for it. */
    record SearchEnded(SearchId search, int results) implements EngineEvent {}
}
