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

/** What a peer told about itself in its hello, and whether it proved its identity. */
public record PeerInfo(
        String address,
        Hash16 userHash,
        Optional<String> nick,
        String software,
        boolean obfuscated,
        Identification identification) {

    /** The outcome of the secure identification. */
    public enum Identification {
        /** The peer signed our challenge with the key of its user hash. */
        VERIFIED,
        FAILED,
        /** The peer does not take part. */
        NOT_SUPPORTED,
        /** No outcome yet. */
        PENDING
    }

    public PeerInfo {
        Objects.requireNonNull(address, "address");
        Objects.requireNonNull(userHash, "userHash");
        Objects.requireNonNull(nick, "nick");
        Objects.requireNonNull(software, "software");
        Objects.requireNonNull(identification, "identification");
    }
}
