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
package org.njmule.app;

import java.util.ArrayList;
import java.util.List;

/** A Windows command line, for the system to start a program with these parts. */
public final class WindowsCommandLine {

    private WindowsCommandLine() {}

    /** The program and its arguments, each quoted as programs on Windows read their arguments. */
    public static String of(List<String> parts) {
        List<String> quoted = new ArrayList<>();
        for (String part : parts) {
            // A backslash before the closing quote would escape it.
            int trailing = 0;
            while (trailing < part.length() && part.charAt(part.length() - 1 - trailing) == '\\') {
                trailing++;
            }
            quoted.add("\"" + part.replace("\"", "\\\"") + "\\".repeat(trailing) + "\"");
        }
        return String.join(" ", quoted);
    }
}
