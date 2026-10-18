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
package org.njmule.gui.infra;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Locale;

/** Opens a web page in the system's browser. */
public final class WebPages {

    private WebPages() {}

    /**
     * Opens {@code page}, an https address; the browser runs on its own.
     *
     * @throws IllegalArgumentException for any other kind of address
     */
    public static void open(URI page) throws IOException {
        Folders.process(command(System.getProperty("os.name", ""), page)).start();
    }

    // The desktop's own opener, given the address as one argument that no shell parses.
    static List<String> command(String os, URI page) {
        if (!"https".equalsIgnoreCase(page.getScheme()) || page.getHost() == null) {
            throw new IllegalArgumentException("not an https address: " + page);
        }
        String name = os.toLowerCase(Locale.ROOT);
        if (name.startsWith("windows")) {
            return List.of("explorer.exe", page.toASCIIString());
        }
        if (name.startsWith("mac")) {
            return List.of("open", page.toASCIIString());
        }
        return List.of("xdg-open", page.toASCIIString());
    }
}
