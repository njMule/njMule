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

import java.nio.file.Path;
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

    /** A download was added. */
    record DownloadAdded(DownloadInfo download) implements EngineEvent {}

    /**
     * Data arrived, sources changed or the download was paused or resumed; at most once a second.
     */
    record DownloadProgress(DownloadInfo download) implements EngineEvent {}

    /** The download is verified and moved to {@code file}. */
    record DownloadCompleted(DownloadInfo download, Path file) implements EngineEvent {}

    /** The download stopped on an error, such as a full disk. */
    record DownloadFailed(DownloadInfo download, String reason) implements EngineEvent {}

    /** The download was cancelled and its data deleted. */
    record DownloadRemoved(Hash16 hash) implements EngineEvent {}

    /** A client sent us a message; {@code address} is its IP and listening port. */
    record MessageReceived(String address, Hash16 userHash, Optional<String> nick, String text)
            implements EngineEvent {}

    /** A client wants a CAPTCHA solved before it shows our messages; the picture is a BMP. */
    // equals, hashCode and toString below compare and show the bytes
    @SuppressWarnings("ArrayRecordComponent")
    record CaptchaRequested(String address, Hash16 userHash, byte[] image) implements EngineEvent {
        public CaptchaRequested {
            image = image.clone();
        }

        @Override
        public byte[] image() {
            return this.image.clone();
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof CaptchaRequested c
                    && c.address.equals(this.address)
                    && c.userHash.equals(this.userHash)
                    && java.util.Arrays.equals(c.image, this.image);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(
                    this.address, this.userHash, java.util.Arrays.hashCode(this.image));
        }

        @Override
        public String toString() {
            return "CaptchaRequested[" + this.address + ", " + this.image.length + " bytes]";
        }
    }

    /** A client told whether our CAPTCHA solution was right. */
    record CaptchaAnswered(String address, boolean solved) implements EngineEvent {}

    /** Messages to a client could not be delivered. */
    record MessageFailed(String address, String reason) implements EngineEvent {}
}
