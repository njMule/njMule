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

import java.util.Locale;

/** Operating system families with distinct file-placement conventions. */
public enum OsFamily {
    WINDOWS,
    MACOS,
    /** Linux and any other Unix-like system (XDG Base Directory specification). */
    LINUX;

    public static OsFamily fromOsName(String osName) {
        String name = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
        if (name.startsWith("windows")) {
            return WINDOWS;
        }
        if (name.startsWith("mac") || name.startsWith("darwin")) {
            return MACOS;
        }
        return LINUX;
    }

    public static OsFamily current() {
        return fromOsName(System.getProperty("os.name"));
    }
}
