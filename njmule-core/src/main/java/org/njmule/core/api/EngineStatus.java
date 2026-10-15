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

import java.util.Objects;
import java.util.Optional;

/**
 * The state of the engine at a glance: connections, rates and counts.
 *
 * @param server the server we are logged in to
 * @param clientId our id on that server, 0 when not logged in
 * @param downloaded payload bytes received in this session
 * @param uploaded payload bytes sent in this session
 * @param totalDownloaded payload bytes received in all sessions, this one included
 * @param sessions the sessions so far, this one included
 * @param publicIp our address as the server sees it, empty when unknown
 * @param tcpPort the port other clients reach us on
 * @param relatedSearch whether the server we are on finds the files related to others
 * @param missingInterface the network interface the preferences bind to while it is not there, which keeps us offline
 */
public record EngineStatus(
        Optional<ServerInfo> server,
        long clientId,
        boolean lowId,
        boolean obfuscated,
        Optional<KadInfo> kad,
        long downloadBytesPerSecond,
        long uploadBytesPerSecond,
        long downloaded,
        long uploaded,
        int downloads,
        int uploads,
        int queued,
        int sharedFiles,
        long totalDownloaded,
        long totalUploaded,
        long sessions,
        long sessionSeconds,
        long totalSeconds,
        String publicIp,
        int tcpPort,
        boolean relatedSearch,
        Optional<String> missingInterface) {

    public EngineStatus {
        Objects.requireNonNull(server, "server");
        Objects.requireNonNull(kad, "kad");
        Objects.requireNonNull(publicIp, "publicIp");
        Objects.requireNonNull(missingInterface, "missingInterface");
    }

    /** A status with every network interface there is. */
    public EngineStatus(
            Optional<ServerInfo> server,
            long clientId,
            boolean lowId,
            boolean obfuscated,
            Optional<KadInfo> kad,
            long downloadBytesPerSecond,
            long uploadBytesPerSecond,
            long downloaded,
            long uploaded,
            int downloads,
            int uploads,
            int queued,
            int sharedFiles,
            long totalDownloaded,
            long totalUploaded,
            long sessions,
            long sessionSeconds,
            long totalSeconds,
            String publicIp,
            int tcpPort,
            boolean relatedSearch) {
        this(
                server,
                clientId,
                lowId,
                obfuscated,
                kad,
                downloadBytesPerSecond,
                uploadBytesPerSecond,
                downloaded,
                uploaded,
                downloads,
                uploads,
                queued,
                sharedFiles,
                totalDownloaded,
                totalUploaded,
                sessions,
                sessionSeconds,
                totalSeconds,
                publicIp,
                tcpPort,
                relatedSearch,
                Optional.empty());
    }
}
