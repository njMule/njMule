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
 * A contact of our Kad routing table.
 *
 * @param type how far it is trusted, from 0 (answered lately) to 4 (not heard from)
 * @param distance the XOR of its id and ours, in hex: the smaller, the closer
 * @param country the contact's country as its ISO 3166-1 code in lower case, when the countries are shown and known
 */
public record KadContactInfo(
        String id, String address, int type, String distance, int version, boolean verified, Optional<String> country) {

    public KadContactInfo {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(address, "address");
        Objects.requireNonNull(distance, "distance");
        Objects.requireNonNull(country, "country");
    }

    /** A contact whose country is not known. */
    public KadContactInfo(String id, String address, int type, String distance, int version, boolean verified) {
        this(id, address, type, distance, version, verified, Optional.empty());
    }

    /** This contact in {@code where}. */
    public KadContactInfo withCountry(Optional<String> where) {
        return new KadContactInfo(this.id, this.address, this.type, this.distance, this.version, this.verified, where);
    }
}
