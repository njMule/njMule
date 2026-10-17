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
package org.njmule.core.config;

import java.util.Objects;

/**
 * The proxy outgoing TCP connections go through: SOCKS (4 or 5, with optional user name and password) or an HTTP proxy
 * that allows CONNECT. UDP (server pings, global searches, Kad) goes through the UDP relay of a SOCKS5 proxy, and
 * straight to the network with a proxy that has none.
 */
public record ProxySettings(Type type, String host, int port, String user, String password) {

    public enum Type {
        NONE,
        SOCKS,
        HTTP
    }

    /** No proxy. */
    public static final ProxySettings NONE = new ProxySettings(Type.NONE, "", 1080, "", "");

    public ProxySettings {
        Objects.requireNonNull(type, "type");
        host = Objects.requireNonNull(host, "host").strip();
        Objects.requireNonNull(user, "user");
        Objects.requireNonNull(password, "password");
        if (port < 1 || port > Preferences.MAX_PORT) {
            throw new IllegalArgumentException("proxy port must be in [1, 65535]: " + port);
        }
        if (type != Type.NONE && host.isEmpty()) {
            throw new IllegalArgumentException("a proxy needs a host");
        }
    }

    public boolean isEnabled() {
        return this.type != Type.NONE;
    }

    // The password never shows in logs.
    @Override
    public String toString() {
        return "ProxySettings[type="
                + this.type
                + ", host="
                + this.host
                + ", port="
                + this.port
                + ", user="
                + this.user
                + (this.password.isEmpty() ? "" : ", password=***")
                + "]";
    }
}
