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

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** File names tidied as the network writes them badly. */
class FileNamesTest {

    private String cleaned;

    @ParameterizedTest(name = "{0} without \"{1}\" -> {2}")
    @CsvSource(
            delimiter = ';',
            value = {
                "Big_Buck_Bunny.sponsored.[www.site.com].mkv;sponsored;Big Buck Bunny.mkv",
                "linux%20mint%2022.1.iso;'';Linux Mint 22.1.iso",
                "The.Movie.2010.[1080p].x264.mkv;'';The Movie 2010 [1080P] x264.mkv",
                "a+b=c_d.txt;'';A B C D.txt",
                "100% pure.mp3;'';100% Pure.mp3",
                "What? Why: Who*.avi;'';What Why Who.avi"
            })
    void shouldTidyAName(String name, String words, String expected) {
        whenTheNameIsCleanedUp(name, words);

        thenTheNameIs(expected);
    }

    @ParameterizedTest(name = "{0} without \"{1}\" stays")
    @CsvSource(
            delimiter = ';',
            value = {"sponsored.mkv;sponsored", "www.;www."})
    void shouldKeepANameOfWhichNothingWouldBeLeft(String name, String words) {
        whenTheNameIsCleanedUp(name, words);

        thenTheNameIs(name);
    }

    private void whenTheNameIsCleanedUp(String name, String words) {
        this.cleaned = FileNames.cleanUp(name, words);
    }

    private void thenTheNameIs(String expected) {
        assertThat(this.cleaned).isEqualTo(expected);
    }
}
