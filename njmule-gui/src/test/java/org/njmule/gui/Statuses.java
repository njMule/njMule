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
package org.njmule.gui;

import java.util.Optional;
import org.njmule.core.api.EngineStatus;
import org.njmule.core.api.KadInfo;
import org.njmule.core.api.ServerInfo;

/** Engine states and servers the real engine cannot be brought to in a test. */
public final class Statuses {

    private Statuses() {}

    /** A state with no traffic: on {@code server} as {@code clientId}, with {@code kad}. */
    public static EngineStatus of(
            Optional<ServerInfo> server, long clientId, boolean lowId, Optional<KadInfo> kad, boolean relatedSearch) {
        return new EngineStatus(
                server, clientId, lowId, false, kad, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, "", 0, relatedSearch);
    }

    /** Kad running, connected or not, behind a firewall or not, at {@code ip} (empty when unknown). */
    public static KadInfo kad(boolean connected, boolean firewalled, String ip) {
        return new KadInfo(connected, 10, 5, firewalled, "00112233445566778899AABBCCDDEEFF", ip, 4672);
    }

    /** A server with users and files, static or not. */
    public static ServerInfo server(String address, String name, boolean isStatic) {
        return new ServerInfo(
                address,
                name,
                name + " description",
                1200,
                34_000,
                5000,
                0,
                ServerInfo.Priority.NORMAL,
                40,
                1000,
                2000,
                300,
                "17.15",
                true,
                isStatic);
    }
}
