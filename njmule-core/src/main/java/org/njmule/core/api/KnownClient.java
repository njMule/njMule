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
 * A client met before, from the history njMule keeps for 90 days: never its address.
 *
 * @param nick the name it gave last
 * @param software the program it said it runs, last
 * @param firstSeen when it was first met, in seconds since the epoch
 * @param lastSeen when it was last met, in seconds since the epoch
 * @param country where it was last met from, as its ISO 3166-1 code in lower case, when the countries were shown
 * @param uploaded the bytes we sent it, of all times
 * @param downloaded the bytes it sent us, of all times
 */
public record KnownClient(
        Hash16 userHash,
        String nick,
        String software,
        long firstSeen,
        long lastSeen,
        Optional<String> country,
        long uploaded,
        long downloaded) {
    public KnownClient {
        Objects.requireNonNull(userHash, "userHash");
        Objects.requireNonNull(nick, "nick");
        Objects.requireNonNull(software, "software");
        Objects.requireNonNull(country, "country");
    }
}
