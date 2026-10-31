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
 * What front ends show about a server; {@code address} is {@code ip:port}, zero or empty values are unknown.
 *
 * @param failed connection attempts that failed in a row
 * @param ping milliseconds the server took to answer a status request
 * @param softFiles the number of files a client should offer at most
 * @param hardFiles the number of files beyond which the server drops a client
 * @param isStatic kept in the static list: never dropped as dead, and written to {@code staticservers.dat}
 * @param country the server's country as its ISO 3166-1 code in lower case, when the countries are shown and known
 */
public record ServerInfo(
        String address,
        String name,
        String description,
        long users,
        long files,
        long maxUsers,
        int failed,
        Priority priority,
        int ping,
        long softFiles,
        long hardFiles,
        long lowIdUsers,
        String version,
        boolean supportsObfuscation,
        boolean isStatic,
        Optional<String> country) {

    /** The order servers are tried in. */
    public enum Priority {
        HIGH,
        NORMAL,
        LOW
    }

    public ServerInfo {
        Objects.requireNonNull(address, "address");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(priority, "priority");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(country, "country");
    }

    /** A server whose country is not known. */
    public ServerInfo(
            String address,
            String name,
            String description,
            long users,
            long files,
            long maxUsers,
            int failed,
            Priority priority,
            int ping,
            long softFiles,
            long hardFiles,
            long lowIdUsers,
            String version,
            boolean supportsObfuscation,
            boolean isStatic) {
        this(
                address,
                name,
                description,
                users,
                files,
                maxUsers,
                failed,
                priority,
                ping,
                softFiles,
                hardFiles,
                lowIdUsers,
                version,
                supportsObfuscation,
                isStatic,
                Optional.empty());
    }

    /** This server in {@code where}. */
    public ServerInfo withCountry(Optional<String> where) {
        return new ServerInfo(
                this.address,
                this.name,
                this.description,
                this.users,
                this.files,
                this.maxUsers,
                this.failed,
                this.priority,
                this.ping,
                this.softFiles,
                this.hardFiles,
                this.lowIdUsers,
                this.version,
                this.supportsObfuscation,
                this.isStatic,
                where);
    }
}
