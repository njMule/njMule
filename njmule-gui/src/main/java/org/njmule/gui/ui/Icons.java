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
package org.njmule.gui.ui;

/**
 * The line icons of the interface: SVG path data on a 24 by 24 grid, drawn with a round 1.75 stroke (PLAN §7). Our own
 * drawings, so that every icon shares one stroke and one grid; a directional icon points the way text runs.
 */
public enum Icons {
    ADD("M12 5v14M5 12h14"),
    ALIGN_BOTTOM("M12 4v11M7.5 10.5L12 15l4.5-4.5M5 20h14"),
    ARROW_BACK("M19 12H5M11 6l-6 6 6 6", Kind.DIRECTIONAL),
    ARROW_DOWN("M12 5v14M6.5 13.5L12 19l5.5-5.5"),
    ARROW_FORWARD("M5 12h14M13 6l6 6-6 6", Kind.DIRECTIONAL),
    ARROW_UP("M12 19V5M6.5 10.5L12 5l5.5 5.5"),
    BLOCK(Outline.CIRCLE + "M6 6l12 12"),
    CATEGORY("M12 3.5l4.5 7.5h-9zM13.5 16.5a3.5 3.5 0 1 0 7 0a3.5 3.5 0 1 0-7 0M4 13h6.5v6.5H4z"),
    CHATS("M14.5 4.5h-9A1.5 1.5 0 0 0 4 6v6.5l3-2.5h7.5A1.5 1.5 0 0 0 16 8.5V6a1.5 1.5 0 0 0-1.5-1.5z"
            + "M19 9.5a1 1 0 0 1 1 1V18l-3-2.5h-7a1 1 0 0 1-1-1V13"),
    CHECK("M6 12.5l4 4L18 8"),
    CHECK_CIRCLE(Outline.CIRCLE + "M8.3 12.4l2.6 2.6 4.9-5.6"),
    CHEVRON_DOWN("M7 10l5 5 5-5"),
    CHEVRON_RIGHT("M10 7l5 5-5 5", Kind.DIRECTIONAL),
    CLEAN("M14.5 3.5l-3 7.5M7.5 11h9l1.5 9.5h-12zM10.5 15v5.5M13.5 15v5.5"),
    CLEAR_ALL("M4 7h10M4 12h10M4 17h7M15 14.5l5 5M20 14.5l-5 5", Kind.DIRECTIONAL),
    CLOCK(Outline.CIRCLE + "M12 7.5V12l3 2"),
    CLOSE("M7 7l10 10M17 7L7 17"),
    CLOUD_OFF("M7 18a4.5 4.5 0 0 1-.6-8.96 6 6 0 0 1 1.3-2.6M10.5 4.7A6 6 0 0 1 18 8a4 4 0 0 1 1.6 7.6M4 4l16 16"
            + "M17 18H7"),
    CODE("M8.5 7L3.5 12l5 5M15.5 7l5 5-5 5"),
    COMMENT("M20 15.5a1.5 1.5 0 0 1-1.5 1.5H8l-4 3.5v-15A1.5 1.5 0 0 1 5.5 4h13A1.5 1.5 0 0 1 20 5.5zM8 9h8M8 12.5h5"),
    COPY("M10.5 8.5h7a2 2 0 0 1 2 2v7a2 2 0 0 1-2 2h-7a2 2 0 0 1-2-2v-7a2 2 0 0 1 2-2z"
            + "M15.5 8.5V6a1.5 1.5 0 0 0-1.5-1.5H6A1.5 1.5 0 0 0 4.5 6v8A1.5 1.5 0 0 0 6 15.5h2.5"),
    DELETE("M4.5 7h15M9.5 7V4.5h5V7M6.5 7l1 12.5h9l1-12.5M10 11v5M14 11v5"),
    DELETE_ALL("M3 7h11M5 7l.9 12h7.2l.9-12M7.5 7V4.5h4V7M16.5 9H21M16.5 13H20M16.5 17H19"),
    DONE_ALL("M2.5 12.5l4 4L15 8M11.5 16.5L20 8"),
    /** A filled disc, for state marks. */
    DOT("M7 12a5 5 0 1 0 10 0a5 5 0 1 0-10 0", Kind.FILLED),
    DOWNLOAD("M7 18a4.5 4.5 0 0 1-.6-8.96A6 6 0 0 1 18 8a4 4 0 0 1 .5 7.97M12 11.5v8M9 16.5l3 3 3-3"),
    EDIT("M4 20h4L19 9a2.1 2.1 0 0 0-3-3L5 17zM14 7l3 3"),
    ERROR(Outline.CIRCLE + "M12 7.5v5.5M12 16.3v.2"),
    EXIT("M10 4.5H6A1.5 1.5 0 0 0 4.5 6v12A1.5 1.5 0 0 0 6 19.5h4M10 12h10M16.5 8.5L20 12l-3.5 3.5", Kind.DIRECTIONAL),
    /** A file of no kind the extension tells. */
    FILE(Outline.SHEET + "M13.5 3.5v4h4"),
    FILE_APP(Outline.FRAME + "M3.5 9h17M7 6.8h.01M9.5 6.8h.01"),
    FILE_ARCHIVE("M6.5 3.5h11a2 2 0 0 1 2 2v13a2 2 0 0 1-2 2h-11a2 2 0 0 1-2-2v-13a2 2 0 0 1 2-2z"
            + "M12 3.5v2M12 7.5v2M12 11.5v2M11 15h2a.5.5 0 0 1 .5.5v1.5a.5.5 0 0 1-.5.5h-2a.5.5 0 0 1-.5-.5v-1.5"
            + "a.5.5 0 0 1 .5-.5z"),
    FILE_AUDIO("M9 17.5V6.5l10-2v11M4 17.5a2.5 2.5 0 1 0 5 0a2.5 2.5 0 1 0-5 0M14 15.5a2.5 2.5 0 1 0 5 0"
            + "a2.5 2.5 0 1 0-5 0"),
    FILE_DISC(Outline.CIRCLE + "M9.5 12a2.5 2.5 0 1 0 5 0a2.5 2.5 0 1 0-5 0"),
    FILE_DOCUMENT(Outline.SHEET + "M13.5 3.5v4h4M9 12h6M9 15.5h6"),
    FILE_IMAGE(Outline.FRAME + "M3.5 16l5-5 4 4 2.5-2.5 5.5 5.5M14 8.5a1.5 1.5 0 1 0 3 0a1.5 1.5 0 1 0-3 0"),
    FILE_VIDEO("M6 5.5h12a2.5 2.5 0 0 1 2.5 2.5v8a2.5 2.5 0 0 1-2.5 2.5H6A2.5 2.5 0 0 1 3.5 16V8A2.5 2.5 0 0 1 6 5.5z"
            + "M10.5 9.5v5l4.2-2.5z"),
    FILTER("M4 5.5h16l-6.2 7.6v5.4l-3.6 1.8v-7.2z"),
    FOLDER_ADD(Outline.FOLDER + "M12 10.5v5M9.5 13h5"),
    FOLDER_OPEN("M3.5 8V6.5A1.5 1.5 0 0 1 5 5h4l2 2h6.5A1.5 1.5 0 0 1 19 8.5V9"
            + "M3.5 9h17l-1.6 8.6a1.8 1.8 0 0 1-1.8 1.4H5.3a1.8 1.8 0 0 1-1.8-1.8z"),
    HELP(Outline.CIRCLE + "M9.6 9.6a2.5 2.5 0 1 1 3.6 2.2c-.7.4-1.2 1-1.2 1.8v.4M12 16.9v.2"),
    HOURGLASS("M7 4h10M7 20h10M8 4c0 4.5 8 4.5 8 8s-8 3.5-8 8M16 4c0 4.5-8 4.5-8 8s8 3.5 8 8"),
    IMPORT(
            "M9 7V5.5A1.5 1.5 0 0 1 10.5 4h8A1.5 1.5 0 0 1 20 5.5v13a1.5 1.5 0 0 1-1.5 1.5h-8A1.5 1.5 0 0 1 9 18.5V17"
                    + "M3.5 12h11M11 8.5l3.5 3.5-3.5 3.5",
            Kind.DIRECTIONAL),
    INFO(Outline.CIRCLE + "M12 11v5.5M12 7.8v.2"),
    KAD("M9.5 12a2.5 2.5 0 1 0 5 0a2.5 2.5 0 1 0-5 0M3.2 5a1.8 1.8 0 1 0 3.6 0a1.8 1.8 0 1 0-3.6 0"
            + "M17.2 5a1.8 1.8 0 1 0 3.6 0a1.8 1.8 0 1 0-3.6 0M3.2 19a1.8 1.8 0 1 0 3.6 0a1.8 1.8 0 1 0-3.6 0"
            + "M17.2 19a1.8 1.8 0 1 0 3.6 0a1.8 1.8 0 1 0-3.6 0"
            + "M6.4 6.4l3.8 3.8M17.6 6.4l-3.8 3.8M6.4 17.6l3.8-3.8M17.6 17.6l-3.8-3.8"),
    KEYBOARD("M4.5 6h15a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2h-15a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2z"
            + "M6 10h.01M9.5 10h.01M13 10h.01M16.5 10h.01M7.5 14h9"),
    LABEL("M3.5 12.2V4.5a1 1 0 0 1 1-1h7.7l8.3 8.3a1 1 0 0 1 0 1.4l-7.1 7.1a1 1 0 0 1-1.4 0zM8 8h.01"),
    LINK("M10 14a4 4 0 0 0 5.66 0l3-3a4 4 0 0 0-5.66-5.66l-1 1M14 10a4 4 0 0 0-5.66 0l-3 3a4 4 0 0 0 5.66 5.66l1-1"),
    LINK_OFF("M10 14a4 4 0 0 0 5.66 0l3-3a4 4 0 0 0-3.2-6.8M14 10a4 4 0 0 0-5.66 0l-3 3a4 4 0 0 0 3.2 6.8M4 4l16 16"),
    LIST_ADD("M4 7h11M4 12h11M4 17h6M18 14v6M15 17h6", Kind.DIRECTIONAL),
    MESSAGE("M20.5 12a8 8 0 0 1-11.7 7.1L4 20l1-4.3A8 8 0 1 1 20.5 12zM8.5 11h7M8.5 14h4"),
    /** Three dots: more of the same kind, in a menu. */
    MORE(
            "M4 12a1.5 1.5 0 1 0 3 0a1.5 1.5 0 1 0-3 0M10.5 12a1.5 1.5 0 1 0 3 0a1.5 1.5 0 1 0-3 0"
                    + "M17 12a1.5 1.5 0 1 0 3 0a1.5 1.5 0 1 0-3 0",
            Kind.FILLED),
    NETWORK(Outline.CIRCLE + "M3.5 12h17M12 3.5c2.6 2.4 3.8 5.2 3.8 8.5s-1.2 6.1-3.8 8.5"
            + "M12 3.5C9.4 5.9 8.2 8.7 8.2 12s1.2 6.1 3.8 8.5"),
    NO_SOURCES("M4.5 11a6.5 6.5 0 1 0 13 0a6.5 6.5 0 1 0-13 0M20 20l-4.4-4.4M8 8l6 6"),
    OPEN_EXTERNAL("M14 4.5h5.5V10M19.5 4.5l-8.5 8.5M17.5 14v4.5a1 1 0 0 1-1 1h-11a1 1 0 0 1-1-1v-11a1 1 0 0 1 1-1H10"),
    OPTIONS("M4 7h9M18 7h2M4 17h3M11 17h9M13.2 7a2.3 2.3 0 1 0 4.6 0a2.3 2.3 0 1 0-4.6 0"
            + "M6.2 17a2.3 2.3 0 1 0 4.6 0a2.3 2.3 0 1 0-4.6 0"),
    PASTE("M9 5H7a1.5 1.5 0 0 0-1.5 1.5v12A1.5 1.5 0 0 0 7 20h10a1.5 1.5 0 0 0 1.5-1.5v-12A1.5 1.5 0 0 0 17 5h-2"
            + "M9 4h6v2.5H9zM9 11h6M9 14.5h4"),
    PAUSE("M9 6v12M15 6v12"),
    PERSON_ADD("M6.5 8.5a3.5 3.5 0 1 0 7 0a3.5 3.5 0 1 0-7 0M3.5 19.5a6.5 6.5 0 0 1 13 0M18.5 8v6M15.5 11h6"),
    PLAY("M8 5.5l10.5 6.5L8 18.5z"),
    PORT_TEST("M4 10a11.5 11.5 0 0 1 16 0M7 13.5a7 7 0 0 1 10 0M12 17.5v.2"),
    POWER("M12 3.5v8M7.2 6.5a7.5 7.5 0 1 0 9.6 0"),
    PREVIEW("M2.5 12S6 5.5 12 5.5 21.5 12 21.5 12 18 18.5 12 18.5 2.5 12 2.5 12z"
            + "M9 12a3 3 0 1 0 6 0a3 3 0 1 0-6 0"),
    PRIORITY("M4 7h16M4 12h11M4 17h6", Kind.DIRECTIONAL),
    QUEUE("M12 4l8.5 4.5L12 13 3.5 8.5zM3.5 12.5L12 17l8.5-4.5M3.5 16.5L12 21l8.5-4.5"),
    REFRESH("M19.5 11.5a7.5 7.5 0 1 0-2.2 5.3M19.5 5v6.5H13"),
    REMOVE_CIRCLE(Outline.CIRCLE + "M8 12h8"),
    SAVE("M5 4h11l4 4v11a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V5a1 1 0 0 1 1-1zM8 4v5h7V4M8 20v-6h8v6"),
    SEARCH("M4.5 11a6.5 6.5 0 1 0 13 0a6.5 6.5 0 1 0-13 0M20 20l-4.4-4.4"),
    SELECT_ALL("M4 7V5a1 1 0 0 1 1-1h2M10 4h4M17 4h2a1 1 0 0 1 1 1v2M20 10v4M20 17v2a1 1 0 0 1-1 1h-2M14 20h-4"
            + "M7 20H5a1 1 0 0 1-1-1v-2M4 14v-4M8.5 12l2.5 2.5 4.5-5"),
    SEND("M4.5 12L20 4.5l-4 15-3.6-5.9zM12.4 13.6L20 4.5", Kind.DIRECTIONAL),
    SERVER("M5 4.5h14a1.5 1.5 0 0 1 1.5 1.5v3.5a1.5 1.5 0 0 1-1.5 1.5H5a1.5 1.5 0 0 1-1.5-1.5V6A1.5 1.5 0 0 1 5 4.5z"
            + "M5 13h14a1.5 1.5 0 0 1 1.5 1.5V18a1.5 1.5 0 0 1-1.5 1.5H5A1.5 1.5 0 0 1 3.5 18v-3.5A1.5 1.5 0 0 1 5 13z"
            + "M7 7.8h.01M7 16.3h.01"),
    SHARED("M3 7.5A2.5 2.5 0 0 1 5.5 5H9l2 2h7.5A2.5 2.5 0 0 1 21 9.5v7a2.5 2.5 0 0 1-2.5 2.5h-13A2.5 2.5 0 0 1 3 16.5z"
            + "M12 16.5v-6M9.5 13l2.5-2.5 2.5 2.5"),
    SHIELD("M12 3.5l7.5 2.8v5.5c0 4.3-3.1 7.6-7.5 8.7-4.4-1.1-7.5-4.4-7.5-8.7V6.3z"),
    SIDEBAR(
            "M6 4.5h12a2.5 2.5 0 0 1 2.5 2.5v10a2.5 2.5 0 0 1-2.5 2.5H6A2.5 2.5 0 0 1 3.5 17V7A2.5 2.5 0 0 1 6 4.5z"
                    + "M9.5 4.5v15",
            Kind.DIRECTIONAL),
    SKIP("M6 6l9 6-9 6zM18 6v12"),
    SLOW("M4 17a8 8 0 1 1 16 0M12 17l-4.5-4"),
    SPAM("M8.3 3.5h7.4l4.8 4.8v7.4l-4.8 4.8H8.3l-4.8-4.8V8.3zM12 8v5M12 16.2v.1"),
    SPEED("M4 17a8 8 0 1 1 16 0M12 17l4-5.5"),
    STAR("M12 4l2.4 5 5.4.7-4 3.7 1 5.4L12 16.2l-4.8 2.6 1-5.4-4-3.7 5.4-.7z"),
    STATISTICS("M4 19.5h16M5 15l4.5-5 4 3 6-7"),
    STOP("M8 6.5h8a1.5 1.5 0 0 1 1.5 1.5v8a1.5 1.5 0 0 1-1.5 1.5H8a1.5 1.5 0 0 1-1.5-1.5V8A1.5 1.5 0 0 1 8 6.5z"),
    STORAGE("M4 6.5C4 5 7.6 4 12 4s8 1 8 2.5S16.4 9 12 9 4 8 4 6.5zM4 6.5v11C4 19 7.6 20 12 20s8-1 8-2.5v-11"
            + "M4 12c0 1.5 3.6 2.5 8 2.5s8-1 8-2.5"),
    SWAP("M4 8h14M14 4l4 4-4 4M20 16H6M10 12l-4 4 4 4"),
    THEME(Outline.CIRCLE + "M12 3.5v17M12 7h4.8M12 10.5h7.2M12 14h7M12 17.5h4.5"),
    TOOLS("M15 4.5a4.5 4.5 0 0 0-4.2 6.1L4.5 16.9a1.9 1.9 0 0 0 2.7 2.7l6.3-6.3A4.5 4.5 0 0 0 19.5 9"
            + "l-2.6 2.6-2.4-.6-.6-2.4L16.5 6A4.5 4.5 0 0 0 15 4.5z"),
    TRANSFERS("M7 20V4M3 8l4-4 4 4M17 4v16M13 16l4 4 4-4"),
    UNLOCK("M7 10.5h10a2 2 0 0 1 2 2V18a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2v-5.5a2 2 0 0 1 2-2z"
            + "M8.5 10.5V8a3.5 3.5 0 0 1 6.8-1.2"),
    VOLUME("M4 9.5h3l4.5-4v13L7 14.5H4zM15 9a4 4 0 0 1 0 6M17.5 6.5a7.5 7.5 0 0 1 0 11"),
    WARNING("M10.3 4.6L3.2 17a2 2 0 0 0 1.7 3h14.2a2 2 0 0 0 1.7-3L13.7 4.6a2 2 0 0 0-3.4 0zM12 9.5v4M12 16.8v.2"),
    WIZARD("M4.5 19.5l10-10M13 6l1-2.5L15 6l2.5 1L15 8l-1 2.5L13 8l-2.5-1z"
            + "M18.5 13.5l.7-1.5.8 1.5 1.5.7-1.5.8-.8 1.5-.7-1.5-1.5-.8z");

    /** How an icon is drawn. */
    enum Kind {
        LINE,
        /** Mirrored in a right-to-left layout. */
        DIRECTIONAL,
        /** Filled instead of stroked. */
        FILLED
    }

    private final String path;
    private final Kind kind;

    Icons(String path) {
        this(path, Kind.LINE);
    }

    Icons(String path, Kind kind) {
        this.path = path;
        this.kind = kind;
    }

    /** The SVG path data, on a 24 by 24 grid. */
    public String path() {
        return this.path;
    }

    /** Whether the icon points the way text runs, so a right-to-left layout mirrors it. */
    public boolean directional() {
        return this.kind == Kind.DIRECTIONAL;
    }

    /** Whether the icon is a filled shape rather than a line. */
    public boolean filled() {
        return this.kind == Kind.FILLED;
    }

    /** Outlines that several icons start from. */
    private static final class Outline {
        static final String CIRCLE = "M3.5 12a8.5 8.5 0 1 0 17 0a8.5 8.5 0 1 0-17 0";
        static final String FOLDER =
                "M3.5 7.5A2 2 0 0 1 5.5 5.5H9l2 2h7.5a2 2 0 0 1 2 2v7a2 2 0 0 1-2 2h-13a2 2 0 0 1-2-2z";
        static final String SHEET = "M6.5 3.5h7l4 4v13h-11z";
        static final String FRAME =
                "M6 4.5h12a2.5 2.5 0 0 1 2.5 2.5v10a2.5 2.5 0 0 1-2.5 2.5H6A2.5 2.5 0 0 1 3.5 17V7A2.5 2.5 0 0 1 6 4.5z";

        private Outline() {}
    }
}
