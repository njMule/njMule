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
package org.njmule.webui;

/**
 * The line icons of the pages, the desktop app's own drawings: SVG path data on a 24 by 24 grid, stroked as the style
 * sheet says. Inline in the page, so that they take the colour of their text.
 */
enum Icons {
    TRANSFERS("M7 20V4M3 8l4-4 4 4M17 4v16M13 16l4 4 4-4"),
    SEARCH("M4.5 11a6.5 6.5 0 1 0 13 0a6.5 6.5 0 1 0-13 0M20 20l-4.4-4.4"),
    SHARED("M3 7.5A2.5 2.5 0 0 1 5.5 5H9l2 2h7.5A2.5 2.5 0 0 1 21 9.5v7a2.5 2.5 0 0 1-2.5 2.5h-13A2.5 2.5 0 0 1 3"
            + " 16.5zM12 16.5v-6M9.5 13l2.5-2.5 2.5 2.5"),
    NETWORK("M3.5 12a8.5 8.5 0 1 0 17 0a8.5 8.5 0 1 0-17 0M3.5 12h17M12 3.5c2.6 2.4 3.8 5.2 3.8 8.5s-1.2 6.1-3.8"
            + " 8.5M12 3.5C9.4 5.9 8.2 8.7 8.2 12s1.2 6.1 3.8 8.5"),
    STATISTICS("M4 19.5h16M5 15l4.5-5 4 3 6-7"),
    OPTIONS("M4 7h9M18 7h2M4 17h3M11 17h9M13.2 7a2.3 2.3 0 1 0 4.6 0a2.3 2.3 0 1 0-4.6 0M6.2 17a2.3 2.3 0 1 0 4.6"
            + " 0a2.3 2.3 0 1 0-4.6 0"),
    EXIT("M10 4.5H6A1.5 1.5 0 0 0 4.5 6v12A1.5 1.5 0 0 0 6 19.5h4M10 12h10M16.5 8.5L20 12l-3.5 3.5"),
    SERVER("M5 4.5h14a1.5 1.5 0 0 1 1.5 1.5v3.5a1.5 1.5 0 0 1-1.5 1.5H5a1.5 1.5 0 0 1-1.5-1.5V6A1.5 1.5 0 0 1 5"
            + " 4.5zM5 13h14a1.5 1.5 0 0 1 1.5 1.5V18a1.5 1.5 0 0 1-1.5 1.5H5A1.5 1.5 0 0 1 3.5 18v-3.5A1.5 1.5 0 0 1"
            + " 5 13zM7 7.8h.01M7 16.3h.01"),
    KAD("M9.5 12a2.5 2.5 0 1 0 5 0a2.5 2.5 0 1 0-5 0M3.2 5a1.8 1.8 0 1 0 3.6 0a1.8 1.8 0 1 0-3.6 0M17.2 5a1.8 1.8 0"
            + " 1 0 3.6 0a1.8 1.8 0 1 0-3.6 0M3.2 19a1.8 1.8 0 1 0 3.6 0a1.8 1.8 0 1 0-3.6 0M17.2 19a1.8 1.8 0 1 0"
            + " 3.6 0a1.8 1.8 0 1 0-3.6 0M6.4 6.4l3.8 3.8M17.6 6.4l-3.8 3.8M6.4 17.6l3.8-3.8M17.6 17.6l-3.8-3.8"),
    LOG("M4.5 6.5h15M4.5 10.5h15M4.5 14.5h9M4.5 18.5h12"),
    ARROW_DOWN("M12 5v14M6.5 13.5L12 19l5.5-5.5"),
    ARROW_UP("M12 19V5M6.5 10.5L12 5l5.5 5.5"),
    CHECK_CIRCLE("M3.5 12a8.5 8.5 0 1 0 17 0a8.5 8.5 0 1 0-17 0M8.3 12.4l2.6 2.6 4.9-5.6"),
    WARNING("M10.3 4.6L3.2 17a2 2 0 0 0 1.7 3h14.2a2 2 0 0 0 1.7-3L13.7 4.6a2 2 0 0 0-3.4 0zM12 9.5v4M12 16.8v.2"),
    CLOUD_OFF("M7 18a4.5 4.5 0 0 1-.6-8.96 6 6 0 0 1 1.3-2.6M10.5 4.7A6 6 0 0 1 18 8a4 4 0 0 1 1.6 7.6M4 4l16"
            + " 16M17 18H7"),
    HOURGLASS("M7 4h10M7 20h10M8 4c0 4.5 8 4.5 8 8s-8 3.5-8 8M16 4c0 4.5-8 4.5-8 8s8 3.5 8 8"),
    PORT_TEST("M4 10a11.5 11.5 0 0 1 16 0M7 13.5a7 7 0 0 1 10 0M12 17.5v.2"),
    ADD("M12 5v14M5 12h14");

    private final String path;

    Icons(String path) {
        this.path = path;
    }

    /** The icon as inline SVG, hidden from screen readers: the words beside it say what it means. */
    String svg() {
        return "<svg class=\"icon\" viewBox=\"0 0 24 24\" aria-hidden=\"true\" focusable=\"false\"><path d=\""
                + this.path + "\"/></svg>";
    }
}
