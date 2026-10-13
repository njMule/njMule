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
package org.njmule.core.peer;

import java.util.Locale;
import java.util.OptionalLong;
import org.njmule.core.protocol.ed2k.ClientVersion;
import org.njmule.core.protocol.ed2k.Hello;

/** A readable name and version for a peer's client software, from its hello. */
public final class PeerSoftware {

    private PeerSoftware() {}

    public static String describe(Hello hello) {
        OptionalLong tag = hello.emuleVersion();
        if (tag.isEmpty()) {
            return "eDonkey compatible";
        }
        int value = (int) tag.getAsLong();
        int compat = value >>> 24;
        int major = value >>> 17 & 0x7F;
        int minor = value >>> 10 & 0x7F;
        int update = value >>> 7 & 0x7;
        return switch (compat) {
            // eMule writes its update as a letter: 0.72 + 0 = 0.72a.
            case 0 -> String.format(Locale.ROOT, "eMule v%d.%02d%c", major, minor, 'a' + update);
            case 3 -> String.format(Locale.ROOT, "aMule v%d.%d.%d", major, minor, update);
            case ClientVersion.NJMULE_COMPAT ->
                    String.format(Locale.ROOT, "njMule v%d.%d.%d", major, minor, update);
            default ->
                    String.format(
                            Locale.ROOT,
                            "eMule compatible (client %d) v%d.%d.%d",
                            compat,
                            major,
                            minor,
                            update);
        };
    }
}
