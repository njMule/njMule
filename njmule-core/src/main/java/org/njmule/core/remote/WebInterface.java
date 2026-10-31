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
package org.njmule.core.remote;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The web interface's own settings, which its host keeps apart from the engine's preferences and follows at once. The
 * API gives them to any admin, and changes them only for the desktop app's window on njMule's computer.
 */
public interface WebInterface {

    /** The settings as they are kept, without the password hashes. */
    Settings settings();

    /**
     * Keeps the settings with {@code change} applied, and answers them as they are then.
     *
     * @throws IllegalArgumentException when a value is wrong, or the interface would be on without a password; nothing
     *     is kept then
     */
    Settings change(Change change) throws IOException;

    /**
     * The web interface's settings.
     *
     * @param lan whether other computers may connect, rather than this one only
     * @param hosts the names besides localhost under which browsers open the interface
     * @param proxies the reverse proxies trusted to name their client
     * @param api whether the remote-control API answers callers with its token too
     * @param url where a browser opens the interface, while it is on with a password
     */
    record Settings(
            boolean enabled,
            int port,
            boolean lan,
            List<String> hosts,
            List<String> proxies,
            boolean api,
            boolean hasPassword,
            boolean hasGuest,
            Optional<String> url) {
        public Settings {
            hosts = List.copyOf(hosts);
            proxies = List.copyOf(proxies);
            Objects.requireNonNull(url, "url");
        }
    }

    /**
     * What to change; what is empty stays.
     *
     * @param password the new password of the admin
     * @param guestPassword the new password of the guest, or an empty one to let no guest in
     */
    record Change(
            Optional<Boolean> enabled,
            Optional<Integer> port,
            Optional<Boolean> lan,
            Optional<List<String>> hosts,
            Optional<List<String>> proxies,
            Optional<Boolean> api,
            Optional<String> password,
            Optional<String> guestPassword) {
        public Change {
            Objects.requireNonNull(enabled, "enabled");
            Objects.requireNonNull(port, "port");
            Objects.requireNonNull(lan, "lan");
            hosts = hosts.map(List::copyOf);
            proxies = proxies.map(List::copyOf);
            Objects.requireNonNull(api, "api");
            Objects.requireNonNull(password, "password");
            Objects.requireNonNull(guestPassword, "guestPassword");
        }

        @Override
        public String toString() {
            return "Change[enabled=" + this.enabled + ", port=" + this.port + ", lan=" + this.lan + ", hosts="
                    + this.hosts + ", proxies=" + this.proxies + ", api=" + this.api + ", password="
                    + this.password.isPresent() + ", guestPassword=" + this.guestPassword.isPresent() + "]";
        }
    }
}
