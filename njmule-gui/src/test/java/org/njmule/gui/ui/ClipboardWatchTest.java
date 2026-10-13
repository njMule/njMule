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

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.njmule.core.api.Ed2kLink;

class ClipboardWatchTest {

    private static final String FILM = "ed2k://|file|my film.mkv|700000000|0123456789ABCDEF0123456789ABCDEF|/";
    private static final String SONG = "ed2k://|file|song.mp3|4000000|00112233445566778899AABBCCDDEEFF|/";

    @Test
    void takesTheFileLinksOfATextThatStartsWithOne() {
        assertThat(ClipboardWatch.fileLinks("  " + FILM + "\r\n" + SONG + " " + FILM + "\n"))
                .extracting(Ed2kLink.FileLink::name)
                .containsExactly("my film.mkv", "song.mp3");
    }

    @Test
    void leavesOtherTexts() {
        assertThat(ClipboardWatch.fileLinks("see " + FILM))
                .as("not at the start")
                .isEmpty();
        assertThat(ClipboardWatch.fileLinks("ed2k://|server|10.0.0.1|4661|/")).isEmpty();
        assertThat(ClipboardWatch.fileLinks(FILM + " ed2k://|file|broken|/"))
                .extracting(Ed2kLink.FileLink::name)
                .containsExactly("my film.mkv");
    }
}
