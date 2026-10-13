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

import com.google.common.net.HostAndPort;
import java.util.Objects;

/** A server to connect to, as the user gives it: a host name or IPv4 address and a port. */
public record ServerAddress(String host, int port) {

    public ServerAddress {
        Objects.requireNonNull(host, "host");
        if (host.isBlank() || port < 1 || port > 0xFFFF) {
            throw new IllegalArgumentException("invalid server address " + host + ":" + port);
        }
    }

    /** Parses {@code host:port}. */
    public static ServerAddress parse(String text) {
        HostAndPort parsed = HostAndPort.fromString(text.strip());
        if (!parsed.hasPort()) {
            throw new IllegalArgumentException("missing port in " + text);
        }
        return new ServerAddress(parsed.getHost(), parsed.getPort());
    }

    @Override
    public String toString() {
        return this.host + ":" + this.port;
    }
}
