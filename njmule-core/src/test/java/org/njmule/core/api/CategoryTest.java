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
import static org.assertj.core.api.Assertions.catchThrowable;

import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class CategoryTest {

    private Category category;
    private boolean matches;
    private Throwable exception;

    @ParameterizedTest
    @CsvSource(delimiter = ';', textBlock = """
            *.mkv | film?.avi; Holiday.MKV;              true
            *.mkv | film?.avi; film1.avi;                true
            *.mkv | film?.avi; film12.avi;               false
            *.mkv | film?.avi; mkv.txt;                  false
            ubuntu|debian;     Ubuntu 24.04 desktop.iso; true
            ubuntu|debian;     my debian.iso;            true
            '';                anything;                 false
            """)
    void shouldMatchWholeNamesWithWildcardsAndPartsWithout(String patterns, String name, boolean expected) {
        givenACategoryWithThePatterns(patterns);

        whenTheNameIsMatched(name);

        thenTheNameMatches(expected);
    }

    @ParameterizedTest
    @CsvSource(delimiter = ';', textBlock = """
            .*\\.(avi|mkv)$;    Holiday.MKV;    true
            .*\\.(avi|mkv)$;    film.avi;       true
            .*\\.(avi|mkv)$;    film.avi.txt;   false
            ubuntu|debian;      Debian;         true
            ^\\d{4} .*\\.flac$; 2024 live.flac; true
            '';                 '';             false
            """)
    void shouldMatchTheWholeNameWithOneRegularExpression(String expression, String name, boolean expected) {
        givenACategoryWithTheRegularExpression(expression);

        whenTheNameIsMatched(name);

        thenTheNameMatches(expected);
    }

    @Test
    void shouldNotMatchAPartOfTheNameWithARegularExpression() {
        givenACategoryWithTheRegularExpression("ubuntu|debian");

        whenTheNameIsMatched("Ubuntu 24.04 desktop.iso");

        thenOnlyTheWholeNameCounts();
    }

    @Test
    void shouldRefuseAnIdBelowOne() {
        whenACategoryIsMade(0, "x", Optional.empty(), 0, "", false);

        thenExceptionOccurred(IllegalArgumentException.class);
    }

    @Test
    void shouldRefuseABlankName() {
        whenACategoryIsMade(1, " ", Optional.empty(), 0, "", false);

        thenExceptionOccurred(IllegalArgumentException.class);
    }

    @Test
    void shouldRefuseARelativeIncomingFolder() {
        whenACategoryIsMade(1, "x", Optional.of(Path.of("relative")), 0, "", false);

        thenExceptionOccurred(IllegalArgumentException.class);
    }

    @Test
    void shouldRefuseAColourOutOfRange() {
        whenACategoryIsMade(1, "x", Optional.empty(), 9, "", false);

        thenExceptionOccurred(IllegalArgumentException.class);
    }

    @Test
    void shouldRefuseAnInvalidRegularExpression() {
        whenACategoryIsMade(1, "Films", Optional.empty(), 0, "(unclosed", true);

        thenExceptionOccurred(IllegalArgumentException.class);
    }

    private void givenACategoryWithThePatterns(String patterns) {
        this.category = category(patterns, false);
    }

    private void givenACategoryWithTheRegularExpression(String expression) {
        this.category = category(expression, true);
    }

    private void whenTheNameIsMatched(String name) {
        this.matches = this.category.matches(name);
    }

    private void whenACategoryIsMade(
            int id, String name, Optional<Path> incoming, int color, String patterns, boolean regex) {
        this.exception = catchThrowable(
                () -> new Category(id, name, incoming, "", color, DownloadPriority.AUTO, patterns, regex));
    }

    private void thenTheNameMatches(boolean expected) {
        assertThat(this.matches).isEqualTo(expected);
    }

    private void thenOnlyTheWholeNameCounts() {
        assertThat(this.matches).as("the whole name, not a part of it").isFalse();
    }

    private void thenExceptionOccurred(Class<? extends Throwable> type) {
        assertThat(this.exception).isInstanceOf(type);
    }

    private static Category category(String patterns, boolean regex) {
        return new Category(1, "Films", Optional.empty(), "", 0, DownloadPriority.AUTO, patterns, regex);
    }
}
