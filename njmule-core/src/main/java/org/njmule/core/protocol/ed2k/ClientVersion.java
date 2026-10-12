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
package org.njmule.core.protocol.ed2k;

import org.njmule.core.api.NjMuleInfo;

/** The version numbers njMule announces to servers and peers. */
public final class ClientVersion {

    /** eDonkey protocol version announced by every current client. */
    public static final int EDONKEY = 0x3C;

    /** Compatible-client id of njMule in the version tag; no other client uses it. */
    public static final int NJMULE_COMPAT = 0x4E;

    private ClientVersion() {}

    /** The version tag value: {@code compat<<24 | major<<17 | minor<<10 | patch<<7}. */
    public static int encode(int compat, int major, int minor, int patch) {
        return (compat & 0xFF) << 24
                | Math.min(major, 0x7F) << 17
                | Math.min(minor, 0x7F) << 10
                | Math.min(patch, 0x7) << 7;
    }

    /** njMule's own version tag, from its semantic version. */
    public static int njMule() {
        return fromSemVer(NjMuleInfo.version());
    }

    static int fromSemVer(String version) {
        String[] parts = version.split("[.\\-+]", 4);
        return encode(NJMULE_COMPAT, number(parts, 0), number(parts, 1), number(parts, 2));
    }

    private static int number(String[] parts, int index) {
        try {
            return index < parts.length ? Math.max(0, Integer.parseInt(parts[index])) : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
